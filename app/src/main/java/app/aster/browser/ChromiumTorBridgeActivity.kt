package app.aster.browser

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.torproject.jni.TorService

/** Starts Tor in the app process, then hands its SOCKS listener to the isolated Chromium Tor process. */
class ChromiumTorBridgeActivity : Activity() {
    private var tor: TorService? = null
    private var bound = false
    private var registered = false
    private var startedService = false
    private var launched = false
    private var observedTorOn = false
    private var latestStatus: String? = null
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { fail("Tor could not establish a circuit in time.") }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                TorService.ACTION_STATUS -> {
                    latestStatus = intent.getStringExtra(TorService.EXTRA_STATUS)
                    if (latestStatus == TorService.STATUS_ON) {
                        observedTorOn = true
                        launchIfReady()
                    }
                    // TorService reports OFF while it is being created, before its daemon
                    // and control listener have started. Treating that initial state as a
                    // disconnect used to close this screen before Tor had a chance to boot.
                    // The startup timeout and ACTION_ERROR still fail closed; after a circuit
                    // has been established, a later OFF/STOPPING is a real disconnect.
                    if (observedTorOn &&
                        (latestStatus == TorService.STATUS_OFF || latestStatus == TorService.STATUS_STOPPING)) {
                        fail("Tor stopped before the private window opened.")
                    }
                }
                TorService.ACTION_ERROR -> fail(intent.getStringExtra(Intent.EXTRA_TEXT) ?: "Tor could not connect.")
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            tor = (binder as? TorService.LocalBinder)?.service
            launchIfReady()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            tor = null
            fail("Tor service disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        ContextCompat.registerReceiver(this, receiver, IntentFilter().apply {
            addAction(TorService.ACTION_STATUS)
            addAction(TorService.ACTION_ERROR)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        val service = Intent(this, TorService::class.java)
        try {
            bound = bindService(service, connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Could not bind to Tor service" }
            startService(service)
            startedService = true
            status.text = "Building a Tor circuit…"
            handler.postDelayed(timeout, 150_000L)
        } catch (error: Exception) {
            fail("Could not start Tor: ${error.message ?: "unknown error"}")
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setBackgroundColor(0xff111519.toInt())
        }
        root.addView(TextView(this).apply {
            text = "✦  ASTER · PRIVATE ROUTE"
            textSize = 13f
            setTextColor(0xff70e0bd.toInt())
            gravity = Gravity.CENTER
        })
        root.addView(ProgressBar(this).apply { isIndeterminate = true },
            LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(26); bottomMargin = dp(18) })
        status = TextView(this).apply {
            text = "Starting Tor…"
            textSize = 17f
            setTextColor(0xffeef4f2.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(status)
        root.addView(TextView(this).apply {
            text = "The Chromium Tor window opens only after the circuit is ready."
            textSize = 13f
            setTextColor(0xffa8b5b4.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(20))
        })
        root.addView(Button(this).apply { text = "Cancel"; setOnClickListener { fail(null) } })
        setContentView(root)
    }

    private fun launchIfReady() {
        if (launched || latestStatus != TorService.STATUS_ON) return
        val socksPort = tor?.socksPort ?: return
        if (socksPort !in 1..65535) return
        launched = true
        handler.removeCallbacks(timeout)
        startActivity(Intent(this, ChromiumTorActivity::class.java)
            .putExtra(ChromiumTorActivity.EXTRA_SOCKS_PORT, socksPort)
            .putExtra(ChromiumTorActivity.EXTRA_URL, intent.getStringExtra(EXTRA_URL)))
        finish()
    }

    private fun fail(message: String?) {
        if (isFinishing || launched) return
        handler.removeCallbacks(timeout)
        message?.let {
            status.text = "$it\n\nNo page was opened outside Tor."
            Toast.makeText(this, it, Toast.LENGTH_LONG).show()
        }
        window.decorView.postDelayed({ if (!isFinishing) finish() }, if (message == null) 0L else 1_800L)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        if (registered) runCatching { unregisterReceiver(receiver) }
        if (bound) runCatching { unbindService(connection) }
        if (startedService && !launched) runCatching { stopService(Intent(this, TorService::class.java)) }
        super.onDestroy()
    }

    companion object { const val EXTRA_URL = "app.aster.browser.chromiumtor.URL" }
}
