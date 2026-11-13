"""Homologação da ativação QR: sem senha no QR, prazo curto e uso único."""
import os
import sqlite3

from fastapi.testclient import TestClient

from app.main import app
from app import terminal_activation as activation

TEST_KEY = "terminal-chave-exemplo-1234567890-123456789012345"
client = TestClient(app)


def configure(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", TEST_KEY)
    monkeypatch.setenv("TERMINAL_ACTIVATION_DB", str(tmp_path / "activation.db"))


def test_server_generates_single_use_qr_and_never_places_key_inside(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    payload, expires = activation.issue(5)
    assert payload.startswith("ISM-ACT1:")
    assert TEST_KEY not in payload
    code = payload[len(activation.PREFIX):]
    assert len(code) == 43
    assert abs(expires - int(activation.time.time()) - 300) <= 1
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": code}).status_code == 200
    response = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
    assert response.status_code == 401


def test_api_activation_key_is_transmitted_only_on_success(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    payload, _ = activation.issue(5)
    code = payload.split(":", 1)[1]
    response = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
    assert response.status_code == 200
    assert response.json() == {"autorizado": True, "terminal_key": TEST_KEY}
    assert "no-store" in response.headers.get("cache-control", "")
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": code}).status_code == 401


def test_expired_and_malformed_activation_are_rejected(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    payload, _ = activation.issue(1)
    code = payload.split(":", 1)[1]
    monkeypatch.setattr(activation.time, "time", lambda: 1700000000)
    # Expira a validade diretamente na base para verificar 401 sem espera.
    with sqlite3.connect(activation.db_path()) as conn:
        conn.execute("UPDATE terminal_activation SET expires_at=?", (1699999999,))
    response = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
    assert response.status_code == 401
    assert TEST_KEY not in response.text
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": "invalido"}).status_code == 422


def test_qr_is_hashed_in_sqlite_and_ttl_is_bounded(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    with __import__("pytest").raises(activation.ActivationError):
        activation.issue(16)
    payload, _ = activation.issue(5)
    code = payload.split(":", 1)[1]
    with sqlite3.connect(activation.db_path()) as conn:
        record = conn.execute("SELECT code_sha256, used_at FROM terminal_activation").fetchone()
    assert record[0] == activation._digest(code)
    assert record[0] != code
    assert record[1] is None


def test_server_cannot_activate_if_terminal_secret_missing(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    payload, _ = activation.issue()
    monkeypatch.setenv("TERMINAL_KEY", "")
    code = payload.split(":", 1)[1]
    response = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
    assert response.status_code == 503
    assert "terminal_key" not in response.text
