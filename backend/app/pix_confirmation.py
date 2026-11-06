"""Confirmação conservadora de PIX: somente leitura do título financeiro no IXC.

Não interpretar apenas status R como PIX. Nunca criar comprovante quando a
origem ou a confirmação bancária estiverem incertas.
"""
from decimal import Decimal, InvalidOperation


def _amount(raw):
    if raw is None or str(raw).strip() == "":
        return None
    value = str(raw).strip().replace("R$", "").replace(" ", "")
    if "," in value:
        value = value.replace(".", "").replace(",", ".")
    try:
        result = Decimal(value)
        return result if result.is_finite() else None
    except (InvalidOperation, ValueError):
        return None


def verify_pix_receipt(invoice):
    """Retorna informações minimizadas, sem CPF, nome ou credenciais."""
    if not isinstance(invoice, dict):
        return {"situacao": "indeterminado", "confirmado": False}

    status = str(invoice.get("status") or "").upper().strip()
    via_pix = str(invoice.get("recebido_via_pix") or "").upper().strip()
    waiting = str(invoice.get("aguardando_confirmacao_pagamento") or "").upper().strip()
    reversed_ = str(invoice.get("estornado") or "").upper().strip()
    open_amount = _amount(invoice.get("valor_aberto"))
    paid_amount = _amount(invoice.get("valor_recebido"))

    # Um status de baixa por outro meio não prova o recebimento via PIX.
    if reversed_ == "S":
        state = "estornado"
    elif waiting == "S":
        state = "aguardando"
    elif status == "C":
        state = "cancelado"
    elif status == "A":
        state = "aguardando"
    elif status != "R":
        state = "indeterminado"
    elif via_pix != "S":
        state = "outro_meio" if via_pix == "N" else "indeterminado"
    elif open_amount is not None and open_amount > 0:
        state = "parcial"
    elif paid_amount is None or paid_amount <= 0:
        state = "indeterminado"
    else:
        state = "confirmado"

    result = {"situacao": state, "confirmado": state == "confirmado"}
    if state == "confirmado":
        payment_date = (
            invoice.get("pagamento_data")
            or invoice.get("data_pagamento")
            or invoice.get("data_recebido")
            or invoice.get("data_recebimento")
            or ""
        )
        txid = str(invoice.get("pix_txid") or "").strip()
        result.update({
            "id": str(invoice.get("id") or ""),
            "documento": str(invoice.get("documento") or "")[:70],
            "data_pagamento": str(payment_date)[:32],
            "valor_recebido": format(paid_amount, ".2f"),
            "pix_txid": txid[:90],
            "origem": "IXC",
        })
    return result
