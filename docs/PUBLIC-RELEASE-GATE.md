# Dream Wi-Fi public-release acceptance gate

**Release decision: BLOCKED.** Passing CI and producing a debug APK are necessary, but they do not demonstrate a safe, working public product. Do not publish any APK or advertise full-device/hotspot support until every mandatory gate below has dated evidence.

## 1. Build and automated verification

- [x] Backend syntax and automated smoke/integration tests pass in GitHub Actions on the working branch.
- [x] Android debug APK builds in GitHub Actions.
- [x] Add public privacy, acceptable-use, and support/abuse-reporting pages, link them from all user-facing screens, and test their disclosures/navigation in CI. Owner/legal review and confirmation of a private privacy contact channel remain required before public release.
- [x] Harden public destination IP filtering for the Node source relay and native Android relay, including reserved IPv4/IPv6 ranges, with automated address-classification tests.
- [x] Live production smoke passed on `main` commit `b3369cbd61395a0f78b4bab07a28a9012f98bc55`: [run 39972672608](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39972672608). It verified live health, Supabase/durable-storage readiness, security headers, privacy/terms/support pages, and the homepage/receiver prototype warnings.
- [ ] Run backend tests against the exact commit selected for release.
- [ ] Build a signed release APK/AAB from the exact commit selected for release.
- [ ] Verify release signing, package ID/version, permissions, dependency licenses, and artifact checksum.
- [ ] Confirm the production backend commit and mobile app's configured service URL match.

## 2. Durable production operations

- [x] Configure the approved Supabase URL and server-only service-role key in the deployment environment; Render startup reported `Dream Wi-Fi Supabase durable storage verified` at 2026-10-23 12:32:20 UTC. [ ] Enroll a real source and verify its credential/source-directory records survive a live restart. Do not assume a container filesystem is durable across deploys/restarts.
- [x] Receiver sessions are intentionally held in backend memory; expired/revoked sessions are rejected, and the Android app stores receiver tokens encrypted in Android Keystore and clears rejected credentials. Physical-device recovery behavior remains unverified.
- [ ] Test server restart, redeploy, storage failure, and recovery without accidentally accepting stale or revoked credentials.
- [ ] Verify rate limits work across multiple instances/restarts; current in-memory limits are per-process and are not production-wide.
- [ ] Add monitored error reporting, uptime checks, alerting, log redaction, backups/retention policy, and an incident/revocation procedure.
- [ ] Review denial-of-service/resource limits, WebSocket origin policy, enrollment abuse controls, privacy disclosures, and location-consent behavior before exposing the service broadly.

## 3. Physical-device acceptance (mandatory)

Use at least two supported Android phones and a third phone for downstream-hotspot validation. Record device model, Android version, backend commit, app version, date, and observed results.

- [ ] Source enrollment succeeds and source credentials survive a server restart when durable storage is configured.
- [ ] A receiver pairs with a fresh one-time code; invalid, expired, reused, and revoked codes/tokens are rejected.
- [ ] Receiver loads real HTTPS websites and resolves DNS through the selected source, not merely through its own direct internet connection.
- [ ] Test multiple apps, large transfers, simultaneous connections, IPv4/IPv6, arbitrary TCP/UDP ports, timeouts, and interrupted connections. TCP/UDP destination-port restrictions have been removed, but ICMP and other non-TCP/UDP IP protocols are not forwarded; broad app compatibility remains unverified.
- [ ] Confirm no receiver traffic leaks directly when the tunnel is disconnected, the source is unavailable, or the VPN service fails.
- [ ] Test screen-off/Doze, app backgrounding, Wi-Fi/cellular changes, reboot, force-stop, reconnect, battery optimization, and Android foreground-service behavior. The service now attempts sticky restoration from encrypted credentials after an OS service restart and restarts the source relay on a newly validated Wi-Fi network; both behaviors remain physically unverified.
- [ ] Confirm tracker status distinguishes connected WebSocket, source-side internet probe, and successful end-to-end receiver traffic.
- [ ] Verify the Android UI distinguishes the remote source's reported network name, this phone's current Wi-Fi name when available, and receiver hotspot name only when Android exposes it; it must not imply the receiver joined the remote Wi-Fi or that hotspot forwarding works.
- [ ] Test source sharing only on an authorized network and confirm provider portal/package activation is handled normally.
- [ ] Test third-phone internet through the receiver phone's hotspot. This feature is not implemented/verified; do not advertise it until proven on supported devices.

## 4. Production and release

- [x] Deploy the current backend to production; the live smoke test verified health, configuration, and durable-storage readiness. [ ] Verify live source enrollment, pairing, revocation, and real tunnel traffic against the live host.
- [x] HTTPS/TLS connectivity and security headers were exercised by the live production smoke test. [ ] Verify error behavior, service restart recovery, and rollback procedure.
- [x] Publish a project-level privacy notice covering location, network metadata, connection logs, and retention. [ ] Owner/legal review and confirmation of a suitable private privacy-contact channel remain required.
- [x] Publish terms/acceptable-use rules, support and abuse-reporting instructions, and known prototype limitations. [ ] Confirm a private support/security contact channel and publish a supported-device list plus incident/recovery procedures.
- [ ] Perform a final code/security review and retain the physical acceptance evidence.
- [ ] Only then convert this PR from draft and authorize public distribution.

## Current verified facts

- Backend verification passed on `main` commit `b3369cbd61395a0f78b4bab07a28a9012f98bc55`: [GitHub Actions run 39972672571](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39972672571). The suite includes Supabase mock integration, source-credential restart restoration, receiver-session restart rejection, browser WebSocket origin rejection, invalid/reused/expired pairing-code rejection, active-session revocation, revoked-token rejection, arbitrary TCP/UDP port forwarding, split TCP response chunks, public-IP safety checks, public-page disclosure checks, and invalid-port rejection.
- Android debug APK and signed release APK/AAB variant builds passed on `main` commit `63c868d0243f3d63eb8f9bd0b88daf5c3306a590`: [GitHub Actions run 39971729676](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39971729676). Later commits changed public pages and workflow checks, not Android source. CI used an ephemeral key only for validation and did not publish those release artifacts. The Android package is `com.mjnr0477.dreamwifi`, and the code includes Android Keystore source/receiver credential encryption, sticky service restoration, and source Wi-Fi network-change recovery. The downloadable debug APK remains a test build, not a production release.
- `npm audit --audit-level=moderate` passed on [GitHub Actions run 39969103361](https://github.com/mjnr0477/dream-wifi-live/actions/runs/39969103361); CI now blocks moderate- and high-severity dependency advisories.
- Supabase integration tests use a local mock PostgREST service. They verify server-only key use, hashed source credential persistence, startup restoration, and source authentication after a simulated restart. They do **not** prove that Render can authenticate to the live Supabase project.
- Supabase project `Mariona` (`lzmrvalonsxtapigtjcx`) is `ACTIVE_HEALTHY` and has the two RLS-enabled Dream Wi-Fi tables. The Supabase integration tests and Render startup readiness checks passed. A live database inspection on 2026-10-23 still showed zero rows in both tables, so a real source enrollment followed by live restart recovery has not yet been demonstrated.
- Render production service `dream-wifi-live` tracks `main` on the free plan and has no persistent disk. The live application logs report `Dream Wi-Fi Supabase durable storage verified` and `Dream Wi-Fi listening on port 10000 (Supabase durable storage)`. The local-file fallback is not safe to rely on for production; durable source records must use the configured Supabase store.
- Render environment-variable values remain intentionally unexposed. Live startup readiness confirms that the service can use the configured Supabase store, but no real source has yet been enrolled through the live endpoint and verified after a live restart. That end-to-end persistence check remains open.
- No physical-device end-to-end acceptance tests have been completed. Real HTTPS/DNS through the selected source, arbitrary-port app compatibility, background/Doze recovery, no-leak behavior, reconnect, network-name accuracy, and third-phone hotspot forwarding remain unverified. Automated tests cover arbitrary TCP/UDP ports, but ICMP and other non-TCP/UDP IP protocols are not forwarded.
- The manually triggered signed Android release workflow is present at `.github/workflows/dream-wifi-android-release.yml`. To produce a distributable build, the repository owner must configure `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, and `ANDROID_KEY_PASSWORD` as GitHub Actions secrets. No artifact signed with the owner's permanent release key has yet been produced.
- **Release decision remains BLOCKED.** Do not publish the debug APK or advertise full-device/hotspot support until all mandatory gates have dated evidence.
