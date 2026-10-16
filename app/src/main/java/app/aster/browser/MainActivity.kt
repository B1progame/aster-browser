package app.aster.browser

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.AllowOrDeny
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

private data class BrowserTab(
    val id: Long,
    var url: String,
    var title: String,
    val session: GeckoSession,
    var loaded: Boolean = false,
    var trackingPermission: GeckoSession.PermissionDelegate.ContentPermission? = null
)
private const val REQUEST_EXTENSION_FILE = 4201
private const val MAX_EXTENSION_BYTES = 50L * 1024L * 1024L

class MainActivity : Activity() {
    private lateinit var runtime: GeckoRuntime
    private lateinit var extensions: WebExtensionController
    private lateinit var gecko: GeckoView
    private lateinit var pageHost: FrameLayout
    private lateinit var address: EditText
    private lateinit var addressRow: LinearLayout
    private lateinit var privacyBadge: TextView
    private val tabs = mutableListOf<BrowserTab>()
    private val homeUrl = "resource://android/assets/home.html"
    private var activeId = 0L
    private var nextId = 1L
    private var screen = "browser"
    private var themeName = "Midnight"
    private var dark = 0xff17191d.toInt()
    private var panel = 0xff22262b.toInt()
    private var raised = 0xff2c3137.toInt()
    private var ink = 0xfff3f4f5.toInt()
    private var muted = 0xffa1a8af.toInt()
    private var accent = 0xff65d6b1.toInt()
    private val prefs by lazy { getSharedPreferences("aster", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        themeName = prefs.getString("theme", "Midnight") ?: "Midnight"
        applyTheme()
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(
                ContentBlocking.AntiTracking.DEFAULT or
                    ContentBlocking.AntiTracking.AD or
                    ContentBlocking.AntiTracking.ANALYTIC or
                    ContentBlocking.AntiTracking.SOCIAL or
                    ContentBlocking.AntiTracking.CONTENT or
                    ContentBlocking.AntiTracking.FINGERPRINTING or
                    ContentBlocking.AntiTracking.CRYPTOMINING or
                    ContentBlocking.AntiTracking.STP or
                    ContentBlocking.AntiTracking.EMAIL
            )
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
            .strictSocialTrackingProtection(true)
            .cookiePurging(true)
            .queryParameterStrippingEnabled(true)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .contentBlockingDatabase(true)
            .build()
        runtime = GeckoRuntime.create(this, GeckoRuntimeSettings.Builder()
            .contentBlocking(blocking)
            .globalPrivacyControlEnabled(true)
            .build())
        extensions = runtime.webExtensionController
        extensions.setPromptDelegate(object : WebExtensionController.PromptDelegate {
            override fun onInstallPromptRequest(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<WebExtension.PermissionPromptResponse> {
                val result = GeckoResult<WebExtension.PermissionPromptResponse>()
                runOnUiThread {
                    val details = extensionPermissionDetails(permissions, origins, dataCollectionPermissions)
                    val answered = AtomicBoolean(false)
                    val dialog = AlertDialog.Builder(this@MainActivity)
                        .setTitle("Install ${extension.metaData.name}")
                        .setMessage("Review what this extension can access.\n\n$details")
                        .setNegativeButton("Cancel") { _, _ ->
                            if (answered.compareAndSet(false, true)) result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                        }
                        .setPositiveButton("Install") { _, _ ->
                            if (answered.compareAndSet(false, true)) result.complete(WebExtension.PermissionPromptResponse(true, false, false))
                        }.create()
                    dialog.setOnCancelListener {
                        if (answered.compareAndSet(false, true)) result.complete(WebExtension.PermissionPromptResponse(false, false, false))
                    }
                    dialog.show(); dialog.styleDialog()
                }
                return result
            }

            override fun onUpdatePrompt(extension: WebExtension, newPermissions: Array<String>, newOrigins: Array<String>, newDataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny> =
                promptForExtraPermissions("Update ${extension.metaData.name}", newPermissions, newOrigins, newDataCollectionPermissions)

            override fun onOptionalPrompt(extension: WebExtension, permissions: Array<String>, origins: Array<String>, dataCollectionPermissions: Array<String>): GeckoResult<AllowOrDeny> =
                promptForExtraPermissions("Allow ${extension.metaData.name} more access?", permissions, origins, dataCollectionPermissions)
        })
        tabs += BrowserTab(nextId++, homeUrl, "New tab", GeckoSession())
        restoreTabs()
        buildShell()
        selectTab(tabs.firstOrNull { it.id == activeId }?.id ?: tabs.first().id)
    }

    private fun applyTheme() {
        when (themeName) {
            "Light" -> { dark=0xfff3f5f6.toInt(); panel=Color.WHITE; raised=0xffe7ebee.toInt(); ink=0xff182026.toInt(); muted=0xff627079.toInt(); accent=0xff007f68.toInt() }
            "Ocean" -> { dark=0xff101b26.toInt(); panel=0xff172735.toInt(); raised=0xff203747.toInt(); ink=0xfff0f7fa.toInt(); muted=0xff9fb5c2.toInt(); accent=0xff59d6e8.toInt() }
            else -> { dark=0xff17191d.toInt(); panel=0xff22262b.toInt(); raised=0xff2c3137.toInt(); ink=0xfff3f4f5.toInt(); muted=0xffa1a8af.toInt(); accent=0xff65d6b1.toInt() }
        }
        window.statusBarColor = dark
        window.navigationBarColor = dark
        window.decorView.systemUiVisibility = if (themeName == "Light") View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(dark) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        val top = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(10),dp(16),dp(10)); setBackgroundColor(dark) }
        val brand = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
        val mark = ImageView(this).apply {
            setImageResource(R.drawable.ic_aster_foreground)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = shape(raised, 18)
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38))
            contentDescription = "Aster Browser"
        }
        val nameCol = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),0,0,0) }
        nameCol.addView(label("ASTER", 13f, ink, true)); nameCol.addView(label("PRIVATE BY DEFAULT",9f,muted,false))
        val spacer=View(this); brand.addView(mark); brand.addView(nameCol); brand.addView(spacer,LinearLayout.LayoutParams(0,1,1f))
        privacyBadge = label("•  SHIELDS ON",10f,accent,true).apply {
            setPadding(dp(10),dp(8),dp(10),dp(8)); background=shape(panel,18); contentDescription="Open site protection settings"; isFocusable=true; isClickable=true
            setOnClickListener { showSiteShields() }
        }
        brand.addView(privacyBadge); top.addView(brand)
        address = EditText(this).apply {
            setSingleLine(true); textSize=14f; setTextColor(ink); setHintTextColor(muted); hint="Search or enter address"; setPadding(dp(14),0,dp(12),0)
            background=shape(panel,16); imeOptions=EditorInfo.IME_ACTION_GO; inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setOnFocusChangeListener { view, focused ->
                val field = shape(panel, 16)
                field.setStroke(dp(1), if (focused) accent else raised)
                view.background = field
            }
            setOnEditorActionListener { _, action, event -> if(action==EditorInfo.IME_ACTION_GO || event?.keyCode==KeyEvent.KEYCODE_ENTER){navigate(text.toString()); true}else false }
            contentDescription="Search or enter address"
        }
        addressRow=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(0,dp(12),0,dp(2)) }
        addressRow.addView(address,LinearLayout.LayoutParams(0,dp(48),1f))
        val reload=iconButton(R.drawable.ic_refresh, "Reload page", 48, 48, 0f) { current()?.session?.reload() }
        addressRow.addView(reload,LinearLayout.LayoutParams(dp(44),dp(48))); top.addView(addressRow)
        root.addView(top)
        pageHost=FrameLayout(this).apply { setBackgroundColor(dark) }
        gecko=GeckoView(this)
        gecko.setBackgroundColor(dark)
        pageHost.addView(gecko,FrameLayout.LayoutParams(-1,-1))
        root.addView(pageHost,LinearLayout.LayoutParams(-1,0,1f))
        val nav=LinearLayout(this).apply { gravity=Gravity.CENTER; orientation=LinearLayout.HORIZONTAL; setPadding(dp(8),dp(8),dp(8),dp(8)); setBackgroundColor(panel) }
        nav.addView(iconButton(R.drawable.ic_back,"Go back") { current()?.session?.goBack() })
        nav.addView(iconButton(R.drawable.ic_forward,"Go forward") { current()?.session?.goForward() })
        nav.addView(iconButton(R.drawable.ic_home,"Home") { navigate(homeUrl) })
        nav.addView(iconButton(R.drawable.ic_tabs,"Tabs") { showTabs() })
        nav.addView(iconButton(R.drawable.ic_more,"Settings and more") { showMenu() })
        root.addView(nav,LinearLayout.LayoutParams(-1,dp(58)))
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
    }

    private fun button(glyph:String, description:String, action:()->Unit): TextView = button(glyph,description).apply { pressFeedback(this); setOnClickListener { action() } }
    private fun button(glyph:String, description:String): TextView = TextView(this).apply {
        text=glyph; textSize=23f; gravity=Gravity.CENTER; setTextColor(ink); contentDescription=description; isFocusable=true
        layoutParams=LinearLayout.LayoutParams(0,dp(46),1f); background=selectableBackground()
    }

    private fun iconButton(icon:Int, description:String, width:Int=0, height:Int=48, weight:Float=1f, action:()->Unit): ImageView = ImageView(this).apply {
        setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(ink); scaleType=ImageView.ScaleType.FIT_CENTER
        contentDescription=description; isFocusable=true; isClickable=true; setPadding(dp(14),dp(14),dp(14),dp(14))
        background=selectableBackground(); layoutParams=LinearLayout.LayoutParams(if(width>0)dp(width) else 0,dp(height),weight)
        pressFeedback(this)
        setOnClickListener { action() }
    }

    private fun pressFeedback(view: View) {
        view.setOnTouchListener { target, event ->
            val pressed = event.actionMasked == android.view.MotionEvent.ACTION_DOWN
            val released = event.actionMasked == android.view.MotionEvent.ACTION_UP || event.actionMasked == android.view.MotionEvent.ACTION_CANCEL
            if (pressed || released) {
                val scale = if (pressed) 0.96f else 1f
                target.animate().scaleX(scale).scaleY(scale)
                    .setDuration(motionDuration(if (pressed) 105L else 155L))
                    .setInterpolator(android.view.animation.DecelerateInterpolator())
                    .start()
            }
            false
        }
    }

    private fun showMenu() {
        val dialog = AlertDialog.Builder(this).create()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(18))
            background = shape(panel, 28)
        }
        val handleColor = Color.argb(110, Color.red(muted), Color.green(muted), Color.blue(muted))
        val handle = View(this).apply { background = shape(handleColor, 3) }
        val handleRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(2), 0, dp(15)) }
        handleRow.addView(handle, LinearLayout.LayoutParams(dp(34), dp(4)))
        content.addView(handleRow)
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(2), 0, 0, dp(12)) }
        heading.addView(label("Your browser", 22f, ink, true))
        heading.addView(label("Tools for this tab", 12f, muted, false).apply { setPadding(0, dp(3), 0, 0) })
        content.addView(heading)
        content.addView(menuRow(R.drawable.ic_bookmark, "Add bookmark", "Save this page") { dialog.dismiss(); bookmarkCurrent() })
        content.addView(menuRow(R.drawable.ic_bookmarks, "Bookmarks", "Your saved pages") { dialog.dismiss(); showBookmarks() })
        content.addView(menuRow(R.drawable.ic_history, "History", "Recently visited") { dialog.dismiss(); showHistory() })
        content.addView(menuRow(R.drawable.ic_download, "Downloads", "Download handling · planned") { dialog.dismiss(); message("Downloads", "Download management is planned for a future version.") })
        content.addView(menuRow(R.drawable.ic_extensions, "Extensions", "Manage installed add-ons") { dialog.dismiss(); showExtensions() })
        content.addView(menuRow(R.drawable.ic_settings, "Settings", "Appearance, search and privacy") { dialog.dismiss(); showSettings() })
        val divider = View(this).apply { setBackgroundColor(raised) }
        val dividerLp = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(8); bottomMargin = dp(8) }
        content.addView(divider, dividerLp)
        content.addView(menuRow(R.drawable.ic_share, "Send to PC", "In development · not active yet", enabled = false) {
            dialog.dismiss(); message("Coming later", "Send tabs and bookmarks to your PC is planned. This control is not active yet.")
        })
        dialog.setView(content)
        dialog.show()
        val wideUi = resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f
        dialog.window?.apply {
            setBackgroundDrawable(shape(panel, 28))
            setDimAmount(0.58f)
            setGravity(if (wideUi) Gravity.CENTER else Gravity.BOTTOM)
            setLayout(if (wideUi) dp(520) else -1, -2)
            decorView.setPadding(if (wideUi) 0 else dp(8), 0, if (wideUi) 0 else dp(8), if (wideUi) 0 else dp(8))
        }
        dialog.window?.decorView?.apply {
            alpha = 0f
            translationY = dp(24).toFloat()
            animate().alpha(1f).translationY(0f).setDuration(motionDuration(245L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        }
    }

    private fun showTabs() {
        lateinit var dialog: AlertDialog
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(18),dp(12),dp(18),dp(16)); background=shape(panel,28) }
        val handleRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(12)) }
        handleRow.addView(View(this).apply { background = shape(Color.argb(110, Color.red(muted), Color.green(muted), Color.blue(muted)), 3) }, LinearLayout.LayoutParams(dp(34), dp(4)))
        content.addView(handleRow)
        val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        val headingText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        headingText.addView(label("Your tabs",22f,ink,true))
        headingText.addView(label("${tabs.size} open",12f,muted,false).apply { setPadding(0,dp(2),0,0) })
        heading.addView(headingText,LinearLayout.LayoutParams(0,dp(52),1f))
        val add=button("＋","New tab") { dialog.dismiss(); createTab(homeUrl); showTabs() }.apply { layoutParams=LinearLayout.LayoutParams(dp(48),dp(48)); background=shape(raised,16); setTextColor(accent) }
        heading.addView(add); content.addView(heading)
        tabs.forEach { tab ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(10),dp(8),dp(10)); background=shape(if(tab.id==activeId) raised else dark,18); isFocusable=true; isClickable=true; minimumHeight=dp(74); pressFeedback(this) }
            val mark = ImageView(this).apply {
                setImageResource(R.drawable.ic_aster_foreground)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = shape(if (tab.id == activeId) panel else raised, 14)
                contentDescription = if (tab.id == activeId) "Current tab" else "Tab"
            }
            card.addView(mark, LinearLayout.LayoutParams(dp(42), dp(42)).apply { rightMargin = dp(12) })
            val textCol=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(0,0,dp(8),0) }
            textCol.addView(label(tab.title.ifBlank { "New tab" },15f,ink,true))
            textCol.addView(label(if (tab.url.startsWith("resource://")) "New tab" else tab.url,11f,muted,false))
            card.addView(textCol,LinearLayout.LayoutParams(0,dp(54),1f))
            val close=button("×","Close tab") { dialog.dismiss(); closeTab(tab.id); showTabs() }.apply { layoutParams=LinearLayout.LayoutParams(dp(42),dp(44)); setTextColor(muted) }; card.addView(close)
            card.setOnClickListener { dialog.dismiss(); selectTab(tab.id); showBrowser() }
            val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(8); content.addView(card,lp)
        }
        val scroll=ScrollView(this).apply { isFillViewport=true; addView(content) }
        dialog=AlertDialog.Builder(this).setView(scroll).setNegativeButton("Done",null).create()
        dialog.show(); dialog.window?.apply {
            setBackgroundDrawable(shape(panel,28)); setDimAmount(0.55f); setGravity(Gravity.BOTTOM)
            val wideUi = resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f
            setGravity(if (wideUi) Gravity.CENTER else Gravity.BOTTOM)
            setLayout(if (wideUi) dp(720) else -1, minOf(dp(620), (resources.displayMetrics.heightPixels * 0.82f).toInt()))
            decorView.setPadding(if (wideUi) 0 else dp(8), 0, if (wideUi) 0 else dp(8), if (wideUi) 0 else dp(8))
            decorView.apply { alpha=0f; translationY=dp(24).toFloat(); animate().alpha(1f).translationY(0f).setDuration(motionDuration(245L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
        }
        dialog.styleDialog()
    }

    private fun showSettings() {
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(20),dp(8),dp(20),dp(18)); setBackgroundColor(dark) }
        content.addView(label("Settings",26f,ink,true)); content.addView(label("Make Aster feel like yours.",14f,muted,false).apply { setPadding(0,dp(4),0,dp(18)) })
        section(content,"APPEARANCE")
        row(content,"Theme",themeName,"Change") { chooseTheme() }
        section(content,"SEARCH")
        row(content,"Search engine",prefs.getString("engine","DuckDuckGo") ?: "DuckDuckGo","Choose") { chooseEngine() }
        section(content,"PRIVACY")
        row(content,"Tracking protection","Strict · enabled","Details") { message("Tracking protection","Aster enables GeckoView’s known ad, analytics, social, email, fingerprinting and cryptomining protections, non-tracker cookie policy, tracking-parameter stripping, and safe-browsing lists. This blocks known ad/tracker resources; it cannot remove every first-party ad or guarantee Brave-level cosmetic filtering. Tap SHIELDS ON in the address header to set a persistent exception for the current website.") }
        row(content,"Global Privacy Control","Enabled","Details") { message("Global Privacy Control","Aster sends a privacy preference signal to sites asking them not to sell or share your data.") }
        row(content,"Extensions","Firefox WebExtensions","Manage") { showExtensions() }
        section(content,"SYNC")
        row(content,"Send to PC","In development","Preview") { message("Coming later","Cross-device sharing is planned for a future version. No data is sent from this build.") }
        val close=button("Done","Close settings") { showBrowser() }.apply { text="Done"; textSize=15f; setTypeface(null,Typeface.BOLD); background=shape(accent,15); setTextColor(dark); layoutParams=LinearLayout.LayoutParams(-1,dp(48)); setOnClickListener { showBrowser() } }
        content.addView(close)
        val settingsScroll = ScrollView(this).apply { isFillViewport = true }
        val settingsStage = FrameLayout(this)
        val maxContentWidth = if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f) dp(720) else -1
        settingsStage.addView(content, FrameLayout.LayoutParams(maxContentWidth, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        settingsScroll.addView(settingsStage, ViewGroup.LayoutParams(-1, -2))
        settingsScroll.alpha = 0f; settingsScroll.translationY = dp(8).toFloat()
        pageHost.removeAllViews(); pageHost.addView(settingsScroll, FrameLayout.LayoutParams(-1, -1))
        settingsScroll.animate().alpha(1f).translationY(0f).setDuration(motionDuration(245L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        screen="settings"; addressRow.visibility=View.GONE; privacyBadge.text="✦  ASTER SETTINGS"
    }

    private fun chooseTheme() {
        AlertDialog.Builder(this).setTitle("Choose a theme").setSingleChoiceItems(arrayOf("Midnight","Ocean","Light"), listOf("Midnight","Ocean","Light").indexOf(themeName)) { d, index ->
            themeName=listOf("Midnight","Ocean","Light")[index]; prefs.edit().putString("theme",themeName).apply(); applyTheme(); d.dismiss()
            tabs.filter { it.url.startsWith(homeUrl) }.forEach { it.url = homeUrl; it.loaded = false }
            buildShell(); selectTab(activeId); showSettings()
        }.setNegativeButton("Cancel",null).show().styleDialog()
    }

    private fun chooseEngine() {
        val engines=arrayOf("DuckDuckGo","Google","Bing","Brave Search")
        AlertDialog.Builder(this).setTitle("Search engine").setSingleChoiceItems(engines,engines.indexOf(prefs.getString("engine","DuckDuckGo"))) { d,i -> prefs.edit().putString("engine",engines[i]).apply(); d.dismiss(); showSettings() }.setNegativeButton("Cancel",null).show().styleDialog()
    }

    private fun showExtensions() {
        screen = "extensions"
        addressRow.visibility = View.GONE
        privacyBadge.text = "✦  ADD-ONS"
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(dark)
        }
        content.addView(label("Extensions", 26f, ink, true))
        content.addView(label("Firefox WebExtensions for Android", 13f, accent, true).apply { setPadding(0, dp(4), 0, dp(8)) })
        content.addView(label("Install Mozilla-signed .xpi add-ons. GeckoView checks the package signature and Aster asks before granting access.", 13f, muted, false).apply { setPadding(0, 0, 0, dp(16)) })

        val installUrl = button("↗", "Install extension from URL") { promptInstallUrl() }.apply {
            text = "Install signed add-on URL"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(ink)
            background = shape(raised, 16)
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
        }
        content.addView(installUrl)
        val installFile = button("＋", "Choose XPI file") { chooseExtensionFile() }.apply {
            text = "Choose .xpi file"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(dark)
            background = shape(accent, 16)
            layoutParams = LinearLayout.LayoutParams(-1, dp(50)).apply { bottomMargin = dp(8) }
        }
        content.addView(installFile)
        val store = button("⌕", "Browse Firefox Add-ons") {
            showBrowser()
            createTab("https://addons.mozilla.org/android/")
        }.apply {
            text = "Browse Firefox Add-ons"
            textSize = 14f
            setTextColor(accent)
            background = shape(panel, 16)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(20) }
        }
        content.addView(store)
        content.addView(sectionHeading("INSTALLED"))
        val listHost = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(listHost)
        val compatibility = label("Chrome Web Store .crx packages and Chrome themes are not directly executable here. A manifest/API converter cannot make arbitrary extensions compatible: unsupported Chrome APIs, service workers, native code and desktop UI need extension-specific ports. Only signed WebExtensions supported by GeckoView can be installed.", 12f, muted, false).apply {
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = shape(panel, 16)
        }
        val compatLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        content.addView(compatibility, compatLp)
        val done = button("✓", "Done") { showBrowser() }.apply {
            text = "Done"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(dark)
            background = shape(accent, 15)
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(16) }
        }
        content.addView(done)

        val stage = FrameLayout(this)
        val maxWidth = if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f) dp(720) else -1
        stage.addView(content, FrameLayout.LayoutParams(maxWidth, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(stage, ViewGroup.LayoutParams(-1, -2)); alpha = 0f; translationY = dp(8).toFloat() }
        pageHost.removeAllViews()
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1, -1))
        scroll.animate().alpha(1f).translationY(0f).setDuration(motionDuration(245L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        refreshExtensions(listHost)
    }

    private fun sectionHeading(text: String) = label(text, 10f, accent, true).apply { setPadding(0, dp(4), 0, dp(10)) }

    private fun extensionPermissionDetails(permissions: Array<String>, origins: Array<String>, data: Array<String>): String {
        val lines = mutableListOf<String>()
        if (permissions.isNotEmpty()) lines += "Permissions: ${permissions.joinToString()}"
        if (origins.isNotEmpty()) lines += "Sites: ${origins.joinToString()}"
        if (data.isNotEmpty()) lines += "Data collection requested: ${data.joinToString()}"
        if (lines.isEmpty()) lines += "No additional permissions were requested."
        return lines.joinToString("\n\n")
    }

    private fun promptForExtraPermissions(title: String, permissions: Array<String>, origins: Array<String>, data: Array<String>): GeckoResult<AllowOrDeny> {
        val result = GeckoResult<AllowOrDeny>()
        runOnUiThread {
            val answered = AtomicBoolean(false)
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle(title)
                .setMessage(extensionPermissionDetails(permissions, origins, data))
                .setNegativeButton("Deny") { _, _ -> if (answered.compareAndSet(false, true)) result.complete(AllowOrDeny.DENY) }
                .setPositiveButton("Allow") { _, _ -> if (answered.compareAndSet(false, true)) result.complete(AllowOrDeny.ALLOW) }
                .create()
            dialog.setOnCancelListener { if (answered.compareAndSet(false, true)) result.complete(AllowOrDeny.DENY) }
            dialog.show(); dialog.styleDialog()
        }
        return result
    }

    private fun promptInstallUrl() {
        val field = EditText(this).apply {
            hint = "https://…/extension.xpi"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(ink); setHintTextColor(muted); setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(raised, 14)
        }
        AlertDialog.Builder(this).setTitle("Install signed add-on")
            .setMessage("Paste an HTTPS URL to a Mozilla-signed .xpi package. GeckoView verifies its signature before installation.")
            .setView(field)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Continue") { _, _ ->
                val uri = runCatching { Uri.parse(field.text.toString().trim()) }.getOrNull()
                if (uri?.scheme != "https" || uri.host.isNullOrBlank()) {
                    message("Invalid URL", "Use a complete HTTPS link to a signed .xpi add-on.")
                } else installExtension(uri.toString(), WebExtensionController.INSTALLATION_METHOD_MANAGER)
            }.show().styleDialog()
    }

    private fun chooseExtensionFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/x-xpinstall", "application/zip", "application/octet-stream"))
        }
        startActivityForResult(intent, REQUEST_EXTENSION_FILE)
    }

    @Deprecated("Uses the document picker result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXTENSION_FILE || resultCode != RESULT_OK) return
        val selected = data?.data ?: return
        val cached = File(cacheDir, "aster-extension-${System.currentTimeMillis()}.xpi")
        try {
            val input = contentResolver.openInputStream(selected) ?: throw IllegalArgumentException("Could not read selected file")
            input.use { source -> cached.outputStream().use { destination ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_EXTENSION_BYTES) { "The add-on file is larger than 50 MB." }
                    destination.write(buffer, 0, read)
                }
            } }
            installExtension(cached.toURI().toString(), WebExtensionController.INSTALLATION_METHOD_FROM_FILE, cached)
        } catch (error: Exception) {
            cached.delete()
            message("Could not open add-on", error.message ?: "Choose a valid .xpi file.")
        }
    }

    private fun installExtension(uri: String, method: String, temporaryFile: File? = null) {
        Toast.makeText(this, "Checking add-on signature…", Toast.LENGTH_SHORT).show()
        extensions.install(uri, method).accept({ installed ->
            temporaryFile?.delete()
            runOnUiThread {
                if (installed != null) {
                    registerExtensionTabDelegate(installed)
                    Toast.makeText(this, "${installed.metaData.name.orEmpty()} installed", Toast.LENGTH_LONG).show()
                } else Toast.makeText(this, "Add-on installation finished", Toast.LENGTH_LONG).show()
                if (screen == "extensions") showExtensions()
            }
        }, { error ->
            temporaryFile?.delete()
            runOnUiThread {
                val detail = error?.cause?.message ?: error?.message ?: "The add-on could not be installed. It may be unsigned or incompatible."
                message("Add-on not installed", detail)
                if (screen == "extensions") showExtensions()
            }
        })
    }

    private fun refreshExtensions(host: LinearLayout) {
        extensions.list().accept({ installed -> runOnUiThread {
            if (screen == "extensions") renderExtensions(host, installed.orEmpty())
        } }, { error -> runOnUiThread {
            if (screen == "extensions") {
                host.removeAllViews()
                host.addView(label("Could not load installed add-ons: ${error?.message ?: "unknown error"}", 13f, muted, false))
            }
        } })
    }

    private fun renderExtensions(host: LinearLayout, installed: List<WebExtension>) {
        host.removeAllViews()
        installed.forEach { registerExtensionTabDelegate(it) }
        if (installed.isEmpty()) {
            host.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(18), dp(24), dp(18), dp(24)); background = shape(panel, 18)
                addView(label("No extensions installed", 16f, ink, true))
                addView(label("Choose a signed .xpi file or browse Firefox Add-ons to get started.", 12f, muted, false).apply { gravity = Gravity.CENTER; setPadding(0, dp(6), 0, 0) })
            })
            return
        }
        installed.forEach { extension ->
            val metadata = extension.metaData
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(12), dp(14), dp(12)); background = shape(panel, 18) }
            val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            titleBlock.addView(label(metadata.name.orEmpty().ifBlank { extension.id }, 15f, ink, true))
            titleBlock.addView(label("${metadata.version} · ${if (metadata.enabled) "Enabled" else "Disabled"}", 11f, muted, false).apply { setPadding(0, dp(3), 0, 0) })
            heading.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
            val toggle = button("◉", if (metadata.enabled) "Disable extension" else "Enable extension") {
                val result = if (metadata.enabled) extensions.disable(extension, WebExtensionController.EnableSource.USER) else extensions.enable(extension, WebExtensionController.EnableSource.USER)
                result.accept({ refreshExtensions(host) }, { error -> runOnUiThread { message("Extension update failed", error?.message ?: "Please try again.") } })
            }.apply { text = if (metadata.enabled) "Turn off" else "Turn on"; textSize = 12f; setTextColor(accent); layoutParams = LinearLayout.LayoutParams(-2, dp(40)); setPadding(dp(10), 0, dp(10), 0); background = shape(raised, 12) }
            heading.addView(toggle)
            card.addView(heading)
            val description = metadata.description
            if (!description.isNullOrBlank()) card.addView(label(description, 12f, muted, false).apply { setPadding(0, dp(8), 0, dp(8)) })
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(10), 0, 0) }
            val update = smallAction("Update") {
                extensions.update(extension).accept({ updated -> runOnUiThread { Toast.makeText(this, if (updated == null) "Already up to date" else "Extension updated", Toast.LENGTH_SHORT).show(); refreshExtensions(host) } }, { error -> runOnUiThread { message("Update failed", error?.message ?: "Please try again.") } })
            }
            actions.addView(update)
            val optionsPageUrl = metadata.optionsPageUrl
            if (!optionsPageUrl.isNullOrBlank()) actions.addView(smallAction("Options") { showBrowser(); createTab(optionsPageUrl) })
            actions.addView(smallAction("Remove") {
                AlertDialog.Builder(this).setTitle("Remove ${metadata.name}?").setMessage("This removes the extension and its saved extension data.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                        extensions.uninstall(extension).accept({ refreshExtensions(host) }, { error -> runOnUiThread { message("Remove failed", error?.message ?: "Please try again.") } })
                    }.show().styleDialog()
            })
            card.addView(actions)
            val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) }
            host.addView(card, lp)
        }
    }

    private fun smallAction(text: String, action: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 12f; setTextColor(accent); isFocusable = true; isClickable = true; background = selectableBackground(); setPadding(dp(10), dp(8), dp(10), dp(8)); pressFeedback(this); setOnClickListener { action() }
    }

    private fun registerExtensionTabDelegate(extension: WebExtension) {
        extension.setTabDelegate(object : WebExtension.TabDelegate {
            override fun onNewTab(extension: WebExtension, details: WebExtension.CreateTabDetails): GeckoResult<GeckoSession> {
                val result = GeckoResult<GeckoSession>()
                runOnUiThread {
                    val url = details.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("about:") } ?: homeUrl
                    val tab = BrowserTab(nextId++, url, "New tab", GeckoSession())
                    tabs += tab
                    showBrowser()
                    selectTab(tab.id)
                    result.complete(tab.session)
                }
                return result
            }

            override fun onOpenOptionsPage(extension: WebExtension) {
                val optionsUrl = extension.metaData.optionsPageUrl ?: return
                runOnUiThread { showBrowser(); createTab(optionsUrl) }
            }
        })
    }

    private fun showBookmarks() {
        val saved=prefs.getStringSet("bookmarks", emptySet())?.toList().orEmpty()
        if(saved.isEmpty()){message("Bookmarks","No bookmarks yet. Use the menu while browsing to save pages.");return}
        AlertDialog.Builder(this).setTitle("Bookmarks").setItems(saved.toTypedArray()) { _,i -> navigate(saved[i]) }.setNegativeButton("Close",null).show().styleDialog()
    }
    private fun showHistory() {
        val history=prefs.getStringSet("history", emptySet())?.toList()?.reversed().orEmpty()
        if(history.isEmpty()){message("History","Pages you visit will appear here.");return}
        AlertDialog.Builder(this).setTitle("Recent history").setItems(history.take(30).toTypedArray()) { _,i -> navigate(history.reversed()[i]) }.setNegativeButton("Close",null).setNeutralButton("Clear") { _,_ -> prefs.edit().remove("history").apply() }.show().styleDialog()
    }

    private fun updateProtectionBadge() {
        val tab = current()
        val url = tab?.url.orEmpty()
        val isWebPage = url.startsWith("https://") || url.startsWith("http://")
        val enabled = tab?.trackingPermission?.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
        privacyBadge.text = if (!isWebPage) "•  SHIELDS READY" else if (enabled) "•  SHIELDS ON" else "•  SHIELDS OFF"
        privacyBadge.setTextColor(if (enabled) accent else muted)
        privacyBadge.contentDescription = if (isWebPage) "Site protection ${if (enabled) "on" else "off"}. Tap to change." else "Site protection settings"
    }

    private fun showSiteShields() {
        val tab = current() ?: return
        val uri = Uri.parse(tab.url)
        val host = uri.host?.removePrefix("www.")
        if ((uri.scheme != "https" && uri.scheme != "http") || host.isNullOrBlank()) {
            message("Aster Shields", "Open a website first to change protection for that site.")
            return
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = shape(panel, 26)
        }
        content.addView(label("Aster Shields", 22f, ink, true))
        content.addView(label(host, 13f, accent, true).apply { setPadding(0, dp(3), 0, dp(14)) })
        content.addView(label("Known ad and tracker domains, cross-site tracking cookies, fingerprinting, and cryptomining protection are managed by GeckoView.", 13f, muted, false))

        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = shape(raised, 16)
        }
        val switchCopy = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        switchCopy.addView(label("Block ads & trackers", 14f, ink, true))
        switchCopy.addView(label("For this website", 11f, muted, false).apply { setPadding(0, dp(3), 0, 0) })
        switchRow.addView(switchCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val shieldSwitch = Switch(this).apply {
            isChecked = tab.trackingPermission?.value != GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            contentDescription = "Enable ad and tracker protection for $host"
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            trackTintList = android.content.res.ColorStateList.valueOf(if (isChecked) Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)) else muted)
        }
        switchRow.addView(shieldSwitch)
        val rowLp = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        content.addView(switchRow, rowLp)

        val count = label("Checking blocked resources…", 12f, muted, false).apply {
            setPadding(dp(12), dp(12), dp(12), dp(4))
        }
        content.addView(count)
        content.addView(label("Turning protection off creates a site exception in GeckoView. Turning it back on removes the exception. Your choice is saved for this site.", 11f, muted, false).apply { setPadding(dp(12), dp(4), dp(12), dp(6)) })
        val done = button("✓", "Close site protection") { }.apply {
            text = "Done"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(dark); background = shape(accent, 14); layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(12) }
        }
        content.addView(done)

        val dialog = AlertDialog.Builder(this).create()
        dialog.setView(content)
        dialog.setOnShowListener { dialog.window?.apply {
            setBackgroundDrawable(shape(panel, 26)); setDimAmount(0.58f)
            setGravity(if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f) Gravity.CENTER else Gravity.BOTTOM)
            setLayout(if (resources.displayMetrics.widthPixels / resources.displayMetrics.density >= 760f) dp(520) else -1, -2)
            decorView.setPadding(dp(8), 0, dp(8), dp(8))
        } }
        done.setOnClickListener { dialog.dismiss() }
        shieldSwitch.setOnCheckedChangeListener { _, enabled ->
            shieldSwitch.isEnabled = false
            setSiteProtection(tab, enabled) { success, failure ->
                shieldSwitch.isEnabled = true
                if (success) {
                    shieldSwitch.isChecked = enabled
                    updateProtectionBadge()
                    count.text = if (enabled) "Protection is on for $host" else "Protection is off for $host"
                    if (!failure.isNullOrBlank()) count.text = failure
                } else {
                    shieldSwitch.isChecked = !enabled
                    count.text = failure ?: "Could not save this site setting."
                }
                shieldSwitch.trackTintList = android.content.res.ColorStateList.valueOf(if (shieldSwitch.isChecked) Color.argb(115, Color.red(accent), Color.green(accent), Color.blue(accent)) else muted)
            }
        }
        dialog.show()
        dialog.window?.decorView?.apply { alpha = 0f; translationY = dp(16).toFloat(); animate().alpha(1f).translationY(0f).setDuration(motionDuration(220L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start() }
        runtime.contentBlockingController.getLog(tab.session).accept({ entries -> runOnUiThread {
            if (dialog.isShowing) {
                val blocked = entries.orEmpty().sumOf { entry -> entry.blockingData.filter { it.blocked }.sumOf { it.count } }
                count.text = if (blocked == 0) "No blocked resources reported for this page yet" else "$blocked known tracking resources blocked on this page"
            }
        } }, { _ -> runOnUiThread { if (dialog.isShowing) count.text = "Protection is active; blocked-resource totals are unavailable for this page." } })
    }

    private fun setSiteProtection(tab: BrowserTab, enabled: Boolean, callback: (Boolean, String?) -> Unit) {
        val permission = tab.trackingPermission
        if (permission == null) {
            callback(false, "This page has not reported its site permission yet. Reload it and try again.")
            return
        }
        try {
            val value = if (enabled) GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT else GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
            runtime.storageController.setPermission(permission, value)
            tab.trackingPermission = runCatching { GeckoSession.PermissionDelegate.ContentPermission.fromJson(permission.toJson().put("value", value)) }.getOrNull() ?: permission
            callback(true, null)
        } catch (error: Exception) {
            callback(false, error.message ?: "Could not save the site permission.")
        }
    }

    private fun section(parent:LinearLayout,title:String){ parent.addView(label(title,10f,accent,true).apply { setPadding(0,dp(18),0,dp(6)) }) }
    private fun row(parent:LinearLayout,title:String,value:String,action:String,onClick:()->Unit) {
        val box=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(14),dp(10),dp(12),dp(10)); background=shape(panel,15); isFocusable=true; isClickable=true; pressFeedback(this) }
        val texts=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        texts.addView(label(title,14f,ink,true)); texts.addView(label(value,11f,muted,false).apply { setPadding(0,dp(3),0,0) })
        box.addView(texts,LinearLayout.LayoutParams(0,dp(48),1f)); box.addView(label("$action  ›",12f,accent,true)); box.setOnClickListener { onClick() }
        val lp=LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(7); parent.addView(box,lp)
    }

    private fun menuRow(icon:Int,title:String,subtitle:String,enabled:Boolean=true,onClick:()->Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(5), dp(10), dp(5))
            minimumHeight = dp(58)
            isFocusable = enabled
            isClickable = enabled
            alpha = if (enabled) 1f else 0.48f
            background = if (enabled) selectableBackground() else null
            setOnClickListener { if (enabled) onClick() }
        }
        if (enabled) pressFeedback(row)
        val badge = FrameLayout(this).apply { background = shape(raised, 15) }
        val image = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(accent)
            contentDescription = title
            setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        badge.addView(image, FrameLayout.LayoutParams(-1, -1))
        row.addView(badge, LinearLayout.LayoutParams(dp(44), dp(44)))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(13), 0, 0, 0) }
        text.addView(label(title, 14f, ink, true))
        text.addView(label(subtitle, 11f, muted, false).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun showBrowser() {
        screen="browser"; pageHost.removeAllViews(); pageHost.setBackgroundColor(dark); gecko.setBackgroundColor(dark); gecko.alpha=0f; gecko.translationY=dp(7).toFloat()
        pageHost.addView(gecko,FrameLayout.LayoutParams(-1,-1))
        gecko.animate().alpha(1f).translationY(0f).setDuration(motionDuration(245L)).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
        addressRow.visibility=View.VISIBLE
        current()?.let { if(it.url.startsWith("http")) address.setText(it.url) else address.setText("") }
        updateProtectionBadge()
    }
    private fun createTab(url:String) { val tab=BrowserTab(nextId++,url,"New tab",GeckoSession()); tabs += tab; selectTab(tab.id) }
    private fun selectTab(id:Long) {
        val tab=tabs.firstOrNull { it.id==id } ?: return
        val previous = current()
        if (previous?.id != id) {
            previous?.let { if (it.session.isOpen) extensions.setTabActive(it.session, false) }
            extensions.setTabActive(tab.session, true)
        }
        activeId=id
        if(!tab.session.isOpen) tab.session.open(runtime)
        tab.session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: List<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (!url.isNullOrBlank()) {
                    tab.url = url
                    tab.trackingPermission = perms.firstOrNull { it.permission == GeckoSession.PermissionDelegate.PERMISSION_TRACKING }
                    tab.title = if (url.startsWith("resource://")) "New tab" else url.substringAfter("//").substringBefore("/").ifBlank { "New tab" }
                    if (tab.id == activeId && screen == "browser") {
                        address.setText(if (url.startsWith("http://") || url.startsWith("https://")) url else "")
                        updateProtectionBadge()
                    }
                    persistTabs()
                }
            }
        }
        gecko.setSession(tab.session)
        gecko.setBackgroundColor(dark)
        if (!tab.loaded) { tab.session.loadUri(uriForTheme(tab.url)); tab.loaded = true }
        address.setText(if(tab.url.startsWith("http"))tab.url else "")
        updateProtectionBadge()
        persistTabs()
    }
    private fun closeTab(id:Long) {
        val ix=tabs.indexOfFirst{it.id==id}; if(ix<0)return
        tabs.removeAt(ix).session.close()
        if(tabs.isEmpty()) tabs+=BrowserTab(nextId++,homeUrl,"New tab",GeckoSession())
        if(activeId==id) { val next=tabs[minOf(ix,tabs.lastIndex)]; activeId=next.id; selectTab(next.id) }
        persistTabs()
    }
    private fun bookmarkCurrent() {
        val url=current()?.url ?: return
        if(!url.startsWith("http")){ message("Bookmark","Open a website first, then add it to bookmarks."); return }
        val saved=prefs.getStringSet("bookmarks",emptySet())?.toMutableSet() ?: mutableSetOf()
        saved.add(url); prefs.edit().putStringSet("bookmarks",saved).apply(); message("Saved","Bookmark added.")
    }
    private fun current()=tabs.firstOrNull{it.id==activeId}
    private fun uriForTheme(url:String) = if (url == homeUrl) "$homeUrl?theme=${themeName.lowercase()}" else url
    private fun navigate(raw:String) {
        if(raw.isBlank())return
        val value=raw.trim()
        val url=if(value.contains(" ") || (!value.contains(".") && !value.startsWith("about:") && !value.startsWith("file:"))) {
            val engine=prefs.getString("engine","DuckDuckGo") ?: "DuckDuckGo"
            val base=when(engine){"Google"->"https://www.google.com/search?q=";"Bing"->"https://www.bing.com/search?q=";"Brave Search"->"https://search.brave.com/search?q=";else->"https://duckduckgo.com/?q="}
            base+Uri.encode(value)
        } else if(value.startsWith("http://") || value.startsWith("https://") || value.startsWith("about:") || value.startsWith("file:") || value.startsWith("resource:")) value else "https://$value"
        current()?.let { it.url=url; it.loaded=true; it.title=if(url.startsWith(homeUrl)) "New tab" else url.substringAfter("//").substringBefore("/").ifBlank { "New tab" }; it.session.loadUri(uriForTheme(url)) }
        prefs.edit().putStringSet("history", (prefs.getStringSet("history",emptySet())?.toMutableSet() ?: mutableSetOf()).apply { add(url) }).apply()
        address.setText(url); (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(address.windowToken,0); address.clearFocus(); persistTabs()
    }

    private fun restoreTabs() {
        val data=prefs.getString("tabs",null) ?: return
        try {
            val arr=JSONArray(data); tabs.clear()
            for(i in 0 until arr.length()) {
                val item=arr.getJSONObject(i); val id=item.optLong("id",nextId++); nextId=maxOf(nextId,id+1)
                val savedUrl=item.optString("url",homeUrl).let {
                    if (it == "about:home" || it == "file:///android_asset/home.html" || it.startsWith(homeUrl)) homeUrl else it
                }
                val savedTitle = if (savedUrl == homeUrl) "New tab" else item.optString("title","New tab")
                tabs += BrowserTab(id,savedUrl,savedTitle,GeckoSession())
            }
            if(tabs.isEmpty())tabs+=BrowserTab(nextId++,homeUrl,"New tab",GeckoSession())
            activeId=prefs.getLong("active",tabs.first().id)
        } catch(_:Exception) { tabs.clear(); tabs+=BrowserTab(nextId++,homeUrl,"New tab",GeckoSession()) }
    }
    private fun persistTabs() {
        val arr=JSONArray(); tabs.forEach { arr.put(JSONObject().put("id",it.id).put("url",it.url).put("title",it.title)) }
        prefs.edit().putString("tabs",arr.toString()).putLong("active",activeId).apply()
    }

    private fun message(title:String,text:String) { AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("Got it",null).show().styleDialog() }
    private fun selectSearchEngineAndSearch(value:String) { navigate(value) }
    private fun label(text:String,size:Float,color:Int,bold:Boolean)=TextView(this).apply { this.text=text; textSize=size; setTextColor(color); if(bold)setTypeface(null,Typeface.BOLD); gravity=Gravity.CENTER_VERTICAL; isFocusable=false }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun motionDuration(duration:Long) = if (android.animation.ValueAnimator.areAnimatorsEnabled()) duration else 0L
    private fun shape(color:Int,radius:Int)=GradientDrawable().apply { setColor(color); cornerRadius=dp(radius).toFloat() }
    private fun selectableBackground(): android.graphics.drawable.Drawable = android.util.TypedValue().let { v -> themeResolve(v); if(v.resourceId!=0) getDrawable(v.resourceId)!! else shape(raised,15) }
    private fun themeResolve(v:android.util.TypedValue){ super.getTheme().resolveAttribute(android.R.attr.selectableItemBackground,v,true) }
    private fun AlertDialog.styleDialog() {
        window?.setBackgroundDrawable(shape(panel,22))
        window?.setDimAmount(0.55f)
        findViewById<TextView>(android.R.id.title)?.setTextColor(ink)
        listOf(android.R.id.button1, android.R.id.button2, android.R.id.button3).forEach { id ->
            getButton(id)?.apply { setTextColor(accent); isAllCaps = false; textSize = 14f }
        }
        findViewById<TextView>(android.R.id.message)?.setTextColor(muted)
    }

    override fun onBackPressed() { if(screen!="browser")showBrowser() else current()?.session?.goBack() ?: super.onBackPressed() }
    override fun onPause() { super.onPause(); persistTabs() }
    override fun onDestroy() { super.onDestroy(); tabs.forEach { if(it.session.isOpen) it.session.close() } }
}
