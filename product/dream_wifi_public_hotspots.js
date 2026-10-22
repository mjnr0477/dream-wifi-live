"use strict";

// Public hotspot directory backed by OpenStreetMap data. This discovers mapped
// public Wi-Fi venues; it cannot detect every SSID or private router remotely.
const OVERPASS_ENDPOINTS = [
  "https://osm.hpi.de/overpass/api/interpreter",
  "https://overpass.private.coffee/api/interpreter",
  "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
];
const CACHE_TTL_MS = 10 * 60 * 1000;
const MAX_RESULTS = 200;
const cache = new Map();

function distanceKm(aLat, aLon, bLat, bLon) {
  const rad = n => n * Math.PI / 180;
  const dLat = rad(bLat - aLat), dLon = rad(bLon - aLon);
  const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
  return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
}

function buildOverpassQuery(latitude, longitude, radiusKm) {
  const radiusM = Math.round(radiusKm * 1000);
  const lat = Number(latitude), lon = Number(longitude);
  if (!Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lon) || lon < -180 || lon > 180) {
    throw new TypeError("Valid latitude and longitude are required.");
  }
  if (!Number.isFinite(radiusKm) || radiusKm < 1 || radiusKm > 300) {
    throw new RangeError("radiusKm must be between 1 and 300.");
  }
  const around = `around:${radiusM},${lat},${lon}`;
  return `[out:json][timeout:20];(nwr(${around})["internet_access"~"^(wlan|yes|free|customers|public)$",i];nwr(${around})["wifi"~"^(yes|free|customers)$",i];);out center 200;`;
}

function normalizeElements(elements, latitude, longitude, radiusKm) {
  const seen = new Set();
  const results = [];
  for (const element of Array.isArray(elements) ? elements : []) {
    if (!element || !["node", "way", "relation"].includes(element.type) || element.id == null) continue;
    const tags = element.tags && typeof element.tags === "object" ? element.tags : {};
    const access = String(tags.internet_access || tags.wifi || "").toLowerCase();
    if (!/^(wlan|yes|free|customers|public)$/.test(access) && !/^(yes|free|customers)$/.test(String(tags.wifi || "").toLowerCase())) continue;
    const lat = Number(element.lat ?? element.center?.lat);
    const lon = Number(element.lon ?? element.center?.lon);
    if (!Number.isFinite(lat) || lat < -90 || lat > 90 || !Number.isFinite(lon) || lon < -180 || lon > 180) continue;
    const distance = distanceKm(latitude, longitude, lat, lon);
    if (distance > radiusKm) continue;
    const id = element.type + "/" + element.id;
    if (seen.has(id)) continue;
    seen.add(id);
    const venue = String(tags.name || tags.amenity || tags.shop || tags.tourism || "Public Wi-Fi location").slice(0, 120);
    results.push({
      id,
      name: venue,
      latitude: lat,
      longitude: lon,
      distance_km: Math.round(distance * 10) / 10,
      access: access || "unknown",
      venue_type: String(tags.amenity || tags.shop || tags.tourism || "Wi-Fi hotspot").slice(0, 60),
      connected: false,
      connectable: false,
      map_url: `https://www.openstreetmap.org/${element.type}/${element.id}`
    });
  }
  return results.sort((a, b) => a.distance_km - b.distance_km).slice(0, MAX_RESULTS);
}

async function fetchPublicHotspots({ latitude, longitude, radiusKm, fetchImpl = globalThis.fetch, now = Date.now }) {
  const query = buildOverpassQuery(latitude, longitude, radiusKm);
  const cacheKey = [Number(latitude).toFixed(3), Number(longitude).toFixed(3), Number(radiusKm)].join(":");
  const cached = cache.get(cacheKey);
  if (cached && cached.expiresAt > now()) return { ...cached.value, cached: true };
  let lastError;
  for (const endpoint of OVERPASS_ENDPOINTS) {
    try {
      const response = await fetchImpl(endpoint, {
        method: "POST",
        headers: {
          "content-type": "application/x-www-form-urlencoded;charset=UTF-8",
          "accept": "application/json",
          "user-agent": "Dream-WiFi/1.0 (+https://github.com/mjnr0477/dream-wifi-live)"
        },
        body: new URLSearchParams({ data: query }),
        signal: AbortSignal.timeout(8000)
      });
      if (!response.ok) throw new Error("OpenStreetMap hotspot data service returned HTTP " + response.status);
      const payload = await response.json();
      if (!payload || !Array.isArray(payload.elements)) throw new Error("Hotspot data response was invalid.");
      const hotspots = normalizeElements(payload.elements, Number(latitude), Number(longitude), Number(radiusKm));
      const value = {
        success: true,
        provider: "OpenStreetMap",
        attribution: "© OpenStreetMap contributors",
        radius_km: Number(radiusKm),
        hotspots,
        count: hotspots.length,
        note: "Mapped public Wi-Fi venues only. A listed place is not proof its Wi-Fi is currently on, free, or available to connect."
      };
      cache.set(cacheKey, { value, expiresAt: now() + CACHE_TTL_MS });
      if (cache.size > 100) {
        const oldestKey = cache.keys().next().value;
        cache.delete(oldestKey);
      }
      return { ...value, cached: false };
    } catch (error) {
      lastError = error;
    }
  }
  throw new Error("Public hotspot data is temporarily unavailable. Please retry shortly. " + String(lastError?.name === "TimeoutError" ? "The map provider timed out." : ""));
}

module.exports = { buildOverpassQuery, distanceKm, fetchPublicHotspots, normalizeElements };
