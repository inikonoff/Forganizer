"""Configuration from environment variables (Render dashboard), not from code."""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, field


@dataclass(frozen=True)
class ModelSpec:
    provider: str  # "openrouter" | "groq"
    model: str


PROVIDER_URLS = {
    "openrouter": "https://openrouter.ai/api/v1/chat/completions",
    "groq": "https://api.groq.com/openai/v1/chat/completions",
}

DEFAULT_MODELS = "openrouter:meta-llama/llama-3.3-70b-instruct:free,groq:llama-3.3-70b-versatile"


def parse_models(raw: str) -> list[ModelSpec]:
    """MODELS="provider:model,provider:model" or a JSON list of {"provider","model"}."""
    raw = raw.strip()
    if not raw:
        return []
    if raw.startswith("["):
        return [ModelSpec(m["provider"], m["model"]) for m in json.loads(raw)]
    out = []
    for item in raw.split(","):
        item = item.strip()
        if not item:
            continue
        provider, _, model = item.partition(":")
        if provider not in PROVIDER_URLS or not model:
            raise ValueError(f"bad model spec: {item}")
        out.append(ModelSpec(provider, model))
    return out


@dataclass(frozen=True)
class Settings:
    app_token: str = ""
    models: list[ModelSpec] = field(default_factory=list)
    api_keys: dict[str, str] = field(default_factory=dict)
    max_body_bytes: int = 512 * 1024
    model_timeout: float = 60.0
    temperature: float = 0.1

    @staticmethod
    def from_env() -> "Settings":
        return Settings(
            app_token=os.environ.get("APP_TOKEN", ""),
            models=parse_models(os.environ.get("MODELS", DEFAULT_MODELS)),
            api_keys={
                "openrouter": os.environ.get("OPENROUTER_API_KEY", ""),
                "groq": os.environ.get("GROQ_API_KEY", ""),
            },
            max_body_bytes=int(os.environ.get("MAX_BODY_BYTES", 512 * 1024)),
            model_timeout=float(os.environ.get("MODEL_TIMEOUT", 60)),
            temperature=min(0.2, max(0.0, float(os.environ.get("TEMPERATURE", 0.1)))),
        )
