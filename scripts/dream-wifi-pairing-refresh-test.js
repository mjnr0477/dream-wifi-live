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
  const server = http.createServer();
  const wss = attachDreamWifiTunnel(server, { sourceToken: "pairing-refresh-test-token" });
  let source, oldCodeClient, newCodeClient;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const url = "ws://127.0.0.1:" + server.address().port + "/api/dream-wifi/tunnel";
  try {
    source = new WebSocket(url);
    await waitOpen(source);
    const registered = waitMessage(source, m => m.type === "registered");
    const firstCodePromise = waitMessage(source, m => m.type === "pairing-code");
    source.send(JSON.stringify({ type: "register", role: "source", sourceToken: "pairing-refresh-test-token", sourceId: "refresh-source" }));
    await registered;
    const firstCode = await firstCodePromise;

    const originalNow = Date.now;
    try {
      Date.now = () => originalNow() + 61_000;
      const nextCodePromise = waitMessage(source, m => m.type === "pairing-code");
      source.send(JSON.stringify({ type: "request-pairing-code" }));
      const nextCode = await nextCodePromise;
      assert.match(nextCode.code, /^[A-F0-9]{12}$/);
      assert.notEqual(nextCode.code, firstCode.code, "Refreshing must rotate the one-time pairing code.");
      Date.now = originalNow;

      oldCodeClient = new WebSocket(url);
      await waitOpen(oldCodeClient);
      const oldRejected = waitMessage(oldCodeClient, m => m.type === "error");
      oldCodeClient.send(JSON.stringify({ type: "register", role: "owner", pairingCode: firstCode.code }));
      assert.match((await oldRejected).error, /pairing code/i);

      newCodeClient = new WebSocket(url);
      await waitOpen(newCodeClient);
      const paired = waitMessage(newCodeClient, m => m.type === "registered" && m.role === "client");
      newCodeClient.send(JSON.stringify({ type: "register", role: "owner", pairingCode: nextCode.code }));
      const result = await paired;
      assert.equal(result.sourceId, "refresh-source");
    } finally {
      Date.now = originalNow;
    }
    console.log("Dream Wi-Fi pairing-code refresh and rotation test passed.");
  } finally {
    for (const socket of [oldCodeClient, newCodeClient, source]) if (socket) socket.terminate();
    wss.close();
    await new Promise(resolve => server.close(resolve));
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
