"""Cadastro durável dos interessados do terminal ISM, independente do canal comercial.

A mensagem ao comercial pode falhar, mas o interessado não desaparece.
O volume /data é persistente; acesso a estes dados deve ser restrito ao operador.
"""
import json
import os
import sqlite3
import time
import uuid
from datetime import datetime, timezone


def _database():
    path = os.getenv("LEADS_DB", "/data/leads.db")
    if not os.path.isabs(path) or not path.endswith(".db"):
        raise OSError("Caminho LEADS_DB deve ser absoluto e terminar em .db")
    previous = os.umask(0o077)
    try:
        conn = sqlite3.connect(path, timeout=10)
    finally:
        os.umask(previous)
    try:
        os.chmod(path, 0o600)
        conn.execute("PRAGMA busy_timeout=10000")
        conn.execute("""
            CREATE TABLE IF NOT EXISTS interessados (
                protocolo TEXT PRIMARY KEY,
                criado_em INTEGER NOT NULL,
                cpf TEXT NOT NULL,
                telefone TEXT NOT NULL,
                nome TEXT NOT NULL,
                id_cliente TEXT NOT NULL,
                tecnico TEXT NOT NULL,
                estado TEXT NOT NULL,
                canal TEXT NOT NULL DEFAULT '',
                referencia TEXT NOT NULL DEFAULT '',
                detalhe TEXT NOT NULL DEFAULT ''
            )
        """)
        conn.execute(
            "CREATE INDEX IF NOT EXISTS idx_interessados_estado "
            "ON interessados (estado, criado_em)"
        )
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def save_interest(data):
    """Previne duplicações ao reenviar o mesmo lead na mesma hora."""
    timestamp = int(time.time())
    conn = _database()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            prev = conn.execute(
                "SELECT protocolo, estado FROM interessados "
                "WHERE cpf = ? AND telefone = ? AND criado_em >= ? "
                "ORDER BY criado_em DESC LIMIT 1",
                (data["cpf"], data["telefone"], timestamp - 3600)
            ).fetchone()
            if prev:
                return {"protocolo": prev[0], "estado": prev[1], "novo": False}
            protocol = uuid.uuid4().hex[:12].upper()
            conn.execute(
                "INSERT INTO interessados "
                "(protocolo, criado_em, cpf, telefone, nome, id_cliente, tecnico, estado) "
                "VALUES (?, ?, ?, ?, ?, ?, ?, 'pendente')",
                (protocol, timestamp, data["cpf"], data["telefone"],
                 data["nome"], data["id_cliente"], data["tecnico"])
            )
            return {"protocolo": protocol, "estado": "pendente", "novo": True}
    finally:
        conn.close()


def claim_for_send(protocol):
    conn = _database()
    try:
        with conn:
            cursor = conn.execute(
                "UPDATE interessados SET estado='em_envio' "
                "WHERE protocolo=? AND estado='pendente'", (protocol,)
            )
            return cursor.rowcount == 1
    finally:
        conn.close()


def set_status(protocol, status, channel="", reference="", detail=""):
    if status not in ("pendente", "encaminhado", "revisar"):
        raise ValueError("Estado comercial inválido")
    conn = _database()
    try:
        with conn:
            conn.execute(
                "UPDATE interessados SET estado=?, canal=?, referencia=?, detalhe=? "
                "WHERE protocolo=?",
                (status, channel[:30], reference[:200], detail[:150], protocol)
            )
    finally:
        conn.close()


def list_leads(status="pendente", limit=50):
    if status not in ("pendente", "encaminhado", "revisar", "em_envio"):
        raise ValueError("Estado comercial inválido")
    conn = _database()
    try:
        rows = conn.execute(
            "SELECT protocolo, criado_em, nome, cpf, telefone, id_cliente, tecnico "
            "FROM interessados WHERE estado=? ORDER BY criado_em ASC LIMIT ?",
            (status, min(int(limit), 500))
        ).fetchall()
        return [{
            "protocolo": row[0],
            "data": datetime.fromtimestamp(row[1], tz=timezone.utc).isoformat(),
            "nome": row[2], "cpf": row[3], "telefone": row[4],
            "id_cliente": row[5], "tecnico": row[6],
        } for row in rows]
    finally:
        conn.close()


def _safe_preview():
    """Apenas metadados/telefone parcialmente mascarado, não exporta CPF."""
    for status in ("pendente", "revisar", "encaminhado"):
        records = list_leads(status, 100)
        print(f"{status}: {len(records)} registro(s), até 100 listados")
        for lead in records:
            phone = lead["telefone"]
            print(lead["protocolo"], lead["data"], lead["nome"][:40],
                  "telefone final", phone[-4:] if phone else "sem telefone")


async def dispatch_pending(limit=50):
    from .main import try_commercial_delivery
    count = 0
    for item in list_leads("pendente", limit):
        result = await try_commercial_delivery(item)
        print(item["protocolo"], result["situacao"])
        count += 1
    return count


if __name__ == "__main__":
    import asyncio
    import sys
    if len(sys.argv) == 2 and sys.argv[1] == "pendentes":
        _safe_preview()
    elif len(sys.argv) == 2 and sys.argv[1] == "encaminhar":
        asyncio.run(dispatch_pending())
    else:
        print("Uso: python -m app.commercial_leads pendentes|encaminhar")
        raise SystemExit(2)
