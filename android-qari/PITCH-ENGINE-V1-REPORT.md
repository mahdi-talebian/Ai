# گزارش تحویل — موتور تحلیل Pitch تلاوت قرآن V1 (Android)

- تاریخ: ۲۰۲۶-۱۰-۳۱
- مخزن: `mahdi-talebian/Ai` — شاخهٔ کاری این نشست: `arena/052c93c3-ai` (منشعب از `feature/android-quran-pitch-engine-v1` @ `034daac`)
- برنچ‌های `Android`، `main` و تاریخچهٔ Git **بدون تغییر** ماندند (فقط commit افزودنی روی شاخهٔ کاری).
- نسخهٔ آزمایشی: `1.2.0-pitchv1` / versionCode `9`

---

## ۱) چه فایل‌هایی تغییر کردند؟

**افزوده (پکیج جدید `ir.hamyareqari.dsp.pitch`):**

| فایل | نقش |
|---|---|
| `dsp/pitch/PitchContracts.kt` | قرارداد مشترک: `PitchSample`، `RawPitchFrame`، `FrameStatus`، `BackendId`، `PitchEstimator` |
| `dsp/pitch/EnhancedKotlinBackend.kt` | آداپتر موتور فعلی (YIN+MPM فایل / YinTracker+quickOctaveFix زنده) |
| `dsp/pitch/PitchQualityGate.kt` | گیت چندشاهدی + `NoiseTracker` + کف نویز batch |
| `dsp/pitch/PitchStabilizer.kt` | محافظت نمودار (اکتاو/جهش/median/هموارسازی تطبیقی/Hold/شکاف) |
| `dsp/pitch/PitchBackendSelector.kt` | انتخاب backend + fallback واقعی + ثبت دلیل |
| `dsp/pitch/FcpeOnnxBackend.kt` | اجرای واقعی FCPE با ONNX Runtime (reflection) + اعتبارسنجی خروجی |
| `dsp/pitch/ModelResampler.kt` | تبدیل نرخ ضد-aliasing (sinc+Kaiser) به ۱۶kHz + نسخهٔ streaming |
| `dsp/pitch/PitchSampleStream.kt` | ترکیب gate+stabilizer → `PitchSample` (فایل و زنده از یک مسیر) |
| `test/sim/PitchV1Test.kt` | ۱۲ سناریو با Ground Truth مستقل + معیارهای پذیرش |
| `test/sim/PipelineTest.kt` | ۳۴ تست واحد (resampler/gate/stabilizer/fallback/قرارداد) |
| `test/baseline/*` | baseline وضعیت شروع (git + بنچمارک) |
| `docs/FCPE_V1_PROVENANCE.md` | provenance، مجوز، قرارداد tensor مدل و تصمیم فنی ONNX |
| `release/hamyar-qari-1.2.0-pitchv1.apk` | APK آزمایشی (مسیر مستقل؛ APKهای قبلی دست‌نخورده) |

**تغییریافته (حداقلی، بدون حذف قابلیت):**

| فایل | تغییر |
|---|---|
| `dsp/Analyzer.kt` | پارامتر اختیاری `onCurve` در `analyzeSignal` (سازگار با همهٔ صداهای قبلی) |
| `dsp/Json.kt` | بخش افزودنی `pitch_samples` در خروجی JSON (کلیدهای قبلی دست‌نخورده) |
| `app/MainActivity.kt` | اتصال فایل/ضبط/زنده به قرارداد مشترک؛ `LiveEngine` به backend selector متصل شد؛ VERSION |
| `app/ChartView.kt` | تفکیک بصری وضعیت‌ها: سگمنت‌های ESTIMATED با خط‌چین کم‌رنگ |
| `test/sim/run_tests.sh`، `run_bench.sh` | افزودن کامپایل/اجرای تست‌های جدید |
| `AndroidManifest.xml` | فقط نسخه (versionCode 9 / versionName 1.2.0-pitchv1) — مجوزها بدون تغییر |

**دست‌نخورده:** `PitchEngine.kt`، `DualEstimator.kt`، `Yin.kt`، `Harmonics.kt`، `Denoise.kt`، `Segment.kt`، `AudioIo.kt`، `PitchBench.kt`، تمام تست‌های قبلی، keystore، `Engine.LEGACY` و `Engine.ENHANCED`.

## ۲) چه کلاس‌هایی اضافه شدند؟

`PitchSample` · `RawPitchFrame` · `FrameStatus` · `BackendId` · `PitchEstimator` · `EnhancedKotlinBackend` · `PitchQualityGate` (+ `NoiseTracker`, `Evidence`, `Verdict`) · `PitchStabilizer` (+ `FrameIn`, `FrameOut`) · `PitchBackendSelector` · `FcpeOnnxBackend` · `OnnxRuntime`/`OrtEnv`/`OnnxSession` (پل reflection) · `ModelResampler` · `StreamingSincResampler` · `PitchSampleStream` · `PitchV1Test` · `PipelineTest`

## ۳) FCPE واقعاً اجرا شد یا متوقف شد؟

**در این چرخه متوقف شد — اما نه به دلیل نقص کد؛ به دلیل دو artifact در دسترس‌نبوده.** آنچه واقعاً انجام شد:

- ✅ قرارداد واقعی مدل از کد upstream و checkpoint باندل‌شده تأیید شد (ورودی `[1,n,1]` @16kHz، hop=160، خروجی `[1,m,1]` Hz، محدودهٔ 32.7–1975.5Hz — جزئیات در `docs/FCPE_V1_PROVENANCE.md`).
- ✅ مجوز MIT و provenance وزن (43,363,260 بایت) با hash ثبت شد.
- ✅ کد اجرای واقعی ONNX (`FcpeOnnxBackend`) نوشته و کامپایل شد: ساخت تنسور، اجرای session، اعتبارسنجی NaN/صفر/محدوده، inference تکه‌ای با overlap.
- ✅ resampler مستقل ۱۶kHz (ضد-aliasing، آزمایش‌شده: تضعیف −۶۱.۵dB آلیاسینگ) جایگزین interpolation خطی شد.
- ✅ fallback واقعی تست شد: بدون runtime/مدل → `tryCreate` برمی‌گردد null با دلیل ثبت‌شده → selector به ENHANCED برمی‌گردد — **بدون Crash، بدون خروجی ساختگی**.
- ❌ اجرای inference روی دستگاه انجام نشد: (۱) کتابخانهٔ `onnxruntime-android` فقط از Maven منتشر می‌شود و در محیط توسعهٔ فعلی (دسترسی شبکه محدود به github/npm/pypi) قابل دریافت نبود؛ (۲) export رسمی ONNX از upstream وجود ندارد (`InferCFNaiveMelPEONNX` یک stub است) و فایل ONNX جامعه‌ای قابل تأیید در دسترس نبود.

**تصمیم فنی مستند:** کد با reflection نوشته شده تا بدون AAR هم بسته‌بندی شود؛ فعال‌سازی واقعی فقط با افزودن AAR + قراردادن `assets/models/fcpe.onnx` (مراحل دقیق در سند provenance). تا آن زمان ENHANCED پیش‌فرض است.

## ۴) موتور قدیمی چه وضعیتی دارد؟

**کاملاً سالم و پیش‌فرض.** بنچمارک قبل و بعد از تغییرات عیناً یکسان است:

| مسیر | میانهٔ خطا | بدترین p95 | اکتاو | فاحش | پذیرش اشتباه نویز |
|---|---|---|---|---|---|
| `Engine.LEGACY` | 3.57c | 157.3c | 0.2% | 0.6% | 26.1% |
| `Engine.ENHANCED` | 2.28c | 24.5c | 0.1% | 0.2% | 4.0% |

هر دو مسیر قابل اجرا و بدون رگرسیون‌اند («✅ هیچ رگرسیونی نسبت به مسیر فعلی دیده نشد»). موتور به‌عنوان backend `EnhancedKotlinBackend` پشت رابط مشترک نشسته و هیچ کدی از آن حذف/تغییر نکرده است.

## ۵) نتایج تست دقت (Ground Truth مستقل، سطح نمونه)

تست‌های جدید (`PitchV1Test`) با سیگنال‌های دارای truth نمونه‌به‌نمونه:

| سناریو | میانهٔ سنت | P95 سنت | فاحش (GPE) | اکتاو (OER) | رد اشتباه | پذیرش اشتباه |
|---|---|---|---|---|---|---|
| ۱. تلاوت تمیز | **3.98c** | 7.66c | 0.34% | 0.00% | 0.00% | — |
| ۲. ضعیف/قوی (گین ۱ و ۰٫۰۶) | 4.25c | — | — | — | — | اختلاف F0 بین دو گین: **0.00c** |
| ۳. جهش ناگهانی شدت | 4.23c | 6.50c | — | **0.00%** | — | — |
| ۴. تشویق | — | — | — | — | — | **0.00%** (0/994 فریم) |
| ۵. همهمهٔ چندنفره | — | — | — | — | — | 26.36% (سخت‌ترین سناریو) |
| ۶. نویز سفید / باد | — | — | — | — | — | 0.00% / 0.00% |
| ۷. سکوت مطلق / هیس ضعیف | — | — | — | — | — | 0 فریم خروجی |
| ۸. تحریر سریع (۱۱Hz، ±۹۰c) | 8.46c | — | — | 0.00% | — | **حفظ تحریر 0.65** |
| ۹. تغییر سریع نت (۱۰۰ms) | **2.97c** | 48.65c | — | — | **0.00%** | — |
| ۱۰. خطای اکتاو تزریقی | 2.17c (میانگین) | — | — | **0 باقی‌مانده** | — | — |
| ۱۱. فایل بلند ۹۰s | 3.25c | — | — | — | — | — |
| ۱۲. زنده (۲۰۰۰ فریم) | — | — | — | — | — | P50=0.12ms · P95=0.23ms* |

\* روی CPU این محیط؛ اندازه‌گیری روی گوشی انجام نشده (بند ۷).

- جدایی Loudness از Pitch: اختلاف F0 بین ورودی‌های هم‌شکل با گین متفاوت در مسیر فایل (نرمال‌شده) و مسیر خام (بدون preprocess) **0.00 سنت** — بلندی صدا به‌تنهایی F0 را جابه‌جا نمی‌کند.
- تست‌های واحد (`PipelineTest`) ۳۴/۳۴ سبز: ضد-aliasing resampler، گیت (رد نویز/تشویق، خنثی‌سازی SNR در دامنهٔ دینامیکی کم)، stabilizer (حذف جهش تک‌فریمی با حفظ تغییر نت واقعی، شکاف ۳۰۰ms پرنشدنی، شکاف ۳۰ms با ESTIMATED، Hold با سقف)، fallback FCPE، قرارداد `PitchSample`.
- تست‌های قبلی **همه سبز** (بدون تغییر): VideoTest (۴ پروفایل + PSNR)، ZoomFollowTest (خطای پیکسلی ≤1.0px)، LongRun، PitchBench بدون رگرسیون.

**محدودیت صادقانه:** همهٔ نمونه‌ها مصنوعی‌اند (تُن هارمونیک با vibrato/تحریر واقع‌گرایانه + نویز/تشویق/همهمهٔ سنتزشده). این‌ها رگرسیون و خطای نسبی را می‌سنجند؛ **ادعای دقت مطلق روی تلاوت واقعی قاریان نیازمند مجموعهٔ صوتی برچسب‌خوردهٔ انسانی است که در این محیط در دسترس نبود.**

## ۶) آیا تشویق باعث پرش کاذب نمودار می‌شود؟

**خیر.** در سناریوی تشویق (ضربه‌های تصادفی ۵ ثانیه‌ای) **۰ فریم از ۹۹۴ فریم** خروجی pitch گرفت (پذیرش اشتباه 0.00%). نویز سفید و باد هم 0.00%. همهمهٔ چندنفره (که ذاتاً تُنال است) 26.36% پذیرش دارد — نمودار را نمی‌پوشاند اما صفر هم نیست؛ برای V2 قابل بهبود است. در سطح واحد هم تست شده: فریم‌های پرنویز با تناوب پایین/پخشی طیفی بالا هرگز VALID نمی‌شوند.

## ۷) عملکرد روی دستگاه واقعی؟

**اندازه‌گیری‌نشده** — در این محیط ADB/دستگاه اندروید در دسترس نبود. آنچه اندازه‌گیری شده (روی CPU سرور): تأخیر پردازش هر فریم زنده P50=0.12ms / P95=0.23ms (بودجهٔ ۱۲۰ms حلقهٔ زنده)، تحلیل فایل ۹۰ ثانیه‌ای در 751ms با ≈35MB رم. مصرف باتری و رفتار گوشی‌های ضعیف/قوی **ادعا نمی‌شود** و در برنامهٔ تست نسخهٔ بعد است.

## ۸) APK کجا ساخته شد؟

- مسیر: `release/hamyar-qari-1.2.0-pitchv1.apk` (مسیر مستقل؛ `hamyar-qari-final.apk` و `hamyar-qari-1.1.3.apk` دست‌نخورده)
- حجم: 2,401,335 بایت · MD5: `e57cdc1ad66920a38538f1bd699517a8` · versionCode 9 / versionName 1.2.0-pitchv1
- ساخت با `android-qari/build_apk.sh` (زنجیرهٔ رسمی مخزن)؛ `applicationId=ir.hamyareqari.app` و کلید امضا **همان قبلی** (گواهی CN=Hamyar Qari)
- بدون مجوز اینترنت (اعتبارسنجی شد)؛ کلاس‌های موتور V1 داخل dex تأیید شدند
- نصب روی دستگاه واقعی: انجام نشد (بند ۷)

## ۹) Commit SHA

```
5e6016e build(release): APK آزمایشی موتور Pitch V1 — versionCode 9 / 1.2.0-pitchv1
138bb52 feat(app): اتصال فایل/میکروفن به قرارداد مشترک V1 + تفکیک وضعیت‌ها در نمودار
f23efdd docs(fcpe): provenance و قرارداد واقعی مدل FCPE و تصمیم فنی ONNX
c3c65ce feat(pitch): موتور V1 — قرارداد مشترک، Quality Gate، Stabilizer، Selector، FCPE/ONNX
4fa7b14 test(baseline): ثبت baseline تست‌ها و بنچمارک موتور فعلی
```

(پایه: `034daac` روی `feature/android-quran-pitch-engine-v1`)

## ۱۰) برای نسخهٔ بعد باقی مانده است

1. **اجرای واقعی FCPE روی دستگاه**: تهیهٔ AAR `onnxruntime-android` (از Maven) + فایل ONNX مدل با provenance مشخص، بسته‌بندی در build، سپس اجرای همان ۱۲ سناریو و مقایسهٔ سر به سر با ENHANCED (تصمیم fusion باید candidate-based و quality-aware بماند؛ میانگین سادهٔ F0 ممنوع).
2. **مجموعهٔ تلاوت واقعی برچسب‌خورده**: ارزیابی با annotation انسانی/ابزار مرجع (median/P95 cent، GPE، OER، FA/FR، حفظ تحریر) — بدون آن ادعای دقت مطلق ممکن نیست.
3. **تست روی گوشی واقعی** (ضعیف/قوی): تأخیر end-to-end، CPU، RAM، باتری، رفتار میکروفن واقعی.
4. **بهبود رد همهمه**: کاهش پذیرش 26.36% همهمه با شواهد جداسازی منبع (خارج از دامنهٔ V1).
5. **کالیبراسیون qualityScore** روی دادهٔ واقعی (در حال حاضر ترکیب وزنی با آستانه‌های مبتنی بر رفتار موتور فعلی است).
6. **HybridPitchBackend** در صورت اثبات برتری FCPE — با شواهد مستقل، نه میانگین‌گیری کورکورانه.
