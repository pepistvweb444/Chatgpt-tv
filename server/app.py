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

APP_VERSION = "1.0-dual-continuous"
ROOT = Path(__file__).resolve().parent
APK_PATH = ROOT / "latest.apk"
SESSION_ROOT = ROOT / "session-data"
SESSION_ROOT.mkdir(parents=True, exist_ok=True)

PCM_RATE = 48000
BYTES_PER_SECOND = PCM_RATE * 2
FAST_WINDOW_SECONDS = float(os.getenv("INIT_FAST_WINDOW_SECONDS", "1.0"))
CLONE_WINDOW_SECONDS = float(os.getenv("INIT_CLONE_WINDOW_SECONDS", "1.5"))
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
    def __init__(self, sid: str, language: str, voice_mode: str):
        self.sid = safe_id(sid)
        self.language = language
        self.voice_mode = voice_mode if voice_mode in ("fast", "clone") else "fast"
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

    def window_seconds(self) -> float:
        return FAST_WINDOW_SECONDS if self.voice_mode == "fast" else CLONE_WINDOW_SECONDS

    def window_bytes(self) -> int:
        return int(BYTES_PER_SECOND * self.window_seconds())

    def push(self, pcm: bytes, language: str, voice_mode: str):
        if language:
            self.language = language
        if voice_mode in ("fast", "clone"):
            self.voice_mode = voice_mode
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
                return None
            return self.output.popleft()

    def worker(self):
        while True:
            with self.cond:
                needed = self.window_bytes()
                while not self.stopped and self.write_offset - self.read_offset < needed:
                    self.cond.wait(timeout=0.5)
                    needed = self.window_bytes()
                if self.stopped:
                    return
                offset = self.read_offset
                self.read_offset += needed

            with self.input_path.open("rb") as f:
                f.seek(offset)
                window = f.read(needed)

            if len(window) != needed:
                with self.lock:
                    self.read_offset -= needed
                time.sleep(0.05)
                continue

            item = {
                "kind": "none",
                "audio": b"",
                "text": "",
                "mode": "no-speech",
            }
            try:
                source_text, source_lang = transcribe(window)
                self.processed += 1
                if source_text:
                    translated = translate_text(source_text, source_lang, self.language)
                    self.translated += 1

                    if self.voice_mode == "fast":
                        item = {
                            "kind": "text",
                            "audio": b"",
                            "text": translated,
                            "mode": "translated-text",
                            "source_lang": source_lang,
                        }
                    else:
                        out = synthesize(translated, self.language)
                        item = {
                            "kind": "audio",
                            "audio": out,
                            "text": translated,
                            "mode": "translated-openvoice",
                            "source_lang": source_lang,
                        }
            except Exception as e:
                msg = (type(e).__name__ + ":" + str(e))[:180]
                self.errors.append(msg)
                item = {
                    "kind": "error",
                    "audio": b"",
                    "text": "",
                    "mode": "error-fallback",
                    "error": msg,
                }

            with self.lock:
                self.output.append(item)
                self.last_activity = time.time()


def get_session(sid: str, language: str, voice_mode: str) -> Session:
    sid = safe_id(sid)
    with _sessions_lock:
        sess = _sessions.get(sid)
        if sess is None:
            sess = Session(sid, language, voice_mode)
            _sessions[sid] = sess
        else:
            sess.language = language
            if voice_mode in ("fast", "clone"):
                sess.voice_mode = voice_mode
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
        "mode": "dual-continuous",
        "fast_mode": "device-tts",
        "clone_mode": f"openvoice:{VOICE_PROFILE}",
        "asr": f"faster-whisper:{WHISPER_MODEL_NAME}",
        "translation": "argos-offline",
        "openvoice_ok": openvoice_ok,
        "fast_window_seconds": FAST_WINDOW_SECONDS,
        "clone_window_seconds": CLONE_WINDOW_SECONDS,
        "tts_speed": TTS_SPEED,
        "active_sessions": len(_sessions),
    }


@app.post("/v1/stream/push")
async def stream_push(request: Request):
    pcm = await request.body()
    lang = request.query_params.get("lang", "es-ES")
    voice_mode = request.query_params.get("voice_mode", "fast")
    sid = request.headers.get("X-Init-Session") or (
        request.client.host if request.client else "default"
    )
    sess = get_session(sid, lang, voice_mode)
    sess.push(pcm, lang, voice_mode)
    return JSONResponse({
        "ok": True,
        "session": sess.sid,
        "voice_mode": sess.voice_mode,
        "queued_seconds": round(sess.queued_seconds(), 2),
        "processed_windows": sess.processed,
        "translated_windows": sess.translated,
    })


@app.get("/v1/stream/poll-text")
def stream_poll_text(session: str):
    sess = get_session(session, "es-ES", "fast")
    item = sess.pop()
    if item is None:
        return {
            "ok": True,
            "mode": "waiting",
            "text": "",
            "queued_seconds": round(sess.queued_seconds(), 2),
        }

    return {
        "ok": item.get("kind") != "error",
        "mode": item.get("mode", "waiting"),
        "text": item.get("text", ""),
        "source_lang": item.get("source_lang", ""),
        "error": item.get("error", ""),
        "queued_seconds": round(sess.queued_seconds(), 2),
        "processed_windows": sess.processed,
        "translated_windows": sess.translated,
    }


@app.get("/v1/stream/poll")
def stream_poll(session: str):
    sess = get_session(session, "es-ES", "clone")
    item = sess.pop()
    if item is None:
        return Response(
            content=b"",
            media_type="audio/L16",
            headers={
                "X-Init-Mode": "waiting",
                "X-Init-Queued-Seconds": f"{sess.queued_seconds():.1f}",
            },
        )

    headers = {
        "X-Init-Mode": item.get("mode", "waiting"),
        "X-Init-Queued-Seconds": f"{sess.queued_seconds():.1f}",
        "X-Init-Processed-Windows": str(sess.processed),
        "X-Init-Translated-Windows": str(sess.translated),
    }
    if item.get("error"):
        headers["X-Init-Error"] = item["error"]

    return Response(
        content=item.get("audio", b""),
        media_type="audio/L16",
        headers=headers,
    )


@app.post("/v1/synthesize-text")
async def synthesize_text_endpoint(request: Request):
    lang = request.query_params.get("lang", "es-ES")
    text = (await request.body()).decode("utf-8", errors="ignore").strip()
    if not text:
        return Response(content=b"", media_type="audio/L16")
    try:
        audio = synthesize(text, lang)
        return Response(
            content=audio,
            media_type="audio/L16",
            headers={"X-Init-Mode": "fallback-openvoice"},
        )
    except Exception as e:
        return Response(
            content=b"",
            media_type="audio/L16",
            headers={
                "X-Init-Mode": "error-fallback",
                "X-Init-Error": (type(e).__name__ + ":" + str(e))[:180],
            },
        )


@app.get("/v1/session/status")
def session_status(session: str):
    sid = safe_id(session)
    with _sessions_lock:
        sess = _sessions.get(sid)
    if sess is None:
        return JSONResponse(
            status_code=404,
            content={"ok": False, "error": "session-not-found"},
        )
    return {
        "ok": True,
        "session": sid,
        "voice_mode": sess.voice_mode,
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
