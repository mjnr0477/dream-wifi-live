# Dream Wi-Fi

Standalone Dream Wi-Fi / Dream Tunnel service, separate from Dream Education.

**Product direction: router-to-phone.** A router owner authorizes a compatible gateway on their router network; a receiver phone discovers that live gateway and tunnels supported internet traffic through it. Phone-to-phone internet sharing is not the product goal.

**Public release status: BLOCKED.** Backend CI and Android debug builds have passed, but durable production storage, production-aligned deployment, and physical-device end-to-end acceptance remain incomplete. Do not publish a debug APK or advertise unrestricted internet/hotspot support as production-ready.

## 500 km router-to-phone concept

- The app searches participating gateways within a 500 km discovery radius. This is geographic discovery over the internet, not Wi-Fi radio range.
- A remote router must have an owner-authorized, online gateway. A mapped venue or router alone is not remotely connectable.
- The current practical route is an always-on Linux gateway attached to the router, installed with `scripts/dream-wifi-router-gateway-install.sh`. See [Remote router connection](docs/REMOTE-ROUTER-CONNECTION.md).
- The target is router-native support for explicitly tested OpenWrt/Linux firmware. Stock consumer routers are not universally compatible.
- The receiver uses one-time pairing and Android's user-approved VPN to route supported traffic through the selected gateway.
- See [500 km router-to-phone implementation routes](docs/500KM-IMPLEMENTATION-ROUTES.md) for architecture and acceptance criteria.

## Security and status

- Source credentials must stay on the authorized gateway and server; never put them in the APK.
- The backend requires durable source-credential and directory storage before public source enrollment is enabled.
- TCP/UDP relay limits apply; ICMP and other non-TCP/UDP protocols are not forwarded.
- Downstream hotspot forwarding and full Android application compatibility are not verified.
- A source-side health check or online badge is not proof of receiver internet. Real HTTPS and DNS tests through the selected gateway are required.
- Only connect to networks whose owners explicitly authorize sharing.

## Validation

Run `npm test`. Passing tests validate code paths, not real-world connectivity. Physical receiver testing, including HTTPS, DNS, reconnect, screen-off, revocation, and traffic-leak checks, is required before release.
