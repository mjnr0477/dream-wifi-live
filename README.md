# Dream Wi-Fi

Standalone Dream Wi-Fi / Dream Tunnel service, independent of Dream Education and Supabase.

## Public discovery
- The web app requests the visitor's location and searches a radius up to 300 km.
- No owner login, Supabase account, Supabase keys, or database is required for public clients.
- The directory records participating source devices that have reported coordinates. It cannot scan all routers over 300 km or discover every nearby SSID from a normal browser.
- Sources appear online only while connected. An offline source is not connectable.
- A source can relay traffic only while online and explicitly configured to share its network. The tunnel is a bounded HTTP/HTTPS relay, not a full-device VPN.
- Public tunnel clients have per-IP connection limits and per-client stream limits. Source devices still require the server-only source token.

## Environment
Server:
- `DREAM_WIFI_SOURCE_TOKEN`: long random secret used only by authorized source agents. Never put it in browser code.
- `DREAM_WIFI_DATA_DIR`: optional writable directory for the source directory JSON file. Point this to a persistent disk mount to retain reports across host restarts/deploys.
- `PORT`: provided by the host.

Authorized source device:
- `DREAM_WIFI_TUNNEL_URL=wss://YOUR_HOST/api/dream-education/dream-wifi/tunnel`
- `DREAM_WIFI_SOURCE_TOKEN`
- `DREAM_WIFI_SOURCE_NAME`, `DREAM_WIFI_SOURCE_ID`
- `DREAM_WIFI_SOURCE_LATITUDE` and `DREAM_WIFI_SOURCE_LONGITUDE`: decimal coordinates. These are configured values, not automatically read from GPS.

## Run
```sh
npm install
DREAM_WIFI_SOURCE_TOKEN='use-a-long-random-secret' npm start
```
Open the hosted site and allow location permission. To make a source visible within the 300 km radius, configure its coordinates and run the authorized source agent with the same token.

## Validation
```sh
npm test
```
Passing tests do not prove end-to-end browsing through a real Quant source. Test real HTTP/HTTPS traffic through an authorized source before advertising access.
