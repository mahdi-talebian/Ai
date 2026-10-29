package ir.hamyareqari.dsp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min

/**
 * خواندن/نوشتن WAV بدون کتابخانهٔ بیرونی.
 * نوشتن برای «اجرای زنده» لازم است (ضبط در حافظه → فایل).
 */
object Wav {

    /** WAV مونو ۱۶ بیت */
    fun encode16(samples: DoubleArray, sampleRate: Int): ByteArray {
        val n = samples.size
        val dataLen = n * 2
        val out = ByteArrayOutputStream(44 + dataLen)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        out.write('R'.code); out.write('I'.code); out.write('F'.code); out.write('F'.code)
        le32(36 + dataLen)
        out.write('W'.code); out.write('A'.code); out.write('V'.code); out.write('E'.code)
        out.write('f'.code); out.write('m'.code); out.write('t'.code); out.write(' '.code)
        le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write('d'.code); out.write('a'.code); out.write('t'.code); out.write('a'.code)
        le32(dataLen)
        for (v in samples) {
            var s = (v * 32767.0).toInt()
            if (s > 32767) s = 32767
            if (s < -32768) s = -32768
            le16(s and 0xFFFF)
        }
        return out.toByteArray()
    }

    fun encode16Pcm(samples: ShortArray, sampleRate: Int): ByteArray {
        val n = samples.size
        val out = ByteArrayOutputStream(44 + n * 2)
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        out.write('R'.code); out.write('I'.code); out.write('F'.code); out.write('F'.code)
        le32(36 + n * 2)
        out.write('W'.code); out.write('A'.code); out.write('V'.code); out.write('E'.code)
        out.write('f'.code); out.write('m'.code); out.write('t'.code); out.write(' '.code)
        le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
        out.write('d'.code); out.write('a'.code); out.write('t'.code); out.write('a'.code)
        le32(n * 2)
        for (s in samples) {
            out.write(s.toInt() and 0xFF)
            out.write((s.toInt() shr 8) and 0xFF)
        }
        return out.toByteArray()
    }

    class Decoded(val samples: DoubleArray, val sampleRate: Int)

    /** خواندن WAV ۸/۱۶/۲۴/۳۲ بیت، PCM یا float، مونو یا چندکاناله */
    fun decode(bytes: ByteArray): Decoded? {
        if (bytes.size < 44) return null
        if (bytes[0] != 'R'.code.toByte() || bytes[8] != 'W'.code.toByte()) return null
        var pos = 12
        var fmt = -1
        var channels = 1
        var sampleRate = 44100
        var bits = 16
        var dataOff = -1
        var dataLen = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = le32(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    fmt = le16(bytes, body)
                    channels = max(1, le16(bytes, body + 2))
                    sampleRate = max(8000, le32(bytes, body + 4))
                    bits = le16(bytes, body + 14)
                }
                "data" -> {
                    dataOff = body
                    dataLen = min(size, bytes.size - body)
                }
            }
            if (size <= 0) break
            pos = body + size + (size and 1)
        }
        if (dataOff < 0 || dataLen <= 0) return null
        val bytesPerSample = max(1, bits / 8)
        val frames = dataLen / (bytesPerSample * channels)
        if (frames <= 0) return null
        val out = DoubleArray(frames)
        for (i in 0 until frames) {
            var acc = 0.0
            for (c in 0 until channels) {
                val off = dataOff + (i * channels + c) * bytesPerSample
                acc += when {
                    fmt == 3 && bits == 32 -> Float.fromBits(le32(bytes, off)).toDouble()
                    bits == 16 -> le16(bytes, off).toShort().toDouble() / 32768.0
                    bits == 24 -> {
                        val b0 = bytes[off].toInt() and 0xFF
                        val b1 = bytes[off + 1].toInt() and 0xFF
                        val b2 = bytes[off + 2].toInt()
                        ((b2 shl 16) or (b1 shl 8) or b0) / 8388608.0
                    }
                    bits == 32 -> le32(bytes, off) / 2147483648.0
                    bits == 8 -> ((bytes[off].toInt() and 0xFF) - 128) / 128.0
                    else -> 0.0
                }
            }
            out[i] = acc / channels
        }
        return Decoded(out, sampleRate)
    }

    fun decodeStream(input: InputStream): Decoded? = decode(input.readBytes())

    fun decodeFile(f: File): Decoded? = decode(f.readBytes())

    private fun le16(b: ByteArray, i: Int): Int = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    /**
     * تبدیل نرخ نمونه با فیلتر پایین‌گذر سبک + درون‌یابی خطی.
     * برای یکسان‌سازی ورودی‌ها به ۲۲٬۰۵۰ هرتز استفاده می‌شود.
     */
    fun resample(x: DoubleArray, from: Int, to: Int): DoubleArray {
        if (from == to) return x
        var src = x
        if (to < from) {
            // میانگین‌گیری ساده به‌جای فیلتر سنگین؛ نویز بالاگذر حذف می‌شود
            val ratio = from.toDouble() / to
            if (ratio >= 2.0) {
                val outN = (x.size / ratio).toInt()
                val out = DoubleArray(outN)
                var acc = 0.0
                var c = 0
                var j = 0
                for (i in x.indices) {
                    acc += x[i]; c++
                    if (i + 1 >= ((j + 1) * ratio).toInt()) {
                        if (j < outN) out[j] = acc / c
                        j++; acc = 0.0; c = 0
                    }
                }
                src = out
                return resampleLinear(src, from / ratio, to)
            }
        }
        return resampleLinear(src, from.toDouble(), to)
    }

    private fun resampleLinear(x: DoubleArray, fromRate: Double, toRate: Int): DoubleArray {
        val ratio = toRate / fromRate
        val n = (x.size * ratio).toInt()
        val out = DoubleArray(n)
        for (i in 0 until n) {
            val pos = i / ratio
            val i0 = pos.toInt()
            val frac = pos - i0
            val a = if (i0 < x.size) x[i0] else 0.0
            val b = if (i0 + 1 < x.size) x[i0 + 1] else 0.0
            out[i] = a * (1.0 - frac) + b * frac
        }
        return out
    }
}
