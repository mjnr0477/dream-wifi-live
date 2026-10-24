"use strict";

// Authorized source-side connector. It relays validated public TCP and UDP flows.
// This is not a general LAN proxy: private, loopback, link-local, and special-purpose destinations are blocked.
const dns = require("node:dns").promises;
const net = require("node:net");
const tls = require("node:tls");
const dgram = require("node:dgram");
const os = require("node:os");
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");
const { WebSocket } = require("ws");
const { isPublicAddress } = require("../product/dream_wifi_public_ip");
const readline = require("node:readline");
const execFileAsync = promisify(execFile);

const tunnelUrl = String(process.env.DREAM_WIFI_TUNNEL_URL || "").trim();
const sourceToken = String(process.env.DREAM_WIFI_SOURCE_TOKEN || "");
const configuredSourceName = String(process.env.DREAM_WIFI_SOURCE_NAME || "").trim().slice(0, 80);
const sourceId = String(process.env.DREAM_WIFI_SOURCE_ID || os.hostname() || "source").replace(/[^a-zA-Z0-9_-]/g, "-").slice(0, 80) || "source";
const sourceLatitude = process.env.DREAM_WIFI_SOURCE_LATITUDE === undefined || process.env.DREAM_WIFI_SOURCE_LATITUDE === "" ? null : Number(process.env.DREAM_WIFI_SOURCE_LATITUDE);
const sourceLongitude = process.env.DREAM_WIFI_SOURCE_LONGITUDE === undefined || process.env.DREAM_WIFI_SOURCE_LONGITUDE === "" ? null : Number(process.env.DREAM_WIFI_SOURCE_LONGITUDE);
if ((sourceLatitude !== null && (!Number.isFinite(sourceLatitude) || sourceLatitude < -90 || sourceLatitude > 90)) ||
    (sourceLongitude !== null && (!Number.isFinite(sourceLongitude) || sourceLongitude < -180 || sourceLongitude > 180))) {
  console.error("Source latitude/longitude must be valid decimal coordinates.");
  process.exit(1);
}
const MAX_CHUNK = 12 * 1024;
const MAX_WS_BUFFERED_BYTES = 1024 * 1024;
const streams = new Map();
const pendingOpens = new Set();
const udpSockets = new Map();
const MAX_UDP_DATAGRAM_BYTES = 8192;
const MAX_UDP_FLOWS = 32;
const UDP_IDLE_TIMEOUT_MS = 60000;

if (!tunnelUrl || !sourceToken) {
  console.error("Set DREAM_WIFI_TUNNEL_URL and DREAM_WIFI_SOURCE_TOKEN before starting the source agent.");
  process.exit(1);
}
if (!/^wss:\/\//i.test(tunnelUrl)) {
  console.error("DREAM_WIFI_TUNNEL_URL must use wss:// so the source token is encrypted in transit.");
  process.exit(1);
}

let stopped = false;
let socket = null;
let reconnectTimer = null;
let heartbeatTimer = null;
let healthTimer = null;
let pairingRefreshTimer = null;
let reconnectDelayMs = 5000;

async function detectSourceName() {
  if (configuredSourceName) return configuredSourceName;
  const attempts = [
    { command: "termux-wifi-connectioninfo", args: [], parse: output => {
      try {
        const data = JSON.parse(output);
        const ssid = String(data.ssid || "").trim().replace(/^"(.*)"$/, "$1");
        return ssid && ssid.toLowerCase() !== "<unknown ssid>" ? ssid : "";
      } catch (_) { return ""; }
    } },
    { command: "iwgetid", args: ["-r"], parse: output => output.trim().replace(/^"(.*)"$/, "$1") },
    { command: "nmcli", args: ["-t", "-f", "active,ssid", "dev", "wifi"], parse: output => {
      const match = output.match(/^yes:(.*)$/m);
      return match ? match[1].replace(/\\:/g, ":").trim() : "";
    } }
  ];
  for (const attempt of attempts) {
    try {
      const { stdout } = await execFileAsync(attempt.command, attempt.args, { timeout: 1200, maxBuffer: 4096 });
      const name = String(attempt.parse(stdout) || "").trim().replace(/[<>\x00-\x1f]/g, "").slice(0, 80);
      if (name && name.toLowerCase() !== "<unknown ssid>") return name;
    } catch (_) {}
  }
  return String(os.hostname() || "Dream Wi-Fi source").trim().slice(0, 80) || "Dream Wi-Fi source";
}
async function detectSourceCoordinates() {
  if (sourceLatitude !== null && sourceLongitude !== null) return { latitude: sourceLatitude, longitude: sourceLongitude };
  try {
    const { stdout } = await execFileAsync("termux-location", ["-p", "network", "-r", "once"], { timeout: 8000, maxBuffer: 8192 });
    const location = JSON.parse(stdout);
    const latitude = Number(location.latitude), longitude = Number(location.longitude);
    if (Number.isFinite(latitude) && latitude >= -90 && latitude <= 90 &&
        Number.isFinite(longitude) && longitude >= -180 && longitude <= 180) return { latitude, longitude };
  } catch (_) {}
  return { latitude: null, longitude: null };
}
function send(payload) {
  if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(payload));
}
function validId(value) {
  return typeof value === "string" && /^[a-zA-Z0-9_-]{1,80}$/.test(value);
}
async function resolvePublicHost(host) {
  if (net.isIP(host)) {
    if (!isPublicAddress(host)) throw new Error("Private or reserved destination is blocked.");
    return { address: host, family: net.isIP(host) };
  }
  const answers = await dns.lookup(host, { all: true, verbatim: true });
  if (!answers.length || answers.some(answer => !isPublicAddress(answer.address))) {
    throw new Error("Destination did not resolve exclusively to public IP addresses.");
  }
  return answers[0];
}
async function checkSourceInternet() {
  const checkedSocket = socket;
  if (!checkedSocket || checkedSocket.readyState !== WebSocket.OPEN) return;
  let ready = false, errorMessage = null;
  try {
    const target = await resolvePublicHost("example.com");
    if (socket !== checkedSocket || checkedSocket.readyState !== WebSocket.OPEN) return;
    await new Promise(resolve => {
      let settled = false;
      let response = "";
      const finish = (success, error) => {
        if (settled) return;
        settled = true;
        ready = success;
        errorMessage = error || null;
        clearTimeout(timer);
        tlsSocket.destroy();
        resolve();
      };
      const timer = setTimeout(() => finish(false, "https_probe_timeout"), 7000);
      const tlsSocket = tls.connect({
        host: target.address,
        family: target.family,
        port: 443,
        servername: "example.com",
        rejectUnauthorized: true
      });
      tlsSocket.setTimeout(6000, () => finish(false, "https_probe_timeout"));
      tlsSocket.once("secureConnect", () => {
        if (!tlsSocket.authorized) {
          finish(false, "https_certificate_invalid");
          return;
        }
        tlsSocket.write("GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\nUser-Agent: Dream-WiFi-Gateway-Health/1.0\r\n\r\n");
      });
      tlsSocket.on("data", chunk => {
        response += chunk.toString("latin1");
        if (response.length > 16384) {
          finish(false, "https_probe_response_too_large");
          return;
        }
        const headerEnd = response.indexOf("\\r\\n\\r\\n");
        if (headerEnd < 0) return;
        const statusLine = response.slice(0, headerEnd).split("\\r\\n", 1)[0];
        if (/^HTTP\/1\.[01] 200(?: |$)/.test(statusLine)) finish(true, null);
        else finish(false, "https_probe_" + (statusLine.match(/^HTTP\/1\.[01] (\d{3})/) || [])[1] || "invalid_response");
      });
      tlsSocket.once("error", error => finish(false, String(error.code || "https_probe_failed")));
    });
  } catch (error) {
    errorMessage = String(error.code || error.message || "https_probe_failed").slice(0, 120);
  }
  if (socket === checkedSocket && checkedSocket.readyState === WebSocket.OPEN) {
    checkedSocket.send(JSON.stringify({
      type: "source-health",
      ready,
      error: errorMessage,
      checkedAt: new Date().toISOString()
    }));
  }
}
function closeUdpFlow(key) {
  const entry = udpSockets.get(key); if (!entry) return; udpSockets.delete(key);
  if (entry.timer) clearTimeout(entry.timer); try { entry.socket.close(); } catch (_) {}
}
function closeUdpAssociation(id) { for (const [key, entry] of udpSockets.entries()) if (entry.id === id) closeUdpFlow(key); }
function refreshUdpTimer(key) { const entry = udpSockets.get(key); if (!entry) return; if (entry.timer) clearTimeout(entry.timer); entry.timer = setTimeout(() => closeUdpFlow(key), UDP_IDLE_TIMEOUT_MS); }
async function relayUdp(message) {
  const id = message.id, host = typeof message.host === 'string' ? message.host.trim() : '', port = Number(message.port);
  if (!validId(id) || !host || host.length > 253 || !Number.isInteger(port) || port < 1 || port > 65535 || typeof message.data !== 'string' || message.data.length > 10930) { send({ type: 'udp-error', id: validId(id) ? id : '', error: 'Invalid UDP datagram.' }); return; }
  const data = Buffer.from(message.data, 'base64'); if (!data.length || data.length > MAX_UDP_DATAGRAM_BYTES) { send({ type: 'udp-error', id, error: 'UDP datagram size is not supported.' }); return; }
  const key = id + '|' + host.toLowerCase() + '|' + port;
  try {
    let entry = udpSockets.get(key);
    if (!entry) {
      if (udpSockets.size >= MAX_UDP_FLOWS) { send({ type: 'udp-error', id, error: 'Source UDP flow limit reached.' }); return; }
      const target = await resolvePublicHost(host); if (!socket || socket.readyState !== WebSocket.OPEN) return;
      const udp = dgram.createSocket(target.family === 6 ? 'udp6' : 'udp4'); entry = { id, host, port, socket: udp, timer: null, target }; udpSockets.set(key, entry);
      udp.on('message', response => { if (response.length > MAX_UDP_DATAGRAM_BYTES || !socket || socket.readyState !== WebSocket.OPEN) return; if (socket.bufferedAmount > MAX_WS_BUFFERED_BYTES) return closeUdpFlow(key); send({ type: 'udp-data', id, host, port, data: response.toString('base64') }); refreshUdpTimer(key); });
      udp.on('error', error => { send({ type: 'udp-error', id, error: String(error.message || 'UDP socket failed').slice(0, 160) }); closeUdpFlow(key); });
    }
    refreshUdpTimer(key); entry.socket.send(data, port, entry.target.address, error => { if (error) send({ type: 'udp-error', id, error: String(error.message || 'UDP send failed').slice(0, 160) }); });
  } catch (error) { send({ type: 'udp-error', id, error: String(error.message || 'UDP destination rejected').slice(0, 160) }); }
}
function closeStream(id, sendClose) {
  const entry = streams.get(id);
  if (!entry) return;
  streams.delete(id);
  entry.finished = true;
  if (sendClose) send({ type: "tcp-close", id });
  try { entry.socket.destroy(); } catch (_) {}
}
async function openStream(message) {
  const id = message.id;
  const host = typeof message.host === "string" ? message.host.trim() : "";
  const port = Number(message.port);
  if (!validId(id) || !host || host.length > 253 || (!Number.isInteger(port) || port < 1 || port > 65535) || streams.has(id)) {
    send({ type: "tcp-error", id: validId(id) ? id : "", error: "Invalid or unsupported TCP destination." });
    return;
  }
  if (streams.size >= 8) {
    send({ type: "tcp-error", id, error: "Source endpoint stream limit reached." });
    return;
  }

  pendingOpens.add(id);
  try {
    const target = await resolvePublicHost(host);
    if (!pendingOpens.has(id) || !socket || socket.readyState !== WebSocket.OPEN) return;
    pendingOpens.delete(id);
    const tcp = net.connect({ host: target.address, family: target.family, port });
    const entry = { socket: tcp, opened: false, finished: false };
    streams.set(id, entry);
    tcp.setTimeout(15000, () => tcp.destroy(new Error("TCP connection timed out.")));

    tcp.once("connect", () => {
      if (entry.finished) return;
      entry.opened = true;
      tcp.setTimeout(5 * 60 * 1000, () => tcp.destroy(new Error("TCP stream idle timeout.")));
      send({ type: "tcp-opened", id });
    });
    tcp.on("data", chunk => {
      if (entry.finished) return;
      tcp.setTimeout(5 * 60 * 1000, () => tcp.destroy(new Error("TCP stream idle timeout.")));
      if (socket && socket.bufferedAmount > MAX_WS_BUFFERED_BYTES) {
        closeStream(id, true);
        return;
      }
      for (let offset = 0; offset < chunk.length; offset += MAX_CHUNK) {
        send({ type: "tcp-data", id, data: chunk.subarray(offset, offset + MAX_CHUNK).toString("base64") });
      }
    });
    tcp.on("error", error => {
      if (entry.finished) return;
      entry.finished = true;
      streams.delete(id);
      if (!entry.opened) send({ type: "tcp-error", id, error: String(error.message || "TCP connection failed").slice(0, 180) });
      else send({ type: "tcp-close", id });
    });
    tcp.on("close", () => {
      if (entry.finished) return;
      entry.finished = true;
      streams.delete(id);
      if (entry.opened) send({ type: "tcp-close", id });
    });
  } catch (error) {
    pendingOpens.delete(id);
    send({ type: "tcp-error", id, error: String(error.message || "Destination rejected").slice(0, 180) });
  }
}

function connect() {
  if (stopped) return;
  socket = new WebSocket(tunnelUrl, { perMessageDeflate: false, maxPayload: 24 * 1024 });
  const currentSocket = socket;
  let heartbeatAlive = true;
  currentSocket.on("pong", () => { heartbeatAlive = true; });
  currentSocket.on("open", () => {
    heartbeatTimer = setInterval(() => {
      if (currentSocket.readyState !== WebSocket.OPEN) return;
      if (!heartbeatAlive) {
        console.error("Dream Tunnel heartbeat timed out; reconnecting.");
        currentSocket.terminate();
        return;
      }
      heartbeatAlive = false;
      try {
        currentSocket.ping();
      } catch (error) {
        console.error("Dream Tunnel heartbeat failed:", error.message);
        currentSocket.terminate();
      }
    }, 25000);
    Promise.all([detectSourceName(), detectSourceCoordinates()]).then(([sourceName, location]) => {
      if (stopped || currentSocket !== socket || currentSocket.readyState !== WebSocket.OPEN) return;
      currentSocket.send(JSON.stringify({ type: "register", role: "source", sourceToken, sourceId, sourceName, latitude: location.latitude, longitude: location.longitude }));
    }).catch(() => {
      if (!stopped && currentSocket === socket && currentSocket.readyState === WebSocket.OPEN) {
        currentSocket.send(JSON.stringify({ type: "register", role: "source", sourceToken, sourceId, sourceName: os.hostname(), latitude: sourceLatitude, longitude: sourceLongitude }));
      }
    });
  });
  currentSocket.on("message", raw => {
    let message;
    try { message = JSON.parse(raw.toString("utf8")); } catch (_) { return; }
    if (message.type === "registered" && message.role === "source") {
      reconnectDelayMs = 5000;
      console.log("Dream Tunnel source authenticated; relay is waiting for an authorized receiver pairing.");
      if (healthTimer) clearInterval(healthTimer);
      checkSourceInternet();
      healthTimer = setInterval(checkSourceInternet, 60000);
      if (pairingRefreshTimer) clearInterval(pairingRefreshTimer);
      pairingRefreshTimer = setInterval(() => {
        if (currentSocket.readyState === WebSocket.OPEN) {
          currentSocket.send(JSON.stringify({ type: "request-pairing-code" }));
        }
      }, 8 * 60 * 1000);
    } else if (message.type === "pairing-code-rate-limited") {
      console.log("Pairing code refresh rate-limited by server; a new code will be requested on the next refresh cycle.");
    } else if (message.type === "pairing-code" && typeof message.code === "string") {
      console.log("\\nDream Wi-Fi receiver pairing code: " + message.code);
      console.log("Give this code only to the receiver you authorize. It expires in " + Math.ceil(Number(message.expiresInSeconds || 600) / 60) + " minutes and can be used once.\\n");
    } else if (message.type === "pairings-revoked") {
      console.log("All receiver pairings for this source have been revoked.");
    } else if (message.type === "ping" && typeof message.id === "string") {
      send({ type: "pong", id: message.id });
    } else if (message.type === "tcp-open") {
      openStream(message);
    } else if (message.type === "udp-send") {
      relayUdp(message);
    } else if (message.type === "udp-close" && validId(message.id)) {
      closeUdpAssociation(message.id);
    } else if (message.type === "tcp-data" && validId(message.id)) {
      const entry = streams.get(message.id);
      if (!entry || entry.finished || typeof message.data !== "string") return;
      const data = Buffer.from(message.data, "base64");
      if (data.length > MAX_CHUNK) return closeStream(message.id, true);
      if (entry.socket.writableLength > MAX_WS_BUFFERED_BYTES) return closeStream(message.id, true);
      entry.socket.write(data);
      entry.socket.setTimeout(5 * 60 * 1000, () => entry.socket.destroy(new Error("TCP stream idle timeout.")));
    } else if (message.type === "tcp-close" && validId(message.id)) {
      pendingOpens.delete(message.id);
      closeStream(message.id, false);
    } else if (message.type === "error") {
      console.error("Dream Tunnel source rejected:", String(message.error || "unknown error"));
      currentSocket.close(1008, "Source rejected");
    }
  });
  currentSocket.on("close", (code, reason) => {
    if (socket !== currentSocket) return;
    if (heartbeatTimer) clearInterval(heartbeatTimer);
    if (healthTimer) clearInterval(healthTimer);
    if (pairingRefreshTimer) clearInterval(pairingRefreshTimer);
    heartbeatTimer = null;
    healthTimer = null;
    pairingRefreshTimer = null;
    pendingOpens.clear();
    for (const id of [...streams.keys()]) closeStream(id, false);
    for (const key of [...udpSockets.keys()]) closeUdpFlow(key);
    if (code === 1008) {
      stopped = true;
      console.error("Dream Tunnel source authorization was rejected; automatic retries stopped.");
      return;
    }
    if (!stopped) {
      const delay = reconnectDelayMs;
      reconnectDelayMs = Math.min(reconnectDelayMs * 2, 60000);
      const detail = reason && reason.length ? reason.toString("utf8") : "no reason supplied";
      console.error("Dream Tunnel source WebSocket closed (code " + code + "; " + detail + ").");
      console.log("Dream Tunnel source reconnecting in " + Math.ceil(delay / 1000) + " seconds.");
      reconnectTimer = setTimeout(connect, delay);
    }
  });
  currentSocket.on("error", error => {
    console.error("Dream Tunnel source WebSocket error:", error.message);
  });
}

function shutdown() {
  stopped = true;
  if (reconnectTimer) clearTimeout(reconnectTimer);
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  if (healthTimer) clearInterval(healthTimer);
  if (pairingRefreshTimer) clearInterval(pairingRefreshTimer);
  heartbeatTimer = null;
  healthTimer = null;
  pairingRefreshTimer = null;
  for (const id of [...streams.keys()]) closeStream(id, false);
  for (const key of [...udpSockets.keys()]) closeUdpFlow(key);
  if (socket) socket.close();
}
process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);
if (process.stdin.isTTY) {
  const terminal = readline.createInterface({ input: process.stdin, output: process.stdout });
  terminal.on("line", line => {
    const command = line.trim().toLowerCase();
    if (command === "revoke") {
      send({ type: "revoke-pairings" });
      console.log("Requested revocation of all receiver pairings for this source.");
    } else if (command === "help") {
      console.log("Source commands: revoke (invalidate all receiver pairings), help.");
    }
  });
}
connect();
