package ir.hamyareqari.dsp.pitch

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Stabilizer منحنی F0 — محافظت نمودار بدون حذف تحریر واقعی.
 *
 * لایه‌ها (به ترتیب):
 *   ۱) تشخیص خطای اکتاو: پرش ≈×۲/÷۲ که با شاهد همسایگی اصلاح‌پذیر باشد
 *   ۲) تشخیص جهش تک‌فریمی: فریم منفردِ ناسازگار با هر دو همسایه (≈۱۰ms —
 *      از نظر فیزیولوژیکی نمی‌تواند تغییر نت واقعی باشد) حذف/جایگزین می‌شود
 *   ۳) Median filter کوتاه (۳ فریمی) — پله‌های واقعی را حفظ می‌کند
 *   ۴) هموارسازی تطبیقی: فقط لرزش زیر-پله را می‌گیرد؛ در نواحی پرتغییر
 *      (تحریر) ضریب هموارسازی به‌شدت کاهش می‌یابد تا دامنهٔ تحریر حفظ شود
 *   ۵) شکاف‌ها: کوتاه (≤۶۰ms، دوطرفه) با وضعیت ESTIMATED پر می‌شود؛
 *      Hold آخرین معتبر فقط تا سقف زمانی [holdMaxSec] و با وضعیت ESTIMATED؛
 *      بعد از شکاف طولانی هیچ عددی رسم نمی‌شود (شکاف واقعی).
 *
 * `rawF0Hz` هرگز تغییر نمی‌کند؛ همهٔ پردازش‌ها روی `stableF0Hz` است.
 * مسیر streaming با تأخیر یک فریم کار می‌کند (برای تشخیص قطعی جهش منفرد)؛
 * یعنی تأخیر ۵ تا ۱۰ میلی‌ثانیه — نامحسوس برای نمودار زنده.
 */
class PitchStabilizer(
    /** حداکثر طول شکافِ پرشدنی با درون‌یابی دوطرفه (ثانیه) */
    val maxFillGapSec: Double = 0.060,
    /** سقف نگه‌داشتن آخرین نت معتبر در شکاف (ثانیه) */
    val holdMaxSec: Double = 0.250,
    /** جهش بالاتر از این (سنت) نسبت به میانهٔ همسایگان = جهش مشکوک */
    val spikeCents: Double = 350.0,
    /** پنجرهٔ همسایگی برای میانه (هر طرف) */
    val neighborRadius: Int = 3
) {

    class FrameIn(
        val timestampSec: Double,
        val rawF0Hz: Double,
        val status: Byte,
        val qualityScore: Double,
        val loudnessRms: Double = 0.0
    )

    class FrameOut(
        val timestampSec: Double,
        val rawF0Hz: Double,
        val stableF0Hz: Double,
        val status: Byte,
        val qualityScore: Double,
        val loudnessRms: Double
    )

    // ── وضعیت streaming ──
    private val pending = ArrayList<FrameIn>(4)
    private var lastValidF0 = 0.0
    private var lastValidT = Double.NaN
    private var holdUsedSec = 0.0
    private var lastEmittedF0 = 0.0
    private var recentSteps = ArrayList<Double>(16) // سنت/فریم برای تطبیق هموارسازی

    fun reset() {
        pending.clear()
        lastValidF0 = 0.0
        lastValidT = Double.NaN
        holdUsedSec = 0.0
        lastEmittedF0 = 0.0
        recentSteps.clear()
    }

    // ═══════════════════ مسیر batch (فایل) — دوطرفه ═══════════════════

    /**
     * پردازش کامل با نگاه دوطرفه. [raws] خروجی gate/backend است.
     * ترتیب عملیات در مستندات کلاس آمده است.
     */
    fun process(raws: List<FrameIn>): List<FrameOut> {
        val n = raws.size
        if (n == 0) return emptyList()
        val f0 = DoubleArray(n) { raws[it].rawF0Hz }
        val hopSec = if (n > 1) medianDt(raws) else 0.01
        val hopFrames = max(1, (hopSec * 1000.0).toInt())

        // ۱) اصلاح خطای اکتاو
        octaveFix(f0)
        // ۲) حذف/جایگزینی جهش‌های تک‌فریمی
        dropSingleFrameSpikes(f0)
        // ۳) median filter کوتاه
        val med = median3(f0)
        // ۴) هموارسازی تطبیقی (فقط لرزش زیر-پله)
        val smoothed = adaptiveSmooth(med, hopSec)

        // ۵) شکاف‌ها: درون‌یابی دوطرفهٔ کوتاه + سکوت طولانی
        val est = BooleanArray(n)
        fillShortGaps(smoothed, est, hopSec)
        holdShort(smoothed, est, raws, hopSec)

        val out = ArrayList<FrameOut>(n)
        for (i in 0 until n) {
            val stable = if (smoothed[i] > 0.0) smoothed[i] else 0.0
            val finalStatus = when {
                stable <= 0.0 -> if (raws[i].status == FrameStatus.SILENT) FrameStatus.SILENT else FrameStatus.INVALID
                est[i] -> FrameStatus.ESTIMATED
                else -> FrameStatus.VALID
            }
            out.add(
                FrameOut(
                    timestampSec = raws[i].timestampSec,
                    rawF0Hz = raws[i].rawF0Hz,
                    stableF0Hz = stable,
                    status = finalStatus,
                    qualityScore = raws[i].qualityScore,
                    loudnessRms = raws[i].loudnessRms
                )
            )
        }
        return out
    }

    private fun medianDt(raws: List<FrameIn>): Double {
        val dts = ArrayList<Double>(raws.size)
        for (i in 1 until raws.size) {
            val d = raws[i].timestampSec - raws[i - 1].timestampSec
            if (d > 0.0) dts.add(d)
        }
        return if (dts.isEmpty()) 0.01 else PitchMath2.median(dts)
    }

    /** اصلاح خطای اکتاو با شاهد میانهٔ همسایگی */
    private fun octaveFix(f0: DoubleArray) {
        val n = f0.size
        val r = neighborRadius
        for (i in 0 until n) {
            if (f0[i] <= 0.0) continue
            val neigh = ArrayList<Double>(2 * r)
            for (k in max(0, i - r)..min(n - 1, i + r)) {
                if (k == i || f0[k] <= 0.0) continue
                neigh.add(f0[k])
            }
            if (neigh.size < 3) continue
            val med = PitchMath2.median(neigh)
            if (med <= 0.0) continue
            val cents = cents(f0[i], med)
            if (abs(cents) < 900.0) continue // فقط خطای اکتاو (≈±۱۲۰۰)
            // آیا ×۲ یا ÷۲ به میانه نزدیک می‌شود؟
            val up = f0[i] * 2.0
            val down = f0[i] * 0.5
            val dUp = abs(cents(up, med))
            val dDown = abs(cents(down, med))
            when {
                dUp <= 160.0 && dUp < dDown -> f0[i] = up
                dDown <= 160.0 -> f0[i] = down
                // درون‌یابی اکتاوی ناممکن → به میانه نزدیکش نگه نمی‌داریم؛
                // حذف تک‌فریمی با dropSingleFrameSpikes انجام می‌شود
            }
        }
    }

    /** حذف جهش‌های تک‌فریمی: فریم منفرد ناسازگار با هر دو همسایه */
    private fun dropSingleFrameSpikes(f0: DoubleArray) {
        val n = f0.size
        for (i in 1 until n - 1) {
            if (f0[i] <= 0.0) continue
            val a = f0[i - 1]
            val b = f0[i + 1]
            if (a <= 0.0 || b <= 0.0) continue
            val medN = 0.5 * (a + b)
            if (abs(cents(a, b)) > 150.0) continue // همسایه‌ها خودشان اختلاف دارند → قضاوت نکن
            val dev = abs(cents(f0[i], medN))
            if (dev > spikeCents) {
                // فریم منفردِ دور از مسیر → حذف (شکاف واقعی نیست؛ در fillShortGaps
                // اگر شرایط دوطرفه برقرار باشد با ESTIMATED پر می‌شود)
                f0[i] = 0.0
            }
        }
    }

    /** median filter سه‌فریمی — پله‌ها را حفظ می‌کند، جهش منفرد را می‌گیرد */
    private fun median3(f0: DoubleArray): DoubleArray {
        val n = f0.size
        val out = DoubleArray(n)
        for (i in 0 until n) {
            if (f0[i] <= 0.0) { out[i] = 0.0; continue }
            val a = if (i > 0) f0[i - 1] else f0[i]
            val b = f0[i]
            val c = if (i < n - 1) f0[i + 1] else f0[i]
            // میانه روی مقادیر صدادار
            val vals = ArrayList<Double>(3)
            for (v in doubleArrayOf(a, b, c)) if (v > 0.0) vals.add(v)
            out[i] = if (vals.isEmpty()) 0.0 else PitchMath2.median(vals)
        }
        return out
    }

    /**
     * هموارسازی تطبیقی: فقط گام‌های کوچک‌تر از «کف لرزش» هموار می‌شوند؛
     * گام‌های بزرگ (تغییر نت/تحریر) دست‌نخورده می‌مانند. ضریب در نواحی
     * پرتغییر کاهش می‌یابد تا دامنهٔ تحریر حفظ شود.
     */
    private fun adaptiveSmooth(f0: DoubleArray, hopSec: Double): DoubleArray {
        val n = f0.size
        val out = f0.copyOf()
        // گام‌های محلی برای تطبیق
        val steps = ArrayList<Double>()
        for (i in 1 until n) {
            if (f0[i] > 0.0 && f0[i - 1] > 0.0) steps.add(abs(cents(f0[i], f0[i - 1])))
        }
        val localActivity = if (steps.isEmpty()) 0.0 else PitchMath2.median(steps)
        // کف لرزش: در ناحیهٔ آرام کوچک، در ناحیهٔ پرتغییر بزرگ
        val jitterFloor = max(12.0, min(90.0, 2.2 * localActivity))
        var prev = 0.0
        for (i in 0 until n) {
            if (out[i] <= 0.0) { prev = 0.0; continue }
            if (prev > 0.0) {
                val step = abs(cents(out[i], prev))
                if (step in 1.0..jitterFloor) {
                    // لرزش زیر-پله: میانگین نمایی با alpha بالا (فقط تثبیت خفیف)
                    val alpha = 0.35
                    val target = prev * Math.pow(2.0, cents(out[i], prev) * alpha / 1200.0)
                    out[i] = target
                }
                // گام‌های بزرگ‌تر: بدون تغییر (تحریر/تغییر نت واقعی)
            }
            prev = out[i]
        }
        return out
    }

    /** درون‌یابی دوطرفهٔ شکاف‌های کوتاه — فقط با شرایط دوطرفه و علامت ESTIMATED */
    private fun fillShortGaps(f0: DoubleArray, est: BooleanArray, hopSec: Double) {
        val n = f0.size
        val maxGap = max(1, (maxFillGapSec / max(hopSec, 0.001)).toInt())
        var i = 0
        while (i < n) {
            if (f0[i] > 0.0) { i++; continue }
            var j = i
            while (j < n && f0[j] <= 0.0) j++
            val gap = j - i
            if (gap in 1..maxGap && i > 0 && j < n) {
                val a = f0[i - 1]
                val b = f0[j]
                if (a > 0.0 && b > 0.0 && abs(cents(b, a)) <= 250.0) {
                    for (k in i until j) {
                        val frac = (k - i + 1).toDouble() / (gap + 1)
                        f0[k] = a * Math.pow(b / a, frac)
                        est[k] = true
                    }
                }
            }
            i = j
        }
    }

    /** Hold آخرین معتبر — فقط تا سقف زمانی و فقط اگر ادامه دوباره صدا بیاید (دوطرفه) */
    private fun holdShort(f0: DoubleArray, est: BooleanArray, raws: List<FrameIn>, hopSec: Double) {
        val n = f0.size
        val maxHold = max(1, (holdMaxSec / max(hopSec, 0.001)).toInt())
        var i = 0
        while (i < n) {
            if (f0[i] > 0.0) { i++; continue }
            var j = i
            while (j < n && f0[j] <= 0.0) j++
            val gap = j - i
            // شکاف‌های پرشده با fillShortGaps قبلاً ESTIMATED شده‌اند
            if (gap > 0 && gap <= maxHold && i > 0 && f0[i - 1] > 0.0) {
                // دوطرفه: یا صدا ادامه می‌یابد (j < n) یا انتهای منبع است
                val resumes = j < n
                val nearSilence = raws.subList(i, j).any { it.status == FrameStatus.SILENT }
                if (resumes && !nearSilence) {
                    for (k in i until j) {
                        if (f0[k] <= 0.0) {
                            f0[k] = f0[i - 1]
                            est[k] = true
                        }
                    }
                }
                // اگر سکوت واقعی است یا صدا برنمی‌گردد، شکاف واقعی می‌ماند
            }
            i = j
        }
    }

    // ═══════════════════ مسیر streaming (میکروفن) — یک‌طرفه با تأخیر ۱ فریم ═══════════════════

    /**
     * یک فریم جدید را می‌گیرد. خروجی فریمِ قبلی (با تأخیر ۱ فریم) برمی‌گردد؛
     * null یعنی هنوز خروجی‌ای آماده نیست. در پایان [flush] را صدا بزن.
     */
    fun push(f: FrameIn): FrameOut? {
        pending.add(f)
        if (pending.size < 2) return null
        val cur = pending[pending.size - 2] // فریم i (نگاه به i+1 آماده است)
        val next = pending[pending.size - 1]
        while (pending.size > 3) pending.removeAt(0)
        return emitStreaming(cur, next)
    }

    /** تخلیهٔ فریم آخر */
    fun flush(): FrameOut? {
        if (pending.isEmpty()) return null
        val cur = pending.removeAt(pending.size - 1)
        return emitStreaming(cur, null)
    }

    private fun emitStreaming(cur: FrameIn, next: FrameIn?): FrameOut {
        var f0 = cur.rawF0Hz
        val hopSec = 0.01
        // ۱) اصلاح اکتاو نسبت به آخرین مسیر معتبر
        if (f0 > 0.0 && lastEmittedF0 > 0.0) {
            val c = abs(cents(f0, lastEmittedF0))
            if (c in 900.0..1500.0) {
                val up = f0 * 2.0
                val down = f0 * 0.5
                val dUp = abs(cents(up, lastEmittedF0))
                val dDown = abs(cents(down, lastEmittedF0))
                if (dUp <= 160.0 && dUp < dDown) f0 = up
                else if (dDown <= 160.0) f0 = down
            }
        }
        // ۲) جهش تک‌فریمی: اگر فریم بعدی به سطح قبلی برگشت، این فریم منفرد بود
        if (f0 > 0.0 && lastEmittedF0 > 0.0 && next != null) {
            val devCur = abs(cents(f0, lastEmittedF0))
            val nextF = next.rawF0Hz
            if (devCur > spikeCents && nextF > 0.0 && abs(cents(nextF, lastEmittedF0)) < 150.0) {
                f0 = 0.0 // جهش منفرد → حذف
            }
        }
        // ۳) median کوتاه روی مسیر اخیر
        if (f0 > 0.0 && recentSteps.size >= 2) {
            val medStep = PitchMath2.median(recentSteps)
            if (medStep < 2.0 && abs(cents(f0, lastEmittedF0)) in 1.0..12.0) {
                // لرزش خالص در ناحیهٔ ثابت → تثبیت خفیف
                f0 = lastEmittedF0 * Math.pow(2.0, cents(f0, lastEmittedF0) * 0.35 / 1200.0)
            }
        }
        // ۴) وضعیت و Hold با سقف زمانی
        var status = cur.status
        if (f0 <= 0.0) {
            // شکاف: آیا Hold مجاز است؟
            if (lastValidF0 > 0.0 && !cur.timestampSec.isNaN() && !lastValidT.isNaN()) {
                val gap = cur.timestampSec - lastValidT
                if (gap in 0.0..holdMaxSec && cur.status != FrameStatus.SILENT) {
                    f0 = lastValidF0
                    holdUsedSec = gap
                    status = FrameStatus.ESTIMATED
                } else {
                    status = if (cur.status == FrameStatus.SILENT) FrameStatus.SILENT else FrameStatus.INVALID
                }
            } else {
                status = if (cur.status == FrameStatus.SILENT) FrameStatus.SILENT else FrameStatus.INVALID
            }
        } else {
            lastValidF0 = f0
            lastValidT = cur.timestampSec
            holdUsedSec = 0.0
            status = FrameStatus.VALID
            if (lastEmittedF0 > 0.0) {
                recentSteps.add(abs(cents(f0, lastEmittedF0)))
                if (recentSteps.size > 16) recentSteps.removeAt(0)
            }
            lastEmittedF0 = f0
        }
        return FrameOut(cur.timestampSec, cur.rawF0Hz, f0, status, cur.qualityScore, cur.loudnessRms)
    }

    private fun cents(a: Double, b: Double): Double =
        if (a > 0.0 && b > 0.0) 1200.0 * kotlin.math.log2(a / b) else Double.NaN
}

/** کمکی‌های آماری محلی (بدون وابستگی به فایل‌های دیگر) */
internal object PitchMath2 {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val c = values.sorted()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }

    fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val c = values.copyOf()
        c.sort()
        val n = c.size
        return if (n % 2 == 1) c[n / 2] else 0.5 * (c[n / 2 - 1] + c[n / 2])
    }
}
