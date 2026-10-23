package com.dreamwifi.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public final class DreamVpnService extends VpnService {
    public static final String ACTION_START = "com.dreamwifi.android.START";
    public static final String ACTION_STOP = "com.dreamwifi.android.STOP";
    public static final String ACTION_SELECT_SOURCE = "com.dreamwifi.android.SELECT_SOURCE";
    public static final String ACTION_REVOKE_PAIRINGS = "com.dreamwifi.android.REVOKE_PAIRINGS";
    static final String ACTION_SOURCES = "com.dreamwifi.android.SOURCES";
    public static final String EXTRA_SELECTED_SOURCE_ID = "dream_wifi_selected_source_id";
    public static final String EXTRA_ACCESS_TOKEN = "dream_wifi_access_token";
    public static final String EXTRA_REFRESH_TOKEN = "dream_wifi_refresh_token";
    public static final String EXTRA_SOURCE_TICKET = "dream_wifi_source_ticket";
    public static final String EXTRA_PAIRING_CODE = "dream_wifi_pairing_code";
    public static final String EXTRA_SOURCE_TOKEN = "dream_wifi_source_token";
    public static final String EXTRA_SOURCE_ID = "dream_wifi_source_id";
    public static final String EXTRA_SOURCE_LATITUDE = "dream_wifi_source_latitude";
    public static final String EXTRA_SOURCE_LONGITUDE = "dream_wifi_source_longitude";
    public static final String EXTRA_SOURCE_ONLY = "dream_wifi_source_only";
    private static final int ID = 7201;
    private static final String CHANNEL = "dream_wifi_vpn";

    private volatile boolean running = false;
    private volatile boolean captivePortalMode = false;
    private volatile int tunFd = -1;
    private DreamWifiRelay relay;

    static { System.loadLibrary("hev-socks5-tunnel"); }
    public native boolean TProxyStartService(String configPath, int fd);
    public native boolean TProxyStopService();
    public native boolean TProxyIsRunning();
    public native long[] TProxyGetStats();

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_STOP : intent.getAction();
        if (ACTION_SELECT_SOURCE.equals(action)) {
            String selected = intent == null ? "" : intent.getStringExtra(EXTRA_SELECTED_SOURCE_ID);
            DreamWifiRelay current = relay;
            if (current != null) current.setSelectedSourceId(selected);
            updateNotification(selected == null || selected.isEmpty()
                    ? "Dream Wi-Fi will automatically choose an available source."
                    : "Dream Wi-Fi source selected: " + selected);
            return START_STICKY;
        }
        if (ACTION_STOP.equals(action)) {
            stopTunnel();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_REVOKE_PAIRINGS.equals(action)) {
            DreamWifiRelay current = relay;
            if (current != null) current.revokePairings();
            else updateNotification("Start source sharing before revoking receiver pairings.");
            return START_STICKY;
        }
        startForeground(ID, notification("Connecting to Dream Tunnel…"));
        if (relay != null) {
            updateNotification("Dream Wi-Fi is already connecting or running.");
            return START_NOT_STICKY;
        }
        boolean sourceOnly = intent != null && intent.getBooleanExtra(EXTRA_SOURCE_ONLY, false);
        String pairingCode = intent == null ? "" : intent.getStringExtra(EXTRA_PAIRING_CODE);
        String receiverAccessToken = intent == null ? "" : intent.getStringExtra(EXTRA_ACCESS_TOKEN);
        String sourceToken = intent == null ? "" : intent.getStringExtra(EXTRA_SOURCE_TOKEN);
        String enrolledSourceId = intent == null ? "" : intent.getStringExtra(EXTRA_SOURCE_ID);
        double sourceLatitude = intent == null ? Double.NaN : intent.getDoubleExtra(EXTRA_SOURCE_LATITUDE, Double.NaN);
        double sourceLongitude = intent == null ? Double.NaN : intent.getDoubleExtra(EXTRA_SOURCE_LONGITUDE, Double.NaN);
        if (sourceOnly ? sourceToken == null || sourceToken.isEmpty() || enrolledSourceId == null || enrolledSourceId.isEmpty()
                : (pairingCode == null || pairingCode.trim().isEmpty())
                        && (receiverAccessToken == null || receiverAccessToken.trim().isEmpty())) {
            updateNotification(sourceOnly
                    ? "Source enrollment failed. Publish this phone again to obtain a source credential."
                    : "Enter a valid one-time source pairing code in Dream Wi-Fi.");
            stopSelf();
            return START_NOT_STICKY;
        }
        Network sourceNetwork = sourceOnly ? findConnectedWifiNetwork() : null;
        if (sourceOnly && sourceNetwork == null) {
            updateNotification("Connect this phone to a Wi-Fi network with internet before publishing it as a source.");
            stopSelf();
            return START_NOT_STICKY;
        }
        String sourceName = sourceOnly ? getConnectedWifiName() : "Android " + Build.MODEL;
        Network controlNetwork = sourceOnly ? findAlternateControlNetwork(sourceNetwork) : null;
        relay = new DreamWifiRelay(pairingCode, receiverAccessToken, sourceToken, enrolledSourceId, sourceOnly, sourceNetwork, controlNetwork, sourceName, sourceLatitude, sourceLongitude, new DreamWifiRelay.Listener() {
            @Override public void onStatus(String message) {
                if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("captive portal redirect detected")) {
                    captivePortalMode = true;
                } else if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("public internet verified")) {
                    captivePortalMode = false;
                }
                if (message != null) {
                    String normalized = message.toLowerCase(java.util.Locale.ROOT);
                    if (sourceOnly && normalized.contains("source channel rejected")
                            && (normalized.contains("not authorized") || normalized.contains("not configured") || normalized.contains("expired"))) {
                        DreamWifiCredentialStore.clearSource(DreamVpnService.this);
                    }
                    if (!sourceOnly && normalized.contains("receiver pairing expired or was revoked")) {
                        DreamWifiCredentialStore.clearReceiver(DreamVpnService.this);
                    }
                }
                updateNotification(message);
            }
            @Override public void onSources(String[] ids, String[] names) {
                Intent sources = new Intent(ACTION_SOURCES);
                sources.setPackage(getPackageName());
                sources.putExtra("ids", ids);
                sources.putExtra("names", names);
                sendBroadcast(sources);
            }
            @Override public void onReceiverCredential(String pairedSourceId, String accessToken) {
                try {
                    DreamWifiCredentialStore.saveReceiver(DreamVpnService.this, pairedSourceId, accessToken);
                } catch (Exception error) {
                    updateNotification("Receiver connected, but its session could not be saved securely. Re-pair if the app process restarts.");
                }
            }
            @Override public void onReady() {
                if (sourceOnly) return;
                try {
                    startTunnel();
                } catch (Exception error) {
                    stopTunnelInterfaceOnly();
                    updateNotification("VPN start failed: " + safe(error.getMessage()));
                }
            }
        });
        relay.setSelectedSourceId(intent == null ? "" : intent.getStringExtra(EXTRA_SELECTED_SOURCE_ID));
        relay.start();
        return START_NOT_STICKY;
    }

    private Network findConnectedWifiNetwork() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        Network active = manager.getActiveNetwork();
        if (active != null && isInternetWifi(manager, active)) return active;
        for (Network network : manager.getAllNetworks()) {
            if (isInternetWifi(manager, network)) return network;
        }
        return null;
    }


    private Network findAlternateControlNetwork(Network sourceNetwork) {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return null;
        for (Network network : manager.getAllNetworks()) {
            if (sourceNetwork != null && sourceNetwork.equals(network)) continue;
            NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return network;
            }
        }
        return null;
    }

    private boolean isInternetWifi(ConnectivityManager manager, Network network) {
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private String getConnectedWifiName() {
        try {
            WifiManager manager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = manager == null ? null : manager.getConnectionInfo();
            String ssid = info == null ? "" : info.getSSID();
            if (ssid != null && !ssid.equals("<unknown ssid>")) {
                return ssid.replaceAll("^\\\"|\\\"$", "").trim();
            }
        } catch (Exception ignored) {}
        return "Android " + Build.MODEL;
    }

    private synchronized void startTunnel() throws IOException {
        if (running && TProxyIsRunning()) return;
        try (Socket probe = new Socket()) {
            probe.connect(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), DreamWifiRelay.LOCAL_PROXY_PORT), 1200);
        } catch (IOException error) {
            throw new IOException("The native SOCKS5 relay is not listening on loopback.");
        }

        File config = new File(getFilesDir(), "dream-wifi-tun.yml");
        String yaml = "tunnel:\n" +
                "  name: tun0\n" +
                "  mtu: 1500\n" +
                "  ipv4: 198.18.0.1\n" +
                "  ipv6: 'fd00::1'\n" +
                "  icmp: 'off'\n" +
                "socks5:\n" +
                "  address: 127.0.0.1\n" +
                "  port: " + DreamWifiRelay.LOCAL_PROXY_PORT + "\n" +
                "  udp: 'udp'\n" +
                "mapdns:\n" +
                "  address: 198.18.0.2\n" +
                "  port: 53\n" +
                "  network: 100.64.0.0\n" +
                "  netmask: 255.192.0.0\n" +
                "  cache-size: 4096\n" +
                "misc:\n" +
                "  connect-timeout: 10000\n" +
                "  tcp-read-write-timeout: 120000\n" +
                "  udp-read-write-timeout: 30000\n" +
                "  log-level: warn\n";
        try (FileOutputStream output = new FileOutputStream(config)) {
            output.write(yaml.getBytes(StandardCharsets.UTF_8));
        }

        Builder builder = new Builder().setSession("Dream Wi-Fi").setMtu(1500)
                .addAddress("198.18.0.1", 32).addAddress("fd00::1", 128)
                .addDnsServer("198.18.0.2").addRoute("0.0.0.0", 0).addRoute("::", 0);
        try { builder.addDisallowedApplication(getPackageName()); }
        catch (PackageManager.NameNotFoundException ignored) {}
        try { builder.addDisallowedApplication("com.termux"); }
        catch (PackageManager.NameNotFoundException ignored) {}

        ParcelFileDescriptor tun = builder.establish();
        if (tun == null) throw new IOException("Android did not establish the VPN interface.");
        tunFd = tun.detachFd();
        if (!TProxyStartService(config.getAbsolutePath(), tunFd)) {
            try (ParcelFileDescriptor close = ParcelFileDescriptor.adoptFd(tunFd)) {}
            tunFd = -1;
            throw new IOException("Native TUN-to-SOCKS forwarder did not start.");
        }
        running = true;
        updateNotification("Dream VPN connected. Testing actual internet access is still required.");
    }

    private synchronized void stopTunnelInterfaceOnly() {
        if (running) {
            try { TProxyStopService(); } catch (Exception ignored) {}
            running = false;
        }
        if (tunFd >= 0) {
            try (ParcelFileDescriptor close = ParcelFileDescriptor.adoptFd(tunFd)) {}
            catch (Exception ignored) {}
            tunFd = -1;
        }
    }

    private synchronized void stopTunnel() {
        stopTunnelInterfaceOnly();
        DreamWifiRelay current = relay;
        relay = null;
        if (current != null) current.stop();
    }

    private void updateNotification(String text) {
        try { getSystemService(NotificationManager.class).notify(ID, notification(text)); }
        catch (Exception ignored) {}
        Intent update = new Intent(MainActivity.ACTION_STATUS);
        update.setPackage(getPackageName());
        update.putExtra("message", text);
        sendBroadcast(update);
    }

    private Notification notification(String text) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(CHANNEL, "Dream Wi-Fi VPN", NotificationManager.IMPORTANCE_LOW));
        }
        PendingIntent pending = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        builder.setContentTitle("Dream Wi-Fi").setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentIntent(pending).setOngoing(true);
        if (captivePortalMode) {
            Intent portalIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com"));
            portalIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            PendingIntent portalPending = PendingIntent.getActivity(this, 7202, portalIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
            builder.addAction(android.R.drawable.ic_menu_view, "Open Wi-Fi portal", portalPending);
        }
        return builder.build();
    }

    private String safe(String value) {
        if (value == null || value.trim().isEmpty()) return "unknown error";
        String clean = value.replace('\n', ' ').replace('\r', ' ');
        return clean.substring(0, Math.min(160, clean.length()));
    }

    @Override public void onRevoke() {
        stopTunnel();
        stopForeground(true);
        stopSelf();
        super.onRevoke();
    }

    @Override public void onDestroy() {
        stopTunnel();
        super.onDestroy();
    }
}
