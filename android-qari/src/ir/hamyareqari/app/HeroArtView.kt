package ir.hamyareqari.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** Lightweight offline illustration: an Islamic arch, Qur'an and rehal drawn with Canvas. */
class HeroArtView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arch = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val cx = w * .5f
        val unit = minOf(w / 320f, h / 250f)
        canvas.save()
        canvas.translate(cx, h * .5f)
        canvas.scale(unit, unit)

        paint.shader = RadialGradient(0f, 12f, 155f,
            intArrayOf(0x44F7D171, 0x10253D69, 0x000B1224), null, Shader.TileMode.CLAMP)
        canvas.drawCircle(0f, 0f, 155f, paint)
        paint.shader = null

        // Layered, restrained mihrab outlines.
        for (i in 0..2) {
            val inset = i * 13f
            arch.reset()
            arch.moveTo(-118f + inset, 95f)
            arch.lineTo(-118f + inset, -12f)
            arch.cubicTo(-118f + inset, -60f, -63f + inset, -82f, -28f + inset, -113f)
            arch.cubicTo(-10f + inset, -129f, -7f + inset, -151f, 0f, -164f)
            arch.cubicTo(7f - inset, -151f, 10f - inset, -129f, 28f - inset, -113f)
            arch.cubicTo(63f - inset, -82f, 118f - inset, -60f, 118f - inset, -12f)
            arch.lineTo(118f - inset, 95f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (i == 0) 1.5f else 1f
            paint.color = if (i == 0) 0x557F6B43 else 0x287F6B43
            canvas.drawPath(arch, paint)
        }

        // Fine decorative floor lines.
        paint.color = 0x337F6B43
        paint.strokeWidth = 1f
        canvas.drawLine(-132f, 99f, 132f, 99f, paint)
        canvas.drawLine(-106f, 111f, 106f, 111f, paint)

        // Qur'an on a wooden rehal.
        val book = Path().apply {
            moveTo(-79f, 41f); lineTo(-6f, 18f); lineTo(75f, 42f)
            lineTo(3f, 67f); close()
        }
        paint.style = Paint.Style.FILL
        paint.color = 0xFF182945.toInt()
        canvas.drawPath(book, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = Theme.GOLD
        canvas.drawPath(book, paint)
        canvas.drawLine(-6f, 18f, 3f, 67f, paint)
        paint.color = 0xAAE8C879.toInt()
        paint.strokeWidth = 1f
        canvas.drawLine(-53f, 42f, -5f, 28f, paint)
        canvas.drawLine(22f, 32f, 54f, 42f, paint)
        canvas.drawCircle(0f, 43f, 8f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = 0xFF704F25.toInt()
        canvas.drawLine(-57f, 66f, 0f, 103f, paint)
        canvas.drawLine(59f, 66f, 0f, 103f, paint)
        canvas.drawLine(0f, 103f, 0f, 76f, paint)
        paint.strokeWidth = 3f
        canvas.drawLine(-59f, 107f, 60f, 107f, paint)

        // Small central finial / star.
        paint.style = Paint.Style.FILL
        paint.color = Theme.GOLD
        canvas.drawCircle(0f, -70f, 3f, paint)
        canvas.restore()
    }
}
