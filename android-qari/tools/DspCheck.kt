package ir.hamyareqari.tools

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.Wav
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * آزمون دقت موتور تحلیل، بدون گوشی و بدون اندروید.
 *   • یک اجرای مرجع مصنوعی (۱۴ نت، A3 ↔ A4، با ویبراتو و هارمونیک) می‌سازد
 *   • همان اجرا را در چند «قالب» دشوار (۸k، ۱۶k، کم‌صدا، نویزی، سکوت اضافه) می‌سنجد
 *   • انتظار: بالاترین A4، پایین‌ترین A3، گستره ۱۲ نیم‌پرده
 */
object DspCheck {

    private const val SR = 22050

    /** یک نت با ویبراتو، هارمونیک‌های نزولی و پوش‌دامنه */
    private fun synthNote(sr: Int, f0: Double, dur: Double, vibCents: Double = 25.0, seed: Int = 1): DoubleArray {
        val n = (dur * sr).toInt()
        val out = DoubleArray(n)
        var phase = 0.0
        var rnd = seed.toLong() * 1103515245L + 12345L
        val harm = DoubleArray(10) { i -> 1.0 / (i + 1).toDouble().pow(1.25) }
        // تشدید سبک شبیه فورمنت خواننده
        harm[2] *= 1.6   // ~۳ برابر f0
        harm[4] *= 1.3
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            val cents = vibCents * sin(2 * PI * 5.5 * t)
            val f = f0 * 2.0.pow(cents / 1200.0)
            phase += 2 * PI * f / sr
            var s = 0.0
            for (h in harm.indices) {
                val hf = f * (h + 1)
                if (hf > sr / 2.0 * 0.9) break
                s += harm[h] * sin(phase * (h + 1))
            }
            // نویز بسیار کم
            rnd = rnd * 6364136223846793005L + 1442695040888963407L
            val noise = ((rnd ushr 40).toDouble() / (1 shl 24).toDouble() - 0.5) * 0.004
            // پوش‌دامنه: حمله ۲۵ms، رها ۵۰ms
            val env = when {
                t < 0.025 -> t / 0.025
                t > dur - 0.05 -> (dur - t) / 0.05
                else -> 1.0
            }
            out[i] = (s + noise) * env
        }
        return out
    }

    /** اجرای مرجع: A3 → A4 و برگشت (۱۴ نت) */
    private fun reference(): Pair<DoubleArray, List<Double>> {
        val notes = listOf(
            220.0, 246.94, 261.63, 293.66, 329.63, 349.23, 392.0, 440.0,
            392.0, 349.23, 329.63, 293.66, 261.63, 220.0
        )
        val chunks = ArrayList<DoubleArray>()
        val gap = DoubleArray((0.12 * SR).toInt())
        for ((i, f) in notes.withIndex()) {
            chunks.add(synthNote(SR, f, 1.4, seed = i + 3))
            chunks.add(gap)
        }
        val total = chunks.sumOf { it.size }
        val out = DoubleArray(total)
        var p = 0
        for (c in chunks) { System.arraycopy(c, 0, out, p, c.size); p += c.size }
        return out to notes
    }

    private fun addNoise(x: DoubleArray, level: Double, seed: Int = 7): DoubleArray {
        val out = x.copyOf()
        var rnd = seed.toLong() * 6364136223846793005L + 1442695040888963407L
        for (i in out.indices) {
            rnd = rnd * 6364136223846793005L + 1442695040888963407L
            out[i] += ((rnd ushr 40).toDouble() / (1 shl 24).toDouble() - 0.5) * 2 * level
        }
        return out
    }

    private fun report(name: String, x: DoubleArray, sr: Int, expectHi: String, expectLo: String, expectSpan: Double) {
        val t0 = System.currentTimeMillis()
        val r = Analyzer.analyze(x, sr, null)
        val ms = System.currentTimeMillis() - t0
        val hi = r.highest?.nameAscii ?: "—"
        val lo = r.lowest?.nameAscii ?: "—"
        val ok = hi == expectHi && lo == expectLo && abs(r.spanSemitones - expectSpan) < 0.5
        // خطای کوک هر نت: فاصلهٔ نزدیک‌ترین نت درست تا فرکانس مرجع
        val cents = buildList {
            r.notes.forEach { n -> add(abs(PitchMath.centsOffPitch(n.f0))) }
        }.sorted()
        val medCents = if (cents.isNotEmpty()) cents[cents.size / 2] else 0.0
        val status = if (ok) "✅" else "❌"
        println(
            "$status $name\n" +
                "   بالاترین=$hi (${"%.1f".format(r.highest?.f0 ?: 0.0)}Hz)  پایین‌ترین=$lo (${"%.1f".format(r.lowest?.f0 ?: 0.0)}Hz)\n" +
                "   گستره=${"%.2f".format(r.spanSemitones)} نیم‌پرده (انتظار ${"%.1f".format(expectSpan)})  " +
                "مرکز=${r.centerName}  نت‌ها=${r.noteCount}\n" +
                "   میانهٔ انحراف از کوک=${"%.2f".format(medCents)} سنت  SNR=${"%.1f".format(r.quality.snrDb)}dB  " +
                "هم‌رأیی=${"%.0f".format(r.quality.agreementPct)}%  کیفیت=${r.quality.status}  (${ms}ms)"
        )
        return
    }

    @JvmStatic
    fun main(args: Array<String>) {
        println("═══ آزمون موتور «همیار قاری» ═══\n")
        val (ref, _) = reference()
        report("۱) مرجع ۲۲٬۰۵۰ هرتز", ref, SR, "A4", "A3", 12.0)

        // ۲) همان اجرا با ۱۶k و تبدیل به ۲۲٬۰۵۰
        val at16k = Wav.resample(ref, SR, 16000)
        val back = Wav.resample(at16k, 16000, SR)
        report("۲) نسخهٔ ۱۶٬۰۰۰ هرتز", back, SR, "A4", "A3", 12.0)

        // ۳) ۸k (باند تلفنی)
        val at8k = Wav.resample(Wav.resample(ref, SR, 8000), 8000, SR)
        report("۳) نسخهٔ باند تلفنی ۸k", at8k, SR, "A4", "A3", 12.0)

        // ۴) کم‌صدا + نویز
        val quiet = addNoise(ref.map { it * 0.08 }.toDoubleArray(), 0.002)
        report("۴) کم‌صدا ۲۲dB + نویز", quiet, SR, "A4", "A3", 12.0)

        // ۵) ۶۰ ثانیه سکوت در ابتدا (باید نتیجه یکسان بدهد)
        val padded = DoubleArray((60 * SR)) + ref
        report("۵) ۶۰ ثانیه سکوت در ابتدا", padded, SR, "A4", "A3", 12.0)

        // ۶) نصف بلندی + قطبیت برعکس
        val inv = DoubleArray(ref.size) { -ref[it] * 0.5 }
        report("۶) نصف بلندی + قطبیت برعکس", inv, SR, "A4", "A3", 12.0)

        // ۷) خیلی کوتاه: یک نت تنها
        val one = synthNote(SR, 261.63, 3.0, seed = 11)
        report("۷) یک نت تنها C4", one, SR, "C4", "C4", 0.0)

        // ۸) صدای بم با هارمونیک ضعیف پایه (خطر خطای اکتاو)
        val low = synthNote(SR, 110.0, 3.0, seed = 13)
        report("۸) نت بم ۱۱۰Hz", low, SR, "A2", "A2", 0.0)

        println("\n═══ پایان ═══")
    }
}
