#!/usr/bin/env node
/* ============================================================================
   تست «دقتِ گامِ نت‌ها» — اصلاحِ خطای اکتاو
   ----------------------------------------------------------------------------
   مشکلی که روی فایلِ واقعیِ خواننده دیده شد: چند نُتِ کوتاه یک اکتاو بالاتر از
   ملودی گزارش می‌شدند (قفلِ تخمین روی هارمونیکِ دوم).
   این تست سه چیز را می‌سنجد:
     ۱) نُتِ کوتاهِ یک‌اکتاو-بالا که شواهدِ طیفی پشتیبانش نیست → به اکتاوِ درست برمی‌گردد
     ۲) نُتِ کشیدهٔ واقعیِ یک اکتاو بالاتر → دست‌نخورده می‌ماند
     ۳) تونِ با فوندامانتالِ ضعیف (تلهٔ کلاسیکِ خطای اکتاو) درست خوانده می‌شود
     ۴) شمارندهٔ اصلاح در `quality.notes_octave_corrected` گزارش می‌شود
   اجرا:  node web/tests/note_octave_test.js
   ========================================================================== */
'use strict';
global.window = global;
global.document = { createElement: () => ({ getContext: () => ({}) }) };
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL; global.URLSearchParams = URLSearchParams;
require('../engine.js');
const CORE = global.LocalEngineCore;

let pass = 0, fail = 0;
function check(name, ok, x) {
  if (ok) { pass++; console.log('✓ ' + name + (x !== undefined ? '  → ' + x : '')); }
  else { fail++; console.log('✖ ' + name + (x !== undefined ? '  → ' + x : '')); }
}
const SR = 22050;
const midiOf = f => 69 + 12 * Math.log2(f / 440);

/* بومِ صوتیِ ساده: هر بخش یک تونِ هارمونیک با پوشِ نرم */
function build(segments, harmonics) {
  const total = segments.reduce((a, s) => a + s.dur, 0) + 0.3;
  const y = new Float32Array(Math.round(total * SR));
  let t = 0;
  segments.forEach(s => {
    const i0 = Math.round(t * SR), i1 = Math.round((t + s.dur) * SR);
    const harm = s.harm || harmonics;
    for (let i = i0; i < i1; i++) {
      const local = (i - i0) / SR;
      const env = Math.min(1, local / 0.03) * Math.min(1, (s.dur - local) / 0.05);
      let v = 0;
      harm.forEach((a, k) => { v += a * Math.sin(2 * Math.PI * s.f * (k + 1) * (i / SR)); });
      y[i] = 0.35 * env * v;
    }
    t += s.dur;
  });
  return y;
}

/* ── ۱) اصلاحِ نُتِ کوتاهِ اکتاو-بالا با «زمینهٔ ملودی» ────────────────── */
{
  const silence = new Float32Array(SR * 3);
  const notes = [
    { start: 0.10, end: 1.10, duration: 1.00, f0_hz: 196.0, confidence: 0.8, n_frames: 1000 },
    { start: 1.12, end: 1.26, duration: 0.14, f0_hz: 392.0, confidence: 0.7, n_frames: 14 },
    { start: 1.28, end: 2.50, duration: 1.22, f0_hz: 196.0, confidence: 0.8, n_frames: 1200 }
  ];
  const fixed = CORE.fixNoteOctavesByContext(silence, SR, notes);
  const mid = midiOf(notes[1].f0_hz);
  check('نُتِ کوتاهِ یک‌اکتاو-بالا به اکتاوِ درست برگردانده می‌شود',
    fixed === 1 && Math.abs(mid - midiOf(196)) < 0.2,
    'اصلاح‌شده=' + fixed + ' · گامِ نُت=' + mid.toFixed(1) + ' (باید ≈' + midiOf(196).toFixed(1) + ')');
  check('نُتِ اصلاح‌شده علامت‌گذاری و گامِ اولیه‌اش نگه داشته می‌شود',
    notes[1].octave_corrected === true && Math.abs(notes[1].f0_hz_coarse - 392) < 0.5,
    'f0_hz_coarse=' + notes[1].f0_hz_coarse);
}

/* ── ۲) پرشِ واقعیِ اکتاو (نُتِ کشیده) دست‌نخورده می‌ماند ──────────────── */
{
  const silence = new Float32Array(SR * 4);
  const notes = [
    { start: 0.10, end: 1.20, duration: 1.10, f0_hz: 196.0, confidence: 0.8, n_frames: 1100 },
    { start: 1.22, end: 2.12, duration: 0.90, f0_hz: 392.0, confidence: 0.85, n_frames: 900 },
    { start: 2.14, end: 3.30, duration: 1.16, f0_hz: 196.0, confidence: 0.8, n_frames: 1100 }
  ];
  const fixed = CORE.fixNoteOctavesByContext(silence, SR, notes);
  check('نُتِ کشیدهٔ یک اکتاو بالاتر (پرشِ واقعی) دست‌نخورده می‌ماند',
    fixed === 0 && Math.abs(midiOf(notes[1].f0_hz) - 67.0) < 0.2,
    'اصلاح‌شده=' + fixed + ' · گام=' + midiOf(notes[1].f0_hz).toFixed(1));
}

/* ── ۳) تونِ با فوندامانتالِ ضعیف: تلهٔ کلاسیکِ خطای اکتاو ─────────────── */
{
  const weakFund = [0.06, 1.0, 0.85, 0.55, 0.35, 0.22];
  const y = build([{ f: 196, dur: 2.2 }], weakFund);
  const res = CORE.analyzeChannels([y], SR, {});
  let worst = 0;
  res.notes.forEach(n => { worst = Math.max(worst, Math.abs(midiOf(n.f0_hz) - midiOf(196))); });
  check('تونِ ۱۹۶Hz با فوندامانتالِ ضعیف یک اکتاو بالا گزارش نمی‌شود',
    res.notes.length >= 1 && worst < 0.6,
    res.notes.length + ' نت · بیشترین خطا=' + worst.toFixed(2) + ' نیم‌پرده');

  /* پرشِ واقعیِ ۰٫۹ ثانیه‌ای در همان صدا باید بماند */
  const obs = [0.6, 1.0, 0.5, 0.3, 0.2, 0.1];
  const y2 = build([
    { f: 196, dur: 1.2 }, { f: 392, dur: 0.9, harm: obs }, { f: 196, dur: 1.2 }
  ], weakFund);
  const res2 = CORE.analyzeChannels([y2], SR, {});
  const high = res2.notes.filter(n => midiOf(n.f0_hz) > 63 && n.duration >= 0.5);
  check('پرشِ واقعیِ ۰٫۹ ثانیه‌ای در تحلیلِ کامل حفظ می‌شود', high.length === 1,
    high.length + ' نتِ بالا · ' + res2.notes.map(n => midiOf(n.f0_hz).toFixed(0)).join(','));
  check('شمارندهٔ اصلاحِ اکتاو در کیفیت گزارش می‌شود',
    typeof res2.quality.notes_octave_corrected === 'number',
    'notes_octave_corrected=' + res2.quality.notes_octave_corrected);
}

/* ── ۴) رَدِّ تیونرمانند: هیچ پرشِ عمودیِ فریم‌به‌فریم در خروجیِ رسم ────── */
{
  const weak = [0.06, 1.0, 0.85, 0.55, 0.35, 0.22];
  /* تونِ ۲۲۰Hz که در میانه‌اش ۰٫۱۵s روی هارمونیکِ دوم می‌پرد */
  const total = 3.0, n = Math.round(SR * total);
  const y = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    const t = i / SR;
    const locked = (t > 1.2 && t < 1.35);
    const f = locked ? 440 : 220;
    ph += 2 * Math.PI * f / SR;
    let s = 0;
    const harm = locked ? [0.9, 1.0, 0.4, 0.2, 0.1, 0.05] : weak;
    harm.forEach((a, k) => { s += a * Math.sin((k + 1) * ph); });
    const env = Math.min(1, t / 0.02) * Math.min(1, (total - t) / 0.05);
    y[i] = 0.3 * s * Math.max(0, env);
  }
  const res = CORE.analyzeChannels([y], SR, {});
  const trace = res.trace_freqs || res.freqs;
  let maxJump = 0, at = 0;
  for (let i = 1; i < res.times.length; i++) {
    if (!(trace[i] > 0) || !(trace[i - 1] > 0)) continue;
    if (res.times[i] - res.times[i - 1] > 0.06) continue;
    const d = Math.abs(69 + 12 * Math.log2(trace[i] / 440) - (69 + 12 * Math.log2(trace[i - 1] / 440)));
    if (d > maxJump) { maxJump = d; at = res.times[i]; }
  }
  check('رَدِّ رسم دیگر پرشِ فریم‌به‌فریمِ بزرگ ندارد',
    maxJump <= 1.5, 'بیشترین پرش=' + maxJump.toFixed(2) + ' نیم‌پرده @ ' + at.toFixed(2) + 's');
  const cont = CORE.buildReport(res, {}).contour;
  let cJump = 0;
  for (let i = 1; i < cont.midi.length; i++) {
    if (cont.midi[i] == null || cont.midi[i - 1] == null) continue;
    cJump = Math.max(cJump, Math.abs(cont.midi[i] - cont.midi[i - 1]));
  }
  check('کانتورِ گزارش هم بدونِ پرشِ عمودی است', cJump <= 2.5,
    'بیشترین پرش=' + cJump.toFixed(2) + ' نیم‌پرده');
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ دقتِ گامِ نت‌ها (اصلاحِ خطای اکتاو) سالم است (' + pass + ' بررسی)');
process.exit(0);
