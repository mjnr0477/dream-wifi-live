# Dream Wi-Fi

Standalone Dream Wi-Fi / Dream Tunnel service, separated from Dream Education.

## Status

This is a separated prototype. It is **not yet proven to provide remote internet through Quant end-to-end**. Do not advertise paid access or promise internet availability until the source device, tunnel, remote client, and provider permissions have all been verified.

## Architecture

- `server.js`: independent Express service and authenticated WebSocket tunnel.
- `scripts/dream-wifi-source-agent.js`: authorized source-side connector.
- `scripts/dream-wifi-proxy-client.js`: local SOCKS5 client for compatible applications; it is not a full-device Android VPN.
- `public/`: standalone interface.
- `android/dream-wifi-vpn/`: native Android client source, if included in this repository.

Only connect networks and devices you own or are explicitly authorized to use. The relay restricts TCP to ports 80/443 and blocks private/local destinations.

## Required environment

Copy `.env.example` to `.env` for local development. Never commit real credentials.

- `SUPABASE_URL`
- `SUPABASE_ANON_KEY`
- `SUPABASE_SERVICE_ROLE_KEY` — server-only credential for the source registry; never expose it to the browser
- `DREAM_WIFI_OWNER_EMAIL` — exact owner email allowed to access the control plane
- `DREAM_WIFI_SOURCE_TOKEN` — long random secret used by static source agents
- `PORT` — supplied automatically by most hosts

For the source agent, set `DREAM_WIFI_TUNNEL_URL=wss://YOUR_DEPLOYED_HOST/api/dream-education/dream-wifi/tunnel` and `DREAM_WIFI_SOURCE_TOKEN` on the authorized source device.

For the local proxy, set `DREAM_WIFI_TUNNEL_URL` and sign in with a current owner access token as `DREAM_WIFI_OWNER_ACCESS_TOKEN`.

## Run

```sh
npm install
cp .env.example .env
npm start
```

Open `http://localhost:3000`. Set the Supabase environment values before testing owner authentication.

## Validation

```sh
npm test
```

Successful unit/smoke tests do not prove that a remote phone can browse through a Quant connection. That requires a real authorized source device and an end-to-end network test.
