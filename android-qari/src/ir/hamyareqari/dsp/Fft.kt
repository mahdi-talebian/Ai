package ir.hamyareqari.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** کوچک‌ترین توانِ دو ≥ x */
fun nextPow2(x: Int): Int {
    var n = 1
    while (n < x) n = n shl 1
    return n
}

/**
 * FFT پایه-۲ درجا (Cooley–Tukey) با جدول‌های آماده.
 * فقط به آرایه‌های Double نیاز دارد؛ بدون هیچ کتابخانهٔ بیرونی.
 */
class Fft(val n: Int) {
    private val levels: Int
    private val cosT: DoubleArray
    private val sinT: DoubleArray
    private val rev: IntArray

    init {
        require(n > 1 && (n and (n - 1)) == 0) { "n باید توان دو باشد" }
        var lv = 0
        while ((1 shl lv) < n) lv++
        levels = lv
        cosT = DoubleArray(n / 2)
        sinT = DoubleArray(n / 2)
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a)
            sinT[i] = sin(a)
        }
        rev = IntArray(n)
        for (i in 0 until n) {
            var x = i
            var r = 0
            for (b in 0 until levels) {
                r = (r shl 1) or (x and 1)
                x = x shr 1
            }
            rev[i] = r
        }
    }

    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, false)

    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, true)
        val s = 1.0 / n
        for (i in 0 until n) {
            re[i] *= s
            im[i] *= s
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var k = 0
                for (i in start until start + half) {
                    val j = i + half
                    val c = cosT[k]
                    val s = if (inverse) -sinT[k] else sinT[k]
                    val tre = re[j] * c - im[j] * s
                    val tim = re[j] * s + im[j] * c
                    re[j] = re[i] - tre
                    im[j] = im[i] - tim
                    re[i] += tre
                    im[i] += tim
                    k += step
                }
                start += size
            }
            size = size shl 1
        }
    }
}

/** پنجرهٔ هنینگ (هَن) دوره‌ای */
fun hann(n: Int): DoubleArray {
    val w = DoubleArray(n)
    for (i in 0 until n) w[i] = 0.5 - 0.5 * cos(2.0 * PI * i / n)
    return w
}

/**
 * دامنهٔ نیم‌طیف |X[k]| برای k = 0..nfft/2.
 * x باید طول nfft یا کمتر باشد؛ صفر-انباشت انجام می‌شود.
 */
fun magSpectrum(x: DoubleArray, nfft: Int, win: DoubleArray?, fft: Fft, re: DoubleArray, im: DoubleArray): DoubleArray {
    java.util.Arrays.fill(re, 0, nfft, 0.0)
    java.util.Arrays.fill(im, 0, nfft, 0.0)
    val m = if (x.size < nfft) x.size else nfft
    for (i in 0 until m) re[i] = if (win != null && i < win.size) x[i] * win[i] else x[i]
    fft.forward(re, im)
    val half = nfft / 2
    val out = DoubleArray(half + 1)
    for (k in 0..half) {
        val a = re[k]
        val b = im[k]
        out[k] = kotlin.math.sqrt(a * a + b * b)
    }
    return out
}

/**
 * درون‌یابی سهمی روی قدر مطلق: موقعیت دقیقِ قله بین دو بین.
 * مقدار برگشتی، اندیس اعشاری قله است.
 */
fun parabolicPeak(mag: DoubleArray, i: Int): Double {
    if (i <= 0 || i >= mag.size - 1) return i.toDouble()
    val a = mag[i - 1]
    val b = mag[i]
    val c = mag[i + 1]
    val den = a - 2.0 * b + c
    if (kotlin.math.abs(den) < 1e-14) return i.toDouble()
    val d = 0.5 * (a - c) / den
    // بیرون از بازهٔ ±۰٫۵ یعنی قله در همسایه است؛ ارزش اعتماد ندارد
    if (d > 0.5 || d < -0.5) return i.toDouble()
    return i + d
}
