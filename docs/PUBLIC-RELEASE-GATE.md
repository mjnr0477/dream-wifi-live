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

- [ ] Configure the approved Supabase URL and server-only service-role key in the deployment environment; verify the app startup check succeeds and the new source credentials/source directory survive a restart. Do not assume a container filesystem is durable across deploys/restarts.
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

- Backend verification passed on working-branch head `f8c05a190f76343e065b1149434c3fb63b8c8a8a`: [GitHub Actions run 39938693485](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39938693485).
- Android debug APK build passed on the same head: [GitHub Actions run 39938693590](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39938693590). This is a debug/test artifact, not a production-signed APK.
- Automated Supabase integration tests use a local mock PostgREST service. They verify server-side key use, source credential hash persistence, startup restoration, and source authentication after a simulated restart. They do **not** prove that Render can authenticate to the live Supabase project.
- Supabase project `Mariona` (`lzmrvalonsxtapigtjcx`) has the two RLS-enabled Dream Wi-Fi tables. A security-advisor check returned no findings. The tables are currently empty; live application persistence has not yet been demonstrated.
- Render production service `dream-wifi-live` remains configured to track `main` on the free plan, with no persistent disk. The working branch has not been deployed to production. No production deployment or paid infrastructure change has been made.
- Live Render environment-variable values are not available for inspection here, and no live Supabase connection test has been completed. Do not mark production storage as ready until both server-only Supabase variables are configured on Render and the live restart-recovery test passes.
- No physical-device end-to-end acceptance tests have been completed. Real HTTPS/DNS through the selected source, background/Doze recovery, no-leak behavior, reconnect, and third-phone hotspot forwarding remain unverified. The current TCP/UDP restrictions also mean broad app compatibility is not established.
- **Release decision remains BLOCKED.** Do not merge this draft, deploy it, publish the debug APK, or advertise full-device/hotspot support until the mandatory gates above have dated evidence.
