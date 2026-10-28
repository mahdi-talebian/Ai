#!/usr/bin/env node
/* ============================================================================
   تست دقت استخراج نت با «حقیقت زمینی» دقیق
   ----------------------------------------------------------------------------
   سه اجرای مصنوعی با ویبراتو، پورتامنتو، نویز و نُت‌های کوتاه ساخته می‌شود و
   خروجی موتور با نت‌های واقعی مقایسه می‌شود:
     • خطای کلاس اکتاو (قفل روی نصف/یک‌سوم فرکانس) باید صفر باشد
     • خطای کوک (سنت) در حد چند سنت
     • مرز شروع نت‌ها باید نزدیک لبهٔ واقعی صدا باشد (چفت‌شدن روی پوش انرژی)
     • همهٔ نت‌های خوانده‌شده پیدا شوند و نت اضافه ساخته نشود

   اجرا:  node web/tests/accuracy_test.js
   ========================================================================== */
'use strict';
const E = require('../engine.js');

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra ? '  → ' + extra : '')); }
  else { fail++; console.log('✗ ' + name + (extra ? '  → ' + extra : '')); }
}

/* ── تولید صدا با حقیقت زمینی ─────────────────────────────────────────── */
function rng(seed) { let s = seed >>> 0; return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }
function gauss(r) { let u = 0, v = 0; while (!u) u = r(); while (!v) v = r(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }

function reson(y, sr, fc, bw, g) {
  const r = Math.exp(-Math.PI * bw / sr), th = 2 * Math.PI * fc / sr;
  const b0 = 1 - r, a1 = -2 * r * Math.cos(th), a2 = r * r;
  const out = new Float32Array(y.length); let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < y.length; i++) {
    const x0 = y[i] * g, v = b0 * x0 - a1 * y1 - a2 * y2;
    out[i] = v + 0.25 * x0; x2 = x1; x1 = x0; y2 = y1; y1 = v;
  }
  return out;
}
function vowel(y, sr) {
  let s = reson(y, sr, 700, 130, 1.0);
  s = reson(s, sr, 1220, 160, 0.7);
  return reson(s, sr, 2600, 250, 0.35);
}

function sing(spec) {
  const r = rng(spec.seed || 11);
  const sr = spec.sr;
  const total = spec.notes.reduce((a, [, d]) => a + d, 0) + spec.gap * spec.notes.length + 0.4;
  const y = new Float64Array(Math.round(sr * total));
  const truth = [];
  let tCur = 0.2;
  for (const [semi, dur] of spec.notes) {
    const f0 = spec.tonic * Math.pow(2, semi / 12);
    const n = Math.round(sr * dur);
    const src = new Float64Array(n);
    let phase = 0;
    const vibAmp = (spec.vibCents || 40) / 100;
    for (let i = 0; i < n; i++) {
      const t = i / sr;
      const ramp = Math.min(1, Math.max(0, (t - 0.12) / 0.25));
      const vib = Math.pow(2, (vibAmp * Math.sin(2 * Math.PI * (spec.vibHz || 5.5) * t) * ramp) / 12);
      const glide = t < 0.06 ? Math.pow(2, (-0.6 * (1 - t / 0.06)) / 12) : 1;
      phase += 2 * Math.PI * f0 * vib * glide / sr;
      let s = 0; for (let k = 1; k <= 13; k++) s += Math.sin(k * phase) / k;
      const env = Math.min(1, Math.min(t / 0.025, (dur - t) / 0.04));
      src[i] = s * Math.max(0, env) + 0.01 * gauss(r) * Math.max(0, env);
    }
    const seg = vowel(src, sr);
    const i0 = Math.round(sr * tCur);
    for (let i = 0; i < n && i0 + i < y.length; i++) y[i0 + i] += seg[i];
    truth.push({ start: tCur, end: tCur + dur, f0, midi: E.hzToMidi(f0) });
    tCur += dur + spec.gap;
  }
  let peak = 0; for (const v of y) peak = Math.max(peak, Math.abs(v));
  const out = new Float32Array(y.length);
  for (let i = 0; i < y.length; i++) out[i] = y[i] / (peak + 1e-9) * 0.6 + (spec.noise || 0) * gauss(r);
  return { channels: [out], sr, truth };
}

function measure(truth, notes) {
  const used = new Array(notes.length).fill(false);
  const on = [], cents = [];
  let oct = 0, matched = 0;
  for (const gt of truth) {
    let best = -1, bd = 1e9;
    for (let i = 0; i < notes.length; i++) {
      if (used[i]) continue;
      const d = Math.abs(notes[i].start - gt.start);
      if (d < bd && d <= 0.15) { bd = d; best = i; }
    }
    if (best < 0) continue;
    used[best] = true; matched++;
    const n = notes[best];
    on.push(Math.abs(n.start - gt.start));
    const c = 1200 * Math.log2(n.f0_hz / gt.f0);
    if (Math.abs(Math.abs(c) - 1200) < 150) oct++;
    else cents.push(Math.abs(c));
  }
  const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : 0;
  return { matched, extra: notes.filter((_, i) => !used[i]).length, oct, onsetMs: mean(on) * 1000, cents: mean(cents) };
}

/* ── ۱) اصلاح کلاس اکتاو (واحد) ───────────────────────────────────────── */
{
  /* نت ۲۲۰Hz با هارمونیک‌های کامل؛ ردیاب اشتباهاً ۱۱۰Hz داده است */
  const sr = 22050, dur = 0.8;
  const n = Math.round(sr * dur);
  const y = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    let s = 0;
    for (let k = 1; k <= 12; k++) s += Math.sin(2 * Math.PI * 220 * k * t) / k;
    y[i] = 0.4 * s * Math.min(1, Math.min(t / 0.02, (dur - t) / 0.03));
  }
  const fixed = E.refineNoteF0WithOctave(y, sr, 0.05, dur - 0.05, 110);
  check('f0 نصف‌شده به اکتاو درست برمی‌گردد',
    fixed.f0 != null && Math.abs(1200 * Math.log2(fixed.f0 / 220)) < 25,
    fixed.f0 ? fixed.f0.toFixed(1) + 'Hz' : 'null');

  /* نتِ درست (۲۲۰) نباید دو برابر شود */
  const keep = E.refineNoteF0WithOctave(y, sr, 0.05, dur - 0.05, 220);
  check('نتِ درست دو برابر نمی‌شود',
    keep.f0 != null && Math.abs(1200 * Math.log2(keep.f0 / 220)) < 25,
    keep.f0 ? keep.f0.toFixed(1) + 'Hz' : 'null');
}

/* ── ۲) چفت‌شدن مرز روی لبهٔ انرژی (واحد) ─────────────────────────────── */
{
  const sr = 22050;
  const y = new Float32Array(sr);
  const start = 0.30, end = 0.70;
  for (let i = Math.round(start * sr); i < Math.round(end * sr); i++) {
    const t = i / sr;
    y[i] = 0.5 * Math.sin(2 * Math.PI * 220 * t) * Math.min(1, Math.min((t - start) / 0.01, (end - t) / 0.01));
  }
  /* موتور مرز را ۳۰ms زودتر گزارش کرده است */
  const notes = [{ start: start - 0.03, end: end + 0.03, duration: 0.46, f0_hz: 220,
    confidence: 0.9, stability_cents: 10, n_frames: 40 }];
  const snapped = E.snapNoteBoundaries(y, sr, notes);
  check('شروع نت روی لبهٔ واقعی صدا چفت می‌شود',
    Math.abs(snapped[0].start - start) < 0.02, ((snapped[0].start - start) * 1000).toFixed(0) + 'ms');
  check('پایان نت هم چفت می‌شود',
    Math.abs(snapped[0].end - end) < 0.03, ((snapped[0].end - end) * 1000).toFixed(0) + 'ms');
  check('مدت نت پس از چفت‌شدن درست است',
    Math.abs(snapped[0].duration - 0.40) < 0.03, snapped[0].duration.toFixed(3) + 's');
}

/* ── ۳) اجرای کامل: سه محدودهٔ صدا ────────────────────────────────────── */
const cases = [
  { name: 'میانه (زن/مرد)', tonic: 220, sr: 44100, gap: 0.16, vibCents: 35,
    notes: [[0, .55], [2, .45], [3, .6], [5, .5], [7, .65], [5, .45], [3, .5], [2, .4], [0, .7], [12, .55], [10, .45], [7, .55], [5, .4], [0, .75]] },
  { name: 'بم (مرد)', tonic: 110, sr: 22050, gap: 0.14, vibCents: 45, noise: 0.012,
    notes: [[0, .6], [3, .5], [5, .7], [7, .5], [5, .6], [3, .55], [0, .8], [7, .6], [5, .5], [3, .6], [0, .7]] },
  { name: 'زیر (زن)', tonic: 392, sr: 44100, gap: 0.12, vibCents: 50, noise: 0.008,
    notes: [[0, .5], [2, .45], [4, .6], [5, .5], [7, .55], [5, .45], [4, .5], [2, .45], [0, .6], [7, .5], [4, .55], [0, .7]] }
];

for (const c of cases) {
  const d = sing(c);
  const res = E.analyzeChannels(d.channels, d.sr, {});
  const m = measure(d.truth, res.notes);
  console.log('· ' + c.name + ': ' + m.matched + '/' + d.truth.length + ' نت، ' +
    m.onsetMs.toFixed(0) + 'ms شروع، ' + m.cents.toFixed(1) + ' سنت، اکتاو=' + m.oct);
  check(c.name + ': همهٔ نت‌ها پیدا شدند', m.matched === d.truth.length, m.matched + '/' + d.truth.length);
  check(c.name + ': نت اضافه ساخته نشد', m.extra === 0, 'اضافه=' + m.extra);
  check(c.name + ': خطای اکتاو صفر', m.oct === 0, 'اکتاو=' + m.oct);
  check(c.name + ': خطای کوک زیر ۸ سنت', m.cents < 8, m.cents.toFixed(1) + ' سنت');
  check(c.name + ': خطای شروع زیر ۲۰ms', m.onsetMs < 20, m.onsetMs.toFixed(0) + 'ms');
}

console.log('─'.repeat(70));
if (!fail) console.log('✅ دقت استخراج نت (اکتاو، کوک، مرزها) در محدودهٔ هدف است (' + pass + ' بررسی)');
else console.log('❌ ' + fail + ' بررسی ناموفق از ' + (pass + fail));
process.exit(fail ? 1 : 0);
