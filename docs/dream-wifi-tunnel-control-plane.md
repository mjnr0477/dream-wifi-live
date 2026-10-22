# Dream Wi-Fi / Dream Tunnel control-plane prototype

This milestone adds an authenticated control plane plus a bounded TCP relay for HTTP/HTTPS destinations on ports 80 and 443. It is a SOCKS5 proxy prototype, not a full-device VPN, and has not yet been verified end-to-end over Quant.

## How the prototype works

1. Dream Education validates the owner's Supabase access token before allowing the private dashboard's WebSocket session.
2. A source-side agent connects outbound over TLS WebSocket (`wss://`) using a separate high-entropy source token.
3. The owner dashboard can request a short ping and see whether that source agent answers through the relay.
4. Source presence and ping latency are control-plane signals. The owner-only local SOCKS5 client can relay compatible browser/app TCP traffic through the source agent; live access and packages remain disabled until authorized end-to-end tests pass.

## Configure the source token without paying for a provider

Generate a random token on a trusted device:

```sh
node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"
```

Set that same value as `DREAM_WIFI_SOURCE_TOKEN` in the Dream Education hosting environment and on the authorized source-side device. Never commit the token to GitHub or put it in the frontend.

On the source-side device, install the project's dependencies and run the source agent:

```sh
DREAM_WIFI_TUNNEL_URL=wss://YOUR_DREAM_WIFI_HOST/api/dream-education/dream-wifi/tunnel \
DREAM_WIFI_SOURCE_TOKEN='your-generated-token' \
npm run dream-wifi:source
```

Run the agent only on a device/network you are authorized to use. It opens public HTTP/HTTPS TCP streams and blocks private/reserved destinations and ports other than 80/443. The source network/provider must permit this forwarding.

## Start the remote phone's SOCKS5 client

On the remote Android phone, install Node.js and npm in Termux, clone the standalone Dream Wi-Fi repository, and install dependencies. Then use a current owner Supabase access token from your own signed-in session:

```sh
DREAM_WIFI_TUNNEL_URL=wss://YOUR_DREAM_WIFI_HOST/api/dream-education/dream-wifi/tunnel \
DREAM_WIFI_OWNER_ACCESS_TOKEN='your-current-owner-session-token' \
npm run dream-wifi:proxy
```

The client binds only to `127.0.0.1:1080`. Configure a compatible browser/application to use SOCKS5 at that address. Keep the token private; Supabase access tokens expire, so re-authenticate and restart the client when needed.

This is a bounded web TCP proxy, not full-device routing. It has not yet passed a live test from a Quant-connected source device. Do not enable provider sharing unless the network provider permits this use.

## What remains before remote internet can work

- Run the source agent on a device connected to an authorized Quant network and verify it can reach the deployed Dream server.
- Implement and test a real traffic-forwarding data plane with strict authentication, routing rules, DNS handling, timeouts, and abuse controls.
- Run the local SOCKS5 client on the remote phone and configure a compatible browser/app to use 127.0.0.1:1080. Full-device routing still requires an Android VPNService-based client or a compatible VPN client.
- Test that traffic exits through the authorized Quant connection and that the phone in another location can browse through it.
- Keep provider access and package sales disabled until the end-to-end tests pass.

A tunnel still needs a real transport path. Mobile data may carry the encrypted tunnel but can incur data charges. This prototype does not create radio coverage or free internet.