"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");

const page = fs.readFileSync("public/dream-wifi-page.js", "utf8");
const html = fs.readFileSync("public/dream-wifi.html", "utf8");

for (const required of [
  'get("county-search")',
  'navigator.geolocation.getCurrentPosition',
  '/api/dream-wifi/registry/observations?',
  'Authorization: "Bearer " + accessToken',
  'radius_km: String(radius)',
  'center === "kibabii"',
  'requestObservations(0.5698, 34.5584, "the Kibabii area")',
  'center === "coordinates"',
  'Enter a valid latitude and longitude',
  'renderCountyObservations(result.observations)',
  'observation.remote_access_ready === true',
  'observation.internet_verified',
  'textContent = observation.ssid',
  'replaceChildren()',
  'last seen ',
  'not a live radio scan or proof of remote tunnel access'
]) {
  assert.ok(page.includes(required), "County tracker implementation is missing: " + required);
}
assert.ok(html.includes('id="county-search"'), "County tracker search button is missing.");
assert.ok(html.includes('src="/dream-wifi-page.js?v=20261022d"'), "Dashboard script cache-bust version must be updated when the tracker changes.");
assert.ok(html.includes('id="county-radius"'), "County tracker radius control is missing.");
assert.ok(html.includes('id="county-center"'), "County tracker search center selector is missing.");
assert.ok(html.includes('id="county-latitude"'), "County tracker custom latitude input is missing.");
assert.ok(html.includes('id="county-longitude"'), "County tracker custom longitude input is missing.");
assert.ok(html.includes('value="kibabii">Kibabii area'), "County tracker Kibabii preset is missing.");
assert.ok(!html.includes('href="http://quant.com/status"'), "Do not invent a provider portal URL.");
assert.ok(html.includes('Open Wi-Fi portal'), "Explain how to open the portal through the native VPN.");
assert.ok(html.includes('id="county-results"'), "County tracker results list is missing.");
assert.ok(html.includes("cannot identify phones connected to a remote router"), "Tracker must not imply it can identify connected devices.");
assert.ok(html.includes("github.com/mjnr0477/dream-education-live/blob/main/docs/dream-wifi-source-agent-without-app.md"), "Standalone source-agent limitations must be linked.");
assert.ok(html.includes('id="wifi-generate-source-ticket"'), "Standalone source ticket button is missing.");
assert.ok(html.includes('id="wifi-source-ticket-output"'), "Temporary source ticket output is missing.");
assert.ok(html.includes("Treat this like a password"), "Source ticket handling warning is missing.");
assert.ok(page.includes('fetch("/api/dream-wifi/source-ticket"'), "Dashboard must request tickets from the authenticated endpoint.");
assert.ok(page.includes("navigator.clipboard.writeText(value)"), "Source ticket copy action is missing.");
assert.ok(page.includes("sourceTicketOutput.hidden = true"), "Previous source ticket must be hidden before generating a replacement.");
const sourceAgentDocs = fs.readFileSync("docs/dream-wifi-source-agent-without-app.md", "utf8");
assert.ok(sourceAgentDocs.includes("informed opt-in"), "Source agent documentation must require informed opt-in.");
assert.ok(sourceAgentDocs.includes("does not bind sockets to a specific Android Wi-Fi network"), "Standalone agent routing limitation must be documented.");
assert.ok(sourceAgentDocs.includes("read -rsp"), "Standalone Termux instructions must avoid echoing source tickets.");
assert.ok(sourceAgentDocs.includes("DREAM_WIFI_TUNNEL_URL='wss://dream-education-live-4.onrender.com/api/dream-education/dream-wifi/tunnel'"), "Standalone agent setup must use the deployed tunnel URL.");
assert.ok(sourceAgentDocs.includes("Ctrl+C"), "Standalone agent instructions must explain how to stop and withdraw the source.");

assert.ok(!page.includes('list.innerHTML'), "Registry SSIDs must not be inserted as HTML.");
console.log("Dream Wi-Fi county tracker UI smoke test passed.");
