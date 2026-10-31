package ir.hamyareqari.dsp.pitch

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * Quality Gate چندشاهدی — تصمیم «معتبر/نامطمئن/نامعتبر/سکوت» برای هر فریم.
 *
 * اصل‌ها:
 *  • هیچ شاهدی به‌تنهایی تصمیم نمی‌گیرد؛ امتیاز ترکیبی ضرب هندسی وزنی است
 *    (اگر یک شاهد به‌شدت بد باشد امتیاز می‌افتد، ولی یک شاهد متوسط بقیه را
 *    نابود نمی‌کند).
 *  • «بلندی صدا» فقط شاهد وضعیت است؛ هرگز مقدار F0 را جابه‌جا نمی‌کند.
 *  • سکوت نسبی سنجیده می‌شود (نسبت به کف نویز محلی و مرجع دامنه)، نه یک
 *    آستانهٔ مطلق که با گین فایل بی‌معنا شود.
 *  • خروجی «کیفیت ترکیبی برنامه» است (`qualityScore`) و نه confidence رسمی
 *    هیچ مدلی.
 *  • نویز/تشویق/همهمه نباید به «پذیرش اشتباه» منجر شود؛ شواهد تناوبی
 *    (periodicity، agreement، flatness) برای رد آن‌ها کافی است.
 */
class PitchQualityGate(
    /** حداقل امتیاز برای «معتبر» */
    val validScore: Double = 0.60,
    /** زیر این حد، فریم «نامطمئن» است */
    val minScore: Double = 0.42
) {

    /** شواهد یک فریم — همه از بیرون می‌آیند تا gate روی JVM قابل تست باشد */
    class Evidence(
        val f0Hz: Double,
        val loudnessRms: Double,
        /** کف نویز محلی در همین لحظه (از پیش محاسبه‌شده) */
        val noiseFloorRms: Double,
        /** مرجع دامنه (مثلاً صدک ۹۵٪ انرژی) — برای تشخیص سکوت نسبی */
        val referenceRms: Double,
        /** تناوب/شفافیت تخمین‌گر (۰..۱) */
        val periodicity: Double,
        /** اختلاف دو شاهد مستقل بر حسب سنت؛ NaN = شاهد دوم نداریم */
        val agreementCents: Double = Double.NaN,
        /** پخشی طیفی (۱ = نویز سفید)؛ NaN = محاسبه‌نشده */
        val flatness: Double = Double.NaN,
        val clipFrac: Double = 0.0,
        /** اعتماد خام موتور (۰..۱) — فقط یکی از شواهد */
        val engineConfidence: Double = 0.0
    )

    class Verdict(val status: Byte, val qualityScore: Double, val snrDb: Double)

    /** وضعیت پیوستهٔ کف نویز برای مسیر streaming */
    class NoiseTracker(private val attackMs: Double = 400.0, private val releaseMs: Double = 2500.0) {
        var floorRms: Double = 1e-6
            private set
        private var refRms: Double = 1e-4
        private var lastT = Double.NaN

        /** به‌روزرسانی با نمونهٔ جدید؛ فقط فریم‌های آرام کف را پایین می‌آورند */
        fun update(t: Double, rms: Double, voicedLikely: Boolean) {
            val dt = if (lastT.isNaN()) 0.05 else max(0.001, t - lastT)
            lastT = t
            val a = if (rms < floorRms) 1.0 - exp(-dt * 1000.0 / attackMs)
            else 1.0 - exp(-dt * 1000.0 / releaseMs)
            if (!voicedLikely || rms < floorRms * 2.0) {
                floorRms += a * (max(rms, 1e-7) - floorRms)
            }
            if (rms > refRms) refRms += 0.05 * (rms - refRms)
            else refRms += 0.002 * (rms - refRms)
            floorRms = floorRms.coerceIn(1e-7, 1.0)
            refRms = refRms.coerceIn(1e-6, 1.0)
        }

        fun reference(): Double = refRms

        fun reset() {
            floorRms = 1e-6
            refRms = 1e-4
            lastT = Double.NaN
        }
    }

    /**
     * ارزیابی یک فریم. خروجی: وضعیت + qualityScore + SNR تخمینی.
     */
    fun assess(ev: Evidence): Verdict {
        val r = ev.loudnessRms
        val nf = max(ev.noiseFloorRms, 1e-7)
        val ref = max(ev.referenceRms, 1e-6)

        // ── اعتماد به کف نویز: اگر دامنهٔ دینامیکی کم باشد (صدای پیوسته/فشرده)،
        //    صدک بیستم «نویز» نیست بلکه خودِ صداست؛ در این حالت SNR و کف نویز
        //    به‌عنوان شاهد خنثی می‌شوند (نه اینکه بهانهٔ رد کردن شوند) ──
        val dynRangeDb = 20.0 * kotlin.math.log10(ref / nf)
        val floorTrust = clamp01((dynRangeDb - 3.0) / 8.0)

        // ── سکوت نسبی: نه آستانهٔ مطلق، بلکه نسبت به مرجع و (در صورت اعتماد) کف نویز ──
        val silentRel = r <= max(0.045 * ref, 1.2e-4)
        val silentFloor = floorTrust > 0.5 && r <= 1.5 * nf
        val silent = silentRel || silentFloor
        val snrDb = 20.0 * kotlin.math.log10((r + 1e-12) / (nf + 1e-12))

        if (silent) return Verdict(FrameStatus.SILENT, 0.0, snrDb)

        // ── شواهد (هر کدام ۰..۱) ──
        // ۱) نسبت صدا به نویز محلی (با اعتماد پویا — مانند موتور فعلی)
        val eSnrRaw = clamp01((snrDb + 4.0) / 14.0)
        val eSnr = eSnrRaw * floorTrust + 0.90 * (1.0 - floorTrust)
        // ۲) تناوب — قوی‌ترین شاهد «صدای واقعی» بودن
        val ePeriod = clamp01((ev.periodicity - 0.55) / 0.25)
        // ۳) توافق دو تخمین‌گر/شاهد مستقل
        val eAgree = if (ev.agreementCents.isNaN()) 0.55
        else when {
            abs(ev.agreementCents) <= 45.0 -> 1.0
            abs(ev.agreementCents) <= 120.0 -> 0.6
            else -> 0.15
        }
        // ۴) پخشی طیفی — نویز سفید/همهمه را می‌زند
        val eFlat = if (ev.flatness.isNaN()) 0.6 else clamp01((0.58 - ev.flatness) / 0.30)
        // ۵) کلیپینگ
        val eClip = 1.0 - 0.35 * clamp01((ev.clipFrac - 0.01) / 0.08)
        // ۶) اعتماد خام موتور
        val eConf = clamp01(ev.engineConfidence)
        // ۷) فاصله از کف نویز — فقط وقتی کف قابل‌اتکاست
        val eFloorRaw = clamp01((r / nf - 1.6) / 2.0)
        val eFloor = eFloorRaw * floorTrust + 0.55 * (1.0 - floorTrust)

        // ── ضرب هندسی وزنی ──
        val wS = 1.0; val wP = 2.5; val wA = 1.2; val wF = 1.2
        val wC = 0.3; val wE = 1.0; val wN = 1.0
        val wSum = wS + wP + wA + wF + wC + wE + wN
        val logSum = wS * ln(max(eSnr, 0.02)) + wP * ln(max(ePeriod, 0.02)) +
            wA * ln(max(eAgree, 0.02)) + wF * ln(max(eFlat, 0.02)) +
            wC * ln(max(eClip, 0.02)) + wE * ln(max(eConf, 0.02)) +
            wN * ln(max(eFloor, 0.02))
        val score = exp(logSum / wSum).coerceIn(0.0, 1.0)

        // ── بدون تخمین F0، «معتبر» بی‌معنی است ──
        val hasF0 = ev.f0Hz > 0.0 && ev.f0Hz.isFinite()

        val status = when {
            !hasF0 -> if (score >= minScore) FrameStatus.UNCERTAIN else FrameStatus.INVALID
            score >= validScore -> FrameStatus.VALID
            score >= minScore -> FrameStatus.UNCERTAIN
            else -> FrameStatus.INVALID
        }
        return Verdict(status, if (hasF0) score else score * 0.5, snrDb)
    }

    private fun clamp01(v: Double): Double = if (v < 0.0) 0.0 else if (v > 1.0) 1.0 else v

    companion object {
        /**
         * کف نویز محلی برای مسیر batch: صدک ۲۰٪ انرژی در بلوک‌های ~۰٫۴ ثانیه‌ای
         * با هموارسازی میانه‌ای — نویز متغیر در طول فایل ردیابی می‌شود.
         */
        fun batchNoiseFloor(rms: DoubleArray, hopMs: Double): DoubleArray {
            val n = rms.size
            if (n == 0) return DoubleArray(0)
            val blockFrames = max(1, (400.0 / hopMs).toInt())
            val nb = (n + blockFrames - 1) / blockFrames
            val block = DoubleArray(nb)
            for (b in 0 until nb) {
                val a = b * blockFrames
                val z = min(n, a + blockFrames)
                val part = rms.copyOfRange(a, z)
                part.sort()
                block[b] = max(part[(0.20 * (part.size - 1)).toInt().coerceIn(0, part.size - 1)], 1e-7)
            }
            val sm = DoubleArray(nb)
            for (b in 0 until nb) {
                val a = max(0, b - 1)
                val z = min(nb - 1, b + 1)
                val part = DoubleArray(z - a + 1) { block[a + it] }
                part.sort()
                sm[b] = part[part.size / 2]
            }
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

        /** مرجع دامنه (صدک ۹۵) + اعتماد پویای SNR مانند موتور فعلی */
        fun batchReference(rms: DoubleArray): Double {
            if (rms.isEmpty()) return 1e-4
            val s = rms.copyOf()
            s.sort()
            return max(s[(0.95 * (s.size - 1)).toInt()], 1e-5)
        }
    }
}
