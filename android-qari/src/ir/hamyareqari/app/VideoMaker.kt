package ir.hamyareqari.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import ir.hamyareqari.dsp.PitchMath
import ir.hamyareqari.dsp.Report
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ساخت ویدیوی «ردِّ صدا» روی خود گوشی — بدون سرور، بدون ffmpeg.
 *
 * مسیر کار:
 *   Bitmap (ARGB) → تبدیل YUV420 → کدگذار H.264 با MediaCodec
 *   PCM ۱۶ بیتی → کدگذار AAC با MediaCodec
 *   هر دو با MediaMuxer در یک MP4 نوشته می‌شوند.
 *
 * اگر دستگاه کدگذار AAC نداشت، ویدیو بی‌صدا ساخته می‌شود (و پیام می‌دهد).
 */
class VideoMaker(private val ctx: Context) {

    class Result(val file: File, val frames: Int, val withAudio: Boolean, val ms: Long)

    /** کیفیت‌های پیشنهادی */
    enum class Quality(val label: String, val w: Int, val h: Int) {
        SD("۵۴۰p — سریع", 960, 540),
        HD("۷۲۰p — باکیفیت", 1280, 720)
    }

    companion object {
        private const val FPS = 25
        private const val AUDIO_RATE = 22050
        private const val AUDIO_BITRATE = 96_000
        private const val MAX_SECONDS = 20 * 60
    }

    /**
     * @param chart نموداری که فریم‌ها از آن رسم می‌شود
     * @param pcm صدای تحلیل‌شده (۲۲٬۰۵۰ هرتز، مونو)
     */
    fun build(
        r: Report,
        pcm: ShortArray,
        chart: ChartView,
        sourceTitle: String,
        quality: Quality,
        progress: (Double, String) -> Unit
    ): Result {
        val started = System.currentTimeMillis()
        val duration = min(r.durationSec, MAX_SECONDS.toDouble())
        val w = quality.w
        val h = quality.h
        val totalFrames = max(1, (duration * FPS).roundToInt())
        val outFile = File(ctx.cacheDir, "radde-seda-${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false

        val videoFormat = MediaFormat.createVideoFormat("video/avc", w, h).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            )
            setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1280) 4_500_000 else 2_400_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val videoCodec = MediaCodec.createEncoderByType("video/avc")
        videoCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        videoCodec.start()

        // ── کدگذار صدا (اختیاری) ──────────────────────────────────────────
        var audioCodec: MediaCodec? = null
        try {
            val af = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_RATE, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val ac = MediaCodec.createEncoderByType("audio/mp4a-latm")
            ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            ac.start()
            audioCodec = ac
        } catch (e: Exception) {
            audioCodec = null
        }

        val info = MediaCodec.BufferInfo()
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val yuv = ByteArray(w * h * 3 / 2)

        val audioEnd = min(pcm.size, (duration * AUDIO_RATE).toInt())
        var audioPos = 0
        var audioDone = audioCodec == null

        var videoFormatReady = false
        var audioFormatReady = false

        fun tryStartMuxer() {
            if (muxerStarted) return
            if (!videoFormatReady) return
            if (audioCodec != null && !audioFormatReady) return
            videoTrack = muxer.addTrack(videoCodec.outputFormat)
            audioCodec?.let { audioTrack = muxer.addTrack(it.outputFormat) }
            muxer.start()
            muxerStarted = true
        }

        fun drain(codec: MediaCodec, isVideo: Boolean, timeoutUs: Long) {
            while (true) {
                val idx = codec.dequeueOutputBuffer(info, timeoutUs)
                if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (isVideo) videoFormatReady = true else audioFormatReady = true
                    tryStartMuxer()
                    continue
                }
                if (idx < 0) continue
                val buf = codec.getOutputBuffer(idx) ?: continue
                if (info.size > 0 && muxerStarted) {
                    buf.position(info.offset)
                    buf.limit(info.offset + info.size)
                    val track = if (isVideo) videoTrack else audioTrack
                    if (track >= 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        muxer.writeSampleData(track, buf, info)
                    }
                }
                codec.releaseOutputBuffer(idx, false)
                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
            }
        }

        fun feedAudio(deadlineUs: Long) {
            val ac = audioCodec ?: return
            while (!audioDone) {
                val inIdx = ac.dequeueInputBuffer(0)
                if (inIdx < 0) break
                val buf = ac.getInputBuffer(inIdx) ?: break
                buf.clear()
                val room = buf.remaining() / 2
                val remain = audioEnd - audioPos
                if (remain <= 0) {
                    ac.queueInputBuffer(inIdx, 0, 0, (audioPos * 1_000_000L) / AUDIO_RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    audioDone = true
                    break
                }
                val count = min(room, remain)
                // تا هم‌گامی با فریم ویدیو، فقط تا نقطهٔ زمانی جاری جلو می‌رویم
                val allowed = ((deadlineUs * AUDIO_RATE) / 1_000_000L - audioPos).toInt()
                if (allowed <= 0) break
                val n = min(count, allowed)
                val sb = buf.asShortBuffer()
                sb.put(pcm, audioPos, n)
                ac.queueInputBuffer(inIdx, 0, n * 2, (audioPos * 1_000_000L) / AUDIO_RATE, 0)
                audioPos += n
            }
            drain(ac, false, 0)
        }

        try {
            var frameIndex = 0
            var lastReport = 0L
            while (frameIndex < totalFrames) {
                val t = frameIndex.toDouble() / FPS
                renderFrame(canvas, w, h, r, chart, t, sourceTitle)
                toYuv420SemiPlanar(bitmap, yuv, w, h)

                var queued = false
                while (!queued) {
                    val inIdx = videoCodec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = videoCodec.getInputBuffer(inIdx)!!
                        buf.clear()
                        buf.put(yuv)
                        val ptsUs = (frameIndex * 1_000_000L) / FPS
                        // صدا را کمی جلوتر بفرست تا هم‌گام بماند
                        feedAudio(ptsUs + 200_000L)
                        videoCodec.queueInputBuffer(
                            inIdx, 0, yuv.size, ptsUs,
                            if (frameIndex == totalFrames - 1) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                        queued = true
                    }
                    drain(videoCodec, true, 0)
                }
                drain(videoCodec, true, 0)
                // اگر کدگذار صدا تا اینجا فرمت نداده، بی‌صدا ادامه می‌دهیم تا خروجی خالی نماند
                if (frameIndex == 25 && audioCodec != null && !audioFormatReady) {
                    try { audioCodec!!.stop() } catch (e: Exception) { }
                    try { audioCodec!!.release() } catch (e: Exception) { }
                    audioCodec = null
                    audioDone = true
                    audioFormatReady = true
                    tryStartMuxer()
                }
                frameIndex++

                val now = System.currentTimeMillis()
                if (now - lastReport > 120) {
                    lastReport = now
                    progress(
                        frameIndex.toDouble() / totalFrames,
                        "رندر فریم ${PitchMath.faDigits(frameIndex)} از ${PitchMath.faDigits(totalFrames)}"
                    )
                }
            }

            // تخلیهٔ پایانی هر دو کدگذار
            for (i in 0 until 60) {
                drain(videoCodec, true, 5_000)
            }
            audioCodec?.let { ac ->
                while (!audioDone) {
                    feedAudio(Long.MAX_VALUE / 4)
                    val inIdx = ac.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        ac.queueInputBuffer(
                            inIdx, 0, 0,
                            (audioPos * 1_000_000L) / AUDIO_RATE,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        audioDone = true
                    }
                }
                for (i in 0 until 60) drain(ac, false, 5_000)
            }
            progress(1.0, "بستن فایل…")
        } finally {
            try { videoCodec.stop() } catch (e: Exception) { }
            try { videoCodec.release() } catch (e: Exception) { }
            audioCodec?.let {
                try { it.stop() } catch (e: Exception) { }
                try { it.release() } catch (e: Exception) { }
            }
            try { if (muxerStarted) muxer.stop() } catch (e: Exception) { }
            try { muxer.release() } catch (e: Exception) { }
            bitmap.recycle()
        }
        return Result(outFile, totalFrames, audioCodec != null, System.currentTimeMillis() - started)
    }

    // ── رسم یک فریم ───────────────────────────────────────────────────────

    /** یک فریم = همان نمودارِ روی صفحه، تمام‌قاب (خروجی ویدیو عیناً همان نمایش اپ) */
    private fun renderFrame(
        c: Canvas,
        w: Int,
        h: Int,
        r: Report,
        chart: ChartView,
        t: Double,
        title: String
    ) {
        val unit = w / 460f
        chart.drawFrameForVideo(c, w.toFloat(), h.toFloat(), unit, t)
    }

    private fun hzAt(r: Report, t: Double): Double {
        var lo = 0
        var hi = r.contourT.size - 1
        if (hi < 0) return 0.0
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (r.contourT[mid] < t) lo = mid + 1 else hi = mid
        }
        val i = lo.coerceIn(0, r.contourT.size - 1)
        return r.contourHz[i]
    }

    // ── تبدیل ARGB → YUV420 Semi-Planar (NV12/NV21 سازگار با کدگذارها) ──

    private fun toYuv420SemiPlanar(bmp: Bitmap, out: ByteArray, w: Int, h: Int) {
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        var yIdx = 0
        var uIdx = w * h
        var vIdx = w * h + 1
        val uvSize = (w * h) / 4
        val uArr = ByteArray(uvSize)
        val vArr = ByteArray(uvSize)
        var ui = 0
        var vi = 0
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                val p = pixels[row * w + col]
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                // BT.601 محدود (۱۶–۲۳۵)
                val y = ((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16
                out[yIdx++] = y.coerceIn(16, 235).toByte()
                if ((row and 1) == 0 && (col and 1) == 0) {
                    val u = ((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128
                    val v = ((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128
                    uArr[ui++] = u.coerceIn(16, 240).toByte()
                    vArr[vi++] = v.coerceIn(16, 240).toByte()
                }
                col++
            }
            row++
        }
        // NV12: U و V درهم، ابتدا U
        var k = 0
        var o = w * h
        while (k < uvSize) {
            out[o++] = uArr[k]
            out[o++] = vArr[k]
            k++
        }
        uIdx = 0; vIdx = 0  // فقط برای ساکت‌کردن تحلیل‌گر
    }

    /** ذخیرهٔ ویدیو در گالری (Movies) */
    fun saveToGallery(file: File, name: String): Persistence.Saved {
        return Persistence.saveVideo(ctx, file, name)
    }
}
