"use strict";
(async function () {
  const $ = id => document.getElementById(id);
  const status = message => { $("status").textContent = message; };
  let position = null;
  const DIRECT_MAP_ENDPOINTS = [
    "https://osm.hpi.de/overpass/api/interpreter",
    "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
  ];
  function distanceKm(aLat, aLon, bLat, bLon) {
    const rad = n => n * Math.PI / 180;
    const dLat = rad(bLat - aLat), dLon = rad(bLon - aLon);
    const a = Math.sin(dLat / 2) ** 2 + Math.cos(rad(aLat)) * Math.cos(rad(bLat)) * Math.sin(dLon / 2) ** 2;
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0, 1 - a)));
  }
  function buildBrowserOverpassQuery(latitude, longitude, radiusKm) {
    const radiusM = Math.round(radiusKm * 1000);
    const around = `around:${radiusM},${latitude},${longitude}`;
    return `[out:json][timeout:20];(nwr(${around})["internet_access"~"^(wlan|yes|free|customers|public)$",i];nwr(${around})["wifi"~"^(yes|free|customers)$",i];);out center 200;`;
  }
  async function directBrowserHotspotSearch() {
    const radiusKm = Number($("radius").value);
    const query = buildBrowserOverpassQuery(position.latitude, position.longitude, radiusKm);
    const failures = [];
    for (const endpoint of DIRECT_MAP_ENDPOINTS) {
      try {
        const response = await fetch(endpoint, {
          method: "POST",
          headers: {
            "content-type": "application/x-www-form-urlencoded;charset=UTF-8",
            "accept": "application/json"
          },
          body: new URLSearchParams({ data: query }),
          signal: AbortSignal.timeout(8000)
        });
        if (!response.ok) throw new Error("HTTP " + response.status);
        const payload = await response.json();
        if (!payload || !Array.isArray(payload.elements)) throw new Error("Invalid map response.");
        const seen = new Set();
        const hotspots = [];
        for (const element of payload.elements) {
          if (!element || !["node", "way", "relation"].includes(element.type) || element.id == null) continue;
          const tags = element.tags && typeof element.tags === "object" ? element.tags : {};
          const access = String(tags.internet_access || tags.wifi || "").toLowerCase();
          if (!/^(wlan|yes|free|customers|public)$/.test(access) && !/^(yes|free|customers)$/.test(String(tags.wifi || "").toLowerCase())) continue;
          const latitude = Number(element.lat ?? element.center?.lat);
          const longitude = Number(element.lon ?? element.center?.lon);
          if (!Number.isFinite(latitude) || latitude < -90 || latitude > 90 || !Number.isFinite(longitude) || longitude < -180 || longitude > 180) continue;
          const distance = distanceKm(position.latitude, position.longitude, latitude, longitude);
          if (distance > radiusKm) continue;
          const id = element.type + "/" + element.id;
          if (seen.has(id)) continue;
          seen.add(id);
          hotspots.push({
            id,
            name: String(tags.name || tags.amenity || tags.shop || tags.tourism || "Public Wi-Fi location").slice(0, 120),
            latitude,
            longitude,
            distance_km: Math.round(distance * 10) / 10,
            access: access || "unknown",
            venue_type: String(tags.amenity || tags.shop || tags.tourism || "Wi-Fi hotspot").slice(0, 60),
            connected: false,
            connectable: false,
            map_url: `https://www.openstreetmap.org/${element.type}/${element.id}`
          });
        }
        hotspots.sort((a, b) => a.distance_km - b.distance_km);
        return {
          success: true,
          provider: "OpenStreetMap (direct browser fallback)",
          attribution: "© OpenStreetMap contributors",
          radius_km: radiusKm,
          hotspots: hotspots.slice(0, 200),
          count: Math.min(hotspots.length, 200),
          note: "Loaded directly from a public map provider. Listings are not live-verified and do not grant Wi-Fi access."
        };
      } catch (error) {
        failures.push(new URL(endpoint).hostname + ": " + String(error.message || error).slice(0, 100));
      }
    }
    throw new Error("Server and direct map lookups are unavailable. " + failures.join("; "));
  }
  function renderExternalQueryFallback() {
    const note = document.createElement("p");
    note.className = "muted";
    note.textContent = "If automatic map requests are blocked, you can try this query in Overpass Turbo. This opens a third-party site and includes your coordinates and selected search radius.";
    const link = document.createElement("a");
    link.href = "https://overpass-turbo.eu/?Q=" + encodeURIComponent(buildBrowserOverpassQuery(position.latitude, position.longitude, Number($("radius").value)));
    link.target = "_blank";
    link.rel = "noopener noreferrer";
    link.textContent = "Open Wi-Fi query in Overpass Turbo";
    link.style.color = "#86efac";
    $("hotspots").append(note, link);
  }
  function renderSources(data) {
    $("sources").replaceChildren();
    $("source-summary").textContent = data.count + " participating sources within " + data.radius_km + " km · " + data.online_count + " online";
    if (!data.sources.length) {
      const p = document.createElement("p"); p.className = "muted";
      p.textContent = "No participating Dream Wi-Fi source devices with known coordinates were found. This does not mean there are no Wi-Fi hotspots nearby.";
      $("sources").appendChild(p); return;
    }
    for (const source of data.sources) {
      const item = document.createElement("article"); item.className = "tile";
      const title = document.createElement("strong"); title.textContent = source.name;
      const details = document.createElement("p"); details.className = "muted";
      details.textContent = source.distance_km + " km away · " + (source.connected ? "Online" : "Offline — not connectable now") + " · Last seen " + new Date(source.last_seen).toLocaleString();
      item.append(title, details); $("sources").appendChild(item);
    }
  }
  function renderHotspots(data) {
    $("hotspots").replaceChildren();
    $("hotspot-summary").textContent = data.count + " mapped public Wi-Fi locations within " + data.radius_km + " km";
    if (!data.hotspots.length) {
      const p = document.createElement("p"); p.className = "muted";
      p.textContent = "No mapped public Wi-Fi locations were returned for this area. Map coverage may be incomplete.";
      $("hotspots").appendChild(p); return;
    }
    for (const hotspot of data.hotspots) {
      const item = document.createElement("article"); item.className = "tile";
      const title = document.createElement("strong"); title.textContent = hotspot.name;
      const details = document.createElement("p"); details.className = "muted";
      details.textContent = hotspot.distance_km + " km away · " + hotspot.venue_type + " · Mapped Wi-Fi: " + hotspot.access + " · Not live-verified";
      const link = document.createElement("a"); link.href = hotspot.map_url; link.target = "_blank"; link.rel = "noopener noreferrer"; link.textContent = "View on OpenStreetMap";
      link.style.color = "#86efac";
      item.append(title, details, link); $("hotspots").appendChild(item);
    }
    $("attribution").textContent = data.attribution || "© OpenStreetMap contributors";
  }
  async function requestJson(url) {
    const response = await fetch(url);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || "Search failed.");
    return data;
  }
  async function search() {
    if (!position) { status("Detect your location first."); return; }
    const params = new URLSearchParams({ latitude: position.latitude, longitude: position.longitude, radius_km: $("radius").value });
    $("search").disabled = true;
    status("Searching mapped public Wi-Fi locations and participating Dream Wi-Fi sources…");
    const results = await Promise.allSettled([
      requestJson("/api/wifi/sources?" + params),
      requestJson("/api/wifi/hotspots?" + params)
    ]);
    let messages = [];
    if (results[0].status === "fulfilled") {
      renderSources(results[0].value);
      if (results[0].value.note) messages.push(results[0].value.note);
    } else messages.push("Participating-source lookup failed: " + results[0].reason.message);
    let hotspotResult = results[1];
    if (hotspotResult.status === "rejected") {
      status("Server map lookup failed; trying direct browser map providers…");
      try {
        hotspotResult = { status: "fulfilled", value: await directBrowserHotspotSearch() };
        messages.push("Used direct browser map-provider fallback.");
      } catch (error) {
        messages.push(error.message || "Direct map lookup failed.");
      }
    }
    if (hotspotResult.status === "fulfilled") {
      renderHotspots(hotspotResult.value);
      messages.push(hotspotResult.value.note);
    } else {
      $("hotspot-summary").textContent = "Automatic public hotspot lookup is unavailable.";
      $("hotspots").replaceChildren();
      renderExternalQueryFallback();
    }
    $("search").disabled = false;
    status(messages.join("\n"));
  }
  $("locate").addEventListener("click", () => {
    if (!navigator.geolocation) { status("This browser does not support location detection."); return; }
    $("locate").disabled = true; status("Requesting your location…");
    navigator.geolocation.getCurrentPosition(async p => {
      position = { latitude: p.coords.latitude, longitude: p.coords.longitude };
      $("location").textContent = "Location detected. Coordinates are sent to the public hotspot map provider to search mapped places.";
      $("locate").disabled = false; await search();
    }, error => {
      $("locate").disabled = false;
      status(error.code === 1 ? "Location permission denied. Allow location access and retry." : "Could not detect location. Check device location services and retry.");
    }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
  });
  $("search").addEventListener("click", search);
  $("radius").addEventListener("change", () => { if (position) search(); });

  // Keep participating Dream Wi-Fi sources fresh while the discovery page stays open.
  // Public hotspot map data is refreshed only on an explicit/full search, not every 30 seconds.
  async function refreshSourceTracking() {
    if (!position || document.visibilityState === "hidden") return;
    const params = new URLSearchParams({
      latitude: position.latitude,
      longitude: position.longitude,
      radius_km: $("radius").value
    });
    try {
      const data = await requestJson("/api/wifi/sources?" + params);
      renderSources(data);
      const checkedAt = new Date().toLocaleTimeString();
      $("source-summary").textContent += " · refreshed " + checkedAt;
    } catch (error) {
      status("Live source refresh failed: " + (error.message || "request failed") + "\\nWill retry automatically.");
    }
  }
  setInterval(refreshSourceTracking, 30000);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "visible") refreshSourceTracking();
  });

  status("Detecting your location. Allow the location prompt to search Wi-Fi within your selected radius.");
  // Auto-request location on opening; the user can deny permission or retry with the button.
  $("locate").click();
})();
