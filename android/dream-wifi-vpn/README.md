# Dream Wi-Fi Android VPN prototype

Native Android `VpnService` plus the upstream HevSocks5Tunnel native TUN-to-SOCKS engine. The standalone Android application ID is `com.mjnr0477.dreamwifi`; it uses its own package namespace. The core VPN flow uses standalone one-time receiver pairing and per-source enrollment credentials; it does not require Dream Education sign-in. The app starts a loopback SOCKS5 relay on `127.0.0.1:1081`. The app package is excluded from VPN routing so its own source WebSocket and source-side sockets use the underlying network instead of looping back into the VPN.

## Current implementation and limitations

- The Android debug APK builds successfully in GitHub Actions. A successful build confirms compilation only; this app has not yet been installed and acceptance-tested on physical Android devices.
- The GitHub workflow fetches HevSocks5Tunnel at build time and pins its upstream commit. Retain upstream license notices.
- The relay accepts validated TCP and UDP destination ports. UDP datagrams are capped at 8 KiB, and the implementation limits concurrent streams/UDP associations and rejects private destinations. ICMP and other non-TCP/UDP IP protocols are not forwarded. Broad app compatibility, large-transfer behavior, and absence of traffic leaks remain unverified until physical-device acceptance tests pass.
- Source enrollment uses `POST /api/dream-wifi/source-enroll`. The Android app encrypts the source ID and source token with an AES-GCM key held by Android Keystore; the encrypted payload is stored in app-private preferences and reused when the user resumes source sharing after an app restart. A server authorization rejection clears the saved credential so the user can enroll again. The server stores only source-token SHA-256 hashes and source-directory records in Supabase when configured. Render startup reported Supabase durable storage verified on 2026-10-23; a real source enrollment followed by a live backend restart still needs acceptance testing. The local-file fallback requires a durable writable disk and must not be used on Render's ephemeral filesystem.
- Receiver pairing codes and active receiver sessions are held in backend memory. The Android app encrypts the receiver access token in Android Keystore and can resume it after an app-process restart, but a backend restart invalidates the in-memory session and requires a fresh source pairing code. The app-resume behavior still needs physical-device acceptance testing.
- The Android foreground service uses `START_STICKY`, restores saved encrypted source/receiver sessions after an OS service restart, and attempts to restart source relay on a newly validated Wi-Fi network. These recovery paths do not guarantee recovery after force-stop or every network change and still require physical-device tests. Background operation, reconnect after network changes, screen-off behavior, traffic-leak prevention, and end-to-end receiver browsing still require physical-device tests.
- Downstream hotspot/tethering for a third phone is not implemented or verified. Android tethering traffic generally requires additional routing and device/OS support beyond simply running a VPN on the phone.
- The Android screen distinguishes the remote source's reported network name from this phone's current Wi-Fi name when Android exposes it. Android may hide the receiver hotspot SSID from ordinary apps; the UI explicitly warns that downstream hotspot forwarding is not implemented/verified.
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

## Signed release build

The repository includes a manually triggered signed APK/AAB workflow. It intentionally fails before building if signing secrets are not configured. Never commit the keystore or passwords.

1. Create and keep a release keystore in a secure location that you control:

   ```sh
   keytool -genkeypair -v -keystore dream-wifi-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias dream-wifi-release
   base64 < dream-wifi-release.jks | tr -d '\n' > dream-wifi-release.jks.base64
   ```

2. In GitHub repository **Settings → Secrets and variables → Actions**, add these repository secrets:
   - `ANDROID_KEYSTORE_BASE64`: contents of the base64 file
   - `ANDROID_KEYSTORE_PASSWORD`: keystore password
   - `ANDROID_KEY_ALIAS`: `dream-wifi-release` (or the alias you chose)
   - `ANDROID_KEY_PASSWORD`: key password

3. Add the repository variable `DREAM_WIFI_BASE_URL` with the production HTTPS URL, for example `https://dream-wifi-live.onrender.com`.
4. Open **Actions → Dream Wi-Fi signed Android release → Run workflow**. The workflow builds a signed APK and AAB and publishes SHA-256 checksums as an artifact.

Back up the keystore securely: losing it can prevent updates to the same Android app identity. Do not share the keystore or secrets in chat, issues, or commits. A signed artifact is not release approval; physical VPN, traffic-leak, background, and third-phone hotspot tests must still pass.

## Configure the standalone service host

Build with the deployed Dream Wi-Fi base URL:

```sh
./gradlew assembleDebug -PDREAM_WIFI_BASE_URL=https://YOUR_DREAM_WIFI_HOST
```

The core VPN flow uses standalone source enrollment and one-time source pairing; it does not require Dream Education login or source-ticket endpoints. Before each release, confirm the APK's `DREAM_WIFI_BASE_URL` matches the production backend and that the exact backend commit is live. A successful APK build alone does not prove runtime compatibility.

**Release status:** not ready for public distribution until live source enrollment and restart recovery, backend/app version alignment, signed release packaging, and all physical-device acceptance tests above pass. Keep release work blocked until those gates have dated evidence.
