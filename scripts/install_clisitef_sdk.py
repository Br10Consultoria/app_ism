#!/usr/bin/env python3
"""Stage the CliSiTef (Software Express) SDK for the GPOS780 Android build.

The SDK package is user-provided (Software Express / GetNet) and must be
committed ONLY to the private repository. Do not download SDKs from
unofficial URLs. Only exact, expected ZIP entries are extracted, so path
traversal cannot create files outside the project.
"""
from __future__ import annotations

import hashlib
import pathlib
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parent.parent
BUNDLE = ROOT / "android/app/sdk/clisitef-7.0.117.108.r4-Simulado-gpos780.zip"
BUNDLE_SHA256 = "37a0bedcaa47218deaf1d7aea17d8eb58c7c4bdb8170832ab09a08266622283c"
LIBS = ROOT / "android/app/libs"
JNI = ROOT / "android/app/src/main/jniLibs"

JAR = "clisitef/libs/clisitef-android.jar"
ABIS = ("arm64-v8a", "armeabi-v7a")
NATIVE = ("libclisitef.so", "libtlswnb.so", "libgsurf_rsa.so", "libssl.so", "libcrypto.so")


def main() -> int:
    if not BUNDLE.is_file():
        print(f"\nCliSiTef AUSENTE: envie {BUNDLE.name} para android/app/sdk/.\n", file=sys.stderr)
        return 2
    digest = hashlib.sha256(BUNDLE.read_bytes()).hexdigest()
    if digest != BUNDLE_SHA256:
        raise RuntimeError("SHA256 divergente no pacote CliSiTef; nao usar SDK alterado")

    with zipfile.ZipFile(BUNDLE) as archive:
        names = set(archive.namelist())

        def extract(entry: str, dest: pathlib.Path) -> None:
            if entry not in names:
                raise RuntimeError(f"Arquivo obrigatorio ausente no CliSiTef: {entry}")
            if archive.getinfo(entry).file_size > 15 * 1024 * 1024:
                raise RuntimeError(f"Arquivo grande demais no CliSiTef: {entry}")
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(entry))
            print(f"CliSiTef preparado: {dest.relative_to(ROOT)}")

        extract(JAR, LIBS / "clisitef-android.jar")
        for abi in ABIS:
            for lib in NATIVE:
                extract(f"clisitef/jni/{abi}/{lib}", JNI / abi / lib)

    print("CliSiTef 7.0.117.108.r4 (Simulado) pronto para o build GPOS780.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
