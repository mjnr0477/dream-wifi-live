"use strict";
const assert = require("node:assert/strict");
const { isPublicAddress } = require("../product/dream_wifi_public_ip");

for (const address of [
  "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.1.1",
  "172.16.0.1", "192.0.0.9", "192.0.2.1", "192.88.99.1", "192.168.1.1",
  "198.18.0.1", "198.51.100.10", "203.0.113.1", "224.0.0.1", "255.255.255.255",
  "::", "::1", "::ffff:8.8.8.8", "fc00::1", "fe80::1", "ff02::1",
  "2001:db8::1", "2001:0::1", "2001:1::1", "2002::1", "3fff::1"
]) {
  assert.equal(isPublicAddress(address), false, address + " must not be relayed");
}
for (const address of ["8.8.8.8", "1.1.1.1", "192.0.1.1", "2001:4860:4860::8888"]) {
  assert.equal(isPublicAddress(address), true, address + " is expected to be globally routable");
}
assert.equal(isPublicAddress("not-an-ip"), false);
console.log("Dream Wi-Fi public destination IP safety tests passed.");
