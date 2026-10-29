import android.content.Context
import android.media.MediaCodecInfo
import android.media.SimEnv
import ir.hamyareqari.app.ChartView
import ir.hamyareqari.app.VideoMaker
import ir.hamyareqari.dsp.*
import java.io.File

object LongRun {
    @JvmStatic
    fun main(args: Array<String>) {
        val ctx = Context()
        val wav = Wav.decode(File(System.getProperty("demo.wav", "../assets/demo.wav")).readBytes())!!
        val res = Wav.resample(wav.samples, wav.sampleRate, 22050)
        val pcm = ShortArray(res.size) { i -> (res[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort() }
        Analyzer.prepareShort(pcm, 22050)
        val r = Analyzer.analyzeSignal(ShortSignal(pcm, 22050))
        SimEnv.device = SimEnv.Device("long", intArrayOf(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar))
        val chart = ChartView(ctx).apply { setReport(r) }
        var maxProgress = 0.0
        val t0 = System.currentTimeMillis()
        val out = VideoMaker(ctx).build(r, pcm, chart, "دمو", VideoMaker.Quality.SD) { p, _ ->
            if (p > maxProgress) maxProgress = p
        }
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        val lines = out.file.readLines()
        val video = lines.count { it.startsWith("t0") }
        val audio = lines.count { it.startsWith("t1") }
        val audioLast = lines.filter { it.startsWith("t1") }
            .mapNotNull { it.substringAfter("pts=").substringBefore(" ").toLongOrNull() }.maxOrNull() ?: 0
        println("مدت صدا: ${"%.1f".format(r.durationSec)}s · فریم: ${out.frames} · نمونه ویدیو: $video · بستهٔ صدا: $audio")
        println("آخرین PTS صدا: ${audioLast / 1_000_000.0}s · پیشرفت تا ${"%.0f".format(maxProgress * 100)}% · رندر ${"%.1f".format(secs)}s")
        val ok = video == out.frames && audio > 0 && audioLast > (r.durationSec * 0.9 * 1e6).toLong() && maxProgress >= 1.0
        println(if (ok) "✅ تستِ طولانی موفق" else "❌ تست طولانی ناموفق")
        if (!ok) System.exit(1)
    }
}
