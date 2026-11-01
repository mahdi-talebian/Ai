# بررسی منبع، مجوز و قرارداد مدل FCPE — V1

- تاریخ بررسی: ۲۰۲۶-۱۰-۳۱
- وضعیت: **کد ادغام آماده است؛ بارگذاری مدل روی دستگاه به دو artifact نیاز دارد که در این محیط قابل دریافت نبود (جزئیات پایین).**
- قاعدهٔ رعایت‌شده: هیچ خروجی ساختگی/شبیه‌سازی‌شده‌ای برای FCPE تولید نشده است. تا زمان بارگذاری موفق، `PitchBackendSelector` به‌صورت خودکار و بدون Crash به موتور `ENHANCED` برمی‌گردد.

---

## ۱) منبع و مجوز

| مورد | مقدار |
|---|---|
| مخزن upstream | `CNChTu/FCPE` (TorchFCPE) — github.com/CNChTu/FCPE |
| مقاله | arXiv:2509.15140 — «FCPE: A Fast Context-based Pitch Estimation Model» (Luo et al., 2025) |
| مجوز کد و وزن‌ها | **MIT** (Copyright (c) 2023 CN_ChiTu) — از فایل LICENSE مخزن تأیید شد |
| بستهٔ pip | `torchfcpe==0.0.4` — wheel: `torchfcpe-0.0.4-py3-none-any.whl` (۴۰٬۲۲۲٬۰۱۲ بایت) |
| sha256 wheel | `f042c463d850d76c6f4899a0b84f0b694bb560adf05f4de951097a756d17472d` |
| وزن باندل‌شده | `torchfcpe/assets/fcpe_c_v001.pt` — ۴۳٬۳۶۳٬۲۶۰ بایت (همان فایل در `assets/` مخزن) |
| نوع وزن | PyTorch checkpoint (شامل `config_dict`) — **نه ONNX** |

توجه: مدل ارزیابی‌شده در مقاله روی مجموعه‌هایی مثل MIR-1K گزارش شده است؛ این به‌تنهایی اثبات دقت روی تلاوت قرآن نیست (طبق برنامهٔ پذیرش).

## ۲) قرارداد tensor (از کد upstream و checkpoint باندل‌شده استخراج شد)

از `torchfcpe/models_infer.py` (کلاس `InferCFNaiveMelPE.infer`) و config داخل checkpoint:

| مورد | مقدار |
|---|---|
| ورودی | `wav: float32 (B, n_sample, 1)` — **نرخ ۱۶٬۰۰۰ Hz** |
| mel (داخل گراف مدل) | `num_mels=128`, `n_fft=1070`, `win_size=1070`, `hop_size=160`, `fmin=46`, `fmax=8000` |
| گام خروجی | **160 نمونه = ۱۰ms** (`f0_target_length = n_sample // 160 + 1`) |
| خروجی | `f0: float32 (B, n_frames, 1)` بر حسب **Hz** |
| تصمیم V/UV | `threshold=0.006` (پیش‌فرض decoder داخلی) — **نه confidence کالیبره‌شده** |
| محدودهٔ مدل (`f0_min`, `f0_max`) | **32.70 Hz (C1)** تا **1975.50 Hz (B6)** |
| معماری | `CFNaiveMelPE` — `out_dims=360`, `hidden_dims=512`, `n_layers=6`, `n_heads=8` |
| decoder توصیه‌شده upstream | `local_argmax` |

قرارداد گزارش‌شدهٔ exportهای جامعهٔ ONNX (مطابق برنامهٔ پیاده‌سازی): ورودی `float32 [1, n_samples, 1]`، خروجی `float32 [1, n_frames, 1]` بر حسب Hz با همان گام ۱۰ms. **این قرارداد باید در زمان بارگذاری با فایل ONNX واقعی روی دستگاه تأیید شود** — `FcpeOnnxBackend` در زمان ساخت session، شکل تنسورها را واقعاً بررسی می‌کند و اگر نخواند مدل را نمی‌پذیرد.

## ۳) وضعیت ONNX در upstream

- کلاس `InferCFNaiveMelPEONNX` در `models_infer.py` با `raise NotImplementedError` یک **stub** است.
- یعنی **export رسمی ONNX از طرف نویسندگان وجود ندارد**؛ خروجی‌های ONNX موجود در جامعه منتشر شده‌اند و نباید «رسمی/تأییدشده» معرفی شوند.
- پیامد: مدل ONNX مورد استفاده برای اندروید باید با provenance مشخص (منبع، hash، نسخهٔ opset، سازگاری خروجی با مرجع PyTorch) تهیه و در گزارش ثبت شود.

## ۴) ادغام ONNX Runtime در build سفارشی (بدون Gradle)

| مورد | وضعیت |
|---|---|
| کتابخانه | `com.microsoft.onnxruntime:onnxruntime-android` (AAR) |
| منبع استاندارد | Maven Central / Google Maven |
| GitHub Releases مایکروسافت | فقط بسته‌های دسکتاپ؛ **AAR اندروید در Releases نیست** (بررسی شد: v1.22.0) |
| در دسترس بودن در محیط توسعهٔ فعلی | **خیر** — دسترسی شبکه فقط به github/npm/pypi محدود است و Maven در دسترس نیست |
| تصمیم فنی | کد `FcpeOnnxBackend` با **reflection** به `ai.onnxruntime.*` نوشته شده تا بدون AAR هم کامپایل و بسته‌بندی شود. در زمان اجرا اگر کلاس‌ها نباشند، `tryCreate` با دلیل ثبت‌شده null برمی‌گرداند و selector به ENHANCED برمی‌گردد — بدون Crash و بدون خروجی ساختگی |
| نحوهٔ فعال‌سازی واقعی | ۱) افزودن AAR به classpath کامپایل و dex در `build_apk.sh` (استخراج `classes.jar` از AAR + بسته‌بندی `jni/`های `arm64-v8a`/`armeabi-v7a` داخل APK) ۲) قراردادن فایل مدل واقعی در `android-qari/assets/models/fcpe.onnx` ۳) ساخت نسخهٔ جدید و اجرای تست روی دستگاه |
| مجوز اینترنت | **اضافه نشده** و نباید اضافه شود؛ دانلود مدل در زمان اجرا ممنوع است |

## ۵) آنچه در همین مخزن پیاده‌سازی شده

- `dsp/pitch/FcpeOnnxBackend.kt` — مسیر واقعی inference (ساخت تنسور `[1,n,1]`، اجرای session، استخراج `[1,m,1]`) + اعتبارسنجی خروجی (NaN/Infinity رد، صفر = unvoiced، خارج از محدوده = نامعتبر) + inference تکه‌ای با overlap برای فایل.
- `dsp/pitch/ModelResampler.kt` — تبدیل نرخ ضد-aliasing (sinc پنجره‌ای Kaiser) از هر نرخ منبع به ۱۶٬۰۰۰؛ **جایگزین interpolation خطی** (که برای مدل عصبی مناسب نیست). تست: تضعیف ≥۴۰dB برای تُن بالاتر از Nyquist مقصد.
- `dsp/pitch/PitchBackendSelector.kt` — fallback واقعی و ثبت دلیل.
- `MainActivity.LiveEngine` — بارگذاری مدل از `assets/models/fcpe.onnx` در صورت وجود؛ در غیر این صورت ENHANCED.

## ۶) محدودیت‌های گزارش‌شده (بدون ادعای اندازه‌گیری‌نشده)

- اجرای واقعی FCPE روی دستگاه Android در این چرخه **انجام نشده** (نبود AAR و مدل در محیط). بنچمارک دقت/تأخیر/مصرف FCPE **اندازه‌گیری نشده** و ادعا نمی‌شود.
- تا پیش از عبور از معیارهای پذیرش روی دادهٔ تلاوت واقعی، `ENHANCED` پیش‌فرض می‌ماند و FCPE فقط opt-in/آزمایشی است.
