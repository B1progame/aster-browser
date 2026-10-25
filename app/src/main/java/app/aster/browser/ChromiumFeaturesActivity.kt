package app.aster.browser

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import com.google.ai.edge.litertlm.tool

/** Chromium-owned Aster feature surface. It never starts Gecko's MainActivity. */
class ChromiumFeaturesActivity : Activity() {
    private val bg = Color.rgb(17, 21, 25)
    private val card = Color.rgb(31, 37, 42)
    private val raised = Color.rgb(43, 51, 57)
    private val ink = Color.rgb(240, 244, 245)
    private val muted = Color.rgb(162, 174, 178)
    private val accent = Color.rgb(103, 220, 185)
    private lateinit var content: LinearLayout
    private var harness: LocalAiHarness? = null
    private var transcript: LinearLayout? = null
    private var input: EditText? = null
    private var status: TextView? = null
    private var includePageContext: CheckBox? = null
    private var pageContext: AiPageContext? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showFeature(intent.getStringExtra(EXTRA_FEATURE).orEmpty())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showFeature(intent.getStringExtra(EXTRA_FEATURE).orEmpty())
    }

    private fun showFeature(feature: String) {
        if (feature == FEATURE_TOR) {
            startActivity(Intent(this, ChromiumTorBridgeActivity::class.java)
                .putExtra(ChromiumTorBridgeActivity.EXTRA_URL, intent.getStringExtra("aster_tor_url")))
            finish()
            return
        }
        harness?.close()
        harness = null
        val isChat = feature == FEATURE_AI
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            setBackgroundColor(bg)
        }
        if (isChat) {
            // Keep the composer in a fixed bottom row. Putting the weight-based transcript
            // inside the feature page's outer ScrollView made Android measure it at the full
            // screen height, so the IME covered the actual Send button on phones.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setContentView(content)
            ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
                // New Android versions draw edge-to-edge and may keep the decor at full
                // height while the IME overlays it. Reserve the IME inset inside this root
                // so the transcript yields space and the fixed composer remains tappable.
                val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                view.setPadding(dp(20), dp(18), dp(20), dp(22) + maxOf(imeBottom, navBottom))
                insets
            }
            ViewCompat.requestApplyInsets(content)
        } else {
            scroll.addView(content)
            setContentView(scroll)
        }
        val top = row()
        top.addView(text("✦", 20f, accent, true).also {
            it.gravity = Gravity.CENTER
            it.background = rounded(raised, 18)
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        val brand = column().apply {
            addView(text("ASTER  ·  CHROMIUM", 11f, accent, true))
            addView(text("Private by default", 12f, muted, false))
        }
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        top.addView(action("Close", "Close feature window") { finish() })
        content.addView(top)

        when (feature) {
            FEATURE_AI -> showAi()
            FEATURE_ISSUE -> showIssue()
            FEATURE_UPDATES -> showUpdates()
            FEATURE_TOOLS, "" -> showTools()
            FEATURE_PRIVACY -> showPrivacy()
            FEATURE_SHRED -> showShred()
            FEATURE_EXTENSIONS -> showExtensions()
            FEATURE_SETTINGS -> showSettings()
            FEATURE_REQUEST -> showFeatureRequest()
            else -> showTools()
        }
    }

    private fun showTools() {
        content.addView(text("Your Chromium tools", 26f, ink, true).apply { setPadding(0, dp(24), 0, dp(5)) })
        content.addView(text("These tools open in this Chromium build. Your Gecko tabs and profile are not used.", 14f, muted, false))
        toolCard("◉", "On-device AI", "Local assistant · model stays on this device") { showFeature(FEATURE_AI) }
        toolCard("◌", "Tor private window", "Separate temporary Chromium-based WebView profile") { showFeature(FEATURE_TOR) }
        toolCard("⌖", "Privacy controls", "Review Chromium privacy capabilities") { showFeature(FEATURE_PRIVACY) }
        toolCard("◇", "Extensions", "Open Chromium's extension manager") { showFeature(FEATURE_EXTENSIONS) }
        toolCard("⌫", "Clear site data", "Manage site data in Chromium settings") { showFeature(FEATURE_SHRED) }
        toolCard("✉", "Prepare issue report", "Review a safe Chromium issue draft") { showFeature(FEATURE_ISSUE) }
        toolCard("✉", "Feature request", "Prepare a request for Aster") { showFeature(FEATURE_REQUEST) }
        toolCard("↻", "Aster updates", "Check for the latest combined APK") { showFeature(FEATURE_UPDATES) }
        toolCard("⚙", "Chromium settings", "Open this engine's settings") { showFeature(FEATURE_SETTINGS) }
    }

    private fun showAi() {
        heading("Aster AI", "Local inference in the Chromium feature surface")
        pageContext = AiPageContext.create(
            intent.getStringExtra(EXTRA_PAGE_URL),
            intent.getStringExtra(EXTRA_PAGE_TITLE),
            intent.getStringExtra(EXTRA_PAGE_TEXT)
        )
        val page = pageContext
        includePageContext = CheckBox(this).apply {
            text = if (page == null) "No page text was shared by Chromium" else "Include this page in my prompts · ${page.title.ifBlank { Uri.parse(page.url).host.orEmpty() }}"
            setTextColor(if (page == null) muted else ink)
            isEnabled = page != null
            isChecked = false
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
        }
        content.addView(includePageContext)
        content.addView(text("Chromium asks before sharing page text. This switch controls whether each prompt includes it. The model runs on this device; page text is treated as untrusted input.", 12f, muted, false))
        val prefs = getSharedPreferences("aster", MODE_PRIVATE)
        val model = sequenceOf(
            prefs.getString(SHARED_MODEL_KEY, null),
            prefs.getString(CHROMIUM_MODEL_KEY, null)
        ).filterNotNull().mapNotNull { runCatching { File(it) }.getOrNull() }
            .firstOrNull { it.isFile && it.length() > 1024 * 1024 }
        status = text(if (model == null) "Choose a .litertlm model to begin. Inference runs on-device." else "Model available · ${model.name}", 13f, muted, false)
        content.addView(status)
        content.addView(action("Choose local model", "Import local LiteRT LM model") { chooseModel() })
        val scroll = ScrollView(this).apply { isFillViewport = true; background = rounded(card, 18) }
        transcript = column().apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        scroll.addView(transcript)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(14) })
        val composer = row().apply { setPadding(0, dp(12), 0, 0) }
        input = EditText(this).apply {
            hint = "Ask Aster…"; setHintTextColor(muted); setTextColor(ink); textSize = 15f
            setSingleLine(true); imeOptions = EditorInfo.IME_ACTION_SEND
            background = rounded(card, 18); setPadding(dp(14), dp(10), dp(14), dp(10))
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) { sendAiMessage(); true } else false
            }
        }
        composer.addView(input, LinearLayout.LayoutParams(0, dp(54), 1f))
        composer.addView(action("Send", "Send prompt to local model") { sendAiMessage() })
        content.addView(composer)
        if (model != null) loadModel(model)
    }

    private fun showIssue() {
        heading("Prepare an issue", "Choose what to share")
        content.addView(text("No page URL, cookies, or browsing history is attached automatically.", 14f, muted, false))
        val details = EditText(this).apply {
            hint = "Describe what happened"; setHintTextColor(muted); setTextColor(ink)
            minLines = 4; gravity = Gravity.TOP; background = rounded(card, 16); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(details, LinearLayout.LayoutParams(-1, dp(150)).apply { topMargin = dp(16) })
        content.addView(action("Continue to GitHub", "Open Aster issue draft") {
            val body = Uri.encode(details.text.toString())
            launchChromiumUrl("https://github.com/B1progame/aster-browser/issues/new?body=$body")
        })
    }

    private fun showPrivacy() {
        val currentUrl = currentChromiumUrl()?.toString().orEmpty()
        val host = currentChromiumUrl()?.host.orEmpty()
        heading("Chromium privacy", if (host.isBlank()) "Controls apply to Chromium's regular profile" else "Native protection for $host")
        content.addView(text("uBlock Origin runs in Chromium and supplies filter-list ad and tracker blocking. Its own site switch is available from the uBlock toolbar action. This panel also controls Chromium's native intrusive-ad setting for the current site.", 14f, muted, false))
        if (host.isNotBlank()) {
            val nativeBlocked = ChromiumProfileActions.intrusiveAdsBlocked(currentUrl)
            val nativeAds = CheckBox(this).apply {
                text = "Block intrusive ads on $host"; setTextColor(ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = nativeBlocked == true; isEnabled = nativeBlocked != null
                setOnCheckedChangeListener { _, checked ->
                    isEnabled = false
                    val result = ChromiumProfileActions.setIntrusiveAdsBlocked(currentUrl, checked)
                    isEnabled = true
                    if (!result.ok) isChecked = !checked
                    Toast.makeText(this@ChromiumFeaturesActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            content.addView(nativeAds, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            if (nativeBlocked == null) content.addView(text("This Chromium build does not expose its site content-settings bridge in this activity.", 12f, muted, false))
            val cookiesBlocked = ChromiumProfileActions.siteCookiesBlocked(currentUrl)
            val cookieControl = CheckBox(this).apply {
                text = "Block cookies for $host · may sign you out"; setTextColor(ink)
                buttonTintList = android.content.res.ColorStateList.valueOf(accent)
                isChecked = cookiesBlocked == true; isEnabled = cookiesBlocked != null
                setOnCheckedChangeListener { _, checked ->
                    isEnabled = false
                    val result = ChromiumProfileActions.setSiteCookiesBlocked(currentUrl, checked)
                    isEnabled = true
                    if (!result.ok) isChecked = !checked
                    Toast.makeText(this@ChromiumFeaturesActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
            content.addView(cookieControl, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        } else content.addView(text("Open a regular website in Chromium to enable the per-site native control.", 13f, muted, false).apply { setPadding(0, dp(14), 0, dp(4)) })
        content.addView(action("Open Chromium privacy settings", "Open Chromium privacy settings") { launchChromiumUrl("chrome://settings/privacy") }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
        content.addView(action("Manage uBlock Origin", "Open Chromium extension controls") { launchChromiumUrl("chrome://extensions") }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
    }

    private fun showShred() {
        val url = currentChromiumUrl()
        val host = url?.host
        heading("Website shredder", if (host == null) "Clear data from Chromium's profile" else "Clear Chromium data for $host")
        content.addView(text("These actions use Chromium's own profile storage and never touch Gecko. Site-data clearing affects this host and can sign you out. History, cache, and cookies can also be cleared from the regular profile below.", 14f, muted, false))
        val siteInput = EditText(this).apply {
            hint = "Website host, e.g. example.com"; setHintTextColor(muted); setTextColor(ink)
            setSingleLine(true); background = rounded(card, 16); setPadding(dp(12), dp(12), dp(12), dp(12))
            setText(host.orEmpty())
        }
        content.addView(siteInput, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(14) })
        val siteStatus = text("Ready", 13f, muted, false)
        content.addView(siteStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        content.addView(action("Shred this site's data", "Clear Chromium site data for the entered host") {
            val safeHost = siteInput.text.toString().trim().lowercase(java.util.Locale.ROOT)
                .removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
            if (safeHost.isBlank()) { siteStatus.text = "Enter a website host first."; return@action }
            AlertDialog.Builder(this)
                .setTitle("Clear data for $safeHost?")
                .setMessage("Chromium will delete supported cookies and stored site data for this host. The action cannot be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear site data") { _, _ ->
                    siteStatus.text = "Clearing Chromium site data…"
                    ChromiumProfileActions.clearSiteData(this, safeHost) { result ->
                        siteStatus.text = result.message
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }.show()
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })

        content.addView(text("Clear all time · choose data types", 16f, ink, true).apply { setPadding(0, dp(24), 0, dp(4)) })
        val clearSite = check("Cookies and site data", true)
        val clearCache = check("Cached images and files", false)
        val clearHistory = check("Browsing history", false)
        content.addView(clearSite); content.addView(clearCache); content.addView(clearHistory)
        val allStatus = text("", 13f, muted, false).apply { setPadding(0, dp(8), 0, 0) }
        content.addView(allStatus)
        content.addView(action("Clear selected Chromium data", "Clear selected regular Chromium browsing data") {
            val types = buildList {
                if (clearSite.isChecked) add("SITE_DATA")
                if (clearCache.isChecked) add("CACHE")
                if (clearHistory.isChecked) add("HISTORY")
            }
            if (types.isEmpty()) { allStatus.text = "Select at least one data type."; return@action }
            AlertDialog.Builder(this)
                .setTitle("Clear selected Chromium data?")
                .setMessage("This permanently clears the selected items from Chromium's regular profile for all time.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear selected data") { _, _ ->
                    allStatus.text = "Clearing Chromium data…"
                    ChromiumProfileActions.clearAllData(this, types) { result ->
                        allStatus.text = result.message
                        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    }
                }.show()
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(10) })
    }

    private fun check(label: String, checked: Boolean) = CheckBox(this).apply {
        text = label; isChecked = checked; setTextColor(ink)
        buttonTintList = android.content.res.ColorStateList.valueOf(accent)
    }

    private fun currentChromiumUrl(): Uri? {
        val extraUrl = intent.getStringExtra(EXTRA_PAGE_URL)
        val urls = intent.getStringArrayListExtra("aster_tabs")
        val activeIndex = intent.getIntExtra("aster_active_tab", 0)
        val candidate = extraUrl ?: urls?.getOrNull(activeIndex)
        val parsed = candidate?.let(Uri::parse) ?: return null
        if ((parsed.scheme != "https" && parsed.scheme != "http") || parsed.host.isNullOrBlank()) return null
        return parsed
    }

    private fun showExtensions() {
        heading("Chromium extensions", "Managed by the Chromium engine")
        content.addView(text("Aster does not translate extension packages here. Installed extensions run only when supported and enabled by this Chromium build.", 14f, muted, false))
        content.addView(action("Open extensions", "Open Chromium extensions page") { launchChromiumUrl("chrome://extensions" ) })
    }

    private fun showSettings() {
        heading("Chromium settings", "Engine-local controls")
        content.addView(action("Browser settings", "Open Chromium settings") { launchChromiumUrl("chrome://settings") })
        content.addView(action("Privacy settings", "Open Chromium privacy settings") { launchChromiumUrl("chrome://settings/privacy") })
        content.addView(action("Extensions", "Open Chromium extensions") { launchChromiumUrl("chrome://extensions") })
    }

    private fun showFeatureRequest() {
        heading("Feature request", "Suggest an improvement")
        content.addView(text("Feature requests open as a draft. Review it before submitting.", 14f, muted, false))
        val idea = EditText(this).apply {
            hint = "What should Aster do?"; setHintTextColor(muted); setTextColor(ink)
            minLines = 3; background = rounded(card, 16); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        content.addView(idea, LinearLayout.LayoutParams(-1, dp(130)).apply { topMargin = dp(14) })
        content.addView(action("Create draft", "Open feature request draft") {
            val body = Uri.encode(idea.text.toString())
            launchChromiumUrl("https://github.com/B1progame/aster-browser/issues/new?labels=enhancement&body=$body")
        })
    }

    private fun showUpdates() {
        heading("Aster updates", "One combined Android app with both engines")
        val updateStatus = text("Ready to check GitHub releases.", 13f, muted, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12)); background = rounded(card, 16)
        }
        content.addView(updateStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        content.addView(action("Check for updates", "Check the latest combined Aster APK") {
            AsterAppUpdater.check(this, updateStatus)
        }, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(12) })
    }

    private fun chooseModel() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
        }, REQUEST_MODEL)
    }

    @Deprecated("Android document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_MODEL || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val modelDirectory = (getExternalFilesDir("ai-models") ?: File(filesDir, "ai-models")).apply { mkdirs() }
        val target = File(modelDirectory, "aster-imported-model.litertlm")
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(target).use { output -> source.copyTo(output) }
            } ?: error("Could not open selected model file")
            getSharedPreferences("aster", MODE_PRIVATE).edit()
                .putString(SHARED_MODEL_KEY, target.absolutePath)
                .putString(CHROMIUM_MODEL_KEY, target.absolutePath)
                .apply()
            loadModel(target)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not import model: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadModel(file: File) {
        status?.text = "Loading local model…"
        val ai = LocalAiHarness(this).also { harness = it }
        ai.load(file, listOf(tool(createChromiumBrowserTools())), object : LocalAiHarness.LoadListener {
            override fun onStatus(statusText: String) { status?.text = statusText }
            override fun onReady() { status?.text = "Ready · on-device inference"; addBubble("Aster is ready. Prompts stay on this device.", false) }
            override fun onError(error: String) { status?.text = "Model error · $error" }
        })
    }

    private fun sendAiMessage() {
        val prompt = input?.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) return
        val ai = harness
        if (ai == null) { Toast.makeText(this, "Load a local model first", Toast.LENGTH_SHORT).show(); return }
        input?.text?.clear()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input?.windowToken, 0)
        addBubble(prompt, true)
        val response = text("", 14f, ink, false).apply { setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(card, 14) }
        transcript?.addView(response, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val localPrompt = if (includePageContext?.isChecked == true) {
            val context = pageContext?.asPromptBlock()
            if (context == null) prompt else "$prompt\n\n$context"
        } else prompt
        ai.send(localPrompt, object : LocalAiHarness.ReplyListener {
            override fun onChunk(text: String) { response.append(text) }
            override fun onDone() {}
            override fun onError(error: String) { response.text = "AI error: $error" }
        })
    }

    /** Chromium-only AI tools. Actions return to this Chromium activity, never Gecko. */
    private fun createChromiumBrowserTools() = AsterBrowserTools(
        findTabs = { rawQuery ->
            val query = rawQuery.trim().lowercase(java.util.Locale.ROOT)
            val urls = intent.getStringArrayListExtra("aster_tabs").orEmpty()
            val titles = intent.getStringArrayListExtra("aster_tab_titles").orEmpty()
            val activeIndex = intent.getIntExtra("aster_active_tab", 0)
            urls.indices.asSequence()
                .filter { i -> query.isBlank() || urls[i].lowercase().contains(query) || titles.getOrNull(i).orEmpty().lowercase().contains(query) }
                .take(30)
                .map { i -> mapOf("title" to titles.getOrNull(i).orEmpty().take(160), "url" to urls[i].take(2048), "active" to (i == activeIndex).toString()) }
                .toList()
        },
        openTab = { rawUrl ->
            val uri = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull()
            if (uri == null || uri.scheme !in setOf("https", "http") || uri.host.isNullOrBlank() || !uri.userInfo.isNullOrBlank()) {
                "I can only open a complete HTTP or HTTPS website URL."
            } else runOnUiThreadAndWait {
                launchChromiumUrl(uri.toString())
                "Opened ${uri.host} in Chromium. The page content has not been read unless you separately enable page sharing in the AI screen."
            }
        },
        searchWeb = { rawQuery ->
            val query = rawQuery.trim().take(240)
            if (query.isBlank()) "Please provide search terms." else runOnUiThreadAndWait {
                val engineName = getSharedPreferences("aster", MODE_PRIVATE).getString("engine", "DuckDuckGo") ?: "DuckDuckGo"
                val base = when (engineName) {
                    "Google" -> "https://www.google.com/search?q="
                    "Bing" -> "https://www.bing.com/search?q="
                    "Brave Search" -> "https://search.brave.com/search?q="
                    else -> "https://duckduckgo.com/?q="
                }
                launchChromiumUrl(base + Uri.encode(query))
                "Opened $engineName results in Chromium for: $query. I did not fetch or read the results."
            }
        },
        inspectPrivacy = {
            val page = currentChromiumUrl()
            if (page == null) mapOf("status" to "Open a regular HTTP or HTTPS page in Chromium to inspect its configured protections.")
            else mapOf(
                "engine" to "Chromium",
                "host" to page.host.orEmpty(),
                "https" to (page.scheme == "https").toString(),
                "nativeIntrusiveAdBlocking" to ChromiumProfileActions.intrusiveAdsBlocked(page.toString())?.toString().orEmpty().ifBlank { "unavailable" },
                "extensionFiltering" to "uBlock Origin provides separate filter-list ad and tracker blocking; its live per-site state is not exposed to Aster.",
                "limitations" to "This reads site configuration; it does not scan page requests or guarantee every ad and tracker is blocked."
            )
        },
        readSharedPage = {
            if (includePageContext?.isChecked != true) {
                mapOf("status" to "The user has not enabled sharing this page with the local assistant.")
            } else pageContext?.let { mapOf("url" to it.url, "title" to it.title, "text" to it.text) }
                ?: mapOf("status" to "No readable page text was shared. Start Aster AI from a regular Chromium page and confirm sharing first.")
        }
    )

    private fun <T> runOnUiThreadAndWait(action: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action()
        val task = FutureTask<T> { action() }
        runOnUiThread { task.run() }
        return task.get(20, TimeUnit.SECONDS)
    }

    private fun addBubble(message: String, user: Boolean) {
        transcript?.addView(text(message, 14f, ink, false).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8)); background = rounded(if (user) raised else card, 14)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    private fun launchChromiumUrl(url: String) {
        // ChromeTabbedActivity is resolved at runtime so this shared AAR can still
        // compile independently from the Chromium source tree.
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).setClassName(
            packageName, "org.chromium.chrome.browser.ChromeTabbedActivity"
        ).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun heading(title: String, subtitle: String) {
        content.addView(text(title, 25f, ink, true).apply { setPadding(0, dp(24), 0, dp(4)) })
        content.addView(text(subtitle, 14f, muted, false))
    }

    private fun toolCard(icon: String, title: String, subtitle: String, click: () -> Unit) {
        val cardView = row().apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(13), dp(12), dp(14), dp(12)); background = rounded(card, 18)
            isClickable = true; isFocusable = true; setOnClickListener { click() }
        }
        cardView.addView(text(icon, 19f, accent, true).apply { gravity = Gravity.CENTER; background = rounded(raised, 14) }, LinearLayout.LayoutParams(dp(44), dp(44)))
        val labels = column().apply { addView(text(title, 15f, ink, true)); addView(text(subtitle, 12f, muted, false)) }
        cardView.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(12) })
        cardView.addView(text("›", 24f, accent, true))
        content.addView(cardView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun text(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun action(value: String, description: String, click: () -> Unit) = TextView(this).apply {
        text = value; contentDescription = description; gravity = Gravity.CENTER; setTextColor(ink); textSize = 14f
        setPadding(dp(14), 0, dp(14), 0); background = rounded(raised, 14); isClickable = true; isFocusable = true
        setOnClickListener { click() }
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() { harness?.close(); super.onDestroy() }

    companion object {
        const val EXTRA_FEATURE = "aster_feature"
        const val EXTRA_PAGE_URL = "aster_page_url"
        const val EXTRA_PAGE_TITLE = "aster_page_title"
        const val EXTRA_PAGE_TEXT = "aster_page_text"
        const val FEATURE_AI = "ai"
        const val FEATURE_TOR = "tor"
        const val FEATURE_TOOLS = "tools"
        const val FEATURE_ISSUE = "issue"
        const val FEATURE_PRIVACY = "privacy"
        const val FEATURE_SHRED = "shred"
        const val FEATURE_EXTENSIONS = "extensions"
        const val FEATURE_SETTINGS = "settings"
        const val FEATURE_REQUEST = "feature"
        const val FEATURE_UPDATES = "updates"
        const val REQUEST_MODEL = 7201
        private const val SHARED_MODEL_KEY = "local_ai_model_path"
        private const val CHROMIUM_MODEL_KEY = "chromium_local_ai_model_path"
    }
}
