package com.mjnr0477.dreamwifi;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RouterDiscoveryActivity extends Activity {
    private static final int LOCATION_REQUEST = 412;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout results;
    private TextView status;
    private TextView count;
    private Button scanButton;
    private WifiManager wifi;
    private int localCount;
    private int mapCount;
    private final Handler scanHandler = new Handler(Looper.getMainLooper());
    private BroadcastReceiver scanReceiver;
    private Runnable scanTimeout;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(16), dp(20), dp(30));
        page.setBackgroundColor(0xfff4f7fd);
        scroll.addView(page);
        setContentView(scroll);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("◉", 25, 0xffffffff, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(shape(0xff1764ed, 16));
        top.addView(logo, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(12), 0, 0, 0);
        TextView name = text("Dream Wi-Fi", 21, 0xff14264b, true);
        TextView tagline = text("Find a connection around you", 12, 0xff74819a, false);
        brand.addView(name); brand.addView(tagline);
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        page.addView(top);

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setPadding(dp(22), dp(24), dp(22), dp(22));
        hero.setBackground(shapeGradient());
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.topMargin = dp(22);
        page.addView(hero, heroLp);

        TextView eyebrow = text("SMART WI-FI FINDER", 11, 0xffd8e7ff, true);
        hero.addView(eyebrow);
        TextView headline = text("Your next connection
starts here.", 29, 0xffffffff, true);
        headline.setPadding(0, dp(10), 0, dp(8));
        hero.addView(headline);
        TextView description = text("Scan real Wi-Fi signals nearby and explore mapped Wi-Fi places up to 500 km away.", 14, 0xffe4edff, false);
        description.setLineSpacing(dp(3), 1f);
        hero.addView(description);

        scanButton = new Button(this);
        scanButton.setText("⌕   SCAN FOR WI-FI");
        scanButton.setAllCaps(false);
        scanButton.setTextSize(16);
        scanButton.setTypeface(null, Typeface.BOLD);
        scanButton.setTextColor(0xff1749a8);
        scanButton.setBackground(shape(0xffffffff, 16));
        scanButton.setOnClickListener(v -> scanEverything());
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(-1, dp(56));
        scanLp.topMargin = dp(20);
        hero.addView(scanButton, scanLp);

        LinearLayout stats = new LinearLayout(this);
        stats.setPadding(dp(16), dp(15), dp(16), dp(15));
        stats.setGravity(Gravity.CENTER_VERTICAL);
        stats.setBackground(shape(0xffffffff, 18));
        LinearLayout.LayoutParams statsLp = new LinearLayout.LayoutParams(-1, -2);
        statsLp.topMargin = dp(16);
        page.addView(stats, statsLp);
        LinearLayout statText = new LinearLayout(this);
        statText.setOrientation(LinearLayout.VERTICAL);
        statText.addView(text("DISCOVERY RESULTS", 11, 0xff78869f, true));
        count = text("Ready to scan", 18, 0xff172b50, true);
        count.setPadding(0, dp(4), 0, 0);
        statText.addView(count);
        stats.addView(statText, new LinearLayout.LayoutParams(0, -2, 1));
        TextView radius = text("500 km radius", 12, 0xff1b55b9, true);
        radius.setPadding(dp(11), dp(8), dp(11), dp(8));
        radius.setBackground(shape(0xffeaf1ff, 50));
        stats.addView(radius);

        status = text("Tap Scan for Wi-Fi to begin. Allow location so Android can scan networks and find your position.", 13, 0xff5d6d86, false);
        status.setLineSpacing(dp(3), 1f);
        status.setPadding(dp(2), dp(14), dp(2), dp(4));
        page.addView(status);

        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        section.setPadding(0, dp(20), 0, dp(10));
        TextView sectionTitle = text("Available connections", 20, 0xff172b50, true);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView live = text("LOCAL + MAP", 10, 0xff47618d, true);
        live.setPadding(dp(9), dp(6), dp(9), dp(6));
        live.setBackground(shape(0xffe5ecf8, 50));
        section.addView(live);
        page.addView(section);

        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        page.addView(results);
        addEmptyState();

        TextView footer = text("A real Wi-Fi signal is connectable only when your phone can detect it. Mapped places are leads, not live routers; they cannot be joined from a map pin alone. Passwords and joining approvals stay under your control.", 12, 0xff77849a, false);
        footer.setLineSpacing(dp(3), 1f);
        footer.setPadding(dp(3), dp(20), dp(3), 0);
        page.addView(footer);

        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
        } else {
            updateStatus();
        }
    }

    private void scanEverything() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQUEST);
            return;
        }
        scanButton.setEnabled(false);
        scanButton.setText("Scanning…");
        status.setText("Scanning physical Wi-Fi signals and looking up mapped Wi-Fi places near your location…");
        results.removeAllViews();
        localCount = 0;
        mapCount = 0;
        if (wifi == null || !wifi.isWifiEnabled()) {
            addInfoCard("Turn on Wi-Fi", "Switch on Wi-Fi in Android settings, then tap Scan again.", "OPEN WI-FI SETTINGS", () -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
            localCount = 1;
            loadMappedPlaces();
            return;
        }

        // Android scans asynchronously. Wait for its broadcast instead of displaying stale results.
        scanReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (!WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) return;
                finishPhysicalScan();
            }
        };
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED);
            else registerReceiver(scanReceiver, filter);
        } catch (Exception e) {
            scanReceiver = null;
        }
        scanTimeout = this::finishPhysicalScan;
        scanHandler.postDelayed(scanTimeout, 7000L);
        boolean started = false;
        try { started = wifi.startScan(); } catch (SecurityException ignored) {}
        if (!started) {
            // Android can rate-limit scans. The timeout path will show the latest available results.
            status.setText("Android limited a fresh scan. Checking the latest results…");
        }
    }

    private void finishPhysicalScan() {
        if (scanTimeout != null) scanHandler.removeCallbacks(scanTimeout);
        scanTimeout = null;
        if (scanReceiver != null) {
            try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
            scanReceiver = null;
        }
        List<ScanResult> found = Collections.emptyList();
        try { found = wifi == null ? Collections.emptyList() : wifi.getScanResults(); } catch (SecurityException ignored) {}
        if (found == null) found = Collections.emptyList();
        Collections.sort(found, (a, b) -> Integer.compare(b.level, a.level));
        addSectionHeader("Nearby Wi-Fi signals · strongest first");
        for (ScanResult network : found) {
            if (network == null) continue;
            String ssid = network.SSID == null || network.SSID.trim().isEmpty() ? "(Hidden Wi-Fi network)" : network.SSID.trim();
            addLocalNetwork(ssid, network.level, network.capabilities == null ? "" : network.capabilities);
            localCount++;
        }
        if (found.isEmpty()) {
            addInfoCard("No scan results available", "Android may rate-limit scans or hide results until permissions and Location are enabled. Wait a few seconds and tap Scan again.", null, null);
        }
        loadMappedPlaces();
    }

    private void loadMappedPlaces() {
        Location location = lastKnownLocation();
        if (location == null) {
            addInfoCard("Location not available", "Turn on device Location and allow Dream Wi-Fi location permission to search mapped places within 500 km.", null, null);
            finishScan("Nearby radio scan complete. Map search needs your current location.");
            return;
        }
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String base = String.valueOf(BuildConfig.DREAM_WIFI_BASE_URL).trim();
                while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
                String endpoint = base + "/api/wifi/hotspots?latitude=" + location.getLatitude()
                        + "&longitude=" + location.getLongitude() + "&radius_km=500";
                connection = (HttpURLConnection) new URL(endpoint).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null || code < 200 || code >= 300) throw new IllegalStateException("Map service HTTP " + code);
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
                }
                JSONObject data = new JSONObject(body.toString());
                JSONArray places = data.optJSONArray("hotspots");
                runOnUiThread(() -> {
                    addSectionHeader("Wi-Fi places within 500 km");
                    if (places == null || places.length() == 0) {
                        addInfoCard("No mapped places found", "The public map has no matching listings for this search area. That does not mean there are no Wi-Fi routers.", null, null);
                    } else {
                        for (int i = 0; i < places.length(); i++) {
                            JSONObject p = places.optJSONObject(i);
                            if (p == null) continue;
                            String placeName = p.optString("name", "Wi-Fi place");
                            String distance = p.optString("distance_km", "?");
                            String kind = p.optString("venue_type", "Mapped Wi-Fi venue");
                            addMapPlace(placeName, distance, kind);
                            mapCount++;
                        }
                    }
                    finishScan("Scan finished. Nearby radio results show real signals; mapped places are unverified listings, not live router connections.");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    addSectionHeader("Wi-Fi places within 500 km");
                    addInfoCard("Map search unavailable", "Could not load mapped places right now. Nearby Wi-Fi scan results remain available. Check internet and scan again.", "RETRY SCAN", this::scanEverything);
                    finishScan("Nearby scan finished. Map lookup failed.");
                });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void addLocalNetwork(String ssid, int rssi, String capabilities) {
        boolean open = capabilities.isEmpty() || (!capabilities.contains("WEP") && !capabilities.contains("PSK")
                && !capabilities.contains("EAP") && !capabilities.contains("SAE") && !capabilities.contains("WPA"));
        String quality = rssi >= -55 ? "Excellent signal" : rssi >= -67 ? "Good signal" : rssi >= -75 ? "Fair signal" : "Weak signal";
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("⌁", 27, 0xff1764ed, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(0xffeaf1ff, 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, dp(4), 0);
        info.addView(text(ssid, 16, 0xff172b50, true));
        info.addView(text(quality + "  ·  " + rssi + " dBm", 12, 0xff63728a, false));
        info.addView(text(open ? "Open network · Android approval required" : "Password-protected · credentials required", 11, open ? 0xff17805b : 0xff8a5b17, false));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        Button connect = smallButton(open ? "CONNECT" : "CHOOSE NETWORK");
        connect.setOnClickListener(v -> connectToNetwork(ssid, open, capabilities));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(44));
        lp.topMargin = dp(13);
        card.addView(connect, lp);
        results.addView(card);
    }

    private void connectToNetwork(String ssid, boolean open, String capabilities) {
        if (ssid.equals("(Hidden Wi-Fi network)")) {
            Toast.makeText(this, "Hidden networks must be added manually in Android Wi-Fi settings.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        if (!open || Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Choose this network in Android Wi-Fi settings and enter its password if you are authorized.", Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            return;
        }
        try {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder().setSsid(ssid).build();
            List<WifiNetworkSuggestion> suggestions = new ArrayList<>();
            suggestions.add(suggestion);
            int result = wifi.addNetworkSuggestions(suggestions);
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Toast.makeText(this, "Connection request sent. Android may ask you to approve Dream Wi-Fi; connection is not confirmed until Android joins the network.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } else {
                Toast.makeText(this, "Android did not accept the Wi-Fi suggestion (" + result + "). Open Wi-Fi settings to connect.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Android could not submit this network: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void addMapPlace(String name, String distance, String kind) {
        LinearLayout card = card();
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("⌖", 24, 0xff7b57c7, true);
        icon.setGravity(Gravity.CENTER);
        icon.setBackground(shape(0xfff1ebff, 15));
        row.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setPadding(dp(12), 0, 0, 0);
        info.addView(text(name, 15, 0xff172b50, true));
        info.addView(text(distance + " km away · " + kind, 12, 0xff63728a, false));
        info.addView(text("MAP LISTING · NOT LIVE VERIFIED", 10, 0xff8a5b17, true));
        row.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row);
        TextView explanation = text("This listing has no live router signal or credentials. Visit the venue and use its Wi-Fi details to connect.", 12, 0xff63728a, false);
        explanation.setPadding(0, dp(11), 0, 0);
        card.addView(explanation);
        results.addView(card);
    }

    private void addSectionHeader(String title) {
        TextView header = text(title, 17, 0xff172b50, true);
        header.setPadding(dp(2), dp(20), 0, dp(8));
        results.addView(header);
    }

    private void addEmptyState() {
        addInfoCard("Ready when you are", "Tap the Scan button to find nearby Wi-Fi signals and mapped Wi-Fi places around your location.", null, null);
    }

    private void addInfoCard(String title, String detail, String action, Runnable callback) {
        LinearLayout card = card();
        card.addView(text(title, 15, 0xff172b50, true));
        TextView body = text(detail, 13, 0xff63728a, false);
        body.setLineSpacing(dp(3), 1f);
        body.setPadding(0, dp(6), 0, 0);
        card.addView(body);
        if (action != null && callback != null) {
            Button button = smallButton(action);
            button.setOnClickListener(v -> callback.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(42));
            lp.topMargin = dp(12);
            card.addView(button, lp);
        }
        results.addView(card);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(15), dp(15), dp(15), dp(15));
        card.setBackground(shape(0xffffffff, 20));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(10);
        results.addView(card, lp);
        results.removeView(card);
        return card;
    }

    private Button smallButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setTypeface(null, Typeface.BOLD);
        b.setTextColor(0xff1556c0);
        b.setBackground(shape(0xffeaf1ff, 12));
        return b;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable shapeGradient() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{0xff102d71, 0xff155eef, 0xff4388ff});
        d.setCornerRadius(dp(26));
        return d;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == LOCATION_REQUEST) {
            if (hasLocationPermission()) {
                status.setText("Location permission allowed. Tap Scan for Wi-Fi to search.");
            } else {
                status.setText("Location permission was denied. Nearby scan and 500 km search may be restricted by Android.");
            }
        }
    }

    private Location lastKnownLocation() {
        if (!hasLocationPermission()) return null;
        try {
            LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location candidate = lm.getLastKnownLocation(provider);
                if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            }
            return best;
        } catch (Exception e) { return null; }
    }

    private void finishScan(String message) {
        scanButton.setEnabled(true);
        scanButton.setText("⌕   SCAN AGAIN");
        count.setText(localCount + " nearby · " + mapCount + " mapped");
        status.setText(message);
    }

    private void updateStatus() {
        status.setText("Ready. Tap Scan for Wi-Fi to show real nearby signals and map listings.");
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }
}
