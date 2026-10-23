"use strict";

function createDreamWifiSupabaseStore(options = {}) {
  const url = String(options.url !== undefined ? options.url : (process.env.SUPABASE_URL || "")).trim().replace(/\/$/, "");
  const serviceKey = String(options.serviceKey !== undefined ? options.serviceKey : (process.env.SUPABASE_SERVICE_ROLE_KEY || "")).trim();
  const fetchImpl = options.fetch || globalThis.fetch;
  const hasAnyConfig = Boolean(url || serviceKey);
  const configured = Boolean(url && serviceKey);
  let ready = false;

  function endpoint(table, query = "") {
    return url + "/rest/v1/" + table + (query ? "?" + query : "");
  }
  async function request(table, query, init = {}) {
    if (!configured) throw new Error("Supabase server credentials are not configured.");
    const response = await fetchImpl(endpoint(table, query), {
      ...init,
      headers: {
        apikey: serviceKey,
        Authorization: "Bearer " + serviceKey,
        "Content-Type": "application/json",
        ...(init.headers || {})
      },
      signal: AbortSignal.timeout(8000)
    });
    if (!response.ok) {
      const detail = (await response.text()).slice(0, 300);
      throw new Error("Supabase " + table + " request failed (" + response.status + "): " + detail);
    }
    if (response.status === 204) return null;
    const text = await response.text();
    return text ? JSON.parse(text) : null;
  }

  async function initialize() {
    if (!configured) return { configured: false, ready: false, credentials: [], sources: [] };
    // Verify both tables are reachable before advertising durable storage.
    async function loadPages(table, columns, maximumRows) {
      const rows = [];
      const pageSize = 1000;
      for (let offset = 0; offset < maximumRows; offset += pageSize) {
        const page = await request(table, "select=" + columns + "&order=" + (table === "dream_wifi_source_credentials" ? "token_hash" : "id") + ".asc&limit=" + pageSize, {
          headers: { Range: offset + "-" + (offset + pageSize - 1) }
        });
        const batch = Array.isArray(page) ? page : [];
        rows.push(...batch);
        if (batch.length < pageSize) break;
      }
      return rows;
    }
    const [credentials, sources] = await Promise.all([
      loadPages("dream_wifi_source_credentials", "source_id,token_hash,created_at", 10000),
      loadPages("dream_wifi_sources", "id,name,latitude,longitude,first_seen,last_seen", 1000)
    ]);
    const validCredentials = (Array.isArray(credentials) ? credentials : []).filter(row =>
      row && typeof row.source_id === "string" &&
      /^[a-zA-Z0-9_-]{1,80}$/.test(row.source_id) &&
      typeof row.token_hash === "string" && /^[a-f0-9]{64}$/.test(row.token_hash)
    ).map(row => ({
      sourceId: row.source_id,
      tokenHash: row.token_hash,
      createdAt: row.created_at ? Date.parse(row.created_at) || Date.now() : Date.now()
    }));
    const validSources = (Array.isArray(sources) ? sources : []).filter(row =>
      row && typeof row.id === "string" && /^[a-zA-Z0-9_-]{1,80}$/.test(row.id)
    ).map(row => ({
      id: row.id, name: row.name || "Dream Wi-Fi source",
      latitude: row.latitude, longitude: row.longitude,
      first_seen: row.first_seen, last_seen: row.last_seen
    }));
    ready = true;
    return { configured: true, ready, credentials: validCredentials, sources: validSources };
  }

  async function saveCredential(credential) {
    if (!ready) throw new Error("Supabase durable storage is not ready.");
    if (!credential || !/^[a-zA-Z0-9_-]{1,80}$/.test(String(credential.sourceId || "")) ||
        !/^[a-f0-9]{64}$/.test(String(credential.tokenHash || ""))) {
      throw new Error("Invalid source credential record.");
    }
    await request("dream_wifi_source_credentials", "on_conflict=token_hash", {
      method: "POST",
      headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify({
        source_id: credential.sourceId,
        token_hash: credential.tokenHash,
        created_at: new Date(credential.createdAt || Date.now()).toISOString()
      })
    });
  }

  async function saveSources(sources) {
    if (!ready) throw new Error("Supabase durable storage is not ready.");
    const rows = (Array.isArray(sources) ? sources : []).slice(-1000).filter(source => {
      if (!source || !/^[a-zA-Z0-9_-]{1,80}$/.test(String(source.id || ""))) return false;
      if (source.latitude === null || source.latitude === "" || source.longitude === null || source.longitude === "") return false;
      const latitude = Number(source.latitude), longitude = Number(source.longitude);
      return Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
        Number.isFinite(longitude) && longitude >= -180 && longitude <= 180;
    }).map(source => ({
      id: source.id,
      name: String(source.name || "Dream Wi-Fi source").slice(0, 80),
      latitude: Number(source.latitude),
      longitude: Number(source.longitude),
      first_seen: source.first_seen || new Date().toISOString(),
      last_seen: source.last_seen || new Date().toISOString()
    }));
    if (!rows.length) return;
    await request("dream_wifi_sources", "on_conflict=id", {
      method: "POST",
      headers: { Prefer: "resolution=merge-duplicates,return=minimal" },
      body: JSON.stringify(rows)
    });
  }

  return {
    configured,
    hasAnyConfig,
    get ready() { return ready; },
    initialize,
    saveCredential,
    saveSources
  };
}

module.exports = { createDreamWifiSupabaseStore };
