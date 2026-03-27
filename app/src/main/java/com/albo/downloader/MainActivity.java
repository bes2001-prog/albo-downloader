package com.albo.downloader;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
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

    private static final String DEFAULT_SERVER = "http://192.168.1.200:5005";

    private EditText   urlInput, serverInput;
    private Button     pasteBtn, clearBtn, btnVideo, btnAudio,
                       btnSubtitles, btnTextOnly, btnPhotos, btnPlaylist, btnAgain;
    private ProgressBar progressBar;
    private TextView   statusText, titleText, uploaderText, connectionText;
    private LinearLayout menuPanel, settingsPanel, infoCard, againPanel;
    private View       divider;

    private String  serverUrl;
    private String  currentUrl;
    private String  currentJobId;
    private boolean isDownloading = false;
    private boolean showSubtitles = false;
    private boolean showTextOnly  = false;
    private boolean showPhotos    = false;
    private boolean showPlaylist  = false;

    private final Handler         handler  = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs     = getSharedPreferences("albo_dl", MODE_PRIVATE);
        serverUrl = prefs.getString("server_url", DEFAULT_SERVER);
        bindViews();
        setupListeners();
        checkConnection();

        // Auto-paste URL from clipboard if it looks like a link
        autoCheckClipboard();

        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-check clipboard every time app comes to foreground
        if (urlInput.getText().toString().isEmpty()) {
            autoCheckClipboard();
        }
    }

    private void autoCheckClipboard() {
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                if (isValidUrl(text) && !text.equals(currentUrl)) {
                    urlInput.setText(text);
                    fetchInfo(text);
                }
            }
        } catch (Exception ignored) {}
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text != null && !text.isEmpty()) {
                urlInput.setText(text.trim());
                fetchInfo(text.trim());
            }
        }
    }

    private void bindViews() {
        urlInput       = findViewById(R.id.urlInput);
        serverInput    = findViewById(R.id.serverInput);
        pasteBtn       = findViewById(R.id.pasteBtn);
        clearBtn       = findViewById(R.id.clearBtn);
        btnVideo       = findViewById(R.id.btnVideo);
        btnAudio       = findViewById(R.id.btnAudio);
        btnSubtitles   = findViewById(R.id.btnSubtitles);
        btnTextOnly    = findViewById(R.id.btnTextOnly);
        btnPhotos      = findViewById(R.id.btnPhotos);
        btnPlaylist    = findViewById(R.id.btnPlaylist);
        btnAgain       = findViewById(R.id.btnAgain);
        progressBar    = findViewById(R.id.progressBar);
        statusText     = findViewById(R.id.statusText);
        titleText      = findViewById(R.id.titleText);
        uploaderText   = findViewById(R.id.uploaderText);
        connectionText = findViewById(R.id.connectionText);
        menuPanel      = findViewById(R.id.menuPanel);
        settingsPanel  = findViewById(R.id.settingsPanel);
        infoCard       = findViewById(R.id.infoCard);
        againPanel     = findViewById(R.id.againPanel);
        divider        = findViewById(R.id.divider);

        serverInput.setText(serverUrl);
        menuPanel.setVisibility(View.GONE);
        infoCard.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
        againPanel.setVisibility(View.GONE);
        clearBtn.setVisibility(View.GONE);
    }

    private void setupListeners() {
        // Show/hide X clear button based on whether field has text
        urlInput.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
            }
            public void afterTextChanged(Editable s) {}
        });

        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                urlInput.setText(text);
                if (isValidUrl(text)) fetchInfo(text);
                hideKeyboard();
            }
        });

        clearBtn.setOnClickListener(v -> {
            urlInput.setText("");
            currentUrl = null;
            menuPanel.setVisibility(View.GONE);
            infoCard.setVisibility(View.GONE);
            divider.setVisibility(View.GONE);
            againPanel.setVisibility(View.GONE);
            statusText.setText("Paste a URL to get started");
            statusText.setTextColor(0xFF888888);
        });

        urlInput.setOnEditorActionListener((v, id, e) -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) fetchInfo(url);
            hideKeyboard();
            return true;
        });

        btnVideo.setOnClickListener(v     -> startDownload("video"));
        btnAudio.setOnClickListener(v     -> startDownload("mp3"));
        btnSubtitles.setOnClickListener(v -> startDownload("subtitles"));
        btnTextOnly.setOnClickListener(v  -> startDownload("textonly"));
        btnPhotos.setOnClickListener(v    -> startDownload("photos"));
        btnPlaylist.setOnClickListener(v  -> startDownload("playlist"));

        btnAgain.setOnClickListener(v -> {
            againPanel.setVisibility(View.GONE);
            showMenu();
        });

        connectionText.setOnClickListener(v -> {
            boolean vis = settingsPanel.getVisibility() == View.VISIBLE;
            settingsPanel.setVisibility(vis ? View.GONE : View.VISIBLE);
        });

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
        connectionText.setText("Connecting to PC...");
        connectionText.setTextColor(0xFF888888);
        executor.execute(() -> {
            try {
                HttpURLConnection conn = openConn(serverUrl + "/ping", "GET", null);
                int code = conn.getResponseCode();
                conn.disconnect();
                handler.post(() -> {
                    if (code == 200) {
                        connectionText.setText("✓ Connected to PC  ⚙");
                        connectionText.setTextColor(0xFF4CAF50);
                    } else {
                        connectionText.setText("✗ PC not reachable  ⚙");
                        connectionText.setTextColor(0xFFE53935);
                    }
                });
            } catch (Exception e) {
                handler.post(() -> {
                    connectionText.setText("✗ PC not reachable — check WiFi  ⚙");
                    connectionText.setTextColor(0xFFE53935);
                });
            }
        });
    }

    private void fetchInfo(String url) {
        if (!isValidUrl(url)) return;
        currentUrl = url;

        infoCard.setVisibility(View.GONE);
        menuPanel.setVisibility(View.GONE);
        againPanel.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
        statusText.setText("Fetching info...");
        statusText.setTextColor(0xFF888888);

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                HttpURLConnection conn = openConn(serverUrl + "/info", "POST", body.toString());
                String resp = readResp(conn);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);

                String title    = json.optString("title", "");
                String uploader = json.optString("uploader", "");
                String platform = json.optString("platform", "Video");
                int    duration = json.optInt("duration", 0);
                String dur      = duration > 0 ? "  •  " + fmtDur(duration) : "";

                // Server controls which buttons to show
                showSubtitles = json.optBoolean("show_subtitles", false);
                showTextOnly  = json.optBoolean("show_textonly", false);
                showPhotos    = json.optBoolean("show_photos", false);
                showPlaylist  = json.optBoolean("show_playlist", false);

                handler.post(() -> {
                    if (!title.isEmpty()) {
                        titleText.setText(title);
                        uploaderText.setText(platform
                            + (uploader.isEmpty() ? "" : "  •  " + uploader) + dur);
                        infoCard.setVisibility(View.VISIBLE);
                        divider.setVisibility(View.VISIBLE);
                    }
                    statusText.setText("What do you want from this " + platform + " link?");
                    statusText.setTextColor(0xFFCCCCCC);
                    showMenu();
                });

            } catch (Exception e) {
                handler.post(() -> {
                    statusText.setText("Choose format:");
                    statusText.setTextColor(0xFF888888);
                    // Default: show video and audio only
                    showSubtitles = false;
                    showTextOnly  = false;
                    showPhotos    = false;
                    showPlaylist  = false;
                    showMenu();
                });
            }
        });
    }

    private void showMenu() {
        btnSubtitles.setVisibility(showSubtitles ? View.VISIBLE : View.GONE);
        btnTextOnly.setVisibility(showTextOnly   ? View.VISIBLE : View.GONE);
        btnPhotos.setVisibility(showPhotos       ? View.VISIBLE : View.GONE);
        btnPlaylist.setVisibility(showPlaylist   ? View.VISIBLE : View.GONE);
        setButtonsEnabled(true);
        menuPanel.setVisibility(View.VISIBLE);
    }

    private void startDownload(String fmt) {
        if (currentUrl == null || currentUrl.isEmpty()) { toast("Paste a URL first"); return; }
        if (isDownloading) return;

        isDownloading = true;
        setButtonsEnabled(false);
        againPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("Starting download...");
        statusText.setTextColor(0xFF888888);

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", currentUrl);
                body.put("format", fmt);
                HttpURLConnection conn = openConn(serverUrl + "/download", "POST", body.toString());
                String resp = readResp(conn);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);
                if (json.has("error")) {
                    handler.post(() -> showError(json.optString("error", "Failed")));
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
        handler.postDelayed(() -> executor.execute(() -> {
            try {
                HttpURLConnection conn = openConn(serverUrl + "/status/" + jobId, "GET", null);
                String resp = readResp(conn);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);
                String status   = json.optString("status", "");
                int    progress = json.optInt("progress", 0);
                String platform = json.optString("platform", "");
                int    count    = json.optInt("count", 0);

                handler.post(() -> {
                    progressBar.setProgress(progress);
                    if (progress > 0)
                        statusText.setText("Downloading " + platform + "... " + progress + "%");
                });

                if ("done".equals(status)) {
                    String mainFile = json.optString("main_file", "");
                    double sizeMb   = json.optDouble("size_mb", 0);
                    handler.post(() -> triggerDownload(jobId, mainFile, sizeMb, count));
                } else if ("error".equals(status)) {
                    handler.post(() -> showError(json.optString("error", "Download failed")));
                } else {
                    pollProgress(jobId);
                }
            } catch (Exception e) {
                handler.post(() -> showError(e.getMessage()));
            }
        }), 1000);
    }

    private void triggerDownload(String jobId, String filename, double sizeMb, int count) {
        try {
            // URL-encode each path segment separately
            String encodedFilename = Uri.encode(filename, "/");
            String fileUrl = serverUrl + "/file/" + jobId + "/" + encodedFilename;
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fileUrl));
            String displayName = filename.contains("/") ?
                    filename.substring(filename.lastIndexOf("/") + 1) : filename;
            req.setTitle(displayName);
            req.setDescription("Albo Downloader");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, displayName);
            if (dm != null) dm.enqueue(req);
        } catch (Exception ignored) {}

        String sizeStr  = sizeMb > 0 ? String.format(" (%.1f MB)", sizeMb) : "";
        String countStr = count > 1  ? " — " + count + " files" : "";
        resetUI("✓ Saved to Downloads!" + sizeStr + countStr);
        toast("Saved to Downloads — check notification");
    }

    private void resetUI(String message) {
        isDownloading = false;
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        statusText.setTextColor(0xFF4CAF50);
        setButtonsEnabled(true);
        againPanel.setVisibility(View.VISIBLE);
    }

    private void showError(String msg) {
        isDownloading = false;
        setButtonsEnabled(true);
        progressBar.setVisibility(View.GONE);
        statusText.setText("❌ " + (msg != null ? msg : "Something went wrong"));
        statusText.setTextColor(0xFFE53935);
        againPanel.setVisibility(View.VISIBLE);
    }

    private void setButtonsEnabled(boolean on) {
        int[] ids = {R.id.btnVideo, R.id.btnAudio, R.id.btnSubtitles,
                     R.id.btnTextOnly, R.id.btnPhotos, R.id.btnPlaylist};
        for (int id : ids) {
            View v = findViewById(id);
            if (v != null) { v.setEnabled(on); v.setAlpha(on ? 1f : 0.5f); }
        }
    }

    private String fmtDur(int secs) {
        int m = secs / 60, s = secs % 60;
        return m >= 60 ? String.format("%dh %02dm", m / 60, m % 60)
                       : String.format("%d:%02d", m, s);
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private HttpURLConnection openConn(String urlStr, String method, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
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

    private String readResp(HttpURLConnection conn) throws Exception {
        InputStream is;
        try { is = conn.getInputStream(); } catch (IOException e) { is = conn.getErrorStream(); }
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
