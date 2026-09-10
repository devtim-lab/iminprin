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
import androidx.appcompat.app.AppCompatActivity
import com.imin.printer.INeoPrinterCallback
import com.imin.printer.PrinterHelper

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
    }

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
