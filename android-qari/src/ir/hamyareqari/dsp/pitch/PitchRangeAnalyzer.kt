package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.PitchMath
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * تحلیل‌گر محدودهٔ صوتی — محاسبهٔ دقیق بالاترین/پایین‌ترین نت قاری از روی
 * فریم‌های معتبر F0. برای فایل و میکروفن زنده **یک** منطق مشترک است.
 *
 * دو محدودهٔ جداگانه (طبق نیاز محصول):
 *
 *   ۱) محدودهٔ «واقعی لحظه‌ای» (Absolute Extrema):
 *      بالاترین/پایین‌ترین F0 معتبر در کل اجرا. نت‌های کوتاه واقعی فقط به‌خاطر
 *      کوتاه بودن حذف نمی‌شوند؛ تحریرها/جهش‌های واقعیِ پشتوانه‌دار حفظ می‌شوند؛
 *      فقط فریم‌هایی که شواهد صوتی‌شان رد می‌کند (خطای اکتاوِ اثبات‌شده با شواهد
 *      دوطرفه، جهش تک‌فریمی بدون پشتوانه) بیرون می‌روند.
 *
 *   ۲) محدودهٔ «نت‌های معتبر و پایدار» (Stable Range) — مبنای اصلی گزارش:
 *      بالاترین/پایین‌ترین نتی که با شواهد صوتی و تداوم زمانی کافی تشخیص داده
 *      شده باشد. نمایندهٔ هر گروه، **میانهٔ فرکانس** آن است.
 *
 * اگر دو محدوده متفاوت باشند، هر دو نگه داشته می‌شوند تا تحریر/جهش واقعی از
 * خطای احتمالی قابل تفکیک بماند.
 *
 * ── اصول ──
 *  • تصمیم فقط با یک فریم گرفته نمی‌شود؛ هر ناهنجاری با همسایگی زمانیِ قبل/بعد
 *    و شواهد (qualityScore، تداوم، اصلاح اکتاوی) سنجیده می‌شود.
 *  • جهش سریع همیشه خطا نیست: تحریر و جهش واقعیِ پشتوانه‌دار حفظ می‌شوند.
 *  • آمار مقاوم (Median/MAD در فضای لگاریتمی f0) برای غربال ناهنجاری؛ نه
 *    میانگین/واریانس کلاسیک (پرت‌ها میانگین را می‌کشند).
 *  • بلندی صدا مستقیماً وارد تصمیم Pitch نمی‌شود؛ فریم کم‌حجم ولی تمیز کامل
 *    در محدوده شرکت می‌کند.
 *  • مدت‌ها فقط از timestamp واقعی فریم‌ها — نه تعداد فریم‌ها و نه نرخ refresh.
 *  • فرکانس‌ها بدون گرد/کوانتیزه نگه داشته می‌شوند؛ نام نت فقط برچسب است (A4=440).
 *  • داده‌ای که از موتور به دست نیامده با مقدار ساختگی پر نمی‌شود (فیلدهای null).
 *
 * همهٔ آستانه‌ها در [PitchRangeConfig] با دلیل مستند شده‌اند.
 */
class PitchRangeAnalyzer(val config: PitchRangeConfig = PitchRangeConfig()) {

    /** یک مقدار قابل‌ردیابی محدوده — همهٔ فیلدها از دادهٔ واقعی موتور */
    class Extremum(
        /** فرکانس واقعی (بدون گرد/کوانتیزه) */
        val f0Hz: Double,
        /** لحظهٔ وقوع (ثانیه، از timestamp فریم‌ها) */
        val timestampSec: Double,
        /** نزدیک‌ترین نت (A4=440) — فقط برچسب نمایشی */
        val noteMidi: Int,
        val noteName: String,
        /** اختلاف با نزدیک‌ترین نت (سنت، −۵۰..+۵۰) */
        val cents: Double,
        /** تداوم/اعتبار زمانی پشتوانه (ثانیه، از timestamp) */
        val durationSec: Double,
        /** میانگین qualityScore فریم‌های پشتوانه */
        val qualityScore: Double,
        /** تعداد فریم‌های پشتوانه */
        val frameCount: Int,
        /** آیا از محدودهٔ پایدار است (نه لحظه‌ای) */
        val stable: Boolean
    ) {
        val isReal: Boolean get() = f0Hz > 0.0 && f0Hz.isFinite()
        fun withStable(s: Boolean) = Extremum(
            f0Hz, timestampSec, noteMidi, noteName, cents, durationSec, qualityScore, frameCount, s
        )
    }

    /** آمار قابل‌ممیزه برای گزارش/اشکال‌زدایی (همه شمرده‌شده، نه ساختگی) */
    class Stats(
        val framesConsidered: Int,
        val framesUsed: Int,
        val invalidFramesExcluded: Int,
        val estimatedFramesExcluded: Int,
        val octaveErrorsExcluded: Int,
        val outliersExcluded: Int,
        /** جهش‌های بزرگی که بعد از بررسی شواهد پذیرفته شدند (جهش واقعی) */
        val jumpsAccepted: Int,
        /** فریم‌های اوجِ تحریر/لحظه‌ایِ حفظ‌شده */
        val ornamentPeaks: Int,
        val stableGroups: Int,
        val totalGroups: Int
    )

    /** خروجی کامل تحلیل محدوده */
    class RangeResult(
        val stableHigh: Extremum?,
        val stableLow: Extremum?,
        val absoluteHigh: Extremum?,
        val absoluteLow: Extremum?,
        /** وقتی هیچ گروه پایداری تشخیص داده نشد — stable* = null؛ فقط absolute* معتبر است */
        val stableFallback: Boolean,
        val stats: Stats
    ) {
        val hasRange: Boolean get() = stableHigh != null && stableLow != null
    }

    /** ورودی تحلیل — همان قرارداد مشترک خروجی موتور */
    class FrameIn(
        val timestampSec: Double,
        val f0Hz: Double,
        val qualityScore: Double,
        val status: Byte,
        val loudnessRms: Double = 0.0
    )

    private class Group(val hop: Double) {
        var tFirst = 0.0
        var tLast = 0.0
        val members = ArrayList<FrameIn>(64)
        val medWindow = ArrayList<Double>(48)

        fun add(f: FrameIn) {
            if (members.isEmpty()) tFirst = f.timestampSec
            tLast = f.timestampSec
            members.add(f)
            medWindow.add(f.f0Hz)
            if (medWindow.size > 48) medWindow.removeAt(0)
        }

        fun runningMedian(): Double = PitchMath.median(medWindow.toDoubleArray())

        fun medianF0(): Double = PitchMath.median(DoubleArray(members.size) { members[it].f0Hz })

        fun durationSec(): Double = max(0.0, tLast - tFirst) + hop

        fun meanQuality(): Double {
            if (members.isEmpty()) return 0.0
            var s = 0.0
            for (m in members) s += m.qualityScore
            return s / members.size
        }
    }

    /**
     * تحلیل دسته‌ای (فایل و پایان جلسهٔ زنده) — از کل داده‌های معتبر.
     * ورودی باید زمان‌مرتب باشد (از موتور همین‌طور می‌آید).
     */
    fun analyze(frames: List<FrameIn>): RangeResult {
        val cfg = config
        var invalidEx = 0
        var estimatedEx = 0

        // ── ۱) غربال اولیه: وضعیت/کیفیت — بلندی صدا شرط نیست ──
        val considered = ArrayList<FrameIn>(frames.size)
        for (f in frames) {
            if (f.status == FrameStatus.SILENT || f.status == FrameStatus.INVALID ||
                !(f.f0Hz > 0.0) || !f.f0Hz.isFinite()
            ) { invalidEx++; continue }
            if (f.status == FrameStatus.ESTIMATED) {
                // تخمینی (Hold/درون‌یابی) اندازه‌گیری واقعی نیست — مبنای محدوده نیست
                estimatedEx++
                continue
            }
            if (f.qualityScore < cfg.minFrameQuality) { invalidEx++; continue }
            considered.add(f)
        }
        if (considered.isEmpty()) {
            return RangeResult(null, null, null, null, false,
                Stats(0, 0, invalidEx, estimatedEx, 0, 0, 0, 0, 0, 0))
        }

        // گام نمونه‌برداری واقعی (از timestamp ها، نه فرض)
        val hop = medianHop(considered)

        // ── ۲) آمار مقاوم سراسری در فضای لگاریتمی (Median/MAD) ──
        val logs = DoubleArray(considered.size) { log2f(considered[it].f0Hz) }
        val medLog = PitchMath.median(logs)
        val devs = DoubleArray(logs.size) { abs(logs[it] - medLog) }
        val madEff = max(PitchMath.median(devs), 25.0 / 1200.0) // کف ≈ ۲۵ سنت

        // ── ۳) گروه‌بندی زمانی-فرکانسی (یک منطق برای فایل و زنده) ──
        // تفکیک «اوج تحریر» از «تغییر نت پایدار»: انحرافِ موقتِ بازگشت‌پذیر = تحریر
        // (کنار همان نت می‌ماند)؛ انحرافِ پیوستهٔ بیش از maxOrnamentSec = نت جدید.
        val groups = ArrayList<Group>()
        val memberGroup = HashMap<FrameIn, Group>() // هویتی (equals پیش‌فرض = هویت)
        val pending = ArrayList<FrameIn>() // نامزدهای اوجِ تحریر — بعداً تعیین تکلیف
        var cur: Group? = null
        var lastMemberT = Double.NaN
        for (f in considered) {
            val g = cur
            if (g == null) {
                val ng = Group(hop); ng.add(f); cur = ng; groups.add(ng); memberGroup[f] = ng
                lastMemberT = f.timestampSec
                pending.clear()
                continue
            }
            val gap = f.timestampSec - lastMemberT
            val devCents = abs(PitchMath.cents(f.f0Hz, g.runningMedian()))
            if (gap <= cfg.maxGroupGapSec && devCents <= cfg.groupContinueCents) {
                // عضو واقعی همان نت — بازگشت به سطح یعنی pending قبلی‌ها تحریر بودند
                g.add(f); memberGroup[f] = g; lastMemberT = f.timestampSec
                pending.clear()
                continue
            }
            val pendingSpan = if (pending.isEmpty()) 0.0 else f.timestampSec - pending[0].timestampSec
            val ornamentOk = gap <= cfg.maxGroupGapSec &&
                devCents <= cfg.ornamentCents && pendingSpan <= cfg.maxOrnamentSec
            if (ornamentOk) {
                pending.add(f) // نامزد اوجِ تحریر/لغزش — هنوز عضو نشده
                continue
            }
            // گسست واقعی: وقفه، پرش بزرگ، یا «تحریری» که در واقع تداومِ نت جدید بود
            if (pending.size >= 2 && pendingSpan >= cfg.minAbsRunSec) {
                // pending در سطحی پایدار ادامه یافته = شروع نت جدید بوده — گروهش را بساز
                val ng = Group(hop)
                for (p in pending) { ng.add(p); memberGroup[p] = ng }
                groups.add(ng); cur = ng
                pending.clear()
                val dev2 = abs(PitchMath.cents(f.f0Hz, ng.runningMedian()))
                if (dev2 <= cfg.groupContinueCents) {
                    ng.add(f); memberGroup[f] = ng; lastMemberT = f.timestampSec
                    continue
                }
                // f سطح دیگری است — گروه خودش را می‌خواهد
            } else {
                pending.clear() // اوج‌های کوتاه = تحریر؛ کنار همان نت قبلی می‌مانند
            }
            val ng = Group(hop); ng.add(f); cur = ng; groups.add(ng); memberGroup[f] = ng
            lastMemberT = f.timestampSec
        }

        // ── ۴) تشخیص خطای اکتاوِ پایدار (سگمنتی) با شواهد دوطرفه ──
        val glitchGroups = HashSet<Group>()
        var octaveEx = 0
        for (g in groups) {
            if (isOctaveGlitch(g, considered, cfg)) {
                glitchGroups.add(g)
                octaveEx += g.members.size
            }
        }

        // ── ۵) غربال پشتوانهٔ فریم‌ها (تصمیم تک‌فریمی ممنوع) ──
        // قاعده: فریم باید «پشتوانه» داشته باشد: ≥۲ فریم هم‌سطح در پنجرهٔ زمانی،
        // یا همسایهٔ هم‌سطح + کیفیت قوی. تک‌فریمِ بدون پشتوانه = ناهنجاری.
        val kept = ArrayList<FrameIn>(considered.size)
        var outliersEx = 0
        var jumpsAccepted = 0
        var ornamentKept = 0
        for (i in considered.indices) {
            val f = considered[i]
            val grp = memberGroup[f]
            if (grp != null && glitchGroups.contains(grp)) continue // شمرده‌شده در octaveEx

            // خطای اکتاوی تک‌فریمی: ×۲/÷۲ به میانهٔ محلی نزدیک است
            val localMed = localMedianSliding(considered, i, cfg.localWindowSec)
            val devLocal = if (localMed > 0) abs(PitchMath.cents(f.f0Hz, localMed)) else 0.0
            if (devLocal in cfg.octaveSuspendLow..cfg.octaveSuspendHigh && localMed > 0) {
                val up = abs(PitchMath.cents(f.f0Hz * 2.0, localMed))
                val down = abs(PitchMath.cents(f.f0Hz * 0.5, localMed))
                if (min(up, down) <= cfg.octaveFixTolerance) {
                    octaveEx++
                    continue
                }
            }

            val runSec = supportRunSec(considered, i, cfg.runLinkCents, cfg.runWindowSec, hop)
            val neighbor = hasNeighbor(considered, i, cfg.spikeWindowSec, cfg.spikeLinkCents)

            // ناهنجاری سراسری: انحراف MAD در فضای لگاریتمی — پرت‌ها میانگین را نمی‌کشند
            val devMad = abs(log2f(f.f0Hz) - medLog) / madEff
            val madOutlier = devMad > cfg.outlierMadK

            val supported = if (madOutlier) {
                // ناهنجاری سراسری فقط با تداوم واقعی پذیرفته می‌شود (جهش واقعی = دوام دارد)
                runSec >= cfg.minAbsRunSec
            } else {
                runSec > 0.0 || (neighbor && f.qualityScore >= cfg.strongQuality)
            }
            if (!supported) {
                outliersEx++
                continue
            }
            kept.add(f)
            if (grp == null && devLocal > cfg.spikeDevCents) ornamentKept++
            if (devLocal > cfg.spikeDevCents) jumpsAccepted++
        }

        if (kept.isEmpty()) {
            return RangeResult(null, null, null, null, false,
                Stats(considered.size, 0, invalidEx, estimatedEx, octaveEx, outliersEx,
                    jumpsAccepted, ornamentKept, 0, groups.size))
        }

        // ── ۶) محدودهٔ لحظه‌ای (Absolute) روی فریم‌های نگه‌داشته‌شده ──
        var hiF = kept[0]; var loF = kept[0]
        for (f in kept) {
            if (f.f0Hz > hiF.f0Hz) hiF = f
            if (f.f0Hz < loF.f0Hz) loF = f
        }
        val absHigh = toExtremum(hiF, kept, hop, stable = false)
        val absLow = toExtremum(loF, kept, hop, stable = false)

        // ── ۷) محدودهٔ پایدار (Stable): گروه‌های دارای تداوم + کیفیت ──
        val keptSet = HashSet<FrameIn>(kept)
        class Rep(val g: Group, val f0: Double, val q: Double, val dur: Double)
        val reps = ArrayList<Rep>(groups.size)
        for (g in groups) {
            if (glitchGroups.contains(g)) continue
            val mv = g.members.filter { keptSet.contains(it) }
            if (mv.size < 2) continue
            var tFirst = Double.MAX_VALUE; var tLast = -Double.MAX_VALUE
            for (m in mv) {
                if (m.timestampSec < tFirst) tFirst = m.timestampSec
                if (m.timestampSec > tLast) tLast = m.timestampSec
            }
            val dur = (tLast - tFirst) + hop
            if (dur < cfg.minStableSec) continue
            var q = 0.0
            for (m in mv) q += m.qualityScore
            q /= mv.size
            if (q + 1e-9 < cfg.minStableQuality) continue
            reps.add(Rep(g, PitchMath.median(DoubleArray(mv.size) { mv[it].f0Hz }), q, dur))
        }

        val stableHigh: Extremum?
        val stableLow: Extremum?
        val fallback: Boolean
        if (reps.isNotEmpty()) {
            val hiRep = reps.maxByOrNull { it.f0 }!!
            val loRep = reps.minByOrNull { it.f0 }!!
            stableHigh = extremumFromGroup(hiRep.g, hiRep.f0, hiRep.q, hiRep.dur, keptSet)
            stableLow = extremumFromGroup(loRep.g, loRep.f0, loRep.q, loRep.dur, keptSet)
            fallback = false
        } else {
            // هیچ گروه پایداری نیست (اجرا خیلی کوتاه/پراکنده/نویزی): محدودهٔ پایدار
            // «موجود نیست» — با مقدار ساختگی پر نمی‌شود. محدودهٔ لحظه‌ای معتبر
            // (absolute*) گزارش می‌شود تا داده‌ای از دست نرود.
            stableHigh = null
            stableLow = null
            fallback = true
        }

        return RangeResult(
            stableHigh, stableLow, absHigh, absLow, fallback,
            Stats(
                framesConsidered = considered.size,
                framesUsed = kept.size,
                invalidFramesExcluded = invalidEx,
                estimatedFramesExcluded = estimatedEx,
                octaveErrorsExcluded = octaveEx,
                outliersExcluded = outliersEx,
                jumpsAccepted = jumpsAccepted,
                ornamentPeaks = ornamentKept,
                stableGroups = reps.size,
                totalGroups = groups.size
            )
        )
    }

    /** تحلیل از قرارداد مشترک نمونه‌های موتور */
    fun analyzeSamples(samples: List<PitchSample>): RangeResult =
        analyze(samples.map {
            FrameIn(it.timestampSec, it.stableF0Hz, it.qualityScore, it.status, it.loudnessRms)
        })

    // ───────────────────────────── کمکی‌ها ─────────────────────────────

    private fun toExtremum(f: FrameIn, kept: List<FrameIn>, hop: Double, stable: Boolean): Extremum {
        // پشتوانه: تداوم فریم‌های هم‌سطح در بازهٔ زمانی دوطرفه (از timestamp)
        var tFirst = f.timestampSec; var tLast = f.timestampSec; var n = 0; var q = 0.0
        for (o in kept) {
            if (abs(o.timestampSec - f.timestampSec) <= config.runWindowSec &&
                abs(PitchMath.cents(o.f0Hz, f.f0Hz)) <= config.runLinkCents
            ) {
                if (o.timestampSec < tFirst) tFirst = o.timestampSec
                if (o.timestampSec > tLast) tLast = o.timestampSec
                n++; q += o.qualityScore
            }
        }
        val midi = PitchMath.nearestMidi(f.f0Hz)
        return Extremum(
            f0Hz = f.f0Hz,
            timestampSec = f.timestampSec,
            noteMidi = midi,
            noteName = if (midi >= 0) PitchMath.latinName(midi) else "—",
            cents = PitchMath.centsOffPitch(f.f0Hz),
            durationSec = (tLast - tFirst) + hop,
            qualityScore = if (n > 0) q / n else f.qualityScore,
            frameCount = max(1, n),
            stable = stable
        )
    }

    private fun extremumFromGroup(
        g: Group, f0: Double, quality: Double, dur: Double, keptSet: HashSet<FrameIn>
    ): Extremum {
        val midi = PitchMath.nearestMidi(f0)
        // لحظهٔ وقوع = نزدیک‌ترین عضو نگه‌داشته‌شده به میانه (زمان واقعی)
        var bestT = g.tFirst
        var bestD = Double.MAX_VALUE
        var n = 0
        for (m in g.members) {
            if (!keptSet.contains(m)) continue
            n++
            val d = abs(PitchMath.cents(m.f0Hz, f0))
            if (d < bestD) { bestD = d; bestT = m.timestampSec }
        }
        return Extremum(
            f0Hz = f0,
            timestampSec = bestT,
            noteMidi = midi,
            noteName = if (midi >= 0) PitchMath.latinName(midi) else "—",
            cents = PitchMath.centsOffPitch(f0),
            durationSec = dur,
            qualityScore = quality,
            frameCount = max(1, n),
            stable = true
        )
    }

    /** میانهٔ گام نمونه‌برداری از timestamp های واقعی */
    private fun medianHop(sorted: List<FrameIn>): Double {
        if (sorted.size < 2) return 0.01
        val dts = ArrayList<Double>(min(sorted.size - 1, 4096))
        val step = max(1, sorted.size / 4096)
        var i = step
        while (i < sorted.size) {
            val dt = sorted[i].timestampSec - sorted[i - 1].timestampSec
            if (dt > 0) dts.add(dt)
            i += step
        }
        return if (dts.isEmpty()) 0.01 else max(0.001, PitchMath.median(dts.toDoubleArray()))
    }

    /** میانهٔ محلی با پنجرهٔ زمانی دوطرفه روی فهرست زمان‌مرتب */
    private fun localMedianSliding(sorted: List<FrameIn>, idx: Int, windowSec: Double): Double {
        val t = sorted[idx].timestampSec
        var lo = idx
        while (lo > 0 && t - sorted[lo - 1].timestampSec <= windowSec) lo--
        var hi = idx
        while (hi + 1 < sorted.size && sorted[hi + 1].timestampSec - t <= windowSec) hi++
        val vals = DoubleArray(hi - lo + 1)
        for (k in lo..hi) vals[k - lo] = sorted[k].f0Hz
        return PitchMath.median(vals)
    }

    /** تداوم پشتوانه: بازهٔ زمانی فریم‌های هم‌سطح در پنجرهٔ دوطرفه (از timestamp) */
    private fun supportRunSec(
        sorted: List<FrameIn>, idx: Int, linkCents: Double, windowSec: Double, hop: Double
    ): Double {
        val f = sorted[idx]
        val t = f.timestampSec
        var lo = idx
        while (lo > 0 && t - sorted[lo - 1].timestampSec <= windowSec) lo--
        var hi = idx
        while (hi + 1 < sorted.size && sorted[hi + 1].timestampSec - t <= windowSec) hi++
        var tFirst = t; var tLast = t; var n = 0
        for (k in lo..hi) {
            val o = sorted[k]
            if (abs(PitchMath.cents(o.f0Hz, f.f0Hz)) <= linkCents) {
                if (o.timestampSec < tFirst) tFirst = o.timestampSec
                if (o.timestampSec > tLast) tLast = o.timestampSec
                n++
            }
        }
        return if (n <= 1) 0.0 else (tLast - tFirst) + hop
    }

    private fun hasNeighbor(sorted: List<FrameIn>, idx: Int, windowSec: Double, linkCents: Double): Boolean {
        val f = sorted[idx]
        val t = f.timestampSec
        for (k in (idx - 1) downTo max(0, idx - 40)) {
            val o = sorted[k]
            if (t - o.timestampSec > windowSec) break
            if (abs(PitchMath.cents(o.f0Hz, f.f0Hz)) <= linkCents) return true
        }
        for (k in (idx + 1) until min(sorted.size, idx + 40)) {
            val o = sorted[k]
            if (o.timestampSec - t > windowSec) break
            if (abs(PitchMath.cents(o.f0Hz, f.f0Hz)) <= linkCents) return true
        }
        return false
    }

    /**
     * تشخیص سگمنت خطای اکتاو: گروه کوتاهی که فقط پس از ×۲/÷۲ با سطحِ
     * همسایه‌های قبل/بعد هم‌میزان می‌شود (شواهد دوره‌ای/پیوستگی کانتور).
     * جهش واقعی اکتاوِ بلند یا بدون تطابق اصلاحی، حفظ می‌شود.
     */
    private fun isOctaveGlitch(g: Group, all: List<FrameIn>, cfg: PitchRangeConfig): Boolean {
        if (g.members.size < 2) return false
        if (g.durationSec() > cfg.maxOctaveGlitchSec) return false
        val gMed = g.medianF0()
        var tFirst = Double.MAX_VALUE; var tLast = -Double.MAX_VALUE
        val memberSet = HashSet<FrameIn>(g.members)
        for (m in g.members) {
            if (m.timestampSec < tFirst) tFirst = m.timestampSec
            if (m.timestampSec > tLast) tLast = m.timestampSec
        }
        // همسایه‌های دوطرفه (خارج از گروه) در بازهٔ flank — فهرست زمان‌مرتب
        val flank = ArrayList<Double>(64)
        var i = 0
        while (i < all.size && all[i].timestampSec < tFirst - cfg.flankSec) i++
        while (i < all.size && all[i].timestampSec <= tLast + cfg.flankSec) {
            val f = all[i]
            if (!memberSet.contains(f)) flank.add(f.f0Hz)
            i++
        }
        if (flank.size < 3) return false // شواهد دوطرفه کافی نیست — حفظ (تصمیم یک‌طرفه ممنوع)
        val flankMed = PitchMath.median(flank.toDoubleArray())
        val devRaw = abs(PitchMath.cents(gMed, flankMed))
        if (devRaw < cfg.octaveSuspendLow) return false // اصلاً اکتاوی نیست
        if (devRaw > cfg.octaveSuspendHigh) return false // گسست اکتاوی نیست (دورتر)
        val up = abs(PitchMath.cents(gMed * 2.0, flankMed))
        val down = abs(PitchMath.cents(gMed * 0.5, flankMed))
        // فقط اگر اصلاح اکتاوی، گروه را با همسایه‌ها هم‌میزان کند → خطا
        return min(up, down) <= cfg.octaveLinkCents
    }

    private fun log2f(v: Double): Double = kotlin.math.log2(v)

    /**
     * نسخهٔ streaming برای مسیر زنده: نمونه‌ها را جمع می‌کند و با هر فراخوانی،
     * محدوده را **از کل داده تاکنون** بازمحاسبه می‌کند (همان الگوریتم batch).
     * مدت‌ها از timestamp می‌آیند؛ نتیجه به تعداد فریم‌ها/نرخ refresh وابسته نیست.
     * پایان تحلیل = بازبینی کامل از کل داده (همان current()).
     */
    class Streaming(private val analyzer: PitchRangeAnalyzer = PitchRangeAnalyzer()) {
        private val frames = ArrayList<FrameIn>(8192)
        private var cached: RangeResult? = null

        @Synchronized
        fun add(sample: PitchSample) {
            frames.add(
                FrameIn(
                    sample.timestampSec, sample.stableF0Hz, sample.qualityScore,
                    sample.status, sample.loudnessRms
                )
            )
            cached = null
        }

        /** بازمحاسبه از کل داده‌های جمع‌شده (کش‌شده تا دادهٔ بعدی) */
        @Synchronized
        fun current(): RangeResult {
            val c = cached
            if (c != null) return c
            val r = analyzer.analyze(frames)
            cached = r
            return r
        }

        @Synchronized
        fun size(): Int = frames.size

        @Synchronized
        fun clear() {
            frames.clear()
            cached = null
        }
    }
}

/**
 * همهٔ آستانه‌ها و ضرایب محدوده در همین یک محل — با دلیل انتخاب هر مقدار.
 * مقادیر با واحد واقعی (ثانیه/سنت/کیفیت) و قابل تنظیم‌اند.
 */
class PitchRangeConfig(
    /** حداقل qualityScore برای ورود فریم به هر محدوده — زیر این حد فریم «نامعتبر» است */
    val minFrameQuality: Double = 0.42,

    /**
     * حداقل تداوم زمانی برای «نت پایدار» (ثانیه).
     * ۱۰۰ms: کوتاه‌ترین نت‌های واقعی تلاوت (گام‌های سریع) حذف نشوند، ولی
     * جهش‌های تک‌فریمی/نویزی (≤۲۰ms) نتوانند محدودهٔ پایدار را بسازند.
     */
    val minStableSec: Double = 0.10,

    /** حداقل میانگین کیفیت گروه برای «پایدار» */
    val minStableQuality: Double = 0.55,

    /**
     * حداقل تداوم پشتوانه برای ورود ناهنجار/جهش به محدوده (ثانیه).
     * ۵۰ms: نت کوتاه واقعی (حتی یک‌بار اجرا) پشتوانه دارد؛ فریم خطای تنها نه.
     */
    val minAbsRunSec: Double = 0.05,

    /** کیفیت «قوی» — نت خیلی کوتاه با این کیفیت و همسایهٔ هم‌سطح می‌تواند وارد شود */
    val strongQuality: Double = 0.75,

    /**
     * آستانهٔ ادامهٔ عضویت در گروه (سنت نسبت به میانهٔ در حال اجرای گروه).
     * ۱۲۰c: vibrato طبیعی (±۵۰c) و تحریر ملایم داخل نت می‌مانند (چند نت
     * مصنوعی ساخته نمی‌شود)؛ گام واقعی نت (پایدار >۱۲۰c) گروه جدید می‌سازد.
     */
    val groupContinueCents: Double = 120.0,

    /**
     * حداکثر انحراف «اوج تحریر» کنار همان نت (سنت) — بیش از آن گسست واقعی است.
     * ۳۵۰c: تحریر شدید (تا ±۱۵۰c) و لغزش‌های ملایم هنوز «کنار همان نت»‌اند و
     * گروه نمی‌شکنند؛ پرش بزرگ/گام واقعی گروه جدید می‌سازد.
     */
    val ornamentCents: Double = 350.0,

    /** حداکثر وقفهٔ داخل‌گروه (ثانیه) — قطع موقت/نفسِ کوتاه گروه را نمی‌شکند */
    val maxGroupGapSec: Double = 0.25,

    /**
     * حداکثر تداوم «انحرافِ برگشت‌ناپذیر» (ثانیه). انحرافِ موقتِ بازگشت‌پذیر =
     * اوج تحریر (کنار همان نت)؛ انحراف پیوسته بیش از این = تغییر نت واقعی.
     * ۲۵۰ms: تحریرهای معمول (تا ~۲Hz) برگشت می‌کنند؛ تداومِ بلندتر یعنی نت بعدی.
     */
    val maxOrnamentSec: Double = 0.25,

    /** ضریب MAD برای غربال ناهنجاری سراسری در فضای log2(f0) */
    val outlierMadK: Double = 6.0,

    /** بازهٔ «شبه‌اکتاو»: |Δc| ∈ [1050,1350] یعنی مشکوک به ×۲/÷۲ */
    val octaveSuspendLow: Double = 1050.0,
    val octaveSuspendHigh: Double = 1350.0,
    /** اگر اصلاح اکتاوی تا این حد به میانهٔ محلی/همسایه نزدیک شود، خطا است */
    val octaveFixTolerance: Double = 180.0,
    /** تطابق اصلاح اکتاوی با سطح همسایه‌های دوطرفه برای خطای سگمنتی */
    val octaveLinkCents: Double = 200.0,
    /** حداکثر طول سگمنتی که می‌تواند خطای اکتاو باشد (ثانیه) — جهش واقعی بلندتر حفظ می‌شود */
    val maxOctaveGlitchSec: Double = 0.60,
    /** بازهٔ شواهد دوطرفه (قبل/بعد) برای خطای اکتاو (ثانیه) */
    val flankSec: Double = 0.30,

    /** پنجرهٔ همسایگی زمانی برای میانهٔ محلی (ثانیه، دوطرفه) */
    val localWindowSec: Double = 0.30,
    /** پنجرهٔ جست‌وجوی پشتوانهٔ تداوم (ثانیه) */
    val runWindowSec: Double = 0.25,
    /** پیوند «هم‌سطح» برای پشتوانهٔ تداوم (سنت) */
    val runLinkCents: Double = 150.0,
    /** پنجرهٔ جست‌وجوی همسایه برای ردِ تک‌گانه بودن (ثانیه) */
    val spikeWindowSec: Double = 0.06,
    val spikeLinkCents: Double = 200.0,
    /** انحراف محلی (سنت) که فریم را «مشکوک به جهش» می‌کند */
    val spikeDevCents: Double = 400.0
)
