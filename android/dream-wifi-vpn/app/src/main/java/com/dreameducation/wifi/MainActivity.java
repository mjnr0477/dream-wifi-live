package com.dreameducation.wifi;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;
    private static final int WIFI_PERMISSION_REQUEST = 1002;
    static final String ACTION_STATUS = "com.dreameducation.wifi.STATUS";
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private EditText emailInput;
    private EditText passwordInput;
    private EditText pairingCodeInput;
    private Button connectButton;
    private Button sourceButton;
    private TextView status;
    private String[] availableSourceIds = new String[0];
    private String[] availableSourceNames = new String[0];
    private String selectedSourceId = "";
    private String pendingAccessToken;
    private String pendingPairingCode;
    private String pendingSourceToken;
    private String pendingSourceId;
    private boolean statusReceiverRegistered = false;
    private boolean scanReceiverRegistered = false;
    private boolean scanInProgress = false;
    private WifiManager wifiManager;
    private TextView scanResultsText;
    private Button scanButton;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DreamVpnService.ACTION_SOURCES.equals(intent.getAction())) {
                String[] ids = intent.getStringArrayExtra("ids");
                String[] names = intent.getStringArrayExtra("names");
                availableSourceIds = ids == null ? new String[0] : ids;
                availableSourceNames = names == null ? new String[0] : names;
                if (sourceButton != null) sourceButton.setText(selectedSourceLabel());
            } else if (status != null) status.setText(intent.getStringExtra("message"));
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(28, 32, 28, 24);

        TextView title = new TextView(this);
        title.setText("Dream Wi-Fi");
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        box.addView(title);

        TextView note = new TextView(this);
        note.setText("Standalone Dream Wi-Fi: enter the one-time pairing code supplied by the source owner to connect this phone through that source. Source publishing uses a separate enrollment step. Any provider captive portal or package/payment login must still be completed normally.");
        note.setTextSize(15);
        note.setPadding(0, 18, 0, 18);
        box.addView(note);

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setHint("One-time source pairing code");
        pairingCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        box.addView(pairingCodeInput, new LinearLayout.LayoutParams(-1, -2));

        connectButton = new Button(this);
        connectButton.setText("Pair and connect VPN");
        connectButton.setOnClickListener(v -> connectWithPairingCode());
        box.addView(connectButton);

        Button publishSourceButton = new Button(this);
        publishSourceButton.setText("Publish this Wi-Fi as a remote source");
        publishSourceButton.setOnClickListener(v -> enrollAndStartSource());
        box.addView(publishSourceButton);

        Button revokePairingsButton = new Button(this);
        revokePairingsButton.setText("Revoke all receiver pairings");
        revokePairingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, DreamVpnService.class);
            intent.setAction(DreamVpnService.ACTION_REVOKE_PAIRINGS);
            startService(intent);
            status.setText("Requested receiver pairing revocation. Source sharing must be running for this to take effect.");
        });
        box.addView(revokePairingsButton);

        sourceButton = new Button(this);
        sourceButton.setText("Choose Wi-Fi source (automatic)");
        sourceButton.setOnClickListener(v -> showSourcePicker());
        box.addView(sourceButton);

        TextView optionalScannerNote = new TextView(this);
        optionalScannerNote.setText("Optional legacy county Wi-Fi observation feature below; not required for Dream Wi-Fi pairing or VPN.");
        optionalScannerNote.setTextSize(12);
        optionalScannerNote.setPadding(0, 18, 0, 4);
        box.addView(optionalScannerNote);

        emailInput = new EditText(this);
        emailInput.setSingleLine(true);
        emailInput.setHint("Optional county-scanner account email");
        emailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        box.addView(emailInput, new LinearLayout.LayoutParams(-1, -2));

        passwordInput = new EditText(this);
        passwordInput.setSingleLine(true);
        passwordInput.setHint("Optional county-scanner account password");
        passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(passwordInput, new LinearLayout.LayoutParams(-1, -2));

        Button scannerSignInButton = new Button(this);
        scannerSignInButton.setText("Sign in for county Wi-Fi scanning");
        scannerSignInButton.setOnClickListener(v -> signInForScanning());
        box.addView(scannerSignInButton);

        scanButton = new Button(this);
        scanButton.setText("Scan and report nearby Wi-Fi");
        scanButton.setOnClickListener(v -> requestScanPermissionsAndScan());
        box.addView(scanButton);

        scanResultsText = new TextView(this);
        scanResultsText.setText("County tracker: no field observations submitted yet.");
        scanResultsText.setTextSize(13);
        scanResultsText.setPadding(0, 10, 0, 10);
        box.addView(scanResultsText);

        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);

        Button portalButton = new Button(this);
        portalButton.setText("Open Quant Wi-Fi portal");
        portalButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("http://quant.com/status")));
            } catch (Exception error) {
                status.setText("Could not open the Quant portal. Connect to Quant Wi-Fi and visit quant.com/status.");
            }
        });
        box.addView(portalButton);

        Button disconnect = new Button(this);
        disconnect.setText("Disconnect Dream VPN");
        disconnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, DreamVpnService.class);
            intent.setAction(DreamVpnService.ACTION_STOP);
            startService(intent);
            status.setText("Disconnect requested.");
        });
        box.addView(disconnect);

        status = new TextView(this);
        status.setText("Ready. Complete Quant's package/login flow before testing internet.");
        status.setTextSize(14);
        status.setPadding(0, 16, 0, 0);
        box.addView(status);
        setContentView(box);
    }

    private void connectWithPairingCode() {
        if (DreamWifiRelay.BASE_URL == null || DreamWifiRelay.BASE_URL.isEmpty()) {
            status.setText("The Dream Wi-Fi service URL is not configured in this APK build.");
            return;
        }
        String code = pairingCodeInput.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (code.isEmpty()) {
            status.setText("Enter the one-time pairing code shown by the source owner's running connector.");
            return;
        }
        pendingPairingCode = code;
        connectButton.setEnabled(false);
        status.setText("Requesting Android VPN permission and pairing with the selected source…");
        Intent permission = VpnService.prepare(this);
        if (permission != null) startActivityForResult(permission, VPN_PERMISSION_REQUEST);
        else {
            connectButton.setEnabled(true);
            startVpnService();
        }
    }

    private void enrollAndStartSource() {
        if (DreamWifiRelay.BASE_URL == null || DreamWifiRelay.BASE_URL.isEmpty()) {
            status.setText("The Dream Wi-Fi service URL is not configured in this APK build.");
            return;
        }
        if (wifiManager == null) wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            status.setText("Connect this phone to the Wi-Fi network you want to share, then retry.");
            return;
        }
        connectButton.setEnabled(false);
        status.setText("Creating a unique standalone source credential…");
        authWorker.execute(() -> {
            try {
                JSONObject enrollment = postJson(DreamWifiRelay.BASE_URL + "/api/dream-wifi/source-enroll", new JSONObject(), null);
                String sourceId = enrollment.optString("source_id", "");
                String sourceToken = enrollment.optString("source_token", "");
                if (!enrollment.optBoolean("success") || sourceId.isEmpty() || sourceToken.isEmpty()) {
                    throw new Exception(enrollment.optString("error", "Source enrollment failed."));
                }
                runOnUiThread(() -> {
                    pendingSourceId = sourceId;
                    pendingSourceToken = sourceToken;
                    connectButton.setEnabled(true);
                    startSourceService();
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    connectButton.setEnabled(true);
                    status.setText("Standalone source enrollment failed: " + safe(error.getMessage()));
                });
            }
        });
    }

    private void signInForScanning() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (email.isEmpty() || password.isEmpty()) {
            status.setText("Enter your Dream Education email and password.");
            return;
        }
        status.setText("Signing in for secure county Wi-Fi observations…");
        authWorker.execute(() -> {
            try {
                JSONObject credentials = new JSONObject();
                credentials.put("email", email);
                credentials.put("password", password);
                JSONObject login = postJson(DreamWifiRelay.BASE_URL + "/api/auth/login", credentials, null);
                JSONObject session = login.optJSONObject("session");
                String accessToken = session == null ? "" : session.optString("access_token", "");
                String refreshToken = session == null ? "" : session.optString("refresh_token", "");
                if (!login.optBoolean("success") || accessToken.isEmpty() || refreshToken.isEmpty()) {
                    throw new Exception(login.optString("error", "Login did not return a session."));
                }
                runOnUiThread(() -> {
                    pendingAccessToken = accessToken;
                    passwordInput.setText("");
                    status.setText("Signed in for county scanning. You can now scan and report nearby Wi-Fi.");
                });
            } catch (Exception error) {
                runOnUiThread(() -> status.setText("County scanner sign-in failed: " + safe(error.getMessage())));
            }
        });
    }

    private String selectedSourceLabel() {
        if (selectedSourceId.isEmpty()) return "Choose Wi-Fi source (automatic)";
        for (int i = 0; i < availableSourceIds.length; i++) {
            if (selectedSourceId.equals(availableSourceIds[i])) {
                return "Wi-Fi source: " + availableSourceNames[i];
            }
        }
        return "Wi-Fi source selected";
    }

    private void showSourcePicker() {
        String[] choices = new String[availableSourceNames.length + 1];
        choices[0] = "Automatically select an available source";
        System.arraycopy(availableSourceNames, 0, choices, 1, availableSourceNames.length);
        new AlertDialog.Builder(this)
                .setTitle("Available Dream Wi-Fi sources")
                .setItems(choices, (dialog, which) -> {
                    selectedSourceId = which == 0 ? "" : availableSourceIds[which - 1];
                    if (sourceButton != null) sourceButton.setText(selectedSourceLabel());
                    Intent intent = new Intent(this, DreamVpnService.class);
                    intent.setAction(DreamVpnService.ACTION_SELECT_SOURCE);
                    intent.putExtra(DreamVpnService.EXTRA_SELECTED_SOURCE_ID, selectedSourceId);
                    startService(intent);
                    if (status != null) status.setText(selectedSourceId.isEmpty()
                            ? "Automatic source selection enabled."
                            : "Selected source: " + availableSourceNames[which - 1]);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void requestScanPermissionsAndScan() {
        List<String> missing = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), WIFI_PERMISSION_REQUEST);
            return;
        }
        startWifiScan();
    }

    private void startWifiScan() {
        if (pendingAccessToken == null || pendingAccessToken.isEmpty()) {
            status.setText("Sign in to Dream Education first so scan observations can be submitted securely.");
            return;
        }
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            status.setText("Turn on Wi-Fi before scanning nearby networks.");
            return;
        }
        LocationManager locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        boolean locationEnabled = false;
        try {
            locationEnabled = locationManager != null &&
                    (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                     locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        } catch (Exception ignored) {}
        if (!locationEnabled) {
            status.setText("Turn on Android Location so Wi-Fi scan results can be associated with an approximate position.");
            return;
        }
        scanButton.setEnabled(false);
        scanInProgress = true;
        status.setText("Scanning nearby Wi-Fi networks. Android may limit how often scans run.");
        boolean started = false;
        try { started = wifiManager.startScan(); } catch (SecurityException ignored) {}
        if (!started) {
            // Android throttles scans on some devices. Use the most recent OS-provided scan results.
            status.setText("A fresh scan was throttled; checking the latest Wi-Fi scan results.");
            authWorker.execute(this::processWifiScanResults);
        }
    }

    private void processWifiScanResults() {
        try {
            if (wifiManager == null) throw new Exception("Wi-Fi scanner is unavailable.");
            List<ScanResult> results = wifiManager.getScanResults();
            if (results == null || results.isEmpty()) throw new Exception("No scan results. Move to a place with Wi-Fi, enable Location, and try again.");
            Location location = getApproximateLocation();
            if (location == null) throw new Exception("Android has no recent location yet. Keep Location enabled, open Maps once, then retry.");
            String connectedSsid = getConnectedWifiSsid();
            Network activeNetwork = null;
            ConnectivityManager connectivity = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (connectivity != null) activeNetwork = connectivity.getActiveNetwork();
            NetworkCapabilities capabilities = connectivity == null || activeNetwork == null
                    ? null : connectivity.getNetworkCapabilities(activeNetwork);
            boolean activeWifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            boolean connectedGateway = activeWifi && hasDefaultRoute(connectivity, activeNetwork);

            List<ScanResult> ordered = new ArrayList<>(results);
            ordered.sort(Comparator.comparingInt((ScanResult item) -> item.level).reversed());
            Set<String> seen = new HashSet<>();
            List<String> lines = new ArrayList<>();
            int submitted = 0;
            int skipped = 0;
            for (ScanResult result : ordered) {
                String ssid = result == null ? "" : String.valueOf(result.SSID == null ? "" : result.SSID).trim();
                if (ssid.isEmpty() || !seen.add(ssid)) continue;
                if (submitted + skipped >= 12) break;
                boolean currentNetwork = activeWifi && ssid.equals(connectedSsid);
                boolean gatewayDetected = currentNetwork && connectedGateway;
                boolean internetVerified = false;
                String accessState = "unknown";
                if (gatewayDetected) {
                    internetVerified = probeInternetOnNetwork(activeNetwork);
                    accessState = internetVerified ? "authorized" :
                            (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ? "captive_portal" : "unknown");
                }

                JSONObject observation = new JSONObject();
                observation.put("ssid", ssid);
                observation.put("latitude", location.getLatitude());
                observation.put("longitude", location.getLongitude());
                observation.put("gateway_detected", gatewayDetected);
                observation.put("internet_verified", internetVerified);
                observation.put("access_state", accessState);
                observation.put("observed_at", java.time.Instant.now().toString());
                try {
                    postJson(DreamWifiRelay.BASE_URL + "/api/dream-wifi/registry/observations",
                            observation, pendingAccessToken);
                    submitted++;
                    lines.add((internetVerified ? "INTERNET VERIFIED · " : gatewayDetected ? "GATEWAY · " : "DETECTED · ")
                            + ssid + " (" + result.level + " dBm)");
                } catch (Exception error) {
                    skipped++;
                    lines.add("NOT SAVED · " + ssid + " — " + safe(error.getMessage()));
                }
            }
            final int savedCount = submitted;
            final String report = "Nearby Wi-Fi observations\nSubmitted: " + savedCount + " · Not saved: " + skipped
                    + "\nOnly the Wi-Fi currently connected to this phone can be checked for internet. Other networks are listed as detected, not verified.\n\n"
                    + String.join("\n", lines);
            runOnUiThread(() -> {
                scanResultsText.setText(report);
                status.setText(savedCount > 0
                        ? "Reported " + savedCount + " nearby Wi-Fi observation(s) to the cloud registry."
                        : "No observations were saved. Check sign-in, permissions, and database setup.");
                scanButton.setEnabled(true);
                scanInProgress = false;
            });
        } catch (Exception error) {
            runOnUiThread(() -> {
                status.setText("Wi-Fi scan failed: " + safe(error.getMessage()));
                scanButton.setEnabled(true);
                scanInProgress = false;
            });
        }
    }

    private Location getApproximateLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null
                        && System.currentTimeMillis() - candidate.getTime() <= 30L * 60L * 1000L
                        && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private String getConnectedWifiSsid() {
        if (wifiManager == null) return "";
        try {
            WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? "" : info.getSSID();
            if (ssid == null || ssid.equals("<unknown ssid>")) return "";
            return ssid.replaceAll("^\\\"|\\\"$", "").trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    private boolean hasDefaultRoute(ConnectivityManager connectivity, Network network) {
        try {
            LinkProperties properties = connectivity.getLinkProperties(network);
            if (properties == null) return false;
            for (RouteInfo route : properties.getRoutes()) {
                if (route != null && route.isDefaultRoute()) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean probeInternetOnNetwork(Network network) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://connectivitycheck.gstatic.com/generate_204");
            connection = (HttpURLConnection) network.openConnection(url);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            return connection.getResponseCode() == 204;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private JSONObject postJson(String endpoint, JSONObject body, String bearer) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        if (bearer != null && !bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        if (stream != null) {
            try (InputStream input = stream) {
                byte[] chunk = new byte[4096];
                int count;
                while ((count = input.read(chunk)) != -1) buffer.write(chunk, 0, count);
            }
        }
        connection.disconnect();
        JSONObject result = new JSONObject(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
        if (code < 200 || code >= 300) throw new Exception(result.optString("error", "Server rejected the request (HTTP " + code + ")."));
        return result;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WIFI_PERMISSION_REQUEST) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (Build.VERSION.SDK_INT >= 33) {
                granted = granted && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) startWifiScan();
            else status.setText("Wi-Fi scanning needs Location and nearby-device permission. You can still use Dream Tunnel without scanning.");
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_PERMISSION_REQUEST) {
            if (resultCode == RESULT_OK) startVpnService();
            else {
                connectButton.setEnabled(true);
                status.setText("VPN permission was not granted.");
            }
        }
    }

    private void startSourceService() {
        if (pendingSourceToken == null || pendingSourceId == null) {
            status.setText("Publish this source again to obtain a standalone source credential.");
            return;
        }
        Intent intent = new Intent(this, DreamVpnService.class);
        intent.setAction(DreamVpnService.ACTION_START);
        intent.putExtra(DreamVpnService.EXTRA_SOURCE_TOKEN, pendingSourceToken);
        intent.putExtra(DreamVpnService.EXTRA_SOURCE_ID, pendingSourceId);
        intent.putExtra(DreamVpnService.EXTRA_SOURCE_ONLY, true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        status.setText("Publishing this phone's connected Wi-Fi as a remote Dream Wi-Fi source. Keep this phone powered and connected.");
    }

    private void startVpnService() {
        if (pendingPairingCode == null || pendingPairingCode.isEmpty()) {
            connectButton.setEnabled(true);
            status.setText("Enter the one-time pairing code from the source owner.");
            return;
        }
        Intent intent = new Intent(this, DreamVpnService.class);
        intent.setAction(DreamVpnService.ACTION_START);
        intent.putExtra(DreamVpnService.EXTRA_PAIRING_CODE, pendingPairingCode);
        intent.putExtra(DreamVpnService.EXTRA_SELECTED_SOURCE_ID, selectedSourceId);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        connectButton.setEnabled(true);
        status.setText("Connecting to Dream Tunnel through the paired source. Actual browsing must pass the relay test.");
    }

    private String safe(String message) {
        if (message == null || message.trim().isEmpty()) return "unknown error";
        String clean = message.replace('\n', ' ').replace('\r', ' ');
        return clean.substring(0, Math.min(180, clean.length()));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!statusReceiverRegistered) {
            IntentFilter filter = new IntentFilter(ACTION_STATUS);
            filter.addAction(DreamVpnService.ACTION_SOURCES);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(statusReceiver, filter);
            statusReceiverRegistered = true;
        }
        if (!scanReceiverRegistered) {
            IntentFilter scanFilter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(scanReceiver, scanFilter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(scanReceiver, scanFilter);
            scanReceiverRegistered = true;
        }
    }

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction()) && scanInProgress) {
                authWorker.execute(MainActivity.this::processWifiScanResults);
            }
        }
    };

    @Override protected void onPause() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        if (scanReceiverRegistered) {
            unregisterReceiver(scanReceiver);
            scanReceiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        authWorker.shutdownNow();
        super.onDestroy();
    }
}
