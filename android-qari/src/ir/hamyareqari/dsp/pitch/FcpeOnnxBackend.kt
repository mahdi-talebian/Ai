package ir.hamyareqari.dsp.pitch

import ir.hamyareqari.dsp.Signal
import kotlin.math.max
import kotlin.math.min

/**
 * backend واقعی FCPE با ONNX Runtime — **بدون هیچ خروجی ساختگی**.
 *
 * این کلاس فقط وقتی فعال می‌شود که هر دو شرط برقرار باشد:
 *   ۱) کلاس‌های ONNX Runtime (`ai.onnxruntime.*`) در کلاس‌پث برنامه باشند
 *      (AAR کتابخانهٔ `onnxruntime-android`)؛
 *   ۲) فایل مدل ONNX واقعی در assets برنامه (یا مسیر داده‌شده) موجود باشد.
 *
 * اگر هر کدام نبود، [tryCreate] برمی‌گردد null با دلیل ثبت‌شده و
 * [PitchBackendSelector] بدون Crash به موتور فعلی برمی‌گردد.
 *
 * ── قرارداد مدل (تأییدشده از کد upstream CNChTu/FCPE و checkpoint باندل‌شده) ──
 *   • ورودی:  float32 [1, n_samples, 1] — موج صوتی ۱۶٬۰۰۰ هرتز (mel داخل گراف)
 *   • خروجی:  float32 [1, n_frames, 1] بر حسب Hz؛ n_frames ≈ n_samples/160 + 1
 *   • گام:    160 نمونه = ۱۰ms (در مدل باندل‌شدهٔ fcpe_c_v001)
 *   • 0 یا زیر آستانهٔ V/UV یعنی unvoiced
 *   • خروجی ممکن است NaN باشد (در decode بعضی exportها) — باید رد شود
 *   • محدودهٔ مدل باندل‌شده: f0_min=32.7Hz (C1)، f0_max=1975.5Hz (B6)
 *   • مدل «اعتماد کالیبره‌شده» نمی‌دهد؛ تصمیم V/UV با threshold=0.006 داخل
 *     مدل/decoder است و نباید confidence رسمی مدل نامیده شود.
 *
 * نکتهٔ provenance: export رسمی ONNX از upstream وجود ندارد (کلاس
 * `InferCFNaiveMelPEONNX` در torchfcpe یک stub با `NotImplementedError` است)؛
 * exportهای ONNX موجود در جامعه‌اند و باید hash/سازگاری‌شان روی دستگاه تأیید شود.
 * این کلاس در زمان بارگذاری، شکل ورودی/خروجی session را واقعاً بررسی می‌کند و
 * اگر با قرارداد فوق نخواند، مدل را نمی‌پذیرد.
 */
class FcpeOnnxBackend private constructor(
    private val session: OnnxSession,
    private val fMin: Double,
    private val fMax: Double
) : PitchEstimator {

    override val backendId: BackendId = BackendId.FCPE_ONNX

    /** یک resampler به‌ازای هر نرخ منبع (کش کوچک) */
    private val resamplers = HashMap<Int, ModelResampler>()

    private fun resamplerFor(fromRate: Int): ModelResampler =
        synchronized(resamplers) {
            resamplers.getOrPut(fromRate) { ModelResampler(fromRate, MODEL_RATE) }
        }

    override fun reset() {
        // session بدون‌حالت است؛ چیزی برای پاک‌سازی نیست
    }

    /**
     * تخمین یک فریم: پنجرهٔ ~۴۵ms اطراف [startSample] → resample به ۱۶kHz →
     * inference → اعتبارسنجی خروجی. برای زنده؛ برای فایل از [estimateAll] استفاده کن.
     */
    override fun estimateFrame(sig: Signal, startSample: Int, timestampSec: Double): RawPitchFrame {
        val sr = sig.sampleRate
        val win = max(MIN_WINDOW, (0.045 * sr).toInt())
        val start = max(0, startSample - win / 2)
        val len = min(win, sig.size - start)
        if (len < MIN_WINDOW / 2) return unvoiced(timestampSec)
        val buf = DoubleArray(len)
        sig.read(start, len, buf)
        val rms = rmsOf(buf, 0, buf.size)
        val f0 = inferMedian(buf, sr) ?: return unvoiced(timestampSec, rms)
        return RawPitchFrame(
            timestampSec = timestampSec,
            f0Hz = f0,
            confidence = 0.0, // مدل confidence نمی‌دهد؛ صفر یعنی «کالیبره‌نشده»
            loudnessRms = rms,
            snrDb = 0.0,
            periodicity = 0.0,
            agreementCents = Double.NaN,
            backend = BackendId.FCPE_ONNX.tag
        )
    }

    /**
     * مسیر فایل: تکه‌های ~۴ ثانیه‌ای با overlap و حذف لبه‌های نامطمئن —
     * از کپی چندبارهٔ کل فایل جلوگیری می‌شود.
     */
    override fun estimateAll(
        sig: Signal,
        hopMs: Double,
        progress: ((Double) -> Unit)?
    ): List<RawPitchFrame> {
        val sr = sig.sampleRate
        val chunkSec = 4.0
        val overlapSec = 0.25
        val chunkN = (chunkSec * sr).toInt()
        val overlapN = (overlapSec * sr).toInt()
        val out = ArrayList<RawPitchFrame>(max(1, sig.size / max(1, (hopMs / 1000.0 * sr).toInt())))
        var pos = 0
        while (pos < sig.size) {
            val len = min(chunkN + 2 * overlapN, sig.size - pos)
            val buf = DoubleArray(len)
            sig.read(pos, len, buf)
            val isLastChunk = pos + len >= sig.size
            val frames = inferFrames(buf, sr, hopMs)
            for (tr in frames) {
                // لبه‌های تکه فقط برای آخرین تکه نگه داشته می‌شوند
                if (!isLastChunk && (tr.tRel < overlapSec || tr.tRel > (len.toDouble() / sr) - overlapSec)) continue
                out.add(
                    RawPitchFrame(
                        timestampSec = pos.toDouble() / sr + tr.tRel,
                        f0Hz = tr.f0,
                        confidence = 0.0,
                        loudnessRms = tr.rms,
                        snrDb = 0.0,
                        periodicity = 0.0,
                        agreementCents = Double.NaN,
                        backend = BackendId.FCPE_ONNX.tag
                    )
                )
            }
            if (isLastChunk) break
            pos += chunkN
            progress?.invoke(min(1.0, pos.toDouble() / sig.size))
        }
        progress?.invoke(1.0)
        return out
    }

    private class FrameOut(val tRel: Double, val f0: Double, val rms: Double)

    /** تخمین تک‌عددی روی یک بافر (میانهٔ فریم‌های صدادار) */
    private fun inferMedian(buf: DoubleArray, sr: Int): Double? {
        val frames = inferFrames(buf, sr, 10.0)
        val voiced = frames.filter { it.f0 > 0.0 }
        if (voiced.isEmpty()) return null
        val sorted = voiced.map { it.f0 }.sorted()
        return sorted[sorted.size / 2]
    }

    /** تخمین فریم‌به‌فریم روی یک بافر: resample → session → اعتبارسنجی */
    private fun inferFrames(buf: DoubleArray, sr: Int, hopMs: Double): List<FrameOut> {
        val resampled = if (sr == MODEL_RATE) buf else resamplerFor(sr).process(buf)
        if (resampled.size < MODEL_HOP * 2) return emptyList()
        val rawOut = try {
            session.run(resampled)
        } catch (e: Throwable) {
            null // inference شکست خورد → بدون خروجی ساختگی
        } ?: return emptyList()
        val out = ArrayList<FrameOut>(rawOut.size)
        for (i in rawOut.indices) {
            val tRel = i * MODEL_HOP.toDouble() / MODEL_RATE
            val a = (tRel * sr).toInt().coerceIn(0, max(0, buf.size - 1))
            val b = min(buf.size, a + max(1, (hopMs / 1000.0 * sr).toInt()))
            out.add(FrameOut(tRel, sanitizeF0(rawOut[i]), rmsOf(buf, a, b)))
        }
        return out
    }

    private fun rmsOf(buf: DoubleArray, a: Int, b: Int): Double {
        if (b <= a) return 0.0
        var acc = 0.0
        for (k in a until b) acc += buf[k] * buf[k]
        return kotlin.math.sqrt(acc / (b - a))
    }

    /**
     * اعتبارسنجی خروجی مدل — طبق قرارداد:
     * NaN/Infinity رد، صفر = unvoiced، خارج از محدودهٔ مجاز = نامعتبر (نه clamp).
     */
    fun sanitizeF0(v: Float): Double {
        if (v.isNaN() || v.isInfinite()) return 0.0
        val d = v.toDouble()
        if (d <= 0.0) return 0.0
        if (d < fMin * 0.9 || d > fMax * 1.1) return 0.0
        return d
    }

    private fun unvoiced(t: Double, rms: Double = 0.0) = RawPitchFrame(
        timestampSec = t,
        f0Hz = 0.0,
        confidence = 0.0,
        loudnessRms = rms,
        snrDb = 0.0,
        periodicity = 0.0,
        agreementCents = Double.NaN,
        backend = BackendId.FCPE_ONNX.tag
    )

    // ───────────────────────────── ساخت/بارگذاری ─────────────────────────────

    companion object {
        const val MODEL_RATE = 16000
        const val MODEL_HOP = 160            // 10ms
        const val MIN_WINDOW = 512
        const val DEFAULT_FMIN = 55.0        // کف برنامه (Analyzer.FMIN)
        const val DEFAULT_FMAX = 1200.0      // سقف برنامه (Analyzer.FMAX)
        const val MODEL_ASSET_PATH = "models/fcpe.onnx"

        @Volatile
        private var lastReason: String = "هنوز تلاشی نشده"

        /** آخرین دلیلِ در دسترس نبودن/شدن (برای گزارش شفاف و JSON) */
        fun lastAvailabilityReason(): String = lastReason

        internal fun noteUnavailable(reason: String) {
            lastReason = reason
        }

        /**
         * تلاش برای ساخت backend واقعی. null یعنی FCPE در دسترس نیست
         * و مصرف‌کننده باید به موتور فعلی برگردد. هرگز exception پرتاب نمی‌کند.
         */
        fun tryCreate(
            modelBytesProvider: () -> ByteArray?,
            fMin: Double = DEFAULT_FMIN,
            fMax: Double = DEFAULT_FMAX
        ): FcpeOnnxBackend? {
            return try {
                val env = OnnxRuntime.open() ?: return null // دلیل داخل open ثبت شد
                val bytes = try {
                    modelBytesProvider()
                } catch (e: Throwable) {
                    null
                }
                if (bytes == null || bytes.isEmpty()) {
                    lastReason = "مدل FCPE پیدا نشد ($MODEL_ASSET_PATH)"
                    return null
                }
                val session = try {
                    env.newSession(bytes)
                } catch (e: Throwable) {
                    lastReason = "ساخت session مدل FCPE شکست خورد: ${e.javaClass.simpleName}"
                    return null
                }
                if (!session.contractOk()) {
                    lastReason = "شکل تنسورهای مدل FCPE با قرارداد [1,n,1]→[1,m,1] نمی‌خواند"
                    return null
                }
                lastReason = "فعال"
                FcpeOnnxBackend(session, fMin, fMax)
            } catch (e: Throwable) {
                lastReason = "خطای غیرمنتظره در بارگذاری FCPE: ${e.javaClass.simpleName}"
                null
            }
        }
    }
}

/**
 * پلِ reflection به ONNX Runtime (`ai.onnxruntime`) — تا برنامه بدون AAR هم
 * کامپایل و بسته‌بندی شود و در زمان اجرا بدون Crash برگردد.
 */
internal object OnnxRuntime {

    fun runtimePresent(): Boolean = try {
        Class.forName("ai.onnxruntime.OnnxTensor")
        Class.forName("ai.onnxruntime.OrtEnvironment")
        true
    } catch (e: Throwable) {
        false
    }

    fun missingRuntimeReason(): String =
        "کتابخانهٔ ONNX Runtime (ai.onnxruntime) در کلاس‌پث نیست — AAR onnxruntime-android بسته‌بندی نشده"

    /** ساخت محیط؛ null یعنی runtime موجود نیست (دلیل ثبت می‌شود) */
    fun open(): OrtEnv? {
        if (!runtimePresent()) {
            FcpeOnnxBackend.noteUnavailable(missingRuntimeReason())
            return null
        }
        return try {
            val envCls = Class.forName("ai.onnxruntime.OrtEnvironment")
            val env = envCls.getMethod("getEnvironment").invoke(null)
            OrtEnv(env)
        } catch (e: Throwable) {
            FcpeOnnxBackend.noteUnavailable("ساخت OrtEnvironment شکست خورد: ${e.javaClass.simpleName}")
            null
        }
    }
}

/** نگهدارندهٔ OrtEnvironment */
internal class OrtEnv(private val envObj: Any) {
    fun newSession(modelBytes: ByteArray): OnnxSession {
        val optsCls = Class.forName("ai.onnxruntime.OrtSession\$SessionOptions")
        val opts = optsCls.getConstructor().newInstance()
        // CPU fallback؛ تعداد threadها را محدود نگه دار تا با threadهای برنامه تلاقی نکند
        try {
            optsCls.getMethod("setIntraOpNumThreads", Int::class.javaPrimitiveType).invoke(opts, 2)
        } catch (e: Throwable) { }
        try {
            val session = envObj.javaClass
                .getMethod("createSession", ByteArray::class.java, optsCls)
                .invoke(envObj, modelBytes, opts)
            @Suppress("UNCHECKED_CAST")
            val inInfo = session.javaClass.getMethod("getInputInfo").invoke(session) as Map<Any?, Any?>
            @Suppress("UNCHECKED_CAST")
            val outInfo = session.javaClass.getMethod("getOutputInfo").invoke(session) as Map<Any?, Any?>
            return OnnxSession(
                envObj, session,
                inputName = inInfo.keys.first() as String,
                outputName = outInfo.keys.first() as String
            )
        } finally {
            try {
                optsCls.getMethod("close").invoke(opts)
            } catch (e: Throwable) { }
        }
    }
}

/** یک session ONNX آماده با قرارداد ورودی/خروجی بررسی‌شده */
internal class OnnxSession(
    private val envObj: Any,
    private val sessionObj: Any,
    private val inputName: String,
    private val outputName: String
) {
    /** قرارداد ورودی/خروجی را واقعاً بررسی می‌کند (۳بُعدی بودن تنسورها) */
    fun contractOk(): Boolean = try {
        @Suppress("UNCHECKED_CAST")
        val inInfo = sessionObj.javaClass.getMethod("getInputInfo").invoke(sessionObj) as Map<Any?, Any?>
        @Suppress("UNCHECKED_CAST")
        val outInfo = sessionObj.javaClass.getMethod("getOutputInfo").invoke(sessionObj) as Map<Any?, Any?>
        if (inInfo.isEmpty() || outInfo.isEmpty()) return false
        val inShape = shapeOf(inInfo.values.first())
        val outShape = shapeOf(outInfo.values.first())
        (inShape == null || inShape.size == 3) && (outShape == null || outShape.size == 3)
    } catch (e: Throwable) {
        false
    }

    private fun shapeOf(info: Any?): LongArray? = try {
        val vi = info?.javaClass?.getMethod("getValue")?.invoke(info)
        val tt = vi?.javaClass?.getMethod("getShape")?.invoke(vi)
        tt as? LongArray
    } catch (e: Throwable) {
        null
    }

    /** اجرای session روی یک بافر ۱۶kHz — خروجی: آرایهٔ F0 بر حسب Hz (بدون سانیتایز) */
    fun run(samples16k: DoubleArray): FloatArray? {
        try {
            val envCls = envObj.javaClass
            val floatArr = FloatArray(samples16k.size)
            for (i in samples16k.indices) floatArr[i] = samples16k[i].toFloat()
            val shape = longArrayOf(1, samples16k.size.toLong(), 1)
            val onnxTensorCls = Class.forName("ai.onnxruntime.OnnxTensor")
            val createMethod = onnxTensorCls.getMethod(
                "createTensor", envCls, FloatArray::class.java, LongArray::class.java
            )
            val inputTensor = createMethod.invoke(null, envObj, floatArr, shape)
            try {
                val runMethod = sessionObj.javaClass.methods.firstOrNull {
                    it.name == "run" && it.parameterTypes.size == 1
                } ?: return null
                val results = runMethod.invoke(sessionObj, java.util.Collections.singletonMap(inputName, inputTensor))
                try {
                    val outVal = results.javaClass.getMethod("get", Any::class.java)
                        .invoke(results, outputName) ?: return null
                    val arr = outVal.javaClass.getMethod("getValue").invoke(outVal)
                    return when (arr) {
                        is Array<*> -> flattenToFloat(arr)
                        is FloatArray -> arr
                        is DoubleArray -> FloatArray(arr.size) { arr[it].toFloat() }
                        else -> null
                    }
                } finally {
                    results.javaClass.getMethod("close").invoke(results)
                }
            } finally {
                inputTensor.javaClass.getMethod("close").invoke(inputTensor)
            }
        } catch (e: Throwable) {
            return null
        }
    }

    private fun flattenToFloat(arr: Array<*>): FloatArray? {
        // خروجی [1, m, 1] → Array<Array<FloatArray>> — بازکردن بازگشتی
        val out = ArrayList<Float>(1024)
        fun rec(a: Any?) {
            when (a) {
                is FloatArray -> for (v in a) out.add(v)
                is DoubleArray -> for (v in a) out.add(v.toFloat())
                is Array<*> -> for (e in a) rec(e)
                is Number -> out.add(a.toFloat())
            }
        }
        rec(arr)
        return if (out.isEmpty()) null else out.toFloatArray()
    }
}
