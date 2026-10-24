"use strict";
const assert = require("node:assert/strict");
const { buildOverpassQuery, distanceKm, normalizeElements, fetchPublicHotspots } = require("../product/dream_wifi_public_hotspots");

async function main() {
  const query = buildOverpassQuery(-0.56, 34.56, 500;
  assert.match(query, /around:500000,-0\.56,34\.56/);
  assert.match(query, /internet_access/);
  assert.match(query, /\["wifi"~/);
  assert.throws(() => buildOverpassQuery(91, 0, 20), /latitude/);
  assert.throws(() => buildOverpassQuery(0, 0, 501, /radiusKm/);
  assert.throws(() => buildOverpassQuery(0, 0, 0), /radiusKm/);
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

  let calls = [];
  const successFetch = async (url, options) => {
    calls.push({ url, options });
    return {
      ok: true,
      status: 200,
      json: async () => ({ elements: [{ type: "node", id: 111, lat: 12.345, lon: 45.678, tags: { name: "Mock Library", wifi: "yes", amenity: "library" } }] })
    };
  };
  const now = () => 1000000;
  const first = await fetchPublicHotspots({ latitude: 12.345, longitude: 45.678, radiusKm: 3, fetchImpl: successFetch, now });
  assert.equal(first.success, true);
  assert.equal(first.count, 1);
  assert.equal(first.hotspots[0].name, "Mock Library");
  assert.equal(first.cached, false);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].options.method, "POST");
  assert.match(calls[0].options.body.get("data"), /around:3000,12\.345,45\.678/);

  const cached = await fetchPublicHotspots({
    latitude: 12.345, longitude: 45.678, radiusKm: 3,
    fetchImpl: async () => { throw new Error("Cache should avoid a network call"); }, now
  });
  assert.equal(cached.cached, true);

  calls = [];
  const fallback = await fetchPublicHotspots({
    latitude: 13.456, longitude: 46.789, radiusKm: 4, now,
    fetchImpl: async (url) => {
      calls.push(url);
      if (calls.length === 1) return { ok: false, status: 429, json: async () => ({}) };
      return { ok: true, status: 200, json: async () => ({ elements: [] }) };
    }
  });
  assert.equal(calls.length, 2);
  assert.equal(fallback.success, true);
  assert.equal(fallback.count, 0);

  calls = [];
  await assert.rejects(fetchPublicHotspots({
    latitude: 14.567, longitude: 47.891, radiusKm: 5, now,
    fetchImpl: async url => { calls.push(url); throw new Error("provider offline"); }
  }), /temporarily unavailable/);
  assert.equal(calls.length, 5, "All public map endpoints should be attempted before reporting an outage.");

  console.log("Dream Wi-Fi public hotspot discovery smoke test passed (validation, filtering, cache, fallback, outage handling).");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
