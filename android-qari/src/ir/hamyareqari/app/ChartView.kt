package ir.hamyareqari.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import ir.hamyareqari.dsp.PitchCurve
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** یک نت برای نمایش روی نمودار */
class TraceNote(val start: Double, val duration: Double, val midi: Double)

/**
 * دادهٔ کامل نمودار «ردِّ صدا» — همان ورودیِ رندرر نسخهٔ ۱٫۳٫۹
 * (times / mids / notes / range / duration)
 */
class TraceSpec(
    val times: DoubleArray,
    val mids: DoubleArray,          // MIDI، NaN = بی‌صدا
    val notes: List<TraceNote>,
    val duration: Double,
    val highMidi: Double,
    val lowMidi: Double,
    val spanSemitones: Double,
    /** وضعیت هر نقطه (PitchCurve.VALID/ESTIMATED/…) — null = رفتار قبلی */
    val status: ByteArray? = null
) {
    companion object {
        fun from(r: Report): TraceSpec {
            val n = r.contourT.size
            val mids = DoubleArray(n)
            for (i in 0 until n) {
                val hz = r.contourHz[i]
                mids[i] = if (hz > 0) PitchMath.hzToMidi(hz) else Double.NaN
            }
            val notes = r.notes.map {
                TraceNote(it.start, it.duration, PitchMath.hzToMidi(it.f0))
            }
            return TraceSpec(
                times = r.contourT,
                mids = mids,
                notes = notes,
                duration = r.durationSec,
                highMidi = r.highest?.let { PitchMath.hzToMidi(it.f0) } ?: Double.NaN,
                lowMidi = r.lowest?.let { PitchMath.hzToMidi(it.f0) } ?: Double.NaN,
                spanSemitones = r.spanSemitones,
                status = r.contourStatus
            )
        }
    }
}

/**
 * نمودار «ردِّ صدا» — بازسازیِ دقیقِ رندرر نسخهٔ ۱٫۳٫۹ پروژه:
 *
 *   • منحنی محوِ آینده (خاکستری‌آبی)
 *   • منحنی طلایی سه‌لایه تا لحظهٔ جاری (درخشش + مغز)
 *   • نوارِ ضخیمِ دو-سرگرد برای هر نتِ کشیده؛ نوارِ نتی که همین حالا خوانده
 *     می‌شود تا لحظهٔ جاری کشیده می‌شود (کلفت دیده می‌شود)
 *   • نورِ طلایی با گرادیان شعاعی که با درون‌یابیِ خطی بین نمونه‌ها نرم جابه‌جا
 *     می‌شود، به‌همراه مغزِ سفید
 *   • خط پخش، راهنمای بالاترین/پایین‌ترین، کپسولِ «نتِ همین لحظه»، خطوط نت
 *
 * همان تابع، هم روی صفحه رسم می‌شود، هم فریم‌های ویدیو و هم تصویر PNG؛
 * پس هر سه خروجی عیناً یکی‌اند.
 */
class ChartView(context: Context) : View(context) {

    var onSeek: ((Double) -> Unit)? = null

    // ── تنظیم دستی خط‌چین‌های بالاترین/پایین‌ترین (درگ روی خط) ──────────────

    /** مقدار دستی خطِ بالا (NaN = خودکار) */
    var overrideHighMidi: Double = Double.NaN
        private set

    /** مقدار دستی خطِ پایین (NaN = خودکار) */
    var overrideLowMidi: Double = Double.NaN
        private set

    /** درگ خط‌چین‌ها فقط در نمودارِ «نتیجه» (فایل/ضبط) فعال است — نه نمودار زنده */
    var rangesDraggable = false

    /** فراخوانی هنگام درگ خط: (isHigh, hz) — مقدار آزاد بر حسب هرتز */
    var onRangeDrag: ((Boolean, Double) -> Unit)? = null

    private var dragWhich = 0 // 0=none 1=high 2=low

    /** اعمال محدودهٔ دستی (هر کدام null = بازگشت به مقدار خودکارِ همان خط) */
    fun setRangeOverride(highHz: Double?, lowHz: Double?) {
        overrideHighMidi = if (highHz != null && highHz > 0.0) PitchMath.hzToMidi(highHz) else Double.NaN
        overrideLowMidi = if (lowHz != null && lowHz > 0.0) PitchMath.hzToMidi(lowHz) else Double.NaN
        invalidateFrameCaches()
        invalidate()
    }

    /** مقدار مؤثر خطِ بالا: دستی اگر تنظیم شده باشد، وگرنه خودکار */
    fun effHighMidi(sp: TraceSpec): Double =
        if (!overrideHighMidi.isNaN()) overrideHighMidi else sp.highMidi

    /** مقدار مؤثر خطِ پایین: دستی اگر تنظیم شده باشد، وگرنه خودکار */
    fun effLowMidi(sp: TraceSpec): Double =
        if (!overrideLowMidi.isNaN()) overrideLowMidi else sp.lowMidi

    var spec: TraceSpec? = null
        private set
    var playhead: Double = -1.0
        private set
    var live = false
        private set

    /** با زوم، پنجرهٔ دید همراه پخش جلو می‌رود (قفل نمی‌کند) */
    var followPlayhead = true

    var timeZoom = 1.0
        private set
    var tightness = 1.0          // زوم ارتفاع (۱ = پیش‌فرض)
        private set
    private var viewCenter = Double.NaN

    // ── رنگ‌ها و اندازه‌های مرجع (همان مقادیر trace_style.js) ──────────────
    private object C {
        const val BG_TOP = 0xFF0D172B.toInt()
        const val BG_BOTTOM = 0xFF172440.toInt()
        const val BG_GLOW = 0x33466EB4
        const val GRID_C = 0x29FFFFFF
        const val GRID_N = 0x12FFFFFF
        const val GRID_LABEL = 0x73B4C8EB.toInt()
        const val DIM_CURVE = 0x8C6084AF.toInt()
        const val DIM_SHADOW = 0x596084AF
        const val DIM_EST = 0x666084AF // خط‌چینِ تخمینی — کم‌رنگ‌تر از VALID
        const val NOTE_DIM = 0x61789BC3
        const val GOLD = 0xFFF7D171.toInt()
        const val NOTE_FUTURE = 0x8C8CA8CD.toInt()
        const val NOTE_BAR = 0xFFF7D57F.toInt()
        const val NOTE_BAR_GLOW = 0x4DFFC65C
        const val NOTE_GOLD = 0xFFFFE6A6.toInt()
        const val BORDER = 0x47789BCD
        const val PLAYHEAD_GLOW = 0x2EFFFFFF
        const val PLAYHEAD = 0xD9FFFFFF.toInt()
        const val DOT_IN = 0xF2FFF4D6.toInt()
        const val DOT_MID = 0x8CFFDE8C.toInt()
        const val DOT_CORE = 0xFFFFFAF0.toInt()
        const val FAINT = 0xD9A0B2D2.toInt()
        const val HINT = 0xD9C4D2EB.toInt()
        const val HIGH_LINE = 0x8CFFE2A0.toInt()
        const val LOW_LINE = 0x8CA9D6FF.toInt()
        const val GAP_BRIDGE_SEC = 0.75
        // نور جاری در ۳۸٪ از عرض کادر می‌ایستد (کمی جلوتر از مرکز)
        const val FOLLOW_BIAS = 0.12
        const val BAR_MIN_SEC = 0.34
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()

    // کش «بخش دیده‌شده» برای همین فریم (با حرکت پنجرهٔ زوم هر فریم عوض می‌شود)
    private var frameKey = ""
    private var frameDim: List<FloatArray> = emptyList()
    private var frameGold: List<FloatArray> = emptyList()
    private var frameDimEst: List<FloatArray> = emptyList()

    private val pathDim = Path()
    private val pathGold = Path()

    private class Geom(
        val w: Float, val h: Float,
        val ml: Float, val mr: Float, val mt: Float, val mb: Float,
        val k: Float,
        val loLim: Double, val hiLim: Double,
        val t0: Double, val t1: Double
    ) {
        fun xOf(t: Double): Float = ml + (((t - t0) / max(1e-6, t1 - t0)) * (w - ml - mr)).toFloat()
        fun yOf(m: Double): Float = mt + ((1.0 - (m - loLim) / max(1e-6, hiLim - loLim)) * (h - mt - mb)).toFloat()
        /** معکوس yOf — برای درگ خط‌چین‌ها (MIDI پیوسته = هرتز آزاد) */
        fun midiOf(y: Float): Double =
            loLim + (1.0 - (y - mt) / max(1e-6, (h - mt - mb).toDouble())) * (hiLim - loLim)
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
    }

    // ───────────────────────────── ورودی داده ─────────────────────────────

    /** Keep source order; never sort malformed timestamps into a fabricated melody.
     * A bad timestamp breaks the contour instead of joining unrelated samples. */
    private fun normalizeTrace(source: TraceSpec): TraceSpec {
        val ts = ArrayList<Double>()
        val ms = ArrayList<Double>()
        val statuses = ArrayList<Byte>()
        var broken = false
        for (i in 0 until min(source.times.size, source.mids.size)) {
            val t = source.times[i]
            val m = source.mids[i]
            if (!t.isFinite() || t < 0.0 || (ts.isNotEmpty() && t - ts.last() < 0.000001)) {
                // Conflicting duplicate/backwards data has no defensible ordering.
                if (ts.isNotEmpty()) ms[ms.lastIndex] = Double.NaN
                broken = true
                continue
            }
            ts.add(t)
            ms.add(if (broken || !m.isFinite()) Double.NaN else m)
            statuses.add(source.status?.getOrNull(i) ?: PitchCurve.VALID)
            broken = false
        }
        return TraceSpec(ts.toDoubleArray(), ms.toDoubleArray(), source.notes,
            source.duration, source.highMidi, source.lowMidi, source.spanSemitones,
            if (source.status == null) null else statuses.toByteArray())
    }

    fun setReport(r: Report) {
        spec = normalizeTrace(TraceSpec.from(r))
        live = false
        invalidateFrameCaches()
        invalidate()
    }

    fun setLiveData(times: List<Double>, mids: List<Double>) {
        val n = min(times.size, mids.size)
        val t = DoubleArray(n)
        val m = DoubleArray(n)
        for (i in 0 until n) {
            t[i] = times[i]
            m[i] = mids[i]
        }
        spec = normalizeTrace(TraceSpec(
            times = t, mids = m, notes = emptyList(),
            duration = t.filter { it.isFinite() && it >= 0.0 }.maxOrNull() ?: 1.0,
            highMidi = Double.NaN, lowMidi = Double.NaN, spanSemitones = 0.0
        ))
        invalidateFrameCaches()
        invalidate()
    }

    fun setLive(isLive: Boolean) {
        live = isLive
        invalidateFrameCaches()
        invalidate()
    }

    /** باطل‌کردن کش‌ها (هندسه، بخش دیده‌شده) */
    private fun invalidateFrameCaches() {
        frameKey = ""
        frameDim = emptyList()
        frameGold = emptyList()
        frameDimEst = emptyList()
    }

    fun setPlayhead(sec: Double) {
        playhead = sec
        invalidate()
    }

    // ───────────────────────────── زوم و بازه ─────────────────────────────

    fun zoomTime(factor: Double) {
        timeZoom = (timeZoom * factor).coerceIn(1.0, 16.0)
        invalidateFrameCaches()
        invalidate()
    }

    fun zoomPitch(factor: Double) {
        tightness = (tightness * factor).coerceIn(1.0, 4.0)
        invalidateFrameCaches()
        invalidate()
    }

    fun zoomReset() {
        timeZoom = 1.0
        tightness = 1.0
        viewCenter = Double.NaN
        invalidateFrameCaches()
        invalidate()
    }

    fun setViewCenter(sec: Double) {
        viewCenter = sec
        invalidateFrameCaches()
        invalidate()
    }

    /** مدت کل صدا (برای نوار پخش) */
    fun totalDuration(): Double = spec?.duration ?: 1.0

    fun zoomLabel(): String {
        val window = shownWindow()
        return if (timeZoom <= 1.001) "کل اجرا · ارتفاع ×${PitchMath.faFixed(tightness, 0)}"
        else "پنجرهٔ ${PitchMath.faFixed(window, 0)} ثانیه‌ای · ارتفاع ×${PitchMath.faFixed(tightness, 0)}"
    }

    private fun shownWindow(): Double {
        val total = spec?.duration ?: 1.0
        return max(0.5, total / timeZoom)
    }

    // ───────────────────────────── محاسبات ─────────────────────────────

    // ── پاک‌سازیِ منحنی برای «رسم» (دادهٔ گزارش دست‌نخورده می‌ماند) ──────
    // همان پنج پاسِ رندرر نسخهٔ ۱٫۳٫۹: پرشِ اکتاوی، گشتِ کوتاه، تیغِ تک‌فریمی،
    // سقفِ سرعتِ تغییر گام، و صافیِ میانه.

    /** Reject only an unsupported isolated excursion bracketed by matching pitches.
     * Preserve sustained changes and short ornaments; never modify the report itself. */
    private fun cleanMids(times: DoubleArray, mids: DoubleArray): DoubleArray {
        val out = mids.copyOf()
        for (i in 1 until min(times.size, mids.size) - 1) {
            val a = mids[i - 1]; val b = mids[i]; val c = mids[i + 1]
            val dt0 = times[i] - times[i - 1]; val dt1 = times[i + 1] - times[i]
            if (a.isFinite() && b.isFinite() && c.isFinite() &&
                dt0 > 0 && dt1 > 0 && dt0 <= 0.08 && dt1 <= 0.08 &&
                abs(a - c) <= 1.5 && abs(b - a) > 5.0 && abs(b - c) > 5.0) {
                // No acoustic support for this sample: leave a gap, not invented pitch.
                out[i] = Double.NaN
            }
        }
        return out
    }

    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = q.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = ceil(pos).toInt().coerceAtMost(sorted.size - 1)
        val f = pos - lo
        return sorted[lo] * (1 - f) + sorted[hi] * f
    }

    /** بازهٔ ارتفاع نمایش‌داده‌شده — همان logic نسخهٔ ۱٫۳٫۹ */
    private fun pitchLimits(
        spec: TraceSpec, mids: DoubleArray, t0: Double, t1: Double, nowT: Double
    ): DoubleArray {
        val lim = pitchLimitsRaw(spec, mids, t0, t1, nowT)
        // خط‌های دستی/خودکارِ محدوده همیشه داخل دید بمانند (با کمی فاصله)
        val eHi = effHighMidi(spec)
        val eLo = effLowMidi(spec)
        if (eHi.isNaN() && eLo.isNaN()) return lim
        var lo = lim[0]
        var hi = lim[1]
        if (!eLo.isNaN() && eLo - 1.0 < lo) lo = eLo - 1.0
        if (!eHi.isNaN() && eHi + 1.0 > hi) hi = eHi + 1.0
        return doubleArrayOf(lo, hi)
    }

    private fun pitchLimitsRaw(
        spec: TraceSpec, mids: DoubleArray, t0: Double, t1: Double, nowT: Double
    ): DoubleArray {
        val all = ArrayList<Double>(mids.size)
        for (m in mids) if (!m.isNaN()) all.add(m)
        if (all.isEmpty()) return doubleArrayOf(48.0, 60.0)
        all.sort()
        val arr = all.toDoubleArray()
        var gLo = if (arr.size > 40) percentile(arr, 0.02) else arr.first()
        var gHi = if (arr.size > 40) percentile(arr, 0.98) else arr.last()
        var gCenter = arr[arr.size / 2]

        // گستره از «نت‌های واقعی» (بدون پرش‌های یک‌فریمی)
        if (spec.notes.isNotEmpty()) {
            val nm = spec.notes.map { it.midi }.sorted()
            if (nm.size >= 2) {
                val nsLo = if (nm.size >= 6) percentile(nm.toDoubleArray(), 0.03) else nm.first()
                val nsHi = if (nm.size >= 6) percentile(nm.toDoubleArray(), 0.97) else nm.last()
                if (nsHi > nsLo) {
                    gLo = min(nsLo, gLo + (gHi - gLo) * 0.15)
                    gHi = max(nsHi, gHi - (gHi - gLo) * 0.15)
                    if (gHi < nsHi) gHi = nsHi
                    if (gLo > nsLo) gLo = nsLo
                    gCenter = if (nm.size % 2 == 1) nm[nm.size / 2] else (nm[nm.size / 2 - 1] + nm[nm.size / 2]) / 2
                }
            }
        }
        val base = max(gHi - gLo, 11.9) * 1.2
        val z = if (tightness > 0) tightness else 1.0

        // آمارهٔ محلی بازهٔ دید
        val inView = ArrayList<Double>()
        for (i in spec.times.indices) {
            val m = mids[i]
            if (m.isNaN()) continue
            if (spec.times[i] < t0 || spec.times[i] > t1) continue
            inView.add(m)
        }
        val viewSpan = if (inView.size > 1) {
            val s = inView.sorted()
            if (s.size > 12) percentile(s.toDoubleArray(), 0.97) - percentile(s.toDoubleArray(), 0.03)
            else s.last() - s.first()
        } else 0.0

        if (z <= 1.0001) {
            val shown0 = max(base, viewSpan + 1.0)
            return doubleArrayOf(gCenter - shown0 / 2, gCenter + shown0 / 2)
        }

        var shown = base / z
        if (shown < 4.0) shown = 4.0
        if (shown > base) shown = base

        // مرکز: کوکِ همین لحظه
        var center = gCenter
        val nearLo = ArrayList<Double>()
        if (!nowT.isNaN()) {
            for (i in spec.times.indices) {
                val tt = spec.times[i]
                if (tt < nowT - 1.2 || tt > nowT + 1.2) continue
                val m = mids[i]
                if (!m.isNaN()) nearLo.add(m)
            }
        }
        if (nearLo.isNotEmpty()) {
            val s = nearLo.sorted()
            center = s[s.size / 2]
        } else if (inView.isNotEmpty()) {
            val s = inView.sorted()
            center = s[s.size / 2]
        }

        var half = shown / 2
        if (half < 2.0) half = min(2.0, base / 2)
        if (nearLo.isNotEmpty()) {
            val s = nearLo.sorted()
            val need = max(abs(s.last() - center), abs(center - s.first())) + 0.6
            if (need > half) {
                center = if (s.last() - center > center - s.first()) center + (need - half)
                else center - (need - half)
            }
        }
        center = max(gLo - 0.5 + half, min(gHi + 0.5 - half, center))
        if (center.isNaN()) center = gCenter
        return doubleArrayOf(center - half, center + half)
    }

    /**
     * ساخت منحنی در «فضای زمان» — یک‌بار برای هر گزارش.
     *
     * چرا فضای زمان و نه پیکسل؟ چون با زوم، پنجرهٔ دید با پخش جلو می‌رود.
     * اگر نقطه‌ها با پیکسل ذخیره شوند، هر فریم باید کل منحنی دوباره ساخته
     * شود (همان چیزی که هنگام زوم، رابط را قفل/کند می‌کرد). این‌جا هندسه
     * یک‌بار ساخته می‌شود و هر فریم فقط بخشِ داخل کادر تبدیل به پیکسل
     * می‌شود؛ پس نور طلایی روان حرکت می‌کند.
     */
    private var segSpec: TraceSpec? = null
    private var segMids: DoubleArray? = null
    private var segTimes: List<DoubleArray> = emptyList()
    private var segVals: List<DoubleArray> = emptyList()
    private var segEst: List<Boolean> = emptyList()

    /** بستهٔ سگمنت‌ها: هندسه + وضعیت «تخمینی» بودن هر سگمنت */
    private class SegBundle(
        val times: List<DoubleArray>,
        val vals: List<DoubleArray>,
        val est: List<Boolean>
    )

    private fun segmentsFor(sp: TraceSpec, mids: DoubleArray): SegBundle {
        if (segSpec === sp && segMids === mids) return SegBundle(segTimes, segVals, segEst)

        class Run(val pts: ArrayList<DoubleArray>, val hard: Boolean, val est: Boolean)

        val runs = ArrayList<Run>()
        var cur = ArrayList<DoubleArray>()
        var curHard = false
        var curEst = false
        var lastT = Double.NaN

        fun flush(nextHard: Boolean, nextEst: Boolean) {
            if (cur.size > 1) runs.add(Run(ArrayList(cur), curHard, curEst))
            cur = ArrayList()
            curHard = nextHard
            curEst = nextEst
        }

        for (i in sp.times.indices) {
            val t = sp.times[i]
            val m = mids[i]
            // وضعیت «تخمینی»: نقاط ESTIMATED از VALID جدا رسم می‌شوند
            val isEst = sp.status != null && sp.status!![i] == PitchCurve.ESTIMATED
            if (m.isNaN()) {
                flush(true, isEst)
                lastT = Double.NaN
                continue
            }
            if (!lastT.isNaN() && t - lastT > C.GAP_BRIDGE_SEC) {
                flush(false, isEst)
            } else if (isEst != curEst && cur.isNotEmpty()) {
                // مرز VALID ↔ ESTIMATED: سگمنت جدا (خط‌چین از خط پیوسته قابل تشخیص)
                flush(false, isEst)
            } else if (cur.isNotEmpty() && !lastT.isNaN()) {
                val prevM = cur[cur.size - 1][1]
                // A large change between sparse samples has no evidence for the
                // intervening pitches. Keep both runs, but do not invent a
                // near-vertical transition (especially at whole-file zoom).
                val dt = t - lastT
                val leap = abs(m - prevM)
                if ((leap > 4.0 && dt < 0.05) || (leap > 7.0 && dt <= 0.12)) {
                    flush(true, isEst)
                }
            }
            if (cur.isEmpty()) curEst = isEst
            cur.add(doubleArrayOf(t, m))
            lastT = t
        }
        if (cur.size > 1) runs.add(Run(ArrayList(cur), curHard, curEst))

        // پل‌زدن شکاف‌های کوتاه با شیب معقول (فقط هم‌وضعیت‌ها)
        val merged = ArrayList<ArrayList<DoubleArray>>()
        val mergedEst = ArrayList<Boolean>()
        for (run in runs) {
            if (merged.isEmpty() || run.hard || run.est != mergedEst[mergedEst.size - 1]) {
                merged.add(ArrayList(run.pts))
                mergedEst.add(run.est)
                continue
            }
            val prev = merged[merged.size - 1]
            val a = prev[prev.size - 1]
            val b = run.pts[0]
            val gap = b[0] - a[0]
            val leap = abs(b[1] - a[1])
            val slope = if (gap > 1e-6) leap / gap else 999.0
            if (gap > 0.000001 && gap <= C.GAP_BRIDGE_SEC && leap <= 4.0 && slope <= 12.0) {
                val steps = max(2, min(24, Math.round(gap * 40).toInt() + 2))
                for (j in 1 until steps) {
                    val f = j.toDouble() / steps
                    prev.add(doubleArrayOf(a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f))
                }
                prev.addAll(run.pts)
            } else {
                merged.add(ArrayList(run.pts))
                mergedEst.add(run.est)
            }
        }

        // صاف‌کردن گوشه‌ها (Chaikin، دو مرحله)
        val smoothed = ArrayList<ArrayList<DoubleArray>>(merged.size)
        for (run in merged) {
            var c = run
            for (it in 0 until 2) {
                if (c.size < 3 || c.size > 4000) break
                val nxt = ArrayList<DoubleArray>(c.size * 2)
                nxt.add(c[0])
                for (k in 1 until c.size) {
                    val a0 = c[k - 1]
                    val b0 = c[k]
                    nxt.add(doubleArrayOf(a0[0] * 0.75 + b0[0] * 0.25, a0[1] * 0.75 + b0[1] * 0.25))
                    nxt.add(doubleArrayOf(a0[0] * 0.25 + b0[0] * 0.75, a0[1] * 0.25 + b0[1] * 0.75))
                }
                nxt.add(c[c.size - 1])
                c = nxt
            }
            smoothed.add(c)
        }

        // جداکردن زمان و ارتفاع + سبک‌سازی زمانی (هر ۴ms یک نقطه کافی است)
        val times = ArrayList<DoubleArray>(smoothed.size)
        val vals = ArrayList<DoubleArray>(smoothed.size)
        val est = ArrayList<Boolean>(smoothed.size)
        for ((si, run) in smoothed.withIndex()) {
            if (run.size < 2) continue
            val keepT = ArrayList<Double>(run.size)
            val keepM = ArrayList<Double>(run.size)
            var lastKept = -1e9
            for (i in run.indices) {
                val t = run[i][0]
                if (i == run.size - 1 || t - lastKept >= 0.004) {
                    keepT.add(t)
                    keepM.add(run[i][1])
                    lastKept = t
                }
            }
            if (keepT.size > 1) {
                times.add(keepT.toDoubleArray())
                vals.add(keepM.toDoubleArray())
                est.add(if (si < mergedEst.size) mergedEst[si] else false)
            }
        }

        segSpec = sp
        segMids = mids
        segTimes = times
        segVals = vals
        segEst = est
        return SegBundle(times, vals, est)
    }

    // ───────────────────────────── رسم ─────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawTrace(canvas, width.toFloat(), height.toFloat(), if (playhead >= 0) playhead else Double.NaN)
    }

    /** رسم کامل یک فریم. tNow = لحظهٔ جاری (NaN یعنی انتهای داده) */
    @Synchronized
    fun drawTrace(canvas: Canvas, w: Float, h: Float, tNow: Double) {
        val sp = spec
        val bg = LinearGradient(0f, 0f, 0f, h, C.BG_TOP, C.BG_BOTTOM, Shader.TileMode.CLAMP)
        p.shader = bg
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        if (sp == null || sp.times.isEmpty()) {
            drawEmpty(canvas, w, h)
            return
        }

        val k = (w / 700f).coerceIn(0.8f, 2.0f)
        val ml = Math.round(w * 0.0335f).toFloat()
        val mr = Math.round(w * 0.0215f).toFloat()
        val mt = Math.round(h * 0.1083f).toFloat()
        val mb = Math.round(h * 0.0889f).toFloat()

        // بازهٔ دید
        val total = max(0.001, sp.duration)
        val t1: Double
        val t0: Double
        if (live) {
            t1 = max(1.0, if (tNow.isNaN()) sp.times.last() else tNow)
            t0 = max(0.0, t1 - max(2.0, sp.duration.coerceAtLeast(2.0)))
        } else if (timeZoom <= 1.001) {
            t0 = 0.0
            t1 = total
        } else {
            val win = total / timeZoom
            // وقتی دنبال‌کردن روشن است، کادر با پخش جلو می‌رود و نور طلایی
            // هیچ‌وقت به لبه نمی‌رسد و «قفل» نمی‌شود. کمی جلوتر از مرکز
            // می‌ایستد تا نت‌های بعدی هم دیده شوند.
            val center = when {
                followPlayhead && !tNow.isNaN() -> tNow + win * C.FOLLOW_BIAS
                !viewCenter.isNaN() -> viewCenter
                !tNow.isNaN() -> tNow
                else -> total / 2
            }
            val a = (center - win / 2).coerceIn(0.0, max(0.0, total - win))
            t0 = a
            t1 = a + win
        }

        val midsClean = cleanMids(sp.times, sp.mids)
        val lim = pitchLimits(sp, midsClean, t0, t1, tNow)
        val g = Geom(w, h, ml, mr, mt, mb, k, lim[0], lim[1], t0, t1)

        // هالهٔ پس‌زمینه
        val halo = RadialGradient(
            w / 2, h / 2, max(w, h) * 0.62f,
            intArrayOf(C.BG_GLOW, 0x00466EB4.toInt()),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        p.shader = halo
        canvas.drawRect(0f, 0f, w, h, p)
        p.shader = null

        val endT = if (tNow.isNaN()) total else min(tNow, g.t1)
        buildFramePaths(sp, midsClean, g, endT)

        drawGrid(canvas, sp, g, k)
        drawDimCurve(canvas, g)
        drawDimNotes(canvas, sp, g, k, tNow)
        drawBorder(canvas, w, h)

        drawGoldCurve(canvas, g, k, endT)
        drawPastNotes(canvas, sp, g, k, endT)
        drawRangeGuides(canvas, sp, g, k)
        drawPlayhead(canvas, g, k, endT, h)
        drawGlowDot(canvas, sp, g, k, endT, midsClean)
        drawNowPill(canvas, sp, g, k, endT, midsClean)
        drawFooter(canvas, sp, g, k, endT, h)
    }

    private fun drawEmpty(canvas: Canvas, w: Float, h: Float) {
        textPaint.color = 0x66FFFFFF
        textPaint.textSize = Theme.dpF(context, 12f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Theme.font(context, false)
        canvas.drawText("هنوز داده‌ای نیست", w / 2, h / 2, textPaint)
    }

    private fun drawGrid(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float) {
        val loI = ceil(g.loLim).toInt()
        val hiI = floor(g.hiLim).toInt()
        val labelAll = (g.hiLim - g.loLim) <= 9.0
        textPaint.typeface = Theme.font(context, true)
        textPaint.textSize = 10f * k
        textPaint.textAlign = Paint.Align.LEFT
        var m = loI
        while (m <= hiI) {
            val y = g.yOf(m.toDouble())
            val isC = ((m % 12) + 12) % 12 == 0
            stroke.color = if (isC) C.GRID_C else C.GRID_N
            stroke.strokeWidth = if (isC) 1.4f else 1f
            canvas.drawLine(6f, y, g.w - 6f, y, stroke)
            if ((isC || labelAll) && m > g.loLim + 0.3 && m < g.hiLim - 0.3) {
                textPaint.color = C.GRID_LABEL
                canvas.drawText(PitchMath.latinNameAscii(m), 8f, y - 4f * k, textPaint)
            }
            m++
        }
    }

    /**
     * تبدیلِ فقط «بخشِ داخل کادر» به پیکسل برای همین فریم.
     *
     * این تابع قلبِ روان‌بودنِ زوم است: با هر فریم، پنجره دو-سه پیکسل
     * جابه‌جا می‌شود، ولی ما فقط نقطه‌های همان بازه را تبدیل می‌کنیم
     * (در زوم ×۱۶ حدود ۶٪ از کل منحنی) و نقطه‌هایی که به یک پیکسل
     * می‌افتند را رد می‌کنیم. نتیجه: بدون لَگی و بدون قفل.
     */
    private fun buildFramePaths(sp: TraceSpec, mids: DoubleArray, g: Geom, endT: Double) {
        val key = "${g.t0},${g.t1},${g.loLim},${g.hiLim},${g.w},${g.h},$endT"
        if (key == frameKey) return

        val segs = segmentsFor(sp, mids)
        val times = segs.times
        val vals = segs.vals
        val dim = ArrayList<FloatArray>(times.size)
        val gold = ArrayList<FloatArray>(times.size)
        val dimEst = ArrayList<FloatArray>(times.size)
        val spanT = max(1e-6, g.t1 - g.t0)
        val spanM = max(1e-6, g.hiLim - g.loLim)
        val usableW = g.w - g.ml - g.mr
        val usableH = g.h - g.mt - g.mb
        val t0m = g.t0 - spanT * 0.02
        val t1m = g.t1 + spanT * 0.02

        for (si in times.indices) {
            val ts = times[si]
            val ms = vals[si]
            val n = ts.size
            if (n < 2) continue
            // جست‌وجوی دودویی: اولین نقطهٔ داخل کادر
            var lo = 0
            var hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (ts[mid] < t0m) lo = mid + 1 else hi = mid
            }
            val start = max(0, lo - 1)
            // آخرین نقطهٔ داخل کادر
            lo = start
            hi = n - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (ts[mid] > t1m) hi = mid - 1 else lo = mid
            }
            val end = min(n - 1, lo + 1)
            if (end - start < 1) continue

            var dimBuf = FloatArray(min(end - start + 2, 4096) * 2)
            var goldBuf = FloatArray(dimBuf.size)
            var dimN = 0
            var goldN = 0
            var lastX = Float.NaN
            var lastY = Float.NaN
            var lastTime = Double.NaN
            var goldOpen = false
            var closed = false

            for (i in start..end) {
                val x = (g.ml + ((ts[i] - g.t0) / spanT) * usableW).toFloat()
                val y = (g.mt + ((1.0 - (ms[i] - g.loLim) / spanM) * usableH)).toFloat()
                // نقطه‌های هم‌پیکسل حذف می‌شوند (سبک‌سازی)
                if (!lastX.isNaN() && abs(x - lastX) < 0.35f && abs(y - lastY) < 0.35f) continue
                if (dimN + 2 > dimBuf.size) dimBuf = dimBuf.copyOf(dimBuf.size * 2)
                dimBuf[dimN] = x
                dimBuf[dimN + 1] = y
                dimN += 2
                if (ts[i] <= endT) {
                    if (goldN + 2 > goldBuf.size) goldBuf = goldBuf.copyOf(goldBuf.size * 2)
                    goldBuf[goldN] = x
                    goldBuf[goldN + 1] = y
                    goldN += 2
                    goldOpen = true
                } else if (goldOpen && !closed) {
                    // نقطهٔ پایانیِ نور: میانِ این نقطه و قبلی، تا حرکت نرم بماند
                    if (goldN + 2 <= goldBuf.size) {
                        val f = ((endT - lastTime) / (ts[i] - lastTime)).coerceIn(0.0, 1.0).toFloat()
                        goldBuf[goldN] = lastX + (x - lastX) * f
                        goldBuf[goldN + 1] = lastY + (y - lastY) * f
                        goldN += 2
                    }
                    closed = true
                }
                lastX = x
                lastY = y
                lastTime = ts[i]
            }
            if (dimN >= 4) {
                // سگمنت‌های «تخمینی» جدا و کم‌رنگ‌تر رسم می‌شوند (قابل تشخیص از VALID)
                if (segs.est[si]) dimEst.add(dimBuf.copyOf(dimN)) else dim.add(dimBuf.copyOf(dimN))
            }
            if (goldN >= 4 && !segs.est[si]) gold.add(goldBuf.copyOf(goldN))
        }
        frameDim = dim
        frameGold = gold
        frameDimEst = dimEst
        frameKey = key
    }

    private fun drawDimCurve(canvas: Canvas, g: Geom) {
        if (frameDim.isEmpty() && frameDimEst.isEmpty()) return
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.pathEffect = null
        for (pts in frameDim) {
            pathDim.reset()
            pathDim.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) {
                pathDim.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            stroke.color = C.DIM_SHADOW
            stroke.strokeWidth = 3.8f * g.k
            canvas.drawPath(pathDim, stroke)
            stroke.color = C.DIM_CURVE
            stroke.strokeWidth = 2.7f * g.k
            canvas.drawPath(pathDim, stroke)
        }
        // نقاط «تخمینی» (ESTIMATED): خط‌چین کم‌رنگ — از VALID قابل تشخیص
        if (frameDimEst.isNotEmpty()) {
            stroke.pathEffect = DashPathEffect(floatArrayOf(6f * g.k, 5f * g.k), 0f)
            for (pts in frameDimEst) {
                pathDim.reset()
                pathDim.moveTo(pts[0], pts[1])
                var i = 2
                while (i + 1 < pts.size) {
                    pathDim.lineTo(pts[i], pts[i + 1])
                    i += 2
                }
                stroke.color = C.DIM_EST
                stroke.strokeWidth = 2.1f * g.k
                canvas.drawPath(pathDim, stroke)
            }
            stroke.pathEffect = null
        }
    }

    private fun drawDimNotes(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, tNow: Double) {
        for (n in sp.notes) {
            if (n.midi.isNaN()) continue
            val centerT = n.start + n.duration / 2
            if (centerT < g.t0 || centerT > g.t1) continue
            if (n.duration >= C.BAR_MIN_SEC) {
                drawNoteBar(canvas, g, n, Double.NaN, C.NOTE_FUTURE, 0, 0.42f, k)
            } else {
                val x = g.xOf(centerT)
                val y = g.yOf(n.midi)
                fill.color = C.NOTE_DIM
                rect.set(x - 4.1f * k, y - 2.4f * k, x + 4.1f * k, y + 2.4f * k)
                canvas.drawOval(rect, fill)
            }
        }
    }

    private fun barWidth(duration: Double, k: Float): Float {
        var d = if (duration > 0) duration else 0.0
        if (d > 4.0) d = 4.0
        var w = 5.4 + 3.2 * d
        if (w > 16.0) w = 16.0
        return (w * k).toFloat()
    }

    /**
     * نوار ضخیمِ یک نت. اگر tLimit داده شود و نت در حال اجرا باشد، نوار فقط تا
     * همان لحظه کشیده می‌شود (همان «کلفت شدنِ نتی که نور رویش است»).
     */
    private fun drawNoteBar(
        canvas: Canvas, g: Geom, note: TraceNote, tLimit: Double,
        color: Int, glowColor: Int, widthScale: Float, k: Float
    ): Boolean {
        if (note.duration < C.BAR_MIN_SEC || note.midi.isNaN()) return false
        val start = note.start
        var end = start + note.duration
        val growing = !tLimit.isNaN() && tLimit < end
        if (growing) end = max(start, tLimit)
        val inset = min(note.duration * 0.18, 0.10)
        var x0 = g.xOf(start + inset)
        var x1 = g.xOf(end - (if (growing) 0.0 else inset))
        val y = g.yOf(note.midi)
        if (x1 < x0) { val tmp = x0; x0 = x1; x1 = tmp }
        val bw = barWidth(note.duration, k) * (if (widthScale > 0) widthScale else 1f)
        stroke.strokeCap = Paint.Cap.ROUND
        if (glowColor != 0) {
            stroke.color = glowColor
            stroke.strokeWidth = bw * 1.55f
            canvas.drawLine(x0, y, x1, y, stroke)
        }
        stroke.color = color
        stroke.strokeWidth = bw
        canvas.drawLine(x0, y, x1, y, stroke)
        return true
    }

    private fun drawBorder(canvas: Canvas, w: Float, h: Float) {
        stroke.color = C.BORDER
        stroke.strokeWidth = 1.4f
        rect.set(3f, 3f, w - 3f, h - 3f)
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
    }

    private fun drawGoldCurve(canvas: Canvas, g: Geom, k: Float, endT: Double) {
        if (frameGold.isEmpty()) return
        val widths = floatArrayOf(8.2f, 4.6f, 2.7f)
        val colors = intArrayOf(0x26FFD478, 0x57FFD478, C.GOLD)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        for (pts in frameGold) {
            // یک مسیر، سه لایه — به‌جای ساختن دوبارهٔ مسیر برای هر لایه
            pathGold.reset()
            pathGold.moveTo(pts[0], pts[1])
            var i = 2
            while (i + 1 < pts.size) {
                pathGold.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            for (li in 0 until 3) {
                stroke.color = colors[li]
                stroke.strokeWidth = widths[li] * k
                canvas.drawPath(pathGold, stroke)
            }
        }
    }

    private fun drawPastNotes(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, endT: Double) {
        for (n in sp.notes) {
            if (n.start > endT) continue
            if (n.start < g.t0 || n.start > g.t1) continue
            if (n.duration >= C.BAR_MIN_SEC) {
                drawNoteBar(canvas, g, n, endT, C.NOTE_BAR, C.NOTE_BAR_GLOW, 1f, k)
            } else {
                val x = g.xOf(n.start + n.duration / 2)
                val y = g.yOf(n.midi)
                fill.color = C.NOTE_GOLD
                val r = 2.6f * k
                rect.set(x - r * 1.5f, y - r, x + r * 1.5f, y + r)
                canvas.drawOval(rect, fill)
            }
        }
    }

    private fun drawRangeGuides(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float) {
        val hi = effHighMidi(sp)
        val lo = effLowMidi(sp)
        if (!hi.isNaN()) drawGuide(canvas, sp, g, k, hi, true)
        if (!lo.isNaN()) drawGuide(canvas, sp, g, k, lo, false)
    }

    private fun drawGuide(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, midi: Double, high: Boolean) {
        var y = g.yOf(midi)
        val yTop = g.mt + 6f * k
        val yBot = g.h - g.mb - 6f * k
        var edgeUp = false
        var edgeDown = false
        if (y < yTop) { y = yTop; edgeUp = true }
        if (y > yBot) { y = yBot; edgeDown = true }
        val color = if (high) C.HIGH_LINE else C.LOW_LINE

        stroke.color = color
        stroke.strokeWidth = 1.4f * k
        stroke.pathEffect = DashPathEffect(floatArrayOf(7f * k, 6f * k), 0f)
        canvas.drawLine(6f * k, y, g.w - 6f * k, y, stroke)
        stroke.pathEffect = null

        val deg = run {
            val baseLow = effLowMidi(sp)
            if (!baseLow.isNaN()) PitchMath.degreeFromLow(Math.round(midi).toInt(), Math.round(baseLow).toInt()) else 0
        }
        // برای مقدار دستی: نامِ نزدیک‌ترین نت + فاصلهٔ سنتی (مقدار آزاد است، نیم‌پرده‌ای نیست)
        val manualLine = if (high) !overrideHighMidi.isNaN() else !overrideLowMidi.isNaN()
        val txt = if (manualLine) {
            val hz = PitchMath.midiToHz(midi)
            val off = PitchMath.centsOffPitch(hz)
            val offTxt = PitchMath.faDigits(Math.round(off).toInt()).let { if (off >= 0) "+$it" else it }
            (if (high) "بالاترین " else "پایین‌ترین ") + PitchMath.noteName(hz) +
                " · $offTxt سنت · ${PitchMath.faFixed(hz, 1)} هرتز"
        } else {
            (if (high) "بالاترین " else "پایین‌ترین ") + PitchMath.solfegeWithOctave(Math.round(midi).toInt()) +
                (if (deg > 0) " · درجهٔ ${PitchMath.faDigits(deg)}" else "")
        }
        textPaint.typeface = Theme.font(context, true)
        textPaint.textSize = 10f * k
        textPaint.textAlign = Paint.Align.RIGHT
        val tw = textPaint.measureText(txt)
        val bx = if (high) g.w - tw - 18f * k else g.ml + 2f * k
        val by = if (high) {
            if (y + 13f * k < g.h - g.mb) y + 13f * k else y - 6f * k
        } else {
            if (y - 8f * k > g.mt) y - 6f * k else y + 14f * k
        }
        fill.color = 0xDB081020.toInt()
        rect.set(bx - 5f * k, by - 10f * k, bx + tw + 5f * k, by + 4f * k)
        canvas.drawRoundRect(rect, 6f * k, 6f * k, fill)
        textPaint.color = color
        if (high) {
            canvas.drawText(txt, bx + tw, by, textPaint)
        } else {
            textPaint.textAlign = Paint.Align.LEFT
            canvas.drawText(txt, bx, by, textPaint)
        }
        // نشانگر لبه (مثلث کوچک) اگر خط از کادر بیرون افتاده باشد
        if (edgeUp || edgeDown) {
            val tx = if (high) bx + tw + 4f * k else bx - 8f * k
            val ty = by - 3.5f * k
            path.reset()
            if (edgeUp) {
                path.moveTo(tx, ty - 3.5f * k)
                path.lineTo(tx - 3f * k, ty + 2f * k)
                path.lineTo(tx + 3f * k, ty + 2f * k)
            } else {
                path.moveTo(tx, ty + 3f * k)
                path.lineTo(tx - 3f * k, ty - 2f * k)
                path.lineTo(tx + 3f * k, ty - 2f * k)
            }
            path.close()
            fill.color = color
            canvas.drawPath(path, fill)
        }
    }

    private fun drawPlayhead(canvas: Canvas, g: Geom, k: Float, endT: Double, h: Float) {
        val x = g.xOf(endT)
        stroke.color = C.PLAYHEAD_GLOW
        stroke.strokeWidth = 4.2f * k
        canvas.drawLine(x, 4f, x, h - 4f, stroke)
        stroke.color = C.PLAYHEAD
        stroke.strokeWidth = 1.6f * k
        canvas.drawLine(x, 4f, x, h - 4f, stroke)
    }

    /** ارتفاع لحظهٔ جاری با درون‌یابی خطی بین نمونه‌ها (حرکت نرمِ نور) */
    private fun currentMidi(sp: TraceSpec, mids: DoubleArray, t: Double): Double {
        val times = sp.times
        if (times.isEmpty()) return Double.NaN
        if (t <= times[0]) return mids[0]
        if (t >= times[times.size - 1]) return mids[mids.size - 1]
        var lo = 0
        var hi = times.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        val i = lo - 1
        val m0 = mids[i]
        val m1 = mids[min(i + 1, mids.size - 1)]
        if (m0.isNaN() && m1.isNaN()) return Double.NaN
        if (m0.isNaN() || m1.isNaN() || times[i + 1] - times[i] > C.GAP_BRIDGE_SEC) return Double.NaN
        val t0 = times[i]
        val t1 = times[min(i + 1, times.size - 1)]
        val f = if (t1 > t0) ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0) else 0.0
        return m0 + (m1 - m0) * f
    }

    private fun drawGlowDot(
        canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, endT: Double, mids: DoubleArray
    ) {
        val mNow = currentMidi(sp, mids, endT)
        if (mNow.isNaN()) return
        val x = g.xOf(endT)
        val y = g.yOf(mNow)
        val r = 25f * k
        val glow = RadialGradient(
            x, y, r,
            intArrayOf(C.DOT_IN, C.DOT_MID, 0x00FFDE8C.toInt()),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        fill.shader = glow
        canvas.drawCircle(x, y, r, fill)
        fill.shader = null
        fill.color = C.DOT_CORE
        canvas.drawCircle(x, y, 5.6f * k, fill)

        // حلقهٔ نرمِ اضافه تا نور روی پس‌زمینهٔ روشن هم دیده شود
        stroke.color = 0x33FFD478
        stroke.strokeWidth = 1.2f * k
        canvas.drawCircle(x, y, 9.5f * k, stroke)
    }

    private fun drawNowPill(
        canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, endT: Double, mids: DoubleArray
    ) {
        val mNow = currentMidi(sp, mids, endT)
        val muted = mNow.isNaN()
        val nameTxt = if (muted) "سکوت" else PitchMath.solfegeWithOctave(Math.round(mNow).toInt())
        val deg = if (!muted && !sp.lowMidi.isNaN())
            PitchMath.degreeFromLow(Math.round(mNow).toInt(), Math.round(sp.lowMidi).toInt()) else 0
        val degTxt = if (deg > 0) "درجهٔ ${PitchMath.faDigits(deg)}" else ""
        val hzTxt = if (!muted) PitchMath.faFixed(440.0 * Math.pow(2.0, (mNow - 69.0) / 12.0), 1) + " هرتز" else ""

        val padX = 11f * k
        val padY = 7f * k
        val x = 9f * k
        val y = 8f * k
        textPaint.typeface = Theme.font(context, true)
        textPaint.textSize = 20f * k
        val wName = textPaint.measureText(nameTxt)
        textPaint.textSize = 10.5f * k
        val wDeg = textPaint.measureText(degTxt)
        val wHz = textPaint.measureText(hzTxt)
        val wSub = wDeg + (if (degTxt.isNotEmpty() && hzTxt.isNotEmpty()) 8f * k else 0f) + wHz
        val innerW = max(wName, wSub)
        val boxW = innerW + padX * 2
        val boxH = 20f * k + 6f * k + 12f * k + padY * 2

        fill.color = 0xD9081020.toInt()
        rect.set(x, y, x + boxW, y + boxH)
        canvas.drawRoundRect(rect, 12f * k, 12f * k, fill)
        stroke.color = if (muted) 0x33FFFFFF else 0x59FFD478
        stroke.strokeWidth = 1f * k
        canvas.drawRoundRect(rect, 12f * k, 12f * k, stroke)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.typeface = Theme.font(context, true)
        textPaint.textSize = 20f * k
        textPaint.color = if (muted) Theme.DIM else Theme.GOLD2
        val nameX = x + (boxW - wName) / 2
        canvas.drawText(nameTxt, nameX, y + padY + 16f * k, textPaint)

        // خط دوم: «درجهٔ n» و فرکانس، پشت‌سرهم و بدون همپوشانی
        textPaint.typeface = Theme.font(context, false)
        textPaint.textSize = 10.5f * k
        textPaint.color = C.FAINT
        var cx = x + padX
        if (degTxt.isNotEmpty()) {
            canvas.drawText(degTxt, cx, y + padY + 29f * k, textPaint)
            cx += wDeg + 8f * k
        }
        if (hzTxt.isNotEmpty()) {
            canvas.drawText(hzTxt, cx, y + padY + 29f * k, textPaint)
        }
    }

    private fun drawFooter(canvas: Canvas, sp: TraceSpec, g: Geom, k: Float, endT: Double, h: Float) {
        textPaint.typeface = Theme.font(context, false)
        textPaint.textSize = 11f * k
        textPaint.color = C.FAINT
        textPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(
            "${PitchMath.faFixed(endT, 1)} / ${PitchMath.faFixed(sp.duration, 1)} ثانیه",
            g.w - 10f * k, 24f * k, textPaint
        )
        val eHi = effHighMidi(sp)
        val eLo = effLowMidi(sp)
        if (!eHi.isNaN() && !eLo.isNaN()) {
            textPaint.color = C.HINT
            val spanSemis = if (!overrideHighMidi.isNaN() || !overrideLowMidi.isNaN())
                PitchMath.cents(PitchMath.midiToHz(eHi), PitchMath.midiToHz(eLo)) / 100.0
            else sp.spanSemitones
            val span = PitchMath.spanInDegrees(spanSemis)
            canvas.drawText(
                "${PitchMath.solfegeWithOctave(Math.round(eHi).toInt())} ↕ " +
                    "${PitchMath.solfegeWithOctave(Math.round(eLo).toInt())}  ·  ${span.text}",
                g.w - 10f * k, h - 9f * k, textPaint
            )
        }
    }

    // ───────────────────────────── لمس و پخش ─────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (live) return false
        val sp = spec ?: return false

        // ── درگ خط‌چین‌های بالاترین/پایین‌ترین (فقط وقتی rangesDraggable) ──
        if (rangesDraggable) {
            val gNow = geomForTouch(sp)
            val k = (width / 700f).coerceIn(0.8f, 2.0f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (gNow != null) {
                        val yH = if (!effHighMidi(sp).isNaN()) gNow.yOf(effHighMidi(sp)) else Float.MAX_VALUE
                        val yL = if (!effLowMidi(sp).isNaN()) gNow.yOf(effLowMidi(sp)) else Float.MAX_VALUE
                        val grab = 30f * k
                        val dH = Math.abs(event.y - yH)
                        val dL = Math.abs(event.y - yL)
                        dragWhich = when {
                            dH <= grab && dH <= dL -> 1
                            dL <= grab -> 2
                            else -> 0
                        }
                        if (dragWhich != 0) {
                            parent?.requestDisallowInterceptTouchEvent(true)
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (dragWhich != 0 && gNow != null) {
                        var midi = gNow.midiOf(event.y)
                        // خط‌ها از هم عبور نمی‌کنند (حداقل ۰٫۲ نیم‌پرده فاصله)
                        if (dragWhich == 1) {
                            val loM = effLowMidi(sp)
                            if (!loM.isNaN() && midi < loM + 0.2) midi = loM + 0.2
                        } else {
                            val hiM = effHighMidi(sp)
                            if (!hiM.isNaN() && midi > hiM - 0.2) midi = hiM - 0.2
                        }
                        midi = midi.coerceIn(12.0, 108.0)
                        val hz = PitchMath.midiToHz(midi)
                        if (dragWhich == 1) overrideHighMidi = midi else overrideLowMidi = midi
                        invalidateFrameCaches()
                        invalidate()
                        onRangeDrag?.invoke(dragWhich == 1, hz)
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragWhich != 0) {
                        dragWhich = 0
                        parent?.requestDisallowInterceptTouchEvent(false)
                        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                        return true
                    }
                }
            }
        }

        if (event.action == MotionEvent.ACTION_UP) {
            val k = (width / 700f).coerceIn(0.8f, 2.0f)
            val ml = Math.round(width * 0.0335f).toFloat()
            val mr = Math.round(width * 0.0215f).toFloat()
            val left = ml
            val right = width - mr
            if (event.x in left..right) {
                val frac = ((event.x - left) / (right - left)).coerceIn(0f, 1f).toDouble()
                val total = max(0.001, sp.duration)
                val (t0, t1) = viewWindow(total)
                onSeek?.invoke(t0 + frac * (t1 - t0))
                performClick()
            }
        }
        return true
    }

    /** هندسهٔ لحظه‌ای برای تبدیل لمس ↔ MIDI (همان فرمول drawTrace برای دیدِ جاری) */
    private fun geomForTouch(sp: TraceSpec): Geom? {
        if (sp.times.isEmpty() || width == 0 || height == 0) return null
        val w = width.toFloat()
        val h = height.toFloat()
        val k = (w / 700f).coerceIn(0.8f, 2.0f)
        val ml = Math.round(w * 0.0335f).toFloat()
        val mr = Math.round(w * 0.0215f).toFloat()
        val mt = Math.round(h * 0.1083f).toFloat()
        val mb = Math.round(h * 0.0889f).toFloat()
        val total = max(0.001, sp.duration)
        val (t0, t1) = viewWindow(total)
        val midsClean = cleanMids(sp.times, sp.mids)
        val lim = pitchLimits(sp, midsClean, t0, t1, if (playhead >= 0) playhead else Double.NaN)
        return Geom(w, h, ml, mr, mt, mb, k, lim[0], lim[1], t0, t1)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun viewWindow(total: Double): Pair<Double, Double> {
        return if (timeZoom <= 1.001) 0.0 to total
        else {
            val win = total / timeZoom
            val center = if (followPlayhead && playhead >= 0) playhead + win * C.FOLLOW_BIAS
            else if (!viewCenter.isNaN()) viewCenter
            else if (playhead > 0) playhead else total / 2
            val a = (center - win / 2).coerceIn(0.0, max(0.0, total - win))
            a to a + win
        }
    }

    // ───────────────────────────── خروجی تصویر ─────────────────────────────

    /** تصویر تحلیل: نمودار + سرصفحه + پانویس (همان رندرر) */
    /**
     * خروجی PNG: عیناً همان نموداری که روی صفحه می‌بینی (یک رندرر برای
     * صفحه، تصویر و ویدیو). عنوان/خطوط فقط برای سازگاری امضا نگه داشته شده‌اند.
     */
    fun renderToBitmap(w: Int, h: Int, title: String, lines: List<String>): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val at = if (playhead >= 0) playhead else 0.0
        drawTrace(c, w.toFloat(), h.toFloat(), at)
        return bmp
    }

    /** رسم فریم ویدیو با همان رندرر (خروجی ویدیو = همان چیزی که می‌بینی) */
    @Synchronized
    fun drawFrameForVideo(c: Canvas, w: Float, h: Float, unit: Float, t: Double) {
        drawTrace(c, w, h, t)
    }
}
