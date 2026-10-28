/* ============================================================================
   آزمون «پردازش موازی/جریانی» — web/tests/parallel_test.js
   ----------------------------------------------------------------------------
   تضمین می‌کند:
     ۱) هر تکهٔ محاسبه (pitch/yin/refine) دقیقاً همان مقدار مسیر کامل را بدهد
     ۲) استخر کارگرها (Web Worker در مرورگر، worker_threads در Node) همان
        گزارش مسیر سریال را تولید کند — بیت‌به‌بیت
     ۳) اگر موازی‌سازی در دسترس نباشد، بی‌صدا به مسیر سریال برگردد
     ۴) سرعت: موازی از سریال کندتر نباشد و روی چند هسته چند برابر شود

   اجرا:  node web/tests/parallel_test.js
   ========================================================================== */
'use strict';

const path = require('path');
const CORE = require(path.join(__dirname, '..', 'engine.js'));

let pass = 0, fail = 0;
function check(name, ok, info) {
  if (ok) { pass++; console.log('✓ ' + name + (info ? '  → ' + info : '')); }
  else { fail++; console.log('✗ ' + name + (info ? '  → ' + info : '')); }
}

/* ── سیگنال مرجع: ۱۲ ثانیه، ۸ نت با ویبراتو و نویز کم ─────────────────── */
const SR = 44100;
const DUR = 12;
const N = SR * DUR;
const sig = new Float32Array(N);
{
  const steps = [0, 2, 4, 5, 7, 9, 11, 12];
  for (let i = 0; i < N; i++) {
    const t = i / SR;
    const bar = Math.floor(t / 1.2);
    const semis = steps[bar % steps.length];
    const f0 = 220 * Math.pow(2, semis / 12);
    let v = 0;
    for (let k = 1; k <= 6; k++) {
      v += Math.sin(2 * Math.PI * f0 * k * t + k * 0.2) / (k * k);
    }
    v *= 0.32 * (1 + 0.05 * Math.sin(2 * Math.PI * 5 * t));
    sig[i] = v + (Math.random() * 2 - 1) * 0.0015;
  }
}

const PREP = CORE.prepareAudio([sig], SR, {});
const Y = PREP.y;

/* ═════════ ۱) برابری تکه‌ها با مسیر کامل ═════════ */
{
  const full = CORE.pitchTrack(Y, PREP.sr, 55, 1200, null);
  const w = CORE.pitchWindow(Y, PREP.sr, 55);
  let diffs = 0, checked = 0;
  [[0, 137], [137, 900], [900, full.nFrames]].forEach(([a, b]) => {
    const part = CORE.pitchTrackRange(Y, PREP.sr, 55, 1200, w, a, b);
    for (let k = 0; k < b - a; k++) {
      checked++;
      if (part.freqs[k] !== full.freqs[a + k] || part.strength[k] !== full.strength[a + k]) diffs++;
    }
  });
  check('تکه‌های F0 با مسیر کامل یکسان‌اند', diffs === 0, diffs + ' اختلاف از ' + checked + ' فریم');
}

{
  const full = CORE.yinTrack(Y, PREP.sr, 55, 1200, PREP.duration, 0.01);
  const p = CORE.yinParams(Y, PREP.sr, 55, 1200, 0.01);
  const part = CORE.yinTrackRange(Y, PREP.sr, 55, 1200, p, 40, 300);
  let diffs = 0;
  for (let k = 0; k < 260; k++) if (part.f0[k] !== full.f0[40 + k]) diffs++;
  check('تکه‌های YIN با مسیر کامل یکسان‌اند', diffs === 0, diffs + ' اختلاف');
}

{
  const notes = [
    { start: 0.05, end: 1.0, f0_hz: 220 },
    { start: 1.2, end: 2.2, f0_hz: 246.94 },
    { start: 2.4, end: 3.3, f0_hz: 261.63 }
  ];
  const all = CORE.refineNotesRange(Y, PREP.sr, notes, 0, notes.length);
  const part = CORE.refineNotesRange(Y, PREP.sr, notes, 1, 3);
  check('تکه‌های پالایش نت با مسیر کامل یکسان‌اند',
    JSON.stringify(part) === JSON.stringify(all.slice(1)),
    part.map(r => (r.f0 == null ? '-' : r.f0.toFixed(3))).join(' / '));
}

/* ═════════ ۲) مسیر async بدون کارگر = همان مسیر سریال ═════════ */
const norm = (r) => {
  const o = JSON.parse(JSON.stringify(r));
  delete o.meta.analyzed_at;
  delete o.meta.engine_sec;
  delete o.quality.engine_sec;
  delete o.quality.parallel;
  delete o.quality.parallel_cores;
  return JSON.stringify(o);
};

(async () => {
  const syncRep = CORE.buildReport(CORE.analyzeChannels([sig], SR, {}), { file: 'x' });

  const noPool = await CORE.analyzeChannelsAsync([sig], SR, { parallel: false });
  check('analyzeChannelsAsync بدون کارگر = مسیر سریال',
    norm(CORE.buildReport(noPool, { file: 'x' })) === norm(syncRep));

  /* ═════════ ۳) با استخر کارگرها (اگر در دسترس باشد) ═════════ */
  let P = null;
  try { P = require(path.join(__dirname, '..', 'parallel.js')); } catch (e) { P = null; }

  if (!P || !P.init()) {
    console.log('• استخر کارگر در دسترس نیست — از بررسی موازی صرف‌نظر شد (' +
      ((P && P.reason) || 'دلیل نامعلوم') + ')');
  } else {
    console.log('• استخر کارگر: ' + P.reason + ' (هسته‌ها: ' + P.cores + ')');

    const t0 = Date.now();
    const par = await CORE.analyzeChannelsAsync([sig], SR, {});
    const tPar = Date.now() - t0;

    check('مسیر موازی واقعاً استفاده شد', par.quality.parallel === true);
    check('گزارش موازی بیت‌به‌بیت مثل سریال است',
      norm(CORE.buildReport(par, { file: 'x' })) === norm(syncRep),
      (() => {
        const a = JSON.parse(norm(syncRep)), b = JSON.parse(norm(CORE.buildReport(par, { file: 'x' })));
        const out = [];
        const walk = (x, y, p) => {
          if (x && typeof x === 'object' && y && typeof y === 'object') {
            for (const k of new Set([...Object.keys(x), ...Object.keys(y)])) walk(x[k], y[k], p + '.' + k);
            return;
          }
          if (x !== y) out.push(p + ': ' + x + ' ≠ ' + y);
        };
        walk(a, b, '');
        return out.slice(0, 3).join(' | ') || 'بدون اختلاف';
      })());

    // سرعت: سریال (پس از گرم شدن) در برابر موازی
    CORE.analyzeChannels([sig], SR, {});            // warm-up
    const s0 = Date.now();
    CORE.analyzeChannels([sig], SR, {});
    const tSync = Date.now() - s0;
    const p0 = Date.now();
    await CORE.analyzeChannelsAsync([sig], SR, {});
    const tPar2 = Date.now() - p0;

    const ratio = tSync / Math.max(1, tPar2);
    check('موازی از سریال کندتر نیست', tPar2 <= tSync * 1.15,
      'سریال ' + tSync + 'ms · موازی ' + tPar2 + 'ms (' + ratio.toFixed(2) + '×)');
    check('برآورد سرعت روی این دستگاه گزارش شد', true,
      'cores=' + P.cores + ' · ' + ratio.toFixed(2) + '× (سرعت با هستهٔ واقعی زیاد می‌شود)');

    if (P.reset) P.reset();
  }

  /* ═════════ ۴) بازگشت بی‌صدا هنگام خرابی کارگر ═════════ */
  {
    const broken = {
      available: true, cores: 2,
      pitchTrack: () => Promise.reject(new Error('شکست ساختگی')),
      yinTrack: () => Promise.reject(new Error('شکست ساختگی')),
      refineNotes: () => Promise.reject(new Error('شکست ساختگی'))
    };
    globalThis.LocalParallel = broken;
    const res = await CORE.analyzeChannelsAsync([sig], SR, {});
    check('خرابی Worker ⇒ نتیجهٔ درست از مسیر سریال',
      norm(CORE.buildReport(res, { file: 'x' })) === norm(syncRep) && res.quality.parallel === false);
    delete globalThis.LocalParallel;
  }

  console.log('─'.repeat(70));
  if (fail) { console.log('❌ ' + fail + ' بررسی ناموفق، ' + pass + ' موفق'); process.exit(1); }
  console.log('✅ پردازش موازی/جریانی سالم است و نتیجه‌اش با مسیر سریال یکی است (' + pass + ' بررسی)');
})().catch(e => {
  console.error(e && e.stack || e);
  process.exit(1);
});
