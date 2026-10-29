package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * کاهش نویز پس‌زمینه با «دروازهٔ طیفی» (spectral gate).
 *
 * چرا این روش؟ ساده، بی‌پارامتر برای کاربر، و بدون آرتیفکت شدید:
 *   • STFT با پنجرهٔ ۱۰۲۴ و گام ۲۵۶
 *   • برای هر بین، سطح نویز = صدکِ ۱۵٪ دامنه در طول فایل
 *     (نویز پس‌زمینه همیشه حاضر است، پس کفِ توزیع همان نویز است)
 *   • هر بین زیر «سطح نویز × ۱٫۸» با فاکتور نرم کم می‌شود، نه صفر
 *   • بازسازی با هم‌جمعی وزنی (overlap-add)
 *
 * نتیجه: هیس و هم اتاق کم می‌شود ولی هارمونیک‌های صدا دست‌نخورده می‌مانند،
 * چون هارمونیک‌ها همیشه بالای کف نویزند.
 */
object Denoise {

    private const val N = 1024
    private const val HOP = 256

    fun process(x: DoubleArray, sampleRate: Int): DoubleArray {
        if (x.size < N * 4) return x
        val win = hann(N)
        val nfft = N
        val fft = Fft(nfft)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        val frames = (x.size - N) / HOP + 1
        if (frames < 4) return x
        val bins = nfft / 2 + 1

        // ── مرحلهٔ ۱: تخمین کف نویز برای هر بین (روی نمونه‌های پراکنده) ──
        val step = max(1, frames / 220)
        val samples = ArrayList<DoubleArray>()
        var f = 0
        while (f < frames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until N) re[i] = x[off + i] * win[i]
            fft.forward(re, im)
            val mag = DoubleArray(bins)
            for (k in 0 until bins) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            samples.add(mag)
            f += step
        }
        if (samples.size < 3) return x
        val noise = DoubleArray(bins)
        val col = DoubleArray(samples.size)
        for (k in 0 until bins) {
            for (s in samples.indices) col[s] = samples[s][k]
            noise[k] = PitchMath.percentile(col, 15.0)
        }

        // ── مرحلهٔ ۲: اعمال دروازه و بازسازی ──
        val out = DoubleArray(x.size)
        val norm = DoubleArray(x.size)
        val gate = DoubleArray(bins)
        val factor = 0.35   // کف عبور: نویز حذف نمی‌شود، فقط ۹ دسی‌بل کم می‌شود
        f = 0
        while (f < frames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until N) re[i] = x[off + i] * win[i]
            fft.forward(re, im)
            for (k in 0 until bins) {
                val mag = sqrt(re[k] * re[k] + im[k] * im[k])
                val floorK = noise[k] * 1.8
                gate[k] = when {
                    mag <= floorK -> factor
                    mag > floorK * 2.2 -> 1.0
                    else -> {
                        // گذار نرم تا «صدای زیرآبی» ایجاد نشود
                        val t = (mag - floorK) / (floorK * 1.2)
                        factor + (1.0 - factor) * t
                    }
                }
                re[k] *= gate[k]
                im[k] *= gate[k]
                if (k > 0 && k < nfft / 2) {
                    re[nfft - k] *= gate[k]
                    im[nfft - k] *= gate[k]
                }
            }
            fft.inverse(re, im)
            for (i in 0 until N) {
                val idx = off + i
                if (idx >= out.size) break
                out[idx] += re[i] * win[i]
                norm[idx] += win[i] * win[i]
            }
            f += 1
        }
        for (i in out.indices) {
            if (norm[i] > 1e-9) out[i] /= norm[i]
        }
        return out
    }

    /** نسبت سیگنال به نویز تقریبی (دسی‌بل) برای نمایش به کاربر */
    fun estimatedNoiseDb(x: DoubleArray, sampleRate: Int): Double {
        if (x.size < N * 2) return 60.0
        val win = hann(N)
        val fft = Fft(N)
        val re = DoubleArray(N)
        val im = DoubleArray(N)
        var energy = 0.0
        var count = 0
        var f = 0
        val frames = (x.size - N) / HOP + 1
        while (f < frames) {
            val off = f * HOP
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            for (i in 0 until N) re[i] = x[off + i] * win[i]
            fft.forward(re, im)
            for (k in 1 until N / 2) energy += re[k] * re[k] + im[k] * im[k]
            count++
            f += max(1, frames / 120)
        }
        if (count == 0) return 60.0
        val mean = energy / count
        return max(0.0, 10.0 * (ln(max(mean, 1e-12)) / ln(10.0)))
    }

    fun dbToGain(db: Double): Double = Math.pow(10.0, db / 20.0)

    /** میانگین سادهٔ قدرمطلق (برای سنجش سکوت) */
    fun meanAbs(x: DoubleArray): Double {
        if (x.isEmpty()) return 0.0
        var s = 0.0
        for (v in x) s += abs(v)
        return s / x.size
    }

    /** شمارهٔ فریم‌های موجود */
    fun frameCount(sampleCount: Int): Int = if (sampleCount < N) 0 else (sampleCount - N) / HOP + 1

    fun floorDiv(a: Int, b: Int): Int = floor(a.toDouble() / b).toInt()

    fun smallWindow(): DoubleArray = hann(min(N, 256))

    fun clampGain(g: Double): Double = min(1.0, max(0.0, g))

    fun cosineTaper(i: Int, n: Int): Double = 0.5 - 0.5 * cos(Math.PI * i / max(1, n))
}
