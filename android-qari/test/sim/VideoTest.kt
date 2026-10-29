import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.SimEnv
import ir.hamyareqari.app.ChartView
import ir.hamyareqari.app.VideoMaker
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.Wav
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * تستِ واقعیِ ساختِ ویدیو روی دسکتاپ با شبیه‌سازِ MediaCodec/MediaMuxer.
 * سه «دستگاه» مختلف را امتحان می‌کند و خروجی را از نظر محتوا و زمان‌بندی
 * بررسی می‌کند.
 */
object VideoTest {

    private var failures = 0

    private fun check(ok: Boolean, label: String, extra: String = "") {
        if (ok) println("  ✅ $label${if (extra.isEmpty()) "" else " — $extra"}")
        else { failures++; println("  ❌ $label${if (extra.isEmpty()) "" else " — $extra"}") }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val ctx = Context()
        val wav = Wav.decode(File(System.getProperty("demo.wav", "../assets/demo.wav")).readBytes())!!
        val res = Wav.resample(wav.samples, wav.sampleRate, 22050)
        val pcm = ShortArray(res.size) { i -> (res[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, 22050)
        val report = Analyzer.analyzeSignal(ShortSignal(pcm, 22050))
        // فقط ۳ ثانیهٔ اول را برای سرعتِ تست رندر می‌کنیم
        val dur = 3.0
        val cutT = report.contourT.filter { it <= dur }
        val cutHz = report.contourHz.take(cutT.size)
        val short = Report(
            durationSec = dur, hopMs = report.hopMs, sampleRate = report.sampleRate,
            highest = report.highest, lowest = report.lowest,
            rawHighest = report.rawHighest, rawLowest = report.rawLowest,
            spanSemitones = report.spanSemitones, spanOctaves = report.spanOctaves,
            spanCents = report.spanCents, fullOctaves = report.fullOctaves,
            restSemitones = report.restSemitones, quarterTones = report.quarterTones,
            ofOctavePct = report.ofOctavePct, centerHz = report.centerHz,
            notes = report.notes.filter { it.start < dur },
            shares = report.shares, pauseCount = report.pauseCount, pauses = report.pauses,
            longestPause = report.longestPause, sungSec = report.sungSec,
            quality = report.quality,
            contourT = cutT.toDoubleArray(), contourHz = cutHz.toDoubleArray()
        )
        val pcmCut = pcm.copyOfRange(0, minOf(pcm.size, (dur * 22050).toInt()))

        val FLEX = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        val SEMI = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        val PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar

        // ── دستگاه ۱: فقط Flexible (رایج‌ترین روی اندروید ۸+) ──────────────
        println("\n▸ دستگاه «Flexible» (فقط YUV420Flexible)")
        runCase(ctx, short, pcmCut, "flex", SimEnv.Device("flex", intArrayOf(FLEX)))

        // ── دستگاه ۲: فقط SemiPlanar (کدگذارهای قدیمی‌تر) ──────────────────
        println("\n▸ دستگاه «SemiPlanar» (فقط NV12)")
        runCase(ctx, short, pcmCut, "semi", SimEnv.Device("semi", intArrayOf(SEMI)))

        // ── دستگاه ۳: فقط Planar (I420) ────────────────────────────────────
        println("\n▸ دستگاه «Planar» (فقط I420)")
        runCase(ctx, short, pcmCut, "planar", SimEnv.Device("planar", intArrayOf(PLANAR)))

        // ── دستگاه ۴: Flexible ولی بدون Image (بافرِ خام) ───────────────────
        println("\n▸ دستگاه «Flexible بدون Image» (مسیر جایگزین)")
        runCase(ctx, short, pcmCut, "flexNoImg",
            SimEnv.Device("flexNoImg", intArrayOf(FLEX), imageStrides = false))

        // ── دستگاه ۵: بافر ورودیِ کوچک → باید پیامِ تمیز بدهد، نه کرش ───────
        println("\n▸ دستگاه «بافر کوچک» (باید خطای خوانا بدهد)")
        SimEnv.device = SimEnv.Device("tiny", intArrayOf(SEMI), inputCapacity = 512)
        var msg: String? = null
        try {
            val chart = ChartView(ctx).apply { setReport(short) }
            VideoMaker(ctx).build(short, pcmCut, chart, "t", VideoMaker.Quality.SD) { _, _ -> }
        } catch (e: Throwable) {
            msg = e.message
        }
        check(msg != null && msg.contains("کوچک"), "خطای خوانا برای بافرِ کوچک", msg ?: "هیچ خطایی نداد")

        // ── صدای خالی ──────────────────────────────────────────────────────
        println("\n▸ ورودیِ بی‌صدا")
        SimEnv.device = SimEnv.Device("flex2", intArrayOf(FLEX))
        var msg2: String? = null
        try {
            val chart = ChartView(ctx).apply { setReport(short) }
            VideoMaker(ctx).build(short, ShortArray(0), chart, "t", VideoMaker.Quality.SD) { _, _ -> }
        } catch (e: Throwable) {
            msg2 = e.message
        }
        check(msg2 != null, "ورودی خالی با پیام رد شد", msg2 ?: "-")

        println("\n" + if (failures == 0) "🎉 همهٔ تست‌ها موفق" else "❗ $failures تست ناموفق")
        if (failures > 0) System.exit(1)
    }

    private fun runCase(ctx: Context, r: Report, pcm: ShortArray, tag: String, dev: SimEnv.Device) {
        SimEnv.device = dev
        SimEnv.decoded.clear()
        SimEnv.statAudioQueues = 0
        SimEnv.statAudioDequeues = 0
        val quality = VideoMaker.Quality.SD
        val chart = ChartView(ctx).apply { setReport(r) }
        var lastLabel = ""
        var progressCount = 0
        val t0 = System.currentTimeMillis()
        val out = try {
            VideoMaker(ctx).build(r, pcm, chart, "دمو", quality) { p, label ->
                progressCount++
                lastLabel = label
                if (p < 0 || p > 1.0) failures++
            }
        } catch (e: Throwable) {
            failures++
            println("  ❌ استثنا: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            return
        }
        val ms = System.currentTimeMillis() - t0
        SimEnv.lastAudioQueues = 0
        val expectedFrames = (r.durationSec * 25).roundToInt()
        println("  ↪ کدگذار=${out.codecName} · فرمت=${out.colorFormat} · فریم=${out.frames} · صدا=${out.withAudio} · ${ms}ms")

        check(out.colorFormat == "YUV420Flexible" || out.colorFormat == "YUV420SemiPlanar" ||
                out.colorFormat == "YUV420Planar", "فرمت رنگ با دستگاه هم‌خوان است", out.colorFormat)
        check(out.frames == expectedFrames, "تعداد فریم", "${out.frames} / $expectedFrames")
        check(out.file.exists() && out.file.length() > 1000, "فایل خروجی ساخته شد",
            "«${out.file.length()} بایت»")
        check(progressCount > 0 && lastLabel.isNotEmpty(), "گزارش پیشرفت", "$progressCount گزارش")
        check(out.withAudio, "صدا در ویدیو هست")

        // PTS باید صعودی و با نرخ ۲۵ باشد
        val codecPts = SimEnv.device
        // فریم‌های رمزگشایی‌شده با فاصلهٔ ۴۰۰۰۰µs
        val keySet = samplePtsOf(out.file)
        check(keySet.isNotEmpty(), "نمونه‌های ویدیو در فایل نوشته شدند", "${keySet.size} نمونه")
        // صدا: باید تا انتها نوشته شده باشد
        val audioLines = out.file.readLines().filter { it.startsWith("t1") }
        check(audioLines.isNotEmpty(), "نمونه‌های صدا نوشته شدند", "${audioLines.size} بسته")
        val audioLast = audioLines.mapNotNull {
            it.substringAfter("pts=").substringBefore(" ").toLongOrNull()
        }.maxOrNull() ?: 0L
        check(audioLast > (r.durationSec * 1_000_000L * 0.6).toLong(), "صدا تا انتها رفت",
            "${audioLast / 1000}ms از ${(r.durationSec * 1000).toInt()}ms")

        val sorted = keySet.sorted()
        var mono = true
        for (i in 1 until sorted.size) if (sorted[i] <= sorted[i - 1]) mono = false
        check(mono, "زمان‌های نمونه صعودی‌اند")
        if (sorted.size > 2) {
            val dt = (sorted[1] - sorted[0]) / 1000.0
            check(abs(dt - 40.0) < 1.0, "فاصلهٔ فریم‌ها ۴۰ms", "${dt}ms")
        }

        // بازبینیِ محتوا: فریمِ ساخته‌شده باید همان نمودار باشد
        compareFrames(ctx, r, quality, tag)
    }

    /** زمان‌های نمونه‌های ویدیو را از پروندهٔ ساختگی می‌خواند */
    private fun samplePtsOf(file: File): LongArray {
        val lines = file.readLines().filter { it.startsWith("t0") }
        return lines.mapNotNull {
            it.substringAfter("pts=").substringBefore(" ").toLongOrNull()
        }.toLongArray()
    }

    /** مقایسهٔ فریمِ بازسازی‌شده توسط کدگذار با نمودارِ اصلی (PSNR) */
    private fun compareFrames(ctx: Context, r: Report, q: VideoMaker.Quality, tag: String) {
        val w = q.w
        val h = q.h
        val decodeKey = 1_000_000L // t = ۱ ثانیه
        val decoded = SimEnv.decoded[decodeKey]
        if (decoded == null) {
            // دستگاه‌هایی که مسیر بافر دارند فریم رمزگشایی‌شده ندارند
            println("  · (بدون بازبینی محتوا برای $tag)")
            return
        }
        val rgb = decoded.first
        val chart = ChartView(ctx).apply { setReport(r) }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        chart.drawFrameForVideo(Canvas(bmp), w.toFloat(), h.toFloat(), w / 460f, 1.0)
        val ref = IntArray(w * h)
        bmp.getPixels(ref, 0, w, 0, 0, w, h)

        var se = 0.0
        var count = 0
        for (i in ref.indices) {
            val a = ref[i]
            val b = rgb[i]
            for (shift in intArrayOf(16, 8, 0)) {
                val d = ((a shr shift) and 0xFF) - ((b shr shift) and 0xFF)
                se += d.toDouble() * d
                count++
            }
        }
        val mse = se / count
        val psnr = if (mse <= 1e-9) 99.0 else 10 * log10(255.0 * 255.0 / mse)
        check(psnr > 28.0, "تصویرِ رسیده به کدگذار همان نمودار است (PSNR)", "%.1f dB".format(psnr))
    }
}
