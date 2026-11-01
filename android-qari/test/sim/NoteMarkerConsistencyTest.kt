import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import ir.hamyareqari.app.*
import ir.hamyareqari.dsp.*
import java.io.File
import kotlin.math.*

object NoteMarkerConsistencyTest {
    private fun spec(t: DoubleArray, m: DoubleArray, notes: List<TraceNote>, status: ByteArray? = null) =
        TraceSpec(t, m, notes, t.last(), Double.NaN, Double.NaN, 0.0, status)
    private fun valid(s: TraceSpec) = DisplayNoteValidator.validate(s, s.mids)
    private fun makeChart(s: TraceSpec): ChartView = ChartView(Context()).also {
        val f = ChartView::class.java.getDeclaredField("spec"); f.isAccessible = true; f.set(it, s)
    }
    private fun pixels(c: ChartView, video: Boolean = false): IntArray {
        val b = Bitmap.createBitmap(600, 300, Bitmap.Config.ARGB_8888)
        if (video) c.drawFrameForVideo(Canvas(b), 600f, 300f, 1f, 1.5)
        else c.drawTrace(Canvas(b), 600f, 300f, 1.5)
        return IntArray(600 * 300).also { b.getPixels(it, 0, 600, 0, 0, 600, 300) }
    }
    @JvmStatic fun main(args: Array<String>) {
        val times = DoubleArray(201) { it * .01 }
        val base = DoubleArray(201) { 69.0 }
        val bad = listOf(TraceNote(.2,.2,90.0), TraceNote(.6,.2,81.0), TraceNote(1.0,.2,45.0))
        val a = valid(spec(times, base, bad))
        check(a.notes.isEmpty() && a.diagnostics.all { it.decision == DisplayNoteValidator.Decision.UNSUPPORTED })
        check(pixels(makeChart(spec(times, base, bad))).contentEquals(pixels(makeChart(spec(times, base, emptyList()))))) { "bad notes changed raster or scaling" }
        val short = base.copyOf(); for(i in 80..90) short[i] = 77.0
        val b = valid(spec(times, short, listOf(TraceNote(.80,.1,77.0), TraceNote(.2,.15,81.0))))
        check(b.notes.size == 1 && abs(b.notes[0].midi - 77) < .01 && b.notes[0].duration < .34)
        check(b.diagnostics[1].decision == DisplayNoteValidator.Decision.UNSUPPORTED)
        val trill = DoubleArray(201) { 69 + 2.0 * sin(2 * PI * 7 * times[it]) }
        val tr = valid(spec(times, trill, listOf(TraceNote(.3,.15,69.0), TraceNote(.3,.15,87.0))))
        check(tr.diagnostics[1].decision == DisplayNoteValidator.Decision.UNSUPPORTED)
        check(trill.maxOrNull()!! - trill.minOrNull()!! > 3.9)
        for (rate in listOf(4.0,6.0,8.0)) {
            val vib = DoubleArray(201) { 69 + .7 * sin(2 * PI * rate * times[it]) }
            check(valid(spec(times,vib,listOf(TraceNote(.2,1.0,69.0), TraceNote(.2,.2,90.0)))).notes.isNotEmpty())
            check(vib.maxOrNull()!! - vib.minOrNull()!! > 1.3)
        }
        val gap = base.copyOf(); for(i in 70..100) gap[i] = Double.NaN
        val gaps = valid(spec(times,gap,listOf(TraceNote(.6,.5,69.0), TraceNote(.75,.1,69.0))))
        check(gaps.notes.size == 2 && gaps.notes.none { it.start < .7 && it.start + it.duration > 1.0 })
        check(gaps.diagnostics[1].decision == DisplayNoteValidator.Decision.INDETERMINATE)
        val invalid = ByteArray(201) { PitchCurve.INVALID }
        check(valid(spec(times,base,listOf(TraceNote(.2,.3,69.0)),invalid)).notes.isEmpty())
        val mixed = spec(times,short, listOf(TraceNote(.2,.3,69.0), TraceNote(.8,.1,77.0)) + bad)
        val v = valid(mixed); check(v.notes.size == 2)
        val c = makeChart(mixed); check(pixels(c).contentEquals(pixels(c, true))) { "screen/video diverged" }
        c.setPlayhead(1.5)
        val png = c.renderToBitmap(600, 300, "", emptyList())
        val pngPixels = IntArray(600 * 300); png.getPixels(pngPixels, 0, 600, 0, 0, 600, 300)
        check(pngPixels.contentEquals(pixels(c))) { "PNG diverged" }
        // Bracketed 3:1 harmonic artifact: uncertain contour island and marker
        // are suppressed, while the original data is untouched.
        val harmonic = base.copyOf(); for (i in 80..90) harmonic[i] = 88.01955
        for (i in 75..79) harmonic[i] = Double.NaN
        for (i in 91..95) harmonic[i] = Double.NaN
        val hs = spec(times,harmonic,listOf(TraceNote(.8,.1,88.01955)))
        val hc = makeChart(hs); pixels(hc)
        val dm = ChartView::class.java.getDeclaredMethod("displayData", TraceSpec::class.java); dm.isAccessible = true
        val hp = dm.invoke(hc, hs) as Pair<*, *>
        check((hp.second as DisplayNoteValidator.Result).notes.isEmpty())
        check(harmonic[85] == 88.01955)
        // A high short note with no matching neighbours is retained.
        val highEnd = base.copyOf(); for (i in 190..200) highEnd[i] = 88.01955
        val es = spec(times,highEnd,listOf(TraceNote(1.9,.1,88.01955)))
        val ec = makeChart(es); pixels(ec)
        check(((dm.invoke(ec,es) as Pair<*, *>).second as DisplayNoteValidator.Result).notes.isNotEmpty())
        val wav = Wav.decode(File(System.getProperty("demo.wav")).readBytes())!!
        val res = Wav.resample(wav.samples, wav.sampleRate, 22050)
        val pcm = ShortArray(res.size) { (res[it] * 32767).toInt().coerceIn(-32768,32767).toShort() }
        Analyzer.prepareShort(pcm,22050)
        val report = Analyzer.analyzeSignal(ShortSignal(pcm,22050))
        val chart = ChartView(Context()); chart.setReport(report)
        val method = ChartView::class.java.getDeclaredMethod("displayData", TraceSpec::class.java); method.isAccessible = true
        val pair = method.invoke(chart, chart.spec!!) as Pair<*, *>
        val result = pair.second as DisplayNoteValidator.Result
        for (n in report.notes.filter { it.start < 30 && PitchMath.hzToMidi(it.f0) > 72 }) {
            println("HIGH start=${n.start} dur=${n.duration} hz=${n.f0}")
            for (i in report.contourT.indices.filter { report.contourT[it] >= n.start - .07 && report.contourT[it] <= n.start + n.duration + .07 }) {
                println("SAMPLE t=${report.contourT[i]} hz=${report.contourHz[i]} status=${report.contourStatus[i]} confidence=${report.contourConfidence[i]}")
            }
        }
        println("DEMO raw=${report.notes.size} supported=${result.diagnostics.count { it.decision == DisplayNoteValidator.Decision.SUPPORTED }} partial=${result.diagnostics.count { it.decision == DisplayNoteValidator.Decision.PARTIALLY_SUPPORTED }} unsupported=${result.diagnostics.count { it.decision == DisplayNoteValidator.Decision.UNSUPPORTED }} indeterminate=${result.diagnostics.count { it.decision == DisplayNoteValidator.Decision.INDETERMINATE }}")
        for (d in result.diagnostics.filter { it.decision != DisplayNoteValidator.Decision.SUPPORTED }) {
            println("NOTE start=${d.note.start} duration=${d.note.duration} midi=${d.note.midi} hz=${PitchMath.midiToHz(d.note.midi)} valid=${d.validCount} medianHz=${d.medianMidi?.let { PitchMath.midiToHz(it) }} cents=${d.differenceCents} status=${d.decision}")
        }
        println("NoteMarkerConsistencyTest: OK")
    }
}
