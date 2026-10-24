#!/bin/sh
# Install Dream Wi-Fi directly on a compatible OpenWrt router.
# This requires owner-authorized router administration and available Node.js packages.
set -eu

BASE_URL="${DREAM_WIFI_BASE_URL:-https://dream-wifi-live.onrender.com}"
BASE_URL="${BASE_URL%/}"
REPO_URL="${DREAM_WIFI_REPO_URL:-https://github.com/mjnr0477/dream-wifi-live.git}"
REPO_BRANCH="${DREAM_WIFI_REPO_BRANCH:-idea/500km-one-phone-router-discovery}"
SOURCE_NAME="${DREAM_WIFI_SOURCE_NAME:-$(uci get system.@system[0].hostname 2>/dev/null || echo Dream-Wi-Fi-Router)}"
SOURCE_NAME="$(printf '%s' "$SOURCE_NAME" | tr -cd 'A-Za-z0-9_. -' | cut -c1-80)"
SOURCE_LATITUDE="${DREAM_WIFI_SOURCE_LATITUDE:-}"
SOURCE_LONGITUDE="${DREAM_WIFI_SOURCE_LONGITUDE:-}"
INSTALL_DIR="/opt/dream-wifi-source"
ENV_FILE="/etc/dream-wifi-source.env"
INIT_FILE="/etc/init.d/dream-wifi-source"

fail() { printf 'Dream Wi-Fi OpenWrt setup failed: %s\n' "$*" >&2; exit 1; }
[ "$(id -u)" -eq 0 ] || fail "run as root on the router."
[ -r /etc/openwrt_release ] || fail "this installer is only for OpenWrt."
command -v opkg >/dev/null 2>&1 || fail "opkg is unavailable."
[ "$BASE_URL" = "${BASE_URL#https://}" ] && fail "DREAM_WIFI_BASE_URL must use HTTPS."
[ -n "$SOURCE_NAME" ] || SOURCE_NAME="Dream-Wi-Fi-Router"
[ ! -e "$ENV_FILE" ] || fail "$ENV_FILE already exists; revoke the old source credential before reinstalling."

if [ -n "$SOURCE_LATITUDE$SOURCE_LONGITUDE" ]; then
  [ -n "$SOURCE_LATITUDE" ] && [ -n "$SOURCE_LONGITUDE" ] || fail "set both source latitude and longitude."
  awk -v lat="$SOURCE_LATITUDE" -v lon="$SOURCE_LONGITUDE" 'BEGIN { exit !(lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) }' || fail "invalid source coordinates."
fi

printf 'Installing required OpenWrt packages (this may use router storage)...\n'
opkg update
opkg install node node-npm git-http ca-bundle curl tar jsonfilter coreutils-tr coreutils-cut
command -v node >/dev/null 2>&1 || fail "Node.js is unavailable for this router architecture/firmware."
command -v npm >/dev/null 2>&1 || fail "npm is unavailable for this router architecture/firmware."
node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 18 ? 0 : 1)' || fail "Node.js 18 or newer is required."

printf 'Checking Dream Wi-Fi source enrollment service...\n'
CONFIG="$(curl -fsS --connect-timeout 10 --max-time 20 "$BASE_URL/api/dream-wifi/config")" || fail "cannot reach $BASE_URL."
printf '%s' "$CONFIG" | grep -q '"source_enrollment":true' || fail "durable source enrollment is not enabled on the server."
printf '%s' "$CONFIG" | grep -q '"durable_storage_ready":true' || fail "server durable storage is not ready."

ENROLL="$(curl -fsS --connect-timeout 10 --max-time 25 -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' --data '{}' "$BASE_URL/api/dream-wifi/source-enroll")" || fail "source enrollment failed."
SOURCE_ID="$(printf '%s' "$ENROLL" | jsonfilter -e '@.source_id' 2>/dev/null || true)"
SOURCE_TOKEN="$(printf '%s' "$ENROLL" | jsonfilter -e '@.source_token' 2>/dev/null || true)"
[ -n "$SOURCE_ID" ] && [ -n "$SOURCE_TOKEN" ] || fail "server response lacked source credentials; install jsonfilter or check enrollment API."

mkdir -p "$INSTALL_DIR"
if [ -d "$INSTALL_DIR/.git" ]; then
  git -C "$INSTALL_DIR" fetch --depth 1 origin "$REPO_BRANCH"
  git -C "$INSTALL_DIR" checkout -f FETCH_HEAD
else
  git clone --depth 1 --branch "$REPO_BRANCH" "$REPO_URL" "$INSTALL_DIR"
fi
npm --prefix "$INSTALL_DIR" install --omit=dev --no-audit --no-fund

TUNNEL_URL="$(printf '%s' "$BASE_URL" | sed 's#^https:#wss:#')"
umask 077
{
  printf "DREAM_WIFI_TUNNEL_URL=%s\n" "$TUNNEL_URL"
  printf "DREAM_WIFI_SOURCE_ID=%s\n" "$SOURCE_ID"
  printf "DREAM_WIFI_SOURCE_TOKEN=%s\n" "$SOURCE_TOKEN"
  printf "DREAM_WIFI_SOURCE_NAME=%s\n" "$SOURCE_NAME"
  [ -z "$SOURCE_LATITUDE" ] || printf "DREAM_WIFI_SOURCE_LATITUDE=%s\n" "$SOURCE_LATITUDE"
  [ -z "$SOURCE_LONGITUDE" ] || printf "DREAM_WIFI_SOURCE_LONGITUDE=%s\n" "$SOURCE_LONGITUDE"
} > "$ENV_FILE"
chmod 600 "$ENV_FILE"

cat > "$INIT_FILE" <<'INIT'
#!/bin/sh /etc/rc.common
START=95
STOP=10
USE_PROCD=1
start_service() {
  . /etc/dream-wifi-source.env
  procd_open_instance
  procd_set_param command /bin/sh -c 'set -a; . /etc/dream-wifi-source.env; exec node /opt/dream-wifi-source/scripts/dream-wifi-source-agent.js'
  procd_set_param respawn 3600 5 5
  procd_set_param stdout 1
  procd_set_param stderr 1
  procd_close_instance
}
INIT
chmod 755 "$INIT_FILE"
"$INIT_FILE" enable
"$INIT_FILE" restart
sleep 3
if ! pgrep -f 'dream-wifi-source-agent.js' >/dev/null 2>&1; then
  "$INIT_FILE" stop || true
  fail "source agent did not remain running; inspect logread."
fi

printf '\nDream Wi-Fi router-native agent installed and running.\n'
printf 'Source ID: %s\n' "$SOURCE_ID"
printf 'Service: /etc/init.d/dream-wifi-source status\n'
printf 'Logs: logread -e dream-wifi-source\n'
printf 'Stop sharing: /etc/init.d/dream-wifi-source stop\n'
printf 'Keep %s private; it contains the source credential.\n' "$ENV_FILE"
printf '\nCompatibility note: this works only on OpenWrt models with enough storage/RAM and a supported Node.js package. Stock routers without compatible firmware/software cannot be remotely turned into gateways by the phone app alone.\n'
