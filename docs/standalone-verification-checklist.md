# Standalone verification checklist

- [ ] GitHub Actions `Dream Wi-Fi verification` passes on pull request.
- [ ] Configure private host environment variables; never commit secrets.
- [ ] Confirm the owner sign-in and source ticket endpoints.
- [ ] Connect an authorized Quant source device and confirm the tunnel source is online.
- [ ] Test real traffic from a remote client; confirm it exits through the authorized source network.
- [ ] Keep paid packages disabled until end-to-end testing and provider permission are confirmed.
