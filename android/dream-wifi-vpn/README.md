# Dream Wi-Fi Android VPN prototype

Native Android `VpnService` plus the upstream HevSocks5Tunnel native TUN-to-SOCKS engine. The app signs in through Dream Education, obtains a short-lived source ticket, starts its own loopback SOCKS5 relay on `127.0.0.1:1081`, and opens owner/source WebSocket roles to the hosted tunnel. Termux is no longer required by this native prototype. The app package is excluded from VPN routing so its own source WebSocket and Quant-side sockets use the underlying Wi-Fi connection instead of looping back into the VPN.

## Current limitations
- The GitHub workflow fetches HevSocks5Tunnel at build time and configures its JNI class for this app. Retain upstream license notices.
- The relay includes authenticated, size-limited UDP datagram forwarding for public destinations, used by SOCKS5 UDP ASSOCIATE and mapped DNS. TCP remains limited to ports 80 and 443. The owner dashboard includes HTTP and DNS/UDP source-path probes; these verify the relay path, not complete phone routing. UDP support has protocol smoke coverage but is not yet validated on a physical device; compatibility with arbitrary apps is not guaranteed.
- This source has not yet been built, installed, or tested on a physical Android device. Do not distribute or advertise full-device coverage until device tests pass.
- Run the source agent only on a network you are authorized to use. Software cannot create Wi-Fi signal or internet access without a real transport path.
- The backend issues a short-lived source ticket to a verified owner at `POST /api/dream-wifi/source-ticket`; the app keeps owner and source credentials in memory and does not embed a permanent source secret.
- Native SOCKS relay supports TCP ports 80/443 and DNS UDP port 53 only. The Quant captive portal/package/payment flow must be completed by the user; this app does not bypass provider access controls.

## Test
Verify sign-in, source-ticket issuance, VPN permission, Quant portal/package activation, HTTPS browsing, DNS, disconnect, reconnect after network changes, and traffic leakage on a physical Android device. TCP ports other than 80/443 and UDP ports other than DNS/53 remain blocked.


## Configure the standalone service host

Build with the deployed Dream Wi-Fi base URL so the app does not depend on Dream Education:

```sh
./gradlew assembleDebug -PDREAM_WIFI_BASE_URL=https://YOUR_DREAM_WIFI_HOST
```

The standalone service must be deployed and configured first. Do not use the placeholder host literally. The app's login and refresh endpoints are provided by the standalone service.
