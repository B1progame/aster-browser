package app.aster.browser

import java.util.concurrent.Executors

internal data class SavedBrowserTab(val id: Long, val url: String, val title: String)
internal data class BrowserSessionSnapshot(val tabs: List<SavedBrowserTab>, val active: Long)

/** Process-scoped so an Activity handoff cannot discard or reorder queued saves. */
internal object BrowserStatePersistence {
    // A replacement Activity must see the latest snapshot even if its disk
    // write has not finished yet. Neither cache contains private tabs.
    @Volatile var sessionSnapshot: BrowserSessionSnapshot? = null
    @Volatile var privacySnapshot: Map<String, Int>? = null
    private val writer = CoalescingExecutor(Executors.newSingleThreadExecutor { task ->
        Thread(task, "aster-state-writer").apply { isDaemon = true }
    })

    fun write(key: String, action: () -> Unit) {
        writer.submit(key) {
            runCatching(action).onFailure {
                // Never log the snapshot, URLs, or exception message.
                android.util.Log.w("AsterState", "Could not persist $key")
            }
        }
    }
}
