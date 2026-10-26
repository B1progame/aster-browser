package app.aster.browser

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Process
import java.util.concurrent.atomic.AtomicBoolean

/** Stores only a small, user-selectable crash category and app-owned frame summary. */
object CrashSummaryFormatter {
    const val MAX_CHARS = 900
    private val safeFrame = Regex("^[A-Za-z0-9_.$():#<>-]{1,220}$")

    fun formatUncaught(exceptionType: String, frames: List<String>, appPackage: String): String {
        val type = exceptionType.substringBefore('?').substringAfterLast('.').filter { it.isLetterOrDigit() || it == '_' }
            .take(80).ifBlank { "UnknownException" }
        val prefix = "$appPackage."
        val appFrames = frames.asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.takeWhile { ch -> ch != '?' && ch != '\n' && ch != '\r' }.take(220) }
            .filter { safeFrame.matches(it) }
            .distinct().take(5).toList()
        return buildString {
            append("Java crash: ").append(type)
            appFrames.forEach { append("\n- ").append(it) }
        }.take(MAX_CHARS)
    }

    fun formatProcessExit(category: String): String =
        "Previous process ended: ${category.filter { it.isLetterOrDigit() || it == ' ' || it == '-' }.take(80)}"
            .take(MAX_CHARS)

    fun sanitizeIssueText(raw: String): String {
        var safe = raw.take(2400)
        safe = Regex("(?i)\\b(bearer\\s+)[a-z0-9._~+/=-]{8,}").replace(safe, "$1[redacted]")
        safe = Regex("(?i)\\b(password|passwd|token|secret|api[_-]?key|authorization)(\\s*[:=]\\s*)[^\\s,;]+")
            .replace(safe) { "${it.groupValues[1]}${it.groupValues[2]}[redacted]" }
        safe = Regex("(?i)https?://[^\\s<>]+")
            .replace(safe) { "[URL redacted]" }
        return Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
            .replace(safe, "[email redacted]")
    }
}

object CrashDiagnostics {
    private const val PREFS = "aster"
    private const val SUMMARY_KEY = "safe_crash_summary"
    private const val SUMMARY_TIME_KEY = "safe_crash_summary_time"
    private const val EXIT_TIME_KEY = "safe_crash_exit_time"
    private val initialized = AtomicBoolean(false)

    fun summary(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(SUMMARY_KEY, null)?.takeIf { it.isNotBlank() }?.take(CrashSummaryFormatter.MAX_CHARS)

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        recordLastSystemExit(appContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            recordUncaught(appContext, error)
            previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
        }
    }

    fun recordUncaught(context: Context, error: Throwable) {
        runCatching {
            val frames = error.stackTrace.asSequence()
                .filter { it.className.startsWith("${context.packageName}.") }
                .take(5).map { it.toString() }.toList()
            val safe = CrashSummaryFormatter.formatUncaught(error.javaClass.name, frames, context.packageName)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(SUMMARY_KEY, safe)
                .putLong(SUMMARY_TIME_KEY, System.currentTimeMillis()).commit()
        }
    }

    fun recordLastSystemExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
                .asSequence()
                .filter { it.timestamp > prefs.getLong(EXIT_TIME_KEY, 0L) }
                .toList()
            val latestExit = exits.maxOfOrNull { it.timestamp } ?: return
            val failure = exits.asSequence()
                .filter { it.reason in setOf(
                    ApplicationExitInfo.REASON_CRASH,
                    ApplicationExitInfo.REASON_CRASH_NATIVE,
                    ApplicationExitInfo.REASON_ANR,
                    ApplicationExitInfo.REASON_LOW_MEMORY
                ) }
                .maxByOrNull { it.timestamp }
            val edit = prefs.edit().putLong(EXIT_TIME_KEY, latestExit)
            if (failure != null) {
                val summaryTime = prefs.getLong(SUMMARY_TIME_KEY, 0L)
                // A Java handler writes its private frame summary immediately before Android
                // records the same process exit. Keep that better report instead of replacing
                // it with the generic system category.
                if (failure.timestamp - summaryTime > 5_000L) {
                    val category = when (failure.reason) {
                        ApplicationExitInfo.REASON_CRASH -> "Java crash"
                        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
                        ApplicationExitInfo.REASON_ANR -> "app not responding"
                        else -> "low memory termination"
                    }
                    edit.putString(SUMMARY_KEY, CrashSummaryFormatter.formatProcessExit(category))
                        .putLong(SUMMARY_TIME_KEY, failure.timestamp)
                }
            }
            edit.apply()
        }
    }
}

class AsterCrashInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let(CrashDiagnostics::initialize)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
