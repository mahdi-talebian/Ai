package ir.hamyareqari.app

import kotlin.math.abs
import kotlin.math.max

/** Shared, deterministic helpers for the audio waveform and playback timeline. */
object AudioTimeline {
    fun waveform(samples: ShortArray?, bins: Int = 104): FloatArray {
        if (samples == null || samples.isEmpty() || bins <= 0) return FloatArray(0)
        val values = FloatArray(bins)
        for (i in 0 until bins) {
            val from = (i.toLong() * samples.size / bins).toInt()
            val to = (((i + 1L) * samples.size / bins).toInt())
                .coerceAtLeast(from + 1).coerceAtMost(samples.size)
            var peak = 0
            val count = minOf(28, to - from).coerceAtLeast(1)
            for (j in 0 until count) {
                val index = from + ((to - from - 1).toLong() * j / max(1, count - 1)).toInt()
                peak = max(peak, abs(samples[index].toInt()))
            }
            values[i] = (peak / 32768f).coerceIn(0.0f, 1.0f)
        }
        return values
    }

    fun secondsAtFraction(fraction: Double, durationSeconds: Double): Double =
        fraction.coerceIn(0.0, 1.0) * durationSeconds.coerceAtLeast(0.0)

    fun fractionAtSeconds(seconds: Double, durationSeconds: Double): Double =
        if (durationSeconds <= 0.0) 0.0 else (seconds / durationSeconds).coerceIn(0.0, 1.0)

    /** Input data must be strictly aligned in time; invalid F0 samples stay invalid. */
    fun validF0(hz: Double): Boolean = hz.isFinite() && hz > 0.0
}
