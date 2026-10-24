package com.mjnr0477.dreamwifi;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class LocationDiscoveryService extends Service {
    public static final String ACTION_START = "com.mjnr0477.dreamwifi.LOCATION_DISCOVERY_START";
    public static final String ACTION_STOP = "com.mjnr0477.dreamwifi.LOCATION_DISCOVERY_STOP";
    public static final String ACTION_LOCATION = "com.mjnr0477.dreamwifi.LOCATION_UPDATED";
    private static final String CHANNEL_ID = "dream_wifi_location_discovery";
    private static final int NOTIFICATION_ID = 7202;
    private static final long MIN_REFRESH_MS = 15L * 60L * 1000L;
    private static final long MIN_MOVEMENT_REFRESH_MS = 5L * 60L * 1000L;
    private static final float MIN_MOVEMENT_METERS = 2000f;

    private LocationManager locationManager;
    private LocationListener locationListener;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile Location lastLocation;
    private volatile long lastQueryAt;
    private volatile String lastSummary = "Waiting for location";
    private String baseUrl = "";

    @Override public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        baseUrl = String.valueOf(BuildConfig.DREAM_WIFI_BASE_URL).trim();
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopTracking();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, notification("Starting automatic discovery…"));
        if (!isConfigured()) {
            updateNotification("Tracking paused: configure a separate development backend to enable mapping.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!hasLocationPermission()) {
            updateNotification("Allow location access to start automatic discovery.");
            stopSelf();
            return START_NOT_STICKY;
        }
        startTracking();
        return START_STICKY;
    }

    private boolean isConfigured() {
        Uri uri = Uri.parse(baseUrl);
        return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null && !uri.getHost().trim().isEmpty();
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void startTracking() {
        if (locationListener != null) return;
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (locationManager == null) {
            updateNotification("Location service is unavailable on this phone.");
            return;
        }
        locationListener = new LocationListener() {
            @Override public void onLocationChanged(Location location) {
                if (location == null) return;
                Location previous = lastLocation;
                lastLocation = new Location(location);
                getSharedPreferences("dream_wifi_location_discovery", MODE_PRIVATE).edit()
                        .putLong("latitude_bits", Double.doubleToRawLongBits(location.getLatitude()))
                        .putLong("longitude_bits", Double.doubleToRawLongBits(location.getLongitude()))
                        .putLong("updated_at", location.getTime())
                        .apply();
                Intent update = new Intent(ACTION_LOCATION);
                update.setPackage(getPackageName());
                update.putExtra("latitude", location.getLatitude());
                update.putExtra("longitude", location.getLongitude());
                sendBroadcast(update);

                long now = System.currentTimeMillis();
                boolean moved = previous == null || previous.distanceTo(location) >= MIN_MOVEMENT_METERS;
                if (lastQueryAt == 0L || now - lastQueryAt >= MIN_REFRESH_MS || (moved && now - lastQueryAt >= MIN_MOVEMENT_REFRESH_MS)) {
                    lastQueryAt = now;
                    queryDiscovery(location);
                } else {
                    updateNotification("Automatic discovery is active · last update " + lastSummary);
                }
            }
        };

        boolean requested = false;
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 120000L, 250f, locationListener);
                requested = true;
            }
        } catch (Exception ignored) { }
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 120000L, 250f, locationListener);
                requested = true;
            }
        } catch (Exception ignored) { }
        if (!requested) {
            updateNotification("Turn on Location in Android settings to map nearby discovery results.");
            return;
        }
        try {
            Location cached = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if (cached == null) cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (cached != null) locationListener.onLocationChanged(cached);
        } catch (SecurityException ignored) { }
        updateNotification("Automatic discovery is running in the background.");
    }

    private void queryDiscovery(Location location) {
        worker.execute(() -> {
            int places = -1;
            try {
                JSONObject hotspotResult = getJson("/api/wifi/hotspots?latitude=" + location.getLatitude()
                        + "&longitude=" + location.getLongitude() + "&radius_km=500");
                if (hotspotResult.optBoolean("success", false)) places = hotspotResult.optInt("count", 0);
            } catch (Exception ignored) { }

            if (places < 0) {
                lastSummary = "map unavailable";
                updateNotification("Background discovery is running · mapped Wi-Fi lookup unavailable; will retry.");
                return;
            }
            lastSummary = places + " mapped Wi-Fi places within 500 km";
            updateNotification("Location updated · " + lastSummary + " (not live-verified)");
        });
    }

    private JSONObject getJson(String path) throws Exception {
        URL url = new URL(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(15000);
        connection.setRequestProperty("Accept", "application/json");
        try {
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream == null) throw new IllegalStateException("Empty discovery response");
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
            }
            if (status < 200 || status >= 300) throw new IllegalStateException("Discovery HTTP " + status);
            return new JSONObject(body.toString());
        } finally {
            connection.disconnect();
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Dream Wi-Fi automatic discovery",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows when background location discovery is active.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 7202, open,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        return builder.setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Dream Wi-Fi discovery")
                .setContentText(text)
                .setContentIntent(pending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification(text));
    }

    private void stopTracking() {
        if (locationManager != null && locationListener != null) {
            try { locationManager.removeUpdates(locationListener); } catch (Exception ignored) { }
        }
        locationListener = null;
        lastLocation = null;
    }

    @Override public void onDestroy() {
        stopTracking();
        worker.shutdownNow();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
