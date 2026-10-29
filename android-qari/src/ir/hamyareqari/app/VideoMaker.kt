package ir.hamyareqari.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
 *   Bitmap (ARGB) → YUV 4:2:0 → کدگذار H.264 با MediaCodec
 *   PCM ۱۶ بیتی → کدگذار AAC با MediaCodec
 *   هر دو با MediaMuxer در یک MP4 نوشته می‌شوند.
 *
 * ── چرا این‌قدر احتیاط؟ (رفعِ کرشِ نسخهٔ ۱٫۱٫۱) ─────────────────────────
 * ۱) کدگذارهای H.264 روی هر گوشی یک «فرمت رنگِ ورودی» متفاوت می‌خواهند.
 *    نسخهٔ قبل همیشه NV12 (SemiPlanar) می‌فرستاد؛ اگر کدگذار آن را قبول
 *    نمی‌کرد، خطا در لایهٔ بومی (C++) رخ می‌داد و اپ **بدون استثنا** بسته
 *    می‌شد. حالا فرمت‌های پشتیبانی‌شدهٔ همان کدگذار خوانده می‌شود و
 *    به‌ترتیب Flexible → SemiPlanar → Planar → PackedSemiPlanar امتحان
 *    می‌شوند.
 * ۲) نوشتن در بافر ورودی با `put(ByteArray)` بدون بررسی ظرفیت، در بعضی
 *    کدگذارها BufferOverflowException/کرش می‌داد. حالا اول ظرفیت بررسی
 *    می‌شود و در حالت Flexible تصویر مستقیماً در Image نوشته می‌شود.
 * ۳) محاسبهٔ مهلت صدا با `Long.MAX_VALUE` سرریز می‌کرد و طولِ منفی به
 *    `ShortBuffer.put` می‌رسید (IndexOutOfBounds). جایش `null` = بی‌سقف.
 * ۴) نمودارِ روی صفحه در ترد رابط کاربری رسم می‌شد و هم‌زمان ترد رندر هم
 *    در همان Paint/Path می‌نوشت (مسابقهٔ نخی → کرش بومی). حالا برای ویدیو
 *    یک ChartView جدا ساخته می‌شود و رسمِ آن قفل دارد.
 * هر خطایی هم به‌صورت [VideoException] با پیام فارسی بالا می‌رود تا اپ
 * به‌جای بسته‌شدن، دلیل را نشان بدهد.
 */
class VideoMaker(private val ctx: Context) {

    class Result(
        val file: File,
        val frames: Int,
        val withAudio: Boolean,
        val ms: Long,
        val codecName: String,
        val colorFormat: String
    )

    /** خطای قابل‌نمایش به کاربر (نه کرش) */
    class VideoException(message: String, cause: Throwable? = null) : Exception(message, cause)

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

        // فرمت‌های رنگِ ورودیِ کدگذار (به‌ترتیب اولویت)
        private const val FLEXIBLE = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        private const val SEMI_PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        private const val PLANAR = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        private const val PACKED_SEMI = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar
    }

    /**
     * @param chart نمودارِ اختصاصیِ رندر (نباید همان نمودارِ روی صفحه باشد)
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
        if (duration <= 0.0 || pcm.isEmpty()) {
            throw VideoException("صدایی برای ساخت ویدیو نیست")
        }
        val w = quality.w
        val h = quality.h
        val totalFrames = max(1, (duration * FPS).roundToInt())
        val outFile = File(ctx.cacheDir, "radde-seda-${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        // ── انتخاب کدگذارِ ویدیو با فرمتِ رنگیِ پشتیبانی‌شده ──────────────
        progress(0.0, "آماده‌سازی کدگذار…")
        val picked = pickVideoEncoder(w, h)
        val videoCodec = picked.codec
        val colorFormat = picked.colorFormat
        val codecName = picked.name

        var audioCodec: MediaCodec? = null
        val muxer = try {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            safeRelease(videoCodec)
            throw VideoException("ساخت فایل MP4 ممکن نشد", e)
        }

        var videoTrack = -1
        var audioTrack = -1
        var muxerStarted = false
        var videoFormatReady = false
        var audioFormatReady = false
        var encodeError: Throwable? = null

        val info = MediaCodec.BufferInfo()
        var bitmap: Bitmap? = null
        val pixels = IntArray(w * h)
        val yuv = ByteArray(w * h * 3 / 2)

        try {
            // ── کدگذار صدا (اختیاری) ──────────────────────────────────────
            audioCodec = try {
                val af = MediaFormat.createAudioFormat("audio/mp4a-latm", AUDIO_RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val ac = MediaCodec.createEncoderByType("audio/mp4a-latm")
                ac.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                ac.start()
                ac
            } catch (e: Throwable) {
                null // ویدیوی بی‌صدا هم قابل قبول است
            }

            val audioEnd = min(pcm.size, (duration * AUDIO_RATE).toInt())
            var audioPos = 0
            var audioDone = audioCodec == null
            var audioEosQueued = false

            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            fun startMuxerIfReady() {
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
                    val idx = try {
                        codec.dequeueOutputBuffer(info, timeoutUs)
                    } catch (e: IllegalStateException) {
                        return
                    }
                    if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) return
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (isVideo) videoFormatReady = true else audioFormatReady = true
                        startMuxerIfReady()
                        continue
                    }
                    if (idx < 0) continue
                    val buf = codec.getOutputBuffer(idx)
                    val track = if (isVideo) videoTrack else audioTrack
                    if (buf != null && info.size > 0 && muxerStarted && track >= 0 &&
                        (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
            }

            /**
             * صدا را تا نقطهٔ زمانی limitUs (یا همه را اگر null) در کدگذار می‌ریزد.
             *
             * نکتهٔ مهم (باگِ نسخهٔ ۱٫۱٫۱): هر بافرِ ورودی که از کدگذار
             * گرفته می‌شود **باید** پر و برگردانده شود؛ گرفتن و رهاکردن
             * (مثلاً وقتی هنوز به لحظهٔ مجاز نرسیده‌ایم) بافر را برای همیشه
             * از دست می‌دهد و بعد از چند فریم کدگذار صدا قفل می‌شود.
             * پس شرط‌ها پیش از گرفتنِ بافر سنجیده می‌شوند.
             */
            fun feedAudio(limitUs: Long?) {
                val ac = audioCodec ?: return
                while (true) {
                    val remain = audioEnd - audioPos
                    if (remain <= 0) {
                        if (!audioEosQueued) {
                            val inIdx = try { ac.dequeueInputBuffer(0) } catch (e: IllegalStateException) { return }
                            if (inIdx < 0) return
                            audioEosQueued = true
                            audioDone = true
                            ac.queueInputBuffer(
                                inIdx, 0, 0,
                                (audioPos.toLong() * 1_000_000L) / AUDIO_RATE,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                        }
                        break
                    }
                    if (limitUs != null) {
                        val allowed = (limitUs * AUDIO_RATE / 1_000_000L) - audioPos
                        if (allowed <= 0) break
                    }
                    val inIdx = try {
                        ac.dequeueInputBuffer(0)
                    } catch (e: IllegalStateException) {
                        return
                    }
                    if (inIdx < 0) break
                    val buf = ac.getInputBuffer(inIdx)
                    if (buf == null) {
                        // بافرِ ناموجود: بازگرداندنِ خالی بهتر از ازدست‌دادن آن است
                        ac.queueInputBuffer(inIdx, 0, 0,
                            (audioPos.toLong() * 1_000_000L) / AUDIO_RATE, 0)
                        break
                    }
                    buf.clear()
                    var count = min(buf.capacity() / 2, remain)
                    if (limitUs != null) {
                        val allowed = (limitUs * AUDIO_RATE / 1_000_000L) - audioPos
                        count = min(count, allowed.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                    }
                    if (count <= 0) {
                        ac.queueInputBuffer(inIdx, 0, 0,
                            (audioPos.toLong() * 1_000_000L) / AUDIO_RATE, 0)
                        break
                    }
                    buf.asShortBuffer().put(pcm, audioPos, count)
                    ac.queueInputBuffer(
                        inIdx, 0, count * 2,
                        (audioPos.toLong() * 1_000_000L) / AUDIO_RATE, 0
                    )
                    audioPos += count
                }
                drain(ac, false, 0)
            }

            /** یک فریم را در کدگذار می‌گذارد و خروجی‌های آماده را می‌نویسد */
            fun encodeFrame(ptsUs: Long, eos: Boolean) {
                var guard = 0
                while (true) {
                    guard++
                    if (guard > 5000) throw VideoException("کدگذار ویدیو پاسخ نداد")
                    val inIdx = try {
                        videoCodec.dequeueInputBuffer(10_000)
                    } catch (e: IllegalStateException) {
                        throw VideoException("کدگذار ویدیو از کار افتاد", e)
                    }
                    drain(videoCodec, true, 0)
                    if (inIdx < 0) continue

                    if (colorFormat == FLEXIBLE) {
                        val img = videoCodec.getInputImage(inIdx)
                        if (img == null) {
                            // کدگذار Image نداد ⇒ بافرِ خام را با احتیاط پر کن
                            fillFlexibleFallbackOrThrow(videoCodec, inIdx, bitmap!!, pixels, w, h, yuv, ptsUs, eos)
                        } else {
                            fillImage(img, bitmap!!, pixels, w, h)
                            videoCodec.queueInputBuffer(
                                inIdx, 0, w * h * 3 / 2, ptsUs,
                                if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            )
                        }
                    } else {
                        val buf = videoCodec.getInputBuffer(inIdx)
                            ?: throw VideoException("بافر ورودی کدگذار خالی بود")
                        if (buf.capacity() < yuv.size) {
                            throw VideoException(
                                "بافر کدگذار کوچک است (${buf.capacity()} < ${yuv.size})"
                            )
                        }
                        val payload = when (colorFormat) {
                            PLANAR -> toYuv420Planar(bitmap!!, pixels, w, h)
                            else -> toYuv420SemiPlanar(bitmap!!, pixels, w, h, yuv)
                        }
                        buf.clear()
                        buf.put(payload)
                        videoCodec.queueInputBuffer(
                            inIdx, 0, payload.size, ptsUs,
                            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        )
                    }
                    break
                }
            }

            // ── حلقهٔ اصلی: هر فریم = همان نمودارِ روی صفحه ───────────────
            var frameIndex = 0
            var lastReport = 0L
            while (frameIndex < totalFrames) {
                val t = frameIndex.toDouble() / FPS
                renderFrame(canvas, w, h, chart, t)
                val ptsUs = (frameIndex.toLong() * 1_000_000L) / FPS
                feedAudio(ptsUs + 200_000L)
                encodeFrame(ptsUs, frameIndex == totalFrames - 1)
                drain(videoCodec, true, 0)

                // اگر کدگذار صدا تا فریمِ ۲۵ فرمت نداد، بی‌صدا ادامه می‌دهیم
                if (frameIndex == 25 && audioCodec != null && !audioFormatReady && !muxerStarted) {
                    safeRelease(audioCodec)
                    audioCodec = null
                    audioDone = true
                    audioFormatReady = true
                    startMuxerIfReady()
                }
                frameIndex++

                val now = System.currentTimeMillis()
                if (now - lastReport > 120 || frameIndex == totalFrames) {
                    lastReport = now
                    progress(
                        frameIndex.toDouble() / totalFrames,
                        "رندر فریم ${PitchMath.faDigits(frameIndex)} از ${PitchMath.faDigits(totalFrames)}"
                    )
                }
            }

            // ── تخلیهٔ پایانی ────────────────────────────────────────────
            repeat(200) { drain(videoCodec, true, 5_000) }
            audioCodec?.let { ac ->
                if (!audioEosQueued) feedAudio(null)
                repeat(40) { drain(ac, false, 5_000) }
            }
            progress(1.0, "بستن فایل…")
        } catch (e: Throwable) {
            encodeError = e
        } finally {
            safeStopRelease(videoCodec)
            audioCodec?.let { safeStopRelease(it) }
            try { if (muxerStarted) muxer.stop() } catch (e: Throwable) { }
            try { muxer.release() } catch (e: Throwable) { }
            try { bitmap?.recycle() } catch (e: Throwable) { }
        }

        if (encodeError != null) {
            outFile.delete()
            val msg = when (encodeError) {
                is VideoException -> encodeError.message ?: "خطای کدگذاری"
                is OutOfMemoryError -> "حافظه کافی نبود"
                else -> encodeError.message ?: encodeError.javaClass.simpleName
            }
            throw VideoException(msg, encodeError)
        }
        if (!outFile.exists() || outFile.length() < 1000L) {
            throw VideoException("فایل ویدیو خالی ساخته شد")
        }
        return Result(
            outFile, totalFrames, audioCodec != null,
            System.currentTimeMillis() - started, codecName, colorFormatName(colorFormat)
        )
    }

    // ── انتخاب و تنظیم کدگذار ──────────────────────────────────────────────

    private class PickedCodec(val codec: MediaCodec, val colorFormat: Int, val name: String)

    private fun pickVideoEncoder(w: Int, h: Int): PickedCodec {
        val supported: IntArray = try {
            val probe = MediaCodec.createEncoderByType("video/avc")
            val caps = probe.codecInfo.getCapabilitiesForType("video/avc")
            val formats = caps.colorFormats.copyOf()
            val nm = probe.name
            safeRelease(probe)
            lastProbeName = nm
            formats
        } catch (e: Throwable) {
            intArrayOf(FLEXIBLE, SEMI_PLANAR, PLANAR)
        }

        val order = listOf(FLEXIBLE, SEMI_PLANAR, PLANAR, PACKED_SEMI)
        val candidates = order.filter { cf -> supported.isEmpty() || supported.contains(cf) }
            .ifEmpty { order }

        var lastError: Throwable? = null
        for (cf in candidates) {
            var codec: MediaCodec? = null
            try {
                val format = MediaFormat.createVideoFormat("video/avc", w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (w >= 1280) 4_500_000 else 2_400_000)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, w * h * 3 / 2 + 4096)
                }
                codec = MediaCodec.createEncoderByType("video/avc")
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                return PickedCodec(codec, cf, codec.name ?: lastProbeName)
            } catch (e: Throwable) {
                lastError = e
                codec?.let { safeRelease(it) }
            }
        }
        throw VideoException("این دستگاه کدگذار H.264 مناسب ندارد", lastError)
    }

    private var lastProbeName: String = "H.264"

    private fun colorFormatName(cf: Int): String = when (cf) {
        FLEXIBLE -> "YUV420Flexible"
        SEMI_PLANAR -> "YUV420SemiPlanar"
        PLANAR -> "YUV420Planar"
        PACKED_SEMI -> "YUV420PackedSemiPlanar"
        else -> "0x${Integer.toHexString(cf)}"
    }

    private fun safeRelease(codec: MediaCodec?) {
        try { codec?.release() } catch (e: Throwable) { }
    }

    private fun safeStopRelease(codec: MediaCodec?) {
        try { codec?.stop() } catch (e: Throwable) { }
        try { codec?.release() } catch (e: Throwable) { }
    }

    // ── رسم یک فریم ────────────────────────────────────────────────────────

    /** یک فریم = همان نمودارِ روی صفحه، تمام‌قاب (خروجی ویدیو عیناً همان نمایش اپ) */
    private fun renderFrame(c: Canvas, w: Int, h: Int, chart: ChartView, t: Double) {
        val unit = w / 460f
        chart.drawFrameForVideo(c, w.toFloat(), h.toFloat(), unit, t)
    }

    // ── پر کردن تصویرِ کدگذار (مسیر Flexible) ──────────────────────────────

    private fun fillImage(img: android.media.Image, bmp: Bitmap, px: IntArray, w: Int, h: Int) {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val planes = img.planes
        if (planes.size < 3) throw VideoException("تصویر کدگذار سه لایه نداشت")
        val yP = planes[0]
        val uP = planes[1]
        val vP = planes[2]
        val yBuf = yP.buffer
        val uBuf = uP.buffer
        val vBuf = vP.buffer

        // لایهٔ Y
        var i = 0
        for (row in 0 until h) {
            val base = row * yP.rowStride
            var off = base
            for (col in 0 until w) {
                val p = px[i++]
                val rr = (p shr 16) and 0xFF
                val gg = (p shr 8) and 0xFF
                val bb = p and 0xFF
                val y = (((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).coerceIn(16, 235)
                yBuf.put(off, y.toByte())
                off += yP.pixelStride
            }
        }

        // لایه‌های رنگی (۲×۲ میانگین)
        val cw = w / 2
        val ch = h / 2
        for (row in 0 until ch) {
            val r0 = row * 2
            for (col in 0 until cw) {
                val c0 = col * 2
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (dy in 0..1) {
                    val base = (r0 + dy) * w + c0
                    for (dx in 0..1) {
                        val p = px[base + dx]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val rr = sumR / 4
                val gg = sumG / 4
                val bb = sumB / 4
                val u = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(16, 240)
                val v = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(16, 240)
                val uOff = row * uP.rowStride + col * uP.pixelStride
                val vOff = row * vP.rowStride + col * vP.pixelStride
                if (uOff < uBuf.capacity()) uBuf.put(uOff, u.toByte())
                if (vOff < vBuf.capacity()) vBuf.put(vOff, v.toByte())
            }
        }
    }

    /** اگر کدگذار با Flexible بافرِ Image نداد، همان بافر خام را پر می‌کنیم */
    private fun fillFlexibleFallbackOrThrow(
        codec: MediaCodec,
        inIdx: Int,
        bmp: Bitmap,
        px: IntArray,
        w: Int,
        h: Int,
        yuv: ByteArray,
        ptsUs: Long,
        eos: Boolean
    ) {
        val buf = codec.getInputBuffer(inIdx) ?: throw VideoException("بافر ورودی کدگذار خالی بود")
        val payload = toYuv420SemiPlanar(bmp, px, w, h, yuv)
        if (buf.capacity() < payload.size) {
            throw VideoException("بافر کدگذار کوچک است")
        }
        buf.clear()
        buf.put(payload)
        codec.queueInputBuffer(
            inIdx, 0, payload.size, ptsUs,
            if (eos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
        )
    }

    // ── تبدیل ARGB → YUV ──────────────────────────────────────────────────

    /** NV12: صفحهٔ Y و سپس U,V درهم — رایج‌ترین فرمت کدگذارهای موبایل */
    private fun toYuv420SemiPlanar(bmp: Bitmap, px: IntArray, w: Int, h: Int, out: ByteArray): ByteArray {
        if (out.size != w * h * 3 / 2) throw VideoException("اندازهٔ بافر YUV نامناسب است")
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var yIdx = 0
        for (i in 0 until w * h) {
            val p = px[i]
            val rr = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val bb = p and 0xFF
            val y = (((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).coerceIn(16, 235)
            out[yIdx++] = y.toByte()
        }
        var o = w * h
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = px[(row + dy) * w + (col + dx)]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val rr = sumR / 4
                val gg = sumG / 4
                val bb = sumB / 4
                val u = (((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(16, 240)
                val v = (((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(16, 240)
                out[o++] = u.toByte()
                out[o++] = v.toByte()
                col += 2
            }
            row += 2
        }
        return out
    }

    /** I420: سه صفحهٔ جدا — برای کدگذارهایی که Planar می‌خواهند */
    private fun toYuv420Planar(bmp: Bitmap, px: IntArray, w: Int, h: Int): ByteArray {
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val out = ByteArray(w * h * 3 / 2)
        var yIdx = 0
        for (i in 0 until w * h) {
            val p = px[i]
            val rr = (p shr 16) and 0xFF
            val gg = (p shr 8) and 0xFF
            val bb = p and 0xFF
            out[yIdx++] = ((((66 * rr + 129 * gg + 25 * bb + 128) shr 8) + 16).coerceIn(16, 235)).toByte()
        }
        var uIdx = w * h
        var vIdx = w * h + (w * h) / 4
        var row = 0
        while (row < h) {
            var col = 0
            while (col < w) {
                var sumR = 0
                var sumG = 0
                var sumB = 0
                for (dy in 0..1) {
                    for (dx in 0..1) {
                        val p = px[(row + dy) * w + (col + dx)]
                        sumR += (p shr 16) and 0xFF
                        sumG += (p shr 8) and 0xFF
                        sumB += p and 0xFF
                    }
                }
                val rr = sumR / 4
                val gg = sumG / 4
                val bb = sumB / 4
                out[uIdx++] = ((((-38 * rr - 74 * gg + 112 * bb + 128) shr 8) + 128).coerceIn(16, 240)).toByte()
                out[vIdx++] = ((((112 * rr - 94 * gg - 18 * bb + 128) shr 8) + 128).coerceIn(16, 240)).toByte()
                col += 2
            }
            row += 2
        }
        return out
    }

    /** ذخیرهٔ ویدیو در گالری (Movies) */
    fun saveToGallery(file: File, name: String): Persistence.Saved {
        return Persistence.saveVideo(ctx, file, name)
    }
}
