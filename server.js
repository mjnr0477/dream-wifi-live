"use strict";

require("dotenv").config();
const http = require("node:http");
const path = require("node:path");
const express = require("express");
const { createClient } = require("@supabase/supabase-js");

const app = express();
const port = Number(process.env.PORT || 3000);
const supabaseUrl = String(process.env.SUPABASE_URL || "").trim();
const supabaseAnonKey = String(process.env.SUPABASE_ANON_KEY || "").trim();
const ownerEmail = String(process.env.DREAM_WIFI_OWNER_EMAIL || "").trim().toLowerCase();
const sourceToken = String(process.env.DREAM_WIFI_SOURCE_TOKEN || "");
const supabaseServiceRoleKey = String(process.env.SUPABASE_SERVICE_ROLE_KEY || "").trim();
const supabase = supabaseUrl && supabaseServiceRoleKey
  ? createClient(supabaseUrl, supabaseServiceRoleKey, { auth: { persistSession: false, autoRefreshToken: false } })
  : null;
const supabaseAuth = supabaseUrl && supabaseAnonKey
  ? createClient(supabaseUrl, supabaseAnonKey, { auth: { persistSession: false, autoRefreshToken: false } })
  : null;

function isOwnerAccount(account) {
  const email = String(account && account.email || "").trim().toLowerCase();
  return Boolean(ownerEmail && email && email === ownerEmail);
}
async function getVerifiedUser(req) {
  const header = String(req.get("authorization") || "");
  const match = header.match(/^Bearer\s+(.+)$/i);
  if (!match || !supabaseAuth) return { user: null, status: 401 };
  try {
    const result = await supabaseAuth.auth.getUser(match[1]);
    if (result.error || !result.data || !result.data.user) return { user: null, status: 401 };
    if (!isOwnerAccount({ email: result.data.user.email })) return { user: null, status: 403 };
    return { user: result.data.user, status: 200 };
  } catch (_) {
    return { user: null, status: 401 };
  }
}

app.disable("x-powered-by");
app.use((req, res, next) => {
  res.set("X-Content-Type-Options", "nosniff");
  res.set("Referrer-Policy", "no-referrer");
  res.set("Cache-Control", "no-store");
  next();
});
app.use(express.json({ limit: "32kb" }));
if (supabaseAuth && supabase) {
  require("./product/dream_wifi_source_registry").registerDreamWifiSourceRegistryRoutes(app, { supabaseAuth, supabase });
}
app.post("/api/auth/login", async (req, res) => {
  if (!supabaseAuth) return res.status(503).json({ success: false, error: "Supabase authentication is not configured." });
  const email = String(req.body && req.body.email || "").trim();
  const password = String(req.body && req.body.password || "");
  if (!email || !password) return res.status(400).json({ success: false, error: "Email and password are required." });
  try {
    const result = await supabaseAuth.auth.signInWithPassword({ email, password });
    if (result.error || !result.data || !result.data.user || !result.data.session) {
      return res.status(401).json({ success: false, error: "Email or password is invalid." });
    }
    if (!isOwnerAccount({ email: result.data.user.email })) {
      return res.status(403).json({ success: false, error: "Owner access is required." });
    }
    return res.json({ success: true, session: { access_token: result.data.session.access_token, refresh_token: result.data.session.refresh_token } });
  } catch (_) { return res.status(401).json({ success: false, error: "Sign-in failed." }); }
});
app.post("/api/auth/refresh", async (req, res) => {
  if (!supabaseAuth) return res.status(503).json({ success: false, error: "Supabase authentication is not configured." });
  const refreshToken = String(req.body && req.body.refresh_token || "").trim();
  if (!refreshToken) return res.status(400).json({ success: false, error: "Refresh token is required." });
  try {
    const result = await supabaseAuth.auth.refreshSession({ refresh_token: refreshToken });
    if (result.error || !result.data || !result.data.user || !result.data.session) return res.status(401).json({ success: false, error: "Session expired; sign in again." });
    if (!isOwnerAccount({ email: result.data.user.email })) return res.status(403).json({ success: false, error: "Owner access is required." });
    return res.json({ success: true, session: { access_token: result.data.session.access_token, refresh_token: result.data.session.refresh_token } });
  } catch (_) { return res.status(401).json({ success: false, error: "Session refresh failed." }); }
});
app.get("/healthz", (_req, res) => res.json({ ok: true, service: "dream-wifi-live" }));
app.get("/api/dream-wifi/config", (_req, res) => {
  res.json({
    configured: Boolean(supabaseUrl && supabaseAnonKey && ownerEmail),
    supabaseUrl: supabaseUrl || null,
    supabaseAnonKey: supabaseAnonKey || null
  });
});
app.get("/api/dream-wifi/owner-status", async (req, res) => {
  const auth = await getVerifiedUser(req);
  if (!auth.user) return res.status(auth.status).json({ success: false, error: auth.status === 403 ? "Owner access is required." : "Sign in with the configured owner account." });
  return res.json({
    success: true,
    owner_authenticated: true,
    source_endpoint_configured: Boolean(sourceToken),
    source_connected: Boolean(dreamWifiTunnel && dreamWifiTunnel.sourceConnected()),
    source_auth_available: Boolean(dreamWifiTunnel && dreamWifiTunnel.issueSourceTicket),
    live_access_enabled: false,
    packages_enabled: false
  });
});
app.post("/api/dream-wifi/source-ticket", async (req, res) => {
  const auth = await getVerifiedUser(req);
  if (!auth.user) return res.status(auth.status).json({ success: false, error: "A valid owner session is required." });
  if (!dreamWifiTunnel) return res.status(503).json({ success: false, error: "Tunnel service is unavailable." });
  try {
    const ticket = dreamWifiTunnel.issueSourceTicket(auth.user.id);
    return res.json({ success: true, token: ticket.token, expires_at: ticket.expires_at });
  } catch (error) {
    return res.status(500).json({ success: false, error: "Could not issue source ticket." });
  }
});

const publicDir = path.join(__dirname, "public");
app.use(express.static(publicDir));
app.use((req, res, next) => {
  if (req.method === "GET" && !req.path.startsWith("/api/")) {
    return res.sendFile(path.join(publicDir, "index.html"));
  }
  return next();
});
app.use((_req, res) => res.status(404).json({ success: false, error: "Not found." }));

const server = http.createServer(app);
let dreamWifiTunnel = null;
if (supabaseAuth && ownerEmail) {
  dreamWifiTunnel = require("./product/dream_wifi_tunnel_engine").attachDreamWifiTunnel(server, {
    supabaseAuth,
    isOwnerAccount,
    sourceToken
  });
} else {
  console.warn("Dream Wi-Fi tunnel is not enabled: configure SUPABASE_URL, SUPABASE_ANON_KEY, and DREAM_WIFI_OWNER_EMAIL.");
}
server.listen(port, "0.0.0.0", () => {
  console.log("Dream Wi-Fi listening on port " + port);
});
