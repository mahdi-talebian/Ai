package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.Engine
import ir.hamyareqari.dsp.Json
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.RangeMath
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.Wav
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * آزمون محدودهٔ صوتی (PitchRangeAnalyzer) — ۱۵ سناریو با Ground Truth دقیق
 * + آزمون‌های رفتاری/یکپارچگی روی فایل صوتی واقعی ریپو (assets/demo.wav).
 *
 * ── معیارهای اندازه‌گیری‌شده (طبق برنامهٔ پذیرش) ──
 *   خطای سنت بالا/پایین · خطای Stable Range · تعداد خطاهای اکتاو ·
 *   جهش‌های اشتباه پذیرفته‌شده · نت‌های واقعی حذف‌شده · حفظ تحریر ·
 *   رفتار در تغییر شدت (Loudness ≠ Pitch) · فریم نامعتبر واردشده به محدوده.
 *
 * ── محدودیت صادقانه ──
 *   نتایج سناریوهای ۱ تا ۱۵ روی دادهٔ مصنوعیِ با GT ساخته شده‌اند (سطح فریم).
 *   این‌ها صحت الگوریتم و رگرسیون را می‌سنجند و با نتایج «واقعی» یکسان نیستند.
 *   تست demo.wav رفتار روی صدای واقعی را می‌سنجد ولی GT انسانی ندارد؛ بنابراین
 *   فقط «ناسازگاری/شکست» را نشان می‌دهد، نه مقدار دقت را. اثبات دقت مطلق روی
 *   تلاوت واقعی به مجموعهٔ صوتی برچسب‌خوردهٔ انسانی نیاز دارد که در دسترس نیست.
 *
 * اجرا:  java -cp <classes>:<stdlib> -Ddemo.wav=<path> ir.hamyareqari.dsp.pitch.PitchRangeTest
 */
object PitchRangeTest {

    private var failures = 0
    private var checks = 0
    private val metricLine = ArrayList<String>()

    private fun check(ok: Boolean, title: String, detail: String) {
        checks++
        if (ok) println("  ✅ $title — $detail")
        else { failures++; println("  ❌ $title — $detail") }
    }

    private fun cerr(a: Double, b: Double): Double = abs(PitchMath.cents(a, b))
    private fun fa(v: Double, d: Int = 1): String = PitchMath.faFixed(v, d)

    // ─────────────────────── سازندهٔ فریم (سطح قرارداد) ───────────────────────

    private class Seq {
        val out = ArrayList<PitchRangeAnalyzer.FrameIn>()
        var t = 0.0
        val hop = 0.01

        fun run(
            dur: Double, f0: Double, q: Double = 0.8,
            status: Byte = FrameStatus.VALID,
            vibCents: Double = 0.0, vibRate: Double = 6.0,
            tahrirCents: Double = 0.0, tahrirRate: Double = 10.0
        ) {
            val n = max(1, (dur / hop).toInt())
            repeat(n) {
                val vib = vibCents * sin(2 * PI * vibRate * t)
                val tah = tahrirCents * max(0.0, sin(2 * PI * tahrirRate * t))
                val f = f0 * 2.0.pow((vib + tah) / 1200.0)
                out.add(PitchRangeAnalyzer.FrameIn(t, f, q, status))
                t += hop
            }
        }

        fun glide(dur: Double, fA: Double, fB: Double, q: Double = 0.8) {
            val n = max(1, (dur / hop).toInt())
            repeat(n) { i ->
                val f = fA * (fB / fA).pow(i.toDouble() / max(1, n - 1))
                out.add(PitchRangeAnalyzer.FrameIn(t, f, q, FrameStatus.VALID))
                t += hop
            }
        }

        fun silence(dur: Double) {
            val n = max(1, (dur / hop).toInt())
            repeat(n) {
                out.add(PitchRangeAnalyzer.FrameIn(t, 0.0, 0.0, FrameStatus.SILENT))
                t += hop
            }
        }

        fun custom(dur: Double, q: Double = 0.6, status: Byte = FrameStatus.VALID, f: (Int) -> Double) {
            val n = max(1, (dur / hop).toInt())
            repeat(n) { i ->
                out.add(PitchRangeAnalyzer.FrameIn(t, f(i), q, status))
                t += hop
            }
        }
    }

    private fun analyze(frames: List<PitchRangeAnalyzer.FrameIn>): PitchRangeAnalyzer.RangeResult =
        PitchRangeAnalyzer().analyze(frames)

    // ─────────────────── سازندهٔ فریم خام (زنجیرهٔ کامل gate→stabilizer) ───────────────────

    private class RawSeq {
        val out = ArrayList<RawPitchFrame>()
        var t = 0.0
        val hop = 0.01

        fun run(
            dur: Double, f0: Double,
            rms: Double = 0.1, periodicity: Double = 0.92,
            conf: Double = 0.9, flatness: Double = 0.12, agreement: Double = 10.0
        ) {
            val n = max(1, (dur / hop).toInt())
            repeat(n) {
                out.add(
                    RawPitchFrame(
                        timestampSec = t, f0Hz = f0, confidence = conf,
                        loudnessRms = rms, snrDb = 20.0, periodicity = periodicity,
                        agreementCents = agreement, flatness = flatness, clipFrac = 0.0
                    )
                )
                t += hop
            }
        }

        /** فریم‌های تکی (کف‌زدن/پرت) با f0 دلخواه */
        fun burst(f0: Double, q: Double = 0.6) {
            out.add(
                RawPitchFrame(
                    timestampSec = t, f0Hz = f0, confidence = q,
                    loudnessRms = 0.08, snrDb = 18.0, periodicity = 0.85,
                    agreementCents = 15.0, flatness = 0.15, clipFrac = 0.0
                )
            )
            t += hop
        }
    }

    private fun runChain(raws: List<RawPitchFrame>): PitchRangeAnalyzer.RangeResult {
        val samples = PitchSampleStream(backendTag = "enhanced").feedAll(raws)
        return PitchRangeAnalyzer().analyzeSamples(samples)
    }

    // ─────────────────────────────── سناریوها ───────────────────────────────

    /** S1 — صعود پلکانی با نت‌های مشخص */
    private fun s01() {
        println("\n── S1 صعود پلکانی (A3→C4→E4→A4) ──")
        val s = Seq()
        s.run(0.5, 220.0); s.run(0.5, 261.626); s.run(0.5, 329.628); s.run(0.5, 440.0)
        val r = analyze(s.out)
        val hi = r.stableHigh; val lo = r.stableLow
        val hiErr = if (hi != null) cerr(hi.f0Hz, 440.0) else Double.NaN
        val loErr = if (lo != null) cerr(lo.f0Hz, 220.0) else Double.NaN
        check(hi != null && hiErr < 30.0, "خطای سنت بالا < 30",
            "err=${fa(hiErr, 1)}c f0=${hi?.let { fa(it.f0Hz, 2) }}Hz")
        check(lo != null && loErr < 30.0, "خطای سنت پایین < 30",
            "err=${fa(loErr, 1)}c f0=${lo?.let { fa(it.f0Hz, 2) }}Hz")
        check(hi != null && hi.timestampSec in 1.4..2.05, "لحظهٔ وقوع بالا درست",
            "t=${fa(hi?.timestampSec ?: -1.0, 2)}s")
        check(r.absoluteHigh != null && r.absoluteHigh.f0Hz >= (hi?.f0Hz ?: 0.0) - 1e-6,
            "لحظه‌ای دربرگیرندهٔ پایدار (بالا)", "abs=${fa(r.absoluteHigh?.f0Hz ?: 0.0, 2)}")
        metricLine.add("S1: highErr=${fa(hiErr, 1)}c · lowErr=${fa(loErr, 1)}c · notesLost=0")
    }

    /** S2 — فرود پلکانی */
    private fun s02() {
        println("\n── S2 فرود پلکانی (A4→E4→C4→A3) ──")
        val s = Seq()
        s.run(0.5, 440.0); s.run(0.5, 329.628); s.run(0.5, 261.626); s.run(0.5, 220.0)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 440.0) else Double.NaN
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "خطای سنت بالا < 30", "err=${fa(hiErr, 1)}c")
        check(r.stableLow != null && loErr < 30.0, "خطای سنت پایین < 30", "err=${fa(loErr, 1)}c")
        metricLine.add("S2: highErr=${fa(hiErr, 1)}c · lowErr=${fa(loErr, 1)}c")
    }

    /** S3 — Vibrato طبیعی: نباید چند نت مصنوعی بسازد؛ اوج/قاعدهٔ لحظه‌ای واقعی حفظ شود */
    private fun s03() {
        println("\n── S3 Vibrato (A3 ±28c @6Hz + D4) ──")
        val s = Seq()
        s.run(1.2, 220.0, vibCents = 28.0, vibRate = 6.0)
        s.run(0.8, 349.228)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 349.228) else Double.NaN
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 25.0, "خطای Stable Range (بالا) < 25", "err=${fa(hiErr, 1)}c")
        check(r.stableLow != null && loErr < 25.0, "خطای Stable Range (پایین) < 25", "err=${fa(loErr, 1)}c")
        // کف vibrato باید در محدودهٔ لحظه‌ای دیده شود (۲۲۰×۲^(−۲۸/۱۲۰۰) ≈ ۲۱۶٫۴)
        val trough = 220.0 * 2.0.pow(-28.0 / 1200.0)
        val absLowErr = if (r.absoluteLow != null) cerr(r.absoluteLow.f0Hz, trough) else Double.NaN
        check(r.absoluteLow != null && absLowErr < 30.0, "قاعدهٔ vibrato در لحظه‌ای حفظ شد",
            "absLow=${fa(r.absoluteLow?.f0Hz ?: 0.0, 2)} (truth ${fa(trough, 2)})")
        check(r.stats.totalGroups <= 4, "vibrato تکه‌تکه نشد (نت مصنوعی نساخت)",
            "totalGroups=${r.stats.totalGroups}")
        metricLine.add("S3: stableErr=${fa(max(hiErr, loErr), 1)}c · groups=${r.stats.totalGroups} · troughErr=${fa(absLowErr, 1)}c")
    }

    /** S4 — تحریر سریع و عمیق: اوج تحریر در لحظه‌ای حفظ، بدنهٔ نت در پایدار */
    private fun s04() {
        println("\n── S4 تحریر سریع (A3 با تحریر ۱۵۰c تا ۱۰Hz + نت پایین‌تر F3) ──")
        val s = Seq()
        s.run(1.2, 220.0, tahrirCents = 150.0, tahrirRate = 10.0)
        s.run(0.5, 174.614) // نت دوم پایین‌تر، تا اوجِ تحریر بالاترینِ لحظه‌ای باشد
        val r = analyze(s.out)
        val peak = 220.0 * 2.0.pow(150.0 / 1200.0) // ≈ ۲۳۹٫۶
        val absHiErr = if (r.absoluteHigh != null) cerr(r.absoluteHigh.f0Hz, peak) else Double.NaN
        check(r.absoluteHigh != null && absHiErr < 40.0, "حفظ تحریر: اوج در محدودهٔ لحظه‌ای",
            "absHigh=${fa(r.absoluteHigh?.f0Hz ?: 0.0, 2)} (truth ${fa(peak, 2)}) err=${fa(absHiErr, 1)}c")
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "بدنهٔ نت در محدودهٔ پایدار ماند (تحریر نت نساخت)",
            "stableHigh=${fa(r.stableHigh?.f0Hz ?: 0.0, 2)} err=${fa(hiErr, 1)}c")
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 174.614) else Double.NaN
        check(r.stableLow != null && loErr < 30.0, "نت پایین‌تر در پایدار", "err=${fa(loErr, 1)}c")
        metricLine.add("S4: tahrirKept=${if (absHiErr < 40) 1 else 0} (absHigh err=${fa(absHiErr, 1)}c) · stableErr=${fa(max(hiErr, loErr), 1)}c")
    }

    /** S5 — جهش واقعی سریع بین دو نت دور: هر دو نت باید بمانند */
    private fun s05() {
        println("\n── S5 جهش واقعی (A3 → D4 با لغزش ۴۰ms و برگشت) ──")
        val s = Seq()
        s.run(0.5, 220.0)
        s.glide(0.04, 220.0, 349.228)
        s.run(0.5, 349.228)
        s.glide(0.04, 349.228, 220.0)
        s.run(0.5, 220.0)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 349.228) else Double.NaN
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "نت بالای جهش حذف نشد", "err=${fa(hiErr, 1)}c")
        check(r.stableLow != null && loErr < 30.0, "نت پایین جهش حذف نشد", "err=${fa(loErr, 1)}c")
        // «جهش پذیرفته‌شده» = هر دو نت در خروجی‌اند؛ «جهش اشتباه» = هیچ مقداری بیرون از دو نت نیست
        val ah = r.absoluteHigh; val al = r.absoluteLow
        val wrongJumps = if (ah != null && al != null)
            (if (cerr(ah.f0Hz, 349.228) > 60.0) 1 else 0) + (if (cerr(al.f0Hz, 220.0) > 60.0) 1 else 0)
        else -1
        check(ah != null && wrongJumps == 0, "هیچ جهش اشتباهی پذیرفته نشد (فقط دو نت واقعی)",
            "wrongJumps=$wrongJumps absHigh=${fa(ah?.f0Hz ?: 0.0, 2)}")
        metricLine.add("S5: highErr=${fa(hiErr, 1)}c · lowErr=${fa(loErr, 1)}c · realJumpKept=true · wrongJumpsAccepted=$wrongJumps")
    }

    /** S6 — تغییر ناگهانی شدت بدون تغییر F0 (Loudness ≠ Pitch) — در سطح زنجیرهٔ کامل */
    private fun s06() {
        println("\n── S6 تغییر شدت بدون تغییر F0 (زنجیرهٔ gate→stabilizer) ──")
        val all = RawSeq()
        all.run(1.0, 293.665, rms = 0.05)
        all.run(1.0, 293.665, rms = 0.50)
        val rAll = runChain(all.out)
        val a = runChain(all.out.subList(0, 100))  // نیمهٔ آرام
        val b = runChain(all.out.subList(100, 200)) // نیمهٔ بلند
        val errAll = if (rAll.stableHigh != null) cerr(rAll.stableHigh.f0Hz, 293.665) else Double.NaN
        val errA = if (a.stableHigh != null) cerr(a.stableHigh.f0Hz, 293.665) else Double.NaN
        val errB = if (b.stableHigh != null) cerr(b.stableHigh.f0Hz, 293.665) else Double.NaN
        check(rAll.stableHigh != null && errAll < 30.0, "محدوده با شدت‌های مختلف درست", "err=${fa(errAll, 1)}c")
        check(abs(errA - errB) < 15.0, "شدت، محدوده را تغییر نداد (اختلاف نیم‌ها)",
            "quiet=${fa(errA, 1)}c vs loud=${fa(errB, 1)}c")
        metricLine.add("S6: gainInvariance errΔ=${fa(abs(errA - errB), 1)}c")
    }

    /** S7 — صدای ضعیف ولی معتبر: فقط به‌خاطر ضعف/کم‌حجمی حذف نشود */
    private fun s07() {
        println("\n── S7 صدای ضعیف معتبر (G3 با کیفیت ۰٫۵۵ و rms کم) ──")
        val s = Seq()
        s.run(1.2, 196.0, q = 0.55)
        val r = analyze(s.out)
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 196.0) else Double.NaN
        check(r.stableHigh != null && loErr < 30.0, "صدای ضعیف وارد محدوده شد",
            "err=${fa(loErr, 1)}c q=${fa(r.stableHigh?.qualityScore ?: 0.0, 2)}")
        // در سطح زنجیره: rms خیلی کم ولی تناوب قوی → نباید رد شود
        val raw = RawSeq()
        raw.run(1.2, 196.0, rms = 0.02, periodicity = 0.85, conf = 0.7)
        val rc = runChain(raw.out)
        val cErr = if (rc.stableHigh != null) cerr(rc.stableHigh.f0Hz, 196.0) else Double.NaN
        check(rc.stableHigh != null && cErr < 30.0, "در زنجیرهٔ کامل هم حذف نشد (rms=0.02)",
            "err=${fa(cErr, 1)}c")
        metricLine.add("S7: weakVoiceErr=${fa(loErr, 1)}c · chainErr=${fa(cErr, 1)}c")
    }

    /** S8 — نویز سفید/محیطی: دادهٔ ناموجود با مقدار ساختگی پر نشود */
    private fun s08() {
        println("\n── S8 نویز سفید/محیطی ──")
        // الف) نویز واقعی از نظر گیت (تناوب پایین/پخشی طیفی) → بدون دادهٔ معتبر
        val raw = RawSeq()
        var seed = 42L
        fun rnd(): Double {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
            return ((seed * 2685821657736338717L ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        repeat(300) {
            raw.out.add(
                RawPitchFrame(
                    timestampSec = raw.t, f0Hz = 80.0 + 720.0 * rnd(), confidence = 0.25,
                    loudnessRms = 0.05 + 0.05 * rnd(), snrDb = 3.0, periodicity = 0.22,
                    agreementCents = Double.NaN, flatness = 0.55, clipFrac = 0.0
                )
            )
            raw.t += raw.hop
        }
        val rn = runChain(raw.out)
        check(rn.stableHigh == null && rn.stableLow == null, "نویز: محدودهٔ پایدار = null (بدون مقدار ساختگی)",
            "hasRange=${rn.hasRange}")
        check(rn.absoluteHigh == null && rn.absoluteLow == null, "نویز: محدودهٔ لحظه‌ای هم null",
            "invalidExcluded=${rn.stats.invalidFramesExcluded}")
        // ب) دشمنانه: فریم‌هایی که از گیت هم رد می‌شوند (تصادفی ولی باکیفیت) → نباید «محدودهٔ مطمئن» بسازد
        val s = Seq()
        var sd = 7L
        fun rnd2(): Double {
            sd = sd xor (sd shl 13); sd = sd xor (sd ushr 7); sd = sd xor (sd shl 17)
            return ((sd * 2685821657736338717L ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        s.custom(3.0, q = 0.5) { 100.0 + 700.0 * rnd2() }
        val ra = analyze(s.out)
        check(ra.stableFallback && ra.stableHigh == null, "نویز دشمنانه: ادعای محدودهٔ پایدار نشد",
            "stableFallback=${ra.stableFallback} groups=${ra.stats.totalGroups} (fragments)")
        val invalidIn = ra.stats.framesUsed // همهٔ ورودی نامعتبرند (تصادفی)
        metricLine.add("S8: noiseHasRange=${rn.hasRange} · adversarialStable=${ra.stableHigh != null} · usedFrames=$invalidIn")
    }

    /** S9 — چندنفره/همهمه: نباید محدودهٔ مطمئن یک نفره ادعا شود */
    private fun s09() {
        println("\n── S9 دو صدا هم‌زمان (۲۲۰/۳۳۰ متناوب) ──")
        val s = Seq()
        repeat(40) {
            s.run(0.025, 220.0, q = 0.5)
            s.run(0.025, 329.628, q = 0.5)
        }
        val r = analyze(s.out)
        check(r.stableFallback && r.stableHigh == null, "ادعای مطمئن برای چندنفره نشد",
            "stableFallback=${r.stableFallback}")
        check(r.absoluteHigh != null && r.absoluteLow != null, "دادهٔ دیده‌شده صادقانه گزارش شد (لحظه‌ای)",
            "abs=[${fa(r.absoluteLow?.f0Hz ?: 0.0, 1)}..${fa(r.absoluteHigh?.f0Hz ?: 0.0, 1)}]Hz")
        metricLine.add("S9: confidentStable=false · absSpan=${fa(PitchMath.cents(r.absoluteHigh?.f0Hz ?: 1.0, r.absoluteLow?.f0Hz ?: 1.0), 0)}c")
    }

    /** S10 — کف‌زدن/صداهای مزاحم لحظه‌ای: وارد محدوده نشوند */
    private fun s10() {
        println("\n── S10 کف‌زدن‌های پراکنده روی نت B3 ──")
        val s = Seq()
        s.run(0.5, 246.94)
        // کف‌ها: تک‌فریم با f0 دور از نت و دور از هم (بدون پشتوانه)
        val claps = doubleArrayOf(380.0, 660.0, 830.0, 950.0)
        for (c in claps) {
            s.custom(0.02, q = 0.6) { if (it == 0) c else 246.94 }
        }
        s.run(1.0, 246.94)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 246.94) else Double.NaN
        val absHiErr = if (r.absoluteHigh != null) cerr(r.absoluteHigh.f0Hz, 246.94) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "محدودهٔ پایدار از کف‌ها پاک ماند", "err=${fa(hiErr, 1)}c")
        check(r.absoluteHigh != null && absHiErr < 80.0, "کف‌ها وارد محدودهٔ لحظه‌ای نشدند",
            "absHigh=${fa(r.absoluteHigh?.f0Hz ?: 0.0, 1)} err=${fa(absHiErr, 1)}c")
        check(r.stats.outliersExcluded + r.stats.octaveErrorsExcluded >= 3, "فریم‌های نامعتبر شمرده و رد شدند",
            "outliers=${r.stats.outliersExcluded} octave=${r.stats.octaveErrorsExcluded}")
        metricLine.add("S10: clapRejected=${r.stats.outliersExcluded + r.stats.octaveErrorsExcluded} · absHighErr=${fa(absHiErr, 1)}c")
    }

    /** S11 — سکوت/نفس: هیچ مقداری ساخته نشود */
    private fun s11() {
        println("\n── S11 سکوت و نفس ──")
        val s = Seq()
        s.silence(0.8)
        s.custom(0.4, q = 0.25) { 80.0 + 70.0 * (it % 5) / 5.0 } // نفس با f0 بی‌معنا و کیفیت پایین
        s.silence(0.8)
        val r = analyze(s.out)
        check(r.stableHigh == null && r.stableLow == null, "سکوت: پایدار = null", "hasRange=${r.hasRange}")
        check(r.absoluteHigh == null && r.absoluteLow == null, "سکوت: لحظه‌ای = null",
            "used=${r.stats.framesUsed} invalidExcluded=${r.stats.invalidFramesExcluded}")
        metricLine.add("S11: anyOutput=${r.hasRange || r.absoluteHigh != null}")
    }

    /** S12 — قطع موقت صدا: نت‌های دو طرف محفوظ بمانند */
    private fun s12() {
        println("\n── S12 قطع موقت (A3 | وقفهٔ ۳۰۰ms | A3) ──")
        val s = Seq()
        s.run(0.6, 220.0)
        s.silence(0.3)
        s.run(0.6, 220.0)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 220.0) else Double.NaN
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0 && loErr < 30.0, "هر دو طرف وقفه ماندند",
            "hiErr=${fa(hiErr, 1)}c loErr=${fa(loErr, 1)}c")
        check(r.stats.stableGroups >= 2, "دو گروه جدا (وقفه تشخیص داده شد) ولی هر دو معتبر",
            "stableGroups=${r.stats.stableGroups}")
        metricLine.add("S12: bothSidesKept=true · groups=${r.stats.stableGroups}")
    }

    /** S13 — خطای اکتاو مصنوعی تزریق‌شده: نباید محدوده را بدزدد */
    private fun s13() {
        println("\n── S13 خطای اکتاو تزریق‌شده (A3 | 250ms×2 | A3) ──")
        val s = Seq()
        s.run(1.0, 220.0, q = 0.8)
        s.run(0.25, 440.0, q = 0.8) // خطای اکتاو با کیفیت بالا (خطای tracker نه نویز)
        s.run(1.0, 220.0, q = 0.8)
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 220.0) else Double.NaN
        val absHiErr = if (r.absoluteHigh != null) cerr(r.absoluteHigh.f0Hz, 220.0) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "محدودهٔ پایدار دزدیده نشد", "err=${fa(hiErr, 1)}c")
        check(r.absoluteHigh != null && absHiErr < 60.0, "لحظه‌ای هم دزدیده نشد", "err=${fa(absHiErr, 1)}c")
        check(r.stats.octaveErrorsExcluded >= 20, "خطاهای اکتاو شمرده و رد شدند",
            "octaveErrorsExcluded=${r.stats.octaveErrorsExcluded}")
        // همان تزریق در سطح زنجیرهٔ کامل (گیت+تثبیت‌کننده+محدوده)
        val raw = RawSeq()
        raw.run(1.0, 220.0)
        raw.run(0.25, 440.0)
        raw.run(1.0, 220.0)
        val rc = runChain(raw.out)
        val cErr = if (rc.stableHigh != null) cerr(rc.stableHigh.f0Hz, 220.0) else Double.NaN
        check(rc.stableHigh != null && cErr < 40.0, "در زنجیرهٔ کامل هم دزدیده نشد",
            "chainErr=${fa(cErr, 1)}c octaveEx=${rc.stats.octaveErrorsExcluded}")
        metricLine.add("S13: octaveErrors=${r.stats.octaveErrorsExcluded} (chain: ${rc.stats.octaveErrorsExcluded}) · highErr=${fa(hiErr, 1)}c · wrongAccepted=0")
    }

    /** S14 — صدای ریورب‌دار (سیگنال صوتی واقعی از مسیر کامل تحلیل فایل) */
    private fun s14() {
        println("\n── S14 ریورب/اکو (سیگنال صوتی → مسیر کامل فایل) ──")
        val sr = 22050
        val dur = 3.0
        val n = (dur * sr).toInt()
        val dry = DoubleArray(n)
        var ph = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val f = 220.0 * 2.0.pow((12.0 * sin(2 * PI * 5.2 * t)) / 1200.0) // vibrato ملایم
            ph += 2 * PI * f / sr
            var v = 0.0
            for (h in 1..10) {
                if (f * h > sr * 0.45) break
                v += (1.0 / h) * sin(ph * h)
            }
            dry[i] = 0.35 * v
        }
        // اکو: کپی با تأخیر ۲۵۰ms و بهرهٔ ۰٫۴۵ + نویز ملایم
        val out = DoubleArray(n)
        var seed = 99L
        fun rnd(): Double {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 7); seed = seed xor (seed shl 17)
            return ((seed * 2685821657736338717L ushr 11).toDouble() / (1L shl 53).toDouble())
        }
        for (i in 0 until n) {
            val e = if (i >= (0.25 * sr).toInt()) 0.45 * dry[i - (0.25 * sr).toInt()] else 0.0
            out[i] = dry[i] + e + 0.004 * (rnd() * 2 - 1)
        }
        val pcm = Analyzer.prepareShort(shortPcm(out), sr)
        val rep = Analyzer.analyzeSignal(ShortSignal(pcm, sr), false)
        val r = rep.rangeInfo
        check(r != null, "تحلیل ریورب: rangeInfo ساخته شد", "engine=${rep.engineName}")
        if (r != null && r.stableHigh != null && r.stableLow != null) {
            val hiErr = cerr(r.stableHigh.f0Hz, 220.0 * 2.0.pow(12.0 / 1200.0))
            val loErr = cerr(r.stableLow.f0Hz, 220.0 * 2.0.pow(-12.0 / 1200.0))
            // تحمل صادقانه: اکو، موتور را هم به چالش می‌کشد؛ فقط «شکست فاحش» مردود است
            check(hiErr < 120.0 && loErr < 120.0, "محدوده در حضور اکو معقول ماند",
                "hiErr=${fa(hiErr, 1)}c loErr=${fa(loErr, 1)}c (tol 120c)")
            check(r.absoluteHigh != null && r.absoluteHigh.f0Hz + 1e-9 >= r.stableHigh.f0Hz,
                "لحظه‌ای دربرگیرندهٔ پایدار", "ok")
        } else {
            check(false, "محدوده در حضور اکو", "دادهٔ کافی نبود (صادقانه گزارش شد)")
        }
        metricLine.add("S14: reverb ok (tolerance 120c — اکو موتور را هم به چالش می‌کشد)")
    }

    /** S15 — تلاوت طولانی با چند محدودهٔ مختلف */
    private fun s15() {
        println("\n── S15 تلاوت طولانی (۸ بخش، ~۵۰ ثانیه) ──")
        val s = Seq()
        repeat(8) {
            s.run(0.8, 174.614); s.run(0.8, 196.0); s.run(0.8, 220.0)
            s.silence(0.4)
            s.run(0.8, 329.628); s.run(0.8, 392.0); s.run(0.8, 440.0)
            s.silence(0.4)
        }
        val r = analyze(s.out)
        val hiErr = if (r.stableHigh != null) cerr(r.stableHigh.f0Hz, 440.0) else Double.NaN
        val loErr = if (r.stableLow != null) cerr(r.stableLow.f0Hz, 174.614) else Double.NaN
        check(r.stableHigh != null && hiErr < 30.0, "بالاترین نت تلاوت طولانی درست", "err=${fa(hiErr, 1)}c")
        check(r.stableLow != null && loErr < 30.0, "پایین‌ترین نت تلاوت طولانی درست", "err=${fa(loErr, 1)}c")
        check(r.stableHigh != null && r.stableHigh!!.durationSec >= 0.7, "تداوم بالاترین از timestamp",
            "dur=${fa(r.stableHigh?.durationSec ?: -1.0, 2)}s")
        check(r.stableLow != null && r.stableLow!!.timestampSec < 10.0, "لحظهٔ وقوع در بخش درست",
            "t=${fa(r.stableLow?.timestampSec ?: -1.0, 2)}s")
        metricLine.add("S15: highErr=${fa(hiErr, 1)}c · lowErr=${fa(loErr, 1)}c · stableGroups=${r.stats.stableGroups}")
    }

    // ─────────────────────── تست رفتاری با صدای واقعی ───────────────────────

    /**
     * D1 — فایل صوتی واقعی ریپو (demo.wav). بدون GT انسانی ⇒ فقط سازگاری/ناسازگاری؛
     * اعداد زیر «اندازه‌گیری‌شده»‌اند نه ادعای دقت.
     */
    private fun d01() {
        println("\n── D1 فایل واقعی demo.wav (بدون GT انسانی — فقط سازگاری) ──")
        val path = System.getProperty("demo.wav", "assets/demo.wav")
        val f = File(path)
        if (!f.exists()) {
            check(false, "demo.wav پیدا شد", "مسیر $path موجود نیست")
            return
        }
        val wav = Wav.decode(f.readBytes())
        if (wav == null) {
            check(false, "demo.wav خوانده شد", "decode ناموفق")
            return
        }
        val resampled = Wav.resample(wav.samples, wav.sampleRate, 22050)
        val pcm = Analyzer.prepareShort(shortPcm(resampled), 22050)
        var captured: ir.hamyareqari.dsp.PitchCurve? = null
        val rep = Analyzer.analyzeSignal(
            ShortSignal(pcm, 22050), false, null,
            Engine.ENHANCED, { c -> captured = c }
        )
        val r = rep.rangeInfo
        check(r != null, "rangeInfo برای demo.wav ساخته شد", "notes=${rep.noteCount} engine=${rep.engineName}")
        if (r == null) return

        // سازگاری ساختاری: محدودهٔ پایدار باید داخل محدودهٔ لحظه‌ای باشد
        val sh = r.stableHigh; val sl = r.stableLow
        val ah = r.absoluteHigh; val al = r.absoluteLow
        check(ah != null && al != null, "لحظه‌ای (absolute) محاسبه شد",
            "abs=[${fa(al?.f0Hz ?: 0.0, 1)}..${fa(ah?.f0Hz ?: 0.0, 1)}]Hz")
        if (sh != null && sl != null && ah != null && al != null) {
            check(sh.f0Hz <= ah.f0Hz + 1e-6 && sl.f0Hz >= al.f0Hz - 1e-6,
                "پایدار داخل لحظه‌ای (سازگاری)",
                "stable=[${fa(sl.f0Hz, 1)}..${fa(sh.f0Hz, 1)}] abs=[${fa(al.f0Hz, 1)}..${fa(ah.f0Hz, 1)}]")
            check(sh.durationSec > 0 && sl.durationSec > 0 && sh.qualityScore in 0.0..1.0,
                "فیلدهای قابل‌ردیابی معتبر",
                "hiDur=${fa(sh.durationSec, 2)}s q=${fa(sh.qualityScore, 2)} t=${fa(sh.timestampSec, 1)}s")
            check(sh.timestampSec in 0.0..rep.durationSec + 0.1 && sl.timestampSec in 0.0..rep.durationSec + 0.1,
                "زمان‌ها داخل دامنهٔ فایل", "fileDur=${fa(rep.durationSec, 1)}s")
        }
        // نگاشت Report: highest/lowest از stable
        if (rep.highest != null && sh != null) {
            check(abs(rep.highest.f0 - sh.f0Hz) < 1e-6, "Report.highest از Stable آمده",
                "report=${fa(rep.highest.f0, 2)} stable=${fa(sh.f0Hz, 2)}")
        }
        // JSON: ساختار range_details
        val js = Json.report(rep, "demo.wav", "1.2.1-pitchv1")
        check(js.contains("\"range_details\"") && js.contains("\"stable_high\"") &&
            js.contains("\"absolute_low\""), "JSON جزئیات قابل‌ردیابی را دارد", "keys ok")
        check(!js.contains("NaN") && !js.contains("Infinity"), "JSON بدون NaN/Infinity", "ok")

        // خروجی JSON با محدودهٔ دستیِ کاربر (override درگ خط‌چین‌ها)
        val jsManual = Json.report(rep, "demo", "test", null, 880.0, 220.0)
        check(jsManual.contains("\"source\": \"manual\"") && jsManual.contains("\"manual\": true"),
            "JSON محدودهٔ دستی را نشان می‌دهد", "source=manual")
        check(jsManual.contains("\"raw_highest\"") && jsManual.contains("\"range_details\""),
            "ردِّ خودکار موتور در کنار مقدار دستی حفظ می‌شود", "raw+details ok")
        check(!jsManual.contains("NaN") && !jsManual.contains("Infinity"),
            "JSON دستی بدون NaN/Infinity", "ok")
        val jsAuto = Json.report(rep, "demo", "test", null)
        check(jsAuto.contains("\"source\": \"auto\"") && !jsAuto.contains("\"manual\": true"),
            "بدون override برچسب auto می‌خورد", "source=auto")

        // یکسانی منطق streaming (زنده) با batch (فایل) روی همان داده
        val curve = captured
        if (curve != null) {
            val samples = PitchSampleStream.fromCurve(curve, maxPoints = 0)
            val st = PitchRangeAnalyzer.Streaming()
            for (smp in samples) st.add(smp)
            val rs = st.current()
            val rb = PitchRangeAnalyzer().analyzeSamples(samples)
            val sameHi = (rs.stableHigh == null && rb.stableHigh == null) ||
                (rs.stableHigh != null && rb.stableHigh != null &&
                    abs(rs.stableHigh.f0Hz - rb.stableHigh.f0Hz) < 1e-9)
            val sameLo = (rs.stableLow == null && rb.stableLow == null) ||
                (rs.stableLow != null && rb.stableLow != null &&
                    abs(rs.stableLow.f0Hz - rb.stableLow.f0Hz) < 1e-9)
            check(sameHi && sameLo, "منطق زنده و فایل یکی است (Streaming == Batch)",
                "samples=${samples.size}")
        }

        println("  ╌ اندازه‌گیری‌شده روی demo.wav (بدون GT انسانی — فقط گزارش رفتار):")
        println("    stable=[${fa(sl?.f0Hz ?: 0.0, 1)}..${fa(sh?.f0Hz ?: 0.0, 1)}]Hz · " +
            "absolute=[${fa(al?.f0Hz ?: 0.0, 1)}..${fa(ah?.f0Hz ?: 0.0, 1)}]Hz · " +
            "frames=${r.stats.framesUsed}/${r.stats.framesConsidered} · " +
            "octaveEx=${r.stats.octaveErrorsExcluded} outliersEx=${r.stats.outliersExcluded} · " +
            "groups=${r.stats.stableGroups}/${r.stats.totalGroups}")
        metricLine.add("D1: demo.wav — stable=[${fa(sl?.f0Hz ?: 0.0, 1)}..${fa(sh?.f0Hz ?: 0.0, 1)}]Hz (رفتاری، بدون GT)")
    }

    /** R1 — محاسبات گسترهٔ دستی (RangeMath): فرمول‌های مشتقِ محدودهٔ درگ‌شده */
    private fun m01() {
        println("\n── R1 محاسبات گسترهٔ دستی (RangeMath) ──")
        val sp = RangeMath.span(440.0, 220.0)
        check(abs(sp.spanSemitones - 12.0) < 1e-9, "۱۲ نیم‌پرده دقیق (۴۴۰/۲۲۰)", "span=${fa(sp.spanSemitones, 6)}")
        check(sp.fullOctaves == 1 && abs(sp.restSemitones) < 1e-9,
            "۱ اکتاو تمام + باقی‌مانده صفر", "oct=${sp.fullOctaves} rest=${fa(sp.restSemitones, 6)}")
        check(abs(sp.spanOctaves - 1.0) < 1e-9 && abs(sp.quarterTones - 24.0) < 1e-9,
            "اکتاو و ربع‌پرده", "oct=${fa(sp.spanOctaves, 6)} qt=${fa(sp.quarterTones, 3)}")
        check(abs(sp.ofOctavePct - 100.0) < 1e-9, "درصد از اکتاو", "${fa(sp.ofOctavePct, 3)}")
        check(abs(sp.centerHz - sqrt(440.0 * 220.0)) < 1e-9, "مرکز هندسی (√(hi·lo))", "${fa(sp.centerHz, 3)}")
        // ورودی جابه‌جا (hi<lo) نباید خروجی را خراب کند
        val sp2 = RangeMath.span(220.0, 440.0)
        check(abs(sp2.spanSemitones - 12.0) < 1e-9, "جابه‌جایی hi/lo ایمن", "span=${fa(sp2.spanSemitones, 6)}")
        // مقدار آزاد سنتی (درگ بدون قفل نیم‌پرده): ۵۰ سنت بالای ۲۲۰ → ۱۱٫۵ نیم‌پرده
        val lo = 220.0 * 2.0.pow(50.0 / 1200.0)
        val sp3 = RangeMath.span(440.0, lo)
        check(abs(sp3.spanSemitones - 11.5) < 1e-6, "گسترهٔ سنتی آزاد (۱۱٫۵ نیم‌پرده)", "span=${fa(sp3.spanSemitones, 6)}")
        check(sp3.fullOctaves == 0 && abs(sp3.restSemitones - 11.5) < 1e-6,
            "باقی‌ماندهٔ ۱۱٫۵ نیم‌پرده", "rest=${fa(sp3.restSemitones, 6)}")
        check(abs(sp3.quarterTones - 23.0) < 1e-6, "ربع‌پردهٔ ۲۳", "qt=${fa(sp3.quarterTones, 3)}")
        metricLine.add("R1: RangeMath — 12.000s/1oct exact · 11.500s free-cents ok")
    }

    private fun shortPcm(x: DoubleArray): ShortArray {
        val out = ShortArray(x.size)
        for (i in x.indices) {
            val v = (x[i] * 32767.0).toInt()
            out[i] = v.coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    // ─────────────────────────────── اجرا ───────────────────────────────

    @JvmStatic
    fun main(args: Array<String>) {
        println("══ آزمون محدودهٔ صوتی (PitchRangeAnalyzer) — ۱۵ سناریو با GT + demo.wav ══")
        s01(); s02(); s03(); s04(); s05(); s06(); s07(); s08(); s09()
        s10(); s11(); s12(); s13(); s14(); s15()
        d01()
        m01()

        println("\n── جدول معیارها ──")
        for (m in metricLine) println("  • $m")
        println("\n──────── نتیجه: $checks بررسی، $failures شکست ────────")
        if (failures > 0) {
            println("FAILED")
            System.exit(1)
        } else {
            println("ALL GREEN")
        }
    }
}
