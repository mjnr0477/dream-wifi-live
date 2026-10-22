"use strict";
const assert = require("node:assert/strict");
const { buildOverpassQuery, distanceKm, normalizeElements } = require("../product/dream_wifi_public_hotspots");

const query = buildOverpassQuery(-0.56, 34.56, 300);
assert.match(query, /around:300000,-0\.56,34\.56/);
assert.match(query, /internet_access/);
assert.throws(() => buildOverpassQuery(91, 0, 20), /latitude/);
assert.throws(() => buildOverpassQuery(0, 0, 301), /radiusKm/);
assert.ok(distanceKm(-0.56, 34.56, -0.56, 34.56) < 0.001);

const data = normalizeElements([
  { type: "node", id: 1, lat: -0.56, lon: 34.56, tags: { name: "Library", wifi: "yes", amenity: "library" } },
  { type: "way", id: 2, center: { lat: -0.57, lon: 34.57 }, tags: { name: "Cafe", internet_access: "wlan" } },
  { type: "node", id: 3, lat: -0.56, lon: 34.56, tags: { name: "No Wi-Fi tag", amenity: "cafe" } },
  { type: "node", id: 4, lat: 20, lon: 20, tags: { wifi: "yes" } },
  { type: "node", id: 5, lat: -0.56, lon: 34.56, tags: { wifi: "yes" } }
], -0.56, 34.56, 10);
assert.equal(data.length, 3);
assert.equal(data[0].name, "Library");
assert.equal(data.every(item => item.connectable === false), true);
assert.match(data[0].map_url, /openstreetmap\.org/);
console.log("Dream Wi-Fi public hotspot discovery smoke test passed.");
