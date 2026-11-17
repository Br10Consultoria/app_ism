#!/usr/bin/env python3
"""Stage GPOS780 988B vendor libs and the official ISM logo for Android build.

The vendor SDK bundle is user-provided and must be committed ONLY to the
private repository branch. Do not download SDKs from unofficial URLs.
The script reads exact ZIP entries; path traversal cannot create files.
"""
from __future__ import annotations

import hashlib
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "android/app/sdk/ISM_GPOS780_988B_SDK_PRIVADO.zip"
LIBS = ROOT / "android/app/libs"
DRAWABLE = ROOT / "android/app/src/main/res/drawable"

# Hashes verified against the user's GPOS780 988B vendor archives.
FILES = {
    "vendor/libgedi-2.2.6-b801c08-gpos780Neo-payment-release.aar":
        "171843d1bc4d6efbe46a17f7fe0536ba0a59c94cfb67439ba978904c4a36ea18",
    "vendor/libppcomp-001.39-250807-gpos780-release.aar":
        "985a19fd72f96a1b586a609cd73ccf68f0886525ed6f046e0c1ec2d85599481a",
    "vendor/libhcl-1.0.10-gpos780-release.aar":
        "77819427f52f5ef2c709cb64ba6516c02917fa6bbee346dc45bc9fcb2a4172c3",
    "vendor/libgandi-2.1.19-668c185a-gpos780Neo-payment-release.aar":
        "6ab22d95c9a0485f9825320eb485f6e03cb197342c589d026a1111e5f5dd825f",
}
LOGO = "assets/ism_logo_gpos780.png"

# GANDI is not referenced by GEDI 2.2.6 (verified across its classes.jar).
# Omit it until a specific runtime need is demonstrated. Its AAR manifest asks
# for broad system and personal-data permissions unrelated to printing/SN.
STAGE = ("libgedi-", "libppcomp-", "libhcl-")


def main() -> int:
    if not BUNDLE.is_file():
        print(
            "\nGPOS780 SDK AUSENTE: envie ISM_GPOS780_988B_SDK_PRIVADO.zip "
            "para android/app/sdk/ na branch deste PR no repositorio PRIVADO.\n",
            file=sys.stderr,
        )
        return 2

    with zipfile.ZipFile(BUNDLE) as archive:
        names = set(archive.namelist())
        for entry, expected in FILES.items():
            if entry not in names:
                raise RuntimeError(f"Arquivo obrigatorio ausente no SDK: {entry}")
            meta = archive.getinfo(entry)
            if meta.file_size > 15 * 1024 * 1024:
                raise RuntimeError(f"Arquivo grande demais no SDK: {entry}")
            payload = archive.read(entry)
            actual = hashlib.sha256(payload).hexdigest()
            if actual != expected:
                raise RuntimeError(f"SHA256 divergente em {entry}; nao usar SDK alterado")

            if pathlib.Path(entry).name.startswith(STAGE):
                LIBS.mkdir(parents=True, exist_ok=True)
                dest = LIBS / pathlib.Path(entry).name
                dest.write_bytes(payload)
                print(f"SDK validado e preparado: {dest.relative_to(ROOT)}")

        if LOGO not in names or archive.getinfo(LOGO).file_size > 4 * 1024 * 1024:
            raise RuntimeError("A logomarca transparente da ISM nao foi encontrada")
        data = archive.read(LOGO)
        if not data.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("O arquivo da logomarca nao e PNG")
        DRAWABLE.mkdir(parents=True, exist_ok=True)
        logo = DRAWABLE / "ism_logo_gpos780.png"
        logo.write_bytes(data)
        print(f"Logomarca transparente adicionada: {logo.relative_to(ROOT)}")

    print("GPOS780 988B: bibliotecas de impressora e identidade prontas.")
    print("GANDI 2.1.19 validado no ZIP, mas nao integrado; permissoes restritas.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
