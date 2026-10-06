"""Forganizer plan server. Request bodies and model answers are never logged, only metrics."""
from __future__ import annotations

import hmac
import json
import logging
import time
from contextlib import asynccontextmanager
from typing import Optional

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from .config import Settings
from .llm import AllModelsFailed, Completion, Planner
from .schemas import PlanRequest

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("forganizer")


def error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": code, "message": message})


def create_app(settings: Optional[Settings] = None, completion: Optional[Completion] = None) -> FastAPI:
    settings = settings or Settings.from_env()
    planner = Planner(settings, completion)

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        yield
        await planner.close()

    app = FastAPI(title="Forganizer", docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.get("/health")
    @app.head("/health")
    async def health():
        return {"status": "ok"}

    @app.post("/plan")
    async def plan(request: Request):
        started = time.monotonic()
        status = 500
        size = 0
        model = "-"
        try:
            token = request.headers.get("x-app-token", "")
            if not settings.app_token or not hmac.compare_digest(token, settings.app_token):
                status = 401
                return error(401, "unauthorized", "Неверный токен приложения")

            declared = request.headers.get("content-length")
            if declared and declared.isdigit() and int(declared) > settings.max_body_bytes:
                status = 413
                return error(413, "too_large", "Слишком большой запрос")
            body = bytearray()
            async for chunk in request.stream():
                body += chunk
                if len(body) > settings.max_body_bytes:
                    status = 413
                    return error(413, "too_large", "Слишком большой запрос")
            size = len(body)

            try:
                req = PlanRequest.model_validate(json.loads(body))
            except (ValueError, ValidationError):
                status = 400
                return error(400, "bad_request", "Неверный формат запроса")

            try:
                result, model = await planner.plan(req)
            except AllModelsFailed:
                status = 503
                return error(503, "ai_unavailable", "ИИ временно недоступен")
            status = 200
            return result.model_dump()
        finally:
            log.info(
                "plan status=%d bytes=%d ms=%d model=%s",
                status, size, (time.monotonic() - started) * 1000, model,
            )

    return app


app = create_app()
