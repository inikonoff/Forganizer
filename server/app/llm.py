"""Model calls with retry on invalid JSON and fallback across the configured model list."""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from pathlib import Path
from typing import Awaitable, Callable, Optional, TypeVar

import httpx
from pydantic import ValidationError

from .config import PROVIDER_URLS, ModelSpec, Settings
from .schemas import PlanOut, PlanRequest, RefineOut, RefineRequest

log = logging.getLogger("forganizer")

SYSTEM_PROMPT = (Path(__file__).parent / "prompt.txt").read_text(encoding="utf-8")
REFINE_PROMPT = (Path(__file__).parent / "prompt_refine.txt").read_text(encoding="utf-8")

T = TypeVar("T")

# (spec, messages, json_mode) -> raw text of the model answer
Completion = Callable[[ModelSpec, list[dict], bool], Awaitable[str]]


class AllModelsFailed(Exception):
    pass


class ProviderError(Exception):
    def __init__(self, message: str, status: Optional[int] = None, detail: str = ""):
        super().__init__(message)
        self.status = status
        self.detail = detail  # provider error text; shown only by /diag (fixed harmless prompt), never logged


class InvalidAnswer(Exception):
    pass


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


def _extract_json(text: str) -> dict:
    t = _FENCE.sub("", text.strip())
    if not t.startswith("{"):
        start, end = t.find("{"), t.rfind("}")
        if start < 0 or end <= start:
            raise InvalidAnswer("ответ не содержит JSON-объекта")
        t = t[start : end + 1]
    try:
        data = json.loads(t)
    except json.JSONDecodeError as e:
        raise InvalidAnswer(f"невалидный JSON: {e.msg} (позиция {e.pos})") from e
    if not isinstance(data, dict):
        raise InvalidAnswer("корень ответа должен быть объектом")
    return data


def _schema_error(e: ValidationError) -> InvalidAnswer:
    first = e.errors()[0]
    loc = ".".join(str(p) for p in first["loc"])
    return InvalidAnswer(f"неверная структура: {loc}: {first['msg']}")


def parse_answer(text: str, phase: int) -> PlanOut:
    """Parses and checks the model answer structure. Content rules are enforced on the device."""
    data = _extract_json(text)
    try:
        plan = PlanOut.model_validate(data)
    except ValidationError as e:
        raise _schema_error(e) from e
    if phase == 1:
        plan.assignments = []
        plan.leave = []
    return plan


def parse_refine(text: str) -> RefineOut:
    """Strict patch schema: only the six known operations, at most 20 of them."""
    data = _extract_json(text)
    try:
        return RefineOut.model_validate(data)
    except ValidationError as e:
        raise _schema_error(e) from e


class Planner:
    def __init__(self, settings: Settings, completion: Optional[Completion] = None):
        self.settings = settings
        self.completion = completion or self._http_completion
        self._client: Optional[httpx.AsyncClient] = None

    async def close(self) -> None:
        if self._client:
            await self._client.aclose()

    async def plan(self, req: PlanRequest) -> tuple[PlanOut, str]:
        user = json.dumps(req.model_dump(), ensure_ascii=False)
        return await self._run(SYSTEM_PROMPT, user, lambda text: parse_answer(text, req.phase))

    async def refine(self, req: RefineRequest) -> tuple[RefineOut, str]:
        user = json.dumps(req.model_dump(exclude_none=True), ensure_ascii=False)
        return await self._run(REFINE_PROMPT, user, parse_refine)

    async def _run(self, system: str, user: str, parse: Callable[[str], T]) -> tuple[T, str]:
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": user},
        ]
        for spec in self.settings.models:
            if not self.settings.api_keys.get(spec.provider) and self.completion == self._http_completion:
                continue
            attempt_messages = messages
            for attempt in range(2):
                started = time.monotonic()
                try:
                    text = await self.completion(spec, attempt_messages, True)
                    result = parse(text)
                    log.info("model=%s attempt=%d ok ms=%d", spec.model, attempt, (time.monotonic() - started) * 1000)
                    return result, spec.model
                except InvalidAnswer as e:
                    log.info(
                        "model=%s attempt=%d invalid_answer ms=%d reason=%s",
                        spec.model, attempt, (time.monotonic() - started) * 1000, str(e)[:120],
                    )
                    attempt_messages = messages + [
                        {"role": "assistant", "content": "(предыдущий ответ отклонён)"},
                        {
                            "role": "user",
                            "content": f"Ответ отклонён: {e}. Верни только валидный JSON в требуемом формате.",
                        },
                    ]
                except ProviderError as e:
                    log.info("model=%s provider_error status=%s", spec.model, e.status)
                    break
                except httpx.HTTPError as e:
                    log.info("model=%s network_error=%s", spec.model, type(e).__name__)
                    break
        raise AllModelsFailed()

    async def diagnose(self) -> list[dict]:
        """Pings every configured model with a tiny fixed prompt; safe to show to the user."""
        messages = [
            {"role": "system", "content": "Reply with the JSON object {\"ok\": true} and nothing else."},
            {"role": "user", "content": "ping"},
        ]
        results = []
        for spec in self.settings.models:
            item = {"provider": spec.provider, "model": spec.model, "ok": False, "ms": 0, "error": ""}
            if self.completion == self._http_completion and not self.settings.api_keys.get(spec.provider):
                item["error"] = f"нет ключа {spec.provider.upper()}_API_KEY"
                results.append(item)
                continue
            started = time.monotonic()
            try:
                await self.completion(spec, messages, True)
                item["ok"] = True
            except ProviderError as e:
                item["error"] = f"HTTP {e.status}" + (f": {e.detail}" if e.detail else "")
            except httpx.HTTPError as e:
                item["error"] = f"сеть: {type(e).__name__}"
            item["ms"] = int((time.monotonic() - started) * 1000)
            results.append(item)
        return results

    async def _post(self, spec: ModelSpec, body: dict, headers: dict) -> httpx.Response:
        """POST with a short wait-and-retry on 429: free tiers limit tokens per minute, which resets quickly."""
        assert self._client is not None
        r = await self._client.post(PROVIDER_URLS[spec.provider], json=body, headers=headers)
        for _ in range(MAX_RATE_RETRIES):
            if r.status_code != 429:
                break
            try:
                wait = float(r.headers.get("retry-after", "") or 5)
            except ValueError:
                wait = 5.0
            if wait > MAX_RATE_WAIT:
                break
            await asyncio.sleep(max(wait, 1.0))
            r = await self._client.post(PROVIDER_URLS[spec.provider], json=body, headers=headers)
        return r

    async def _http_completion(self, spec: ModelSpec, messages: list[dict], json_mode: bool) -> str:
        if self._client is None:
            self._client = httpx.AsyncClient(timeout=self.settings.model_timeout)
        body: dict = {
            "model": spec.model,
            "messages": messages,
            "temperature": self.settings.temperature,
        }
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        headers = {"Authorization": f"Bearer {self.settings.api_keys.get(spec.provider, '')}"}
        if spec.provider == "openrouter":
            headers["X-Title"] = "Forganizer"
        r = await self._post(spec, body, headers)
        if r.status_code == 400 and json_mode:
            # Some free models reject response_format; retry once without it.
            body.pop("response_format", None)
            r = await self._post(spec, body, headers)
        if r.status_code != 200:
            raise ProviderError(f"status {r.status_code}", r.status_code, _error_text(r))
        try:
            return r.json()["choices"][0]["message"]["content"] or ""
        except (KeyError, IndexError, TypeError, ValueError) as e:
            raise ProviderError("bad provider payload", r.status_code) from e


MAX_RATE_RETRIES = 2
MAX_RATE_WAIT = 30.0


def _error_text(r: httpx.Response) -> str:
    """Short provider error message (e.g. 'No endpoints found for ...'); used only by /diag."""
    try:
        err = r.json().get("error")
        text = err.get("message") if isinstance(err, dict) else err
        return " ".join(str(text or "").split())[:160]
    except Exception:
        return ""
