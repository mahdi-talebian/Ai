package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.DualEstimator
import ir.hamyareqari.dsp.Fft
import ir.hamyareqari.dsp.FramePitch
import ir.hamyareqari.dsp.Harmonics
import ir.hamyareqari.dsp.PitchEngine
import ir.hamyareqari.dsp.Signal
import ir.hamyareqari.dsp.YinTracker
import ir.hamyareqari.dsp.hann
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * آداپتر موتور فعلی (ENHANCED) روی قرارداد [PitchEstimator].
 *
 * هیچ تغییری در خود موتور نمی‌دهد — فقط آن را پشت رابط مشترک می‌آورد:
 *  • مسیر فایل: [PitchEngine.analyze] (YIN+MPM، اعتبارسنجی فریم، ردیابی نامزدها،
 *    اصلاح اکتاو) — دقیقاً همان چیزی که `Analyzer` امروز صدا می‌زند.
 *  • مسیر زنده: [YinTracker] + [Harmonics.quickOctaveFix] — دقیقاً همان
 *    ترتیبی که `MainActivity.LiveEngine` امروز اجرا می‌کند.
 *
 * خروجی: [RawPitchFrame] با شواهد لازم برای Quality Gate.
 */
class EnhancedKotlinBackend(
    private val fMin: Double = DEFAULT_FMIN,
    private val fMax: Double = DEFAULT_FMAX
) : PitchEstimator {

    override val backendId: BackendId = BackendId.ENHANCED

    // ── وضعیت مسیر زنده (یک نمونه از backend برای کل جلسهٔ زنده) ──
    private var yin: YinTracker? = null
    private var frameBuf: DoubleArray? = null
    private var fp: FramePitch? = null
    private var fft: Fft? = null
    private var fre: DoubleArray? = null
    private var fim: DoubleArray? = null
    private var scratch: DoubleArray? = null
    private var fwin: DoubleArray? = null
    private var liveRate = 0

    private fun ensureLive(sr: Int) {
        if (yin != null && liveRate == sr) return
        liveRate = sr
        yin = YinTracker(sr, fMin, fMax, 0.055)
        frameBuf = DoubleArray(yin!!.w)
        fp = FramePitch()
        fft = Fft(2048)
        fre = DoubleArray(2048)
        fim = DoubleArray(2048)
        scratch = DoubleArray(2048)
        fwin = hann(2048)
    }

    override fun reset() {
        yin = null
        frameBuf = null
        fp = null
        fft = null
        fre = null
        fim = null
        scratch = null
        fwin = null
        liveRate = 0
    }

    /**
     * تخمین یک فریم — همان مسیر LiveEngine: YIN تازه، شرط clarity، اصلاح سریع
     * اکتاو. agreementCents = اختلاف تخمین خام و اصلاح‌شده (شاهد دوم).
     */
    override fun estimateFrame(sig: Signal, startSample: Int, timestampSec: Double): RawPitchFrame {
        ensureLive(sig.sampleRate)
        val y = yin!!
        val win = y.w
        if (startSample + win > sig.size) return unvoiced(timestampSec)
        val f = fp!!
        y.pitchAt(sig, startSample, f, frameBuf!!)
        val rawF0 = f.f0
        val clarity = f.clarity
        var fixedF0 = 0.0
        if (rawF0 > 0 && clarity > 0.5) {
            fixedF0 = Harmonics.quickOctaveFix(
                sig, sig.sampleRate, startSample, min(win, 2048), rawF0,
                fft!!, fre!!, fim!!, scratch!!, fwin!!
            )
            if (fixedF0 <= 0) fixedF0 = rawF0
        }
        // RMS فریم
        val buf = DoubleArray(min(win, sig.size - startSample))
        sig.read(startSample, buf.size, buf)
        var acc = 0.0
        for (v in buf) acc += v * v
        val rms = if (buf.isNotEmpty()) kotlin.math.sqrt(acc / buf.size) else 0.0

        val f0 = if (clarity > 0.5) fixedF0 else 0.0
        return RawPitchFrame(
            timestampSec = timestampSec,
            f0Hz = f0,
            confidence = if (f0 > 0) clarity else 0.0,
            loudnessRms = rms,
            snrDb = 0.0, // در مسیر زنده با NoiseTracker gate محاسبه می‌شود
            periodicity = if (f0 > 0) clarity else 0.0,
            agreementCents = if (rawF0 > 0 && fixedF0 > 0 && rawF0 != fixedF0)
                abs(1200.0 * kotlin.math.log2(fixedF0 / rawF0)) else Double.NaN,
            backend = BackendId.ENHANCED.tag
        )
    }

    /**
     * مسیر فایل — [PitchEngine.analyze] با همان تنظیمات `Analyzer` (گام ۵ یا ۱۰ms).
     * فریم‌ها از PitchCurve استخراج می‌شوند (raw f0، confidence، rms، snr).
     */
    override fun estimateAll(
        sig: Signal,
        hopMs: Double,
        progress: ((Double) -> Unit)?
    ): List<RawPitchFrame> {
        val curve = PitchEngine.analyze(
            sig,
            hopMs = hopMs,
            options = PitchEngine.Options(fMin = fMin, fMax = fMax),
            progress = progress
        )
        return fromCurve(curve)
    }

    /** تبدیل خروجی موتور فعلی به قرارداد مشترک (برای هر دو مسیر) */
    fun fromCurve(curve: ir.hamyareqari.dsp.PitchCurve): List<RawPitchFrame> {
        val n = curve.size
        val out = ArrayList<RawPitchFrame>(n)
        for (i in 0 until n) {
            out.add(
                RawPitchFrame(
                    timestampSec = curve.t[i],
                    f0Hz = curve.f0[i],
                    confidence = curve.confidence[i].toDouble(),
                    loudnessRms = curve.rms[i].toDouble(),
                    snrDb = curve.snrDb[i].toDouble(),
                    periodicity = curve.confidence[i].toDouble(),
                    agreementCents = Double.NaN,
                    backend = BackendId.ENHANCED.tag
                )
            )
        }
        return out
    }

    private fun unvoiced(t: Double) = RawPitchFrame(
        timestampSec = t,
        f0Hz = 0.0,
        confidence = 0.0,
        loudnessRms = 0.0,
        snrDb = 0.0,
        periodicity = 0.0,
        agreementCents = Double.NaN,
        backend = BackendId.ENHANCED.tag
    )

    companion object {
        const val DEFAULT_FMIN = 55.0
        const val DEFAULT_FMAX = 1200.0
    }
}
