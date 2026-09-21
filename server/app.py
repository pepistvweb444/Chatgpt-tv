from __future__ import annotations

import json
import os
import subprocess
import tempfile
import threading
import urllib.request
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.responses import FileResponse, JSONResponse

APP_VERSION = "0.7-live-pipeline"
ROOT = Path(__file__).resolve().parent
APK_PATH = ROOT / "latest.apk"

PCM_RATE = 48000
PCM_CHANNELS = 1
PCM_BYTES_PER_SAMPLE = 2
WINDOW_SECONDS = float(os.getenv("INIT_WINDOW_SECONDS", "3.0"))
WINDOW_BYTES = int(PCM_RATE * PCM_CHANNELS * PCM_BYTES_PER_SAMPLE * WINDOW_SECONDS)
OPENVOICE_URL = os.getenv("INIT_OPENVOICE_URL", "http://127.0.0.1:8000")
VOICE_PROFILE = os.getenv("INIT_VOICE_PROFILE", "Jarvis")
WHISPER_MODEL_NAME = os.getenv("INIT_WHISPER_MODEL", "tiny")
TTS_SPEED = float(os.getenv("INIT_TTS_SPEED", "1.15"))

app = FastAPI(title="INIT Media AI Backend", version=APP_VERSION)

_buffers: dict[str, bytearray] = {}
_buffer_lock = threading.Lock()
_model = None
_model_lock = threading.Lock()


def get_whisper_model():
    global _model
    if _model is None:
        with _model_lock:
            if _model is None:
                from faster_whisper import WhisperModel
                _model = WhisperModel(
                    WHISPER_MODEL_NAME,
                    device="cpu",
                    compute_type="int8",
                    cpu_threads=max(1, min(2, os.cpu_count() or 1)),
                    num_workers=1,
                )
    return _model


def pcm48_to_wav16k(pcm: bytes) -> bytes:
    p = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-f", "s16le", "-ar", str(PCM_RATE), "-ac", "1", "-i", "pipe:0",
            "-ar", "16000", "-ac", "1", "-f", "wav", "pipe:1",
        ],
        input=pcm,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return p.stdout


def wav_to_pcm48(wav: bytes) -> bytes:
    p = subprocess.run(
        [
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-i", "pipe:0",
            "-f", "s16le", "-ar", str(PCM_RATE), "-ac", "1", "pipe:1",
        ],
        input=wav,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return p.stdout


def transcribe(pcm: bytes) -> tuple[str, str]:
    wav = pcm48_to_wav16k(pcm)
    with tempfile.NamedTemporaryFile(suffix=".wav") as tmp:
        tmp.write(wav)
        tmp.flush()
        segments, info = get_whisper_model().transcribe(
            tmp.name,
            beam_size=1,
            best_of=1,
            vad_filter=True,
            condition_on_previous_text=False,
        )
        text = " ".join(seg.text.strip() for seg in segments if seg.text.strip()).strip()
        return text, (info.language or "en")


def translate_text(text: str, source: str, target: str) -> str:
    target = target.split("-")[0].lower()
    source = source.split("-")[0].lower()
    if not text or source == target:
        return text

    import argostranslate.translate

    installed = argostranslate.translate.get_installed_languages()
    src = next((x for x in installed if x.code == source), None)
    dst = next((x for x in installed if x.code == target), None)
    if src is None or dst is None:
        raise RuntimeError(f"Argos model not installed: {source}->{target}")
    tr = src.get_translation(dst)
    if tr is None:
        raise RuntimeError(f"Argos translation unavailable: {source}->{target}")
    return tr.translate(text)


def synthesize(text: str, language: str) -> bytes:
    language = language.split("-")[0].lower()
    body = json.dumps(
        {
            "text": text,
            "profile": VOICE_PROFILE,
            "language": language,
            "speed": TTS_SPEED,
        }
    ).encode("utf-8")
    req = urllib.request.Request(
        OPENVOICE_URL + "/synthesize",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        wav = r.read()
    return wav_to_pcm48(wav)


@app.get("/health")
def health():
    try:
        with urllib.request.urlopen(OPENVOICE_URL + "/health", timeout=2) as r:
            openvoice_ok = r.status == 200
    except Exception:
        openvoice_ok = False

    return {
        "ok": True,
        "version": APP_VERSION,
        "mode": "cpu-live-prototype",
        "asr": f"faster-whisper:{WHISPER_MODEL_NAME}",
        "translation": "argos-offline",
        "voice": f"openvoice:{VOICE_PROFILE}",
        "openvoice_ok": openvoice_ok,
        "window_seconds": WINDOW_SECONDS,
        "tts_speed": TTS_SPEED,
        "video_ai": "remote-or-disabled",
        "spatial_audio": "planned",
    }


@app.post("/v1/translate-pcm")
async def translate_pcm(request: Request):
    pcm = await request.body()
    lang = request.query_params.get("lang", "es-ES")
    quality = request.query_params.get("quality", "Auto AI")
    spatial = request.query_params.get("spatial", "Spatial AI automatico")
    client_id = request.headers.get("X-Init-Session") or (
        request.client.host if request.client else "default"
    )

    common_headers = {
        "X-Init-Lang": lang,
        "X-Init-Quality": quality,
        "X-Init-Spatial": spatial,
    }

    with _buffer_lock:
        buf = _buffers.setdefault(client_id, bytearray())
        buf.extend(pcm)
        if len(buf) < WINDOW_BYTES:
            h = dict(common_headers)
            h["X-Init-Mode"] = "buffering"
            h["X-Init-Buffered"] = str(len(buf))
            return Response(content=b"", media_type="audio/L16", headers=h)

        window = bytes(buf[:WINDOW_BYTES])
        del buf[:WINDOW_BYTES]

    try:
        source_text, source_lang = transcribe(window)
        if not source_text:
            h = dict(common_headers)
            h["X-Init-Mode"] = "no-speech"
            return Response(content=b"", media_type="audio/L16", headers=h)

        translated = translate_text(source_text, source_lang, lang)
        out_pcm = synthesize(translated, lang)

        h = dict(common_headers)
        h.update(
            {
                "X-Init-Mode": "translated-openvoice",
                "X-Init-Source-Lang": source_lang,
                "X-Init-ASR-Chars": str(len(source_text)),
                "X-Init-Translation-Chars": str(len(translated)),
            }
        )
        return Response(content=out_pcm, media_type="audio/L16", headers=h)
    except Exception as e:
        h = dict(common_headers)
        h["X-Init-Mode"] = "error-fallback"
        h["X-Init-Error"] = (type(e).__name__ + ":" + str(e))[:180]
        return Response(content=b"", media_type="audio/L16", headers=h)


@app.get("/apk/latest")
def latest_apk():
    if APK_PATH.exists():
        return FileResponse(
            APK_PATH,
            media_type="application/vnd.android.package-archive",
            filename="INIT-Media-AI-TV-latest.apk",
        )
    return JSONResponse(
        status_code=404,
        content={"ok": False, "error": "latest.apk not deployed yet"},
    )
