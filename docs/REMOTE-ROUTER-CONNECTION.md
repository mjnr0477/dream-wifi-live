# Remote router connection

Dream Wi-Fi can carry a receiver phone's supported TCP/UDP traffic through an **authorized gateway** connected to a remote router's internet connection. The gateway maintains an outbound encrypted WebSocket to Dream Wi-Fi, so it does not require a public IP address or inbound port forwarding at the remote site.

## Set up the remote router site

1. The router owner must authorize sharing and connect an always-on Linux host to that router's internet (for example, a Raspberry Pi or small Linux gateway). This installer targets systemd Linux such as Debian, Ubuntu, and Raspberry Pi OS; it is not a universal installer for stock home-router firmware.
2. On that host, install Node.js 18+, npm, Git, curl, Python 3, and systemd. Set optional source coordinates and name, then run the installer as root:

   ```sh
   sudo DREAM_WIFI_SOURCE_NAME="My remote router" \
     DREAM_WIFI_SOURCE_LATITUDE="0.0000" \
     DREAM_WIFI_SOURCE_LONGITUDE="0.0000" \
     bash scripts/dream-wifi-router-gateway-install.sh
   ```

   Replace the sample coordinates with the gateway's real location. Coordinates are optional; without them the gateway can still relay traffic, but it will not be geographically discoverable.
3. The installer checks that the live backend allows durable enrollment, creates a source-scoped credential, stores it in a root-owned file readable only by the dedicated service account, installs the source agent as a systemd service, and starts it. The secret is not printed. If enrollment is disabled, do not bypass the check; restore the server's durable storage configuration first.
4. Check the gateway with `systemctl status dream-wifi-source.service` and `journalctl -u dream-wifi-source.service -f`. The source must report authenticated/online and have a successful source-side internet probe. The agent requests a fresh one-time pairing code every eight minutes while connected; read the latest code from the service logs and share it only with the receiver you authorize.
5. On the receiver phone, open Dream Wi-Fi, choose **Receive internet**, enter the fresh one-time pairing code supplied by the authorized source owner, approve Android's VPN request, and test HTTPS. The code expires after 10 minutes and is single-use. Re-pair after the receiver session expires or a backend restart.
6. To stop sharing, run `sudo systemctl disable --now dream-wifi-source.service` on the gateway.

## What this provides

- The receiver phone can use the internet egress of the remote router site while the gateway and receiver tunnel are online, subject to Android VPN routing, source network policy, and supported TCP/UDP protocols.
- The connection is not based on the map radius. The 500 km map helps find locations; the encrypted gateway tunnel is the actual transport and can work across larger distances when both endpoints have internet.
- It does not automatically connect to arbitrary unregistered routers, reveal Wi-Fi passwords, bypass captive portals, or join a router's Wi-Fi from a map pin. A router owner must authorize the gateway.
- It does not make the receiver phone's Wi-Fi radio physically join the remote SSID. The phone's traffic is tunneled through the remote gateway.
- Downstream hotspot sharing, every Android app/protocol, background reliability, and real-world speed equivalence must not be advertised unless validated on physical devices. Source-side health is not proof of receiver HTTPS traffic.

## Security

Each gateway gets a unique source credential. Keep `/etc/dream-wifi-source.env` private; never copy the token into a web page, APK, issue, or chat. If a gateway is retired or compromised, revoke its source credential through the service's credential-management procedure and stop the systemd service. Only share networks you own or have permission to share.
