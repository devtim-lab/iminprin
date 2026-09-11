package com.imin.printapp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.imin.printer.INeoPrinterCallback;
import com.imin.printer.PrinterHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private final PrinterHelper helper = PrinterHelper.getInstance();

    private final INeoPrinterCallback cb = new INeoPrinterCallback() {
        @Override public void onRunResult(boolean isSuccess) {}
        @Override public void onReturnString(String result) {}
        @Override public void onRaiseException(int code, String msg) {}
        @Override public void onPrintResult(int code, String msg) {}
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        helper.initPrinterService(this);
        helper.initPrinter(getPackageName(), cb);

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new PrinterBridge(), "Android");

        SharedPreferences prefs = getSharedPreferences("app", Context.MODE_PRIVATE);
        String savedUrl = prefs.getString("url", null);
        if (savedUrl != null) {
            findViewById(R.id.setupPanel).setVisibility(View.GONE);
            webView.loadUrl(savedUrl);
        } else {
            findViewById(R.id.btnSimpan).setOnClickListener(v -> {
                String url = ((EditText) findViewById(R.id.etUrl)).getText().toString().trim();
                if (!url.isEmpty()) {
                    if (!url.startsWith("http")) url = "https://" + url;
                    prefs.edit().putString("url", url).apply();
                    findViewById(R.id.setupPanel).setVisibility(View.GONE);
                    webView.loadUrl(url);
                }
            });
        }

        // Android 11 (API 30) tidak butuh izin POST_NOTIFICATIONS (itu baru di Android 13+)
        checkForUpdate();
    }

    // ================= AUTO UPDATE =================
    // Cek rilis terbaru di GitHub; kalau lebih baru, download + minta install.
    private void checkForUpdate() {
        new Thread(() -> {
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(
                        "https://api.github.com/repos/devtim-lab/iminprin/releases/latest"
                ).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                String latest = json.getString("tag_name").replaceFirst("^v", "");
                if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return;

                JSONArray assets = json.getJSONArray("assets");
                String apkUrl = null;
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    if (a.getString("name").endsWith(".apk")) {
                        apkUrl = a.getString("browser_download_url");
                    }
                }
                if (apkUrl == null) return;

                String finalApkUrl = apkUrl;
                runOnUiThread(() -> {
                    showUpdateNotification(latest);
                    new AlertDialog.Builder(this)
                            .setTitle("Update tersedia (v" + latest + ")")
                            .setMessage("Versi baru APK tersedia. Update sekarang?")
                            .setPositiveButton("UPDATE", (d, w) -> startUpdateFlow(finalApkUrl, latest))
                            .setNegativeButton("Nanti", null)
                            .show();
                });
            } catch (Exception e) {
                e.printStackTrace(); // offline / limit API -> abaikan saja
            }
        }).start();
    }

    private void showUpdateNotification(String latest) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                    new NotificationChannel("update", "Update", NotificationManager.IMPORTANCE_HIGH));
        }
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(1, new NotificationCompat.Builder(this, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update tersedia (v" + latest + ")")
                .setContentText("Buka iMin Print untuk memperbarui")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build());
    }

    // Cek izin install; kalau belum ada, antarkan user ke halaman izinnya
    private void startUpdateFlow(String apkUrl, String latest) {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this)
                    .setTitle("Izin install diperlukan")
                    .setMessage("Aktifkan 'Izinkan dari sumber ini' untuk iMin Print agar update bisa terpasang otomatis.")
                    .setPositiveButton("BUKA IZIN", (d, w) ->
                            startActivity(new Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + getPackageName()))))
                    .setNegativeButton("Batal", null)
                    .show();
            return;
        }
        downloadUpdate(apkUrl, latest);
    }

    private void downloadUpdate(String apkUrl, String version) {
        // FIX: DownloadManager tidak bisa menulis ke cacheDir internal aplikasi.
        // Pakai external files dir milik aplikasi (tidak butuh izin storage di Android 11).
        File dir = new File(getExternalFilesDir(null), "updates");
        if (!dir.exists()) dir.mkdirs();
        File file = new File(dir, "iminprint-" + version + ".apk");
        if (file.exists()) file.delete();

        DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("Update iMin Print v" + version)
                .setDestinationInExternalFilesDir(this, null, "updates/iminprint-" + version + ".apk")
                .setAllowedOverMetered(true)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        long id = dm.enqueue(req);

        // Android 11 (API 30) masih boleh registerReceiver tanpa flag exported
        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                    unregisterReceiver(this);
                    installApk(file);
                }
            }
        }, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private void installApk(File file) {
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private boolean isNewerVersion(String latest, String current) {
        String[] l = latest.split("\\.");
        String[] c = current.split("\\.");
        int n = Math.max(l.length, c.length);
        for (int i = 0; i < n; i++) {
            int a = i < l.length ? parseIntSafe(l[i]) : 0;
            int b = i < c.length ? parseIntSafe(c[i]) : 0;
            if (a != b) return a > b;
        }
        return false;
    }

    private int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    // ================= PRINT =================
    public class PrinterBridge {
        @JavascriptInterface
        public void printTicket(String html) {
            runOnUiThread(() -> {
                WebView offscreen = new WebView(MainActivity.this);
                offscreen.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                offscreen.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.post(() -> {
                            int widthPx = 945; // 300dpi, lebar 80mm
                            view.measure(
                                    View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                            view.layout(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight());
                            Bitmap bmp = Bitmap.createBitmap(
                                    view.getMeasuredWidth(), view.getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                            view.draw(new Canvas(bmp));

                            helper.printBitmap(bmp, new INeoPrinterCallback() {
                                @Override public void onRunResult(boolean isSuccess) {
                                    helper.printAndFeedPaper(3);
                                    helper.fullCutAndFeedPaper(5);
                                }
                                @Override public void onReturnString(String result) {}
                                @Override public void onRaiseException(int code, String msg) {}
                                @Override public void onPrintResult(int code, String msg) {}
                            });
                        });
                    }
                });
            });
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
