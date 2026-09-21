#!/usr/bin/env bash
set -euo pipefail

APP_DIR=/opt/init-media-ai
REPO_URL=https://github.com/pepistvweb444/Chatgpt-tv.git
PORT=8765

sudo apt-get update
sudo apt-get install -y git python3 python3-venv python3-pip ffmpeg curl ufw

if [ ! -d "$APP_DIR/.git" ]; then
  sudo git clone "$REPO_URL" "$APP_DIR"
else
  sudo git -C "$APP_DIR" pull --ff-only
fi

sudo chown -R "$USER":"$USER" "$APP_DIR"
cd "$APP_DIR/server"

python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip wheel
pip install -r requirements.txt
python install_models.py

sudo tee /etc/systemd/system/init-media-ai.service >/dev/null <<'UNIT'
[Unit]
Description=INIT Media AI Backend
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory=/opt/init-media-ai/server
Environment=PYTHONUNBUFFERED=1
Environment=INIT_WHISPER_MODEL=tiny
Environment=INIT_OPENVOICE_URL=http://127.0.0.1:8000
Environment=INIT_VOICE_PROFILE=Jarvis
Environment=INIT_FAST_WINDOW_SECONDS=1.0
Environment=INIT_CLONE_WINDOW_SECONDS=1.5
Environment=INIT_TTS_SPEED=1.15
ExecStart=/opt/init-media-ai/server/.venv/bin/uvicorn app:app --host 0.0.0.0 --port 8765 --workers 1
Restart=always
RestartSec=3
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
UNIT

sudo systemctl daemon-reload
sudo systemctl restart init-media-ai.service
sudo systemctl enable init-media-ai.service

sudo ufw allow 22/tcp || true
sudo ufw allow ${PORT}/tcp || true
sudo ufw --force enable || true

echo
echo "INIT Media AI backend status:"
sudo systemctl --no-pager --full status init-media-ai.service || true
echo
curl -fsS http://127.0.0.1:${PORT}/health || true
echo
