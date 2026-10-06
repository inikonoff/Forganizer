import json

from tests.test_api import TOKEN, client

REQ = {
    "instruction": "все PDF в Документы, скриншоты отдельно",
    "existing_folders": ["Docs"],
    "allow_existing": False,
    "folders": [{"name": "Документы", "count": 3, "exts": {"pdf": 3}, "bundles": []}],
    "leave": {"count": 1, "exts": {"zip": 1}},
    "pinned": [{"kind": "folder_name", "name": "Фото"}],
    "history": [],
}

GOOD = json.dumps(
    {
        "ops": [
            {"op": "move", "select": {"ext": ["pdf"]}, "to": "Документы"},
            {"op": "create_folder", "name": "Скриншоты", "desc": "Снимки экрана"},
            {"op": "move", "select": {"name_starts_with": "Screenshot_"}, "to": "Скриншоты"},
            {"op": "rename_folder", "from": "A", "to": "B"},
            {"op": "merge_folders", "from": ["A", "C"], "into": "B"},
            {"op": "unbundle", "bundle": "X"},
            {"op": "to_leave", "select": {"refs": ["f1"]}},
        ],
        "note": "",
    }
)


def post(c, body=REQ):
    return c.post("/refine", json=body, headers={"X-App-Token": TOKEN})


def test_refine_ok_roundtrip():
    r = post(client([GOOD]))
    assert r.status_code == 200
    data = r.json()
    assert len(data["ops"]) == 7
    assert data["ops"][3] == {"op": "rename_folder", "from": "A", "to": "B"}
    assert data["ops"][4]["from"] == ["A", "C"]


def test_unknown_op_and_delete_rejected_then_fallback():
    bad = json.dumps({"ops": [{"op": "delete", "select": {"ext": ["pdf"]}}], "note": ""})
    calls = []
    r = post(client([bad, bad, GOOD], calls))
    assert r.status_code == 200
    assert [c[0] for c in calls] == ["m1", "m1", "m2"]


def test_empty_selector_and_too_many_ops_rejected():
    empty_sel = json.dumps({"ops": [{"op": "move", "select": {}, "to": "A"}], "note": ""})
    many = json.dumps({"ops": [{"op": "unbundle", "bundle": "x"}] * 21, "note": ""})
    too_many_refs = json.dumps({"ops": [{"op": "to_leave", "select": {"refs": [f"f{i}" for i in range(21)]}}]})
    r = post(client([empty_sel, many, too_many_refs, "{}"]))
    assert r.status_code == 200
    assert r.json() == {"ops": [], "note": ""}


def test_question_in_note():
    q = json.dumps({"ops": [], "note": "Какие \"файлы\" имеются в виду?\nУточните"})
    r = post(client([q]))
    assert r.status_code == 200
    assert r.json()["note"] == "Какие файлы имеются в виду? Уточните"


def test_refine_bad_request_and_auth():
    c = client([GOOD])
    assert post(c, dict(REQ, instruction="   ")).status_code == 400
    assert post(c, dict(REQ, pinned=[{"kind": "x"}] * 51)).status_code == 400
    assert c.post("/refine", json=REQ, headers={"X-App-Token": "no"}).status_code == 401


def test_refine_all_fail():
    assert post(client(["x", "x", "x", "x"])).status_code == 503
