package ir.hamyareqari.dsp

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * تخمین‌گر دوگانهٔ F0 + ویژگی‌های طیفی، روی یک FFT مشترک.
 *
 * چرا یک FFT مشترک؟ هم‌بستگی خودکار (که هم YIN و هم MPM به آن نیاز دارند) از
 * توانِ طیف به‌دست می‌آید؛ پس با یک رفت‌وبرگشت FFT هم دو تخمین‌گر و هم
 * ویژگی‌های طیفی (پخشی/شار/برجستگی هارمونیک‌ها) محاسبه می‌شوند. این کار
 * «نظر دوم» را تقریباً رایگان می‌کند.
 *
 * دو تخمین‌گر:
 *   • YIN (de Cheveigné & Kawahara 2002) — تابع تفاضل نرمال‌شدهٔ تجمعی
 *   • MPM (McLeod & Wyvill 2005) — NSDF و «قله‌های کلیدی»
 * هر دو از یک هم‌بستگی استفاده می‌کنند اما نرمال‌سازی و قاعدهٔ انتخاب قله
 * متفاوت است؛ در نتیجه در شرایط نویزی معمولاً خطاهای متفاوتی می‌سازند.
 *
 * خروجی: چند «نامزد» F0 با امتیاز، به‌جای یک عدد. تصمیم نهایی (کدام نامزد و
 * آیا اصلاً معتبر است) در PitchEngine و با شواهد زمانی گرفته می‌شود.
 */
class FrameResult {

    var rms = 0.0
    var clipFrac = 0.0
    /** پخشی طیفی: هندسی/حسابی روی توان (۱ = نویز سفید، نزدیک ۰ = تُنال) */
    var flatness = 1.0
    /** شار طیفی نرمال‌شده (۰ = ایستا) */
    var flux = 0.0
    var yinF0 = 0.0
    var yinClarity = 0.0
    var mpmF0 = 0.0
    var mpmClarity = 0.0
    var candCount = 0
    val candF0 = DoubleArray(DualEstimator.MAX_CAND)
    val candScore = DoubleArray(DualEstimator.MAX_CAND)
    var bestSupport = 0.0
    /** سهم انرژی باند صدای توضیح‌داده‌شده با هارمونیک‌های بهترین نامزد */
    var coverage = 0.0
    /** برجستگی هارمونیک اول (پایه) بهترین نامزد از کف محلی طیف */
    var fundamentalProminence = 0.0

    fun reset() {
        rms = 0.0
        clipFrac = 0.0
        flatness = 1.0
        flux = 0.0
        yinF0 = 0.0
        yinClarity = 0.0
        mpmF0 = 0.0
        mpmClarity = 0.0
        candCount = 0
        bestSupport = 0.0
        coverage = 0.0
        fundamentalProminence = 0.0
        for (i in candF0.indices) { candF0[i] = 0.0; candScore[i] = 0.0 }
    }
}

class DualEstimator(
    private val sampleRate: Int,
    val fMin: Double = 55.0,
    val fMax: Double = 1200.0,
    frameSec: Double = 0.045,
    /** کف باند محاسبهٔ ویژگی‌ها؛ زیر آن معمولاً نویز باد و هوم است */
    private val featureLoHz: Double = 70.0,
    private val featureHiHz: Double = 3600.0
) {
    companion object {
        /** نامزدهایی که به لایهٔ ردیابی داده می‌شود */
        const val MAX_CAND = 4
        /** استخر داخلی نامزدها (شامل فرض‌های ×۲ و ×۳ پیش از امتیازدهی) */
        private const val MAX_POOL = 8
        /** آستانهٔ سخاوتمندانه برای «نامزد بودن» — تصمیم نهایی جای دیگری گرفته می‌شود */
        private const val CAND_CMND = 0.50
        private const val CAND_NSDF = 0.45
        /** دو نامزد نزدیک‌تر از این مقدار یکی شمرده می‌شوند */
        private const val MERGE_CENTS = 45.0
    }

    val w: Int = max(64, (frameSec * sampleRate).toInt())
    val tauMin: Int = max(2, floor(sampleRate / fMax).toInt())
    val tauMax: Int = min(w - 2, ceil(sampleRate / fMin).toInt())
    private val nfft: Int = nextPow2(w + tauMax + 2)
    private val bins: Int = nfft / 2 + 1

    private val fft = Fft(nfft)
    private val re = DoubleArray(nfft)
    private val im = DoubleArray(nfft)
    private val prefix = DoubleArray(w + 1)
    private val d = DoubleArray(tauMax + 2)
    private val cmnd = DoubleArray(tauMax + 2)
    private val nsdf = DoubleArray(tauMax + 2)
    private val mag = DoubleArray(bins)
    private val prevMag = DoubleArray(bins)
    private val floorAcc = DoubleArray(bins + 1)
    private val floorVal = DoubleArray(bins)
    private var hasPrev = false

    private val candF0 = DoubleArray(MAX_POOL)
    private val candRaw = DoubleArray(MAX_POOL)
    private val topF0 = DoubleArray(MAX_CAND)
    private val topScore = DoubleArray(MAX_CAND)
    private var candN = 0

    private val loBin = max(1, (featureLoHz / (sampleRate.toDouble() / nfft)).toInt())
    private val hiBin: Int = min(bins - 1, (min(featureHiHz, sampleRate / 2.0 - 1.0) / (sampleRate.toDouble() / nfft)).toInt())
    private val binHz = sampleRate.toDouble() / nfft
    private val maxHarmHz = min(featureHiHz, sampleRate / 2.0 - 1.0)

    fun framesCount(totalSamples: Int, hop: Int): Int {
        if (totalSamples < w) return 0
        return (totalSamples - w) / hop + 1
    }

    fun newFrameBuffer(): DoubleArray = DoubleArray(w)

    fun resetHistory() { hasPrev = false }

    /** تحلیل کامل یک فریم در نقطهٔ start */
    fun analyze(sig: Signal, start: Int, out: FrameResult, frame: DoubleArray) {
        out.reset()
        if (start < 0 || start + w > sig.size) { hasPrev = false; return }
        sig.read(start, w, frame)

        // ── انرژی، DC، کلیپینگ ──
        prefix[0] = 0.0
        var mean = 0.0
        for (i in 0 until w) mean += frame[i]
        mean /= w
        var sq = 0.0
        var clipped = 0
        for (i in 0 until w) {
            val v = frame[i] - mean
            frame[i] = v
            sq += v * v
            prefix[i + 1] = sq
            if (abs(frame[i]) > 0.88) clipped++
        }
        out.rms = sqrt(sq / w)
        out.clipFrac = clipped.toDouble() / w
        if (sq < 1e-12) { hasPrev = false; return }

        // ── طیف (برای ویژگی‌ها) و هم‌بستگی خودکار (برای دو تخمین‌گر) ──
        java.util.Arrays.fill(re, 0, nfft, 0.0)
        java.util.Arrays.fill(im, 0, nfft, 0.0)
        for (i in 0 until w) re[i] = frame[i]
        fft.forward(re, im)
        for (k in 0 until bins) {
            val a = re[k]
            val b = im[k]
            mag[k] = sqrt(a * a + b * b)
        }
        for (k in 0 until nfft) {
            val a = re[k]
            val b = im[k]
            re[k] = a * a + b * b
            im[k] = 0.0
        }
        fft.inverse(re, im)

        fun acf(tau: Int): Double = re[tau]

        // ── YIN: تابع تفاضل و نرمال‌سازی تجمعی ──
        var running = 0.0
        cmnd[0] = 1.0
        d[0] = 0.0
        for (tau in 1..tauMax) {
            val len = w - tau
            if (len <= 0) { cmnd[tau] = 1.0; d[tau] = 0.0; continue }
            val sq0 = prefix[len]
            val sqTau = prefix[w] - prefix[tau]
            var dv = (sq0 + sqTau - 2.0 * acf(tau)) / len
            if (dv < 0.0) dv = 0.0
            d[tau] = dv
            running += dv
            cmnd[tau] = if (running > 1e-12) dv * tau / running else 1.0
            // NSDF همان مخرج است، فقط نرمال‌سازی متفاوت (بیشینهٔ محلی در ۰..۱)
            val m = sq0 + sqTau
            nsdf[tau] = if (m > 1e-12) 2.0 * acf(tau) / m else 0.0
        }
        nsdf[0] = 1.0

        // ── ویژگی‌های طیفی ──
        out.flatness = flatness(loBin, hiBin)
        if (hasPrev) out.flux = flux(loBin, hiBin) else out.flux = 0.0
        System.arraycopy(mag, 0, prevMag, 0, bins)
        hasPrev = true

        // ── نامزدهای YIN: کمینه‌های محلی زیر آستانه ──
        candN = 0
        val yinPick = ArrayList<Int>(4)
        var bestTau = -1
        var bestVal = Double.MAX_VALUE
        for (tau in tauMin..tauMax) {
            if (cmnd[tau] < bestVal) { bestVal = cmnd[tau]; bestTau = tau }
            if (tau > tauMin && tau < tauMax && cmnd[tau] <= CAND_CMND &&
                cmnd[tau] < cmnd[tau - 1] && cmnd[tau] <= cmnd[tau + 1]
            ) {
                yinPick.add(tau)
            }
        }
        // اولین کمینه (قاعدهٔ YIN) و کمینهٔ سراسری، هر دو در فهرست باشند
        if (bestTau > 0) yinPick.add(bestTau)
        var firstTau = -1
        var tau = tauMin
        while (tau <= tauMax - 1) {
            if (cmnd[tau] < CAND_CMND) {
                while (tau + 1 <= tauMax && cmnd[tau + 1] < cmnd[tau]) tau++
                firstTau = tau
                break
            }
            tau++
        }
        if (firstTau > 0) yinPick.add(firstTau)

        for (tp in yinPick) {
            if (tp <= 1 || tp >= tauMax) continue
            val te = parabolicTau(tp, cmnd)
            val f = if (te > 0.5) sampleRate / te else 0.0
            val clarity = (1.0 - cmnd[tp]).coerceIn(0.0, 1.0)
            if (f > 0.0) addCandidate(f, clarity)
        }
        out.yinF0 = if (firstTau > 0) sampleRate / parabolicTau(firstTau, cmnd) else 0.0
        out.yinClarity = if (firstTau > 0) (1.0 - cmnd[firstTau]).coerceIn(0.0, 1.0) else 0.0

        // ── نامزدهای MPM: قله‌های کلیدی NSDF ──
        var peakMax = 0.0
        for (tau in tauMin..tauMax) if (nsdf[tau] > peakMax) peakMax = nsdf[tau]
        var bestPeak = -1
        var bestPeakVal = -1.0
        for (tau in tauMin + 1 until tauMax) {
            if (nsdf[tau] > nsdf[tau - 1] && nsdf[tau] >= nsdf[tau + 1] && nsdf[tau] >= CAND_NSDF) {
                if (nsdf[tau] > bestPeakVal) { bestPeakVal = nsdf[tau]; bestPeak = tau }
            }
        }
        // قاعدهٔ McLeod: اولین قلهٔ کلیدیِ ≥ ۹۰٪ بیشینه — نه بلندترین قله.
        // (در تُنِ هارمونیک، همهٔ مضارب دوره قله‌ای نزدیک ۱ دارند؛ بدون این
        // قاعده، MPM به‌طور تصادفی زیراکتاو انتخاب می‌کند.)
        val cut = 0.90 * peakMax
        var keyTau = -1
        if (peakMax >= CAND_NSDF) {
            for (tau in tauMin + 1 until tauMax) {
                if (nsdf[tau] > nsdf[tau - 1] && nsdf[tau] >= nsdf[tau + 1] && nsdf[tau] >= cut) {
                    keyTau = tau
                    break
                }
            }
        }
        if (keyTau < 0) keyTau = bestPeak
        if (keyTau > 0) {
            val te = parabolicTau(keyTau, nsdf)
            out.mpmF0 = if (te > 0.5) sampleRate / te else 0.0
            out.mpmClarity = nsdf[keyTau].coerceIn(0.0, 1.0)
            if (out.mpmF0 > 0.0) addCandidate(out.mpmF0, out.mpmClarity)
        }
        // بلندترین قله هم به‌عنوان نامزد دیگر (اگر متفاوت بود) نگه داشته می‌شود
        if (bestPeak > 0 && bestPeak != keyTau) {
            val te = parabolicTau(bestPeak, nsdf)
            val f = if (te > 0.5) sampleRate / te else 0.0
            if (f > 0.0) addCandidate(f, bestPeakVal.coerceIn(0.0, 1.0) * 0.9)
        }

        // ── امتیازدهی نامزدها با شواهد طیفیِ همان فریم ──
        //
        // نکتهٔ مهم: اگر یک تُن دقیقاً در شبکهٔ نمونه‌برداری دوره‌گرد باشد
        // (مثلاً ۳۰۰ هرتز با ۲۲٫۰۵ کیلوهرتز → دورهٔ ۱۴۷ نمونه)، تأخیر
        // درست روی شبکهٔ صحیح نمی‌افتد و کمینهٔ تابع تفاضل در ۲× یا ۳× دوره
        // ظاهر می‌شود. لذا هر نامزد با فرض‌های ×۲ و ×۳ هم ارزیابی می‌شود و
        // فقط نامزدهایی می‌مانند که هارمونیک‌هایشان واقعاً در طیف دیده می‌شود.
        computeFloor()
        var bestSupport = 0.0
        var topN = 0
        for (i in 0 until candN) {
            for (mul in 1..3) {
                val f = candF0[i] * mul
                if (f < fMin * 0.9 || f > fMax * 1.1) continue
                val sup = harmonicSupport(f)
                if (sup > bestSupport) bestSupport = sup
                // امتیاز = شفافیت تخمین‌گر × شاهد هارمونیکی همان لحظه
                var score = candRaw[i] * (0.35 + 0.65 * sup)
                if (mul > 1) score *= 0.97 // فرض اضافی، جریمهٔ ناچیز برای سادگی
                score = score.coerceIn(0.0, 1.0)
                // درج در فهرست مرتب (حداکثر MAX_CAND)، با ادغام نامزد نزدیک
                var dup = false
                for (k in 0 until topN) {
                    if (abs(PitchMath.cents(f, topF0[k])) <= MERGE_CENTS) {
                        if (score > topScore[k]) { topScore[k] = score; topF0[k] = f }
                        dup = true
                        break
                    }
                }
                if (dup) continue
                if (topN < MAX_CAND) {
                    var pos = topN
                    while (pos > 0 && topScore[pos - 1] < score) {
                        topScore[pos] = topScore[pos - 1]
                        topF0[pos] = topF0[pos - 1]
                        pos--
                    }
                    topScore[pos] = score
                    topF0[pos] = f
                    topN++
                } else if (score > topScore[MAX_CAND - 1]) {
                    var pos = MAX_CAND - 1
                    while (pos > 0 && topScore[pos - 1] < score) {
                        topScore[pos] = topScore[pos - 1]
                        topF0[pos] = topF0[pos - 1]
                        pos--
                    }
                    topScore[pos] = score
                    topF0[pos] = f
                }
            }
        }
        // حذف مضاربِ هم‌امتیاز: اگر ۲× یا ۳× یک نامزد با امتیاز تقریباً یکسان
        // هم در فهرست باشد، نامزد پایین‌تر ترجیح داده می‌شود (اصل «فرض پایین‌تر»)
        var n2 = 0
        for (i in 0 until topN) {
            var drop = false
            for (k in 0 until n2) {
                val ratioCents = abs(1200.0 * kotlin.math.log2(topF0[i] / topF0[k]))
                if ((abs(ratioCents - 1200.0) < 70.0 || abs(ratioCents - 1902.0) < 70.0) &&
                    topScore[i] <= topScore[k] + 0.10
                ) { drop = true; break }
            }
            if (drop) continue
            topF0[n2] = topF0[i]
            topScore[n2] = topScore[i]
            n2++
        }
        for (i in 0 until n2) { out.candF0[i] = topF0[i]; out.candScore[i] = topScore[i] }
        out.candCount = n2
        out.bestSupport = bestSupport
        // سهم انرژی توضیح‌داده‌شده با هارمونیک‌های بهترین نامزد: یک صدای
        // تک‌منبع بخش بزرگی از انرژی باند را زیر هارمونیک‌هایش دارد؛ همهمهٔ
        // چندگوینده‌ای نه.
        if (n2 > 0) {
            out.coverage = harmonicCoverage(topF0[0])
            out.fundamentalProminence = fundamentalProminence(topF0[0])
        }
    }

    private fun parabolicTau(tau: Int, v: DoubleArray): Double {
        var exact = tau.toDouble()
        if (tau in 1 until tauMax) {
            val a = v[tau - 1]
            val b = v[tau]
            val c = v[tau + 1]
            val den = a - 2.0 * b + c
            if (abs(den) > 1e-14) {
                val delta = 0.5 * (a - c) / den
                if (delta in -0.5..0.5) exact += delta
            }
        }
        return exact
    }

    /** افزودن نامزد با ادغام نامزدهای نزدیک (به سنت) */
    private fun addCandidate(f0: Double, score: Double) {
        if (f0 < fMin * 0.9 || f0 > fMax * 1.1) return
        for (i in 0 until candN) {
            if (abs(PitchMath.cents(f0, candF0[i])) <= MERGE_CENTS) {
                if (score > candRaw[i]) { candRaw[i] = score; candF0[i] = f0 }
                return
            }
        }
        if (candN < candF0.size) {
            candF0[candN] = f0
            candRaw[candN] = score
            candN++
            return
        }
        // جایگزینی ضعیف‌ترین
        var wi = 0
        for (i in 1 until candN) if (candRaw[i] < candRaw[wi]) wi = i
        if (score > candRaw[wi]) { candF0[wi] = f0; candRaw[wi] = score }
    }

    /** پخشی طیفی روی توان، با میانگین هندسی/حسابی */
    private fun flatness(lo: Int, hi: Int): Double {
        var sumLog = 0.0
        var sum = 0.0
        var cnt = 0
        for (k in lo..hi) {
            val p = mag[k] * mag[k] + 1e-18
            sumLog += ln(p)
            sum += p
            cnt++
        }
        if (cnt < 8 || sum <= 0.0) return 1.0
        val geo = kotlin.math.exp(sumLog / cnt)
        val arith = sum / cnt
        return (geo / arith).coerceIn(0.0, 1.0)
    }

    /** شار طیفی نرمال‌شده: جمع قدرمطلق تفاوت تقسیم بر انرژی جاری */
    private fun flux(lo: Int, hi: Int): Double {
        var diff = 0.0
        var energy = 0.0
        for (k in lo..hi) {
            diff += abs(mag[k] - prevMag[k])
            energy += mag[k]
        }
        return if (energy > 1e-12) (diff / energy).coerceIn(0.0, 2.0) else 0.0
    }

    /** کف محلی طیف با میانگین لغزان (بدون تخصیص حافظه در حلقه) */
    private fun computeFloor() {
        floorAcc[0] = 0.0
        for (k in 0 until bins) floorAcc[k + 1] = floorAcc[k] + mag[k]
        val half = 7
        for (k in 0 until bins) {
            val a = max(0, k - half)
            val b = min(bins - 1, k + half)
            val c0 = max(0, k - 2)
            val c1 = min(bins - 1, k + 2)
            val total = floorAcc[b + 1] - floorAcc[a] - (floorAcc[c1 + 1] - floorAcc[c0])
            val cnt = (b - a + 1) - (c1 - c0 + 1)
            floorVal[k] = if (cnt > 0) total / cnt else 0.0
        }
    }

    /**
     * برجستگی هارمونیک‌های یک نامزد: چه سهمی از ۱۲ هارمونیک اول، از کف محلی
     * طیف بیرون زده‌اند؟ (وزن هارمونیک‌های پایین‌تر بیشتر است، چون در صدا
     * حضور پایه و هارمونیک‌های دوم/سوم نشانهٔ اصلی «صدا» است.)
     */
    /** سهم انرژی باند (۷۰–۳۶۰۰Hz) که با قله‌های هارمونیک نامزد توضیح داده می‌شود */
    private fun harmonicCoverage(f0: Double): Double {
        if (f0 <= 0.0) return 0.0
        val total = floorAcc[hiBin + 1] - floorAcc[loBin]
        if (total <= 1e-12) return 0.0
        var explained = 0.0
        for (k in 1..24) {
            val hz = f0 * k
            if (hz >= maxHarmHz) break
            val c = (hz / binHz).roundToInt()
            if (c < 2 || c >= bins - 2) break
            var pk = 0.0
            for (o in -2..2) {
                val j = c + o
                if (j >= 1 && j < bins) { val v = mag[j]; if (v > pk) pk = v }
            }
            explained += pk
        }
        // انرژی هر هارمونیک حدود ۳ بین پخش می‌شود (پنجرهٔ هَن)
        return (explained * 3.0 / total).coerceIn(0.0, 1.0)
    }

    /** برجستگی پایه (هارمونیک اول) نسبت به کف محلی طیف */
    private fun fundamentalProminence(f0: Double): Double {
        if (f0 <= 0.0) return 0.0
        val pos = f0 / binHz
        val c = pos.roundToInt()
        if (c < 2 || c >= bins - 2) return 0.0
        var pk = 0.0
        for (o in -2..2) {
            val j = c + o
            if (j >= 1 && j < bins) { val v = mag[j]; if (v > pk) pk = v }
        }
        val fl = floorVal[c]
        val prom = if (fl > 1e-15) pk / fl else 1.0
        return PitchMath.clamp((prom - 1.7) / 2.6, 0.0, 1.0)
    }

    private fun harmonicSupport(f0: Double): Double {
        if (f0 <= 0.0) return 0.0
        var num = 0.0
        var den = 0.0
        for (k in 1..12) {
            val hz = f0 * k
            if (hz >= maxHarmHz) break
            val pos = hz / binHz
            val c = pos.roundToInt()
            if (c < 2 || c >= bins - 2) break
            var pk = 0.0
            for (o in -2..2) {
                val j = c + o
                if (j >= 1 && j < bins) { val v = mag[j]; if (v > pk) pk = v }
            }
            val fl = floorVal[c]
            val prom = if (fl > 1e-15) pk / fl else 1.0
            val s = PitchMath.clamp((prom - 1.7) / 2.6, 0.0, 1.0)
            val wgt = 1.0 / (1.0 + 0.35 * (k - 1))
            num += s * wgt
            den += wgt
        }
        return if (den > 0.3) (num / den).coerceIn(0.0, 1.0) else 0.0
    }
}
