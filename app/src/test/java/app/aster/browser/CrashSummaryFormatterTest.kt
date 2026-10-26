package app.aster.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashSummaryFormatterTest {
    @Test fun excludesExceptionMessageAndNonAppFrames() {
        val summary = CrashSummaryFormatter.formatUncaught(
            "java.lang.IllegalStateException",
            listOf(
                "app.aster.browser.MainActivity.onCreate(MainActivity.kt:42)",
                "com.vendor.Library.run(Library.kt:12)"
            ),
            "app.aster.browser"
        )
        assertTrue(summary.contains("IllegalStateException"))
        assertTrue(summary.contains("MainActivity.onCreate"))
        assertFalse(summary.contains("vendor"))
        assertFalse(summary.contains("https://"))
        assertFalse(summary.contains("secret user message"))
    }

    @Test fun acceptsOnlySafeFrameCharactersAndBoundsOutput() {
        val summary = CrashSummaryFormatter.formatUncaught(
            "x.Exception?token=private",
            listOf("app.aster.browser.SomeClass.run(SomeClass.kt:5)".repeat(30)),
            "app.aster.browser"
        )
        assertTrue(summary.length <= CrashSummaryFormatter.MAX_CHARS)
        assertFalse(summary.contains("token=private"))
        assertFalse(summary.contains("?"))
    }

    @Test fun issueTextRedactsLinksCredentialsAndEmails() {
        val safe = CrashSummaryFormatter.sanitizeIssueText(
            "Page https://example.com/path?token=private, token=abcd1234 user@example.com"
        )
        assertFalse(safe.contains("example.com"))
        assertFalse(safe.contains("private"))
        assertFalse(safe.contains("abcd1234"))
        assertFalse(safe.contains("user@example.com"))
        assertTrue(safe.contains("[URL redacted]"))
    }
}
