from __future__ import annotations

import os
from pathlib import Path
from fastapi import FastAPI, Request, Response
from fastapi.responses import FileResponse, JSONResponse

APP_VERSION = "0.4-cloud-test"
ROOT = Path(__file__).resolve().parent
APK_PATH = ROOT / "latest.apk"

app = FastAPI(title="INIT Media AI Backend", version=APP_VERSION)

@app.get("/health")
def health():
    return {
        "ok": True,
        "version": APP_VERSION,
        "mode": "lightweight-edge",
        "asr": os.getenv("INIT_ASR", "not-loaded"),
        "translation": os.getenv("INIT_TRANSLATION", "not-loaded"),
        "voice": os.getenv("INIT_VOICE", "not-loaded"),
        "video_ai": os.getenv("INIT_VIDEO_AI", "remote-or-disabled"),
        "spatial_audio": os.getenv("INIT_SPATIAL_AUDIO", "planned"),
    }

@app.post("/v1/translate-pcm")
async def translate_pcm(request: Request):
    # v0.4 transport test path.
    # It intentionally returns PCM unchanged until the ASR/translation/TTS pipeline
    # is installed on the server. This lets us validate TV capture/network/playback.
    pcm = await request.body()
    lang = request.query_params.get("lang", "es-ES")
    quality = request.query_params.get("quality", "Auto AI")
    spatial = request.query_params.get("spatial", "Spatial AI automatico")
    headers = {
        "X-Init-Lang": lang,
        "X-Init-Quality": quality,
        "X-Init-Spatial": spatial,
        "X-Init-Mode": "pcm-loopback",
    }
    return Response(content=pcm, media_type="audio/L16", headers=headers)

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
