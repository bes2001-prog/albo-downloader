package com.albo.downloader;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    // ── Change this to your PC's IP ──
    private static final String DEFAULT_SERVER = "http://192.168.1.200:5005";

    private EditText urlInput, serverInput;
    private Button pasteBtn, downloadBtn, settingsBtn;
    private Spinner formatSpinner;
    private CheckBox subtitlesCheck;
    private ProgressBar progressBar;
    private TextView statusText, titleText;
    private LinearLayout settingsPanel;
    private View infoCard;

    private String serverUrl;
    private String currentJobId;
    private boolean isDownloading = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("albo_dl", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", DEFAULT_SERVER);

        bindViews();
        setupSpinner();
        setupListeners();
        checkConnection();

        // Handle share intent (URL shared from another app)
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("text/")) {
            String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (sharedText != null && !sharedText.isEmpty()) {
                urlInput.setText(sharedText.trim());
                fetchInfo(sharedText.trim());
            }
        }
    }

    private void bindViews() {
        urlInput       = findViewById(R.id.urlInput);
        serverInput    = findViewById(R.id.serverInput);
        pasteBtn       = findViewById(R.id.pasteBtn);
        downloadBtn    = findViewById(R.id.downloadBtn);
        settingsBtn    = findViewById(R.id.settingsBtn);
        formatSpinner  = findViewById(R.id.formatSpinner);
        subtitlesCheck = findViewById(R.id.subtitlesCheck);
        progressBar    = findViewById(R.id.progressBar);
        statusText     = findViewById(R.id.statusText);
        titleText      = findViewById(R.id.titleText);
        settingsPanel  = findViewById(R.id.settingsPanel);
        infoCard       = findViewById(R.id.infoCard);

        serverInput.setText(serverUrl);
        progressBar.setVisibility(View.GONE);
        infoCard.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
    }

    private void setupSpinner() {
        String[] formats = {"Best quality (MP4)", "720p", "480p", "MP3 audio", "Playlist (up to 150)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, formats);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        formatSpinner.setAdapter(adapter);
    }

    private void setupListeners() {
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                urlInput.setText(text);
                fetchInfo(text);
            }
        });

        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) fetchInfo(url);
            hideKeyboard();
            return true;
        });

        downloadBtn.setOnClickListener(v -> {
            if (isDownloading) return;
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                toast("Paste a URL first");
                return;
            }
            startDownload(url);
        });

        settingsBtn.setOnClickListener(v -> {
            boolean visible = settingsPanel.getVisibility() == View.VISIBLE;
            settingsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        // Save server URL when changed
        serverInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) {
                String url = serverInput.getText().toString().trim();
                if (!url.isEmpty()) {
                    serverUrl = url;
                    prefs.edit().putString("server_url", url).apply();
                    checkConnection();
                }
            }
        });
    }

    private void checkConnection() {
        executor.execute(() -> {
            try {
                HttpURLConnection conn = openConnection(serverUrl + "/ping", "GET", null);
                int code = conn.getResponseCode();
                conn.disconnect();
                handler.post(() -> {
                    if (code == 200) {
                        statusText.setText("✓ Connected to PC");
                        statusText.setTextColor(0xFF4CAF50);
                        downloadBtn.setEnabled(true);
                    } else {
                        statusText.setText("✗ PC not reachable");
                        statusText.setTextColor(0xFFE53935);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    statusText.setText("✗ PC not reachable — check WiFi");
                    statusText.setTextColor(0xFFE53935);
                    downloadBtn.setEnabled(false);
                });
            }
        });
    }

    private void fetchInfo(String url) {
        if (!isValidUrl(url)) return;
        statusText.setText("Fetching info...");
        statusText.setTextColor(0xFF888888);
        infoCard.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                HttpURLConnection conn = openConnection(serverUrl + "/info", "POST", body.toString());
                String resp = readResponse(conn);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);

                if (json.has("error")) {
                    handler.post(() -> statusText.setText("Could not fetch info"));
                    return;
                }

                String title = json.optString("title", "");
                String uploader = json.optString("uploader", "");
                int duration = json.optInt("duration", 0);
                String dur = duration > 0 ? formatDuration(duration) : "";

                handler.post(() -> {
                    titleText.setText(title);
                    statusText.setText(uploader + (dur.isEmpty() ? "" : "  •  " + dur));
                    statusText.setTextColor(0xFF888888);
                    infoCard.setVisibility(View.VISIBLE);
                    downloadBtn.setEnabled(true);
                });

            } catch (Exception e) {
                handler.post(() -> {
                    statusText.setText("Ready");
                    statusText.setTextColor(0xFF888888);
                });
            }
        });
    }

    private void startDownload(String url) {
        isDownloading = true;
        downloadBtn.setEnabled(false);
        downloadBtn.setText("Downloading...");
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);

        String fmt = getSelectedFormat();
        boolean subs = subtitlesCheck.isChecked();

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                body.put("format", fmt);
                body.put("subtitles", subs);

                HttpURLConnection conn = openConnection(serverUrl + "/download", "POST", body.toString());
                String resp = readResponse(conn);
                conn.disconnect();

                JSONObject json = new JSONObject(resp);
                if (json.has("error")) {
                    handler.post(() -> showError(json.getString("error")));
                    return;
                }

                currentJobId = json.getString("job_id");
                pollProgress(currentJobId);

            } catch (Exception e) {
                handler.post(() -> showError(e.getMessage()));
            }
        });
    }

    private void pollProgress(String jobId) {
        handler.postDelayed(() -> {
            executor.execute(() -> {
                try {
                    HttpURLConnection conn = openConnection(serverUrl + "/status/" + jobId, "GET", null);
                    String resp = readResponse(conn);
                    conn.disconnect();
                    JSONObject json = new JSONObject(resp);

                    String status = json.optString("status", "");
                    int progress = json.optInt("progress", 0);

                    handler.post(() -> {
                        progressBar.setProgress(progress);
                        downloadBtn.setText(progress + "%");
                    });

                    if ("done".equals(status)) {
                        String mainFile = json.getString("main_file");
                        handler.post(() -> triggerFileDownload(jobId, mainFile));
                    } else if ("error".equals(status)) {
                        String err = json.optString("error", "Download failed");
                        handler.post(() -> showError(err));
                    } else {
                        // Still downloading - poll again
                        pollProgress(jobId);
                    }

                } catch (Exception e) {
                    handler.post(() -> showError(e.getMessage()));
                }
            });
        }, 1000);
    }

    private void triggerFileDownload(String jobId, String filename) {
        String fileUrl = serverUrl + "/file/" + jobId + "/" + Uri.encode(filename);

        // Use Android DownloadManager to save to Downloads folder
        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fileUrl));
        req.setTitle(filename);
        req.setDescription("Albo Downloader");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
        req.allowScanningByMediaScanner();

        if (dm != null) {
            dm.enqueue(req);
        }

        // Also offer share sheet
        Intent shareIntent = new Intent(Intent.ACTION_VIEW);
        shareIntent.setData(Uri.parse(fileUrl));

        resetUI("✓ Saved to Downloads!");
        toast("Saved to Downloads — check notification");
    }

    private void resetUI(String message) {
        isDownloading = false;
        downloadBtn.setEnabled(true);
        downloadBtn.setText("Download");
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        statusText.setTextColor(0xFF4CAF50);
    }

    private void showError(String msg) {
        isDownloading = false;
        downloadBtn.setEnabled(true);
        downloadBtn.setText("Download");
        progressBar.setVisibility(View.GONE);
        statusText.setText("Error: " + msg);
        statusText.setTextColor(0xFFE53935);
    }

    private String getSelectedFormat() {
        switch (formatSpinner.getSelectedItemPosition()) {
            case 0: return "best";
            case 1: return "720p";
            case 2: return "480p";
            case 3: return "mp3";
            case 4: return "playlist";
            default: return "best";
        }
    }

    private String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m >= 60) {
            return String.format("%dh %dm", m / 60, m % 60);
        }
        return String.format("%d:%02d", m, s);
    }

    private boolean isValidUrl(String url) {
        return url.startsWith("http://") || url.startsWith("https://");
    }

    private HttpURLConnection openConnection(String urlStr, String method, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
        }
        return conn;
    }

    private String readResponse(HttpURLConnection conn) throws Exception {
        InputStream is;
        try {
            is = conn.getInputStream();
        } catch (IOException e) {
            is = conn.getErrorStream();
        }
        if (is == null) return "{}";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View focus = getCurrentFocus();
        if (imm != null && focus != null) imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
