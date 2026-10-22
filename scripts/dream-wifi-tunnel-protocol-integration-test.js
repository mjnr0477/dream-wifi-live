"use strict";

const assert = require("node:assert/strict");
const http = require("node:http");
const { WebSocket } = require("ws");
const { attachDreamWifiTunnel } = require("../product/dream_wifi_tunnel_engine");

function connect(url) {
  const socket = new WebSocket(url);
  const queue = [];
  const waiters = [];
  socket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString()); } catch (_) { return; }
    const index = waiters.findIndex(waiter => waiter.predicate(message));
    if (index >= 0) {
      const [waiter] = waiters.splice(index, 1);
      clearTimeout(waiter.timer);
      waiter.resolve(message);
    } else {
      queue.push(message);
    }
  });
  function next(predicate, label) {
    const index = queue.findIndex(predicate);
    if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const waiter = { predicate, resolve, reject, timer: null };
      waiter.timer = setTimeout(() => {
        const i = waiters.indexOf(waiter);
        if (i >= 0) waiters.splice(i, 1);
        reject(new Error("Timed out waiting for " + label));
      }, 3000);
      waiters.push(waiter);
    });
  }
  return { socket, next };
}

async function main() {
  const server = http.createServer();
  const tunnel = attachDreamWifiTunnel(server, {
    sourceToken: "integration-test-source-token",
    supabaseAuth: {
      auth: {
        async getUser(token) {
          if (token !== "valid-owner-session") {
            return { data: { user: null }, error: new Error("invalid session") };
          }
          return { data: { user: { id: "integration-owner", email: "owner@example.test" } }, error: null };
        }
      }
    },
    isOwnerAccount: account => account && account.email === "owner@example.test"
  });

  let source;
  let owner;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  const url = "ws://127.0.0.1:" + address.port + "/api/dream-education/dream-wifi/tunnel";

  try {
    source = connect(url);
    await new Promise((resolve, reject) => {
      source.socket.once("open", resolve);
      source.socket.once("error", reject);
    });
    source.socket.send(JSON.stringify({
      type: "register",
      role: "source",
      sourceToken: "integration-test-source-token",
      sourceId: "integration-source",
      sourceName: "Integration Source"
    }));
    const sourceRegistered = await source.next(m => m.type === "registered" && m.role === "source", "source registration");
    assert.equal(sourceRegistered.sourceId, "integration-source");

    owner = connect(url);
    await new Promise((resolve, reject) => {
      owner.socket.once("open", resolve);
      owner.socket.once("error", reject);
    });
    owner.socket.send(JSON.stringify({ type: "register", role: "owner", accessToken: "valid-owner-session" }));
    await owner.next(m => m.type === "registered" && m.role === "owner", "owner registration");
    const sourceList = await owner.next(m => m.type === "source-list", "owner source list");
    assert.ok(sourceList.sources.some(item => item.id === "integration-source"), "Authorized owner should see the registered static source.");

    owner.socket.send(JSON.stringify({
      type: "tcp-open",
      id: "integration-stream",
      host: "example.com",
      port: 80,
      sourceId: "integration-source"
    }));
    const openRequest = await source.next(m => m.type === "tcp-open" && m.id === "integration-stream", "forwarded TCP open");
    assert.equal(openRequest.host, "example.com");
    assert.equal(openRequest.port, 80);

    source.socket.send(JSON.stringify({ type: "tcp-opened", id: openRequest.id }));
    await owner.next(m => m.type === "tcp-opened" && m.id === openRequest.id, "TCP opened acknowledgement");

    const payload = ["HTTP/1.1 200 OK", "Content-Length: 2", "", "OK"].join("\r\n");
    source.socket.send(JSON.stringify({
      type: "tcp-data",
      id: openRequest.id,
      data: Buffer.from(payload).toString("base64")
    }));
    const response = await owner.next(m => m.type === "tcp-data" && m.id === openRequest.id, "forwarded TCP data");
    assert.equal(Buffer.from(response.data, "base64").toString("utf8"), payload);

    owner.socket.send(JSON.stringify({ type: "tcp-close", id: openRequest.id }));
    await source.next(m => m.type === "tcp-close" && m.id === openRequest.id, "forwarded TCP close");

    const unauthorized = connect(url);
    try {
      await new Promise((resolve, reject) => {
        unauthorized.socket.once("open", resolve);
        unauthorized.socket.once("error", reject);
      });
      unauthorized.socket.send(JSON.stringify({ type: "register", role: "owner", accessToken: "invalid-session" }));
      await unauthorized.next(m => m.type === "error", "unauthorized owner rejection");
      assert.ok(true);
    } finally {
      try { unauthorized.socket.close(); } catch (_) {}
    }

    console.log("Dream Wi-Fi tunnel protocol integration test passed.");
  } finally {
    for (const endpoint of [owner, source]) {
      if (endpoint && endpoint.socket.readyState !== WebSocket.CLOSED) {
        try { endpoint.socket.close(); } catch (_) {}
      }
    }
    await new Promise(resolve => tunnel.close(() => resolve()));
    if (server.listening) await new Promise(resolve => server.close(() => resolve()));
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
