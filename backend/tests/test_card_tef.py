"""m-SiTef/SitDemo: testes simulados. Nenhuma comunicação com Getnet nem baixa IXC."""
import sqlite3
from fastapi.testclient import TestClient
from app import main
from app.card_tef import amount_in_cents, CardTefError

client = TestClient(main.app)
KEY = "terminal-teste-sitdemo-12345678901234567890"
AUTH = {"X-Terminal-Key": KEY}


def configure(monkeypatch, tmp_path, *, enabled=True):
    monkeypatch.setenv("TERMINAL_KEY", KEY)
    monkeypatch.setenv("SESSION_SECRET", "session-sitdemo-12345678901234567890123456")
    monkeypatch.setenv("CARD_TEF_DB", str(tmp_path / "tef.db"))
    monkeypatch.setenv("CARD_TEF_HOMOLOGATION_ENABLED", "true" if enabled else "false")
    monkeypatch.setenv("CARD_TEF_MODE", "sitdemo")
    monkeypatch.setenv("MSITEF_EMPRESA", "00000000")
    monkeypatch.setenv("MSITEF_COM_EXTERNA", "0")
    monkeypatch.setenv("MSITEF_ENDERECO", "10.87.56.8")
    monkeypatch.setenv("MSITEF_CNPJ_ESTABELECIMENTO", "11716083000136")
    monkeypatch.setenv("MSITEF_CNPJ_AUTOMACAO", "11716083000136")
    main.attempts.clear()


def test_money_from_ixc_not_from_terminal():
    assert amount_in_cents("179.80") == 17980
    assert amount_in_cents("179,80") == 17980
    assert amount_in_cents("1.500,00") == 150000
    for v in ("", "0", "-1", "10.123", "NaN", "R$ 50", None):
        try:
            amount_in_cents(v)
        except CardTefError:
            pass
        else:
            assert False, f"Validação aceitou valor inválido {v!r}"


def test_not_enabled_requires_no_attempt(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path, enabled=False)
    ready = client.post("/api/v1/cartao/disponibilidade", headers=AUTH, json={})
    assert ready.status_code == 200 and ready.json()["habilitado"] is False
    assert "10.87.56.8" not in ready.text
    result = client.post("/api/v1/cartao/faturas/100/preparar", headers=AUTH,
                         json={"session": main.create_session("17"), "modalidade": "debito"})
    assert result.status_code == 503
    assert not (tmp_path / "tef.db").exists()


def test_only_sitdemo_configuration(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    assert client.get("/api/v1/cartao/disponibilidade", headers=AUTH).json()["habilitado"] is True
    for key, bad in (("MSITEF_COM_EXTERNA", "4"), ("MSITEF_EMPRESA", "12345678"),
                     ("CARD_TEF_MODE", "producao"), ("MSITEF_ENDERECO", "127.0.0.1")):
        monkeypatch.setenv(key, bad)
        assert client.get("/api/v1/cartao/disponibilidade", headers=AUTH).json()["habilitado"] is False
        configure(monkeypatch, tmp_path)


def test_prepare_debit_and_audit_no_ixc_write(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    called = []
    class IXCTest:
        async def invoice(self, ident):
            called.append(ident)
            return {"id": ident, "id_cliente": "17", "status": "A",
                    "liberado": "S", "valor_aberto": "179.80"}
    monkeypatch.setattr(main, "IXC", IXCTest)
    body = {"session": main.create_session("17"), "modalidade": "debito"}
    assert client.post("/api/v1/cartao/faturas/295590/preparar", json=body).status_code == 401
    result = client.post("/api/v1/cartao/faturas/295590/preparar", headers=AUTH, json=body)
    assert result.status_code == 200, result.text
    data = result.json()
    assert data["valor_centavos"] == 17980
    assert data["sitef"]["empresaSitef"] == "00000000"
    assert data["sitef"]["comExterna"] == "0"
    assert data["sitef"]["enderecoSitef"] == "10.87.56.8"
    assert data["sitef"]["cnpj_automacao"] == "11716083000136"
    assert data["baixa_ixc_automatica"] is False
    assert "IXC_TOKEN" not in result.text
    assert called == ["295590"]
    repeated = client.post("/api/v1/cartao/faturas/295590/preparar", headers=AUTH, json=body)
    assert repeated.status_code == 409

    path = "/api/v1/cartao/tentativas/" + data["tentativa"] + "/retorno"
    rejected = client.post(path, headers=AUTH,
        json={"segredo_retorno": "x" * 32, "activity_result": -1,
              "codresp": "0", "nsu": "11111"})
    assert rejected.status_code == 404
    accepted = client.post(path, headers=AUTH,
        json={"segredo_retorno": data["segredo_retorno"], "activity_result": -1,
              "codresp": "0", "nsu": "11111", "autorizacao": "", "rede": "SITDEMO"})
    assert accepted.status_code == 200, accepted.text
    assert accepted.json()["situacao"] == "retorno_positivo_a_conciliar"
    assert accepted.json()["baixa_ixc"] == "nao_executada"
    assert client.post(path, headers=AUTH,
        json={"segredo_retorno": data["segredo_retorno"], "activity_result": -1,
              "codresp": "0", "nsu": "11111"}).status_code == 409
    with sqlite3.connect(tmp_path / "tef.db") as con:
        assert con.execute("SELECT state FROM tef_attempts").fetchone()[0] == "retorno_positivo_a_conciliar"


def test_wrong_customer_not_charged(monkeypatch, tmp_path):
    configure(monkeypatch, tmp_path)
    class IXCTest:
        async def invoice(self, ident):
            return {"id": ident, "id_cliente": "99", "status": "A",
                    "liberado": "S", "valor_aberto": "179.80"}
    monkeypatch.setattr(main, "IXC", IXCTest)
    result = client.post("/api/v1/cartao/faturas/123/preparar", headers=AUTH,
           json={"session": main.create_session("17"), "modalidade": "credito"})
    assert result.status_code == 409
    assert not (tmp_path / "tef.db").exists()
