"use strict";

const crypto = require("node:crypto");
const { WebSocket, WebSocketServer } = require("ws");

const MAX_MESSAGE_BYTES = 24 * 1024;
const MAX_TCP_CHUNK_BYTES = 12 * 1024;
const MAX_STREAMS_PER_OWNER = 8;
const MAX_CLIENTS_PER_IP = 12;
const connectionsPerIp = new Map();
const MAX_UDP_ASSOCIATIONS_PER_OWNER = 8;
const MAX_UDP_DATAGRAM_BYTES = 8192;
const UDP_IDLE_TIMEOUT_MS = 60000;
const MAX_WS_BUFFERED_BYTES = 1024 * 1024;
const PING_TIMEOUT_MS = 5000;
const PAIRING_CODE_TTL_MS = 10 * 60 * 1000;
const PAIRING_TOKEN_TTL_MS = 24 * 60 * 60 * 1000;

function safeEqual(left, right) {
  const a = Buffer.from(String(left || ""), "utf8");
  const b = Buffer.from(String(right || ""), "utf8");
  return a.length > 0 && a.length === b.length && crypto.timingSafeEqual(a, b);
}
function send(socket, payload) {
  if (socket && socket.readyState === WebSocket.OPEN) {
    socket.send(JSON.stringify(payload));
  }
}
function validStreamId(value) {
  return typeof value === "string" && /^[a-zA-Z0-9_-]{1,80}$/.test(value);
}
function decodeChunk(value) {
  if (typeof value !== "string" || value.length > Math.ceil(MAX_TCP_CHUNK_BYTES * 4 / 3) + 8 ||
      !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) return null;
  const decoded = Buffer.from(value, "base64");
  return decoded.length <= MAX_TCP_CHUNK_BYTES ? decoded : null;
}

function attachDreamWifiTunnel(server, options = {}) {
  const sourceToken = String(options.sourceToken || "");
  const owners = new Set();
  const sources = new Map();
  const pairingCodes = new Map();
  const pairingTokens = new Map();
  const pairingAttempts = new Map();
  const enrolledSourceTokens = new Map();
  const pendingPings = new Map();

  for (const credential of Array.isArray(options.enrolledSourceCredentials) ? options.enrolledSourceCredentials : []) {
    if (credential && typeof credential.sourceId === "string" &&
        /^[a-zA-Z0-9_-]{1,80}$/.test(credential.sourceId) &&
        typeof credential.tokenHash === "string" && /^[a-f0-9]{64}$/.test(credential.tokenHash)) {
      enrolledSourceTokens.set(credential.tokenHash, {
        sourceId: credential.sourceId,
        createdAt: Number.isFinite(credential.createdAt) ? credential.createdAt : Date.now()
      });
    }
  }
  const streams = new Map();
  const udpAssociations = new Map();

  const wss = new WebSocketServer({
    server,
    maxPayload: MAX_MESSAGE_BYTES,
    perMessageDeflate: false
  });

  function sourceConnected(sourceId) {
    if (sourceId) {
      const source = sources.get(String(sourceId));
      return Boolean(source && source.readyState === WebSocket.OPEN);
    }
    for (const source of sources.values()) {
      if (source.readyState === WebSocket.OPEN) return true;
    }
    return false;
  }
  function sourceDetails(socket) {
    const checkedAt = socket.dreamInternetCheckedAt || null;
    const checkedAtMs = checkedAt ? Date.parse(checkedAt) : NaN;
    const healthFresh = Number.isFinite(checkedAtMs) && Date.now() - checkedAtMs <= 120000;
    return {
      id: socket.dreamSourceId,
      name: socket.dreamSourceName,
      connected: socket.readyState === WebSocket.OPEN,
      connectedAt: socket.dreamSourceConnectedAt,
      latitude: socket.dreamSourceLatitude,
      longitude: socket.dreamSourceLongitude,
      internetReady: healthFresh && typeof socket.dreamInternetReady === "boolean" ? socket.dreamInternetReady : null,
      internetCheckedAt: checkedAt,
      internetError: healthFresh ? (socket.dreamInternetError || null) : (checkedAt ? "health_probe_stale" : null)
    };
  }
  function sourceListForOwner(ownerSocket) {
    const result = [];
    if (!ownerSocket.dreamSourceId) return result;
    const source = sources.get(ownerSocket.dreamSourceId);
    if (source) result.push(sourceDetails(source));
    return result.sort((a, b) => a.name.localeCompare(b.name));
  }
  function resolveSourceForOwner(ownerSocket, sourceId) {
    const requestedId = sourceId === undefined || sourceId === null || !String(sourceId).trim()
      ? ownerSocket.dreamSourceId : String(sourceId);
    if (!ownerSocket.dreamSourceId || requestedId !== ownerSocket.dreamSourceId) return null;
    const selected = sources.get(requestedId);
    return selected && selected.readyState === WebSocket.OPEN ? selected : null;
  }
  function sourceAuthKind(candidate, proposedId) {
    const supplied = String(candidate || "");
    if (sourceToken && safeEqual(supplied, sourceToken)) return { kind: "static", ownerId: null };
    const enrolled = supplied ? enrolledSourceTokens.get(hashCredential(supplied)) : null;
    if (enrolled && enrolled.sourceId === proposedId) return { kind: "enrolled", ownerId: enrolled.sourceId };
    return null;
  }
  function hashCredential(value) { return crypto.createHash("sha256").update(String(value)).digest("hex"); }
  function issuePairingCode(sourceSocket) {
    const now = Date.now();
    for (const [hash, pairing] of pairingCodes.entries()) if (pairing.expiresAt <= now) pairingCodes.delete(hash);
    for (const [hash, credential] of pairingTokens.entries()) if (credential.expiresAt <= now) pairingTokens.delete(hash);
    for (const [ip, attempt] of pairingAttempts.entries()) if (attempt.resetAt <= now) pairingAttempts.delete(ip);
    const code = crypto.randomBytes(6).toString("hex").toUpperCase();
    const expiresAt = now + PAIRING_CODE_TTL_MS;
    pairingCodes.set(hashCredential(code), { sourceId: sourceSocket.dreamSourceId, expiresAt });
    send(sourceSocket, { type: "pairing-code", code, expiresAt, expiresInSeconds: PAIRING_CODE_TTL_MS / 1000 });
  }
  function registerOwner(socket, sourceId, accessToken, expiresAt) {
    socket.dreamRole = "owner";
    socket.dreamSourceId = sourceId;
    socket.dreamOwnerId = crypto.randomUUID();
    socket.dreamPairingExpiresAt = expiresAt;
    socket.dreamPairingTimer = setTimeout(() => {
      if (socket.readyState === WebSocket.OPEN) reject(socket, "Pairing session expired. Pair again with a fresh source code.");
    }, Math.max(0, expiresAt - Date.now()));
    if (typeof socket.dreamPairingTimer.unref === "function") socket.dreamPairingTimer.unref();
    owners.add(socket);
    send(socket, { type: "registered", role: "client", sourceId, accessToken, expiresAt });
    send(socket, { type: "source-state", connected: sourceListForOwner(socket).length > 0 });
    send(socket, { type: "source-list", sources: sourceListForOwner(socket) });
  }
  function broadcastSourceState() {
    for (const owner of owners) {
      send(owner, { type: "source-state", connected: sourceListForOwner(owner).length > 0 });
      send(owner, { type: "source-list", sources: sourceListForOwner(owner) });
    }
  }
  function reject(socket, message) {
    send(socket, { type: "error", error: message });
    try { socket.close(1008, "Dream Wi-Fi authorization failed"); } catch (_) {}
  }
  function closeStream(id, reason, notifyOwner = true, notifySource = true) {
    const entry = streams.get(id);
    if (!entry) return;
    streams.delete(id);
    if (notifySource) send(entry.source, { type: "tcp-close", id, reason: reason || "closed" });
    if (notifyOwner) send(entry.owner, { type: "tcp-close", id, reason: reason || "closed" });
  }
  function closeStreamsFor(socket, reason) {
    for (const [id, entry] of streams.entries()) {
      if (entry.owner === socket || entry.source === socket) {
        closeStream(id, reason, entry.owner !== socket, entry.source !== socket);
      }
    }
    for (const [id, entry] of udpAssociations.entries()) {
      if (entry.owner === socket || entry.source === socket) {
        udpAssociations.delete(id); clearTimeout(entry.timeout);
        if (entry.owner !== socket) send(entry.owner, { type: 'udp-close', id, reason });
        if (entry.source !== socket) send(entry.source, { type: 'udp-close', id, reason });
      }
    }
  }
  function clearPingsFor(socket) {
    for (const [id, entry] of pendingPings.entries()) {
      if (entry.owner === socket) {
        clearTimeout(entry.timeout);
        pendingPings.delete(id);
      }
    }
  }

  wss.on("connection", (socket, request) => {
    const requestPath = String(request && request.url || "").split("?")[0];
    if (requestPath !== "/api/dream-wifi/tunnel" && requestPath !== "/api/dream-education/dream-wifi/tunnel") {
      socket.close(1008, "Unknown Dream Wi-Fi WebSocket path");
      return;
    }
    const remoteIp = String(socket._socket && socket._socket.remoteAddress || "unknown");
    const activeForIp = connectionsPerIp.get(remoteIp) || 0;
    if (activeForIp >= MAX_CLIENTS_PER_IP) { socket.close(1013, "Connection limit reached"); return; }
    connectionsPerIp.set(remoteIp, activeForIp + 1);
    socket.dreamRemoteIp = remoteIp;
    socket.dreamRole = null;
    socket.registering = false;

    socket.on("message", async (raw) => {
      if (raw.length > MAX_MESSAGE_BYTES) {
        reject(socket, "Message too large.");
        return;
      }
      let message;
      try { message = JSON.parse(raw.toString("utf8")); }
      catch (_) { reject(socket, "Invalid message format."); return; }
      if (!message || typeof message !== "object" || typeof message.type !== "string") {
        reject(socket, "Invalid message.");
        return;
      }

      if (!socket.dreamRole) {
        if (socket.registering || message.type !== "register") {
          reject(socket, "Register a verified endpoint first.");
          return;
        }
        socket.registering = true;

        if (message.role === "source") {
          const proposedId = String(message.sourceId || "").trim();
          const sourceAuth = sourceAuthKind(message.sourceToken, proposedId);
          if (!sourceAuth) {
            reject(socket, "Source endpoint is not authorized or not configured.");
            return;
          }
          const sourceName = String(message.sourceName || "").trim().replace(/[<>\x00-\x1f]/g, "").slice(0, 80) || "Dream Wi-Fi source";
          const sourceId = /^[a-zA-Z0-9_-]{1,80}$/.test(proposedId)
            ? proposedId
            : crypto.createHash("sha256").update(sourceName + "|" + (sourceAuth.ownerId || "static")).digest("hex").slice(0, 24);
          const previous = sources.get(sourceId);
          if (previous && previous !== socket && previous.readyState === WebSocket.OPEN) {
            // A shared source token authorizes source agents, not ownership of an ID.
            // Never let a second connection evict a live source by claiming its ID.
            send(socket, { type: "error", error: "Source ID is already connected." });
            try { socket.close(1013, "Source ID already connected"); } catch (_) {}
            return;
          }
          if (previous && previous !== socket &&
              (previous.dreamSourceOwnerId !== (sourceAuth.ownerId || sourceId) ||
               previous.dreamSourceAuth !== sourceAuth.kind)) {
            reject(socket, "Source ID is already registered to a different authorization scope.");
            return;
          }
          if (previous && previous !== socket) {
            try { previous.close(1012, "Source endpoint replaced"); } catch (_) {}
          }
          socket.dreamSourceId = sourceId;
          socket.dreamSourceName = sourceName;
          socket.dreamSourceAuth = sourceAuth.kind;
          socket.dreamSourceOwnerId = sourceAuth.ownerId || sourceId;
          socket.dreamSourceConnectedAt = new Date().toISOString();
          socket.dreamInternetReady = null;
          socket.dreamInternetCheckedAt = null;
          socket.dreamInternetError = null;
          socket.dreamSourceLatitude = Number.isFinite(Number(message.latitude)) && message.latitude !== null && message.latitude !== "" && Number(message.latitude) >= -90 && Number(message.latitude) <= 90 ? Number(message.latitude) : null;
          socket.dreamSourceLongitude = Number.isFinite(Number(message.longitude)) && message.longitude !== null && message.longitude !== "" && Number(message.longitude) >= -180 && Number(message.longitude) <= 180 ? Number(message.longitude) : null;
          if (sourceAuth.kind === "ticket" && Number.isFinite(sourceAuth.expiresAt)) {
            const remainingMs = Math.max(0, sourceAuth.expiresAt - Date.now());
            socket.dreamSourceTicketTimer = setTimeout(() => {
              if (socket.readyState === WebSocket.OPEN) {
                reject(socket, "Source ticket expired. Generate a new ticket and reconnect.");
              }
            }, remainingMs);
            if (typeof socket.dreamSourceTicketTimer.unref === "function") socket.dreamSourceTicketTimer.unref();
          }
          socket.dreamRole = "source";
          sources.set(sourceId, socket);
          send(socket, { type: "registered", role: "source", sourceId, sourceName });
          issuePairingCode(socket);
          broadcastSourceState();
          return;
        }

        if (message.role === "client" || message.role === "owner") {
          if (socket.readyState !== WebSocket.OPEN) return;
          const now = Date.now();
          const remote = socket.dreamRemoteIp;
          const attempt = pairingAttempts.get(remote) || { count: 0, resetAt: now + 10 * 60 * 1000 };
          if (attempt.resetAt <= now) { attempt.count = 0; attempt.resetAt = now + 10 * 60 * 1000; }
          if (attempt.count >= 20) { reject(socket, "Too many pairing attempts. Wait ten minutes and retry."); return; }

          const suppliedToken = typeof message.accessToken === "string" ? message.accessToken : "";
          if (suppliedToken) {
            const tokenHash = hashCredential(suppliedToken);
            const credential = pairingTokens.get(tokenHash);
            if (!credential || credential.expiresAt <= now) {
              pairingTokens.delete(tokenHash);
              reject(socket, "Pairing session is invalid or expired. Enter a fresh source pairing code.");
              return;
            }
            registerOwner(socket, credential.sourceId, suppliedToken, credential.expiresAt);
            return;
          }

          const suppliedCode = typeof message.pairingCode === "string" ? message.pairingCode.trim().toUpperCase() : "";
          const codeHash = suppliedCode ? hashCredential(suppliedCode) : "";
          const pairing = codeHash ? pairingCodes.get(codeHash) : null;
          if (!pairing || pairing.expiresAt <= now || !sources.has(pairing.sourceId)) {
            attempt.count += 1;
            pairingAttempts.set(remote, attempt);
            reject(socket, "A valid, unexpired pairing code from an online source is required.");
            return;
          }
          pairingCodes.delete(codeHash);
          const accessToken = crypto.randomBytes(32).toString("base64url");
          pairingTokens.set(hashCredential(accessToken), {
            sourceId: pairing.sourceId,
            expiresAt: now + PAIRING_TOKEN_TTL_MS,
            createdAt: now
          });
          pairingAttempts.delete(remote);
          registerOwner(socket, pairing.sourceId, accessToken, now + PAIRING_TOKEN_TTL_MS);
          return;
        }
        reject(socket, "Unsupported endpoint role.");
        return;
      }

      if (socket.dreamRole === "source" && message.type === "source-health") {
        if (typeof message.ready !== "boolean") {
          send(socket, { type: "error", error: "Invalid source health report." });
          return;
        }
        socket.dreamInternetReady = message.ready;
        socket.dreamInternetCheckedAt = new Date().toISOString();
        socket.dreamInternetError = message.ready ? null : String(message.error || "internet_check_failed").slice(0, 120);
        broadcastSourceState();
        return;
      }

      if (socket.dreamRole === "source" && message.type === "revoke-pairings") {
        for (const [tokenHash, credential] of pairingTokens.entries()) {
          if (credential.sourceId === socket.dreamSourceId) pairingTokens.delete(tokenHash);
        }
        for (const owner of [...owners]) {
          if (owner.dreamSourceId === socket.dreamSourceId) {
            closeStreamsFor(owner, "pairing-revoked");
            reject(owner, "The source revoked this pairing. Pair again to reconnect.");
          }
        }
        for (const [codeHash, pairing] of pairingCodes.entries()) {
          if (pairing.sourceId === socket.dreamSourceId) pairingCodes.delete(codeHash);
        }
        send(socket, { type: "pairings-revoked", sourceId: socket.dreamSourceId });
        return;
      }

      if (socket.dreamRole === "owner" && message.type === "ping-source") {
        const selectedSource = resolveSourceForOwner(socket, message.sourceId);
        if (!selectedSource) {
          send(socket, { type: "ping-result", success: false, error: "No accessible source endpoint is connected." });
          return;
        }
        const id = crypto.randomUUID();
        const sentAt = Date.now();
        const timeout = setTimeout(() => {
          pendingPings.delete(id);
          send(socket, { type: "ping-result", success: false, sourceId: selectedSource.dreamSourceId, error: "Source endpoint did not respond in time." });
        }, PING_TIMEOUT_MS);
        pendingPings.set(id, { owner: socket, source: selectedSource, sourceId: selectedSource.dreamSourceId, sentAt, timeout });
        send(selectedSource, { type: "ping", id });
        return;
      }

      if (socket.dreamRole === 'owner' && message.type === 'udp-send') {
        const id = message.id, host = typeof message.host === 'string' ? message.host.trim() : '', port = Number(message.port);
        const chunk = decodeChunk(message.data);
        if (!validStreamId(id) || !host || host.length > 253 || host.includes('/') || host.includes(' ') || !Number.isInteger(port) || port < 1 || port > 65535 || !chunk || chunk.length > MAX_UDP_DATAGRAM_BYTES) {
          send(socket, { type: 'udp-error', id: validStreamId(id) ? id : '', error: 'Invalid UDP destination or datagram.' }); return;
        }
        let entry = udpAssociations.get(id);
        if (!entry) {
          if ([...udpAssociations.values()].filter(item => item.owner === socket).length >= MAX_UDP_ASSOCIATIONS_PER_OWNER) { send(socket, { type: 'udp-error', id, error: 'Too many UDP associations.' }); return; }
          const selectedSource = resolveSourceForOwner(socket, message.sourceId);
          if (!selectedSource) { send(socket, { type: 'udp-error', id, error: 'No accessible source endpoint is connected.' }); return; }
          entry = { owner: socket, source: selectedSource, sourceId: selectedSource.dreamSourceId, timeout: null }; udpAssociations.set(id, entry);
        }
        if (entry.owner !== socket) { send(socket, { type: 'udp-error', id, error: 'UDP association is not owned by this session.' }); return; }
        clearTimeout(entry.timeout); entry.timeout = setTimeout(() => {
          if (udpAssociations.get(id) === entry) { udpAssociations.delete(id); send(entry.source, { type: 'udp-close', id, reason: 'idle-timeout' }); send(entry.owner, { type: 'udp-close', id, reason: 'idle-timeout' }); }
        }, UDP_IDLE_TIMEOUT_MS);
        if (entry.source.bufferedAmount > MAX_WS_BUFFERED_BYTES) { udpAssociations.delete(id); clearTimeout(entry.timeout); send(socket, { type: 'udp-error', id, error: 'Source relay is backpressured.' }); return; }
        send(entry.source, { type: 'udp-send', id, host, port, data: chunk.toString('base64') }); return;
      }
      if (socket.dreamRole === 'owner' && message.type === 'udp-close') {
        const entry = udpAssociations.get(message.id);
        if (entry && entry.owner === socket) { udpAssociations.delete(message.id); clearTimeout(entry.timeout); send(entry.source, { type: 'udp-close', id: message.id, reason: 'owner-closed' }); }
        return;
      }
      if (socket.dreamRole === "owner" && message.type === "tcp-open") {
        const id = message.id;
        const host = typeof message.host === "string" ? message.host.trim() : "";
        const port = Number(message.port);
        const activeForOwner = [...streams.values()].filter(entry => entry.owner === socket).length;
        if (!validStreamId(id) || !host || host.length > 253 || /\s/.test(host) || host.includes("/") || host.includes("\\") ||
            ![80, 443].includes(port)) {
          send(socket, { type: "tcp-error", id: validStreamId(id) ? id : "", error: "Only public HTTP/HTTPS TCP destinations are allowed." });
          return;
        }
        if (activeForOwner >= MAX_STREAMS_PER_OWNER) {
          send(socket, { type: "tcp-error", id, error: "Too many concurrent streams." });
          return;
        }
        const selectedSource = resolveSourceForOwner(socket, message.sourceId);
        if (!selectedSource) {
          send(socket, { type: "tcp-error", id, error: "No accessible source endpoint is connected." });
          return;
        }
        if (streams.has(id)) {
          send(socket, { type: "tcp-error", id, error: "Duplicate stream identifier." });
          return;
        }
        streams.set(id, { owner: socket, source: selectedSource, sourceId: selectedSource.dreamSourceId });
        send(selectedSource, { type: "tcp-open", id, host, port });
        return;
      }

      if (socket.dreamRole === "owner" && message.type === "tcp-data") {
        const entry = streams.get(message.id);
        const chunk = decodeChunk(message.data);
        if (!entry || entry.owner !== socket || !chunk) {
          if (entry && entry.owner === socket) closeStream(message.id, "invalid-data-frame");
          else send(socket, { type: "tcp-error", id: validStreamId(message.id) ? message.id : "", error: "Invalid stream or data frame." });
          return;
        }
        if (entry.source.bufferedAmount > MAX_WS_BUFFERED_BYTES) {
          closeStream(message.id, "source-backpressure-limit");
          return;
        }
        send(entry.source, { type: "tcp-data", id: message.id, data: chunk.toString("base64") });
        return;
      }

      if (socket.dreamRole === "owner" && message.type === "tcp-close") {
        if (validStreamId(message.id)) closeStream(message.id, "owner-closed", false, true);
        return;
      }

      if (socket.dreamRole === "source" && message.type === "pong" && typeof message.id === "string") {
        const entry = pendingPings.get(message.id);
        if (!entry || entry.source !== socket) return;
        clearTimeout(entry.timeout);
        pendingPings.delete(message.id);
        send(entry.owner, { type: "ping-result", success: true, sourceId: entry.sourceId, latency_ms: Math.max(0, Date.now() - entry.sentAt) });
        return;
      }

      if (socket.dreamRole === 'source' && message.type === 'udp-data') {
        const entry = udpAssociations.get(message.id), host = typeof message.host === 'string' ? message.host : '', port = Number(message.port), chunk = decodeChunk(message.data);
        if (!entry || entry.source !== socket || !host || host.length > 253 || !Number.isInteger(port) || port < 1 || port > 65535 || !chunk || chunk.length > MAX_UDP_DATAGRAM_BYTES) return;
        if (entry.owner.bufferedAmount > MAX_WS_BUFFERED_BYTES) { udpAssociations.delete(message.id); clearTimeout(entry.timeout); send(entry.owner, { type: 'udp-close', id: message.id, reason: 'owner-backpressure-limit' }); send(socket, { type: 'udp-close', id: message.id, reason: 'owner-backpressure-limit' }); return; }
        clearTimeout(entry.timeout); entry.timeout = setTimeout(() => { if (udpAssociations.get(message.id) === entry) { udpAssociations.delete(message.id); send(entry.source, { type: 'udp-close', id: message.id, reason: 'idle-timeout' }); send(entry.owner, { type: 'udp-close', id: message.id, reason: 'idle-timeout' }); } }, UDP_IDLE_TIMEOUT_MS);
        send(entry.owner, { type: 'udp-data', id: message.id, host, port, data: chunk.toString('base64') }); return;
      }
      if (socket.dreamRole === 'source' && message.type === 'udp-error') { const entry = udpAssociations.get(message.id); if (entry && entry.source === socket) send(entry.owner, { type: 'udp-error', id: message.id, error: String(message.error || 'UDP relay failed').slice(0, 180) }); return; }
      if (socket.dreamRole === "source" && ["tcp-opened", "tcp-error", "tcp-data", "tcp-close"].includes(message.type)) {
        if (!validStreamId(message.id)) return;
        const entry = streams.get(message.id);
        if (!entry || entry.source !== socket) return;
        if (message.type === "tcp-data") {
          const chunk = decodeChunk(message.data);
          if (!chunk || entry.owner.bufferedAmount > MAX_WS_BUFFERED_BYTES) {
            closeStream(message.id, "owner-backpressure-or-invalid-frame");
            return;
          }
          send(entry.owner, { type: "tcp-data", id: message.id, data: chunk.toString("base64") });
        } else {
          send(entry.owner, message);
          if (message.type === "tcp-close" || message.type === "tcp-error") {
            closeStream(message.id, message.type, false, false);
          }
        }
        return;
      }

      send(socket, { type: "error", error: "Unsupported control message." });
    });

    socket.on("close", () => {
      clearPingsFor(socket);
      closeStreamsFor(socket, "endpoint-disconnected");
      owners.delete(socket);
      if (socket.dreamPairingTimer) clearTimeout(socket.dreamPairingTimer);
      const remainingForIp = Math.max(0, (connectionsPerIp.get(socket.dreamRemoteIp) || 1) - 1);
      if (remainingForIp) connectionsPerIp.set(socket.dreamRemoteIp, remainingForIp); else connectionsPerIp.delete(socket.dreamRemoteIp);
      if (socket.dreamRole === "source" && sources.get(socket.dreamSourceId) === socket) {
        sources.delete(socket.dreamSourceId);
        for (const [codeHash, pairing] of pairingCodes.entries()) {
          if (pairing.sourceId === socket.dreamSourceId) pairingCodes.delete(codeHash);
        }
        broadcastSourceState();
      }
    });
    socket.on("error", () => {});
  });

  wss.sourceConnected = sourceConnected;
  wss.getSources = () => [...sources.values()].map(sourceDetails);
  wss.enrollSource = () => {
    const sourceId = crypto.randomUUID().replace(/-/g, "");
    const sourceTokenValue = crypto.randomBytes(32).toString("base64url");
    const credential = { sourceId, tokenHash: hashCredential(sourceTokenValue), createdAt: Date.now() };
    enrolledSourceTokens.set(credential.tokenHash, { sourceId, createdAt: credential.createdAt });
    if (typeof options.onEnrollSource === "function") options.onEnrollSource(credential);
    return { sourceId, sourceToken: sourceTokenValue };
  };
  wss.getEnrolledSourceCredentials = () => [...enrolledSourceTokens.entries()].map(([tokenHash, credential]) => ({
    sourceId: credential.sourceId, tokenHash, createdAt: credential.createdAt
  }));
  wss.revokePairingsForSource = sourceId => {
    const id = String(sourceId || "");
    for (const [tokenHash, credential] of pairingTokens.entries()) if (credential.sourceId === id) pairingTokens.delete(tokenHash);
    for (const [codeHash, pairing] of pairingCodes.entries()) if (pairing.sourceId === id) pairingCodes.delete(codeHash);
  };
  return wss;
}

module.exports = { attachDreamWifiTunnel };
