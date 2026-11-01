#!/usr/bin/env bash
# D-Agent PC installer for Linux (Ubuntu, Debian, Fedora, Arch, Crostini) and macOS.
#   curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash
set -euo pipefail
BRANCH="claude/project-thread-7jgi6v"
RAW="https://raw.githubusercontent.com/deezi74/D-Agent/$BRANCH/pc-worker"
DIR="$HOME/.dagent-pc"
SCRIPT="$DIR/dagent_pc.py"
PORT=8787
say() { printf '\033[36m==> %s\033[0m\n' "$*"; }

PY=""
for c in python3 python; do
  if command -v "$c" >/dev/null 2>&1 && "$c" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 9) else 1)'; then PY="$(command -v "$c")"; break; fi
done
if [ -z "$PY" ]; then
  say "Installing Python 3..."
  if command -v apt-get >/dev/null; then sudo apt-get update -qq && sudo apt-get install -y python3
  elif command -v dnf >/dev/null; then sudo dnf install -y python3
  elif command -v pacman >/dev/null; then sudo pacman -S --noconfirm python
  elif command -v brew >/dev/null; then brew install python
  else echo "Install Python 3.9+ and run this again."; exit 1; fi
  PY="$(command -v python3)"
fi

mkdir -p "$DIR"
say "Downloading D-Agent PC..."
curl -fsSL "$RAW/dagent_pc.py" -o "$SCRIPT.new"
"$PY" -c 'import ast,sys; ast.parse(open(sys.argv[1]).read())' "$SCRIPT.new"
mv -f "$SCRIPT.new" "$SCRIPT"
pkill -f "dagent_pc.py" 2>/dev/null || true

if [ "$(uname)" = "Darwin" ]; then
  PLIST="$HOME/Library/LaunchAgents/dev.dagent.pc.plist"
  mkdir -p "$(dirname "$PLIST")"
  cat > "$PLIST" <<PL
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>dev.dagent.pc</string>
<key>ProgramArguments</key><array><string>$PY</string><string>$SCRIPT</string></array>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
</dict></plist>
PL
  launchctl unload "$PLIST" 2>/dev/null || true
  launchctl load "$PLIST"
elif command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; then
  say "Starting D-Agent PC as a user service..."
  mkdir -p "$HOME/.config/systemd/user"
  cat > "$HOME/.config/systemd/user/dagent-pc.service" <<UNIT
[Unit]
Description=D-Agent PC (always-on computer for D-Agent)
After=network-online.target

[Service]
ExecStart=$PY $SCRIPT
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now dagent-pc.service
  loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER" 2>/dev/null || true
else
  say "No systemd here (e.g. a container); starting it in the background with nohup."
  nohup "$PY" "$SCRIPT" >/dev/null 2>&1 &
fi

if command -v ufw >/dev/null && sudo -n true 2>/dev/null; then sudo ufw allow "$PORT"/tcp >/dev/null || true; fi
sleep 2
"$PY" "$SCRIPT" --code
say "Done. Logs: $DIR/dagent-pc.log  Settings: $DIR/config.json"
