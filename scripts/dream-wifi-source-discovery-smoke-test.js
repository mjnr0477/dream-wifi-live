"use strict";

const assert = require("node:assert/strict");
const {
  isPrivateOrLocalAddress,
  parseDefaultGateway,
  diagnoseSourceNetwork
} = require("../product/dream_wifi_source_discovery");

assert.equal(parseDefaultGateway("default via 192.168.1.1 dev wlan0 proto dhcp metric 600"), "192.168.1.1");
assert.equal(parseDefaultGateway("default via 10.10.0.1 dev wlan0"), "10.10.0.1");
assert.equal(parseDefaultGateway("default dev tun0"), null);
assert.equal(parseDefaultGateway("default via 8.8.8.8 dev eth0"), null);
assert.equal(parseDefaultGateway("default via 999.1.1.1 dev wlan0"), null);
assert.equal(isPrivateOrLocalAddress("192.168.0.1"), true);
assert.equal(isPrivateOrLocalAddress("10.0.0.1"), true);
assert.equal(isPrivateOrLocalAddress("172.20.0.1"), true);
assert.equal(isPrivateOrLocalAddress("8.8.8.8"), false);
assert.equal(isPrivateOrLocalAddress("127.0.0.1"), true);
assert.equal(isPrivateOrLocalAddress("2001:4860:4860::8888"), false);

(async () => {
  const result = await diagnoseSourceNetwork({
    gateway: "192.168.1.1",
    interfaces: [{ interface: "wlan0", family: "IPv4", address: "192.168.1.20" }],
    probeTcp: async () => ({ success: true })
  });
  assert.equal(result.gatewayDetected, true);
  assert.equal(result.gateway, "192.168.1.1");
  assert.equal(Array.isArray(result.localInterfaces), true);
  assert.equal(typeof result.internet.dnsResolved, "boolean");
  assert.equal(typeof result.internet.tcp443Reachable, "boolean");
  console.log("Dream Wi-Fi source discovery smoke test passed.");
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
