package com.mjnr0477.dreamwifi;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiNetworkSuggestion;
import android.location.LocationManager;
import android.provider.Settings;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

public final class DashboardActivity extends Activity {
    private String dashboardUrl;
    private String dashboardHost;
    private WebView webView;
    private ProgressBar progressBar;
    private static final int LOCATION_PERMISSION_REQUEST = 7402;
    private GeolocationPermissions.Callback pendingLocationCallback;
    private String pendingLocationOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String configuredBaseUrl = String.valueOf(BuildConfig.DREAM_WIFI_BASE_URL).trim();
        while (configuredBaseUrl.endsWith("/")) {
            configuredBaseUrl = configuredBaseUrl.substring(0, configuredBaseUrl.length() - 1);
        }
        Uri configuredUri = Uri.parse(configuredBaseUrl);
        if ("https".equalsIgnoreCase(configuredUri.getScheme())
                && configuredUri.getHost() != null
                && !configuredUri.getHost().trim().isEmpty()) {
            dashboardHost = configuredUri.getHost();
            dashboardUrl = configuredBaseUrl + "/discovery.html";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(12, 8, 12, 8);

        Button backButton = new Button(this);
        backButton.setText("Back to connection");
        backButton.setOnClickListener(v -> finish());
        header.addView(backButton, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(this);
        title.setText("Dream Wi-Fi");
        title.setTextSize(18);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(header);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setIndeterminate(true);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 4));

        webView = new WebView(this);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setGeolocationEnabled(true);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                Uri requestedOrigin = Uri.parse(origin);
                if (!"https".equalsIgnoreCase(requestedOrigin.getScheme())
                        || (dashboardHost == null || !dashboardHost.equalsIgnoreCase(requestedOrigin.getHost()))) {
                    callback.invoke(origin, false, false);
                    return;
                }
                boolean fine = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                boolean coarse = checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                if (fine || coarse) callback.invoke(origin, true, false);
                else {
                    pendingLocationOrigin = origin;
                    pendingLocationCallback = callback;
                    requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION },
                            LOCATION_PERMISSION_REQUEST);
                }
            }
        });
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.addJavascriptInterface(new PairingCodeBridge(), "DreamWifiNative");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return routeUrl(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return routeUrl(Uri.parse(url));
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                progressBar.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progressBar.setVisibility(View.GONE);
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        if (dashboardUrl == null) {
            progressBar.setVisibility(View.GONE);
            webView.setVisibility(View.GONE);
            TextView setupNotice = new TextView(this);
            setupNotice.setText("Development workspace\\n\\nThis build is intentionally not connected to the published Dream Wi-Fi service. Configure DREAM_WIFI_BASE_URL with a separate development backend, then rebuild to enable discovery and pairing.");
            setupNotice.setTextSize(16);
            setupNotice.setPadding(24, 32, 24, 24);
            root.addView(setupNotice, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            if (savedInstanceState != null) {
                webView.restoreState(savedInstanceState);
            }
            if (webView.getUrl() == null) {
                webView.loadUrl(dashboardUrl);
            }
        }
    }

    /** Exposes only the short-lived pairing code, never the source credential/token. */
    private final class PairingCodeBridge {
        @JavascriptInterface
        public String requestLocalWifiScan() {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[] { Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION }, LOCATION_PERMISSION_REQUEST));
                return "{\"status\":\"permission_required\",\"networks\":[]}";
            }
            try {
                LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
                if (android.os.Build.VERSION.SDK_INT >= 28 && lm != null && !lm.isLocationEnabled()) return "{\"status\":\"location_services_off\",\"networks\":[]}";
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi == null || !wifi.isWifiEnabled()) return "{\"status\":\"wifi_off\",\"networks\":[],\"message\":\"Turn on Wi-Fi and retry.\"}";
                boolean started = wifi.startScan();
                java.util.List<ScanResult> scans = wifi.getScanResults();
                org.json.JSONArray networks = new org.json.JSONArray();
                java.util.Set<String> seen = new java.util.HashSet<>();
                for (ScanResult scan : scans) {
                    if (scan == null) continue;
                    String ssid = scan.SSID == null ? "" : scan.SSID;
                    if (!seen.add(ssid + "|" + scan.BSSID)) continue;
                    org.json.JSONObject item = new org.json.JSONObject();
                    item.put("ssid", ssid); item.put("level_dbm", scan.level);
                    String caps = scan.capabilities == null ? "" : scan.capabilities;
                    item.put("capabilities", caps);
                    boolean secured = caps.contains("PSK") || caps.contains("SAE") || caps.contains("EAP") || caps.contains("WEP") || caps.contains("OWE");
                    item.put("security", secured ? "Secured" : (caps.contains("ESS") ? "Open" : "Unknown"));
                    networks.put(item);
                }
                org.json.JSONObject result = new org.json.JSONObject();
                result.put("status", "ok"); result.put("networks", networks); result.put("scan_started", started);
                result.put("note", started ? "Android may return cached results. Scan again in a few seconds for refreshed results." : "Android may have throttled the scan; these are the available results.");
                return result.toString();
            } catch (SecurityException error) { return "{\"status\":\"permission_required\",\"networks\":[]}";
            } catch (Exception error) { return "{\"status\":\"error\",\"networks\":[],\"message\":\"Wi-Fi scan failed. Check Wi-Fi and Location settings.\"}"; }
        }

        @JavascriptInterface
        public String suggestOpenWifi(String ssid) {
            String networkName = ssid == null ? "" : ssid.trim();
            if (networkName.isEmpty() || networkName.length() > 32) return "{\"status\":\"invalid_ssid\"}";
            if (android.os.Build.VERSION.SDK_INT < 29) return "{\"status\":\"settings_required\"}";
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                runOnUiThread(() -> requestPermissions(new String[] { Manifest.permission.NEARBY_WIFI_DEVICES }, LOCATION_PERMISSION_REQUEST + 1));
                return "{\"status\":\"permission_required\"}";
            }
            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                if (wifi == null) return "{\"status\":\"unavailable\"}";
                WifiNetworkSuggestion suggestion = new WifiNetworkSuggestion.Builder().setSsid(networkName).build();
                int result = wifi.addNetworkSuggestions(java.util.Collections.singletonList(suggestion));
                if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) return "{\"status\":\"suggested\"}";
                if (result == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) return "{\"status\":\"already_suggested\"}";
                return "{\"status\":\"settings_required\"}";
            } catch (SecurityException error) { return "{\"status\":\"permission_required\"}";
            } catch (Exception error) { return "{\"status\":\"settings_required\"}"; }
        }
        @JavascriptInterface
        public void openWifiSettings() {
            runOnUiThread(() -> { try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception error) { Toast.makeText(DashboardActivity.this, "Open Android Wi-Fi settings.", Toast.LENGTH_SHORT).show(); } });
        }
        @JavascriptInterface
        public String getSourcePairingCode() {
            android.content.SharedPreferences prefs = getSharedPreferences(
                    DreamVpnService.PAIRING_CODE_PREFS, MODE_PRIVATE);
            String code = prefs.getString(DreamVpnService.KEY_PAIRING_CODE, "");
            long expiresAt = prefs.getLong(DreamVpnService.KEY_PAIRING_CODE_EXPIRES_AT, 0L);
            org.json.JSONObject result = new org.json.JSONObject();
            try {
                if (code == null || !code.matches("[A-F0-9]{12}") || expiresAt <= System.currentTimeMillis()) {
                    result.put("available", false);
                } else {
                    result.put("available", true);
                    result.put("code", code);
                    result.put("expiresAt", expiresAt);
                }
                return result.toString();
            } catch (org.json.JSONException error) {
                return "{}";
            }
        }
    }

    private boolean routeUrl(Uri uri) {
        if (dashboardHost != null && "https".equalsIgnoreCase(uri.getScheme())
                && dashboardHost.equalsIgnoreCase(uri.getHost())) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            Toast.makeText(this, "Unable to open that link.", Toast.LENGTH_SHORT).show();
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == LOCATION_PERMISSION_REQUEST && pendingLocationCallback != null) {
            boolean granted = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
            pendingLocationCallback.invoke(pendingLocationOrigin, granted, false);
            pendingLocationCallback = null;
            pendingLocationOrigin = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) {
            webView.saveState(outState);
        }
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
