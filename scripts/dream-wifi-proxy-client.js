"use strict";

require("dotenv").config();

// Local SOCKS5 client for public-client HTTP/HTTPS TCP and bounded public UDP through the source endpoint.
// This is not an Android VPNService and does not automatically route all device traffic.
const net = require("node:net");
const dgram = require("node:dgram");
const crypto = require("node:crypto");
const { WebSocket } = require("ws");

const tunnelUrl = String(process.env.DREAM_WIFI_TUNNEL_URL || "").trim();
const port = Number(process.env.DREAM_WIFI_PROXY_PORT || 1080);
const selectedSourceId = String(process.env.DREAM_WIFI_SELECTED_SOURCE_ID || "").trim();
const MAX_CHUNK = 12 * 1024;
const MAX_BUFFER_BYTES = 1024 * 1024;
const streams = new Map();
const udpAssociations = new Map();
const MAX_UDP_DATAGRAM_BYTES = 8192;

if (!tunnelUrl) {
  console.error("Set DREAM_WIFI_TUNNEL_URL in your environment or .env file before starting the proxy client.");
  process.exit(1);
}
if (!/^wss:\/\//i.test(tunnelUrl)) {
  console.error("DREAM_WIFI_TUNNEL_URL must use wss://.");
  process.exit(1);
}
if (!Number.isInteger(port) || port < 1024 || port > 65535) {
  console.error("DREAM_WIFI_PROXY_PORT must be an available port between 1024 and 65535.");
  process.exit(1);
}

let socket = null;
let ownerReady = false;
let stopped = false;
let reconnectTimer = null;
let heartbeatTimer = null;
let proxyServer = null;

function send(payload) {
  if (ownerReady && socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
    return true;
  }
  return false;
}
function parseUdpPacket(packet) {
  if (!Buffer.isBuffer(packet) || packet.length < 4 || packet[0] !== 0 || packet[1] !== 0 || packet[2] !== 0) return null;
  const atyp = packet[3]; let host, offset;
  if (atyp === 1) { if (packet.length < 10) return null; host = Array.from(packet.subarray(4, 8)).join('.'); offset = 8; }
  else if (atyp === 3) { if (packet.length < 7) return null; const n = packet[4]; if (packet.length < 7 + n) return null; host = packet.subarray(5, 5 + n).toString('utf8'); offset = 5 + n; }
  else if (atyp === 4) { if (packet.length < 22) return null; host = Array.from({ length: 8 }, (_, i) => packet.readUInt16BE(4 + i * 2).toString(16)).join(':'); offset = 20; }
  else return null;
  if (!host || host.includes('/') || host.includes(' ')) return null;
  const port = packet.readUInt16BE(offset); const data = packet.subarray(offset + 2);
  return port > 0 && data.length > 0 && data.length <= MAX_UDP_DATAGRAM_BYTES ? { host, port, data } : null;
}
function makeUdpPacket(host, port, data) {
  const name = Buffer.from(host, 'utf8'); if (!name.length || name.length > 253 || data.length > MAX_UDP_DATAGRAM_BYTES) return null;
  const header = Buffer.alloc(7 + name.length); header[0] = 0; header[1] = 0; header[2] = 0; header[3] = 3; header[4] = name.length; name.copy(header, 5); header.writeUInt16BE(port, 5 + name.length);
  return Buffer.concat([header, data]);
}
function closeUdpAssociation(id, notify = true) {
  const entry = udpAssociations.get(id); if (!entry) return; udpAssociations.delete(id);
  try { entry.udp.close(); } catch (_) {} if (notify) send({ type: 'udp-close', id });
}
function sendChunk(id, buffer) {
  for (let offset = 0; offset < buffer.length; offset += MAX_CHUNK) {
    if (!send({ type: "tcp-data", id, data: buffer.subarray(offset, offset + MAX_CHUNK).toString("base64") })) return false;
  }
  return true;
}
function removeStream(id, notifyServer) {
  const entry = streams.get(id);
  if (!entry) return;
  streams.delete(id);
  if (notifyServer) send({ type: "tcp-close", id });
  entry.state = "closed";
}
function failSocks(entry, code) {
  if (entry.state !== "connected") {
    try { entry.local.write(Buffer.from([5, code || 1, 0, 1, 0, 0, 0, 0, 0, 0])); } catch (_) {}
  }
  try { entry.local.end(); } catch (_) {}
  removeStream(entry.id, true);
}
function handleLocalSocket(local) {
  local.setNoDelay(true);
  let state = "greeting";
  let buffer = Buffer.alloc(0);
  let entry = null;
  let closed = false;

  function onData(chunk) {
    if (state === "udp") return;
    if (state === "connected") {
      if (entry && entry.local.writableLength > MAX_BUFFER_BYTES) return failSocks(entry, 1);
      if (!sendChunk(entry.id, chunk)) return failSocks(entry, 1);
      return;
    }
    if (state === "connecting") {
      entry.pending = Buffer.concat([entry.pending, chunk]);
      if (entry.pending.length > MAX_BUFFER_BYTES) failSocks(entry, 1);
      return;
    }
    buffer = Buffer.concat([buffer, chunk]);
    if (buffer.length > 4096) return local.destroy();

    while (true) {
      if (state === "greeting") {
        if (buffer.length < 2) return;
        const count = buffer[1];
        if (buffer.length < 2 + count) return;
        const methods = buffer.subarray(2, 2 + count);
        buffer = buffer.subarray(2 + count);
        if (!methods.includes(0)) {
          local.end(Buffer.from([5, 255]));
          return;
        }
        local.write(Buffer.from([5, 0]));
        state = "request";
        continue;
      }

      if (state === "request") {
        if (buffer.length < 4) return;
        if (buffer[0] !== 5 || ![1, 3].includes(buffer[1]) || buffer[2] !== 0) {
          local.end(Buffer.from([5, 7, 0, 1, 0, 0, 0, 0, 0, 0]));
          return;
        }
        const command = buffer[1];
        const atyp = buffer[3];
        let host, consumed;
        if (atyp === 1) {
          if (buffer.length < 10) return;
          host = Array.from(buffer.subarray(4, 8)).join(".");
          consumed = 10;
        } else if (atyp === 3) {
          if (buffer.length < 5) return;
          const length = buffer[4];
          if (buffer.length < 7 + length) return;
          host = buffer.subarray(5, 5 + length).toString("utf8");
          consumed = 7 + length;
        } else if (atyp === 4) {
          if (buffer.length < 22) return;
          host = Array.from({ length: 8 }, (_, index) => buffer.readUInt16BE(4 + index * 2).toString(16)).join(":");
          consumed = 22;
        } else {
          local.end(Buffer.from([5, 8, 0, 1, 0, 0, 0, 0, 0, 0]));
          return;
        }
        const targetPort = buffer.readUInt16BE(consumed - 2);
        buffer = buffer.subarray(consumed);
        if (command === 3) {
          if (!ownerReady || !socket || socket.readyState !== WebSocket.OPEN || udpAssociations.size >= 8) { local.end(Buffer.from([5, 1, 0, 1, 0, 0, 0, 0, 0, 0])); return; }
          const id = crypto.randomUUID(); const udp = dgram.createSocket('udp4');
          const association = { id, local, udp, endpoint: null }; udpAssociations.set(id, association);
          udp.on('message', (packet, remote) => { const parsed = parseUdpPacket(packet); if (!parsed || !ownerReady) return; association.endpoint = { address: remote.address, port: remote.port }; if (!send({ type: 'udp-send', id, host: parsed.host, port: parsed.port, data: parsed.data.toString('base64'), sourceId: selectedSourceId || undefined })) closeUdpAssociation(id); });
          udp.on('error', () => closeUdpAssociation(id));
          udp.bind(0, '127.0.0.1', () => { const addr = udp.address(); local.write(Buffer.from([5, 0, 0, 1, 127, 0, 0, 1, (addr.port >> 8) & 255, addr.port & 255])); state = 'udp'; });
          return;
        }
        if (!host || host.includes(' ') || host.includes('/') || !Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) {
          local.end(Buffer.from([5, 2, 0, 1, 0, 0, 0, 0, 0, 0]));
          return;
        }
        if (!ownerReady || !socket || socket.readyState !== WebSocket.OPEN) {
          local.end(Buffer.from([5, 1, 0, 1, 0, 0, 0, 0, 0, 0]));
          return;
        }
        const id = crypto.randomUUID();
        entry = { id, local, state: "connecting", pending: buffer, finished: false, setState: value => { state = value; } };
        streams.set(id, entry);
        state = "connecting";
        buffer = Buffer.alloc(0);
        local.pause();
        if (!send({ type: "tcp-open", id, host, port: targetPort, sourceId: selectedSourceId || undefined })) failSocks(entry, 1);
        return;
      }
      return;
    }
  }

  local.on("data", onData);
  local.on("close", () => {
    closed = true;
    if (entry) removeStream(entry.id, true);
    for (const [id, association] of udpAssociations.entries()) if (association.local === local) closeUdpAssociation(id, true);
  });
  local.on("error", () => {
    if (entry) removeStream(entry.id, true);
  });
  local.on("end", () => {
    if (entry && entry.state === "connected") send({ type: "tcp-close", id: entry.id });
  });
  // Avoid an unused closed-state ambiguity if the socket closes during handshake.
  local.on("close", () => { if (!closed) closed = true; });
}

function startProxy() {
  if (proxyServer || !ownerReady) return;
  proxyServer = net.createServer(handleLocalSocket);
  proxyServer.on("error", error => {
    console.error("Local SOCKS proxy could not start:", error.message);
    proxyServer = null;
  });
  proxyServer.listen(port, "127.0.0.1", () => {
    console.log("Dream Tunnel SOCKS5 proxy listening on 127.0.0.1:" + port);
    console.log("TCP and UDP support valid destination ports; UDP datagrams are capped at 8 KiB and public destinations only.");
  });
}

function closeAllStreams() {
  for (const [id, entry] of streams.entries()) {
    streams.delete(id);
    entry.state = "closed";
    try { entry.local.destroy(); } catch (_) {}
  }
  for (const id of [...udpAssociations.keys()]) closeUdpAssociation(id, false);
}
function connect() {
  if (stopped) return;
  socket = new WebSocket(tunnelUrl, { perMessageDeflate: false, maxPayload: 24 * 1024 });
  const currentSocket = socket;
  currentSocket.on("open", () => {
    // Keep intermediary proxies from expiring an otherwise healthy idle WebSocket.
    heartbeatTimer = setInterval(() => {
      if (currentSocket.readyState !== WebSocket.OPEN) return;
      try {
        currentSocket.ping();
      } catch (error) {
        console.error("Dream Tunnel heartbeat failed:", error.message);
      }
    }, 25000);
    currentSocket.send(JSON.stringify({ type: "register", role: "client" }));
  });
  currentSocket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString("utf8")); } catch (_) { return; }
    if (message.type === "registered" && message.role === "client") {
      ownerReady = true;
      startProxy();
      console.log("Dream Tunnel owner client authenticated.");
    } else if (message.type === "tcp-opened" && typeof message.id === "string") {
      const entry = streams.get(message.id);
      if (!entry || entry.state !== "connecting") return;
      entry.state = "connected";
      entry.setState("connected");
      entry.local.write(Buffer.from([5, 0, 0, 1, 0, 0, 0, 0, 0, 0]));
      entry.local.resume();
      if (entry.pending.length) {
        const pending = entry.pending;
        entry.pending = Buffer.alloc(0);
        if (!sendChunk(entry.id, pending)) failSocks(entry, 1);
      }
    } else if (message.type === "tcp-data" && typeof message.id === "string") {
      const entry = streams.get(message.id);
      if (!entry || entry.state !== "connected" || typeof message.data !== "string") return;
      const data = Buffer.from(message.data, "base64");
      if (data.length > MAX_CHUNK || entry.local.writableLength > MAX_BUFFER_BYTES) return failSocks(entry, 1);
      if (!entry.local.write(data)) {
        entry.local.pause();
        entry.local.once("drain", () => entry.local.resume());
      }
    } else if (message.type === "tcp-error" && typeof message.id === "string") {
      const entry = streams.get(message.id);
      if (entry) failSocks(entry, 1);
    } else if (message.type === "tcp-close" && typeof message.id === "string") {
      const entry = streams.get(message.id);
      if (entry) {
        streams.delete(message.id);
        entry.state = "closed";
        entry.local.end();
      }
    } else if (message.type === "udp-data" && typeof message.id === "string") {
      const entry = udpAssociations.get(message.id);
      if (!entry || !entry.endpoint || typeof message.data !== "string") return;
      const data = Buffer.from(message.data, "base64");
      const packet = makeUdpPacket(String(message.host || ""), Number(message.port), data);
      if (!packet || data.length > MAX_UDP_DATAGRAM_BYTES) return;
      entry.udp.send(packet, entry.endpoint.port, entry.endpoint.address, () => {});
    } else if (message.type === "udp-error" && typeof message.id === "string") {
      if (udpAssociations.has(message.id)) closeUdpAssociation(message.id, true);
    } else if (message.type === "udp-close" && typeof message.id === "string") {
      closeUdpAssociation(message.id, false);
    } else if (message.type === "error") {
      console.error("Dream Tunnel client rejected:", String(message.error || "unknown error"));
      currentSocket.close(1008, "Owner rejected");
    }
  });
  currentSocket.on("close", (code, reason) => {
    // A late close event from an older socket must not affect the current connection.
    if (socket !== currentSocket) return;
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    heartbeatTimer = null;
    ownerReady = false;
    closeAllStreams();

    // Stop accepting local SOCKS connections while the relay is disconnected.
    // Drop the server reference immediately so the next successful registration
    // can create a fresh listener instead of retaining a stale Server object.
    const previousProxyServer = proxyServer;
    proxyServer = null;
    if (previousProxyServer) {
      try { previousProxyServer.close(); } catch (_) {}
    }

    if (!stopped) {
      const detail = reason && reason.length ? reason.toString("utf8") : "no reason supplied";
      console.error("Dream Tunnel WebSocket closed (code " + code + "; " + detail + ").");
      console.log("Dream Tunnel disconnected; reconnecting in 5 seconds.");
      reconnectTimer = setTimeout(connect, 5000);
    }
  });
  currentSocket.on("error", error => {
    console.error("Dream Tunnel WebSocket error:", error.message);
  });
}

function shutdown() {
  stopped = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = null;
  ownerReady = false;
  closeAllStreams();
  const previousProxyServer = proxyServer;
  proxyServer = null;
  if (previousProxyServer) {
    try { previousProxyServer.close(); } catch (_) {}
  }
  if (socket) socket.close();
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
connect();
