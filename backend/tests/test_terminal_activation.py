"""Homologação da ativação QR: sem senha no QR, prazo curto e uso único."""
import os
import sqlite3

from fastapi.testclient import TestClient

from app.main import app, attempts, rate_lock
from app import terminal_activation as activation

TEST_KEY = "terminal-chave-exemplo-1234567890-123456789012345"
client = TestClient(app)


def configure(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", TEST_KEY)
    monkeypatch.setenv("TERMINAL_ACTIVATION_DB", str(tmp_path / "activation.db"))
    # O rate limit é global no TestClient; cada teste simula uma janela nova.
    with rate_lock:
        attempts.clear()


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


def test_shared_qr_can_activate_many_devices(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    shared = activation.issue_shared()
    assert shared.startswith(activation.PREFIX)
    assert TEST_KEY not in shared
    code = shared[len(activation.PREFIX):]
    assert len(code) == 43
    for _ in range(5):
        response = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
        assert response.status_code == 200
        assert response.json()["autorizado"] is True
    with sqlite3.connect(activation.db_path()) as conn:
        stored = conn.execute(
            "SELECT code_sha256 FROM terminal_shared_activation WHERE id=1"
        ).fetchone()[0]
    assert stored == activation._digest(code)
    assert code != stored


def test_shared_qr_survives_time_and_remains_active(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    payload = activation.issue_shared()
    code = payload[len(activation.PREFIX):]
    now = int(activation.time.time())
    monkeypatch.setattr(activation.time, "time", lambda: now + 60 * 60 * 24 * 180)
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": code}).status_code == 200
    assert activation.shared_status()["ativo"] is True


def test_rotate_replaces_old_shared_qr(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    original = activation.issue_shared()
    new = activation.issue_shared()
    assert original != new
    first_code = original[len(activation.PREFIX):]
    second_code = new[len(activation.PREFIX):]
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": first_code}).status_code == 401
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": second_code}).status_code == 200
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": second_code}).status_code == 200


def test_shared_qr_can_be_revoked(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    code = activation.issue_shared()[len(activation.PREFIX):]
    assert activation.shared_status()["ativo"] is True
    assert activation.revoke_shared() is True
    assert activation.revoke_shared() is False
    assert activation.shared_status()["ativo"] is False
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": code}).status_code == 401


def test_legacy_one_time_qr_still_single_use_with_shared_enabled(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    shared = activation.issue_shared()[len(activation.PREFIX):]
    one_time = activation.issue()[0][len(activation.PREFIX):]
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": one_time}).status_code == 200
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": one_time}).status_code == 401
    assert client.post("/api/v1/terminal/ativar-qr", json={"codigo": shared}).status_code == 200


def test_shared_qr_rejects_disabled_terminal_key(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    code = activation.issue_shared()[len(activation.PREFIX):]
    monkeypatch.setenv("TERMINAL_KEY", "")
    denied = client.post("/api/v1/terminal/ativar-qr", json={"codigo": code})
    assert denied.status_code == 503
    assert "terminal_key" not in denied.text


def test_unique_sequential_terminal_numbers_after_shared_qr_scan(monkeypatch, tmp_path):
    import uuid
    configure(monkeypatch, tmp_path)
    qr = activation.issue_shared()[len(activation.PREFIX):]
    # O QR compartilhado segue igual, mas cada instalação recebe seu número.
    device_a = str(uuid.uuid4())
    device_b = str(uuid.uuid4())
    a = client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": qr, "installation_uuid": device_a})
    b = client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": qr, "installation_uuid": device_b})
    assert a.status_code == 200, a.text
    assert b.status_code == 200, b.text
    assert a.json()["terminal_id"] == "ISM-0001"
    assert b.json()["terminal_id"] == "ISM-0002"
    assert a.json()["terminal_key"] == b.json()["terminal_key"] == TEST_KEY
    # Reescanear no mesmo dispositivo não altera ID nem consome outro número.
    again = client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": qr, "installation_uuid": device_a})
    assert again.status_code == 200
    assert again.json()["terminal_id"] == "ISM-0001"
    with sqlite3.connect(activation.db_path()) as conn:
        assert conn.execute("SELECT COUNT(*) FROM terminal_registry").fetchone()[0] == 2
    assert activation.shared_status()["ativo"] is True


def test_migration_of_old_activated_devices_without_replacing_qr(monkeypatch, tmp_path):
    import uuid
    configure(monkeypatch, tmp_path)
    activation.issue_shared()
    id_a = str(uuid.uuid4())
    no_key = client.post("/api/v1/terminal/identificar",
                         json={"installation_uuid": id_a})
    assert no_key.status_code == 401
    headers = {"X-Terminal-Key": TEST_KEY}
    first = client.post("/api/v1/terminal/identificar",
                        headers=headers, json={"installation_uuid": id_a})
    second = client.post("/api/v1/terminal/identificar",
                         headers=headers, json={"installation_uuid": id_a})
    assert first.status_code == second.status_code == 200
    assert first.json()["terminal_id"] == second.json()["terminal_id"] == "ISM-0001"
    assert activation.shared_status()["ativo"] is True


def test_invalid_installation_id_cannot_register(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    code = activation.issue_shared()[len(activation.PREFIX):]
    attempt = client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": code, "installation_uuid": "device-test"})
    assert attempt.status_code == 422
    attempt = client.post("/api/v1/terminal/identificar",
                          headers={"X-Terminal-Key": TEST_KEY},
                          json={"installation_uuid": "device-test"})
    assert attempt.status_code == 422
    with sqlite3.connect(activation.db_path()) as conn:
        assert conn.execute("SELECT COUNT(*) FROM terminal_registry").fetchone()[0] == 0


def test_one_time_qr_number_registry_transaction_and_legacy_compat(monkeypatch, tmp_path):
    import uuid
    configure(monkeypatch, tmp_path)
    code = activation.issue()[0][len(activation.PREFIX):]
    device = str(uuid.uuid4())
    one = client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": code, "installation_uuid": device})
    assert one.status_code == 200
    assert one.json()["terminal_id"] == "ISM-0001"
    assert client.post("/api/v1/terminal/ativar-qr", json={
        "codigo": code, "installation_uuid": device}).status_code == 401
    # Contrato de dispositivos antigos não exige installation_uuid.
    shared = activation.issue_shared()[len(activation.PREFIX):]
    legacy = client.post("/api/v1/terminal/ativar-qr", json={"codigo": shared})
    assert legacy.status_code == 200
    assert legacy.json() == {"autorizado": True, "terminal_key": TEST_KEY}
