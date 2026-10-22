"use strict";
(async function () {
  const $ = id => document.getElementById(id);
  const status = message => { $("status").textContent = message; };
  let position = null;
  function renderSources(data) {
    $("sources").replaceChildren();
    $("summary").textContent = data.count + " services within " + data.radius_km + " km · " + data.online_count + " online";
    if (!data.sources.length) {
      const p = document.createElement("p"); p.className = "muted";
      p.textContent = "No participating services with known coordinates were found. This does not mean there are no routers nearby.";
      $("sources").appendChild(p); return;
    }
    for (const source of data.sources) {
      const item = document.createElement("article"); item.className = "tile";
      const title = document.createElement("strong"); title.textContent = source.name;
      const details = document.createElement("p"); details.className = "muted";
      details.textContent = source.distance_km + " km away · " + (source.connected ? "Online" : "Offline — not connectable now") + " · Last seen " + new Date(source.last_seen).toLocaleString();
      item.append(title, details); $("sources").appendChild(item);
    }
    status(data.note);
  }
  async function search() {
    if (!position) return;
    status("Searching services within " + $("radius").value + " km…");
    try {
      const params = new URLSearchParams({ latitude: position.latitude, longitude: position.longitude, radius_km: $("radius").value });
      const response = await fetch("/api/wifi/sources?" + params);
      const data = await response.json();
      if (!response.ok) throw new Error(data.error || "Search failed.");
      renderSources(data);
    } catch (error) { status(error.message || "Could not search for services."); }
  }
  $("locate").addEventListener("click", () => {
    if (!navigator.geolocation) { status("This browser does not support location detection."); return; }
    $("locate").disabled = true; status("Requesting your location…");
    navigator.geolocation.getCurrentPosition(async p => {
      position = { latitude: p.coords.latitude, longitude: p.coords.longitude };
      $("location").textContent = "Location detected. Coordinates are used for this search.";
      $("locate").disabled = false; await search();
    }, error => {
      $("locate").disabled = false;
      status(error.code === 1 ? "Location permission denied. Allow location access and retry." : "Could not detect location. Check device location services and retry.");
    }, { enableHighAccuracy: true, timeout: 15000, maximumAge: 60000 });
  });
  $("search").addEventListener("click", search);
  status("No sign-in or Supabase account is required. Allow location to search.");
})();
