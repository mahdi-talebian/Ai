package android.media

import java.nio.ByteBuffer

/** بازسازیِ MediaFormat برای تستِ دسکتاپ */
class MediaFormat {
    val map = HashMap<String, Any>()

    fun setInteger(key: String, value: Int) { map[key] = value }
    fun getInteger(key: String): Int = map[key] as? Int ?: throw NullPointerException("no $key")
    fun containsKey(key: String) = map.containsKey(key)
    val width: Int get() = (map["width"] as? Int) ?: 0
    val height: Int get() = (map["height"] as? Int) ?: 0
    val mime: String get() = (map["mime"] as? String) ?: ""

    companion object {
        const val KEY_COLOR_FORMAT = "color-format"
        const val KEY_BIT_RATE = "bitrate"
        const val KEY_FRAME_RATE = "frame-rate"
        const val KEY_I_FRAME_INTERVAL = "i-frame-interval"
        const val KEY_MAX_INPUT_SIZE = "max-input-size"
        const val KEY_AAC_PROFILE = "aac-profile"

        fun createVideoFormat(mime: String, w: Int, h: Int) = MediaFormat().apply {
            map["mime"] = mime; map["width"] = w; map["height"] = h
        }

        fun createAudioFormat(mime: String, rate: Int, ch: Int) = MediaFormat().apply {
            map["mime"] = mime; map["sample-rate"] = rate; map["channel-count"] = ch
        }
    }
}

class MediaCodecInfo(private val nm: String) {
    val name: String get() = nm

    class CodecCapabilities(val colorFormats: IntArray) {
        companion object {
            const val COLOR_FormatYUV420Flexible = 0x7F420888
            const val COLOR_FormatYUV420SemiPlanar = 21
            const val COLOR_FormatYUV420Planar = 19
            const val COLOR_FormatYUV420PackedSemiPlanar = 39
            const val COLOR_FormatSurface = 2130708361
        }
    }

    class CodecProfileLevel {
        companion object { const val AACObjectLC = 2 }
    }

    fun getCapabilitiesForType(type: String) =
        CodecCapabilities(SimEnv.device.colorFormats)
}

/** وضعیت دستگاهِ شبیه‌سازی‌شده */
object SimEnv {
    class Device(
        val name: String,
        val colorFormats: IntArray,
        val inputCapacity: Int = 0,      // ۰ = اندازهٔ معمول
        val imageStrides: Boolean = true // Flexible با چیدمان NV12
    )

    var device = Device("default", intArrayOf(0x7F420888, 21))
    /** فریم‌هایی که کدگذار از تصویرِ ورودی بازسازی کرده (برای بازبینی محتوا) */
    val decoded = HashMap<Long, Triple<IntArray, Int, Int>>()
    var lastAudioQueues = 0
    var lastAudioSamples = 0L
    var statAudioQueues = 0
    var statAudioDequeues = 0
    var statAudioInputFails = 0
    var failConfigure = false
}

class MediaCodec internal constructor(val mime: String) {

    class BufferInfo {
        var offset: Int = 0
        var size: Int = 0
        var presentationTimeUs: Long = 0
        var flags: Int = 0
    }


    val name: String = "c2.sim." + mime.replace('/', '.')
    val codecInfo = MediaCodecInfo(name)

    private var configured = false
    private var started = false
    private var colorFormat = 0
    private var w = 0
    private var h = 0

    var outputFormat = MediaFormat()
        private set

    private var inputFree = ArrayDeque<Int>()
    private var pendingOut = ArrayDeque<OutChunk>()
    private var nextPts = 0L
    private var sawEos = false
    private var eosSent = false
    private var formatSent = false

    var framesQueued = 0
        private set
    var bytesQueued = 0L
        private set
    var audioInputQueues = 0
    var audioSamplesQueued = 0L
        private set
    val queuedPts = ArrayList<Long>()
    val decodedFrames = HashMap<Long, SimDecoded>()
    var lastError: String? = null

    class OutChunk(val data: ByteArray, val pts: Long, val flags: Int)
    class SimDecoded(val w: Int, val h: Int, val rgb: IntArray)

    fun configure(format: MediaFormat, surface: Any?, crypto: Any?, flags: Int) {
        if (SimEnv.failConfigure) throw IllegalArgumentException("simulated configure failure")
        val isVideo = mime == "video/avc"
        if (isVideo) {
            colorFormat = format.map[MediaFormat.KEY_COLOR_FORMAT] as? Int ?: 0
            if (!SimEnv.device.colorFormats.contains(colorFormat)) {
                throw IllegalArgumentException(
                    "sim: ${SimEnv.device.name} does not support color format 0x${Integer.toHexString(colorFormat)}"
                )
            }
            w = format.width
            h = format.height
        }
        configured = true
    }

    fun start() {
        if (!configured) throw IllegalStateException("not configured")
        started = true
        val isVideo = mime == "video/avc"
        val n = if (isVideo) 4 else 4
        for (i in 0 until n) inputFree.addLast(i)
        outputFormat = MediaFormat().apply {
            map["mime"] = mime
            if (isVideo) { map["width"] = w; map["height"] = h }
        }
    }

    fun stop() { started = false }
    fun release() { started = false }

    fun dequeueInputBuffer(timeoutUs: Long): Int {
        if (!started) throw IllegalStateException("not started")
        return if (inputFree.isEmpty()) -1 else inputFree.removeFirst()
    }

    private val givenBuffers = HashMap<Int, ByteBuffer>()

    fun getInputBuffer(index: Int): ByteBuffer? {
        val cap = if (mime == "video/avc") {
            if (SimEnv.device.inputCapacity > 0) SimEnv.device.inputCapacity else w * h * 3 / 2 + 4096
        } else 16384
        val b = ByteBuffer.allocate(cap)
        givenBuffers[index] = b
        return b
    }

    fun getInputImage(index: Int): Image? {
        if (mime != "video/avc") return null
        if (colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return null
        if (!SimEnv.device.imageStrides) return null
        // چیدمان رایجِ Android: Y کامل، U/V با pixelStride=2 و rowStride=w
        val yBuf = ByteBuffer.allocate(w * h)
        val uBuf = ByteBuffer.allocate(w * h / 2)
        val vBuf = ByteBuffer.allocate(w * h / 2)
        val im = Image(w, h,
            arrayOf(
                Image.Plane(yBuf, w, 1),
                Image.Plane(uBuf, w, 2),
                Image.Plane(vBuf, w, 2)
            ), this
        )
        grabbedImage = im
        return im
    }

    fun queueInputBuffer(index: Int, offset: Int, size: Int, ptsUs: Long, flags: Int) {
        if (!started) throw IllegalStateException("not started")
        // بافرِ خالی (size=0) در اندروید مجاز است
        if (mime == "video/avc") {
            if (size > w * h * 3 / 2) {
                throw IllegalArgumentException("sim: size $size > frame ${w * h * 3 / 2}")
            }
            if (queuedPts.isNotEmpty() && ptsUs <= queuedPts.last()) {
                throw IllegalArgumentException("sim: non-monotonic pts $ptsUs after ${queuedPts.last()}")
            }
            if (size > 0) {
                queuedPts.add(ptsUs)
                framesQueued++
                bytesQueued += size
            }
        } else {
            if (size > 0) { audioSamplesQueued += size / 2; audioInputQueues++; audioQueues++; SimEnv.statAudioQueues++ }
        }
        inputFree.addLast(index)
        if (size > 0) {
            pendingOut.addLast(OutChunk(ByteArray(120), ptsUs, 0))
        }
        if ((flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            sawEos = true
            pendingOut.addLast(OutChunk(ByteArray(0), ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM))
        }
        if (size > 0 && mime == "video/avc" && colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            val b = givenBuffers[index]
            if (b != null) decodeBuffer(ptsUs, b, size)
        }
        if (size > 0 && mime == "video/avc" && colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
            val im = grabbedImage
            if (im != null) { decodeInto(ptsUs, im); grabbedImage = null }
        }
    }

    /** تصویرِ Flexible که کد می‌شود را برای بازبینی رمزگشایی می‌کند */
    fun decodeInto(ptsUs: Long, img: Image) {
        val rgb = IntArray(w * h)
        val planes = img.planes
        val yb = planes[0]
        val ub = planes[1]
        val vb = planes[2]
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = yb.buffer.get(row * yb.rowStride + col * yb.pixelStride).toInt() and 0xFF
                val cy = (row / 2) * ub.rowStride + (col / 2) * ub.pixelStride
                val uu = (ub.buffer.get(cy).toInt() and 0xFF) - 128
                val vv = (vb.buffer.get(cy).toInt() and 0xFF) - 128
                rgb[row * w + col] = yuvToRgb(yy, uu, vv)
            }
        }
        decodedFrames[ptsUs] = SimDecoded(w, h, rgb)
        // فقط چند فریمِ اول نگه داشته می‌شود (بازبینی محتوا، بدون فشار حافظه)
        if (ptsUs <= 1_500_000L) SimEnv.decoded[ptsUs] = Triple(rgb, w, h)
    }

    /** بازسازیِ RGB از بافرِ NV12 یا I420 (برای بازبینی محتوا) */
    fun decodeBuffer(ptsUs: Long, b: ByteBuffer, size: Int) {
        val rgb = IntArray(w * h)
        val semi = colorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        for (row in 0 until h) {
            for (col in 0 until w) {
                val yy = b.get(row * w + col).toInt() and 0xFF
                val ci = (row / 2) * (w / 2) + (col / 2)
                val uu: Int
                val vv: Int
                if (semi) {
                    uu = (b.get(w * h + ci * 2).toInt() and 0xFF) - 128
                    vv = (b.get(w * h + ci * 2 + 1).toInt() and 0xFF) - 128
                } else {
                    uu = (b.get(w * h + ci).toInt() and 0xFF) - 128
                    vv = (b.get(w * h + w * h / 4 + ci).toInt() and 0xFF) - 128
                }
                rgb[row * w + col] = yuvToRgb(yy, uu, vv)
            }
        }
        // فقط چند فریمِ اول نگه داشته می‌شود (بازبینی محتوا، بدون فشار حافظه)
        if (ptsUs <= 1_500_000L) SimEnv.decoded[ptsUs] = Triple(rgb, w, h)
    }

    var audioDequeues = 0
    var audioQueues = 0

    fun dequeueOutputBuffer(info: MediaCodec.BufferInfo, timeoutUs: Long): Int {
        if (!started) throw IllegalStateException("not started")
        if (mime != "video/avc") { audioDequeues++; SimEnv.statAudioDequeues++ }
        if (!formatSent) {
            formatSent = true
            return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED
        }
        val c = pendingOut.removeFirstOrNull() ?: return MediaCodec.INFO_TRY_AGAIN_LATER
        info.offset = 0
        info.size = c.data.size
        info.presentationTimeUs = c.pts
        info.flags = c.flags
        lastOut = c
        return 0
    }

    private var lastOut: OutChunk? = null
    var grabbedImage: Image? = null

    fun getOutputBuffer(index: Int): ByteBuffer? = ByteBuffer.wrap(lastOut?.data ?: ByteArray(0))

    fun releaseOutputBuffer(index: Int, render: Boolean) { }

    companion object {
        const val CONFIGURE_FLAG_ENCODE = 1
        const val BUFFER_FLAG_CODEC_CONFIG = 2
        const val BUFFER_FLAG_END_OF_STREAM = 4
        const val INFO_TRY_AGAIN_LATER = -1
        const val INFO_OUTPUT_FORMAT_CHANGED = -2

        fun createEncoderByType(type: String): MediaCodec = MediaCodec(type)

        internal fun yuvToRgb(y: Int, u: Int, v: Int): Int {
            val yy = (y - 16).coerceAtLeast(0)
            val r = ((298 * yy + 409 * v + 128) shr 8).coerceIn(0, 255)
            val g = ((298 * yy - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
            val b = ((298 * yy + 516 * u + 128) shr 8).coerceIn(0, 255)
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}

class Image(
    val width: Int,
    val height: Int,
    val planes: Array<Plane>,
    private val owner: MediaCodec? = null
) {
    class Plane(val buffer: ByteBuffer, val rowStride: Int, val pixelStride: Int)
    fun close() { }
}

class MediaMuxer(path: String, format: Int) {
    class Sample(val track: Int, val pts: Long, val size: Int)

    val samples = ArrayList<Sample>()
    fun countFor(track: Int) = samples.count { it.track == track }
    private val tracks = ArrayList<MediaFormat>()
    private var started = false
    private val file = java.io.File(path)
    var stopped = false
        private set

    fun addTrack(format: MediaFormat): Int {
        if (started) throw IllegalStateException("addTrack after start")
        tracks.add(format)
        return tracks.size - 1
    }

    fun start() {
        if (tracks.isEmpty()) throw IllegalStateException("no tracks")
        started = true
    }

    fun writeSampleData(track: Int, buf: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started) throw IllegalStateException("write before start")
        if (track < 0 || track >= tracks.size) throw IllegalArgumentException("bad track $track")
        samples.add(Sample(track, info.presentationTimeUs, info.size))
        // پروندهٔ ساختگی: برای عبور از بررسی «خالی نبودن» و ثبت محتوا
        file.appendText("t$track pts=${info.presentationTimeUs} size=${info.size}\n")
    }

    fun stop() {
        if (!started) throw IllegalStateException("stop before start")
        stopped = true
        file.appendText("# tracks=${tracks.size} samples=${samples.size}\n")
    }

    fun release() { }

    object OutputFormat { const val MUXER_OUTPUT_MPEG_4 = 0 }
}
