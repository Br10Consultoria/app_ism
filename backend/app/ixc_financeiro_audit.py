"""Diagnóstico SOMENTE LEITURA dos campos financeiros IXC para uma fatura.

Uso no Ubuntu (sem expor IXC_TOKEN):
  docker compose exec -T api python -m app.ixc_financeiro_audit 12345

Este arquivo nunca altera fatura, conta ou carteira e não exibe PII.
"""
import asyncio
import json
import re
import sys

import httpx

from .ixc import IXC, IXCError


def safe_field(row, fields):
    if not isinstance(row, dict):
        return {}
    return {key: str(row[key])[:120] for key in fields if row.get(key) not in ("", None)}


async def inspect(invoice_id):
    ixc = IXC()
    invoice = await ixc.invoice(invoice_id)
    if not invoice:
        print("Fatura não encontrada ou sem permissão de consulta")
        return 2

    fields = (
        "id", "status", "liberado", "id_cliente", "id_filial", "filial_id",
        "id_carteira_cobranca", "carteira_cobranca", "id_conta", "conta_",
        "id_conta_caixa", "id_planejamento", "id_plan_contas",
        "tipo_recebimento", "valor", "valor_aberto",
    )
    print("=== FATURA IXC (campos financeiros selecionados) ===")
    # Não imprime id_cliente para evitar vincular informação pessoal ao diagnóstico.
    print(json.dumps(safe_field(invoice, tuple(k for k in fields if k != "id_cliente")),
                     indent=2, ensure_ascii=False))

    wallet = str(invoice.get("id_carteira_cobranca") or invoice.get("carteira_cobranca") or "")
    if not re.fullmatch(r"[0-9]{1,15}", wallet):
        print("Carteira de cobrança não informada nesse retorno da fatura.")
        print("Consulte no IXC: Financeiro > Contas a receber > Fatura > Carteira.")
        return 0

    print("\n=== CARTEIRA DE COBRANÇA IXC (GET, somente leitura) ===")
    url = ixc.root + "/fn_carteira_cobranca"
    body = {
        "qtype": "fn_carteira_cobranca.id",
        "query": wallet,
        "oper": "=",
        "page": "1",
        "rp": "1",
        "sortname": "fn_carteira_cobranca.id",
        "sortorder": "asc",
    }
    try:
        async with httpx.AsyncClient(timeout=18, follow_redirects=False, trust_env=False) as client:
            response = await client.request(
                "GET", url, json=body,
                headers={"Authorization": ixc.auth, "ixcsoft": "listar",
                         "Content-Type": "application/json", "Accept": "application/json"})
            response.raise_for_status()
            data = response.json()
        records = data.get("registros", []) if isinstance(data, dict) else []
        matched = next((x for x in records if str(x.get("id")) == wallet), None)
        if not matched:
            print("Carteira não retornada pela API; ID:", wallet)
            return 0
        print(json.dumps(safe_field(matched, (
            "id", "descricao", "id_conta", "filial_id", "registrado",
            "ativo", "tipo_recebimento"
        )), indent=2, ensure_ascii=False))
        print("\nASSOCIAÇÕES (não presumem conta de liquidação Getnet):")
        print("filial_id da baixa: conferir filial real do recebimento no IXC")
        print("conta_ da baixa: candidato = carteira.id_conta")
        print("id_conta da baixa: planejamento analítico; NÃO igualar automaticamente a conta_")
        print("Para homologar baixa, conferir conta de liquidação Getnet e planejamento no IXC.")
        return 0
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        # Sem expor URL, token, erro HTTP com cabeçalhos nem dados do assinante.
        print("Sem acesso ou resposta válida à consulta de carteira (apenas leitura).")
        return 3


def main():
    if len(sys.argv) != 2 or not re.fullmatch(r"[0-9]{1,15}", sys.argv[1]):
        print("Uso: python -m app.ixc_financeiro_audit NUMERO_ID_FATURA")
        raise SystemExit(2)
    try:
        code = asyncio.run(inspect(sys.argv[1]))
    except IXCError:
        print("Consulta de fatura indisponível, confira permissões API.")
        code = 3
    raise SystemExit(code)


if __name__ == "__main__":
    main()
