package app.aster.browser

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Checks the public Aster release and downloads the GeckoView APK for this device. */
object AsterAppUpdater {
    private const val RELEASE_API = "https://api.github.com/repos/B1progame/aster-browser/releases/latest"
    private const val RELEASE_DOWNLOAD_PREFIX = "https://github.com/B1progame/aster-browser/releases/download/"

    fun check(context: Context, status: TextView) {
        val appContext = context.applicationContext
        val installedVersion = BuildConfig.VERSION_NAME
        status.text = "Checking the latest Aster release…"
        Thread({
            val result = runCatching { checkAndQueueDownload(appContext, installedVersion) }
                .getOrElse { "Update check failed: ${it.message ?: "check your connection and try again"}" }
            status.post { if (status.isAttachedToWindow) status.text = result }
        }, "AsterGeckoUpdateCheck").apply {
            isDaemon = true
            start()
        }
    }

    private fun checkAndQueueDownload(context: Context, installedVersion: String): String {
        val release = fetchLatestRelease()
        val remoteVersion = release.getString("tag_name")
        require(Regex("v[0-9]+\\.[0-9]+\\.[0-9]+").matches(remoteVersion)) {
            "GitHub returned an invalid release tag"
        }

        val comparison = compareVersions(remoteVersion, installedVersion)
        if (comparison <= 0) {
            return if (comparison == 0) {
                "Aster $installedVersion is up to date."
            } else {
                "This GeckoView build ($installedVersion) is newer than the latest release $remoteVersion."
            }
        }

        val abi = androidAbiSuffix()
        val expectedName = "AsterBrowser-$remoteVersion-Gecko-Android-$abi.apk"
        val assets = release.getJSONArray("assets")
        val asset = (0 until assets.length())
            .map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name") == expectedName }
            ?: return "Aster $remoteVersion is available, but this release has no GeckoView $abi APK yet."

        val downloadUrl = asset.getString("browser_download_url")
        val uri = Uri.parse(downloadUrl)
        require(uri.scheme == "https" && uri.host == "github.com" &&
            downloadUrl.startsWith("$RELEASE_DOWNLOAD_PREFIX$remoteVersion/$expectedName")) {
            "GitHub returned an untrusted APK link"
        }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: error("Android Downloads is unavailable")
        val request = DownloadManager.Request(uri)
            .setTitle("Aster Browser $remoteVersion")
            .setDescription("Open the Android download notification to review and install the update")
            .setMimeType("application/vnd.android.package-archive")
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, expectedName)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        manager.enqueue(request)
        return "Downloading Aster $remoteVersion for GeckoView ($abi). Open the Android notification to install."
    }

    private fun fetchLatestRelease(): JSONObject {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 18_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Aster-Browser-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val responseCode = connection.responseCode
            check(responseCode == HttpURLConnection.HTTP_OK) {
                "GitHub returned HTTP $responseCode"
            }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            check(body.length <= 2_000_000) { "GitHub release response was too large" }
            val release = JSONObject(body)
            check(!release.optBoolean("draft", true) && !release.optBoolean("prerelease", true)) {
                "The latest Aster release is not stable"
            }
            return release
        } finally {
            connection.disconnect()
        }
    }

    private fun androidAbiSuffix(): String = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi ->
        when (abi) {
            "arm64-v8a" -> "arm64"
            "x86_64" -> "x86_64"
            else -> null
        }
    } ?: error("This device's CPU architecture is not supported")

    private fun compareVersions(first: String, second: String): Int {
        val left = first.removePrefix("v").split('.').map { it.toLongOrNull() ?: return 0 }
        val right = second.removePrefix("v").split('.').map { it.toLongOrNull() ?: return 0 }
        if (left.size != 3 || right.size != 3) return 0
        for (index in 0..2) {
            val comparison = left[index].compareTo(right[index])
            if (comparison != 0) return comparison
        }
        return 0
    }
}
