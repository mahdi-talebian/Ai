package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * منحنی F0 با وضعیت صریح هر فریم.
 *
 * وضعیت‌ها (status) از هم جدا نگه داشته می‌شوند تا مصرف‌کننده بتواند بین
 * «سکوت»، «نویز/نامعتبر» و «نزدیک به مرز اعتبار» تفاوت بگذارد. مقدار f0
 * فقط برای وضعیت‌های VALID و ESTIMATED ناترازی است.
 */
class PitchCurve(
    val t: DoubleArray,
    val f0: DoubleArray,
    val confidence: FloatArray,
    val status: ByteArray,
    val estimated: BooleanArray,
    val rms: FloatArray,
    val snrDb: FloatArray,
    val hop: Int,
    val sampleRate: Int,
    val hopMs: Double,
    val agreementPct: Double,
    val medianSnrDb: Double,
    val voicedFrames: Int,
    val uncertainFrames: Int,
    val estimatedFrames: Int,
    val repairedOctaves: Int,
    val repairedUp: Int,
    val repairedDown: Int,
    /** کمترین کف نویز محلی در کل فایل (تخمین سطح نویز پس‌زمینه) */
    val globalNoiseRms: Double = 0.0
) {
    val size: Int get() = f0.size

    companion object {
        const val SILENT: Byte = 0
        const val INVALID: Byte = 1
        const val UNCERTAIN: Byte = 2
        const val VALID: Byte = 3
        const val ESTIMATED: Byte = 4
    }

    /** سازگاری با مسیر قدیمی: منحنی را به PitchTrack تبدیل می‌کند */
    fun toTrack(): PitchTrack {
        val clar = DoubleArray(size)
        val rr = DoubleArray(size)
        for (i in 0 until size) {
            clar[i] = confidence[i].toDouble()
            rr[i] = rms[i].toDouble()
        }
        return PitchTrack(hop, sampleRate, t, f0, clar, rr)
    }
}

/**
 * موتور F0 جدید: دو تخمین‌گر + اعتبارسنجی مستقل + ردیابی نامزدها.
 *
 * تفکیک مسئولیت‌ها (عمداً جدا نگه داشته شده‌اند):
 *   A) اعتبارسنجی فریم  → [validity]: سکوت/نویز/نامطمئن/معتبر با چند ویژگی
 *   B) تخمین F0         → [DualEstimator]: YIN و MPM، هر کدام چند نامزد
 *   C) ردیابی پیوسته    → [track]: انتخاب نامزد با شواهد زمانی، بدون صاف‌کردن
 *   D) تفسیر موسیقایی   → بیرون از این فایل (Segmenter / Harmonics.refineNote)
 *
 * اصل: هیچ‌کدام از این مراحل از آستانهٔ ثابتِ دامنه استفاده نمی‌کند. همهٔ
 * آستانه‌ها نسبت به کف نویز محلی یا نسبت به همسایگی همان فریم سنجیده می‌شوند.
 */
object PitchEngine {

    class Options(
        val hopMsAdaptive: Boolean = true,
        val frameSec: Double = 0.045,
        val fMin: Double = Analyzer.FMIN,
        val fMax: Double = Analyzer.FMAX,
        /** حداقل اعتماد برای «معتبر» */
        val validConfidence: Double = 0.60,
        /** زیر این حد، فریم «نامطمئن» است و نتیجه تولید نمی‌کند */
        val minConfidence: Double = 0.42,
        /** حداکثر طول شکافِ کوتاه که درون‌یابی می‌شود (ثانیه) */
        val maxGapSec: Double = 0.06,
        /** آستانهٔ زمانی برای ردیابی اکتاو/پرش پرت */
        val outlierCents: Double = 280.0,
        val repairCents: Double = 60.0
    )

    fun empty(sampleRate: Int, hopMs: Double): PitchCurve = PitchCurve(
        DoubleArray(0), DoubleArray(0), FloatArray(0), ByteArray(0), BooleanArray(0),
        FloatArray(0), FloatArray(0), 1, sampleRate, hopMs, 0.0, 0.0, 0, 0, 0, 0, 0, 0, 0.0
    )

    /**
     * ردیابی کامل F0.
     *
     * @param sig سیگنال آماده‌شده (DC حذف‌شده، بالاگذر، نرمال‌شده) — همان ورودی
     *            که مسیر فعلی Analyzer استفاده می‌کند.
     */
    fun analyze(
        sig: Signal,
        hopMs: Double = 5.0,
        options: Options = Options(),
        progress: ((Double) -> Unit)? = null
    ): PitchCurve {
        val sr = sig.sampleRate
        val hop = max(1, (hopMs / 1000.0 * sr).toInt())
        val frameSec = options.frameSec
        val est = DualEstimator(sr, options.fMin, options.fMax, frameSec)
        val n = est.framesCount(sig.size, hop)
        if (n <= 1) return empty(sr, hopMs)

        val MAXC = DualEstimator.MAX_CAND
        val rms = FloatArray(n)
        val flat = FloatArray(n)
        val flux = FloatArray(n)
        val clip = FloatArray(n)
        val yinF = FloatArray(n)
        val yinC = FloatArray(n)
        val mpmF = FloatArray(n)
        val mpmC = FloatArray(n)
        val support = FloatArray(n)
        val coverage = FloatArray(n)
        val fundProm = FloatArray(n)
        val candF0 = FloatArray(n * MAXC)
        val candScore = FloatArray(n * MAXC)
        val candCnt = ByteArray(n)

        val fr = FrameResult()
        val frame = est.newFrameBuffer()
        val reportEvery = max(1, n / 50)
        for (i in 0 until n) {
            est.analyze(sig, i * hop, fr, frame)
            rms[i] = fr.rms.toFloat()
            flat[i] = fr.flatness.toFloat()
            flux[i] = fr.flux.toFloat()
            clip[i] = fr.clipFrac.toFloat()
            yinF[i] = fr.yinF0.toFloat()
            yinC[i] = fr.yinClarity.toFloat()
            mpmF[i] = fr.mpmF0.toFloat()
            mpmC[i] = fr.mpmClarity.toFloat()
            support[i] = fr.bestSupport.toFloat()
            coverage[i] = fr.coverage.toFloat()
            fundProm[i] = fr.fundamentalProminence.toFloat()
            candCnt[i] = fr.candCount.toByte()
            for (j in 0 until fr.candCount) {
                candF0[i * MAXC + j] = fr.candF0[j].toFloat()
                candScore[i * MAXC + j] = fr.candScore[j].toFloat()
            }
            if (progress != null && i % reportEvery == 0) progress(0.75 * i / n)
        }

        // ── کف نویز محلی (نسبت به زمان، نه یک عدد برای کل فایل) ──
        val floor = localNoiseFloor(rms, hop, sr)
        var globalFloor = Double.MAX_VALUE
        for (f in floor) if (f < globalFloor) globalFloor = f
        if (globalFloor == Double.MAX_VALUE) globalFloor = 0.0
        // مرجع دامنه: صدک ۹۵٪ انرژی فریم‌ها. «سکوت» نسبی سنجیده می‌شود، نه با
        // یک عدد مطلق که با تنظیم گین فایل بی‌معنا می‌شود.
        val sorted = FloatArray(n)
        System.arraycopy(rms, 0, sorted, 0, n)
        sorted.sort()
        val refRms = max(sorted[(0.95 * (n - 1)).toInt()].toDouble(), 1e-5)
        // اگر دامنهٔ دینامیکی فایل کم باشد (فشرده/AGC/صدای پیوسته)، صدک بیستم
        // انرژی «نویز» نیست بلکه خودِ صدا است؛ در این حالت SNR قابل اتکا نیست
        // و به‌عنوان شاهد کنار گذاشته می‌شود (نه اینکه بهانهٔ رد کردن شود).
        val p20 = sorted[(0.20 * (n - 1)).toInt()].toDouble()
        val dynRangeDb = 20.0 * kotlin.math.log10((refRms + 1e-12) / (max(p20, 1e-7) + 1e-12))
        val snrTrust = clamp01((dynRangeDb - 4.0) / 8.0)

        // ── A) اعتبارسنجی فریم ──
        val conf = FloatArray(n)
        val status = ByteArray(n)
        val snrFrame = FloatArray(n)
        for (i in 0 until n) {
            val nf = floor[i]
            val r = rms[i].toDouble()
            val silent = r <= max(0.045 * refRms, 1.2e-4)
            if (silent) {
                status[i] = PitchCurve.SILENT
                conf[i] = 0.0f
                snrFrame[i] = 0.0f
                continue
            }
            val snr = 20.0 * log10((r + 1e-12) / (nf + 1e-12))
            snrFrame[i] = snr.toFloat()
            conf[i] = frameConfidence(
                snrDb = snr,
                yinF0 = yinF[i].toDouble(), yinClarity = yinC[i].toDouble(),
                mpmF0 = mpmF[i].toDouble(), mpmClarity = mpmC[i].toDouble(),
                support = support[i].toDouble(),
                coverage = coverage[i].toDouble(),
                fundProminence = fundProm[i].toDouble(),
                flatness = flat[i].toDouble(),
                flux = flux[i].toDouble(),
                clipFrac = clip[i].toDouble(),
                snrTrust = snrTrust
            ).toFloat()
            status[i] = when {
                conf[i] >= options.validConfidence -> PitchCurve.VALID
                conf[i] >= options.minConfidence -> PitchCurve.UNCERTAIN
                else -> PitchCurve.INVALID
            }
        }

        // ── C) ردیابی (Viterbi روی نامزدها + حالت «نامعتبر») ──
        val chosen = track(n, conf, candF0, candScore, candCnt)
        val f0 = DoubleArray(n)
        for (i in 0 until n) {
            if (chosen[i] < MAXC && candCnt[i].toInt() > chosen[i]) {
                val c = candF0[i * MAXC + chosen[i]].toDouble()
                // نتیجه فقط با اعتماد کافی تولید می‌شود؛ فریمِ «نامطمئن»
                // عدد تولید نمی‌کند (به‌جای حدس زدن).
                f0[i] = if (conf[i] >= options.validConfidence) c else 0.0
                if (f0[i] <= 0.0 && status[i] == PitchCurve.VALID) status[i] = PitchCurve.UNCERTAIN
            } else {
                f0[i] = 0.0
                if (status[i] == PitchCurve.VALID) status[i] = PitchCurve.UNCERTAIN
            }
        }

        // ── اصلاح پرش‌های پرت (اکتاو/گام اشتباه) با شاهد همسایگی ──
        val repairDir = IntArray(2)
        val repaired = repairOutliers(f0, candF0, candCnt, options, repairDir)
        val estimated = BooleanArray(n)

        // ── حذف ران‌های بسیار کوتاه (ضربه/تشویق) ──
        dropShortRuns(f0, conf)

        // ── درون‌یابی شکاف‌های کوتاه، با علامت «تخمینی» ──
        fillShortGaps(f0, estimated, rms, conf, hop, sr, options.maxGapSec)

        // ── وضعیت نهایی ──
        var voiced = 0
        var uncertain = 0
        var estCount = 0
        for (i in 0 until n) {
            when {
                f0[i] > 0.0 && estimated[i] -> { status[i] = PitchCurve.ESTIMATED; estCount++; voiced++ }
                f0[i] > 0.0 -> { if (status[i] != PitchCurve.VALID) status[i] = PitchCurve.VALID; voiced++ }
                status[i] == PitchCurve.VALID -> { status[i] = PitchCurve.UNCERTAIN; uncertain++ }
                status[i] == PitchCurve.UNCERTAIN -> uncertain++
            }
        }

        // ── آمار کیفیت: توافق دو تخمین‌گر و SNR روی فریم‌های معتبر ──
        var agreeOk = 0
        var agreeTotal = 0
        for (i in 0 until n) {
            if (f0[i] > 0.0 && yinF[i] > 0f && mpmF[i] > 0f) {
                agreeTotal++
                if (abs(PitchMath.cents(yinF[i].toDouble(), mpmF[i].toDouble())) <= 70.0) agreeOk++
            }
        }
        val voicedSnrs = DoubleArray(voiced)
        var k = 0
        for (i in 0 until n) if (f0[i] > 0.0) voicedSnrs[k++] = snrFrame[i].toDouble()
        val medSnr = if (voiced > 0) PitchMath.median(voicedSnrs) else 0.0

        val t = DoubleArray(n) { (it * hop + est.w / 2.0) / sr }
        progress?.invoke(1.0)
        return PitchCurve(
            t = t,
            f0 = f0,
            confidence = conf,
            status = status,
            estimated = estimated,
            rms = rms,
            snrDb = snrFrame,
            hop = hop,
            sampleRate = sr,
            hopMs = hopMs,
            agreementPct = if (agreeTotal > 0) 100.0 * agreeOk / agreeTotal else 0.0,
            medianSnrDb = medSnr,
            voicedFrames = voiced,
            uncertainFrames = uncertain,
            estimatedFrames = estCount,
            repairedOctaves = repaired,
            repairedUp = repairDir[0],
            repairedDown = repairDir[1],
            globalNoiseRms = globalFloor
        )
    }

    // ─────────────────────────── A) اعتبارسنجی ───────────────────────────

    /**
     * اعتماد یک فریم از ترکیب وزنیِ چند شاهد مستقل. هیچ شاهدی به‌تنهایی
     * «تصمیم» نمی‌گیرد؛ ضرب هندسی یعنی اگر یکی از شواهد به‌شدت بد باشد،
     * اعتماد پایین می‌آید، ولی یک شاهدِ متوسط بقیه را از بین نمی‌برد.
     */
    class ConfidenceParts(
        val snr: Double,
        val period: Double,
        val agree: Double,
        val harm: Double,
        val cov: Double,
        val flat: Double,
        val flux: Double,
        val clip: Double,
        val total: Double
    )

    internal fun confidenceParts(
        snrDb: Double,
        yinF0: Double,
        yinClarity: Double,
        mpmF0: Double,
        mpmClarity: Double,
        support: Double,
        coverage: Double,
        flatness: Double,
        flux: Double,
        clipFrac: Double,
        snrTrust: Double = 1.0,
        fundProminence: Double = 1.0
    ): ConfidenceParts {
        val sSnrRaw = clamp01((snrDb + 4.0) / 14.0)
        val sSnr = sSnrRaw * snrTrust + 0.90 * (1.0 - snrTrust)
        val bestPeriod = max(yinClarity, mpmClarity)
        val sPeriod = clamp01((bestPeriod - 0.55) / 0.25)
        val sAgree = when {
            yinF0 > 0.0 && mpmF0 > 0.0 -> {
                val c = abs(PitchMath.cents(yinF0, mpmF0))
                when {
                    c <= 45.0 -> 1.0
                    c <= 120.0 -> 0.6
                    else -> 0.15
                }
            }
            yinF0 > 0.0 || mpmF0 > 0.0 -> 0.55
            else -> 0.05
        }
        val sCount = clamp01((support - 0.20) / 0.45)
        val sCov = clamp01((coverage - 0.22) / 0.33)
        // شاهد هارمونیکی: یا «چند هارمونیک از سری دیده می‌شود» (sCount) یا
        // «چه سهمی از انرژی باند با همین سری توضیح داده می‌شود» (sCov). صدای
        // تُنال با هارمونیک‌های کم (مثلاً نِی یا صدای نازک) نباید فقط به‌خاطر
        // کم بودن شمار هارمونیک‌ها رد شود؛ در عوض همهمهٔ چندمنبعی از sCov
        // ضربه می‌خورد.
        // جایگزینی شمارش هارمونیک با «انرژی توضیح‌داده‌شده» فقط وقتی مجاز است
        // که پایهٔ همان نامزد واقعاً از کف طیف بیرون زده باشد؛ وگرنه نویز
        // کم‌گذر (باد) هم پوشش بالا می‌گرفت.
        // اگر تناوب قوی نباشد، «انرژی متمرکز» شاهد صدا نیست: نویز کم‌گذر (باد)
        // هم می‌تواند بخش بزرگی از انرژی باند را در چند بین جمع کند.
        val periodGate = clamp01((bestPeriod - 0.70) / 0.12)
        val sHarm = max(sCount, sCov * clamp01(fundProminence) * periodGate)
        val sFlat = clamp01((0.58 - flatness) / 0.30)
        val sFlux = 1.0 - 0.35 * clamp01((flux - 0.35) / 0.65)
        val sClip = 1.0 - 0.35 * clamp01((clipFrac - 0.01) / 0.08)

        val wS = 1.0
        val wP = 2.5
        val wA = 1.0
        val wH = 1.2
        val wF = 1.5
        val wX = 0.6
        val wC = 0.3
        val wV = 0.8
        val wSum = wS + wP + wA + wH + wF + wX + wC + wV
        val logSum = wS * ln(max(sSnr, 0.02)) + wP * ln(max(sPeriod, 0.02)) +
            wA * ln(max(sAgree, 0.02)) + wH * ln(max(sHarm, 0.02)) +
            wF * ln(max(sFlat, 0.02)) + wX * ln(max(sFlux, 0.02)) +
            wC * ln(max(sClip, 0.02)) + wV * ln(max(sCov, 0.02))
        return ConfidenceParts(
            sSnr, sPeriod, sAgree, sHarm, sCov, sFlat, sFlux, sClip,
            exp(logSum / wSum).coerceIn(0.0, 1.0)
        )
    }

    internal fun frameConfidence(
        snrDb: Double,
        yinF0: Double,
        yinClarity: Double,
        mpmF0: Double,
        mpmClarity: Double,
        support: Double,
        coverage: Double,
        flatness: Double,
        flux: Double,
        clipFrac: Double,
        snrTrust: Double = 1.0,
        fundProminence: Double = 1.0
    ): Double = confidenceParts(
        snrDb, yinF0, yinClarity, mpmF0, mpmClarity, support, coverage,
        flatness, flux, clipFrac, snrTrust, fundProminence
    ).total

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    /**
     * کف نویز محلی: صدک ۲۰٪ انرژی در بلوک‌های ۰٫۴ ثانیه‌ای، سپس هموارسازی
     * میانه‌ای. این‌طور نویزِ متغیر (باد، همهمهٔ کم‌شدت، هیس) در طول فایل
     * ردیابی می‌شود و «یک عدد برای کل فایل» فرض نمی‌شود.
     */
    private fun localNoiseFloor(rms: FloatArray, hop: Int, sr: Int): DoubleArray {
        val n = rms.size
        val blockFrames = max(1, (0.4 * sr / hop).toInt())
        val nb = (n + blockFrames - 1) / blockFrames
        val block = DoubleArray(nb)
        val scratch = FloatArray(blockFrames)
        for (b in 0 until nb) {
            val a = b * blockFrames
            val z = min(n, a + blockFrames)
            val cnt = z - a
            if (cnt <= 0) { block[b] = 1e-6; continue }
            System.arraycopy(rms, a, scratch, 0, cnt)
            val part = scratch.copyOf(cnt)
            part.sort()
            val idx = (0.20 * (cnt - 1)).toInt().coerceIn(0, cnt - 1)
            block[b] = max(part[idx].toDouble(), 1e-7)
            if (cnt < 4) block[b] = max(part[0].toDouble(), 1e-7)
        }
        // هموارسازی میانه‌ای ۳ بلوکی
        val sm = DoubleArray(nb)
        for (b in 0 until nb) {
            val a = max(0, b - 1)
            val z = min(nb - 1, b + 1)
            sm[b] = PitchMath.median(DoubleArray(z - a + 1) { block[a + it] })
        }
        // درون‌یابی خطی روی مراکز بلوک‌ها
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val pos = i.toDouble() / blockFrames - 0.5
            val b0 = pos.toInt().coerceIn(0, nb - 1)
            val b1 = min(nb - 1, b0 + 1)
            val frac = (pos - b0).coerceIn(0.0, 1.0)
            out[i] = max(1e-7, sm[b0] * (1.0 - frac) + sm[b1] * frac)
        }
        return out
    }

    // ─────────────────────────── C) ردیابی ───────────────────────────

    /**
     * Viterbi روی نامزدهای هر فریم + حالت «نامعتبر».
     *
     * هزینهٔ گذار با فاصلهٔ سنت رشد می‌کند ولی سقف دارد: تغییر واقعی و سریع
     * (تحریر) جریمهٔ نامتناهی نمی‌گیرد؛ فقط اگر نامزد دیگری با پیوستگیِ بهتر
     * وجود داشته باشد ترجیح داده می‌شود. پرش اکتاوی جریمهٔ کوچکی دارد تا در
     * حالت «هم‌امتیاز» پیوستگی برنده شود، نه اینکه تحریرِ واقعی حذف شود.
     */
    internal fun track(
        n: Int,
        conf: FloatArray,
        candF0: FloatArray,
        candScore: FloatArray,
        candCnt: ByteArray
    ): IntArray {
        val MAXC = DualEstimator.MAX_CAND
        val S = MAXC + 1
        val INF = 1e9f
        val prev = FloatArray(S)
        val cur = FloatArray(S)
        val back = Array(S) { ByteArray(n) }
        val invalidE0: Float = invalidE(conf[0])
        for (j in 0 until S) {
            cur[j] = if (j == MAXC) invalidE0
            else if (j < candCnt[0]) (1.6 * (1.0 - conf[0]) + 1.2 * (1.0 - candScore[j])).toFloat() else INF
        }
        System.arraycopy(cur, 0, prev, 0, S)
        for (i in 1 until n) {
            val c = conf[i]
            val cnt = candCnt[i].toInt()
            for (j in 0 until S) {
                val em: Float = if (j == MAXC) invalidE(c)
                else if (j < cnt) (1.6 * (1.0 - c) + 1.2 * (1.0 - candScore[i * MAXC + j])).toFloat()
                else INF
                var best = INF
                var bi = 0
                for (p in 0 until S) {
                    val pv = prev[p]
                    if (pv >= INF * 0.5f) continue
                    val tr: Float = when {
                        p == MAXC && j == MAXC -> 0f
                        p == MAXC -> 1.4f
                        j == MAXC -> 0.9f
                        else -> {
                            val a = candF0[(i - 1) * MAXC + p].toDouble()
                            val b = candF0[i * MAXC + j].toDouble()
                            if (a <= 0.0 || b <= 0.0) 9f
                            else {
                                val cents = abs(PitchMath.cents(b, a))
                                var cost = min(9.0, (cents / 85.0) * (cents / 85.0))
                                if (cents > 1100.0 && cents < 1300.0) cost += 0.8
                                cost.toFloat()
                            }
                        }
                    }
                    val v = pv + tr
                    if (v < best) { best = v; bi = p }
                }
                cur[j] = best + em
                back[j][i] = bi.toByte()
            }
            System.arraycopy(cur, 0, prev, 0, S)
        }
        // بهترین حالت پایانی
        var last = 0
        var bestLast = INF
        for (j in 0 until S) if (prev[j] < bestLast) { bestLast = prev[j]; last = j }
        val out = IntArray(n)
        out[n - 1] = last
        for (i in n - 1 downTo 1) out[i - 1] = back[out[i]][i].toInt()
        return out
    }

    private fun invalidE(conf: Float): Float = (0.25 + 3.2 * conf).toFloat()

    /**
     * اصلاح پرش‌های پرت: اگر فریمی بیش از [Options.outlierCents] از میانهٔ
     * همسایگانش دور باشد و در همان فریم نامزدی نزدیک به آن میانه وجود داشته
     * باشد، همان نامزد جایگزین می‌شود. این کار خطای اکتاو/گام را بدون
     * صاف‌کردن منحنی برمی‌دارد (چون فقط پرش‌های غیرقابل‌توجیه را عوض می‌کند).
     */
    private fun repairOutliers(
        f0: DoubleArray,
        candF0: FloatArray,
        candCnt: ByteArray,
        options: Options,
        dir: IntArray
    ): Int {
        val n = f0.size
        val MAXC = DualEstimator.MAX_CAND
        var repaired = 0
        val radius = 8
        val neigh = DoubleArray(2 * radius + 1)
        for (i in 1 until n - 1) {
            if (f0[i] <= 0.0) continue
            var cnt = 0
            for (k in max(0, i - radius)..min(n - 1, i + radius)) {
                if (k == i || f0[k] <= 0.0) continue
                neigh[cnt++] = f0[k]
            }
            if (cnt < 4) continue
            val med = PitchMath.median(neigh.copyOf(cnt))
            if (med <= 0.0) continue
            if (abs(PitchMath.cents(f0[i], med)) <= options.outlierCents) continue
            val cntC = candCnt[i].toInt()
            var best = f0[i]
            var bestD = Double.MAX_VALUE
            for (j in 0 until cntC) {
                val c = candF0[i * MAXC + j].toDouble()
                if (c <= 0.0) continue
                val d = abs(PitchMath.cents(c, med))
                if (d < bestD && d <= options.repairCents) { bestD = d; best = c }
            }
            if (best != f0[i]) {
                if (best > f0[i]) dir[0]++ else dir[1]++
                f0[i] = best
                repaired++
            }
        }
        return repaired
    }

    /** حذف ران‌های صدادارِ بسیار کوتاه که با اعتماد پایین کشف شده‌اند */
    private fun dropShortRuns(f0: DoubleArray, conf: FloatArray) {
        val n = f0.size
        var i = 0
        while (i < n) {
            if (f0[i] <= 0.0) { i++; continue }
            var j = i
            var sum = 0.0
            while (j < n && f0[j] > 0.0) { sum += conf[j]; j++ }
            val len = j - i
            val meanConf = sum / max(1, len)
            if (len < 4 && meanConf < 0.85) for (k in i until j) f0[k] = 0.0
            i = j
        }
    }

    /**
     * درون‌یابی شکاف‌های کوتاه بین دو ناحیهٔ معتبر (حداکثر ۶۰ms پیش‌فرض).
     * فریم‌های پر‌شده با estimated=true علامت می‌خورند تا هیچ‌جا با دادهٔ
     * اندازه‌گیری‌شده اشتباه نشوند. شکاف بلند هرگز پر نمی‌شود.
     */
    private fun fillShortGaps(
        f0: DoubleArray,
        estimated: BooleanArray,
        rms: FloatArray,
        conf: FloatArray,
        hop: Int,
        sr: Int,
        maxGapSec: Double
    ) {
        val n = f0.size
        val maxGap = max(1, (maxGapSec * sr / hop).toInt())
        var i = 0
        while (i < n) {
            if (f0[i] > 0.0) { i++; continue }
            var j = i
            while (j < n && f0[j] <= 0.0) j++
            val gap = j - i
            if (gap in 1..maxGap && i > 0 && j < n) {
                val a = f0[i - 1]
                val b = f0[j]
                if (a > 0.0 && b > 0.0 && abs(PitchMath.cents(b, a)) <= 250.0) {
                    // اگر داخل شکاف انرژی به‌شدت افتاده (سکوت واقعی)، پر نکن
                    var minR = Double.MAX_VALUE
                    var maxR = 0.0
                    for (k in i until j) { minR = min(minR, rms[k].toDouble()); maxR = max(maxR, rms[k].toDouble()) }
                    val edge = 0.5 * (rms[i - 1] + rms[j])
                    // شاهد اعتبار در شکاف: فریم‌های شکاف باید دست‌کم «نامطمئن»
                    // باشند نه سکوت/نویز خالص (وگرنه درون‌یابی ساختگی می‌شود)
                    var trusted = true
                    for (k in i until j) if (conf[k] < 0.30f) { trusted = false; break }
                    if (trusted && (edge <= 0.0 || minR >= 0.22 * edge)) {
                        for (k in i until j) {
                            val frac = (k - i + 1).toDouble() / (gap + 1)
                            f0[k] = a * (b / a).pow(frac)
                            estimated[k] = true
                        }
                    }
                }
            }
            i = j
        }
    }

    // ─────────────────────────── آمار کمکی ───────────────────────────

    /** نسبت فریم‌های معتبر در یک بازهٔ زمانی (برای هشدار کیفیت) */
    fun validShareIn(curve: PitchCurve, from: Double, to: Double): Double {
        if (curve.size == 0) return 0.0
        var total = 0
        var valid = 0
        for (i in 0 until curve.size) {
            val t = curve.t[i]
            if (t < from || t > to) continue
            total++
            if (curve.f0[i] > 0.0) valid++
        }
        return if (total > 0) valid.toDouble() / total else 0.0
    }

    /** تبدیل فرکانس به شمارهٔ نیم‌پرده با دقت اعشاری (برای تحلیل فواصل) */
    fun centsBetween(a: Double, b: Double): Double = PitchMath.cents(a, b)

    /** میانهٔ گام به گام منحنی (سنت بر ثانیه) — شاهد «سرعت تغییر» برای تحریر */
    fun contourSlopeCentsPerSec(f0: DoubleArray, t: DoubleArray): DoubleArray {
        val n = f0.size
        val out = DoubleArray(n)
        for (i in 1 until n - 1) {
            if (f0[i] <= 0.0 || f0[i - 1] <= 0.0 || f0[i + 1] <= 0.0) continue
            val dt = t[i + 1] - t[i - 1]
            if (dt <= 0.0) continue
            out[i] = PitchMath.cents(f0[i + 1], f0[i - 1]) / dt
        }
        return out
    }

    /** تعداد نیم‌پردهٔ گردشده — فقط برای گزارش، نه برای جایگزینی دادهٔ خام */
    fun roundedSemitones(f0: Double): Int = if (f0 > 0.0) f0.let { PitchMath.nearestMidi(it) } else -1

    /** اختلاف دو فرکانس بر حسب سنت با مدیریت صفر */
    fun safeCents(a: Double, b: Double): Double =
        if (a > 0.0 && b > 0.0) 1200.0 * kotlin.math.log2(a / b) else 0.0

    /** درجهٔ آزادی کمکی برای میانگین‌گیری در تست‌ها */
    fun medianF0(values: DoubleArray): Double = PitchMath.median(values)

    fun rounded(v: Double): Int = v.roundToInt()
}
