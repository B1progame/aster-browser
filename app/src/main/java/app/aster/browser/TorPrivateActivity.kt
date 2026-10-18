package app.aster.browser

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.torproject.jni.TorService
import java.io.File

/** A separate Gecko runtime/profile whose network requests are forced through Tor's SOCKS5 listener. */
class TorPrivateActivity : Activity() {
    companion object {
        const val EXTRA_SOCKS_PORT = "app.aster.browser.tor.SOCKS_PORT"
        const val EXTRA_START_URL = "app.aster.browser.tor.START_URL"
        const val ACTION_WINDOW_CLOSED = "app.aster.browser.tor.WINDOW_CLOSED"
        private const val TOR_CHECK_URL = "https://check.torproject.org/api/ip"
        private const val START_PAGE = "resource://android/assets/home.html?theme=midnight"
    }

    private var runtime: GeckoRuntime? = null
    private var session: GeckoSession? = null
    private var socksPort = 0
    private lateinit var address: EditText
    private lateinit var status: TextView
    private lateinit var geckoView: GeckoView
    private lateinit var torProfile: File
    private var receiverRegistered = false
    private var pendingStartUrl = START_PAGE

    private val torStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != TorService.ACTION_STATUS) return
            val state = intent.getStringExtra(TorService.EXTRA_STATUS).orEmpty()
            if (state == TorService.STATUS_ON) {
                setTorState("TOR CONNECTED", 0xff6ee7bd.toInt())
            } else if (state == TorService.STATUS_OFF || state == TorService.STATUS_STOPPING) {
                setTorState("TOR DISCONNECTED · BLOCKED", 0xffff9a90.toInt())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        socksPort = intent.getIntExtra(EXTRA_SOCKS_PORT, 0)
        if (socksPort !in 1..65535) {
            Toast.makeText(this, "Tor did not provide a valid local proxy. Aster did not load this page outside Tor.", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        torProfile = File(cacheDir, "aster-tor-private-profile")
        torProfile.deleteRecursively()
        torProfile.mkdirs()
        val config = File(cacheDir, "aster-tor-geckoview.yaml")
        config.writeText(
            """args:
  - --profile
  - "${torProfile.absolutePath}"
prefs:
  network.proxy.type: 1
  network.proxy.socks: "127.0.0.1"
  network.proxy.socks_port: $socksPort
  network.proxy.socks_version: 5
  network.proxy.socks_remote_dns: true
  network.proxy.no_proxies_on: ""
  network.proxy.failover_direct: false
  media.peerconnection.enabled: false
  network.http.http3.enable: false
""".trimIndent()
        )

        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.ANALYTIC or ContentBlocking.AntiTracking.SOCIAL or
                    ContentBlocking.AntiTracking.CONTENT or ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.CRYPTOMINING or ContentBlocking.AntiTracking.STP or
                    ContentBlocking.AntiTracking.EMAIL
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .strictSocialTrackingProtection(true)
            .queryParameterStrippingEnabled(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .contentBlockingDatabase(true)
            .build()
        runtime = GeckoRuntime.create(
            this,
            GeckoRuntimeSettings.Builder()
                .configFilePath(config.absolutePath)
                .arguments(arrayOf("--profile", torProfile.absolutePath))
                .contentBlocking(blocking)
                .globalPrivacyControlEnabled(true)
                .build()
        )
        runtime?.warmUp()
        val torSession = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(true).build())
        torSession.open(requireNotNull(runtime) { "Tor Gecko runtime was not created" })
        session = torSession
        buildBrowserUi(torSession)
        registerTorStatus()
        pendingStartUrl = intent.getStringExtra(EXTRA_START_URL).orEmpty().ifBlank { START_PAGE }
        // MainActivity launches this only after Guardian TorService reports STATUS_ON,
        // which means its first Tor circuit is established. Gecko has a strict SOCKS
        // proxy configured with failover-direct disabled, so a failed route stays blocked.
        setTorState("TOR CONNECTED · $socksPort", 0xff6ee7bd.toInt())
        load(pendingStartUrl)
    }

    private fun buildBrowserUi(browserSession: GeckoSession) {
        val background = Color.rgb(15, 19, 28)
        val panel = Color.rgb(27, 37, 53)
        val accent = 0xff79a9ff.toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(background) }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(12))
            setBackgroundColor(background)
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = TextView(this).apply {
            text = "◉  TOR PRIVATE WINDOW"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xffb4f6dd.toInt())
        }
        brand.addView(title, LinearLayout.LayoutParams(0, dp(40), 1f))
        status = TextView(this).apply {
            text = "TOR CONNECTED"
            textSize = 10f
            setTypeface(null, Typeface.BOLD)
            setTextColor(0xff6ee7bd.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(11), 0, dp(11), 0)
            setBackground(rounded(0xff203c39.toInt(), 30))
            contentDescription = "Tor connection status"
        }
        brand.addView(status, LinearLayout.LayoutParams(-2, dp(32)))
        top.addView(brand)
        val subtitle = TextView(this).apply {
            text = "Private session · Tor SOCKS · data discarded when closed"
            textSize = 12f
            setTextColor(0xff9daabc.toInt())
            setPadding(0, 0, 0, dp(9))
        }
        top.addView(subtitle)
        val addressRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        address = EditText(this).apply {
            hint = "Search or enter address"
            textSize = 15f
            setSingleLine(true)
            setTextColor(0xfff2f4f8.toInt())
            setHintTextColor(0xff9daabc.toInt())
            setPadding(dp(16), 0, dp(12), 0)
            setBackground(rounded(panel, 18))
            imeOptions = EditorInfo.IME_ACTION_GO
            setSelectAllOnFocus(true)
            setOnEditorActionListener { _, action, _ ->
                if (action == EditorInfo.IME_ACTION_GO || action == EditorInfo.IME_ACTION_DONE) {
                    navigate(address.text.toString())
                    true
                } else false
            }
        }
        addressRow.addView(address, LinearLayout.LayoutParams(0, dp(52), 1f))
        val close = actionButton("×", "Close Tor private window", accent) { finish() }
        addressRow.addView(close, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(7) })
        top.addView(addressRow)
        root.addView(top)

        geckoView = GeckoView(this).apply { setBackgroundColor(background); setSession(browserSession) }
        root.addView(geckoView, LinearLayout.LayoutParams(-1, 0, 1f))

        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
            setBackgroundColor(0xff172233.toInt())
        }
        controls.addView(actionButton("‹", "Back", accent) { session?.goBack() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(actionButton("›", "Forward", accent) { session?.goForward() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(actionButton("⌂", "Home", accent) { load(START_PAGE) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(actionButton("✓", "Verify Tor connection", accent) { load(TOR_CHECK_URL) }, LinearLayout.LayoutParams(0, dp(48), 1f))
        controls.addView(actionButton("×", "Close Tor private window", accent) { finish() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(controls)
        setContentView(root)

        session?.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                if (title.isNullOrBlank()) return
                this@TorPrivateActivity.title = title
            }
        }
        session?.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    runOnUiThread { address.setText(url.takeIf { it.startsWith("http") }.orEmpty()) }
                }
            }
        }
    }

    private fun registerTorStatus() {
        ContextCompat.registerReceiver(
            this,
            torStatusReceiver,
            IntentFilter(TorService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        receiverRegistered = true
    }

    private fun load(url: String) {
        if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("resource://android/assets/")) {
            session?.loadUri(url)
        } else {
            session?.loadUri("https://duckduckgo.com/?q=" + Uri.encode(url))
        }
    }

    private fun navigate(raw: String) {
        val text = raw.trim()
        if (text.isBlank()) return
        val target = if (text.contains(' ') || (!text.contains('.') && !text.startsWith("http"))) {
            "https://duckduckgo.com/?q=" + Uri.encode(text)
        } else if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
        address.setText(target)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken, 0)
        address.clearFocus()
        session?.loadUri(target)
    }

    private fun setTorState(text: String, color: Int) {
        if (!::status.isInitialized) return
        status.text = text
        status.setTextColor(color)
        status.setBackground(rounded(if (color == 0xff6ee7bd.toInt()) 0xff203c39.toInt() else 0xff482a2b.toInt(), 30))
    }

    private fun actionButton(text: String, description: String, color: Int, action: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = if (text.length == 1) 24f else 13f
        setTextColor(color)
        gravity = Gravity.CENTER
        contentDescription = description
        isClickable = true
        isFocusable = true
        background = android.util.TypedValue().let { value ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)
            if (value.resourceId != 0) getDrawable(value.resourceId)!! else rounded(0xff263247.toInt(), 14)
        }
        setOnClickListener { action() }
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_START_URL).orEmpty()
        if (url.isNotBlank()) load(url)
    }

    @Deprecated("Use the close control to end the disposable Tor profile")
    override fun onBackPressed() {
        session?.goBack() ?: finish()
    }

    override fun onDestroy() {
        if (receiverRegistered) runCatching { unregisterReceiver(torStatusReceiver) }
        session?.let { if (it.isOpen) it.close() }
        runtime?.shutdown()
        if (isFinishing) {
            sendBroadcast(Intent(ACTION_WINDOW_CLOSED).setPackage(packageName))
            stopService(Intent(this, TorService::class.java))
            if (::torProfile.isInitialized) torProfile.deleteRecursively()
        }
        super.onDestroy()
    }
}
