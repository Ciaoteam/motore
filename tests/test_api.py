import time

from fastapi.testclient import TestClient

from app.main import app

PAYLOAD = {
    "week_start": "2026-12-28",
    "time_limit_seconds": 3,
    "employees": [{"id": "anna", "roles": ["Cameriere"]}],
    "requirement_rules": [{"role": "Cameriere", "start": "18:00", "end": "23:00", "headcount": 1}],
    "rules": [{"id": "r1", "label": "max 3", "code": 'hard(works("anna") <= 3)'}],
}


def test_solve_e_job_asincrono():
    c = TestClient(app)
    assert c.get("/health").json() == {"ok": True}
    r = c.post("/solve", json=PAYLOAD).json()
    assert sum(1 for a in r["assignments"] if a["employee_id"] == "anna") == 3
    job = c.post("/jobs", json=PAYLOAD).json()["job_id"]
    for _ in range(50):
        state = c.get(f"/jobs/{job}").json()
        if state["status"] != "SOLVING":
            break
        time.sleep(0.1)
    assert state["status"] == "DONE" and state["result"]["status"] in ("OPTIMAL", "FEASIBLE")


def test_chiave_api(monkeypatch):
    monkeypatch.setenv("API_KEY", "segreta")
    c = TestClient(app)
    assert c.post("/solve", json=PAYLOAD).status_code == 401
    assert c.post("/solve", json=PAYLOAD, headers={"x-api-key": "segreta"}).status_code == 200
    assert c.post("/solve", json=PAYLOAD, headers={"Authorization": "Bearer segreta"}).status_code == 200
    assert c.get("/health").status_code == 200


def test_input_non_valido_spiegato():
    c = TestClient(app)
    bad = {**PAYLOAD, "requirement_rules": [{"role": "X", "start": "25:00", "end": "23:00", "headcount": 1}]}
    r = c.post("/solve", json=bad)
    assert r.status_code == 422 and "HH:MM" in r.text
