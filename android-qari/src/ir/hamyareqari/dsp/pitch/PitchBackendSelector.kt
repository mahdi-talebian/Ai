package ir.hamyareqari.dsp.pitch

/**
 * انتخاب backend — با fallback واقعی و بدون Crash.
 *
 * راهبرد (طبق برنامهٔ پیاده‌سازی):
 *  • ENHANCED (موتور فعلی) پیش‌فرض و مسیر امن است.
 *  • FCPE فقط وقتی انتخاب می‌شود که صراحتاً experimental روشن باشد **و**
 *    ONNX Runtime + مدل واقعاً بارگذاری شوند.
 *  • هر شکستی در بارگذاری FCPE → بازگشت به ENHANCED با دلیل ثبت‌شده.
 *  • هیچ‌وقت خروجی ساختگی از FCPE تولید نمی‌شود.
 */
class PitchBackendSelector(
    /** آیا FCPE (آزمایشی) اصلاً بررسی شود؟ */
    private val preferFcpe: Boolean = false
) {

    sealed class Choice {
        /** backend انتخاب‌شده + آیا همان انتخاب اول بود یا fallback */
        data class Selected(
            val estimator: PitchEstimator,
            val backendId: BackendId,
            val isFallback: Boolean,
            val reason: String
        ) : Choice()
    }

    data class Report(
        val backendTag: String,
        val isFallback: Boolean,
        val reason: String
    )

    var lastReport: Report = Report(BackendId.ENHANCED.tag, true, "هنوز انتخابی نشده")
        private set

    /**
     * انتخاب backend.
     *
     * @param fcpeModelBytesProvider خواندن بایت‌های مدل ONNX (assets یا فایل)؛
     *        null یعنی مدل در دسترس نیست. resampler مدل بر اساس نرخ هر سیگنال
     *        داخل backend ساخته می‌شود (نیازی به نرخ سراسری نیست).
     */
    fun select(
        fcpeModelBytesProvider: (() -> ByteArray?)? = null
    ): Choice.Selected {
        // ۱) تلاش برای FCPE فقط وقتی صراحتاً خواسته شده
        if (preferFcpe && fcpeModelBytesProvider != null) {
            val fcpe = try {
                FcpeOnnxBackend.tryCreate(
                    fcpeModelBytesProvider,
                    EnhancedKotlinBackend.DEFAULT_FMIN,
                    EnhancedKotlinBackend.DEFAULT_FMAX
                )
            } catch (e: Throwable) {
                null
            }
            if (fcpe != null) {
                lastReport = Report(BackendId.FCPE_ONNX.tag, false, "FCPE بارگذاری شد")
                return Choice.Selected(fcpe, BackendId.FCPE_ONNX, false, lastReport.reason)
            }
            // fallback — دلیل را از FcpeOnnxBackend بردار
            val reason = "fallback به موتور فعلی: ${FcpeOnnxBackend.lastAvailabilityReason()}"
            val enhanced = EnhancedKotlinBackend()
            lastReport = Report(BackendId.ENHANCED.tag, true, reason)
            return Choice.Selected(enhanced, BackendId.ENHANCED, true, reason)
        }

        // ۲) حالت پیش‌فرض: موتور فعلی
        val reason = if (preferFcpe) "fallback: پروایدر مدل FCPE داده نشده" else "موتور فعلی (پیش‌فرض)"
        lastReport = Report(BackendId.ENHANCED.tag, preferFcpe, reason)
        return Choice.Selected(EnhancedKotlinBackend(), BackendId.ENHANCED, preferFcpe, reason)
    }

    companion object {
        /**
         * انتخاب امن برای برنامه: اگر FCPE در دسترس نبود، ENHANCED بدون هیچ
         * استثنایی برمی‌گردد. این تابع نقطهٔ ورود MainActivity است.
         */
        fun selectSafe(
            preferFcpe: Boolean,
            fcpeModelBytesProvider: (() -> ByteArray?)?
        ): PitchEstimator = try {
            PitchBackendSelector(preferFcpe).select(fcpeModelBytesProvider).estimator
        } catch (e: Throwable) {
            EnhancedKotlinBackend() // نهایت ایمنی: همیشه یک موتور سالم برمی‌گردد
        }
    }
}
