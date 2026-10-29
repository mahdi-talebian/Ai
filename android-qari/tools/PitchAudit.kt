package ir.hamyareqari.tools

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.DoubleSignal
import ir.hamyareqari.dsp.Harmonics
import ir.hamyareqari.dsp.PitchEngine
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.PitchTrack
import ir.hamyareqari.dsp.PitchTracker
import ir.hamyareqari.dsp.Segmenter
import ir.hamyareqari.dsp.ShortSignal
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * بازبینی مستقل موتور F0 (مرحلهٔ ۲ کار).
 *
 * این ابزار عمداً «بازبین» است نه «بنچمارک محصول»:
 *  • معیار پوشش و دقت را هم‌زمان و جدا گزارش می‌کند تا معلوم شود بهبود دقت
 *    از راه حذف فریم‌های سخت به دست نیامده است.
 *  • «خطای جریمه‌دار» را هم می‌دهد: فریم‌های ردشده با خطای ۱۲۰۰ سنت (بدترین
 *    خطای معنادار) شمرده می‌شوند؛ پس اگر موتور فقط فریم‌های سخت را حذف کند،
 *    این عدد فاش می‌کند.
 *  • خطای شروع/پایان را بدون «ناحیهٔ کور» ۲۸ms اندازه می‌گیرد (روش بنچمارک
 *    با halfWin جست‌وجو می‌کند و کف خطا دارد).
 *  • سناریوهای همهمه/گفتار/تشویق/اکو/نویز متغیر را جدا می‌سازد.
 *  • بررسی می‌کند استخراج نت، منحنی F0 خام را دست می‌زند یا نه.
 *
 * اجرا (JVM، بدون اندروید):
 *   ir.hamyareqari.tools.PitchAudit [--coverage] [--boundary] [--mixture]
 *                                   [--tahrir] [--memory] [--all]
 */
object PitchAudit {

    // ───────────────────────────── اجرای موتورها ─────────────────────────────

    private class Run(val t: DoubleArray, val f0: DoubleArray, val ms: Long)

    private fun runLegacy(c: PitchBench.Case): Run {
        val prep = Analyzer.preprocess(c.audio, c.sr)
        val sig = DoubleSignal(prep, c.sr)
        val t0 = System.nanoTime()
        val track = PitchTracker.track(sig, 5.0)
        val corr = Harmonics.correctRuns(track, sig, 55.0, 1200.0)
        return Run(track.t, corr.f0, (System.nanoTime() - t0) / 1_000_000)
    }

    private fun runEnhanced(c: PitchBench.Case): Run {
        val prep = Analyzer.preprocess(c.audio, c.sr)
        val sig = DoubleSignal(prep, c.sr)
        val t0 = System.nanoTime()
        val curve = PitchEngine.analyze(sig, hopMs = 5.0)
        return Run(curve.t, curve.f0, (System.nanoTime() - t0) / 1_000_000)
    }

    private fun runEnhancedCurve(c: PitchBench.Case): ir.hamyareqari.dsp.PitchCurve {
        val prep = Analyzer.preprocess(c.audio, c.sr)
        return PitchEngine.analyze(DoubleSignal(prep, c.sr), hopMs = 5.0)
    }

    private fun pct(v: Double): String =
        if (v.isNaN()) "  —  " else String.format(java.util.Locale.US, "%.1f%%", v * 100.0)

    private fun f(v: Double, d: Int = 1): String =
        if (v.isNaN()) "  —  " else String.format(java.util.Locale.US, "%." + d + "f", v)

    // ─────────────────────── سنجهٔ پوشش + دقت (هم‌زمان) ───────────────────────

    private class Cov(
        val name: String,
        val engine: String,
        val gtFrames: Int,
        val accepted: Int,
        val medCents: Double,
        val p95Cents: Double,
        val p95Penalized: Double,
        val acc50: Double,
        val unvoicedFrames: Int,
        val falseAccept: Double,
        val notes: Int,
        val sungSec: Double,
        val ms: Long
    ) {
        val coverage: Double get() = if (gtFrames > 0) accepted.toDouble() / gtFrames else Double.NaN
    }

    /** نیم‌پنجرهٔ داوری: همان ۲۸ms بنچمارک، تا نتایج قابل مقایسه بمانند */
    private fun evaluateCoverage(c: PitchBench.Case, engine: String, r: Run): Cov {
        val sr = c.sr
        val halfWin = (0.028 * sr).toInt()
        val errs = ArrayList<Double>()
        val errsPen = ArrayList<Double>()
        var gt = 0
        var acc = 0
        var unvoiced = 0
        var fa = 0
        var acc50 = 0
        for (k in r.t.indices) {
            val cc = (r.t[k] * sr).roundToInt()
            if (cc - halfWin < 0 || cc + halfWin >= c.truth.size) continue
            var allVoiced = true
            var anyVoiced = false
            var x = cc - halfWin
            while (x <= cc + halfWin) {
                if (c.truth[x] > 0.0) anyVoiced = true else allVoiced = false
                x += 4
            }
            if (!anyVoiced) {
                unvoiced++
                if (r.f0[k] > 0.0) fa++
                continue
            }
            if (!allVoiced) continue
            gt++
            if (r.f0[k] <= 0.0) { errsPen.add(1200.0); continue }
            acc++
            val e = abs(PitchMath.cents(r.f0[k], c.truth[cc]))
            errs.add(e); errsPen.add(e)
            if (e <= 50.0) acc50++
        }
        val track = PitchTrack(1, sr, r.t, r.f0,
            DoubleArray(r.f0.size) { if (r.f0[it] > 0.0) 0.8 else 0.0 },
            DoubleArray(r.f0.size) { 0.05 })
        val notes = Segmenter.segment(track)
        return Cov(
            c.name, engine, gt, acc,
            if (errs.isEmpty()) Double.NaN else PitchBench.median(errs.toDoubleArray()),
            if (errs.isEmpty()) Double.NaN else PitchBench.percentile(errs.toDoubleArray(), 95.0),
            if (errsPen.isEmpty()) Double.NaN else PitchBench.percentile(errsPen.toDoubleArray(), 95.0),
            if (acc > 0) acc50.toDouble() / acc else Double.NaN,
            unvoiced, if (unvoiced > 0) fa.toDouble() / unvoiced else 0.0,
            notes.size, Segmenter.sungSeconds(notes), r.ms
        )
    }

    private fun coverageSection() {
        println("═══ ۲) پوشش و دقت هم‌زمان (فریم‌های ردشده از پوشش حذف نمی‌شوند) ═══")
        println("ستون «جریمه‌دار»: صدک۹۵ خطا اگر هر فریم ردشده ۱۲۰۰ سنت خطا شمرده شود (بدترین حالت).")
        println("ستون «≤۵۰سنت»: سهم فریم‌های پذیرفته‌شده‌ای که خطایشان ≤۵۰ سنت است.\n")
        println(String.format(java.util.Locale.US,
            "%-22s %-9s %8s %8s %8s %9s %7s %9s %8s %6s %7s",
            "نمونه", "موتور", "پوشش%", "میانه", "p95", "جریمه‌دار", "≤۵۰سنت", "پذیرش%", "نت", "آواز", "زمانms"))
        val pool = HashMap<String, MutableList<Cov>>()
        for (c in PitchBench.buildCases()) {
            for (eng in listOf("legacy", "enhanced")) {
                val r = if (eng == "legacy") runLegacy(c) else runEnhanced(c)
                val m = evaluateCoverage(c, eng, r)
                pool.getOrPut(eng) { ArrayList() }.add(m)
                println(String.format(java.util.Locale.US,
                    "%-22s %-9s %8s %8s %8s %9s %7s %9s %6d %7s %7d",
                    m.name, m.engine, pct(m.coverage), f(m.medCents, 2), f(m.p95Cents, 1),
                    f(m.p95Penalized, 0), pct(m.acc50), pct(m.falseAccept),
                    m.notes, f(m.sungSec, 1), m.ms))
            }
        }
        println()
        println("── جمع‌بندی تجمعی (همهٔ نمونه‌های صدادار، شمارش فریم به فریم) ──")
        for (eng in listOf("legacy", "enhanced")) {
            val ms = pool[eng]!!.filter { it.gtFrames > 200 }
            var g = 0; var a = 0; var fa = 0; var uv = 0
            for (m in ms) {
                g += m.gtFrames; a += m.accepted; uv += m.unvoicedFrames
                if (!m.falseAccept.isNaN()) fa += (m.falseAccept * m.unvoicedFrames).roundToInt()
            }
            val cov = if (g > 0) a.toDouble() / g else Double.NaN
            println("  $eng: پوشش تجمعی=${pct(cov)} ($a/$g فریم) · پذیرش اشتباه تجمعی=${pct(if (uv > 0) fa.toDouble() / uv else Double.NaN)} ($fa/$uv)")
        }
        println("\nتوجه: صحت‌سنجی فقط روی فریم‌های پذیرفته‌شده است؛ «پوشش%» می‌گوید چند فریمِ واقعاً صدادار پذیرفته شد.")
    }

    // ─────────────────── ۳) سناریوهای همهمه/گفتار/تشویق/اکو/نویز ───────────────────

    private fun voiceMixCase(
        name: String,
        group: String,
        dur: Double,
        seed: Long,
        baseF0: Double,
        depthCents: Double,
        interferer: DoubleArray,
        ifToVoiceDb: Double,
        echo: Boolean = false
    ): PitchBench.Case {
        val rnd = PitchBench.Rnd(seed)
        val (v, t) = PitchBench.voice(dur, PitchBench.VoiceParams(vibDepthCents = depthCents),
            { x -> baseF0 * 2.0.pow((55.0 * sin(2 * Math.PI * 0.5 * x)) / 1200.0) }, rnd)
        var sum = 0.0
        var cnt = 0
        for (i in v.indices) if (t[i] > 0.0) { sum += v[i] * v[i]; cnt++ }
        val voiceRms = sqrt(sum / max(1, cnt))
        val g = voiceRms / Math.pow(10.0, ifToVoiceDb / 20.0)
        var x = v.copyOf()
        for (i in x.indices) x[i] += interferer[i] * g
        if (echo) x = PitchBench.addEcho(x, intArrayOf(45, 90, 180), doubleArrayOf(0.70, 0.50, 0.35))
        return PitchBench.Case(name, group, PitchBench.SR, x, t, dur, depthCents)
    }

    private fun noiseOnly(name: String, group: String, x: DoubleArray): PitchBench.Case =
        PitchBench.Case(name, group, PitchBench.SR, x, DoubleArray(x.size), 0.0)

    private fun mixtureCases(): List<PitchBench.Case> {
        val out = ArrayList<PitchBench.Case>()
        // گفتار یک‌نفره بدون تلاوت (۷ و ۹ هرتز هجا نیست؛ همان همهمه با یک گوینده)
        out.add(noiseOnly("speech_1spk_a", "speech", PitchBench.babble(6.0, 0.07, PitchBench.Rnd(211), 1)))
        out.add(noiseOnly("speech_1spk_b", "speech", PitchBench.babble(6.0, 0.07, PitchBench.Rnd(223), 1)))
        out.add(noiseOnly("speech_3spk", "speech", PitchBench.babble(6.0, 0.07, PitchBench.Rnd(227), 3)))
        out.add(noiseOnly("applause_only", "applause", PitchBench.applause(6.0, 0.08, PitchBench.Rnd(229))))
        out.add(noiseOnly("wind_only", "noise", PitchBench.wind(6.0, 0.10, PitchBench.Rnd(231))))
        // تلاوت + همهمه در سه نسبت بلندی
        val babble6 = PitchBench.babble(6.0, 0.07, PitchBench.Rnd(233), 4)
        out.add(voiceMixCase("voice_babble_+6dB", "mixed", 6.0, 301, 300.0, 40.0, babble6, +6.0))
        out.add(voiceMixCase("voice_babble_0dB", "mixed", 6.0, 303, 300.0, 40.0, babble6, 0.0))
        out.add(voiceMixCase("voice_babble_-6dB", "mixed", 6.0, 305, 300.0, 40.0, babble6, -6.0))
        // تلاوت + تشویق
        out.add(voiceMixCase("voice_applause_0dB", "mixed", 6.0, 307, 280.0, 35.0,
            PitchBench.applause(6.0, 0.08, PitchBench.Rnd(235)), 0.0))
        // تلاوت + نویز متغیر (۰٫۲ تا ۱ برابر)
        val nVar = PitchBench.whiteNoise(6.0, 0.06, PitchBench.Rnd(237))
        for (i in nVar.indices) {
            val m = 0.2 + 0.8 * (0.5 + 0.5 * sin(2 * Math.PI * 0.27 * i / PitchBench.SR))
            nVar[i] *= m
        }
        out.add(voiceMixCase("voice_noise_var", "mixed", 6.0, 309, 320.0, 45.0, nVar, 0.0))
        // تلاوت + اکوی قوی
        out.add(voiceMixCase("voice_echo_hard", "echo", 6.0, 311, 294.0, 35.0, DoubleArray((6.0 * PitchBench.SR).toInt()), 0.0, echo = true))
        // تلاوت + باد
        out.add(voiceMixCase("voice_wind", "mixed", 6.0, 313, 300.0, 40.0, PitchBench.wind(6.0, 0.10, PitchBench.Rnd(239)), 0.0))
        return out
    }

    private fun mixtureSection() {
        println("\n═══ ۳) همهمه / گفتار / تشویق / اکو / نویز متغیر (مصنوعی) ═══")
        println(String.format(java.util.Locale.US,
            "%-22s %-9s %8s %8s %8s %9s %8s %6s %7s",
            "نمونه", "موتور", "پوشش%", "میانه", "p95", "پذیرش%", "جریمه‌دار", "نت", "آواز"))
        for (c in mixtureCases()) {
            for (eng in listOf("legacy", "enhanced")) {
                val r = if (eng == "legacy") runLegacy(c) else runEnhanced(c)
                val m = evaluateCoverage(c, eng, r)
                println(String.format(java.util.Locale.US,
                    "%-22s %-9s %8s %8s %8s %9s %8s %6d %7s",
                    m.name, m.engine, pct(m.coverage), f(m.medCents, 2), f(m.p95Cents, 1),
                    pct(m.falseAccept), f(m.p95Penalized, 0), m.notes, f(m.sungSec, 1)))
            }
        }
        println("\n«پوشش%» فقط برای نمونه‌های دارای تلاوت معنا دارد؛ در نمونه‌های بدون تلاوت «—» است.")
        println("هدف در حالت «تلاوت + همهمه» حذف نکردن کل فریم‌هاست، بلکه تخمین F0 قاری است.")
    }

    // ───────────────────── ۴) مرز بخش‌های معتبر (بدون ناحیهٔ کور) ─────────────────────

    private class Bnd(val name: String, val engine: String, val onsetMs: Double, val offsetMs: Double,
                      val onsetP95: Double, val matched: Int, val gtSegs: Int, val holes: Int)

    /** بازه‌های صدادار از یک آرایهٔ f0 (شکاف <۲ فریم به هم چسبیده می‌شود) */
    private fun intervals(t: DoubleArray, f0: DoubleArray, minSec: Double): List<DoubleArray> {
        val merged = ArrayList<DoubleArray>() // start,end ثانیه
        var i = 0
        val n = f0.size
        while (i < n) {
            if (f0[i] <= 0.0) { i++; continue }
            var j = i
            while (j < n && f0[j] > 0.0) j++
            val s = t[i]
            val e = t[min(n - 1, j - 1)]
            if (merged.isNotEmpty() && s - merged[merged.size - 1][1] <= 0.015) {
                merged[merged.size - 1][1] = e
            } else merged.add(doubleArrayOf(s, e))
            i = j
        }
        return merged.filter { it[1] - it[0] >= minSec }
    }

    private fun gtIntervals(c: PitchBench.Case, minSec: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var i = 0
        while (i < c.truth.size) {
            if (c.truth[i] <= 0.0) { i++; continue }
            var j = i
            while (j < c.truth.size && c.truth[j] > 0.0) j++
            val s = i.toDouble() / c.sr
            val e = j.toDouble() / c.sr
            if (e - s >= minSec) out.add(doubleArrayOf(s, e))
            i = j
        }
        return out
    }

    private fun boundaryOf(c: PitchBench.Case, engine: String, r: Run): Bnd {
        val gt = gtIntervals(c, 0.15)
        val det = intervals(r.t, r.f0, 0.05)
        val on = ArrayList<Double>()
        val off = ArrayList<Double>()
        var matched = 0
        var holes = 0
        for (g in gt) {
            // همهٔ بازه‌های تشخیص‌داده‌شده که با این بازهٔ واقعی هم‌پوشانی دارند:
            // شروع = زودترین شروع، پایان = دیرترین پایان (سوراخ‌های داخلی جدا
            // شمرده می‌شوند، نه اینکه خطای مرز را بزرگ کنند).
            var first = Double.MAX_VALUE
            var last = -Double.MAX_VALUE
            var any = false
            for (d in det) {
                val ov = min(g[1], d[1]) - max(g[0], d[0])
                if (ov <= 0.0) continue
                any = true
                if (d[0] < first) first = d[0]
                if (d[1] > last) last = d[1]
            }
            if (any) {
                matched++
                on.add(abs(first - g[0]) * 1000.0)
                off.add(abs(last - g[1]) * 1000.0)
                // سوراخ داخلی: شکاف > ۲۰ms بین بازه‌های تشخیص‌داده‌شده داخل این بازه
                val inside = det.filter { it[0] >= g[0] - 0.05 && it[1] <= g[1] + 0.05 }.sortedBy { it[0] }
                for (k in 1 until inside.size) {
                    if (inside[k][0] - inside[k - 1][1] > 0.020) holes++
                }
            }
        }
        return Bnd(
            c.name, engine,
            if (on.isEmpty()) Double.NaN else PitchBench.median(on.toDoubleArray()),
            if (off.isEmpty()) Double.NaN else PitchBench.median(off.toDoubleArray()),
            if (on.isEmpty()) Double.NaN else PitchBench.percentile(on.toDoubleArray(), 95.0),
            matched, gt.size, holes
        )
    }

    private fun boundarySection() {
        println("\n═══ ۴) خطای مرز بخش‌های معتبر (بدون ناحیهٔ کور ۲۸ms) ═══")
        println("روش: بازه‌های صدادار واقعی با بازه‌های f0>0 تطبیق داده می‌شوند (زودترین شروع/دیرترین پایان).")
        println("«سوراخ» = شکاف >۲۰ms داخل یک بازهٔ واقعی (نشانهٔ دورریختن موضعی فریم).")
        println(String.format(java.util.Locale.US,
            "%-22s %-9s %9s %9s %9s %8s %7s",
            "نمونه", "موتور", "شروعms", "پایانms", "شروعp95", "تطبیق", "سوراخ"))
        for (c in PitchBench.buildCases()) {
            if (c.expectedVoiceSec < 0.5) continue
            for (eng in listOf("legacy", "enhanced")) {
                val r = if (eng == "legacy") runLegacy(c) else runEnhanced(c)
                val b = boundaryOf(c, eng, r)
                println(String.format(java.util.Locale.US,
                    "%-22s %-9s %9s %9s %9s %5d/%-3d %5d",
                    b.name, b.engine, f(b.onsetMs, 0), f(b.offsetMs, 0), f(b.onsetP95, 0),
                    b.matched, b.gtSegs, b.holes))
            }
        }
    }

    // ─────────────────── ۵) تحریر: مسیر F0 در برابر مسیر نت ───────────────────

    /** نسخهٔ محلیِ الگوریتم Segmenter با آستانهٔ ادامهٔ قابل‌تنظیم — برای سنجش «چه می‌شد اگر» */
    private fun segmentVariant(track: PitchTrack, minDuration: Double, stabilityCents: Double,
                               mergeGap: Double, mergeCents: Double): List<DoubleArray> {
        val n = track.size
        if (n == 0) return emptyList()
        val times = track.t
        val freqs = track.f0
        val conf = track.clarity
        val dt = if (n > 1) PitchMath.median(DoubleArray(n - 1) { times[it + 1] - times[it] }) else 0.01
        val contCents = stabilityCents * 1.6
        val raw = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (!(freqs[i] > 0.0)) { i++; continue }
            var j = i
            val vals = ArrayList<Double>()
            val weights = ArrayList<Double>()
            vals.add(freqs[i]); weights.add(max(conf[i], 1e-3))
            while (j + 1 < n && freqs[j + 1] > 0.0) {
                val lo = max(0, vals.size - 25)
                var num = 0.0; var den = 0.0
                for (q in lo until vals.size) { num += vals[q] * weights[q]; den += weights[q] }
                val refF = if (den > 0) num / den else vals[vals.size - 1]
                if (abs(PitchMath.cents(freqs[j + 1], refF)) > contCents) break
                j++
                vals.add(freqs[j]); weights.add(max(conf[j], 1e-3))
            }
            raw.add(intArrayOf(i, j))
            i = j + 1
        }
        val merged = ArrayList<IntArray>()
        for (seg in raw) {
            if (merged.isEmpty()) { merged.add(seg.copyOf()); continue }
            val prev = merged[merged.size - 1]
            val gap = times[seg[0]] - times[prev[1]]
            if (gap <= mergeGap) {
                val a = PitchMath.median(DoubleArray(prev[1] - prev[0] + 1) { freqs[prev[0] + it] })
                val b = PitchMath.median(DoubleArray(seg[1] - seg[0] + 1) { freqs[seg[0] + it] })
                if (abs(PitchMath.cents(b, a)) <= mergeCents) { prev[1] = seg[1]; continue }
            }
            merged.add(seg.copyOf())
        }
        val out = ArrayList<DoubleArray>()
        for (s in merged) {
            val duration = times[s[1]] - times[s[0]] + dt
            if (duration < minDuration) continue
            if (s[1] - s[0] + 1 < 2) continue
            out.add(doubleArrayOf(times[s[0]], times[s[1]]))
        }
        return out
    }

    /** شمار «ران»های خام (پیش از فیلتر مدت) با آستانهٔ ادامهٔ داده‌شده */
    private fun runCount(track: PitchTrack, stabilityCents: Double): Int {
        val contCents = stabilityCents * 1.6
        val n = track.size
        val freqs = track.f0
        val conf = track.clarity
        var runs = 0
        var i = 0
        while (i < n) {
            if (!(freqs[i] > 0.0)) { i++; continue }
            var j = i
            val vals = ArrayList<Double>()
            val weights = ArrayList<Double>()
            vals.add(freqs[i]); weights.add(max(conf[i], 1e-3))
            while (j + 1 < n && freqs[j + 1] > 0.0) {
                val lo = max(0, vals.size - 25)
                var num = 0.0; var den = 0.0
                for (q in lo until vals.size) { num += vals[q] * weights[q]; den += weights[q] }
                val refF = if (den > 0) num / den else vals[vals.size - 1]
                if (abs(PitchMath.cents(freqs[j + 1], refF)) > contCents) break
                j++
                vals.add(freqs[j]); weights.add(max(conf[j], 1e-3))
            }
            runs++
            i = j + 1
        }
        return runs
    }

    private fun tahrirSection() {
        println("\n═══ ۵) حفظ تحریر: مسیر F0 خام در برابر مسیر استخراج نت ═══")
        val cases = PitchBench.buildCases().filter {
            it.name in listOf("scale_clean", "tarjeeh_fast", "tarjeeh_wide", "voice_snr10", "falsetto_high")
        }
        println(String.format(java.util.Locale.US,
            "%-16s %9s %8s %8s %8s %8s %10s %9s",
            "نمونه", "محصول(۴۵)", "آستانه۷۲", "آستانه۱۲۰", "آستانه۱۹۲", "آستانه۲۵۰", "قطعه‌های۴۵", "F0ثابت"))
        for (c in cases) {
            val curve = runEnhancedCurve(c)
            val track = curve.toTrack()
            val f0Before = curve.f0.copyOf()
            val notesProduct = Segmenter.segment(track)
            // اثبات نبودِ تغییر در منحنی خام
            var same = true
            for (k in f0Before.indices) if (f0Before[k] != curve.f0[k]) { same = false; break }
            val raw45 = segmentVariant(track, 0.08, 45.0, 0.10, 55.0)
            val raw72 = segmentVariant(track, 0.08, 72.0, 0.10, 55.0)
            val raw120 = segmentVariant(track, 0.08, 120.0, 0.10, 55.0)
            val raw192 = segmentVariant(track, 0.08, 192.0, 0.10, 55.0)
            val runs45 = runCount(track, 45.0)
            println(String.format(java.util.Locale.US,
                "%-16s %9d %8d %8d %8d %8d %10d %9s",
                c.name, notesProduct.size, raw72.size, raw120.size, raw192.size,
                segmentVariant(track, 0.08, 250.0, 0.10, 55.0).size, runs45, if (same) "بله" else "خیر!"))
        }
        println("\n«محصول(۴۵)» = Segmenter فعلی محصول (آستانهٔ شروع ۴۵، ادامه ۷۲ سنت).")
        println("ستون‌های آستانه = همان الگوریتم با آستانهٔ شروع ۷۲/۱۲۰/۱۹۲/۲۵۰ سنت (آزمایشی؛ در محصول اعمال نشده).")
        println("«قطعه‌های۴۵» = شمار ران‌های خام پیش از فیلتر مدت؛ اگر بسیار بزرگ باشد یعنی تحریر تکه‌تکه شده است.")

        // عمق واقعی تحریر در منحنی در برابر واقعیت
        println("\nحفظ دامنهٔ تحریر در منحنی F0 (نسبت خروجی به واقعیت):")
        for (c in cases) {
            if (c.vibDepthCents < 10.0) continue
            val curve = runEnhancedCurve(c)
            val sr = c.sr
            val halfWin = (0.028 * sr).toInt()
            val gt = ArrayList<Double>()
            val es = ArrayList<Double>()
            for (k in curve.t.indices) {
                val cc = (curve.t[k] * sr).roundToInt()
                if (cc - halfWin < 0 || cc + halfWin >= c.truth.size) continue
                if (c.truth[cc] <= 0.0) continue
                gt.add(1200.0 * log2(c.truth[cc] / 220.0))
                es.add(if (curve.f0[k] > 0.0) 1200.0 * log2(curve.f0[k] / 220.0) else Double.NaN)
            }
            val amp = ampRatio(gt, es)
            println("  ${c.name}: دامنهٔ واقعی=${f(c.vibDepthCents, 0)} سنت · نسبت حفظ‌شده=${f(amp, 2)}")
        }
    }

    private fun ampRatio(gt: List<Double>, est: List<Double>): Double {
        fun amplitude(src: List<Double>, requireFull: Boolean): Double {
            val win = 60
            val devs = ArrayList<Double>()
            var i = 0
            while (i < src.size) {
                val j = min(src.size, i + win)
                val seg = ArrayList<Double>()
                var total = 0
                for (k in i until j) { total++; if (!src[k].isNaN()) seg.add(src[k]) }
                if (seg.size > win / 3 && (!requireFull || seg.size >= 0.8 * total)) {
                    val m = PitchBench.median(seg.toDoubleArray())
                    devs.add(1.4826 * PitchBench.median(DoubleArray(seg.size) { abs(seg[it] - m) }))
                }
                i += win / 2
            }
            if (devs.isEmpty()) return Double.NaN
            return PitchBench.median(devs.toDoubleArray())
        }
        val a = amplitude(gt, false)
        val b = amplitude(est, true)
        if (a.isNaN() || b.isNaN() || a < 1.0) return Double.NaN
        return b / a
    }

    // ───────────────────────────── ۶) حافظه و زمان ─────────────────────────────

    private fun synthPcm(durSec: Double, sr: Int): ShortArray {
        val n = (durSec * sr).toInt()
        val pcm = ShortArray(n)
        var ph = 0.0
        var rnd = 424242L
        var i = 0
        while (i < n) {
            val t = i.toDouble() / sr
            val idx = (t / 1.2).toInt()
            val f = 190.0 * 2.0.pow((idx % 10) / 12.0)
            val gap = (t / 1.2) % 1.0 > 0.9
            ph += 2 * Math.PI * f / sr
            var s = 0.0
            var h = 1
            while (h <= 8) { s += sin(ph * h) / h; h++ }
            rnd = rnd * 6364136223846793005L + 1442695040888963407L
            val noise = ((rnd ushr 40).toDouble() / (1 shl 24).toDouble() - 0.5) * 0.002
            pcm[i] = ((s * (if (gap) 0.0 else 0.35) + noise) * 32000).toInt().coerceIn(-32768, 32767).toShort()
            i++
        }
        return pcm
    }

    /**
     * سهم حافظهٔ هر بخش (بایت بر فریم) برای موتور جدید — شمارش مستقیم آرایه‌ها:
     *  ۱۱×Float(4) ویژگی + ۲×(۴ نامزد×Float) + Byte کنت‌ها + conf(4) + snr(4)
     *  + status(Byte) + f0(Double 8) + floor(8) + sorted(4) + estimated(1)
     *  + back(Viterbi، ۵×Byte) + t(8 در PitchCurve) + toTrack(clarity+rms، ۱۶)
     */
    private fun bytesPerFrame(): Int {
        val featureFloats = 11 * 4          // rms,flat,flux,clip,yinF,yinC,mpmF,mpmC,support,coverage,fundProm
        val cands = 2 * 4 * 4               // candF0 + candScore
        val small = 1 + 4 + 4 + 1           // candCnt, conf, snrFrame, status
        val doubl = 8 + 8                   // f0, floor
        val misc = 4 + 1                    // sorted, estimated
        val viterbi = 5                     // back: (MAX_CAND+1) × Byte
        val curve = 8                       // t
        val track = 16                      // clarity + rms
        return featureFloats + cands + small + doubl + misc + viterbi + curve + track
    }

    private fun memorySection(one: Double) {
        val sr = 22050
        val pcm = synthPcm(one, sr)
        val pcmMb = pcm.size * 2.0 / 1048576.0
        Analyzer.prepareShort(pcm, sr)
        val sig = ShortSignal(pcm, sr)
        val hopMs = if (one <= 180.0) 5.0 else 10.0
        val frames = max(1, ((one * sr - 992) / (hopMs / 1000.0 * sr)).toInt() + 1)
        val estMb = bytesPerFrame().toDouble() * frames / 1048576.0
        val rt = Runtime.getRuntime()
        System.gc()
        Thread.sleep(120)
        System.gc()
        val before = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        val t0 = System.nanoTime()
        val rep = Analyzer.analyzeSignal(sig, false, null, ir.hamyareqari.dsp.Engine.ENHANCED)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val after = (rt.totalMemory() - rt.freeMemory()) / 1048576.0
        var peak = 0L
        for (pool in java.lang.management.ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.type == java.lang.management.MemoryType.HEAP) peak += pool.peakUsage.used
        }
        println(String.format(java.util.Locale.US,
            "مدت=%4.0fs · هپ=%3.0fms · فریم=%6d · PCM=%6.1fMB · قبل از تحلیل=%6.1fMB · پس از تحلیل=%6.1fMB · سربار موتور=%5.1fMB (انتظار %5.1f) · peak=%6.1fMB · زمان=%6dms (%.1f× realtime)",
            one, hopMs, frames, pcmMb, before, after, after - before, estMb, peak / 1048576.0, ms, ms / 1000.0))
        println(String.format(java.util.Locale.US,
            "   نتیجه: نت=%d · گستره=%.1f · معتبر=%.1f%% · نامطمئن=%.1f%% · تخمینی=%.2f%%",
            rep.noteCount, rep.spanSemitones, rep.validShare * 100, rep.uncertainShare * 100, rep.estimatedShare * 100))
    }

    // ───────────────────────────── ورودی ─────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        val all = args.isEmpty() || args.contains("--all")
        if (all || args.contains("--coverage")) coverageSection()
        if (all || args.contains("--mixture")) mixtureSection()
        if (all || args.contains("--boundary")) boundarySection()
        if (all || args.contains("--tahrir")) tahrirSection()
        val memArgs = args.filter { it.startsWith("--duration=") }
        if (all || args.contains("--memory")) {
            if (memArgs.isEmpty()) {
                println("\n═══ ۶) حافظه و زمان اجرا ═══")
                println("برای عدد تمیز، هر مدت را در پروسهٔ جدا اجرا کنید:")
                println("  PitchAudit --memory --duration=10 | --duration=300 | --duration=1200")
                println("سرباری موتور جدید ≈ ${bytesPerFrame()} بایت بر فریم (شمارش مستقیم آرایه‌های میانی).")
            } else {
                memorySection(memArgs[0].substringAfter("=").toDouble())
            }
        }
    }
}
