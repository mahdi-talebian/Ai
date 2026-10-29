package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * ریاضیات نت: فرکانس ↔ نامِ نت، سنت، اکتاو، و ارقام فارسی.
 * این فایل هیچ وابستگی‌ای به اندروید ندارد و روی JVM هم تست می‌شود.
 */
object PitchMath {

    private val LATIN = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val SOLFEGE = arrayOf("دو", "دو♯", "ره", "ره♯", "می", "فا", "فا♯", "سل", "سل♯", "لا", "لا♯", "سی")

    /** A4 = 440Hz، میانهٔ پیانو */
    const val A4_HZ = 440.0
    const val A4_MIDI = 69.0

    fun hzToMidi(f: Double): Double {
        if (!(f > 0.0) || !f.isFinite()) return Double.NaN
        return A4_MIDI + 12.0 * log2(f / A4_HZ)
    }

    fun midiToHz(m: Double): Double = A4_HZ * 2.0.pow((m - A4_MIDI) / 12.0)

    /** اختلاف دو فرکانس بر حسب سنت (مثبت = a بالاتر از b) */
    fun cents(a: Double, b: Double): Double {
        if (!(a > 0.0) || !(b > 0.0)) return Double.NaN
        return 1200.0 * log2(a / b)
    }

    fun nearestMidi(f: Double): Int {
        val m = hzToMidi(f)
        if (m.isNaN()) return -1
        return m.roundToInt()
    }

    /** نام لاتین با شمارهٔ اکتاو: A4، C#3 … */
    fun latinName(midi: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val oct = floor(midi / 12.0).toInt() - 1
        return LATIN[pc] + faDigits(oct.toString())
    }

    /** نام لاتین با رقم لاتین (برای خروجی JSON و پایداری داده) */
    fun latinNameAscii(midi: Int): String {
        val pc = ((midi % 12) + 12) % 12
        val oct = floor(midi / 12.0).toInt() - 1
        return LATIN[pc] + oct.toString()
    }

    fun noteName(f: Double): String {
        val m = nearestMidi(f)
        return if (m < 0) "—" else latinName(m)
    }

    fun noteNameAscii(f: Double): String {
        val m = nearestMidi(f)
        return if (m < 0) "" else latinNameAscii(m)
    }

    fun solfege(f: Double): String {
        val m = nearestMidi(f)
        if (m < 0) return "—"
        val pc = ((m % 12) + 12) % 12
        return SOLFEGE[pc] + faDigits((floor(m / 12.0).toInt() - 1).toString())
    }

    /** انحراف از نزدیک‌ترین نیم‌پرده بر حسب سنت (−۵۰ تا +۵۰) */
    fun centsOffPitch(f: Double): Double {
        val m = hzToMidi(f)
        if (m.isNaN()) return Double.NaN
        return (m - m.roundToInt()) * 100.0
    }

    private val FA = charArrayOf('۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹')

    fun faDigits(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') FA[c - '0'] else c)
        return sb.toString()
    }

    fun faDigits(v: Int): String = faDigits(v.toString())

    fun faDigits(v: Long): String = faDigits(v.toString())

    /** عدد اعشاری با تعداد رقم مشخص، با ارقام فارسی */
    fun faFixed(v: Double, digits: Int = 1): String {
        if (!v.isFinite()) return "—"
        val s = String.format(java.util.Locale.US, "%." + digits + "f", v)
        return faDigits(s)
    }

    fun clamp(v: Double, lo: Double, hi: Double): Double = if (v < lo) lo else if (v > hi) hi else v

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val c = values.copyOf()
        c.sort()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val c = values.sorted()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    fun mean(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        var s = 0.0
        for (v in values) s += v
        return s / values.size
    }

    fun percentile(values: DoubleArray, p: Double): Double {
        if (values.isEmpty()) return Double.NaN
        val c = values.copyOf()
        c.sort()
        if (c.size == 1) return c[0]
        val pos = clamp(p, 0.0, 100.0) / 100.0 * (c.size - 1)
        val lo = floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt().coerceAtMost(c.size - 1)
        val frac = pos - lo
        return c[lo] * (1.0 - frac) + c[hi] * frac
    }

    fun db(v: Double): Double = 20.0 * ln(max(v, 1e-12)) / ln(10.0)

    fun absDiff(a: Double, b: Double): Double = abs(a - b)
}
