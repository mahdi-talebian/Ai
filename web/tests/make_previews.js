#!/usr/bin/env node
/* ============================================================================
   ساخت پیش‌نمایش‌های PNG برای `docs/preview/` — بدون مرورگر
   ----------------------------------------------------------------------------
   همان رندرری که اپ و ویدیو استفاده می‌کنند (trace_style.js) صدا زده می‌شود،
   پس این تصویرها عیناً همان خروجی‌اند (فقط گرادیان/متن با تقریب ساده کشیده
   می‌شود؛ ساختار، مختصات و ضخامت‌ها همان است).

   اجرا:  node web/tests/make_previews.js
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');
const { Ctx, writePng } = require('./png_stub.js');

const ROOT = path.resolve(__dirname, '../..');
const OUT = path.join(ROOT, 'docs/preview');

function makeCanvas(W, H) {
  const c = { width: W, height: H };
  const ctx = new Ctx(W, H);
  c.getContext = () => ctx;
  c._ctx = ctx;
  c.toBlob = (cb) => cb(new Blob([new Uint8Array(8)], { type: 'image/png' }));
  return c;
}

global.document = { createElement: (t) => t === 'canvas' ? makeCanvas(1280, 720) : {} };
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const CORE = global.LocalEngineCore;
const OFF = global.LocalOffline;

/* ── صدا: فایل نمونهٔ خود ریپو (اگر نبود، اجرای مصنوعی) ────────────────── */
function loadDemo() {
  const p = path.join(ROOT, 'web/demo_bayati.wav');
  if (!fs.existsSync(p)) return null;
  const buf = fs.readFileSync(p);
  const dv = new DataView(buf.buffer, buf.byteOffset, buf.length);
  const chn = dv.getUint16(22, true), sr = dv.getUint32(24, true);
  const n = (buf.length - 44) / (2 * chn);
  const y = new Float32Array(n);
  for (let i = 0; i < n; i++) y[i] = dv.getInt16(44 + i * 2 * chn, true) / 32768;
  return { y, sr };
}

function synth() {
  const sr = 22050, dur = 22;
  const y = new Float32Array(Math.round(sr * dur));
  const plan = [[0.4, 1.8, 196], [2.6, 2.0, 261.6], [5.0, 1.8, 349.2], [7.2, 2.2, 220],
                [9.8, 1.6, 293.7], [11.8, 2.4, 174.6], [14.6, 2.0, 392], [17.0, 2.4, 246.9]];
  plan.forEach(([st, len, f]) => {
    const i0 = Math.round(st * sr), m = Math.round(len * sr);
    let phase = 0;
    for (let i = 0; i < m && i0 + i < y.length; i++) {
      const t = i / sr;
      const vib = Math.pow(2, (40 / 1200) * Math.sin(2 * Math.PI * 5.5 * t));
      phase += 2 * Math.PI * f * vib / sr;
      const env = Math.min(1, t / 0.03) * Math.min(1, (len - t) / 0.05);
      let s = 0;
      for (let k = 1; k <= 10; k++) s += Math.sin(k * phase) / k;
      y[i0 + i] += 0.2 * s * Math.max(0, env);
    }
  });
  return { y, sr };
}

const audio = loadDemo() || synth();
const res = CORE.analyzeChannels([audio.y], audio.sr, {});
const mid = Math.min(res.duration * 0.5, res.duration - 1);

fs.mkdirSync(OUT, { recursive: true });

function shot(name, opts, t) {
  const canvas = makeCanvas(1280, 720);
  const R = new OFF.TraceRenderer(canvas, res.times, res.trace_freqs, res.notes, opts);
  R.draw(t);
  writePng(canvas._ctx, path.join(OUT, name + '.png'));
}

/* ۱) نمای کلی (تصویر تحلیل) */
shot('overview', {}, res.duration);
/* ۲) ویدیو: نوار ۱۵ ثانیه‌ای */
shot('video-t4', { windowSec: 15 }, Math.min(4, res.duration - 0.2));
shot('video-t12', { windowSec: 15 }, Math.min(12, res.duration - 0.2));
/* ۳) زوم زمانی + زوم ارتفاع (جدید) */
shot('zoom-time', { windowSec: 6 }, mid);
shot('zoom-pitch', { windowSec: 6, tightness: 2 }, mid);
shot('zoom-max', { windowSec: 6, tightness: 3.5 }, mid);

/* ۴) رشتهٔ خام در برابر سخت‌گیرانه (برای مستند «فقط نت‌ها») */
['raw', 'strict'].forEach((tag) => {
  const canvas = makeCanvas(1280, 720);
  const R = new OFF.TraceRenderer(canvas, res.times,
    tag === 'raw' ? res.freqs : res.trace_freqs, res.notes);
  R.draw(res.duration);
  writePng(canvas._ctx, path.join(OUT, 'filter-' + tag + '.png'));
});

console.log('نوشته شد در ' + OUT);
console.log('طول صدا: ' + res.duration.toFixed(1) + 's · نت‌ها: ' + res.notes.length +
  ' · ردِّ نگه‌داشته‌شده: ' + res.quality.trace_kept_pct + '%');
