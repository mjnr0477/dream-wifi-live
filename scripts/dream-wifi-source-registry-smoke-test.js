"use strict";

const assert = require("node:assert/strict");
const { normalizeObservation, ACCESS_STATES } = require("../product/dream_wifi_source_registry");

const valid = normalizeObservation({
  ssid: "  Kibabii Guest WiFi  ",
  latitude: 0.5698,
  longitude: 34.5584,
  gateway_detected: true,
  internet_verified: true,
  access_state: "captive_portal",
  observed_at: new Date().toISOString(),
  bssid: "AA:BB:CC:DD:EE:FF",
  password: "must-not-be-stored"
}, "user-123");

assert.equal(valid.error, undefined);
assert.equal(valid.record.ssid, "Kibabii Guest WiFi");
assert.equal(valid.record.latitude, 0.57);
assert.equal(valid.record.longitude, 34.558);
assert.equal(valid.record.gateway_detected, true);
assert.equal(valid.record.internet_verified, true);
assert.equal(valid.record.access_state, "captive_portal");
assert.equal(valid.record.remote_access_ready, false);
assert.equal(Object.hasOwn(valid.record, "bssid"), false);
assert.equal(Object.hasOwn(valid.record, "password"), false);
assert.match(valid.record.network_fingerprint, /^[a-f0-9]{64}$/);
assert.equal(ACCESS_STATES.has("authorized"), true);

assert.match(normalizeObservation({ ssid: "", latitude: 0, longitude: 0 }, "user-123").error, /SSID/);
assert.match(normalizeObservation({ ssid: "x", latitude: 200, longitude: 0 }, "user-123").error, /latitude/);
assert.equal(normalizeObservation({ ssid: "x", latitude: 0, longitude: 0, gateway_detected: false, internet_verified: true }, "user-123").record.internet_verified, false);


async function verifyRoutes() {
  const routes = {};
  const app = {
    post(path, handler) { routes["POST " + path] = handler; },
    get(path, handler) { routes["GET " + path] = handler; }
  };
  let inserted = null;
  const sampleRow = {
    id: 7, ssid: "Kibabii Guest WiFi", latitude: 0.57, longitude: 34.558,
    gateway_detected: true, internet_verified: true, access_state: "authorized",
    observed_at: new Date().toISOString(), remote_access_ready: false
  };
  const supabaseAuth = { auth: { async getUser(token) {
    return token === "valid-session"
      ? { data: { user: { id: "user-123" } }, error: null }
      : { data: { user: null }, error: new Error("invalid token") };
  } } };
  const supabase = {
    from(table) {
      assert.equal(table, "dream_wifi_source_observations");
      const builder = {
        insert(record) { inserted = record; return this; },
        select() { return this; },
        single() { return Promise.resolve({ data: { ...sampleRow, ...inserted, id: 7 }, error: null }); },
        gte() { return this; }, lte() { return this; }, order() { return this; }, limit() { return this; },
        then(resolve, reject) { return Promise.resolve({ data: [sampleRow], error: null }).then(resolve, reject); }
      };
      return builder;
    }
  };
  const { registerDreamWifiSourceRegistryRoutes } = require("../product/dream_wifi_source_registry");
  registerDreamWifiSourceRegistryRoutes(app, { supabaseAuth, supabase });
  function response() {
    return {
      statusCode: 200, payload: null,
      set() { return this; },
      status(code) { this.statusCode = code; return this; },
      json(payload) { this.payload = payload; return this; }
    };
  }
  const postRes = response();
  await routes["POST /api/dream-wifi/registry/observations"]({
    get: () => "Bearer valid-session",
    body: { ssid: "Kibabii Guest WiFi", latitude: 0.57, longitude: 34.558,
      gateway_detected: true, internet_verified: true, access_state: "authorized" }
  }, postRes);
  assert.equal(postRes.statusCode, 201);
  assert.equal(postRes.payload.success, true);
  assert.equal(inserted.reporter_user_id, "user-123");
  assert.equal(inserted.remote_access_ready, false);

  const getRes = response();
  await routes["GET /api/dream-wifi/registry/observations"]({
    get: () => "Bearer valid-session",
    query: { latitude: "0.57", longitude: "34.558", radius_km: "25" }
  }, getRes);
  assert.equal(getRes.statusCode, 200);
  assert.equal(getRes.payload.count, 1);
  assert.equal(getRes.payload.observations[0].ssid, "Kibabii Guest WiFi");

  const unauthorizedRes = response();
  await routes["GET /api/dream-wifi/registry/observations"]({
    get: () => "Bearer invalid-session",
    query: { latitude: "0.57", longitude: "34.558", radius_km: "25" }
  }, unauthorizedRes);
  assert.equal(unauthorizedRes.statusCode, 401);
}

verifyRoutes().then(() => {
  console.log("Dream Wi-Fi source registry route smoke test passed.");
}).catch(error => {
  console.error(error);
  process.exitCode = 1;
});

