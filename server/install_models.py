from __future__ import annotations

print("=== INIT Media AI: preparando modelos CPU ===")

from faster_whisper import WhisperModel
print("Descargando/cargando faster-whisper tiny...")
WhisperModel("tiny", device="cpu", compute_type="int8", cpu_threads=2, num_workers=1)
print("Whisper tiny: OK")

import argostranslate.package
import argostranslate.translate

print("Actualizando indice de Argos Translate...")
argostranslate.package.update_package_index()
available = argostranslate.package.get_available_packages()
installed = argostranslate.translate.get_installed_languages()

installed_pairs = set()
for src in installed:
    for dst in installed:
        if src.get_translation(dst) is not None:
            installed_pairs.add((src.code, dst.code))

for source, target in [("en", "es"), ("es", "en")]:
    if (source, target) in installed_pairs:
        print(f"Argos {source}->{target}: ya instalado")
        continue

    pkg = next((p for p in available if p.from_code == source and p.to_code == target), None)
    if pkg is None:
        print(f"Argos {source}->{target}: paquete no encontrado")
        continue

    print(f"Descargando Argos {source}->{target}...")
    path = pkg.download()
    argostranslate.package.install_from_path(path)
    print(f"Argos {source}->{target}: OK")

print("=== Modelos listos ===")
