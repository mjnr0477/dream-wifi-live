"use strict";

const assert = require("node:assert/strict");
const crypto = require("node:crypto");
const http = require("node:http");
const net = require("node:net");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { WebSocket } = require("ws");

function reservePort() {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const port = server.address().port;
      server.close(error => error ? reject(error) : resolve(port));
    });
  });
}

function startApp(port, supabaseUrl) {
  const child = spawn(process.execPath, [path.join(__dirname, "..", "server.js")], {
    env: {
      ...process.env,
      PORT: String(port),
      DREAM_WIFI_BIND_HOST: "127.0.0.1",
      SUPABASE_URL: supabaseUrl,
      SUPABASE_SERVICE_ROLE_KEY: "server-side-test-key"
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  let output = "";
  const started = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error("App startup timed out: " + output)), 10000);
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
        reject(new Error("App exited before startup (code " + code + "): " + output));
      }
    });
  });
  return { child, started, get output() { return output; } };
}

async function stopApp(child) {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill("SIGTERM");
  await new Promise(resolve => {
    child.once("exit", resolve);
    setTimeout(resolve, 2500);
  });
}

async function main() {
  const appPort = await reservePort();
  const dbPort = await reservePort();
  const records = {
    dream_wifi_source_credentials: [],
    dream_wifi_sources: []
  };
  const dbServer = http.createServer(async (req, res) => {
    const table = new URL(req.url, "http://localhost").pathname.split("/").pop();
    if (req.headers.apikey !== "server-side-test-key" ||
        req.headers.authorization !== "Bearer server-side-test-key") {
      res.writeHead(401, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ message: "invalid server key" }));
      return;
    }
    if (!Object.hasOwn(records, table)) {
      res.writeHead(404); res.end(); return;
    }
    if (req.method === "GET") {
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify(records[table]));
      return;
    }
    if (req.method === "POST") {
      let raw = "";
      for await (const chunk of req) raw += chunk;
      const input = JSON.parse(raw || "null");
      const rows = Array.isArray(input) ? input : [input];
      const key = table === "dream_wifi_source_credentials" ? "token_hash" : "id";
      for (const row of rows) {
        const index = records[table].findIndex(existing => existing[key] === row[key]);
        if (index >= 0) records[table][index] = { ...records[table][index], ...row };
        else records[table].push(row);
      }
      res.writeHead(201); res.end(); return;
    }
    res.writeHead(405); res.end();
  });
  await new Promise(resolve => dbServer.listen(dbPort, "127.0.0.1", resolve));
  const supabaseUrl = "http://127.0.0.1:" + dbPort;
  let app = startApp(appPort, supabaseUrl);
  let sourceSocket;
  try {
    await app.started;
    let base = "http://127.0.0.1:" + appPort;
    let health = await (await fetch(base + "/healthz")).json();
    assert.equal(health.database, true);
    assert.equal(health.durable_storage_ready, true);

    const enrollResponse = await fetch(base + "/api/dream-wifi/source-enroll", { method: "POST" });
    assert.equal(enrollResponse.status, 201);
    const enrollment = await enrollResponse.json();
    assert.ok(enrollment.source_id);
    assert.ok(enrollment.source_token);
    const hash = crypto.createHash("sha256").update(enrollment.source_token).digest("hex");
    assert.ok(records.dream_wifi_source_credentials.some(row =>
      row.source_id === enrollment.source_id && row.token_hash === hash
    ));
    assert.ok(!records.dream_wifi_source_credentials.some(row => row.token_hash === enrollment.source_token));

    await stopApp(app.child);
    app = startApp(appPort, supabaseUrl);
    await app.started;
    base = "http://127.0.0.1:" + appPort;
    health = await (await fetch(base + "/healthz")).json();
    assert.equal(health.database, true);
    assert.equal(health.durable_storage_ready, true);

    sourceSocket = new WebSocket("ws://127.0.0.1:" + appPort + "/api/dream-wifi/tunnel");
    await new Promise((resolve, reject) => {
      sourceSocket.once("open", resolve);
      sourceSocket.once("error", reject);
    });
    const registered = new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error("Restored source credential was not accepted.")), 5000);
      sourceSocket.on("message", raw => {
        const message = JSON.parse(raw.toString());
        if (message.type === "registered" && message.role === "source") {
          clearTimeout(timer); resolve(message);
        }
      });
    });
    sourceSocket.send(JSON.stringify({
      type: "register", role: "source", sourceId: enrollment.source_id,
      sourceToken: enrollment.source_token, sourceName: "Durable integration source",
      latitude: -1.29, longitude: 36.82
    }));
    assert.equal((await registered).sourceId, enrollment.source_id);
    await fetch(base + "/api/wifi/sources?latitude=-1.29&longitude=36.82&radius_km=10");
    assert.ok(records.dream_wifi_sources.some(row => row.id === enrollment.source_id));
    console.log("Dream Wi-Fi Supabase app integration passed: startup readiness, hashed credential persistence, restart restoration, source authentication, and source-directory persistence.");
  } finally {
    if (sourceSocket && sourceSocket.readyState < WebSocket.CLOSING) sourceSocket.close();
    await stopApp(app.child);
    await new Promise(resolve => dbServer.close(resolve));
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
