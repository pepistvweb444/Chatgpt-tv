from __future__ import annotations

import collections
import json
import os
import re
import subprocess
import tempfile
import threading
import time
import urllib.request
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.responses import FileResponse, JSONResponse

APP_VERSION = "0.9-continuous-session"
ROOT = Path(__file__).resolve().parent
APK_PATH = ROOT / "latest.apk"
SESSION_ROOT = ROOT / "session-data"
SESSION_ROOT.mkdir(parents=True, exist_ok=True)

PCM_RATE = 48000
BYTES_PER_SECOND = PCM_RATE * 2
WINDOW_SECONDS = float(os.getenv("INIT_WINDOW_SECONDS", "1.5"))
WINDOW_BYTES = int(BYTES_PER_SECOND * WINDOW_SECONDS)
OPENVOICE_URL = os.getenv("INIT_OPENVOICE_URL", "http://127.0.0.1:8000")
VOICE_PROFILE = os.getenv("INIT_VOICE_PROFILE", "Jarvis")
WHISPER_MODEL_NAME = os.getenv("INIT_WHISPER_MODEL", "tiny")
TTS_SPEED = float(os.getenv("INIT_TTS_SPEED", "1.15"))

app = FastAPI(title="INIT Media AI Backend", version=APP_VERSION)

_model = None
_model_lock = threading.Lock()
_sessions = {}
_sessions_lock = threading.Lock()


def safe_id(value: str) -> str:
    value = re.sub(r"[^a-zA-Z0-9_.-]", "_", value or "default")
    return value[:96] or "default"


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
            "-f", "s16le", "-ar", "48000", "-ac", "1", "-i", "pipe:0",
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
            "-f", "s16le", "-ar", "48000", "-ac", "1", "pipe:1",
        ],
        input=wav,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=True,
    )
    return p.stdout


def transcribe(pcm: bytes):
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
    source = source.split("-")[0].lower()
    target = target.split("-")[0].lower()
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
    body = json.dumps({
        "text": text,
        "profile": VOICE_PROFILE,
        "language": language.split("-")[0].lower(),
        "speed": TTS_SPEED,
    }).encode("utf-8")
    req = urllib.request.Request(
        OPENVOICE_URL + "/synthesize",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=180) as r:
        return wav_to_pcm48(r.read())


class Session:
    def __init__(self, sid: str, language: str):
        self.sid = safe_id(sid)
        self.language = language
        self.input_path = SESSION_ROOT / f"{self.sid}.pcm"
        self.input_path.touch(exist_ok=True)
        self.read_offset = 0
        self.write_offset = self.input_path.stat().st_size
        self.output = collections.deque()
        self.errors = collections.deque(maxlen=20)
        self.lock = threading.Lock()
        self.cond = threading.Condition(self.lock)
        self.stopped = False
        self.processed = 0
        self.translated = 0
        self.last_activity = time.time()
        threading.Thread(target=self.worker, daemon=True).start()

    def push(self, pcm: bytes, language: str):
        if language:
            self.language = language
        if not pcm:
            return
        with self.cond:
            with self.input_path.open("ab") as f:
                f.write(pcm)
            self.write_offset += len(pcm)
            self.last_activity = time.time()
            self.cond.notify_all()

    def queued_seconds(self) -> float:
        with self.lock:
            return max(0, self.write_offset - self.read_offset) / BYTES_PER_SECOND

    def pop(self):
        with self.lock:
            if not self.output:
                return b"", {}
            return self.output.popleft()

    def worker(self):
        while True:
            with self.cond:
                while not self.stopped and self.write_offset - self.read_offset < WINDOW_BYTES:
                    self.cond.wait(timeout=1.0)
                if self.stopped:
                    return
                offset = self.read_offset
                self.read_offset += WINDOW_BYTES

            with self.input_path.open("rb") as f:
                f.seek(offset)
                window = f.read(WINDOW_BYTES)

            if len(window) != WINDOW_BYTES:
                with self.lock:
                    self.read_offset -= WINDOW_BYTES
                time.sleep(0.1)
                continue

            out = b""
            meta = {"mode": "no-speech"}
            try:
                text, source_lang = transcribe(window)
                self.processed += 1
                if text:
                    translated = translate_text(text, source_lang, self.language)
                    out = synthesize(translated, self.language)
                    self.translated += 1
                    meta = {
                        "mode": "translated-openvoice",
                        "source_lang": source_lang,
                        "asr_chars": len(text),
                        "translation_chars": len(translated),
                    }
            except Exception as e:
                msg = (type(e).__name__ + ":" + str(e))[:180]
                self.errors.append(msg)
                meta = {"mode": "error-fallback", "error": msg}

            with self.lock:
                self.output.append((out, meta))
                self.last_activity = time.time()


def get_session(sid: str, language: str) -> Session:
    sid = safe_id(sid)
    with _sessions_lock:
        sess = _sessions.get(sid)
        if sess is None:
            sess = Session(sid, language)
            _sessions[sid] = sess
        else:
            sess.language = language
        return sess


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
        "mode": "continuous-session-queue",
        "asr": f"faster-whisper:{WHISPER_MODEL_NAME}",
        "translation": "argos-offline",
        "voice": f"openvoice:{VOICE_PROFILE}",
        "openvoice_ok": openvoice_ok,
        "window_seconds": WINDOW_SECONDS,
        "tts_speed": TTS_SPEED,
        "active_sessions": len(_sessions),
        "video_ai": "remote-or-disabled",
        "spatial_audio": "planned",
    }


@app.post("/v1/stream/push")
async def stream_push(request: Request):
    pcm = await request.body()
    lang = request.query_params.get("lang", "es-ES")
    sid = request.headers.get("X-Init-Session") or (request.client.host if request.client else "default")
    sess = get_session(sid, lang)
    sess.push(pcm, lang)
    return JSONResponse({
        "ok": True,
        "session": sess.sid,
        "queued_seconds": round(sess.queued_seconds(), 2),
        "processed_windows": sess.processed,
        "translated_windows": sess.translated,
    })


@app.get("/v1/stream/poll")
def stream_poll(session: str):
    sess = get_session(session, "es-ES")
    audio, meta = sess.pop()
    mode = meta.get("mode", "waiting")
    headers = {
        "X-Init-Mode": mode,
        "X-Init-Queued-Seconds": f"{sess.queued_seconds():.1f}",
        "X-Init-Processed-Windows": str(sess.processed),
        "X-Init-Translated-Windows": str(sess.translated),
    }
    if meta.get("error"):
        headers["X-Init-Error"] = meta["error"]
    return Response(content=audio, media_type="audio/L16", headers=headers)


@app.get("/v1/session/status")
def session_status(session: str):
    sid = safe_id(session)
    with _sessions_lock:
        sess = _sessions.get(sid)
    if sess is None:
        return JSONResponse(status_code=404, content={"ok": False, "error": "session-not-found"})
    return {
        "ok": True,
        "session": sid,
        "queued_seconds": round(sess.queued_seconds(), 2),
        "processed_windows": sess.processed,
        "translated_windows": sess.translated,
        "pending_outputs": len(sess.output),
        "errors": list(sess.errors),
    }


@app.post("/v1/session/stop")
def session_stop(session: str):
    sid = safe_id(session)
    with _sessions_lock:
        sess = _sessions.pop(sid, None)
    if sess:
        with sess.cond:
            sess.stopped = True
            sess.cond.notify_all()
    return {"ok": True, "session": sid}


@app.post("/v1/translate-pcm")
async def translate_pcm_compat(request: Request):
    pcm = await request.body()
    lang = request.query_params.get("lang", "es-ES")
    sid = request.headers.get("X-Init-Session") or (request.client.host if request.client else "default")
    sess = get_session(sid, lang)
    sess.push(pcm, lang)
    audio, meta = sess.pop()
    headers = {
        "X-Init-Mode": meta.get("mode", "queued"),
        "X-Init-Queued-Seconds": f"{sess.queued_seconds():.1f}",
    }
    if meta.get("error"):
        headers["X-Init-Error"] = meta["error"]
    return Response(content=audio, media_type="audio/L16", headers=headers)


@app.get("/apk/latest")
def latest_apk():
    if APK_PATH.exists():
        return FileResponse(APK_PATH, media_type="application/vnd.android.package-archive", filename="INIT-Media-AI-TV-latest.apk")
    return JSONResponse(status_code=404, content={"ok": False, "error": "latest.apk not deployed yet"})
