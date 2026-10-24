"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const root = path.join(__dirname, "..");
const server = fs.readFileSync(path.join(root, "server.js"), "utf8");
const page = fs.readFileSync(path.join(root, "public/discovery.html"), "utf8");
const js = fs.readFileSync(path.join(root, "public/discovery.js"), "utf8");
const appJs = fs.readFileSync(path.join(root, "public/app.js"), "utf8");
const activity = fs.readFileSync(path.join(root, "android/dream-wifi-vpn/app/src/main/java/com/mjnr0477/dreamwifi/DashboardActivity.java"), "utf8");
const manifest = fs.readFileSync(path.join(root, "android/dream-wifi-vpn/app/src/main/AndroidManifest.xml"), "utf8");
const { compareSourceCandidates, sourcePriority } = require(path.join(root, "product/dream_wifi_source_ranking.js"));
assert.match(server, /radiusKm > 20040/, "Participating source search must support worldwide radius.");
assert.match(server, /Worldwide search covers the registered Dream Wi-Fi source directory only/, "Worldwide search must explain that it is not a global router scanner.");
assert.match(server, /slice\(0, 200\)/, "Source response must remain capped to protect the API.");
const candidates = [
  { id: "far-ready", connected: true, internet_ready: true, distance_km: 450 },
  { id: "near-offline", connected: false, internet_ready: null, distance_km: 2 },
  { id: "near-ready", connected: true, internet_ready: true, distance_km: 12 },
  { id: "connected-unverified", connected: true, internet_ready: null, distance_km: 1 }
].sort(compareSourceCandidates);
assert.deepEqual(candidates.map(source => source.id), ["near-ready", "far-ready", "connected-unverified", "near-offline"],
  "Live internet-ready gateways must outrank nearer offline sources; distance breaks ties.");
assert.equal(sourcePriority({ connected: true, internet_ready: false }), 2);
assert.match(server, /sort\(compareSourceCandidates\)/, "API must apply gateway ranking before returning discovery results.");
assert.match(page, /Scan physical Wi-Fi/);
assert.match(page, /Participating Dream Wi-Fi sources/);
assert.match(page, /Mapped public Wi-Fi places/);
assert.match(page, /Internet, without the distance/);
assert.match(page, /value="500" selected/);
const landing = fs.readFileSync(path.join(root, "public/index.html"), "utf8");
assert.match(landing, /Find a connection/);
assert.match(landing, /Open discovery/);
assert.match(landing, /Receive internet/);
assert.match(landing, /Share internet/);
assert.doesNotMatch(landing, /id="hotspots"/, "Landing page should stay simple and direct users to the discovery page.");
assert.match(js, /radius = selected === "world" \? 20040/);
assert.match(js, /navigator\.geolocation\.watchPosition/);
assert.match(js, /lastAutoRefreshAt/);
assert.match(js, /then\(startLocationWatch\)/);
assert.match(js, /requestLocalWifiScan/);
assert.match(js, /data\.scan_started === true/, "Physical Wi-Fi discovery must wait for Android's asynchronous scan refresh.");
assert.match(js, /setTimeout\(resolve, 1800\)/, "Physical Wi-Fi discovery must allow Android time to refresh scan results.");
assert.match(js, /attempt < 2/, "Physical Wi-Fi discovery must bound retries and avoid an endless scan loop.");
assert.match(js, /internet-ready sources prioritized; speed is not measured yet/);
assert.match(js, /const rankedSources = \[\.\.\.data\.sources\]\.sort/);
assert.match(appJs, /internet-ready sources prioritized; speed is not measured yet/);
assert.match(appJs, /const rankedSources = \[\.\.\.data\.sources\]\.sort/);
assert.match(js, /openWifiSettings/);
assert.match(activity, /BuildConfig\.DREAM_WIFI_BASE_URL/);
assert.doesNotMatch(activity, /dream-wifi-live\.onrender\.com/, "Development app must not hardcode the published backend.");
assert.match(activity, /public String requestLocalWifiScan\(\)/);
assert.match(activity, /WifiNetworkSuggestion/);
assert.match(activity, /public String suggestOpenWifi\(String ssid\)/);
assert.match(js, /Connect to open Wi-Fi/);
assert.match(activity, /Settings\.ACTION_WIFI_SETTINGS/);
assert.match(manifest, /ACCESS_FINE_LOCATION/);
assert.match(manifest, /ACCESS_BACKGROUND_LOCATION/);
assert.match(manifest, /FOREGROUND_SERVICE_LOCATION/);
assert.match(manifest, /ACCESS_WIFI_STATE/);
assert.match(manifest, /CHANGE_WIFI_STATE/);
console.log("Dream Wi-Fi global/local discovery smoke test passed.");
