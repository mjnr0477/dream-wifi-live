# Standalone Android client verification

The Android build workflow fetches the pinned upstream TUN-to-SOCKS engine and compiles the APK.

Before distributing a build:
1. Set the GitHub repository variable `DREAM_WIFI_BASE_URL` to the deployed standalone service base URL (HTTPS, no trailing slash).
2. Confirm the standalone service environment is configured and `/healthz` responds.
3. Install the APK on a test phone and verify owner sign-in, source ticket renewal, and VPN consent.
4. Test on an authorized Quant-connected source and a separate remote phone.
5. Do not enable package sales until end-to-end traffic and provider permission are confirmed.
