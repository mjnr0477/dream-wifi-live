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

- Backend automated workflow and Android debug build succeeded on commit `81369114464a1016d4ab9d4f6cad9ba8e26065b8`.
- The working branch has since received documentation-only updates; rerun CI on the latest branch head before release.
- Render blueprint currently uses the free plan and has no configured persistent disk. No production deployment update is asserted here.
- No physical-device end-to-end tests have been completed in this repository.
- The debug APK is a test artifact, not a production-signed release.