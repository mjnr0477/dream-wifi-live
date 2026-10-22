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

async function main() {
  const server = http.createServer((req, res) => { res.writeHead(200); res.end("ok"); });
  await new Promise(resolve => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  const wss = attachDreamWifiTunnel(server, {
    sourceToken: "test-source-secret",
    sourceTicketTtlMs: 250,
    supabaseAuth: {
      auth: {
        async getUser(token) {
          if (token === "valid-owner-token") return { data: { user: { email: "owner@example.test" } }, error: null };
          if (token === "valid-user-token") return { data: { user: { email: "learner@example.test" } }, error: null };
          return { data: { user: null }, error: new Error("invalid token") };
        }
      }
    },
    isOwnerAccount: ({ email }) => email === "owner@example.test"
  });

  const url = "ws://127.0.0.1:" + address.port + "/api/dream-education/dream-wifi/tunnel";
  let source, owner, nonOwner, legacy, nativeSource;
  try {
    source = new WebSocket(url);
    await waitOpen(source);
    const sourceRegistered = waitMessage(source, m => m.type === "registered");
    source.send(JSON.stringify({ type: "register", role: "source", sourceToken: "test-source-secret" }));
    assert.equal((await sourceRegistered).role, "source");

    owner = new WebSocket(url);
    await waitOpen(owner);
    const ownerRegistered = waitMessage(owner, m => m.type === "registered");
    owner.send(JSON.stringify({ type: "register", role: "owner", accessToken: "valid-owner-token" }));
    assert.equal((await ownerRegistered).role, "client");

    const pingResult = waitMessage(owner, m => m.type === "ping-result");
    owner.send(JSON.stringify({ type: "ping-source" }));
    const sourcePing = await waitMessage(source, m => m.type === "ping");
    source.send(JSON.stringify({ type: "pong", id: sourcePing.id }));
    const result = await pingResult;
    assert.equal(result.success, true);
    assert.equal(typeof result.latency_ms, "number");

    const opened = waitMessage(owner, m => m.type === "tcp-opened" && m.id === "test-stream-1");
    const sourceOpen = waitMessage(source, m => m.type === "tcp-open" && m.id === "test-stream-1");
    owner.send(JSON.stringify({ type: "tcp-open", id: "test-stream-1", host: "example.com", port: 443 }));
    assert.equal((await sourceOpen).host, "example.com");
    source.send(JSON.stringify({ type: "tcp-opened", id: "test-stream-1" }));
    assert.equal((await opened).id, "test-stream-1");

    const sourceData = waitMessage(source, m => m.type === "tcp-data" && m.id === "test-stream-1");
    owner.send(JSON.stringify({ type: "tcp-data", id: "test-stream-1", data: "aGVsbG8=" }));
    assert.equal((await sourceData).data, "aGVsbG8=");
    const ownerData = waitMessage(owner, m => m.type === "tcp-data" && m.id === "test-stream-1");
    source.send(JSON.stringify({ type: "tcp-data", id: "test-stream-1", data: "d29ybGQ=" }));
    assert.equal((await ownerData).data, "d29ybGQ=");

    const udpForward = waitMessage(source, m => m.type === "udp-send" && m.id === "test-udp-1");
    const udpReturn = waitMessage(owner, m => m.type === "udp-data" && m.id === "test-udp-1");
    owner.send(JSON.stringify({ type: "udp-send", id: "test-udp-1", host: "1.1.1.1", port: 53, data: "AQIDBA==" }));
    const udpRequest = await udpForward;
    assert.equal(udpRequest.host, "1.1.1.1");
    assert.equal(udpRequest.port, 53);
    assert.equal(udpRequest.data, "AQIDBA==");
    source.send(JSON.stringify({ type: "udp-data", id: "test-udp-1", host: "1.1.1.1", port: 53, data: "BQYHCA==" }));
    const udpResponse = await udpReturn;
    assert.equal(udpResponse.data, "BQYHCA==");
    const udpClosed = waitMessage(source, m => m.type === "udp-close" && m.id === "test-udp-1");
    owner.send(JSON.stringify({ type: "udp-close", id: "test-udp-1" }));
    await udpClosed;

    const rejectedPort = waitMessage(owner, m => m.type === "tcp-error" && m.id === "blocked-port");
    owner.send(JSON.stringify({ type: "tcp-open", id: "blocked-port", host: "example.com", port: 22 }));
    assert.match((await rejectedPort).error, /HTTP.HTTPS/);
    const sourceClosed = waitMessage(source, m => m.type === "tcp-close" && m.id === "test-stream-1");
    owner.send(JSON.stringify({ type: "tcp-close", id: "test-stream-1" }));
    await sourceClosed;

    nonOwner = new WebSocket(url);
    await waitOpen(nonOwner);
    const publicRegistered = waitMessage(nonOwner, m => m.type === "registered");
    nonOwner.send(JSON.stringify({ type: "register", role: "client" }));
    assert.equal((await publicRegistered).role, "client");

    legacy = new WebSocket(url);
    await waitOpen(legacy);
    const legacyRegistered = waitMessage(legacy, m => m.type === "registered");
    legacy.send(JSON.stringify({ type: "register", role: "source", sourceToken: "test-source-secret", sourceId: "legacy-source", sourceName: "Legacy source" }));
    assert.equal((await legacyRegistered).role, "source");

    console.log("Dream Tunnel control-plane smoke test passed.");
  } finally {
    for (const socket of [source, nativeSource, owner, nonOwner, legacy]) {
      if (socket && socket.readyState < WebSocket.CLOSING) socket.close();
    }
    await new Promise(resolve => wss.close(resolve));
    await new Promise(resolve => server.close(resolve));
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
