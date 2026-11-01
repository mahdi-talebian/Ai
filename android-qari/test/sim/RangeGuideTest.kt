import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import ir.hamyareqari.app.ChartView
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.RangeMath
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.ShortSignal
import kotlin.math.abs

/**
 * تست رگرسیونِ خروجی‌های محدودهٔ دستی (PNG + MP4):
 *
 *   T1 تصویر با هر دو مقدار دستی — خط‌ها در موقعیت‌های پیش‌بینی‌شده (کالیبراسیون محور)
 *   T2 فریم ویدیو با هر دو مقدار دستی — همان موقعیت‌ها در چند فریم
 *   T3 رندررِ جداگانهٔ ویدیو (ChartView تازه + انتقال عکس لحظه‌ای) = همان موقعیت‌ها
 *   T4 فقط بالا دستی — بالا دستی + پایین خودکار (بدون مقدار کهنه)
 *   T5 فقط پایین دستی — پایین دستی + بالا خودکار (بدون مقدار کهنه)
 *   T6 بازگشت به خودکار (هر دو null) — PNG و فریم ویدیو = محدودهٔ خودکار
 *   T7 سازگاری متادیتا — جفت مؤثر و سربرگ با همان مقادیرِ نمودار (هر دو حالت)
 *   T8 بدون تغییر دستی — خروجی عین رفتار خودکارِ ۱٫۲٫۲
 *
 * روش: رندر واقعی روی BufferedImage (استاب‌های گرافیکی AWT) + تشخیص ردیفِ
 * هر خط‌چین از روی رنگش + کالیبراسیونِ محورِ عمودی از داخلِ خودِ رندرها
 * (همهٔ مقادیر دستی داخل پنجرهٔ نمایش انتخاب می‌شوند تا مقیاس بین رندرها
 * ثابت بماند). ابعاد PNG و فریم ویدیو یکسان است تا مقایسهٔ مختصات بی‌معنا نشود.
 */
object RangeGuideTest {

    private var failures = 0

    private fun check(ok: Boolean, label: String, extra: String = "") {
        if (ok) println("  ✅ $label${if (extra.isEmpty()) "" else " — $extra"}")
        else { failures++; println("  ❌ $label${if (extra.isEmpty()) "" else " — $extra"}") }
    }

    /**
     * گزارش مصنوعی: نتِ ۲۲۰ هرتز (۰ تا ۰٫۵ ثانیه) + نتِ ۴۴۰ هرتز (۱٫۵ تا ۲٫۰ ثانیه)
     * — دو نتِ پایدار با نوارهای کوتاه (~۲۵٪ عرض) تا با ردیفِ خط‌ها قاطی نشوند.
     */
    private fun makeReport(): Report {
        val sr = 22050
        val n = sr * 2
        val x = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = when {
                t < 0.5 -> 220.0
                t >= 1.5 && t < 2.0 -> 440.0
                else -> 0.0
            }
            x[i] = if (f > 0) 0.5 * kotlin.math.sin(2.0 * Math.PI * f * t) else 0.0
        }
        val pcm = ShortArray(n) { i -> (x[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, sr)
        return Analyzer.analyzeSignal(ShortSignal(pcm, sr))
    }

    /**
     * ردیفِ خطِ راهنما (خطوط تمام‌عرضِ رنگی، متمایز از شبکه/منحنی/نوار نت‌ها).
     * خطِ بالا گرم/طلایی‌کم‌رنگ (۱۴۷٬۱۳۶٬۱۱۱ تا ۸۸٬۸۹٬۹۳ با پوشش زیرپیکسلی)،
     * خطِ پایین آبی‌کم‌رنگ (۱۰۴٬۱۳۴٬۱۷۰ تا ۶۴٬۸۶٬۱۱۹).
     */
    private fun guideRow(bmp: Bitmap, high: Boolean): Int {
        val w = bmp.width
        var best = -1
        var bestCount = 0
        for (y in 0 until bmp.height) {
            var c = 0
            for (x in 15 until w - 15) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val hit = if (high) (r in 75..215 && r >= b - 8 && g >= b - 25)
                else (b in 100..255 && b >= r + 15 && g >= r)
                if (hit) c++
            }
            if (c > bestCount) { bestCount = c; best = y }
        }
        return if (bestCount >= (w - 30) * 35 / 100) best else -1
    }

    private fun sameRaster(a: Bitmap, b: Bitmap): Boolean {
        if (a.width != b.width || a.height != b.height) return false
        for (y in 0 until a.height) for (x in 0 until a.width) if (a.getPixel(x, y) != b.getPixel(x, y)) return false
        return true
    }

    private fun renderPng(chart: ChartView, w: Int = 420, h: Int = 640): Bitmap =
        chart.renderToBitmap(w, h, "", emptyList())

    private fun renderFrame(chart: ChartView, t: Double, w: Int = 420, h: Int = 640): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        chart.drawFrameForVideo(Canvas(bmp), w.toFloat(), h.toFloat(), 1f, t)
        return bmp
    }

    private fun rows(bmp: Bitmap): Pair<Int, Int> = Pair(guideRow(bmp, true), guideRow(bmp, false))

    @JvmStatic
    fun main(args: Array<String>) {
        println("══ آزمون خروجیِ محدودهٔ دستی (PNG + MP4) — ۸ سناریو ══")
        val ctx = Context()
        val rep = makeReport()
        val autoHigh = rep.highest?.f0
        val autoLow = rep.lowest?.f0
        check(autoHigh != null && autoLow != null, "گزارش مصنوعی محدودهٔ خودکار دارد",
            "auto=[${autoLow?.let { String.format("%.1f", it) }}..${autoHigh?.let { String.format("%.1f", it) }}]Hz")
        if (autoHigh == null || autoLow == null) return

        // ── T1 تصویر (PNG) با هر دو مقدار دستی — موقعیت‌های پیش‌بینی‌شده ──────
        println("\n▸ T1 تصویر PNG با هر دو مقدار دستی")
        val chart = ChartView(ctx)
        chart.width = 420; chart.height = 640
        chart.setReport(rep)

        // رندرِ مرجع (کالیبراسیون): هر دو دستی — G♯4 و B3 (داخل پنجرهٔ نمایش
        // تا مقیاسِ محور بین همهٔ رندرها ثابت بماند)
        val hH0 = 415.30 // G♯4
        val hL0 = 246.94 // B3
        val mH0 = ir.hamyareqari.dsp.PitchMath.hzToMidi(hH0)
        val mL0 = ir.hamyareqari.dsp.PitchMath.hzToMidi(hL0)
        chart.setRangeOverride(hH0, hL0)
        val (yH0, yL0) = rows(renderPng(chart))
        check(yH0 > 0 && yL0 > 0 && yH0 < yL0, "هر دو خط در رندرِ مرجع پیدا شدند", "high=$yH0 low=$yL0")
        if (yH0 <= 0 || yL0 <= 0) return
        val slope = (yH0 - yL0) / (mH0 - mL0) // پیکسل به ازای هر نیم‌پرده

        // رندرِ آزمون: F4 و D4 — محور ثابت است، پس جای خطوط قابل پیش‌بینی است
        val hH1 = 349.23 // F4
        val hL1 = 293.66 // D4
        val mH1 = ir.hamyareqari.dsp.PitchMath.hzToMidi(hH1)
        val mL1 = ir.hamyareqari.dsp.PitchMath.hzToMidi(hL1)
        chart.setRangeOverride(hH1, hL1)
        val bmpB = renderPng(chart)
        val (yH1, yL1) = rows(bmpB)
        val expH = yL0 + (mH1 - mL0) * slope
        val expL = yL0 + (mL1 - mL0) * slope
        check(yH1 > 0 && abs(yH1 - expH) <= 2.0,
            "خطِ بالا در موقعیتِ پیش‌بینی‌شده", "y=$yH1 expected≈$expH")
        check(yL1 > 0 && abs(yL1 - expL) <= 2.0,
            "خطِ پایین در موقعیتِ پیش‌بینی‌شده", "y=$yL1 expected≈$expL")

        // ── T2 فریم ویدیو با هر دو مقدار دستی ─────────────────────────────
        println("\n▸ T2 فریم‌های ویدیو (drawFrameForVideo)")
        for (t in doubleArrayOf(0.3, 1.0, 1.8)) {
            val (yHv, yLv) = rows(renderFrame(chart, t))
            check(abs(yHv - expH) <= 2.0 && abs(yLv - expL) <= 2.0,
                "فریم t=$t خطوط را در موقعیتِ دستی نشان می‌دهد", "high=$yHv low=$yLv")
        }

        // ── T3 رندررِ جداگانهٔ ویدیو (جریان واقعی اپ) ─────────────────────
        println("\n▸ T3 رندررِ جداگانهٔ ویدیو + انتقال عکس لحظه‌ای")
        // عکس لحظه‌ای در لحظهٔ درخواست خروجی (مثل ترد رابط کاربری)
        val snap = RangeMath.effective(hH1, hL1, autoHigh, autoLow)
        check(snap != null && snap.manualHigh && snap.manualLow, "عکس لحظه‌ای هر دو مقدار دستی را دارد")
        val renderChart = ChartView(ctx).apply {
            setReport(rep)
            setPlayhead(0.0)
            setRangeOverride(hH1, hL1) // انتقالِ صریح به رندررِ ویدیو
        }
        val (yHr, yLr) = rows(renderFrame(renderChart, 1.0))
        check(abs(yHr - expH) <= 2.0 && abs(yLr - expL) <= 2.0,
            "ویدیوِ رندررِ جداگانه = موقعیت‌های دستی", "high=$yHr low=$yLr")

        // گارد: بدون انتقال، مقادیر خودکار رندر می‌شوند (همان باگِ قبلی)
        val bareChart = ChartView(ctx).apply { setReport(rep); setPlayhead(0.0) }
        val (yHb, yLb) = rows(renderFrame(bareChart, 1.0))
        check(!(abs(yHb - expH) <= 2.0 && abs(yLb - expL) <= 2.0),
            "بدون setRangeOverride مقادیر دستی رندر نمی‌شوند (گارد تست)", "high=$yHb low=$yLb")

        // ── T4 فقط بالا دستی ─────────────────────────────────────────────
        println("\n▸ T4 فقط خطِ بالا دستی")
        val chart4 = ChartView(ctx)
        chart4.width = 420; chart4.height = 640
        chart4.setReport(rep)
        chart4.setRangeOverride(349.23, 246.94) // ابتدا مقدار کهنهٔ پایین!
        chart4.setRangeOverride(349.23, null) // پایین = خودکار (مقدار کهنه نباید بماند)
        val (yH4, yL4) = rows(renderPng(chart4))
        // مرجع: همان بالای دستی + پایین = مقدارِ خودکارِ واقعی (۲۲۰)
        val ref4 = ChartView(ctx)
        ref4.width = 420; ref4.height = 640
        ref4.setReport(rep)
        ref4.setRangeOverride(349.23, autoLow)
        val (yHr4, yLr4) = rows(renderPng(ref4))
        check(abs(yH4 - yHr4) <= 1 && abs(yL4 - yLr4) <= 1,
            "بالا دستی + پایین خودکار = مرجع (بدون مقدار کهنه)", "high=$yH4 low=$yL4 vs ref high=$yHr4 low=$yLr4")

        // ── T5 فقط پایین دستی ────────────────────────────────────────────
        println("\n▸ T5 فقط خطِ پایین دستی")
        val chart5 = ChartView(ctx)
        chart5.width = 420; chart5.height = 640
        chart5.setReport(rep)
        chart5.setRangeOverride(415.30, 293.66) // ابتدا مقدار کهنهٔ بالا!
        chart5.setRangeOverride(null, 293.66) // بالا = خودکار
        val (yH5, yL5) = rows(renderPng(chart5))
        val ref5 = ChartView(ctx)
        ref5.width = 420; ref5.height = 640
        ref5.setReport(rep)
        ref5.setRangeOverride(autoHigh, 293.66)
        val (yHr5, yLr5) = rows(renderPng(ref5))
        check(abs(yH5 - yHr5) <= 1 && abs(yL5 - yLr5) <= 1,
            "پایین دستی + بالا خودکار = مرجع (بدون مقدار کهنه)", "high=$yH5 low=$yL5 vs ref high=$yHr5 low=$yLr5")

        // ── T6 بازگشت به خودکار (هر دو null) ─────────────────────────────
        println("\n▸ T6 بازگشت به حالت خودکار")
        chart.setRangeOverride(null, null)
        val (yH6, yL6) = rows(renderPng(chart))
        val freshAuto = ChartView(ctx)
        freshAuto.width = 420; freshAuto.height = 640
        freshAuto.setReport(rep)
        val (yHa, yLa) = rows(renderPng(freshAuto))
        check(abs(yH6 - yHa) <= 1 && abs(yL6 - yLa) <= 1,
            "PNG پس از ریست = محدودهٔ خودکار", "high=$yH6 low=$yL6 vs auto high=$yHa low=$yLa")
        val (yH6v, yL6v) = rows(renderFrame(chart, 1.0))
        check(abs(yH6v - yHa) <= 1 && abs(yL6v - yLa) <= 1,
            "فریم ویدیو پس از ریست = محدودهٔ خودکار", "high=$yH6v low=$yL6v")

        // ── T7 سازگاری متادیتا (جفت مؤثر + سربرگ) ─────────────────────────
        println("\n▸ T7 سازگاری متادیتا (سربرگ = نمودار)")
        val effBoth = RangeMath.effective(349.23, 293.66, autoHigh, autoLow)
        check(effBoth != null && effBoth.manualHigh && effBoth.manualLow &&
            effBoth.highHz == 349.23 && effBoth.lowHz == 293.66,
            "هر دو دستی: جفت مؤثر = مقادیر دستی", "$effBoth")
        val effHighOnly = RangeMath.effective(349.23, null, autoHigh, autoLow)
        check(effHighOnly != null && effHighOnly.manualHigh && !effHighOnly.manualLow &&
            effHighOnly.highHz == 349.23 && effHighOnly.lowHz == autoLow,
            "فقط بالا: بالا دستی + پایین خودکار (بدون مقدار کهنه)", "$effHighOnly")
        val effLowOnly = RangeMath.effective(null, 293.66, autoHigh, autoLow)
        check(effLowOnly != null && !effLowOnly.manualHigh && effLowOnly.manualLow &&
            effLowOnly.highHz == autoHigh && effLowOnly.lowHz == 293.66,
            "فقط پایین: پایین دستی + بالا خودکار", "$effLowOnly")
        val effNone = RangeMath.effective(null, null, autoHigh, autoLow)
        check(effNone != null && !effNone.manualHigh && !effNone.manualLow &&
            effNone.highHz == autoHigh && effNone.lowHz == autoLow,
            "بدون دستی: هر دو خودکار", "$effNone")
        // اعتبارسنجی: هرگز مقدار ساختگی ساخته نمی‌شود
        check(RangeMath.effective(220.0, 440.0, autoHigh, autoLow) == null,
            "بالا ≤ پایین → null (بدون مقدار ساختگی)")
        check(RangeMath.effective(null, null, null, 220.0) == null,
            "نبودنِ مقدار خودکارِ بالا → null")
        check(RangeMath.effective(-5.0, 220.0, 880.0, 220.0)?.manualHigh == false,
            "مقدار دستیِ نامعتبر نادیده گرفته می‌شود (بازگشت به خودکار)")
        // سربرگ از همان جفت مؤثر ساخته می‌شود — برای هر دو حالتِ دستی/تک‌خطی
        val headBoth = RangeMath.headerLines(effBoth, 2, 2.0)
        check(headBoth[1].contains(PitchMathFa(effBoth!!.span.spanSemitones)) &&
            headBoth[2].contains(effBoth.span.centerName),
            "سربرگ (هر دو دستی): گستره و مرکز از جفتِ مؤثر", headBoth[1])
        val headHighOnly = RangeMath.headerLines(effHighOnly, 2, 2.0)
        val spanHighOnly = RangeMath.span(effHighOnly!!.highHz, effHighOnly.lowHz)
        check(headHighOnly[1].contains(PitchMathFa(spanHighOnly.spanSemitones)) &&
            headHighOnly[2].contains(spanHighOnly.centerName),
            "سربرگ (فقط بالا): گستره و مرکز از جفتِ مؤثرِ ترکیبی", headHighOnly[1])

        // ── T8 بدون تغییر دستی = رفتار خودکار ─────────────────────────────
        println("\n▸ T8 بدون تغییر دستی (رفتار خودکار)")
        val chart8 = ChartView(ctx)
        chart8.width = 420; chart8.height = 640
        chart8.setReport(rep)
        chart8.setRangeOverride(null, null)
        check(sameRaster(renderPng(chart8), renderPng(freshAuto)),
            "setRangeOverride(null,null) = نمودارِ بدون override (پیکسل‌به‌پیکسل)")
        check(sameRaster(renderFrame(chart8, 1.0), renderFrame(freshAuto, 1.0)),
            "فریم ویدیوی بدون دستی = نمودارِ بدون override (پیکسل‌به‌پیکسل)")
        val headAuto = RangeMath.headerLines(RangeMath.effective(null, null, autoHigh, autoLow), 2, 2.0)
        check(headAuto[0].contains(ir.hamyareqari.dsp.PitchMath.noteName(autoHigh)) &&
            headAuto[0].contains(ir.hamyareqari.dsp.PitchMath.noteName(autoLow)),
            "سربرگِ بدون دستی = مقادیر خودکار", headAuto[0])

        println("\n──────── نتیجه: $failures شکست ────────")
        if (failures > 0) { println("FAILED"); System.exit(1) } else println("ALL GREEN")
    }

    /** faFixed با همان قالبِ سربرگ (بدون وابستگی به نام‌های داخلی) */
    private fun PitchMathFa(v: Double): String = ir.hamyareqari.dsp.PitchMath.faFixed(v, 1)
}
