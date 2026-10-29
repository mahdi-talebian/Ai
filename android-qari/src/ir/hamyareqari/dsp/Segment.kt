package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** یک نتِ خوانده‌شده */
class NoteHit(
    val start: Double,
    val end: Double,
    val duration: Double,
    val f0: Double,
    val stability: Double,
    val confidence: Double,
    val glide: Double,
    val frames: Int,
    val harmonics: Int
) {
    val midi: Int get() = PitchMath.nearestMidi(f0)
    val name: String get() = PitchMath.noteName(f0)
    val nameAscii: String get() = PitchMath.noteNameAscii(f0)
}

/** سهم هر نت در کل خوانده‌شده */
class NoteShare(val name: String, val hz: Double, val sungSec: Double, val sharePct: Double, val count: Int)

object Segmenter {

    /**
     * تفکیک نت‌ها با هیسترزیس:
     *  • آستانهٔ شروع ۴۵ سنت، آستانهٔ ادامه ۷۲ سنت (۱٫۶ برابر)
     *  • شکاف‌های کوتاه (≤۰٫۱ ثانیه) اگر هم‌نت باشند ادغام می‌شوند
     */
    fun segment(
        track: PitchTrack,
        minDuration: Double = 0.08,
        stabilityCents: Double = 45.0,
        mergeGap: Double = 0.10,
        mergeCents: Double = 55.0
    ): List<NoteHit> {
        val n = track.size
        if (n == 0) return emptyList()
        val times = track.t
        val freqs = track.f0
        val conf = track.clarity
        val dt = if (n > 1) PitchMath.median(DoubleArray(n - 1) { times[it + 1] - times[it] }) else 0.01
        val contCents = stabilityCents * 1.6

        // ۱) ران‌های پیوسته با آستانهٔ ادامه
        val raw = ArrayList<IntArray>()
        var i = 0
        while (i < n) {
            if (!(freqs[i] > 0.0)) { i++; continue }
            var j = i
            val vals = ArrayList<Double>()
            val weights = ArrayList<Double>()
            vals.add(freqs[i]); weights.add(max(conf[i], 1e-3))
            while (j + 1 < n && freqs[j + 1] > 0.0) {
                val lo = max(0, vals.size - 25)
                var num = 0.0
                var den = 0.0
                for (q in lo until vals.size) { num += vals[q] * weights[q]; den += weights[q] }
                val refF = if (den > 0) num / den else vals[vals.size - 1]
                if (abs(PitchMath.cents(freqs[j + 1], refF)) > contCents) break
                j++
                vals.add(freqs[j]); weights.add(max(conf[j], 1e-3))
            }
            raw.add(intArrayOf(i, j))
            i = j + 1
        }

        // ۲) ادغام شکاف‌های کوتاه با نتِ نزدیک
        val merged = ArrayList<IntArray>()
        for (seg in raw) {
            if (merged.isEmpty()) { merged.add(seg.copyOf()); continue }
            val prev = merged[merged.size - 1]
            val gap = times[seg[0]] - times[prev[1]]
            if (gap <= mergeGap) {
                val a = PitchMath.median(DoubleArray(prev[1] - prev[0] + 1) { freqs[prev[0] + it] })
                val b = PitchMath.median(DoubleArray(seg[1] - seg[0] + 1) { freqs[seg[0] + it] })
                if (abs(PitchMath.cents(b, a)) <= mergeCents) {
                    prev[1] = seg[1]
                    continue
                }
            }
            merged.add(seg.copyOf())
        }

        // ۳) ساخت نت‌ها
        val out = ArrayList<NoteHit>(merged.size)
        for (s in merged) {
            val duration = times[s[1]] - times[s[0]] + dt
            if (duration < minDuration) continue
            val count = s[1] - s[0] + 1
            if (count < 2) continue
            var num = 0.0
            var den = 0.0
            for (k in s[0]..s[1]) {
                val f = freqs[k]
                if (f > 0.0) {
                    val w = max(conf[k], 1e-3)
                    num += f * w
                    den += w
                }
            }
            if (den <= 0.0) continue
            val f0 = num / den
            if (!(f0 > 0.0)) continue
            val devs = ArrayList<Double>(count)
            for (k in s[0]..s[1]) {
                val f = freqs[k]
                if (f > 0.0) devs.add(abs(PitchMath.cents(f, f0)))
            }
            if (devs.isEmpty()) continue
            val stability = PitchMath.percentile(devs.toDoubleArray(), 75.0)
            val trend = if (count > 1) PitchMath.cents(freqs[s[1]], f0) - PitchMath.cents(freqs[s[0]], f0) else 0.0
            var confSum = 0.0
            for (k in s[0]..s[1]) confSum += conf[k]
            out.add(
                NoteHit(
                    start = times[s[0]],
                    end = times[s[1]],
                    duration = duration,
                    f0 = f0,
                    stability = stability,
                    confidence = confSum / count,
                    glide = trend,
                    frames = count,
                    harmonics = 0
                )
            )
        }
        return out
    }

    /**
     * بستهٔ ابزار آماری: سهم نت‌ها، مکث‌ها، و انتخاب نت‌های «واقعی».
     */
    fun shares(notes: List<NoteHit>, sungSec: Double): List<NoteShare> {
        val byMidi = HashMap<Int, Double>()
        val countByMidi = HashMap<Int, Int>()
        val hzByMidi = HashMap<Int, Double>()
        for (nt in notes) {
            val m = nt.midi
            byMidi[m] = (byMidi[m] ?: 0.0) + nt.duration
            countByMidi[m] = (countByMidi[m] ?: 0) + 1
            // فرکانسِ نمایندهٔ هر نت = میانهٔ وزنی (میانگین ساده کافی است)
            hzByMidi[m] = (hzByMidi[m] ?: 0.0) + nt.f0
        }
        val total = if (sungSec > 0) sungSec else 1.0
        return byMidi.keys.sortedByDescending { byMidi[it] ?: 0.0 }.map { m ->
            val c = countByMidi[m] ?: 1
            val sec = byMidi[m] ?: 0.0
            NoteShare(
                name = PitchMath.latinName(m),
                hz = (hzByMidi[m] ?: 0.0) / c,
                sungSec = sec,
                sharePct = 100.0 * sec / total,
                count = c
            )
        }
    }

    /**
     * انتخاب نت‌های واقعی: هر نت باید دست‌کم ۲ بار و در مجموع ≥۰٫۴٪ زمان خوانده‌شده
     * اجرا شده باشد. جیغ‌های لحظه‌ای و لغزش‌های تک‌فریمی از گستره بیرون می‌مانند.
     */
    fun realNotes(notes: List<NoteHit>, sungSec: Double): List<NoteHit> {
        if (notes.isEmpty()) return emptyList()
        val total = if (sungSec > 0) sungSec else 1.0
        val need = total * 0.004
        val secByMidi = HashMap<Int, Double>()
        val countByMidi = HashMap<Int, Int>()
        for (nt in notes) {
            secByMidi[nt.midi] = (secByMidi[nt.midi] ?: 0.0) + nt.duration
            countByMidi[nt.midi] = (countByMidi[nt.midi] ?: 0) + 1
        }
        // شرط: یا دست‌کم دو بار خوانده شده، یا خودش به‌تنهایی به‌قدر کافی طولانی است.
        // (اجرایی که یک نتِ اوج را فقط یک بار می‌خواند نباید اوجش را از دست بدهد)
        val real = notes.filter { nt ->
            val c = countByMidi[nt.midi] ?: 0
            val sec = secByMidi[nt.midi] ?: 0.0
            (c >= 2 || sec >= 0.30) && sec >= need && sec >= 0.10
        }
        if (real.isNotEmpty()) return real
        // اگر خواندن کوتاه بود، سقف را نرم می‌کنیم تا هیچ‌وقت دست‌خالی نمانیم
        val relaxed = notes.filter { it.duration >= 0.12 }
        if (relaxed.isNotEmpty()) return relaxed
        return listOf(notes.maxByOrNull { it.duration }!!)
    }

    /** سکوت‌های ≥ آستانه، به‌ثانیه */
    fun pauses(notes: List<NoteHit>, durationSec: Double, minPause: Double = 0.30): List<Double> {
        if (notes.isEmpty()) return emptyList()
        val out = ArrayList<Double>()
        val first = notes.first().start
        if (first >= minPause) out.add(first)
        for (i in 1 until notes.size) {
            val gap = notes[i].start - notes[i - 1].end
            if (gap >= minPause) out.add(gap)
        }
        val tail = durationSec - notes.last().end
        if (tail >= minPause) out.add(tail)
        return out
    }

    fun sungSeconds(notes: List<NoteHit>): Double {
        var s = 0.0
        for (nt in notes) s += nt.duration
        return s
    }

    /** نوار گستره: چند نقطه در هر اکتاو برای نمایش گرافیکی */
    fun octaveSpan(lowMidi: Int, highMidi: Int): Int = (highMidi / 12) - (lowMidi / 12)

    fun semitoneLabel(semitones: Double): String {
        val r = (semitones * 10).roundToInt() / 10.0
        return PitchMath.faDigits(String.format(java.util.Locale.US, "%.1f", r))
    }
}
