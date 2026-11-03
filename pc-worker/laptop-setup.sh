#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# laptop-setup.sh — turn an old Linux laptop into D-Agent's always-on box.
#
#   curl -fsSL https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/laptop-setup.sh | bash
#
#   • D-Agent PC worker (port 8787)   — long tasks keep running while the phone sleeps
#   • FreeLLMAPI (port 3001)           — free-provider AI router, off the phone's battery
#   • Freebuff CLI                     — free, ad-supported coding agent (no card)
#   • Tailscale                        — reach all of it from anywhere, no port forwarding
#
# Everything is free. No AI runs locally: the worker uses FreeLLMAPI on this
# laptop (or LM Studio on the PC), so it fits in 4 GB of RAM.
#
#   bash laptop-setup.sh                  # everything
#   bash laptop-setup.sh --llm pc         # worker uses LM Studio on the PC instead
#   bash laptop-setup.sh --skip-freebuff --skip-tailscale
#   bash laptop-setup.sh --headless       # boot to text mode (frees ~700 MB RAM)
#   bash laptop-setup.sh --status         # just show what's running
#
# Safe to re-run: each step checks before it changes anything.
# Tested targets: Ubuntu/Xubuntu 22.04–26.04, Debian 12/13 (x86_64 or arm64).
# ─────────────────────────────────────────────────────────────────────────────
set -Eeuo pipefail

PC_IP="${PC_IP:-192.168.1.113}"          # Windows PC running LM Studio
LAN_CIDR="${LAN_CIDR:-192.168.1.0/24}"
FREELLMAPI_DIR="${FREELLMAPI_DIR:-$HOME/freellmapi}"
FREELLMAPI_REPO="https://github.com/tashfeenahmed/freellmapi.git"
WORKER_INSTALL="https://raw.githubusercontent.com/deezi74/D-Agent/claude/project-thread-7jgi6v/pc-worker/install.sh"
WORKER_CFG="$HOME/.dagent-pc/config.json"
NODE_MIN="20.18.0"
NODE_MAJOR=22

LLM_MODE="freellmapi" SKIP_FREEBUFF=0 SKIP_TAILSCALE=0 SKIP_FREELLMAPI=0 HEADLESS=0 STATUS_ONLY=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --llm) LLM_MODE="${2:?--llm needs freellmapi|pc}"; shift ;;
    --skip-freebuff) SKIP_FREEBUFF=1 ;;
    --skip-tailscale) SKIP_TAILSCALE=1 ;;
    --skip-freellmapi) SKIP_FREELLMAPI=1 ;;
    --headless) HEADLESS=1 ;;
    --status) STATUS_ONLY=1 ;;
    -h|--help) sed -n '2,25p' "${BASH_SOURCE[0]:-$0}" 2>/dev/null || true; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done
[[ "$LLM_MODE" == freellmapi || "$LLM_MODE" == pc ]] || { echo "--llm must be freellmapi or pc" >&2; exit 2; }

if [[ -t 1 ]]; then B=$'\e[36m' G=$'\e[32m' Y=$'\e[33m' R=$'\e[31m' N=$'\e[0m'; else B="" G="" Y="" R="" N=""; fi
step() { printf '\n%s==> %s%s\n' "$B" "$*" "$N"; }
ok()   { printf '  %s✔%s %s\n' "$G" "$N" "$*"; }
warn() { printf '  %s⚠%s %s\n' "$Y" "$N" "$*" >&2; }
die()  { printf '  %s✘ %s%s\n' "$R" "$*" "$N" >&2; exit 1; }
trap 'die "failed at line $LINENO: $BASH_COMMAND"' ERR
have() { command -v "$1" >/dev/null 2>&1; }
ver_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" == "$2" ]]; }

[[ $EUID -ne 0 ]] || die "run as your normal user (it uses sudo when needed), not as root"
export PATH="$HOME/.npm-global/bin:$HOME/.local/bin:$PATH"

lan_ip() { ip -4 route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}'; }
ts_ip()  { have tailscale && tailscale ip -4 2>/dev/null | head -n1 || true; }
code()   { python3 "$HOME/.dagent-pc/dagent_pc.py" --token 2>/dev/null || true; }

# ── status ─────────────────────────────────────────────────────────────────
show_status() {
  local lan ts
  lan="$(lan_ip)"; ts="$(ts_ip)"
  step "Status"
  for svc in dagent-pc freellmapi; do
    if systemctl --user is-active --quiet "$svc" 2>/dev/null; then ok "$svc running"
    else warn "$svc not running  (journalctl --user -u $svc -n 30)"; fi
  done
  curl -fsS -m 3 http://127.0.0.1:8787/ >/dev/null 2>&1 && ok "worker answering on :8787" || warn "worker not answering on :8787"
  curl -fsS -m 3 -o /dev/null http://127.0.0.1:3001/ 2>/dev/null && ok "FreeLLMAPI answering on :3001" || warn "FreeLLMAPI not answering on :3001"
  have freebuff && ok "freebuff installed ($(freebuff --version 2>/dev/null | head -n1 || echo ok))"
  [[ -n "$ts" ]] && ok "Tailscale $ts" || warn "Tailscale not up"
  printf '  RAM free: %s MB of %s MB\n' \
    "$(awk '/MemAvailable/{print int($2/1024)}' /proc/meminfo)" "$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)"
  if have docker && docker ps -q 2>/dev/null | grep -q .; then
    warn "Docker containers are still running (old stacks?) and use RAM:"
    docker ps --format '      {{.Names}}  {{.Status}}' 2>/dev/null || true
  fi
  cat <<EOF

${G}In D-Agent on your phone:${N}
  Always-on computer  Settings > Connectors > Always-on computer > A PC or laptop
                      Address  http://${lan:-<laptop-ip>}:8787   (away from home: http://${ts:-<tailscale-ip>}:8787)
                      Code     $(code)
  FreeLLMAPI          Settings > AI providers > FreeLLMAPI
                      Base URL http://${lan:-<laptop-ip>}:3001/v1   model: auto
                      API key  the freellmapi-... key from the dashboard (Keys page)
  Dashboard           http://${lan:-<laptop-ip>}:3001   (first login: see "First run" in the guide)
EOF
}
[[ $STATUS_ONLY -eq 1 ]] && { show_status; exit 0; }

# ── 0. preflight ───────────────────────────────────────────────────────────
step "Preflight"
[[ "$(uname -s)" == Linux ]] || die "this script is for Linux (on Windows use the D-Agent PowerShell command)"
[[ -r /etc/os-release ]] && . /etc/os-release && ok "${PRETTY_NAME:-Linux} ($(uname -m))"
have apt-get || die "needs apt (Ubuntu/Debian/Xubuntu)"
mem=$(awk '/MemTotal/{print int($2/1024)}' /proc/meminfo)
ok "RAM ${mem} MB"
swap=$(awk '/SwapTotal/{print int($2/1024)}' /proc/meminfo)
(( swap >= 1024 )) || warn "only ${swap} MB swap — the FreeLLMAPI build may run out of memory (zram is set up below)"
curl -fsS -m 8 -o /dev/null https://github.com || die "no internet"
ok "internet reachable"
sudo -v || die "sudo is needed for package installs"

# ── 1. packages + zram ─────────────────────────────────────────────────────
step "System packages"
sudo apt-get update -qq
sudo apt-get install -y -qq curl ca-certificates git python3 build-essential jq >/dev/null
ok "curl git python3 build-essential jq"
if ! swapon --show 2>/dev/null | grep -q zram; then
  if sudo apt-get install -y -qq systemd-zram-generator >/dev/null 2>&1; then
    printf '[zram0]\nzram-size = ram / 2\ncompression-algorithm = zstd\nswap-priority = 100\n' | sudo tee /etc/systemd/zram-generator.conf >/dev/null
    sudo systemctl daemon-reload && sudo systemctl start systemd-zram-setup@zram0.service 2>/dev/null || true
    ok "zram swap (half of RAM, compressed)"
  else warn "zram not available — continuing"; fi
else ok "zram already on"; fi

# ── 2. Node.js ─────────────────────────────────────────────────────────────
step "Node.js (FreeLLMAPI needs >= $NODE_MIN, < 25)"
cur="$(node -v 2>/dev/null | tr -d v || true)"
if [[ -z "$cur" ]] || ! ver_ge "$cur" "$NODE_MIN" || ver_ge "$cur" "25.0.0"; then
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | sudo -E bash - >/dev/null
  sudo apt-get install -y -qq nodejs >/dev/null
  ok "Node $(node -v) installed"
else ok "Node v$cur"; fi
# global npm packages in your home folder: no sudo for npm -g
mkdir -p "$HOME/.npm-global"; npm config set prefix "$HOME/.npm-global"
grep -q '.npm-global/bin' "$HOME/.bashrc" 2>/dev/null || echo 'export PATH="$HOME/.npm-global/bin:$PATH"' >> "$HOME/.bashrc"

# ── 3. Tailscale ───────────────────────────────────────────────────────────
if [[ $SKIP_TAILSCALE -eq 0 ]]; then
  step "Tailscale"
  have tailscale || { curl -fsSL https://tailscale.com/install.sh | sh >/dev/null; ok "installed"; }
  if tailscale status >/dev/null 2>&1; then ok "up: $(ts_ip)"
  else
    warn "log in: open the link it prints (your phone is fine)"
    sudo tailscale up --ssh --accept-dns=false
    ok "up: $(ts_ip)"
  fi
fi

# ── 4. FreeLLMAPI ──────────────────────────────────────────────────────────
if [[ $SKIP_FREELLMAPI -eq 0 ]]; then
  step "FreeLLMAPI"
  if [[ -d "$FREELLMAPI_DIR/.git" ]]; then
    git -C "$FREELLMAPI_DIR" pull --ff-only -q && ok "updated $FREELLMAPI_DIR"
  else
    git clone -q --depth 1 "$FREELLMAPI_REPO" "$FREELLMAPI_DIR" && ok "cloned to $FREELLMAPI_DIR"
  fi
  envf="$FREELLMAPI_DIR/.env"
  if [[ ! -f "$envf" ]]; then
    {
      echo "# written by laptop-setup.sh"
      echo "NODE_ENV=production"
      echo "ENCRYPTION_KEY=$(python3 -c 'import secrets;print(secrets.token_hex(32))')"
      echo "PORT=3001"
      echo "HOST=0.0.0.0"
      echo "FREEAPI_DB_PATH=$FREELLMAPI_DIR/server/data/freellmapi.db"
      echo "# Slow free tiers: allow longer first replies than the 60 s default"
      echo "FALLBACK_TIME_BUDGET_MS=90000"
    } > "$envf"
    chmod 600 "$envf"
    ok "wrote .env (encryption key generated — back up $envf)"
  else ok ".env exists — kept"; fi
  grep -q '^ENCRYPTION_KEY=[0-9a-f]\{64\}$' "$envf" || die "ENCRYPTION_KEY in $envf must be 64 hex characters"

  ( cd "$FREELLMAPI_DIR"
    echo "  installing packages (a few minutes on an old laptop)..."
    npm ci --no-audit --no-fund --loglevel=error
    echo "  building (uses up to ~1.5 GB RAM)..."
    NODE_OPTIONS="--max-old-space-size=1536" npm run build --silent )
  [[ -f "$FREELLMAPI_DIR/server/dist/index.js" ]] || die "build did not produce server/dist/index.js"
  ok "built"

  mkdir -p "$HOME/.config/systemd/user"
  cat > "$HOME/.config/systemd/user/freellmapi.service" <<UNIT
[Unit]
Description=FreeLLMAPI (free LLM router for D-Agent)
After=network-online.target

[Service]
WorkingDirectory=$FREELLMAPI_DIR/server
Environment=FREEAPI_ENV_PATH=$envf
Environment=NODE_ENV=production
Environment=NODE_OPTIONS=--max-old-space-size=512
ExecStart=$(command -v node) dist/index.js
Restart=always
RestartSec=5

[Install]
WantedBy=default.target
UNIT
  systemctl --user daemon-reload
  systemctl --user enable --now freellmapi.service
  systemctl --user restart freellmapi.service
  for _ in $(seq 1 30); do curl -fsS -m 2 -o /dev/null http://127.0.0.1:3001/ 2>/dev/null && break; sleep 2; done
  curl -fsS -m 2 -o /dev/null http://127.0.0.1:3001/ 2>/dev/null && ok "running on :3001" || warn "not answering yet — journalctl --user -u freellmapi -n 50"
fi

# ── 5. D-Agent PC worker ───────────────────────────────────────────────────
step "D-Agent PC worker (always-on computer)"
curl -fsSL "$WORKER_INSTALL" | bash
[[ -f "$WORKER_CFG" ]] || die "worker config not found at $WORKER_CFG"
if [[ "$LLM_MODE" == pc ]]; then
  base="http://$PC_IP:1234/v1"; model=""
else
  base="http://127.0.0.1:3001/v1"; model="auto"
fi
python3 - "$WORKER_CFG" "$base" "$model" <<'PY'
import json, sys
path, base, model = sys.argv[1:4]
cfg = json.load(open(path))
llm = cfg.setdefault("llm", {})
# Only fill in defaults the worker shipped with; keep anything set from the phone.
if llm.get("base_url") in (None, "", "http://localhost:1234/v1"):
    llm["base_url"] = base
    llm["model"] = model
json.dump(cfg, open(path, "w"), indent=2)
print("  model server:", llm["base_url"], "model:", llm.get("model") or "(first listed)")
PY
systemctl --user restart dagent-pc.service 2>/dev/null || true
ok "worker restarted"
[[ "$LLM_MODE" == freellmapi ]] && warn "the worker needs the freellmapi-... key: phone > Your PC > set API key, or put it in $WORKER_CFG (llm.api_key)"

# ── 6. Freebuff ────────────────────────────────────────────────────────────
if [[ $SKIP_FREEBUFF -eq 0 ]]; then
  step "Freebuff (free coding agent)"
  npm install -g freebuff --no-audit --no-fund --loglevel=error
  have freebuff && ok "installed — run: cd <project> && freebuff  (logs in on first run)" || warn "freebuff not on PATH; open a new terminal"
fi

# ── 7. firewall + always-on ────────────────────────────────────────────────
step "Firewall and always-on"
if have ufw && sudo ufw status 2>/dev/null | grep -q "Status: active"; then
  for p in 8787 3001; do
    sudo ufw allow from "$LAN_CIDR" to any port "$p" proto tcp >/dev/null
    sudo ufw allow in on tailscale0 to any port "$p" proto tcp >/dev/null 2>&1 || true
  done
  ok "ufw: 8787 + 3001 from LAN and Tailscale only"
else
  warn "ufw is off — ports are reachable by anything on your Wi-Fi (both need a key/code)"
fi
loginctl enable-linger "$USER" 2>/dev/null || sudo loginctl enable-linger "$USER"
ok "services keep running when you log out"
sudo install -d /etc/systemd/logind.conf.d
printf '[Login]\nHandleLidSwitch=ignore\nHandleLidSwitchExternalPower=ignore\nHandleLidSwitchDocked=ignore\n' \
  | sudo tee /etc/systemd/logind.conf.d/10-dagent.conf >/dev/null
sudo systemctl mask -q sleep.target suspend.target hibernate.target hybrid-sleep.target
ok "closing the lid no longer sleeps it (takes effect after reboot)"
if have xfconf-query && [[ -n "${DISPLAY:-}" ]]; then
  xfconf-query -c xfce4-power-manager -p /xfce4-power-manager/logind-handle-lid-switch -n -t bool -s true 2>/dev/null || true
fi
if [[ $HEADLESS -eq 1 ]]; then
  sudo systemctl set-default multi-user.target >/dev/null
  ok "boots to text mode next time (undo: sudo systemctl set-default graphical.target)"
fi

show_status
