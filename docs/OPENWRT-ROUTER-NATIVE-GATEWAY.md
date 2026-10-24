# Router-native Dream Wi-Fi gateway (OpenWrt)

This is the closest supported path to **router → receiver phone without a separate source phone or computer**. It installs the Dream Wi-Fi source agent on the router itself. The router makes an outbound encrypted connection to Dream Wi-Fi, so no inbound public port or port forwarding is required.

## Hard compatibility limit

This is **not a universal installer for every router**. It requires an OpenWrt router whose CPU architecture and firmware feeds provide Node.js 18+, npm, git, enough writable storage/RAM, and working outbound HTTPS/WSS. Many stock ISP routers and many low-memory OpenWrt models do not meet those requirements. A stock router with no compatible agent cannot be made remotely accessible by the receiver phone alone. For those routers, a supported router firmware/feature or an authorized device connected to the router is still required.

The app cannot remotely read Wi-Fi passwords, turn an arbitrary router into a gateway, or connect to a mapped hotspot without authorization.

## Install on a compatible router

1. Back up the router configuration and confirm you are authorized to administer it.
2. Ensure the router has internet access and sufficient storage. The installer uses `opkg` and installs packages; it can fail safely if packages are unavailable.
3. Copy `scripts/dream-wifi-openwrt-gateway-install.sh` onto the router from this repository's `idea/500km-one-phone-router-discovery` branch.
4. Run as root:
   ```sh
   sh /root/dream-wifi-openwrt-gateway-install.sh
   ```
5. For location-based discovery, set `DREAM_WIFI_SOURCE_LATITUDE` and `DREAM_WIFI_SOURCE_LONGITUDE` in the shell before running. Do not share the resulting source token.
6. Inspect service logs for `Dream Tunnel source authenticated` and the one-time receiver pairing code:
   ```sh
   logread -e dream-wifi-source
   ```
7. On the receiver phone, use the app's receiver flow and enter the current code. Keep the router online and connected to the internet.

The installer enrolls a dedicated source credential in the configured Dream Wi-Fi backend and stores it in `/etc/dream-wifi-source.env` with root-only permissions. To stop sharing, run `/etc/init.d/dream-wifi-source stop`; to start again, use `/etc/init.d/dream-wifi-source start`. To fully revoke access, also revoke/delete that source credential using the project's supported credential-management path before removing the file.

## Required proof before release

A successful installation or a “source authenticated” log is not end-to-end proof. Test from a receiver phone physically away from the router:

- Pair the receiver and enable its Android VPN.
- Open multiple HTTPS sites and confirm DNS works.
- Verify the receiver's public egress IP matches the router network's egress (where distinguishable).
- Test screen-off operation, Wi-Fi/mobile handover, reconnect after source reboot, revocation, and measured latency/throughput.
- Confirm no receiver traffic silently falls back to the receiver's local network when the source tunnel fails.

Do not publish claims that this works with **any** router until compatibility coverage exists for the specific router models/firmware and those physical acceptance tests pass.
