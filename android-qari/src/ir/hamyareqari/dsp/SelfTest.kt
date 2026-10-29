package ir.hamyareqari.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * آزمون داخلی موتور: یک اجرای مرجع مصنوعی (A3 سپس A4) روی همین گوشی ساخته
 * می‌شود و از همان مسیر تحلیل واقعی می‌گذرد. اگر جواب درست بود، یعنی هستهٔ
 * تحلیل روی این دستگاه سالم است.
 */
object SelfTest {

    class Result(
        val ok: Boolean,
        val title: String,
        val detail: String
    )

    private const val SR = 22050

    private fun tone(f0: Double, seconds: Double, seed: Int): DoubleArray {
        val n = (seconds * SR).toInt()
        val out = DoubleArray(n)
        var phase = 0.0
        var rnd = (seed + 1).toLong() * 6364136223846793005L + 1442695040888963407L
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val cents = 22.0 * sin(2 * PI * 5.2 * t)
            val f = f0 * 2.0.pow(cents / 1200.0)
            phase += 2 * PI * f / SR
            var s = 0.0
            for (h in 1..8) {
                if (f * h > SR / 2.0 * 0.9) break
                s += sin(phase * h) / h.toDouble().pow(1.3)
            }
            rnd = rnd * 6364136223846793005L + 1442695040888963407L
            val noise = ((rnd ushr 40).toDouble() / (1 shl 24).toDouble() - 0.5) * 0.004
            val env = when {
                t < 0.03 -> t / 0.03
                t > seconds - 0.06 -> (seconds - t) / 0.06
                else -> 1.0
            }
            out[i] = (s + noise) * env
        }
        return out
    }

    fun run(): Result {
        val started = System.currentTimeMillis()
        val a3 = tone(220.0, 1.3, 3)
        val gap = DoubleArray((0.15 * SR).toInt())
        val a4 = tone(440.0, 1.3, 5)
        val x = DoubleArray(a3.size + gap.size + a4.size)
        System.arraycopy(a3, 0, x, 0, a3.size)
        System.arraycopy(gap, 0, x, a3.size, gap.size)
        System.arraycopy(a4, 0, x, a3.size + gap.size, a4.size)

        val r = Analyzer.analyze(x, SR, null)
        val ms = System.currentTimeMillis() - started
        val hi = r.highest?.nameAscii ?: "—"
        val lo = r.lowest?.nameAscii ?: "—"
        val span = r.spanSemitones
        val errHi = r.highest?.let { abs(PitchMath.cents(it.f0, 440.0)) } ?: 999.0
        val errLo = r.lowest?.let { abs(PitchMath.cents(it.f0, 220.0)) } ?: 999.0
        val ok = hi == "A4" && lo == "A3" && abs(span - 12.0) < 0.4 && errHi < 12 && errLo < 12
        val detail = "بالاترین $hi · پایین‌ترین $lo · گستره ${PitchMath.faFixed(span, 1)} نیم‌پرده · " +
            "خطای کوک ${PitchMath.faFixed(maxOf(errHi, errLo), 1)} سنت · ${PitchMath.faDigits(ms)} میلی‌ثانیه"
        return Result(
            ok,
            if (ok) "✅ موتور تحلیل سالم است" else "⚠️ نتیجه غیرمنتظره بود",
            detail
        )
    }
}
