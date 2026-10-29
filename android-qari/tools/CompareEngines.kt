package ir.hamyareqari.tools

import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.Engine
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.Wav
import java.io.File

/**
 * مقایسهٔ دو موتور روی یک فایل واقعی (و اثر کاهش نویز).
 *
 *   java -cp <classes>:<stdlib> ir.hamyareqari.tools.CompareEngines <file.wav> [--denoise]
 *
 * توجه: این فایل «حقیقت مبنا» (ground truth) ندارد؛ اعداد خروجی فقط تفاوت
 * رفتار دو مسیر را نشان می‌دهند، نه دقت مطلق روی تلاوت واقعی.
 */
object CompareEngines {

    private fun run(name: String, sig: ShortSignal, engine: Engine, denoise: Boolean): Report {
        val t0 = System.nanoTime()
        val r = Analyzer.analyzeSignal(sig, denoise, null, engine)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val line = name.padEnd(20) + " " + ms.toString().padStart(6) + "ms" +
            " | نت=" + r.noteCount.toString().padStart(3) +
            " | گستره=" + String.format(java.util.Locale.US, "%.1f", r.spanSemitones) +
            " | بالا=" + (r.highest?.nameAscii ?: "—") +
            " پایین=" + (r.lowest?.nameAscii ?: "—") +
            " | SNR=" + String.format(java.util.Locale.US, "%.1f", r.quality.snrDb) +
            " | هم‌رأیی=" + String.format(java.util.Locale.US, "%.0f", r.quality.agreementPct) +
            " | معتبر=" + String.format(java.util.Locale.US, "%.0f", r.validShare * 100.0) +
            "٪ (تخمینی " + String.format(java.util.Locale.US, "%.1f", r.estimatedShare * 100.0) + "٪)" +
            " | کیفیت=" + r.quality.status
        println(line)
        for (w in r.quality.warnings) println("      ⚠ $w")
        return r
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val path = args.firstOrNull { !it.startsWith("--") } ?: "android-qari/assets/demo.wav"
        val f = File(path)
        if (!f.exists()) {
            println("فایل پیدا نشد: $path")
            return
        }
        val wav = Wav.decode(f.readBytes())
        if (wav == null) {
            println("WAV خوانده نشد: $path")
            return
        }
        val res = Wav.resample(wav.samples, wav.sampleRate, AudioIoRate)
        val pcm = ShortArray(res.size) { i -> (res[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, AudioIoRate)
        val sig = ShortSignal(pcm, AudioIoRate)
        println("═══ مقایسهٔ موتور روی «${f.name}» — ${String.format(java.util.Locale.US, "%.1f", pcm.size.toDouble() / AudioIoRate)} ثانیه ═══")
        val legacy = run("legacy", sig, Engine.LEGACY, false)
        val enhanced = run("enhanced", sig, Engine.ENHANCED, false)
        run("enhanced + denoise", sig, Engine.ENHANCED, true)
        run("legacy + denoise", sig, Engine.LEGACY, true)

        // مقایسهٔ منحنی‌ها (فقط روی نقاط معتبر هر دو)
        val n = minOf(legacy.contourHz.size, enhanced.contourHz.size)
        var both = 0
        var close = 0
        val diffs = ArrayList<Double>()
        for (i in 0 until n) {
            val a = legacy.contourHz[i]
            val b = enhanced.contourHz[i]
            if (a > 0.0 && b > 0.0) {
                both++
                val c = PitchMath.cents(b, a)
                diffs.add(Math.abs(c))
                if (Math.abs(c) <= 50.0) close++
            }
        }
        println()
        println("نقاط منحنی: legacy=${legacy.contourHz.size} · enhanced=${enhanced.contourHz.size} · مشترک=$both")
        if (diffs.isNotEmpty()) {
            val arr = diffs.sorted()
            println(
                "اختلاف منحنی (سنت) — میانه=%.2f · صدک۹۰=%.2f · بیشینه=%.1f · سهم ≤۵۰ سنت=%.1f%%"
                    .format(
                        java.util.Locale.US, arr[arr.size / 2], arr[(0.9 * (arr.size - 1)).toInt()],
                        arr.last(), 100.0 * close / both
                    )
            )
        }
        // حافظهٔ مصرفی (تقریبی، پس از تخلیه)
        System.gc()
        val rt = Runtime.getRuntime()
        println("حافظهٔ JVM: max=%.0fMB · used=%.0fMB".format(java.util.Locale.US, rt.maxMemory() / 1048576.0, (rt.totalMemory() - rt.freeMemory()) / 1048576.0))
    }

    private const val AudioIoRate = 22050
}
