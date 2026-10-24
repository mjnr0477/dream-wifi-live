package com.mjnr0477.dreamwifi;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.view.View;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.ScrollView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int VPN_PERMISSION_REQUEST = 1001;
    private static final int SOURCE_LOCATION_REQUEST = 1002;
    static final String ACTION_STATUS = "com.mjnr0477.dreamwifi.STATUS";
    private final ExecutorService authWorker = Executors.newSingleThreadExecutor();
    private EditText pairingCodeInput;
    private Button connectButton;
    private Button sourceButton;
    private TextView status;
    private TextView networkInfo;
    private ScrollView homeScrollView;
    private LinearLayout pairingCard;
    private TextView pairingCodeDisplay;
    private TextView pairingExpiryDisplay;
    private Button copyPairingCodeButton;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable pairingCodeRefresh = new Runnable() {
        @Override public void run() {
            refreshPairingCodeCard();
            if (pairingCard != null && pairingCard.getVisibility() == View.VISIBLE) uiHandler.postDelayed(this, 1000L);
        }
    };
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
            refreshNetworkInfo();
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(24, 30, 24, 24);
        box.setBackgroundColor(android.graphics.Color.rgb(244, 247, 253));

        TextView title = new TextView(this);
        title.setText("Dream Wi-Fi");
        title.setTextSize(32);
        title.setPadding(0, 8, 0, 4);
        title.setTextColor(android.graphics.Color.rgb(20, 48, 100));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setGravity(Gravity.CENTER);
        box.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView intro = new TextView(this);
        intro.setText("What would you like to do?");
        intro.setTextSize(17);
        intro.setGravity(Gravity.CENTER);
        intro.setTextColor(android.graphics.Color.rgb(80, 94, 118));
        intro.setPadding(0, 10, 0, 24);
        box.addView(intro, new LinearLayout.LayoutParams(-1, -2));

        TextView connectionArt = new TextView(this);
        connectionArt.setText("👩🏽‍💻   ···   🌍   ···   👨🏿‍💻");
        connectionArt.setTextSize(25);
        connectionArt.setGravity(Gravity.CENTER);
        connectionArt.setPadding(8, 18, 8, 18);
        connectionArt.setBackground(rounded(android.graphics.Color.rgb(226, 237, 255), 22));
        box.addView(connectionArt, fullWidthButton());

        TextView artCaption = new TextView(this);
        artCaption.setText("Connect people across town or across the world.");
        artCaption.setTextSize(13);
        artCaption.setGravity(Gravity.CENTER);
        artCaption.setTextColor(android.graphics.Color.rgb(91, 105, 128));
        artCaption.setPadding(0, 2, 0, 10);
        box.addView(artCaption, new LinearLayout.LayoutParams(-1, -2));

        Button findButton = new Button(this);
        findButton.setText("Find Wi-Fi nearby");
        styleActionButton(findButton, android.graphics.Color.WHITE, android.graphics.Color.rgb(21, 75, 170));
        findButton.setOnClickListener(v ->
                startActivity(new Intent(this, DashboardActivity.class)));
        box.addView(findButton, fullWidthButton());

        Button receiveButton = new Button(this);
        receiveButton.setText("Receive internet");
        styleActionButton(receiveButton, android.graphics.Color.WHITE, android.graphics.Color.rgb(21, 75, 170));
        receiveButton.setOnClickListener(v -> showReceiveDialog());
        box.addView(receiveButton, fullWidthButton());

        Button shareButton = new Button(this);
        shareButton.setText("Share internet");
        shareButton.setOnClickListener(v -> {
            showPairingCodeCard();
            enrollAndStartSource();
        });
        styleActionButton(shareButton, android.graphics.Color.rgb(21, 94, 239), android.graphics.Color.WHITE);
        box.addView(shareButton, fullWidthButton());

        pairingCard = new LinearLayout(this);
        pairingCard.setOrientation(LinearLayout.VERTICAL);
        pairingCard.setPadding(20, 18, 20, 18);
        pairingCard.setBackground(rounded(android.graphics.Color.WHITE, 20));
        pairingCard.setElevation(5f);
        pairingCard.setVisibility(View.GONE);
        TextView pairingHeading = new TextView(this);
        pairingHeading.setText("Your sharing code");
        pairingHeading.setTextSize(19);
        pairingHeading.setTypeface(null, android.graphics.Typeface.BOLD);
        pairingHeading.setTextColor(android.graphics.Color.rgb(20, 48, 100));
        pairingCard.addView(pairingHeading);
        TextView pairingHint = new TextView(this);
        pairingHint.setText("Give this one-time code only to the person you want to connect, even over a long distance.");
        pairingHint.setTextSize(13);
        pairingHint.setTextColor(android.graphics.Color.rgb(91, 105, 128));
        pairingHint.setPadding(0, 6, 0, 12);
        pairingCard.addView(pairingHint);
        pairingCodeDisplay = new TextView(this);
        pairingCodeDisplay.setText("Waiting for source…");
        pairingCodeDisplay.setTextSize(27);
        pairingCodeDisplay.setGravity(Gravity.CENTER);
        pairingCodeDisplay.setTypeface(null, android.graphics.Typeface.BOLD);
        pairingCodeDisplay.setTextColor(android.graphics.Color.rgb(21, 94, 239));
        pairingCodeDisplay.setPadding(8, 18, 8, 18);
        pairingCodeDisplay.setBackground(rounded(android.graphics.Color.rgb(237, 243, 255), 14));
        pairingCard.addView(pairingCodeDisplay);
        pairingExpiryDisplay = new TextView(this);
        pairingExpiryDisplay.setText("The code appears when the source relay is online.");
        pairingExpiryDisplay.setTextSize(12);
        pairingExpiryDisplay.setTextColor(android.graphics.Color.rgb(91, 105, 128));
        pairingExpiryDisplay.setPadding(0, 10, 0, 8);
        pairingCard.addView(pairingExpiryDisplay);
        copyPairingCodeButton = new Button(this);
        copyPairingCodeButton.setText("Copy sharing code");
        copyPairingCodeButton.setEnabled(false);
        copyPairingCodeButton.setOnClickListener(v -> {
            String code = pairingCodeDisplay != null && pairingCodeDisplay.getTag() instanceof String
                    ? (String) pairingCodeDisplay.getTag() : "";
            if (code.isEmpty()) { status.setText("The sharing code is not ready yet."); return; }
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("Dream Wi-Fi sharing code", code));
                status.setText("Sharing code copied. Send it only to your chosen receiver.");
            }
        });
        pairingCard.addView(copyPairingCodeButton, fullWidthButton());
        box.addView(pairingCard, fullWidthButton());

        TextView explanation = new TextView(this);
        explanation.setText("Choose one action to get started. You can find Wi-Fi locations, connect using a source owner's one-time code, or share a Wi-Fi connection you are authorized to use.");
        explanation.setTextSize(14);
        explanation.setTextColor(android.graphics.Color.rgb(85, 98, 120));
        explanation.setGravity(Gravity.CENTER);
        explanation.setPadding(4, 16, 4, 18);
        box.addView(explanation, new LinearLayout.LayoutParams(-1, -2));

        Button moreButton = new Button(this);
        moreButton.setText("Connection settings");
        moreButton.setOnClickListener(v -> showConnectionSettings());
        box.addView(moreButton, fullWidthButton());

        pairingCodeInput = new EditText(this);
        pairingCodeInput.setSingleLine(true);
        pairingCodeInput.setHint("One-time source pairing code");
        pairingCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);

        connectButton = new Button(this);
        connectButton.setText("Connect");
        sourceButton = new Button(this);
        sourceButton.setText("Choose Wi-Fi source (automatic)");

        wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        networkInfo = new TextView(this);
        status = new TextView(this);
        status.setText("Ready. Choose an action above.");
        status.setTextSize(13);
        status.setTextColor(android.graphics.Color.rgb(64, 78, 100));
        status.setPadding(0, 12, 0, 0);
        box.addView(status, new LinearLayout.LayoutParams(-1, -2));

        homeScrollView = new ScrollView(this);
        homeScrollView.setFillViewport(true);
        homeScrollView.addView(box);
        setContentView(homeScrollView);
        refreshNetworkInfo();

        DreamWifiCredentialStore.ReceiverCredentials savedReceiver = DreamWifiCredentialStore.loadReceiver(this);
        if (savedReceiver != null) {
            pendingAccessToken = savedReceiver.accessToken;
            pendingPairingCode = "";
            selectedSourceId = savedReceiver.sourceId;
            status.setText("A saved receiver connection is available. Tap Receive internet to resume.");
        }
    }

    private void styleActionButton(Button button, int backgroundColor, int textColor) {
        button.setAllCaps(false);
        button.setTextSize(16);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setTextColor(textColor);
        button.setBackground(rounded(backgroundColor, 18));
        button.setElevation(2f);
    }

    private android.graphics.drawable.GradientDrawable rounded(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable d = new android.graphics.drawable.GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return d;
    }

    private void refreshPairingCodeCard() {
        if (pairingCodeDisplay == null || pairingExpiryDisplay == null) return;
        android.content.SharedPreferences prefs = getSharedPreferences(DreamVpnService.PAIRING_CODE_PREFS, MODE_PRIVATE);
        String code = prefs.getString(DreamVpnService.KEY_PAIRING_CODE, "");
        long expiresAt = prefs.getLong(DreamVpnService.KEY_PAIRING_CODE_EXPIRES_AT, 0L);
        if (code == null || !code.matches("[A-F0-9]{12}") || expiresAt <= System.currentTimeMillis()) {
            pairingCodeDisplay.setTag("");
            pairingCodeDisplay.setText("Waiting for source…");
            pairingExpiryDisplay.setText(expiresAt > 0L
                    ? "This code expired. Restart sharing to request a fresh source session."
                    : "Keep this phone connected. The code appears when the secure relay comes online.");
            if (copyPairingCodeButton != null) copyPairingCodeButton.setEnabled(false);
            return;
        }
        pairingCodeDisplay.setTag(code);
        pairingCodeDisplay.setText(code.replaceAll("(.{4})(?=.)", "$1 "));
        long seconds = Math.max(0L, (expiresAt - System.currentTimeMillis() + 999L) / 1000L);
        pairingExpiryDisplay.setText("One-time code · expires in " + (seconds / 60L) + "m " + (seconds % 60L) + "s.");
        if (copyPairingCodeButton != null) copyPairingCodeButton.setEnabled(true);
    }

    private void showPairingCodeCard() {
        if (pairingCard == null) return;
        pairingCard.setVisibility(View.VISIBLE);
        refreshPairingCodeCard();
        uiHandler.removeCallbacks(pairingCodeRefresh);
        uiHandler.post(pairingCodeRefresh);
        pairingCard.post(() -> {
            if (homeScrollView != null) homeScrollView.smoothScrollTo(0, pairingCard.getTop());
        });
    }

    private LinearLayout.LayoutParams fullWidthButton() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 6, 0, 6);
        return params;
    }

    private void showReceiveDialog() {
        EditText codeInput = new EditText(this);
        codeInput.setSingleLine(true);
        codeInput.setHint("Enter the source owner's code");
        codeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        DreamWifiCredentialStore.ReceiverCredentials savedReceiver = DreamWifiCredentialStore.loadReceiver(this);
        if (savedReceiver != null) codeInput.setHint("Leave blank to resume saved connection");
        LinearLayout content = new LinearLayout(this);
        content.setPadding(22, 8, 22, 0);
        content.addView(codeInput, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(this)
                .setTitle("Receive internet")
                .setMessage("Enter the one-time code shared by the source owner.")
                .setView(content)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Connect", (dialog, which) -> {
                    pairingCodeInput.setText(codeInput.getText().toString().trim());
                    connectWithPairingCode();
                })
                .show();
    }

    private void showConnectionSettings() {
        String[] choices = {
                "Choose available source",
                "Open Wi-Fi dashboard",
                "Open Quant Wi-Fi portal",
                "Disconnect Dream Wi-Fi"
        };
        new AlertDialog.Builder(this)
                .setTitle("Connection settings")
                .setItems(choices, (dialog, which) -> {
                    if (which == 0) {
                        showSourcePicker();
                    } else if (which == 1) {
                        startActivity(new Intent(this, DashboardActivity.class));
                    } else if (which == 2) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    android.net.Uri.parse("http://quant.com/status")));
                        } catch (Exception error) {
                            status.setText("Could not open the Quant portal.");
                        }
                    } else {
                        Intent intent = new Intent(this, DreamVpnService.class);
                        intent.setAction(DreamVpnService.ACTION_STOP);
                        startService(intent);
                        status.setText("Disconnect requested.");
                    }
                })
                .show();
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
        if (!hasValidatedInternetConnection()) {
            status.setText("Connect to Wi-Fi or turn on mobile data with internet, then tap Share internet again.");
            return;
        }
        // Location is optional for sharing. Never block source setup on a location permission prompt.
        Location location = getApproximateLocation();
        pendingSourceLatitude = location == null ? null : location.getLatitude();
        pendingSourceLongitude = location == null ? null : location.getLongitude();
        performSourceEnrollment();
    }

    private boolean hasValidatedInternetConnection() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) return false;
        Network active = manager.getActiveNetwork();
        if (active != null && hasValidatedInternet(manager, active)) return true;
        for (Network network : manager.getAllNetworks()) {
            if (hasValidatedInternet(manager, network)) return true;
        }
        return false;
    }

    private boolean hasValidatedInternet(ConnectivityManager manager, Network network) {
        NetworkCapabilities caps = manager.getNetworkCapabilities(network);
        return caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
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
        status.setText("Starting secure internet sharing over Wi-Fi or mobile data. Keep this phone online.");
        showPairingCodeCard();
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
            networkFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(networkReceiver, networkFilter, Context.RECEIVER_NOT_EXPORTED);
            else registerReceiver(networkReceiver, networkFilter);
            networkReceiverRegistered = true;
        }
        refreshNetworkInfo();
    }

    @Override protected void onDestroy() {
        uiHandler.removeCallbacks(pairingCodeRefresh);
        authWorker.shutdownNow();
        super.onDestroy();
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

}
