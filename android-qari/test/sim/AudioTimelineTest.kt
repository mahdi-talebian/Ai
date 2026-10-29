import ir.hamyareqari.app.AudioTimeline
import kotlin.math.abs

object AudioTimelineTest {
    @JvmStatic fun main(args: Array<String>) {
        val source = shortArrayOf(0, 0, 12000, 0, -28000, 0, 4000, 0)
        val wave = AudioTimeline.waveform(source, 4)
        check(wave.size == 4) { "expected four waveform bins" }
        check(wave[0] == 0f && wave[1] > 0.3f && wave[2] > 0.8f && wave[3] > 0f) {
            "waveform must describe the real sample amplitudes: ${wave.toList()}"
        }
        check(AudioTimeline.waveform(null).isEmpty())
        check(AudioTimeline.waveform(shortArrayOf()).isEmpty())
        check(AudioTimeline.waveform(source, 0).isEmpty())

        val changedFile = AudioTimeline.waveform(ShortArray(source.size) { 0 }, 4)
        check(wave.indices.any { abs(wave[it] - changedFile[it]) > .01f }) {
            "new file must not reuse a previous waveform"
        }
        check(AudioTimeline.secondsAtFraction(.5, 120.0) == 60.0)
        check(AudioTimeline.secondsAtFraction(-1.0, 120.0) == 0.0)
        check(AudioTimeline.secondsAtFraction(2.0, 120.0) == 120.0)
        check(AudioTimeline.fractionAtSeconds(60.0, 120.0) == .5)
        check(AudioTimeline.fractionAtSeconds(500.0, 120.0) == 1.0)
        check(AudioTimeline.fractionAtSeconds(20.0, 0.0) == 0.0)

        check(AudioTimeline.validF0(220.0))
        check(!AudioTimeline.validF0(0.0))
        check(!AudioTimeline.validF0(Double.NaN))
        check(!AudioTimeline.validF0(Double.POSITIVE_INFINITY))
        println("AudioTimelineTest: waveform, file reset, empty state, shared seek clock and invalid F0 passed")
    }
}
