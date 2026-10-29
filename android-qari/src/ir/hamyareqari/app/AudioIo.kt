package ir.hamyareqari.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import ir.hamyareqari.dsp.Analyzer
import ir.hamyareqari.dsp.ShortSignal
import ir.hamyareqari.dsp.Wav
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.max
import kotlin.math.min

/** بافر ۱۶ بیتی رشدکنندهٔ سبک */
class ShortGrow(initial: Int = 1 shl 16, private val cap: Int = Int.MAX_VALUE) {
    private var data = ShortArray(max(1024, min(initial, cap)))
    var size: Int = 0
        private set

    fun add(src: ShortArray, count: Int) {
        if (count <= 0 || size >= cap) return
        val n = min(count, cap - size)
        if (size + n > data.size) {
            var newCap = data.size
            while (newCap < size + n) newCap = newCap * 2
            data = data.copyOf(min(newCap, cap))
        }
        System.arraycopy(src, 0, data, size, n)
        size += n
    }

    fun add(v: Short) {
        if (size >= cap) return
        if (size + 1 > data.size) data = data.copyOf(min(data.size * 2, cap))
        data[size++] = v
    }

    fun toArray(): ShortArray = data.copyOf(size)

    fun get(i: Int): Short = data[i]
}

/** تبدیل نرخ نمونهٔ جریانی (بدون نگه‌داشتن کل سیگنال) */
class StreamResampler(private val from: Int, private val to: Int) {
    private var pos = 0.0
    private var prev = 0.0
    private var hasPrev = false
    private val ratio = to.toDouble() / from.toDouble()
    private val outBuf = ShortArray(4096)

    /** src را می‌گیرد و خروجی را به sink می‌دهد */
    fun push(src: ShortArray, count: Int, sink: (ShortArray, Int) -> Unit) {
        if (from == to) {
            sink(src, count)
            return
        }
        var n = 0
        for (i in 0 until count) {
            val cur = src[i].toDouble()
            if (!hasPrev) {
                prev = cur
                hasPrev = true
                pos = 0.0
                continue
            }
            // پرش گام‌ها: میانگین‌گیری برای جلوگیری از آلیاسینگ
            while (pos < 1.0) {
                val v = prev + (cur - prev) * pos
                outBuf[n++] = v.toInt().coerceIn(-32768, 32767).toShort()
                pos += 1.0 / ratio
                if (n >= outBuf.size) {
                    sink(outBuf, n)
                    n = 0
                }
            }
            pos -= 1.0
            prev = cur
        }
        if (n > 0) sink(outBuf, n)
    }
}

object AudioIo {

    const val TARGET_RATE = 22050
    const val MAX_SECONDS = 40 * 60

    class Decoded(
        val pcm: ShortArray,
        val sampleRate: Int,
        val durationSec: Double,
        val sourceRate: Int,
        val channels: Int,
        val truncated: Boolean
    )

    /**
     * رمزگشایی هر فایل صوتی یا ویدیویی که اندروید می‌شناسد
     * (mp3, m4a/aac, wav, flac, ogg/opus, amr, mp4, mkv, webm …) با MediaCodec.
     * خروجی: مونو ۱۶ بیتی ۲۲٬۰۵۰ هرتز، آمادهٔ تحلیل.
     */
    @Throws(IOException::class)
    fun decode(ctx: Context, uri: Uri, progress: ((Double) -> Unit)? = null): Decoded {
        val extractor = MediaExtractor()
        extractor.setDataSource(ctx, uri, null)
        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            throw IOException("این فایل صدایی برای تحلیل ندارد")
        }
        extractor.selectTrack(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!
        var srcRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE))
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION))
            format.getLong(MediaFormat.KEY_DURATION) else 0L

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val capSamples = MAX_SECONDS * TARGET_RATE
        val sink = ShortGrow(1 shl 18, capSamples)
        var resampler = StreamResampler(srcRate, TARGET_RATE)
        val mono = ShortArray(8192)
        var pcmFloat = false

        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        var lastProgress = 0L

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)
                        val size = if (buf != null) extractor.readSampleData(buf, 0) else -1
                        if (size < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    if (info.size > 0) {
                        val obuf = codec.getOutputBuffer(outIdx)
                        if (obuf != null) {
                            obuf.position(info.offset)
                            obuf.limit(info.offset + info.size)
                            val frames: Int
                            if (pcmFloat) {
                                val fb = obuf.asFloatBuffer()
                                val n = fb.remaining()
                                val tmp = ShortArray(n)
                                for (k in 0 until n) {
                                    val v = (fb.get() * 32767f).toInt()
                                    tmp[k] = v.coerceIn(-32768, 32767).toShort()
                                }
                                frames = downmix(tmp, n, channels, mono)
                            } else {
                                val sb = obuf.asShortBuffer()
                                val n = sb.remaining()
                                val tmp = ShortArray(n)
                                sb.get(tmp, 0, n)
                                frames = downmix(tmp, n, channels, mono)
                            }
                            if (frames > 0) resampler.push(mono, frames) { arr, cnt -> sink.add(arr, cnt) }
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    val now = System.currentTimeMillis()
                    if (progress != null && durationUs > 0 && now - lastProgress > 120) {
                        lastProgress = now
                        progress((extractor.sampleTime.toDouble() / durationUs).coerceIn(0.0, 1.0))
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    val r = if (of.containsKey(MediaFormat.KEY_SAMPLE_RATE))
                        of.getInteger(MediaFormat.KEY_SAMPLE_RATE) else srcRate
                    val c = if (of.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                        of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channels
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        val enc = of.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        pcmFloat = enc == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    if (r != srcRate) {
                        srcRate = r
                        resampler = StreamResampler(srcRate, TARGET_RATE)
                    }
                    channels = c
                }
                if (sink.size >= capSamples) outputDone = true
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { }
            try { codec.release() } catch (e: Exception) { }
            extractor.release()
        }

        val pcm = sink.toArray()
        if (pcm.isEmpty()) throw IOException("صدایی از این فایل خوانده نشد")
        // آماده‌سازی: حذف DC، بالاگذر ۴۵Hz و نرمال‌سازی پیک
        Analyzer.prepareShort(pcm, TARGET_RATE)
        val dur = pcm.size.toDouble() / TARGET_RATE
        return Decoded(
            pcm = pcm,
            sampleRate = TARGET_RATE,
            durationSec = dur,
            sourceRate = srcRate,
            channels = channels,
            truncated = dur >= MAX_SECONDS - 0.5
        )
    }

    private fun downmix(src: ShortArray, n: Int, channels: Int, dst: ShortArray): Int {
        if (channels <= 1) {
            val cnt = min(n, dst.size)
            System.arraycopy(src, 0, dst, 0, cnt)
            return cnt
        }
        val frames = n / channels
        val cnt = min(frames, dst.size)
        for (i in 0 until cnt) {
            var sum = 0
            for (c in 0 until channels) sum += src[i * channels + c].toInt()
            dst[i] = (sum / channels).toShort()
        }
        return cnt
    }

    /** نمونهٔ آزمایشی داخل بستهٔ اپ (خروجی: همان قالب یکسان‌شده) */
    @Throws(IOException::class)
    fun decodeAsset(ctx: Context, name: String): Decoded {
        val bytes = try {
            ctx.assets.open(name).use { it.readBytes() }
        } catch (e: Exception) {
            throw IOException("نمونهٔ آزمایشی در بستهٔ اپ نیست")
        }
        val wav = Wav.decode(bytes) ?: throw IOException("نمونهٔ آزمایشی خوانده نشد")
        val pcm: ShortArray
        val rate: Int
        if (wav.sampleRate == TARGET_RATE) {
            pcm = ShortArray(wav.samples.size) { i ->
                (wav.samples[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            rate = TARGET_RATE
        } else {
            val res = Wav.resample(wav.samples, wav.sampleRate, TARGET_RATE)
            pcm = ShortArray(res.size) { i ->
                (res[i] * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            rate = TARGET_RATE
        }
        Analyzer.prepareShort(pcm, TARGET_RATE)
        return Decoded(
            pcm = pcm,
            sampleRate = rate,
            durationSec = pcm.size.toDouble() / TARGET_RATE,
            sourceRate = wav.sampleRate,
            channels = 1,
            truncated = false
        )
    }

    /** سیگنال آماده برای تحلیل (بدون کپی اضافه) */
    fun signal(decoded: Decoded): ShortSignal = ShortSignal(decoded.pcm, decoded.sampleRate)

    // ───────────────────────────── اجرای زنده ─────────────────────────────

    /**
     * ضبط زنده از میکروفون. اگر دستگاه ۲۲٬۰۵۰ هرتز را نپذیرد، ۴۴٬۱۰۰ می‌گیرد
     * و جریانی به ۲۲٬۰۵۰ تبدیل می‌شود.
     */
    class MicCapture(
        private val onBlock: (ShortArray, Int) -> Unit
    ) {
        var sampleRate = TARGET_RATE
            private set
        private var record: AudioRecord? = null
        private var thread: Thread? = null
        @Volatile private var running = false
        var lastError: String? = null
            private set

        fun start(): Boolean {
            val candidates = intArrayOf(TARGET_RATE, 44100, 48000)
            for (rate in candidates) {
                val minBuf = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val rec = try {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        rate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        max(minBuf * 2, rate / 2)
                    )
                } catch (e: Exception) {
                    null
                }
                if (rec == null) continue
                if (rec.state != AudioRecord.STATE_INITIALIZED) {
                    try { rec.release() } catch (e: Exception) { }
                    continue
                }
                record = rec
                sampleRate = rate
                break
            }
            val rec = record ?: run {
                lastError = "میکروفون در دسترس نیست"
                return false
            }
            running = true
            try {
                rec.startRecording()
            } catch (e: Exception) {
                lastError = "شروع ضبط ممکن نشد: ${e.message}"
                running = false
                try { rec.release() } catch (e2: Exception) { }
                record = null
                return false
            }
            val rs = StreamResampler(sampleRate, TARGET_RATE)
            val block = ShortArray(2048)
            val outBlock = ShortArray(4096)
            thread = Thread {
                val buf = ShortArray(1024)
                while (running) {
                    val read = try { rec.read(buf, 0, buf.size) } catch (e: Exception) { -1 }
                    if (read <= 0) {
                        try { Thread.sleep(25) } catch (e: InterruptedException) { }
                        continue
                    }
                    if (sampleRate == TARGET_RATE) {
                        onBlock(buf, read)
                    } else {
                        var n = 0
                        rs.push(buf, read) { arr, cnt ->
                            val take = min(cnt, outBlock.size - n)
                            System.arraycopy(arr, 0, outBlock, n, take)
                            n += take
                        }
                        if (n > 0) onBlock(outBlock, n)
                    }
                }
                try { rec.stop() } catch (e: Exception) { }
                try { rec.release() } catch (e: Exception) { }
            }.also { it.isDaemon = true; it.start() }
            return true
        }

        fun stop() {
            running = false
            thread?.let { t ->
                try { t.join(600) } catch (e: Exception) { }
            }
            thread = null
            record = null
        }
    }

    // ───────────────────────────── پخش ─────────────────────────────

    /** پخش فایل تحلیل‌شده؛ فایل مؤقت WAV ساخته می‌شود تا پخش و جابه‌جایی ساده باشد */
    class Player(private val ctx: Context) {
        private var mp: MediaPlayer? = null
        private var tempFile: File? = null

        fun load(pcm: ShortArray, sampleRate: Int): Boolean {
            release()
            return try {
                val f = File(ctx.cacheDir, "preview.wav")
                val bytes = Wav.encode16Pcm(pcm, sampleRate)
                FileOutputStream(f).use { it.write(bytes) }
                tempFile = f
                val player = MediaPlayer()
                player.setAudioStreamType(AudioManager.STREAM_MUSIC)
                player.setDataSource(f.absolutePath)
                player.prepare()
                mp = player
                true
            } catch (e: Exception) {
                mp = null
                false
            }
        }

        val isPlaying: Boolean get() = try { mp?.isPlaying == true } catch (e: Exception) { false }

        fun playFrom(sec: Double) {
            val player = mp ?: return
            try {
                player.seekTo((sec * 1000).toInt())
                player.start()
            } catch (e: Exception) { }
        }

        fun pause() {
            try { mp?.pause() } catch (e: Exception) { }
        }

        /** سرعت پخش (۰٫۵× تا ۲×) — بدون تغییر زیر و بمی */
        fun setSpeed(speed: Float) {
            val player = mp ?: return
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val params = player.playbackParams
                    params.speed = speed.coerceIn(0.5f, 2f)
                    params.pitch = 1f
                    player.playbackParams = params
                }
            } catch (e: Exception) { }
        }

        fun seekTo(sec: Double) {
            try { mp?.seekTo((sec.coerceAtLeast(0.0) * 1000).toInt()) } catch (e: Exception) { }
        }

        fun positionSec(): Double {
            val player = mp ?: return 0.0
            return try { player.currentPosition / 1000.0 } catch (e: Exception) { 0.0 }
        }

        fun durationSec(): Double {
            val player = mp ?: return 0.0
            return try { player.duration / 1000.0 } catch (e: Exception) { 0.0 }
        }

        fun onComplete(cb: () -> Unit) {
            try { mp?.setOnCompletionListener { cb() } } catch (e: Exception) { }
        }

        fun release() {
            try { mp?.stop() } catch (e: Exception) { }
            try { mp?.release() } catch (e: Exception) { }
            mp = null
            tempFile?.delete()
            tempFile = null
        }
    }
}
