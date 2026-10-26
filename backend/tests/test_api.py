import pytest
from fastapi.testclient import TestClient
from app import app


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setenv("AURA_DB", str(tmp_path / "test.db"))
    monkeypatch.setenv("AURA_API_TOKEN", "test-token-with-at-least-24-characters")
    return TestClient(app, headers={"Authorization": "Bearer test-token-with-at-least-24-characters"})


def test_auth(client, monkeypatch):
    assert TestClient(app).get("/api/devices").status_code == 401
    assert TestClient(app, headers={"Authorization": "Bearer invalid-token"}).get("/api/devices").status_code == 401
    monkeypatch.delenv("AURA_API_TOKEN")
    assert client.get("/api/status").status_code == 503


def test_vertical_slice_and_persistence(client):
    device = client.post("/api/devices", json={"name": "Kitchen"}).json()
    rule = client.post("/api/rules", json={"device_id": device["id"], "text": "Notify me when sound level is above 0.15"}).json()
    payload = {"device_id": device["id"], "rule_id": rule["id"], "rms": 0.3}
    assert client.post("/api/events", json=payload).json()["simulated"] is False
    assert client.post("/api/simulate", json=payload).json()["simulated"] is True
    assert len(client.get("/api/events").json()) == 2
    assert client.get("/api/status").json()["enabled_rules"] == 1
    assert client.patch('/api/rules/' + rule['id'], json={"enabled": False}).status_code == 200
    assert client.post("/api/events", json=payload).status_code == 422
    assert client.delete('/api/devices/' + device['id']).status_code == 204
    assert client.get("/api/rules").json() == []
    assert client.get("/api/events").json() == []


@pytest.mark.parametrize("text", ["Detect rain", "Call emergency services", "Notify me when RMS above 0", "Notify me when RMS above 1.1", "Notify me when RMS above NaN"])
def test_unsupported_rules(client, text):
    assert client.post("/api/rules/validate", json={"text": text}).status_code == 422


def test_invalid_events_and_foreign_keys(client):
    assert client.post("/api/events", json={"device_id": "missing", "rms": 0.2}).status_code == 404
    assert client.post("/api/events", json={"device_id": "missing", "rms": 2}).status_code == 422
    assert client.get("/api/events?limit=0").status_code == 422
    a = client.post("/api/devices", json={"name": "A"}).json()['id']
    b = client.post("/api/devices", json={"name": "B"}).json()['id']
    rule = client.post("/api/rules", json={"device_id": a, "text": "Notify me when RMS above 0.2"}).json()['id']
    assert client.post("/api/events", json={"device_id": b, "rule_id": rule, "rms": 0.5}).status_code == 404
    assert client.post("/api/events", json={"device_id": a, "rule_id": rule, "rms": 0.1}).status_code == 422
