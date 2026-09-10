package com.imin.printapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.imin.printersdk.Callback
import com.imin.printersdk.IminPrintUtils

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var utils: IminPrintUtils

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Init printer built-in 80mm
        utils = IminPrintUtils.getInstance(this)
        utils.initPrinter(IminPrintUtils.PrintConnectType.USB)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true   // localStorage jalan
        webView.webViewClient = WebViewClient()

        // Jembatan JS: window.Android.printTicket(html)
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
    }

    /** Render HTML jadi Bitmap 300dpi lebar 80mm lalu cetak TANPA dialog. */
    inner class PrinterBridge {

        @JavascriptInterface
        fun printTicket(html: String) {
            runOnUiThread {
                val offscreen = WebView(this@MainActivity)
                offscreen.settings.javaScriptEnabled = false
                offscreen.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
                offscreen.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.post {
                            val widthPx = 945 // ~300 dpi untuk 80mm
                            view.measure(
                                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            )
                            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                            val bmp = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
                            view.draw(Canvas(bmp))

                            utils.printSingleBitmap(bmp, object : Callback {
                                override fun onSuccess() {
                                    utils.printAndFeedPaper(3)
                                    utils.cutPaper()
                                }
                                override fun onError(code: Int, msg: String?) {
                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "Print error: $msg", Toast.LENGTH_SHORT).show()
                                    }
                                }
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
