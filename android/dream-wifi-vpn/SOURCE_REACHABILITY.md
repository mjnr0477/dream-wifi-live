# Dream Wi-Fi source reachability diagnostic

This diagnostic is the first step toward automatic source enrollment. Run it on a device that is connected to the router/network whose internet you want Dream Wi-Fi to use.

```sh
node scripts/dream-wifi-source-diagnostic.js
```

It reports the detected private/local default gateway, non-loopback local interface addresses, DNS resolution, and whether a TCP connection to `example.com:443` succeeds. Set `DREAM_WIFI_SOURCE_NAME` to give the source a friendly label; otherwise the host name is used.

The diagnostic is read-only: it does not scan arbitrary IP ranges, log into router administration, change router settings, or bypass captive portals. The result confirms the source device's local route and public internet path; it does **not** yet mean that a router has registered with the hosted tunnel or that the phone's VPN traffic is being relayed end-to-end.

A router that is not reachable from the internet still needs an authorized source agent on its local network, or a supported router client, to create an outbound secure tunnel. This diagnostic checks that initial local network path before tunnel registration is attempted.
