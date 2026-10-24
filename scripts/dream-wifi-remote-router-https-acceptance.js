"use strict";
require("dotenv").config();
const assert = require("node:assert/strict");
const tls = require("node:tls");
const { Duplex } = require("node:stream");
const { WebSocket } = require("ws");
const url = String(process.env.DREAM_WIFI_TUNNEL_URL || "").trim();
const code = String(process.env.DREAM_WIFI_PAIRING_CODE || "").trim().toUpperCase();
const token = String(process.env.DREAM_WIFI_ACCESS_TOKEN || "").trim();
let sourceId = String(process.env.DREAM_WIFI_SOURCE_ID || "").trim();
const timeout = Math.max(5000, Math.min(60000, Number(process.env.DREAM_WIFI_ACCEPTANCE_TIMEOUT_MS || 25000)));
const chunkSize = 12 * 1024;
if (!/^wss:\/\//i.test(url)) throw new Error("Set DREAM_WIFI_TUNNEL_URL to the secure wss:// endpoint.");
if (!token && !/^[A-F0-9]{12}$/.test(code)) throw new Error("Set DREAM_WIFI_ACCESS_TOKEN or a fresh 12-character DREAM_WIFI_PAIRING_CODE.");

function connect() {
  const socket = new WebSocket(url, { handshakeTimeout: 15000, perMessageDeflate: false });
  const queue = [], waiters = [], listeners = new Set();
  socket.on("message", raw => {
    let msg; try { msg = JSON.parse(raw.toString()); } catch (_) { return; }
    for (const listener of listeners) { try { listener(msg); } catch (_) {} }
    const index = waiters.findIndex(w => w.predicate(msg));
    if (index >= 0) { const w = waiters.splice(index, 1)[0]; clearTimeout(w.timer); w.resolve(msg); }
    else queue.push(msg);
  });
  socket.on("error", error => waiters.splice(0).forEach(w => { clearTimeout(w.timer); w.reject(error); }));
  return { socket, onMessage(listener) { listeners.add(listener); return () => listeners.delete(listener); }, next(predicate, label) {
    const index = queue.findIndex(predicate);
    if (index >= 0) return Promise.resolve(queue.splice(index, 1)[0]);
    return new Promise((resolve, reject) => {
      const w = { predicate, resolve, reject, timer: setTimeout(() => {
        const i = waiters.indexOf(w); if (i >= 0) waiters.splice(i, 1);
        reject(new Error("Timed out waiting for " + label));
      }, timeout) };
      waiters.push(w);
    });
  }};
}
function open(endpoint) {
  return new Promise((resolve, reject) => {
    endpoint.socket.once("open", resolve); endpoint.socket.once("error", reject);
  });
}
function verifyHttps(endpoint, id, host, requestPath, validateBody) {
  return new Promise((resolve, reject) => {
    let settled = false, response = "";
    let secure;
    const finish = (error, result) => {
      if (settled) return; settled = true; clearTimeout(timer);
      if (typeof unsubscribe === "function") unsubscribe();
      try { secure.destroy(); } catch (_) {}
      if (error) reject(error); else resolve(result);
    };
    const timer = setTimeout(() => finish(new Error("Timed out waiting for HTTPS through the remote router.")), timeout);
    const relay = new Duplex({
      read() {},
      write(value, encoding, callback) {
        const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value, encoding);
        if (endpoint.socket.readyState !== WebSocket.OPEN) return callback(new Error("Tunnel disconnected."));
        let offset = 0;
        const next = () => {
          if (offset >= bytes.length) return callback();
          const part = bytes.subarray(offset, offset + chunkSize); offset += part.length;
          endpoint.socket.send(JSON.stringify({type:"tcp-data",id,data:part.toString("base64")}), err => err ? callback(err) : next());
        };
        next();
      },
      final(callback) {
        if (endpoint.socket.readyState === WebSocket.OPEN) endpoint.socket.send(JSON.stringify({type:"tcp-close",id}), callback);
        else callback();
      }
    });
    const unsubscribe = endpoint.onMessage(message => {
      if (message.id !== id) return;
      if (message.type === "tcp-data") {
        try { relay.push(Buffer.from(message.data || "", "base64")); }
        catch (error) { finish(error); }
      } else if (message.type === "tcp-close") {
        relay.push(null);
      } else if (message.type === "tcp-error") {
        finish(new Error("Remote source TCP relay failed: " + String(message.error || "unknown error")));
      }
    });
    secure = tls.connect({socket:relay,servername:host,rejectUnauthorized:true,ALPNProtocols:["http/1.1"]});
    secure.setTimeout(timeout, () => finish(new Error("HTTPS timed out through the remote router.")));
    secure.once("secureConnect", () => {
      if (!secure.authorized) return finish(new Error("TLS certificate validation failed."));
      secure.write("GET " + requestPath + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\nUser-Agent: Dream-WiFi-Remote-Router-Acceptance/1.0\r\nAccept: text/plain, text/html\r\n\r\n");
    });
    secure.on("data", part => {
      response += part.toString("latin1");
      if (response.length > 256 * 1024) finish(new Error("HTTPS response exceeded 256 KiB."));
    });
    secure.once("error", error => finish(error));
    secure.once("end", () => {
      const status = response.split("\r\n",1)[0] || "";
      const headEnd = response.indexOf("\r\n\r\n");
      const body = headEnd < 0 ? "" : response.slice(headEnd + 4).toLowerCase();
      try {
        assert.match(status,/^HTTP\/1\.[01] 200(?: |$)/,"Expected HTTP 200 through remote router.");
        validateBody(body);
        finish(null,{status,bytes:Buffer.byteLength(response,"latin1"),body:body.trim()});
      } catch (error) { finish(error); }
    });
  });
}
async function main() {
  let endpoint, streamId = "https-acceptance-" + Date.now().toString(36);
  try {
    endpoint = connect(); await open(endpoint);
    const registration = {type:"register",role:"client"};
    if (token) registration.accessToken = token; else registration.pairingCode = code;
    endpoint.socket.send(JSON.stringify(registration));
    const registered = await endpoint.next(m => m.type === "registered" || m.type === "error","receiver authorization");
    if (registered.type === "error") throw new Error("Receiver authorization failed: " + String(registered.error || "unknown"));
    if (!sourceId) sourceId = String(registered.sourceId || "");
    if (!sourceId) throw new Error("No source ID returned; set DREAM_WIFI_SOURCE_ID.");
    console.log("PASS: receiver authorized to source",sourceId);
    endpoint.socket.send(JSON.stringify({type:"tcp-open",id:streamId,host:"example.com",port:443,sourceId}));
    const opened = await endpoint.next(m => (m.type === "tcp-opened" || m.type === "tcp-error") && m.id === streamId,"source TCP connection");
    if (opened.type === "tcp-error") throw new Error("Source could not open example.com:443: " + String(opened.error || "unknown"));
    console.log("PASS: source opened example.com:443");
    const startedAt = Date.now();
    const result = await verifyHttps(endpoint,streamId,"example.com","/",body => {
      assert.match(body,/example domain/,"Unexpected HTTPS body from example.com.");
    });
    console.log("PASS: TLS certificate verified; example.com returned",result.status);
    console.log("PASS:",result.bytes,"response bytes traversed the tunnel.");
    const ipResult = await verifyHttps(endpoint,streamId + "-egress","api.ipify.org","/",body => {
      assert.match(body,/^(?:[0-9]{1,3}\.){3}[0-9]{1,3}$/,"Remote egress check did not return an IPv4 address.");
      const octets = body.split(".").map(Number);
      assert.ok(octets.every(n => n >= 0 && n <= 255),"Remote egress IP is malformed.");
    });
    console.log("PASS: public egress IP returned through remote router:",ipResult.body);
    console.log("PASS: HTTPS round-trip checks completed in",Date.now() - startedAt,"ms.");
    console.log("REMOTE ROUTER HTTPS + EGRESS ACCEPTANCE: PASSED");
  } finally {
    if (endpoint && endpoint.socket.readyState === WebSocket.OPEN) endpoint.socket.close(1000,"acceptance complete");
  }
}
main().catch(error => { console.error("REMOTE ROUTER HTTPS ACCEPTANCE: FAILED"); console.error(error.message || String(error)); process.exitCode = 1; });
