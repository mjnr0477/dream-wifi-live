package com.mjnr0477.dreamwifi;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
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
        String configuredBaseUrl = String.valueOf(BuildConfig.DREAM_WIFI_BASE_URL).trim();
        Uri configuredUri = Uri.parse(configuredBaseUrl);
        if ("https".equalsIgnoreCase(configuredUri.getScheme())
                && configuredUri.getHost() != null
                && !configuredUri.getHost().trim().isEmpty()) {
            dashboardHost = configuredUri.getHost();
            dashboardUrl = configuredBaseUrl.endsWith("/") ? configuredBaseUrl : configuredBaseUrl + "/";
        }

        setContentView(root);
        if (dashboardUrl == null) {
            progressBar.setVisibility(View.GONE);
            webView.setVisibility(View.GONE);
            TextView setupNotice = new TextView(this);
            setupNotice.setText("Development workspace\n\nThis build is intentionally not connected to the published Dream Wi-Fi service. Configure DREAM_WIFI_BASE_URL to a separate development backend, then rebuild to enable source discovery and pairing here.");
            setupNotice.setTextColor(Color.parseColor("#D9E7FA"));
            setupNotice.setTextSize(16);
            setupNotice.setLineSpacing(8, 1.0f);
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
