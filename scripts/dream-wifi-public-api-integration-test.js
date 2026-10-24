"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const net = require("node:net");

function reservePort() {
  return new Promise((resolve, reject) => {
    const listener = net.createServer();
    listener.once("error", reject);
    listener.listen(0, "127.0.0.1", () => {
      const port = listener.address().port;
      listener.close(error => error ? reject(error) : resolve(port));
    });
  });
}

function waitForStartup(child, timeoutMs = 10000) {
  return new Promise((resolve, reject) => {
    let output = "";
    const timer = setTimeout(() => reject(new Error("Test server did not start in time. Output: " + output)), timeoutMs);
    child.stdout.on("data", chunk => {
      output += chunk.toString();
      if (output.includes("Dream Wi-Fi listening on port")) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.stderr.on("data", chunk => { output += chunk.toString(); });
    child.once("error", error => { clearTimeout(timer); reject(error); });
    child.once("exit", (code, signal) => {
      if (code !== null && code !== 0) {
        clearTimeout(timer);
        reject(new Error("Test server exited before startup (code " + code + ", signal " + signal + "). Output: " + output));
      }
    });
  });
}

async function main() {
  const port = await reservePort();
  const dataDir = fs.mkdtempSync(path.join(os.tmpdir(), "dream-wifi-api-test-"));
  const preload = path.join(__dirname, "mock-overpass-fetch.js");
  const serverPath = path.join(__dirname, "..", "server.js");
  const child = spawn(process.execPath, [serverPath], {
    env: {
      ...process.env,
      PORT: String(port),
      DREAM_WIFI_BIND_HOST: "127.0.0.1",
      DREAM_WIFI_SOURCE_TOKEN: "integration-test-source-token",
      DREAM_WIFI_DATA_DIR: dataDir,
      DREAM_WIFI_STORAGE_READY: "true",
      // This test exercises the durable-disk fallback; do not inherit production Supabase credentials.
      SUPABASE_URL: "",
      SUPABASE_SERVICE_ROLE_KEY: "",
      NODE_OPTIONS: "--require=" + preload
    },
    stdio: ["ignore", "pipe", "pipe"]
  });

  try {
    await waitForStartup(child);
    const base = "http://127.0.0.1:" + port;
    const healthResponse = await fetch(base + "/healthz", { signal: AbortSignal.timeout(5000) });
    assert.equal(healthResponse.status, 200);
    assert.deepEqual(await healthResponse.json(), { ok: true, service: "dream-wifi-live", database: false, durable_storage_ready: true });

    const configResponse = await fetch(base + "/api/dream-wifi/config");
    assert.equal(configResponse.status, 200);
    const config = await configResponse.json();
    assert.equal(config.configured, true);
    assert.equal(config.database, false);
    assert.equal(config.authentication, "one-time-source-pairing");
    assert.equal(config.source_enrollment, true);
    assert.equal(config.durable_storage_ready, true);

    const missingLocation = await fetch(base + "/api/wifi/sources");
    assert.equal(missingLocation.status, 400);

    const sourceResponse = await fetch(base + "/api/wifi/sources?latitude=-0.56&longitude=34.56&radius_km=500");
    assert.equal(sourceResponse.status, 200);
    const sources = await sourceResponse.json();
    assert.equal(sources.success, true);
    assert.equal(sources.count, 0);
    assert.equal(sources.online_count, 0);
    assert.equal(sources.radius_km, 500);

    const invalidRadius = await fetch(base + "/api/wifi/hotspots?latitude=-0.56&longitude=34.56&radius_km=501");
    assert.equal(invalidRadius.status, 400);

    const hotspotResponse = await fetch(base + "/api/wifi/hotspots?latitude=-0.56&longitude=34.56&radius_km=500");
    assert.equal(hotspotResponse.status, 200);
    const hotspots = await hotspotResponse.json();
    assert.equal(hotspots.success, true);
    assert.equal(hotspots.radius_km, 500);
    assert.equal(hotspots.provider, "OpenStreetMap");
    assert.equal(hotspots.count, 1);
    assert.equal(hotspots.hotspots[0].name, "Integration Test Library");
    assert.equal(hotspots.hotspots[0].connectable, false);

    const pageResponse = await fetch(base + "/");
    assert.equal(pageResponse.status, 200);
    const page = await pageResponse.text();
    assert.match(page, /Internet, <em>without the distance/);
    assert.match(page, /value="500" selected/);
    assert.match(page, /src="\/app\.js"/);

    console.log("Dream Wi-Fi public API integration test passed (startup, health, public config, location validation, source directory, hotspot route, and page delivery).");
  } finally {
    child.kill("SIGTERM");
    await new Promise(resolve => {
      if (child.exitCode !== null || child.signalCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 2000);
    });
    fs.rmSync(dataDir, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
