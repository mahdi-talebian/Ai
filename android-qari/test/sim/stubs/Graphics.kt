package android.graphics

import java.awt.BasicStroke
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.min

object Color {
    fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    fun alpha(c: Int) = (c ushr 24) and 0xFF
    fun red(c: Int) = (c shr 16) and 0xFF
    fun green(c: Int) = (c shr 8) and 0xFF
    fun blue(c: Int) = c and 0xFF
}

class Typeface private constructor(val awt: Font?) {
    companion object {
        val DEFAULT = Typeface(null)
        val DEFAULT_BOLD = Typeface(null)
        fun createFromFile(f: File): Typeface =
            Typeface(Font.createFont(Font.TRUETYPE_FONT, f))
    }
}

open class Shader {
    enum class TileMode { CLAMP, REPEAT, MIRROR }
    var paint: java.awt.Paint? = null
}

class LinearGradient : Shader {
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, colors: IntArray, stops: FloatArray?, mode: Shader.TileMode) {
        val fr = FloatArray(colors.size) { i -> stops?.getOrNull(i) ?: (if (colors.size == 1) 0f else i.toFloat() / (colors.size - 1)) }
        val cs = Array(colors.size) { java.awt.Color(colors[it], true) }
        paint = java.awt.LinearGradientPaint(x0, y0, x1, y1, fr, cs)
    }
    constructor(x0: Float, y0: Float, x1: Float, y1: Float, c0: Int, c1: Int, mode: Shader.TileMode) {
        paint = java.awt.LinearGradientPaint(
            x0, y0, x1, y1, floatArrayOf(0f, 1f),
            arrayOf(java.awt.Color(c0, true), java.awt.Color(c1, true))
        )
    }
}

class RadialGradient(cx: Float, cy: Float, r: Float, colors: IntArray, stops: FloatArray?, mode: Shader.TileMode) : Shader() {
    init {
        val fr = FloatArray(colors.size) { i ->
            (stops?.getOrNull(i) ?: (if (colors.size == 1) 0f else i.toFloat() / (colors.size - 1))).coerceIn(0f, 1f)
        }
        val cs = Array(colors.size) { java.awt.Color(colors[it], true) }
        paint = java.awt.RadialGradientPaint(
            java.awt.geom.Point2D.Float(cx, cy), r.coerceAtLeast(0.01f), fr, cs
        )
    }
}

class DashPathEffect(val intervals: FloatArray, val phase: Float = 0f)

class Paint(flags: Int = 0) {
    enum class Style { FILL, STROKE, FILL_AND_STROKE }
    enum class Cap { BUTT, ROUND, SQUARE }
    enum class Join { MITER, ROUND, BEVEL }
    enum class Align { LEFT, CENTER, RIGHT }
    companion object { const val ANTI_ALIAS_FLAG = 1 }

    var color: Int = 0xFF000000.toInt()
    var style: Style = Style.FILL
    var strokeWidth: Float = 1f
    var strokeCap: Cap = Cap.BUTT
    var strokeJoin: Join = Join.MITER
    var textSize: Float = 12f
    var textAlign: Align = Align.LEFT
    var typeface: Typeface? = null
    var shader: Shader? = null
    var pathEffect: DashPathEffect? = null

    fun awtColor(): java.awt.Color = java.awt.Color(color, true)

    fun awtPaint(bounds: java.awt.Rectangle?): java.awt.Paint {
        val sh = shader
        if (sh?.paint != null) return sh.paint!!
        return awtColor()
    }

    fun awtStroke(): BasicStroke = BasicStroke(
        strokeWidth.coerceAtLeast(0.1f),
        when (strokeCap) { Cap.ROUND -> BasicStroke.CAP_ROUND; Cap.SQUARE -> BasicStroke.CAP_SQUARE; else -> BasicStroke.CAP_BUTT },
        when (strokeJoin) { Join.ROUND -> BasicStroke.JOIN_ROUND; Join.BEVEL -> BasicStroke.JOIN_BEVEL; else -> BasicStroke.JOIN_MITER }
    )

    fun awtFont(): Font {
        val base = typeface?.awt ?: Font("SansSerif", Font.PLAIN, 12)
        return base.deriveFont(textSize)
    }

    fun measureText(s: String): Float {
        val img = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.font = awtFont()
        val w = g.fontMetrics.stringWidth(s).toFloat()
        g.dispose()
        return w
    }
}

class Path {
    val p = Path2D.Float()
    private var has = false
    fun reset() { p.reset(); has = false }
    fun moveTo(x: Float, y: Float) { p.moveTo(x, y); has = true }
    fun lineTo(x: Float, y: Float) { if (!has) p.moveTo(x, y) else p.lineTo(x, y); has = true }
    fun quadTo(x1: Float, y1: Float, x2: Float, y2: Float) { if (!has) p.moveTo(x1, y1); p.quadTo(x1, y1, x2, y2); has = true }
    fun close() { p.closePath() }
}

class RectF(var left: Float = 0f, var top: Float = 0f, var right: Float = 0f, var bottom: Float = 0f) {
    fun set(l: Float, t: Float, r: Float, b: Float) { left = l; top = t; right = r; bottom = b }
}

class Bitmap(val image: BufferedImage) {
    enum class Config { ARGB_8888 }
    val width: Int get() = image.width
    val height: Int get() = image.height
    companion object {
        fun createBitmap(w: Int, h: Int, config: Config): Bitmap =
            Bitmap(BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB))
    }
    fun recycle() { }
    fun getPixels(pixels: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        var o = offset
        for (row in y until y + h) {
            for (col in x until x + w) {
                pixels[o++] = image.getRGB(col, row)
            }
            o += stride - w
        }
    }
    fun getPixel(x: Int, y: Int): Int = image.getRGB(x, y)
}

class Canvas(val image: BufferedImage) {
    constructor(bmp: Bitmap) : this(bmp.image)

    val g: Graphics2D = image.createGraphics().apply {
        setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    private var paintRef: Paint? = null

    fun drawRect(l: Float, t: Float, r: Float, b: Float, p: Paint) {
        g.paint = p.awtPaint(null)
        g.fill(java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t))
    }

    fun drawLine(x0: Float, y0: Float, x1: Float, y1: Float, p: Paint) {
        g.paint = p.awtPaint(null)
        g.stroke = p.awtStroke()
        g.draw(Line2D.Float(x0, y0, x1, y1))
    }

    fun drawPath(path: Path, p: Paint) {
        g.paint = p.awtPaint(null)
        if (p.style == Paint.Style.FILL) g.fill(path.p) else { g.stroke = p.awtStroke(); g.draw(path.p) }
    }

    fun drawCircle(cx: Float, cy: Float, r: Float, p: Paint) {
        g.paint = p.awtPaint(null)
        val shape: Shape = Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2)
        if (p.style == Paint.Style.FILL) g.fill(shape) else { g.stroke = p.awtStroke(); g.draw(shape) }
    }

    fun drawOval(rect: RectF, p: Paint) {
        g.paint = p.awtPaint(null)
        val shape: Shape = Ellipse2D.Float(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top)
        if (p.style == Paint.Style.FILL) g.fill(shape) else { g.stroke = p.awtStroke(); g.draw(shape) }
    }

    fun drawRoundRect(rect: RectF, rx: Float, ry: Float, p: Paint) {
        g.paint = p.awtPaint(null)
        val shape: Shape = RoundRectangle2D.Float(rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top, rx * 2, ry * 2)
        if (p.style == Paint.Style.FILL) g.fill(shape) else { g.stroke = p.awtStroke(); g.draw(shape) }
    }

    fun drawText(text: String, x: Float, y: Float, p: Paint) {
        g.paint = p.awtPaint(null)
        g.font = p.awtFont()
        val fm = g.fontMetrics
        val xx = when (p.textAlign) {
            Paint.Align.LEFT -> x
            Paint.Align.CENTER -> x - fm.stringWidth(text) / 2f
            Paint.Align.RIGHT -> x - fm.stringWidth(text)
        }
        g.drawString(text, xx, y)
    }

    fun save(): Int { return 1 }
    fun restore() { }
    fun restoreToCount(c: Int) { }
    fun translate(x: Float, y: Float) { g.translate(x.toDouble(), y.toDouble()) }
    fun scale(sx: Float, sy: Float) { g.scale(sx.toDouble(), sy.toDouble()) }
}
