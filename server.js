"use strict";
require("dotenv").config();
const http = require("node:http");
const path = require("node:path");
const fs = require("node:fs");
const express = require("express");
const app = express();
app.set("trust proxy", 1);
const port = Number(process.env.PORT || 3000);
const sourceToken = String(process.env.DREAM_WIFI_SOURCE_TOKEN || "");
const publicDir = path.join(__dirname, "public");
const dataDir = path.resolve(process.env.DREAM_WIFI_DATA_DIR || path.join(__dirname, "data"));
const registryFile = path.join(dataDir, "sources.json");
const sourceCredentialsFile = path.join(dataDir, "source-credentials.json");
const { fetchPublicHotspots } = require("./product/dream_wifi_public_hotspots");
let knownSources = new Map();
const sourceEnrollmentAttempts = new Map();
let enrolledSourceCredentials = [];
try {
  const savedCredentials = JSON.parse(fs.readFileSync(sourceCredentialsFile, "utf8"));
  if (Array.isArray(savedCredentials)) enrolledSourceCredentials = savedCredentials.filter(credential =>
    credential && typeof credential.sourceId === "string" &&
    /^[a-zA-Z0-9_-]{1,80}$/.test(credential.sourceId) &&
    typeof credential.tokenHash === "string" && /^[a-f0-9]{64}$/.test(credential.tokenHash)
  ).slice(-10000);
} catch (_) {}
function saveSourceCredentials(credential) {
  const byHash = new Map(enrolledSourceCredentials.map(item => [item.tokenHash, item]));
  byHash.set(credential.tokenHash, credential);
  enrolledSourceCredentials = [...byHash.values()].slice(-10000);
  try {
    fs.mkdirSync(dataDir, { recursive: true });
    const temp = sourceCredentialsFile + ".tmp";
    fs.writeFileSync(temp, JSON.stringify(enrolledSourceCredentials), { mode: 0o600 });
    try { fs.chmodSync(temp, 0o600); } catch (_) {}
    fs.renameSync(temp, sourceCredentialsFile);
  } catch (error) {
    console.error("Could not persist source credentials. Configure DREAM_WIFI_DATA_DIR to a writable persistent disk:", error.code || "write_error");
  }
}
try {
  const saved = JSON.parse(fs.readFileSync(registryFile, "utf8"));
  if (Array.isArray(saved)) for (const source of saved) if (source && source.id) knownSources.set(source.id, source);
} catch (_) {}
function saveRegistry() {
  try {
    fs.mkdirSync(dataDir, { recursive: true });
    const temp = registryFile + ".tmp";
    fs.writeFileSync(temp, JSON.stringify([...knownSources.values()].slice(-1000), null, 2), { mode: 0o600 });
    fs.renameSync(temp, registryFile);
  } catch (error) {
    console.error("Could not persist source directory. Configure DREAM_WIFI_DATA_DIR to a writable persistent disk:", error.code || "write_error");
  }
}
function coordinate(value, min, max) {
  const n = Number(value);
  return Number.isFinite(n) && n >= min && n <= max ? n : null;
}
function distanceKm(aLat, aLon, bLat, bLon) {
  const rad = n => n * Math.PI / 180;
  const dLat = rad(bLat - aLat), dLon = rad(bLon - aLon);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
}
app.disable("x-powered-by");
app.use((req, res, next) => { res.set("X-Content-Type-Options", "nosniff"); res.set("Referrer-Policy", "no-referrer"); res.set("Cache-Control", "no-store"); next(); });
app.use(express.json({ limit: "32kb" }));
app.get("/healthz", (_req, res) => res.json({ ok: true, service: "dream-wifi-live", database: false }));
app.get("/api/dream-wifi/config", (_req, res) => res.json({ configured: Boolean(sourceToken), source_enrollment: true, database: false, authentication: "one-time-source-pairing" }));
app.post("/api/dream-wifi/source-enroll", (req, res) => {
  const now = Date.now();
  const key = String(req.ip || req.socket.remoteAddress || "unknown");
  for (const [ip, attempt] of sourceEnrollmentAttempts.entries()) if (attempt.resetAt <= now) sourceEnrollmentAttempts.delete(ip);
  const attempt = sourceEnrollmentAttempts.get(key) || { count: 0, resetAt: now + 60 * 60 * 1000 };
  if (attempt.resetAt <= now) { attempt.count = 0; attempt.resetAt = now + 60 * 60 * 1000; }
  if (attempt.count >= 5) return res.status(429).json({ success: false, error: "Source enrollment limit reached for this network. Try again later." });
  attempt.count += 1;
  sourceEnrollmentAttempts.set(key, attempt);
  const enrollment = dreamWifiTunnel.enrollSource();
  res.status(201).json({
    success: true,
    source_id: enrollment.sourceId,
    source_token: enrollment.sourceToken,
    note: "Keep this source token private. It is scoped to this source; only its SHA-256 hash is persisted. Restart recovery works only when DREAM_WIFI_DATA_DIR points to durable writable storage."
  });
});
app.get("/api/wifi/source-status", (_req, res) => { const sources = dreamWifiTunnel.getSources(); res.json({ success: true, online_count: sources.length, internet_ready_count: sources.filter(source => source.internetReady === true).length, note: "Relay-connected sources and source-side DNS/TCP reachability are separate signals; neither replaces an end-to-end receiver relay test." }); });
const server = http.createServer(app);
const dreamWifiTunnel = require("./product/dream_wifi_tunnel_engine").attachDreamWifiTunnel(server, {
  sourceToken,
  enrolledSourceCredentials,
  onEnrollSource: saveSourceCredentials
});
app.get("/api/wifi/sources", (req, res) => {
  const latitude = coordinate(req.query.latitude, -90, 90);
  const longitude = coordinate(req.query.longitude, -180, 180);
  const radiusKm = Number(req.query.radius_km || 300);
  if (latitude === null || longitude === null) return res.status(400).json({ success: false, error: "Allow location access to search nearby services." });
  if (!Number.isFinite(radiusKm) || radiusKm < 1 || radiusKm > 300) return res.status(400).json({ success: false, error: "radius_km must be between 1 and 300." });
  const live = dreamWifiTunnel.getSources();
  const now = new Date().toISOString();
  for (const source of live) {
    const lat = coordinate(source.latitude, -90, 90), lon = coordinate(source.longitude, -180, 180);
    if (lat === null || lon === null) continue;
    const prior = knownSources.get(source.id) || {};
    knownSources.set(source.id, { id: source.id, name: source.name, latitude: lat, longitude: lon, first_seen: prior.first_seen || now, last_seen: now });
  }
  saveRegistry();
  const liveById = new Map(live.map(source => [source.id, source]));
  const liveIds = new Set(liveById.keys());
  const sources = [...knownSources.values()].map(source => {
    const liveSource = liveById.get(source.id);
    return {
      ...source,
      connected: liveIds.has(source.id),
      internet_ready: liveSource ? liveSource.internetReady : null,
      internet_checked_at: liveSource ? liveSource.internetCheckedAt : null,
      internet_error: liveSource ? liveSource.internetError : null,
      distance_km: Math.round(distanceKm(latitude, longitude, source.latitude, source.longitude) * 10) / 10
    };
  }).filter(source => source.distance_km <= radiusKm)
    .sort((a, b) => a.distance_km - b.distance_km).slice(0, 200);
  res.json({ success: true, radius_km: radiusKm, sources, count: sources.length, online_count: sources.filter(source => source.connected).length,
    note: "Online means the source relay is connected. internet_ready is a separate source-side DNS/TCP reachability probe, not proof that a receiver's end-to-end relay test will succeed." });
});
app.get("/api/wifi/hotspots", async (req, res) => {
  const latitude = coordinate(req.query.latitude, -90, 90);
  const longitude = coordinate(req.query.longitude, -180, 180);
  const radiusKm = Number(req.query.radius_km || 300);
  if (latitude === null || longitude === null) return res.status(400).json({ success: false, error: "Allow location access to search public hotspots." });
  if (!Number.isFinite(radiusKm) || radiusKm < 1 || radiusKm > 300) return res.status(400).json({ success: false, error: "radius_km must be between 1 and 300." });
  try {
    const result = await fetchPublicHotspots({ latitude, longitude, radiusKm });
    res.set("Cache-Control", "public, max-age=300");
    return res.json(result);
  } catch (error) {
    console.error("Public Wi-Fi hotspot lookup failed:", String(error.message || "lookup_error").slice(0, 180));
    return res.status(503).json({ success: false, error: "The public Wi-Fi map is temporarily unavailable. Your participating-source search is still available; try public hotspot search again shortly." });
  }
});
app.use(express.static(publicDir));
app.use((req, res, next) => { if (req.method === "GET" && !req.path.startsWith("/api/")) return res.sendFile(path.join(publicDir, "index.html")); next(); });
app.use((_req, res) => res.status(404).json({ success: false, error: "Not found." }));
server.listen(port, process.env.DREAM_WIFI_BIND_HOST || "0.0.0.0", () => console.log("Dream Wi-Fi listening on port " + port + " (standalone source-paired relay)"));
