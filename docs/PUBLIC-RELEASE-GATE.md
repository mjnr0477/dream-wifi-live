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

- [x] Configure the approved Supabase URL and server-only service-role key in the deployment environment; Render startup reported `Dream Wi-Fi Supabase durable storage verified` at 2026-10-23 12:32:20 UTC. [ ] Enroll a real source and verify its credential/source-directory records survive a live restart. Do not assume a container filesystem is durable across deploys/restarts.
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

- Backend verification passed on production `main` commit `b3f1bea486c48bde95ef8995f4aa3d4cc3c46cca`: [GitHub Actions run 39953950016](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39953950016). The suite includes Supabase mock integration, source-credential restart restoration, receiver-session restart rejection, and browser WebSocket origin rejection. PR #21 adds invalid-code, single-use-code, active-session revocation, and revoked-token regression coverage; keep it unmerged unless its full CI run succeeds.
- Android debug APK build passed on the same commit: [GitHub Actions run 39939530484](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39939530484). Artifact: [dream-wifi-vpn-debug-apk](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39939530484), SHA-256 `fcbf380e947fbc3fd0704165aae274e9d3edf399c462e2debbba49736261802f`. This is a debug/test APK, not a production-signed release.
- Supabase integration tests use a local mock PostgREST service. They verify server-only key use, hashed source credential persistence, startup restoration, and source authentication after a simulated restart. They do **not** prove that Render can authenticate to the live Supabase project.
- Supabase project `Mariona` (`lzmrvalonsxtapigtjcx`) is `ACTIVE_HEALTHY` and has the two RLS-enabled Dream Wi-Fi tables. The Supabase integration tests and Render startup readiness checks passed. A live database inspection on 2026-10-23 still showed zero rows in both tables, so a real source enrollment followed by live restart recovery has not yet been demonstrated.
- Render production service `dream-wifi-live` tracks `main` on the free plan and has no persistent disk. The latest deployment is live on commit `b3f1bea486c48bde95ef8995f4aa3d4cc3c46cca` (deploy `dep-dbdl76p42hec739ad52g`, completed 2026-10-23 12:32:23 UTC). Its logs report Supabase durable storage verified. The local-file fallback is therefore not safe to rely on for production; durable source records must use the configured Supabase store.
- Render environment-variable values remain intentionally unexposed. The live startup readiness check confirms the service can use the configured Supabase store, but no real source has yet been enrolled through the live endpoint and verified after a live restart. That end-to-end persistence check remains open.
- No physical-device end-to-end acceptance tests have been completed. Real HTTPS/DNS through the selected source, background/Doze recovery, no-leak behavior, reconnect, and third-phone hotspot forwarding remain unverified. The current TCP/UDP restrictions also mean broad app compatibility is not established.
- **Release decision remains BLOCKED.** Do not merge this draft, deploy it, publish the debug APK, or advertise full-device/hotspot support until all mandatory gates have dated evidence.
