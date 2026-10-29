package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** نتیجهٔ یک فریم: فرکانس پایه (صفر = بی‌صدا/ناخوانا)، شفافیت و بلندی */
class FramePitch(var f0: Double = 0.0, var clarity: Double = 0.0, var rms: Double = 0.0) {
    fun reset() { f0 = 0.0; clarity = 0.0; rms = 0.0 }
}

/**
 * ردیابِ فرکانس پایه به روش YIN، ولی تابعِ تفاضل با همبستگیِ FFT محاسبه می‌شود
 * (O(N log N) به‌جای O(N·τ)) — همان چیزی که اجرای روی گوشی را ممکن می‌کند.
 *
 * مرجع روش: de Cheveigné & Kawahara (2002).
 */
class YinTracker(
    private val sampleRate: Int,
    private val fMin: Double = 55.0,
    private val fMax: Double = 1200.0,
    frameSec: Double = 0.055,
    private val threshold: Double = 0.15
) {
    val w: Int = max(64, (frameSec * sampleRate).toInt())
    val tauMin: Int = max(2, floor(sampleRate / fMax).toInt())
    val tauMax: Int = min(w - 2, ceil(sampleRate / fMin).toInt())
    private val l: Int = w - tauMax
    private val nfft: Int = nextPow2(w + tauMax + 2)

    private val fft = Fft(nfft)
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)
    private val d = DoubleArray(tauMax + 2)
    private val cmnd = DoubleArray(tauMax + 2)
    private val prefix = DoubleArray(w + 1)

    /** طول لازم برای تحلیل یک فریم در نقطهٔ start */
    fun framesCount(totalSamples: Int, hop: Int): Int {
        if (totalSamples < w) return 0
        return (totalSamples - w) / hop + 1
    }

    /** بافر کمکی هر فریم */
    fun newFrameBuffer(): DoubleArray = DoubleArray(w)

    fun pitchAt(sig: Signal, start: Int, out: FramePitch, frame: DoubleArray) {
        out.reset()
        if (start < 0 || start + w > sig.size) return
        sig.read(start, w, frame)

        // جمع تجمعی مربع‌ها برای محاسبهٔ سریع انرژی
        prefix[0] = 0.0
        for (i in 0 until w) {
            val v = frame[i]
            prefix[i + 1] = prefix[i] + v * v
        }
        val energy = prefix[w]
        val rms = sqrt(energy / w)
        out.rms = rms
        if (rms < 1e-7) return

        // همبستگی خودکار با FFT
        java.util.Arrays.fill(re, 0, nfft, 0.0)
        java.util.Arrays.fill(im, 0, nfft, 0.0)
        for (i in 0 until w) re[i] = frame[i]
        fft.forward(re, im)
        for (k in 0 until nfft) {
            val a = re[k]
            val b = im[k]
            re[k] = a * a + b * b
            im[k] = 0.0
        }
        fft.inverse(re, im)

        var running = 0.0
        cmnd[0] = 1.0
        d[0] = 0.0
        var tauBest = -1
        var valBest = Double.MAX_VALUE
        val limit = tauMax
        for (tau in 1..limit) {
            // پنجرهٔ لغزانِ کوتاه‌شونده: طول = w − τ (همان تعدادی که همبستگی
            // خودکار می‌دهد)؛ نرمال‌سازی با همین طول انجام می‌شود.
            val len = w - tau
            val sq0 = prefix[len]
            val sqTau = prefix[w] - prefix[tau]
            var dv = (sq0 + sqTau - 2.0 * re[tau]) / len
            if (dv < 0.0) dv = 0.0
            d[tau] = dv
            running += dv
            val c = if (running > 1e-12) dv * tau / running else 1.0
            cmnd[tau] = c
            if (tau >= tauMin && c < valBest) {
                valBest = c
                tauBest = tau
            }
        }
        if (tauBest < 0) return

        // اولین کمینهٔ محلی زیر آستانه (قاعدهٔ YIN) — جلوی خطای زیراکتاو را می‌گیرد
        var chosen = -1
        var tau = tauMin
        while (tau <= limit) {
            if (cmnd[tau] < threshold) {
                while (tau + 1 <= limit && cmnd[tau + 1] < cmnd[tau]) tau++
                chosen = tau
                break
            }
            tau++
        }
        if (chosen < 0) chosen = tauBest

        // درون‌یابی سهمی روی تابع تفاضل برای دقت زیر-نمونه‌ای
        var tauExact = chosen.toDouble()
        if (chosen in 1 until limit) {
            val a = cmnd[chosen - 1]
            val b = cmnd[chosen]
            val c = cmnd[chosen + 1]
            val den = a - 2.0 * b + c
            if (abs(den) > 1e-14) {
                val delta = 0.5 * (a - c) / den
                if (delta in -0.5..0.5) tauExact += delta
            }
        }
        out.clarity = (1.0 - cmnd[chosen]).coerceIn(0.0, 1.0)
        if (tauExact <= 0.5) return
        val f0 = sampleRate / tauExact
        if (f0 < fMin * 0.9 || f0 > fMax * 1.1) return
        out.f0 = f0
    }
}

/** نتیجهٔ کل ردیابی: فرکانس هر فریم + شفافیت */
class PitchTrack(
    val hop: Int,
    val sampleRate: Int,
    val t: DoubleArray,
    val f0: DoubleArray,
    val clarity: DoubleArray,
    val rms: DoubleArray
) {
    val size: Int get() = f0.size
}

object PitchTracker {

    /**
     * ردیابی کامل. فرکانس فریم‌های خاموش (سکوت) صفر می‌ماند.
     */
    fun track(
        sig: Signal,
        hopMs: Double,
        fMin: Double = 55.0,
        fMax: Double = 1200.0,
        frameSec: Double = 0.055,
        noiseFloor: Double = 0.004,
        progress: ((Double) -> Unit)? = null
    ): PitchTrack {
        val sr = sig.sampleRate
        val hop = max(1, (hopMs / 1000.0 * sr).toInt())
        val yin = YinTracker(sr, fMin, fMax, frameSec)
        val n = yin.framesCount(sig.size, hop)
        val t = DoubleArray(n)
        val f0 = DoubleArray(n)
        val clar = DoubleArray(n)
        val rms = DoubleArray(n)
        val fp = FramePitch()
        val frame = yin.newFrameBuffer()
        val reportEvery = max(1, n / 100)
        for (i in 0 until n) {
            yin.pitchAt(sig, i * hop, fp, frame)
            t[i] = (i * hop + yin.w / 2.0) / sr
            rms[i] = fp.rms
            if (fp.rms < noiseFloor || fp.clarity < 0.30) {
                f0[i] = 0.0
                clar[i] = fp.clarity
            } else {
                f0[i] = fp.f0
                clar[i] = fp.clarity
            }
            if (progress != null && i % reportEvery == 0) progress(i.toDouble() / n)
        }
        progress?.invoke(1.0)
        return PitchTrack(hop, sr, t, f0, clar, rms)
    }
}
