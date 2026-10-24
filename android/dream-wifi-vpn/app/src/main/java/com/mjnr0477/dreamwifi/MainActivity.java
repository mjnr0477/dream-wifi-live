package com.mjnr0477.dreamwifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;
    private static final int SOURCE_LOCATION_REQUEST = 1002;
    private static final int LOCAL_WIFI_SCAN_REQUEST = 1003;
    static final String ACTION_STATUS = "com.mjnr0477.dreamwifi.STATUS";
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private EditText pairingCodeInput;
    private Button connectButton;
    private Button sourceButton;
    private TextView status;
    private TextView networkInfo;
    private TextView nearbyWifiResults;
    private String[] availableSourceIds = new String[0];
    private String[] availableSourceNames = new String[0];
    private String selectedSourceId = "";
    private String pendingPairingCode;
    private String pendingSourceToken;
    private String pendingAccessToken;
    private String pendingSourceId;
    private Double pendingSourceLatitude;
    private Double pendingSourceLongitude;
    private boolean statusReceiverRegistered = false;
    private boolean networkReceiverRegistered = false;
    private WifiManager wifiManager;
    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (DreamVpnService.ACTION_SOURCES.equals(intent.getAction())) {
                String[] ids = intent.getStringArrayExtra("ids");
                String[] names = intent.getStringArrayExtra("names");
                availableSourceIds = ids == null ? new String[0] : ids;
                availableSourceNames = names == null ? new String[0] : names;
                if (selectedSourceId.isEmpty() && availableSourceIds.length == 1) {
                    selectedSourceId = availableSourceIds[0];
                }
                if (sourceButton != null) sourceButton.setText(selectedSourceLabel());
                refreshNetworkInfo();
            } else if (status != null) status.setText(intent.getStringExtra("message"));
        }
    };
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
                renderNearbyWifiScanResults();
            } else {
                refreshNetworkInfo();
            }
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        final int navy = Color.parseColor("#07111F");
        final int panel = Color.parseColor("#10243A");
        final int mint = Color.parseColor("#79F2C0");
        final int blue = Color.parseColor("#86B8FF");
        final int muted = Color.parseColor("#B7C7DC");
        getWindow().setStatusBarColor(navy);
        getWindow().setNavigationBarColor(navy);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(navy);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(22), dp(24), dp(22), dp(28));

        TextView brand = new TextView(this);
        brand.setText("DREAM  WI-FI");
        brand.setTextColor(mint);
        brand.setTextSize(13);
        brand.setLetterSpacing(0.14f);
        brand.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        page.addView(brand, fullWidth());

        TextView title = new TextView(this);
        title.setText("Internet, without\nthe distance.");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setLineSpacing(dp(1), 1.0f);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = fullWidth();
        titleParams.topMargin = dp(18);
        page.addView(title, titleParams);

        TextView intro = new TextView(this);
        intro.setText("Discover Wi-Fi locations and participating internet sources. Dream Wi-Fi keeps the connection steps simple.");
        intro.setTextColor(muted);
        intro.setTextSize(15);
        intro.setLineSpacing(dp(4), 1.0f);
        LinearLayout.LayoutParams introParams = fullWidth();
        introParams.topMargin = dp(10);
        introParams.bottomMargin = dp(20);
        page.addView(intro, introParams);

        TextView radiusChip = new TextView(this);
        radiusChip.setText("⌖  SEARCH UP TO 500 KM  ·  FREE & PAID LISTINGS");
        radiusChip.setTextColor(mint);
        radiusChip.setTextSize(11);
        radiusChip.setPadding(dp(12), dp(10), dp(12), dp(10));
        radiusChip.setBackground(roundRect(Color.parseColor("#183B3A"), dp(14), Color.parseColor("#285A56")));
        page.addView(radiusChip, fullWidth());

        LinearLayout receiveCard = makeCard(panel);
        addCardHeading(receiveCard, "Receive internet", "Enter a one-time code from a participating source to pair this phone.");
        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setHint("One-time source pairing code");
        pairingCodeInput.setHintTextColor(Color.parseColor("#8397B0"));
        pairingCodeInput.setTextColor(Color.WHITE);
        pairingCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        pairingCodeInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        pairingCodeInput.setBackground(roundRect(Color.parseColor("#09182A"), dp(13), Color.parseColor("#35516E")));
        receiveCard.addView(pairingCodeInput, fullWidth());
        connectButton = makeButton("Pair & connect", mint, navy);
        connectButton.setOnClickListener(v -> connectWithPairingCode());
        addWithTopMargin(receiveCard, connectButton, 12);
        page.addView(receiveCard, cardParams());

        LinearLayout sourceCard = makeCard(panel);
        addCardHeading(sourceCard, "Share internet", "Offer a network you are authorized to share as a participating source.");
        Button publishSourceButton = makeButton("Start sharing this network", blue, navy);
        publishSourceButton.setOnClickListener(v -> enrollAndStartSource());
        sourceCard.addView(publishSourceButton, fullWidth());
        sourceButton = makeButton("Choose Wi-Fi source (automatic)", Color.parseColor("#1A3551"), Color.WHITE);
        sourceButton.setOnClickListener(v -> showSourcePicker());
        addWithTopMargin(sourceCard, sourceButton, 10);
        page.addView(sourceCard, cardParams());

        LinearLayout discoverCard = makeCard(panel);
        addCardHeading(discoverCard, "Discover & manage", "Open the discovery workspace to search the selected radius and review source availability.");
        Button dashboardButton = makeButton("Explore Wi-Fi sources", mint, navy);
        dashboardButton.setOnClickListener(v -> startActivity(new Intent(this, DashboardActivity.class)));
        discoverCard.addView(dashboardButton, fullWidth());
        page.addView(discoverCard, cardParams());

        LinearLayout localScanCard = makeCard(panel);
        addCardHeading(localScanCard, "Nearby Wi-Fi scan", "Scan radio networks your phone can physically detect, then compare their names and signal levels.");
        Button scanButton = makeButton("Scan nearby networks", blue, navy);
        scanButton.setOnClickListener(v -> scanNearbyWifi());
        localScanCard.addView(scanButton, fullWidth());
        nearbyWifiResults = new TextView(this);
        nearbyWifiResults.setText("Tap scan to list nearby Wi-Fi signals.");
        nearbyWifiResults.setTextColor(muted);
        nearbyWifiResults.setTextSize(13);
        nearbyWifiResults.setLineSpacing(dp(3), 1.0f);
        addWithTopMargin(localScanCard, nearbyWifiResults, 12);
        page.addView(localScanCard, cardParams());

        LinearLayout statusCard = makeCard(panel);
        addCardHeading(statusCard, "Connection status", "Live updates from the app appear here.");
        networkInfo = new TextView(this);
        networkInfo.setTextColor(muted);
        networkInfo.setTextSize(13);
        networkInfo.setLineSpacing(dp(3), 1.0f);
        statusCard.addView(networkInfo, fullWidth());
        status = new TextView(this);
        status.setText("Ready. Pair with an available source or start sharing an authorized network.");
        status.setTextColor(Color.WHITE);
        status.setTextSize(14);
        status.setLineSpacing(dp(3), 1.0f);
        addWithTopMargin(statusCard, status, 12);
        page.addView(statusCard, cardParams());

        LinearLayout actionsCard = makeCard(panel);
        addCardHeading(actionsCard, "Connection controls", "Stop the tunnel or revoke receiver pairings when needed.");
        Button disconnect = makeButton("Disconnect Dream Tunnel", Color.parseColor("#1A3551"), Color.WHITE);
        disconnect.setOnClickListener(v -> {
            Intent intent = new Intent(this, DreamVpnService.class);
            intent.setAction(DreamVpnService.ACTION_STOP);
            startService(intent);
            status.setText("Disconnect requested.");
        });
        actionsCard.addView(disconnect, fullWidth());
        Button revokePairingsButton = makeButton("Revoke receiver pairings", Color.parseColor("#3B2835"), Color.parseColor("#FFD8E4"));
        revokePairingsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, DreamVpnService.class);
            intent.setAction(DreamVpnService.ACTION_REVOKE_PAIRINGS);
            startService(intent);
            status.setText("Requested receiver pairing revocation. Source sharing must be running for this to take effect.");
        });
        addWithTopMargin(actionsCard, revokePairingsButton, 10);
        page.addView(actionsCard, cardParams());

        TextView footer = new TextView(this);
        footer.setText("Dream Wi-Fi development build · Internet access is verified only after real traffic passes through the selected source.");
        footer.setTextColor(Color.parseColor("#8397B0"));
        footer.setTextSize(12);
        footer.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams footerParams = fullWidth();
        footerParams.topMargin = dp(22);
        page.addView(footer, footerParams);

        scroll.addView(page);
        setContentView(scroll);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        refreshNetworkInfo();
        DreamWifiCredentialStore.ReceiverCredentials savedReceiver = DreamWifiCredentialStore.loadReceiver(this);
        if (savedReceiver != null) {
            pendingAccessToken = savedReceiver.accessToken;
            pendingPairingCode = "";
            selectedSourceId = savedReceiver.sourceId;
            sourceButton.setText(selectedSourceLabel());
            status.setText("Saved encrypted receiver pairing available. Tap Pair & connect to resume; if the service restarted, a fresh code may be needed.");
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(16);
        return params;
    }

    private void addWithTopMargin(LinearLayout parent, android.view.View child, int top) {
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private GradientDrawable roundRect(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private LinearLayout makeCard(int color) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(roundRect(color, dp(20), Color.parseColor("#263E5A")));
        return card;
    }

    private void addCardHeading(LinearLayout card, String heading, String description) {
        TextView title = new TextView(this);
        title.setText(heading);
        title.setTextColor(Color.WHITE);
        title.setTextSize(18);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        card.addView(title, fullWidth());
        TextView detail = new TextView(this);
        detail.setText(description);
        detail.setTextColor(Color.parseColor("#B7C7DC"));
        detail.setTextSize(13);
        detail.setLineSpacing(dp(3), 1.0f);
        LinearLayout.LayoutParams params = fullWidth();
        params.topMargin = dp(6);
        params.bottomMargin = dp(14);
        card.addView(detail, params);
    }

    private Button makeButton(String label, int backgroundColor, int textColor) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        button.setTextColor(textColor);
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setBackground(roundRect(backgroundColor, dp(13), backgroundColor));
        button.setStateListAnimator(null);
        return button;
    }

    private void connectWithPairingCode() {
        if (DreamWifiRelay.BASE_URL == null || DreamWifiRelay.BASE_URL.isEmpty()) {
            status.setText("The Dream Wi-Fi service URL is not configured in this APK build.");
            return;
        }
        String code = pairingCodeInput.getText().toString().trim().toUpperCase(java.util.Locale.ROOT);
        if (!code.isEmpty()) {
            pendingPairingCode = code;
            pendingAccessToken = "";
            DreamWifiCredentialStore.clearReceiver(this);
        } else {
            DreamWifiCredentialStore.ReceiverCredentials savedReceiver = DreamWifiCredentialStore.loadReceiver(this);
            if (savedReceiver == null) {
                status.setText("Enter the one-time pairing code shown by the source owner's running connector.");
                return;
            }
            pendingAccessToken = savedReceiver.accessToken;
            pendingPairingCode = "";
            selectedSourceId = savedReceiver.sourceId;
        }
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
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, SOURCE_LOCATION_REQUEST);
            status.setText("Location permission lets Dream Wi-Fi show this source in the radius tracker. You can still share without location.");
            return;
        }
        Location location = getApproximateLocation();
        pendingSourceLatitude = location == null ? null : location.getLatitude();
        pendingSourceLongitude = location == null ? null : location.getLongitude();
        performSourceEnrollment();
    }

    private void performSourceEnrollment() {
        DreamWifiCredentialStore.SourceCredentials saved = DreamWifiCredentialStore.loadSource(this);
        if (saved != null) {
            pendingSourceId = saved.sourceId;
            pendingSourceToken = saved.sourceToken;
            connectButton.setEnabled(true);
            status.setText("Reusing this phone's encrypted source credential.");
            startSourceService();
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
                DreamWifiCredentialStore.saveSource(MainActivity.this, sourceId, sourceToken);
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

    private Location getApproximateLocation() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager manager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (manager == null) return null;
        Location best = null;
        String[] providers = {LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        for (String provider : providers) {
            try {
                Location candidate = manager.getLastKnownLocation(provider);
                if (candidate != null && System.currentTimeMillis() - candidate.getTime() <= 30L * 60L * 1000L
                        && (best == null || candidate.getTime() > best.getTime())) best = candidate;
            } catch (Exception ignored) {}
        }
        return best;
    }

    private void scanNearbyWifi() {
        if (wifiManager == null) wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wifiManager == null || !wifiManager.isWifiEnabled()) {
            status.setText("Turn on Wi-Fi to scan nearby wireless networks.");
            return;
        }
        boolean hasFineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasNearbyPermission = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
        if (!hasFineLocation || !hasNearbyPermission) {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.NEARBY_WIFI_DEVICES}, LOCAL_WIFI_SCAN_REQUEST);
            } else {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, LOCAL_WIFI_SCAN_REQUEST);
            }
            status.setText("Allow nearby Wi-Fi permission so Android can return scan results.");
            return;
        }
        boolean started = false;
        try {
            started = wifiManager.startScan();
        } catch (SecurityException error) {
            status.setText("Android denied the Wi-Fi scan. Check nearby Wi-Fi permissions and retry.");
            return;
        }
        renderNearbyWifiScanResults();
        status.setText(started
                ? "Nearby Wi-Fi scan requested. Results refresh when Android completes the scan."
                : "Android limited a new scan; showing the latest results available on this phone.");
    }

    private void renderNearbyWifiScanResults() {
        if (nearbyWifiResults == null || wifiManager == null) return;
        try {
            List<ScanResult> results = new ArrayList<>(wifiManager.getScanResults());
            Collections.sort(results, (left, right) -> Integer.compare(right.level, left.level));
            if (results.isEmpty()) {
                nearbyWifiResults.setText("No scan results yet. Keep Wi-Fi enabled, allow permission, and try again.");
                return;
            }
            StringBuilder text = new StringBuilder();
            int shown = Math.min(results.size(), 15);
            for (int i = 0; i < shown; i++) {
                ScanResult result = results.get(i);
                String ssid = result.SSID == null || result.SSID.trim().isEmpty() ? "Hidden network" : result.SSID.trim();
                String security = result.capabilities != null && result.capabilities.contains("WEP") ? "WEP"
                        : result.capabilities != null && result.capabilities.contains("PSK") ? "Secured"
                        : result.capabilities != null && result.capabilities.contains("EAP") ? "Enterprise"
                        : result.capabilities != null && result.capabilities.contains("SAE") ? "Secured"
                        : "Open or unknown";
                if (i > 0) text.append("\n");
                text.append("• ").append(ssid).append(" · ").append(result.level).append(" dBm · ")
                        .append(result.frequency).append(" MHz · ").append(security);
            }
            if (results.size() > shown) text.append("\n+ ").append(results.size() - shown).append(" more networks");
            nearbyWifiResults.setText(text.toString());
        } catch (SecurityException error) {
            nearbyWifiResults.setText("Android did not allow scan results. Check location and nearby Wi-Fi permissions.");
        }
    }

    private void refreshNetworkInfo() {
        if (networkInfo == null) return;
        String remote = selectedSourceLabel();
        if ("Choose Wi-Fi source (automatic)".equals(remote)) {
            remote = "Automatic selection; no specific remote source selected";
        } else if ("Wi-Fi source selected".equals(remote)) {
            remote = "Selected source name is currently unavailable";
        }
        networkInfo.setText(
                "Network details\n"
                + "Remote source network: " + remote + "\n"
                + "This phone's current Wi-Fi: " + getReceiverWifiName() + "\n"
                + "Receiver hotspot name: " + getReceiverHotspotName() + "\n"
                + "Hotspot forwarding: Not implemented/verified. A receiver VPN does not automatically route a third device's traffic."
        );
    }

    private String getReceiverWifiName() {
        try {
            if (wifiManager == null) wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wifiManager == null) return "Unavailable on this device";
            android.net.wifi.WifiInfo info = wifiManager.getConnectionInfo();
            String ssid = info == null ? null : info.getSSID();
            if (ssid == null || ssid.trim().isEmpty() || "<unknown ssid>".equalsIgnoreCase(ssid.trim())) {
                return "Unavailable (Android permission or device restriction)";
            }
            ssid = ssid.trim();
            if (ssid.length() >= 2 && ssid.startsWith("\"") && ssid.endsWith("\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            return ssid;
        } catch (Exception unavailable) {
            return "Unavailable (Android permission or device restriction)";
        }
    }

    private String getReceiverHotspotName() {
        // Public Android SDKs do not expose the active hotspot SSID consistently to ordinary apps.
        // Do not use hidden APIs or infer a hotspot name from the local Wi-Fi connection.
        return "Not exposed to ordinary apps through the supported Android API";
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
                    refreshNetworkInfo();
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
        if (requestCode == SOURCE_LOCATION_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Location location = getApproximateLocation();
                pendingSourceLatitude = location == null ? null : location.getLatitude();
                pendingSourceLongitude = location == null ? null : location.getLongitude();
            } else {
                pendingSourceLatitude = null;
                pendingSourceLongitude = null;
            }
            performSourceEnrollment();
        } else if (requestCode == LOCAL_WIFI_SCAN_REQUEST) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            if (Build.VERSION.SDK_INT >= 33) {
                granted = granted && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED;
            }
            if (granted) scanNearbyWifi();
            else status.setText("Wi-Fi scan permission was not granted. Allow nearby Wi-Fi/location permission and retry.");
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
        if (pendingSourceLatitude != null && pendingSourceLongitude != null) {
            intent.putExtra(DreamVpnService.EXTRA_SOURCE_LATITUDE, pendingSourceLatitude);
            intent.putExtra(DreamVpnService.EXTRA_SOURCE_LONGITUDE, pendingSourceLongitude);
        }
        intent.putExtra(DreamVpnService.EXTRA_SOURCE_ONLY, true);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent);
        else startService(intent);
        status.setText("Publishing this phone's connected Wi-Fi as a remote Dream Wi-Fi source. Keep this phone powered and connected.");
    }

    private void startVpnService() {
        boolean hasPairingCode = pendingPairingCode != null && !pendingPairingCode.isEmpty();
        boolean hasSavedAccessToken = pendingAccessToken != null && !pendingAccessToken.isEmpty();
        if (!hasPairingCode && !hasSavedAccessToken) {
            connectButton.setEnabled(true);
            status.setText("Enter a fresh one-time source pairing code or resume a saved receiver pairing.");
            return;
        }
        Intent intent = new Intent(this, DreamVpnService.class);
        intent.setAction(DreamVpnService.ACTION_START);
        intent.putExtra(DreamVpnService.EXTRA_PAIRING_CODE, hasPairingCode ? pendingPairingCode : "");
        intent.putExtra(DreamVpnService.EXTRA_ACCESS_TOKEN, hasSavedAccessToken ? pendingAccessToken : "");
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
        if (!networkReceiverRegistered) {
            IntentFilter networkFilter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            networkFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
            networkFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
            networkFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(networkReceiver, networkFilter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(networkReceiver, networkFilter);
            networkReceiverRegistered = true;
        }
        refreshNetworkInfo();
    }

    @Override protected void onPause() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        if (networkReceiverRegistered) {
            unregisterReceiver(networkReceiver);
            networkReceiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        authWorker.shutdownNow();
        super.onDestroy();
    }
}
