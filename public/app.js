"use strict";
(async function () {
  const $ = id => document.getElementById(id);
  const status = message => { $("status").textContent = message; };
  let position = null;
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
    if (results[1].status === "fulfilled") {
      renderHotspots(results[1].value);
      messages.push(results[1].value.note);
    } else {
      $("hotspot-summary").textContent = "Public hotspot data unavailable.";
      $("hotspots").replaceChildren();
      messages.push(results[1].reason.message);
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
  status("No sign-in or Supabase account is required. Detect your location to search.");
})();
