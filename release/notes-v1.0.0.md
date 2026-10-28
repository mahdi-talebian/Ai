## 📱 اپ اندروید «ردِّ صدا» — نسخهٔ ۱٫۰٫۰

فایل قابل نصب: **`radde-seda.apk`** (~۱٫۶ مگابایت، امضاشده با v2+v3، اندروید ۷ / API 24 و بالاتر)

### ⬇ دانلود

* **دانلود مستقیم (تگ v1.0.0):**
  https://github.com/mahdi-talebian/Ai/raw/refs/tags/v1.0.0/release/radde-seda.apk

* صفحهٔ فایل در مخزن (دکمهٔ «Download raw file»):
  https://github.com/mahdi-talebian/Ai/blob/v1.0.0/release/radde-seda.apk

* نسخهٔ همیشه‌به‌روزِ شاخهٔ توسعه:
  https://github.com/mahdi-talebian/Ai/raw/arena/0d697dab-ai/release/radde-seda.apk

### نصب

1. `radde-seda.apk` را از لینک‌های بالا دانلود کن.
2. روی فایل بزن؛ اگر «نصب از منابع نامشخص» پرسید، برای همین فایل اجازه بده.

### اولین اجرا — آدرس سرور

تحلیل روی سرور خودت انجام می‌شود (Praat برای نت‌ها، ffmpeg برای ویدیو)، پس اپ یک بار آدرس سرور را می‌خواهد:

```
⚙ سرور → https://your-server.example → ذخیره و بارگذاری
```

مقدار در `localStorage` ذخیره می‌شود و هر بار خودکار خوانده می‌شود.
برای تب «اجرای زنده» آدرس **https** لازم است (WebSocket از صفحهٔ https به `ws://` بسته می‌شود).

### همهٔ قابلیت‌ها داخل اپ کار می‌کنند

| قابلیت | مسیر در اپ |
|---|---|
| انتخاب فایل صوتی/ویدیو از گوشی | انتخاب‌گر فایل اندروید |
| تحلیل: بالاترین/پایین‌ترین نت، گستره، نت‌به‌نت، کیفیت | همان API سرور |
| ذخیرهٔ فایل اصلی / تصویر تحلیل / گزارش JSON | MediaStore → `Downloads/` و `Pictures/` با نام اصلی |
| ساخت ویدیوی ردِّ صدا + ذخیره در گالری | دانلود بومی → `Movies/` با ایندکس رسانه |
| ذخیرهٔ ویدیو در دانلودها | DownloadManager با اعلان پیشرفت |
| اجرای زنده: لحظه‌ای و با ضبط | میکروفون WebView + WebSocket |
| ذخیرهٔ صدای هر ضبط (WAV)، تصویر و گزارش آن | پل بومی تکه‌تکه (پایدار برای فایل‌های چند مگابایتی) |

### ساخت از سورس (بدون Gradle / Android Studio)

```bash
bash android/tools/fetch_toolchain.sh     # یک‌بار: java + aapt2 + d8 + apksigner + ecj
bash android/build_apk.sh                 # → release/radde-seda-<version>.apk
DEFAULT_API=https://your-server.example bash android/build_apk.sh   # از اول تنظیم‌شده
```

جزئیات: [`android/README.md`](https://github.com/mahdi-talebian/Ai/blob/arena/0d697dab-ai/android/README.md)

### مشخصات بسته

```
package   : ir.raddeseda.app
version   : 1.0.0 (versionCode 1)
minSdk    : 24   targetSdk: 34
MD5       : d375edd5c7e553b2ccd2888b4311e78d
دسترسی‌ها : INTERNET, RECORD_AUDIO (+ WRITE_EXTERNAL_STORAGE فقط تا اندروید ۹)
```

> کلید امضا در `android/keystore/radde-seda.jks` نگهداری می‌شود؛ برای آپدیت‌های بعدی همان کلید لازم است تا نسخهٔ جدید روی نسخهٔ قبلی نصب شود.
