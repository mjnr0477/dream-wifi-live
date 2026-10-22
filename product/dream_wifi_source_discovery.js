"use strict";

// Local source-network discovery for Dream Wi-Fi.
// This inspects only the machine's own default route and local interfaces.
// It does not scan arbitrary networks or attempt to change router settings.

const fs = require("node:fs");
const os = require("node:os");
const net = require("node:net");
const dns = require("node:dns").promises;
const { execFile } = require("node:child_process");
const { promisify } = require("node:util");

const execFileAsync = promisify(execFile);
const INTERNET_PROBE_HOST = "example.com";
const INTERNET_PROBE_PORT = 443;

function isPrivateOrLocalAddress(address) {
  const family = net.isIP(address);
  if (family === 4) {
    const octets = address.split(".").map(Number);
    if (octets.length !== 4 || octets.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false;
    const [a, b] = octets;
    return a === 10 ||
      a === 127 ||
      (a === 169 && b === 254) ||
      (a === 172 && b >= 16 && b <= 31) ||
      (a === 192 && b === 168) ||
      (a === 100 && b >= 64 && b <= 127);
  }
  if (family === 6) {
    const value = address.toLowerCase();
    return value === "::1" || value.startsWith("fe80:") ||
      value.startsWith("fc") || value.startsWith("fd") ||
      value.startsWith("::ffff:127.");
  }
  return false;
}

function parseDefaultGateway(routeText) {
  const text = String(routeText || "");
  const match = text.match(/(?:^|\s)default\s+via\s+(\d{1,3}(?:\.\d{1,3}){3})(?:\s|$)/m);
  if (!match || !isPrivateOrLocalAddress(match[1]) || net.isIP(match[1]) !== 4) return null;
  return match[1];
}

function readProcRouteGateway() {
  try {
    const rows = fs.readFileSync("/proc/net/route", "utf8").trim().split(/\r?\n/).slice(1);
    for (const row of rows) {
      const fields = row.trim().split(/\s+/);
      if (fields.length < 3 || fields[1] !== "00000000") continue;
      const hex = fields[2];
      if (!/^[0-9A-Fa-f]{8}$/.test(hex)) continue;
      const gateway = [0, 2, 4, 6].map(offset => parseInt(hex.slice(offset, offset + 2), 16)).reverse().join(".");
      if (isPrivateOrLocalAddress(gateway)) return gateway;
    }
  } catch (_) {}
  return null;
}

async function discoverDefaultGateway() {
  try {
    const { stdout } = await execFileAsync("ip", ["-4", "route", "show", "default"], { timeout: 1500, maxBuffer: 8192 });
    const gateway = parseDefaultGateway(stdout);
    if (gateway) return gateway;
  } catch (_) {}
  return readProcRouteGateway();
}

function getLocalInterfaces() {
  const result = [];
  const interfaces = os.networkInterfaces();
  for (const [name, addresses] of Object.entries(interfaces)) {
    for (const entry of addresses || []) {
      if (!entry || entry.internal || !net.isIP(entry.address)) continue;
      result.push({ interface: name, family: entry.family, address: entry.address });
    }
  }
  return result;
}

function probeTcp(host, port, timeoutMs = 2500) {
  return new Promise(resolve => {
    let settled = false;
    const finish = result => {
      if (settled) return;
      settled = true;
      socket.destroy();
      resolve(result);
    };
    const socket = net.createConnection({ host, port });
    socket.setTimeout(timeoutMs);
    socket.once("connect", () => finish({ success: true }));
    socket.once("timeout", () => finish({ success: false, error: "timeout" }));
    socket.once("error", error => finish({ success: false, error: String(error.code || "connection_error") }));
  });
}

async function diagnoseSourceNetwork(options = {}) {
  const gateway = options.gateway === undefined ? await discoverDefaultGateway() : options.gateway;
  const interfaces = options.interfaces || getLocalInterfaces();
  let dnsResolved = false;
  let internetTcp = { success: false, error: "not_tested" };

  try {
    const addresses = await dns.lookup(options.internetHost || INTERNET_PROBE_HOST, { all: true });
    dnsResolved = addresses.length > 0;
  } catch (_) {}

  if (dnsResolved) {
    internetTcp = await (options.probeTcp || probeTcp)(
      options.internetHost || INTERNET_PROBE_HOST,
      options.internetPort || INTERNET_PROBE_PORT,
      options.timeoutMs || 2500
    );
  }

  return {
    success: Boolean(gateway && dnsResolved && internetTcp.success),
    networkLabel: String(process.env.DREAM_WIFI_SOURCE_NAME || os.hostname()).slice(0, 80),
    gateway: gateway && isPrivateOrLocalAddress(gateway) ? gateway : null,
    gatewayDetected: Boolean(gateway && isPrivateOrLocalAddress(gateway)),
    localInterfaces: interfaces,
    internet: {
      dnsResolved,
      tcp443Reachable: Boolean(internetTcp.success),
      error: internetTcp.success ? null : String(internetTcp.error || "unavailable").slice(0, 80)
    },
    checkedAt: new Date().toISOString()
  };
}

module.exports = {
  diagnoseSourceNetwork,
  discoverDefaultGateway,
  getLocalInterfaces,
  isPrivateOrLocalAddress,
  parseDefaultGateway,
  probeTcp
};
