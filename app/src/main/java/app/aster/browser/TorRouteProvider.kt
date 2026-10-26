package app.aster.browser

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import org.torproject.jni.TorService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Same-UID IPC bridge that returns only a display-safe Tor circuit snapshot to the isolated window. */
class TorRouteProvider : ContentProvider() {
    @Volatile private var tor: TorService? = null
    @Volatile private var bindStarted = false
    private var connected = CountDownLatch(1)
    private val countryCache = HashMap<String, String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: android.content.ComponentName?, binder: IBinder?) {
            tor = (binder as? TorService.LocalBinder)?.service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: android.content.ComponentName?) {
            tor = null
            connected = CountDownLatch(1)
            bindStarted = false
        }
    }

    override fun onCreate(): Boolean = context != null

    @Synchronized private fun service(): TorService? {
        tor?.let { return it }
        val ctx = context ?: return null
        if (!bindStarted) {
            bindStarted = true
            connected = CountDownLatch(1)
            val bound = runCatching {
                ctx.bindService(Intent(ctx, TorService::class.java), connection, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!bound) return null
        }
        runCatching { connected.await(2, TimeUnit.SECONDS) }
        return tor
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != context?.applicationInfo?.uid) return Bundle().apply { putBoolean("ready", false) }
        if (method != METHOD_SNAPSHOT) return Bundle().apply { putBoolean("ready", false) }
        val service = service() ?: return Bundle().apply { putBoolean("ready", false); putString("message", "Tor control connection is starting") }
        val circuitStatus = runCatching { service.getInfo("circuit-status") }.getOrNull()
        val streamStatus = runCatching { service.getInfo("stream-status") }.getOrNull()
        val route = TorCircuitSnapshotParser.select(circuitStatus, streamStatus, arg)
            ?: return Bundle().apply { putBoolean("ready", false); putString("message", "Tor has not built a general browsing circuit yet") }
        val rendered = ArrayList<String>(route.relays.size)
        synchronized(countryCache) {
            route.relays.forEachIndexed { index, relay ->
                val country = countryCache.getOrPut(relay.fingerprint) {
                    lookupCountry(service, relay.fingerprint)
                }
                relay.country = country
                val role = relay.role(index, route.relays.size)
                rendered += listOf(role, relay.nickname, country).joinToString("|")
            }
        }
        return Bundle().apply {
            putBoolean("ready", true)
            putBoolean("siteCircuit", route.siteCircuit)
            putStringArrayList("relays", rendered)
        }
    }

    private fun lookupCountry(service: TorService, fingerprint: String): String {
        if (!fingerprint.matches(Regex("[A-F0-9]{40}"))) return ""
        val status = runCatching { service.getInfo("ns/id/$fingerprint") }.getOrNull().orEmpty()
        val router = status.lineSequence().firstOrNull { it.startsWith("r ") } ?: return ""
        val ip = TorCircuitSnapshotParser.routerAddress(router)
        if (ip.isBlank()) return ""
        val country = runCatching { service.getInfo("ip-to-country/$ip") }.getOrNull()?.trim().orEmpty()
        return if (country.matches(Regex("[A-Z]{2}")) && country != "??") country else ""
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun shutdown() {
        if (bindStarted) runCatching { context?.unbindService(connection) }
        bindStarted = false
        tor = null
        super.shutdown()
    }

    companion object { const val METHOD_SNAPSHOT = "aster.tor.snapshot" }
}
