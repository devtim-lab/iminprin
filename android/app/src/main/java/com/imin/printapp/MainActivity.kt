package com.imin.printapp

import android.annotation.SuppressLint
import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.imin.printer.INeoPrinterCallback
import com.imin.printer.PrinterHelper
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val helper = PrinterHelper.getInstance()

    private val cb = object : INeoPrinterCallback() {
        override fun onRunResult(isSuccess: Boolean) {}
        override fun onReturnString(result: String?) {}
        override fun onRaiseException(code: Int, msg: String?) {}
        override fun onPrintResult(code: Int, msg: String?) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        helper.initPrinterService(this)
        helper.initPrinter(packageName, cb)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(PrinterBridge(), "Android")

        val prefs = getSharedPreferences("app", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("url", null)
        if (savedUrl != null) {
            findViewById<View>(R.id.setupPanel).visibility = View.GONE
            webView.loadUrl(savedUrl)
        } else {
            findViewById<Button>(R.id.btnSimpan).setOnClickListener {
                var url = findViewById<EditText>(R.id.etUrl).text.toString().trim()
                if (url.isNotEmpty()) {
                    if (!url.startsWith("http")) url = "https://$url"
                    prefs.edit().putString("url", url).apply()
                    findViewById<View>(R.id.setupPanel).visibility = View.GONE
                    webView.loadUrl(url)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
        }

        checkForUpdate()
    }

    // ================= AUTO UPDATE =================
    // Cek rilis terbaru di GitHub; kalau lebih baru, download + minta install.
    private fun checkForUpdate() {
        thread {
            try {
                val conn = URL("https://api.github.com/repos/devtim-lab/iminprin/releases/latest")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 10000
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                val latest = json.getString("tag_name").removePrefix("v")
                if (!isNewerVersion(latest, BuildConfig.VERSION_NAME)) return@thread

                val assets = json.getJSONArray("assets")
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.getString("name").endsWith(".apk")) apkUrl = a.getString("browser_download_url")
                }
                if (apkUrl == null) return@thread

                runOnUiThread {
                    showUpdateNotification(latest)
                    AlertDialog.Builder(this)
                        .setTitle("Update tersedia (v$latest)")
                        .setMessage("Versi baru APK tersedia. Update sekarang?")
                        .setPositiveButton("UPDATE") { _, _ -> startUpdateFlow(apkUrl, latest) }
                        .setNegativeButton("Nanti", null)
                        .show()
                }
            } catch (e: Exception) {
                e.printStackTrace() // offline / limit API -> abaikan saja
            }
        }
    }

    private fun showUpdateNotification(latest: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("update", "Update", NotificationManager.IMPORTANCE_HIGH)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        nm.notify(
            1,
            NotificationCompat.Builder(this, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("Update tersedia (v$latest)")
                .setContentText("Buka iMin Print untuk memperbarui")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
        )
    }

    // Cek izin install; kalau belum ada, antarkan user ke halaman izinnya
    private fun startUpdateFlow(apkUrl: String, latest: String) {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle("Izin install diperlukan")
                .setMessage("Aktifkan 'Izinkan dari sumber ini' untuk iMin Print agar update bisa terpasang otomatis.")
                .setPositiveButton("BUKA IZIN") { _, _ ->
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:$packageName")
                        )
                    )
                }
                .setNegativeButton("Batal", null)
                .show()
            return
        }
        downloadUpdate(apkUrl, latest)
    }

    private fun downloadUpdate(apkUrl: String, version: String) {
        val dir = File(cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "iminprint-$version.apk")
        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val req = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Update iMin Print v$version")
            .setDestinationUri(Uri.fromFile(file))
            .setAllowedOverMetered(true)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        val id = dm.enqueue(req)

        registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) == id) {
                    unregisterReceiver(this)
                    installApk(file)
                }
            }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
    }

    private fun installApk(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun isNewerVersion(latest: String, current: String): Boolean {
        fun parts(v: String) = v.split(".").map { it.toIntOrNull() ?: 0 }
        val l = parts(latest); val c = parts(current)
        val n = maxOf(l.size, c.size)
        for (i in 0 until n) {
            val a = l.getOrElse(i) { 0 }; val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    // ================= PRINT =================
    inner class PrinterBridge {
        @JavascriptInterface
        fun printTicket(html: String) {
            runOnUiThread {
                val offscreen = WebView(this@MainActivity)
                offscreen.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                offscreen.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.post {
                            val widthPx = 945 // 300dpi, lebar 80mm
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            )
                            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                            val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bmp))

                            helper.printBitmap(bmp, object : INeoPrinterCallback() {
                                override fun onRunResult(isSuccess: Boolean) {
                                    helper.printAndFeedPaper(3)
                                    helper.fullCutAndFeedPaper(5)
                                }
                                override fun onReturnString(result: String?) {}
                                override fun onRaiseException(code: Int, msg: String?) {}
                                override fun onPrintResult(code: Int, msg: String?) {}
                            })
                        }
                    }
                }
            }
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
