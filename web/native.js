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
         ۳) تنظیم «آدرس سرور تحلیل» — چون در اپ، صفحه از آدرس داخلی خودش
            (https://appassets.local) باز می‌شود و سرور تحلیل جای دیگری است
   ========================================================================== */
(function () {
  'use strict';

  var NA = window.NativeApp;
  if (!NA) return;                 // مرورگر: هیچ کاری نمی‌کنیم

  var LS_KEY = 'api_base';

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

  /* ذخیرهٔ یک Blob با ارسال تکه‌تکه (پایدار برای فایل‌های چند ده مگابایتی) */
  function saveBlob(blob, name) {
    var token;
    try {
      token = NA.beginSave(safeName(name), blob.type || 'application/octet-stream');
    } catch (e) {
      toast('شروع ذخیره ناموفق بود: ' + e);
      return;
    }
    if (!token) { toast('ذخیره ناموفق بود'); return; }

    var CHUNK = 384 * 1024;            // بایت خام در هر تکه
    var off = 0;

    function step() {
      if (off >= blob.size) {
        try { NA.endSave(token); } catch (e) { toast('اتمام ذخیره ناموفق بود: ' + e); }
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

  function saveFromUrl(url, name) {
    if (!url) return;
    // فایل‌های خود اپ (بلاب محلی، data:، یا مسیر /api محلی) — بدون اینترنت
    if (/^(blob|data):/i.test(url) || isSameOrigin(url)) {
      try {
        fetch(url).then(function (r) { return r.blob(); })
          .then(function (b) {
            if (!b) { toast('فایل پیدا نشد'); return; }
            saveBlob(b, safeName(name) || 'output');
          })
          .catch(function (e) { toast('ذخیره ناموفق بود: ' + (e && e.message || e)); });
      } catch (e) {
        toast('ذخیره ناموفق بود: ' + (e && e.message || e));
      }
      return;
    }
    if (/^https?:/i.test(url)) {
      try {
        NA.saveUrl(String(url), safeName(name));
        toast('در حال دانلود…');
      } catch (e) { toast('دانلود ناموفق بود: ' + e); }
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
    saveToGallery: function (url, name) {
      if (/^(blob|data):/i.test(url) || isSameOrigin(url)) {
        saveFromUrl(url, safeName(name));
        return;
      }
      try { NA.saveToGallery(String(url), safeName(name)); }
      catch (e) { toast('ذخیره در گالری ناموفق بود: ' + e); }
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
      saveFromUrl(abs, a.getAttribute('download') || 'output');
    }
  }, true);

  /* ─────────────────── تنظیم آدرس سرور تحلیل ─────────────────── */
  var CSS = ''
    + '#nativeCfgBtn{float:left;margin-top:-2px;background:rgba(255,255,255,.07);'
    + 'border:1px solid rgba(255,255,255,.14);color:#e8eefc;border-radius:12px;'
    + 'padding:7px 11px;font:inherit;font-size:.82rem;cursor:pointer}'
    + '#nativeCfgBtn:active{transform:scale(.96)}'
    + '#nativeMask{position:fixed;inset:0;background:rgba(4,8,18,.78);z-index:9998;'
    + 'display:none;align-items:center;justify-content:center;padding:18px;'
    + 'backdrop-filter:blur(3px)}'
    + '#nativeMask.on{display:flex}'
    + '#nativeBox{width:100%;max-width:430px;background:#111b31;border:1px solid rgba(212,175,55,.35);'
    + 'border-radius:18px;padding:16px;color:#e8eefc;box-shadow:0 20px 60px rgba(0,0,0,.6);'
    + 'font-family:inherit;line-height:1.9}'
    + '#nativeBox h3{margin:0 0 6px;font-size:1.02rem;color:#f0d68a}'
    + '#nativeBox p{margin:0 0 10px;font-size:.82rem;color:#a9b6cf}'
    + '#nativeBox input{width:100%;box-sizing:border-box;background:#0b1224;color:#e8eefc;'
    + 'border:1px solid rgba(255,255,255,.18);border-radius:12px;padding:12px;'
    + 'font-family:ui-monospace,Consolas,monospace;font-size:.86rem;direction:ltr;text-align:left}'
    + '#nativeBox .row{display:flex;gap:8px;margin-top:12px}'
    + '#nativeBox button{flex:1;padding:12px;border-radius:12px;border:0;font:inherit;'
    + 'font-size:.9rem;cursor:pointer}'
    + '#nativeSave{background:linear-gradient(135deg,#d4af37,#f0d68a);color:#1a1206;font-weight:700}'
    + '#nativeCancel{background:rgba(255,255,255,.08);color:#cfd9ee;border:1px solid rgba(255,255,255,.14)}'
    + '#nativeErr{color:#ffb3b3;font-size:.8rem;margin-top:8px;min-height:1em}';

  function injectSettings() {
    var st = document.createElement('style');
    st.textContent = CSS;
    document.head.appendChild(st);

    var header = document.querySelector('header');
    var wrap = document.querySelector('.wrap') || document.body;

    var btn = document.createElement('button');
    btn.id = 'nativeCfgBtn';
    btn.type = 'button';
    btn.title = 'آدرس سرور تحلیل';
    btn.textContent = '⚙ سرور';
    (header || wrap).insertBefore(btn, (header || wrap).firstChild);

    var mask = document.createElement('div');
    mask.id = 'nativeMask';
    mask.innerHTML = ''
      + '<div id="nativeBox">'
      + '  <h3>آدرس سرور تحلیل</h3>'
      + '  <p>در اپ، صفحه از حافظهٔ خود گوشی باز می‌شود و تحلیل روی سرور انجام می‌شود؛ '
      + 'آدرس همان سروری را بگذار که نسخهٔ وب روی آن اجراست. برای «ضبط زنده» آدرس '
      + '<b>https</b> لازم است.</p>'
      + '  <input id="nativeApiInput" type="url" inputmode="url" autocomplete="off" '
      + 'spellcheck="false" placeholder="https://your-server.com" />'
      + '  <div id="nativeErr"></div>'
      + '  <div class="row">'
      + '    <button id="nativeSave" type="button">ذخیره و بارگذاری</button>'
      + '    <button id="nativeCancel" type="button">انصراف</button>'
      + '  </div>'
      + '</div>';
    document.body.appendChild(mask);

    var input = mask.querySelector('#nativeApiInput');
    var err = mask.querySelector('#nativeErr');

    function normalize(v) {
      var s = String(v || '').trim().replace(/\/+$/, '');
      if (!s) return '';
      if (!/^https?:\/\//i.test(s)) s = 'https://' + s;
      return s;
    }

    function open() {
      input.value = localStorage.getItem(LS_KEY) || '';
      err.textContent = '';
      mask.classList.add('on');
      setTimeout(function () { try { input.focus(); } catch (e) {} }, 120);
    }
    function close() { mask.classList.remove('on'); }

    btn.onclick = open;
    mask.querySelector('#nativeCancel').onclick = function () {
      if (localStorage.getItem(LS_KEY)) close();
      else toast('بدون آدرس سرور، تحلیل و ضبط زنده کار نمی‌کند');
    };
    mask.querySelector('#nativeSave').onclick = function () {
      var v = normalize(input.value);
      if (!v) { err.textContent = 'آدرس سرور را وارد کن.'; return; }
      if (!/^https?:\/\/[^\s/]+/i.test(v)) { err.textContent = 'آدرس معتبر نیست.'; return; }
      try { localStorage.setItem(LS_KEY, v); } catch (e) {}
      err.textContent = '';
      toast('آدرس سرور ذخیره شد — در حال بارگذاری…');
      setTimeout(function () { location.reload(); }, 250);
    };
    input.addEventListener('keydown', function (e) {
      if (e.key === 'Enter') mask.querySelector('#nativeSave').click();
    });

    // اولین اجرا: اگر آدرسی تنظیم نشده، همان اول بپرس
    if (!localStorage.getItem(LS_KEY)) setTimeout(open, 400);
  }

  function boot() {
    // موتور آفلاین فعال است ⇒ هیچ سروری لازم نیست، پنجرهٔ تنظیم هم نه
    if (window.LocalOffline && window.LocalOffline.enabled) return;
    if (document.getElementById('nativeCfgBtn')) return;   // یک‌بار
    injectSettings();
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
    // پشتیبان: اگر DOMContentLoaded دیر/نرسید
    setTimeout(boot, 1200);
  } else {
    boot();
  }
})();
