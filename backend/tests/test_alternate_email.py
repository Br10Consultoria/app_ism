"""Teste do novo destinatário com posse do endereço via código; nunca usa SMTP real."""
import base64
import re

from fastapi.testclient import TestClient

from app import alternate_boleto_email as alt
from app import main
from app.boleto_email import BoletoEmailError

client = TestClient(main.app)
KEY = "terminal-chave-teste-012345678901234567890123456"
HEADERS = {"X-Terminal-Key": KEY}


class FakeIXC:
    async def invoice(self, invoice_id):
        if str(invoice_id) == "999":
            return {"id": "999", "id_cliente": "999", "status": "A", "liberado": "S"}
        return {"id": str(invoice_id), "id_cliente": "42",
                "status": "A", "liberado": "S"}

    async def payment(self, invoice, kind):
        assert kind == "boleto"
        return {"pdf_base64": base64.b64encode(
            b"%PDF-1.4\n%documento de teste\n%%EOF").decode(), "url": ""}


def setup(monkeypatch, tmp_path):
    monkeypatch.setenv("SESSION_SECRET", "session-key-homologacao-0123456789-0123456789")
    monkeypatch.setenv("TERMINAL_KEY", KEY)
    monkeypatch.setenv("BOLETO_EMAIL_DB", str(tmp_path / "alt-email.db"))
    monkeypatch.setattr(main, "IXC", FakeIXC)
    monkeypatch.setattr(alt, "_config",
                        lambda: ("smtp.example.test", 465, "user", "password",
                                 "cobranca@example.test"))
    with main.rate_lock:
        main.attempts.clear()


def request(invoice_id, email="novo@example.test", consent=True):
    return {"session": main.create_session("42"),
            "email": email, "consentimento": consent}


def test_alternate_email_requires_authorized_terminal_and_invoice(monkeypatch, tmp_path):
    setup(monkeypatch, tmp_path)
    sent = []
    monkeypatch.setattr(alt, "_smtp_send", lambda msg: sent.append(msg))
    path = "/api/v1/faturas/444/email-alternativo/solicitar-codigo"
    assert client.post(path, json=request(444)).status_code == 401
    assert client.post(path, headers=HEADERS,
                       json=request(444, consent=False)).status_code == 422
    assert client.post(path, headers=HEADERS,
                       json=request(444, email="x\r\nBcc:evil@example.test")).status_code == 422
    unauthorized = client.post("/api/v1/faturas/999/email-alternativo/solicitar-codigo",
                               headers=HEADERS, json=request(999))
    assert unauthorized.status_code == 404
    assert not sent


def test_code_sent_only_to_new_email_and_pdf_after_verified_code(monkeypatch, tmp_path):
    setup(monkeypatch, tmp_path)
    sent = []
    monkeypatch.setattr(alt, "_smtp_send", lambda msg: sent.append(msg))
    pdf_out = []
    monkeypatch.setattr(main, "send_verified_pdf",
                        lambda i, e, p: pdf_out.append((i, e, p)) or {"enviado": True})
    url = "/api/v1/faturas/444/email-alternativo/solicitar-codigo"
    first = client.post(url, headers=HEADERS, json=request(444))
    assert first.status_code == 200, first.text
    assert first.json()["codigo_solicitado"] is True
    assert "terminal_key" not in first.text
    assert len(sent) == 1
    assert sent[0]["To"] == "novo@example.test"
    code = re.search(r"([0-9]{6})", sent[0].get_content()).group(1)
    assert code not in first.text
    payload = dict(request(444), codigo=code)
    confirm_path = "/api/v1/faturas/444/email-alternativo/confirmar-envio"
    wrong = client.post(confirm_path, headers=HEADERS,
                        json={**payload, "codigo": "000000" if code != "000000" else "111111"})
    assert wrong.status_code == 422
    assert pdf_out == []
    delivered = client.post(confirm_path, headers=HEADERS, json=payload)
    assert delivered.status_code == 200, delivered.text
    assert delivered.json()["solicitacao_aceita"] is True
    assert delivered.json()["entrega_confirmada"] is False
    assert pdf_out[0][0:2] == (444, "novo@example.test")
    assert base64.b64decode(pdf_out[0][2]).startswith(b"%PDF-")
    replay = client.post(confirm_path, headers=HEADERS, json=payload)
    assert replay.status_code == 422
    assert len(pdf_out) == 1


def test_otp_rate_limit_and_attempts_persist_on_wrong_codes(monkeypatch, tmp_path):
    setup(monkeypatch, tmp_path)
    sent = []
    monkeypatch.setattr(alt, "_smtp_send", lambda msg: sent.append(msg))
    response = client.post(
        "/api/v1/faturas/444/email-alternativo/solicitar-codigo",
        headers=HEADERS, json=request(444))
    assert response.status_code == 200
    repeat = client.post(
        "/api/v1/faturas/444/email-alternativo/solicitar-codigo",
        headers=HEADERS, json=request(444))
    assert repeat.status_code == 429
    code = re.search(r"([0-9]{6})", sent[0].get_content()).group(1)
    wrong = "000000" if code != "000000" else "111111"
    path = "/api/v1/faturas/444/email-alternativo/confirmar-envio"
    for _ in range(5):
        assert client.post(path, headers=HEADERS,
                           json={**request(444), "codigo": wrong}).status_code == 422
    assert client.post(path, headers=HEADERS,
                       json={**request(444), "codigo": code}).status_code == 422


def test_alternate_email_is_not_available_without_smtp(monkeypatch, tmp_path):
    setup(monkeypatch, tmp_path)
    monkeypatch.setattr(alt, "_config",
                        lambda: (_ for _ in ()).throw(
                            BoletoEmailError("SMTP ainda não configurado", 503)))
    response = client.post(
        "/api/v1/faturas/444/email-alternativo/solicitar-codigo",
        headers=HEADERS, json=request(444))
    assert response.status_code == 503
    assert "SMTP" in response.text
