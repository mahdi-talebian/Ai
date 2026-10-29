package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** اطلاعات کیفیت تحلیل */
class QualityInfo(
    val status: String,
    val snrDb: Double,
    val agreementPct: Double,
    val meanConfidence: Double,
    val sampleRate: Int,
    val octaveUp: Int,
    val octaveDown: Int,
    val warnings: List<String>
)

/** گزارش نهایی تحلیل */
class Report(
    val durationSec: Double,
    val hopMs: Double,
    val sampleRate: Int,
    val highest: NoteHit?,
    val lowest: NoteHit?,
    val rawHighest: NoteHit?,
    val rawLowest: NoteHit?,
    val spanSemitones: Double,
    val spanOctaves: Double,
    val spanCents: Double,
    val fullOctaves: Int,
    val restSemitones: Double,
    val quarterTones: Double,
    val ofOctavePct: Double,
    val centerHz: Double,
    val notes: List<NoteHit>,
    val shares: List<NoteShare>,
    val pauseCount: Int,
    val pauses: List<Double>,
    val longestPause: Double,
    val sungSec: Double,
    val quality: QualityInfo,
    val contourT: DoubleArray,
    val contourHz: DoubleArray
) {
    val centerName: String get() = PitchMath.noteName(centerHz)
    val noteCount: Int get() = notes.size
    val hasData: Boolean get() = highest != null && lowest != null
}

object Analyzer {

    const val FMIN = 55.0
    const val FMAX = 1200.0

    /**
     * آماده‌سازی ورودی — همهٔ فایل‌ها از یک درِ یکسان عبور می‌کنند:
     * حذف DC → بالاگذر ۴۵Hz → نرمال‌سازی پیک به ۰٫۹
     */
    fun preprocess(input: DoubleArray, sampleRate: Int): DoubleArray {
        val n = input.size
        val y = DoubleArray(n)
        var mean = 0.0
        for (v in input) mean += v
        mean /= max(1, n)
        for (i in 0 until n) y[i] = input[i] - mean
        highpass45(y, sampleRate)
        var peak = 0.0
        for (v in y) { val a = abs(v); if (a > peak) peak = a }
        if (peak > 1e-9) {
            val g = 0.9 / peak
            for (i in 0 until n) y[i] *= g
        }
        return y
    }

    /**
     * آماده‌سازی درجای صدای ۱۶ بیتی (بدون بافر double بزرگ):
     * حذف DC → بالاگذر ۴۵Hz دوطرفه → نرمال‌سازی پیک به ۰٫۹.
     * فایل ۴۶ دقیقه‌ای اینجا فقط ۱۲۲ مگابایت می‌گیرد، نه ۴۸۸ مگابایت.
     */
    fun prepareShort(input: ShortArray, sampleRate: Int): ShortArray {
        val n = input.size
        if (n < 16) return input
        var mean = 0.0
        for (v in input) mean += v
        mean /= n
        for (i in 0 until n) {
            val v = (input[i] - mean).toInt()
            input[i] = v.coerceIn(-32768, 32767).toShort()
        }
        val fc = 45.0
        if (sampleRate / 2.0 > fc * 1.2) {
            val w0 = 2.0 * Math.PI * fc / sampleRate
            val cosw = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * 0.7071067811865476)
            val a0 = 1.0 + alpha
            val b0 = (1.0 + cosw) / 2.0 / a0
            val b1 = -(1.0 + cosw) / a0
            val b2 = b0
            val a1 = -2.0 * cosw / a0
            val a2 = (1.0 - alpha) / a0
            fun pass(forward: Boolean) {
                var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
                val range = if (forward) 0 until n else (n - 1) downTo 0
                for (i in range) {
                    val x0 = input[i].toDouble()
                    val out = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                    x2 = x1; x1 = x0
                    y2 = y1; y1 = out
                    input[i] = out.toInt().coerceIn(-32768, 32767).toShort()
                }
            }
            pass(true)
            pass(false)
        }
        var peak = 0
        for (v in input) {
            val a = if (v < 0) -v.toInt() else v.toInt()
            if (a > peak) peak = a
        }
        if (peak > 0) {
            val g = 0.9 * 32767.0 / peak
            for (i in 0 until n) {
                input[i] = (input[i] * g).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        return input
    }

    /** بالاگذر مرتبهٔ دو باترورث، دوطرفه (بدون تغییر فاز) */
    private fun highpass45(y: DoubleArray, sr: Int) {
        val fc = 45.0
        if (sr / 2.0 <= fc * 1.2) return
        val w0 = 2.0 * Math.PI * fc / sr
        val cosw = Math.cos(w0)
        val alpha = Math.sin(w0) / (2.0 * 0.7071067811865476)
        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosw) / 2.0 / a0
        val b1 = -(1.0 + cosw) / a0
        val b2 = b0
        val a1 = -2.0 * cosw / a0
        val a2 = (1.0 - alpha) / a0
        forwardBiquad(y, b0, b1, b2, a1, a2)
        y.reverse()
        forwardBiquad(y, b0, b1, b2, a1, a2)
        y.reverse()
    }

    private fun forwardBiquad(y: DoubleArray, b0: Double, b1: Double, b2: Double, a1: Double, a2: Double) {
        var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
        for (i in y.indices) {
            val x0 = y[i]
            val out = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x0
            y2 = y1; y1 = out
            y[i] = out
        }
    }

    /**
     * تحلیل کامل. progress: (مرحله، درصد ۰..۱)
     */
    fun analyze(
        rawInput: DoubleArray,
        sampleRate: Int,
        progress: ((String, Double) -> Unit)? = null
    ): Report {
        progress?.invoke("preprocess", 0.02)
        val prepared = preprocess(rawInput, sampleRate)
        return analyzeSignal(DoubleSignal(prepared, sampleRate), progress)
    }

    /**
     * تحلیل سیگنالی که از قبل آماده شده است (۱۶ بیتی، ۲۲٬۰۵۰ هرتز).
     * این مسیر حافظهٔ کمتری می‌خواهد و برای فایل‌های بلند استفاده می‌شود.
     */
    fun analyzeSignal(
        sig: Signal,
        progress: ((String, Double) -> Unit)? = null
    ): Report {
        val sampleRate = sig.sampleRate
        val duration = sig.size.toDouble() / sampleRate

        // گام زمانی: فایل‌های کوتاه دقیق‌تر (۵ms)، فایل‌های بلند سریع‌تر (۱۰ms)
        val hopMs = if (duration <= 180.0) 5.0 else 10.0
        progress?.invoke("yin", 0.05)
        val track = PitchTracker.track(
            sig, hopMs, FMIN, FMAX,
            progress = { p -> progress?.invoke("yin", 0.05 + 0.55 * p) }
        )
        progress?.invoke("ladder", 0.62)
        val corr = Harmonics.correctRuns(track, sig, FMIN, FMAX) { p ->
            progress?.invoke("ladder", 0.62 + 0.10 * p)
        }
        val fixed = PitchTrack(track.hop, track.sampleRate, track.t, corr.f0, track.clarity, track.rms)

        progress?.invoke("notes", 0.74)
        var notes = Segmenter.segment(fixed)
        val sungAll = Segmenter.sungSeconds(notes)

        // پالایش هر نت با برازش هارمونیک‌ها
        val refined = ArrayList<NoteHit>(notes.size)
        for ((idx, nt) in notes.withIndex()) {
            if (nt.duration >= 0.10) {
                val r = Harmonics.refineNote(sig, sampleRate, nt.start, nt.end, nt.f0)
                refined.add(
                    NoteHit(
                        nt.start, nt.end, nt.duration, r.f0, nt.stability,
                        nt.confidence, nt.glide, nt.frames, r.support
                    )
                )
            } else {
                refined.add(nt)
            }
            if (idx % 8 == 0) progress?.invoke("notes", 0.74 + 0.10 * (idx + 1) / max(1, notes.size))
        }
        notes = refined

        progress?.invoke("stats", 0.88)
        val sungSec = Segmenter.sungSeconds(notes)
        val real = Segmenter.realNotes(notes, sungSec)
        val highest = real.maxByOrNull { it.f0 }
        val lowest = real.minByOrNull { it.f0 }
        val rawHighest = notes.maxByOrNull { it.f0 }
        val rawLowest = notes.minByOrNull { it.f0 }

        val spanSemis = if (highest != null && lowest != null)
            PitchMath.cents(highest.f0, lowest.f0) / 100.0 else 0.0
        val spanOct = spanSemis / 12.0
        val fullOct = kotlin.math.floor(spanOct + 1e-9).toInt()
        var rest = spanSemis - fullOct * 12.0
        if (rest >= 12.0 - 1e-9) { rest = 0.0 }
        val centerHz = if (highest != null && lowest != null)
            Math.sqrt(highest.f0 * lowest.f0) else 0.0

        val shares = Segmenter.shares(notes, sungSec)
        val pauses = Segmenter.pauses(notes, duration)

        // کیفیت
        val voicedRms = ArrayList<Double>()
        val quietRms = ArrayList<Double>()
        for (i in 0 until fixed.size) {
            if (fixed.f0[i] > 0.0) voicedRms.add(fixed.rms[i]) else quietRms.add(fixed.rms[i])
        }
        val snr = if (voicedRms.isNotEmpty()) {
            val vr = PitchMath.mean(voicedRms)
            val qr = if (quietRms.isNotEmpty()) PitchMath.mean(quietRms) else 0.0
            if (qr > 1e-9) 20.0 * kotlin.math.log10(vr / qr) else 60.0
        } else 0.0

        progress?.invoke("agreement", 0.93)
        val agreement = SecondOpinion.agreement(sig, fixed)

        var confSum = 0.0
        for (nt in notes) confSum += nt.confidence
        val meanConf = if (notes.isNotEmpty()) confSum / notes.size else 0.0

        val warnings = ArrayList<String>()
        if (duration < 3.0) warnings.add("فایل کوتاه است؛ برای نتیجهٔ مطمئن دست‌کم ۱۰ ثانیه بخوانید.")
        if (snr < 12.0) warnings.add("نسبت صدا به نویز پایین است؛ در محیط ساکت‌تر دوباره امتحان کنید.")
        if (meanConf < 0.55) warnings.add("کیفیت تشخیص نت‌ها متوسط است؛ شاید صدا خش‌دار یا خیلی نازک است.")
        if (pauses.size > notes.size / 2) warnings.add("سکوت‌های زیادی هست؛ خواندن پیوسته‌تر نتیجهٔ دقیق‌تری می‌دهد.")
        if (rawHighest != null && highest != null &&
            PitchMath.cents(rawHighest.f0, highest.f0) > 200.0
        ) warnings.add("نت‌های لحظه‌ای پرت (جیغ/لغزش) نادیده گرفته شدند.")

        val status = when {
            !hasData(highest, lowest) -> "poor"
            snr >= 24.0 && meanConf >= 0.75 && agreement >= 85.0 -> "excellent"
            snr >= 16.0 && meanConf >= 0.60 && agreement >= 70.0 -> "good"
            snr >= 8.0 -> "fair"
            else -> "poor"
        }

        // منحنی نمایش: فشرده‌سازی تا حداکثر ۶۰۰۰ نقطه
        val maxPoints = 6000
        val stride = max(1, fixed.size / maxPoints)
        val nPts = (fixed.size + stride - 1) / stride
        val ct = DoubleArray(nPts)
        val ch = DoubleArray(nPts)
        for (p in 0 until nPts) {
            val a = p * stride
            val b = min(fixed.size, a + stride)
            ct[p] = fixed.t[a]
            // در هر بلوک: اگر بیش از نیمی صدادار بود، میانهٔ صدادارها
            var count = 0
            var sum = 0.0
            for (k in a until b) {
                if (fixed.f0[k] > 0.0) { count++; sum += fixed.f0[k] }
            }
            ch[p] = if (count * 2 >= (b - a) && count > 0) sum / count else 0.0
        }

        progress?.invoke("done", 1.0)
        return Report(
            durationSec = duration,
            hopMs = hopMs,
            sampleRate = sampleRate,
            highest = highest,
            lowest = lowest,
            rawHighest = rawHighest,
            rawLowest = rawLowest,
            spanSemitones = spanSemis,
            spanOctaves = spanOct,
            spanCents = spanSemis * 100.0,
            fullOctaves = fullOct,
            restSemitones = rest,
            quarterTones = spanSemis * 2.0,
            ofOctavePct = spanSemis / 12.0 * 100.0,
            centerHz = centerHz,
            notes = notes,
            shares = shares,
            pauseCount = pauses.size,
            pauses = pauses,
            longestPause = if (pauses.isNotEmpty()) pauses.max() else 0.0,
            sungSec = sungSec,
            quality = QualityInfo(
                status = status,
                snrDb = snr,
                agreementPct = agreement,
                meanConfidence = meanConf,
                sampleRate = sampleRate,
                octaveUp = corr.up,
                octaveDown = corr.down,
                warnings = warnings
            ),
            contourT = ct,
            contourHz = ch
        )
    }

    private fun hasData(h: NoteHit?, l: NoteHit?): Boolean = h != null && l != null
}

/**
 * «موتور دوم» مستقل: روش McLeod (MPM) روی دامنهٔ زمان.
 * فقط برای گزارش هم‌رأیی/اطمینان استفاده می‌شود و منحنی اصلی را تغییر نمی‌دهد.
 */
object SecondOpinion {

    fun estimate(sig: Signal, sr: Int, start: Int, win: Int, fMin: Double, fMax: Double): Double {
        val tauMin = max(2, Math.floor(sr / fMax).toInt())
        val tauMax = min(win - 2, Math.ceil(sr / fMin).toInt())
        val l = win - tauMax
        if (l < 32 || start + win > sig.size) return 0.0
        val x = DoubleArray(win)
        sig.read(start, win, x)
        var m0 = 0.0
        for (j in 0 until l) m0 += x[j] * x[j]
        if (m0 < 1e-9) return 0.0
        val nsdf = DoubleArray(tauMax + 1)
        for (tau in tauMin..tauMax) {
            var r = 0.0
            var m = 0.0
            for (j in 0 until l) {
                val a = x[j]
                val b = x[j + tau]
                r += a * b
                m += a * a + b * b
            }
            nsdf[tau] = if (m > 1e-12) 2.0 * r / m else 0.0
        }
        // بیشینهٔ محلی
        var peakVal = -1.0
        for (tau in tauMin + 1 until tauMax) {
            if (nsdf[tau] > nsdf[tau - 1] && nsdf[tau] >= nsdf[tau + 1]) {
                if (nsdf[tau] > peakVal) peakVal = nsdf[tau]
            }
        }
        if (peakVal < 0.5) return 0.0
        val cut = 0.85 * peakVal
        var chosen = -1
        for (tau in tauMin + 1 until tauMax) {
            if (nsdf[tau] >= cut && nsdf[tau] > nsdf[tau - 1] && nsdf[tau] >= nsdf[tau + 1]) {
                chosen = tau
                break
            }
        }
        if (chosen < 0) return 0.0
        var tauExact = chosen.toDouble()
        if (chosen in 1 until tauMax) {
            val a = nsdf[chosen - 1]
            val b = nsdf[chosen]
            val c = nsdf[chosen + 1]
            val den = a - 2.0 * b + c
            if (abs(den) > 1e-12) {
                val delta = 0.5 * (a - c) / den
                if (delta in -0.5..0.5) tauExact += delta
            }
        }
        return if (tauExact > 0.5) sr / tauExact else 0.0
    }

    /** درصد فریم‌هایی که دو موتور در ۷۰ سنت هم‌نظرند */
    fun agreement(sig: Signal, track: PitchTrack): Double {
        val sr = track.sampleRate
        val voiced = ArrayList<Int>()
        for (i in 0 until track.size) if (track.f0[i] > 0.0) voiced.add(i)
        if (voiced.isEmpty()) return 0.0
        val maxChecks = 500
        val step = max(1, voiced.size / maxChecks)
        val win = (0.045 * sr).toInt()
        var ok = 0
        var total = 0
        var i = 0
        while (i < voiced.size) {
            val idx = voiced[i]
            val start = idx * track.hop - win / 2
            if (start >= 0 && start + win <= sig.size) {
                val f2 = estimate(sig, sr, start, win, Analyzer.FMIN, Analyzer.FMAX)
                if (f2 > 0.0) {
                    total++
                    if (abs(PitchMath.cents(f2, track.f0[idx])) <= 70.0) ok++
                }
            }
            i += step
        }
        return if (total > 0) 100.0 * ok / total else 0.0
    }
}
