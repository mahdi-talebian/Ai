# 📱 اپ اندروید «ردِّ صدا» — ساخت و نصب

این پوشه، همان رابط وب (`web/`) را به یک APK قابل نصب تبدیل می‌کند؛ **بدون
Gradle، بدون Android Studio و بدون Android SDK کامل**.

```
release/radde-seda-1.0.0.apk      ← فایل آمادهٔ نصب (امضاشده)
```

---

## نصب روی گوشی

1. فایل `release/radde-seda-1.0.0.apk` را روی گوشی کپی کنید (کابل، تلگرام
   «Saved Messages»، Google Drive… هر چیزی).
2. روی فایل بزنید؛ اگر پیام «نصب از منابع نامشخص» آمد، برای همین برنامه
   اجازه بدهید.
3. برای جایگزین‌کردن نسخهٔ قبلی، باید همان کلید امضا (`android/keystore`) باشد
   — پس این پوشه را نگه دارید.

اندروید ۷ (API 24) و بالاتر.

---

## اولین اجرا: آدرس سرور تحلیل

در اپ، صفحه از حافظهٔ خود گوشی باز می‌شود (`https://appassets.local`) و
**تحلیل روی سرور انجام می‌شود**. پس اولین بار باید آدرس همان سروری را بدهید
که نسخهٔ وب روی آن اجراست:

```
⚙ سرور  →  https://your-server.com  →  ذخیره و بارگذاری
```

* پیشنهاد: https (با http هم آپلود/تحلیل کار می‌کند، ولی «اجرای زنده» —
  که از WebSocket استفاده می‌کند — به https نیاز دارد).
* این مقدار در `localStorage` همان اپ ذخیره می‌شود و هر بار خودکار خوانده
  می‌شود. با دکمهٔ «⚙ سرور» هر وقت خواستید عوضش کنید.
* اگر سرور را جابه‌جا کردید، فقط همین آدرس را عوض کنید؛ نیازی به APK جدید نیست.

### می‌خواهید از اول تنظیم‌شده باشد؟

موقع ساخت، آدرس پیش‌فرض را بدهید تا کاربر (یا خودتان) لازم نباشد چیزی وارد کند:

```bash
DEFAULT_API=https://your-server.example bash android/build_apk.sh
```

این مقدار داخل `BuildConfig.DEFAULT_API` جایگزین می‌شود و در `index.html`
تزریق می‌گردد؛ فقط اگر کاربر خودش آدرسی ذخیره نکرده باشد اعمال می‌شود، یعنی
انتخاب دستی کاربر همیشه اولویت دارد.

---

## چه چیزهایی در اپ کار می‌کند؟

| قابلیت | مسیر در اپ |
|---|---|
| انتخاب فایل صوتی/ویدیو از گوشی | `onShowFileChooser` → انتخاب‌گر فایل اندروید |
| آپلود + تحلیل + نتایج | درخواست به همان API سرور (CORS باز است) |
| ذخیرهٔ فایل اصلی / تصویر / گزارش JSON | MediaStore → `Downloads/` و `Pictures/` |
| ساخت ویدیوی ردِّ صدا | دکمهٔ 🎬 (روی سرور، با ffmpeg) |
| ذخیرهٔ ویدیو در گالری | دانلود بومی → `Movies/` + ایندکس رسانه (در گالری دیده می‌شود) |
| ذخیرهٔ ویدیو در پوشهٔ دانلودها | DownloadManager → `Downloads/` با اعلان پیشرفت |
| اجرای زنده — لحظه‌ای / با ضبط | میکروفون WebView (`onPermissionRequest`) + WebSocket به سرور |
| ذخیرهٔ صدای ضبط‌شده (WAV) و گزارش هر ضبط | پل بومی، ارسال تکه‌تکهٔ base64 (پایدار برای فایل‌های چند مگابایتی) |
| نمونهٔ آزمایشی | همان فایل `demo_bayati.wav` از assets |

نکته‌ها:

* WebView خودش «blob» را دانلود نمی‌کند؛ برای همین `web/native.js` مسیر
  ذخیره را به کد بومی می‌سپارد. در مرورگر این فایل **بی‌اثر** است و رفتار
  نسخهٔ وب دقیقاً همان قبلی می‌ماند.
* Service Worker (PWA) در اپ لازم نیست؛ ثبتش بی‌صدا نادیده گرفته می‌شود.
* هیچ داده‌ای از صدای شما جایی جز همان سرور خودتان نمی‌رود.
* دسترسی‌ها: `INTERNET`، `RECORD_AUDIO`، و فقط تا اندروید ۹
  `WRITE_EXTERNAL_STORAGE` (از اندروید ۱۰ به بعد MediaStore، بدون مجوز).

---

## ساخت مجدد APK

```bash
bash android/tools/fetch_toolchain.sh     # یک‌بار: java + aapt2 + d8 + apksigner + ecj
bash android/build_apk.sh                 # خروجی: release/radde-seda-<version>.apk
```

متغیرهای اختیاری:

```bash
VERSION_NAME=1.0.1 VERSION_CODE=2 bash android/build_apk.sh
ANDROID_TOOLS=/path/to/tools JAVA_HOME=/path/to/jdk bash android/build_apk.sh
```

زنجیرهٔ ابزار (همه از منابع عمومی، بدون Google Maven):

| ابزار | منبع |
|---|---|
| `java` (JRE) | بستهٔ پایتونی `jdk4py` (pypi) |
| `aapt2` (linux) | بستهٔ نُدِ `aaptjs3` (npm) |
| `android.jar` (API 34)، `d8.jar`، `apksigner.jar`، `ecj` | بستهٔ نُدِ `@drxiaozhi/minapk` (npm) |
| ترازبندی ۴ بایتی | `android/tools/zipalign.py` (پیاده‌سازی پایتونی `zipalign`) |

مراحل ساخت در `build_apk.sh`:

```
web/  →  android/assets/web/          (کپی خودکار)
res/  →  aapt2 compile + link         (منابع + مانیفست + assets)
java/ →  ecj (جایگزین javac) → d8     (classes.dex)
        → بستن در APK → zipalign.py → apksigner (v2+v3)
```

---

## ساختار

```
android/
  AndroidManifest.xml          مجوزها، مانیفست اپ
  build_apk.sh                 اسکریپت ساخت کامل
  tools/fetch_toolchain.sh     گرفتن ابزارها
  tools/zipalign.py            هم‌ترازی APK (پیش‌نیاز اندروید ۱۱+)
  java/ir/raddeseda/app/MainActivity.java
  res/                         آیکون‌ها، تم، رشته‌ها
  keystore/radde-seda.jks      کلید امضا (رمز: raddeseda / alias: raddeseda)
  assets/                      در زمان ساخت از web/ پر می‌شود (در گیت نیست)
```

> کلید امضا داخل مخزن است تا نسخه‌های بعدی روی همین اپ نصب شوند. اگر می‌خواهید
> اپ را جایی منتشر کنید، کلید تازه بسازید و `KS`/`KS_PASS` را در
> `build_apk.sh` عوض کنید.
