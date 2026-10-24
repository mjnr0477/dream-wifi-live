package com.mjnr0477.dreamwifi;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One-phone router discovery prototype. Local RSSI comes only from actual Wi-Fi scans;
 * remote map entries are locations tagged by the public map, not remotely measurable routers.
 */
public final class RouterDiscoveryActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private TextView status;
    private WifiManager wifi;
    private String pendingOpenSsid;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 36);
        root.setBackgroundColor(0xfff5f7fb);
        scroll.addView(root);
        setContentView(scroll);

        TextView title = new TextView(this);
        title.setText("Dream Wi-Fi");
        title.setTextSize(30);
        title.setTextColor(0xff14213d);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Find nearby Wi-Fi. Map known Wi-Fi places up to 500 km away.");
        subtitle.setTextSize(15);
        subtitle.setTextColor(0xff526079);
        root.addView(subtitle);

        status = new TextView(this);
        status.setTextSize(15);
        status.setTextColor(0xff25334d);
        status.setPadding(0, 20, 0, 14);
        root.addView(status);

        Button scan = button("Scan physical Wi-Fi now");
        scan.setOnClickListener(v -> scanLocal());
        root.addView(scan);

        Button verify = button("Test real HTTPS internet");
        verify.setOnClickListener(v -> verifyInternet());
        root.addView(verify);

        Button map = button("Find mapped Wi-Fi places within 500 km");
        map.setOnClickListener(v -> searchMappedPlaces());
        root.addView(map);

        Button background = button("Enable background discovery");
        background.setOnClickListener(v -> enableBackgroundDiscovery());
        root.addView(background);

        Button settings = button("Open Android Wi-Fi settings");
        settings.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        root.addView(settings);

        TextView note = new TextView(this);
        note.setText("Important: signal strength is measurable only for routers your phone can physically detect. A map pin is not proof that a router is online or open. Secured networks require the owner's password or permission. A distant router needs its own authorized internet-connected remote-access gateway; distance alone cannot extend Wi-Fi radio range.");
        note.setTextSize(13);
        note.setTextColor(0xff596579);
        note.setPadding(0, 18, 0, 12);
        root.addView(note);

        content = root;
        updateNetworkStatus();
        if (hasLocationPermission()) scanLocal();
        else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 412);
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(15);
        return b;
    }

    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == 412 && hasLocationPermission()) scanLocal();
        else if (requestCode == 412) status.setText("Allow location permission to scan Wi-Fi networks on this Android version.");
        if (requestCode == 415 && pendingOpenSsid != null) {
            if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED) {
                String ssid = pendingOpenSsid; pendingOpenSsid = null; suggestOpenNetwork(ssid);
            } else Toast.makeText(this, "Nearby Wi-Fi permission was not granted.", Toast.LENGTH_LONG).show();
        }
        if (requestCode == 413 && hasLocationPermission()) {
            if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 414);
            } else startBackgroundService();
        } else if (requestCode == 414) {
            if (Build.VERSION.SDK_INT < 29 || checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startBackgroundService();
            } else Toast.makeText(this, "Background location was not allowed. Discovery may be limited.", Toast.LENGTH_LONG).show();
        }
    }

    private void enableBackgroundDiscovery() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 413);
            return;
        }
        if (Build.VERSION.SDK_INT >= 29 && checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION}, 414);
            return;
        }
        startBackgroundService();
    }

    private void startBackgroundService() {
        try {
            Intent intent = new Intent(this, LocationDiscoveryService.class);
            intent.setAction(LocationDiscoveryService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
            Toast.makeText(this, "Background discovery requested. Android may limit updates for battery and privacy.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start background discovery: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateNetworkStatus() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        Network active = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = cm == null || active == null ? null : cm.getNetworkCapabilities(active);
        boolean internet = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        boolean validated = internet && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        status.setText(validated ? "Internet: verified on the current connection." :
                internet ? "Internet: network detected, but Android has not verified internet access." :
                "Internet: no verified active connection.");
    }

    private void verifyInternet() {
        status.setText("Testing a real HTTPS request over the phone's active connection…");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL("https://connectivitycheck.gstatic.com/generate_204").openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                String result = code == 204
                        ? "HTTPS test PASSED: the active connection reached the test endpoint."
                        : "HTTPS test did not pass: HTTP " + code + ". This connection may require sign-in or block the test endpoint.";
                runOnUiThread(() -> status.setText(result));
            } catch (Exception e) {
                runOnUiThread(() -> status.setText("HTTPS test FAILED: " + e.getMessage()));
            } finally { if (connection != null) connection.disconnect(); }
        });
    }

    private void scanLocal() {
        if (!hasLocationPermission()) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 412);
            return;
        }
        if (wifi == null || !wifi.isWifiEnabled()) {
            addHeading("Nearby physical routers");
            addText("Turn on Wi-Fi, then scan again. Android may also require Location services to be enabled.");
            return;
        }
        boolean started;
        try { started = wifi.startScan(); } catch (SecurityException e) { started = false; }
        List<ScanResult> results;
        try { results = wifi.getScanResults(); } catch (SecurityException e) {
            addHeading("Nearby physical routers");
            addText("Android denied Wi-Fi scan access. Check Nearby Wi-Fi / Location permissions in Settings.");
            return;
        }
        if (results == null) results = Collections.emptyList();
        Collections.sort(results, (a,b) -> Integer.compare(b.level, a.level));
        addHeading("Nearby physical routers · measured signal");
        if (results.isEmpty()) addText(started ? "Scan started; no results yet. Tap scan again in a few seconds." :
                "No scan results yet. Android may rate-limit scans; wait and retry.");
        for (ScanResult r : results) {
            String ssid = r.SSID == null || r.SSID.isEmpty() ? "(Hidden network)" : r.SSID;
            String security = r.capabilities == null || r.capabilities.isEmpty() ? "Security unknown" : r.capabilities;
            Button row = button(ssid + "  ·  " + r.level + " dBm\n" + security);
            row.setOnClickListener(v -> {
                String caps = r.capabilities == null ? "" : r.capabilities.toUpperCase(java.util.Locale.ROOT);
                boolean open = !caps.contains("WEP") && !caps.contains("WPA") && !caps.contains("EAP")
                        && !caps.contains("SAE") && !caps.contains("PSK");
                if (open && r.SSID != null && !r.SSID.isEmpty()) {
                    pendingOpenSsid = r.SSID;
                    if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, 415);
                    } else { String chosenSsid = pendingOpenSsid; pendingOpenSsid = null; suggestOpenNetwork(chosenSsid); }
                } else {
                    Toast.makeText(this, "This network is secured or hidden. Android Wi-Fi settings will ask you to join it with the owner's permission.", Toast.LENGTH_LONG).show();
                    startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
                }
            });
            content.addView(row);
        }
        addText("Stronger local signal is generally closer to 0 dBm. Android controls scan frequency and whether a network can be joined.");
    }

    private void suggestOpenNetwork(String ssid) {
        if (wifi == null) { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); return; }
        try {
            WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsAppInteractionRequired(true)
                    .build();
            int result = wifi.addNetworkSuggestions(Collections.singletonList(suggestion));
            if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Toast.makeText(this, "Open-network suggestion sent to Android. Approve the system prompt if shown; connection and internet are not guaranteed.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Android did not accept the Wi-Fi suggestion (code " + result + "). Open Wi-Fi settings to connect.", Toast.LENGTH_LONG).show();
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            }
        } catch (Exception e) {
            Toast.makeText(this, "Android controls this connection: " + e.getMessage(), Toast.LENGTH_LONG).show();
            startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
        }
    }

    private void searchMappedPlaces() {
        Location loc = lastKnownLocation();
        if (loc == null) {
            if (!hasLocationPermission()) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, 412);
                return;
            }
            addHeading("Mapped Wi-Fi places");
            addText("Current location is unavailable. Turn on device Location and try again.");
            return;
        }
        String base = String.valueOf(BuildConfig.DREAM_WIFI_BASE_URL).trim();
        if (base.isEmpty()) {
            addHeading("Mapped Wi-Fi places");
            addText("Map service is not configured in this build.");
            return;
        }
        addHeading("Mapped Wi-Fi places · 500 km radius");
        addText("Searching public map data. Results are not live router scans.");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String path = base + "/api/wifi/hotspots?latitude=" + loc.getLatitude()
                        + "&longitude=" + loc.getLongitude() + "&radius_km=500";
                connection = (HttpURLConnection) new URL(path).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("Accept", "application/json");
                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                if (stream == null) throw new IllegalStateException("Empty map response (HTTP " + code + ")");
                StringBuilder body = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && body.length() < 1024 * 1024) body.append(line);
                }
                if (code < 200 || code >= 300) throw new IllegalStateException("Map service returned HTTP " + code);
                JSONObject data = new JSONObject(body.toString());
                JSONArray places = data.optJSONArray("hotspots");
                runOnUiThread(() -> {
                    addHeading("Mapped results: " + (places == null ? 0 : places.length()));
                    if (places == null || places.length() == 0) addText("No mapped Wi-Fi places were returned. Coverage may be incomplete.");
                    else for (int i = 0; i < places.length(); i++) {
                        JSONObject p = places.optJSONObject(i);
                        if (p == null) continue;
                        addText(p.optString("name", "Unnamed Wi-Fi place") + "\n"
                                + p.optString("distance_km", "?") + " km away · "
                                + p.optString("venue_type", "Wi-Fi venue") + "\n"
                                + "Map listing only · live router/internet not verified");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> addText("Map lookup failed: " + e.getMessage()));
            } finally { if (connection != null) connection.disconnect(); }
        });
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

    private void addHeading(String value) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(18); t.setTextColor(0xff14213d);
        t.setPadding(0, 18, 0, 6); content.addView(t);
    }

    private void addText(String value) {
        TextView t = new TextView(this);
        t.setText(value); t.setTextSize(14); t.setTextColor(0xff526079);
        t.setPadding(0, 4, 0, 8); content.addView(t);
    }
}
