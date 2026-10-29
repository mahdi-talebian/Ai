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
        const val VERSION = "1.0.0"
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
    private lateinit var root: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var tabFile: TextView
    private lateinit var tabLive: TextView
    private lateinit var panelFile: View
    private lateinit var panelLive: View
    private lateinit var resultBox: LinearLayout
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
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.background = Theme.screenBackground(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val scrollHolder = FrameLayout(this)
        content = FrameLayout(this)

        val inner = LinearLayout(this)
        inner.orientation = LinearLayout.VERTICAL
        val padH = Theme.dp(this, 14f)
        inner.setPadding(padH, Theme.dp(this, 12f), padH, Theme.dp(this, 28f))

        // سربرگ
        val head = LinearLayout(this)
        head.orientation = LinearLayout.VERTICAL
        head.gravity = Gravity.CENTER_HORIZONTAL
        val title = Theme.text(this, "همیار قاری", 24f, Theme.GOLD, true, Gravity.CENTER)
        val sub = Theme.text(
            this,
            "گسترهٔ صدای خواننده — بالاترین و پایین‌ترین نت",
            12.5f, Theme.DIM, false, Gravity.CENTER
        )
        head.addView(title)
        head.addView(Theme.space(this, 2f))
        head.addView(sub)
        head.addView(Theme.space(this, 6f))
        val privacy = Theme.chip(this, "🔒 ۱۰۰٪ روی همین گوشی · بدون اینترنت", Theme.TEAL)
        val privacyRow = Theme.row(this)
        privacyRow.gravity = Gravity.CENTER_HORIZONTAL
        privacyRow.addView(privacy)
        head.addView(privacyRow)
        inner.addView(head)
        inner.addView(Theme.space(this, 12f))

        // تب‌ها
        val tabs = Theme.row(this)
        tabFile = tabButton("تحلیل فایل", true)
        tabLive = tabButton("اجرای زنده", false)
        tabFile.setOnClickListener { showTab(0) }
        tabLive.setOnClickListener { showTab(1) }
        val lpFile = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpFile.marginEnd = Theme.dp(this, 6f)
        tabFile.layoutParams = lpFile
        val lpLive = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lpLive.marginStart = Theme.dp(this, 6f)
        tabLive.layoutParams = lpLive
        tabs.addView(tabFile)
        tabs.addView(tabLive)
        inner.addView(tabs)
        inner.addView(Theme.space(this, 12f))

        // پنل فایل
        panelFile = buildFilePanel()
        panelLive = buildLivePanel()
        val holder = FrameLayout(this)
        holder.addView(panelFile)
        holder.addView(panelLive)
        inner.addView(holder)

        val sv = ScrollView(this)
        sv.isFillViewport = true
        sv.overScrollMode = View.OVER_SCROLL_NEVER
        sv.addView(inner)
        scrollHolder.addView(sv)
        root.addView(scrollHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        // فاصلهٔ نوار وضعیت
        root.setOnApplyWindowInsetsListener { v, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop
            @Suppress("DEPRECATION")
            val bottom = insets.systemWindowInsetBottom
            v.setPadding(0, top, 0, bottom)
            insets
        }
    }

    private fun tabButton(label: String, active: Boolean): TextView {
        val t = Theme.text(this, label, 14f, if (active) Theme.INK else Theme.DIM, true, Gravity.CENTER)
        val h = Theme.dp(this, 46f)
        t.minHeight = h
        t.gravity = Gravity.CENTER
        val radius = Theme.dpF(this, 14f)
        t.background = if (active)
            Theme.gradient(Theme.GOLD, Theme.GOLD2, radius)
        else
            Theme.rounded(0x0DFFFFFF, radius, 1, Theme.LINE)
        return t
    }

    private fun showTab(index: Int) {
        val fileActive = index == 0
        tabFile.background = if (fileActive) Theme.gradient(Theme.GOLD, Theme.GOLD2, Theme.dpF(this, 14f))
        else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 14f), 1, Theme.LINE)
        tabFile.setTextColor(if (fileActive) Theme.INK else Theme.DIM)

        tabLive.background = if (!fileActive) Theme.gradient(Theme.GOLD, Theme.GOLD2, Theme.dpF(this, 14f))
        else Theme.rounded(0x0DFFFFFF, Theme.dpF(this, 14f), 1, Theme.LINE)
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
        val pickTitle = Theme.text(this, "۱. فایل صوتی یا ویدیو را انتخاب کنید", 14f, Theme.TXT, true)
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

        val chooseBtn = Theme.button(this, "📂 انتخاب فایل", false)
        chooseBtn.setOnClickListener { pickFile() }
        pick.addView(chooseBtn)
        pick.addView(Theme.space(this, 8f))

        fileLabel = Theme.text(this, "فایلی انتخاب نشده", 12.5f, Theme.DIM)
        pick.addView(fileLabel)
        pick.addView(Theme.space(this, 10f))

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
                val rep = Analyzer.analyzeSignal(sig) { stage, p ->
                    ui.post {
                        val (label, base, span) = stageInfo(stage)
                        showProgress(base + span * p, label)
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                ui.post {
                    report = rep
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
        grid.addView(statLine("نت‌های خوانده‌شده", PitchMath.faDigits(r.noteCount)))
        grid.addView(statLine("مدت خواندن", PitchMath.faFixed(r.sungSec, 0) + " از " + PitchMath.faFixed(r.durationSec, 0) + " ثانیه"))
        grid.addView(statLine("مکث‌ها (≥۰٫۳ ثانیه)", PitchMath.faDigits(r.pauseCount) + " · بلندترین " + PitchMath.faFixed(r.longestPause, 1) + " ثانیه"))
        stats.addView(grid)
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
        chart.setData(r.contourT, r.contourHz, r.durationSec)
        chart.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(this, 210f))
        chartCard.addView(chart)

        val playRow = Theme.row(this)
        val playBtn = Theme.button(this, "▶  پخش", false, true)
        val stopBtn = Theme.button(this, "⏸ توقف", false, true)
        playRow.addView(playBtn)
        playRow.addView(Theme.space(this, 8f))
        playRow.addView(stopBtn)
        val playTime = Theme.text(this, "۰٫۰ / ${PitchMath.faFixed(r.durationSec, 1)}", 11.5f, Theme.DIM, false, Gravity.END)
        playRow.addView(playTime, weight(1f))
        val lpPlay = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpPlay.topMargin = Theme.dp(this, 8f)
        playRow.layoutParams = lpPlay
        chartCard.addView(playRow)
        resultBox.addView(chartCard)
        resultBox.addView(Theme.space(this, 12f))

        playBtn.setOnClickListener {
            val player = ensurePlayer() ?: return@setOnClickListener
            player.playFrom(0.0)
            startTicker(chart, playTime, r.durationSec)
        }
        stopBtn.setOnClickListener {
            player?.pause()
            stopTicker()
        }
        chart.onSeek = { sec ->
            val player = ensurePlayer()
            if (player != null) {
                player.playFrom(sec)
                startTicker(chart, playTime, r.durationSec)
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
        val shareBtn = Theme.button(this, "🔗 اشتراک‌گذاری گزارش", false, true)
        export.addView(shareBtn)

        pngBtn.setOnClickListener { exportPng(r, chart) }
        jsonBtn.setOnClickListener { exportJson(r) }
        shareBtn.setOnClickListener { exportJson(r, share = true) }
        resultBox.addView(export)

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

    private fun ensurePlayer(): AudioIo.Player? {
        val pcm = sharePcm ?: return null
        if (pcm.size > AudioIo.TARGET_RATE * 900) {
            toast("پخش نمونه برای فایل‌های بیش از ۱۵ دقیقه خاموش است")
            return null
        }
        val p = player ?: AudioIo.Player(this).also { player = it }
        if (!p.load(pcm, AudioIo.TARGET_RATE)) {
            toast("پخش ممکن نشد")
            return null
        }
        return p
    }

    private fun startTicker(chart: ChartView, label: TextView, total: Double) {
        stopTicker()
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                val pos = p.positionSec()
                chart.setPlayhead(pos)
                label.text = "${PitchMath.faFixed(pos, 1)} / ${PitchMath.faFixed(total, 1)}"
                if (p.isPlaying) ui.postDelayed(this, 80)
            }
        }
        ticker = r
        ui.post(r)
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
        rangeRow.addView(highCol, weight(1f))
        rangeRow.addView(lowCol, weight(1f))
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

        liveStartBtn = Theme.button(this, "🎙  شروع خواندن", true)
        liveStartBtn.setOnClickListener {
            if (live == null) startLive() else stopLive()
        }
        controls.addView(liveStartBtn)
        controls.addView(Theme.space(this, 8f))

        liveStatus = Theme.text(this, "میکروفون خاموش است", 11.5f, Theme.DIM, false, Gravity.CENTER)
        controls.addView(liveStatus)

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
        liveChart.setData(DoubleArray(0), DoubleArray(0), 10.0)
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
        liveStatus.text = if (recordCheck.isChecked) "در حال گوش دادن و ضبط…" else "در حال گوش دادن…"
    }

    private fun stopLive() {
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

        if (recorded != null && recorded.size > AudioIo.TARGET_RATE) {
            liveStatus.text = "ضبط ${PitchMath.faFixed(recorded.size.toDouble() / AudioIo.TARGET_RATE, 1)} ثانیه — آمادهٔ تحلیل"
            liveExportRow.visibility = View.VISIBLE
            liveExportRow.removeAllViews()
            val analyze = Theme.button(this, "📊 تحلیل همین ضبط", true)
            val saveWav = Theme.button(this, "💾 ذخیرهٔ صدا (WAV)", false)
            analyze.setOnClickListener { analyzeRecording(recorded) }
            saveWav.setOnClickListener {
                withWrite {
                    val saved = Persistence.saveWav(
                        this, recorded, AudioIo.TARGET_RATE,
                        Persistence.safeName("همیار-قاری-ضبط", "wav")
                    )
                    toast("✅ ذخیره شد: ${saved.displayPath}")
                }
            }
            liveExportRow.addView(analyze)
            liveExportRow.addView(Theme.space(this, 8f))
            liveExportRow.addView(saveWav)
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
                val rep = Analyzer.analyzeSignal(ShortSignal(prepared, AudioIo.TARGET_RATE)) { stage, p ->
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
                }
            } else {
                liveNoteName.setTextColor(Theme.DIM)
                if (liveNoteName.text != "—") {
                    liveCents.text = "…"
                }
                liveGauge.cents = 0f
                liveGauge.invalidate()
            }
            synchronized(lock) {
                liveChart.setData(traceT.toDoubleArray(), traceHz.toDoubleArray(), 12.0)
            }
        }
    }
}
