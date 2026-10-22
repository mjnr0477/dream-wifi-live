"use strict";

const crypto = require("node:crypto");

const ACCESS_STATES = new Set(["unknown", "authorized", "captive_portal", "payment_required", "blocked"]);
const rateBuckets = new Map();

function cleanText(value, maxLength) {
  return String(value == null ? "" : value).replace(/[\u0000-\u001f\u007f]/g, "").trim().slice(0, maxLength);
}

function normalizeObservation(input, userId, now = new Date()) {
  if (!input || typeof input !== "object" || Array.isArray(input)) return { error: "A JSON observation object is required." };

  const ssid = cleanText(input.ssid, 120);
  const latitude = Number(input.latitude);
  const longitude = Number(input.longitude);
  if (!ssid) return { error: "SSID is required." };
  if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
      !Number.isFinite(longitude) || longitude < -180 || longitude > 180) {
    return { error: "Valid latitude and longitude are required for a field observation." };
  }

  const accessState = ACCESS_STATES.has(input.access_state) ? input.access_state : "unknown";
  const gatewayDetected = input.gateway_detected === true;
  const internetVerified = input.internet_verified === true && gatewayDetected;
  const observedAtCandidate = Date.parse(input.observed_at || "");
  const observedAt = Number.isFinite(observedAtCandidate) &&
    Math.abs(now.getTime() - observedAtCandidate) <= 24 * 60 * 60 * 1000
      ? new Date(observedAtCandidate).toISOString()
      : now.toISOString();

  const fingerprint = crypto.createHash("sha256")
    .update(ssid.toLocaleLowerCase("en-US"))
    .digest("hex");

  return {
    record: {
      reporter_user_id: userId,
      network_fingerprint: fingerprint,
      ssid,
      latitude: Math.round(latitude * 1000) / 1000,
      longitude: Math.round(longitude * 1000) / 1000,
      gateway_detected: gatewayDetected,
      internet_verified: internetVerified,
      access_state: accessState,
      observed_at: observedAt,
      remote_access_ready: false
    }
  };
}

function allowRate(userId, now = Date.now()) {
  const windowMs = 60_000;
  const maxRequests = 30;
  const bucket = rateBuckets.get(userId);
  if (!bucket || now - bucket.startedAt >= windowMs) {
    rateBuckets.set(userId, { startedAt: now, count: 1 });
    return true;
  }
  bucket.count += 1;
  return bucket.count <= maxRequests;
}

function registerDreamWifiSourceRegistryRoutes(app, { supabaseAuth, supabase }) {
  if (!app || !supabaseAuth || !supabase) throw new TypeError("app, supabaseAuth and supabase are required");

  async function authenticatedUser(req, res) {
    const match = String(req.get("authorization") || "").match(/^Bearer\s+(.+)$/i);
    if (!match) {
      res.status(401).json({ success: false, error: "A valid Dream Education session is required." });
      return null;
    }
    const { data, error } = await supabaseAuth.auth.getUser(match[1].trim());
    if (error || !data || !data.user || !data.user.id) {
      res.status(401).json({ success: false, error: "Your session is invalid or expired." });
      return null;
    }
    return data.user;
  }

  app.post("/api/dream-wifi/registry/observations", async (req, res) => {
    res.set("Cache-Control", "no-store");
    const user = await authenticatedUser(req, res);
    if (!user) return;
    if (!allowRate(user.id)) return res.status(429).json({ success: false, error: "Observation rate limit reached." });

    const normalized = normalizeObservation(req.body, user.id);
    if (normalized.error) return res.status(400).json({ success: false, error: normalized.error });

    try {
      const { data, error } = await supabase
        .from("dream_wifi_source_observations")
        .insert(normalized.record)
        .select("id,ssid,latitude,longitude,gateway_detected,internet_verified,access_state,observed_at,remote_access_ready")
        .single();
      if (error) {
        console.error("Dream Wi-Fi registry insert failed:", error.message || "database error");
        return res.status(503).json({ success: false, error: "Source registry storage is not ready. Apply the Dream Wi-Fi registry migration." });
      }
      return res.status(201).json({ success: true, observation: data });
    } catch (error) {
      console.error("Dream Wi-Fi registry insert failed:", error && error.message ? error.message : "unknown error");
      return res.status(503).json({ success: false, error: "Source registry is temporarily unavailable." });
    }
  });

  app.get("/api/dream-wifi/registry/observations", async (req, res) => {
    res.set("Cache-Control", "no-store");
    const user = await authenticatedUser(req, res);
    if (!user) return;
    if (!allowRate(user.id)) return res.status(429).json({ success: false, error: "Observation query rate limit reached." });

    const latitude = Number(req.query.latitude);
    const longitude = Number(req.query.longitude);
    const radius = Number(req.query.radius_km || 25);
    if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 ||
        !Number.isFinite(longitude) || longitude < -180 || longitude > 180 ||
        !Number.isFinite(radius) || radius < 1 || radius > 100) {
      return res.status(400).json({ success: false, error: "Provide valid latitude, longitude, and radius_km between 1 and 100." });
    }

    const latDelta = radius / 111;
    const lonDelta = radius / Math.max(1, 111 * Math.cos(latitude * Math.PI / 180));
    try {
      const { data, error } = await supabase
        .from("dream_wifi_source_observations")
        .select("id,ssid,latitude,longitude,gateway_detected,internet_verified,access_state,observed_at,remote_access_ready")
        .gte("latitude", Math.max(-90, latitude - latDelta))
        .lte("latitude", Math.min(90, latitude + latDelta))
        .gte("longitude", Math.max(-180, longitude - lonDelta))
        .lte("longitude", Math.min(180, longitude + lonDelta))
        .order("observed_at", { ascending: false })
        .limit(500);
      if (error) {
        console.error("Dream Wi-Fi registry query failed:", error.message || "database error");
        return res.status(503).json({ success: false, error: "Source registry storage is not ready. Apply the Dream Wi-Fi registry migration." });
      }

      const toRadians = value => value * Math.PI / 180;
      const distanceKm = row => {
        const dLat = toRadians(row.latitude - latitude);
        const dLon = toRadians(row.longitude - longitude);
        const a = Math.sin(dLat / 2) ** 2 +
          Math.cos(toRadians(latitude)) * Math.cos(toRadians(row.latitude)) * Math.sin(dLon / 2) ** 2;
        return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
      };
      const observations = (data || [])
        .map(row => ({ ...row, distance_km: Math.round(distanceKm(row) * 10) / 10 }))
        .filter(row => row.distance_km <= radius)
        .sort((a, b) => a.distance_km - b.distance_km)
        .slice(0, 200);
      return res.json({ success: true, observations, count: observations.length, radius_km: radius });
    } catch (error) {
      console.error("Dream Wi-Fi registry query failed:", error && error.message ? error.message : "unknown error");
      return res.status(503).json({ success: false, error: "Source registry is temporarily unavailable." });
    }
  });
}

module.exports = { ACCESS_STATES, normalizeObservation, registerDreamWifiSourceRegistryRoutes };
