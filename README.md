# Dream Wi-Fi

Standalone Dream Wi-Fi / Dream Tunnel service, independent of Dream Education and Supabase.

**Public release status: BLOCKED.** The backend CI tests and Android debug APK build have passed, but durable production storage, a production-aligned deployment, and physical-device end-to-end acceptance remain incomplete. See [Public Release Gate](docs/PUBLIC-RELEASE-GATE.md) for the mandatory evidence checklist. Do not publish the debug APK or advertise unrestricted internet/hotspot support as production-ready.

## Wi-Fi discovery
- The web app requests the visitor's location and searches a radius up to 300 km.
- **Public Wi-Fi locations:** queries OpenStreetMap for mapped venues explicitly tagged as offering Wi-Fi, with a short server-side cache and a fallback Overpass endpoint.
- **Participating Dream Wi-Fi sources:** lists source devices that have opted in, reported coordinates, and connected to Dream Wi-Fi.
- Public map entries are not live-verified; they may be closed, paid, password-protected, or inaccurately mapped. This is not a directory of every private router or SSID.
- Core source enrollment and receiver pairing do not require Dream Education sign-in, Supabase accounts, or Supabase keys.
- `POST /api/dream-wifi/source-enroll` creates a unique source ID/token, rate-limited per IP. Receiver pairing uses a one-time source code (10-minute expiry) and returns a source-scoped receiver session (24-hour expiry).
- A source can relay traffic only while online and explicitly configured to share its network. The server tunnel currently supports bounded TCP ports 80/443 and DNS UDP port 53; it is not proof of unrestricted application traffic.
- Source enrollment stores only SHA-256 token hashes in `source-credentials.json` under `DREAM_WIFI_DATA_DIR`. Restart recovery requires that directory to be writable and mounted on durable storage. Pairing codes and receiver sessions remain in memory, so receivers must pair again after backend restarts.
- The legacy Node source agent still supports `DREAM_WIFI_SOURCE_TOKEN` configured only on the server/authorized source agent. Never put that shared secret in browser or APK code.

## Sharing and receiving roles
- `/share.html` is the source-device setup guide. It explains that a person must run the authorized source agent; opening a link does not activate sharing.
- `/receive.html` is the browser receiver. It requires a one-time code from the source owner and can run a real HTTP probe through the selected source relay.
- The native prototype is in `android/dream-wifi-vpn`. Its core UI uses standalone source enrollment and receiver pairing, with Android `VpnService` and a native TUN-to-SOCKS engine. The Android debug APK build passed in GitHub Actions; physical-device acceptance testing remains outstanding.
- A source-side DNS/TCP or HTTPS probe is reported separately from relay connection status. Neither is a substitute for an end-to-end receiver traffic test.
- Downstream hotspot forwarding is not implemented/verified; do not advertise it as working.

## Environment
Server:
- `DREAM_WIFI_SOURCE_TOKEN`: legacy long random secret used only by authorized Node source agents. Never put it in browser or APK code.
- `DREAM_WIFI_DATA_DIR`: writable directory for `sources.json` and hashed `source-credentials.json`. Point this to a persistent disk mount to retain source reports and source credential recovery across host restarts/deploys. The current Render blueprint is free-tier and does not attach a persistent disk, so this requirement is not met by that deployment configuration yet.
- `PORT`: provided by the host.

Authorized source device:
- `DREAM_WIFI_TUNNEL_URL=wss://YOUR_HOST/api/dream-wifi/tunnel` (the old path remains temporarily accepted)
- `DREAM_WIFI_SOURCE_TOKEN`
- `DREAM_WIFI_SOURCE_NAME`, `DREAM_WIFI_SOURCE_ID`
- `DREAM_WIFI_SOURCE_LATITUDE` and `DREAM_WIFI_SOURCE_LONGITUDE`: decimal coordinates, unless the source agent can obtain location from Termux:API.

## Run
```sh
npm install
DREAM_WIFI_SOURCE_TOKEN='use-a-long-random-secret' npm start
```
Open the hosted site and allow location permission. Public hotspot search sends coordinates to OpenStreetMap's public Overpass service. To make a Dream Wi-Fi source visible, configure its location and run the authorized source agent with the same token.

## Validation
```sh
npm test
```
Passing tests validate protocol handling and query/result filtering, not real-world map coverage or end-to-end browsing through a source. Test real HTTP/HTTPS traffic through an authorized source before advertising access.

## Map attribution
Public hotspot results are based on © OpenStreetMap contributors. Data coverage and accuracy vary by location.

## Real internet verification and mobile app status

The browser receiver sends an HTTP request through the selected source relay and requires a valid response before reporting a pass. This is stronger than a control-plane ping, but it does not prove HTTPS/TLS through the phone, DNS routing for every app, or unrestricted full-device coverage.

The native Android prototype includes user-approved VPN setup and foreground-service code. The debug APK builds, but source/receiver pairing, HTTP and HTTPS browsing, DNS, screen-off behavior, network changes, disconnect/reconnect, and traffic-leak prevention still require physical-device acceptance tests.

The intended downstream hotspot-forwarding feature is still a release blocker. Android VPN traffic is not automatically forwarded to tethered clients on every device. Do not claim third-device internet sharing until that device's real HTTPS requests pass through the selected source in a physical test.

Android and network policy constraints mean no app can guarantee internet when the source network has no internet, blocks relay traffic, or lacks capacity. Dream Wi-Fi must show an honest failure rather than falsely report connected.
