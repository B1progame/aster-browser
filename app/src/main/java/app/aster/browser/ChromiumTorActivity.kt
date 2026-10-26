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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import org.torproject.jni.TorService
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/** Isolated Chromium WebView Tor window. A proxy failure is fail-closed. */
class ChromiumTorActivity : Activity() {
    private var registered = false
    private var torServiceBound = false
    private var ready = false
    private var webView: WebView? = null
    private var torHttpProxy: TorSocksHttpProxy? = null
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var routeStatus: TextView
    private lateinit var routeRows: LinearLayout
    private lateinit var routeDetails: LinearLayout
    private lateinit var routeRefreshButton: TextView
    private lateinit var routeToggleButton: TextView
    private lateinit var progress: ProgressBar
    private val mainExecutor = Executor { command -> runOnUiThread(command) }
    private val routeExecutor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private val routeRefresh = object : Runnable {
        override fun run() {
            refreshRoute()
            if (!isFinishing && ready) uiHandler.postDelayed(this, ROUTE_REFRESH_MS)
        }
    }
    private var pageLoading = false
    private var expectedPageHost = ""
    private val pageLoadTimeout = Runnable {
        if (pageLoading) {
            pageLoading = false
            progress.visibility = android.view.View.GONE
            val proxyRequests = torHttpProxy?.requestCount ?: 0
            val proxyDiagnostic = if (proxyRequests == 0) {
                "WebView did not reach the Tor proxy · tap Go to retry"
            } else {
                "${torHttpProxy?.diagnosticSummary().orEmpty()}"
            }
            status.text = "Page timed out · $proxyDiagnostic"
        }
    }
    private val pagePaintTimeout = Runnable {
        if (!isFinishing && status.text.startsWith("Tor response received")) {
            status.text = "Tor response received · page did not paint"
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
                    if (state == TorService.STATUS_ON && ready) status.text = "TOR CONNECTED · HTTP TUNNEL · Chromium WebView"
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
        // Route every WebView request through an app-owned HTTP CONNECT proxy backed by
        // Tor's SOCKS listener. This keeps DNS resolution inside Tor and gives us a
        // controllable proxy boundary without allowing direct fallback.
        val socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 0)
        if (socksPort !in 1..65535) {
            failClosed("Tor did not provide a ready SOCKS listener. No page was loaded outside Tor.")
            return
        }
        status.text = "Enforcing the Tor proxy…"
        progress.visibility = android.view.View.VISIBLE
        try {
            val bridge = TorSocksHttpProxy(socksPort)
            val localProxyPort = bridge.start()
            torHttpProxy = bridge
            attachProxy(localProxyPort)
        } catch (error: Exception) {
            failClosed("Could not start the Tor-only browser proxy: ${error.message ?: "unknown error"}")
        }
    }

    private fun registerStatus() {
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(TorService.ACTION_STATUS); addAction(TorService.ACTION_ERROR)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private fun attachProxy(localProxyPort: Int) {
        if (ready || isFinishing) return
        if (localProxyPort !in 1..65535) {
            failClosed("Tor provided an invalid local proxy. No page was loaded outside Tor.")
            return
        }
        val config = ProxyConfig.Builder().apply {
            removeImplicitRules()
            TorProxyRoutePlan.forLocalHttpProxyPort(localProxyPort).forEach { rule ->
                addProxyRule(rule.proxyUrl, rule.schemeFilter)
            }
        }.build()
        ProxyController.getInstance().setProxyOverride(config, mainExecutor, Runnable {
            if (isFinishing) return@Runnable
            ready = true
            status.text = "Tor-only proxy set · verifying…"
            progress.visibility = android.view.View.GONE
            createWebView()
            uiHandler.removeCallbacks(routeRefresh)
            uiHandler.post(routeRefresh)
            val initial = intent.getStringExtra(EXTRA_URL).orEmpty().ifBlank { TOR_CHECK_URL }
            navigate(initial)
        })
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(17, 21, 25)); setPadding(dp(12), dp(10), dp(12), dp(8)) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(dp(12), dp(10) + bars.top, dp(12), dp(8) + bars.bottom)
            insets
        }
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
        val routeCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = rounded(0xff20282b.toInt(), 16)
        }
        val routeHeading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        routeHeading.addView(TextView(this).apply {
            text = "TOR ROUTE"; textSize = 10f; letterSpacing = .12f
            setTextColor(0xff70e0bd.toInt())
        }, LinearLayout.LayoutParams(0, -2, 1f))
        routeRefreshButton = button("Refresh") { refreshRoute() }
        routeRefreshButton.contentDescription = "Refresh Tor route"
        routeHeading.addView(routeRefreshButton, LinearLayout.LayoutParams(dp(78), dp(32)))
        routeToggleButton = button("⌃") { setRouteExpanded(!routeExpanded) }
        routeToggleButton.contentDescription = "Collapse Tor route details"
        routeHeading.addView(routeToggleButton, LinearLayout.LayoutParams(dp(42), dp(32)).apply { leftMargin = dp(6) })
        routeCard.addView(routeHeading)
        routeStatus = TextView(this).apply {
            text = "Waiting for Tor circuit details…"; textSize = 11f
            setTextColor(0xffa7b4b6.toInt()); setPadding(0, dp(4), 0, dp(6))
        }
        routeDetails = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        routeDetails.addView(routeStatus)
        routeRows = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        routeDetails.addView(routeRows)
        routeCard.addView(routeDetails)
        root.addView(routeCard, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(5); bottomMargin = dp(4) })
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
    private var routeExpanded = true

    private fun setRouteExpanded(expanded: Boolean) {
        if (routeExpanded == expanded) return
        routeExpanded = expanded
        routeToggleButton.text = if (expanded) "⌃" else "⌄"
        routeToggleButton.contentDescription = if (expanded) "Collapse Tor route details" else "Expand Tor route details"
        routeRefreshButton.visibility = if (expanded) android.view.View.VISIBLE else android.view.View.GONE
        routeDetails.animate().cancel()
        if (expanded) {
            routeDetails.alpha = 0f
            routeDetails.visibility = android.view.View.VISIBLE
            routeDetails.animate().alpha(1f).setDuration(180L).start()
        } else {
            routeDetails.animate().alpha(0f).setDuration(140L).withEndAction {
                if (!routeExpanded) {
                    routeDetails.visibility = android.view.View.GONE
                    routeDetails.alpha = 1f
                }
            }.start()
        }
    }

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
                val startedHost = hostForWebUrl(url)
                if (startedHost.isBlank()) return
                expectedPageHost = startedHost
                uiHandler.removeCallbacks(pageLoadTimeout)
                uiHandler.removeCallbacks(pagePaintTimeout)
                pageLoading = true
                uiHandler.postDelayed(pageLoadTimeout, PAGE_LOAD_TIMEOUT_MS)
                address.setText(url.orEmpty())
                progress.visibility = android.view.View.VISIBLE
                status.text = "Loading through the Tor proxy…"
                refreshRoute(url.orEmpty())
            }
            override fun onPageFinished(view: WebView, url: String?) {
                // A new WebView finishes its initial about:blank document. Do not report
                // that as the requested site having loaded or run the Tor API check on it.
                if (!isExpectedPageCompletion(url, expectedPageHost)) return
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
                    status.text = "Tor response received · waiting for page paint…"
                    uiHandler.removeCallbacks(pagePaintTimeout)
                    uiHandler.postDelayed(pagePaintTimeout, PAGE_PAINT_TIMEOUT_MS)
                }
                refreshRoute(url.orEmpty())
            }

            override fun onPageCommitVisible(view: WebView, url: String?) {
                if (!isExpectedPageCompletion(url, expectedPageHost)) return
                uiHandler.removeCallbacks(pagePaintTimeout)
                status.text = if (url.orEmpty().contains("check.torproject.org/api/ip")) {
                    "Tor check page visible · verifying route…"
                } else {
                    "Tor proxy active · page visible"
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                pageLoading = false
                uiHandler.removeCallbacks(pageLoadTimeout)
                progress.visibility = android.view.View.GONE
                status.text = if (detail.didCrash()) {
                    "Private page renderer crashed · tap Go to retry"
                } else {
                    "Private page renderer stopped · tap Go to retry"
                }
                view.stopLoading()
                pageHost.removeView(view)
                if (webView === view) webView = null
                view.destroy()
                return true
            }
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                if (request.isForMainFrame) {
                    pageLoading = false
                    uiHandler.removeCallbacks(pageLoadTimeout)
                    uiHandler.removeCallbacks(pagePaintTimeout)
                    progress.visibility = android.view.View.GONE
                    status.text = "Page failed · ${error.description} (${error.errorCode}) · ${torHttpProxy?.diagnosticSummary().orEmpty()}"
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
                    uiHandler.removeCallbacks(pagePaintTimeout)
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
        expectedPageHost = hostForWebUrl(url)
        address.setText(url)
        pageLoading = true
        uiHandler.removeCallbacks(pageLoadTimeout)
        uiHandler.removeCallbacks(pagePaintTimeout)
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
        routeStatus.text = "No active route · $message"
        routeRows.removeAllViews()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    /** Ask Tor's local control port for the current built circuit. Only relay nicknames and
     * GeoIP countries are returned to the UI; destination URLs and relay IPs are never shown. */
    private fun refreshRoute(rawUrl: String = address.text?.toString().orEmpty()) {
        if (!ready || isFinishing) return
        val host = runCatching { Uri.parse(rawUrl).host.orEmpty() }.getOrDefault("")
        routeExecutor.execute {
            val snapshot = runCatching {
                contentResolver.call(
                    Uri.parse("content://$packageName.tor-route"),
                    TorRouteProvider.METHOD_SNAPSHOT,
                    host,
                    Bundle()
                )
            }.getOrNull()
            val isReady = snapshot?.getBoolean("ready") == true
            val siteCircuit = snapshot?.getBoolean("siteCircuit") == true
            val relays = snapshot?.getStringArrayList("relays").orEmpty()
            runOnUiThread {
                if (isFinishing || !::routeStatus.isInitialized) return@runOnUiThread
                routeRows.removeAllViews()
                if (!isReady || relays.isEmpty()) {
                    routeStatus.text = snapshot?.getString("message") ?: "Waiting for a built Tor browsing circuit…"
                    return@runOnUiThread
                }
                routeStatus.text = if (siteCircuit) "This site's stream is using the circuit below" else "Current Tor browsing circuit · site association not reported yet"
                relays.forEachIndexed { index, encoded ->
                    val parts = encoded.split('|', limit = 3)
                    val role = parts.getOrNull(0).orEmpty()
                    val nickname = parts.getOrNull(1).orEmpty().ifBlank { "Tor relay" }
                    val country = parts.getOrNull(2).orEmpty()
                    val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, dp(4), 0) }
                    column.addView(TextView(this).apply {
                        text = role.uppercase(); textSize = 9f; letterSpacing = .06f
                        setTextColor(0xff70e0bd.toInt())
                    })
                    column.addView(TextView(this).apply {
                        text = nickname; textSize = 11f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                        setTextColor(Color.WHITE)
                    })
                    column.addView(TextView(this).apply {
                        text = countryName(country); textSize = 10f
                        setTextColor(0xffa7b4b6.toInt())
                    })
                    routeRows.addView(column, LinearLayout.LayoutParams(0, -2, 1f))
                    if (index < relays.lastIndex) {
                        routeRows.addView(TextView(this).apply {
                            text = "›"; textSize = 20f; gravity = Gravity.CENTER
                            setTextColor(0xff70e0bd.toInt()); contentDescription = "Next Tor relay"
                        }, LinearLayout.LayoutParams(dp(18), -2))
                    }
                }
            }
        }
    }

    private fun countryName(code: String): String {
        if (!code.matches(Regex("[A-Z]{2}"))) return "Country unavailable"
        return runCatching { java.util.Locale.Builder().setRegion(code).build().displayCountry }
            .getOrDefault(code).ifBlank { code }
    }

    private fun button(label: String, click: () -> Unit) = TextView(this).apply {
        text = label; gravity = Gravity.CENTER; setTextColor(0xffeaf3f1.toInt()); textSize = 13f
        background = rounded(0xff252c31.toInt(), 14); isClickable = true; isFocusable = true
        setPadding(dp(10), 0, dp(10), 0); setOnClickListener { click() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun hideKeyboard() { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0) }

    @Suppress("DEPRECATION")
    override fun onBackPressed() { if (webView?.canGoBack() == true) webView?.goBack() else finish() }

    override fun onDestroy() {
        pageLoading = false
        uiHandler.removeCallbacks(pageLoadTimeout)
        uiHandler.removeCallbacks(pagePaintTimeout)
        uiHandler.removeCallbacks(routeRefresh)
        routeExecutor.shutdownNow()
        webView?.apply {
            stopLoading(); loadUrl("about:blank"); clearHistory(); clearCache(true); removeAllViews(); destroy()
        }
        webView = null
        runCatching { torHttpProxy?.close() }
        torHttpProxy = null
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
        const val EXTRA_SOCKS_PORT = "app.aster.browser.chromiumtor.SOCKS_PORT"
        private const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"
        private const val PAGE_LOAD_TIMEOUT_MS = 30_000L
        private const val PAGE_PAINT_TIMEOUT_MS = 2_500L
        private const val ROUTE_REFRESH_MS = 15_000L
        // Android permits a WebView suffix to be set only once in each process.
        private var webViewSuffixConfigured = false

        internal fun hostForWebUrl(rawUrl: String?): String {
            if (rawUrl.isNullOrBlank()) return ""
            val uri = runCatching { java.net.URI(rawUrl) }.getOrNull() ?: return ""
            val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
            if (scheme != "http" && scheme != "https") return ""
            return uri.host.orEmpty().lowercase(java.util.Locale.ROOT)
        }

        internal fun isExpectedPageCompletion(rawUrl: String?, expectedHost: String): Boolean {
            if (expectedHost.isBlank()) return false
            return hostForWebUrl(rawUrl).equals(expectedHost, ignoreCase = true)
        }
    }
}
