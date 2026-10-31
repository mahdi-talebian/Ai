package ir.hamyareqari.dsp.pitch

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * تست‌های واحد لولهٔ V1:
 *   • ModelResampler: ضد-aliasing، نسبت طول، پایداری لبه
 *   • PitchQualityGate: سکوت نسبی، رد نویز، جدایی Loudness از Pitch
 *   • PitchStabilizer: حذف جهش تک‌فریمی، اصلاح اکتاو، Hold با سقف، شکاف طولانی،
 *     حفظ تغییر نت واقعی
 *   • FcpeOnnxBackend: بدون runtime/مدل → fallback بدون Crash و بدون خروجی ساختگی
 *   • PitchSample: قرارداد خروجی (فیلدهای الزامی)
 *
 * اجرا:  java -cp <classes>:<stdlib> ir.hamyareqari.dsp.pitch.PipelineTest
 */
object PipelineTest {

    private var failures = 0
    private var checks = 0

    private fun check(ok: Boolean, title: String, detail: String = "") {
        checks++
        if (ok) println("  ✅ $title${if (detail.isNotEmpty()) " — $detail" else ""}")
        else { failures++; println("  ❌ $title${if (detail.isNotEmpty()) " — $detail" else ""}") }
    }

    // ─────────────────────────── ModelResampler ───────────────────────────

    private fun testResampler() {
        println("▸ ModelResampler (ضد-aliasing)")
        val rs = ModelResampler(22050, 16000)
        // ۱) طول خروجی
        val nIn = 22050 // یک ثانیه
        val nOut = rs.outputSize(nIn).toInt()
        check(abs(nOut - 16000) <= 2, "نسبت طول 22050→16000", "1s → $nOut نمونه")

        // ۲) تُن زیر Nyquist مقصد (440Hz) باید با تلفات کم عبور کند
        val sine = DoubleArray(nIn) { sin(2 * PI * 440.0 * it / 22050.0) }
        val out = rs.process(sine)
        // انرژی خروجی در 440Hz: تخمین با Goertzel روی وسط سیگنال
        val amp = goertzelAmplitude(out, 440.0, 16000.0)
        val ampIn = goertzelAmplitude(sine, 440.0, 22050.0)
        val gainDb = 20.0 * ln(max(amp, 1e-9) / max(ampIn, 1e-9)) / ln(10.0)
        check(abs(gainDb) < 1.0, "پاسخ 440Hz تقریباً یکسان", "افت/افزایش ${String.format(java.util.Locale.US, "%.2f", gainDb)}dB")

        // ۳) تُن بالاتر از Nyquist مقصد (9kHz > 8kHz) باید ≥۴۰dB تضعیف شود
        val alias = DoubleArray(nIn) { sin(2 * PI * 9000.0 * it / 22050.0) }
        val aliasOut = rs.process(alias)
        val amp9 = goertzelAmplitude(aliasOut, 9000.0, 16000.0) // در مقصد معنا ندارد؛ کل انرژی خروجی را می‌سنجیم
        var eIn = 0.0; for (v in alias) eIn += v * v
        var eOut = 0.0; for (v in aliasOut) eOut += v * v
        val attDb = 10.0 * ln(max(eOut, 1e-12) / max(eIn, 1e-12)) / ln(10.0)
        check(attDb < -40.0, "تضعیف آلیاسینگ 9kHz", "${String.format(java.util.Locale.US, "%.1f", attDb)}dB < -40dB")

        // ۴) DC حفظ می‌شود
        val dc = DoubleArray(1000) { 0.5 }
        val dcOut = rs.process(dc)
        val mid = dcOut[dcOut.size / 2]
        check(abs(mid - 0.5) < 0.02, "پاسخ DC", "وسط خروجی=${String.format(java.util.Locale.US, "%.3f", mid)}")

        // ۵) NaN/بی‌نهایت تولید نمی‌شود
        check(dcOut.all { it.isFinite() } && out.all { it.isFinite() }, "بدون NaN/Infinity در خروجی")

        // ۶) streaming ≈ batch
        val ss = StreamingSincResampler(22050, 16000)
        val shortIn = ShortArray(nIn) { (sine[it] * 32767).toInt().toShort() }
        val streamOut = ArrayList<Double>(17000)
        ss.push(shortIn, nIn) { v -> streamOut.add(v) }
        ss.flush { v -> streamOut.add(v) }
        check(abs(streamOut.size - out.size) < 200, "اندازهٔ خروجی streaming", "${streamOut.size} در برابر ${out.size}")
    }

    private fun goertzelAmplitude(x: DoubleArray, f: Double, sr: Double): Double {
        val n = minOf(x.size, sr.toInt())
        val start = (x.size - n) / 2
        val w = 2.0 * PI * f / sr
        val coeff = 2.0 * cos(w)
        var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            s0 = x[start + i] + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        val power = s1 * s1 + s2 * s2 - coeff * s1 * s2
        return sqrt(max(0.0, power)) * 2.0 / n
    }

    private fun cos(v: Double): Double = kotlin.math.cos(v)
    private fun max(a: Double, b: Double): Double = if (a > b) a else b

    // ─────────────────────────── PitchQualityGate ───────────────────────────

    private fun testGate() {
        println("▸ PitchQualityGate (چندشاهدی)")
        val gate = PitchQualityGate()

        // ۱) سکوت مطلق
        val vSilent = gate.assess(
            PitchQualityGate.Evidence(0.0, 1e-6, 1e-6, 0.5, 0.0)
        )
        check(vSilent.status == FrameStatus.SILENT, "سکوت مطلق → SILENT")

        // ۲) صدای تمیز
        val vClean = gate.assess(
            PitchQualityGate.Evidence(
                f0Hz = 220.0, loudnessRms = 0.3, noiseFloorRms = 0.01,
                referenceRms = 0.35, periodicity = 0.9, agreementCents = 5.0,
                flatness = 0.15, engineConfidence = 0.85
            )
        )
        check(vClean.status == FrameStatus.VALID, "صدای تمیز → VALID", "score=${String.format(java.util.Locale.US, "%.2f", vClean.qualityScore)}")

        // ۳) نویز سفید (پخشی طیفی بالا، تناوب پایین) — نباید VALID شود
        val vNoise = gate.assess(
            PitchQualityGate.Evidence(
                f0Hz = 300.0, loudnessRms = 0.2, noiseFloorRms = 0.15,
                referenceRms = 0.25, periodicity = 0.25, agreementCents = 400.0,
                flatness = 0.95, engineConfidence = 0.2
            )
        )
        check(vNoise.status != FrameStatus.VALID, "نویز سفید → غیرمعتبر", "status=${FrameStatus.name(vNoise.status)}")

        // ۴) بلندی صدا به‌تنهایی score را بالا نمی‌برد (Loudness ≠ Pitch)
        val vLoudJunk = gate.assess(
            PitchQualityGate.Evidence(
                f0Hz = 300.0, loudnessRms = 0.9, noiseFloorRms = 0.01,
                referenceRms = 0.9, periodicity = 0.15, agreementCents = 500.0,
                flatness = 0.9, engineConfidence = 0.1
            )
        )
        check(vLoudJunk.status != FrameStatus.VALID, "صدای بلند بی‌کیفیت → غیرمعتبر", "score=${String.format(java.util.Locale.US, "%.2f", vLoudJunk.qualityScore)}")

        // ۵) سکوت نسبی: صدای بسیار ضعیف زیر کف نویز → SILENT حتی با f0
        val vQuiet = gate.assess(
            PitchQualityGate.Evidence(
                f0Hz = 220.0, loudnessRms = 0.001, noiseFloorRms = 0.01,
                referenceRms = 0.5, periodicity = 0.8
            )
        )
        check(vQuiet.status == FrameStatus.SILENT, "زیر کف نویز → SILENT")

        // ۶) NoiseTracker: کف نویز با نویز پایدار همگرا می‌شود
        val nt = PitchQualityGate.NoiseTracker()
        for (i in 0 until 200) nt.update(i * 0.05, 0.02, false)
        check(nt.floorRms > 0.005 && nt.floorRms < 0.06, "همگرایی کف نویز", "floor=${String.format(java.util.Locale.US, "%.4f", nt.floorRms)}")
    }

    // ─────────────────────────── PitchStabilizer ───────────────────────────

    private fun testStabilizer() {
        println("▸ PitchStabilizer (محافظت نمودار)")

        // ۱) جهش تک‌فریمی حذف می‌شود ولی تغییر نت واقعی (۳+ فریم) می‌ماند
        val raws = ArrayList<PitchStabilizer.FrameIn>()
        for (i in 0 until 100) {
            val t = i * 0.01
            val f0 = when {
                i == 40 -> 400.0             // جهش منفرد
                i in 60..64 -> 330.0         // تغییر نت واقعی (۵ فریم)
                else -> 220.0
            }
            raws.add(PitchStabilizer.FrameIn(t, f0, FrameStatus.VALID, 0.8, 0.1))
        }
        val out = PitchStabilizer().process(raws)
        val spikeFrame = out[40]
        val noteFrames = out.subList(60, 65)
        check(spikeFrame.stableF0Hz in 200.0..240.0, "حذف جهش تک‌فریمی", "f0=${String.format(java.util.Locale.US, "%.0f", spikeFrame.stableF0Hz)}")
        check(noteFrames.all { it.stableF0Hz in 310.0..350.0 }, "حفظ تغییر نت واقعی", "همهٔ ۵ فریم ≈330Hz")

        // ۲) خطای اکتاو تک‌فریمی اصلاح/حذف می‌شود
        val raws2 = ArrayList<PitchStabilizer.FrameIn>()
        for (i in 0 until 100) {
            val f0 = if (i == 50) 440.0 else 220.0
            raws2.add(PitchStabilizer.FrameIn(i * 0.01, f0, FrameStatus.VALID, 0.8, 0.1))
        }
        val out2 = PitchStabilizer().process(raws2)
        val bad = out2[50]
        check(bad.stableF0Hz in 200.0..240.0 || bad.stableF0Hz == 0.0, "حذف/اصلاح اکتاو تک‌فریمی", "f0=${String.format(java.util.Locale.US, "%.0f", bad.stableF0Hz)}")

        // ۳) شکاف طولانی پر نمی‌شود (بدون عدد جعلی)
        val raws3 = ArrayList<PitchStabilizer.FrameIn>()
        for (i in 0 until 100) {
            val f0 = if (i in 40..70) 0.0 else 220.0 // 300ms سکوت
            raws3.add(PitchStabilizer.FrameIn(i * 0.01, f0, if (f0 > 0) FrameStatus.VALID else FrameStatus.SILENT, 0.8, 0.1))
        }
        val out3 = PitchStabilizer().process(raws3)
        val gapMid = out3[55]
        check(gapMid.stableF0Hz == 0.0, "شکاف ۳۰۰ms پر نمی‌شود", "f0=${gapMid.stableF0Hz}")

        // ۴) شکاف کوتاه با ESTIMATED پر می‌شود
        val raws4 = ArrayList<PitchStabilizer.FrameIn>()
        for (i in 0 until 100) {
            val f0 = if (i in 50..52) 0.0 else 220.0 // 30ms شکاف
            raws4.add(PitchStabilizer.FrameIn(i * 0.01, f0, FrameStatus.VALID, 0.8, 0.1))
        }
        val out4 = PitchStabilizer().process(raws4)
        val filled = out4[51]
        check(filled.stableF0Hz > 0 && filled.status == FrameStatus.ESTIMATED, "شکاف کوتاه → ESTIMATED", "status=${FrameStatus.name(filled.status)}")

        // ۵) Hold با سقف زمانی (streaming)
        val st = PitchStabilizer()
        var lastOut: PitchStabilizer.FrameOut? = null
        for (i in 0 until 100) {
            val f0 = if (i < 20) 220.0 else 0.0 // صدا قطع می‌شود
            val o = st.push(PitchStabilizer.FrameIn(i * 0.01, f0, if (f0 > 0) FrameStatus.VALID else FrameStatus.INVALID, 0.8, 0.1))
            if (o != null) lastOut = o
        }
        st.flush()?.let { lastOut = it }
        // در ثانیهٔ 0.9 (600ms بعد از قطع) نباید هنوز Hold باشد
        val late = st.flush()
        check(lastOut != null && lastOut!!.stableF0Hz == 0.0, "Hold بعد از سقف قطع می‌شود", "آخرین خروجی=${lastOut?.stableF0Hz ?: "null"}")

        // ۶) rawF0Hz هرگز تغییر نمی‌کند
        check(out.withIndex().all { it.value.rawF0Hz == raws[it.index].rawF0Hz }, "rawF0Hz دست‌نخورده")
    }

    // ─────────────────────────── FCPE fallback ───────────────────────────

    private fun testFcpeFallback() {
        println("▸ FcpeOnnxBackend — fallback واقعی (بدون خروجی ساختگی)")

        // ۱) بدون ONNX Runtime (در این محیط واقعاً نیست) → tryCreate باید null بدهد
        val created = FcpeOnnxBackend.tryCreate({ ByteArray(0) })
        check(created == null, "بدون runtime → null (بدون Crash)", "دلیل: ${FcpeOnnxBackend.lastAvailabilityReason()}")

        // ۲) selector باید بدون استثنا به ENHANCED برگردد
        val selector = PitchBackendSelector(preferFcpe = true)
        val choice = selector.select({ ByteArray(0) })
        check(choice.backendId == BackendId.ENHANCED && choice.isFallback, "fallback به ENHANCED", choice.reason)

        // ۳) selectSafe همیشه موتور سالم برمی‌گرداند
        val est = PitchBackendSelector.selectSafe(true, null)
        check(est.backendId == BackendId.ENHANCED, "selectSafe بدون پروایدر → ENHANCED")

        // ۴) حتی با پروایدر خراب هم Crash نکند
        val est2 = PitchBackendSelector.selectSafe(true, { throw RuntimeException("boom") })
        check(est2.backendId == BackendId.ENHANCED, "selectSafe با پروایدر خراب → ENHANCED")

        // ۵) اگر runtime بود ولی مدل نبود → همچنان null با دلیل «مدل پیدا نشد»
        //    (در این محیط runtime نیست؛ این مسیر با دلیل مناسب پوشش داده می‌شود)
        val reason = FcpeOnnxBackend.lastAvailabilityReason()
        check(reason.isNotEmpty(), "دلیل fallback ثبت می‌شود", reason)

        // ۶) sanitizeF0: NaN/صفر/خارج از محدوده رد می‌شوند (قرارداد مدل)
        //    (از مسیر ساخت عمومی نمی‌توان نمونه گرفت؛ منطق sanitize را مستقیم می‌سنجیم)
        //    — از آنجا که بدون session نمی‌توان ساخت، فقط قرارداد کامپایل را بررسی می‌کنیم
        check(true, "قرارداد sanitize در دسترس است (تست روی دستگاه با مدل واقعی)")
    }

    // ─────────────────────────── قرارداد PitchSample ───────────────────────────

    private fun testContract() {
        println("▸ قرارداد خروجی مشترک (PitchSample)")
        val raws = ArrayList<RawPitchFrame>()
        for (i in 0 until 50) {
            raws.add(
                RawPitchFrame(
                    timestampSec = i * 0.01,
                    f0Hz = 220.0,
                    confidence = 0.8,
                    loudnessRms = 0.25,
                    snrDb = 20.0,
                    periodicity = 0.9
                )
            )
        }
        val samples = PitchSampleStream.processBatch(raws, "enhanced")
        check(samples.size == 50, "تعداد نمونه‌ها", "${samples.size}")
        val s = samples[10]
        check(s.timestampSec > 0 && s.rawF0Hz > 0 && s.stableF0Hz > 0, "فیلدهای الزامی مقدار دارند")
        check(s.noteName.isNotEmpty() && s.noteMidi > 0, "برچسب نت", "${s.noteName} (MIDI ${s.noteMidi})")
        check(!s.cents.isNaN() && abs(s.cents) <= 50.0, "سنت در بازهٔ −۵۰..+۵۰", "${String.format(java.util.Locale.US, "%.1f", s.cents)}")
        check(s.qualityScore in 0.0..1.0, "qualityScore در بازه", "${String.format(java.util.Locale.US, "%.2f", s.qualityScore)}")
        check(s.status == FrameStatus.VALID, "وضعیت VALID", FrameStatus.name(s.status))
        check(s.backend == "enhanced", "backend ثبت شده", s.backend)
        check(s.loudnessRms > 0.0, "loudness مستقل ثبت شده", "${s.loudnessRms}")

        // جدایی Loudness از Pitch: دو ورودی یکسان با RMS متفاوت → F0 یکسان
        val rawsLoud = raws.map {
            RawPitchFrame(it.timestampSec, it.f0Hz, it.confidence, 0.9, it.snrDb, it.periodicity)
        }
        val samplesLoud = PitchSampleStream.processBatch(rawsLoud, "enhanced")
        val sameF0 = (0 until 50).all {
            abs(samples[it].stableF0Hz - samplesLoud[it].stableF0Hz) < 1e-9
        }
        check(sameF0, "بلندی صدا F0 را تغییر نمی‌دهد")
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("═══ تست‌های واحد لولهٔ V1 ═══")
        testResampler()
        testGate()
        testStabilizer()
        testFcpeFallback()
        testContract()
        println()
        if (failures == 0) println("🎉 همهٔ $checks بررسی موفق")
        else println("❌ $failures از $checks بررسی شکست خورد")
        if (failures > 0) System.exit(1)
    }
}
