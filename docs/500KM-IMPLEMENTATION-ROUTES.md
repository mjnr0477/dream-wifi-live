# Making Dream Wi-Fi work across 500 km

## What “500 km” means
The 500 km value is a discovery radius, not Wi-Fi radio range. A distant network is usable only when its owner has a live source endpoint connected to that network and has approved the receiver. A map listing alone cannot grant access.

## Route 1 — Phone-to-phone proof of concept (lowest hardware cost)
1. Source phone connects to the Wi-Fi/mobile network it is allowed to share.
2. Source owner runs the Dream Wi-Fi source agent on that phone using Termux, with a dedicated enrolled source ID/token. Keep the token private.
3. The source agent opens an outbound WSS connection to the Dream Wi-Fi service; no inbound router port-forwarding is required.
4. The owner gives the receiver the short-lived pairing code shown by the source.
5. Receiver enters the code and starts Android's user-approved VPN. The VPN routes supported traffic through the source relay.
6. Verify a real HTTPS page, DNS, screen-off/reconnect and a second app. A successful “online” badge or ping alone is not acceptance evidence.

Limitations: Android battery/background restrictions may stop Termux unless configured correctly; Android VPN routing and mobile-carrier policies can restrict traffic. A physical two-phone test is required.

## Route 2 — Dedicated gateway near the router (more reliable)
Run `scripts/dream-wifi-router-gateway-install.sh` on an owner-controlled Debian/Ubuntu/Raspberry Pi OS Linux host connected to the remote router. The installer enrolls a source identity, creates a restricted systemd service, and maintains an outbound WSS tunnel. Pair the receiver using the source's code.

This works across cities/countries when both endpoints have working internet, the host stays online, and the source network permits the traffic. It does not work on an ordinary stock router unless that router supports a compatible agent or a separate Linux host is attached.

## Route 3 — Router-native gateway
For supported OpenWrt/Linux routers, package a minimal source agent or use a supported VPN such as WireGuard/Tailscale with explicit owner setup. This is the best long-term appliance experience, but requires firmware/package compatibility and device-specific testing. Do not claim support for every router.

## Required engineering and acceptance gates
- Server: durable storage for source credentials and directory records; secure enrollment and revocation; TLS/WSS; abuse/rate limits.
- Source: owner consent, one-time pairing code, source internet probe, reconnect/backoff, safe destination validation, clear stop-sharing control.
- Android receiver: VPN consent, route only through the paired source, DNS behavior, reconnect after network changes, foreground-service notification and no silent bypass.
- Test: two physical phones separated by a real long distance; confirm receiver HTTPS and DNS via the source, compare latency/throughput, test screen-off and reconnect, revoke pairing, and verify traffic stops.
- Release: never label a gateway “connectable” from map data alone. Publish only after all acceptance gates pass.

## Security
Never put source tokens, Supabase service-role keys, or long-lived credentials in the APK, a public issue, or a public log. Share only a short-lived pairing code with the intended receiver. Use networks only with the owner's explicit permission.
