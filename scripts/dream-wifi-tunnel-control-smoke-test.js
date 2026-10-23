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
  });

  const url = "ws://127.0.0.1:" + address.port + "/api/dream-wifi/tunnel";
  let source, owner, nonOwner, legacy, nativeSource, enrolledSource, spoofedEnrollment, restartedSource, restartedServer, restartedWss;
  try {
    source = new WebSocket(url);
    await waitOpen(source);
    const sourceRegistered = waitMessage(source, m => m.type === "registered");
    const pairingMessagePromise = waitMessage(source, m => m.type === "pairing-code");
    source.send(JSON.stringify({ type: "register", role: "source", sourceToken: "test-source-secret" }));
    const sourceRegistration = await sourceRegistered;
    assert.equal(sourceRegistration.role, "source");
    const pairingMessage = await pairingMessagePromise;
    assert.match(pairingMessage.code, /^[A-F0-9]{12}$/);
    assert.equal(pairingMessage.expiresInSeconds, 600);
    source.send(JSON.stringify({ type: "source-health", ready: true, checkedAt: new Date().toISOString() }));

    nonOwner = new WebSocket(url);
    await waitOpen(nonOwner);
    const anonymousRejected = waitMessage(nonOwner, m => m.type === "error");
    nonOwner.send(JSON.stringify({ type: "register", role: "client" }));
    assert.match((await anonymousRejected).error, /pairing code/i);
    await new Promise(resolve => setTimeout(resolve, 50));
    nonOwner = null;

    const invalidCodeClient = new WebSocket(url);
    await waitOpen(invalidCodeClient);
    const invalidCodeRejected = waitMessage(invalidCodeClient, m => m.type === "error");
    invalidCodeClient.send(JSON.stringify({ type: "register", role: "client", pairingCode: "NOT-A-VALID-CODE" }));
    assert.match((await invalidCodeRejected).error, /valid, unexpired pairing code/i);
    invalidCodeClient.close();

    owner = new WebSocket(url);
    await waitOpen(owner);
    const ownerRegistered = waitMessage(owner, m => m.type === "registered");
    const ownerSourceList = waitMessage(owner, m => m.type === "source-list");
    owner.send(JSON.stringify({ type: "register", role: "owner", pairingCode: pairingMessage.code }));
    const ownerRegistration = await ownerRegistered;
    assert.equal(ownerRegistration.role, "client");
    assert.equal(ownerRegistration.sourceId, sourceRegistration.sourceId);
    const initialSourceList = await ownerSourceList;
    assert.equal(initialSourceList.sources.length, 1);
    assert.equal(initialSourceList.sources[0].internetReady, true);
    assert.equal(typeof ownerRegistration.accessToken, "string");
    assert.ok(ownerRegistration.accessToken.length >= 40);

    const reusedCodeClient = new WebSocket(url);
    await waitOpen(reusedCodeClient);
    const reusedCodeRejected = waitMessage(reusedCodeClient, m => m.type === "error");
    reusedCodeClient.send(JSON.stringify({ type: "register", role: "client", pairingCode: pairingMessage.code }));
    assert.match((await reusedCodeRejected).error, /valid, unexpired pairing code/i);
    reusedCodeClient.close();

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

    const revokedOwner = waitMessage(owner, m => m.type === "error" && /revoked/i.test(m.error));
    source.send(JSON.stringify({ type: "revoke-pairings" }));
    assert.match((await revokedOwner).error, /revoked/i);
    const revokedClient = new WebSocket(url);
    await waitOpen(revokedClient);
    const revokedDenied = waitMessage(revokedClient, m => m.type === "error");
    revokedClient.send(JSON.stringify({ type: "register", role: "client", accessToken: ownerRegistration.accessToken }));
    assert.match((await revokedDenied).error, /invalid or expired/i);
    revokedClient.close();

    legacy = new WebSocket(url);
    await waitOpen(legacy);
    const legacyRegistered = waitMessage(legacy, m => m.type === "registered");
    legacy.send(JSON.stringify({ type: "register", role: "source", sourceToken: "test-source-secret", sourceId: "legacy-source", sourceName: "Legacy source" }));
    assert.equal((await legacyRegistered).role, "source");

    const enrollment = wss.enrollSource();
    assert.ok(enrollment.sourceId);
    assert.ok(enrollment.sourceToken.length >= 40);
    spoofedEnrollment = new WebSocket(url);
    await waitOpen(spoofedEnrollment);
    const spoofRejected = waitMessage(spoofedEnrollment, m => m.type === "error");
    spoofedEnrollment.send(JSON.stringify({ type: "register", role: "source", sourceToken: enrollment.sourceToken, sourceId: "somebody-elses-id", sourceName: "Spoofed enrolled source" }));
    assert.match((await spoofRejected).error, /not authorized/i);
    spoofedEnrollment.close();

    enrolledSource = new WebSocket(url);
    await waitOpen(enrolledSource);
    const enrolledRegistered = waitMessage(enrolledSource, m => m.type === "registered");
    const enrolledCode = waitMessage(enrolledSource, m => m.type === "pairing-code");
    enrolledSource.send(JSON.stringify({ type: "register", role: "source", sourceToken: enrollment.sourceToken, sourceId: enrollment.sourceId, sourceName: "Per-source credential test" }));
    assert.equal((await enrolledRegistered).sourceId, enrollment.sourceId);
    assert.match((await enrolledCode).code, /^[A-F0-9]{12}$/);

    const persistedCredentials = wss.getEnrolledSourceCredentials();
    assert.equal(persistedCredentials.length, 1);
    assert.equal(persistedCredentials[0].sourceId, enrollment.sourceId);
    assert.notEqual(persistedCredentials[0].tokenHash, enrollment.sourceToken);
    assert.match(persistedCredentials[0].tokenHash, /^[a-f0-9]{64}$/);

    restartedServer = http.createServer();
    await new Promise(resolve => restartedServer.listen(0, "127.0.0.1", resolve));
    restartedWss = attachDreamWifiTunnel(restartedServer, {
      sourceToken: "test-source-secret",
      enrolledSourceCredentials: persistedCredentials
    });
    restartedSource = new WebSocket("ws://127.0.0.1:" + restartedServer.address().port + "/api/dream-wifi/tunnel");
    await waitOpen(restartedSource);
    const restoredRegistration = waitMessage(restartedSource, m => m.type === "registered");
    const restoredPairingCode = waitMessage(restartedSource, m => m.type === "pairing-code");
    restartedSource.send(JSON.stringify({
      type: "register", role: "source", sourceToken: enrollment.sourceToken,
      sourceId: enrollment.sourceId, sourceName: "Restored source"
    }));
    assert.equal((await restoredRegistration).sourceId, enrollment.sourceId);
    assert.match((await restoredPairingCode).code, /^[A-F0-9]{12}$/);

    console.log("Dream Tunnel control-plane smoke test passed.");
  } finally {
    for (const socket of [source, nativeSource, owner, nonOwner, legacy, enrolledSource, spoofedEnrollment, restartedSource]) {
      if (socket && socket.readyState < WebSocket.CLOSING) socket.close();
    }
    await new Promise(resolve => wss.close(resolve));
    if (restartedWss) await new Promise(resolve => restartedWss.close(resolve));
    if (restartedServer) await new Promise(resolve => restartedServer.close(resolve));
    await new Promise(resolve => server.close(resolve));
  }
}

main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
