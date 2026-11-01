import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import ir.hamyareqari.app.ChartView
import kotlin.math.abs

object ChartContourTest {
    private fun chart(t: List<Double>, m: List<Double>): ChartView = ChartView(Context()).apply {
        setLiveData(t, m)
        val canvas = Canvas(Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888))
        drawTrace(canvas, 800f, 400f, 1.0)
    }
    private fun gold(c: ChartView): List<FloatArray> {
        val f = ChartView::class.java.getDeclaredField("frameGold")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return f.get(c) as List<FloatArray>
    }
    @JvmStatic fun main(args: Array<String>) {
        val malformed = chart(listOf(0.0, .1, .1, .2, .3, .29, .4, .5, Double.NaN, .6, .7, .8),
            listOf(60.0, 60.0, 84.0, 62.0, 62.0, 80.0, 63.0, 63.0, 80.0, 64.0, 64.0, 64.0))
        val sp = malformed.spec!!
        check(sp.times.all { it.isFinite() } && sp.times.toList().zipWithNext().all { it.second > it.first })
        check(sp.mids.any { it.isNaN() })
        for (path in gold(malformed)) for (i in 2 until path.size step 2) {
            check(path[i] > path[i - 2]) { "non-monotonic screen coordinates" }
        }
        val spike = chart((0..10).map { it * .02 }, (0..10).map { if (it == 5) 84.0 else 60.0 })
        val paths = gold(spike)
        check(paths.size >= 2) { "isolated spike should create a discontinuity" }
        check(paths.all { p -> (1 until p.size step 2).map { p[it] }.let { it.maxOrNull()!! - it.minOrNull()!! < 1f } })
        val ornament = chart((0..10).map { it * .02 }, listOf(60.0,60.0,60.0,61.0,63.0,64.0,63.0,61.0,60.0,60.0,60.0))
        check(gold(ornament).any { p -> (1 until p.size step 2).map { p[it] }.let { it.maxOrNull()!! - it.minOrNull()!! > 30f } })
        // Whole-file zoom like the reference: 29.6 seconds across 1080px.
        // Two unsupported octave jumps 80ms apart must not form gold strokes,
        // while both sustained pitches remain present in separate runs.
        val whole = ChartView(Context()).apply {
            setLiveData(listOf(0.0, 10.0, 10.08, 10.16, 10.24, 10.32, 29.6),
                listOf(60.0, 60.0, 72.0, 72.0, 60.0, 60.0, 60.0))
            drawTrace(Canvas(Bitmap.createBitmap(1080, 604, Bitmap.Config.ARGB_8888)), 1080f, 604f, 29.6)
        }
        val wholePaths = gold(whole)
        check(wholePaths.size >= 2) { "large unsupported jumps must not be joined" }
        check(wholePaths.all { p -> (1 until p.size step 2).map { p[it] }.let { it.maxOrNull()!! - it.minOrNull()!! < 1f } })
        val interpolation = chart(listOf(0.0, 1.0), listOf(60.0, 62.0))
        // Use a short interval to avoid intentional gap splitting.
        interpolation.setLiveData(listOf(0.0, .2), listOf(60.0, 62.0))
        interpolation.drawTrace(Canvas(Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888)), 800f, 400f, .05)
        val last = gold(interpolation).first()
        val expectedX = 27f + (800f - 27f - 17f) * .25f
        check(abs(last[last.size - 2] - expectedX) < 1f) { "gold ends at playback time" }
        println("Chart contour regressions: OK")
    }
}
