package com.dreameducation.wifi;

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
    static final String BASE_URL = "https://dream-education-live-4.onrender.com";
    static final String TUNNEL_URL = "wss://dream-education-live-4.onrender.com/api/dream-education/dream-wifi/tunnel";
    static final int LOCAL_PROXY_PORT = 1081;
    private static final int MAX_CHUNK = 12 * 1024;
    private static final int MAX_UDP_BYTES = 8192;
    private static final int MAX_STREAMS = 8;

    interface Listener {
        void onStatus(String message);
        void onReady();
        void onSources(String[] ids, String[] names);
    }

    private final OkHttpClient http;
    private final boolean sourceOnly;
    private final Network sourceNetwork;
    private final Network controlNetwork;
    private final String sourceName;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ConcurrentHashMap<String, Stream> ownerStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Socket> sourceStreams = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UdpAssociation> udpAssociations = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean readyNotified = new AtomicBoolean(false);
    private final AtomicBoolean ownerRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean sourceRetryScheduled = new AtomicBoolean(false);
    private final AtomicBoolean sessionRefreshRunning = new AtomicBoolean(false);
    private final AtomicBoolean probeInProgress = new AtomicBoolean(false);

    private volatile String accessToken;
    private volatile String refreshToken;
    private volatile String sourceTicket;
    private volatile String selectedSourceId = "";
    private final String sourceId = "android-" + UUID.randomUUID().toString().replace("-", "");
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

    DreamWifiRelay(String accessToken, String refreshToken, String sourceTicket,
                   boolean sourceOnly, Network sourceNetwork, Network controlNetwork, String sourceName, Listener listener) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.sourceTicket = sourceTicket;
        this.sourceOnly = sourceOnly;
        this.sourceNetwork = sourceNetwork;
        this.controlNetwork = controlNetwork;
        this.sourceName = sourceName == null || sourceName.trim().isEmpty()
                ? "Android " + android.os.Build.MODEL : sourceName.trim();
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
        }
        connectSource();
        scheduler.scheduleAtFixedRate(() -> workers.execute(this::renewOwnerSession), 40, 40, TimeUnit.MINUTES);
        scheduler.scheduleAtFixedRate(() -> workers.execute(() -> {
            if (!running.get()) return;
            renewSourceTicket();
            WebSocket current = sourceSocket;
            if (current != null) {
                sourceReady = false;
                current.close(1000, "source ticket rotation");
            }
        }), 10, 10, TimeUnit.HOURS);
        status(sourceOnly
                ? controlNetwork == null
                    ? "No separate validated cellular route found. If Quant blocks internet before payment, activate the package locally first."
                    : "Publishing this Wi-Fi source; control traffic uses cellular data and destination traffic uses the selected Wi-Fi."
                : "Connecting to Dream Tunnel and remote Wi-Fi source…");
    }

    boolean isReady() {
        return running.get() && ownerReady && proxyServer != null && !proxyServer.isClosed();
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
        Request request = new Request.Builder().url(TUNNEL_URL).build();
        ownerSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!running.get()) { webSocket.close(1000, "stopped"); return; }
                ownerSocket = webSocket;
                webSocket.send(new SafeJson().putSafe("type", "register")
                        .putSafe("role", "owner").putSafe("accessToken", accessToken).toString());
                status("Authenticating owner control channel…");
            }
            @Override public void onMessage(WebSocket webSocket, String text) {
                if (webSocket != ownerSocket) return;
                JSONObject message;
                try { message = new JSONObject(text); } catch (Exception ignored) { return; }
                String type = message.optString("type", "");
                if ("registered".equals(type) && "owner".equals(message.optString("role"))) {
                    ownerReady = true;
                    ownerRetrySeconds = 3;
                    status("Owner authenticated; discovering registered Wi-Fi sources…");
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
                    if (reason.toLowerCase().contains("owner access") || reason.toLowerCase().contains("authentication failed")) {
                        workers.execute(DreamWifiRelay.this::renewOwnerSession);
                    }
                    webSocket.close(1008, "owner authorization rejected");
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
        if (!running.get()) return;
        Request request = new Request.Builder().url(TUNNEL_URL).build();
        sourceSocket = http.newWebSocket(request, new WebSocketListener() {
            @Override public void onOpen(WebSocket webSocket, Response response) {
                if (!running.get()) { webSocket.close(1000, "stopped"); return; }
                sourceSocket = webSocket;
                webSocket.send(new SafeJson().putSafe("type", "register")
                        .putSafe("role", "source").putSafe("sourceToken", sourceTicket)
                        .putSafe("sourceId", sourceId)
                        .putSafe("sourceName", sourceName).toString());
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
                    if (reason.toLowerCase().contains("not authorized") || reason.toLowerCase().contains("not configured")) {
                        workers.execute(DreamWifiRelay.this::renewSourceTicket);
                    }
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
        if (!running.get() || !ownerRetryScheduled.compareAndSet(false, true)) return;
        int delay = ownerRetrySeconds;
        ownerRetrySeconds = Math.min(ownerRetrySeconds * 2, 60);
        scheduler.schedule(() -> {
            ownerRetryScheduled.set(false);
            if (running.get() && !ownerReady) connectOwner();
        }, delay, TimeUnit.SECONDS);
    }

    private void scheduleSourceReconnect() {
        if (!running.get() || !sourceRetryScheduled.compareAndSet(false, true)) return;
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
            if (command != 1 || port != 80 && port != 443 || host.isEmpty()) { socksReply(output, 2); return; }
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
                if (parsed == null || parsed.port != 53 || !ownerReady) continue;
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

    private void openSourceStream(JSONObject message) {
        String id = message.optString("id", "");
        String host = message.optString("host", "").trim();
        int port = message.optInt("port", 0);
        Socket socket = null;
        try {
            if (id.isEmpty() || host.isEmpty() || host.length() > 253 || (port != 80 && port != 443)) {
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
            if (port != 53 || host.isEmpty()) throw new IOException("Only DNS UDP on port 53 is supported.");
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

    private void renewOwnerSession() {
        if (!running.get() || refreshToken == null || refreshToken.isEmpty()
                || !sessionRefreshRunning.compareAndSet(false, true)) return;
        HttpURLConnection connection = null;
        try {
            connection = openControlConnection(BASE_URL + "/api/auth/refresh");
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] body = new SafeJson().putSafe("refresh_token", refreshToken).toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int code = connection.getResponseCode();
            InputStream response = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (response == null) throw new IOException("No session-refresh response.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = response) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            JSONObject session = result.optJSONObject("session");
            String newAccess = session == null ? "" : session.optString("access_token", "");
            String newRefresh = session == null ? "" : session.optString("refresh_token", "");
            if (code < 200 || code >= 300 || !result.optBoolean("success") || newAccess.isEmpty() || newRefresh.isEmpty()) {
                status("Dream Education session needs a fresh sign-in before reconnecting.");
                return;
            }
            accessToken = newAccess;
            refreshToken = newRefresh;
        } catch (Exception error) {
            status("Dream Education session refresh failed: " + safeReason(error.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
            sessionRefreshRunning.set(false);
        }
    }

    private void renewSourceTicket() {
        HttpURLConnection connection = null;
        try {
            connection = openControlConnection(BASE_URL + "/api/dream-wifi/source-ticket");
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            try (OutputStream output = connection.getOutputStream()) { output.write(new byte[0]); }
            int code = connection.getResponseCode();
            InputStream response = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (response == null) throw new IOException("No source-ticket response.");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = response) {
                byte[] buffer = new byte[2048]; int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            }
            JSONObject result = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            String fresh = result.optString("token", "");
            if (code < 200 || code >= 300 || !result.optBoolean("success") || fresh.isEmpty()) {
                status("Could not renew the Quant source ticket. Sign in again if the session has expired.");
                return;
            }
            sourceTicket = fresh;
            status("Source ticket renewed; reconnecting to Quant source…");
        } catch (Exception error) {
            status("Source ticket renewal failed: " + safeReason(error.getMessage()));
        } finally {
            if (connection != null) connection.disconnect();
        }
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
                    || (a == 192 && second == 168) || (a == 198 && (second == 18 || second == 19 || second == 51))
                    || (a == 203 && second == 0 && third == 113)) return false;
        } else if (b.length == 16) {
            if ((b[0] & 0xfe) == 0xfc || (b[0] == 0x20 && (b[1] & 0xff) == 0x01
                    && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8)) return false;
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
