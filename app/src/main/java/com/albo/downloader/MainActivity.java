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

    // Views
    private EditText urlInput, serverInput;
    private Button pasteBtn, btnVideo, btnAudio, btnPlaylist;
    private CheckBox subtitlesCheck;
    private ProgressBar progressBar;
    private TextView statusText, titleText, uploaderText, connectionText;
    private LinearLayout menuPanel, settingsPanel, infoCard;
    private View divider;

    private String serverUrl;
    private String currentJobId;
    private String currentUrl;
    private boolean isDownloading = false;
    private boolean isPlaylist = false;

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
        setupListeners();
        checkConnection();
        handleShareIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
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
        btnVideo       = findViewById(R.id.btnVideo);
        btnAudio       = findViewById(R.id.btnAudio);
        btnPlaylist    = findViewById(R.id.btnPlaylist);
        subtitlesCheck = findViewById(R.id.subtitlesCheck);
        progressBar    = findViewById(R.id.progressBar);
        statusText     = findViewById(R.id.statusText);
        titleText      = findViewById(R.id.titleText);
        uploaderText   = findViewById(R.id.uploaderText);
        connectionText = findViewById(R.id.connectionText);
        menuPanel      = findViewById(R.id.menuPanel);
        settingsPanel  = findViewById(R.id.settingsPanel);
        infoCard       = findViewById(R.id.infoCard);
        divider        = findViewById(R.id.divider);

        serverInput.setText(serverUrl);
        menuPanel.setVisibility(View.GONE);
        infoCard.setVisibility(View.GONE);
        settingsPanel.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
    }

    private void setupListeners() {
        // Paste button
        pasteBtn.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null && cm.hasPrimaryClip() && cm.getPrimaryClip() != null) {
                String text = cm.getPrimaryClip().getItemAt(0).getText().toString().trim();
                urlInput.setText(text);
                fetchInfo(text);
                hideKeyboard();
            }
        });

        // URL input done
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            String url = urlInput.getText().toString().trim();
            if (!url.isEmpty()) fetchInfo(url);
            hideKeyboard();
            return true;
        });

        // Video button
        btnVideo.setOnClickListener(v -> {
            if (isDownloading) return;
            startDownload("best");
        });

        // Audio / MP3 button
        btnAudio.setOnClickListener(v -> {
            if (isDownloading) return;
            startDownload("mp3");
        });

        // Playlist button
        btnPlaylist.setOnClickListener(v -> {
            if (isDownloading) return;
            startDownload("playlist");
        });

        // Settings toggle - tap connection text
        connectionText.setOnClickListener(v -> {
            boolean visible = settingsPanel.getVisibility() == View.VISIBLE;
            settingsPanel.setVisibility(visible ? View.GONE : View.VISIBLE);
        });

        // Save server URL on focus change
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
                HttpURLConnection conn = openConnection(serverUrl + "/ping", "GET", null);
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
        isPlaylist = url.contains("list=") && (url.contains("youtube.com") || url.contains("youtu.be"));

        // Show loading state
        infoCard.setVisibility(View.GONE);
        menuPanel.setVisibility(View.GONE);
        divider.setVisibility(View.GONE);
        statusText.setText("Fetching info...");
        statusText.setTextColor(0xFF888888);

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", url);
                HttpURLConnection conn = openConnection(serverUrl + "/info", "POST", body.toString());
                String resp = readResponse(conn);
                conn.disconnect();
                JSONObject json = new JSONObject(resp);

                if (json.has("error")) {
                    handler.post(() -> {
                        statusText.setText("Could not fetch info — paste a valid URL");
                        showMenu();
                    });
                    return;
                }

                String title    = json.optString("title", "");
                String uploader = json.optString("uploader", "");
                int duration    = json.optInt("duration", 0);
                String platform = json.optString("platform", "");
                String dur      = duration > 0 ? "  •  " + formatDuration(duration) : "";

                handler.post(() -> {
                    if (!title.isEmpty()) {
                        titleText.setText(title);
                        uploaderText.setText(platform + (uploader.isEmpty() ? "" : "  •  " + uploader) + dur);
                        infoCard.setVisibility(View.VISIBLE);
                        divider.setVisibility(View.VISIBLE);
                    }
                    statusText.setText("Choose format:");
                    statusText.setTextColor(0xFF888888);
                    showMenu();
                });

            } catch (Exception e) {
                handler.post(() -> {
                    statusText.setText("Choose format:");
                    statusText.setTextColor(0xFF888888);
                    showMenu();
                });
            }
        });
    }

    private void showMenu() {
        // Show playlist button only for playlist URLs
        btnPlaylist.setVisibility(isPlaylist ? View.VISIBLE : View.GONE);
        menuPanel.setVisibility(View.VISIBLE);
    }

    private void startDownload(String fmt) {
        if (currentUrl == null || currentUrl.isEmpty()) {
            toast("Paste a URL first");
            return;
        }

        isDownloading = true;
        setButtonsEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setProgress(0);
        statusText.setText("Starting download...");
        statusText.setTextColor(0xFF888888);

        boolean subs = subtitlesCheck.isChecked();

        executor.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("url", currentUrl);
                body.put("format", fmt);
                body.put("subtitles", subs);

                HttpURLConnection conn = openConnection(serverUrl + "/download", "POST", body.toString());
                String resp = readResponse(conn);
                conn.disconnect();

                JSONObject json = new JSONObject(resp);
                if (json.has("error")) {
                    handler.post(() -> showError(json.optString("error", "Download failed")));
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

                    String status   = json.optString("status", "");
                    int progress    = json.optInt("progress", 0);
                    String platform = json.optString("platform", "");

                    handler.post(() -> {
                        progressBar.setProgress(progress);
                        if (progress > 0) {
                            statusText.setText("Downloading " + platform + "... " + progress + "%");
                        }
                    });

                    if ("done".equals(status)) {
                        String mainFile = json.optString("main_file", "");
                        double sizeMb   = json.optDouble("size_mb", 0);
                        handler.post(() -> triggerFileDownload(jobId, mainFile, sizeMb));
                    } else if ("error".equals(status)) {
                        String err = json.optString("error", "Download failed");
                        handler.post(() -> showError(err));
                    } else {
                        pollProgress(jobId); // keep polling
                    }

                } catch (Exception e) {
                    handler.post(() -> showError(e.getMessage()));
                }
            });
        }, 1000);
    }

    private void triggerFileDownload(String jobId, String filename, double sizeMb) {
        String fileUrl = serverUrl + "/file/" + jobId + "/" + Uri.encode(filename);

        // Save to Downloads folder via DownloadManager
        try {
            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(fileUrl));
            req.setTitle(filename);
            req.setDescription("Albo Downloader");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename);
            if (dm != null) dm.enqueue(req);
        } catch (Exception e) {
            // Fallback — just show the URL
        }

        String sizeStr = sizeMb > 0 ? String.format(" (%.1f MB)", sizeMb) : "";
        resetUI("✓ Saved to Downloads!" + sizeStr);
        toast("Saved to Downloads — check notification");
    }

    private void resetUI(String message) {
        isDownloading = false;
        setButtonsEnabled(true);
        progressBar.setVisibility(View.GONE);
        statusText.setText(message);
        statusText.setTextColor(0xFF4CAF50);
    }

    private void showError(String msg) {
        isDownloading = false;
        setButtonsEnabled(true);
        progressBar.setVisibility(View.GONE);
        statusText.setText("❌ " + (msg != null ? msg : "Something went wrong"));
        statusText.setTextColor(0xFFE53935);
    }

    private void setButtonsEnabled(boolean enabled) {
        btnVideo.setEnabled(enabled);
        btnAudio.setEnabled(enabled);
        btnPlaylist.setEnabled(enabled);
        btnVideo.setAlpha(enabled ? 1f : 0.5f);
        btnAudio.setAlpha(enabled ? 1f : 0.5f);
        btnPlaylist.setAlpha(enabled ? 1f : 0.5f);
    }

    private String formatDuration(int seconds) {
        int m = seconds / 60;
        int s = seconds % 60;
        if (m >= 60) return String.format("%dh %02dm", m / 60, m % 60);
        return String.format("%d:%02d", m, s);
    }

    private boolean isValidUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
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
        try { is = conn.getInputStream(); }
        catch (IOException e) { is = conn.getErrorStream(); }
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
