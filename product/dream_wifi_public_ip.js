"use strict";

const net = require("node:net");

function isPublicAddress(address) {
  const family = net.isIP(address);
  if (family === 4) {
    const parts = address.split(".").map(Number);
    if (parts.length !== 4 || parts.some(n => !Number.isInteger(n) || n < 0 || n > 255)) return false;
    const [a, b, c] = parts;
    if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
    if (a === 100 && b >= 64 && b <= 127) return false; // Shared address space (100.64/10).
    if (a === 169 && b === 254) return false; // Link-local.
    if (a === 172 && b >= 16 && b <= 31) return false; // Private.
    if (a === 192 && b === 168) return false; // Private.
    if (a === 192 && b === 0 && (c === 0 || c === 2)) return false; // IETF special-purpose and documentation.
    if (a === 192 && b === 88 && c === 99) return false; // Deprecated 6to4 relay.
    if (a === 198 && (b === 18 || b === 19)) return false; // Benchmarking.
    if (a === 198 && b === 51 && c === 100) return false; // Documentation.
    if (a === 203 && b === 0 && c === 113) return false; // Documentation.
    return true;
  }
  if (family === 6) {
    const value = address.toLowerCase();
    const words = value.split(":");
    const first = parseInt(words[0] || "0", 16);
    const second = parseInt(words[1] || "0", 16);
    // Only allow global-unicast space; reject special/documentation transition ranges.
    if (first < 0x2000 || first > 0x3fff) return false;
    if (first === 0x2001 && (second <= 0x01ff || second === 0x0db8)) return false;
    if (first === 0x2002) return false; // 6to4 embeds IPv4 routing semantics.
    if (first === 0x3fff && second <= 0x0fff) return false; // Documentation range.
    return true;
  }
  return false;
}

module.exports = { isPublicAddress };
