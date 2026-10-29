package ir.hamyareqari.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import ir.hamyareqari.dsp.PitchMath
import kotlin.math.max

/**
 * نوار «گسترهٔ صدا»: نقطه‌های هر نیم‌پرده در چند اکتاو،
 * نقطه‌های بین پایین‌ترین و بالاترین نت روشن می‌شوند.
 */
class RangeStripView(context: Context) : View(context) {

    var lowMidi: Int = -1
    var highMidi: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80FFFFFF.toInt()
        textSize = 22f
        textAlign = Paint.Align.CENTER
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (lowMidi < 0 || highMidi < 0) return
        val unit = Theme.dpF(context, 1f)
        val lowOct = lowMidi / 12
        val highOct = highMidi / 12
        val octaves = max(1, highOct - lowOct + 1)
        val w = width.toFloat()
        val h = height.toFloat()
        val padL = 6f * unit
        val padR = 6f * unit
        val usable = w - padL - padR
        val cellW = usable / octaves
        val dotGap = cellW / 12f
        val radius = max(2.2f * unit, dotGap * 0.30f)
        val centerY = h * 0.52f

        for (o in 0 until octaves) {
            val baseOct = lowOct + o
            for (k in 0 until 12) {
                val midi = baseOct * 12 + k
                val x = padL + o * cellW + (k + 0.5f) * dotGap
                val inRange = midi in lowMidi..highMidi
                dot.color = when {
                    midi == highMidi -> Theme.GOLD2
                    midi == lowMidi -> Theme.TEAL
                    inRange -> 0x99F7D171.toInt()
                    else -> 0x1FFFFFFF
                }
                canvas.drawCircle(x, centerY, if (inRange) radius * 1.15f else radius * 0.8f, dot)
            }
            // برچسب اکتاو
            label.textSize = 9.5f * unit
            label.color = 0x80FFFFFF.toInt()
            canvas.drawText(
                PitchMath.latinNameAscii(baseOct * 12),
                padL + o * cellW + cellW / 2,
                h - 2f * unit,
                label
            )
        }
    }
}

/**
 * عقربهٔ کوک: انحراف صدای خواننده از نتِ نزدیک، بین −۵۰ تا +۵۰ سنت.
 */
class CentsGaugeView(context: android.content.Context) : View(context) {

    var cents: Float = 0f

    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF.toInt() }
    private val center = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55F7D171 }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x12FFFFFF }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val unit = Theme.dpF(context, 1f)
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 8f * unit
        val left = pad
        val right = w - pad
        val midY = h * 0.42f
        val radius = h * 0.10f
        track.strokeWidth = 0f
        canvas.drawRoundRect(left, midY - radius, right, midY + radius, radius, radius, track)

        // خط‌های ۱۰ سنتی
        tick.strokeWidth = 1f * unit
        for (i in -5..5) {
            val x = left + (i + 5) / 10f * (right - left)
            val tall = i == 0
            center.strokeWidth = if (tall) 2f * unit else 1f * unit
            canvas.drawLine(
                x, midY + radius + 1f * unit,
                x, midY + radius + (if (tall) 7f else 4f) * unit,
                if (tall) center else tick
            )
        }

        // نشانگر
        val clamped = cents.coerceIn(-50f, 50f)
        val x = left + (clamped + 50f) / 100f * (right - left)
        val near = Math.abs(clamped) < 5f
        marker.color = if (near) Theme.TEAL else Theme.GOLD
        canvas.drawCircle(x, midY, radius * 2.0f, marker)
        marker.color = 0x33FFFFFF
        canvas.drawCircle(x, midY, radius * 3.0f, marker)
    }
}
