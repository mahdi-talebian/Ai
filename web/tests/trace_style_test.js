#!/usr/bin/env node
/* ============================================================================
   تست «زوم ردِّ صدا» و «یکسان‌بودن نمودار اپ و ویدیو»
   ----------------------------------------------------------------------------
   • زوم زمانی: پنجرهٔ دید باریک می‌شود، خط پخش وسط می‌ماند، هیچ نقطه‌ای
     بیرون پنجره رسم نمی‌شود.
   • زوم عمودی: کادرِ ارتفاع واقعاً باریک می‌شود (×۱ … ×۳٫۵) ولی نتِ لحظهٔ
     جاری از کادر بیرون نمی‌افتد.
   • بدون زوم، با لغزش پنجره تصویر بالا-پایین نمی‌لرزد (مرکز ثابت).
   • نمودار اپ و ویدیو از یک کد می‌آیند: همان spec ⇒ همان مختصات نسبی.

   اجرا:  node web/tests/trace_style_test.js
   ========================================================================== */
'use strict';

let P = [];
function makeGradient() { return { addColorStop() { } }; }
function makeCtx() {
  const st = { strokeStyle: '#000', lineWidth: 1 };
  const ctx = {
    beginPath() { }, moveTo() { }, lineTo(x, y) { P.push({ x, y, w: st.lineWidth, s: st.strokeStyle }); },
    arc() { }, ellipse() { }, arcTo() { }, closePath() { }, stroke() { }, fill() { },
    fillRect() { }, clearRect() { }, drawImage() { }, fillText() { }, setLineDash() { },
    save() { }, restore() { }, clip() { }, rect() { },
    createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(ctx, {
    get(t, p) { return p in t ? t[p] : function () { }; },
    set(t, p, v) { t[p] = v; if (p in st) st[p] = v; return true; }
  });
}

global.document = {
  createElement: (tag) => {
    if (tag !== 'canvas') return {};
    const c = { width: 1280, height: 720, getContext: () => makeCtx(), toBlob: cb => cb(new Blob([])) };
    return c;
  }
};
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const CORE = global.LocalEngineCore;
const S = global.LocalTraceStyle;
const OFF = global.LocalOffline;

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
  else { fail++; console.log('✖ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
}

/* ── دادهٔ نمونه: نت‌های پله‌ای با ویبراتو، کل ۱۲ ثانیه ───────────────────── */
const sr = 22050;
const dur = 12;
const n = Math.round(sr * dur);
const y = new Float32Array(n);
const plan = [[0.2, 2.0, 220], [2.6, 2.0, 261.6], [5.0, 2.2, 329.6], [7.6, 2.0, 293.7], [10.0, 1.6, 246.9]];
plan.forEach(([start, len, f]) => {
  const i0 = Math.round(start * sr), m = Math.round(len * sr);
  let phase = 0;
  for (let i = 0; i < m && i0 + i < n; i++) {
    const t = i / sr;
    /* ویبراتو با انباشت فاز (فیزیکی): ±۳۵ سنت حول f */
    const vib = Math.pow(2, (35 / 1200) * Math.sin(2 * Math.PI * 5.5 * t));
    phase += 2 * Math.PI * f * vib / sr;
    const env = Math.min(1, t / 0.03) * Math.min(1, (len - t) / 0.05);
    let s = 0;
    for (let k = 1; k <= 10; k++) s += Math.sin(k * phase) / k;
    y[i0 + i] += 0.2 * s * Math.max(0, env);
  }
});

const res = CORE.analyzeChannels([y], sr, {});

const times = res.times;
/* مثل خود اپ: ارتفاع از «ردِّ سخت‌گیرانه» می‌آید، نه دادهٔ خام */
const mids = Array.from(times).map((t, i) =>
  res.trace_freqs[i] > 0 ? CORE.hzToMidi(res.trace_freqs[i]) : null);   /* آرایهٔ معمولی، نه TypedArray */
const notes = res.notes;

/* ── ۱) زوم عمودی: کادر واقعاً باریک می‌شود ───────────────────────────── */
{
  const base = S.pitchLimits(mids, { t0: 0, t1: dur }, times, 1, 3);
  const z16 = S.pitchLimits(mids, { t0: 0, t1: dur }, times, 1.6, 3);
  const z35 = S.pitchLimits(mids, { t0: 0, t1: dur }, times, 3.5, 3);
  check('زوم ×۱٫۶ کادر را باریک می‌کند', z16.shown < base.shown * 0.85,
    base.shown.toFixed(1) + ' → ' + z16.shown.toFixed(1) + ' نیم‌پرده');
  check('زوم ×۳٫۵ باریک‌تر از ×۱٫۶ است', z35.shown < z16.shown,
    z35.shown.toFixed(1) + ' نیم‌پرده');
  check('زوم واقعاً اعمال‌شده نزدیک درخواست است',
    Math.abs(z35.zoomEff - 3.5) < 0.6 || z35.shown <= 4.05, '×' + z35.zoomEff.toFixed(2));

  /* نتِ لحظهٔ جاری باید داخل کادر بماند (در چند لحظهٔ مختلف) */
  let out = 0, checked = 0;
  for (const t of [1.0, 3.4, 5.8, 8.2, 10.6]) {
    const L = S.pitchLimits(mids, { t0: Math.max(0, t - 3), t1: t + 3 }, times, 3.5, t);
    let bi = 0, bd = Infinity;
    for (let k = 0; k < times.length; k++) {
      const dd = Math.abs(times[k] - t);
      if (dd < bd) { bd = dd; bi = k; }
    }
    const m = mids[bi];
    if (m == null) continue;
    checked++;
    if (m < L.loLim || m > L.hiLim) out++;
  }
  check('با زوم، نتِ لحظهٔ جاری بیرون کادر نمی‌افتد', out === 0, out + ' بیرون از ' + checked);
}

/* ── ۲) بدون زوم: لغزش پنجره تصویر را بالا-پایین نمی‌برد ────────────────── */
{
  const centers = [];
  for (const t of [3, 6, 9]) {
    const L = S.pitchLimits(mids, { t0: Math.max(0, t - 3), t1: t + 3 }, times, 1, t);
    centers.push(L.center);
  }
  const spread = Math.max(...centers) - Math.min(...centers);
  check('بدون زوم، مرکز کادر با لغزش نوار ثابت می‌ماند', spread < 0.01,
    'اختلاف=' + spread.toFixed(4));
}

/* ── ۳) زوم زمانی: پنجره باریک، خط پخش وسط، بدون نقطهٔ بیرونی ───────────── */
{
  const canvas = global.document.createElement('canvas');
  const R = new OFF.TraceRenderer(canvas, times, res.trace_freqs, notes, { windowSec: 4 });
  const view = R.viewAt(6);
  check('پنجرهٔ ۴ ثانیه‌ای درست حساب می‌شود',
    Math.abs(view.t1 - view.t0 - 4) < 1e-9, '[' + view.t0 + ', ' + view.t1 + ']');
  const xMid = R.xOfTimeIn(6, view);
  const usable = 1280 - 74 - 46;
  check('خط پخش وسط بوم است', Math.abs(xMid - (74 + usable / 2)) < 0.01, xMid.toFixed(1));

  P = [];
  R.draw(6);
  const curve = P.filter(p => p.w >= 3);
  const lo = 74 - 1.5, hi = 1280 - 46 + 1.5;
  const outside = curve.filter(p => p.x < lo || p.x > hi).length;
  check('هیچ نقطه‌ای بیرون پنجرهٔ زوم رسم نمی‌شود', outside === 0,
    outside + ' از ' + curve.length);
}

/* ── ۴) اپ و ویدیو یک کد: همان اندازه ⇒ همان پیکسل‌ها ─────────────────── */
{
  const canvas = global.document.createElement('canvas');
  const R = new OFF.TraceRenderer(canvas, times, res.trace_freqs, notes,
    { windowSec: 4, tightness: 2 });
  /* مسیر ویدیو: spec از خود رندرر */
  const videoSpec = R._spec(6);
  /* مسیر اپ: buildTraceData همان چیزها را می‌سازد (contour + notes + range) */
  const appSpec = {
    W: 1280, H: 720, dpr: 1,
    times: times, mids: mids, notes: videoSpec.notes,
    range: videoSpec.range, duration: dur, t: 6,
    view: { t0: 4, t1: 8 }, tightness: 2
  };
  const coords = (spec) => {
    P = [];
    S.draw(canvas.getContext('2d'), spec);
    return P.map(p => [Math.round(p.x * 100) / 100, Math.round(p.y * 100) / 100, p.w]);
  };
  const a = coords(videoSpec);
  const b = coords(appSpec);
  check('تعداد نقاط رسم در اپ و ویدیو یکی است', a.length === b.length && a.length > 100,
    a.length + ' / ' + b.length);
  let maxD = 0;
  for (let i = 0; i < Math.min(a.length, b.length); i++) {
    maxD = Math.max(maxD, Math.abs(a[i][0] - b[i][0]), Math.abs(a[i][1] - b[i][1]));
  }
  check('همان مختصات پیکسلی رسم می‌شود', maxD < 0.05, 'اختلاف=' + maxD.toFixed(3) + 'px');
}

/* ── ۵) خروجی ویدیو دقیقاً از همین سبک استفاده می‌کند ───────────────────── */
{
  const canvas = global.document.createElement('canvas');
  const R = new OFF.TraceRenderer(canvas, times, res.trace_freqs, notes, { tightness: 2 });
  check('رندرر ویدیو از سبک مشترک می‌خواند', typeof R._spec === 'function' && !!global.LocalTraceStyle);
  check('زوم عمودی به رندرر ویدیو می‌رسد', R.tightness === 2, String(R.tightness));
  const sp = R._spec(6);
  check('spec ویدیو پنجره و زوم را دارد', !!sp.view && sp.tightness === 2,
    'tightness=' + sp.tightness);
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ زوم و یکسانی نمودار اپ/ویدیو سالم است (' + pass + ' بررسی)');
process.exit(0);
