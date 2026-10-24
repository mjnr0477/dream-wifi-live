# Router-to-phone connectivity across 500 km

**Product decision:** Dream Wi-Fi is a router-to-phone product. The source is a router or an owner-authorized gateway attached to that router. A second phone is not the intended source device.

## The connection model

1. The router provides working internet at the remote site.
2. The router owner enables a compatible Dream Wi-Fi gateway. This can be router-native software on supported firmware or a small always-on Linux device connected by Ethernet/Wi-Fi to the router.
3. The gateway establishes an outbound encrypted WSS tunnel to Dream Wi-Fi and advertises its live status and location.
4. The receiver searches for registered gateways within 500 km, selects one that is online and internet-ready, enters its short-lived authorization code, and approves Android VPN setup.
5. The receiver performs actual HTTPS and DNS tests through the selected router gateway. The UI must not call a gateway connected until the traffic test succeeds.

## Route A — Dedicated gateway (current practical implementation)

Use `scripts/dream-wifi-router-gateway-install.sh` on an owner-controlled Debian/Ubuntu/Raspberry Pi OS computer connected to the router. It enrolls a dedicated source credential and installs a restricted systemd service that reconnects to the server.

- The router remains the internet source; the gateway computer is only the secure bridge.
- No public IP or inbound router port-forwarding is required because the gateway initiates the WSS connection.
- The router owner must approve sharing and provide the location if it should appear in geographic discovery.
- This installer is not compatible with every stock router and requires an always-on Linux host.

Detailed instructions: [Remote router connection](REMOTE-ROUTER-CONNECTION.md).

## Route B — Router-native support (target experience)

Build and validate packages for selected OpenWrt/Linux router models. The package should:
- install the source agent or a supported tunnel client on the router itself;
- keep credentials in root-only storage;
- reconnect automatically and report router WAN health;
- provide an owner-controlled share/stop switch and pairing-code rotation;
- expose only the approved relay path, not an unrestricted router administration interface.

Support must be declared per tested model/firmware. Do not promise that every consumer router can run the agent.

## 500 km behaviour

The 500 km setting is a search radius, not radio range. The receiver reaches the remote router through the public internet and the gateway tunnel. This can work beyond 500 km too, provided both ends have internet and the owner-authorized gateway remains online. A mapped Wi-Fi venue is not itself a reachable gateway.

## Acceptance test before publication

- Register an owner-authorized router gateway and verify it appears in discovery with a correct location.
- From a receiver phone on a separate network, pair to that gateway and approve the VPN.
- Load real HTTPS pages and verify DNS through the selected gateway.
- Record tunnel latency, throughput, packet loss, and the source WAN status.
- Test screen-off, network changes, gateway restart, expired/reused pairing codes, revocation, and disconnect behaviour.
- Confirm traffic stops or safely returns to the receiver's normal connection according to the app's clearly disclosed policy when the gateway is offline.
- Repeat with at least one supported router-native firmware build before advertising router-native compatibility.

## Security and limitations

Never expose source tokens or Supabase service-role keys in the APK, public repository, issue, or logs. Pairing codes should be short-lived and shared only with the intended receiver. Only share a network with the router owner's permission. Ordinary Wi-Fi radio cannot reach 500 km, arbitrary private routers cannot be joined from a map, and unsupported protocols or Android apps may not work through the prototype. Physical-device end-to-end testing remains mandatory.
