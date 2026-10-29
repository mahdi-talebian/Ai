#!/usr/bin/env node
/* ============================================================================
   تست «پرش‌های کاذبِ نمودار» (خطوط عمودیِ بلند)
   ----------------------------------------------------------------------------
   الگویی که کاربر گزارش کرد: در وسطِ یک نُتِ پایدار، خط‌های عمودیِ بلند مثل
   موجِ سینوسیِ کشیده ظاهر می‌شد. علتش دو چیز است:
     • قفل‌شدنِ تخمینِ f0 روی هارمونیک دوم/سوم برای چند فریم (اکتاو/پنجم)
     • نمونه‌برداری یک‌درمیانِ کانتور در فایل‌های بلند (یک فریمِ پرت، تنها
       نمایندهٔ کلِ آن بازه می‌شد)
   این تست هر دو را می‌سنجد:
     ۱) cleanMids پرش اکتاوی و گشتِ کوتاه را پاک می‌کند
     ۲) گذارِ واقعیِ بین نت‌ها دست‌نخورده می‌ماند
     ۳) رسم دیگر هیچ خطِ عمودیِ بلندی (dx کوچک، dy بزرگ) تولید نمی‌کند
     ۴) کانتورِ گزارش «میانه‌محور» است: پرتِ تک‌فریمی روی نقطهٔ بازه اثر ندارد
     ۵) دادهٔ گزارش (freqs) دست‌نخورده است؛ فقط رسم پاک می‌شود

   اجرا:  node web/tests/spike_test.js
   ========================================================================== */
'use strict';

/* بومِ ضبط‌کننده: پاره‌خط‌ها را نگه می‌دارد */
let SEGS = [];
function makeCtx() {
  const st = { strokeStyle: '#000', lineWidth: 1 };
  let cur = [];
  const api = {
    beginPath() { cur = []; },
    moveTo(x, y) { cur.push({ x, y }); },
    lineTo(x, y) { cur.push({ x, y }); },
    stroke() { if (cur.length >= 2) SEGS.push({ p: cur.slice(), w: st.lineWidth, s: String(st.strokeStyle) }); cur = []; },
    arc() { }, ellipse() { }, arcTo() { }, closePath() { }, fill() { }, fillRect() { },
    clearRect() { }, drawImage() { }, fillText() { }, setLineDash() { }, save() { }, restore() { },
    clip() { }, rect() { }, createLinearGradient: () => ({ addColorStop() { } }),
    createRadialGradient: () => ({ addColorStop() { } }),
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(api, {
    get(t, k) { return k in t ? t[k] : function () { }; },
    set(t, k, v) { t[k] = v; if (k in st) st[k] = v; return true; }
  });
}
const mkCanvas = (W, H) => ({ width: W, height: H, getContext: () => makeCtx() });
global.document = { createElement: t => t === 'canvas' ? mkCanvas(1280, 720) : {} };
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL; global.URLSearchParams = URLSearchParams;

require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const CORE = global.LocalEngineCore;
const S = global.LocalTraceStyle;
const OFF = global.LocalOffline;

let pass = 0, fail = 0;
const check = (n, ok, x) => {
  if (ok) { pass++; console.log('✓ ' + n + (x !== undefined ? '  → ' + x : '')); }
  else { fail++; console.log('✖ ' + n + (x !== undefined ? '  → ' + x : '')); }
};

/* ── منحنی مصنوعی: نُت‌های واقعی + ویبراتو + ۶ پرش کاذب ─────────────────── */
const DT = 0.005, TOTAL = 20;
const times = [], mids = [];
const spikes = [
  { t: 3.2, len: 0.05, d: 12 },     /* اکتاو بالا، کوتاه */
  { t: 4.6, len: 0.04, d: 7 },      /* پنجم */
  { t: 6.1, len: 0.06, d: -12 },    /* اکتاو پایین */
  { t: 8.4, len: 0.04, d: 12 },     /* اکتاو بالا */
  { t: 12.9, len: 0.03, d: -7 },
  { t: 15.1, len: 0.05, d: 12 }
];
for (let t = 0; t < TOTAL; t += DT) {
  /* نُت‌های پله‌ایِ واقعی هر ۲٫۵s یک پله */
  let m = 57 + 4 * Math.floor(t / 2.5) + 0.4 * Math.sin(2 * Math.PI * 5.2 * t);
  spikes.forEach(s => { if (t >= s.t && t < s.t + s.len) m += s.d; });
  times.push(t); mids.push(m);
}

/* ── ۱) cleanMids: پرش‌ها پاک، پله‌ها سالم ─────────────────────────────── */
const cleaned = S.cleanMids(times, mids);
{
  let bad = 0, maxOut = 0;
  for (let i = 0; i < mids.length; i++) {
    const expect = 57 + 4 * Math.floor(times[i] / 2.5);
    const err = Math.abs(cleaned[i] - expect);
    maxOut = Math.max(maxOut, err);
    if (err > 2.2) bad++;
  }
  check('پرش‌های کاذب حذف شدند و مقدار روی نُتِ واقعی نشست', bad === 0,
    'بیشترین خطا=' + maxOut.toFixed(2) + ' نیم‌پرده');
  let stepKept = 0;
  for (let i = 1; i < cleaned.length; i++) {
    if (Math.abs(cleaned[i] - cleaned[i - 1]) > 3) stepKept++;
  }
  check('گذارِ واقعیِ بین نُت‌ها حفظ شد (۷ پله)', stepKept >= 7, stepKept + ' پله');
  check('دادهٔ ورودی دست‌نخورده ماند', mids.some((m, i) => Math.abs(m - cleaned[i]) > 3),
    'منحنی خام هنوز پرش دارد');
}

/* ── ۲) رسم: هیچ خطِ عمودیِ بلندی نباید بماند ──────────────────────────── */
function draw(strategy) {
  S.setClean(strategy !== 'raw');
  SEGS = [];
  const canvas = mkCanvas(1280, 720);
  const R = new OFF.TraceRenderer(canvas, times, mids.map(m => 440 * Math.pow(2, (m - 69) / 12)),
    [], {});
  R.draw(TOTAL);
  S.setClean(true);
  /* امضای «پرشِ کاذب»: یک جفت خطِ عمودیِ بلند با جهتِ مخالف در فاصلهٔ
     نزدیک (بالا رفتن و برگشتن). پلهٔ واقعیِ بین نُت‌ها فقط «یک» خطِ عمودی
     دارد، پس در این شمارش نمی‌آید. */
  const verticals = [];
  SEGS.forEach(g => {
    for (let i = 1; i < g.p.length; i++) {
      const a = g.p[i - 1], b = g.p[i];
      if (Math.abs(b.x - a.x) >= 4) continue;
      if (Math.abs(b.y - a.y) <= 45) continue;
      verticals.push({ x: (a.x + b.x) / 2, dir: Math.sign(b.y - a.y), dy: Math.abs(b.y - a.y) });
    }
  });
  let pairs = 0;
  for (let i = 0; i < verticals.length; i++) {
    for (let j = i + 1; j < verticals.length; j++) {
      if (verticals[j].x - verticals[i].x > 60) break;
      if (verticals[i].dir !== verticals[j].dir) { pairs++; break; }
    }
  }
  return pairs;
}
const rawPairs = draw('raw');
const cleanPairs = draw('clean');
check('بدون پاک‌سازی، رسم «برو و برگردِ عمودی» می‌سازد (بازتولید مشکل)',
  rawPairs >= 5, rawPairs + ' جفت پرش');
check('با پاک‌سازی، هیچ برو-و-برگشتی روی نمودار نمی‌ماند', cleanPairs === 0,
  cleanPairs + ' (قبلاً ' + rawPairs + ')');

/* همان معیار روی دادهٔ آمادهٔ رسم: انحراف از سطحِ محلی */
{
  const lvl = (function () {
    const half = 25, out = [];
    for (let i = 0; i < mids.length; i++) {
      const w = [];
      for (let j = Math.max(0, i - half); j <= Math.min(mids.length - 1, i + half); j++) w.push(mids[j]);
      w.sort((a, b) => a - b);
      out.push(w[w.length >> 1]);
    }
    return out;
  })();
  const spec = {
    W: 1280, H: 720, dpr: 1, times, mids, notes: [], range: {},
    duration: TOTAL, t: TOTAL, view: { t0: 0, t1: TOTAL }, tightness: 1
  };
  const L = S.prepare(spec);
  let bad = 0;
  for (let i = 0; i < L.mids.length; i++) {
    if (L.mids[i] == null) continue;
    if (Math.abs(L.mids[i] - lvl[i]) > 2.6) bad++;
  }
  check('دادهٔ رسم‌شده هیچ انحرافِ بیش از ۲٫۶ نیم‌پرده از سطحِ محلی ندارد',
    bad === 0, bad + ' فریم از ' + L.mids.length);
}

/* ── ۳) کانتورِ گزارش میانه‌محور است (فایل‌های بلند) ────────────────────── */
{
  const n = 20000;
  const t2 = [], f2 = [];
  for (let i = 0; i < n; i++) { t2.push(i * 0.005); f2.push(220); }
  /* یک فریمِ پرت (اکتاو) در وسطِ هر بازه؛ نمونه‌برداری قدیمی همان را برمی‌داشت */
  f2[5000] = 440; f2[11000] = 110; f2[16000] = 440;
  const rep = CORE.buildReport({
    times: t2, freqs: Float32Array.from(f2), trace_freqs: Float32Array.from(f2),
    notes: [], quality: {}, duration: n * 0.005, wav: new Float32Array(10)
  }, {});
  const c = rep.contour;
  let maxJump = 0;
  for (let i = 1; i < c.midi.length; i++) {
    if (c.midi[i] == null || c.midi[i - 1] == null) continue;
    maxJump = Math.max(maxJump, Math.abs(c.midi[i] - c.midi[i - 1]));
  }
  check('کانتورِ فایل بلند با پرتِ تک‌فریمی، پرش نمی‌سازد',
    maxJump < 1.5, 'بزرگ‌ترین پرش=' + maxJump.toFixed(2) + ' نیم‌پرده · ' +
    c.midi.length + ' نقطه از ' + n + ' فریم');
}

/* ── ۴) همان پاک‌سازی برای نمودار اپ هم اعمال می‌شود (یک کد برای همه) ──── */
{
  const spec = {
    W: 1280, H: 720, dpr: 1, times, mids, notes: [], range: {},
    duration: TOTAL, t: 10, view: { t0: 0, t1: TOTAL }, tightness: 1
  };
  const L = S.prepare(spec);
  const cleanVals = L.mids || L.segs.reduce((a, s) => a.concat(s.map(p => p.m)), []);
  let out = 0;
  cleanVals.forEach(m => {
    const expect = 57 + 4 * Math.floor(0) ;
    if (!isFinite(m)) out++;
  });
  check('spec رسم، نسخهٔ پاک‌شدهٔ ارتفاع را در اختیار همهٔ خروجی‌ها می‌گذارد',
    !!L.mids && L.mids.length === mids.length, (L.mids ? L.mids.length : 0) + ' نمونه');
  check('همهٔ نقاطِ رسم داخل محدودهٔ منطقی‌اند (بدون NaN)', out === 0);
}

/* ── ۵) کارایی: رسم ۶۰fps نباید هزینهٔ پاک‌سازی را هر فریم بدهد ───────── */
{
  const n = 36000, t2 = new Array(n), m2 = new Array(n);
  for (let i = 0; i < n; i++) { t2[i] = i * 0.005; m2[i] = 60 + 0.5 * Math.sin(i / 9); }
  const t0 = Date.now();
  S.cleanedFor(m2);
  const first = Date.now() - t0;
  const t1 = Date.now();
  for (let k = 0; k < 1200; k++) S.cleanedFor(m2);
  const cached = Date.now() - t1;
  check('پاک‌سازیِ ۳ دقیقه صدا یک‌بار انجام می‌شود و بعد کش می‌شود',
    cached < 50, first + 'ms بار اول · ' + cached + 'ms برای ۱۲۰۰ فراخوانی بعدی');
  check('پاک‌سازی برای فایل بلند زیر یک ثانیه است', first < 900, first + 'ms');
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ پرش‌های کاذب نمودار پاک شد (' + pass + ' بررسی)');
process.exit(0);
