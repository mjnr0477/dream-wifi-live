package com.mjnr0477.dreamwifi;

import android.util.Base64;
import android.net.Network;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * Native owner/source relay. All relay sockets bind to loopback or public destinations only.
 * The hosting provider's captive portal/package authentication is still required for internet access.
 */
final class DreamWifiRelay {
    static final String BASE_URL = BuildConfig.DREAM_WIFI_BASE_URL;
    static final String TUNNEL_URL = BASE_URL.isEmpty() ? "" : BASE_URL.replaceFirst("^https://", "wss://").replaceFirst("^http://", "ws://") + "/api/dream-wifi/tunnel";
    static final int LOCAL_PROXY_PORT = 1081;
    private static final int MAX_CHUNK = 12 * 1024;
    private static final int MAX_UDP_BYTES = 8192;
    private static final int MAX_STREAMS = 8;

    interface Listener {
        void onStatus(String message);
        void onReady();
        void onSources(String[] ids, String[] names);
        void onReceiverCredential(String sourceId, String accessToken);
    }

    private final OkHttpClient http;
    private final boolean sourceOnly;
    private final Network sourceNetwork;
    private final Network controlNetwork;
    private final String sourceName;
    private final double sourceLatitude;
    private final double sourceLongitude;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, Stream> ownerStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Socket> sourceStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UdpAssociation> udpAssociations = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean readyNotified = new AtomicBoolean(false);
    private final AtomicBoolean ownerRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean sourceRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean probeInProgress = new AtomicBoolean(false);

    private volatile String accessToken = "";
    private volatile String sourceTicket;
    private volatile String pairingCode;
    private volatile String selectedSourceId = "";
    private final String sourceId;
    private volatile WebSocket ownerSocket;
    private volatile WebSocket sourceSocket;
    private volatile boolean ownerReady;
    private volatile boolean sourceReady;
    private volatile ServerSocket proxyServer;
    private volatile Listener listener;
    private volatile int ownerRetrySeconds = 3;
    private volatile int sourceRetrySeconds = 3;
    private volatile String activeProbeId;
    private volatile StringBuilder activeProbeResponse;

    DreamWifiRelay(String pairingCode, String receiverAccessToken, String sourceToken, String sourceId,
                   boolean sourceOnly, Network sourceNetwork, Network controlNetwork, String sourceName,
                   double sourceLatitude, double sourceLongitude, Listener listener) {
        this.pairingCode = pairingCode == null ? "" : pairingCode.trim().toUpperCase(java.util.Locale.ROOT);
        this.accessToken = receiverAccessToken == null ? "" : receiverAccessToken.trim();
        this.sourceTicket = sourceToken == null ? "" : sourceToken;
        this.sourceId = sourceId == null || sourceId.trim().isEmpty()
                ? "android-" + UUID.randomUUID().toString().replace("-", "") : sourceId.trim();
        this.sourceOnly = sourceOnly;
        this.sourceNetwork = sourceNetwork;
        this.controlNetwork = controlNetwork;
        this.sourceName = sourceName == null || sourceName.trim().isEmpty()
                ? "Android " + android.os.Build.MODEL : sourceName.trim();
        this.sourceLatitude = sourceLatitude;
        this.sourceLongitude = sourceLongitude;
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .pingInterval(25, TimeUnit.SECONDS)
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS);
        // Keep the control WebSocket on validated cellular data when available, while
        // individual destination sockets below remain bound to the Quant Wi-Fi network.
        if (sourceOnly && controlNetwork != null) {
            builder.socketFactory(controlNetwork.getSocketFactory());
            builder.dns(hostname -> Arrays.asList(controlNetwork.getAllByName(hostname)));
        }
        this.http = builder.build();
        this.listener = listener;
    }

    void start() {
        if (!running.compareAndSet(false, true)) return;
        if (!sourceOnly) {
            workers.execute(this::serveSocks);
            connectOwner();
        } else {
            connectSource();
            scheduler.scheduleAtFixedRate(() -> workers.execute(this::reportSourceInternetHealth), 0, 60, TimeUnit.SECONDS);
        }
        status(sourceOnly
                ? controlNetwork == null
                    ? "No separate validated cellular route found. If Quant blocks internet before payment, activate the package locally first."
                    : "Publishing this Wi-Fi source; control traffic uses cellular data and destination traffic uses the selected Wi-Fi."
                : "Connecting to Dream Tunnel and remote Wi-Fi source…");
    }

    boolean isReady() {
        return running.get() && ownerReady && proxyServer != null && !proxyServer.isClosed();
    }

    void revokePairings() {
        if (!sourceOnly || !sourceReady || sourceSocket == null) {
            status("Source must be connected before receiver pairings can be revoked.");
            return;
        }
        sendSource(new SafeJson().putSafe("type", "revoke-pairings").toString());
        status("Requested revocation of all receiver pairings for this source.");
    }

    void setSelectedSourceId(String sourceId) {
        String value = sourceId == null ? "" : sourceId.trim();
        selectedSourceId = value.matches("[a-zA-Z0-9_-]{1,80}") ? value : "";
        status(selectedSourceId.isEmpty()
                ? "Automatic source selection enabled."
                : "Selected Dream Wi-Fi source: " + selectedSourceId);
    }

    private void status(String message) {
        Listener current = listener;
        if (current != null) current.onStatus(message);
    }

    private void notifyReadyIfPossible() {
        if (!isReady()) {
            readyNotified.set(false);
            return;
        }
        if (readyNotified.get()) return;
        if (probeInProgress.compareAndSet(false, true)) startInternetProbe();
    }

    private void startInternetProbe() {
        String id = "dream-probe-" + UUID.randomUUID().toString().replace("-", "");
        activeProbeId = id;
        activeProbeResponse = new StringBuilder();
        boolean sent = sendOwner(new SafeJson().putSafe("type", "tcp-open").putSafe("id", id)
                .putSafe("host", "example.com").putSafe("port", 80).putSafe("sourceId", selectedSourceId).toString());
        if (!sent) {
            finishInternetProbe(id, false, "Could not start the public internet test.");
            return;
        }
        status("Checking public internet through Quant before enabling the VPN…");
        scheduler.schedule(() -> {
            if (id.equals(activeProbeId)) finishInternetProbe(id, false,
                    "No public HTTP response. Complete the Quant package/login portal, then the test will retry.");
        }, 12, TimeUnit.SECONDS);
    }

    private void handleInternetProbeData(String id, String encoded) {
        if (!id.equals(activeProbeId)) return;
        try {
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            StringBuilder response = activeProbeResponse;
            if (response == null) return;
            response.append(new String(bytes, StandardCharsets.ISO_8859_1));
            if (response.length() > 16384) {
                finishInternetProbe(id, false, "The public internet test returned an oversized response.");
                return;
            }
            String value = response.toString();
            int headerEnd = value.indexOf("\r\n\r\n");
            if (headerEnd < 0) return;
            String[] lines = value.substring(0, headerEnd).split("\r\n");
            if (lines.length == 0) {
                finishInternetProbe(id, false, "Invalid HTTP response from the public internet test.");
                return;
            }
            String[] statusParts = lines[0].split(" ");
            int code = statusParts.length > 1 ? Integer.parseInt(statusParts[1]) : 0;
            String location = "";
            for (String line : lines) {
                if (line.toLowerCase().startsWith("location:")) location = line.substring(9).trim();
            }
            boolean allowedRedirect = false;
            if (!location.isEmpty()) {
                try {
                    java.net.URI uri = new java.net.URI(location);
                    String host = uri.getHost();
                    allowedRedirect = host != null && (host.equalsIgnoreCase("example.com") || host.equalsIgnoreCase("www.example.com"));
                    if (!allowedRedirect) {
                        // Keep the relay available in captive-portal mode so the receiving
                        // phone can open the provider redirect through the selected source.
                        // This is not a successful internet check; the package still needs
                        // to be purchased and activated through the provider's normal flow.
                        finishInternetProbe(id, false,
                                "Captive portal redirect detected. VPN is starting so you can open the provider portal; public internet is not yet verified.", true);
                        return;
                    }
                } catch (Exception ignored) {
                    finishInternetProbe(id, false, "Unexpected redirect during the public internet test.");
                    return;
                }
            }
            if (code >= 300 && code < 400 && allowedRedirect) {
                finishInternetProbe(id, true, "Public internet verified through Quant. Starting the VPN…");
            } else if (code == 200) {
                String body = value.substring(headerEnd + 4).toLowerCase();
                if (body.contains("example domain")) {
                    finishInternetProbe(id, true, "Public internet verified through Quant. Starting the VPN…");
                } else if (body.length() > 1024) {
                    finishInternetProbe(id, false, "Unexpected HTTP content received. A Quant captive portal may still be active.");
                }
            } else if (code < 300 || code >= 400) {
                finishInternetProbe(id, false, "Public internet test returned HTTP " + code + ". Complete the Quant portal/package step and retry.");
            }
        } catch (Exception error) {
            finishInternetProbe(id, false, "Could not validate the public HTTP response.");
        }
    }

    private void finishInternetProbe(String id, boolean success, String message) {
        finishInternetProbe(id, success, message, false);
    }

    private void finishInternetProbe(String id, boolean success, String message, boolean captivePortalDetected) {
        if (id == null || !id.equals(activeProbeId)) return;
        activeProbeId = null;
        activeProbeResponse = null;
        probeInProgress.set(false);
        sendOwner(new SafeJson().putSafe("type", "tcp-close").putSafe("id", id).toString());
        if ((success || captivePortalDetected) && isReady()) {
            readyNotified.set(true);
            status(message);
            Listener current = listener;
            if (current != null) current.onReady();
            if (captivePortalDetected && running.get()) {
                // Re-test periodically so a package activation can transition the
                // relay from portal mode to verified public internet without a restart.
                scheduler.schedule(() -> {
                    if (!running.get()) return;
                    readyNotified.set(false);
                    workers.execute(this::notifyReadyIfPossible);
                }, 20, TimeUnit.SECONDS);
            }
        } else {
            readyNotified.set(false);
            status(message);
            if (running.get()) scheduler.schedule(() -> workers.execute(this::notifyReadyIfPossible), 20, TimeUnit.SECONDS);
        }
    }

    private void connectOwner() {
        if (!running.get()) return;
        if (accessToken.isEmpty() && pairingCode.isEmpty()) {
            status("No valid pairing credential. Disconnect and enter a fresh source pairing code.");
            return;
        }
        Request request = new Request.Builder().url(TUNNEL_URL).build();
        ownerSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!running.get()) { webSocket.close(1000, "stopped"); return; }
                ownerSocket = webSocket;
                SafeJson registration = new SafeJson().putSafe("type", "register").putSafe("role", "client");
                if (!accessToken.isEmpty()) registration.putSafe("accessToken", accessToken);
                else registration.putSafe("pairingCode", pairingCode);
                webSocket.send(registration.toString());
                status("Authenticating owner control channel…");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (webSocket != ownerSocket) return;
                JSONObject message;
                try { message = new JSONObject(text); } catch (Exception ignored) { return; }
                String type = message.optString("type", "");
                if ("registered".equals(type) && ("client".equals(message.optString("role")) || "owner".equals(message.optString("role")))) {
                    accessToken = message.optString("accessToken", accessToken);
                    pairingCode = "";
                    String pairedSourceId = message.optString("sourceId", "");
                    if (!accessToken.isEmpty() && !pairedSourceId.isEmpty()) {
                        Listener current = listener;
                        if (current != null) current.onReceiverCredential(pairedSourceId, accessToken);
                    }
                    if (selectedSourceId.isEmpty() && !pairedSourceId.isEmpty()) selectedSourceId = pairedSourceId;
                    ownerReady = true;
                    ownerRetrySeconds = 3;
                    status("Receiver paired securely; checking the selected source and public internet…");
                    notifyReadyIfPossible();
                } else if ("tcp-opened".equals(type)) {
                    String id = message.optString("id", "");
                    if (id.equals(activeProbeId)) {
                        String requestText = "GET / HTTP/1.1\r\nHost: example.com\r\nConnection: close\r\nUser-Agent: Dream-WiFi-Native-Probe/1.0\r\n\r\n";
                        sendOwner(new SafeJson().putSafe("type", "tcp-data").putSafe("id", id)
                                .putSafe("data", Base64.encodeToString(requestText.getBytes(StandardCharsets.US_ASCII), Base64.NO_WRAP)).toString());
                        return;
                    }
                    Stream stream = ownerStreams.get(id);
                    if (stream != null) { stream.openedSuccessfully = true; stream.opened.countDown(); }
                } else if ("tcp-error".equals(type)) {
                    String id = message.optString("id", "");
                    if (id.equals(activeProbeId)) {
                        finishInternetProbe(id, false, "Quant source could not reach the public internet. Complete the provider portal/package step and retry.");
                        return;
                    }
                    Stream stream = ownerStreams.get(id);
                    if (stream != null) {
                        stream.error = message.optString("error", "Destination connection failed");
                        stream.opened.countDown();
                    }
                } else if ("tcp-data".equals(type)) {
                    String id = message.optString("id", "");
                    if (id.equals(activeProbeId)) {
                        handleInternetProbeData(id, message.optString("data", ""));
                        return;
                    }
                    Stream stream = ownerStreams.get(id);
                    if (stream != null) {
                        try {
                            byte[] data = Base64.decode(message.optString("data", ""), Base64.DEFAULT);
                            if (data.length <= MAX_CHUNK && stream.socket != null && !stream.socket.isClosed()) {
                                synchronized (stream.output) { stream.output.write(data); stream.output.flush(); }
                            } else closeOwnerStream(id, true);
                        } catch (Exception error) { closeOwnerStream(id, true); }
                    }
                } else if ("tcp-close".equals(type)) {
                    String id = message.optString("id", "");
                    if (id.equals(activeProbeId)) {
                        finishInternetProbe(id, false, "Public internet test closed before a valid response arrived.");
                        return;
                    }
                    closeOwnerStream(id, false);
                } else if ("udp-data".equals(type)) {
                    handleUdpResponse(message);
                } else if ("udp-error".equals(type) || "udp-close".equals(type)) {
                    UdpAssociation association = udpAssociations.get(message.optString("id", ""));
                    if (association != null && "udp-error".equals(type)) status("DNS/UDP relay error: " + message.optString("error", "unknown error"));
                } else if ("source-state".equals(type)) {
                    status(message.optBoolean("connected") ? "At least one Dream Wi-Fi source is connected." : "No Dream Wi-Fi source is currently connected.");
                } else if ("source-list".equals(type)) {
                    org.json.JSONArray items = message.optJSONArray("sources");
                    java.util.ArrayList<String> ids = new java.util.ArrayList<>();
                    java.util.ArrayList<String> names = new java.util.ArrayList<>();
                    if (items != null) {
                        for (int i = 0; i < items.length(); i++) {
                            JSONObject item = items.optJSONObject(i);
                            if (item == null || !item.optBoolean("connected", true)) continue;
                            String id = item.optString("id", "");
                            if (id.isEmpty()) continue;
                            ids.add(id);
                            names.add(item.optString("name", id));
                        }
                    }
                    Listener current = listener;
                    if (current != null) current.onSources(ids.toArray(new String[0]), names.toArray(new String[0]));
                    workers.execute(DreamWifiRelay.this::notifyReadyIfPossible);
                } else if ("error".equals(type)) {
                    String reason = message.optString("error", "authorization failed");
                    status("Owner channel rejected: " + reason);
                    String lowerReason = reason.toLowerCase(java.util.Locale.ROOT);
                    if (lowerReason.contains("invalid or expired") || lowerReason.contains("pairing session expired") || lowerReason.contains("revoked")) {
                        accessToken = "";
                        pairingCode = "";
                        status("Receiver pairing expired or was revoked. Disconnect and enter a fresh source pairing code.");
                    } else if (accessToken.isEmpty()) {
                        pairingCode = "";
                    }
                    webSocket.close(1008, "receiver authorization rejected");
                }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (webSocket != ownerSocket) return;
                ownerSocket = null;
                ownerReady = false;
                closeAllOwnerStreams();
                notifyReadyIfPossible();
                status("Owner channel closed (" + code + "): " + safeReason(reason));
                scheduleOwnerReconnect();
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (webSocket != ownerSocket) return;
                ownerSocket = null;
                ownerReady = false;
                closeAllOwnerStreams();
                notifyReadyIfPossible();
                status("Owner channel failed: " + safeReason(error.getMessage()));
                scheduleOwnerReconnect();
            }
        });
    }

    private void connectSource() {
        if (!running.get() || sourceTicket == null || sourceTicket.isEmpty()) return;
        Request request = new Request.Builder().url(TUNNEL_URL).build();
        sourceSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!running.get()) { webSocket.close(1000, "stopped"); return; }
                sourceSocket = webSocket;
                SafeJson registration = new SafeJson().putSafe("type", "register")
                        .putSafe("role", "source").putSafe("sourceToken", sourceTicket)
                        .putSafe("sourceId", sourceId).putSafe("sourceName", sourceName);
                if (Double.isFinite(sourceLatitude) && sourceLatitude >= -90 && sourceLatitude <= 90
                        && Double.isFinite(sourceLongitude) && sourceLongitude >= -180 && sourceLongitude <= 180) {
                    registration.putSafe("latitude", sourceLatitude).putSafe("longitude", sourceLongitude);
                }
                webSocket.send(registration.toString());
                status(sourceOnly ? "Authenticating Wi-Fi source tunnel…" : "Authenticating remote Wi-Fi source…");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (webSocket != sourceSocket) return;
                JSONObject message;
                try { message = new JSONObject(text); } catch (Exception ignored) { return; }
                String type = message.optString("type", "");
                if ("registered".equals(type) && "source".equals(message.optString("role"))) {
                    sourceReady = true;
                    sourceRetrySeconds = 3;
                    status(sourceOnly ? "This phone is now publishing its connected Wi-Fi source." : "Remote Wi-Fi source authenticated; waiting for owner channel…");
                    notifyReadyIfPossible();
                } else if ("pairing-code".equals(type)) {
                    status("Source is online. Receiver pairing code: " + message.optString("code", "")
                            + " · expires in " + Math.max(1, (message.optInt("expiresInSeconds", 600) + 59) / 60)
                            + " minute(s). Share this code only with the receiver you authorize.");
                } else if ("pairings-revoked".equals(type)) {
                    status("All receiver pairings for this source have been revoked.");
                } else if ("ping".equals(type)) {
                    sendSource(new SafeJson().putSafe("type", "pong").putSafe("id", message.optString("id", "")).toString());
                } else if ("tcp-open".equals(type)) {
                    workers.execute(() -> openSourceStream(message));
                } else if ("tcp-data".equals(type)) {
                    Socket socket = sourceStreams.get(message.optString("id", ""));
                    if (socket != null) {
                        try {
                            byte[] data = Base64.decode(message.optString("data", ""), Base64.DEFAULT);
                            if (data.length > MAX_CHUNK) { closeSourceStream(message.optString("id", ""), true); return; }
                            synchronized (socket) { socket.getOutputStream().write(data); socket.getOutputStream().flush(); }
                        } catch (Exception error) { closeSourceStream(message.optString("id", ""), true); }
                    }
                } else if ("tcp-close".equals(type)) {
                    closeSourceStream(message.optString("id", ""), false);
                } else if ("udp-send".equals(type)) {
                    workers.execute(() -> relayUdp(message));
                } else if ("error".equals(type)) {
                    String reason = message.optString("error", "authorization failed");
                    status("Source channel rejected: " + reason);
                    if (reason.toLowerCase().contains("not authorized") || reason.toLowerCase().contains("not configured")) sourceTicket = "";
                    webSocket.close(1008, "source authorization rejected");
                }
            }
            @Override public void onClosed(WebSocket webSocket, int code, String reason) {
                if (webSocket != sourceSocket) return;
                sourceSocket = null;
                sourceReady = false;
                closeAllSourceStreams();
                notifyReadyIfPossible();
                status("Quant source disconnected (" + code + "): " + safeReason(reason));
                scheduleSourceReconnect();
            }
            @Override public void onFailure(WebSocket webSocket, Throwable error, Response response) {
                if (webSocket != sourceSocket) return;
                sourceSocket = null;
                sourceReady = false;
                closeAllSourceStreams();
                notifyReadyIfPossible();
                status("Quant source connection failed: " + safeReason(error.getMessage()));
                scheduleSourceReconnect();
            }
        });
    }

    private void scheduleOwnerReconnect() {
        if (!running.get() || (accessToken.isEmpty() && pairingCode.isEmpty()) || !ownerRetryScheduled.compareAndSet(false, true)) return;
        int delay = ownerRetrySeconds;
        ownerRetrySeconds = Math.min(ownerRetrySeconds * 2, 60);
        scheduler.schedule(() -> {
            ownerRetryScheduled.set(false);
            if (running.get() && !ownerReady) connectOwner();
        }, delay, TimeUnit.SECONDS);
    }

    private void scheduleSourceReconnect() {
        if (!running.get() || sourceTicket == null || sourceTicket.isEmpty() || !sourceRetryScheduled.compareAndSet(false, true)) return;
        int delay = sourceRetrySeconds;
        sourceRetrySeconds = Math.min(sourceRetrySeconds * 2, 60);
        scheduler.schedule(() -> {
            sourceRetryScheduled.set(false);
            if (running.get() && !sourceReady) connectSource();
        }, delay, TimeUnit.SECONDS);
    }

    private void serveSocks() {
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), LOCAL_PROXY_PORT), 32);
            proxyServer = server;
            status("Local SOCKS5 relay is listening.");
            notifyReadyIfPossible();
            while (running.get() && !server.isClosed()) {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                workers.execute(() -> handleSocksClient(client));
            }
        } catch (IOException error) {
            if (running.get()) status("Local relay could not bind port " + LOCAL_PROXY_PORT + ": " + safeReason(error.getMessage()));
        }
    }

    private void handleSocksClient(Socket client) {
        String streamId = null;
        UdpAssociation udp = null;
        try {
            client.setSoTimeout(20000);
            InputStream input = client.getInputStream();
            OutputStream output = client.getOutputStream();
            int version = input.read();
            int methods = input.read();
            if (version != 5 || methods < 1) return;
            byte[] methodList = readExactly(input, methods);
            boolean noAuth = false;
            for (byte method : methodList) if ((method & 0xff) == 0) noAuth = true;
            output.write(new byte[]{5, (byte) (noAuth ? 0 : 0xff)}); output.flush();
            if (!noAuth) return;

            byte[] header = readExactly(input, 4);
            if ((header[0] & 0xff) != 5 || header[2] != 0) { socksReply(output, 1); return; }
            int command = header[1] & 0xff;
            int atyp = header[3] & 0xff;
            String host = readSocksHost(input, atyp);
            byte[] portBytes = readExactly(input, 2);
            int port = ((portBytes[0] & 0xff) << 8) | (portBytes[1] & 0xff);

            if (command == 3) {
                udp = createUdpAssociation(client, output);
                if (udp == null) socksReply(output, 1);
                else {
                    client.setSoTimeout(0);
                    while (running.get() && input.read() != -1) { /* keep the SOCKS UDP control connection open */ }
                }
                return;
            }
            if (command != 1 || port < 1 || port > 65535 || host.isEmpty()) { socksReply(output, 2); return; }
            if (!ownerReady || ownerSocket == null) { socksReply(output, 1); return; }
            if (ownerStreams.size() >= MAX_STREAMS) { socksReply(output, 1); return; }

            streamId = UUID.randomUUID().toString().replace("-", "");
            Stream stream = new Stream(client, output);
            ownerStreams.put(streamId, stream);
            JSONObject open = new SafeJson().putSafe("type", "tcp-open").putSafe("id", streamId)
                    .putSafe("host", host).putSafe("port", port).putSafe("sourceId", selectedSourceId);
            if (!sendOwner(open.toString())) { ownerStreams.remove(streamId); socksReply(output, 1); return; }
            if (!stream.opened.await(15, TimeUnit.SECONDS) || !stream.openedSuccessfully) {
                ownerStreams.remove(streamId);
                socksReply(output, 5);
                return;
            }
            client.setSoTimeout(0);
            socksReply(output, 0);
            byte[] buffer = new byte[MAX_CHUNK];
            int count;
            while (running.get() && (count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                byte[] chunk = Arrays.copyOf(buffer, count);
                if (!sendOwner(new SafeJson().putSafe("type", "tcp-data").putSafe("id", streamId)
                        .putSafe("data", Base64.encodeToString(chunk, Base64.NO_WRAP)).toString())) break;
            }
        } catch (Exception ignored) {
            // Socket disconnects are expected when browsers cancel requests or the tunnel reconnects.
        } finally {
            if (streamId != null) closeOwnerStream(streamId, true);
            if (udp != null) closeUdpAssociation(udp.id, true);
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private UdpAssociation createUdpAssociation(Socket control, OutputStream output) throws IOException {
        if (!ownerReady || ownerSocket == null) return null;
        DatagramSocket datagram = new DatagramSocket(null);
        datagram.setReuseAddress(true);
        datagram.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        String id = UUID.randomUUID().toString().replace("-", "");
        UdpAssociation association = new UdpAssociation(id, datagram);
        udpAssociations.put(id, association);
        int port = datagram.getLocalPort();
        output.write(new byte[]{5, 0, 0, 1, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        output.flush();
        workers.execute(() -> receiveUdp(association));
        return association;
    }

    private void receiveUdp(UdpAssociation association) {
        byte[] buffer = new byte[65535];
        while (running.get() && !association.socket.isClosed()) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                association.socket.receive(packet);
                if (!packet.getAddress().isLoopbackAddress()) continue;
                ParsedUdp parsed = parseUdp(packet.getData(), packet.getLength());
                if (parsed == null || parsed.port < 1 || parsed.port > 65535 || !ownerReady) continue;
                association.endpoint = new InetSocketAddress(packet.getAddress(), packet.getPort());
                sendOwner(new SafeJson().putSafe("type", "udp-send").putSafe("id", association.id)
                        .putSafe("host", parsed.host).putSafe("port", parsed.port)
                        .putSafe("data", Base64.encodeToString(parsed.data, Base64.NO_WRAP)).putSafe("sourceId", selectedSourceId).toString());
            } catch (IOException error) {
                if (!association.socket.isClosed()) status("Local DNS relay error: " + safeReason(error.getMessage()));
                break;
            }
        }
    }

    private void handleUdpResponse(JSONObject message) {
        UdpAssociation association = udpAssociations.get(message.optString("id", ""));
        if (association == null || association.endpoint == null || association.socket.isClosed()) return;
        try {
            byte[] data = Base64.decode(message.optString("data", ""), Base64.DEFAULT);
            if (data.length > MAX_UDP_BYTES) return;
            byte[] packet = buildUdpPacket(message.optString("host", ""), message.optInt("port", 53), data);
            if (packet != null) association.socket.send(new DatagramPacket(packet, packet.length, association.endpoint));
        } catch (Exception ignored) {}
    }

    private void reportSourceInternetHealth() {
        if (!running.get() || !sourceOnly || sourceNetwork == null || !sourceReady) return;
        workers.execute(() -> {
            boolean ready = false;
            String error = "internet_probe_failed";
            HttpURLConnection connection = null;
            try {
                java.net.URL url = new java.net.URL("https://connectivitycheck.gstatic.com/generate_204");
                connection = (HttpURLConnection) sourceNetwork.openConnection(url);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("GET");
                ready = connection.getResponseCode() == 204;
                error = ready ? "" : "http_" + connection.getResponseCode();
            } catch (Exception failure) {
                error = safeReason(failure.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
            sendSource(new SafeJson().putSafe("type", "source-health").putSafe("ready", ready)
                    .putSafe("error", error).putSafe("checkedAt", java.time.Instant.now().toString()).toString());
        });
    }

    private void openSourceStream(JSONObject message) {
        String id = message.optString("id", "");
        String host = message.optString("host", "").trim();
        int port = message.optInt("port", 0);
        Socket socket = null;
        try {
            if (id.isEmpty() || host.isEmpty() || host.length() > 253 || port < 1 || port > 65535) {
                sendSourceError(id, "Invalid or unsupported destination."); return;
            }
            if (sourceStreams.size() >= MAX_STREAMS) { sendSourceError(id, "Source stream limit reached."); return; }
            InetAddress target = resolvePublicAddress(host);
            socket = new Socket();
            if (sourceOnly && sourceNetwork != null) sourceNetwork.bindSocket(socket);
            socket.connect(new InetSocketAddress(target, port), 12000);
            socket.setSoTimeout(5 * 60 * 1000);
            socket.setTcpNoDelay(true);
            if (!running.get() || !sourceReady) { socket.close(); return; }
            sourceStreams.put(id, socket);
            sendSource(new SafeJson().putSafe("type", "tcp-opened").putSafe("id", id).toString());
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[MAX_CHUNK];
            int count;
            while (running.get() && (count = input.read(buffer)) != -1) {
                if (count == 0) continue;
                byte[] chunk = Arrays.copyOf(buffer, count);
                if (!sendSource(new SafeJson().putSafe("type", "tcp-data").putSafe("id", id)
                        .putSafe("data", Base64.encodeToString(chunk, Base64.NO_WRAP)).toString())) break;
            }
        } catch (SocketTimeoutException error) {
            sendSourceError(id, "TCP destination timed out.");
        } catch (Exception error) {
            sendSourceError(id, safeReason(error.getMessage()));
        } finally {
            if (socket != null) {
                sourceStreams.remove(id, socket);
                try { socket.close(); } catch (Exception ignored) {}
            }
            if (running.get() && sourceReady) sendSource(new SafeJson().putSafe("type", "tcp-close").putSafe("id", id).toString());
        }
    }

    private void relayUdp(JSONObject message) {
        String id = message.optString("id", "");
        String host = message.optString("host", "").trim();
        int port = message.optInt("port", 0);
        try {
            if (port < 1 || port > 65535 || host.isEmpty()) throw new IOException("Invalid UDP destination.");
            InetAddress target = resolvePublicAddress(host);
            byte[] data = Base64.decode(message.optString("data", ""), Base64.DEFAULT);
            if (data.length == 0 || data.length > MAX_UDP_BYTES) throw new IOException("DNS datagram size is not supported.");
            try (DatagramSocket socket = new DatagramSocket()) {
                if (sourceOnly && sourceNetwork != null) sourceNetwork.bindSocket(socket);
                socket.setSoTimeout(3500);
                socket.send(new DatagramPacket(data, data.length, target, port));
                byte[] buffer = new byte[MAX_UDP_BYTES];
                DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                socket.receive(response);
                byte[] result = Arrays.copyOf(response.getData(), response.getLength());
                sendSource(new SafeJson().putSafe("type", "udp-data").putSafe("id", id)
                        .putSafe("host", host).putSafe("port", port)
                        .putSafe("data", Base64.encodeToString(result, Base64.NO_WRAP)).toString());
            }
        } catch (Exception error) {
            sendSource(new SafeJson().putSafe("type", "udp-error").putSafe("id", id)
                    .putSafe("error", safeReason(error.getMessage())).toString());
        }
    }


    private HttpURLConnection openControlConnection(String address) throws IOException {
        java.net.URL url = new java.net.URL(address);
        if (sourceOnly && controlNetwork != null) {
            return (HttpURLConnection) controlNetwork.openConnection(url);
        }
        return (HttpURLConnection) url.openConnection();
    }

    private InetAddress resolvePublicAddress(String host) throws IOException {
        InetAddress[] answers = InetAddress.getAllByName(host);
        if (answers.length == 0) throw new IOException("Destination did not resolve.");
        for (InetAddress answer : answers) if (!isPublicAddress(answer)) throw new IOException("Private or reserved destination is blocked.");
        return answers[0];
    }

    private boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, second = b[1] & 255, third = b[2] & 255;
            if (a == 0 || a >= 224 || (a == 100 && second >= 64 && second <= 127)
                    || (a == 192 && second == 0 && (third == 0 || third == 2))
                    || (a == 192 && second == 88 && third == 99)
                    || (a == 192 && second == 168)
                    || (a == 198 && (second == 18 || second == 19 || (second == 51 && third == 100)))
                    || (a == 203 && second == 0 && third == 113)) return false;
        } else if (b.length == 16) {
            int firstWord = ((b[0] & 0xff) << 8) | (b[1] & 0xff);
            int secondWord = ((b[2] & 0xff) << 8) | (b[3] & 0xff);
            if (firstWord < 0x2000 || firstWord > 0x3fff
                    || (firstWord == 0x2001 && (secondWord <= 0x01ff || secondWord == 0x0db8))
                    || firstWord == 0x2002
                    || (firstWord == 0x3fff && secondWord <= 0x0fff)) return false;
            boolean mapped = true;
            for (int i = 0; i < 10; i++) if (b[i] != 0) mapped = false;
            if (mapped && (b[10] & 255) == 255 && (b[11] & 255) == 255) return false;
        }
        return true;
    }

    private ParsedUdp parseUdp(byte[] packet, int length) {
        try {
            if (length < 10 || packet[0] != 0 || packet[1] != 0 || packet[2] != 0) return null;
            int atyp = packet[3] & 255, offset;
            String host;
            if (atyp == 1) {
                if (length < 10) return null;
                host = (packet[4] & 255) + "." + (packet[5] & 255) + "." + (packet[6] & 255) + "." + (packet[7] & 255);
                offset = 8;
            } else if (atyp == 3) {
                int n = packet[4] & 255;
                if (n == 0 || length < 7 + n) return null;
                host = new String(packet, 5, n, StandardCharsets.UTF_8);
                offset = 5 + n;
            } else if (atyp == 4) {
                if (length < 22) return null;
                byte[] raw = Arrays.copyOfRange(packet, 4, 20);
                host = InetAddress.getByAddress(raw).getHostAddress();
                offset = 20;
            } else return null;
            int port = ((packet[offset] & 255) << 8) | (packet[offset + 1] & 255);
            byte[] data = Arrays.copyOfRange(packet, offset + 2, length);
            if (port < 1 || data.length == 0 || data.length > MAX_UDP_BYTES) return null;
            return new ParsedUdp(host, port, data);
        } catch (Exception ignored) { return null; }
    }

    private byte[] buildUdpPacket(String host, int port, byte[] data) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(new byte[]{0, 0, 0});
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            if (bytes.length == 4) { out.write(1); out.write(bytes); }
            else { byte[] name = host.getBytes(StandardCharsets.UTF_8); if (name.length > 255) return null; out.write(3); out.write(name.length); out.write(name); }
            out.write((port >> 8) & 255); out.write(port & 255); out.write(data);
            return out.toByteArray();
        } catch (Exception ignored) { return null; }
    }

    private String readSocksHost(InputStream input, int atyp) throws IOException {
        if (atyp == 1) {
            byte[] b = readExactly(input, 4);
            return (b[0] & 255) + "." + (b[1] & 255) + "." + (b[2] & 255) + "." + (b[3] & 255);
        }
        if (atyp == 3) {
            int length = input.read();
            if (length <= 0) throw new IOException("Invalid SOCKS domain name.");
            return new String(readExactly(input, length), StandardCharsets.UTF_8);
        }
        if (atyp == 4) return InetAddress.getByAddress(readExactly(input, 16)).getHostAddress();
        throw new IOException("Unsupported SOCKS address type.");
    }

    private byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(data, offset, length - offset);
            if (count < 0) throw new EOFException("Unexpected end of SOCKS request.");
            offset += count;
        }
        return data;
    }

    private void socksReply(OutputStream output, int code) throws IOException {
        output.write(new byte[]{5, (byte) code, 0, 1, 0, 0, 0, 0, 0, 0});
        output.flush();
    }

    private void sendSourceError(String id, String error) {
        sendSource(new SafeJson().putSafe("type", "tcp-error").putSafe("id", id)
                .putSafe("error", safeReason(error)).toString());
    }

    private boolean sendOwner(String text) {
        WebSocket socket = ownerSocket;
        return ownerReady && socket != null && socket.send(text);
    }

    private boolean sendSource(String text) {
        WebSocket socket = sourceSocket;
        return sourceReady && socket != null && socket.send(text);
    }

    private void closeOwnerStream(String id, boolean notify) {
        Stream stream = ownerStreams.remove(id);
        if (stream == null) return;
        stream.opened.countDown();
        if (notify) sendOwner(new SafeJson().putSafe("type", "tcp-close").putSafe("id", id).toString());
        try { stream.socket.close(); } catch (Exception ignored) {}
    }

    private void closeAllOwnerStreams() {
        for (String id : ownerStreams.keySet()) closeOwnerStream(id, false);
    }

    private void closeSourceStream(String id, boolean notify) {
        Socket socket = sourceStreams.remove(id);
        if (notify) sendSource(new SafeJson().putSafe("type", "tcp-close").putSafe("id", id).toString());
        if (socket != null) try { socket.close(); } catch (Exception ignored) {}
    }

    private void closeAllSourceStreams() {
        for (String id : sourceStreams.keySet()) closeSourceStream(id, false);
    }

    private void closeUdpAssociation(String id, boolean notify) {
        UdpAssociation association = udpAssociations.remove(id);
        if (association == null) return;
        try { association.socket.close(); } catch (Exception ignored) {}
        if (notify) sendOwner(new SafeJson().putSafe("type", "udp-close").putSafe("id", id).toString());
    }

    private String safeReason(String value) {
        if (value == null || value.trim().isEmpty()) return "no details";
        String clean = value.replaceAll("[\\r\\n]", " ");
        return clean.substring(0, Math.min(180, clean.length()));
    }

    void stop() {
        if (!running.compareAndSet(true, false)) return;
        readyNotified.set(false);
        probeInProgress.set(false);
        activeProbeId = null;
        activeProbeResponse = null;
        ownerReady = false;
        sourceReady = false;
        WebSocket owner = ownerSocket; ownerSocket = null;
        WebSocket source = sourceSocket; sourceSocket = null;
        if (owner != null) owner.close(1000, "stopped");
        if (source != null) source.close(1000, "stopped");
        ServerSocket server = proxyServer; proxyServer = null;
        if (server != null) try { server.close(); } catch (Exception ignored) {}
        closeAllOwnerStreams();
        closeAllSourceStreams();
        for (String id : udpAssociations.keySet()) closeUdpAssociation(id, false);
        workers.shutdownNow();
        scheduler.shutdownNow();
        http.dispatcher().executorService().shutdown();
        http.connectionPool().evictAll();
        listener = null;
    }

    private static final class SafeJson extends JSONObject {
        SafeJson putSafe(String key, Object value) {
            try { super.put(key, value); } catch (Exception ignored) {}
            return this;
        }
    }

    private static final class Stream {
        final Socket socket;
        final OutputStream output;
        final CountDownLatch opened = new CountDownLatch(1);
        volatile boolean openedSuccessfully;
        volatile String error;
        Stream(Socket socket, OutputStream output) { this.socket = socket; this.output = output; }
    }

    private static final class UdpAssociation {
        final String id;
        final DatagramSocket socket;
        volatile InetSocketAddress endpoint;
        UdpAssociation(String id, DatagramSocket socket) { this.id = id; this.socket = socket; }
    }

    private static final class ParsedUdp {
        final String host; final int port; final byte[] data;
        ParsedUdp(String host, int port, byte[] data) { this.host = host; this.port = port; this.data = data; }
    }
}
