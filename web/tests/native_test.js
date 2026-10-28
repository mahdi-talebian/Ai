#!/usr/bin/env node
/* ============================================================================
   تست پل بومی اندروید (web/native.js) — بدون گوشی
   ----------------------------------------------------------------------------
   پل با یک NativeApp تقلیدی تست می‌شود:
     • ذخیرهٔ بلاب در گالری باید با beginSaveTo(...,'gallery') شروع شود
     • پسوند فایل باید با نوع واقعی بلاب هم‌خوان شود (mp4/webm/png/…)
     • تکه‌تکه فرستادن و endSave درست انجام شود
     • نتیجهٔ نهایی جاوا (__nativeSaveDone) به صفحه برگردد
     • بلاب خالی نباید «موفق» گزارش شود

   اجرا:  node web/tests/native_test.js
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');
const { JSDOM } = require('jsdom');

const ROOT = path.resolve(__dirname, '../..');
const NATIVE = fs.readFileSync(path.join(ROOT, 'web/native.js'), 'utf8');

let pass = 0, fail = 0;

/* صبر تا وقتی شرط برقرار شود (FileReader در jsdom چند نوبت طول می‌کشد) */
function until(fn, ms) {
  const limit = ms || 4000;
  const t0 = Date.now();
  return new Promise((resolve) => {
    (function tick() {
      let v = false;
      try { v = fn(); } catch (e) { v = false; }
      if (v) return resolve(true);
      if (Date.now() - t0 > limit) return resolve(false);
      setTimeout(tick, 15);
    })();
  });
}
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra ? '  → ' + extra : '')); }
  else { fail++; console.log('✗ ' + name + (extra ? '  → ' + extra : '')); }
}

/* ── محیط ساختگی: NativeApp ضبط‌کننده ─────────────────────────────────── */
function makeWorld() {
  const dom = new JSDOM('<!doctype html><html><body></body></html>', {
    url: 'https://appassets.local/', runScripts: 'outside-only', pretendToBeVisual: true
  });
  const w = dom.window;

  const calls = [];
  const chunks = [];
  let tokenSeq = 0;

  w.NativeApp = {
    info: () => '{"platform":"android","sdk":34,"version":"1.2.1"}',
    toast: (m) => calls.push({ fn: 'toast', msg: String(m) }),
    saveUrl: (u, n) => calls.push({ fn: 'saveUrl', url: u, name: n }),
    saveToGallery: (u, n) => calls.push({ fn: 'saveToGallery', url: u, name: n }),
    beginSave: (n, m) => {
      const t = 'tok' + (++tokenSeq);
      calls.push({ fn: 'beginSave', name: n, mime: m, token: t });
      return t;
    },
    beginSaveTo: (n, m, target) => {
      const t = 'tok' + (++tokenSeq);
      calls.push({ fn: 'beginSaveTo', name: n, mime: m, target: target, token: t });
      return t;
    },
    appendSave: (t, b64) => {
      chunks.push(b64.length);
      calls.push({ fn: 'appendSave', token: t, bytes: b64.length });
      return true;
    },
    endSave: (t) => {
      calls.push({ fn: 'endSave', token: t });
      /* جاوا پس از نوشتن، نتیجه را به صفحه برمی‌گرداند */
      const nm = (calls.filter(c => c.token === t && (c.fn === 'beginSaveTo' || c.fn === 'beginSave'))[0] || {}).name || '';
      setTimeout(() => {
        try { w.__nativeSaveDone(t, true, '✅ ذخیره شد: ' + nm); } catch (e) {}
      }, 0);
    },
    abortSave: (t) => calls.push({ fn: 'abortSave', token: t })
  };

  w.eval(NATIVE);
  return { w, calls, chunks, dom };
}

(async () => {
  /* ── ۱) ذخیرهٔ مستقیم بلاب تصویر در گالری ── */
  {
    const { w, calls } = makeWorld();
    const blob = new w.Blob([new Uint8Array(1500)], { type: 'image/png' });
    let result = null;
    w.NativeBridge.saveBlobToGallery(blob, 'take-01-analysis', (ok, msg) => { result = { ok, msg }; });
    const done = await until(() => !!result);
    const begin = calls.find(c => c.fn === 'beginSaveTo');
    const end = calls.find(c => c.fn === 'endSave');
    check('ذخیرهٔ گالری با مقصد gallery شروع می‌شود',
      !!begin && begin.target === 'gallery', begin && begin.target);
    check('پسوند تصویر از نوع واقعی بلاب گرفته می‌شود',
      !!begin && /\.png$/.test(begin.name), begin && begin.name);
    check('mime درست به جاوا داده می‌شود',
      !!begin && begin.mime === 'image/png', begin && begin.mime);
    check('همهٔ تکه‌ها فرستاده و endSave صدا زده می‌شود',
      !!end && calls.filter(c => c.fn === 'appendSave').length >= 1);
    check('نتیجهٔ نهایی جاوا به صفحه برمی‌گردد',
      done && result && result.ok === true && /Pictures|take-01/.test(result.msg || ''),
      result && result.msg);
  }

  /* ── ۲) ویدیوی webm → پسوند webm (نه mp4) ── */
  {
    const { w, calls } = makeWorld();
    const blob = new w.Blob([new Uint8Array(900)], { type: 'video/webm;codecs=vp9,opus' });
    w.NativeBridge.saveBlobToGallery(blob, 'sample-radde-seda.mp4', () => {});
    const done = await until(() => calls.some(c => c.fn === 'endSave'));
    const begin = calls.find(c => c.fn === 'beginSaveTo');
    check('نام webm به پسوند درست اصلاح می‌شود',
      done && !!begin && /\.webm$/.test(begin.name), begin && begin.name);
  }

  /* ── ۳) بلاب خالی → خطای روشن، بدون شروع ذخیره ── */
  {
    const { w, calls } = makeWorld();
    const blob = new w.Blob([], { type: 'video/mp4' });
    const res = await new Promise((resolve) => {
      w.NativeBridge.saveBlobToGallery(blob, 'empty.mp4', (ok, msg) => resolve({ ok, msg }));
    });
    check('بلاب خالی «موفق» گزارش نمی‌شود', res.ok === false, res.msg);
    check('برای بلاب خالی اصلاً نوشتن شروع نمی‌شود',
      !calls.some(c => c.fn === 'beginSaveTo' || c.fn === 'beginSave'));
  }

  /* ── ۴) ذخیرهٔ عادی (دانلودها) مقصد gallery نمی‌گیرد ── */
  {
    const { w, calls } = makeWorld();
    const blob = new w.Blob([new Uint8Array(300)], { type: 'audio/wav' });
    w.NativeBridge.saveBlob(blob, 'take-01.wav', () => {});
    const done = await until(() => calls.some(c => c.fn === 'endSave'));
    const begin = calls.find(c => c.fn === 'beginSave');
    check('ذخیرهٔ ساده در پوشهٔ دانلودها مسیر قدیمی را می‌رود',
      done && !!begin && /\.wav$/.test(begin.name), begin && begin.name);
  }

  /* ── ۵) کلیک روی لینک تصویر → گالری (نه دانلود ساده) ── */
  {
    const { w, calls } = makeWorld();
    w.fetch = () => Promise.resolve({
      ok: true, status: 200,
      blob: () => Promise.resolve(new w.Blob([new Uint8Array(120)], { type: 'image/png' }))
    });
    const a = w.document.createElement('a');
    a.setAttribute('href', '/api/download/j1/image');
    a.setAttribute('download', 'x-analysis.png');
    a.setAttribute('data-gallery', 'image');
    w.document.body.appendChild(a);
    a.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true }));
    const done = await until(() => calls.some(c => c.fn === 'endSave'));
    const begin = calls.find(c => c.fn === 'beginSaveTo');
    check('کلیک روی لینک تصویر در اپ به گالری می‌رود',
      done && !!begin && begin.target === 'gallery', begin && begin.name);
  }

  /* ── ۶) لینک ویدیو هم به گالری می‌رود، ولی گزارش JSON به دانلودها ── */
  {
    const { w, calls } = makeWorld();
    w.fetch = () => Promise.resolve({
      ok: true, status: 200,
      blob: () => Promise.resolve(new w.Blob([new Uint8Array(80)], { type: 'video/mp4' }))
    });
    const a = w.document.createElement('a');
    a.setAttribute('href', '/api/download/j1/video');
    a.setAttribute('download', 'x-radde-seda.mp4');
    w.document.body.appendChild(a);
    a.dispatchEvent(new w.MouseEvent('click', { bubbles: true, cancelable: true }));
    const done = await until(() => calls.some(c => c.fn === 'endSave'));
    const begin = calls.find(c => c.fn === 'beginSaveTo');
    check('کلیک روی لینک ویدیو به گالری (Movies) می‌رود',
      done && !!begin && begin.target === 'gallery' && /\.mp4$/.test(begin.name), begin && begin.name);
  }

  console.log('─'.repeat(70));
  if (fail === 0) console.log('✅ پل بومی درست ذخیره می‌کند و نتیجه را برمی‌گرداند (' + pass + ' بررسی)');
  else console.log('❌ ' + fail + ' بررسی ناموفق از ' + (pass + fail));
  process.exit(fail === 0 ? 0 : 1);
})().catch(e => { console.error(e && e.stack || e); process.exit(1); });
