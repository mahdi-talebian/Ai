#!/usr/bin/env node
/* ============================================================================
   تست «موتور نت»: مسیر قدیمی (هیسترزیس) در برابر مسیر ۱٫۲٫۴ (Viterbi)
   ----------------------------------------------------------------------------
   همه‌چیز داخل همین ریپو و روی موتور آفلاین (`web/engine.js`) اجرا می‌شود؛
   هیچ سرور/اینترنتی در کار نیست. برای هر حالت، اجرای مصنوعی با حقیقت زمینی
   ساخته می‌شود و F1 دو مسیر مقایسه می‌شود:

     • ویبراتوی پهن (±۹۰ سنت) — جایی که قاعدهٔ آستانه‌ای می‌شکست
     • اجرای سریع (نت‌های ۱۳۰–۲۰۰ میلی‌ثانیه)
     • نویز ۶٪، لگاتو (فاصلهٔ ۴۰ms)، و اجرای مرجع

   اجرا:  node web/tests/note_engine_test.js
   ========================================================================== */
'use strict';
const E = require('../engine.js');

/* ── تولید اجرا با حقیقت زمینی (همان سبک accuracy_test) ───────────────── */
function rng(seed) { let s = seed >>> 0; return () => { s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0; return s / 4294967296; }; }
function gauss(r) { let u = 0, v = 0; while (!u) u = r(); while (!v) v = r(); return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v); }

function reson(y, sr, fc, bw, g) {
  const r = Math.exp(-Math.PI * bw / sr), th = 2 * Math.PI * fc / sr;
  const b0 = 1 - r, a1 = -2 * r * Math.cos(th), a2 = r * r;
  const out = new Float32Array(y.length);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
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
  const sr = spec.sr || 44100;
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
      phase += 2 * Math.PI * f0 * vib / sr;
      let s = 0; for (let k = 1; k <= 13; k++) s += Math.sin(k * phase) / k;
      const env = Math.min(1, Math.min(t / 0.025, (dur - t) / 0.04));
      src[i] = s * Math.max(0, env) + 0.01 * gauss(r) * Math.max(0, env);
    }
    const seg = vowel(src, sr);
    const i0 = Math.round(sr * tCur);
    for (let i = 0; i < n && i0 + i < y.length; i++) y[i0 + i] += seg[i];
    truth.push({ start: tCur, f0 });
    tCur += dur + spec.gap;
  }
  let peak = 0; for (const v of y) peak = Math.max(peak, Math.abs(v));
  const out = new Float32Array(y.length);
  for (let i = 0; i < y.length; i++) out[i] = y[i] / (peak + 1e-9) * 0.6 + (spec.noise || 0) * gauss(r);
  return { channels: [out], sr, truth };
}

/* ── خط لولهٔ کامل، با انتخاب موتور نت ─────────────────────────────────── */
function run(d, engine) {
  const prep = E.prepareAudio(d.channels, d.sr, {});
  const tr = E.pitchTrack(prep.y, prep.sr, 55, 1200);
  const oct = E.spectralOctaveFix(prep.y, prep.sr, tr.times, tr.freqs, 55, 1200);
  const second = E.yinTrack(prep.y, prep.sr, 55, 1200, prep.duration, 0.01);
  const fused = E.fuseWithSecond(tr.times, oct.freqs, tr.strength, second, 0.01);
  let freqs = E.removeOctaveSpecks(tr.times, fused.freqs);
  freqs = E.smoothPitchContour(freqs, 3);
  let notes;
  if (engine === 'viterbi') {
    const fl = E.spectralFlux(prep.y, prep.sr, {});
    notes = E.viterbiNotes(tr.times, freqs, fused.conf, { onsets: fl.onsets });
  } else {
    notes = E.segmentNotes(tr.times, freqs, fused.conf, {});
  }
  notes = E.snapNoteBoundaries(prep.y, prep.sr, notes);
  notes = E.refineNotes(prep.y, prep.sr, notes).notes;
  return { notes, points: score(d.truth, notes) };
}

/* ── سنجش: هر نت واقعی با نزدیک‌ترین شروع در ۱۰۰ms تطبیق می‌شود ────────── */
function score(truth, notes) {
  const used = new Array(notes.length).fill(false);
  let matched = 0, octErr = 0, centsSum = 0, onsetSum = 0;
  for (const gt of truth) {
    let best = -1, bd = 1e9;
    for (let i = 0; i < notes.length; i++) {
      if (used[i]) continue;
      const dd = Math.abs(notes[i].start - gt.start);
      if (dd < bd && dd <= 0.10) { bd = dd; best = i; }
    }
    if (best < 0) continue;
    used[best] = true; matched++;
    const c = 1200 * Math.log2(notes[best].f0_hz / gt.f0);
    onsetSum += bd;
    if (Math.abs(Math.abs(c) - 1200) < 120) octErr++;
    else centsSum += Math.abs(c);
  }
  const extra = notes.filter((_, i) => !used[i]).length;
  const P = notes.length ? matched / notes.length : 0;
  const R = truth.length ? matched / truth.length : 0;
  const F1 = (P + R) ? 2 * P * R / (P + R) : 0;
  return {
    F1: Math.round(F1 * 1000) / 10,
    miss: truth.length - matched, extra, oct: octErr,
    onsetMs: matched ? onsetSum / matched * 1000 : 0,
    cents: matched ? centsSum / matched : 0
  };
}

/* ── حالت‌ها ───────────────────────────────────────────────────────────── */
const SEQ_A = [[0, .55], [2, .45], [3, .6], [5, .5], [7, .65], [5, .45], [3, .5], [2, .4], [0, .7], [12, .55], [10, .45], [7, .55], [5, .4], [0, .75]];
const FAST = [[0, .16], [2, .14], [4, .16], [5, .13], [7, .15], [5, .14], [4, .16], [2, .13], [0, .18], [7, .15], [4, .14], [0, .2]];

const cases = [
  ['مرجع (۱۴ نت)', { tonic: 220, gap: 0.16, vibCents: 35, notes: SEQ_A }],
  ['ویبراتوی پهن ±۹۰¢', { tonic: 220, gap: 0.10, vibCents: 90, notes: SEQ_A.slice(0, 11) }],
  ['سریع (۱۳۰–۲۰۰ms)', { tonic: 220, gap: 0.03, vibCents: 30, notes: FAST }],
  ['نویز ۶٪', { tonic: 220, gap: 0.16, vibCents: 35, noise: 0.06, notes: SEQ_A }],
  ['لگاتو (فاصلهٔ ۴۰ms)', { tonic: 220, gap: 0.04, vibCents: 35, notes: SEQ_A }]
];

let pass = 0, fail = 0;
const pad = (s, n) => { s = String(s); return s + ' '.repeat(Math.max(0, n - s.length)); };

console.log('تست موتور نت — مسیر قدیمی (segment) در برابر Viterbi');
console.log('─'.repeat(78));
console.log(pad('حالت', 20) + pad('segment: F1/کم/اضافه', 26) + 'viterbi: F1/کم/اضافه');

for (const [name, spec] of cases) {
  const d = sing(spec);
  const oldRun = run(d, 'segment');
  const newRun = run(d, 'viterbi');
  const f = m => pad(m.F1.toFixed(1) + ' / ' + m.miss + ' / ' + m.extra, 26);
  console.log(pad(name, 20) + f(oldRun.points) + f(newRun.points));

  if (newRun.points.F1 < 95) { fail++; console.log('  ✖ F1 زیر ۹۵ در حالت «' + name + '»'); }
  else pass++;
  if (newRun.points.oct !== 0) { fail++; console.log('  ✖ خطای اکتاو در «' + name + '»: ' + newRun.points.oct); }
  else pass++;
  if (newRun.points.F1 + 0.05 < oldRun.points.F1) { fail++; console.log('  ✖ مسیر جدید بدتر از قدیمی شد'); }
  else pass++;
}

/* موتور پیش‌فرض خط لوله باید Viterbi باشد و fallback داشته باشد */
{
  const d = sing({ tonic: 220, gap: 0.16, vibCents: 35, notes: SEQ_A.slice(0, 6) });
  const auto = E.analyzeChannels(d.channels, d.sr, {});
  if (auto.quality && auto.quality.note_engine === 'viterbi') pass++;
  else { fail++; console.log('  ✖ موتور پیش‌فرض Viterbi نیست: ' + (auto.quality && auto.quality.note_engine)); }
  const forced = E.analyzeChannels(d.channels, d.sr, { note_engine: 'segment' });
  if (forced.quality && forced.quality.note_engine === 'segment') pass++;
  else { fail++; console.log('  ✖ انتخاب دستی موتور کار نکرد'); }
}

console.log('─'.repeat(78));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ موتور Viterbi روی همهٔ حالت‌ها ≥ مسیر قدیمی و F1 ≥ ۹۵ است (' + pass + ' بررسی)');
