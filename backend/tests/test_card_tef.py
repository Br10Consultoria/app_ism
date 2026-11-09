"""Testes TEF SEM pagamento real e SEM escrita na API do IXC."""
import os
import sqlite3

from fastapi.testclient import TestClient

from app import main
from app.card_tef import amount_in_cents, CardTefError

client = TestClient(main.app)
KEY = "terminal-homologacao-1234567890-1234567890"
headers = {"X-Terminal-Key": KEY}


def configure(monkeypatch, tmp_path, *, enabled=False):
    monkeypatch.setenv("TERMINAL_KEY", KEY)
    monkeypatch.setenv("SESSION_SECRET", "session-homologacao-1234567890-1234567890")
    monkeypatch.setenv("CARD_TEF_DB", str(tmp_path / "tef.db"))
    monkeypatch.setenv("CARD_TEF_HOMOLOGATION_ENABLED", "true" if enabled else "false")
    monkeypatch.setenv("MSITEF_EMPRESA", "00000001")
    monkeypatch.setenv("MSITEF_ENDERECO", "sitef-hml.exemplo.test:443")
    monkeypatch.setenv("MSITEF_CNPJ_ESTABELECIMENTO", "12345678000190")
    main.attempts.clear()


def test_ixc_money_parsing_and_invalid_amount():
    assert amount_in_cents("150.00") == 15000
    assert amount_in_cents("150,00") == 15000
    assert amount_in_cents("1.500,00") == 150000
    assert amount_in_cents("0.01") == 1
    for v in ("", None, "0", "10.123", "1.0.0", "-10", "NaN", "R$ 100", "99999.00"):
        try:
            amount_in_cents(v)
        except CardTefError:
            pass
        else:
            assert False, f"Accepted invalid amount {v!r}"


def test_disabled_no_attempt(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    class FakeIXC:
        async def invoice(self, ident):
            return {"id": ident, "id_cliente": "17", "status": "A",
                    "liberado": "S", "valor_aberto": "150.00"}
    monkeypatch.setattr(main, "IXC", FakeIXC)
    r = client.post("/api/v1/cartao/faturas/123/preparar", headers=headers,
                    json={"session": main.create_session("17"), "modalidade": "debito"})
    assert r.status_code == 503
    assert not (tmp_path / "tef.db").exists()


def test_prepare_and_return_never_settle_ixc(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, enabled=True)
    observed = []
    class FakeIXC:
        async def invoice(self, ident):
            observed.append(ident)
            return {"id": ident, "id_cliente": "17", "status": "A",
                    "liberado": "S", "valor_aberto": "150.00"}

    monkeypatch.setattr(main, "IXC", FakeIXC)
    body = {"session": main.create_session("17"), "modalidade": "debito"}
    response = client.post("/api/v1/cartao/faturas/123/preparar",
                           headers=headers, json=body)
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["valor_centavos"] == 15000
    assert data["baixa_ixc_automatica"] is False
    assert data["sitef"]["empresaSitef"] == "00000001"
    assert data["sitef"]["CNPJ_CPF"] == "12345678000190"
    assert "IXC_TOKEN" not in response.text
    assert "segredo_retorno" in data
    assert observed == ["123"]

    repeat = client.post("/api/v1/cartao/faturas/123/preparar", headers=headers, json=body)
    assert repeat.status_code == 409

    invalid = client.post(
        f"/api/v1/cartao/tentativas/{data['tentativa']}/retorno",
        headers=headers, json={"segredo_retorno": "x" * 32, "activity_result": -1,
                            "codresp": "0", "nsu": "000123", "autorizacao": "A1"}
    )
    assert invalid.status_code == 404

    r = client.post(
        f"/api/v1/cartao/tentativas/{data['tentativa']}/retorno",
        headers=headers, json={"segredo_retorno": data["segredo_retorno"],
                               "activity_result": -1, "codresp": "0",
                               "nsu": "000123", "autorizacao": "A1"}
    )
    assert r.status_code == 200
    assert r.json()["situacao"] == "retorno_positivo_a_conciliar"
    assert r.json()["baixa_ixc"] == "nao_executada"
    assert "fn_areceber_recebimentos_baixas_novo" not in response.text
    with sqlite3.connect(tmp_path / "tef.db") as con:
        row = con.execute("SELECT state, nsu FROM tef_attempts WHERE id=?",
                          (data["tentativa"],)).fetchone()
        assert row == ("retorno_positivo_a_conciliar", "000123")


def test_wrong_customer_or_received_invoice(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, enabled=True)
    class FakeIXC:
        async def invoice(self, ident):
            return {"id": ident, "id_cliente": "17", "status": "R",
                    "liberado": "S", "valor_aberto": "150.00"}
    monkeypatch.setattr(main, "IXC", FakeIXC)
    body = {"session": main.create_session("17"), "modalidade": "credito"}
    assert client.post("/api/v1/cartao/faturas/123/preparar",
                       headers=headers, json=body).status_code == 409
    assert client.post("/api/v1/cartao/faturas/123/preparar",
                       headers=headers, json={"session": main.create_session("99"),
                                              "modalidade": "credito"}).status_code == 409
    assert not (tmp_path / "tef.db").exists()


def test_card_api_rejects_unauthed_or_invalid_modality(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, enabled=True)
    body = {"session": main.create_session("17"), "modalidade": "parcelado"}
    assert client.post("/api/v1/cartao/faturas/123/preparar",
                       json=body).status_code == 401
    assert client.post("/api/v1/cartao/faturas/123/preparar",
                       headers=headers, json=body).status_code == 422


def test_audit_script_requires_invoice_id(monkeypatch):
    from app.ixc_financeiro_audit import safe_field
    data = {"id": "123", "id_filial": "1", "cnpj_cpf": "secret",
            "id_carteira_cobranca": "8", "token": "secret"}
    assert safe_field(data, ("id", "id_filial", "id_carteira_cobranca")) == {
        "id": "123", "id_filial": "1", "id_carteira_cobranca": "8"
    }
