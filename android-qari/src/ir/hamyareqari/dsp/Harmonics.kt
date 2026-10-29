package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * اصلاح «نردبان هارمونیک» + پالایش هر نت با برازش هارمونیک‌ها.
 *
 * خطاهای رایج ردیاب‌های f0 سه‌تاست و هر سه اینجا درمان می‌شوند:
 *   • اکتاو (۲×، ½×)
 *   • هارمونیک سوم/چهارم (۳×، ۴×)
 *   • گم‌شدن فرکانس پایه (بلندگوی گوشی، باند تلفنی، صدای سینه‌ای ضعیف)
 * درمان: برای هر فرض، «امتیاز هماهنگی هارمونیک» از طیف گرفته می‌شود و
 * فرضی که هارمونیک‌های فردش غایب است جریمه می‌شود.
 */
object Harmonics {

    /** فرض‌های نردبان: زیراکتاوِ دوم تا دو اکتاو بالا */
    val LADDER = doubleArrayOf(0.25, 1.0 / 3.0, 0.5, 1.0, 2.0, 3.0, 4.0)

    private val WEIGHTS = DoubleArray(16) { 1.0 / (it + 1) }

    /** امتیاز هر فرض برای یک فریم — مقدار بین −۱ (بد) و ۰ (خوب) */
    fun frameScores(
        mag: DoubleArray,
        binHz: Double,
        baseF: Double,
        maxHz: Double,
        maxH: Int = 12,
        ratios: DoubleArray = LADDER
    ): DoubleArray {
        val nBins = mag.size
        // مرجع: بلندترین قلهٔ باند
        var ref = 1e-12
        val lo = max(1, floor(55.0 / binHz).toInt())
        val hi = min(nBins - 2, ceil(maxHz / binHz).toInt())
        for (b in lo..hi) if (mag[b] > ref) ref = mag[b]
        val out = DoubleArray(ratios.size)
        for (ri in ratios.indices) {
            var num = 0.0
            var den = 0.0
            for (k in 1..maxH) {
                val pos = baseF * ratios[ri] * k / binHz
                if (pos < 1.0 || pos >= nBins - 2 || pos * binHz > maxHz) continue
                val ip = pos.roundToInt()
                var peak = 0.0
                for (off in -2..2) {
                    val j = ip + off
                    if (j < 0 || j >= nBins) continue
                    if (mag[j] > peak) peak = mag[j]
                }
                val db = 20.0 * log10(max(peak, 1e-12) / ref)
                val sk = PitchMath.clamp(db / 25.0, -1.0, 0.0)
                val wgt = WEIGHTS[(k - 1).coerceAtMost(WEIGHTS.size - 1)]
                num += sk * wgt
                den += wgt
            }
            out[ri] = if (den > 0.35) num / den else -1.0
        }
        return out
    }

    class Correction(val f0: DoubleArray, val up: Int, val down: Int)

    /**
     * اصلاح ران‌های صدادار. «پایین‌ترین فرضِ تقریباً هم‌امتیاز» درست است،
     * چون هارمونیک‌های یک نتِ بم، هارمونیک‌های فرض‌های بالاتر را هم پوشش می‌دهند
     * اما برعکسش نه.
     */
    fun correctRuns(
        track: PitchTrack,
        sig: Signal,
        fMin: Double,
        fMax: Double,
        progress: ((Double) -> Unit)? = null
    ): Correction {
        val n = track.size
        val sr = track.sampleRate
        val out = track.f0.copyOf()
        val ratios = LADDER
        var up = 0
        var down = 0
        if (n == 0) return Correction(out, 0, 0)

        // ران‌های پیوستهٔ صدادار
        val runs = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (!(track.f0[i] > 0.0)) { i++; continue }
            var j = i + 1
            while (j < n && track.f0[j] > 0.0 && (track.t[j] - track.t[j - 1]) < 0.08) j++
            if (j - i >= 3 && (track.t[j - 1] - track.t[i]) >= 0.10) runs.add(intArrayOf(i, j))
            i = j
        }

        val winN = (0.046 * sr).toInt()
        val nfft = nextPow2(max(4096, winN + 8))
        val win = hann(winN)
        val fft = Fft(nfft)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        val binHz = sr.toDouble() / nfft
        val maxHz = min(3600.0, sr / 2.0 - binHz)
        val frame = DoubleArray(winN)

        for ((ri, run) in runs.withIndex()) {
            val i0 = run[0]
            val i1 = run[1]
            val med = PitchMath.median(track.f0.copyOfRange(i0, i1))
            if (!(med > 0.0)) continue

            val stride = max(1, ceil((i1 - i0) / 48.0).toInt())
            val sel = ArrayList<Int>()
            var k = i0
            while (k < i1) {
                sel.add(k)
                k += stride
            }
            if (sel.size < 2) continue

            val pooled = DoubleArray(ratios.size)
            val columns = Array(ratios.size) { DoubleArray(sel.size) }
            for ((si, fi) in sel.withIndex()) {
                val center = (track.t[fi] * sr).toInt()
                val start = center - winN / 2
                if (start < 0 || start + winN > sig.size) continue
                sig.read(start, winN, frame)
                val mag = magSpectrum(frame, nfft, win, fft, re, im)
                val sc = frameScores(mag, binHz, track.f0[fi], maxHz, 12, ratios)
                for (q in ratios.indices) columns[q][si] = sc[q]
            }
            for (q in ratios.indices) pooled[q] = PitchMath.median(columns[q])
            var best = -Double.MAX_VALUE
            for (q in pooled.indices) if (pooled[q] > best) best = pooled[q]
            if (best < -0.5) continue

            var targetR = Double.POSITIVE_INFINITY
            for (q in pooled.indices) if (pooled[q] >= best - 0.10) targetR = min(targetR, ratios[q])
            if (!targetR.isFinite() || abs(targetR - 1.0) < 1e-6) continue

            val target = med * targetR
            val scale = target / med
            for (kk in i0 until i1) {
                val fv = track.f0[kk]
                if (!(fv > 0.0)) continue
                val qq = fv / med
                var rr = ratios[0]
                var bestC = abs(PitchMath.cents(qq, rr))
                for (L in ratios.indices) {
                    val c = abs(PitchMath.cents(qq, ratios[L]))
                    if (c < bestC) { bestC = c; rr = ratios[L] }
                }
                var nv = if (bestC <= 70.0) target * rr else fv * scale
                if (abs(PitchMath.cents(nv, fv)) < 15.0) nv = fv
                if (nv < fMin * 0.95 || nv > fMax * 1.05) nv = fv
                if (nv < fv * 0.98) down++ else if (nv > fv * 1.02) up++
                out[kk] = nv
            }
            progress?.invoke((ri + 1).toDouble() / max(1, runs.size))
        }
        return Correction(out, up, down)
    }

    class Refined(val f0: Double, val support: Int)

    /**
     * پالایش یک نت با میانگین‌گیری طیف در طول نت و سپس برازش هارمونیک‌ها:
     * جای هر هارمونیک با درون‌یابی سهمی پیدا و f0 از همهٔ آن‌ها برازش می‌شود.
     * این مرحله دقت را از «چند سنت» به «کمتر از یک سنت» روی تنِ تمیز می‌برد.
     */
    fun refineNote(
        sig: Signal,
        sr: Int,
        t0: Double,
        t1: Double,
        f0: Double,
        winSec: Double = 0.06,
        nfft: Int = 16384
    ): Refined {
        val dur = max(0.0, t1 - t0)
        val pad = min(0.15 * dur, 0.08)
        var a = t0 + pad
        var b = t1 - pad
        if (b - a < 0.04) { a = t0; b = t1 }
        val winN = (sr * winSec).toInt().coerceAtMost(nfft)
        var i0 = (a * sr).roundToInt() - winN / 2
        var i1 = (b * sr).roundToInt() + winN
        i0 = max(0, i0)
        i1 = min(sig.size, i1)
        if (i1 - i0 < winN) return Refined(f0, 0)
        val block = DoubleArray(i1 - i0)
        sig.read(i0, block.size, block)

        val starts = ArrayList<Int>()
        val half = max(1, winN / 2)
        var s = max(0, (a * sr).roundToInt() - i0)
        val stop = max(0, (b * sr).roundToInt() - i0)
        while (s < stop) {
            if (s + winN <= i1 - i0) starts.add(s)
            s += half
        }
        if (starts.isEmpty()) starts.add(max(0, ((i1 - i0) / 2) - winN / 2))

        val win = hann(winN)
        val fft = Fft(nfft)
        val re = DoubleArray(nfft)
        val im = DoubleArray(nfft)
        val acc = DoubleArray(nfft / 2 + 1)
        val frame = DoubleArray(winN)
        for (st in starts) {
            if (st + winN > block.size) continue
            for (t in 0 until winN) frame[t] = block[st + t]
            val mag = magSpectrum(frame, nfft, win, fft, re, im)
            for (q in acc.indices) acc[q] += mag[q]
        }
        val inv = 1.0 / starts.size
        for (q in acc.indices) acc[q] *= inv

        val binHz = sr.toDouble() / nfft
        val nyq = sr / 2.0
        // بیشینهٔ باند برای نرمال‌سازی (وزن‌ها)
        var ref = 1e-12
        val loB = max(1, (55.0 / binHz).toInt())
        val hiB = min(acc.size - 2, (3600.0 / binHz).toInt())
        for (k in loB..hiB) if (acc[k] > ref) ref = acc[k]

        var est = f0
        var support = 0
        for (iter in 0 until 2) {
            var num = 0.0
            var den = 0.0
            support = 0
            var k = 1
            val maxH = 24
            while (k <= maxH) {
                val target = est * k
                if (target >= nyq * 0.92) break
                val pos = target / binHz
                if (pos < 2.0) { k++; continue }
                val center = pos.roundToInt()
                val span = max(2, (0.035 * pos).roundToInt())
                var bi = -1
                var bv = -1.0
                for (j in max(1, center - span)..min(acc.size - 2, center + span)) {
                    if (acc[j] > bv) { bv = acc[j]; bi = j }
                }
                if (bi < 0 || bv < ref * 1e-3) { k++; continue }
                val frac = parabolicPeak(acc, bi)
                val peakHz = frac * binHz
                val db = 20.0 * log10(max(bv, 1e-12) / ref)
                if (db < -40.0) { k++; continue }
                val c = PitchMath.cents(peakHz, target)
                if (abs(c) > 60.0) { k++; continue }
                val wgt = bv.pow(0.5) / k
                num += c * wgt
                den += wgt
                support++
                k++
            }
            if (den <= 0.0) break
            val shift = num / den
            if (abs(shift) > 60.0) break
            est *= 2.0.pow(shift / 1200.0)
            if (abs(shift) < 0.02) break
        }
        if (abs(PitchMath.cents(est, f0)) > 70.0) return Refined(f0, support)
        return Refined(est, support)
    }

    /**
     * اصلاح سریع اکتاو برای اجرای زنده: فقط سه فرض (½، ۱، ۲) روی یک طیف کوچک.
     * این کار جلوی «نتِ یک اکتاو بالاتر» را در نمایش زنده می‌گیرد.
     */
    fun quickOctaveFix(
        sig: Signal,
        sr: Int,
        start: Int,
        len: Int,
        f0: Double,
        fft: Fft,
        re: DoubleArray,
        im: DoubleArray,
        frame: DoubleArray,
        win: DoubleArray
    ): Double {
        val nfft = fft.n
        val usable = min(len, min(nfft, sig.size - start))
        if (usable < nfft / 2 || !(f0 > 0.0)) return f0
        sig.read(start, usable, frame)
        val mag = magSpectrum(frame.copyOf(usable), nfft, win, fft, re, im)
        val binHz = sr.toDouble() / nfft
        val ratios = doubleArrayOf(0.5, 1.0, 2.0)
        val sc = frameScores(mag, binHz, f0, min(3600.0, sr / 2.0), 8, ratios)
        var best = -Double.MAX_VALUE
        for (v in sc) if (v > best) best = v
        if (best < -0.55) return f0
        var chosen = 1.0
        for (q in ratios.indices) if (sc[q] >= best - 0.12) { chosen = ratios[q]; break }
        return f0 * chosen
    }
}
