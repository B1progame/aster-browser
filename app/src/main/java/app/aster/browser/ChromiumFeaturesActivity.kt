package app.aster.browser

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream

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
            startActivity(Intent(this, ChromiumTorActivity::class.java)
                .putExtra(ChromiumTorActivity.EXTRA_URL, intent.getStringExtra("aster_tor_url")))
            finish()
            return
        }
        harness?.close()
        harness = null
        val scroll = ScrollView(this).apply { setBackgroundColor(bg); isFillViewport = true }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(22))
            setBackgroundColor(bg)
        }
        scroll.addView(content)
        setContentView(scroll)
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
        val model = getSharedPreferences("aster", MODE_PRIVATE).getString(CHROMIUM_MODEL_KEY, null)
            ?.let(::File)?.takeIf { it.isFile && it.length() > 1024 * 1024 }
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
            setSingleLine(false); imeOptions = EditorInfo.IME_ACTION_SEND
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
        heading("Chromium privacy", "Protection is configured by the Chromium engine")
        content.addView(text("This feature surface does not control Chromium's network stack. Use Chromium settings for site permissions and privacy controls; no Gecko settings are read or changed here.", 14f, muted, false))
        content.addView(action("Open Chromium privacy settings", "Open Chromium privacy settings") {
            launchChromiumUrl("chrome://settings/privacy")
        })
    }

    private fun showShred() {
        heading("Site data", "Clear Chromium site data")
        content.addView(text("To keep this action scoped to Chromium, open its built-in site data settings. This screen does not delete Gecko profile data.", 14f, muted, false))
        content.addView(action("Open Chromium site settings", "Open Chromium site settings") { launchChromiumUrl("chrome://settings/content/all") })
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
        val target = File(filesDir, "aster-chromium-model.litertlm")
        try {
            contentResolver.openInputStream(uri)?.use { source ->
                FileOutputStream(target).use { output -> source.copyTo(output) }
            } ?: error("Could not open selected model file")
            getSharedPreferences("aster", MODE_PRIVATE).edit().putString(CHROMIUM_MODEL_KEY, target.absolutePath).apply()
            loadModel(target)
        } catch (error: Exception) {
            Toast.makeText(this, "Could not import model: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun loadModel(file: File) {
        status?.text = "Loading local model…"
        val ai = LocalAiHarness(this).also { harness = it }
        ai.load(file, emptyList(), object : LocalAiHarness.LoadListener {
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
        ai.send(prompt, object : LocalAiHarness.ReplyListener {
            override fun onChunk(text: String) { response.append(text) }
            override fun onDone() {}
            override fun onError(error: String) { response.text = "AI error: $error" }
        })
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
        private const val CHROMIUM_MODEL_KEY = "chromium_local_ai_model_path"
    }
}
