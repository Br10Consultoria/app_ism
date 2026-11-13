"""Não usa SMTP/IXC real; garante titularidade, destinatário de cadastro e deduplicação."""
import base64
from fastapi.testclient import TestClient
from app import main
from app import boleto_email

TESTKEY = "terminal-homologacao-1234567890-1234567890"
client = TestClient(main.app)
headers = {"X-Terminal-Key": TESTKEY}
PDF = base64.b64encode(b"%PDF-1.4\n% exemplo\n%%EOF").decode()


def config(monkeypatch, tmp_path, enabled=True):
    monkeypatch.setenv("TERMINAL_KEY", TESTKEY)
    monkeypatch.setenv("SESSION_SECRET", "session-homologacao-1234567890-1234567890")
    monkeypatch.setenv("BOLETO_EMAIL_ENABLED", "true" if enabled else "false")
    monkeypatch.setenv("BOLETO_EMAIL_DB", str(tmp_path / "email.db"))
    monkeypatch.setenv("BOLETO_SMTP_HOST", "smtp.example.test")
    monkeypatch.setenv("BOLETO_SMTP_PORT", "465")
    monkeypatch.setenv("BOLETO_SMTP_USER", "sender")
    monkeypatch.setenv("BOLETO_SMTP_PASSWORD", "fake-password")
    monkeypatch.setenv("BOLETO_SMTP_FROM", "boletos@example.test")
    main.attempts.clear()


class FakeIXC:
    async def invoice(self, id_):
        return {"id": str(id_), "id_cliente": "23", "status": "A", "liberado": "S"}
    async def customer_by_id(self, id_):
        return {"id": "23", "email": "cliente@example.test"}
    async def payment(self, invoice, kind):
        assert kind == "boleto"
        return {"pdf_base64": PDF}


def test_extract_only_single_registered_email():
    assert boleto_email.registered_email({"email": "cliente@example.test"}) == "cliente@example.test"
    assert boleto_email.registered_email({"email": "a@example.test;b@example.test"}) == ""
    assert boleto_email.registered_email({"email": "evil@example.test\nBcc:someone@example.test"}) == ""
    assert boleto_email.registered_email({"email": "", "email_principal": "other@example.test"}) == "other@example.test"
    assert boleto_email.registered_email({}) == ""


def test_not_enabled_cannot_send(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path, enabled=False)
    monkeypatch.setattr(main, "IXC", FakeIXC)
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers,
                    json={"session": main.create_session("23")})
    assert r.status_code == 503
    assert not (tmp_path / "email.db").exists()


def test_email_only_to_registered_ixc_and_single_send(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    monkeypatch.setattr(main, "IXC", FakeIXC)
    messages = []
    class FakeSMTP:
        def __init__(self, host, port, timeout=0, context=None):
            assert host == "smtp.example.test" and port == 465
        def __enter__(self): return self
        def __exit__(self, *_): pass
        def login(self, user, pwd): assert user == "sender"
        def send_message(self, msg):
            messages.append(msg)
            return {}
    monkeypatch.setattr(boleto_email.smtplib, "SMTP_SSL", FakeSMTP)
    data = {"session": main.create_session("23")}
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers, json=data)
    assert r.status_code == 200, r.text
    assert r.json()["situacao"] == "aceito_pelo_smtp"
    assert r.json()["destinatario_mascarado"] != "cliente@example.test"
    assert "cliente@example.test" not in r.text
    assert len(messages) == 1
    assert messages[0]["To"] == "cliente@example.test"
    attachments = list(messages[0].iter_attachments())
    assert len(attachments) == 1
    assert attachments[0].get_content_type() == "application/pdf"
    assert attachments[0].get_payload(decode=True).startswith(b"%PDF-")
    again = client.post("/api/v1/faturas/444/enviar-email", headers=headers, json=data)
    assert again.status_code == 409
    assert len(messages) == 1


def test_cannot_send_another_customer_invoice(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    monkeypatch.setattr(main, "IXC", FakeIXC)
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers,
                    json={"session": main.create_session("99")})
    assert r.status_code == 404
    assert not (tmp_path / "email.db").exists()


def test_missing_registered_email(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    class NoEmailIXC(FakeIXC):
        async def customer_by_id(self, id_):
            return {"id": "23", "email": ""}
    monkeypatch.setattr(main, "IXC", NoEmailIXC)
    r = client.post("/api/v1/faturas/444/enviar-email", headers=headers,
                    json={"session": main.create_session("23")})
    assert r.status_code == 422
    assert not (tmp_path / "email.db").exists()


def test_invalid_pdf_not_sent(monkeypatch, tmp_path):
    config(monkeypatch, tmp_path)
    try:
        boleto_email.send_pdf("11", "cliente@example.test", base64.b64encode(b"NOT PDF").decode())
    except boleto_email.BoletoEmailError:
        pass
    else:
        assert False, "invalid PDF accepted"
    assert not (tmp_path / "email.db").exists()
