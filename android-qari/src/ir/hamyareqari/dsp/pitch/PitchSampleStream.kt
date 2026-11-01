package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.PitchMath

/**
 * جریان خروجی مشترک — تبدیل [RawPitchFrame] به [PitchSample] با
 * Quality Gate و Stabilizer. هر دو ورودی (فایل و میکروفن) از همین مسیر
 * عبور می‌کنند تا قرارداد خروجی یکسان بماند.
 *
 * نام نت فقط لایهٔ نمایشی است: مقدار دقیق Hz و Cent همیشه جدا نگه داشته می‌شود.
 * بلندی صدا هرگز F0 را جابه‌جا نمی‌کند.
 */
class PitchSampleStream(
    val gate: PitchQualityGate = PitchQualityGate(),
    val stabilizer: PitchStabilizer = PitchStabilizer(),
    val backendTag: String = BackendId.ENHANCED.tag,
    /** گام زمانی برای مسیر batch (ثانیه) — برای کف نویز محلی */
    val hopSec: Double = 0.01
) {

    private val noiseTracker = PitchQualityGate.NoiseTracker()

    fun reset() {
        noiseTracker.reset()
        stabilizer.reset()
    }

    // ═══════════════════ مسیر batch (فایل) ═══════════════════

    /**
     * پردازش کامل فهرست فریم‌ها (فایل): کف نویز batch → gate → stabilizer
     * دوطرفه → [PitchSample] با نام نت و سنت.
     */
    fun feedAll(raws: List<RawPitchFrame>): List<PitchSample> {
        if (raws.isEmpty()) return emptyList()
        val rmsArr = DoubleArray(raws.size) { raws[it].loudnessRms }
        val floor = PitchQualityGate.batchNoiseFloor(rmsArr, hopSec * 1000.0)
        val ref = PitchQualityGate.batchReference(rmsArr)

        val gated = ArrayList<PitchStabilizer.FrameIn>(raws.size)
        val scores = DoubleArray(raws.size)
        for (i in raws.indices) {
            val r = raws[i]
            val ev = PitchQualityGate.Evidence(
                f0Hz = r.f0Hz,
                loudnessRms = r.loudnessRms,
                noiseFloorRms = floor[i],
                referenceRms = ref,
                periodicity = r.periodicity,
                agreementCents = r.agreementCents,
                flatness = r.flatness,
                clipFrac = r.clipFrac,
                engineConfidence = r.confidence
            )
            val verdict = gate.assess(ev)
            scores[i] = verdict.qualityScore
            gated.add(
                PitchStabilizer.FrameIn(
                    timestampSec = r.timestampSec,
                    rawF0Hz = if (verdict.status == FrameStatus.SILENT || verdict.status == FrameStatus.INVALID) 0.0 else r.f0Hz,
                    status = verdict.status,
                    qualityScore = verdict.qualityScore,
                    loudnessRms = r.loudnessRms
                )
            )
        }
        val stabilized = stabilizer.process(gated)
        val out = ArrayList<PitchSample>(stabilized.size)
        for (s in stabilized) {
            out.add(toSample(s, backendTag))
        }
        return out
    }

    // ═══════════════════ مسیر streaming (میکروفن) ═══════════════════

    /**
     * یک فریم زنده → gate → stabilizer (یک‌طرفه، تأخیر ۱ فریم).
     * null یعنی هنوز خروجی آماده نیست؛ در پایان [flush] را صدا بزن.
     */
    fun feed(raw: RawPitchFrame): PitchSample? {
        val voicedLikely = raw.f0Hz > 0.0 && raw.periodicity > 0.5
        noiseTracker.update(raw.timestampSec, raw.loudnessRms, voicedLikely)
        val ev = PitchQualityGate.Evidence(
            f0Hz = raw.f0Hz,
            loudnessRms = raw.loudnessRms,
            noiseFloorRms = noiseTracker.floorRms,
            referenceRms = noiseTracker.reference(),
            periodicity = raw.periodicity,
            agreementCents = raw.agreementCents,
            flatness = raw.flatness,
            clipFrac = raw.clipFrac,
            engineConfidence = raw.confidence
        )
        val verdict = gate.assess(ev)
        val fIn = PitchStabilizer.FrameIn(
            timestampSec = raw.timestampSec,
            rawF0Hz = if (verdict.status == FrameStatus.SILENT || verdict.status == FrameStatus.INVALID) 0.0 else raw.f0Hz,
            status = verdict.status,
            qualityScore = verdict.qualityScore,
            loudnessRms = raw.loudnessRms
        )
        val s = stabilizer.push(fIn) ?: return null
        return toSample(s, backendTag)
    }

    /** تخلیهٔ فریم باقی‌ماندهٔ stabilizer */
    fun flush(): PitchSample? {
        val s = stabilizer.flush() ?: return null
        return toSample(s, backendTag)
    }

    // ═══════════════════ مشترک ═══════════════════

    /** ساخت نمونهٔ خروجی: نام نت فقط برچسب نمایشی است */
    private fun toSample(s: PitchStabilizer.FrameOut, backend: String): PitchSample {
        val stable = s.stableF0Hz
        val has = stable > 0.0 && stable.isFinite()
        val midi = if (has) PitchMath.nearestMidi(stable) else -1
        return PitchSample(
            timestampSec = s.timestampSec,
            rawF0Hz = s.rawF0Hz,
            stableF0Hz = stable,
            noteName = if (has) PitchMath.noteName(stable) else "—",
            noteMidi = midi,
            cents = if (has) PitchMath.centsOffPitch(stable) else Double.NaN,
            qualityScore = s.qualityScore,
            loudnessRms = s.loudnessRms,
            status = s.status,
            backend = backend
        )
    }

    companion object {
        /** نسخهٔ batch ساده برای تست/ابزار */
        fun processBatch(
            raws: List<RawPitchFrame>,
            backendTag: String,
            hopSec: Double = 0.01
        ): List<PitchSample> =
            PitchSampleStream(backendTag = backendTag, hopSec = hopSec).feedAll(raws)

        /**
         * ساخت نمونه‌ها از منحنی فریم‌های موتور فعلی ([ir.hamyareqari.dsp.PitchCurve])
         * — همان قرارداد مشترک برای مسیر فایل، بدون اجرای دوبارهٔ موتور.
         *
         * @param maxPoints سقف تعداد نمونهٔ خروجی (فشرده‌سازی با stride؛ ۰ = بدون سقف)
         */
        fun fromCurve(
            curve: ir.hamyareqari.dsp.PitchCurve,
            backendTag: String = BackendId.ENHANCED.tag,
            maxPoints: Int = 6000
        ): List<PitchSample> {
            val n = curve.size
            if (n == 0) return emptyList()
            val stride = if (maxPoints <= 0) 1 else Math.max(1, n / maxPoints)
            val hopSec = curve.hopMs / 1000.0 * stride
            val raws = ArrayList<RawPitchFrame>((n + stride - 1) / stride)
            var i = 0
            while (i < n) {
                val b = Math.min(n, i + stride)
                // میانهٔ فریم‌های بلوک (در برابر اوج لحظه‌ای تحریر مقاوم است)
                val f0s = ArrayList<Double>(b - i)
                var conf = 0.0; var rms = 0.0; var snr = 0.0; var t = 0.0
                for (k in i until b) {
                    if (curve.f0[k] > 0.0) f0s.add(curve.f0[k])
                    conf += curve.confidence[k].toDouble()
                    rms += curve.rms[k].toDouble()
                    snr += curve.snrDb[k].toDouble()
                    t += curve.t[k]
                }
                val cnt = b - i
                raws.add(
                    RawPitchFrame(
                        timestampSec = t / cnt,
                        f0Hz = if (f0s.isEmpty()) 0.0 else medianOf(f0s),
                        confidence = conf / cnt,
                        loudnessRms = rms / cnt,
                        snrDb = snr / cnt,
                        periodicity = conf / cnt,
                        agreementCents = Double.NaN,
                        backend = backendTag
                    )
                )
                i = b
            }
            return processBatch(raws, backendTag, hopSec)
        }

        private fun medianOf(v: List<Double>): Double {
            val s = v.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2])
        }
    }
}
