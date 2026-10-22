"use strict";
require("dotenv").config();
const http = require("node:http");
const path = require("node:path");
const fs = require("node:fs");
const express = require("express");
const app = express();
const port = Number(process.env.PORT || 3000);
const sourceToken = String(process.env.DREAM_WIFI_SOURCE_TOKEN || "");
const publicDir = path.join(__dirname, "public");
const dataDir = path.resolve(process.env.DREAM_WIFI_DATA_DIR || path.join(__dirname, "data"));
const registryFile = path.join(dataDir, "sources.json");
let knownSources = new Map();
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
app.get("/api/dream-wifi/config", (_req, res) => res.json({ configured: Boolean(sourceToken), database: false, authentication: "public-client" }));
const server = http.createServer(app);
const dreamWifiTunnel = require("./product/dream_wifi_tunnel_engine").attachDreamWifiTunnel(server, { sourceToken });
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
  const liveIds = new Set(live.map(source => source.id));
  const sources = [...knownSources.values()].map(source => ({ ...source, connected: liveIds.has(source.id), distance_km: Math.round(distanceKm(latitude, longitude, source.latitude, source.longitude) * 10) / 10 }))
    .filter(source => source.distance_km <= radiusKm).sort((a, b) => a.distance_km - b.distance_km).slice(0, 200);
  res.json({ success: true, radius_km: radiusKm, sources, count: sources.length, online_count: sources.filter(source => source.connected).length,
    note: "The directory contains only participating Dream Wi-Fi sources with reported coordinates. Offline entries are not connectable. A website cannot scan every router across 300 km." });
});
app.use(express.static(publicDir));
app.use((req, res, next) => { if (req.method === "GET" && !req.path.startsWith("/api/")) return res.sendFile(path.join(publicDir, "index.html")); next(); });
app.use((_req, res) => res.status(404).json({ success: false, error: "Not found." }));
server.listen(port, "0.0.0.0", () => console.log("Dream Wi-Fi listening on port " + port + " (no Supabase; public client access)"));
