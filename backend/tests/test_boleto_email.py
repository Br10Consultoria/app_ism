"""Testes locais: sem envio real de boleto, SMTP ou solicitação ao IXC."""
import asyncio
import httpx
import pytest
from fastapi.testclient import TestClient
from app import main
from app import boleto_email
from app.ixc import IXC, IXCError

TESTKEY = "terminal-homologacao-1234567890-1234567890"
client = TestClient(main.app)
headers = {"X-Terminal-Key": TESTKEY}


def config(monkeypatch, tmp_path):
    monkeypatch.setenv("TERMINAL_KEY", TESTKEY)
    monkeypatch.setenv("SESSION_SECRET", "session-homologacao-1234567890-1234567890")
    monkeypatch.setenv("BOLETO_EMAIL_DB", str(tmp_path / "email.db"))
    main.attempts.clear()


class FakeIXC:
    sent = []

    async def invoice(self, id_):
        status = "R" if str(id_) == "445" else "A"
        return {"id": str(id_), "id_cliente": "23", "status": status, "liberado": "S"}

    async def customer_by_id(self, id_):
        return {"id": "23", "email": "cliente@example.test"}

    async def send_boleto_email(self, id_):
        self.sent.append(id_)
        return {"solicitacao_aceita": True, "entrega_confirmada": False}


def test_extract_only_single_registered_email():
    assert boleto_email.registered_email({"email": "cliente@example.test"}) == "cliente@example.test"
    assert boleto_email.registered_email({"email": "a@example.test;b@example.test"}) == ""
    assert boleto_email.registered_email({"email": "evil@example.test\nBcc:someone@example.test"}) == ""
    assert boleto_email.registered_email({"email": "", "email_principal": "other@example.test"}) == "other@example.test"
    assert boleto_email.registered_email({}) == ""


def test_native_ixc_email_payload_and_response(monkeypatch):
    monkeypatch.setenv("IXC_URL", "https://ixc.example.test")
    monkeypatch.setenv("IXC_USER_ID", "fake")
    monkeypatch.setenv("IXC_TOKEN", "fake")

    class Client:
        def __init__(self, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def request(self, method, url, json, headers):
            assert method == "GET"
            assert url == "https://ixc.example.test/webservice/v1/get_boleto"
            assert headers["ixcsoft"] == "listar"
            assert headers["Authorization"].startswith("Basic ")
            assert json == {
                "boletos": "443303",
                "juro": "N",
                "multa": "N",
                "atualiza_boleto": "N",
                "tipo_boleto": "mail",
            }
            return httpx.Response(
                200,
                request=httpx.Request(method, url),
                headers={"content-type": "text/html; charset=ISO-8859-1"},
                content=b'{"registros":[{"sucesso":true}]}',
            )

    monkeypatch.setattr(httpx, "AsyncClient", Client)
    result = asyncio.run(IXC().send_boleto_email("443303"))
    assert result == {"solicitacao_aceita": True, "entrega_confirmada": False}


def test_ixc_unconfirmed_does_not_report_success(monkeypatch):
    monkeypatch.setenv("IXC_URL", "https://ixc.example.test")
    monkeypatch.setenv("IXC_USER_ID", "fake")
    monkeypatch.setenv("IXC_TOKEN", "fake")

    async def failed(self, path, body, **kwargs):
        return {"registros": [{"sucesso": False}]}

    monkeypatch.setattr(IXC, "request", failed)
    with pytest.raises(IXCError):
        asyncio.run(IXC().send_boleto_email("443303"))
    with pytest.raises(IXCError):
        asyncio.run(IXC().send_boleto_email("../443303"))


def test_api_requires_confirmation_and_correct_invoice(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    FakeIXC.sent = []
    monkeypatch.setattr(main, "IXC", FakeIXC)
    payload = {"session": main.create_session("23"), "confirmar_envio": True}
    url = "/api/v1/faturas/444/enviar-email"
    assert client.post(url, json=payload).status_code == 401
    assert client.post(url, headers=headers, json={
        **payload, "confirmar_envio": False
    }).status_code == 422
    assert client.post(url, headers=headers, json={
        "session": main.create_session("99"), "confirmar_envio": True
    }).status_code == 404
    assert client.post("/api/v1/faturas/445/enviar-email",
                       headers=headers, json=payload).status_code == 409
    assert FakeIXC.sent == []
    assert not (tmp_path / "email.db").exists()

    r = client.post(url, headers=headers, json=payload)
    assert r.status_code == 200, r.text
    assert r.json()["solicitacao_aceita"] is True
    assert r.json()["entrega_confirmada"] is False
    assert "cliente@example.test" not in r.text
    assert r.json()["destinatario_mascarado"].endswith(".test")
    assert r.json()["destinatario_verificado"] is False
    assert FakeIXC.sent == [444]

    # Protege a caixa do cliente contra disparos duplicados.
    again = client.post(url, headers=headers, json=payload)
    assert again.status_code == 409
    assert FakeIXC.sent == [444]


def test_api_without_email_field_does_not_block_ixc_native_mail(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    class MissingEmail(FakeIXC):
        async def customer_by_id(self, id_):
            return {"id": "23", "email": ""}

    FakeIXC.sent = []
    monkeypatch.setattr(main, "IXC", MissingEmail)
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers,
                    json={"session": main.create_session("23"), "confirmar_envio": True})
    assert r.status_code == 200, r.text
    assert r.json()["solicitacao_aceita"] is True
    assert r.json()["destinatario_mascarado"] == ""
    assert r.json()["destinatario_verificado"] is False
    assert FakeIXC.sent == [444]


def test_api_missing_customer_listing_still_uses_validated_invoice(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)

    class CustomerListingIncomplete(FakeIXC):
        async def customer_by_id(self, id_):
            return None

    FakeIXC.sent = []
    monkeypatch.setattr(main, "IXC", CustomerListingIncomplete)
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers,
                    json={"session": main.create_session("23"), "confirmar_envio": True})
    assert r.status_code == 200, r.text
    assert r.json()["destinatario_mascarado"] == ""
    assert FakeIXC.sent == [444]
