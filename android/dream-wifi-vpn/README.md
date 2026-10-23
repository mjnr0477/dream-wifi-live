# Dream Wi-Fi Android VPN prototype

Native Android `VpnService` plus the upstream HevSocks5Tunnel native TUN-to-SOCKS engine. The core VPN flow uses standalone one-time receiver pairing and per-source enrollment credentials; it does not require Dream Education sign-in. The app starts a loopback SOCKS5 relay on `127.0.0.1:1081`. The app package is excluded from VPN routing so its own source WebSocket and source-side sockets use the underlying network instead of looping back into the VPN.

## Current limitations
- The GitHub workflow fetches HevSocks5Tunnel at build time and configures its JNI class for this app. Retain upstream license notices.
- The relay includes authenticated, size-limited UDP datagram forwarding for public destinations, used by SOCKS5 UDP ASSOCIATE and mapped DNS. TCP remains limited to ports 80 and 443. The owner dashboard includes HTTP and DNS/UDP source-path probes; these verify the relay path, not complete phone routing. UDP support has protocol smoke coverage but is not yet validated on a physical device; compatibility with arbitrary apps is not guaranteed.
- This source has not yet been built, installed, or tested on a physical Android device. Do not distribute or advertise full-device coverage until device tests pass.
- Run the source agent only on a network you are authorized to use. Software cannot create Wi-Fi signal or internet access without a real transport path.
- The standalone backend issues source credentials through `POST /api/dream-wifi/source-enroll`; only SHA-256 hashes are persisted. Restart recovery requires `DREAM_WIFI_DATA_DIR` on durable writable storage. Receiver pairing codes and receiver sessions are in memory and receivers must pair again after a backend restart.
- Native SOCKS relay supports TCP ports 80/443 and DNS UDP port 53 only. The Quant captive portal/package/payment flow must be completed by the user; this app does not bypass provider access controls.

## Test
Verify source enrollment, one-time pairing, VPN permission, Quant portal/package activation, real HTTPS browsing, DNS, disconnect/reconnect after network changes, screen-off behavior, and traffic leakage on physical Android devices. TCP ports other than 80/443 and UDP ports other than DNS/53 remain blocked.


## Configure the standalone service host

Build with the deployed Dream Wi-Fi base URL:

```sh
./gradlew assembleDebug -PDREAM_WIFI_BASE_URL=https://YOUR_DREAM_WIFI_HOST
```

The core VPN flow now uses the standalone `POST /api/dream-wifi/source-enroll` endpoint for source enrollment and a one-time pairing code from the source connector for receiver authorization. It no longer needs Dream Education login or source-ticket endpoints for core VPN operation. The previous account-based county-observation UI has been removed from the standalone app. Pairing, source sharing, and VPN connection use the standalone backend.

**Important release blocker:** source enrollment tokens and receiver pairing sessions are currently held in the running backend's memory. A server restart invalidates them, so the source must enroll again and receivers must pair again. Persistent credential storage and restart recovery must be implemented before production. This prototype has not yet been built or tested on physical devices; do not distribute it as a working app until the Android build, end-to-end relay, background, and hotspot acceptance tests pass.
