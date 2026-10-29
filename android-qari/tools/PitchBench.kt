package ir.hamyareqari.tools

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.DoubleSignal
import ir.hamyareqari.dsp.Harmonics
import ir.hamyareqari.dsp.PitchEngine
import ir.hamyareqari.dsp.PitchTrack
import ir.hamyareqari.dsp.PitchTracker
import ir.hamyareqari.dsp.Segmenter
import ir.hamyareqari.dsp.Wav
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * بنچمارک موتور F0 — بدون اندروید، روی JVM.
 *
 * هر نمونهٔ آزمایشی به‌صورت مصنوعی ساخته می‌شود و «فرکانس واقعی هر نمونه»
 * (ground truth در سطح نمونه) کنارش نگه داشته می‌شود. سپس دو مسیر پردازش
 * با هم مقایسه می‌شوند:
 *
 *   1) مسیر فعلی برنامه: YIN → اصلاح نردبان هارمونیک  (legacy)
 *   2) مسیر جدید: YIN + MPM → اعتبارسنجی فریم → ردیابی  (enhanced)
 *
 * معیارها (تعریف دقیق):
 *   • میانه/صدک ۹۵ خطای F0 بر حسب سنت — فقط روی فریم‌هایی که هر دو طرف
 *     (سیگنال و الگوریتم) صدادار هستند و پنجرهٔ تحلیل کاملاً داخل ناحیهٔ
 *     صدادارِ واقعی است (تا ابهام لبه‌ای حذف شود).
 *   • نرخ خطای اکتاو: فریم‌هایی که خطا در بازهٔ ۱۲۰۰±۸۰ سنت است.
 *   • نرخ خطای فاحش: |خطا| > ۵۰ سنت.
 *   • نرخ رد اشتباه (false reject): نسبت فریم‌های صدادارِ واقعی که خروجی
 *     آن‌ها صفر است.
 *   • نرخ پذیرش اشتباه (false accept): نسبت فریم‌های غیرصدادار (سکوت،
 *     نویز، تشویق، همهمه، باد) که خروجی آن‌ها nonzero است.
 *   • حفظ تحریر: دامنهٔ ارتعاش (سنت) در منحنی برآوردشده تقسیم بر همان
 *     دامنه در ground truth؛ ۱ یعنی کامل حفظ شده.
 *   • بازهٔ زمانی شروع/پایان: خطای مطلق onset و offset بر حسب میلی‌ثانیه، ولی جست‌وجو فقط داخل [شروع+۲۸ms، پایان−۲۸ms] انجام می‌شود، پس کفِ اندازه‌گیری ≈۲۸ms است (برای هر دو مسیر یکسان). اندازه‌گیری بدون این ناحیهٔ کور در `PitchAudit --boundary`.
 *
 * محدودیت‌ها: همهٔ نمونه‌ها مصنوعی‌اند. این بنچمارک خطای *نسبی* دو مسیر و
 * رگرسیون را می‌سنجد؛ اثبات دقت روی تلاوت واقعی قاریان نیست. برای آن به
 * مجموعهٔ صوتی واقعی برچسب‌خورده و ارزیابی متخصص نیاز است.
 *
 * اجرا:
 *   java -cp <classes>:<kotlin-stdlib> ir.hamyareqari.tools.PitchBench [--legacy] [--enhanced]
 */
object PitchBench {

    internal const val SR = 22050

    // ─────────────────────────── تولیدکنندهٔ تصادفی قطعی ───────────────────────────
    internal class Rnd(seed: Long) {
        private var s: Long = if (seed == 0L) 88172645463325252L else seed
        fun u(): Double { // [0,1)
            s = s xor (s shl 13)
            s = s xor (s ushr 7)
            s = s xor (s shl 17)
            val v = s * 2685821657736338717L
            return ((v ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        fun n(): Double = u() * 2.0 - 1.0
    }

    // ───────────────────────────── ساخت صدا ─────────────────────────────

    internal class VoiceParams(
        val harmonics: Int = 12,
        val vibDepthCents: Double = 28.0,
        val vibRate: Double = 5.5,
        val jitterCents: Double = 0.6,
        val tilt: Double = 1.15,
        val formants: Boolean = true
    )

    internal fun resonance(f: Double, fc: Double, bw: Double): Double =
        1.0 / (1.0 + ((f - fc) / bw).pow(2.0))

    /**
     * یک صدای هارمونیک شبیه خواننده. f0f(t) فرکانس پایه (Hz) یا ۰ برای سکوت.
     * خروجی: (سیگنال، فرکانس واقعی هر نمونه)
     */
    internal fun voice(
        dur: Double,
        p: VoiceParams,
        f0f: (Double) -> Double,
        rnd: Rnd
    ): Pair<DoubleArray, DoubleArray> {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        val truth = DoubleArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val base = f0f(t)
            if (base <= 0.0) { truth[i] = 0.0; continue }
            val vib = p.vibDepthCents * sin(2 * PI * p.vibRate * t)
            val jit = p.jitterCents * rnd.n()
            val f = base * 2.0.pow((vib + jit) / 1200.0)
            truth[i] = f
            ph += 2 * PI * f / SR
            var s = 0.0
            for (h in 1..p.harmonics) {
                val hf = f * h
                if (hf > SR * 0.45) break
                var a = 1.0 / h.toDouble().pow(p.tilt)
                if (p.formants) a *= resonance(hf, 620.0, 140.0) + 0.45 * resonance(hf, 1180.0, 200.0) + 0.12
                s += a * sin(ph * h)
            }
            out[i] = s
        }
        applyVoiceEnvelope(out, truth, 0.020, 0.045)
        return out to truth
    }

    /** پوش‌دامنه‌ای که از خودِ ناحیهٔ صدادار مشتق می‌شود (بدون نشت به سکوت) */
    private fun applyVoiceEnvelope(x: DoubleArray, truth: DoubleArray, attack: Double, release: Double) {
        val na = (attack * SR).toInt()
        val nr = (release * SR).toInt()
        var i = 0
        while (i < x.size) {
            if (truth[i] <= 0.0) { i++; continue }
            var j = i
            while (j < x.size && truth[j] > 0.0) j++
            for (k in i until j) {
                var g = 1.0
                if (k - i < na) g = min(g, (k - i).toDouble() / max(1, na))
                if (j - 1 - k < nr) g = min(g, (j - 1 - k).toDouble() / max(1, nr))
                x[k] *= g
            }
            i = j
        }
    }

    internal fun whiteNoise(dur: Double, rms: Double, rnd: Rnd): DoubleArray {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        // سه نمونهٔ مستقل: توزیع نزدیک‌تر به گاوسی
        for (i in 0 until n) out[i] = (rnd.n() + rnd.n() + rnd.n()) / 1.7320508 * rms
        return out
    }

    /** باد: نویز کم‌گذر با مدولاسیون آهسته */
    internal fun wind(dur: Double, rms: Double, rnd: Rnd): DoubleArray {
        val n = (dur * SR).toInt()
        val raw = whiteNoise(dur, 1.0, rnd)
        val out = DoubleArray(n)
        var y1 = 0.0; var y2 = 0.0; var y3 = 0.0
        val a = 0.02
        for (i in 0 until n) {
            y1 += a * (raw[i] - y1)
            y2 += a * (y1 - y2)
            y3 += a * (y2 - y3)
            val mod = 0.7 + 0.5 * sin(2 * PI * 0.23 * i / SR) + 0.2 * sin(2 * PI * 1.7 * i / SR)
            out[i] = y3 * mod
        }
        return normalizeRms(out, rms)
    }

    /** تشویق: انفجارهای کوتاه نویز باند‌میانی با افت نمایی */
    internal fun applause(dur: Double, rms: Double, rnd: Rnd): DoubleArray {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        var t = 0.05
        while (t < dur - 0.1) {
            val start = (t * SR).toInt()
            val len = (0.006 + 0.020 * rnd.u()) * SR
            var lp1 = 0.0; var hp = 0.0; var prev = 0.0
            val amp = 0.4 + 0.6 * rnd.u()
            var k = 0
            while (k < len.toInt() && start + k < n) {
                val w = rnd.n()
                lp1 += 0.35 * (w - lp1)
                val bp = lp1 - prev
                prev = lp1
                hp = bp
                out[start + k] += hp * amp * exp(-k / (0.008 * SR))
                k++
            }
            t += 0.045 + 0.09 * rnd.u()
        }
        val hiss = whiteNoise(dur, 0.25, rnd)
        for (i in 0 until n) out[i] += hiss[i]
        return normalizeRms(out, rms)
    }

    /** همهمه: چند «گوینده» هم‌زمان با هجاهای تصادفی */
    internal fun babble(dur: Double, rms: Double, rnd: Rnd, speakers: Int = 5): DoubleArray {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        val truth = DoubleArray(n)
        for (s in 0 until speakers) {
            // قله‌های تصادفی فرکانس پایه در محدودهٔ گفتار
            val base = 95.0 + 130.0 * rnd.u()
            val seedA = rnd.u() * 6.28
            val f0f: (Double) -> Double = { t ->
                val drift = 22.0 * sin(2 * PI * (0.6 + 0.5 * s) * t + seedA) +
                    9.0 * sin(2 * PI * (2.3 + 0.7 * s) * t)
                base + drift
            }
            val (v, t2) = voice(dur, VoiceParams(harmonics = 10, vibDepthCents = 12.0, vibRate = 6.0, tilt = 1.3), f0f, rnd)
            // پوشش هجایی
            for (i in 0 until n) {
                val tt = i.toDouble() / SR
                val syll = 0.5 + 0.5 * sin(2 * PI * (3.4 + 0.8 * s) * tt + s)
                val gate = if (syll > 0.45) 1.0 else 0.0
                out[i] += v[i] * gate * (0.35 + 0.25 * rnd.u())
                if (gate > 0.0 && t2[i] > 0.0) truth[i] = t2[i]
            }
        }
        return normalizeRms(out, rms)
    }

    internal fun normalizeRms(x: DoubleArray, rms: Double): DoubleArray {
        var s = 0.0
        for (v in x) s += v * v
        val cur = sqrt(s / max(1, x.size))
        if (cur < 1e-12) return x
        val g = rms / cur
        for (i in x.indices) x[i] *= g
        return x
    }

    internal fun addEcho(x: DoubleArray, delaysMs: IntArray, gains: DoubleArray): DoubleArray {
        val out = x.copyOf()
        for (d in delaysMs.indices) {
            val lag = (delaysMs[d] / 1000.0 * SR).toInt()
            val g = gains[d]
            for (i in x.indices) {
                val j = i - lag
                if (j >= 0) out[i] += x[j] * g
            }
        }
        return out
    }

    internal fun clip(x: DoubleArray, level: Double): DoubleArray {
        val out = x.copyOf()
        for (i in out.indices) out[i] = out[i].coerceIn(-level, level)
        return out
    }

    internal fun mix(a: DoubleArray, b: DoubleArray, gainB: Double): DoubleArray {
        val n = max(a.size, b.size)
        val out = DoubleArray(n)
        for (i in 0 until n) out[i] = (if (i < a.size) a[i] else 0.0) + (if (i < b.size) b[i] else 0.0) * gainB
        return out
    }

    // ───────────────────────────── نمونه‌های آزمایشی ─────────────────────────────

    class Case(
        val name: String,
        val group: String,
        val sr: Int,
        val audio: DoubleArray,
        val truth: DoubleArray,      // f0 واقعی هر نمونه (۰ = غیرصدادار)
        val expectedVoiceSec: Double, // مجموع زمان صدادار واقعی
        /** عمق ارتعاش واقعی (سنت)؛ صفر = بدون ارتعاش (معیار حفظ تحریر سنجیده نمی‌شود) */
        val vibDepthCents: Double = 0.0
    )

    private fun scaleCase(): Case {
        val rnd = Rnd(11)
        val notes = doubleArrayOf(220.0, 246.94, 261.63, 293.66, 329.63, 349.23, 392.0, 440.0)
        val chunks = ArrayList<Pair<DoubleArray, DoubleArray>>()
        val gapN = (0.10 * SR).toInt()
        for ((i, f) in notes.withIndex()) {
            val (v, t) = voice(0.9, VoiceParams(vibDepthCents = 22.0), { _ -> f }, rnd)
            chunks.add(v to t)
            chunks.add(DoubleArray(gapN) to DoubleArray(gapN))
        }
        var n = 0
        for (c in chunks) n += c.first.size
        val x = DoubleArray(n); val tr = DoubleArray(n)
        var p = 0
        var voiced = 0.0
        for (c in chunks) {
            System.arraycopy(c.first, 0, x, p, c.first.size)
            System.arraycopy(c.second, 0, tr, p, c.second.size)
            for (v in c.second) if (v > 0.0) voiced += 1.0 / SR
            p += c.first.size
        }
        return Case("scale_clean", "clean", SR, x, tr, voiced, 22.0)
    }

    private fun tarjeehCase(): Case {
        val rnd = Rnd(23)
        // تحریر سریع: ارتعاش ±۱۱۰ سنت روی ۵٫۵ هرتز + لغزش پیوسته بین درجات
        val f0f: (Double) -> Double = { t ->
            val base = 246.94 * 2.0.pow((60.0 * sin(2 * PI * 0.13 * t)) / 1200.0)
            base
        }
        val (v1, t1) = voice(4.0, VoiceParams(vibDepthCents = 110.0, vibRate = 5.5, jitterCents = 0.4), f0f, rnd)
        val (gap1, gapT1) = voice(0.25, VoiceParams(), { _ -> 0.0 }, rnd)
        val (v2, t2) = voice(3.0, VoiceParams(vibDepthCents = 60.0, vibRate = 7.0, jitterCents = 0.4),
            { t -> 392.0 * 2.0.pow((280.0 * sin(2 * PI * 0.9 * t)) / 1200.0) }, rnd)
        return concatCase("tarjeeh_fast", "tarjeeh", listOf(v1, gap1, v2), listOf(t1, gapT1, t2), 60.0)
    }

    private fun rangeCase(): Case {
        val rnd = Rnd(31)
        // بم: ۸۰–۱۱۵ هرتز  (صدای سینه‌ای قاری)
        val (v1, t1) = voice(3.0, VoiceParams(harmonics = 14, vibDepthCents = 18.0, tilt = 1.0),
            { t -> 92.0 + 9.0 * sin(2 * PI * 0.2 * t) }, rnd)
        val (g1, gt1) = voice(0.2, VoiceParams(), { _ -> 0.0 }, rnd)
        // زیر: ۴۹۰–۵۸۰ هرتز
        val (v2, t2) = voice(2.5, VoiceParams(harmonics = 8, vibDepthCents = 25.0),
            { t -> 540.0 + 40.0 * sin(2 * PI * 0.25 * t) }, rnd)
        return concatCase("range_low_high", "range", listOf(v1, g1, v2), listOf(t1, gt1, t2))
    }

    private fun stepsCase(): Case {
        val rnd = Rnd(37)
        // تغییر ناگهانی نت هر ۱۵۰ میلی‌ثانیه (بدون گذار) — سخت‌ترین حالت تفکیک زمانی
        val seq = doubleArrayOf(261.63, 329.63, 293.66, 392.0, 349.23, 440.0, 349.23, 293.66, 261.63)
        val parts = ArrayList<DoubleArray>()
        val truths = ArrayList<DoubleArray>()
        for ((i, f) in seq.withIndex()) {
            val (v, t) = voice(0.15, VoiceParams(vibDepthCents = 0.0, jitterCents = 0.2), { _ -> f }, rnd)
            // نامه‌ای بدون محو شدن در لبه‌ها: پوش با پلتوی کوتاه
            parts.add(v); truths.add(t)
            if (i == 4) {
                val s = DoubleArray((0.22 * SR).toInt())
                parts.add(s); truths.add(s.copyOf())
            }
        }
        return concatCase("steps_fast", "steps", parts, truths)
    }

    private fun wideTarjeehCase(): Case {
        val rnd = Rnd(89)
        // تحریر بسیار پهن: ±۱۷۰ سنت روی ۶٫۵ هرتز، با نویز اتاق ۲۵dB
        val (v, t) = voice(5.0, VoiceParams(vibDepthCents = 170.0, vibRate = 6.5, jitterCents = 1.5),
            { x -> 380.0 * 2.0.pow((40.0 * sin(2 * PI * 0.35 * x)) / 1200.0) }, rnd)
        val noise = whiteNoise(5.0, 0.02, rnd)
        return Case("tarjeeh_wide", "tarjeeh", SR, mix(v, noise, 1.0), t, 5.0, 170.0)
    }

    private fun falsettoCase(): Case {
        val rnd = Rnd(97)
        val (v, t) = voice(3.5, VoiceParams(harmonics = 7, vibDepthCents = 40.0),
            { x -> 620.0 * 2.0.pow((50.0 * sin(2 * PI * 0.5 * x)) / 1200.0) }, rnd)
        return Case("falsetto_high", "range", SR, v, t, 3.5, 40.0)
    }

    /** بم با پایهٔ ضعیف: تلهٔ کلاسیک خطای اکتاو */
    private fun weakFundamentalCase(): Case {
        val rnd = Rnd(101)
        val sr = SR
        val dur = 4.0
        val n = (dur * sr).toInt()
        val x = DoubleArray(n)
        val truth = DoubleArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 100.0 * 2.0.pow((25.0 * sin(2 * PI * 0.4 * t)) / 1200.0)
            truth[i] = f
            ph += 2 * PI * f / sr
            // پایه ۲۶dB زیر هارمونیک دوم
            var s = 0.0
            for (h in 1..9) {
                val a = if (h == 1) 0.05 else 1.0 / h.toDouble().pow(1.1)
                if (f * h > sr * 0.45) break
                s += a * sin(ph * h)
            }
            x[i] = s * 0.4 * (if (t < 0.03) t / 0.03 else if (t > dur - 0.05) (dur - t) / 0.05 else 1.0)
        }
        return Case("weak_fundamental_100", "range", sr, x, truth, dur)
    }

    private fun noiseCase(name: String, group: String, x: DoubleArray): Case =
        Case(name, group, SR, x, DoubleArray(x.size), 0.0)

    private fun silenceCase(): Case {
        val n = (6.0 * SR).toInt()
        return Case("silence_digital", "silence", SR, DoubleArray(n), DoubleArray(n), 0.0)
    }

    private fun hissCase(): Case {
        val rnd = Rnd(41)
        val n = (6.0 * SR).toInt()
        val x = whiteNoise(6.0, 0.004, rnd)   // ≈ −۴۸ dBFS
        return Case("silence_hiss", "silence", SR, x, DoubleArray(n), 0.0)
    }

    private fun noisyVoiceCase(snrDb: Double, tag: String): Case {
        val rnd = Rnd((53 + snrDb.toInt()).toLong())
        val (v, t) = voice(6.0, VoiceParams(vibDepthCents = 45.0, vibRate = 6.0),
            { x -> 262.0 * 2.0.pow((90.0 * sin(2 * PI * 0.7 * x)) / 1200.0) }, rnd)
        var sigPow = 0.0
        var cnt = 0
        for (i in v.indices) if (t[i] > 0.0) { sigPow += v[i] * v[i]; cnt++ }
        val sigRms = sqrt(sigPow / max(1, cnt))
        val noiseRms = sigRms / 10.0.pow(snrDb / 20.0)
        val noise = whiteNoise(6.0, noiseRms, rnd)
        val x = mix(v, noise, 1.0)
        return Case("voice_snr${tag}", "noise", SR, x, t, 6.0, 45.0)
    }

    private fun echoCase(): Case {
        val rnd = Rnd(61)
        val (v, t) = voice(5.0, VoiceParams(vibDepthCents = 35.0),
            { x -> 294.0 * 2.0.pow((70.0 * sin(2 * PI * 0.6 * x)) / 1200.0) }, rnd)
        val wet = addEcho(v, intArrayOf(60, 130, 240), doubleArrayOf(0.5, 0.32, 0.18))
        return Case("voice_echo", "echo", SR, wet, t, 5.0, 35.0)
    }

    private fun clippedCase(): Case {
        val rnd = Rnd(67)
        val (v, t) = voice(5.0, VoiceParams(vibDepthCents = 30.0),
            { x -> 330.0 * 2.0.pow((50.0 * sin(2 * PI * 0.5 * x)) / 1200.0) }, rnd)
        var peak = 0.0
        for (y in v) peak = max(peak, abs(y))
        val clipped = clip(v, peak * 0.22)
        return Case("voice_clipped", "clipping", SR, clipped, t, 5.0, 30.0)
    }

    private fun multiSourceCase(): Case {
        val rnd = Rnd(71)
        val (v, t) = voice(6.0, VoiceParams(vibDepthCents = 40.0),
            { x -> 300.0 * 2.0.pow((60.0 * sin(2 * PI * 0.55 * x)) / 1200.0) }, rnd)
        val talk = babble(6.0, 0.06, rnd, 3)
        val x = mix(v, talk, 1.0)
        return Case("voice_plus_babble", "mixed", SR, x, t, 6.0, 40.0)
    }

    private fun concatCase(
        name: String,
        group: String,
        parts: List<DoubleArray>,
        truths: List<DoubleArray>,
        vibDepth: Double = 0.0
    ): Case {
        var n = 0
        for (p in parts) n += p.size
        val x = DoubleArray(n); val tr = DoubleArray(n)
        var p0 = 0
        var voiced = 0.0
        for (i in parts.indices) {
            System.arraycopy(parts[i], 0, x, p0, parts[i].size)
            System.arraycopy(truths[i], 0, tr, p0, truths[i].size)
            for (v in truths[i]) if (v > 0.0) voiced += 1.0 / SR
            p0 += parts[i].size
        }
        return Case(name, group, SR, x, tr, voiced, vibDepth)
    }

    private fun rateCase(from: Int): Case {
        val rnd = Rnd(83)
        val (v, t) = voice(4.0, VoiceParams(vibDepthCents = 30.0),
            { x -> 280.0 * 2.0.pow((60.0 * sin(2 * PI * 0.4 * x)) / 1200.0) }, rnd)
        val audio = Wav.resample(v, SR, from)
        // ground truth با همان نرخ بازنمونه‌برداری می‌شود (مقدار f0 تغییر نمی‌کند)
        val truth = Wav.resample(t.map { it }.toDoubleArray(), SR, from)
        return Case("rate_$from", "samplerate", from, audio, truth, 4.0, 30.0)
    }

    internal fun buildCases(): List<Case> {
        val c = ArrayList<Case>()
        c.add(scaleCase())
        c.add(tarjeehCase())
        c.add(rangeCase())
        c.add(stepsCase())
        c.add(silenceCase())
        c.add(hissCase())
        c.add(noiseCase("white_noise", "noise", whiteNoise(6.0, 0.05, Rnd(43))))
        c.add(noiseCase("wind", "noise", wind(6.0, 0.10, Rnd(47))))
        c.add(noiseCase("applause", "applause", applause(6.0, 0.08, Rnd(59))))
        c.add(noiseCase("babble", "babble", babble(6.0, 0.07, Rnd(79))))
        c.add(noisyVoiceCase(20.0, "20"))
        c.add(noisyVoiceCase(10.0, "10"))
        c.add(noisyVoiceCase(5.0, "05"))
        c.add(echoCase())
        c.add(clippedCase())
        c.add(multiSourceCase())
        c.add(wideTarjeehCase())
        c.add(falsettoCase())
        c.add(weakFundamentalCase())
        c.add(rateCase(16000))
        c.add(rateCase(44100))
        return c
    }

    // ───────────────────────────── سنجش ─────────────────────────────

    class Metrics(
        val case: Case,
        val engine: String,
        val voicedFrames: Int,
        val falseReject: Double,
        val medCents: Double,
        val p95Cents: Double,
        val octaveRate: Double,
        val grossRate: Double,
        val unvoicedFrames: Int,
        val falseAccept: Double,
        val notes: Int,
        val sungSec: Double,
        val onsetErrMs: Double,
        val offsetErrMs: Double,
        val vibRetention: Double,
        val invalidFrames: Int,
        val ms: Long
    )

    internal fun centsOf(a: Double, b: Double): Double =
        if (a > 0.0 && b > 0.0) 1200.0 * log2(a / b) else 0.0

    internal fun median(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        val c = v.copyOf(); c.sort()
        return if (c.size % 2 == 1) c[c.size / 2] else 0.5 * (c[c.size / 2 - 1] + c[c.size / 2])
    }

    internal fun percentile(v: DoubleArray, p: Double): Double {
        if (v.isEmpty()) return 0.0
        val c = v.copyOf(); c.sort()
        val pos = (p / 100.0 * (c.size - 1)).coerceIn(0.0, (c.size - 1).toDouble())
        val lo = floor(pos).toInt()
        val hi = min(c.size - 1, lo + 1)
        return c[lo] + (c[hi] - c[lo]) * (pos - lo)
    }

    /** خطای شروع/پایان قطعه‌های صدادار (نسبت به ground truth) */
    private fun segmentTiming(case: Case, t: DoubleArray, f0: DoubleArray, halfWin: Int): Pair<Double, Double> {
        // قطعه‌های واقعی
        val gt = ArrayList<IntArray>()
        var i = 0
        while (i < case.truth.size) {
            if (case.truth[i] <= 0.0) { i++; continue }
            var j = i
            while (j < case.truth.size && case.truth[j] > 0.0) j++
            if (j - i > 0.15 * case.sr) gt.add(intArrayOf(i, j))
            i = j
        }
        if (gt.isEmpty()) return 0.0 to 0.0
        val onsetErrs = ArrayList<Double>()
        val offsetErrs = ArrayList<Double>()
        for (s in gt) {
            val ts = s[0].toDouble() / case.sr
            val te = s[1].toDouble() / case.sr
            var firstT = Double.NaN
            var lastT = Double.NaN
            for (k in t.indices) {
                val c = (t[k] * case.sr).roundToInt()
                if (c < s[0] + halfWin || c > s[1] - halfWin) continue
                if (f0[k] > 0.0) { if (firstT.isNaN()) firstT = t[k]; lastT = t[k] }
            }
            if (!firstT.isNaN()) {
                onsetErrs.add(abs(firstT - ts) * 1000.0)
                offsetErrs.add(abs(lastT - te) * 1000.0)
            }
        }
        if (onsetErrs.isEmpty()) return Double.NaN to Double.NaN
        return median(onsetErrs.toDoubleArray()) to median(offsetErrs.toDoubleArray())
    }

    private fun evaluate(
        case: Case,
        engineName: String,
        t: DoubleArray,
        f0: DoubleArray,
        ms: Long
    ): Metrics {
        val sr = case.sr
        val halfWin = (0.028 * sr).toInt()
        val errs = ArrayList<Double>()
        var voicedFrames = 0
        var rejected = 0
        var octave = 0
        var gross = 0
        var unvoicedFrames = 0
        var falseAccept = 0
        var invalid = 0
        for (k in t.indices) {
            val c = (t[k] * sr).roundToInt()
            if (c - halfWin < 0 || c + halfWin >= case.truth.size) continue
            var allVoiced = true
            var anyVoiced = false
            var x = c - halfWin
            while (x <= c + halfWin) {
                if (case.truth[x] > 0.0) anyVoiced = true else allVoiced = false
                x += 4
            }
            if (!anyVoiced) {
                unvoicedFrames++
                if (f0[k] > 0.0) falseAccept++
                continue
            }
            if (!allVoiced) continue // ناحیهٔ مرزی: داوری نمی‌شود
            voicedFrames++
            if (f0[k] <= 0.0) { rejected++; invalid++; continue }
            val e = centsOf(f0[k], case.truth[c])
            errs.add(e)
            val ae = abs(e)
            if (ae > 50.0) gross++
            if (ae > 1120.0 && ae < 1280.0) octave++
        }
        val errAbs = DoubleArray(errs.size) { abs(errs[it]) }
        // حفظ تحریر: دامنهٔ ارتعاش
        val gtCents = ArrayList<Double>()
        val estCents = ArrayList<Double>()
        for (k in t.indices) {
            val c = (t[k] * sr).roundToInt()
            if (c - halfWin < 0 || c + halfWin >= case.truth.size) continue
            if (case.truth[c] <= 0.0) continue
            gtCents.add(1200.0 * log2(case.truth[c] / 220.0))
            if (f0[k] > 0.0) estCents.add(1200.0 * log2(f0[k] / 220.0)) else estCents.add(Double.NaN)
        }
        val retention = vibratoRetention(gtCents, estCents, case.vibDepthCents)
        val (on, off) = segmentTiming(case, t, f0, halfWin)

        // نت‌ها: از همان مسیر تولید نت (Segmenter) برای مقایسهٔ یکسان
        val track = PitchTrack(
            1, sr, t, f0,
            DoubleArray(f0.size) { if (f0[it] > 0.0) 0.8 else 0.0 },
            DoubleArray(f0.size) { 0.05 }
        )
        val notes = Segmenter.segment(track)
        val sung = Segmenter.sungSeconds(notes)

        return Metrics(
            case = case,
            engine = engineName,
            voicedFrames = voicedFrames,
            falseReject = if (voicedFrames > 0) rejected.toDouble() / voicedFrames else 0.0,
            medCents = median(errAbs),
            p95Cents = percentile(errAbs, 95.0),
            octaveRate = if (errs.isNotEmpty()) octave.toDouble() / errs.size else 0.0,
            grossRate = if (errs.isNotEmpty()) gross.toDouble() / errs.size else 0.0,
            unvoicedFrames = unvoicedFrames,
            falseAccept = if (unvoicedFrames > 0) falseAccept.toDouble() / unvoicedFrames else 0.0,
            notes = notes.size,
            sungSec = sung,
            onsetErrMs = on,
            offsetErrMs = off,
            vibRetention = retention,
            invalidFrames = invalid,
            ms = ms
        )
    }

    /**
     * دامنهٔ ارتعاش (سنت) نسبت به میانهٔ محلی؛ نسبت خروجی به ورودی.
     * از انحراف مطلق میانه (MAD) استفاده می‌شود تا چند فریم پرت، معیار را
     * خراب نکنند؛ و پنجره‌هایی که کمتر از ۸۰٪ فریم معتبر دارند نادیده
     * گرفته می‌شوند تا «حفظ ارتعاش» با «رد کردن فریم» اشتباه نشود.
     */
    private fun vibratoRetention(gt: List<Double>, est: List<Double>, vibDepth: Double): Double {
        if (gt.size < 50 || vibDepth < 10.0) return Double.NaN
        val win = 60 // ≈ ۳۰۰ میلی‌ثانیه با گام ۵ms
        fun amplitude(src: List<Double>, requireFull: Boolean): Double {
            val devs = ArrayList<Double>()
            var i = 0
            while (i < src.size) {
                val j = min(src.size, i + win)
                val seg = ArrayList<Double>()
                var total = 0
                for (k in i until j) { total++; if (!src[k].isNaN()) seg.add(src[k]) }
                if (seg.size > win / 3 && (!requireFull || seg.size >= 0.8 * total)) {
                    val m = median(seg.toDoubleArray())
                    val absDev = DoubleArray(seg.size) { abs(seg[it] - m) }
                    devs.add(1.4826 * median(absDev))
                }
                i += win / 2
            }
            if (devs.isEmpty()) return Double.NaN
            return median(devs.toDoubleArray())
        }
        val a = amplitude(gt, false)
        val b = amplitude(est, true)
        if (a.isNaN() || b.isNaN() || a < 1.0) return Double.NaN
        return b / a
    }

    // ───────────────────────────── مسیرهای پردازش ─────────────────────────────

    private fun runLegacy(case: Case): Triple<DoubleArray, DoubleArray, Long> {
        val prepared = Analyzer.preprocess(case.audio, case.sr)
        val sig = DoubleSignal(prepared, case.sr)
        val t0 = System.nanoTime()
        val track = PitchTracker.track(sig, 5.0)
        val corr = Harmonics.correctRuns(track, sig, 55.0, 1200.0)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Triple(track.t, corr.f0, ms)
    }

    private fun runEnhanced(case: Case): Triple<DoubleArray, DoubleArray, Long> {
        val prepared = Analyzer.preprocess(case.audio, case.sr)
        val sig = DoubleSignal(prepared, case.sr)
        val t0 = System.nanoTime()
        val curve = PitchEngine.analyze(sig, hopMs = 5.0)
        val ms = (System.nanoTime() - t0) / 1_000_000
        return Triple(curve.t, curve.f0, ms)
    }

    // ───────────────────────────── گزارش ─────────────────────────────

    private fun f(v: Double, d: Int = 1): String =
        if (v.isNaN()) "  —  " else String.format(java.util.Locale.US, "%." + d + "f", v)

    private fun pct(v: Double): String =
        if (v.isNaN()) "  —  " else String.format(java.util.Locale.US, "%.1f%%", v * 100.0)

    /** آمار ویژگی‌های هر نمونه — برای تنظیم آستانه‌ها با شاهد عددی */
    private fun featureStats(cases: List<Case>) {
        println("نمونه                  پشتیبان  پوشش   پخشی   conf  شفافیت  مدولاسیون")
        val est = ir.hamyareqari.dsp.DualEstimator(
            ir.hamyareqari.dsp.Analyzer.preprocess(cases[0].audio, cases[0].sr).size.let { SR },
            if (cases[0].sr == SR) 55.0 else 55.0, 1200.0, 0.045
        )
        for (c in cases) {
            // برای نرخ‌های دیگر، بازنمونه‌برداری به نرخ مبنا انجام می‌شود
            val audio = if (c.sr == SR) c.audio else Wav.resample(c.audio, c.sr, SR)
            val prep = Analyzer.preprocess(audio, SR)
            val sig = DoubleSignal(prep, SR)
            val hop = (0.005 * SR).toInt()
            val n = est.framesCount(sig.size, hop)
            val fr = ir.hamyareqari.dsp.FrameResult()
            val buf = est.newFrameBuffer()
            val sup = ArrayList<Double>()
            val cov = ArrayList<Double>()
            val fla = ArrayList<Double>()
            val con = ArrayList<Double>()
            val clar = ArrayList<Double>()
            val rmsAll = ArrayList<Double>()
            var i = 0
            while (i < n) {
                est.analyze(sig, i * hop, fr, buf)
                rmsAll.add(fr.rms)
                if (fr.rms > 0.01) {
                    sup.add(fr.bestSupport); cov.add(fr.coverage); fla.add(fr.flatness)
                    clar.add(max(fr.yinClarity, fr.mpmClarity))
                    con.add(ir.hamyareqari.dsp.PitchEngine.frameConfidence(
                        40.0, fr.yinF0, fr.yinClarity, fr.mpmF0, fr.mpmClarity,
                        fr.bestSupport, fr.coverage, fr.flatness, fr.flux, fr.clipFrac
                    ))
                }
                i += 3
            }
            // شاخص مدولاسیون پوش‌دقیقه‌ای (پنجرهٔ ±۲۰۰ms)
            val mods = ArrayList<Double>()
            val w = 40
            var k0 = 0
            while (k0 < rmsAll.size) {
                val z = min(rmsAll.size, k0 + w)
                val seg = rmsAll.subList(k0, z).sorted()
                val med = seg[seg.size / 2]
                if (med > 1e-4) {
                    val p10 = seg[(0.10 * (seg.size - 1)).toInt()]
                    val p90 = seg[(0.90 * (seg.size - 1)).toInt()]
                    mods.add((p90 - p10) / med)
                }
                k0 += w / 2
            }
            fun md(v: ArrayList<Double>): Double =
                if (v.isEmpty()) Double.NaN else PitchMathMedian(v)
            println(
                String.format(
                    java.util.Locale.US, "%-22s %7.3f %7.3f %7.3f %7.3f %7.3f %7.2f", c.name,
                    md(sup), md(cov), md(fla), md(con), md(clar), md(mods)
                )
            )
        }
    }

    private fun PitchMathMedian(v: ArrayList<Double>): Double {
        val a = v.sorted()
        val n = a.size
        return if (n % 2 == 1) a[n / 2] else 0.5 * (a[n / 2 - 1] + a[n / 2])
    }

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.contains("--features")) { featureStats(buildCases()); return }
        val wantLegacy = args.isEmpty() || args.contains("--legacy")
        val wantEnhanced = args.isEmpty() || args.contains("--enhanced")
        val cases = buildCases()
        println("═══ بنچمارک موتور F0 «همیار قاری» ═══")
        println("همهٔ نمونه‌ها مصنوعی‌اند؛ اعداد، خطای نسبی دو مسیر و رگرسیون را می‌سنجند.\n")

        val all = ArrayList<Metrics>()
        println(
            String.format(
                java.util.Locale.US,
                "%-22s %-10s %6s %7s %7s %7s %7s %8s %7s %6s %8s %8s %7s",
                "نمونه", "موتور", "رد%", "میانه‌سنت", "p95", "اکتاو%", "فاحش%",
                "پذیرش%", "نت", "آواز", "onsetms", "offsetms", "زمانms"
            )
        )
        for (c in cases) {
            val results = ArrayList<Metrics>()
            if (wantLegacy) {
                val (t, hz, ms) = runLegacy(c)
                results.add(evaluate(c, "legacy", t, hz, ms))
            }
            if (wantEnhanced) {
                val (t, hz, ms) = runEnhanced(c)
                results.add(evaluate(c, "enhanced", t, hz, ms))
            }
            for (m in results) all.add(m)
            for (m in results) {
                println(
                    String.format(
                        java.util.Locale.US,
                        "%-22s %-10s %6s %8s %7s %7s %7s %8s %6d %6s %8s %8s %7d",
                        m.case.name, m.engine, pct(m.falseReject), f(m.medCents, 2), f(m.p95Cents, 1),
                        pct(m.octaveRate), pct(m.grossRate), pct(m.falseAccept),
                        m.notes, f(m.sungSec, 1), f(m.onsetErrMs, 0), f(m.offsetErrMs, 0), m.ms
                    )
                )
            }
        }
        println()

        // خلاصه
        for (eng in listOf("legacy", "enhanced")) {
            val rs = all.filter { it.engine == eng }
            if (rs.isEmpty()) continue
            val voiceCases = rs.filter { it.voicedFrames > 200 }
            val noiseCases = rs.filter { it.case.expectedVoiceSec < 0.2 && it.unvoicedFrames > 200 }
            val medAll = voiceCases.map { it.medCents }.filter { !it.isNaN() }
            val p95All = voiceCases.map { it.p95Cents }
            val grossAll = voiceCases.map { it.grossRate }
            val octAll = voiceCases.map { it.octaveRate }
            val rejectAll = voiceCases.map { it.falseReject }
            val acceptAll = noiseCases.map { it.falseAccept }
            println(
                "خلاصهٔ «$eng»: " +
                    "میانهٔ خطا=${f(median(medAll.toDoubleArray()), 2)} سنت · " +
                    "بدترین p95=${f(p95All.maxOrNull() ?: Double.NaN, 1)} سنت · " +
                    "اکتاو=${pct(octAll.average())} · فاحش=${pct(grossAll.average())} · " +
                    "رد اشتباه=${pct(rejectAll.average())} · " +
                    "پذیرش اشتباهِ نویز=${pct(acceptAll.average())}"
            )
            val vib = rs.filter { !it.vibRetention.isNaN() }.map { it.vibRetention }
            if (vib.isNotEmpty()) {
                println("   حفظ تحریر (دامنهٔ ارتعاش خروجی/ورودی): " +
                    vib.joinToString(" ") { f(it, 2) })
            }
        }

        if (wantLegacy && wantEnhanced) {
            val legacy = all.filter { it.engine == "legacy" }
            val enh = all.filter { it.engine == "enhanced" }
            var failures = 0
            for (i in legacy.indices) {
                val a = legacy[i]; val b = enh[i]
                fun fail(msg: String) { failures++; println("❌ ${a.case.name}: $msg") }
                // ۱) خطای دقت نباید بدتر شود
                if (!b.medCents.isNaN() && !a.medCents.isNaN() && b.medCents > a.medCents + 3.0)
                    fail("میانهٔ خطا بدتر شد (${f(a.medCents, 2)} → ${f(b.medCents, 2)})")
                // ۲) خطای اکتاو نباید بدتر شود
                if (b.octaveRate > a.octaveRate + 0.02) fail("خطای اکتاو بدتر شد")
                // ۳) رد اشتباه نباید بدتر شود
                if (b.falseReject > a.falseReject + 0.10) fail("رد اشتباه بدتر شد")
                // ۴) پذیرش نویز نباید بدتر شود
                if (b.falseAccept > a.falseAccept + 0.05) fail("پذیرش نویز بدتر شد")
                // ۵) حفظ تحریر نباید به‌شدت افت کند
                if (!a.vibRetention.isNaN() && !b.vibRetention.isNaN() &&
                    b.vibRetention < a.vibRetention - 0.15
                ) fail("حفظ تحریر افت کرد (${f(a.vibRetention, 2)} → ${f(b.vibRetention, 2)})")
            }
            if (failures == 0) println("\n✅ هیچ رگرسیونی نسبت به مسیر فعلی دیده نشد")
            else {
                println("\n❌ $failures رگرسیون")
                System.exit(1)
            }
        }
    }
}
