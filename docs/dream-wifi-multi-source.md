# Dream Wi-Fi multi-source connection model

Dream Wi-Fi can list and route through multiple **registered source endpoints**. A source can be local (for example, an authorized device connected to a nearby router) or remote (a device/gateway on another network or a hosted machine with a usable outbound network path). The source endpoint initiates an encrypted `wss://` connection to Dream Tunnel, so it normally does not need an inbound port opened.

## Register a source agent

On an authorized source device connected to the intended internet network, configure:

- `DREAM_WIFI_TUNNEL_URL=wss://YOUR_DREAM_WIFI_HOST/api/dream-education/dream-wifi/tunnel`
- `DREAM_WIFI_SOURCE_TOKEN` to the source credential configured for the hosted service
- `DREAM_WIFI_SOURCE_NAME` to optionally override the friendly name shown in the app
- `DREAM_WIFI_SOURCE_ID` to a stable, unique ID for that source (letters, digits, underscore and hyphen)

Then run `npm run dream-wifi:source`. Multiple agents can be online at once; do not reuse a source ID for different active endpoints. When no source name is configured, the Node source agent tries Termux Wi-Fi connection info, `iwgetid`, and `nmcli` to discover the connected SSID; it falls back to the host name if those tools are unavailable. The Android source app registers a per-session source ID automatically and currently labels it with the Android device model.

## Select a source

- The owner web page receives the registered source list and can select a source for HTTP and DNS/UDP diagnostics.
- The native Android app receives registered source names and offers a source picker. New TCP and DNS/UDP flows use the selected source. Automatic mode lets the tunnel choose an available source.
- The SOCKS proxy supports `DREAM_WIFI_SELECTED_SOURCE_ID` to route new flows through a particular registered source.

## What "anywhere" means

The tunnel can bring a source into the list from anywhere **when an authorized source endpoint can establish an outbound connection to the hosted tunnel**. A source may use a local router, a remote LAN, or a hosted machine's network. A generic router with no compatible tunnel client, no reachable authorized management interface, and no connected source device cannot be discovered or configured solely by a cloud tunnel. Router administration and hotspot captive-portal/package/payment steps remain explicit and authorized.

## Verification boundaries

The smoke tests verify multi-source registration, listing, ping selection, and routing a TCP-open control message to the selected source. They do not prove full internet access on a physical phone or router firmware compatibility. Real source internet access must be tested on the source network, and Android VPN coverage must be verified on-device.
