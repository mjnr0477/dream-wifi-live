# Universal remote-router path: implementation and acceptance

## Objective

Allow a receiver phone to use internet from a router that may be hundreds of kilometres away. The distance is handled by an encrypted overlay tunnel over the existing internet, not by extending Wi-Fi radio range.

## Router-side endpoint

At least one authorized endpoint must be able to originate an outbound connection from the router's network. Supported deployment options, in order of reach:

1. A router-native agent on compatible Linux/OpenWrt firmware.
2. A small Linux device connected to the router's LAN.
3. An Android phone connected to the router's Wi-Fi and running Dream Wi-Fi source mode.

The endpoint does not need an inbound public port. It opens an outbound WSS connection to the Dream Wi-Fi service, authenticates with its own source credential, and forwards receiver traffic to public internet destinations. The router's Wi-Fi password and radio range are not shared across the distance.

A stock router with no compatible software and no device running an agent cannot be controlled or turned into a gateway remotely by the receiver app alone. Discovery data and public hotspot maps are not proof that a gateway is live.

## Receiver path

1. Discover live gateways separately from map-listed venues.
2. Show only gateways with a recent successful source-side internet probe as "internet ready"; do not call this a speed guarantee.
3. Pair with the gateway using a one-time code or a previously issued access token.
4. Establish Android's VPN interface and direct TCP/UDP traffic through the authenticated relay.
5. Verify DNS, HTTPS, reconnect after network changes, screen-off operation, and no unexpected direct-network fallback.
6. Measure latency, packet loss, and throughput separately before displaying speed claims.

## Acceptance gates

A code build or CI pass is not end-to-end proof. Release is blocked until all of these pass:

- The source endpoint connects from a real router network and remains online behind ordinary NAT.
- A receiver phone at a different physical location pairs successfully.
- An HTTPS request made by the receiver reaches a public destination through the source gateway and returns valid response bytes.
- A receiver-side public IP check shows the source gateway's egress, where the source network uses a stable and distinguishable public egress.
- DNS and repeated HTTPS requests work through the Android VPN, including screen-off and network-change recovery.
- Measured latency, packet loss, and throughput are recorded; speed is never inferred from a green status.
- Pairing revocation stops the receiver's relay traffic.

## Current status

The repository has a WebSocket relay, source enrollment/pairing, Android source and receiver modes, and an HTTPS acceptance script. The end-to-end acceptance script has not yet been demonstrated against a real remote gateway and physical receiver. Do not publish or merge to production on the basis of unit tests alone.

## Manual end-to-end test

Run the source agent or Android source mode on an authorized endpoint connected to the target router. On a separate receiver machine with Node.js and the repository dependencies installed, set the tunnel URL and a fresh pairing code locally, then run:

```sh
DREAM_WIFI_TUNNEL_URL=wss://dream-wifi-live.onrender.com/api/dream-wifi/tunnel \
DREAM_WIFI_PAIRING_CODE='PASTE_FRESH_CODE_LOCALLY' \
npm run dream-wifi:acceptance:https
```

The code is one-time use. Never publish it or send it in chat. This script proves a relayed HTTPS request at the protocol level; it does not replace physical Android VPN browsing tests.
