package ir.hamyareqari.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import ir.hamyareqari.dsp.PitchMath
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * پخش‌کنندهٔ صوتیِ نمودار — «شکیل» و کارا:
 *
 *   ┌───────────────────────────────────────────────┐
 *   │  ▶  ۰:۱۲٫۳ ──────●────────────── ۰:۳۸٫۲       │
 *   │      [×۰٬۵] [×۰٬۷۵] [×۱] [×۱٬۲۵] [×۱٬۵]  ⊙دنبال │
 *   └───────────────────────────────────────────────┘
 *
 * • دکمهٔ گرد طلایی با آیکنِ ساخته‌شده با Canvas (بدون ایموجی و فونت اضافه)
 * • نوار پیشرفتِ طلایی با دستگیرهٔ نورانی — با لمس/کشیدن، صدا جابه‌جا می‌شود
 * • زمان جاری و کل، با ارقام فارسی
 * • سرعت پخش (۰٫۵× تا ۱٫۵×) و کلید «دنبال‌کردن» زوم
 *
 * این کلاس فقط «نما» است؛ پخش در اختیار [MainActivity] می‌ماند و با
 * [setPosition] هر ۱۶ میلی‌ثانیه به‌روز می‌شود.
 */
class AudioPlayerBar(context: Context) : LinearLayout(context) {

    var onPlayPause: (() -> Unit)? = null
    var onSeek: ((Double) -> Unit)? = null
    var onRestart: (() -> Unit)? = null
    var onSpeed: ((Float) -> Unit)? = null
    var onFollowToggle: ((Boolean) -> Unit)? = null
    var onSkip: ((Double) -> Unit)? = null
    var onTraceSeek: ((Double) -> Unit)? = null

    private val playBtn = PlayButton(context)
    private val skipBackBtn = Theme.chip(context, "−۱۰ث", Theme.DIM)
    private val skipForwardBtn = Theme.chip(context, "+۱۰ث", Theme.DIM)
    private val timeNow = Theme.text(context, "۰:۰۰٫۰", 12f, Theme.GOLD, true, Gravity.START or Gravity.CENTER_VERTICAL)
    private val timeTotal = Theme.text(context, "۰:۰۰٫۰", 12f, Theme.DIM, false, Gravity.END or Gravity.CENTER_VERTICAL)
    private val bar = GoldSeekBar(context)
    private val pitchTrace = MiniPitchTraceView(context)

    private val followBtn = Theme.chip(context, "⊙ دنبال‌کردن", Theme.TEAL)
    private val restartBtn = Theme.chip(context, "↺ از اول", Theme.DIM)
    private var speedChips: List<TextView> = emptyList()
    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f)
    private var currentSpeed = 1f

    private var totalSec = 1.0
    private var dragging = false
    private var following = true

    init {
        orientation = VERTICAL
        background = Theme.rounded(Theme.withAlpha(Theme.GOLD, 0.05f), Theme.dpF(context, 16f), 1, Theme.withAlpha(Theme.GOLD, 0.22f))
        val pad = Theme.dp(context, 12f)
        setPadding(pad, pad, pad, pad)

        // ── ردیف اول: دکمهٔ پخش + نوار + زمان‌ها ─────────────────────────
        val row1 = Theme.row(context)
        val size = Theme.dp(context, 54f)
        playBtn.layoutParams = LayoutParams(size, size)
        row1.addView(playBtn)
        val skipSize = Theme.dp(context, 38f)
        skipBackBtn.layoutParams = LayoutParams(skipSize, Theme.dp(context, 42f))
        skipForwardBtn.layoutParams = LayoutParams(skipSize, Theme.dp(context, 42f))
        skipBackBtn.setOnClickListener { onSkip?.invoke(-10.0) }
        skipForwardBtn.setOnClickListener { onSkip?.invoke(10.0) }
        row1.addView(Theme.space(context, 4f))
        row1.addView(skipBackBtn)
        row1.addView(Theme.space(context, 4f))
        row1.addView(skipForwardBtn)

        val mid = LinearLayout(context)
        mid.orientation = VERTICAL
        val lpMid = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpMid.weight = 1f
        lpMid.marginStart = Theme.dp(context, 12f)
        lpMid.marginEnd = Theme.dp(context, 12f)
        mid.layoutParams = lpMid

        val timesRow = Theme.row(context)
        timeNow.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12.5f)
        timeTotal.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11.5f)
        timesRow.addView(timeNow)
        timesRow.addView(Theme.space(context, 6f))
        val lpTotal = LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        lpTotal.weight = 1f
        timesRow.addView(timeTotal, lpTotal)
        mid.addView(timesRow)
        mid.addView(Theme.space(context, 2f))

        bar.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 30f))
        mid.addView(bar)
        row1.addView(mid)
        addView(row1)

        // ── ردّ زیر و بمی: نمودار فشرده و قابل لمس ────────────────────────
        addView(Theme.space(context, 8f))
        addView(Theme.text(context, "ردِّ صدا · برای رفتن به لحظهٔ دلخواه لمس کنید", 10.5f, Theme.DIM))
        pitchTrace.layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Theme.dp(context, 72f))
        addView(pitchTrace)
        pitchTrace.onSeek = { onTraceSeek?.invoke(it) }

        // ── ردیف دوم: سرعت‌ها، دنبال‌کردن، از اول ────────────────────────
        addView(Theme.space(context, 8f))
        val row2 = Theme.row(context)
        speedChips = speeds.map { s ->
            val label = "×" + PitchMath.faFixed(s.toDouble(), (if (s == s.toInt().toFloat()) 0 else 2))
                .replace("٫۰", "").replace(".0", "")
            val chip = Theme.chip(context, label, if (s == 1f) Theme.GOLD else Theme.DIM)
            chip.setOnClickListener { setSpeed(s) }
            row2.addView(chip)
            row2.addView(Theme.space(context, 5f))
            chip
        }
        row2.addView(Theme.space(context, 2f))
        row2.addView(restartBtn)
        row2.addView(Theme.space(context, 5f))
        row2.addView(followBtn)
        addView(row2)

        playBtn.setOnClickListener {
            val playing = playBtn.playing
            setPlaying(!playing)
            onPlayPause?.invoke()
        }
        restartBtn.setOnClickListener { onRestart?.invoke() }
        followBtn.setOnClickListener {
            following = !following
            styleFollow()
            onFollowToggle?.invoke(following)
        }
        styleFollow()

        bar.listener = object : GoldSeekBar.Listener {
            override fun onStart() {
                dragging = true
            }

            override fun onPreview(frac: Double) {
                timeNow.text = fmt(frac * totalSec)
                playBtn.ghost = frac
            }

            override fun onCommit(frac: Double) {
                dragging = false
                playBtn.ghost = -1.0
                onSeek?.invoke(frac * totalSec)
            }
        }
        setSpeed(1f)
    }

    // ─────────────────────────────────────────────────────────────────────

    fun setTotal(sec: Double) {
        totalSec = max(0.1, sec)
        timeTotal.text = fmt(totalSec)
        bar.setProgress(0.0)
    }

    /** خلاصهٔ موج صدا؛ نمونه‌برداری محدود برای جلوگیری از مکث روی فایل‌های بلند */
    fun setWaveform(pcm: ShortArray?) {
        if (pcm == null || pcm.isEmpty()) {
            bar.setWaveform(FloatArray(0))
            return
        }
        val bins = 104
        val values = FloatArray(bins)
        for (i in 0 until bins) {
            val from = (i.toLong() * pcm.size / bins).toInt()
            val to = (((i + 1).toLong() * pcm.size / bins).toInt()).coerceAtLeast(from + 1).coerceAtMost(pcm.size)
            var peak = 0
            val samples = 28
            for (j in 0 until samples) {
                val index = (from.toLong() + (to - from - 1L) * j / (samples - 1)).toInt().coerceIn(0, pcm.lastIndex)
                peak = max(peak, kotlin.math.abs(pcm[index].toInt()))
            }
            values[i] = (peak / 32768f).coerceIn(0.06f, 1f)
        }
        bar.setWaveform(values)
    }

    /** هر تیکِ پخش (≈۶۰ بار در ثانیه) */
    fun setPitchTrace(spec: TraceSpec?) {
        pitchTrace.setSpec(spec)
    }

    fun setPosition(sec: Double) {
        if (dragging) return
        val frac = (sec / totalSec).coerceIn(0.0, 1.0)
        bar.setProgress(frac)
        playBtn.progress = frac
        timeNow.text = fmt(sec)
        pitchTrace.setPlayhead(sec)
    }

    fun setPlaying(playing: Boolean) {
        playBtn.playing = playing
        if (playing) playBtn.progress = bar.progressFraction()
    }

    fun setSpeed(s: Float) {
        currentSpeed = s
        for (i in speedChips.indices) {
            val on = speeds[i] == s
            speedChips[i].setTextColor(if (on) Theme.INK else Theme.DIM)
            speedChips[i].background = if (on) {
                Theme.primaryButtonBg(context)
            } else {
                Theme.rounded(0x0DFFFFFF, Theme.dpF(context, 20f), 1, Theme.withAlpha(Theme.DIM, 0.35f))
            }
        }
        onSpeed?.invoke(s)
    }

    fun currentSpeed(): Float = currentSpeed

    fun setFollowing(on: Boolean) {
        following = on
        styleFollow()
    }

    private fun styleFollow() {
        followBtn.setTextColor(if (following) Theme.INK else Theme.DIM)
        followBtn.background = if (following) {
            Theme.primaryButtonBg(context)
        } else {
            Theme.rounded(0x0DFFFFFF, Theme.dpF(context, 20f), 1, Theme.withAlpha(Theme.DIM, 0.35f))
        }
        followBtn.text = if (following) "⊙ دنبال‌کردن" else "⊙ آزاد"
    }

    private fun fmt(sec: Double): String {
        val s = sec.coerceAtLeast(0.0)
        val m = (s / 60).toInt()
        val rest = s - m * 60
        val restTxt = String.format(java.util.Locale.US, "%04.1f", rest)
        return "${PitchMath.faDigits(m)}:$restTxt"
    }

    // ── دکمهٔ گرد پخش/توقف ────────────────────────────────────────────────

    internal class PlayButton(ctx: Context) : View(ctx) {
        var playing = false
            set(v) { field = v; invalidate() }
        var progress = 0.0
            set(v) { field = v; invalidate() }
        var ghost = -1.0   // پیش‌نمایش زمان هنگام کشیدن نوار

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Theme.INK
        }
        private val r = RectF()

        init {
            isClickable = true
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2
            val cy = h / 2
            val radius = min(w, h) / 2 - Theme.dp(context, 4f)

            // حلقهٔ پیشرفت (نشان می‌دهد چقدر از صدا گذشته)
            ringPaint.strokeWidth = Theme.dpF(context, 3.5f)
            ringPaint.color = Theme.withAlpha(Theme.TXT, 0.14f)
            canvas.drawCircle(cx, cy, radius, ringPaint)
            val frac = if (ghost >= 0) ghost else progress
            if (frac > 0.001) {
                ringPaint.color = Theme.GOLD
                r.set(cx - radius, cy - radius, cx + radius, cy + radius)
                canvas.drawArc(r, -90f, (360 * frac).toFloat(), false, ringPaint)
            }

            // بدنهٔ گرد
            bgPaint.color = if (playing) Theme.withAlpha(Theme.GOLD, 0.16f) else Theme.GOLD
            canvas.drawCircle(cx, cy, radius - Theme.dp(context, 5f), bgPaint)

            // آیکن
            if (playing) {
                iconPaint.color = Theme.GOLD
                val bw = radius * 0.16f
                val bh = radius * 0.42f
                r.set(cx - bw * 1.9f, cy - bh, cx - bw * 0.5f, cy + bh)
                canvas.drawRoundRect(r, bw * 0.6f, bw * 0.6f, iconPaint)
                r.set(cx + bw * 0.5f, cy - bh, cx + bw * 1.9f, cy + bh)
                canvas.drawRoundRect(r, bw * 0.6f, bw * 0.6f, iconPaint)
            } else {
                iconPaint.color = Theme.INK
                val s = radius * 0.44f
                val path = android.graphics.Path()
                path.reset()
                path.moveTo(cx - s * 0.55f, cy - s)
                path.lineTo(cx + s * 0.9f, cy)
                path.lineTo(cx - s * 0.55f, cy + s)
                path.close()
                canvas.drawPath(path, iconPaint)
            }
        }
    }

    // ── نوار پیشرفت طلایی ────────────────────────────────────────────────

    @SuppressLint("ViewConstructor")
    internal class GoldSeekBar(ctx: Context) : View(ctx) {

        interface Listener {
            fun onStart()
            fun onPreview(frac: Double)
            fun onCommit(frac: Double)
        }

        var listener: Listener? = null
        private var frac = 0.0
        private var preview = -1.0
        private var active = false
        private var waveform = FloatArray(0)

        private val track = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Theme.GOLD }
        private val knob = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isClickable = true
        }

        fun setProgress(f: Double) {
            frac = f.coerceIn(0.0, 1.0)
            invalidate()
        }

        fun progressFraction(): Double = frac

        fun setWaveform(values: FloatArray) {
            waveform = values.copyOf()
            invalidate()
        }

        private fun fracAt(x: Float): Double {
            val pad = Theme.dp(context, 12f).toFloat()
            val left = pad
            val right = width - pad
            return (((x - left) / max(1f, right - left))).toDouble().coerceIn(0.0, 1.0)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    active = true
                    preview = fracAt(event.x)
                    listener?.onStart()
                    listener?.onPreview(preview)
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (active) {
                        preview = fracAt(event.x)
                        listener?.onPreview(preview)
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (active) {
                        active = false
                        val v = fracAt(event.x)
                        preview = -1.0
                        frac = v
                        listener?.onCommit(v)
                        performClick()
                        invalidate()
                    }
                }
            }
            return true
        }

        override fun performClick(): Boolean {
            super.performClick()
            return true
        }

        override fun onDraw(canvas: Canvas) {
            val cy = height / 2f
            val pad = Theme.dp(context, 12f).toFloat()
            val left = pad
            val right = width - pad
            val thickness = Theme.dp(context, 5f).toFloat()
            val f = if (preview >= 0) preview else frac

            // ریل
            track.color = Theme.withAlpha(Theme.TXT, 0.20f)
            canvas.drawRoundRect(left, cy - thickness / 2, right, cy + thickness / 2, thickness / 2, thickness / 2, track)

            // بخش گذشته
            val x = left + ((right - left) * f).toFloat()
            if (f > 0.0005f) {
                val g = android.graphics.LinearGradient(
                    left, cy, max(left + 1f, right), cy,
                    intArrayOf(Theme.GOLD, Theme.GOLD2), null,
                    android.graphics.Shader.TileMode.CLAMP
                )
                fillPaint.shader = g
                canvas.drawRoundRect(left, cy - thickness / 2, x, cy + thickness / 2, thickness / 2, thickness / 2, fillPaint)
                fillPaint.shader = null
            }

            // موجِ واقعیِ صوت با رنگ متفاوت برای بخش پخش‌شده و باقی‌مانده
            if (waveform.isNotEmpty()) {
                val step = (right - left) / waveform.size
                val barW = max(Theme.dp(context, 1.4f).toFloat(), step * 0.42f)
                for (i in waveform.indices) {
                    val bx = left + (i + 0.5f) * step
                    val amp = waveform[i]
                    val bh = max(Theme.dp(context, 2f).toFloat(), amp * Theme.dp(context, 18f).toFloat())
                    track.color = if (i.toDouble() / waveform.size <= f) Theme.GOLD2 else Theme.withAlpha(Theme.DIM, 0.58f)
                    canvas.drawRoundRect(bx - barW / 2, cy - bh / 2, bx + barW / 2, cy + bh / 2, barW / 2, barW / 2, track)
                }
            }

            // هالهٔ نورانی + دستگیره
            if (f > 0.0005f) {
                halo.color = Theme.withAlpha(Theme.GOLD, 0.25f)
                canvas.drawCircle(x, cy, Theme.dp(context, 9f).toFloat(), halo)
                knob.color = Theme.GOLD2
                canvas.drawCircle(x, cy, Theme.dp(context, 6f).toFloat(), knob)
                knob.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(x, cy, Theme.dp(context, 2.5f).toFloat(), knob)
            }
        }
    }
}
