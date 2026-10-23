"use strict";

const assert = require("node:assert/strict");
const { createDreamWifiSupabaseStore } = require("../product/dream_wifi_supabase_store");

async function main() {
  const calls = [];
  const fixtures = {
    dream_wifi_source_credentials: [{
      source_id: "persistedsource1",
      token_hash: "a".repeat(64),
      created_at: "2026-10-23T10:00:00.000Z"
    }],
    dream_wifi_sources: [{
      id: "persistedsource1", name: "Saved source",
      latitude: -1.29, longitude: 36.82,
      first_seen: "2026-10-20T10:00:00.000Z",
      last_seen: "2026-10-23T10:00:00.000Z"
    }]
  };
  const fetch = async (url, init) => {
    calls.push({ url, init });
    const table = url.split("/rest/v1/")[1].split("?")[0];
    if (init.method === "POST") return {
      ok: true, status: 201, text: async () => ""
    };
    const rows = fixtures[table] || [];
    return { ok: true, status: 200, text: async () => JSON.stringify(rows) };
  };

  const missing = createDreamWifiSupabaseStore({ url: "", serviceKey: "", fetch });
  assert.deepEqual(await missing.initialize(), { configured: false, ready: false, credentials: [], sources: [] });
  assert.equal(missing.ready, false);
  const partial = createDreamWifiSupabaseStore({ url: "https://example.supabase.co", serviceKey: "", fetch });
  assert.equal(partial.configured, false);
  assert.equal(partial.hasAnyConfig, true);

  const store = createDreamWifiSupabaseStore({
    url: "https://example.supabase.co/",
    serviceKey: "server-only-test-key",
    fetch
  });
  const loaded = await store.initialize();
  assert.equal(loaded.ready, true);
  assert.equal(loaded.credentials.length, 1);
  assert.equal(loaded.credentials[0].sourceId, "persistedsource1");
  assert.equal(loaded.sources[0].name, "Saved source");

  await store.saveCredential({ sourceId: "newsource1", tokenHash: "b".repeat(64), createdAt: 1792750000000 });
  await store.saveSources([{
    id: "newsource1", name: "New source", latitude: -1.2, longitude: 36.8,
    first_seen: "2026-10-23T10:00:00.000Z", last_seen: "2026-10-23T10:00:00.000Z"
  }]);
  assert.equal(calls.length, 4);
  assert.ok(calls.every(call => call.init.headers.apikey === "server-only-test-key"));
  assert.ok(calls.every(call => call.init.headers.Authorization === "Bearer server-only-test-key"));
  const credentialWrite = calls.find(call => call.init.method === "POST" && call.url.includes("dream_wifi_source_credentials"));
  assert.equal(JSON.parse(credentialWrite.init.body).token_hash, "b".repeat(64));
  const sourceWrite = calls.find(call => call.init.method === "POST" && call.url.includes("dream_wifi_sources"));
  assert.equal(JSON.parse(sourceWrite.init.body)[0].id, "newsource1");

  const failing = createDreamWifiSupabaseStore({
    url: "https://example.supabase.co", serviceKey: "test",
    fetch: async () => ({ ok: false, status: 401, text: async () => "denied" })
  });
  await assert.rejects(failing.initialize(), /401/);
  assert.equal(failing.ready, false);
  console.log("Dream Wi-Fi Supabase durable-store tests passed.");
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
