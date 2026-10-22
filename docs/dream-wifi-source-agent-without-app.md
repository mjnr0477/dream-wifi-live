# Dream Wi-Fi source agent without the native app

A phone does not become a tunnel endpoint merely because it is connected to Wi-Fi. A participating endpoint must run software that opens an authenticated outbound connection to Dream Tunnel and deliberately forwards requests. Dream Wi-Fi cannot reliably discover which phones are associated with a remote access point, install software on them, or use them as relays without the device owner's knowledge and permission.

## Supported options

### 1. Native Android source mode (recommended for paid/captive Wi-Fi)

Install the Dream Wi-Fi Android app on a phone that is authorized to share the connection. The operator signs in, connects the phone to the intended Wi-Fi, and explicitly selects **Publish this Wi-Fi as a remote source**. When validated cellular data is available, the app can use it for tunnel control while binding destination sockets to the selected Wi-Fi. This is the intended path to test captive-portal/package flows remotely; provider-specific portals may still need compatibility work.

### 2. Standalone Node source agent (no Dream Wi-Fi app)

The repository contains `scripts/dream-wifi-source-agent.js`, which can run in Termux on an Android phone or on a Linux gateway. It uses a short-lived owner-issued source ticket in the `DREAM_WIFI_SOURCE_TOKEN` setting, a `wss://` tunnel URL, and an explicit process started by the device operator. An authorized owner can generate the ticket from the Dream Wi-Fi dashboard using **Generate temporary source ticket**. The ticket is valid for 12 hours, is scoped to that owner account, and should be treated like a password. Never put the token in a public page, a client bundle, screenshots, or a public repository.

For an ordinary Wi-Fi connection that already has working internet, an authorized operator can use Termux like this after generating the ticket on the Dream Wi-Fi dashboard:

```sh
pkg update
pkg install nodejs-lts curl
mkdir -p ~/dream-wifi && cd ~/dream-wifi
curl -fsSL https://raw.githubusercontent.com/mjnr0477/dream-wifi-live/main/scripts/dream-wifi-source-agent.js -o dream-wifi-source-agent.js
read -rsp "Paste temporary source ticket: " DREAM_WIFI_SOURCE_TOKEN
echo
export DREAM_WIFI_SOURCE_TOKEN
export DREAM_WIFI_TUNNEL_URL='wss://dream-education-live-4.onrender.com/api/dream-education/dream-wifi/tunnel'
export DREAM_WIFI_SOURCE_NAME='Authorized Wi-Fi source'
node ./dream-wifi-source-agent.js
```

Stop the process with `Ctrl+C` to withdraw the source. The ticket endpoint is owner-authenticated and rate-limited. The hidden `read -s` prompt avoids echoing the ticket or putting it directly in shell history; do not paste the token into support chats or public places.

**Important limitation:** the standalone Node agent uses the operating system's default network route. It does not bind sockets to a specific Android Wi-Fi network. Do not use it to claim captive-portal or remote-package purchasing works while cellular data and a captive Wi-Fi are both present. Use native Android source mode for that case, then verify with a real provider portal and a live end-to-end test.

## What the tracker can and cannot do

- It can display Wi-Fi observations submitted by participating field devices.
- It cannot radio-scan Kibabii or any other location from the cloud.
- It cannot identify the phones currently connected to a remote router from a Wi-Fi SSID alone.
- It cannot turn an unrelated phone into a source without that phone running an agent with the owner's informed opt-in.
- A reported SSID or internet probe is not proof that a remote tunnel is ready.

Only share a connection from a device or gateway you own or are explicitly authorized to administer. Complete the provider's normal package/payment flow; the relay must not bypass it.
