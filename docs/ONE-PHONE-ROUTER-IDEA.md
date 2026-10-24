# Dream Wi-Fi: one-phone router-discovery track

This track is isolated on `idea/500km-one-phone-router-discovery`. It must not modify the published production deployment.

## Target
- One Android phone scans physical Wi-Fi networks and sorts scan results by measured RSSI (strongest first).
- The app shows the currently active network's Android-reported internet validation state.
- With location permission, it queries mapped Wi-Fi venue records up to 500 km from the phone's last known location.
- Background location discovery is opt-in and subject to Android foreground-service, battery, and permission restrictions.
- Joining networks remains under Android's user-approved Wi-Fi controls. Dream Wi-Fi must not bypass passwords or access controls.

## Truthful limits
- A phone's Wi-Fi radio cannot scan routers hundreds of kilometres away.
- RSSI is available only for physically detectable Wi-Fi radios. Public map entries are venue records, not live router telemetry.
- Remote use of a distant router requires that router/network owner to configure a secure, reachable remote-access gateway and authorize the user. The public hotspot directory does not provide that capability.
- No test should report success unless the phone actually joins a network and a real internet request succeeds.

## Acceptance gates
1. CI builds the Android debug APK from this branch.
2. On a physical Android phone, permission grant and scan show real SSIDs (or clearly report Android restrictions/no results); displayed dBm values come from Android scan results.
3. A mapped-place query with location returns results only as unverified map listings and handles empty/offline API responses.
4. Current internet status distinguishes Android's validated network from merely having a network interface.
5. Test local connection to an authorized Wi-Fi network and verify an HTTPS request; test a secured network without permission and confirm the app does not bypass it.
6. Test background behavior on screen-off and after network/location changes; document device-specific battery and permission restrictions.
7. Remote-router access is a separate gate requiring a real authorized remote gateway plus end-to-end HTTPS proof. Do not mark the 500 km router-access idea achieved before this passes.

The CI artifact is a development/debug build only, not a production release.
