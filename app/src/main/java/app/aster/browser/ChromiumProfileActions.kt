package app.aster.browser

import android.app.Activity
import android.net.Uri
import java.lang.reflect.Proxy

/**
 * Thin runtime bridge to APIs already shipped in the combined Chromium APK.
 * The Gecko-only app can compile without Chromium classes; calls fail visibly
 * when it is not running inside the combined Chromium package.
 */
internal object ChromiumProfileActions {
    data class Result(val ok: Boolean, val message: String)

    fun clearSiteData(activity: Activity, host: String, callback: (Result) -> Unit) {
        requireSafeHost(host)?.let { callback(Result(false, it)); return }
        runCatching {
            val profileClass = chromiumClass("org.chromium.chrome.browser.profiles.Profile")
            val profile = profileClass.getMethod("getLastUsedRegularProfile").invoke(null)
                ?: error("Chromium's regular profile is not ready")
            val callbackClass = chromiumClass("org.chromium.base.Callback")
            val bridgeClass = chromiumClass("org.chromium.chrome.browser.browsing_data.BrowsingDataBridge")
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val modelCallback = Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onResult" -> {
                        val model = args?.firstOrNull() ?: error("Chromium returned no site-data model")
                        val modelClass = model.javaClass
                        val finish = Runnable {
                            if (!completed.compareAndSet(false, true)) return@Runnable
                            runCatching { modelClass.getMethod("destroy").invoke(model) }
                            activity.runOnUiThread {
                                callback(Result(true, "Chromium site data for $host was cleared."))
                            }
                        }
                        try {
                            modelClass.getMethod("removeBrowsingData", String::class.java, Runnable::class.java)
                                .invoke(model, host, finish)
                        } catch (error: Throwable) {
                            runCatching { modelClass.getMethod("destroy").invoke(model) }
                            throw error
                        }
                        null
                    }
                    "toString" -> "AsterBrowsingDataCallback"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            bridgeClass.getMethod("buildBrowsingDataModelFromDisk", profileClass, callbackClass)
                .invoke(null, profile, modelCallback)
        }.onFailure { error ->
            activity.runOnUiThread {
                callback(Result(false, "Could not clear Chromium site data: ${rootCause(error).message ?: error.javaClass.simpleName}"))
            }
        }
    }

    /** Clears only selected categories from the real Chromium profile, for all time. */
    fun clearAllData(activity: Activity, selectedTypes: List<String>, callback: (Result) -> Unit) {
        if (selectedTypes.isEmpty()) {
            callback(Result(false, "Select at least one data type."))
            return
        }
        runCatching {
            val profileClass = chromiumClass("org.chromium.chrome.browser.profiles.Profile")
            val profile = profileClass.getMethod("getLastUsedRegularProfile").invoke(null)
                ?: error("Chromium's regular profile is not ready")
            val bridgeClass = chromiumClass("org.chromium.chrome.browser.browsing_data.BrowsingDataBridge")
            val bridge = bridgeClass.getMethod("getForProfile", profileClass).invoke(null, profile)
                ?: error("Chromium browsing-data service is not ready")
            val typeClass = chromiumClass("org.chromium.chrome.browser.browsing_data.BrowsingDataType")
            val dataTypes = selectedTypes.map { typeClass.getField(it).getInt(null) }.toIntArray()
            val periodClass = chromiumClass("org.chromium.chrome.browser.browsing_data.TimePeriod")
            val allTime = periodClass.getField("ALL_TIME").getInt(null)
            val listenerClass = chromiumClass("org.chromium.chrome.browser.browsing_data.BrowsingDataBridge\$OnClearBrowsingDataListener")
            val completed = java.util.concurrent.atomic.AtomicBoolean(false)
            val listener = Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass)
            ) { proxy, method, args ->
                when (method.name) {
                    "onBrowsingDataCleared" -> {
                        if (completed.compareAndSet(false, true)) {
                            activity.runOnUiThread {
                                callback(Result(true, "Selected Chromium browsing data was cleared."))
                            }
                        }
                        null
                    }
                    "toString" -> "AsterBrowsingDataListener"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.firstOrNull()
                    else -> null
                }
            }
            bridgeClass.getMethod("clearBrowsingData", listenerClass, IntArray::class.java, Int::class.javaPrimitiveType!!)
                .invoke(bridge, listener, dataTypes, allTime)
        }.onFailure { error ->
            activity.runOnUiThread {
                callback(Result(false, "Could not clear Chromium browsing data: ${rootCause(error).message ?: error.javaClass.simpleName}"))
            }
        }
    }

    /** Adds/removes Chromium's native intrusive-ad content-setting exception for this origin. */
    fun setIntrusiveAdsBlocked(url: String, blocked: Boolean): Result =
        setSiteContentSetting(url, "ADS", blocked, "intrusive-ad blocking") { host, enabled ->
            if (enabled) "Chromium intrusive-ad blocking is enabled for $host."
            else "Chromium's default intrusive-ad setting is restored for $host."
        }

    /** Blocks this site's cookies as a Chromium profile exception to reduce cross-site tracking. */
    fun setSiteCookiesBlocked(url: String, blocked: Boolean): Result =
        setSiteContentSetting(url, "COOKIES", blocked, "cookie blocking") { host, enabled ->
            if (enabled) "Chromium blocks cookies for $host. This may sign you out."
            else "Chromium's default cookie setting is restored for $host."
        }

    private fun setSiteContentSetting(
        url: String,
        contentTypeName: String,
        blocked: Boolean,
        label: String,
        message: (String, Boolean) -> String
    ): Result {
        val parsed = Uri.parse(url)
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) {
            return Result(false, "Open a regular website before changing its protection.")
        }
        return runCatching {
            val profileClass = chromiumClass("org.chromium.chrome.browser.profiles.Profile")
            val profile = profileClass.getMethod("getLastUsedRegularProfile").invoke(null)
                ?: error("Chromium's regular profile is not ready")
            val bridgeClass = chromiumClass("org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge")
            val contentTypeClass = chromiumClass("org.chromium.components.content_settings.ContentSettingsType")
            val settingClass = chromiumClass("org.chromium.components.content_settings.ContentSetting")
            val gurlClass = chromiumClass("org.chromium.url.GURL")
            val origin = "${parsed.scheme}://${parsed.host}${if (parsed.port != -1) ":${parsed.port}" else ""}/"
            val primary = gurlClass.getConstructor(String::class.java).newInstance(origin)
            val secondary = gurlClass.getConstructor(String::class.java).newInstance(origin)
            val contentType = contentTypeClass.getField(contentTypeName).getInt(null)
            val value = settingClass.getField(if (blocked) "BLOCK" else "DEFAULT").getInt(null)
            bridgeClass.getMethod(
                "setContentSettingDefaultScope",
                chromiumClass("org.chromium.content_public.browser.BrowserContextHandle"),
                Int::class.javaPrimitiveType!!,
                gurlClass,
                gurlClass,
                Int::class.javaPrimitiveType!!
            ).invoke(null, profile, contentType, primary, secondary, value)
            Result(true, message(parsed.host.orEmpty(), blocked))
        }.getOrElse { error ->
            Result(false, "Could not update Chromium $label: ${rootCause(error).message ?: error.javaClass.simpleName}")
        }
    }

    fun intrusiveAdsBlocked(url: String): Boolean? = siteSettingBlocked(url, "ADS")

    fun siteCookiesBlocked(url: String): Boolean? = siteSettingBlocked(url, "COOKIES")

    private fun siteSettingBlocked(url: String, contentTypeName: String): Boolean? = runCatching {
        val parsed = Uri.parse(url)
        if (parsed.scheme !in setOf("http", "https") || parsed.host.isNullOrBlank()) return null
        val profileClass = chromiumClass("org.chromium.chrome.browser.profiles.Profile")
        val profile = profileClass.getMethod("getLastUsedRegularProfile").invoke(null) ?: return null
        val bridgeClass = chromiumClass("org.chromium.components.browser_ui.site_settings.WebsitePreferenceBridge")
        val contentTypeClass = chromiumClass("org.chromium.components.content_settings.ContentSettingsType")
        val settingClass = chromiumClass("org.chromium.components.content_settings.ContentSetting")
        val gurlClass = chromiumClass("org.chromium.url.GURL")
        val origin = "${parsed.scheme}://${parsed.host}${if (parsed.port != -1) ":${parsed.port}" else ""}/"
        val primary = gurlClass.getConstructor(String::class.java).newInstance(origin)
        val secondary = gurlClass.getConstructor(String::class.java).newInstance(origin)
        val value = bridgeClass.getMethod(
            "getContentSetting",
            chromiumClass("org.chromium.content_public.browser.BrowserContextHandle"),
            Int::class.javaPrimitiveType!!,
            gurlClass,
            gurlClass
        ).invoke(null, profile, contentTypeClass.getField(contentTypeName).getInt(null), primary, secondary) as Int
        value == settingClass.getField("BLOCK").getInt(null)
    }.getOrNull()

    private fun requireSafeHost(host: String): String? =
        if (host.isBlank() || host.length > 253 || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) {
            "Choose a valid website before clearing site data."
        } else null

    private fun chromiumClass(name: String): Class<*> = Class.forName(name)

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return current
    }
}
