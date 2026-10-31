package ir.hamyareqari.dsp

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * محاسبات گسترهٔ صوتی از دو مقدار بالا/پایین (Hz واقعی — بدون گرد/کوانتیزه).
 *
 * یک منبع واحد برای هر دو مصرف‌کننده:
 *   • نمایش زندهٔ UI هنگام تغییر دستی خط‌چین‌های بالاترین/پایین‌ترین
 *   • خروجی JSON (وقتی محدودهٔ دستی اعمال شده)
 * فرمول‌ها همان‌هایی است که [Analyzer] برای مقادیر خودکار استفاده می‌کند.
 */
object RangeMath {

    class Span(
        val spanSemitones: Double,
        val spanOctaves: Double,
        val spanCents: Double,
        val fullOctaves: Int,
        val restSemitones: Double,
        val quarterTones: Double,
        val ofOctavePct: Double,
        val centerHz: Double
    ) {
        val centerName: String get() = PitchMath.noteName(centerHz)
    }

    /** اگر high≤low باشد، جا به‌جا محاسبه می‌شود (ورودی دستی نباید خروجی را خراب کند) */
    fun span(highHz: Double, lowHz: Double): Span {
        val hi = maxOf(highHz, lowHz)
        val lo = minOf(highHz, lowHz)
        val spanSemis = if (hi > 0 && lo > 0) PitchMath.cents(hi, lo) / 100.0 else 0.0
        val spanOct = spanSemis / 12.0
        val fullOct = floor(spanOct + 1e-9).toInt()
        var rest = spanSemis - fullOct * 12.0
        if (rest >= 12.0 - 1e-9) rest = 0.0
        val centerHz = if (hi > 0 && lo > 0) sqrt(hi * lo) else 0.0
        return Span(
            spanSemitones = spanSemis,
            spanOctaves = spanOct,
            spanCents = spanSemis * 100.0,
            fullOctaves = fullOct,
            restSemitones = rest,
            quarterTones = spanSemis * 2.0,
            ofOctavePct = spanSemis / 12.0 * 100.0,
            centerHz = centerHz
        )
    }
}
