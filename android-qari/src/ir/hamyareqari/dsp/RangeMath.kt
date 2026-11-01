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

    /**
     * جفتِ مؤثرِ محدوده: هر کدام از بالا/پایین مستقل انتخاب می‌شوند —
     * مقدار دستی اگر معتبر باشد، وگرنه مقدار خودکارِ موتور.
     * اگر جفتِ نهایی معتبر نباشد (نبودن/نامعتبربودن/کوچک‌تر بودنِ بالا از پایین)
     * برمی‌گردد null — هرگز مقدار ساختگی ساخته نمی‌شود.
     */
    data class EffectiveRange(
        val highHz: Double,
        val lowHz: Double,
        val manualHigh: Boolean,
        val manualLow: Boolean,
        val span: Span
    )

    fun effective(
        manualHighHz: Double?,
        manualLowHz: Double?,
        autoHighHz: Double?,
        autoLowHz: Double?
    ): EffectiveRange? {
        val mh = manualHighHz?.takeIf { it > 0.0 }
        val ml = manualLowHz?.takeIf { it > 0.0 }
        val hi = mh ?: autoHighHz?.takeIf { it > 0.0 } ?: return null
        val lo = ml ?: autoLowHz?.takeIf { it > 0.0 } ?: return null
        if (hi <= lo) return null
        return EffectiveRange(hi, lo, mh != null, ml != null, span(hi, lo))
    }

    /**
     * سه خطِ سربرگِ خروجیِ PNG از همان جفتِ مؤثری که خط‌چین‌ها و برچسب‌های
     * نمودار را می‌سازند — تا سربرگ و نمودار هرگز از هم جلو نیفتند.
     */
    fun headerLines(eff: EffectiveRange?, noteCount: Int, durationSec: Double): List<String> {
        if (eff == null) return listOf(
            "بالاترین: —   ·   پایین‌ترین: —",
            "گستره: —",
            "مرکز صدا: —"
        )
        return listOf(
            "بالاترین: ${PitchMath.noteName(eff.highHz)}   ·   پایین‌ترین: ${PitchMath.noteName(eff.lowHz)}",
            "گستره: ${PitchMath.faFixed(eff.span.spanSemitones, 1)} نیم‌پرده (${PitchMath.faFixed(eff.span.spanOctaves, 2)} اکتاو)",
            "مرکز صدا: ${eff.span.centerName} · $noteCount نت · ${PitchMath.faFixed(durationSec, 0)} ثانیه"
        )
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
