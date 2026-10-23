"use strict";

const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const net = require("node:net");
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

async function main() {
  const port = await reservePort();
  const env = {
    ...process.env,
    PORT: String(port),
    DREAM_WIFI_BIND_HOST: "127.0.0.1",
    DREAM_WIFI_SOURCE_TOKEN: "storage-gate-test-token"
  };
  delete env.DREAM_WIFI_DATA_DIR;
  delete env.DREAM_WIFI_STORAGE_READY;

  const child = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
    env,
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  const started = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("Test server startup timed out: " + output)), 10000);
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
        reject(new Error("Test server exited early: " + output));
      }
    });
  });

  try {
    await started;
    const base = "http://127.0.0.1:" + port;
    const configResponse = await fetch(base + "/api/dream-wifi/config");
    assert.equal(configResponse.status, 200);
    const config = await configResponse.json();
    assert.equal(config.source_enrollment, true, "The enrollment endpoint remains present.");
    assert.equal(config.durable_storage_ready, false, "Storage must default to not-ready.");

    const enrollmentResponse = await fetch(base + "/api/dream-wifi/source-enroll", { method: "POST" });
    assert.equal(enrollmentResponse.status, 503, "Enrollment must fail closed without explicit durable-storage configuration.");
    const result = await enrollmentResponse.json();
    assert.equal(result.success, false);
    assert.match(result.error, /durable storage/i);
    console.log("Dream Wi-Fi durable-storage readiness gate test passed.");
  } finally {
    child.kill("SIGTERM");
    await new Promise(resolve => {
      if (child.exitCode !== null || child.signalCode !== null) return resolve();
      child.once("exit", resolve);
      setTimeout(resolve, 2000);
    });
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
