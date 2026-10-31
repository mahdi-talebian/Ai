package ir.hamyareqari.dsp.pitch

/**
 * قرارداد مشترک خروجی موتورهای Pitch — فایل و میکروفن زنده.
 *
 * هر دو ورودی (فایل صوتی و میکروفن) باید به همین قرارداد برسند تا نمودار،
 * JSON و تست‌ها روی یک نوع داده کار کنند. نامِ نت فقط برچسب نمایشی است؛
 * مقدار دقیق Hz و Cent همیشه جدا نگه داشته می‌شود. بلندی صدا (loudness)
 * هرگز وارد محاسبهٔ F0 نمی‌شود — فقط شاهد کیفیت/وضعیت است.
 */

/** وضعیت هر نمونه — همان مقادیر [ir.hamyareqari.dsp.PitchCurve] تا جابه‌جایی داده ساده بماند */
object FrameStatus {
    const val SILENT: Byte = 0
    const val INVALID: Byte = 1
    const val UNCERTAIN: Byte = 2
    const val VALID: Byte = 3
    const val ESTIMATED: Byte = 4

    fun name(s: Byte): String = when (s) {
        SILENT -> "SILENT"
        INVALID -> "INVALID"
        UNCERTAIN -> "UNCERTAIN"
        VALID -> "VALID"
        ESTIMATED -> "ESTIMATED"
        else -> "UNKNOWN"
    }
}

/** هویت backend تولیدکنندهٔ داده */
enum class BackendId(val tag: String) {
    /** مسیر امن فعلی: YIN + MPM + اعتبارسنجی فریم + ردیابی (بدون تغییر) */
    ENHANCED("enhanced"),

    /** مدل FCPE با ONNX Runtime — فقط وقتی runtime و مدل واقعاً بارگذاری شوند */
    FCPE_ONNX("fcpe_onnx");

    companion object {
        fun fromTag(tag: String): BackendId =
            values().firstOrNull { it.tag == tag } ?: ENHANCED
    }
}

/**
 * خروجی خام هر تخمین‌گر، پیش از Quality Gate و Stabilizer.
 * مقادیر «شاهد» (periodicity، agreementCents، …) فقط برای تصمیم کیفیت‌اند
 * و هرگز F0 را جابه‌جا نمی‌کنند.
 */
class RawPitchFrame(
    /** زمان نمونه بر حسب ثانیه نسبت به شروع منبع */
    val timestampSec: Double,
    /** فرکانس پایهٔ خام بر حسب Hz — صفر یعنی unvoiced/بدون تخمین */
    val f0Hz: Double,
    /** اعتماد موتور (۰..۱، کالیبره‌نشده) */
    val confidence: Double,
    /** RMS صدا — فقط برای وضعیت/کیفیت، نه برای Pitch */
    val loudnessRms: Double,
    /** نسبت صدا به نویز محلی (dB) */
    val snrDb: Double,
    /** تناوب/شفافیت تخمین‌گر (۰..۱) */
    val periodicity: Double,
    /**
     * اختلاف دو شاهد مستقل بر حسب سنت (مثلاً YIN در برابر MPM یا اصلاح
     * هارمونیکی). NaN یعنی شاهد دوم در دسترس نیست.
     */
    val agreementCents: Double = Double.NaN,
    /** پخشی طیفی (۱ = نویز سفید، نزدیک ۰ = تُنال) — NaN = محاسبه‌نشده */
    val flatness: Double = Double.NaN,
    /** سهم نمونه‌های کلیپ‌شدهٔ فریم */
    val clipFrac: Double = 0.0,
    /** شناسهٔ backend */
    val backend: String = BackendId.ENHANCED.tag
) {
    val voiced: Boolean get() = f0Hz > 0.0 && f0Hz.isFinite()
}

/**
 * قرارداد مشترک هر «نمونه» خروجی موتور — آنچه نمودار و JSON می‌بینند.
 *
 * @property rawF0Hz     F0 خامِ backend (audit/مقایسه) — صفر یعنی بدون تخمین
 * @property stableF0Hz  F0 تثبیت‌شده بعد از Quality Gate و Stabilizer — صفر یعنی شکاف واقعی
 * @property noteName    فقط برچسب نمایشی نزدیک‌ترین نت
 * @property noteMidi    شمارهٔ MIDI نزدیک‌ترین نت (−۱ اگر نتی نباشد)
 * @property cents       انحراف از نزدیک‌ترین نیم‌پرده (−۵۰..+۵۰)؛ NaN اگر نتی نباشد
 * @property qualityScore امتیاز ترکیبی چندشاهدی (۰..۱) — نه confidence رسمی مدل
 * @property loudnessRms بلندی صدا — مستقل از Pitch
 * @property status      وضعیت FrameStatus
 * @property backend     شناسهٔ backend
 */
class PitchSample(
    val timestampSec: Double,
    val rawF0Hz: Double,
    val stableF0Hz: Double,
    val noteName: String,
    val noteMidi: Int,
    val cents: Double,
    val qualityScore: Double,
    val loudnessRms: Double,
    val status: Byte,
    val backend: String
) {
    val hasPitch: Boolean get() = stableF0Hz > 0.0 && stableF0Hz.isFinite()
}

/**
 * رابط مشترک موتورهای Pitch.
 *
 * دو مسیر اجرا دارد:
 *  - frame-by-frame برای میکروفن زنده ([estimateFrame])
 *  - chunk-based برای فایل ([estimateAll]) که پیش‌فرض آن حلقهٔ [estimateFrame] است
 *    و backendهای سنگین (مثل FCPE) آن را با inference تکه‌ای بازنویسی می‌کنند.
 */
interface PitchEstimator {
    val backendId: BackendId

    /** پاک‌سازی وضعیت داخلی بین دو اجرای مستقل */
    fun reset()

    /**
     * تخمین یک فریم از سیگنال آماده‌شده.
     *
     * @param sig        سیگنال منبع (هر نرخی؛ backend داخلی خودش مدیریت می‌کند)
     * @param startSample نمونهٔ شروع پنجرهٔ تحلیل
     * @param timestampSec زمان همین فریم در منبع اصلی
     */
    fun estimateFrame(sig: ir.hamyareqari.dsp.Signal, startSample: Int, timestampSec: Double): RawPitchFrame

    /**
     * تخمین کل سیگنال با گام [hopMs] — مسیر فایل. پیش‌فرض: حلقهٔ [estimateFrame].
     */
    fun estimateAll(
        sig: ir.hamyareqari.dsp.Signal,
        hopMs: Double,
        progress: ((Double) -> Unit)? = null
    ): List<RawPitchFrame> {
        val hop = Math.max(1, (hopMs / 1000.0 * sig.sampleRate).toInt())
        val n = if (sig.size < hop) 0 else (sig.size - 1) / hop + 1
        val out = ArrayList<RawPitchFrame>(n)
        for (i in 0 until n) {
            val start = i * hop
            out.add(estimateFrame(sig, start, start.toDouble() / sig.sampleRate))
            if (progress != null && (i % 64 == 0)) progress(i.toDouble() / Math.max(1, n))
        }
        progress?.invoke(1.0)
        return out
    }
}
