"use strict";

const assert = require("node:assert/strict");
const http = require("node:http");
const { WebSocket } = require("ws");
const { attachDreamWifiTunnel } = require("../product/dream_wifi_tunnel_engine");

function waitOpen(socket) {
  return new Promise((resolve, reject) => {
    socket.once("open", resolve);
    socket.once("error", reject);
  });
}
function waitMessage(socket, predicate, timeoutMs = 2500) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error("Timed out waiting for expected WebSocket message."));
    }, timeoutMs);
    function onMessage(raw) {
      let message;
      try { message = JSON.parse(raw.toString("utf8")); } catch (_) { return; }
      if (!predicate(message)) return;
      clearTimeout(timer);
      socket.off("message", onMessage);
      resolve(message);
    }
    socket.on("message", onMessage);
  });
}
async function registerSource(url, sourceId, sourceName, sourceToken = "shared-test-token") {
  const socket = new WebSocket(url);
  await waitOpen(socket);
  const registered = waitMessage(socket, message => message.type === "registered");
  socket.send(JSON.stringify({
    type: "register", role: "source", sourceToken, sourceId, sourceName
  }));
  const result = await registered;
  assert.equal(result.sourceId, sourceId);
  return socket;
}
async function main() {
  const server = http.createServer((req, res) => { res.writeHead(200); res.end("ok"); });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const wss = attachDreamWifiTunnel(server, {
    sourceToken: "shared-test-token",
    supabaseAuth: { auth: { async getUser(token) {
      return token === "valid-owner-token"
        ? { data: { user: { id: "owner-1", email: "owner@example.test" } }, error: null }
        : { data: { user: null }, error: new Error("invalid token") };
    } } },
    isOwnerAccount: ({ email }) => email === "owner@example.test"
  });
  const url = "ws://127.0.0.1:" + address.port + "/api/dream-education/dream-wifi/tunnel";
  let sourceA, sourceB, owner;
  try {
    sourceA = await registerSource(url, "source-a", "Local test router");
    sourceB = await registerSource(url, "source-b", "Remote test gateway");
    owner = new WebSocket(url);
    await waitOpen(owner);
    const registered = waitMessage(owner, message => message.type === "registered" && message.role === "client");
    const list = waitMessage(owner, message => message.type === "source-list" && Array.isArray(message.sources) && message.sources.length === 2);
    owner.send(JSON.stringify({ type: "register", role: "owner", accessToken: "valid-owner-token" }));
    await registered;
    const available = await list;
    assert.deepEqual(available.sources.map(source => source.id).sort(), ["source-a", "source-b"]);
    assert.equal(available.sources.find(source => source.id === "source-a").name, "Local test router");
    assert.equal(available.sources.find(source => source.id === "source-b").name, "Remote test gateway");

    const spoof = new WebSocket(url);
    await waitOpen(spoof);
    const spoofDenied = waitMessage(spoof, message => message.type === "error");
    spoof.send(JSON.stringify({
      type: "register", role: "source", sourceToken: "shared-test-token",
      sourceId: "source-b", sourceName: "Spoofed source"
    }));
    assert.match((await spoofDenied).error, /already connected/i);
    assert.equal(sourceB.readyState, WebSocket.OPEN, "Duplicate source ID must not evict the registered source.");
    assert.equal(wss.getSources().length, 2);
    spoof.terminate();

    const sourcePing = waitMessage(sourceB, message => message.type === "ping");
    const pingResult = waitMessage(owner, message => message.type === "ping-result");
    owner.send(JSON.stringify({ type: "ping-source", sourceId: "source-b" }));
    const ping = await sourcePing;
    sourceB.send(JSON.stringify({ type: "pong", id: ping.id }));
    const pong = await pingResult;
    assert.equal(pong.success, true);
    assert.equal(pong.sourceId, "source-b");

    const sourceOpen = waitMessage(sourceB, message => message.type === "tcp-open" && message.id === "selected-source-test");
    const ownerOpened = waitMessage(owner, message => message.type === "tcp-opened" && message.id === "selected-source-test");
    owner.send(JSON.stringify({ type: "tcp-open", id: "selected-source-test", host: "example.com", port: 443, sourceId: "source-b" }));
    assert.equal((await sourceOpen).host, "example.com");
    sourceB.send(JSON.stringify({ type: "tcp-opened", id: "selected-source-test" }));
    assert.equal((await ownerOpened).id, "selected-source-test");
    assert.equal(wss.getSources().length, 2);
    console.log("Dream Wi-Fi multi-source selection smoke test passed.");
  } finally {
    for (const socket of [owner, sourceA, sourceB]) if (socket) socket.terminate();
    wss.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
