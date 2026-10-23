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

/** Isolated WebView/Chromium Tor window. A proxy failure is fail-closed. */
class ChromiumTorActivity : Activity() {
    private var tor: TorService? = null
    private var bound = false
    private var registered = false
    private var startedService = false
    private var ready = false
    private var webView: WebView? = null
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private val mainExecutor = Executor { command -> runOnUiThread(command) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    val state = intent.getStringExtra(TorService.EXTRA_STATUS).orEmpty()
                    if (state == TorService.STATUS_ON) attachProxy()
                    if (state == TorService.STATUS_OFF || state == TorService.STATUS_STOPPING) failClosed("Tor disconnected. Navigation has been stopped.")
                }
                TorService.ACTION_ERROR -> failClosed(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor could not connect.")
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            tor = (binder as? TorService.LocalBinder)?.service
            tor?.socksPort?.let { if (it in 1..65535) attachProxy() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { tor = null; failClosed("Tor service stopped.") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // This process is dedicated to the temporary Tor profile.
        if (android.os.Build.VERSION.SDK_INT >= 28) WebView.setDataDirectorySuffix("aster_chromium_tor")
        buildUi()
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            failClosed("This Android System WebView cannot enforce a proxy. No page was loaded.")
            return
        }
        registerStatus()
        val serviceIntent = Intent(this, TorService::class.java)
        try {
            bound = bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            startService(serviceIntent)
            startedService = true
            status.text = "Building Tor circuit…"
            progress.visibility = android.view.View.VISIBLE
            window.decorView.postDelayed({ if (!ready && !isFinishing) failClosed("Tor connection timed out. No page was loaded outside Tor.") }, 150_000L)
        } catch (error: Exception) {
            failClosed("Could not start Tor: ${error.message ?: "unknown error"}")
        }
    }

    private fun registerStatus() {
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(TorService.ACTION_STATUS); addAction(TorService.ACTION_ERROR)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
    }

    private fun attachProxy() {
        if (ready || isFinishing) return
        val port = tor?.socksPort ?: return
        if (port !in 1..65535) return
        val config = ProxyConfig.Builder().addProxyRule("socks5://127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(config, mainExecutor, Runnable {
            if (isFinishing) return@Runnable
            ready = true
            status.text = "TOR CONNECTED · SOCKS5 · Chromium WebView"
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
            singleLine = true; imeOptions = EditorInfo.IME_ACTION_GO; setPadding(dp(14), 0, dp(12), 0); background = rounded(0xff252c31.toInt(), 18)
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
                address.setText(url.orEmpty())
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
        webView?.apply {
            stopLoading(); loadUrl("about:blank"); clearHistory(); clearCache(true); removeAllViews(); destroy()
        }
        webView = null
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
        runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
        if (ready) runCatching { ProxyController.getInstance().clearProxyOverride(mainExecutor, Runnable {}) }
        if (registered) runCatching { unregisterReceiver(receiver) }
        if (bound) runCatching { unbindService(serviceConnection) }
        if (startedService) runCatching { stopService(Intent(this, TorService::class.java)) }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "app.aster.browser.chromiumtor.URL"
        private const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"
    }
}
