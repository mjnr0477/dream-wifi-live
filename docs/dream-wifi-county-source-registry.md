# Dream Wi-Fi county source registry (first milestone)

This milestone adds an authenticated cloud registry for observations reported by participating field devices. It does **not** claim that a cloud server can detect Wi-Fi radio signals across a county on its own.

## Observation flow

1. A field device discovers a Wi-Fi network where it is physically present.
2. If the device can connect to the network, it checks whether a gateway exists and whether a DNS/TCP internet probe succeeds.
3. The authenticated client submits the observation to `POST /api/dream-wifi/registry/observations`.
4. A signed-in client queries nearby observations with `GET /api/dream-wifi/registry/observations?latitude=0.57&longitude=34.56&radius_km=25`.

The registry rounds coordinates to three decimal places, stores no BSSID or credentials, validates observation fields, rate-limits reports, and requires a valid Dream Education bearer session. SSIDs and approximate positions are visible only through the authenticated API.

## Apply the database migration

Apply `supabase/migrations/20261022_dream_wifi_source_registry.sql` in the Supabase SQL editor. Do not expose the service-role key to a client. The server-side Supabase credential must have permission to access this table.

## Status semantics

- `gateway_detected`: a gateway was detected by the reporting device.
- `internet_verified`: the reporting device completed its connectivity probe.
- `access_state`: what the reporting device knows about the network's access state.
- `remote_access_ready`: always false in this milestone. A scan or successful internet test does not grant permission to relay traffic through a third-party router.

## Android source publishing

The Android app includes a **Publish this Wi-Fi as a remote source** mode. On a phone physically connected to a Wi-Fi network, the authorized operator signs in and explicitly starts source publishing. The app obtains a short-lived source ticket and binds relayed TCP/DNS destination sockets to the selected Wi-Fi network. It does not start the phone's owner VPN in source-only mode.

When a separate validated cellular network is available, the tunnel control WebSocket and session refresh use that control route while destination sockets remain bound to the selected Wi-Fi. This can keep the control channel alive while the selected Wi-Fi is captive, but it does not itself complete the provider's payment flow or guarantee that the portal can be used remotely. Without a separate control route, the source network may need its package activated locally before the tunnel can connect. On the receiving Android phone, a detected HTTP captive-portal redirect can start the VPN in portal mode and add an **Open Wi-Fi portal** notification action that opens an HTTP request through the selected source. The app continues rechecking the internet so it can switch to verified mode after package activation. This only works when the portal redirect and its required resources are reachable through the source relay; private-address or provider-specific portal flows may need an explicit supported configuration. Source publishing is explicit; it must not be enabled on an unaware person's phone. The source ticket is refreshed periodically while the operator remains signed in.

This provides a way to relay an ordinary router's connection without installing Dream Wi-Fi software on the router itself; it does require a participating phone or gateway at the source location.

## What remains for remote internet

A registry record is not a tunnel endpoint. To make a source usable remotely, Dream Wi-Fi needs an explicitly authorized source gateway/agent or provider integration at that network, a successful authenticated tunnel session, and then source selection by the owner client. A passive scan cannot provide a remote route by itself. Captive portals and payment requirements must be completed normally.

## Test

Run `node scripts/dream-wifi-source-registry-smoke-test.js`.
