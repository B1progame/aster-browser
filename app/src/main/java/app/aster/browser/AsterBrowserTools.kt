package app.aster.browser

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/** Small, explicit tool surface exposed to the local model. */
internal class AsterBrowserTools(
    private val findTabs: (String) -> List<Map<String, String>>,
    private val openTab: (String) -> String,
    private val searchWeb: (String) -> String,
    private val inspectPrivacy: () -> Map<String, String>
) : ToolSet {
    @Tool(description = "Search the user's open regular browser tabs by page title, host, or URL. Returns matching tabs only; it does not inspect page content.")
    fun searchOpenTabs(
        @ToolParam(description = "Words from the page title, website host, or URL to find") query: String
    ): List<Map<String, String>> = findTabs(query.take(160))

    @Tool(description = "Open an HTTP or HTTPS website in a new regular browser tab. Do not use this to open local files, app links, or private tabs.")
    fun openWebsiteInNewTab(
        @ToolParam(description = "A complete HTTP or HTTPS URL") url: String
    ): String = openTab(url.take(2048))

    @Tool(description = "Search the web using the user's chosen search engine and open the results in a new browser tab.")
    fun searchTheWeb(
        @ToolParam(description = "The search terms") query: String
    ): String = searchWeb(query.take(240))

    @Tool(description = "Read the current regular page's privacy configuration, including HTTPS and whether this site has a tracking-protection exception. It does not scan page content or promise that every ad or tracker is blocked.")
    fun inspectCurrentPagePrivacy(): Map<String, String> = inspectPrivacy()
}
