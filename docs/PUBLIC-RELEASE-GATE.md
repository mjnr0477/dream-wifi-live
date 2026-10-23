# Dream Wi-Fi public-release acceptance gate

**Release decision: BLOCKED.** Passing CI and producing a debug APK are necessary, but they do not demonstrate a safe, working public product. Do not merge the release branch, publish the APK, or advertise full-device/hotspot support until every mandatory gate below has dated evidence.

## 1. Build and automated verification

- [x] Backend syntax and automated smoke/integration tests pass in GitHub Actions on the working branch.
- [x] Android debug APK builds in GitHub Actions.
- [ ] Run backend tests against the exact commit selected for release.
- [ ] Build a signed release APK/AAB from the exact commit selected for release.
- [ ] Verify release signing, package ID/version, permissions, dependency licenses, and artifact checksum.
- [ ] Confirm the production backend commit and mobile app's configured service URL match.

## 2. Durable production operations

- [ ] Configure a durable writable data store for source credential hashes and source directory records. Do not assume a container filesystem is durable across deploys/restarts.
- [ ] Add durable persistence/recovery for receiver pairings or document and safely enforce re-pairing after restart.
- [ ] Test server restart, redeploy, storage failure, and recovery without accidentally accepting stale or revoked credentials.
- [ ] Verify rate limits work across multiple instances/restarts; current in-memory limits are per-process and are not production-wide.
- [ ] Add monitored error reporting, uptime checks, alerting, log redaction, backups/retention policy, and an incident/revocation procedure.
- [ ] Review denial-of-service/resource limits, WebSocket origin policy, enrollment abuse controls, privacy disclosures, and location-consent behavior before exposing the service broadly.

## 3. Physical-device acceptance (mandatory)

Use at least two supported Android phones and a third phone for downstream-hotspot validation. Record device model, Android version, backend commit, app version, date, and observed results.

- [ ] Source enrollment succeeds and source credentials survive a server restart when durable storage is configured.
- [ ] A receiver pairs with a fresh one-time code; invalid, expired, reused, and revoked codes/tokens are rejected.
- [ ] Receiver loads real HTTPS websites and resolves DNS through the selected source, not merely through its own direct internet connection.
- [ ] Test multiple apps, large transfers, simultaneous connections, IPv4/IPv6, timeouts, and interrupted connections. Current implementation restricts TCP to ports 80/443 and UDP to DNS/53, so broad app compatibility is not established.
- [ ] Confirm no receiver traffic leaks directly when the tunnel is disconnected, the source is unavailable, or the VPN service fails.
- [ ] Test screen-off/Doze, app backgrounding, Wi-Fi/cellular changes, reboot, force-stop, reconnect, battery optimization, and Android foreground-service behavior.
- [ ] Confirm tracker status distinguishes connected WebSocket, source-side internet probe, and successful end-to-end receiver traffic.
- [ ] Test source sharing only on an authorized network and confirm provider portal/package activation is handled normally.
- [ ] Test third-phone internet through the receiver phone's hotspot. This feature is not implemented/verified; do not advertise it until proven on supported devices.

## 4. Production and release

- [ ] Deploy the reviewed backend to production and verify health, config, source enrollment, pairing, revocation, and tunnel traffic against the live host.
- [ ] Verify TLS, security headers, error behavior, service restart recovery, and rollback procedure.
- [ ] Publish a privacy policy covering location, network metadata, connection logs, retention, and contact details.
- [ ] Provide support/report-abuse contact, terms/acceptable-use rules, supported-device list, known limitations, and setup/recovery instructions.
- [ ] Perform a final code/security review and retain the physical acceptance evidence.
- [ ] Only then convert this PR from draft and authorize public distribution.

## Current verified facts

- Backend verification passed on working-branch commit `f4aad1f0f1c48363c26cdcd05d593f31950f7449`: [GitHub Actions run 39926315369](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39926315369). This includes the durable-storage readiness gate test.
- Android debug APK build passed on that same commit: [GitHub Actions run 39926315472](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39926315472). Artifact: `dream-wifi-vpn-debug-apk`, SHA-256 `13ec82ee9e89f766dd954417af01cb7e391f0cf2209b55a6e0571c382107a2a6`. This is a debug/test APK, not a production-signed release.
- The source-enrollment endpoint now returns HTTP 503 unless the operator explicitly sets `DREAM_WIFI_STORAGE_READY=true` and configures `DREAM_WIFI_DATA_DIR`. The test confirms enrollment fails closed when storage readiness is not attested. This is a code-level guard only; it has not been deployed to production.
- The public hotspot lookup tries five Overpass endpoints. Backend tests pass, but this branch has not been deployed to Render and the two added endpoints have not been verified against the live service.
- Render production remains on the free plan, tracks `main`, and has no configured persistent disk. The Render workspace currently has no Postgres or Key Value instance available for Dream Wi-Fi.
- The connected Supabase account has an active project named `Dream education`, but no dedicated Dream Wi-Fi project. Do not reuse or modify the Dream Education project; keep the products separate.
- Durable production storage remains unresolved. Before enabling public source enrollment, provision an explicitly approved Dream Wi-Fi-only durable store or persistent disk, configure the service to use it, and verify restart recovery. No paid infrastructure or production deployment change has been made.
- No physical-device end-to-end acceptance tests have been completed.
- Current tunnel limits and the unverified downstream-hotspot behavior remain release blockers. Do not advertise unrestricted internet sharing or third-phone hotspot forwarding until verified on supported devices.
