#!/usr/bin/env node
/* ============================================================================
   تستِ «رفتارِ اپِ بومی» و یکسانیِ تحلیلِ ویدیو با تحلیل‌گر
   ----------------------------------------------------------------------------
   بررسی می‌کند:
     ۱) صفحه هیچ زومی ندارد (viewport + CSS + قفلِ اشاره‌ها + تنظیماتِ WebView)
     ۲) برچسبِ «در حال پخش» از بوردِ ردِّ صدا حذف شده است
     ۳) ویدیو «گستره/درجه‌ها» را از گزارشِ تحلیل‌گر می‌گیرد، نه از محاسبهٔ
        دوباره (عددهای ویدیو و اپ باید یکی باشند)
     ۴) پلِ بومی طولِ ویدیو را برای MediaStore می‌فرستد و مسیرِ گالری را
        انتخاب می‌کند

   اجرا:  node web/tests/mobile_app_test.js
   ========================================================================== */
'use strict';

const fs = require('fs');
const path = require('path');

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
  else { fail++; console.log('✖ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
}

const ROOT = path.join(__dirname, '..');
const html = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
const java = fs.readFileSync(path.join(ROOT, '../android/java/ir/raddeseda/app/MainActivity.java'), 'utf8');

/* ── ۱) قفلِ زوم ─────────────────────────────────────────────────────── */
const vp = (html.match(/<meta name="viewport"[^>]*>/) || [''])[0];
check('viewport زوم را غیرفعال کرده است',
  /user-scalable=no/.test(vp) && /maximum-scale=1/.test(vp) && /minimum-scale=1/.test(vp),
  vp.slice(0, 90) + '…');
check('CSS: touch-action و overscroll مهار شده‌اند',
  /touch-action:manipulation/.test(html) && /overscroll-behavior:none/.test(html));
check('JS: زومِ دو‌انگشتی و دابل‌تپ بسته شده است',
  /gesturestart/.test(html) && /e\.touches\.length > 1/.test(html) && /lastTap/.test(html));
check('WebView: setSupportZoom(false) و setBuiltInZoomControls(false)',
  /setSupportZoom\(false\)/.test(java) && /setBuiltInZoomControls\(false\)/.test(java) &&
  /setTextZoom\(100\)/.test(java));

/* ── ۲) برچسبِ «در حال پخش» ─────────────────────────────────────────── */
check('برچسبِ «در حال پخش» از بورد حذف شده است',
  !/● در حال پخش/.test(html) && !/id="liveBadge"/.test(html));
check('هیچ ارجاعِ مرده‌ای به liveBadge نمانده است', !/liveBadge/.test(html));

/* ── ۳) ویدیو از گزارشِ تحلیل‌گر استفاده می‌کند ──────────────────────── */
const offlineSrc = fs.readFileSync(path.join(ROOT, 'offline.js'), 'utf8');
const renderers = offlineSrc.match(/new TraceRenderer\([\s\S]{0,400}?\);/g) || [];
check('هر سه رندرر (سریع/زمان‌واقعی/تصویر) وجود دارند', renderers.length >= 3, renderers.length + ' مورد');
check('گسترهٔ رسم از گزارشِ تحلیل‌گر پاس داده می‌شود',
  (offlineSrc.match(/range: pitch\.range \|\| opts\.range \|\| null/g) || []).length >= 2 &&
  /range: res\.range \|\| null/.test(offlineSrc));

/* رفتارِ واقعی: اگر گزارش گسترهٔ خودش را بدهد، همان باید رسم شود */
global.window = global;
try {
  Object.defineProperty(global, 'navigator', {
    value: { userAgent: 'Mozilla/5.0 (Linux; Android 13) Chrome/120.0 Mobile Safari/537.36' },
    configurable: true
  });
} catch (e) { }
global.document = {
  createElement: () => ({
    width: 0, height: 0,
    getContext: () => new Proxy({}, {
      get: (t, p) => (p in t ? t[p] : function () { }),
      set: (t, p, v) => { t[p] = v; return true; }
    })
  }),
  addEventListener() { }
};
require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const OFF = global.LocalOffline;

const times = [0, 0.5, 1.0, 1.5, 2.0];
const freqs = [220, 330, 440, 330, 220];
const notes = [
  { note_name: 'A3', start: 0.0, end: 0.6, f0_hz: 220, midi: 57 },
  { note_name: 'E4', start: 0.8, end: 1.6, f0_hz: 330, midi: 64 }
];
const reportRange = {
  highest: { note_name: 'A4', f0_hz: 440, start: 1.0 },
  lowest: { note_name: 'A3', f0_hz: 220, start: 0.0 },
  span_semitones: 12,
  span_degrees: 8,
  span_text: '۱ اکتاو'
};
const canvas = { width: 640, height: 360, getContext: () => new Proxy({}, {
  get: (t, p) => (p in t ? t[p] : function () { }),
  set: (t, p, v) => { t[p] = v; return true; }
}) };
const withReport = new OFF.TraceRenderer(canvas, times, freqs, notes, { range: reportRange });
check('گسترهٔ رندرر همان گسترهٔ گزارش است',
  withReport.range === reportRange && withReport.range.span_text === '۱ اکتاو',
  withReport.range && withReport.range.span_text);

const withoutReport = new OFF.TraceRenderer(canvas, times, freqs, notes, {});
check('بدونِ گزارش، رندرر خودش گستره می‌سازد (فقط مسیرِ احتیاطی)',
  withoutReport.range == null || (withoutReport.highest && withoutReport.highest.f0_hz === 330),
  withoutReport.highest ? withoutReport.highest.f0_hz + 'Hz' : 'null');

/* درجهٔ لحظه‌ای: با گسترهٔ گزارش، پایین‌ترین نت ۲۲۰Hz ⇒ پایهٔ درجه‌ها */
const spec = withReport._spec(1.0);
check('مشخصاتِ رسم گسترهٔ گزارش را حمل می‌کند',
  spec.range && spec.range.lowest && spec.range.lowest.f0_hz === 220,
  spec.range.lowest.f0_hz + 'Hz');

/* ── ۴) پلِ بومی و گالری ─────────────────────────────────────────────── */
check('اندروید متدِ ارسالِ طول را دارد (beginSaveToEx)',
  /beginSaveToEx/.test(java) && /MediaStore\.Video\.Media\.DURATION/.test(java));
check('اندروید بعد از نوشتن، ردیفِ رسانه را تأیید می‌کند',
  /querySize/.test(java) && /ردیفِ رسانه تأیید نشد/.test(java));
check('پیامِ «ذخیره شد» فقط وقتی موفق است که واقعاً در گالری باشد',
  /return "✅ در گالری ذخیره شد: "/.test(java) && /⚠️ در گالری ذخیره نشد/.test(java));

const nativeSrc = fs.readFileSync(path.join(ROOT, 'native.js'), 'utf8');
check('پلِ وب طولِ ویدیو را به اندروید می‌فرستد',
  /beginSaveToEx\(fit\.name, fit\.mime, 'gallery', dur\)/.test(nativeSrc) &&
  /saveBlobToGallery: function \(blob, name, done, durationMs\)/.test(nativeSrc));
check('صفحه طولِ واقعیِ ویدیو را نگه می‌دارد',
  /STATE\.videoSeconds = Number\(res\.duration\)/.test(html) &&
  /gallerySaveViaApp\(blob, name, \(STATE\.videoSeconds \|\| 0\) \* 1000\)/.test(html));

/* رفتارِ واقعی: پل باید مسیرِ گالری و طول را درست پاس بدهد */
const calls = [];
global.NativeApp = {
  toast() { },
  beginSaveToEx(name, mime, target, dur) { calls.push({ name, mime, target, dur }); return 'tok'; },
  beginSaveTo(name, mime, target) { calls.push({ name, mime, target }); return 'tok'; },
  beginSave(name, mime) { calls.push({ name, mime }); return 'tok'; },
  appendSave() { return true; },
  endSave() { },
  abortSave() { }
};
global.FileReader = class {
  readAsDataURL(blob) {
    blob.arrayBuffer().then(ab => {
      this.result = 'data:application/octet-stream;base64,' + Buffer.from(ab).toString('base64');
      if (this.onload) this.onload();
    });
  }
};
require('../native.js');
const NB = global.window.NativeBridge;
const vblob = new Blob([new Uint8Array(16)], { type: 'video/mp4' });
NB.saveBlobToGallery(vblob, 'radde-seda.mp4', () => { }, 4321.6);
check('ذخیرهٔ گالری با مقصدِ gallery و طولِ درست فراخوانی شد',
  calls.length === 1 && calls[0].target === 'gallery' && calls[0].dur === '4322',
  calls.length ? JSON.stringify(calls[0]) : '—');

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ رفتارِ اپِ بومی + یکسانیِ تحلیلِ ویدیو (' + pass + ' بررسی)');
process.exit(0);
