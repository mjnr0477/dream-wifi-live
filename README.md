# Dream Wi-Fi

Standalone Dream Wi-Fi / Dream Tunnel service, independent of Dream Education and Supabase.

## Wi-Fi discovery
- The web app requests the visitor's location and searches a radius up to 300 km.
- **Public Wi-Fi locations:** queries OpenStreetMap for mapped venues explicitly tagged as offering Wi-Fi, with a short server-side cache and a fallback Overpass endpoint.
- **Participating Dream Wi-Fi sources:** lists source devices that have opted in, reported coordinates, and connected to Dream Wi-Fi.
- Public map entries are not live-verified; they may be closed, paid, password-protected, or inaccurately mapped. This is not a directory of every private router or SSID.
- No owner login, Supabase account, Supabase keys, or database is required for public clients.
- A source can relay traffic only while online and explicitly configured to share its network. The tunnel is a bounded HTTP/HTTPS relay, not a full-device VPN.
- Public tunnel clients have per-IP connection limits and per-client stream limits. Source devices still require the server-only source token.

## Sharing and receiving roles
- `/share.html` is the source-device setup guide. It explains that a person must run the authorized source agent; opening a link does not activate sharing.
- `/receive.html` is the receiver dashboard. It lists connected source agents and can send a tunnel ping to verify the source responds.
- A successful ping validates the control connection only, not end-to-end browsing. The receiver still needs a compatible local SOCKS client and a proxy-capable app; whole-device Android routing requires a dedicated VPN-capable app. Do not advertise one-tap/full-device internet access until that is implemented and tested.

## Environment
Server:
- `DREAM_WIFI_SOURCE_TOKEN`: long random secret used only by authorized source agents. Never put it in browser code.
- `DREAM_WIFI_DATA_DIR`: optional writable directory for the source directory JSON file. Point this to a persistent disk mount to retain reports across host restarts/deploys.
- `PORT`: provided by the host.

Authorized source device:
- `DREAM_WIFI_TUNNEL_URL=wss://YOUR_HOST/api/dream-education/dream-wifi/tunnel`
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
Passing tests validate query construction and result filtering, not real-world map coverage or end-to-end browsing through a source. Test real HTTP/HTTPS traffic through an authorized source before advertising access.

## Map attribution
Public hotspot results are based on © OpenStreetMap contributors. Data coverage and accuracy vary by location.
