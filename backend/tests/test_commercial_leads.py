"""Homologação do cadastro comercial sem envios reais à API Opa."""
import os
import sqlite3

import httpx
from fastapi.testclient import TestClient

from app import main as module
from app import whatsapp_opa
from app.commercial_leads import list_leads

client = TestClient(module.app)


def payload():
    return {
        "nome": "Interessado Teste",
        "cpf": "52998224725",
        "telefone": "71988887777",
        "id_cliente": "",
        "tecnico": "Tecnico de campo",
        "consentimento": True,
    }


def config(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", "terminal-comercial-test-12345678901234567890")
    monkeypatch.setenv("SESSION_SECRET", "session-comercial-test-12345678901234567890")
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leads.db"))
    monkeypatch.setenv("WHATSAPP_PROVIDER", "opa")
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "TOKEN_FAKE_NAO_REAL")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_TEMPLATE_LEAD_ID", "6924bc62fe20def174e09d5c")
    monkeypatch.setenv("OPA_COMMERCIAL_PHONE", "71977776666")
    monkeypatch.delenv("COMMERCIAL_WEBHOOK_URL", raising=False)
    monkeypatch.delenv("SMTP_HOST", raising=False)
    monkeypatch.delenv("COMMERCIAL_TO", raising=False)
    module.attempts.clear()
    return {"X-Terminal-Key": os.getenv("TERMINAL_KEY")}


def test_commercial_opa_accepts_lead_then_no_duplicate(monkeypatch, tmp_path):
    headers = config(monkeypatch, tmp_path)
    calls = []

    async def fake_send(kind, phone, parameters):
        calls.append([kind, phone, parameters])
        return "opa-test-id-123"

    monkeypatch.setattr(whatsapp_opa, "send_template", fake_send)
    first = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert first.status_code == 200, first.text
    assert first.json()["enviado"] is True
    assert first.json()["situacao"] == "encaminhado"
    assert len(list_leads("encaminhado")) == 1
    assert calls == [[
        "interessados", "5571977776666",
        ["Interessado Teste", "71988887777", "Tecnico de campo",
         first.json()["protocolo"]],
    ]]
    again = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert again.status_code == 200
    assert again.json()["protocolo"] == first.json()["protocolo"]
    assert len(calls) == 1


def test_commercial_opa_failure_preserves_lead_for_review(monkeypatch, tmp_path):
    headers = config(monkeypatch, tmp_path)

    async def uncertain(*args):
        raise httpx.ReadTimeout("timeout simulado, nao enviado")

    monkeypatch.setattr(whatsapp_opa, "send_template", uncertain)
    first = client.post("/api/v1/interessados", headers=headers, json=payload())
    assert first.status_code == 200
    assert first.json()["enviado"] is False
    assert first.json()["situacao"] == "revisar"
    assert len(list_leads("revisar")) == 1
    assert len(list_leads("pendente")) == 0


def test_template_lead_name_and_variable_order(monkeypatch):
    monkeypatch.setenv("OPA_BASE_URL", "https://opa.example.test")
    monkeypatch.setenv("OPA_API_TOKEN", "test")
    monkeypatch.setenv("OPA_CHANNEL_ID", "679cc77501d3afa6ad0a1178")
    monkeypatch.setenv("OPA_TEMPLATE_LEAD_ID", "6924bc62fe20def174e09d5c")

    class FakeClient:
        def __init__(self, **kwargs): pass
        async def __aenter__(self): return self
        async def __aexit__(self, *args): return False
        async def post(self, url, headers, json):
            assert url.endswith("/api/v1/template/send")
            assert json["template"]["_id"] == "6924bc62fe20def174e09d5c"
            assert json["template"]["variaveis"] == [
                "Interessado Teste", "71988887777", "Tecnico", "ABC123DEF456"]
            return httpx.Response(200, request=httpx.Request("POST",url),
                                  json={"status": "success",
                                        "data": {"messageSentId": "OPA-1"}})

    monkeypatch.setattr(httpx, "AsyncClient", FakeClient)
    import asyncio
    assert asyncio.run(whatsapp_opa.send_template(
        "interessados", "5571977776666",
        ["Interessado Teste", "71988887777", "Tecnico", "ABC123DEF456"]
    )) == "OPA-1"


def test_leads_db_private_permissions(monkeypatch, tmp_path):
    monkeypatch.setenv("LEADS_DB", str(tmp_path / "leadbook.db"))
    from app.commercial_leads import save_interest
    data = payload()
    data["protocolo"] = "IGNORED"
    saved = save_interest(data)
    assert saved["estado"] == "pendente"
    assert (tmp_path / "leadbook.db").stat().st_mode & 0o077 == 0
    with sqlite3.connect(tmp_path / "leadbook.db") as db:
        count = db.execute("SELECT COUNT(*) FROM interessados").fetchone()[0]
        assert count == 1
