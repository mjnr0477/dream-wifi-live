"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const fs = require("node:fs");
const net = require("node:net");
const os = require("node:os");
const path = require("node:path");

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

async function checkBlockedCase(name, options = {}) {
  const port = await reservePort();
  const env = {
    ...process.env,
    PORT: String(port),
    DREAM_WIFI_BIND_HOST: "127.0.0.1",
    DREAM_WIFI_SOURCE_TOKEN: "storage-gate-test-token"
  };
  delete env.DREAM_WIFI_DATA_DIR;
  delete env.DREAM_WIFI_STORAGE_READY;
  if (options.dataDir) env.DREAM_WIFI_DATA_DIR = options.dataDir;
  if (options.ready === true) env.DREAM_WIFI_STORAGE_READY = "true";

  const child = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
    env,
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  const started = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(name + ": startup timed out: " + output)), 10000);
    child.stdout.on("data", chunk => {
      output += chunk.toString();
      if (output.includes("Dream Wi-Fi listening on port")) {
        clearTimeout(timer);
        resolve();
      }
    });
    child.stderr.on("data", chunk => { output += chunk.toString(); });
    child.once("error", error => { clearTimeout(timer); reject(error); });
    child.once("exit", code => {
      if (code !== null && code !== 0) {
        clearTimeout(timer);
        reject(new Error(name + ": server exited early: " + output));
      }
    });
  });

  try {
    await started;
    const base = "http://127.0.0.1:" + port;
    const configResponse = await fetch(base + "/api/dream-wifi/config");
    assert.equal(configResponse.status, 200, name + ": config endpoint should respond");
    const config = await configResponse.json();
    assert.equal(config.source_enrollment, true, name + ": enrollment endpoint remains present");
    assert.equal(config.durable_storage_ready, false, name + ": readiness must be false");

    const enrollmentResponse = await fetch(base + "/api/dream-wifi/source-enroll", { method: "POST" });
    assert.equal(enrollmentResponse.status, 503, name + ": enrollment must fail closed");
    const result = await enrollmentResponse.json();
    assert.equal(result.success, false, name + ": enrollment result must be unsuccessful");
    assert.match(result.error, /durable storage/i, name + ": error should explain storage requirement");
  } finally {
    child.kill("SIGTERM");
    await new Promise(resolve => {
      if (child.exitCode !== null || child.signalCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 2000);
    });
  }
}

async function main() {
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), "dream-wifi-storage-gate-"));
  try {
    await checkBlockedCase("neither storage path nor readiness flag");
    await checkBlockedCase("readiness flag without storage path", { ready: true });
    await checkBlockedCase("storage path without readiness flag", { dataDir: tempRoot });
    console.log("Dream Wi-Fi durable-storage readiness gate tests passed for all missing-configuration cases.");
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true });
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
