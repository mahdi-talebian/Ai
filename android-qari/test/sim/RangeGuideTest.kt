import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import ir.hamyareqari.app.ChartView
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.ShortSignal
import kotlin.math.abs

/**
 * تست رگرسیون: مقادیر دستیِ خط‌چین‌های محدوده (درگ) باید
 *   ۱) دقیقاً زیر انگشت بمانند (نه اینکه با باز-اندازه‌گیریِ مقیاس Y عقب بمانند)
 *   ۲) در خروجی‌های رندرشده — تصویر (renderToBitmap) و فریم/ویدیو
 *      (drawFrameForVideo) — دیده شوند، نه فقط روی صفحه
 *   ۳) در جریانِ ساختِ ویدیوی اپ (نمودارِ تازه + انتقالِ محدوده) حفظ شوند
 *
 * روش: رندر واقعی روی BufferedImage (استاب‌های گرافیکی AWT) + شبیه‌سازیِ
 * کاملِ رویدادهای لمسی (DOWN/MOVE/UP) + پیداکردن ردیفِ خط‌چینِ بالا
 * (تنها خطِ تمام‌عرضِ رنگیِ کم‌رنگ؛ نوارِ نتِ کوتاهِ بالایی فقط ~۱۵٪ عرض
 * همان ردیف را می‌پوشاند و مزاحم تشخیص نیست).
 */
object RangeGuideTest {

    private var failures = 0

    private fun check(ok: Boolean, label: String, extra: String = "") {
        if (ok) println("  ✅ $label${if (extra.isEmpty()) "" else " — $extra"}")
        else { failures++; println("  ❌ $label${if (extra.isEmpty()) "" else " — $extra"}") }
    }

    /**
     * گزارش مصنوعی: نتِ ۲۲۰ هرتزِ بلند (۰ تا ۱٫۲ ثانیه) + نتِ ۴۴۰ هرتزِ کوتاه
     * (۱٫۷ تا ۲٫۰ ثانیه). نوارِ نتِ کوتاه، ردیفِ خطِ راهنما را کم‌تر می‌پوشاند.
     */
    private fun makeReport(): Report {
        val sr = 22050
        val n = sr * 2
        val x = DoubleArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = when {
                t < 1.2 -> 220.0
                t >= 1.7 && t < 2.0 -> 440.0
                else -> 0.0
            }
            x[i] = if (f > 0) 0.5 * kotlin.math.sin(2.0 * Math.PI * f * t) else 0.0
        }
        val pcm = ShortArray(n) { i -> (x[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, sr)
        return Analyzer.analyzeSignal(ShortSignal(pcm, sr))
    }

    /**
     * ردیفِ خطِ راهنمای بالا: خطِ راهنما تنها خطِ «تمام‌عرضِ» گرم/طلایی‌کم‌رنگ
     * است. پیکسلِ خط بسته به پوشش زیرپیکسلی بین (147,136,111) [پوشش کامل]
     * تا (88,89,93) [نصف‌پیکسل] تغییر می‌کند؛ نوارِ نتِ طلاییِ روشن (r>215) و
     * خطوط شبکهٔ خاکستریِ تیره (r<75) و خطِ پایینِ آبی (r<b) کنار می‌روند.
     */
    private fun highGuideRow(bmp: Bitmap): Int {
        val w = bmp.width
        val h = bmp.height
        var best = -1
        var bestCount = 0
        for (y in 0 until h) {
            var c = 0
            for (x in 15 until w - 15) {
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (r in 75..215 && r >= b - 8 && g >= b - 25) c++
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

    private fun renderBmp(chart: ChartView, w: Int = 420, h: Int = 640): Bitmap =
        chart.renderToBitmap(w, h, "", emptyList())

    private fun ev(action: Int, x: Float, y: Float): MotionEvent = MotionEvent().also {
        it.action = action
        it.actionMasked = action
        it.x = x
        it.y = y
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("══ آزمون خروجیِ خط‌چین‌های دستی (تصویر + ویدیو) ══")
        val ctx = Context()
        val rep = makeReport()
        val w = 420
        val h = 640

        // ── G1 درگ → خط دقیقاً زیر انگشت → تصویر خروجی ───────────────────
        println("\n▸ G1 درگ (onTouchEvent) → renderToBitmap")
        val chart = ChartView(ctx)
        chart.width = w
        chart.height = h
        chart.setReport(rep)
        chart.rangesDraggable = true
        var dragCb: Pair<Boolean, Double>? = null
        chart.onRangeDrag = { isHigh, hz -> dragCb = Pair(isHigh, hz) }

        // نقطهٔ شروع داخل پنجرهٔ دید (G♯4 ≈ 415.3 هرتز) تا مقیاس Y ثابت بماند
        chart.setRangeOverride(415.30, 220.0)
        val y0 = highGuideRow(renderBmp(chart, w, h))
        check(y0 > 0, "خط‌چینِ بالا در رندرِ اولیه پیدا شد", "y=$y0")

        // درگ واقعی: DOWN روی خط، MOVE به ۴۰ پیکسل پایین‌تر، UP
        chart.onTouchEvent(ev(MotionEvent.ACTION_DOWN, 200f, y0.toFloat()))
        chart.onTouchEvent(ev(MotionEvent.ACTION_MOVE, 200f, (y0 + 40).toFloat()))
        chart.onTouchEvent(ev(MotionEvent.ACTION_UP, 200f, (y0 + 40).toFloat()))
        check(dragCb != null && dragCb!!.first, "فراخوانی onRangeDrag با isHigh=true", "$dragCb")

        val y1 = highGuideRow(renderBmp(chart, w, h))
        check(y1 > 0 && abs(y1 - (y0 + 40)) <= 2,
            "خط در تصویرِ خروجی دقیقاً انگشت را دنبال می‌کند", "y0=$y0 y1=$y1 expected=${y0 + 40}")

        val hzDragged = dragCb?.second
        check(hzDragged != null && hzDragged < 415.30 && hzDragged > 220.0,
            "هرتزِ درگ‌شده معقول است (پایین‌تر از نقطهٔ شروع)", "$hzDragged")

        // ── G2 فریم ویدیو با وضعیتِ درگ‌شده ───────────────────────────────
        println("\n▸ G2 فریم ویدیو (drawFrameForVideo)")
        val bmpV = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        chart.drawFrameForVideo(Canvas(bmpV), w.toFloat(), h.toFloat(), 1f, 1.0)
        val yV = highGuideRow(bmpV)
        check(yV == y1, "موقعیت خط در فریم ویدیو = تصویر خروجی", "yV=$yV yImg=$y1")

        // ── G3 جریان ساخت ویدیوی اپ: نمودارِ تازه + انتقال محدودهٔ دستی ────
        println("\n▸ G3 جریان ساخت ویدیوی اپ (نمودارِ تازه)")
        val renderChart = ChartView(ctx).apply {
            setReport(rep)
            setPlayhead(0.0)
            setRangeOverride(hzDragged, 220.0) // انتقالِ صریحِ مقادیر دستی (رفع باگ ویدیو)
        }
        val yR = highGuideRow(renderBmp(renderChart, w, h))
        check(yR == y1, "ویدیو با محدودهٔ دستی = نمودارِ درگ‌شده (رگرسیونِ ویدیو)", "yR=$yR y1=$y1")

        val bare = ChartView(ctx).apply { setReport(rep); setPlayhead(0.0) }
        val yBare = highGuideRow(renderBmp(bare, w, h))
        check(yBare > 0 && yBare != y1, "بدون انتقالِ محدوده، خط دستی نمی‌شود (گارد تست)",
            "yBare=$yBare y1=$y1")

        // ── G4 قطعیت و حساسیت رندر ───────────────────────────────────────
        println("\n▸ G4 قطعیت/حساسیت رندر")
        chart.setRangeOverride(440.0, 220.0)
        val rA = renderBmp(chart, w, h)
        chart.setRangeOverride(329.63, 220.0) // E4 — تغییر محسوس
        val rB = renderBmp(chart, w, h)
        check(!sameRaster(rA, rB), "تغییر override رندر را تغییر می‌دهد (حساسیت)")

        chart.setRangeOverride(440.0, 220.0)
        val rA2 = renderBmp(chart, w, h)
        check(sameRaster(rA, rA2), "همان ورودی → همان رندر (قطعیت)")

        chart.setRangeOverride(null, null)
        val rAuto = renderBmp(chart, w, h)
        check(!sameRaster(rA, rAuto), "حذف override رندر را تغییر می‌دهد (کنترل)")

        println("\n──────── نتیجه: $failures شکست ────────")
        if (failures > 0) { println("FAILED"); System.exit(1) } else println("ALL GREEN")
    }
}
