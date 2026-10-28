# ساخت APK اندروید «ردِّ صدا»

این پوشه یک پروژهٔ اندروید **بدون Gradle و بدون Android SDK** است:
کد به‌صورت `smali` نوشته می‌شود و خروجی با ابزارهای رسمی اندروید
(`aapt2`، `smali`، `apksigner`) بسته‌بندی و امضا می‌شود.

## آنچه اپ انجام می‌دهد

یک WebView سریع روی همان رابط وب «ردِّ صدا»:

* بارگذاری آدرس سرور تحلیل (پیش‌فرض: آدرس پیش‌نمایش زندهٔ سندباکس)
* **آدرس سرور قابل تغییر است**: در اولین اجرا دیالوگ باز می‌شود؛ بعداً با
  دکمهٔ بازگشت در صفحهٔ اصلی → «تغییر آدرس سرور» (در SharedPreferences می‌ماند)
* انتخاب فایل صوتی/ویدیویی برای آپلود (`onShowFileChooser`)
* ذخیرهٔ خروجی‌ها (ویدیو/تصویر/گزارش) با `DownloadManager` در پوشهٔ دانلودها
* پشتیبانی از کوکی، DOM storage و بارگذاری ترکیبی محتوا

> تحلیل صدا همچنان سمت سرور انجام می‌شود؛ APK فقط پوستهٔ نصب‌شدنی رابط وب است.

## ساخت

```bash
# پیش‌نیاز: ابزارها در TOOLS (پیش‌فرض /tmp) و android.jar
./build.sh
# خروجی: android/dist/raddeseda.apk
```

متغیرها:

| متغیر | پیش‌فرض | توضیح |
|---|---|---|
| `TOOLS` | `/tmp` | پوشهٔ `aapt2`، `smali.jar`، `apksigner.jar` |
| `ANDROID_JAR` | `/tmp/android.jar` | پلتفرم API 28 (حاوی `resources.arsc`) |
| `JAVA_HOME` | jdk4py | جاوا با `java` + `keytool` |

منابع ابزارها (همگی از کانال‌های مجاز این محیط):

* `aapt2` — داخل پکیج npm `aaptjs3` (`bin/x64/linux/aapt2`)
* `smali.jar` / `baksmali.jar` — ریپوی `TOSPlus/TPS` (`build/tools/apktool/`)
* `apksigner.jar` — ریپوی `warren-bank/print-apk-signature` (`libs/apksigner/`)
* `android.jar` (API 28) — ریپوی `Sable/android-platforms`
* جاوا — پکیج pypi `jdk4py` (JRE کامل + `keytool`)

## نصب روی گوشی

فایل `dist/raddeseda.apk` را روی دستگاه کپی و باز کنید
(«نصب از منابع ناشناخته» را برای فایل‌منیجر مربوطه فعال کنید).

* `minSdk 21` (اندروید ۵+) — `targetSdk 28`
* امضا: کلید debug محلی (v1+v2+v3) — برای توزیع رسمی باید با کلید خودتان
  دوباره امضا شود (`apksigner sign --ks your.keystore`)
* نسخه: `versionCode 1` / `versionName 1.0` (در `AndroidManifest.xml`)

## تغییر آدرس پیش‌فرض سرور

`src/com/mahditalebian/raddeseda/MainActivity.smali` → فیلد `DEFAULT_URL`.
