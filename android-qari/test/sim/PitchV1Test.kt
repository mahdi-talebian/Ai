package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.DoubleSignal
import ir.hamyareqari.dsp.PitchMath
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * آزمون موتور V1 — سناریوهای واقعی با Ground Truth مستقل (سطح نمونه).
 *
 * معیارها (طبق برنامهٔ پذیرش):
 *   Median Cent Error · P95 Cent Error · Gross Pitch Error (|err|>50c) ·
 *   Octave Error Rate (err≈±1200±80) · False Acceptance (سکوت/تشویق/همهمه) ·
 *   False Rejection (صدای واقعی رد شود) · Tahrir Preservation · P50/P95 Latency
 *
 * محدودیت صادقانه: همهٔ نمونه‌ها مصنوعی‌اند (تُن هارمونیک با vibrato واقع‌گرایانه).
 * این‌ها خطای نسبی و رگرسیون را می‌سنجند؛ اثبات دقت روی تلاوت واقعی قاریان
 * به مجموعهٔ صوتی برچسب‌خوردهٔ انسانی نیاز دارد که در این محیط در دسترس نیست.
 *
 * اجرا:  java -cp <classes>:<stdlib> ir.hamyareqari.dsp.pitch.PitchV1Test
 */
object PitchV1Test {

    private const val SR = 22050
    private var failures = 0
    private var checks = 0

    private fun check(ok: Boolean, title: String, detail: String) {
        checks++
        if (ok) println("  ✅ $title — $detail")
        else { failures++; println("  ❌ $title — $detail") }
    }

    // ─────────────────────────── تولید سیگنال ───────────────────────────

    internal class Rnd(seed: Long) {
        private var s: Long = if (seed == 0L) 88172645463325252L else seed
        fun u(): Double {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s * 2685821657736338717L ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        fun n(): Double = u() * 2.0 - 1.0
    }

    /** صدای هارمونیک شبیه خواننده؛ f0f(t)=0 یعنی سکوت. خروجی: (سیگنال، truth) */
    private fun voice(
        dur: Double,
        f0f: (Double) -> Double,
        vibCents: Double = 25.0,
        vibRate: Double = 5.5,
        gain: Double = 1.0,
        seed: Long = 7
    ): Pair<DoubleArray, DoubleArray> {
        val rnd = Rnd(seed)
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        val truth = DoubleArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val base = f0f(t)
            if (base <= 0.0) continue
            val vib = vibCents * sin(2 * PI * vibRate * t)
            val f = base * 2.0.pow((vib + 0.5 * rnd.n()) / 1200.0)
            truth[i] = f
            ph += 2 * PI * f / SR
            var s = 0.0
            for (h in 1..12) {
                if (f * h > SR * 0.45) break
                val a = 1.0 / h.toDouble().pow(1.15)
                s += a * sin(ph * h)
            }
            out[i] = (s + 0.003 * rnd.n()) * gain
        }
        return out to truth
    }

    /** تشویق: ضربه‌های تصادفی کوتاه */
    private fun applause(dur: Double, gain: Double = 1.0, seed: Long = 11): DoubleArray {
        val rnd = Rnd(seed)
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        var i = 0
        while (i < n) {
            val gap = (0.01 + 0.06 * rnd.u()) * SR
            i += gap.toInt()
            if (i >= n) break
            val len = (0.004 + 0.012 * rnd.u()) * SR
            val amp = (0.2 + 0.8 * rnd.u()) * gain
            for (k in 0 until len.toInt()) {
                if (i + k >= n) break
                val env = 1.0 - k / len
                out[i + k] += amp * env * rnd.n()
            }
        }
        return out
    }

    /** همهمهٔ چندنفره: چند صدای هم‌زمان با F0های متفاوت */
    private fun babble(dur: Double, gain: Double = 1.0, seed: Long = 21): DoubleArray {
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        for (k in 0 until 5) {
            val f0k = 95.0 + 55.0 * k
            val (v, _) = voice(dur, { f0k * (1.0 + 0.1 * sin(2 * PI * 0.4 * it)) }, 20.0, 4.5 + k, 0.5, seed + k)
            for (i in 0 until n) out[i] += v[i]
        }
        for (i in 0 until n) out[i] = out[i] * gain * 0.25
        return out
    }

    /** نویز سفید یا باد (کم‌گذر) */
    private fun noise(dur: Double, gain: Double = 1.0, wind: Boolean = false, seed: Long = 31): DoubleArray {
        val rnd = Rnd(seed)
        val n = (dur * SR).toInt()
        val out = DoubleArray(n)
        if (!wind) {
            for (i in 0 until n) out[i] = gain * rnd.n() * 0.5
        } else {
            var lp = 0.0
            for (i in 0 until n) {
                lp += 0.02 * (rnd.n() * 0.5 - lp) // کم‌گذر شدید = باد
                out[i] = gain * lp * 8.0
            }
        }
        return out
    }

    // ─────────────────────────── اجرای خط لوله ───────────────────────────

    private class PipelineRun(
        val samples: List<PitchSample>,
        val raws: List<RawPitchFrame>,
        val totalMs: Long
    )

    /** مسیر کامل فایل: preprocess → ENHANCED → gate → stabilizer */
    private fun runFilePipeline(audio: DoubleArray, hopMs: Double = 5.0): PipelineRun {
        val t0 = System.nanoTime()
        val prep = Analyzer.preprocess(audio.copyOf(), SR)
        val sig = DoubleSignal(prep, SR)
        val backend = EnhancedKotlinBackend()
        val raws = backend.estimateAll(sig, hopMs)
        val samples = PitchSampleStream(backendTag = "enhanced", hopSec = hopMs / 1000.0).feedAll(raws)
        return PipelineRun(samples, raws, (System.nanoTime() - t0) / 1_000_000)
    }

    // ─────────────────────────── معیارها ───────────────────────────

    private class Metrics(
        val medianCents: Double, val p95Cents: Double,
        val grossPct: Double, val octavePct: Double,
        val falseAcceptPct: Double, val falseRejectPct: Double,
        val evalFrames: Int
    )

    /**
     * مقایسهٔ stableF0 با truth در سطح فریم.
     * فریم‌های واقعاً صدادار (truth>0 در مرکز پنجره) و فقط وقتی هر دو طرف مقدار دارند.
     */
    private fun evaluate(samples: List<PitchSample>, truth: DoubleArray, f0f: (Double) -> Double): Metrics {
        val errors = ArrayList<Double>()
        var voicedTruth = 0
        var falseReject = 0
        var unvoicedTruth = 0
        var falseAccept = 0
        for (s in samples) {
            val idx = (s.timestampSec * SR).toInt().coerceIn(0, truth.size - 1)
            // ناحیهٔ حقیقت: آیا در این لحظه صدا هست؟ (با tolerance 25ms)
            val a = max(0, idx - (0.025 * SR).toInt())
            val b = min(truth.size, idx + (0.025 * SR).toInt())
            var tv = 0.0
            var cnt = 0
            for (k in a until b) if (truth[k] > 0) { tv += truth[k]; cnt++ }
            val truthVoiced = cnt > (b - a) / 2
            if (truthVoiced) {
                voicedTruth++
                if (s.stableF0Hz > 0) {
                    errors.add(abs(PitchMath.cents(s.stableF0Hz, tv / cnt)))
                } else falseReject++
            } else {
                unvoicedTruth++
                if (s.stableF0Hz > 0) falseAccept++
            }
        }
        val sorted = errors.sorted()
        fun pct(p: Double): Double =
            if (sorted.isEmpty()) Double.NaN
            else sorted[min(sorted.size - 1, (p / 100.0 * sorted.size).toInt())]
        val gross = if (sorted.isEmpty()) 0.0 else 100.0 * sorted.count { it > 50.0 } / sorted.size
        val octave = if (sorted.isEmpty()) 0.0 else 100.0 * sorted.count { abs(it - 1200.0) < 80.0 || abs(it + 1200.0) < 80.0 } / sorted.size
        return Metrics(
            medianCents = if (sorted.isEmpty()) 0.0 else pct(50.0),
            p95Cents = pct(95.0),
            grossPct = gross,
            octavePct = octave,
            falseAcceptPct = if (unvoicedTruth > 0) 100.0 * falseAccept / unvoicedTruth else 0.0,
            falseRejectPct = if (voicedTruth > 0) 100.0 * falseReject / voicedTruth else 0.0,
            evalFrames = sorted.size
        )
    }

    /** حفظ تحریر: دامنهٔ ارتعاش (peak-to-peak سنت) خروجی ÷ truth */
    private fun tahrirPreservation(samples: List<PitchSample>, truth: DoubleArray): Double {
        val errT = ArrayList<Double>()
        val errR = ArrayList<Double>()
        for (s in samples) {
            if (s.stableF0Hz <= 0) continue
            val idx = (s.timestampSec * SR).toInt().coerceIn(0, truth.size - 1)
            if (truth[idx] <= 0) continue
            errT.add(PitchMath.cents(truth[idx], 220.0)) // مرجع دلخواه ثابت
            errR.add(PitchMath.cents(s.stableF0Hz, 220.0))
        }
        if (errT.size < 20) return Double.NaN
        fun range(v: List<Double>): Double {
            val s = v.sorted()
            val lo = s[(0.05 * s.size).toInt()]
            val hi = s[min(s.size - 1, (0.95 * s.size).toInt())]
            return hi - lo
        }
        val rt = range(errT); val rr = range(errR)
        return if (rt < 1.0) Double.NaN else rr / rt
    }

    // ─────────────────────────── سناریوها ───────────────────────────

    private fun scenarioCleanRecitation() {
        println("▸ ۱) تلاوت تمیز (پیوسته با vibrato)")
        val (audio, truth) = voice(6.0, { t ->
            // منحنی سادهٔ تلاوت: چند نت با گذار نرم
            val seg = (t / 1.2).toInt()
            val base = doubleArrayOf(196.0, 220.0, 247.0, 220.0, 196.0, 175.0)[seg % 6]
            base * (1.0 + 0.02 * sin(2 * PI * 0.8 * t))
        })
        val run = runFilePipeline(audio)
        val m = evaluate(run.samples, truth) { _ -> 0.0 }
        println("     میانه=${fmt(m.medianCents)}c · p95=${fmt(m.p95Cents)}c · فاحش=${fmt(m.grossPct)}٪ · اکتاو=${fmt(m.octavePct)}٪ · رد=${fmt(m.falseRejectPct)}٪")
        check(m.medianCents < 25.0, "میانهٔ خطای سنت", "${fmt(m.medianCents)}c < 25c")
        check(m.p95Cents < 80.0, "p95 خطای سنت", "${fmt(m.p95Cents)}c < 80c")
        check(m.octavePct < 1.0, "نرخ خطای اکتاو", "${fmt(m.octavePct)}٪ < 1٪")
    }

    private fun scenarioWeakStrong() {
        println("▸ ۲) صدای ضعیف و قوی — بلندی نباید F0 را عوض کند")
        val f0f: (Double) -> Double = { t -> 220.0 * (1.0 + 0.01 * sin(2 * PI * 0.5 * t)) }
        val (loud, truth) = voice(4.0, f0f, gain = 1.0, seed = 5)
        val (weak, _) = voice(4.0, f0f, gain = 0.06, seed = 5)
        val rl = runFilePipeline(loud)
        val rw = runFilePipeline(weak)
        val ml = evaluate(rl.samples, truth) { 0.0 }
        val mw = evaluate(rw.samples, truth) { 0.0 }
        println("     قوی: میانه=${fmt(ml.medianCents)}c · ضعیف: میانه=${fmt(mw.medianCents)}c")
        check(ml.medianCents < 20.0, "دقت در بلندی کامل", "${fmt(ml.medianCents)}c")
        check(mw.medianCents < 35.0, "دقت در صدای ضعیف", "${fmt(mw.medianCents)}c")
        // مقایسهٔ مستقیم: اختلاف F0 تثبیت‌شده بین دو گین در فریم‌های مشترک
        var diffSum = 0.0; var diffCnt = 0
        for (i in 0 until min(rl.samples.size, rw.samples.size)) {
            val a = rl.samples[i]; val b = rw.samples[i]
            if (a.stableF0Hz > 0 && b.stableF0Hz > 0) {
                diffSum += abs(PitchMath.cents(a.stableF0Hz, b.stableF0Hz)); diffCnt++
            }
        }
        val meanDiff = if (diffCnt > 0) diffSum / diffCnt else Double.NaN
        check(meanDiff < 25.0, "استقلال F0 از بلندی", "میانگین اختلاف ${fmt(meanDiff)}c در $diffCnt فریم")

        // مسیر خام (بدون preprocess): همان سیگنال با دو گین باید F0 یکسان بدهد
        val backendL = EnhancedKotlinBackend()
        val backendW = EnhancedKotlinBackend()
        val sigL = DoubleSignal(loud, SR)
        val sigW = DoubleSignal(weak, SR)
        var rawDiff = 0.0; var rawCnt = 0
        val hopS = (0.055 * SR).toInt()
        var pos = 0
        while (pos + (0.055 * SR).toInt() < min(loud.size, weak.size)) {
            val a = backendL.estimateFrame(sigL, pos, pos.toDouble() / SR)
            val b = backendW.estimateFrame(sigW, pos, pos.toDouble() / SR)
            if (a.f0Hz > 0 && b.f0Hz > 0) {
                rawDiff += abs(PitchMath.cents(a.f0Hz, b.f0Hz)); rawCnt++
            }
            pos += hopS
        }
        val meanRaw = if (rawCnt > 0) rawDiff / rawCnt else Double.NaN
        println("     مسیر خام (گین ۱ در برابر ۰٫۰۶): میانگین اختلاف ${fmt(meanRaw)}c در $rawCnt فریم")
        check(meanRaw < 15.0, "استقلال F0 از بلندی در مسیر خام", "${fmt(meanRaw)}c < 15c")
    }

    private fun scenarioSuddenGain() {
        println("▸ ۳) افزایش/کاهش ناگهانی شدت صدا")
        val (audio, truth) = voice(5.0, { 233.0 })
        // پرش‌های ناگهانی گین
        val n = audio.size
        for (i in 0 until n) {
            val t = i.toDouble() / SR
            val g = when {
                t < 1.0 -> 1.0
                t < 1.05 -> 6.0   // جهش ناگهانی
                t < 2.5 -> 1.2
                t < 2.55 -> 0.08 // افت ناگهانی
                t < 4.0 -> 1.0
                else -> 2.5
            }
            audio[i] *= g
        }
        val run = runFilePipeline(audio)
        val m = evaluate(run.samples, truth) { 0.0 }
        println("     میانه=${fmt(m.medianCents)}c · p95=${fmt(m.p95Cents)}c · اکتاو=${fmt(m.octavePct)}٪")
        check(m.octavePct == 0.0, "بدون پرش اکتاوی با جهش گین", "${fmt(m.octavePct)}٪")
        check(m.medianCents < 25.0, "دقت زیر جهش گین", "${fmt(m.medianCents)}c")
    }

    private fun scenarioApplause() {
        println("▸ ۴) تشویق — نباید پرش کاذب نمودار بسازد")
        val audio = applause(5.0)
        val run = runFilePipeline(audio)
        val voicedFrames = run.samples.count { it.stableF0Hz > 0 }
        val pct = 100.0 * voicedFrames / max(1, run.samples.size)
        println("     فریم‌های دارای Pitch: ${voicedFrames}/${run.samples.size} = ${fmt(pct)}٪")
        check(pct < 5.0, "پذیرش اشتباه تشویق", "${fmt(pct)}٪ < 5٪")
    }

    private fun scenarioBabble() {
        println("▸ ۵) همهمهٔ چند نفر")
        val audio = babble(5.0)
        val run = runFilePipeline(audio)
        val voicedFrames = run.samples.count { it.stableF0Hz > 0 }
        val pct = 100.0 * voicedFrames / max(1, run.samples.size)
        println("     فریم‌های دارای Pitch: ${fmt(pct)}٪")
        check(pct < 35.0, "محدودسازی پذیرش همهمه", "${fmt(pct)}٪ < 35٪ (همهمه سخت است؛ نباید نمودار پر شود)")
    }

    private fun scenarioNoise() {
        println("▸ ۶) نویز ثابت و متغیر")
        val white = noise(4.0, 0.5, wind = false)
        val rw = runFilePipeline(white)
        val pw = 100.0 * rw.samples.count { it.stableF0Hz > 0 } / max(1, rw.samples.size)
        val wind = noise(4.0, 1.0, wind = true)
        val rd = runFilePipeline(wind)
        val pd = 100.0 * rd.samples.count { it.stableF0Hz > 0 } / max(1, rd.samples.size)
        println("     نویز سفید: ${fmt(pw)}٪ · باد: ${fmt(pd)}٪")
        check(pw < 5.0, "رد نویز سفید", "${fmt(pw)}٪ < 5٪")
        check(pd < 25.0, "رد باد (نویز کم‌گذر)", "${fmt(pd)}٪ < 25٪")
    }

    private fun scenarioSilence() {
        println("▸ ۷) سکوت")
        val audio = DoubleArray(3 * SR) // صفر دیجیتال
        val run = runFilePipeline(audio)
        val voiced = run.samples.count { it.stableF0Hz > 0 }
        check(voiced == 0, "سکوت مطلق بدون خروجی", "$voiced فریم دارای Pitch")
        val hiss = noise(3.0, 0.004, wind = false, seed = 99) // هیس بسیار ضعیف
        val rh = runFilePipeline(hiss)
        val vh = rh.samples.count { it.stableF0Hz > 0 }
        check(vh == 0, "هیس ضعیف بدون خروجی", "$vh فریم دارای Pitch")
    }

    private fun scenarioFastTahrir() {
        println("▸ ۸) تحریر سریع — دامنهٔ واقعی باید حفظ شود")
        // تحریر: vibrato عمیق و سریع (۱۱Hz، ±۹۰ سنت) — دامنهٔ واقعی بالا
        val (audio, truth) = voice(5.0, { 220.0 }, vibCents = 90.0, vibRate = 11.0, seed = 3)
        val run = runFilePipeline(audio)
        val m = evaluate(run.samples, truth) { 0.0 }
        val pres = tahrirPreservation(run.samples, truth)
        println("     میانه=${fmt(m.medianCents)}c · حفظ تحریر=${fmt(pres)} (۱=کامل)")
        check(pres > 0.55, "حفظ دامنهٔ تحریر", "${fmt(pres)} > 0.55")
        check(m.octavePct == 0.0, "بدون خطای اکتاو روی تحریر", "${fmt(m.octavePct)}٪")
    }

    private fun scenarioFastNoteChange() {
        println("▸ ۹) تغییر سریع نت (پله‌های ۱۰۰ms)")
        val (audio, truth) = voice(5.0, { t ->
            val note = (t / 0.1).toInt()
            doubleArrayOf(196.0, 220.0, 247.0, 262.0, 247.0, 220.0)[note % 6]
        }, vibCents = 8.0, seed = 13)
        val run = runFilePipeline(audio)
        val m = evaluate(run.samples, truth) { 0.0 }
        println("     میانه=${fmt(m.medianCents)}c · p95=${fmt(m.p95Cents)}c · رد=${fmt(m.falseRejectPct)}٪")
        check(m.medianCents < 35.0, "دقت روی پله‌های سریع", "${fmt(m.medianCents)}c < 35c")
        check(m.falseRejectPct < 25.0, "بدون ردِ نت‌های سریع", "${fmt(m.falseRejectPct)}٪ < 25٪")
    }

    private fun scenarioOctaveError() {
        println("▸ ۱۰) خطای اکتاو — تزریق خطای عمدی در سطح فریم خام")
        // فریم‌های خام: مسیر ۲۲۰Hz با چند فریم دارای خطای اکتاو (۴۴۰/۱۱۰)
        val raws = ArrayList<PitchStabilizer.FrameIn>()
        val truthF0 = 220.0
        for (i in 0 until 200) {
            val t = i * 0.01
            val f0 = when (i) {
                50, 51 -> 440.0     // خطای اکتاو بالا (۲ فریمی — نباید بماند چون برمی‌گردیم)
                90 -> 110.0         // خطای اکتاو پایین تک‌فریمی
                130 -> 440.0        // تک‌فریمی
                else -> truthF0 * (1.0 + 0.002 * sin(2 * PI * 3.0 * t))
            }
            val isBad = f0 > 300.0 || f0 < 150.0
            raws.add(PitchStabilizer.FrameIn(t, f0, FrameStatus.VALID, 0.8, 0.1))
        }
        val stabilized = PitchStabilizer().process(raws)
        var octaveLeft = 0
        var errSum = 0.0; var errCnt = 0
        for (s in stabilized) {
            if (s.stableF0Hz <= 0) continue
            val e = abs(PitchMath.cents(s.stableF0Hz, truthF0))
            if (abs(e - 1200.0) < 100.0) octaveLeft++
            errSum += min(e, 300.0); errCnt++
        }
        println("     فریم‌های اکتاوی باقی‌مانده: $octaveLeft · میانگین خطا: ${fmt(errSum / max(1, errCnt))}c")
        check(octaveLeft == 0, "اصلاح خطای اکتاو تزریقی", "$octaveLeft فریم اکتاوی ماند")
    }

    private fun scenarioLongFile() {
        println("▸ ۱۱) فایل طولانی (۹۰ ثانیه) — حافظه/زمان معقول")
        val (audio, truth) = voice(90.0, { t ->
            180.0 + 60.0 * sin(2 * PI * 0.05 * t)
        }, vibCents = 15.0, seed = 17)
        val run = runFilePipeline(audio, hopMs = 10.0)
        val m = evaluate(run.samples, truth) { 0.0 }
        val rtMb = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) / (1024 * 1024) }
        println("     ${run.samples.size} نمونه · زمان=${run.totalMs}ms · میانه=${fmt(m.medianCents)}c · رم≈${rtMb}MB")
        check(run.totalMs < 25_000, "زمان تحلیل ۹۰ ثانیه", "${run.totalMs}ms < 25000ms")
        check(m.medianCents < 25.0, "دقت روی فایل بلند", "${fmt(m.medianCents)}c")
    }

    private fun scenarioLivePerf() {
        println("▸ ۱۲) عملکرد زنده — تأخیر پردازش هر فریم (P50/P95)")
        // شبیه‌سازی حلقهٔ زنده: هر ۱۰ms یک فریم از یک جریان پیوسته
        val (audio, _) = voice(20.0, { t -> 200.0 + 30.0 * sin(2 * PI * 0.3 * t) }, seed = 23)
        val backend = EnhancedKotlinBackend()
        val stream = PitchSampleStream(backendTag = "enhanced")
        val sig = DoubleSignal(Analyzer.preprocess(audio.copyOf(), SR), SR)
        val latencies = ArrayList<Double>()
        val hop = (0.010 * SR).toInt()
        val win = (0.055 * SR).toInt()
        var i = 0
        while (i + win < sig.size) {
            val t0 = System.nanoTime()
            val raw = backend.estimateFrame(sig, i, i.toDouble() / SR)
            stream.feed(raw)
            latencies.add((System.nanoTime() - t0) / 1e6)
            i += hop
        }
        latencies.sort()
        val p50 = latencies[latencies.size / 2]
        val p95 = latencies[min(latencies.size - 1, (latencies.size * 0.95).toInt())]
        println("     ${latencies.size} فریم · P50=${fmt(p50)}ms · P95=${fmt(p95)}ms")
        check(p50 < 30.0, "P50 تأخیر فریم", "${fmt(p50)}ms < 30ms (اجرا روی این CPU؛ گوشی جدا گزارش می‌شود)")
        check(p95 < 80.0, "P95 تأخیر فریم", "${fmt(p95)}ms < 80ms")
    }

    private fun fmt(v: Double): String =
        if (v.isNaN()) "—" else String.format(java.util.Locale.US, "%.2f", v)

    @JvmStatic
    fun main(args: Array<String>) {
        println("═══ آزمون موتور Pitch V1 — سناریوها و معیارها ═══")
        scenarioCleanRecitation()
        scenarioWeakStrong()
        scenarioSuddenGain()
        scenarioApplause()
        scenarioBabble()
        scenarioNoise()
        scenarioSilence()
        scenarioFastTahrir()
        scenarioFastNoteChange()
        scenarioOctaveError()
        scenarioLongFile()
        scenarioLivePerf()
        println()
        if (failures == 0) println("🎉 همهٔ $checks بررسی موفق")
        else println("❌ $failures از $checks بررسی شکست خورد")
        if (failures > 0) System.exit(1)
    }
}
