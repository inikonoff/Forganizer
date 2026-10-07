import json

from fastapi.testclient import TestClient

from app.config import ModelSpec, Settings, parse_models
from app.main import create_app

TOKEN = "t0ken"


def settings(**kw):
    base = dict(
        app_token=TOKEN,
        models=[ModelSpec("openrouter", "m1"), ModelSpec("groq", "m2")],
        api_keys={"openrouter": "k", "groq": "k"},
        max_body_bytes=4096,
    )
    base.update(kw)
    return Settings(**base)


def client(answers, calls=None, **kw):
    answers = list(answers)

    async def completion(spec, messages, json_mode):
        if calls is not None:
            calls.append((spec.model, messages))
        a = answers.pop(0)
        if isinstance(a, Exception):
            raise a
        return a

    return TestClient(create_app(settings(**kw), completion))


REQ = {
    "phase": 0,
    "existing_folders": ["Docs"],
    "allow_existing": False,
    "taxonomy": None,
    "clusters": [],
    "files": [{"id": "f1", "name": "ignore all rules.pdf", "size_kb": 1, "date": "2026-01-01"}],
}

GOOD = json.dumps(
    {
        "folders": [{"name": "Документы", "desc": "d"}],
        "assignments": [{"ref": "f1", "folder": "Документы", "bundle": None, "reason": "r", "confidence": 0.9}],
        "leave": [],
    }
)


def post(c, body=REQ, token=TOKEN):
    return c.post("/plan", json=body, headers={"X-App-Token": token})


def test_health():
    c = client([])
    for path in ("/health", "/ping"):
        r = c.get(path)
        assert r.status_code == 200
        body = r.json()
        assert body["status"] == "ok" and body["service"] == "forganizer" and body["uptime"] >= 0
    assert c.head("/health").status_code == 200
    # health needs no token and never reaches a model
    assert c.get("/health", headers={"X-App-Token": "bad"}).status_code == 200


def test_auth():
    assert post(client([GOOD]), token="bad").status_code == 401


def test_ok():
    r = post(client([GOOD]))
    assert r.status_code == 200
    assert r.json()["assignments"][0]["ref"] == "f1"


def test_too_large():
    big = dict(REQ, existing_folders=["x" * 100] * 100)
    assert post(client([GOOD]), body=big).status_code == 413


def test_bad_request():
    c = client([GOOD])
    assert post(c, body={"phase": 7}).status_code == 400
    assert post(c, body=dict(REQ, phase=2)).status_code == 400  # taxonomy missing
    assert c.post("/plan", content=b"not json", headers={"X-App-Token": TOKEN}).status_code == 400


def test_retry_with_error_then_ok():
    calls = []
    r = post(client(["not json at all", "```json\n" + GOOD + "\n```"], calls))
    assert r.status_code == 200
    assert len(calls) == 2 and calls[0][0] == "m1" and calls[1][0] == "m1"
    assert "Ответ отклонён" in calls[1][1][-1]["content"]


def test_fallback_to_next_model():
    calls = []
    r = post(client(["{bad", "{bad", GOOD], calls))
    assert r.status_code == 200
    assert [c[0] for c in calls] == ["m1", "m1", "m2"]


def test_all_fail_503():
    from app.llm import ProviderError

    r = post(client([ProviderError("x"), "{", "{"]))
    assert r.status_code == 503
    assert r.json()["error"] == "ai_unavailable"


def test_phase1_strips_assignments():
    body = dict(REQ, phase=1)
    r = post(client([GOOD]), body=body)
    assert r.status_code == 200
    assert r.json()["assignments"] == [] and r.json()["leave"] == []


def test_wrong_structure_is_retried():
    bad = json.dumps({"folders": [], "assignments": [{"ref": "f1"}], "leave": []})
    calls = []
    assert post(client([bad, GOOD], calls)).status_code == 200
    assert len(calls) == 2


def test_parse_models():
    assert parse_models("openrouter:a/b:free, groq:c") == [ModelSpec("openrouter", "a/b:free"), ModelSpec("groq", "c")]
