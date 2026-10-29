package ir.hamyareqari.app

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** جایگزین حداقلی Theme برای بازبینی بصری روی دسکتاپ */
object Theme {
    const val BG1 = 0xFF0B1224.toInt()
    const val BG2 = 0xFF121C33.toInt()
    const val BG3 = 0xFF0A1020.toInt()
    const val CARD = 0xFF111B31.toInt()
    const val LINE = 0x1AFFFFFF
    const val TXT = 0xFFE8ECF7.toInt()
    const val DIM = 0xFF93A0BD.toInt()
    const val GOLD = 0xFFF7D171.toInt()
    const val GOLD2 = 0xFFFFE6A6.toInt()
    const val TEAL = 0xFF23E0C2.toInt()
    const val VIOLET = 0xFF8B7CFF.toInt()
    const val RED = 0xFFFF7B7B.toInt()
    const val INK = 0xFF0C1322.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun font(ctx: Context, heavy: Boolean = false): Typeface {
        if (heavy) bold?.let { return it } else regular?.let { return it }
        val f = File(if (heavy) System.getProperty("font.bold", "../assets/fonts/Estedad-Bold.ttf") else System.getProperty("font.regular", "../assets/fonts/Estedad-Regular.ttf"))
        val t = if (f.exists()) Typeface.createFromFile(f) else Typeface.DEFAULT
        if (heavy) bold = t else regular = t
        return t
    }

    fun dp(ctx: Context, v: Float): Int = v.toInt()
    fun dpF(ctx: Context, v: Float): Float = v
}
