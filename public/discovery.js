"use strict";
(() => {
  const $ = id => document.getElementById(id);
  const API = location.origin;
  let position = null;
  let locationPromise = null;
  let locationWatchId = null;
  let lastAutoRefreshAt = 0;
  let autoRefreshRunning = false;
  const esc = value => String(value ?? "").replace(/[&<>"']/g, ch => ({ "&":"&amp;", "<":"&lt;", ">":"&gt;", '"':"&quot;", "'":"&#39;" }[ch]));
  function distanceKm(aLat, aLon, bLat, bLon) {
    const rad = n => n * Math.PI / 180;
    const dLat = rad(bLat-aLat), dLon = rad(bLon-aLon);
    const a = Math.sin(dLat/2)**2 + Math.cos(rad(aLat))*Math.cos(rad(bLat))*Math.sin(dLon/2)**2;
    return 6371 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0,1-a)));
  }
  function locate() {
    if (position) return Promise.resolve(position);
    if (locationPromise) return locationPromise;
    locationPromise = new Promise((resolve,reject) => {
      if (!navigator.geolocation) return reject(new Error("This device does not provide browser location."));
      navigator.geolocation.getCurrentPosition(p => {
        position = { latitude:p.coords.latitude, longitude:p.coords.longitude };
        resolve(position);
      }, e => reject(new Error(e.code === 1 ? "Location permission was denied. Allow it and retry." : "Location unavailable. Turn on location services and retry.")),
      { enableHighAccuracy:true, timeout:15000, maximumAge:60000 });
    }).finally(() => { locationPromise = null; });
    return locationPromise;
  }
  async function json(url) {
    const response = await fetch(url, { headers:{ accept:"application/json" } });
    let data;
    try { data = await response.json(); } catch (_) { throw new Error("The server returned an unreadable response."); }
    if (!response.ok) throw new Error(data.error || "Request failed (HTTP " + response.status + ").");
    return data;
  }
  function renderSources(data) {
    $("sources-results").replaceChildren();
    $("sources-summary").textContent = data.count + " registered source(s) found · " + data.online_count + " relay connected · " + data.radius_km + " km search radius · internet-ready sources prioritized; speed is not measured yet";
    if (!data.sources.length) {
      $("sources-results").innerHTML = '<p class="muted">No registered source with known coordinates was found in this search. This does not mean there are no ordinary Wi-Fi networks in the area.</p>';
      return;
    }
    const rankedSources = [...data.sources].sort((a, b) => {
      const priority = source => source.connected && source.internet_ready === true ? 0 : source.connected ? 1 : 2;
      return priority(a) - priority(b) || Number(a.distance_km) - Number(b.distance_km);
    });
    for (const source of rankedSources) {
      const item = document.createElement("article"); item.className = "tile";
      const state = source.connected ? "Relay online" : "Offline";
      const internet = source.internet_ready === true ? "Source internet probe passed" : source.internet_ready === false ? "Source internet probe failed" : "Internet not verified";
      item.innerHTML = '<strong>' + esc(source.name || "Dream Wi-Fi source") + '</strong><p class="muted">' +
        esc(source.distance_km) + ' km away · <span class="pill">' + esc(state) + '</span><br>' +
        esc(internet) + '<br>Last seen: ' + esc(source.last_seen || "unknown") + '</p>';
      if (source.connected && source.internet_ready === true) {
        const note = document.createElement("p"); note.className = "muted";
        note.textContent = "This source is discoverable, but connection still requires owner authorization and receiver pairing.";
        item.appendChild(note);
      }
      $("sources-results").appendChild(item);
    }
  }
  function renderHotspots(data) {
    $("map-results").replaceChildren();
    $("map-summary").textContent = data.count + " mapped public Wi-Fi place(s) · within " + data.radius_km + " km" + (data.cached ? " · cached" : "");
    if (!data.hotspots.length) {
      $("map-results").innerHTML = '<p class="muted">No mapped public Wi-Fi places returned. Map coverage may be incomplete.</p>'; return;
    }
    for (const h of data.hotspots) {
      const item = document.createElement("article"); item.className = "tile";
      item.innerHTML = '<strong>' + esc(h.name) + '</strong><p class="muted">' + esc(h.distance_km) + ' km away · ' + esc(h.venue_type) + ' · Map tag: ' + esc(h.access) + ' · Not live verified</p>';
      const link = document.createElement("a"); link.href = h.map_url; link.target = "_blank"; link.rel = "noopener noreferrer"; link.textContent = "View map listing";
      item.appendChild(link); $("map-results").appendChild(item);
    }
  }
  async function searchSources() {
    const pos = await locate();
    const selected = $("source-range").value;
    const radius = selected === "world" ? 20040 : Number(selected);
    const params = new URLSearchParams({ latitude:pos.latitude, longitude:pos.longitude, radius_km:String(radius) });
    $("status").textContent = selected === "world" ? "Searching the worldwide registry of participating Dream Wi-Fi sources…" : "Searching registered Dream Wi-Fi sources…";
    const data = await json(API + "/api/wifi/sources?" + params);
    renderSources(data);
    $("status").textContent = data.note || "Source search complete.";
  }
  async function searchMap() {
    const pos = await locate();
    const params = new URLSearchParams({ latitude:pos.latitude, longitude:pos.longitude, radius_km:$("map-range").value });
    $("map-summary").textContent = "Searching mapped public Wi-Fi places…";
    renderHotspots(await json(API + "/api/wifi/hotspots?" + params));
  }
  async function searchAll() {
    lastAutoRefreshAt = Date.now();
    $("search-all").disabled = true;
    try {
      await searchSources();
      try { await searchMap(); } catch (e) { $("map-summary").textContent = "Public map lookup unavailable: " + e.message; }
    } catch (e) { $("status").textContent = e.message; }
    finally { $("search-all").disabled = false; }
  }
  function renderLocal(data) {
    const root = $("local-results"); root.replaceChildren();
    if (data.status === "permission_required") {
      root.innerHTML = '<p class="muted">Android location permission is needed for Wi-Fi scanning. Grant it, then tap Scan physical Wi-Fi again.</p>'; return;
    }
    if (data.status === "location_services_off") {
      root.innerHTML = '<p class="warning">Turn on device Location in Android settings, then scan again.</p>'; return;
    }
    if (!Array.isArray(data.networks) || !data.networks.length) {
      root.innerHTML = '<p class="muted">' + esc(data.message || "No networks returned. Make sure Wi-Fi and Location are on, then scan again.") + '</p>'; return;
    }
    for (const network of data.networks) {
      const item = document.createElement("article"); item.className = "tile";
      const title = document.createElement("strong"); title.textContent = network.ssid || "(Hidden network)";
      const details = document.createElement("p"); details.className = "muted";
      details.textContent = (network.level_dbm == null ? "Signal unknown" : network.level_dbm + " dBm") + " · " + (network.security || "Security unknown") + (network.capabilities ? " · " + network.capabilities : "");
      const button = document.createElement("button"); button.className = "secondary";
      const native = window.DreamWifiNative;
      const canSuggest = network.security === "Open" && native && typeof native.suggestOpenWifi === "function";
      button.textContent = canSuggest ? "Connect to open Wi-Fi" : "Open Wi-Fi settings";
      button.addEventListener("click", () => {
        if (canSuggest) {
          try {
            const result = JSON.parse(native.suggestOpenWifi(network.ssid));
            const messages = {
              suggested: "Open network suggestion sent to Android. The system may ask you to approve it and decides whether to connect. This does not guarantee internet access.",
              already_suggested: "This network is already suggested to Android. Check the system Wi-Fi notification or Wi-Fi settings to approve/connect.",
              permission_required: "Grant Nearby Wi-Fi permission, then tap Connect again.",
              settings_required: "Android requires its Wi-Fi settings for this network. Choose it there to connect.",
              invalid_ssid: "This network has no usable name. Open Wi-Fi settings to select it manually.",
              unavailable: "Wi-Fi connection suggestions are unavailable on this device."
            };
            $("status").textContent = messages[result.status] || "Android could not suggest this network. Open Wi-Fi settings to connect manually.";
            if (result.status === "settings_required" || result.status === "unavailable" || result.status === "invalid_ssid") native.openWifiSettings();
          } catch (_) {
            $("status").textContent = "Could not request the connection. Open Android Wi-Fi settings to connect.";
            native.openWifiSettings();
          }
        } else if (native && typeof native.openWifiSettings === "function") {
          native.openWifiSettings();
        } else {
          $("status").textContent = "Open Android Settings → Network & Internet → Wi-Fi and select this network. Dream Wi-Fi cannot bypass Android's connection confirmation.";
        }
      });
      item.append(title, details, button); root.appendChild(item);
    }
    const note = document.createElement("p"); note.className = "muted";
    note.textContent = data.note || "Networks shown are within local radio range. A scan result does not mean the network is open or has internet.";
    root.appendChild(note);
  }
  function startLocationWatch() {
    if (locationWatchId !== null || !navigator.geolocation) return;
    locationWatchId = navigator.geolocation.watchPosition(p => {
      const next = { latitude:p.coords.latitude, longitude:p.coords.longitude };
      const moved = !position || distanceKm(position.latitude, position.longitude, next.latitude, next.longitude) >= 2;
      const refreshDue = Date.now() - lastAutoRefreshAt >= 15 * 60 * 1000;
      position = next;
      if ((moved || refreshDue) && !autoRefreshRunning) {
        autoRefreshRunning = true;
        searchAll().catch(e => { $("status").textContent = "Automatic map refresh failed: " + e.message; }).finally(() => { autoRefreshRunning = false; });
      }
    }, () => { $("status").textContent = "Live location updates paused. Check location permission and Android battery settings."; },
    { enableHighAccuracy:false, maximumAge:120000, timeout:20000 });
  }

  $("scan-local").addEventListener("click", async () => {
    const native = window.DreamWifiNative;
    if (!native || typeof native.requestLocalWifiScan !== "function") {
      $("local-results").innerHTML = '<p class="warning">Physical Wi-Fi scanning is available in the Android app. On a browser, use your device Wi-Fi settings to scan nearby networks.</p>';
      return;
    }
    $("scan-local").disabled = true;
    $("local-results").textContent = "Requesting an Android Wi-Fi scan… Android may return cached results first.";
    try {
      let data = JSON.parse(native.requestLocalWifiScan());
      // Android Wi-Fi scans are asynchronous. Re-read the platform results after
      // the scan has had time to complete instead of treating cached results as final.
      for (let attempt = 0; attempt < 2 && data.scan_started === true; attempt++) {
        $("local-results").textContent = "Waiting for Android to refresh nearby Wi-Fi results… (" + (attempt + 1) + "/2)";
        await new Promise(resolve => setTimeout(resolve, 1800));
        data = JSON.parse(native.requestLocalWifiScan());
      }
      renderLocal(data);
    } catch (e) { $("local-results").textContent = "Could not scan Wi-Fi: " + e.message; }
    finally { $("scan-local").disabled = false; }
  });
  $("search-all").addEventListener("click", searchAll);
  $("search-map").addEventListener("click", async () => {
    $("search-map").disabled = true;
    try { await searchMap(); } catch (e) { $("map-summary").textContent = e.message; }
    finally { $("search-map").disabled = false; }
  });
  $("source-range").addEventListener("change", () => { if (position) searchSources().catch(e => $("status").textContent = e.message); });
  $("map-range").addEventListener("change", () => { if (position) searchMap().catch(e => $("map-summary").textContent = e.message); });
  locate().then(() => searchAll()).then(startLocationWatch).catch(e => { $("status").textContent = e.message + " Tap Search online after allowing location."; });
})();