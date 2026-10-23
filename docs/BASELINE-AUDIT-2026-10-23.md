# Dream Wi-Fi baseline audit

Date: 2026-10-23
Repository: `mjnr0477/dream-wifi-live`
Branch: `work/requirements-baseline-audit`
Scope: inspection of the default-branch source files available through GitHub. This is a code inspection, not a live deployment or physical-device test.

## Current architecture observed

- Node.js / Express server in `server.js`, with a WebSocket tunnel engine in `product/dream_wifi_tunnel_engine.js`.
- A source-side Node.js connector in `scripts/dream-wifi-source-agent.js` and a proxy client in `scripts/dream-wifi-proxy-client.js`.
- Browser discovery, source setup, and receiver pages in `public/`.
- The README explicitly describes the current tunnel as a bounded HTTP/HTTPS relay, not a full-device VPN.
- The package test script runs JavaScript syntax checks and several server-side smoke/integration tests.
- No native Android project, Android manifest, `VpnService`, or hotspot-forwarding implementation was found in the inspected repository files/search results.

## Release-blocking findings

### 1. Receiver/client WebSocket registration is currently unauthenticated

In `product/dream_wifi_tunnel_engine.js`, a connection can register with role `client` or `owner` without presenting a credential. The supplied `accessToken` in an existing test is not validated by the shown registration branch. The connection receives the source list and can request relay streams.

The same engine uses a shared `DREAM_WIFI_SOURCE_TOKEN` for source registration. Source ownership is not established by the public client registration path, so the current code does not implement secure per-user source authorization or pairing. This is a release blocker and needs a designed, tested authorization flow before public relay use.

### 2. Pairing and revocation are not implemented as a complete self-service flow

The source page states that a self-service pairing flow is not yet available. No complete short-lived, scoped, revocable source/receiver pairing flow was identified in the inspected paths. The source agent still depends on the server-side shared source token.

### 3. No native Android receiver/source implementation is present in the inspected repository

The web receiver page expressly says it cannot route all phone traffic. The inspected repository did not reveal Android app sources, a VPN service, VPN permission handling, foreground service implementation, or hotspot forwarding. These requirements cannot be satisfied by changing browser JavaScript alone.

### 4. The current receiver probe is narrower than the release requirement

The receiver page tests an outbound TCP stream to `example.com:80` using an HTTP request. This is useful as a limited relay diagnostic, but it does not prove HTTPS/TLS end-to-end behavior, DNS routing, all-app traffic routing, or downstream hotspot forwarding. The UI and release report must continue to state this limitation.

### 5. Tracker data must be treated as reported metadata, not proof of service

The source directory is populated from connected source agents that report coordinates. Historical entries may be shown offline. Location/radius filtering is implemented server-side, but a listed source or mapped public hotspot does not prove it is reachable or can provide internet. Real source status, stale-data handling, and radius boundary tests remain necessary.

### 6. Deployment and physical-device status remain unverified

This inspection did not execute `npm test`, inspect live Render logs, confirm the deployed commit, build an APK, or test two/three physical phones. No such result should be described as passed based on this audit.

## Required implementation sequence

1. Add an explicit threat model and implement authenticated source enrollment plus short-lived pairing credentials, expiry, revocation, and per-source authorization. Keep the shared server token server-side only; do not expose it to browser code.
2. Add regression tests proving anonymous clients cannot enumerate or use private participating sources, and that invalid, expired, revoked, and cross-source credentials are rejected.
3. Harden relay stream lifecycle, validation, timeouts, cleanup, backpressure, and honest connection-health reporting; add real integration tests for partial data and failures.
4. Implement or add a separate native Android application module with user-approved `VpnService`, secure credential storage, supported foreground-service behavior, and receiver/source lifecycle management.
5. Design downstream tethered traffic forwarding for supported devices and document devices/Android versions where it is not possible.
6. Run automated tests and a clean Android build; verify the deployed commit and routes; execute physical-device tests and record the device/OS/network details.
7. Do not publish until every release-blocking acceptance test has documented evidence.

## Verification status

- Repository source inspection: completed for the files listed above.
- Automated test suite: not run during this inspection.
- Live Render deployment / deployed SHA: not verified.
- Android build: not available from the inspected source tree.
- Physical two-phone relay test: not run.
- Third-device hotspot forwarding test: not run.

This document records the baseline and is not a declaration that Dream Wi-Fi is production-ready.


## Follow-up implementation work (same isolated branch)

The following incremental changes were added after the initial inspection:

- Receiver registration now requires a one-time pairing code or a valid scoped session token; anonymous registration is rejected.
- The source connector generates a random 12-hex-character pairing code with a 10-minute expiry.
- Successful pairing issues a random source-scoped session token with a 24-hour expiry. Tokens are stored server-side as SHA-256 hashes.
- Receiver source listings and relay requests are restricted to the paired source ID.
- Source-side terminal command `revoke` sends a revocation request that invalidates all receiver pairings for that source and closes connected paired sessions.
- The receiver page now presents a pairing-code input and stores its session token in tab-scoped `sessionStorage`.
- New clients use `/api/dream-wifi/tunnel`; the old path remains accepted temporarily for existing source connector compatibility.
- Control-plane, protocol, and multi-source test cases were revised to cover pairing, anonymous rejection, one-time code use, source scoping, and revocation.
- A GitHub Actions workflow was added for `npm test`.

## Follow-up verification status

- GitHub Actions runs were created but failed immediately before exposing any executable steps or usable logs through the available GitHub interface. This is not a passing test result and does not establish whether the code tests themselves pass.
- Local test execution was not possible in this environment because the repository could not be cloned (network DNS access to GitHub was unavailable).
- No live Render deployment was triggered or confirmed.
- No native Android project, APK build, physical two-phone relay test, screen-off test, VPN traffic test, or third-device hotspot-forwarding test has been completed.

## Remaining security and product work

- Source enrollment still uses the existing shared server-side source token. A public multi-user release needs per-source enrollment credentials and a reviewed ownership model, not just source-scoped receiver pairing.
- Pairing records and session credentials are held in memory and are lost on service restart. Persistent, revocable session handling and restart recovery must be designed before production.
- Browser HTTP probing remains a narrow relay test and does not demonstrate HTTPS end-to-end, DNS routing for phone apps, full-device VPN operation, or hotspot forwarding.
- All Android, background, tracker radius, live deployment, and physical-device acceptance checks remain release blockers.
