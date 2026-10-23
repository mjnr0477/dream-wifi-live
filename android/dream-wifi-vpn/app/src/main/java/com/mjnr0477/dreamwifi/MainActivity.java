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
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
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
    private WifiManager wifiManager;
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
        DreamWifiCredentialStore.ReceiverCredentials savedReceiver = DreamWifiCredentialStore.loadReceiver(this);
        if (savedReceiver != null) {
            pendingAccessToken = savedReceiver.accessToken;
            pendingPairingCode = "";
            selectedSourceId = savedReceiver.sourceId;
            sourceButton.setText(selectedSourceLabel());
            status.setText("Saved encrypted receiver pairing available. Tap Pair and connect VPN to resume; if the server restarted, you may need a fresh code.");
        }
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
    }

    @Override protected void onPause() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        super.onPause();
    }

    @Override protected void onDestroy() {
        authWorker.shutdownNow();
        super.onDestroy();
    }
}
