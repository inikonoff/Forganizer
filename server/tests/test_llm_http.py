import asyncio

import httpx
import pytest

from app.config import ModelSpec, Settings
from app.llm import Planner, ProviderError


def planner(handler):
    p = Planner(Settings(models=[ModelSpec("groq", "m")], api_keys={"groq": "k"}))
    p._client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return p


OK_BODY = {"choices": [{"message": {"content": "{}"}}]}


def run(coro):
    return asyncio.run(coro)


def test_429_is_retried_after_retry_after(monkeypatch):
    calls = []
    slept = []

    async def fake_sleep(s):
        slept.append(s)

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)

    def handler(request):
        calls.append(1)
        if len(calls) == 1:
            return httpx.Response(429, headers={"retry-after": "3"}, json={"error": {"message": "tpm"}})
        return httpx.Response(200, json=OK_BODY)

    p = planner(handler)
    assert run(p._http_completion(ModelSpec("groq", "m"), [], True)) == "{}"
    assert len(calls) == 2 and slept == [3.0]


def test_long_retry_after_is_not_waited(monkeypatch):
    async def fake_sleep(s):
        raise AssertionError("must not sleep")

    monkeypatch.setattr(asyncio, "sleep", fake_sleep)
    p = planner(lambda req: httpx.Response(429, headers={"retry-after": "600"}, json={"error": {"message": "daily limit"}}))
    with pytest.raises(ProviderError) as e:
        run(p._http_completion(ModelSpec("groq", "m"), [], True))
    assert e.value.status == 429 and e.value.detail == "daily limit"


def test_error_text_and_status_for_missing_model():
    p = planner(lambda req: httpx.Response(404, json={"error": {"message": "No endpoints found for x"}}))
    with pytest.raises(ProviderError) as e:
        run(p._http_completion(ModelSpec("groq", "m"), [], True))
    assert e.value.status == 404 and "No endpoints" in e.value.detail


def test_diag_reports_missing_key():
    p = Planner(Settings(models=[ModelSpec("groq", "m"), ModelSpec("openrouter", "o")], api_keys={"groq": "k"}))
    p._client = httpx.AsyncClient(transport=httpx.MockTransport(lambda req: httpx.Response(200, json=OK_BODY)))
    res = run(p.diagnose())
    assert res[0]["ok"] is True
    assert res[1]["ok"] is False and "OPENROUTER_API_KEY" in res[1]["error"]
