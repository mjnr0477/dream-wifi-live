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

function waitMessage(socket, predicate, label, timeoutMs = 4000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      socket.off("message", onMessage);
      reject(new Error("Timed out waiting for " + label));
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

async function connect(url) {
  const socket = new WebSocket(url);
  await waitOpen(socket);
  return socket;
}

async function main() {
  const server = http.createServer();
  const wss = attachDreamWifiTunnel(server, { sourceToken: "multi-receiver-test-token" });
  const clients = [];
  let source;
  let rejectedReuse;
  let rejectedRefreshReuse;

  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });

  const url = "ws://127.0.0.1:" + server.address().port + "/api/dream-wifi/tunnel";

  try {
    source = await connect(url);
    // The source intentionally has one listener per simultaneous stream waiter in this test.
    source.setMaxListeners(0);
    const sourceRegistered = waitMessage(source, m => m.type === "registered" && m.role === "source", "source registration");
    const firstCodePromise = waitMessage(source, m => m.type === "pairing-code", "initial pairing code");
    source.send(JSON.stringify({
      type: "register",
      role: "source",
      sourceToken: "multi-receiver-test-token",
      sourceId: "multi-receiver-source",
      sourceName: "Multi Receiver Source"
    }));
    await sourceRegistered;
    let currentCode = await firstCodePromise;
    const firstCode = currentCode.code;

    // Keep more than twelve receivers open from the same test IP. This catches
    // the old per-IP socket cap that could block users sharing a carrier NAT.
    for (let i = 0; i < 20; i += 1) {
      const client = await connect(url);
      const registeredPromise = waitMessage(
        client,
        m => m.type === "registered" && m.role === "client",
        "receiver " + (i + 1) + " registration"
      );
      const sourceListPromise = waitMessage(
        client,
        m => m.type === "source-list",
        "receiver " + (i + 1) + " source list"
      );
      const nextCodePromise = waitMessage(
        source,
        m => m.type === "pairing-code" && m.code !== currentCode.code,
        "next one-time code after receiver " + (i + 1)
      );

      client.send(JSON.stringify({ type: "register", role: "owner", pairingCode: currentCode.code }));
      const registered = await registeredPromise;
      const sourceList = await sourceListPromise;
      assert.equal(registered.sourceId, "multi-receiver-source");
      assert.deepEqual(sourceList.sources.map(item => item.id), ["multi-receiver-source"]);
      clients.push(client);
      currentCode = await nextCodePromise;
    }

    // A consumed code cannot be reused, while each successful pairing receives
    // a new code so the source owner can authorize the next receiver.
    rejectedReuse = await connect(url);
    const reusedDenied = waitMessage(rejectedReuse, m => m.type === "error", "reused code rejection");
    rejectedReuse.send(JSON.stringify({ type: "register", role: "owner", pairingCode: firstCode }));
    assert.match((await reusedDenied).error, /pairing code/i);

    // The source owner can explicitly rotate an unused code from the Share action.
    const codeBeforeRefresh = currentCode.code;
    const refreshedCodePromise = waitMessage(
      source,
      m => m.type === "pairing-code" && m.code !== codeBeforeRefresh,
      "manually refreshed one-time code"
    );
    source.send(JSON.stringify({ type: "request-pairing-code" }));
    currentCode = await refreshedCodePromise;

    rejectedRefreshReuse = await connect(url);
    const oldRefreshDenied = waitMessage(rejectedRefreshReuse, m => m.type === "error", "superseded code rejection");
    rejectedRefreshReuse.send(JSON.stringify({ type: "register", role: "owner", pairingCode: codeBeforeRefresh }));
    assert.match((await oldRefreshDenied).error, /pairing code/i);

    // Exercise source routing for every live receiver at once. The server must
    // keep each receiver's stream attached to its own paired source session.
    const pendingOpens = clients.map(async (client, i) => {
      const id = "multi-user-stream-" + i;
      const sourceOpen = waitMessage(source, m => m.type === "tcp-open" && m.id === id, "source stream " + i);
      const ownerOpen = waitMessage(client, m => m.type === "tcp-opened" && m.id === id, "receiver stream acknowledgement " + i);
      client.send(JSON.stringify({
        type: "tcp-open",
        id,
        host: "example.com",
        port: 443,
        sourceId: "multi-receiver-source"
      }));
      const open = await sourceOpen;
      assert.equal(open.port, 443);
      source.send(JSON.stringify({ type: "tcp-opened", id }));
      const acknowledged = await ownerOpen;
      assert.equal(acknowledged.id, id);
    });
    await Promise.all(pendingOpens);

    console.log("Dream Wi-Fi multi-receiver test passed: 20 concurrent receivers, one-time code rotation, and isolated source streams.");
  } finally {
    for (const socket of [rejectedRefreshReuse, rejectedReuse, ...clients, source]) {
      if (socket && socket.readyState !== WebSocket.CLOSED) {
        try { socket.terminate(); } catch (_) {}
      }
    }
    await new Promise(resolve => wss.close(() => resolve()));
    if (server.listening) await new Promise(resolve => server.close(() => resolve()));
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
