package ir.hamyareqari.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.Harmonics
import ir.hamyareqari.dsp.Json
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.PitchTrack
import ir.hamyareqari.dsp.PitchTracker
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.YinTracker
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * «همیار قاری» — تحلیل گسترهٔ صدای خواننده، کاملاً بومی و آفلاین.
 * این اپ نه WebView دارد، نه اینترنت، نه سرور.
 */
class MainActivity : Activity() {

    companion object {
        const val VERSION = "1.1.6"
        private const val REQ_PICK = 1001
        private const val REQ_MIC = 1002
        private const val REQ_WRITE = 1003
    }

    private val ui = Handler(Looper.getMainLooper())

    // وضعیت تحلیل فایل
    private var decoded: AudioIo.Decoded? = null
    private var report: Report? = null
    private var sourceName = "صدا"
    private var busy = false
    private var player: AudioIo.Player? = null

    // نمای‌ها
    private lateinit var pageHost: FrameLayout
    private lateinit var homePanel: View
    private lateinit var analysisScreen: View
    private lateinit var settingsPanel: View
    private lateinit var aboutPanel: View
    private lateinit var navHome: TextView
    private lateinit var navFiles: TextView
    private lateinit var navSettings: TextView
    private var currentPage: String = "home"

    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabFile: TextView
    private lateinit var tabLive: TextView
    private lateinit var panelFile: View
    private lateinit var panelLive: View
    private lateinit var resultBox: LinearLayout
    private lateinit var denoiseCheck: CheckBox
    private var pickUri: Uri? = null
    private var videoFile: java.io.File? = null
    private var lastElapsedMs = 0L
    private var chartRef: ChartView? = null
    private var audioBar: AudioPlayerBar? = null
    private lateinit var fileLabel: TextView
    private lateinit var analyzeBtn: TextView
    private lateinit var progressBar: android.widget.ProgressBar
    private lateinit var stageLabel: TextView

    // نمای‌های تب زنده
    private lateinit var liveNoteName: TextView
    private lateinit var liveSolfege: TextView
    private lateinit var liveCents: TextView
    private lateinit var liveGauge: CentsGaugeView
    private lateinit var liveChart: ChartView
    private lateinit var liveHighLabel: TextView
    private lateinit var liveLowLabel: TextView
    private lateinit var liveStartBtn: TextView
    private lateinit var liveStatus: TextView
    private lateinit var recordCheck: CheckBox
    private lateinit var liveExportRow: LinearLayout

    private var live: LiveEngine? = null
    private var liveRecording: ShortGrow? = null
    private var ticker: Runnable? = null
    private var recordTicker: Runnable? = null
    private lateinit var recBar: LinearLayout
    private lateinit var recTime: TextView
    private var recordStartAt = 0L
    private lateinit var liveSpanLabel: TextView
    private lateinit var liveDurLabel: TextView
    private var liveEngineReset: (() -> Unit)? = null

    // ───────────────────────────── چرخهٔ عمر ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Theme.BG1
        window.navigationBarColor = Theme.BG3
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Theme.BG1))
        buildUi()
        showTab(0)
    }

    override fun onPause() {
        super.onPause()
        stopLive()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }

    // ───────────────────────────── ساخت رابط ─────────────────────────────

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.screenBackground(this@MainActivity)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        pageHost = FrameLayout(this)
        pageHost.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        )

        // Existing analysis panels remain intact; the new shell only changes navigation and presentation.
        panelFile = buildFilePanel()
        panelLive = buildLivePanel()
        val analysisColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val analysisScroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Theme.dp(this@MainActivity, 16f)
            setPadding(p, Theme.dp(this@MainActivity, 10f), p, Theme.dp(this@MainActivity, 22f))
        }
        val header = Theme.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = Theme.text(this, "‹", 28f, Theme.TXT, false, Gravity.CENTER).apply {
            background = Theme.rounded(0x0DFFFFFF, Theme.dpF(this@MainActivity, 12f), 1, Theme.LINE)
            minWidth = Theme.dp(this@MainActivity, 42f)
            minHeight = Theme.dp(this@MainActivity, 42f)
            setOnClickListener { setPage("home") }
        }
        val headerTitle = Theme.text(this, "تحلیل صوت", 18f, Theme.TXT, true, Gravity.CENTER)
        header.addView(back)
        header.addView(Theme.space(this, 12f))
        header.addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inner.addView(header)
        inner.addView(Theme.space(this, 14f))

        val tabs = Theme.row(this)
        tabFile = tabButton("تحلیل فایل", true)
        tabLive = tabButton("اجرای زنده", false)
        tabFile.setOnClickListener { showTab(0) }
        tabLive.setOnClickListener { showTab(1) }
        val lpFile = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginEnd = Theme.dp(this@MainActivity, 5f)
        }
        val lpLive = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = Theme.dp(this@MainActivity, 5f)
        }
        tabFile.layoutParams = lpFile
        tabLive.layoutParams = lpLive
        tabs.addView(tabFile)
        tabs.addView(tabLive)
        inner.addView(tabs)
        inner.addView(Theme.space(this, 12f))

        val panels = FrameLayout(this)
        panels.addView(panelFile)
        panels.addView(panelLive)
        inner.addView(panels)
        analysisScroll.addView(inner)
        analysisColumn.addView(analysisScroll)
        analysisScreen = analysisColumn

        homePanel = buildHomePanel()
        settingsPanel = buildSettingsPanel()
        aboutPanel = buildAboutPanel()
        pageHost.addView(homePanel, FrameLayout.LayoutParams(-1, -1))
        pageHost.addView(analysisScreen, FrameLayout.LayoutParams(-1, -1))
        pageHost.addView(settingsPanel, FrameLayout.LayoutParams(-1, -1))
        pageHost.addView(aboutPanel, FrameLayout.LayoutParams(-1, -1))

        root.addView(pageHost)
        root.addView(buildBottomNavigation())
        setContentView(root)

        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            v.setPadding(0, top, 0, bottom)
            insets
        }
        setPage("home")
    }

    private fun buildHomePanel(): View {
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Theme.dp(this@MainActivity, 20f)
            setPadding(p, Theme.dp(this@MainActivity, 24f), p, Theme.dp(this@MainActivity, 22f))
        }
        val settingsTop = Theme.row(this).apply { gravity = Gravity.END }
        val settings = Theme.text(this, "⚙", 23f, Theme.DIM, false, Gravity.CENTER).apply {
            background = Theme.rounded(0x0DFFFFFF, Theme.dpF(this@MainActivity, 12f), 1, Theme.LINE)
            minWidth = Theme.dp(this@MainActivity, 42f)
            minHeight = Theme.dp(this@MainActivity, 42f)
            setOnClickListener { setPage("settings") }
        }
        settingsTop.addView(settings)
        col.addView(settingsTop)

        col.addView(Theme.space(this, 28f))
        val emblem = Theme.text(this, "۞", 78f, Theme.GOLD, true, Gravity.CENTER).apply {
            setShadowLayer(Theme.dpF(this@MainActivity, 20f), 0f, 0f, Theme.withAlpha(Theme.GOLD, 0.22f))
        }
        col.addView(emblem)
        col.addView(Theme.space(this, 4f))
        col.addView(Theme.text(this, "همیار قاری", 30f, Theme.GOLD, true, Gravity.CENTER))
        col.addView(Theme.space(this, 5f))
        col.addView(Theme.text(this, "تحلیل و همراهی در مسیر تلاوت", 13f, Theme.DIM, false, Gravity.CENTER))
        col.addView(Theme.space(this, 30f))

        col.addView(homeActionCard("تحلیل فایل صوتی", "انتخاب فایل و مشاهدهٔ نمودار و نتایج", "♫") {
            setPage("files")
            showTab(0)
        })
        col.addView(Theme.space(this, 12f))
        col.addView(homeActionCard("اجرای زنده", "تحلیل زیر و بمی صدا با میکروفون", "♩") {
            setPage("files")
            showTab(1)
        })
        col.addView(Theme.space(this, 12f))
        col.addView(homeActionCard("تنظیمات", "گزینه‌های تحلیل و اطلاعات برنامه", "⚙") {
            setPage("settings")
        })
        col.addView(Theme.space(this, 24f))
        col.addView(Theme.text(this, "♢  پردازش روی گوشی · بدون نیاز به اینترنت", 10.5f, Theme.DIM, false, Gravity.CENTER))
        scroll.addView(col)
        return scroll
    }

    private fun homeActionCard(title: String, subtitle: String, icon: String, action: () -> Unit): View {
        val card = Theme.card(this, 16f).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = Theme.rounded(Theme.CARD, Theme.dpF(this@MainActivity, 18f), 1, 0x294C6288)
            setOnClickListener { action() }
        }
        val badge = Theme.text(this, icon, 28f, Theme.GOLD, false, Gravity.CENTER).apply {
            background = Theme.rounded(0x16F7D171, Theme.dpF(this@MainActivity, 24f), 1, 0x557F6A38)
            layoutParams = LinearLayout.LayoutParams(Theme.dp(this@MainActivity, 56f), Theme.dp(this@MainActivity, 56f))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        texts.addView(Theme.text(this, title, 15f, Theme.TXT, true))
        texts.addView(Theme.space(this, 4f))
        texts.addView(Theme.text(this, subtitle, 11.5f, Theme.DIM))
        card.addView(badge)
        card.addView(Theme.space(this, 12f))
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(Theme.text(this, "‹", 25f, Theme.GOLD, false, Gravity.CENTER))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        card.layoutParams = lp
        return card
    }

    private fun buildSettingsPanel(): View {
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Theme.dp(this@MainActivity, 18f)
            setPadding(p, Theme.dp(this@MainActivity, 18f), p, Theme.dp(this@MainActivity, 24f))
        }
        col.addView(pageHeader("تنظیمات"))
        col.addView(Theme.space(this, 16f))
        col.addView(Theme.text(this, "تحلیل صوت", 12f, Theme.GOLD, true))
        col.addView(Theme.space(this, 8f))
        val engine = Theme.card(this, 15f)
        engine.addView(Theme.text(this, "موتور تحلیل صوت", 14f, Theme.TXT, true))
        engine.addView(Theme.space(this, 5f))
        engine.addView(Theme.text(this, "موتور موجود برنامه به همراه مسیر سازگاری قبلی حفظ شده است.", 11.5f, Theme.DIM))
        engine.addView(Theme.space(this, 10f))
        val openAnalysis = Theme.button(this, "رفتن به تحلیل فایل", false, true)
        openAnalysis.setOnClickListener { setPage("files"); showTab(0) }
        engine.addView(openAnalysis)
        col.addView(engine)
        col.addView(Theme.space(this, 16f))

        col.addView(Theme.text(this, "تنظیمات پخش", 12f, Theme.GOLD, true))
        col.addView(Theme.space(this, 8f))
        val playback = Theme.card(this, 15f)
        playback.addView(Theme.text(this, "سرعت و کنترل پخش", 14f, Theme.TXT, true))
        playback.addView(Theme.space(this, 5f))
        playback.addView(Theme.text(this, "سرعت پخش، پرش زمانی و دنبال‌کردن نمودار از داخل پخش‌کننده قابل تنظیم است.", 11.5f, Theme.DIM))
        col.addView(playback)
        col.addView(Theme.space(this, 16f))

        col.addView(Theme.text(this, "درباره", 12f, Theme.GOLD, true))
        val about = Theme.card(this, 15f)
        val aboutRow = Theme.row(this)
        aboutRow.addView(Theme.text(this, "دربارهٔ برنامه", 13f, Theme.TXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        aboutRow.addView(Theme.text(this, "‹", 22f, Theme.DIM, false, Gravity.CENTER))
        about.setOnClickListener { setPage("about") }
        about.addView(aboutRow)
        col.addView(about)
        scroll.addView(col)
        return scroll
    }

    private fun buildAboutPanel(): View {
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            val p = Theme.dp(this@MainActivity, 22f)
            setPadding(p, Theme.dp(this@MainActivity, 34f), p, Theme.dp(this@MainActivity, 26f))
        }
        col.addView(pageHeader("دربارهٔ برنامه"))
        col.addView(Theme.space(this, 38f))
        col.addView(Theme.text(this, "۞", 76f, Theme.GOLD, true, Gravity.CENTER))
        col.addView(Theme.space(this, 8f))
        col.addView(Theme.text(this, "همیار قاری", 28f, Theme.GOLD, true, Gravity.CENTER))
        col.addView(Theme.space(this, 6f))
        col.addView(Theme.text(this, "نسخهٔ $VERSION", 12f, Theme.DIM, false, Gravity.CENTER))
        col.addView(Theme.space(this, 28f))
        col.addView(Theme.text(this, "تحلیل گستره و زیر و بمی تلاوت قرآن کریم", 14f, Theme.TXT, true, Gravity.CENTER))
        col.addView(Theme.space(this, 10f))
        col.addView(Theme.text(this, "با همیار قاری، صدای تلاوت را تحلیل کنید و تغییرات زیر و بمی را روی نمودار ببینید.", 12f, Theme.DIM, false, Gravity.CENTER))
        col.addView(Theme.space(this, 24f))
        val privacy = Theme.card(this, 15f)
        privacy.addView(Theme.text(this, "♢  حریم خصوصی", 13f, Theme.TXT, true))
        privacy.addView(Theme.space(this, 5f))
        privacy.addView(Theme.text(this, "پردازش صوت در خود برنامه انجام می‌شود و اپ برای تحلیل به اینترنت نیاز ندارد.", 11.5f, Theme.DIM))
        col.addView(privacy)
        col.addView(Theme.space(this, 12f))
        val back = Theme.button(this, "بازگشت به خانه", true)
        back.setOnClickListener { setPage("home") }
        col.addView(back)
        scroll.addView(col)
        return scroll
    }

    private fun pageHeader(title: String): View {
        val row = Theme.row(this)
        val back = Theme.text(this, "‹", 28f, Theme.TXT, false, Gravity.CENTER).apply {
            background = Theme.rounded(0x0DFFFFFF, Theme.dpF(this@MainActivity, 12f), 1, Theme.LINE)
            minWidth = Theme.dp(this@MainActivity, 42f)
            minHeight = Theme.dp(this@MainActivity, 42f)
            setOnClickListener { setPage("home") }
        }
        row.addView(back)
        row.addView(Theme.space(this, 12f))
        row.addView(Theme.text(this, title, 18f, Theme.TXT, true, Gravity.CENTER))
        return row
    }

    private fun buildBottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Theme.rounded(0xFF080F1F.toInt(), 0f, 1, 0x223B4B6A)
            setPadding(Theme.dp(this@MainActivity, 8f), Theme.dp(this@MainActivity, 5f),
                Theme.dp(this@MainActivity, 8f), Theme.dp(this@MainActivity, 5f))
        }
        navSettings = navItem("⚙", "تنظیمات") { setPage("settings") }
        navFiles = navItem("▣", "فایل‌ها") { setPage("files") }
        navHome = navItem("⌂", "خانه") { setPage("home") }
        bar.addView(navSettings, LinearLayout.LayoutParams(0, Theme.dp(this, 52f), 1f))
        bar.addView(navFiles, LinearLayout.LayoutParams(0, Theme.dp(this, 52f), 1f))
        bar.addView(navHome, LinearLayout.LayoutParams(0, Theme.dp(this, 52f), 1f))
        return bar
    }

    private fun navItem(icon: String, label: String, action: () -> Unit): TextView {
        return Theme.text(this, "$icon\n$label", 10.5f, Theme.DIM, false, Gravity.CENTER).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
    }

    private fun setPage(page: String) {
        currentPage = page
        homePanel.visibility = if (page == "home") View.VISIBLE else View.GONE
        analysisScreen.visibility = if (page == "files") View.VISIBLE else View.GONE
        settingsPanel.visibility = if (page == "settings") View.VISIBLE else View.GONE
        aboutPanel.visibility = if (page == "about") View.VISIBLE else View.GONE
        val active = Theme.GOLD
        navHome.setTextColor(if (page == "home") active else Theme.DIM)
        navFiles.setTextColor(if (page == "files") active else Theme.DIM)
        navSettings.setTextColor(if (page == "settings" || page == "about") active else Theme.DIM)
    }

    private fun tabButton(label: String, active: Boolean): TextView {
        val t = Theme.text(this, label, 14f, if (active) Theme.INK else Theme.DIM, true, Gravity.CENTER)
        val h = Theme.dp(this, 42f)
        t.minHeight = h
        t.gravity = Gravity.CENTER
        val radius = Theme.dpF(this, 12f)
        t.background = if (active)
            Theme.gradient(Theme.GOLD, Theme.GOLD2, radius)
        else
            Theme.rounded(0x0DFFFFFF, radius, 1, Theme.LINE)
        return t
    }

    private fun showTab(index: Int) {
        val fileActive = index == 0
        tabFile.background = if (fileActive) Theme.gradient(Theme.GOLD, Theme.GOLD2, Theme.dpF(this, 12f))
        else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 12f), 1, Theme.LINE)
        tabFile.setTextColor(if (fileActive) Theme.INK else Theme.DIM)

        tabLive.background = if (!fileActive) Theme.gradient(Theme.GOLD, Theme.GOLD2, Theme.dpF(this, 12f))
        else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 12f), 1, Theme.LINE)
        tabLive.setTextColor(if (!fileActive) Theme.INK else Theme.DIM)

        panelFile.visibility = if (fileActive) View.VISIBLE else View.GONE
        panelLive.visibility = if (fileActive) View.GONE else View.VISIBLE
        if (fileActive) stopLive()
    }

    // ───────────────────────────── تب ۱: فایل ─────────────────────────────

    private fun buildFilePanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val pick = Theme.card(this)
        val pickTitle = Theme.text(this, "انتخاب فایل صوتی یا ویدیو", 14f, Theme.TXT, true)
        pick.addView(pickTitle)
        pick.addView(Theme.space(this, 4f))
        pick.addView(
            Theme.text(
                this,
                "mp3 · m4a · wav · flac · ogg · amr · mp4 · mkv · webm — همه از خودِ گوشی",
                11.5f, Theme.DIM
            )
        )
        pick.addView(Theme.space(this, 10f))

        val chooseRow = Theme.row(this)
        val chooseBtn = Theme.button(this, "📂 انتخاب فایل", false)
        chooseBtn.setOnClickListener { pickFile() }
        val demoBtn = Theme.button(this, "▶ نمونهٔ آزمایشی", false)
        demoBtn.setOnClickListener { loadDemo(demoBtn) }
        chooseRow.addView(chooseBtn, weight(1f))
        chooseRow.addView(Theme.space(this, 8f))
        chooseRow.addView(demoBtn, weight(1f))
        pick.addView(chooseRow)
        pick.addView(Theme.space(this, 8f))

        fileLabel = Theme.text(this, "فایلی انتخاب نشده", 12.5f, Theme.DIM)
        pick.addView(fileLabel)
        pick.addView(Theme.space(this, 8f))

        denoiseCheck = CheckBox(this)
        denoiseCheck.text = "کاهش نویز پس‌زمینه (کمی کندتر)"
        denoiseCheck.setTextColor(Theme.TXT)
        denoiseCheck.typeface = Theme.font(this, false)
        denoiseCheck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        denoiseCheck.buttonTintList = android.content.res.ColorStateList.valueOf(Theme.GOLD)
        pick.addView(denoiseCheck)
        pick.addView(Theme.space(this, 6f))

        analyzeBtn = Theme.button(this, "شروع تحلیل", true)
        analyzeBtn.isEnabled = false
        analyzeBtn.alpha = 0.45f
        analyzeBtn.setOnClickListener { startAnalysis() }
        pick.addView(analyzeBtn)

        progressBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.max = 1000
        progressBar.progress = 0
        progressBar.visibility = View.GONE
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 6f))
        lp.topMargin = Theme.dp(this, 12f)
        progressBar.layoutParams = lp
        pick.addView(progressBar)

        stageLabel = Theme.text(this, "", 12f, Theme.GOLD)
        stageLabel.visibility = View.GONE
        val lp2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp2.topMargin = Theme.dp(this, 6f)
        stageLabel.layoutParams = lp2
        pick.addView(stageLabel)

        col.addView(pick)
        col.addView(Theme.space(this, 12f))

        resultBox = LinearLayout(this)
        resultBox.orientation = LinearLayout.VERTICAL
        col.addView(resultBox)

        val hint = Theme.card(this)
        hint.addView(Theme.text(this, "چطور دقیق‌تر می‌شود؟", 13f, Theme.TXT, true))
        hint.addView(Theme.space(this, 4f))
        hint.addView(
            Theme.text(
                this,
                "• در جای ساکت بخوانید و میکروفون را نزدیک‌تر بگیرید.\n" +
                    "• دست‌کم ۲۰ ثانیه پیوسته بخوانید تا گستره معنادار شود.\n" +
                    "• فایل اصلی را حذف نکنید؛ اپ فقط می‌خواند و چیزی را تغییر نمی‌دهد.",
                11.5f, Theme.DIM
            )
        )
        hint.addView(Theme.space(this, 10f))
        val testBtn = Theme.button(this, "🔬 آزمون داخلی موتور روی همین گوشی", false, true)
        testBtn.setOnClickListener { runSelfTest(testBtn) }
        hint.addView(testBtn)
        hint.addView(Theme.space(this, 8f))
        val stLabel = Theme.text(this, "", 11.5f, Theme.TEAL)
        stLabel.visibility = View.GONE
        selfTestLabel = stLabel
        hint.addView(stLabel)

        val hintLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        hintLp.topMargin = Theme.dp(this, 12f)
        hint.layoutParams = hintLp
        col.addView(hint)
        return col
    }

    private var selfTestLabel: TextView? = null

    private fun runSelfTest(btn: TextView) {
        if (busy) {
            toast("یک تحلیل در جریان است")
            return
        }
        busy = true
        btn.text = "⏳ در حال آزمون…"
        val label = selfTestLabel
        label?.visibility = View.GONE
        Thread {
            val res = try {
                ir.hamyareqari.dsp.SelfTest.run()
            } catch (e: Exception) {
                ir.hamyareqari.dsp.SelfTest.Result(false, "⚠️ آزمون اجرا نشد", e.message ?: "")
            }
            ui.post {
                busy = false
                btn.text = "🔬 آزمون داخلی موتور روی همین گوشی"
                label?.visibility = View.VISIBLE
                label?.text = "${res.title}\n${res.detail}"
                label?.setTextColor(if (res.ok) Theme.TEAL else Theme.RED)
            }
        }.start()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("audio/*", "video/*"))
        }
        try {
            startActivityForResult(intent, REQ_PICK)
        } catch (e: Exception) {
            toast("انتخاب‌گر فایل در دسترس نیست")
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            pickUri = uri
            sourceName = displayName(uri)
            fileLabel.text = sourceName
            fileLabel.setTextColor(Theme.TXT)
            analyzeBtn.isEnabled = true
            analyzeBtn.alpha = 1f
            resultBox.removeAllViews()
            // رمزگشایی از همین حالا تا کاربر منتظر نماند
            loadUri(uri, autoAnalyze = false)
        }
    }

    private fun loadDemo(btn: TextView) {
        if (busy) {
            toast("یک تحلیل در جریان است؛ چند لحظه صبر کنید")
            return
        }
        busy = true
        btn.isEnabled = false
        sourceName = "نمونهٔ آزمایشی"
        pickUri = null
        playerToken++
        player?.release(); player = null
        sharePcm = null
        resultBox.removeAllViews()
        showProgress(0.0, "خواندن نمونهٔ آزمایشی…")
        Thread {
            try {
                val dec = AudioIo.decodeAsset(this, "demo.wav")
                decoded = dec
                sharePcm = dec.pcm
                ui.post {
                    busy = false
                    btn.isEnabled = true
                    hideProgress()
                    fileLabel.text = "نمونهٔ آزمایشی · ${PitchMath.faFixed(dec.durationSec, 1)} ثانیه"
                    fileLabel.setTextColor(Theme.TXT)
                    startAnalysis()
                }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    btn.isEnabled = true
                    hideProgress()
                    resultBox.addView(errorCard("نمونهٔ آزمایشی خوانده نشد", e.message ?: ""))
                }
            }
        }.start()
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val n = c.getString(idx)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (e: Exception) {
        }
        return uri.lastPathSegment ?: "صدا"
    }

    private fun loadUri(uri: Uri, autoAnalyze: Boolean) {
        if (busy) {
            toast("یک تحلیل در جریان است؛ چند لحظه صبر کنید")
            return
        }
        busy = true
        playerToken++
        playerLoading = false
        player?.release(); player = null
        sharePcm = null
        analyzeBtn.isEnabled = false
        analyzeBtn.alpha = 0.45f
        showProgress(0.0, "در حال خواندن فایل…")

        Thread {
            try {
                val dec = AudioIo.decode(this, uri) { p ->
                    ui.post { showProgress(p * 0.35, "در حال خواندن فایل… ${(p * 100).roundToInt()}٪") }
                }
                decoded = dec
                sharePcm = dec.pcm
                ui.post {
                    hideProgress()
                    busy = false
                    analyzeBtn.isEnabled = true
                    analyzeBtn.alpha = 1f
                    fileLabel.text = buildString {
                        append(sourceName)
                        append(" · ")
                        append(PitchMath.faFixed(dec.durationSec, 1))
                        append(" ثانیه")
                        if (dec.sourceRate != dec.sampleRate)
                            append(" · منبع ${PitchMath.faDigits(dec.sourceRate / 1000)}k")
                        if (dec.truncated) append(" · ۴۰ دقیقهٔ اول")
                    }
                    if (autoAnalyze) startAnalysis()
                }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    hideProgress()
                    resultBox.removeAllViews()
                    resultBox.addView(errorCard("فایل خوانده نشد", e.message ?: "قالب پشتیبانی نمی‌شود"))
                }
            }
        }.start()
    }

    private var sharePcm: ShortArray? = null

    private fun showProgress(frac: Double, label: String) {
        progressBar.visibility = View.VISIBLE
        progressBar.progress = (frac.coerceIn(0.0, 1.0) * 1000).toInt()
        stageLabel.visibility = View.VISIBLE
        stageLabel.text = label
    }

    private fun hideProgress() {
        progressBar.visibility = View.GONE
        stageLabel.visibility = View.GONE
    }

    private fun startAnalysis() {
        val dec = decoded
        if (dec == null) {
            toast("اول یک فایل انتخاب کنید")
            return
        }
        if (busy) return
        busy = true
        resultBox.removeAllViews()
        showProgress(0.0, "آماده‌سازی…")
        val started = System.currentTimeMillis()

        Thread {
            try {
                val sig = ShortSignal(dec.pcm, dec.sampleRate)
                val rep = Analyzer.analyzeSignal(sig, denoiseCheck.isChecked) { stage, p ->
                    ui.post {
                        val (label, base, span) = stageInfo(stage)
                        showProgress(base + span * p, label)
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                ui.post {
                    report = rep
                    lastElapsedMs = elapsed
                    busy = false
                    hideProgress()
                    renderReport(rep, elapsed)
                }
            } catch (e: OutOfMemoryError) {
                ui.post {
                    busy = false
                    hideProgress()
                    resultBox.addView(errorCard("حافظه کافی نبود", "فایل کوتاه‌تری را امتحان کنید"))
                }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    hideProgress()
                    resultBox.addView(errorCard("تحلیل انجام نشد", e.message ?: "خطای نامشخص"))
                }
            }
        }.start()
    }

    private fun stageInfo(stage: String): Triple<String, Double, Double> = when (stage) {
        "preprocess" -> Triple("آماده‌سازی صدا…", 0.0, 0.05)
        "yin" -> Triple("ردیابی فرکانس پایه…", 0.05, 0.55)
        "ladder" -> Triple("اصلاح نردبان هارمونیک…", 0.62, 0.10)
        "notes" -> Triple("تفکیک نت‌ها و پالایش هر نت…", 0.74, 0.10)
        "stats" -> Triple("آمار گستره و سهم نت‌ها…", 0.86, 0.04)
        "agreement" -> Triple("هم‌سنجی با موتور دوم…", 0.93, 0.05)
        else -> Triple("تمام شد", 1.0, 0.0)
    }

    // ───────────────────────────── نمایش نتیجه ─────────────────────────────

    private fun renderReport(r: Report, elapsedMs: Long) {
        resultBox.removeAllViews()

        // کارت قهرمان: بالاترین/پایین‌ترین/گستره
        val hero = Theme.card(this)
        val topRow = Theme.row(this)
        topRow.addView(heroCell("بالاترین نت", r.highest?.name ?: "—", r.highest?.f0, Theme.GOLD), weight(1f))
        topRow.addView(Theme.space(this, 8f))
        topRow.addView(heroCell("پایین‌ترین نت", r.lowest?.name ?: "—", r.lowest?.f0, Theme.TEAL), weight(1f))
        hero.addView(topRow)
        hero.addView(Theme.divider(this))

        val rangeRow = Theme.row(this)
        val big = Theme.text(this, PitchMath.faFixed(r.spanSemitones, 1), 30f, Theme.TXT, true, Gravity.CENTER)
        val unit = Theme.text(this, "نیم‌پرده", 12f, Theme.DIM, false, Gravity.CENTER)
        val rangeCol = LinearLayout(this)
        rangeCol.orientation = LinearLayout.VERTICAL
        rangeCol.gravity = Gravity.CENTER_HORIZONTAL
        rangeCol.addView(big)
        rangeCol.addView(unit)
        rangeRow.addView(rangeCol, weight(1f))
        val rangeText = Theme.text(
            this,
            if (r.fullOctaves > 0)
                "${PitchMath.faDigits(r.fullOctaves)} اکتاو و ${PitchMath.faFixed(r.restSemitones, 1)} نیم‌پرده"
            else "${PitchMath.faFixed(r.spanSemitones, 1)} نیم‌پرده",
            12.5f, Theme.GOLD2, true, Gravity.CENTER
        )
        val centerText = Theme.text(
            this,
            "مرکز صدا: ${r.centerName} (${PitchMath.faFixed(r.centerHz, 0)} هرتز)",
            11.5f, Theme.DIM, false, Gravity.CENTER
        )
        val rightCol = LinearLayout(this)
        rightCol.orientation = LinearLayout.VERTICAL
        rightCol.gravity = Gravity.CENTER_HORIZONTAL
        rightCol.addView(rangeText)
        rightCol.addView(Theme.space(this, 2f))
        rightCol.addView(centerText)
        rangeRow.addView(rightCol, weight(1.4f))
        hero.addView(rangeRow)

        hero.addView(Theme.space(this, 10f))
        val strip = RangeStripView(this)
        strip.lowMidi = r.lowest?.midi ?: -1
        strip.highMidi = r.highest?.midi ?: -1
        strip.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 46f))
        hero.addView(strip)

        hero.addView(Theme.space(this, 8f))
        val chips = Theme.row(this)
        chips.addView(Theme.chip(this, statusLabel(r.quality.status), statusColor(r.quality.status)))
        chips.addView(Theme.space(this, 6f))
        chips.addView(Theme.chip(this, "دقت: ${PitchMath.faDigits((r.quality.agreementPct / 10).roundToInt())}/۱۰", Theme.VIOLET))
        chips.addView(Theme.space(this, 6f))
        chips.addView(Theme.chip(this, "${r.noteCount} نت", Theme.TEAL))
        hero.addView(chips)
        resultBox.addView(hero)
        resultBox.addView(Theme.space(this, 12f))

        // کارت آمار
        val stats = Theme.card(this)
        stats.addView(Theme.text(this, "گستره به همهٔ واحدها", 13.5f, Theme.TXT, true))
        stats.addView(Theme.space(this, 8f))
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        grid.addView(statLine("پرده", PitchMath.faFixed(r.spanSemitones / 2.0, 1)))
        grid.addView(statLine("اکتاو", PitchMath.faFixed(r.spanOctaves, 2)))
        grid.addView(statLine("سنت", PitchMath.faFixed(r.spanCents, 0)))
        grid.addView(statLine("ربع‌پرده", PitchMath.faFixed(r.quarterTones, 1)))
        grid.addView(statLine("درصد از یک اکتاو", PitchMath.faFixed(r.ofOctavePct, 0) + "٪"))
        grid.addView(statLine("درجه (اکتاو = ۸ درجه)", PitchMath.spanInDegrees(r.spanSemitones).text))
        grid.addView(statLine("نت‌های خوانده‌شده", PitchMath.faDigits(r.noteCount)))
        grid.addView(statLine("مدت خواندن", PitchMath.faFixed(r.sungSec, 0) + " از " + PitchMath.faFixed(r.durationSec, 0) + " ثانیه"))
        grid.addView(statLine("مکث‌ها (≥۰٫۳ ثانیه)", PitchMath.faDigits(r.pauseCount) + " · بلندترین " + PitchMath.faFixed(r.longestPause, 1) + " ثانیه"))
        if (r.validShare > 0.0) {
            grid.addView(
                statLine(
                    "سهم صدای معتبر",
                    PitchMath.faFixed(r.validShare * 100.0, 0) + "٪" +
                        (if (r.estimatedShare > 0.001)
                            " (تخمینی " + PitchMath.faFixed(r.estimatedShare * 100.0, 1) + "٪)" else "")
                )
            )
        }
        stats.addView(grid)

        // نت‌های لحظه‌ای خام: جدا از گسترهٔ واقعی گزارش می‌شوند
        val rh = r.rawHighest
        val rl = r.rawLowest
        if (rh != null && rl != null && (rh.name != (r.highest?.name ?: "") || rl.name != (r.lowest?.name ?: ""))) {
            stats.addView(Theme.space(this, 6f))
            stats.addView(Theme.divider(this))
            stats.addView(
                Theme.text(
                    this,
                    "بدون فیلتر: بالاترین ${rh.name} (${PitchMath.faFixed(rh.duration, 2)} ثانیه) · " +
                        "پایین‌ترین ${rl.name} (${PitchMath.faFixed(rl.duration, 2)} ثانیه) — " +
                        "این‌ها جیغ/لغزش لحظه‌ای‌اند و در گستره شمرده نشدند.",
                    10.5f, 0x88FFFFFF.toInt()
                )
            )
        }
        resultBox.addView(stats)
        resultBox.addView(Theme.space(this, 12f))

        // کارت نمودار
        val chartCard = Theme.card(this)
        chartCard.addView(Theme.text(this, "ردِّ صدا", 13.5f, Theme.TXT, true))
        chartCard.addView(
            Theme.text(this, "روی نمودار بزنید تا از همان لحظه پخش شود", 11f, Theme.DIM)
        )
        chartCard.addView(Theme.space(this, 6f))
        val chart = ChartView(this)
        chart.setReport(r)
        chart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 210f))
        chartCard.addView(chart)

        // ══ پخش‌کنندهٔ صوتی ══
        val playerBar = AudioPlayerBar(this)
        playerBar.setTotal(r.durationSec)
        playerBar.setWaveform(sharePcm)
        playerBar.setPitchTrace(chart.spec)
        val lpBar = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpBar.topMargin = Theme.dp(this, 10f)
        playerBar.layoutParams = lpBar
        chartCard.addView(playerBar)
        audioBar = playerBar

        playerBar.onPlayPause = {
            val p = ensurePlayer() ?: run {
                playerBar.setPlaying(false)
                null
            }
            if (p != null) {
                if (p.isPlaying) {
                    p.pause()
                    stopTicker()
                } else {
                    if (seekToEnd()) p.playFrom(0.0)
                    p.setSpeed(playerBar.currentSpeed())
                    startTicker(chart, playerBar)
                }
            }
        }
        playerBar.onRestart = {
            val p = ensurePlayer()
            if (p != null) {
                p.setSpeed(playerBar.currentSpeed())
                p.playFrom(0.0)
                chart.setPlayhead(0.0)
                startTicker(chart, playerBar)
            }
        }
        playerBar.onSeek = { sec ->
            val p = ensurePlayer()
            if (p != null) {
                p.setSpeed(playerBar.currentSpeed())
                p.playFrom(sec)
                chart.setPlayhead(sec)
                startTicker(chart, playerBar)
            }
        }
        playerBar.onSpeed = { v -> player?.setSpeed(v) }
        playerBar.onTraceSeek = { sec ->
            val p = ensurePlayer()
            if (p != null) {
                p.setSpeed(playerBar.currentSpeed())
                p.playFrom(sec)
                chart.setPlayhead(sec)
                playerBar.setPosition(sec)
                startTicker(chart, playerBar)
            }
        }
        playerBar.onSkip = { delta ->
            val p = ensurePlayer()
            if (p != null) {
                val target = (p.positionSec() + delta).coerceIn(0.0, p.durationSec())
                p.setSpeed(playerBar.currentSpeed())
                p.playFrom(target)
                chart.setPlayhead(target)
                playerBar.setPosition(target)
                startTicker(chart, playerBar)
            }
        }
        playerBar.onFollowToggle = { on -> chart.followPlayhead = on }
        playerBar.setFollowing(chart.followPlayhead)

        // زوم زمانی و ارتفاع (مثل نسخهٔ وب)
        val zoomRow = Theme.row(this)
        val zoomOutT = Theme.button(this, "⏱ −", false, true)
        val zoomInT = Theme.button(this, "⏱ +", false, true)
        val zoomOutP = Theme.button(this, "↕ −", false, true)
        val zoomInP = Theme.button(this, "↕ +", false, true)
        val zoomReset = Theme.button(this, "↺", false, true)
        val zoomLabel = Theme.text(this, chart.zoomLabel(), 10.5f, Theme.DIM, false, Gravity.END)
        zoomRow.addView(zoomOutT)
        zoomRow.addView(Theme.space(this, 6f))
        zoomRow.addView(zoomInT)
        zoomRow.addView(Theme.space(this, 6f))
        zoomRow.addView(zoomOutP)
        zoomRow.addView(Theme.space(this, 6f))
        zoomRow.addView(zoomInP)
        zoomRow.addView(Theme.space(this, 6f))
        zoomRow.addView(zoomReset)
        zoomRow.addView(zoomLabel, weight(1f))
        val lpZoom = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpZoom.topMargin = Theme.dp(this, 6f)
        zoomRow.layoutParams = lpZoom
        chartCard.addView(zoomRow)

        zoomInT.setOnClickListener {
            // با زوم، کادر خودش دنبال صدا می‌رود (قفل نمی‌کند)
            chart.zoomTime(2.0)
            zoomLabel.text = chart.zoomLabel()
            ensureTicker(chart)
        }
        zoomOutT.setOnClickListener {
            chart.zoomTime(0.5)
            zoomLabel.text = chart.zoomLabel()
            ensureTicker(chart)
        }
        zoomInP.setOnClickListener {
            chart.zoomPitch(1.4)
            zoomLabel.text = chart.zoomLabel()
        }
        zoomOutP.setOnClickListener {
            chart.zoomPitch(1 / 1.4)
            zoomLabel.text = chart.zoomLabel()
        }
        zoomReset.setOnClickListener {
            chart.zoomReset()
            zoomLabel.text = chart.zoomLabel()
            ensureTicker(chart)
        }

        chartRef = chart
        resultBox.addView(chartCard)
        resultBox.addView(Theme.space(this, 12f))

        // ══ کارت ویدیوی ردِّ صدا ══
        resultBox.addView(buildVideoCard(r, chart))
        resultBox.addView(Theme.space(this, 12f))

        // لمس نمودار = پخش از همان لحظه
        chart.onSeek = { sec ->
            val p = ensurePlayer()
            if (p != null) {
                p.setSpeed(audioBar?.currentSpeed() ?: 1f)
                p.playFrom(sec)
                chart.setPlayhead(sec)
                startTicker(chart, audioBar!!)
            }
        }

        // کارت سهم نت‌ها
        if (r.shares.isNotEmpty()) {
            val sharesCard = Theme.card(this)
            sharesCard.addView(Theme.text(this, "کدام نت چقدر خوانده شد", 13.5f, Theme.TXT, true))
            sharesCard.addView(Theme.space(this, 8f))
            val maxShare = r.shares.first().sharePct
            for (s in r.shares.take(8)) {
                sharesCard.addView(shareRow(s.name, s.sharePct, maxShare, s.count))
            }
            resultBox.addView(sharesCard)
            resultBox.addView(Theme.space(this, 12f))
        }

        // هشدارها
        if (r.quality.warnings.isNotEmpty()) {
            val warn = Theme.card(this)
            warn.addView(Theme.text(this, "نکته‌ها", 13f, Theme.GOLD2, true))
            warn.addView(Theme.space(this, 4f))
            for (w in r.quality.warnings) {
                warn.addView(Theme.text(this, "• $w", 11.5f, Theme.DIM))
            }
            resultBox.addView(warn)
            resultBox.addView(Theme.space(this, 12f))
        }

        // خروجی‌ها
        val export = Theme.card(this)
        export.addView(Theme.text(this, "ذخیره و اشتراک‌گذاری", 13.5f, Theme.TXT, true))
        export.addView(Theme.space(this, 8f))
        val row1 = Theme.row(this)
        val pngBtn = Theme.button(this, "🖼 تصویر تحلیل", false, true)
        val jsonBtn = Theme.button(this, "📄 گزارش JSON", false, true)
        row1.addView(pngBtn, weight(1f))
        row1.addView(Theme.space(this, 8f))
        row1.addView(jsonBtn, weight(1f))
        export.addView(row1)
        export.addView(Theme.space(this, 8f))
        val row2 = Theme.row(this)
        val originalBtn = Theme.button(this, "🎵 فایل اصلی", false, true)
        val shareBtn = Theme.button(this, "🔗 اشتراک‌گذاری گزارش", false, true)
        row2.addView(originalBtn, weight(1f))
        row2.addView(Theme.space(this, 8f))
        row2.addView(shareBtn, weight(1f))
        val lpRow2 = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpRow2.topMargin = Theme.dp(this, 8f)
        row2.layoutParams = lpRow2
        export.addView(row2)

        originalBtn.setOnClickListener {
            val uri = pickUri
            if (uri == null) {
                toast("فایل اصلی در دسترس نیست (این تحلیل از ضبط زنده است)")
            } else {
                withWrite {
                    try {
                        val saved = Persistence.saveOriginal(this, uri, sourceName)
                        toast("✅ کپی شد: ${saved.displayPath}")
                    } catch (e: Exception) {
                        toast("ذخیرهٔ فایل اصلی ممکن نشد: ${e.message}")
                    }
                }
            }
        }

        pngBtn.setOnClickListener { exportPng(r, chart) }
        jsonBtn.setOnClickListener { exportJson(r) }
        shareBtn.setOnClickListener { exportJson(r, share = true) }
        resultBox.addView(export)

        prewarmPlayer()

        resultBox.addView(Theme.space(this, 10f))
        resultBox.addView(
            Theme.text(
                this,
                "زمان تحلیل: ${PitchMath.faFixed(elapsedMs / 1000.0, 1)} ثانیه · " +
                    "SNR ${PitchMath.faFixed(r.quality.snrDb, 0)}dB · اصلاح اکتاو: " +
                    "${PitchMath.faDigits(r.quality.octaveDown + r.quality.octaveUp)}",
                10.5f, 0x66FFFFFF.toInt()
            )
        )
    }

    private var playerToken = 0
    private var playerLoading = false

    /** فایل WAV نمونه را در پس‌زمینه می‌سازد تا دکمهٔ پخش بی‌درنگ جواب بدهد */
    private fun prewarmPlayer() {
        val pcm = sharePcm ?: return
        if (pcm.size > AudioIo.TARGET_RATE * 900) return
        val token = ++playerToken
        playerLoading = true
        Thread {
            val candidate = AudioIo.Player(this)
            val ok = candidate.load(pcm, AudioIo.TARGET_RATE)
            ui.post {
                if (token != playerToken) {
                    candidate.release()
                    return@post
                }
                playerLoading = false
                if (ok) {
                    player?.release()
                    player = candidate
                } else {
                    candidate.release()
                }
            }
        }.start()
    }

    private fun ensurePlayer(): AudioIo.Player? {
        val pcm = sharePcm ?: return null
        if (pcm.size > AudioIo.TARGET_RATE * 900) {
            toast("پخش نمونه برای فایل‌های بیش از ۱۵ دقیقه خاموش است")
            return null
        }
        player?.let { return it }
        if (playerLoading) {
            toast("پخش در حال آماده‌سازی است؛ یک لحظه بعد بزنید")
            return null
        }
        val p = AudioIo.Player(this)
        return if (p.load(pcm, AudioIo.TARGET_RATE)) {
            player = p
            p
        } else {
            p.release()
            toast("پخش ممکن نشد")
            null
        }
    }

    private var tickBasePos = 0.0
    private var tickBaseAt = 0L
    private var tickLastSync = 0L

    /**
     * انیمیشن ۶۰ فریمی: موقعیت بین دو هم‌گام‌سازی با ساعت پیش‌بینی می‌شود
     * تا نور طلایی نرم حرکت کند (نه پرشی هر ۸۰ میلی‌ثانیه).
     */
    private fun startTicker(chart: ChartView, bar: AudioPlayerBar) {
        stopTicker()
        audioBar = bar
        tickBasePos = player?.positionSec() ?: 0.0
        tickBaseAt = System.currentTimeMillis()
        tickLastSync = tickBaseAt
        val total = chart.totalDuration()
        player?.onComplete {
            ui.post {
                stopTicker()
                chart.setPlayhead(total)
                bar.setPosition(total)
                bar.setPlaying(false)
            }
        }
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                val now = System.currentTimeMillis()
                // هر ۴۰۰ms با خود پخش‌کننده هم‌گام می‌شویم (جلوگیری از رانش)
                if (now - tickLastSync > 400) {
                    tickBasePos = p.positionSec()
                    tickBaseAt = now
                    tickLastSync = now
                }
                val playing = p.isPlaying
                val pos = (tickBasePos + (now - tickBaseAt) / 1000.0 * bar.currentSpeed()).coerceIn(0.0, total)
                chart.setPlayhead(pos)
                bar.setPosition(pos)
                if (playing) {
                    ui.postDelayed(this, 16)
                } else {
                    bar.setPlaying(false)
                    ticker = null
                }
            }
        }
        ticker = r
        bar.setPlaying(true)
        ui.post(r)
    }

    /** اگر پخش در جریان نیست، فقط نقطه و نوار را در جای فعلی نگه می‌دارد */
    private fun ensureTicker(chart: ChartView) {
        val bar = audioBar ?: return
        if (ticker != null || player?.isPlaying == true) return
        val pos = chart.playhead.takeIf { it >= 0 } ?: 0.0
        bar.setPosition(pos)
    }

    /** آیا پخش به انتها رسیده است؟ */
    private fun seekToEnd(): Boolean {
        val p = player ?: return true
        val pos = p.positionSec()
        return pos >= p.durationSec() - 0.25 || !p.isPlaying && pos > 0.0
    }

    private fun stopTicker() {
        ticker?.let { ui.removeCallbacks(it) }
        ticker = null
    }

    private fun heroCell(title: String, note: String, hz: Double?, color: Int): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.gravity = Gravity.CENTER_HORIZONTAL
        c.setPadding(Theme.dp(this, 6f), Theme.dp(this, 8f), Theme.dp(this, 6f), Theme.dp(this, 8f))
        c.background = Theme.rounded(Theme.withAlpha(color, 0.08f), Theme.dpF(this, 14f), 1, Theme.withAlpha(color, 0.30f))
        c.addView(Theme.text(this, title, 11f, Theme.DIM, false, Gravity.CENTER))
        c.addView(Theme.space(this, 2f))
        c.addView(Theme.text(this, note, 27f, color, true, Gravity.CENTER))
        if (hz != null && hz > 0) {
            c.addView(Theme.text(this, "${PitchMath.faFixed(hz, 1)} هرتز", 10.5f, Theme.DIM, false, Gravity.CENTER))
            c.addView(Theme.text(this, PitchMath.solfege(hz), 10.5f, Theme.withAlpha(color, 0.8f), true, Gravity.CENTER))
        }
        return c
    }

    private fun statLine(label: String, value: String): View {
        val r = Theme.row(this)
        r.addView(Theme.text(this, label, 12f, Theme.DIM), weight(1f))
        r.addView(Theme.text(this, value, 12.5f, Theme.TXT, true, Gravity.END), weight(1f))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Theme.dp(this, 5f)
        r.layoutParams = lp
        return r
    }

    private fun shareRow(name: String, pct: Double, maxPct: Double, count: Int): View {
        val r = Theme.row(this)
        val nameView = Theme.text(this, name, 12.5f, Theme.TXT, true)
        nameView.width = Theme.dp(this, 44f)
        r.addView(nameView)
        val barWrap = FrameLayout(this)
        val barBg = View(this)
        barBg.setBackgroundColor(0x14FFFFFF)
        barWrap.addView(barBg, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 8f), Gravity.CENTER_VERTICAL))
        val bar = View(this)
        val fill = (if (maxPct > 0) pct / maxPct else 0.0).coerceIn(0.02, 1.0)
        val barLp = FrameLayout.LayoutParams(0, Theme.dp(this, 8f), Gravity.CENTER_VERTICAL)
        bar.background = Theme.rounded(Theme.GOLD, Theme.dpF(this, 4f))
        barWrap.addView(bar, barLp)
        barWrap.post { barLp.width = (barWrap.width * fill).toInt(); bar.layoutParams = barLp }
        r.addView(barWrap, weight(1f))
        val pctView = Theme.text(this, "${PitchMath.faFixed(pct, 0)}٪ · ${PitchMath.faDigits(count)}×", 10.5f, Theme.DIM, false, Gravity.END)
        pctView.width = Theme.dp(this, 76f)
        r.addView(pctView)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = Theme.dp(this, 6f)
        r.layoutParams = lp
        return r
    }

    private fun statusLabel(s: String): String = when (s) {
        "excellent" -> "کیفیت عالی"
        "good" -> "کیفیت خوب"
        "fair" -> "کیفیت متوسط"
        else -> "کیفیت ضعیف"
    }

    private fun statusColor(s: String): Int = when (s) {
        "excellent" -> Theme.TEAL
        "good" -> Theme.GOLD
        "fair" -> 0xFFFFB86B.toInt()
        else -> Theme.RED
    }

    private fun errorCard(title: String, msg: String): View {
        val c = Theme.card(this)
        c.background = Theme.rounded(0x1AFF7B7B, Theme.dpF(this, 18f), 1, 0x55FF7B7B)
        c.addView(Theme.text(this, "⚠️ $title", 13.5f, Theme.RED, true))
        c.addView(Theme.space(this, 4f))
        c.addView(Theme.text(this, msg, 11.5f, Theme.DIM))
        return c
    }

    private fun weight(f: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, f)


    // ───────────────────────────── ویدیوی ردِّ صدا ─────────────────────────

    private var videoQuality = VideoMaker.Quality.SD
    private var videoBusy = false

    private fun buildVideoCard(r: Report, chart: ChartView): View {
        val card = Theme.card(this)
        card.addView(Theme.text(this, "🎬 ویدیوی ردِّ صدا", 13.5f, Theme.TXT, true))
        card.addView(
            Theme.text(
                this,
                "همان نمودار، ضبط‌شده در یک فایل MP4 هم‌گام با صدا — کاملاً روی همین گوشی",
                11f, Theme.DIM
            )
        )
        card.addView(Theme.space(this, 8f))

        val qualityRow = Theme.row(this)
        val sdBtn = Theme.button(this, VideoMaker.Quality.SD.label, true, true)
        val hdBtn = Theme.button(this, VideoMaker.Quality.HD.label, false, true)
        qualityRow.addView(sdBtn, weight(1f))
        qualityRow.addView(Theme.space(this, 8f))
        qualityRow.addView(hdBtn, weight(1f))
        card.addView(qualityRow)
        card.addView(Theme.space(this, 8f))

        val pickQuality = { q: VideoMaker.Quality ->
            videoQuality = q
            sdBtn.background = if (q == VideoMaker.Quality.SD) Theme.primaryButtonBg(this)
            else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 15f), 1, Theme.LINE)
            sdBtn.setTextColor(if (q == VideoMaker.Quality.SD) Theme.INK else Theme.TXT)
            hdBtn.background = if (q == VideoMaker.Quality.HD) Theme.primaryButtonBg(this)
            else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 15f), 1, Theme.LINE)
            hdBtn.setTextColor(if (q == VideoMaker.Quality.HD) Theme.INK else Theme.TXT)
        }
        sdBtn.setOnClickListener { pickQuality(VideoMaker.Quality.SD) }
        hdBtn.setOnClickListener { pickQuality(VideoMaker.Quality.HD) }

        val buildBtn = Theme.button(this, "🎬 ساخت ویدیو", true)
        card.addView(buildBtn)

        val vProgress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        vProgress.max = 1000
        vProgress.visibility = View.GONE
        val lpBar = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 6f))
        lpBar.topMargin = Theme.dp(this, 10f)
        vProgress.layoutParams = lpBar
        card.addView(vProgress)

        val vStage = Theme.text(this, "", 11.5f, Theme.GOLD)
        vStage.visibility = View.GONE
        card.addView(vStage)

        val videoBox = LinearLayout(this)
        videoBox.orientation = LinearLayout.VERTICAL
        videoBox.visibility = View.GONE
        val lpBox = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpBox.topMargin = Theme.dp(this, 10f)
        videoBox.layoutParams = lpBox
        card.addView(videoBox)

        buildBtn.setOnClickListener {
            if (videoBusy) {
                toast("ساخت ویدیو در جریان است")
                return@setOnClickListener
            }
            val pcm = sharePcm
            if (pcm == null) {
                toast("اول فایل را تحلیل کنید")
                return@setOnClickListener
            }
            videoBusy = true
            buildBtn.isEnabled = false
            buildBtn.alpha = 0.5f
            vProgress.visibility = View.VISIBLE
            vProgress.progress = 0
            vStage.visibility = View.VISIBLE
            vStage.text = "آماده‌سازی کدگذار…"
            videoBox.visibility = View.GONE
            videoBox.removeAllViews()

            // نمودارِ اختصاصیِ رندر: رسم در ترد پس‌زمینه نباید با نمودارِ روی
            // صفحه (که ترد رابط کاربری هم آن را می‌کشد) قاطی شود.
            val renderChart = ChartView(this).apply {
                setReport(r)
                setPlayhead(0.0)
            }

            Thread {
                try {
                    val maker = VideoMaker(this)
                    val res = maker.build(r, pcm, renderChart, sourceName, videoQuality) { p, label ->
                        ui.post {
                            vProgress.progress = (p.coerceIn(0.0, 1.0) * 1000).toInt()
                            vStage.text = label
                        }
                    }
                    ui.post {
                        videoBusy = false
                        buildBtn.isEnabled = true
                        buildBtn.alpha = 1f
                        vProgress.visibility = View.GONE
                        vStage.visibility = View.GONE
                        videoFile = res.file
                        showVideoResult(videoBox, res, r)
                    }
                } catch (e: OutOfMemoryError) {
                    ui.post { videoFailed(buildBtn, vProgress, vStage, "حافظه کافی نبود") }
                } catch (e: Throwable) {
                    // هر خطایی (حتی خطای بومیِ کدگذار) به پیام تبدیل می‌شود تا
                    // اپ بسته نشود.
                    val msg = e.message?.takeIf { it.isNotBlank() }
                        ?: e.javaClass.simpleName
                    ui.post { videoFailed(buildBtn, vProgress, vStage, msg) }
                }
            }.start()
        }
        return card
    }

    private fun videoFailed(btn: TextView, bar: android.widget.ProgressBar, stage: TextView, msg: String) {
        videoBusy = false
        btn.isEnabled = true
        btn.alpha = 1f
        bar.visibility = View.GONE
        stage.visibility = View.VISIBLE
        stage.setTextColor(Theme.RED)
        stage.text = "ویدیو ساخته نشد: $msg"
    }

    private fun showVideoResult(box: LinearLayout, res: VideoMaker.Result, r: Report) {
        box.removeAllViews()
        box.visibility = View.VISIBLE
        val vv = android.widget.VideoView(this)
        vv.setVideoPath(res.file.absolutePath)
        val media = android.widget.MediaController(this)
        media.setAnchorView(vv)
        vv.setMediaController(media)
        vv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 210f))
        box.addView(vv)
        vv.start()

        box.addView(Theme.space(this, 6f))
        box.addView(
            Theme.text(
                this,
                "ساخته شد: ${PitchMath.faDigits(res.frames)} فریم · ${PitchMath.faFixed(res.ms / 1000.0, 1)} ثانیه رندر · " +
                    (if (res.withAudio) "با صدا" else "بدون صدا") +
                    " · ${PitchMath.faFixed(res.file.length() / 1_048_576.0, 1)} مگابایت\n" +
                    "کدگذار: ${res.codecName} · ${res.colorFormat}",
                10.5f, Theme.DIM
            )
        )
        box.addView(Theme.space(this, 8f))

        val row = Theme.row(this)
        val save = Theme.button(this, "💾 ذخیره در گالری", true, true)
        val share = Theme.button(this, "🔗 اشتراک‌گذاری", false, true)
        row.addView(save, weight(1f))
        row.addView(Theme.space(this, 8f))
        row.addView(share, weight(1f))
        box.addView(row)

        save.setOnClickListener {
            withWrite {
                try {
                    val saved = VideoMaker(this).saveToGallery(
                        res.file, Persistence.safeName("همیار-قاری-${baseName()}", "mp4")
                    )
                    toast("✅ ویدیو ذخیره شد: ${saved.displayPath}")
                } catch (e: Exception) {
                    toast("ذخیرهٔ ویدیو ممکن نشد: ${e.message}")
                }
            }
        }
        share.setOnClickListener {
            try {
                val saved = VideoMaker(this).saveToGallery(
                    res.file, Persistence.safeName("همیار-قاری-${baseName()}", "mp4")
                )
                Persistence.share(this, saved)
            } catch (e: Exception) {
                toast("اشتراک‌گذاری ممکن نشد: ${e.message}")
            }
        }
    }

    // ───────────────────────────── خروجی‌ها ─────────────────────────────

    private var pendingWrite: (() -> Unit)? = null

    /** ذخیره در اندروید ۹ و پایین‌تر به مجوز نوشتن نیاز دارد */
    private fun withWrite(block: () -> Unit) {
        if (!Persistence.needsWritePermission() ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            block()
            return
        }
        pendingWrite = block
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE)
    }

    private fun exportPng(r: Report, chart: ChartView) = withWrite { doExportPng(r, chart) }

    private fun doExportPng(r: Report, chart: ChartView) {
        try {
            val lines = listOf(
                "بالاترین: ${r.highest?.name ?: "—"}   ·   پایین‌ترین: ${r.lowest?.name ?: "—"}",
                "گستره: ${PitchMath.faFixed(r.spanSemitones, 1)} نیم‌پرده (${PitchMath.faFixed(r.spanOctaves, 2)} اکتاو)",
                "مرکز صدا: ${r.centerName} · ${r.noteCount} نت · ${PitchMath.faFixed(r.durationSec, 0)} ثانیه"
            )
            val bmp = chart.renderToBitmap(1600, 2400, "ردِّ صدا — ${sourceName}", lines)
            val saved = Persistence.saveBitmap(this, bmp, Persistence.safeName("همیار-قاری-${baseName()}", "png"))
            toast("✅ ذخیره شد: ${saved.displayPath}")
        } catch (e: Exception) {
            toast("ذخیرهٔ تصویر ممکن نشد: ${e.message}")
        }
    }

    private fun exportJson(r: Report, share: Boolean = false) = withWrite { doExportJson(r, share) }

    private fun doExportJson(r: Report, share: Boolean) {
        try {
            val json = Json.report(r, sourceName, VERSION)
            val saved = Persistence.saveJson(this, json, Persistence.safeName("همیار-قاری-${baseName()}", "json"))
            if (share) Persistence.share(this, saved) else toast("✅ ذخیره شد: ${saved.displayPath}")
        } catch (e: Exception) {
            toast("ذخیرهٔ گزارش ممکن نشد: ${e.message}")
        }
    }

    private fun baseName(): String = sourceName.substringBeforeLast('.').take(28)

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    // ───────────────────────────── تب ۲: زنده ─────────────────────────────

    private fun buildLivePanel(): View {
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL

        val now = Theme.card(this)
        liveNoteName = Theme.text(this, "—", 46f, Theme.GOLD, true, Gravity.CENTER)
        liveSolfege = Theme.text(this, "منتظر شروع…", 13f, Theme.DIM, false, Gravity.CENTER)
        liveCents = Theme.text(this, "", 12f, Theme.DIM, false, Gravity.CENTER)
        now.addView(liveNoteName)
        now.addView(liveSolfege)
        now.addView(Theme.space(this, 6f))
        liveGauge = CentsGaugeView(this)
        liveGauge.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 34f))
        now.addView(liveGauge)
        now.addView(Theme.space(this, 4f))
        now.addView(liveCents)
        col.addView(now)
        col.addView(Theme.space(this, 12f))

        val rangeCard = Theme.card(this)
        val rangeRow = Theme.row(this)
        val lowCol = LinearLayout(this)
        lowCol.orientation = LinearLayout.VERTICAL
        lowCol.gravity = Gravity.CENTER_HORIZONTAL
        lowCol.addView(Theme.text(this, "پایین‌ترین", 11f, Theme.DIM, false, Gravity.CENTER))
        liveLowLabel = Theme.text(this, "—", 22f, Theme.TEAL, true, Gravity.CENTER)
        lowCol.addView(liveLowLabel)
        val highCol = LinearLayout(this)
        highCol.orientation = LinearLayout.VERTICAL
        highCol.gravity = Gravity.CENTER_HORIZONTAL
        highCol.addView(Theme.text(this, "بالاترین", 11f, Theme.DIM, false, Gravity.CENTER))
        liveHighLabel = Theme.text(this, "—", 22f, Theme.GOLD, true, Gravity.CENTER)
        highCol.addView(liveHighLabel)
        val spanCol = LinearLayout(this)
        spanCol.orientation = LinearLayout.VERTICAL
        spanCol.gravity = Gravity.CENTER_HORIZONTAL
        spanCol.addView(Theme.text(this, "گستره", 11f, Theme.DIM, false, Gravity.CENTER))
        liveSpanLabel = Theme.text(this, "—", 16f, Theme.GOLD2, true, Gravity.CENTER)
        spanCol.addView(liveSpanLabel)
        val durCol = LinearLayout(this)
        durCol.orientation = LinearLayout.VERTICAL
        durCol.gravity = Gravity.CENTER_HORIZONTAL
        durCol.addView(Theme.text(this, "مدت", 11f, Theme.DIM, false, Gravity.CENTER))
        liveDurLabel = Theme.text(this, "—", 16f, Theme.TXT, true, Gravity.CENTER)
        durCol.addView(liveDurLabel)

        rangeRow.addView(highCol, weight(1f))
        rangeRow.addView(lowCol, weight(1f))
        rangeRow.addView(spanCol, weight(1f))
        rangeRow.addView(durCol, weight(1f))
        rangeCard.addView(rangeRow)
        col.addView(rangeCard)
        col.addView(Theme.space(this, 12f))

        val chartCard = Theme.card(this)
        chartCard.addView(Theme.text(this, "۱۰ ثانیهٔ اخیر", 13f, Theme.TXT, true))
        liveChart = ChartView(this)
        liveChart.setLive(true)
        liveChart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 170f))
        chartCard.addView(liveChart)
        col.addView(chartCard)
        col.addView(Theme.space(this, 12f))

        val controls = Theme.card(this)
        recordCheck = CheckBox(this)
        recordCheck.text = "ضبط صدا برای تحلیل بعدی"
        recordCheck.setTextColor(Theme.TXT)
        recordCheck.typeface = Theme.font(this, false)
        recordCheck.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        recordCheck.buttonTintList = android.content.res.ColorStateList.valueOf(Theme.GOLD)
        controls.addView(recordCheck)
        controls.addView(Theme.space(this, 8f))

        recBar = LinearLayout(this)
        recBar.orientation = LinearLayout.HORIZONTAL
        recBar.gravity = Gravity.CENTER_VERTICAL
        recBar.background = Theme.rounded(0x22FF7B7B, Theme.dpF(this, 12f), 1, 0x55FF7B7B.toInt())
        val padRec = Theme.dp(this, 10f)
        recBar.setPadding(padRec, Theme.dp(this, 6f), padRec, Theme.dp(this, 6f))
        val dot = View(this)
        dot.background = Theme.rounded(Theme.RED, Theme.dpF(this, 6f))
        dot.layoutParams = LinearLayout.LayoutParams(Theme.dp(this, 10f), Theme.dp(this, 10f))
        recBar.addView(dot)
        recBar.addView(Theme.space(this, 8f))
        recTime = Theme.text(this, "۰۰:۰۰", 12f, Theme.RED, true)
        recTime.width = Theme.dp(this, 58f)
        recBar.addView(recTime)
        recBar.addView(
            Theme.text(this, "در حال ضبط… هر چه می‌خوانی ذخیره می‌شود", 10.5f, Theme.DIM),
            weight(1f)
        )
        recBar.visibility = View.GONE
        val lpRec = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpRec.bottomMargin = Theme.dp(this, 8f)
        recBar.layoutParams = lpRec
        controls.addView(recBar)

        liveStartBtn = Theme.button(this, "🎙  شروع خواندن", true)
        liveStartBtn.setOnClickListener {
            if (live == null) startLive() else stopLive()
        }
        controls.addView(liveStartBtn)
        controls.addView(Theme.space(this, 8f))

        liveStatus = Theme.text(this, "میکروفون خاموش است", 11.5f, Theme.DIM, false, Gravity.CENTER)
        controls.addView(liveStatus)

        val resetLive = Theme.button(this, "↺ صفر کردن رکورد", false, true)
        resetLive.setOnClickListener {
            liveEngineReset?.invoke()
            liveHighLabel.text = "—"
            liveLowLabel.text = "—"
            liveSpanLabel.text = "—"
            liveDurLabel.text = "—"
            liveChart.setLiveData(emptyList(), emptyList())
            toast("رکورد زنده صفر شد")
        }
        val lpReset = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpReset.topMargin = Theme.dp(this, 8f)
        resetLive.layoutParams = lpReset
        controls.addView(resetLive)

        liveExportRow = LinearLayout(this)
        liveExportRow.orientation = LinearLayout.VERTICAL
        liveExportRow.visibility = View.GONE
        controls.addView(liveExportRow)
        col.addView(controls)

        col.addView(Theme.space(this, 12f))
        val note = Theme.card(this)
        note.addView(
            Theme.text(
                this,
                "در این حالت صدا فقط تحلیل می‌شود. اگر «ضبط» را روشن نکنید هیچ صدایی " +
                    "ذخیره نمی‌شود و با توقف همه‌چیز پاک می‌گردد.",
                11.5f, Theme.DIM
            )
        )
        col.addView(note)
        return col
    }

    private fun startLive() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        liveRecording = if (recordCheck.isChecked) ShortGrow(1 shl 17, AudioIo.TARGET_RATE * 60 * 20) else null
        val engine = LiveEngine()
        live = engine
        liveChart.setLiveData(emptyList(), emptyList())
        liveChart.setLive(true)
        liveHighLabel.text = "—"
        liveLowLabel.text = "—"
        liveExportRow.removeAllViews()
        liveExportRow.visibility = View.GONE
        if (!engine.start()) {
            live = null
            liveStatus.text = engine.error ?: "میکروفون در دسترس نیست"
            return
        }
        liveStartBtn.text = "⏹  توقف"
        liveStartBtn.background = Theme.rounded(0x33FF7B7B, Theme.dpF(this, 15f), 1, 0x88FF7B7B.toInt())
        liveStartBtn.setTextColor(Theme.RED)
        liveEngineReset = { engine.resetRecord() }
        liveStatus.text = if (recordCheck.isChecked) "در حال گوش دادن و ضبط…" else "در حال گوش دادن…"
        recordStartAt = System.currentTimeMillis()
        recBar.visibility = if (recordCheck.isChecked) View.VISIBLE else View.GONE
        startRecordTicker()
    }

    private fun startRecordTicker() {
        stopRecordTicker()
        if (!recordCheck.isChecked) return
        val r = object : Runnable {
            override fun run() {
                if (live == null) return
                val sec = (System.currentTimeMillis() - recordStartAt) / 1000
                recTime.text = PitchMath.faDigits(String.format(java.util.Locale.US, "%02d:%02d", sec / 60, sec % 60))
                ui.postDelayed(this, 500)
            }
        }
        recordTicker = r
        ui.post(r)
    }

    private fun stopRecordTicker() {
        recordTicker?.let { ui.removeCallbacks(it) }
        recordTicker = null
        recBar.visibility = View.GONE
    }

    private fun stopLive() {
        stopRecordTicker()
        val engine = live ?: return
        live = null
        val recorded = engine.stop()
        liveStartBtn.text = "🎙  شروع خواندن"
        liveStartBtn.background = Theme.primaryButtonBg(this)
        liveStartBtn.setTextColor(Theme.INK)
        liveStatus.text = "میکروفون خاموش شد"
        liveNoteName.text = "—"
        liveSolfege.text = "متوقف شد"
        liveCents.text = ""
        liveGauge.cents = 0f
        liveGauge.invalidate()

        if (recorded != null && recorded.size > AudioIo.TARGET_RATE / 2) {
            takes.add(0, Take(recorded, System.currentTimeMillis()))
            liveStatus.text = "${PitchMath.faDigits(takes.size)} ضبط در این جلسه"
            renderTakes()
        }
    }

    // ── ضبط‌های این جلسه (مثل «⏺ ضبط‌های این جلسه» نسخهٔ وب) ──

    private class Take(val pcm: ShortArray, val at: Long) {
        val seconds: Double get() = pcm.size.toDouble() / AudioIo.TARGET_RATE
    }

    private val takes = ArrayList<Take>()

    private fun renderTakes() {
        liveExportRow.removeAllViews()
        if (takes.isEmpty()) {
            liveExportRow.visibility = View.GONE
            return
        }
        liveExportRow.visibility = View.VISIBLE

        val head = Theme.row(this)
        head.addView(Theme.text(this, "⏺ ضبط‌های این جلسه", 13f, Theme.TXT, true), weight(1f))
        val clear = Theme.button(this, "🗑 حذف همه", false, true)
        clear.setOnClickListener {
            takes.clear()
            renderTakes()
            toast("همهٔ ضبط‌های این جلسه پاک شد")
        }
        head.addView(clear)
        liveExportRow.addView(head)
        liveExportRow.addView(Theme.space(this, 6f))

        for ((index, take) in takes.withIndex()) {
            val card = LinearLayout(this)
            card.orientation = LinearLayout.VERTICAL
            card.background = Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 14f), 1, Theme.LINE)
            val pad = Theme.dp(this, 10f)
            card.setPadding(pad, pad, pad, pad)
            val lpCard = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lpCard.bottomMargin = Theme.dp(this, 8f)
            card.layoutParams = lpCard

            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(take.at))
            card.addView(
                Theme.text(
                    this,
                    "ضبط ${PitchMath.faDigits(takes.size - index)} · ${PitchMath.faFixed(take.seconds, 1)} ثانیه · ساعت ${PitchMath.faDigits(time)}",
                    12f, Theme.TXT, true
                )
            )
            card.addView(Theme.space(this, 6f))
            val rowA = Theme.row(this)
            val analyze = Theme.button(this, "📊 تحلیل", true, true)
            val save = Theme.button(this, "💾 ذخیره صدا", false, true)
            val del = Theme.button(this, "🗑", false, true)
            analyze.setOnClickListener { analyzeRecording(take.pcm) }
            save.setOnClickListener {
                withWrite {
                    val saved = Persistence.saveWav(
                        this, take.pcm, AudioIo.TARGET_RATE,
                        Persistence.safeName("همیار-قاری-ضبط-${takes.size - index}", "wav")
                    )
                    toast("✅ ذخیره شد: ${saved.displayPath}")
                }
            }
            del.setOnClickListener {
                if (index in takes.indices) {
                    takes.removeAt(index)
                    renderTakes()
                }
            }
            rowA.addView(analyze, weight(1f))
            rowA.addView(Theme.space(this, 6f))
            rowA.addView(save, weight(1f))
            rowA.addView(Theme.space(this, 6f))
            rowA.addView(del)
            card.addView(rowA)
            liveExportRow.addView(card)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE) {
            val block = pendingWrite
            pendingWrite = null
            block?.invoke()
            return
        }
        if (requestCode == REQ_MIC) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLive()
            } else {
                liveStatus.text = "بدون اجازهٔ میکروفون، اجرای زنده ممکن نیست"
            }
        }
    }

    private fun analyzeRecording(pcm: ShortArray) {
        if (busy) return
        busy = true
        showTab(0)
        resultBox.removeAllViews()
        showProgress(0.0, "آماده‌سازی صدای ضبط‌شده…")
        sourceName = "ضبط زنده"
        val copy = pcm.copyOf()
        Thread {
            try {
                val prepared = Analyzer.prepareShort(copy, AudioIo.TARGET_RATE)
                decoded = AudioIo.Decoded(
                    pcm = prepared,
                    sampleRate = AudioIo.TARGET_RATE,
                    durationSec = prepared.size.toDouble() / AudioIo.TARGET_RATE,
                    sourceRate = AudioIo.TARGET_RATE,
                    channels = 1,
                    truncated = false
                )
                sharePcm = prepared
                val rep = Analyzer.analyzeSignal(ShortSignal(prepared, AudioIo.TARGET_RATE), denoiseCheck.isChecked) { stage, p ->
                    ui.post {
                        val info = stageInfo(stage)
                        showProgress(info.second + info.third * p, info.first)
                    }
                }
                ui.post {
                    busy = false
                    hideProgress()
                    fileLabel.text = "ضبط زنده · ${PitchMath.faFixed(rep.durationSec, 1)} ثانیه"
                    fileLabel.setTextColor(Theme.TXT)
                    analyzeBtn.isEnabled = true
                    analyzeBtn.alpha = 1f
                    report = rep
                    renderReport(rep, 0)
                }
            } catch (e: Exception) {
                ui.post {
                    busy = false
                    hideProgress()
                    resultBox.addView(errorCard("تحلیل ضبط انجام نشد", e.message ?: ""))
                }
            }
        }.start()
    }

    // ───────────────────────────── موتور زنده ─────────────────────────────

    /**
     * ردیابی لحظه‌ای: هر ۱۲۰ میلی‌ثانیه روی آخرین ~۵۵ میلی‌ثانیه صدا
     * یک برآورد FRESH می‌گیرد، اصلاح سریع اکتاو می‌زند، و اگر یک نت
     * دست‌کم ۳ فریم پایدار بماند آن را در گسترهٔ زنده حساب می‌کند.
     */
    private inner class LiveEngine {
        var error: String? = null
        private var capture: AudioIo.MicCapture? = null
        private val buffer = ShortGrow(1 shl 17, AudioIo.TARGET_RATE * 900)
        private val lock = Any()
        private var worker: Thread? = null
        @Volatile private var running = false

        private val yin = YinTracker(AudioIo.TARGET_RATE, 55.0, 1200.0, 0.055)
        private val frameBuf = DoubleArray(yin.w)
        private val fp = ir.hamyareqari.dsp.FramePitch()
        private val fft = ir.hamyareqari.dsp.Fft(2048)
        private val fre = DoubleArray(2048)
        private val fim = DoubleArray(2048)
        private val fwin = ir.hamyareqari.dsp.hann(2048)
        private val scratch = DoubleArray(2048)

        private val traceT = ArrayList<Double>(4096)
        private val traceHz = ArrayList<Double>(4096)
        private var startedAt = 0L
        private var lastMidi = -1
        private var stableCount = 0
        private var highHz = 0.0
        private var lowHz = 0.0
        private val recording get() = liveRecording

        fun resetRecord() {
            highHz = 0.0
            lowHz = 0.0
            lastMidi = -1
            stableCount = 0
            synchronized(lock) {
                traceT.clear()
                traceHz.clear()
            }
        }

        fun start(): Boolean {
            startedAt = System.currentTimeMillis()
            running = true
            val cap = AudioIo.MicCapture { block, count ->
                synchronized(lock) {
                    buffer.add(block, count)
                    recording?.add(block, count)
                }
            }
            capture = cap
            if (!cap.start()) {
                error = cap.lastError
                running = false
                return false
            }
            worker = Thread { loop() }.also { it.isDaemon = true; it.start() }
            return true
        }

        fun stop(): ShortArray? {
            running = false
            capture?.stop()
            worker?.let { try { it.join(800) } catch (e: Exception) { } }
            worker = null
            liveChart.setLive(true)
            val rec = recording
            return if (rec != null && rec.size > 0) rec.toArray() else null
        }

        private fun loop() {
            val want = yin.w
            while (running) {
                val snap: ShortArray
                synchronized(lock) {
                    if (buffer.size < want) {
                        snap = ShortArray(0)
                    } else {
                        snap = ShortArray(want)
                        for (i in 0 until want) snap[i] = buffer.get(buffer.size - want + i)
                    }
                }
                if (snap.isNotEmpty()) {
                    val s = ShortSignal(snap, AudioIo.TARGET_RATE)
                    yin.pitchAt(s, 0, fp, frameBuf)
                    var f0 = fp.f0
                    if (f0 > 0 && fp.clarity > 0.5) {
                        val fixed = Harmonics.quickOctaveFix(
                            s, AudioIo.TARGET_RATE, 0, min(want, 2048), f0,
                            fft, fre, fim, scratch, fwin
                        )
                        if (fixed > 0) f0 = fixed
                    } else {
                        f0 = 0.0
                    }
                    val t = (System.currentTimeMillis() - startedAt) / 1000.0
                    synchronized(lock) {
                        traceT.add(t)
                        traceHz.add(f0)
                        while (traceT.isNotEmpty() && t - traceT[0] > 12.0) {
                            traceT.removeAt(0)
                            traceHz.removeAt(0)
                        }
                    }
                    val freq = f0
                    ui.post { onLiveFrame(t, freq) }
                }
                try { Thread.sleep(110) } catch (e: InterruptedException) { }
            }
        }

        private fun onLiveFrame(t: Double, f0: Double) {
            if (f0 > 0) {
                val midi = PitchMath.nearestMidi(f0)
                liveNoteName.text = PitchMath.latinName(midi)
                liveSolfege.text = PitchMath.solfege(f0)
                val cents = PitchMath.centsOffPitch(f0)
                liveGauge.cents = cents.toFloat()
                liveGauge.invalidate()
                liveCents.text = if (abs(cents) < 4) "دقیق روی نت"
                else if (cents > 0) "${PitchMath.faFixed(cents, 0)} سنت بالا"
                else "${PitchMath.faFixed(-cents, 0)} سنت پایین"
                liveNoteName.setTextColor(Theme.GOLD)

                if (midi == lastMidi) stableCount++ else { lastMidi = midi; stableCount = 0 }
                if (stableCount >= 3) {
                    if (f0 > highHz) {
                        highHz = f0
                        liveHighLabel.text = PitchMath.noteName(f0)
                    }
                    if (lowHz == 0.0 || f0 < lowHz) {
                        lowHz = f0
                        liveLowLabel.text = PitchMath.noteName(f0)
                    }
                    if (highHz > 0 && lowHz > 0) {
                        val semis = PitchMath.cents(highHz, lowHz) / 100.0
                        liveSpanLabel.text = PitchMath.faFixed(semis, 1)
                    }
                }
                liveDurLabel.text = PitchMath.faFixed(t, 0) + " ث"
            } else {
                liveNoteName.setTextColor(Theme.DIM)
                if (liveNoteName.text != "—") {
                    liveCents.text = "…"
                }
                liveGauge.cents = 0f
                liveGauge.invalidate()
            }
            synchronized(lock) {
                // میدی برای رندرر؛ سکوت = NaN تا نور پنهان شود
                val mids = ArrayList<Double>(traceHz.size)
                for (v in traceHz) mids.add(if (v > 0) PitchMath.hzToMidi(v) else Double.NaN)
                liveChart.setLiveData(ArrayList(traceT), mids)
            }
        }
    }
}
