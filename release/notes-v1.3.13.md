# رَدِّ صدا ۱٫۳٫۱۳ — رفتارِ اپِ بومی، ذخیرهٔ واقعی در گالری، تحلیلِ یکسان

پایه همان ۱٫۳٫۸ است. پنج تغییرِ خواسته‌شده:

## ۱) هیچ زومی وجود ندارد (مثلِ اپِ بومی)
* `viewport`: `user-scalable=no`، `minimum-scale=1`، `maximum-scale=1`
* CSS: `touch-action:manipulation`، `overscroll-behavior:none`، حذفِ هایلایتِ لمس
* JS: بستنِ `gesturestart/change/end`، لمسِ دو‌انگشتی، دابل‌تپ (۳۲۰ms) و `Ctrl+wheel`
* اندروید: `setSupportZoom(false)`، `setBuiltInZoomControls(false)`،
  `setDisplayZoomControls(false)`، `setTextZoom(100)` — مستقل از تنظیمِ «اندازهٔ متن» گوشی

## ۲) ذخیره در گالری: حالا واقعاً تأیید می‌شود
قبلاً پیامِ «ذخیره شد» از مسیرِ پشتیبان هم می‌آمد (فایل در حافظهٔ خودِ اپ، نه گالری).
حالا:
* نوشتن در `MediaStore` با `IS_PENDING`، سپس `IS_PENDING=0`
* **تأییدِ ردیف**: بعد از نوشتن، اندازهٔ ردیف از خودِ MediaStore خوانده می‌شود؛
  اگر صفر/nothing بود، ردیف پاک و خطا گزارش می‌شود (پیامِ دروغ نمی‌دهیم)
* طولِ ویدیو در `MediaStore.Video.Media.DURATION` نوشته می‌شود (گالری طول را از این می‌خواند)
* پیامِ مسیرِ پشتیبان با «⚠️ در گالری ذخیره نشد…» شروع می‌شود، نه ✅

## ۳) تحلیلِ ویدیو = تحلیلِ تحلیل‌گر
ویدیو «بالاترین/پایین‌ترین/گستره/درجه‌ها» را از `report.range` می‌گیرد، نه از
محاسبهٔ دوبارهٔ خودش. قبلاً رندرر ویدیو گستره را از فهرستِ نت‌ها بازمی‌ساخت و
می‌توانست با عددِ اپ یکی نباشد (مثلاً «درجهٔ …» یا خط‌چینِ بالا/پایین).
تصویر تحلیل هم همان گستره را می‌گیرد.

## ۴) حذفِ برچسبِ «● در حال پخش»
از بوردِ ردِّ صدا و از CSS/JS مربوطه.

## ۵) تشخیصِ دستگاه (برای WebCodecs)
پنلِ «🔧 تشخیصِ دستگاه» زیرِ دکمهٔ ساخت ویدیو، دقیقاً می‌گوید کدام قطعه غایب است:
`VideoEncoder / AudioEncoder / VideoFrame / AudioData / mp4-muxer`، نسخهٔ Chrome/WebView،
پشتیبانیِ واقعیِ H.264 و AAC (`isConfigSupported`)، و پشتیبانیِ `MediaRecorder` از MP4.
اگر رندر کند بود، همان متن جواب می‌دهد که مشکل از WebView است یا از کد.

## آزمون‌ها
`web/tests/mobile_app_test.js` **۱۷ بررسی** (جدید) + بقیه:
degrees ۲۰ · trace_style ۲۳ · spike ۱۴ · sync ۱۵ · mp4_meta ۱۲ · strict ۲۵ ·
parallel ۹ · live ۳۳ · note_octave ۸ · video_fast ۲۱ · video ۱ · render ۱ ·
page ✅ · native ۱۱ · apk ۲۲

## بستهٔ نصبی
`release/radde-seda-1.3.13.apk` — versionCode ۲۵ · ۱٬۷۸۰٬۰۴۲ بایت ·
MD5 `b38c88e9b93d3d88e60eaa2ba558e1da` · کاملاً آفلاین · اندروید ۷+
