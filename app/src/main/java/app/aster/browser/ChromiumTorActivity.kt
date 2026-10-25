package app.aster.browser

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.IBinder
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.torproject.jni.TorService
import java.util.concurrent.Executor

/** Isolated Chromium WebView Tor window. A proxy failure is fail-closed. */
class ChromiumTorActivity : Activity() {
    private var registered = false
    private var torServiceBound = false
    private var ready = false
    private var webView: WebView? = null
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val mainExecutor = Executor { command -> runOnUiThread(command) }
    private val uiHandler = Handler(Looper.getMainLooper())
    private var pageLoading = false
    private val pageLoadTimeout = Runnable {
        if (pageLoading) {
            pageLoading = false
            progress.visibility = android.view.View.GONE
            status.text = "Page did not load through Tor · tap Go to retry"
        }
    }

    // TorService runs in Aster's main process. Keep it bound while this separate-process
    // window is foreground so Android does not freeze the Tor daemon with the background tab UI.
    private val torServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            torServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            torServiceBound = false
            failClosed("Tor service disconnected. Navigation has been stopped.")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    val state = intent.getStringExtra(TorService.EXTRA_STATUS).orEmpty()
                    if (state == TorService.STATUS_ON && ready) status.text = "TOR CONNECTED · SOCKS5 · Chromium WebView"
                    if (state == TorService.STATUS_OFF || state == TorService.STATUS_STOPPING) failClosed("Tor disconnected. Navigation has been stopped.")
                }
                TorService.ACTION_ERROR -> failClosed(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor could not connect.")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerStatus()
        torServiceBound = runCatching {
            bindService(
                Intent(this, TorService::class.java),
                torServiceConnection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            )
        }.getOrDefault(false)
        if (!torServiceBound) {
            failClosed("Could not keep the Tor service active. No page was opened.")
            return
        }
        status.text = "Preparing an isolated Tor browser…"
        progress.visibility = android.view.View.VISIBLE
        // Draw the native controls before asking Android to initialize its WebView provider.
        // Provider initialization can take seconds on cold start and should not look like a
        // black screen while the separate process is starting.
        window.decorView.postDelayed({ initializeTorBrowser() }, 300L)
    }

    private fun initializeTorBrowser() {
        if (isFinishing) return
        try {
            // This dedicated process avoids JNI collisions with Chromium's native engine.
            if (android.os.Build.VERSION.SDK_INT >= 28 && !webViewSuffixConfigured) {
                WebView.setDataDirectorySuffix("aster_chromium_tor")
                webViewSuffixConfigured = true
            }
        } catch (error: RuntimeException) {
            failClosed("Android could not initialize isolated WebView storage: ${error.message ?: "unknown error"}")
            return
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            failClosed("This Android System WebView cannot enforce a proxy. No page was loaded.")
            return
        }
        // Route requests directly to Guardian's local SOCKS listener. ProxyConfig explicitly
        // supports SOCKS; using the app's HTTP CONNECT bridge here left WebView requests
        // pending indefinitely on the Pixel even though the bridge itself passed Tor checks.
        val port = intent.getIntExtra(EXTRA_SOCKS_PROXY_PORT, 0)
        if (port !in 1..65535) {
            failClosed("No ready Tor SOCKS proxy was provided. No page was loaded outside Tor.")
            return
        }
        status.text = "Enforcing the Tor proxy…"
        progress.visibility = android.view.View.VISIBLE
        attachProxy(port)
    }

    private fun registerStatus() {
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(TorService.ACTION_STATUS); addAction(TorService.ACTION_ERROR)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private fun attachProxy(port: Int) {
        if (ready || isFinishing) return
        if (port !in 1..65535) return
        val config = ProxyConfig.Builder().addProxyRule("socks://127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(config, mainExecutor, Runnable {
            if (isFinishing) return@Runnable
            ready = true
            status.text = "Tor SOCKS route set · verifying route…"
            progress.visibility = android.view.View.GONE
            createWebView()
            val initial = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { TOR_CHECK_URL }
            navigate(initial)
        })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 21, 25)); setPadding(dp(12), dp(10), dp(12), dp(8)) }
        val title = TextView(this).apply { text = "✦  ASTER  ·  TOR PRIVATE"; setTextColor(0xff70e0bd.toInt()); textSize = 13f; setPadding(dp(4), dp(4), 0, dp(10)) }
        root.addView(title)
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            hint = "Search or enter address"; setTextColor(Color.WHITE); setHintTextColor(0xff9aa6aa.toInt()); textSize = 14f
            isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_GO; setPadding(dp(14), 0, dp(12), 0); background = rounded(0xff252c31.toInt(), 18)
            setOnEditorActionListener { _, actionId, _ -> if (actionId == EditorInfo.IME_ACTION_GO) { navigate(text.toString()); hideKeyboard(); true } else false }
        }
        bar.addView(address, LinearLayout.LayoutParams(0, dp(48), 1f))
        bar.addView(button("Go") { navigate(address.text.toString()); hideKeyboard() }, LinearLayout.LayoutParams(dp(50), dp(46)).apply { leftMargin = dp(6) })
        root.addView(bar)
        status = TextView(this).apply { text = "Waiting for Tor…"; textSize = 11f; setTextColor(0xffa7b4b6.toInt()); setPadding(dp(4), dp(8), 0, dp(5)) }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true; visibility = android.view.View.GONE; progressTintList = android.content.res.ColorStateList.valueOf(0xff70e0bd.toInt()) }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(3)))
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(4)) }
        tools.addView(button("‹") { if (webView?.canGoBack() == true) webView?.goBack() })
        tools.addView(button("›") { if (webView?.canGoForward() == true) webView?.goForward() })
        tools.addView(button("Tor check") { navigate(TOR_CHECK_URL) })
        tools.addView(button("Close") { finish() })
        root.addView(tools)
        val pageHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = rounded(Color.WHITE, 16); clipToOutline = true }
        root.addView(pageHost, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(4) })
        setContentView(root)
        this.pageHost = pageHost
    }

    private lateinit var pageHost: LinearLayout

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView() {
        val view = WebView(this)
        view.setBackgroundColor(Color.WHITE)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.settings.setSupportMultipleWindows(false)
        view.settings.mediaPlaybackRequiresUserGesture = true
        view.webChromeClient = WebChromeClient()
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase()
                return scheme != "http" && scheme != "https"
            }
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                uiHandler.removeCallbacks(pageLoadTimeout)
                pageLoading = true
                uiHandler.postDelayed(pageLoadTimeout, PAGE_LOAD_TIMEOUT_MS)
                address.setText(url.orEmpty())
                progress.visibility = android.view.View.VISIBLE
                status.text = "Loading through the Tor proxy…"
            }
            override fun onPageFinished(view: WebView, url: String?) {
                pageLoading = false
                uiHandler.removeCallbacks(pageLoadTimeout)
                progress.visibility = android.view.View.GONE
                if (url.orEmpty().contains("check.torproject.org/api/ip")) {
                    view.evaluateJavascript("document.body ? document.body.innerText : ''") { body ->
                        val decoded = body.orEmpty().replace("\\\"", "\"")
                        status.text = if (decoded.contains("\"IsTor\"", ignoreCase = true) &&
                            decoded.contains("true", ignoreCase = true)) {
                            "TOR VERIFIED · Chromium WebView"
                        } else {
                            "Tor check loaded, but did not confirm Tor routing"
                        }
                    }
                } else {
                    status.text = "Tor proxy active · page loaded"
                }
            }
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageLoading = false
                    uiHandler.removeCallbacks(pageLoadTimeout)
                    progress.visibility = android.view.View.GONE
                    status.text = "Page failed · ${error.description} (${error.errorCode})"
                }
            }
            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) {
                    pageLoading = false
                    uiHandler.removeCallbacks(pageLoadTimeout)
                    progress.visibility = android.view.View.GONE
                    status.text = "Page returned HTTP ${errorResponse.statusCode}"
                }
            }
        }
        webView = view
        pageHost.addView(view, LinearLayout.LayoutParams(-1, -1))
    }

    private fun navigate(raw: String) {
        if (!ready) { Toast.makeText(this, "Waiting for a Tor circuit", Toast.LENGTH_SHORT).show(); return }
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return
        val url = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.contains(' ') || !trimmed.contains('.') -> "https://duckduckgo.com/?q=" + Uri.encode(trimmed)
            else -> "https://$trimmed"
        }
        address.setText(url)
        pageLoading = true
        uiHandler.removeCallbacks(pageLoadTimeout)
        uiHandler.postDelayed(pageLoadTimeout, PAGE_LOAD_TIMEOUT_MS)
        progress.visibility = android.view.View.VISIBLE
        status.text = "Loading privately through Tor…"
        webView?.loadUrl(url)
    }

    private fun failClosed(message: String) {
        ready = false
        webView?.stopLoading(); webView?.loadUrl("about:blank")
        progress.visibility = android.view.View.GONE
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun button(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(0xffeaf3f1.toInt()); textSize = 13f
        background = rounded(0xff252c31.toInt(), 14); isClickable = true; isFocusable = true
        setPadding(dp(10), 0, dp(10), 0); setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0) }

    override fun onBackPressed() { if (webView?.canGoBack() == true) webView?.goBack() else finish() }

    override fun onDestroy() {
        pageLoading = false
        uiHandler.removeCallbacks(pageLoadTimeout)
        webView?.apply {
            stopLoading(); loadUrl("about:blank"); clearHistory(); clearCache(true); removeAllViews(); destroy()
        }
        webView = null
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        if (ready) runCatching { ProxyController.getInstance().clearProxyOverride(mainExecutor, Runnable {}) }
        if (registered) runCatching { unregisterReceiver(receiver) }
        if (torServiceBound) runCatching { unbindService(torServiceConnection) }
        runCatching { stopService(Intent(this, TorService::class.java)) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "app.aster.browser.chromiumtor.URL"
        const val EXTRA_SOCKS_PROXY_PORT = "app.aster.browser.chromiumtor.SOCKS_PROXY_PORT"
        private const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        // Android permits a WebView suffix to be set only once in each process.
        private var webViewSuffixConfigured = false
    }
}
