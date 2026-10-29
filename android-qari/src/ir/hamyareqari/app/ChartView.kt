package ir.hamyareqari.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import ir.hamyareqari.dsp.PitchMath
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * نمودار «ردِّ صدا»: خطوط نت + منحنی خوانده‌شده + نقطهٔ نورانی متحرک.
 * لمس روی نمودار = پخش از همان لحظه (onSeek).
 */
class ChartView(context: Context) : View(context) {

    var onSeek: ((Double) -> Unit)? = null

    private var t = DoubleArray(0)
    private var hz = DoubleArray(0)
    private var totalSec = 1.0
    private var playheadSec = -1.0
    private var live = false

    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x16FFFFFF }
    private val paintGridStrong = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
    private val paintLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintPlay = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x88F7D171.toInt() }
    private val paintLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x8CFFFFFF.toInt() }
    private val path = Path()
    private val rect = RectF()

    private var minMidi = 48.0
    private var maxMidi = 72.0

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setData(timeSec: DoubleArray, freqHz: DoubleArray, durationSec: Double) {
        t = timeSec
        hz = freqHz
        totalSec = max(0.5, durationSec)
        if (!live) {
            computeRange()
        } else {
            // در حالت زنده پنجره فقط گسترده می‌شود تا نمودار نلرزد
            var lo = Double.MAX_VALUE
            var hi = -Double.MAX_VALUE
            for (v in hz) if (v > 0.0) { if (v < lo) lo = v; if (v > hi) hi = v }
            if (lo <= hi) {
                val a = PitchMath.hzToMidi(lo) - 3.0
                val b = PitchMath.hzToMidi(hi) + 3.0
                if (a < minMidi) minMidi = floor(a)
                if (b > maxMidi) maxMidi = ceil(b)
            }
        }
        invalidate()
    }

    fun setLive(isLive: Boolean) {
        live = isLive
        if (!live) computeRange()
        invalidate()
    }

    fun setPlayhead(sec: Double) {
        playheadSec = sec
        invalidate()
    }

    private fun computeRange() {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (v in hz) {
            if (v > 0.0) {
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
        }
        if (lo > hi) {
            minMidi = 48.0
            maxMidi = 72.0
            return
        }
        var a = PitchMath.hzToMidi(lo) - 3.0
        var b = PitchMath.hzToMidi(hi) + 3.0
        if (b - a < 9.0) {
            val mid = (a + b) / 2
            a = mid - 4.5
            b = mid + 4.5
        }
        minMidi = floor(a)
        maxMidi = ceil(b)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawChart(canvas, width.toFloat(), height.toFloat(), Theme.dpF(context, 1f), 34f, 8f, 10f, 18f)
    }

    /**
     * ترسیم مستقل از اندازه — برای نمایش روی صفحه و برای خروجی PNG.
     * unit = اندازهٔ یک dp بر حسب پیکسل بوم.
     */
    fun drawChart(
        canvas: Canvas,
        w: Float,
        h: Float,
        unit: Float,
        padLDp: Float,
        padRDp: Float,
        padTDp: Float,
        padBDp: Float
    ) {
        val left = padLDp * unit
        val right = w - padRDp * unit
        val top = padTDp * unit
        val bottom = h - padBDp * unit
        if (right <= left + 4 || bottom <= top + 4) return

        paintGrid.strokeWidth = 1f * unit
        paintGridStrong.strokeWidth = 1.2f * unit
        paintLine.strokeWidth = 2.6f * unit
        paintPlay.strokeWidth = 1.8f * unit
        paintLabel.textSize = 9.5f * unit

        var midi = ceil(minMidi).toInt()
        while (midi <= floor(maxMidi).toInt()) {
            val y = yForMidi(midi.toDouble(), top, bottom)
            val isC = ((midi % 12) + 12) % 12 == 0
            canvas.drawLine(left, y, right, y, if (isC) paintGridStrong else paintGrid)
            if (isC) {
                canvas.drawText(
                    PitchMath.latinNameAscii(midi),
                    3f * unit,
                    y + 3.2f * unit,
                    paintLabel
                )
            }
            midi++
        }

        if (t.isEmpty()) {
            paintLabel.color = 0x66FFFFFF
            paintLabel.textSize = 12f * unit
            canvas.drawText(
                "هنوز داده‌ای نیست",
                left + 60f * unit,
                (top + bottom) / 2,
                paintLabel
            )
            return
        }

        val span = max(0.5, totalSec)
        val startSec = if (live) max(0.0, t[t.size - 1] - span) else 0.0
        val xScale = (right - left) / span

        path.reset()
        var pen = false
        paintLine.shader = LinearGradient(
            left, top, right, bottom,
            intArrayOf(Theme.GOLD, Theme.TEAL), null, Shader.TileMode.CLAMP
        )
        var lastSec = -1.0
        var lastHz = 0.0
        for (i in t.indices) {
            val sec = t[i]
            if (sec < startSec) continue
            val v = hz[i]
            if (v <= 0.0) {
                pen = false
                continue
            }
            val x = left + ((sec - startSec) * xScale).toFloat()
            val y = yForMidi(PitchMath.hzToMidi(v), top, bottom)
            if (!pen) {
                path.moveTo(x, y)
                pen = true
            } else {
                path.lineTo(x, y)
            }
            lastSec = sec
            lastHz = v
        }
        canvas.drawPath(path, paintLine)

        if (lastSec >= 0 && lastHz > 0) {
            val x = left + ((lastSec - startSec) * xScale).toFloat()
            val y = yForMidi(PitchMath.hzToMidi(lastHz), top, bottom)
            paintDot.color = 0x33F7D171
            canvas.drawCircle(x, y, 8f * unit, paintDot)
            paintDot.color = Theme.GOLD2
            canvas.drawCircle(x, y, 3.4f * unit, paintDot)
        }

        if (playheadSec >= 0 && !live) {
            val x = left + ((playheadSec - startSec) * xScale).toFloat()
            canvas.drawLine(x, top, x, bottom, paintPlay)
        }

        rect.set(left, top, right, bottom)
        paintGrid.color = 0x1FFFFFFF
        paintGrid.strokeWidth = 1f * unit
        canvas.drawRoundRect(rect, 10f * unit, 10f * unit, paintGrid)
        paintGrid.color = 0x16FFFFFF
    }

    private fun yForMidi(midi: Double, top: Float, bottom: Float): Float {
        val frac = (midi - minMidi) / max(0.5, maxMidi - minMidi)
        return (bottom - frac * (bottom - top)).toFloat()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (live) return false
        if (event.action == MotionEvent.ACTION_UP) {
            val left = Theme.dpF(context, 34f)
            val right = width - Theme.dpF(context, 8f)
            if (event.x in left..right) {
                val frac = (((event.x - left) / (right - left)).coerceIn(0f, 1f)).toDouble()
                onSeek?.invoke(frac * totalSec)
                performClick()
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    /** خروجی تصویر: کارت تحلیل با نمودار و اعداد */
    fun renderToBitmap(
        w: Int,
        h: Int,
        title: String,
        lines: List<String>
    ): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = LinearGradient(
            0f, 0f, 0f, h.toFloat(),
            intArrayOf(Theme.BG1, Theme.BG2, Theme.BG3), null, Shader.TileMode.CLAMP
        )
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = bg
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)

        val unit = w / 460f  // عرض منطقی = ۴۶۰dp
        val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.GOLD
            textSize = 19f * unit
            typeface = Theme.font(context, true)
            textAlign = Paint.Align.RIGHT
        }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Theme.DIM
            textSize = 11f * unit
            typeface = Theme.font(context, false)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText(title, w - 14f * unit, 26f * unit, gold)
        var y = 44f * unit
        for (line in lines) {
            c.drawText(line, w - 14f * unit, y, dim)
            y += 15f * unit
        }

        val chartTop = 52f * unit + lines.size * 15f * unit
        val chartH = h - chartTop - 26f * unit
        val save = c.save()
        c.translate(0f, chartTop)
        drawChart(c, w.toFloat(), chartH, unit, 34f, 8f, 8f, 14f)
        c.restoreToCount(save)

        val foot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0x99FFFFFF.toInt()
            textSize = 10f * unit
            typeface = Theme.font(context, false)
            textAlign = Paint.Align.RIGHT
        }
        c.drawText("ساخته‌شده با «همیار قاری» — کاملاً روی همین گوشی", w - 14f * unit, h - 10f * unit, foot)
        return bmp
    }
}
