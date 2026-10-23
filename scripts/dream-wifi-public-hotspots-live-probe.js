"use strict";

const { buildOverpassQuery } = require("../product/dream_wifi_public_hotspots");

const endpoints = [
  "https://overpass-api.de/api/interpreter",
  "https://overpass.kumi.systems/api/interpreter",
  "https://osm.hpi.de/overpass/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
];

async function main() {
  const query = buildOverpassQuery(-0.56, 34.56, 5);
  const failures = [];
  for (const endpoint of endpoints) {
    try {
      const response = await fetch(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded;charset=UTF-8",
          "accept": "application/json",
          "user-agent": "Dream-Wi-Fi/1.0 (+https://github.com/mjnr0477/dream-wifi-live)"
        },
        body: new URLSearchParams({ data: query }),
        signal: AbortSignal.timeout(5000)
      });
      if (!response.ok) throw new Error("HTTP " + response.status);
      const payload = await response.json();
      if (!payload || !Array.isArray(payload.elements)) throw new Error("invalid JSON response");
      console.log("Dream Wi-Fi live map-provider probe passed: " + new URL(endpoint).hostname + " returned valid data (" + payload.elements.length + " raw map elements).");
      return;
    } catch (error) {
      failures.push(new URL(endpoint).hostname + ": " + String(error.message || error).slice(0, 120));
    }
  }
  // The external map service is not a startup dependency. Report a warning but
  // keep Dream Tunnel and the participating-source directory available.
  console.warn("WARNING: live map-provider probe could not reach Overpass: " + failures.join("; "));
  console.warn("Public hotspot lookup may be temporarily unavailable; participating Dream Wi-Fi sources remain independent.");
}

main().catch(error => {
  console.warn("WARNING: live map-provider probe did not complete: " + String(error.message || error));
});
