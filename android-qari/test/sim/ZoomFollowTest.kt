import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import ir.hamyareqari.app.ChartView
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.Wav
import java.io.File
import kotlin.math.abs

/**
 * تستِ «زوم قفل نمی‌کند»: در حالت زوم، نقطهٔ طلایی باید همراه صوت جلو برود
 * و همیشه در همان جای ثابتِ کادر (کمی جلوتر از مرکز) بماند.
 *
 * روش: پیکسلِ روشن‌ترین ستون را پیدا می‌کنیم (نقطهٔ نور + خط پخش) و با
 * موقعیتِ انتظاری از فرمولِ پنجرهٔ دید مقایسه می‌کنیم.
 */
object ZoomFollowTest {

    private var failures = 0

    private fun check(ok: Boolean, label: String, extra: String = "") {
        if (ok) println("  ✅ $label${if (extra.isEmpty()) "" else " — $extra"}")
        else { failures++; println("  ❌ $label${if (extra.isEmpty()) "" else " — $extra"}") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val ctx = Context()
        val path = System.getProperty("demo.wav", "../assets/demo.wav")
        val wav = Wav.decode(File(path).readBytes())!!
        val res = Wav.resample(wav.samples, wav.sampleRate, 22050)
        val pcm = ShortArray(res.size) { i -> (res[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, 22050)
        val r = Analyzer.analyzeSignal(ShortSignal(pcm, 22050))

        val w = 960
        val h = 420
        val total = r.durationSec
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val px = IntArray(w * h)

        for (zoom in doubleArrayOf(1.0, 4.0, 12.0)) {
            val chart = ChartView(ctx).apply { setReport(r) }
            if (zoom > 1) chart.zoomTime(zoom)
            println("\n▸ زوم ×${zoom.toInt()}")
            var worst = 0.0
            var t = 3.0
            val samples = ArrayList<String>()
            while (t < total - 1.0 && samples.size < 8) {
                chart.setPlayhead(t)
                chart.drawTrace(c, w.toFloat(), h.toFloat(), t)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                // روشن‌ترین ستون
                var bestCol = -1
                var bestVal = -1
                for (x in 0 until w) {
                    var sum = 0
                    for (y in 0 until h) {
                        val p = px[y * w + x]
                        val rr = (p shr 16) and 0xFF
                        val gg = (p shr 8) and 0xFF
                        sum += (rr + gg) / 2
                    }
                    if (sum > bestVal) { bestVal = sum; bestCol = x }
                }
                // انتظار: همان فرمولِ پنجرهٔ دیدِ نمودار
                val win = total / zoom
                val center = if (zoom <= 1.001) total / 2 else t + win * 0.12
                val a = (center - win / 2).coerceIn(0.0, maxOf(0.0, total - win))
                val ml = Math.round(w * 0.0335f).toFloat()
                val mr = Math.round(w * 0.0215f).toFloat()
                val width = (w - ml - mr).toDouble()
                val expected = if (zoom <= 1.001) ml + (t / total) * width
                else ml + (((t - a) / win) * width)
                val diff = abs(bestCol - expected)
                if (diff > worst) worst = diff
                samples.add("t=${"%.1f".format(t)}→x=$bestCol")
                t += 4.0
            }
            check(worst <= 4.0, "نقطهٔ نور با زمان جلو می‌رود (خطای پیکسلی)", "بیشترین خطا ${"%.1f".format(worst)}px")
            if (samples.isNotEmpty()) println("     ${samples.joinToString(" · ")}")
        }

        // سرعت: ۳۰۰ فریم در زوم ×۶
        val chart = ChartView(ctx).apply { setReport(r); zoomTime(6.0) }
        for (i in 0 until 20) { chart.setPlayhead(4.0 + i / 60.0); chart.drawTrace(c, w.toFloat(), h.toFloat(), 4.0 + i / 60.0) }
        val t0 = System.nanoTime()
        for (i in 0 until 300) {
            val t = 4.0 + i / 60.0
            chart.setPlayhead(t)
            chart.drawTrace(c, w.toFloat(), h.toFloat(), t)
        }
        val ms = (System.nanoTime() - t0) / 1e6 / 300
        println("\n▸ میانگین رسم هر فریم در زوم ×۶: ${"%.2f".format(ms)}ms (بودجه ۱۶٫۶ms)")
        check(ms < 60.0, "رسم هر فریم سبک است (بدون قفلِ رابط)", "${"%.2f".format(ms)}ms")

        println("\n" + if (failures == 0) "🎉 تستِ زوم موفق" else "❗ $failures تست ناموفق")
        if (failures > 0) System.exit(1)
    }
}
