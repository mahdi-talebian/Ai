package ir.hamyareqari.dsp.pitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * تبدیل نرخ نمونهٔ ضد-aliasing برای ورودی مدل (۲۲٬۰۵۰ → ۱۶٬۰۰۰ هرتز).
 *
 * interpolation خطیِ `StreamResampler`/`Wav.resample` برای مدل عصبی کافی نیست
 * (آلیاسینگ بالای ۸kHz وارد mel می‌کند). این کلاس درون‌یابی sinc پنجره‌ای
 * (Kaiser) با برش فرکانسی زیر Nyquist مقصد انجام می‌دهد.
 *
 * کاربرد:
 *  - batch برای مسیر فایل ([process])
 *  - streaming با بافر دنباله برای مسیر زنده ([StreamingSincResampler])
 *
 * دقت: سیگنال زیر ۰٫۹×Nyquist مقصد با موج کوچک (<۰٫۳dB) عبور می‌کند؛
 * تُن‌های بالاتر از Nyquist مقصد ≥۴۰dB تضعیف می‌شوند (در تست ModelResamplerTest).
 */
class ModelResampler(
    val fromRate: Int,
    val toRate: Int,
    /** نیم‌طول فیلتر (تعداد ضرایب هر طرف) — ۳۲ تعادل دقت/هزینه */
    private val halfTaps: Int = 32,
    /** ضریب برش (نسبت به Nyquist مقصد) */
    private val cutoff: Double = 0.95
) {
    init {
        require(fromRate > 0 && toRate > 0) { "نرخ‌ها باید مثبت باشند" }
        require(halfTaps >= 4) { "halfTaps خیلی کوچک است" }
    }

    private val ratio = toRate.toDouble() / fromRate.toDouble()
    private val nyquist = min(fromRate, toRate) / 2.0
    private val fc = cutoff * nyquist / fromRate.toDouble() // برش نرمال‌شده به نرخ مبدأ (سیکل/نمونه)
    private val beta = 8.6 // پنجرهٔ Kaiser — تضعیف لبه ≈ ۹۰dB

    /** ضرایب پنجره‌دار sinc در قالب L فاز (L = toRate / gcd) */
    private val gcd = gcdOf(fromRate, toRate)
    private val phases = toRate / gcd
    private val tapsPerPhase = 2 * halfTaps
    private val coeffs = buildPolyphase()

    private fun gcdOf(a: Int, b: Int): Int {
        var x = a; var y = b
        while (y != 0) { val t = x % y; x = y; y = t }
        return max(1, x)
    }

    private fun kaiser(n: Double): Double {
        // I0(beta*sqrt(1-n^2)) / I0(beta)
        val r = 1.0 - n * n
        val x = beta * sqrt(max(0.0, r))
        return besselI0(x) / besselI0(beta)
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x * x / 4.0
        for (k in 1..24) {
            term *= half / (k.toDouble() * k.toDouble())
            sum += term
            if (term < 1e-12 * sum) break
        }
        return sum
    }

    /**
     * ساخت ماتریس فاز: coeffs[p][k] ضریب فاز p در لغزش k.
     * خروجی: out[j] = Σ_k in[srcIdx(j) - k] * coeffs[phase(j)][k]
     */
    private fun buildPolyphase(): Array<DoubleArray> {
        val out = Array(phases) { DoubleArray(tapsPerPhase) }
        // مقیاس نرخ: هنگام درون‌یابی، موقعیت خروجی j در دامنهٔ مبدأ = j / ratio
        // ضریب‌ها: sinc(2*fc*(pos - k)) * kaiser در بازهٔ [-halfTaps, halfTaps]
        for (p in 0 until phases) {
            // لغزش کسری این فاز
            val frac = p.toDouble() / phases
            for (k in 0 until tapsPerPhase) {
                val m = k - halfTaps + 1 // مکان نمونهٔ مبدأ نسبت به مرکز
                val x = (m - frac) * ratio // به «گام خروجی» نرمال شود
                out[p][k] = 2.0 * fc * ratio * sinc(2.0 * fc * ratio * x) * kaiser((x / halfTaps).coerceIn(-1.0, 1.0))
            }
            // نرمال‌سازی DC هر فاز (پاسخ مسطح در فرکانس صفر)
            var s = 0.0
            for (v in out[p]) s += v
            if (abs(s) > 1e-12) for (k in 0 until tapsPerPhase) out[p][k] /= s
        }
        return out
    }

    private fun sinc(x: Double): Double = if (abs(x) < 1e-12) 1.0 else sin(PI * x) / (PI * x)

    /** تعداد نمونه‌های خروجی برای ورودی n نمونه‌ای */
    fun outputSize(n: Int): Long =
        if (n <= 0) 0L else ((n.toLong() * toRate + fromRate / 2) / fromRate)

    /**
     * تبدیل batch — حاشیه‌ها صفر-حاشیه نیستند؛ لبه‌ها با همان فیلتر و
     * نمونه‌های صفرِ فرضی محاسبه می‌شوند (برای پنجره‌های کوتاه هم پایدار است).
     */
    fun process(input: DoubleArray): DoubleArray {
        if (fromRate == toRate) return input.copyOf()
        val nOut = outputSize(input.size).toInt()
        if (nOut <= 0) return DoubleArray(0)
        val out = DoubleArray(nOut)
        for (j in 0 until nOut) {
            // موقعیت j در دامنهٔ مبدأ
            val pos = j.toDouble() * fromRate.toDouble() / toRate.toDouble()
            val base = Math.floor(pos).toInt()
            val fracIdx = ((pos - base) * phases).toInt().coerceIn(0, phases - 1)
            val c = coeffs[fracIdx]
            var acc = 0.0
            for (k in 0 until tapsPerPhase) {
                val idx = base - halfTaps + 1 + k
                if (idx in input.indices) acc += input[idx] * c[k]
            }
            out[j] = acc
        }
        return out
    }

    /** تبدیل از نمونه‌های ۱۶ بیتی با مقیاس استاندارد */
    fun processShort(input: ShortArray): DoubleArray {
        val d = DoubleArray(input.size)
        for (i in input.indices) d[i] = input[i] / 32768.0
        return process(d)
    }
}

/**
 * نسخهٔ streaming برای مسیر زنده: نمونه‌ها را جمع می‌کند و هر بار که به اندازهٔ
 * کافی داده آمد، بلوک خروجی ۱۶kHz می‌دهد.
 *
 * هم‌ترازی شبکه: با trim کردن جلوی بافر فقط در مضرب‌های from/gcd نمونه،
 * شبکهٔ خروجی هر بلوک دقیقاً روی شبکهٔ سراسری می‌افتد (گام خروجی = to/gcd)
 * و هیچ نمونه‌ای از دست نمی‌رود یا دوبار تولید نمی‌شود.
 */
class StreamingSincResampler(
    fromRate: Int,
    toRate: Int,
    halfTaps: Int = 32
) {
    private val core = ModelResampler(fromRate, toRate, halfTaps)
    private val g = gcdOf(fromRate, toRate)
    /** گام ورودی که برابر گام خروجی صحیح است */
    private val inStep = fromRate / g
    private val outStep = toRate / g
    private val marginIn = 2 * halfTaps + 2

    private val pending = ArrayList<Double>(8192)
    /** شمارهٔ سراسری اولین خروجیِ معادل pending[0] */
    private var baseOut = 0L
    /** شمارهٔ سراسری خروجیِ تحویل‌شده تاکنون */
    private var emittedOut = 0L

    private fun gcdOf(a: Int, b: Int): Int {
        var x = a; var y = b
        while (y != 0) { val t = x % y; x = y; y = t }
        return max(1, x)
    }

    /** نمونه‌های جدید را می‌گیرد و هر نمونهٔ خروجی آماده را به sink می‌دهد */
    fun push(src: ShortArray, count: Int, sink: (Double) -> Unit) {
        for (i in 0 until count) pending.add(src[i] / 32768.0)
        emitReady(sink, final = false)
    }

    private fun emitReady(sink: (Double) -> Unit, final: Boolean) {
        if (pending.size < marginIn + 2) return
        val block = DoubleArray(pending.size)
        for (i in pending.indices) block[i] = pending[i]
        val out = core.process(block)
        // out[j] ↔ شبکهٔ سراسری baseOut + j (به شرط هم‌ترازی)
        val lastSafeJ = if (final) out.size - 1
        else {
            // فقط خروجی‌هایی که context ورودی کامل در سمت راست دارند
            var j = 0
            while (j < out.size) {
                val inPos = j.toLong() * inStep / outStep
                if (inPos > pending.size - marginIn) break
                j++
            }
            j - 1
        }
        for (j in 0..lastSafeJ) {
            val gOut = baseOut + j
            if (gOut < emittedOut) continue
            sink(out[j])
            emittedOut = gOut + 1
        }
        // trim جلوی بافر — فقط در گام‌های هم‌تراز
        val trimSteps = (emittedOut - baseOut) / outStep
        val trimIn = (trimSteps * inStep).toInt()
        if (trimIn > 0 && trimIn < pending.size - marginIn) {
            repeat(trimIn) { pending.removeAt(0) }
            baseOut += trimSteps * outStep
        }
    }

    fun reset() {
        pending.clear()
        baseOut = 0
        emittedOut = 0
    }

    /** نمونه‌های باقی‌مانده را تخلیه کن (پایان منبع) */
    fun flush(sink: (Double) -> Unit) {
        emitReady(sink, final = true)
        pending.clear()
    }
}
