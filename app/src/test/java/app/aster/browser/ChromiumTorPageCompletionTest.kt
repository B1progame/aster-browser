package app.aster.browser

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChromiumTorPageCompletionTest {
    @Test
    fun initialBlankDocumentCannotCompleteRequestedPage() {
        assertFalse(ChromiumTorActivity.isExpectedPageCompletion("about:blank", "example.org"))
        assertFalse(ChromiumTorActivity.isExpectedPageCompletion(null, "example.org"))
    }

    @Test
    fun onlyExpectedHttpSiteCanCompleteNavigation() {
        assertTrue(ChromiumTorActivity.isExpectedPageCompletion("https://EXAMPLE.org/path", "example.org"))
        assertFalse(ChromiumTorActivity.isExpectedPageCompletion("https://other.example/", "example.org"))
        assertFalse(ChromiumTorActivity.isExpectedPageCompletion("file:///tmp/page", "example.org"))
    }
}
