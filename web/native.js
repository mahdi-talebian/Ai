/* ============================================================================
   پل بومی اندروید  —  web/native.js
   ----------------------------------------------------------------------------
   این فایل فقط برای «اپ اندروید» (WebView) نوشته شده است:

     • وقتی صفحه داخل اپ باز می‌شود، شیء `window.NativeApp` توسط خودِ اپ به
       صفحه تزریق می‌شود. اگر این شیء نباشد (یعنی صفحه در مرورگر معمولی باز
       شده) این فایل بلافاصله و بی‌هیچ اثری خارج می‌شود؛ پس رفتار نسخهٔ وب
       هیچ تغییری نمی‌کند.

     • کارهایی که در اپ لازم‌اند و در مرورگر کار نمی‌کنند:
         ۱) ذخیرهٔ فایل روی گوشی (اپ/مرورگر نمی‌تواند blob را در WebView
            دانلود کند) → با MediaStore / DownloadManager اندروید
         ۲) ذخیرهٔ ویدیو در گالری → پوشهٔ Movies با ایندکس رسانه
         ۳) (حذف شده) قبلاً «آدرس سرور تحلیل» هم این‌جا تنظیم می‌شد؛ اپ
            کاملاً آفلاین است و هیچ سروری در کار نیست
   ========================================================================== */
(function () {
  'use strict';

  var NA = window.NativeApp;
  if (!NA) return;                 // مرورگر: هیچ کاری نمی‌کنیم

  /* ───────────────────────── ابزارهای کوچک ───────────────────────── */
  function toast(msg) {
    try { NA.toast(String(msg)); } catch (e) {}
  }

  function safeName(name) {
    var n = String(name || '').trim();
    if (!n) n = 'output';
    n = n.replace(/[\\/:*?"<>|\n\r\t]/g, '_');
    return n.slice(0, 120);
  }

  function b64OfFileReaderResult(res) {
    var s = String(res || '');
    var i = s.indexOf(',');
    return i >= 0 ? s.slice(i + 1) : s;
  }

  function extOfMime(mime) {
    var m = String(mime || '').toLowerCase();
    if (m.indexOf('video/mp4') === 0) return '.mp4';
    if (m.indexOf('video/webm') === 0) return '.webm';
    if (m.indexOf('video/3gpp') === 0) return '.3gp';
    if (m.indexOf('image/png') === 0) return '.png';
    if (m.indexOf('image/jpeg') === 0) return '.jpg';
    if (m.indexOf('image/webp') === 0) return '.webp';
    if (m.indexOf('audio/wav') === 0 || m.indexOf('audio/wave') === 0) return '.wav';
    if (m.indexOf('audio/mpeg') === 0) return '.mp3';
    return '';
  }

  function mimeOfName(name) {
    var n = String(name || '').toLowerCase();
    if (/\.mp4$/.test(n)) return 'video/mp4';
    if (/\.webm$/.test(n)) return 'video/webm';
    if (/\.3gp$/.test(n)) return 'video/3gpp';
    if (/\.png$/.test(n)) return 'image/png';
    if (/\.jpe?g$/.test(n)) return 'image/jpeg';
    if (/\.webp$/.test(n)) return 'image/webp';
    if (/\.wav$/.test(n)) return 'audio/wav';
    if (/\.mp3$/.test(n)) return 'audio/mpeg';
    if (/\.json$/.test(n)) return 'application/json';
    return '';
  }

  /* نوع و پسوند باید یکی باشند؛ وگرنه گالری فایل را نشان نمی‌دهد */
  function fitName(name, mime) {
    var n = safeName(name);
    var m = String(mime || '').trim() || mimeOfName(n) || 'application/octet-stream';
    var ext = extOfMime(m);
    if (ext && !new RegExp(ext.replace('.', '\\.') + '$', 'i').test(n)) {
      n = n.replace(/\.[a-z0-9]{1,5}$/i, '') + ext;
    }
    return { name: n, mime: m };
  }

  /* نتیجهٔ ذخیرهٔ بومی از سمت جاوا (window.__nativeSaveDone) */
  var saveCallbacks = {};

  window.__nativeSaveDone = function (token, ok, msg) {
    var cb = saveCallbacks[token];
    delete saveCallbacks[token];
    var text = String(msg || (ok ? '✅ در گالری ذخیره شد' : 'ذخیره ناموفق بود'));
    toast(text);
    if (typeof cb === 'function') { try { cb(!!ok, text); } catch (e) {} }
  };

  /* ذخیرهٔ یک Blob با ارسال تکه‌تکه (پایدار برای فایل‌های چند ده مگابایتی) */
  function saveBlob(blob, name, target, done) {
    var token;
    if (!blob || !blob.size) {
      toast('فایل خالی است');
      if (typeof done === 'function') done(false, 'فایل خالی است');
      return;
    }
    var fit = fitName(name, blob.type);
    try {
      token = (target === 'gallery' && typeof NA.beginSaveTo === 'function')
        ? NA.beginSaveTo(fit.name, fit.mime, 'gallery')
        : NA.beginSave(fit.name, fit.mime);
    } catch (e) {
      toast('شروع ذخیره ناموفق بود: ' + e);
      if (typeof done === 'function') done(false, 'شروع ذخیره ناموفق بود: ' + e);
      return;
    }
    if (!token) {
      toast('ذخیره ناموفق بود');
      if (typeof done === 'function') done(false, 'ذخیره ناموفق بود');
      return;
    }
    if (typeof done === 'function') saveCallbacks[token] = done;

    var CHUNK = 384 * 1024;            // بایت خام در هر تکه
    var off = 0;

    function step() {
      if (off >= blob.size) {
        try { NA.endSave(token); }
        catch (e) {
          toast('اتمام ذخیره ناموفق بود: ' + e);
          var cb = saveCallbacks[token]; delete saveCallbacks[token];
          if (typeof cb === 'function') cb(false, 'اتمام ذخیره ناموفق بود: ' + e);
        }
        return;
      }
      var part = blob.slice(off, Math.min(off + CHUNK, blob.size));
      off += CHUNK;
      var fr = new FileReader();
      fr.onload = function () {
        try {
          NA.appendSave(token, b64OfFileReaderResult(fr.result));
        } catch (e) {
          toast('نوشتن فایل ناموفق بود: ' + e);
          try { NA.abortSave(token); } catch (e2) {}
          return;
        }
        step();
      };
      fr.onerror = function () {
        try { NA.abortSave(token); } catch (e) {}
        toast('خواندن فایل ناموفق بود');
      };
      fr.readAsDataURL(part);
    }
    step();
  }

  /* ذخیره از یک آدرس: فایل سرور → دانلود مستقیم، blob/داده → تکه‌تکه */
  function isSameOrigin(url) {
    try { return new URL(url, location.href).origin === location.origin; }
    catch (e) { return false; }
  }

  function saveFromUrl(url, name, target, done) {
    if (!url) return;
    var isGallery = target === 'gallery';
    // فایل‌های خود اپ (بلاب محلی، data:، یا مسیر /api محلی) — بدون اینترنت
    if (/^(blob|data):/i.test(url) || isSameOrigin(url)) {
      try {
        fetch(url).then(function (r) {
          if (!r.ok) throw new Error('HTTP ' + r.status);
          return r.blob();
        })
          .then(function (b) {
            if (!b || !b.size) throw new Error('فایل خالی است');
            saveBlob(b, safeName(name) || 'output', target, done);
          })
          .catch(function (e) {
            var m = 'ذخیره ناموفق بود: ' + (e && e.message || e);
            toast(m);
            if (typeof done === 'function') done(false, m);
          });
      } catch (e) {
        var mm = 'ذخیره ناموفق بود: ' + (e && e.message || e);
        toast(mm);
        if (typeof done === 'function') done(false, mm);
      }
      return;
    }
    if (/^https?:/i.test(url)) {
      try {
        if (isGallery && typeof NA.saveToGallery === 'function') NA.saveToGallery(String(url), safeName(name));
        else NA.saveUrl(String(url), safeName(name));
        toast('در حال دانلود…');
        if (typeof done === 'function') done(true, 'در حال دانلود…');
      } catch (e) {
        toast('دانلود ناموفق بود: ' + e);
        if (typeof done === 'function') done(false, 'دانلود ناموفق بود: ' + e);
      }
      return;
    }
  }

  /* ───────────── آنچه صفحهٔ اصلی از آن استفاده می‌کند ───────────── */
  window.NativeBridge = {
    available: true,

    /** جایگزین triggerDownload در اپ */
    save: function (url, name) { saveFromUrl(url, name); },

    /**
     * جایگزین saveVideoToGallery در اپ: پوشهٔ Movies + ایندکس رسانه.
     * ویدیوی آفلاین یک blob محلی است؛ آن را می‌خوانیم و می‌نویسیم
     * (نوع video/* ⇒ همان پوشهٔ Movies و در نتیجه در گالری دیده می‌شود).
     */
    saveToGallery: function (url, name, done) {
      if (/^(blob|data):/i.test(url) || isSameOrigin(url)) {
        saveFromUrl(url, safeName(name), 'gallery', done);
        return;
      }
      try {
        NA.saveToGallery(String(url), safeName(name));
        if (typeof done === 'function') done(true, 'در حال دانلود…');
      } catch (e) {
        toast('ذخیره در گالری ناموفق بود: ' + e);
        if (typeof done === 'function') done(false, 'ذخیره در گالری ناموفق بود: ' + e);
      }
    },

    /** ذخیرهٔ مستقیم یک Blob در گالری (ویدیو → Movies، عکس → Pictures) */
    saveBlobToGallery: function (blob, name, done) {
      saveBlob(blob, safeName(name), 'gallery', done);
    },

    /** ذخیرهٔ Blob در پوشهٔ دانلودها */
    saveBlob: function (blob, name, done) {
      saveBlob(blob, safeName(name), 'downloads', done);
    }
  };

  /* ── هر لینک <a download> که خودِ صفحه مدیریت نکرده باشد ── */
  document.addEventListener('click', function (ev) {
    var a = ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;
    if (!a) return;
    var href = a.getAttribute('href');
    if (!href || href.charAt(0) === '#') return;
    var abs;
    try { abs = new URL(href, location.href).href; } catch (e) { return; }
    // لینک‌های همین اپ (مثل نمونهٔ آزمایشی) و blobهای صفحه
    if (abs.indexOf('blob:') === 0 || abs.indexOf('data:') === 0 ||
        abs.indexOf(location.origin + '/') === 0) {
      ev.preventDefault();
      ev.stopPropagation();
      var dlName = a.getAttribute('download') || 'output';
      var hint = a.getAttribute('data-gallery') || '';
      var mime = mimeOfName(dlName);
      // ویدیو و عکس → گالری؛ بقیه → پوشهٔ دانلودها
      var gallery = hint.indexOf('image') === 0 || hint.indexOf('video') === 0 ||
                    mime.indexOf('video/') === 0 || mime.indexOf('image/') === 0 ||
                    /\/video\b/.test(abs);
      saveFromUrl(abs, dlName, gallery ? 'gallery' : 'downloads');
    }
  }, true);

  /* ─────────────────── هیچ تنظیم سروری وجود ندارد ───────────────────
     این اپ کاملاً آفلاین است: تحلیل، ویدیو و تصویر همه روی خود گوشی انجام
     می‌شوند. قبلاً این‌جا پنجرهٔ «آدرس سرور تحلیل» بود؛ حذف شد تا هیچ مسیر
     سروری (و هیچ قطعه‌کد شبکه‌ای) داخل APK باقی نماند. */

  /* پل بومی فقط «ذخیره در گالری/دانلودها» است و همان لحظهٔ بالا آمدن فعال
     می‌شود؛ هیچ کار دیگری لازم نیست. */
})();
