package app.aster.browser

import android.os.Build

/** Device defaults for Aster's flat browser window on Meta headsets. */
internal object AsterDevicePolicy {
    fun isMetaQuest3s(): Boolean {
        val vendor = listOf(Build.MANUFACTURER, Build.BRAND)
            .any { it.contains("oculus", ignoreCase = true) || it.contains("meta", ignoreCase = true) }
        val model = listOf(Build.DEVICE, Build.PRODUCT, Build.MODEL)
            .any {
                it.contains("panther", ignoreCase = true) ||
                    it.contains("quest 3s", ignoreCase = true) ||
                    it.contains("quest3s", ignoreCase = true)
            }
        return vendor && model
    }
}
