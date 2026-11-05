#!/usr/bin/env bash
# D-Agent PC installer for Linux (Ubuntu, Debian, Fedora, Arch, Crostini), macOS and Termux on Android.
#   curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh | bash
# In Termux it makes the phone its own always-on computer, listening only on 127.0.0.1.
set -euo pipefail
BRANCH="claude/project-thread-7jgi6v"
RAW="https://raw.githubusercontent.com/deezi74/D-Agent/$BRANCH/pc-worker"
DIR="$HOME/.dagent-pc"
SCRIPT="$DIR/dagent_pc.py"
PORT=8787
say() { printf '\033[36m==> %s\033[0m\n' "$*"; }
TERMUX=0
case "${PREFIX:-}" in *com.termux*) TERMUX=1 ;; esac
[ -n "${TERMUX_VERSION:-}" ] && TERMUX=1
if [ "$TERMUX" = 1 ] && ! command -v python >/dev/null 2>&1; then
  say "Installing Python in Termux..."
  pkg install -y python
fi

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
pkill -f "python[0-9.]* [^ ]*dagent_pc[.]py" 2>/dev/null || true
# A computer listens on the home network so the phone can pair; on a phone it stays private.
[ "$TERMUX" = 1 ] || "$PY" "$SCRIPT" --lan --token >/dev/null

if [ "$TERMUX" = 1 ]; then
  say "Setting up D-Agent PC on this phone..."
  cat > "$DIR/start.sh" <<ST
#!/data/data/com.termux/files/usr/bin/bash
# Starts D-Agent PC if it isn't running. Used by Termux:Boot and by the D-Agent app.
command -v termux-wake-lock >/dev/null && termux-wake-lock
if ! pgrep -f "python[0-9.]* [^ ]*dagent_pc[.]py" >/dev/null 2>&1; then
  cd "\$HOME"
  setsid nohup "$PY" "$SCRIPT" >/dev/null 2>&1 < /dev/null &
fi
ST
  chmod 700 "$DIR/start.sh"
  # Termux:Boot (optional add-on from F-Droid) runs this when the phone starts.
  mkdir -p "$HOME/.termux/boot"
  cp -f "$DIR/start.sh" "$HOME/.termux/boot/dagent-pc.sh"
  "$PY" "$SCRIPT" --bind 127.0.0.1 --token >/dev/null
  bash "$DIR/start.sh"
elif [ "$(uname)" = "Darwin" ]; then
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

if [ "$TERMUX" = 0 ] && command -v ufw >/dev/null && sudo -n true 2>/dev/null; then sudo ufw allow "$PORT"/tcp >/dev/null || true; fi
sleep 2
"$PY" "$SCRIPT" --code
say "Done. Logs: $DIR/dagent-pc.log  Settings: $DIR/config.json"
