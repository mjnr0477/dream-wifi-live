"use strict";
(async function () {
  const $ = id => document.getElementById(id);
  let client = null;
  const status = message => { $("status").textContent = message; };
  let session = null;
  try {
    const response = await fetch("/api/dream-wifi/config");
    const config = await response.json();
    if (!config.configured || !config.supabaseUrl || !config.supabaseAnonKey) {
      $("config-warning").textContent = "Owner sign-in is not configured yet. Set SUPABASE_URL, SUPABASE_ANON_KEY and DREAM_WIFI_OWNER_EMAIL in the host environment.";
      $("login").disabled = true;
      return;
    }
    $("config-warning").textContent = "Service configured. Use the owner account configured for this deployment.";
    const sdk = await import("https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2/+esm");
    client = sdk.createClient(config.supabaseUrl, config.supabaseAnonKey, { auth: { persistSession: true, autoRefreshToken: true } });
    const current = await client.auth.getSession();
    session = current.data && current.data.session;
    renderSession();
    if (session) await refreshStatus();
  } catch (error) {
    $("config-warning").textContent = "Could not initialize sign-in. Check service configuration and network access.";
  }
  function renderSession() {
    $("signin").classList.toggle("hidden", Boolean(session));
    $("signed-in").classList.toggle("hidden", !session);
    $("session-status").textContent = session ? "Signed in; checking owner permissions…" : "Signed out";
  }
  async function refreshStatus() {
    if (!session) return;
    try {
      const response = await fetch("/api/dream-wifi/owner-status", { headers: { Authorization: "Bearer " + session.access_token } });
      const data = await response.json();
      if (!response.ok) {
        $("session-status").textContent = "Not authorized";
        status(data.error || "Owner authorization failed.");
        return;
      }
      $("session-status").textContent = "Owner authenticated";
      status("Owner access verified.\nSource token configured: " + data.source_endpoint_configured +
        "\nSource connected: " + data.source_connected +
        "\nLive access enabled: " + data.live_access_enabled +
        "\nPackages enabled: " + data.packages_enabled +
        "\n\nA connected source is not proof that Quant traffic works end-to-end.");
    } catch (_) { status("Could not reach the status endpoint."); }
  }
  $("login").addEventListener("click", async () => {
    if (!client) return;
    $("login").disabled = true;
    try {
      const result = await client.auth.signInWithPassword({ email: $("email").value.trim(), password: $("password").value });
      if (result.error) throw result.error;
      session = result.data.session;
      renderSession();
      await refreshStatus();
    } catch (error) { status(error.message || "Sign-in failed."); }
    finally { $("login").disabled = false; }
  });
  $("refresh").addEventListener("click", refreshStatus);
  $("logout").addEventListener("click", async () => {
    if (client) await client.auth.signOut();
    session = null;
    renderSession();
    status("Signed out.");
  });
})();
