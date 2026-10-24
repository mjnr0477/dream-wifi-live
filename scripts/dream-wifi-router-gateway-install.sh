#!/usr/bin/env bash
set -Eeuo pipefail

# Install an authorized Dream Wi-Fi source gateway on a Linux host connected to
# the router whose internet connection the owner chooses to share.
# This does not break Wi-Fi passwords or remotely activate an ordinary router.
BASE_URL="${DREAM_WIFI_BASE_URL:-https://dream-wifi-live.onrender.com}"
BASE_URL="${BASE_URL%/}"
REPO_URL="${DREAM_WIFI_REPO_URL:-https://github.com/mjnr0477/dream-wifi-live.git}"
REPO_BRANCH="${DREAM_WIFI_REPO_BRANCH:-main}"
SOURCE_NAME="${DREAM_WIFI_SOURCE_NAME:-$(hostname 2>/dev/null || echo Dream-WiFi-Router)}"
SAFE_SOURCE_NAME="$(printf '%s' "$SOURCE_NAME" | tr -d '\r\n')"
SOURCE_LATITUDE="${DREAM_WIFI_SOURCE_LATITUDE:-}"
SOURCE_LONGITUDE="${DREAM_WIFI_SOURCE_LONGITUDE:-}"
INSTALL_DIR="/opt/dream-wifi-source"
ENV_FILE="/etc/dream-wifi-source.env"
SERVICE_FILE="/etc/systemd/system/dream-wifi-source.service"

fail() { printf 'Dream Wi-Fi gateway setup failed: %s\n' "$*" >&2; exit 1; }
[[ "$(id -u)" -eq 0 ]] || fail "run with sudo/root on the Linux gateway."
command -v curl >/dev/null 2>&1 || fail "curl is required."
command -v git >/dev/null 2>&1 || fail "git is required."
command -v node >/dev/null 2>&1 || fail "Node.js is required (18 or newer)."
NODE_BIN="$(command -v node)"
command -v npm >/dev/null 2>&1 || fail "npm is required."
command -v python3 >/dev/null 2>&1 || fail "python3 is required."
command -v systemctl >/dev/null 2>&1 || fail "systemd is required; this installer targets Debian/Ubuntu/Raspberry Pi OS or another systemd Linux host."
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 18 ? 0 : 1)' || fail "Node.js 18 or newer is required."
[[ "$BASE_URL" == https://* ]] || fail "DREAM_WIFI_BASE_URL must use HTTPS."
[[ -n "$SAFE_SOURCE_NAME" ]] || SAFE_SOURCE_NAME="Dream Wi-Fi Router Gateway"

if [[ -n "$SOURCE_LATITUDE" || -n "$SOURCE_LONGITUDE" ]]; then
  [[ -n "$SOURCE_LATITUDE" && -n "$SOURCE_LONGITUDE" ]] || fail "set both DREAM_WIFI_SOURCE_LATITUDE and DREAM_WIFI_SOURCE_LONGITUDE."
  python3 - "$SOURCE_LATITUDE" "$SOURCE_LONGITUDE" <<'PY' || fail "source coordinates are invalid."
import sys
lat, lon = map(float, sys.argv[1:])
if not (-90 <= lat <= 90 and -180 <= lon <= 180):
    raise SystemExit(1)
PY
fi

printf 'Checking Dream Wi-Fi service and durable enrollment availability...\n'
CONFIG="$(curl --fail --silent --show-error --connect-timeout 10 --max-time 20 "$BASE_URL/api/dream-wifi/config")" || fail "cannot reach $BASE_URL."
python3 - "$CONFIG" <<'PY' || fail "server has not enabled durable source enrollment."
import json, sys
d=json.loads(sys.argv[1])
if not d.get("source_enrollment") or not d.get("durable_storage_ready"):
    raise SystemExit(1)
PY

# Enroll one dedicated source identity. Token is written only to a root-owned
# env file and is never printed to the terminal.
ENROLL="$(curl --fail --silent --show-error --connect-timeout 10 --max-time 25 \
  -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' \
  --data '{}' "$BASE_URL/api/dream-wifi/source-enroll")" || fail "source enrollment was rejected; check the server's durable-storage status and rate limit."
SOURCE_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("source_id",""))' <<<"$ENROLL")"
SOURCE_TOKEN="$(python3 -c 'import json,sys; print(json.load(sys.stdin).get("source_token",""))' <<<"$ENROLL")"
[[ "$SOURCE_ID" =~ ^[a-zA-Z0-9_-]{1,80}$ ]] || fail "server returned an invalid source ID."
[[ "$SOURCE_TOKEN" =~ ^[a-zA-Z0-9_-]{32,}$ ]] || fail "server returned an invalid source token."

if ! id dreamwifi >/dev/null 2>&1; then
  useradd --system --home-dir "$INSTALL_DIR" --shell /usr/sbin/nologin dreamwifi
fi
install -d -o dreamwifi -g dreamwifi -m 0750 "$INSTALL_DIR"
if [[ -d "$INSTALL_DIR/.git" ]]; then
  git -C "$INSTALL_DIR" fetch --depth 1 origin "$REPO_BRANCH"
  git -C "$INSTALL_DIR" checkout -f FETCH_HEAD
else
  git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi
npm --prefix "$INSTALL_DIR" ci --omit=dev --no-audit --no-fund
chown -R dreamwifi:dreamwifi "$INSTALL_DIR"

umask 077
{
  printf 'DREAM_WIFI_TUNNEL_URL=%s\n' "${BASE_URL/https:/wss:}/api/dream-wifi/tunnel"
  printf 'DREAM_WIFI_SOURCE_ID=%s\n' "$SOURCE_ID"
  printf 'DREAM_WIFI_SOURCE_TOKEN=%s\n' "$SOURCE_TOKEN"
  printf 'DREAM_WIFI_SOURCE_NAME=%s\n' "$SAFE_SOURCE_NAME"
  [[ -z "$SOURCE_LATITUDE" ]] || printf 'DREAM_WIFI_SOURCE_LATITUDE=%s\n' "$SOURCE_LATITUDE"
  [[ -z "$SOURCE_LONGITUDE" ]] || printf 'DREAM_WIFI_SOURCE_LONGITUDE=%s\n' "$SOURCE_LONGITUDE"
} > "$ENV_FILE"
chown root:dreamwifi "$ENV_FILE"
chmod 0640 "$ENV_FILE"

cat > "$SERVICE_FILE" <<UNIT
[Unit]
Description=Dream Wi-Fi authorized router gateway
Wants=network-online.target
After=network-online.target
[Service]
Type=simple
User=dreamwifi
Group=dreamwifi
WorkingDirectory=$INSTALL_DIR
EnvironmentFile=$ENV_FILE
ExecStart=$NODE_BIN $INSTALL_DIR/scripts/dream-wifi-source-agent.js
Restart=always
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$INSTALL_DIR
UMask=0077
[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable --now dream-wifi-source.service
sleep 2
if ! systemctl is-active --quiet dream-wifi-source.service; then
  journalctl -u dream-wifi-source.service -n 40 --no-pager >&2 || true
  fail "service did not remain active. Inspect: journalctl -u dream-wifi-source.service"
fi

printf '\nDream Wi-Fi router gateway installed and service is active.\n'
printf 'Source ID: %s\n' "$SOURCE_ID"
printf 'Source name: %s\n' "$SAFE_SOURCE_NAME"
printf 'Service status: systemctl status dream-wifi-source.service\n'
printf 'Logs: journalctl -u dream-wifi-source.service -f\n'
printf 'To stop sharing: sudo systemctl disable --now dream-wifi-source.service\n'
printf '\nGive the one-time code shown by the authorized source to your receiver. Do not share this source token.\n'
