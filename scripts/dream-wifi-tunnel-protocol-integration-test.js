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

async function waitOpen(endpoint) {
  await new Promise((resolve, reject) => {
    endpoint.socket.once("open", resolve);
    endpoint.socket.once("error", reject);
  });
}

async function rejectedPairing(url, code, label) {
  const client = connect(url);
  try {
    await waitOpen(client);
    client.socket.send(JSON.stringify({ type: "register", role: "owner", pairingCode: code }));
    const denied = await client.next(m => m.type === "error", label);
    assert.match(denied.error, /pairing code|valid|unexpired/i, label);
  } finally {
    try { client.socket.close(); } catch (_) {}
  }
}

async function main() {
  const server = http.createServer();
  const tunnel = attachDreamWifiTunnel(server, {
    sourceToken: "integration-test-source-token",
  });

  let source;
  let owner;
  let revokedTokenClient;
  await new Promise((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  const address = server.address();
  const url = "ws://127.0.0.1:" + address.port + "/api/dream-wifi/tunnel";

  try {
    source = connect(url);
    await waitOpen(source);
    source.socket.send(JSON.stringify({
      type: "register",
      role: "source",
      sourceToken: "integration-test-source-token",
      sourceId: "integration-source",
      sourceName: "Integration Source"
    }));
    const sourceRegistered = await source.next(m => m.type === "registered" && m.role === "source", "source registration");
    assert.equal(sourceRegistered.sourceId, "integration-source");
    const pairing = await source.next(m => m.type === "pairing-code", "one-time pairing code");

    await rejectedPairing(url, "NOT-A-VALID-CODE", "invalid pairing code rejection");

    const originalNow = Date.now;
    try {
      Date.now = () => originalNow() + 11 * 60 * 1000;
      await rejectedPairing(url, pairing.code, "expired pairing code rejection");
    } finally {
      Date.now = originalNow;
    }

    owner = connect(url);
    await waitOpen(owner);
    owner.socket.send(JSON.stringify({ type: "register", role: "owner", pairingCode: pairing.code }));
    const ownerRegistration = await owner.next(m => m.type === "registered" && m.role === "client", "paired client registration");
    assert.equal(ownerRegistration.sourceId, "integration-source");
    assert.ok(ownerRegistration.accessToken.length >= 40);
    const accessToken = ownerRegistration.accessToken;
    const sourceList = await owner.next(m => m.type === "source-list", "owner source list");
    assert.deepEqual(sourceList.sources.map(item => item.id), ["integration-source"], "Paired receiver should only see its authorized source.");

    await rejectedPairing(url, pairing.code, "one-time pairing code reuse rejection");

    owner.socket.send(JSON.stringify({
      type: "tcp-open",
      id: "integration-stream",
      host: "example.com",
      port: 8443,
      sourceId: "integration-source"
    }));
    const openRequest = await source.next(m => m.type === "tcp-open" && m.id === "integration-stream", "forwarded TCP open");
    assert.equal(openRequest.host, "example.com");
    assert.equal(openRequest.port, 8443, "Non-standard valid TCP ports must be relayable.");

    source.socket.send(JSON.stringify({ type: "tcp-opened", id: openRequest.id }));
    await owner.next(m => m.type === "tcp-opened" && m.id === openRequest.id, "TCP opened acknowledgement");

    const payload = ["HTTP/1.1 200 OK", "Content-Length: 2", "", "OK"].join("\r\n");
    const splitAt = Math.floor(payload.length / 2);
    source.socket.send(JSON.stringify({
      type: "tcp-data",
      id: openRequest.id,
      data: Buffer.from(payload.slice(0, splitAt)).toString("base64")
    }));
    source.socket.send(JSON.stringify({
      type: "tcp-data",
      id: openRequest.id,
      data: Buffer.from(payload.slice(splitAt)).toString("base64")
    }));
    const responsePart1 = await owner.next(m => m.type === "tcp-data" && m.id === openRequest.id, "first split TCP response chunk");
    const responsePart2 = await owner.next(m => m.type === "tcp-data" && m.id === openRequest.id, "second split TCP response chunk");
    const reconstructed = Buffer.concat([
      Buffer.from(responsePart1.data, "base64"),
      Buffer.from(responsePart2.data, "base64")
    ]).toString("utf8");
    assert.equal(reconstructed, payload, "TCP payload bytes must survive partial/split responses.");

    owner.socket.send(JSON.stringify({ type: "tcp-close", id: openRequest.id }));
    await source.next(m => m.type === "tcp-close" && m.id === openRequest.id, "forwarded TCP close");

    const udpRequestPayload = Buffer.from("ntp-request");
    owner.socket.send(JSON.stringify({
      type: "udp-send",
      id: "integration-udp",
      host: "time.example.com",
      port: 123,
      data: udpRequestPayload.toString("base64")
    }));
    const udpRequest = await source.next(m => m.type === "udp-send" && m.id === "integration-udp", "forwarded UDP datagram");
    assert.equal(udpRequest.port, 123, "Valid non-DNS UDP ports must be relayable.");
    assert.equal(Buffer.from(udpRequest.data, "base64").toString("utf8"), "ntp-request");
    source.socket.send(JSON.stringify({
      type: "udp-data",
      id: "integration-udp",
      host: "time.example.com",
      port: 123,
      data: Buffer.from("ntp-response").toString("base64")
    }));
    const udpResponse = await owner.next(m => m.type === "udp-data" && m.id === "integration-udp", "returned UDP datagram");
    assert.equal(udpResponse.port, 123);
    assert.equal(Buffer.from(udpResponse.data, "base64").toString("utf8"), "ntp-response");
    owner.socket.send(JSON.stringify({ type: "udp-close", id: "integration-udp" }));
    await source.next(m => m.type === "udp-close" && m.id === "integration-udp", "forwarded UDP close");

    owner.socket.send(JSON.stringify({
      type: "tcp-open",
      id: "invalid-tcp-port",
      host: "example.com",
      port: 65536,
      sourceId: "integration-source"
    }));
    const invalidTcp = await owner.next(m => m.type === "tcp-error" && m.id === "invalid-tcp-port", "invalid TCP port rejection");
    assert.match(invalidTcp.error, /valid public TCP destinations/i);

    owner.socket.send(JSON.stringify({
      type: "udp-send",
      id: "invalid-udp-port",
      host: "time.example.com",
      port: 65536,
      data: Buffer.from("invalid").toString("base64")
    }));
    const invalidUdp = await owner.next(m => m.type === "udp-error" && m.id === "invalid-udp-port", "invalid UDP port rejection");
    assert.match(invalidUdp.error, /invalid|destination/i);

    const publicClient = connect(url);
    try {
      await waitOpen(publicClient);
      publicClient.socket.send(JSON.stringify({ type: "register", role: "client" }));
      const denied = await publicClient.next(m => m.type === "error", "anonymous client rejection");
      assert.match(denied.error, /pairing code/i);
    } finally {
      try { publicClient.socket.close(); } catch (_) {}
    }

    source.socket.send(JSON.stringify({ type: "revoke-pairings" }));
    await source.next(m => m.type === "pairings-revoked", "source pairing revocation acknowledgement");
    const revoked = await owner.next(m => m.type === "error", "active receiver revocation");
    assert.match(revoked.error, /revoked/i, "Revoking pairings must terminate active receiver sessions.");

    revokedTokenClient = connect(url);
    await waitOpen(revokedTokenClient);
    revokedTokenClient.socket.send(JSON.stringify({ type: "register", role: "owner", accessToken }));
    const staleDenied = await revokedTokenClient.next(m => m.type === "error", "revoked receiver token rejection");
    assert.match(staleDenied.error, /invalid|expired/i, "A revoked receiver token must not restore access.");

    console.log("Dream Wi-Fi tunnel protocol integration test passed (pairing, one-time use, relay, and revocation).");
  } finally {
    for (const endpoint of [revokedTokenClient, owner, source]) {
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
