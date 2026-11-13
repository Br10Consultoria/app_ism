"""Ativação por QR ISM: convite aleatório de uso único e curta duração.

O QR *não contém* a TERMINAL_KEY. Apenas o backend HTTPS resgata o convite.
CLI (dentro do container): python -m app.terminal_activation --minutes 5
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import secrets
import sqlite3
import sys
import time


PREFIX = "ISM-ACT1:"
TOKEN_RE = re.compile(r"^[A-Za-z0-9_-]{43}$")


class ActivationError(Exception):
    pass


def db_path():
    path = os.getenv("TERMINAL_ACTIVATION_DB", "/data/terminal_activation.db")
    if not path.startswith("/") or not path.endswith(".db"):
        raise ActivationError("TERMINAL_ACTIVATION_DB precisa ser um caminho absoluto .db")
    return path


def _connection():
    path = db_path()
    old_mask = os.umask(0o077)
    try:
        conn = sqlite3.connect(path, timeout=10)
    finally:
        os.umask(old_mask)
    try:
        os.chmod(path, 0o600)
        conn.execute("PRAGMA busy_timeout = 10000")
        conn.execute("""CREATE TABLE IF NOT EXISTS terminal_activation (
            code_sha256 TEXT PRIMARY KEY,
            expires_at INTEGER NOT NULL,
            used_at INTEGER
        )""")
        conn.commit()
        return conn
    except Exception:
        conn.close()
        raise


def _digest(code: str) -> str:
    return hashlib.sha256(code.encode("ascii")).hexdigest()


def issue(minutes=5):
    """Emite convite SOMENTE para o operador com acesso ao shell do backend."""
    if not 1 <= minutes <= 15:
        raise ActivationError("Validade permitida: de 1 a 15 minutos")
    key = os.getenv("TERMINAL_KEY", "")
    if not 32 <= len(key) <= 512:
        raise ActivationError("TERMINAL_KEY não configurada no backend")
    token = secrets.token_urlsafe(32)
    expires = int(time.time()) + minutes * 60
    conn = _connection()
    try:
        with conn:
            conn.execute("DELETE FROM terminal_activation WHERE expires_at < ?", (int(time.time()) - 3600,))
            conn.execute(
                "INSERT INTO terminal_activation (code_sha256,expires_at,used_at) VALUES (?,?,NULL)",
                (_digest(token), expires),
            )
    finally:
        conn.close()
    return PREFIX + token, expires


def redeem(code: str):
    """Consumo atômico, irreversível. Evita reuso mesmo com requisições simultâneas."""
    if not isinstance(code, str) or not TOKEN_RE.fullmatch(code):
        return False
    now = int(time.time())
    conn = _connection()
    try:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            cursor = conn.execute(
                "UPDATE terminal_activation SET used_at=? "
                "WHERE code_sha256=? AND used_at IS NULL AND expires_at>=?",
                (now, _digest(code), now),
            )
            return cursor.rowcount == 1
    finally:
        conn.close()


def main():
    parser = argparse.ArgumentParser(
        description="Gerar QR de ativação ISM para uma ÚNICA maquineta, sem mostrar a chave permanente."
    )
    parser.add_argument("--minutes", type=int, default=5, help="Validade: 1 a 15 min (padrão 5)")
    parser.add_argument(
        "--png", default="/tmp/ism-ativacao.png",
        help="Local da imagem temporária PNG (padrão /tmp/ism-ativacao.png)",
    )
    parser.add_argument("--no-ascii", action="store_true", help="Não desenhar QR no terminal")
    args = parser.parse_args()
    try:
        payload, expiration = issue(args.minutes)
        import qrcode
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=10, border=4)
        qr.add_data(payload)
        qr.make(fit=True)
        destination = Path(args.png)
        if not destination.is_absolute():
            raise ActivationError("Informe caminho absoluto para --png")
        qr.make_image(fill_color="black", back_color="white").save(str(destination))
        os.chmod(str(destination), 0o600)
        print("\nISM Telecom — ativação por QR, uso único")
        print("Expira em:", time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(expiration)))
        print("PNG no container:", destination)
        print("ATENÇÃO: encaminhe ao cliente somente o QR e apague a imagem após ativar.")
        if not args.no_ascii:
            print("\nQR para capturar com a câmera da maquineta:\n")
            qr.print_ascii(out=sys.stdout, invert=True)
    except (ActivationError, OSError, sqlite3.Error) as error:
        parser.exit(2, f"Erro ao gerar ativação: {error}\n")


if __name__ == "__main__":
    main()
