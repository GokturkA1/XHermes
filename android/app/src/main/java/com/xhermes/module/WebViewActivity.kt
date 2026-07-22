package com.xhermes.module

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class WebViewActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        XLog.i("WebViewActivity started successfully.")

        try {
            if (ConfigManager.enableWebViewDebugging) {
                WebView.setWebContentsDebuggingEnabled(true)
                XLog.i("WebViewActivity - WebView debugging enabled.")
            }

            val webView = WebView(this).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        return false
                    }
                }
                settings.setSupportZoom(false)
                settings.builtInZoomControls = false
                settings.displayZoomControls = false
                settings.useWideViewPort = false
                
                setOnTouchListener { _, event ->
                    // Consume multi-touch events to block pinch-to-zoom
                    event.pointerCount > 1
                }
                
                val url = ConfigManager.webViewUrl.trim()
                if (url.isNotEmpty()) {
                    XLog.i("WebViewActivity - Loading URL: $url")
                    loadUrl(url)
                } else {
                    val fallbackHtml = """
                        <html>
                        <body style='background:#1C1B1F;color:#E6E1E5;display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0;font-family:sans-serif;'>
                            <h2 style='color:#D0BCFF;'>XHermes WebView</h2>
                            <p style='color:#CAC4D0;'>Yönlendirilecek sayfa ayarlanmadı.</p>
                        </body>
                        </html>
                    """.trimIndent()
                    loadDataWithBaseURL("file:///android_asset/", fallbackHtml, "text/html", "UTF-8", null)
                }
            }
            setContentView(webView)
            XLog.i("WebViewActivity - Layout set to WebView.")
        } catch (t: Throwable) {
            XLog.e("WebViewActivity - Failed to initialize layout", t)
        }
    }
}
