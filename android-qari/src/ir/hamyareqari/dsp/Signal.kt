package ir.hamyareqari.dsp

/**
 * دسترسی یکنواخت به نمونه‌های صدا، بدون نیاز به نگه‌داشتن کل سیگنال به‌صورت
 * double. فایل ۴۶ دقیقه‌ای در حافظهٔ ۱۶ بیتی ≈ ۱۲۲ مگابایت است، ولی به‌صورت
 * double بیش از ۴۸۰ مگابایت می‌شد.
 */
interface Signal {
    val size: Int
    val sampleRate: Int
    /** len نمونه از نقطهٔ start را در dst (double، بازهٔ ±۱) می‌نویسد */
    fun read(start: Int, len: Int, dst: DoubleArray)
}

class DoubleSignal(private val data: DoubleArray, override val sampleRate: Int) : Signal {
    override val size: Int get() = data.size
    override fun read(start: Int, len: Int, dst: DoubleArray) {
        val n = if (start + len <= data.size) len else maxOf(0, data.size - start)
        for (i in 0 until n) dst[i] = data[start + i]
        for (i in n until len) dst[i] = 0.0
    }
}

/** سیگنال ۱۶ بیتی — همان چیزی که از رمزگشا و میکروفون می‌آید */
class ShortSignal(private val data: ShortArray, override val sampleRate: Int) : Signal {
    override val size: Int get() = data.size
    override fun read(start: Int, len: Int, dst: DoubleArray) {
        val n = if (start + len <= data.size) len else maxOf(0, data.size - start)
        for (i in 0 until n) dst[i] = data[start + i] / 32768.0
        for (i in n until len) dst[i] = 0.0
    }
}
