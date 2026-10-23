# Dream Wi-Fi Android VPN prototype

Native Android `VpnService` plus the upstream HevSocks5Tunnel native TUN-to-SOCKS engine. The core VPN flow uses standalone one-time receiver pairing and per-source enrollment credentials; it does not require Dream Education sign-in. The app starts a loopback SOCKS5 relay on `127.0.0.1:1081`. The app package is excluded from VPN routing so its own source WebSocket and source-side sockets use the underlying network instead of looping back into the VPN.

## Current implementation and limitations

- The Android debug APK builds successfully in GitHub Actions. A successful build confirms compilation only; this app has not yet been installed and acceptance-tested on physical Android devices.
- The GitHub workflow fetches HevSocks5Tunnel at build time and pins its upstream commit. Retain upstream license notices.
- The relay supports TCP destinations on ports 80 and 443 and UDP DNS on port 53. Other TCP/UDP ports are intentionally not supported by the current implementation, so compatibility with arbitrary apps is not guaranteed. HTTP/DNS dashboard probes do not prove complete device routing or absence of traffic leaks.
- Source enrollment uses `POST /api/dream-wifi/source-enroll`. The Android app encrypts the source ID and source token with an AES-GCM key held by Android Keystore; the encrypted payload is stored in app-private preferences and reused when the user resumes source sharing after an app restart. A server authorization rejection clears the saved credential so the user can enroll again. The server stores only source-token SHA-256 hashes and source-directory records in Supabase when configured. Render startup reported Supabase durable storage verified on 2026-10-23; a real source enrollment followed by a live backend restart still needs acceptance testing. The local-file fallback requires a durable writable disk and must not be used on Render's ephemeral filesystem.
- Receiver pairing codes and receiver sessions are held in memory. Receivers must pair again after a backend restart. Production-grade durable sessions and restart recovery remain outstanding.
- Background operation, reconnect after network changes, screen-off behavior, traffic-leak prevention, and end-to-end receiver browsing have not been verified on physical devices.
- Downstream hotspot/tethering for a third phone is not implemented or verified. Android tethering traffic generally requires additional routing and device/OS support beyond simply running a VPN on the phone.
- Run the source relay only on a network you are authorized to share. The app does not create internet access where there is no working upstream connection and does not bypass a provider's captive portal, package, or payment controls.

## Required physical-device acceptance tests

Before public release, test using at least a source Android phone on the authorized Wi-Fi, a separate receiver Android phone, and a third phone for hotspot testing:

1. Enroll the source and verify that a receiver can pair using a fresh one-time code.
2. Verify HTTPS pages, DNS resolution, and representative apps from the receiver; confirm unsupported protocols are clearly reported rather than presented as working.
3. Confirm receiver traffic stops when the source is disconnected or revoked, and resumes after reconnection without unsafe fallback or traffic leakage.
4. Test network changes, app backgrounding, screen-off/Doze, force-stop/restart, and server restart/redeployment behavior.
5. Verify source health/tracker status against actual reachability rather than WebSocket connection alone.
6. Test third-phone access through the receiver's hotspot. Do not advertise this feature until it demonstrably works on supported devices.
7. Configure durable server storage and validate source credential recovery and session expiry/revocation before production use.
8. Review privacy disclosures, location consent, abuse/rate limiting, logging, and release signing before distribution.

## Configure the standalone service host

Build with the deployed Dream Wi-Fi base URL:

```sh
./gradlew assembleDebug -PDREAM_WIFI_BASE_URL=https://YOUR_DREAM_WIFI_HOST
```

The core VPN flow uses standalone source enrollment and one-time source pairing; it does not require Dream Education login or source-ticket endpoints. Before each release, confirm the APK's `DREAM_WIFI_BASE_URL` matches the production backend and that the exact backend commit is live. A successful APK build alone does not prove runtime compatibility.

**Release status:** not ready for public distribution until live source enrollment and restart recovery, backend/app version alignment, signed release packaging, and all physical-device acceptance tests above pass. Keep release work blocked until those gates have dated evidence.
