#!/usr/bin/env node
'use strict';
const E = require('../engine.js');
const { synthVoice, SCORE, TONIC, EXPECT_HI, EXPECT_LO, EXPECT_RAW_HI, SR_GEN } = require('./synth.js');

/* ── ارزیابی ──────────────────────────────────────────────────────────── */
function noteName(hz) { return E.midiToName(E.hzToMidi(hz)); }

function evaluate(label, data, opts) {
  const t0 = Date.now();
  const res = E.analyzeChannels(data.channels, data.sr, Object.assign({ onProgress: () => {} }, opts || {}));
  const rep = E.buildReport(res, { file: label, original_name: label + '.wav', denoise: false });
  const ms = Date.now() - t0;

  const hi = rep.range.highest, lo = rep.range.lowest;
  const hiOk = hi && Math.abs(hi.f0_hz - EXPECT_HI) / EXPECT_HI < 0.03;
  const loOk = lo && Math.abs(lo.f0_hz - EXPECT_LO) / EXPECT_LO < 0.03;
  const raw = rep.range.raw_highest;
  const rawOk = raw && Math.abs(raw.f0_hz - EXPECT_RAW_HI) / EXPECT_RAW_HI < 0.03;

  // دقت نت: هر نتِ تشخیص‌داده‌شده به نزدیک‌ترین نتِ امتیاز نسبت داده می‌شود
  const expected = SCORE.map(([semi]) => TONIC * Math.pow(2, semi / 12));
  let correct = 0, centsErrs = [];
  for (const n of rep.notes) {
    let best = null, bestD = Infinity;
    for (const f of expected) {
      const d = Math.abs(1200 * Math.log2(n.f0_hz / f));
      if (d < bestD) { bestD = d; best = f; }
    }
    if (bestD < 50) { correct++; centsErrs.push(Math.abs(1200 * Math.log2(n.f0_hz / best))); }
  }
  centsErrs.sort((a, b) => a - b);
  const medCents = centsErrs.length ? centsErrs[centsErrs.length >> 1] : NaN;

  return {
    label, ms, rawOk,
    hi: hi ? hi.note_name : '-', lo: lo ? lo.note_name : '-',
    hiOk, loOk,
    span: rep.range.span_semitones,
    notes: rep.notes.length,
    correctPct: rep.notes.length ? Math.round(100 * correct / rep.notes.length) : 0,
    medCents: isFinite(medCents) ? Math.round(medCents * 10) / 10 : NaN,
    quality: rep.quality.status,
    agree: rep.quality.pyin_agreement_pct,
    report: rep,
  };
}

const CASES = [['clean', null], ['16k', '16k'], ['8k', '8k'], ['stereo_phase', 'stereo_phase'],
  ['quiet', 'quiet'], ['noisy', 'noisy'], ['rumble', 'rumble'], ['clip', 'clip'],
  ['phone', 'phone'], ['hum', 'hum']];

const verbose = process.argv.includes('-v');
let fails = 0;
const rows = [];
const reports = {};

console.log('موتور آفلاین — بنچمارک نت و دقت');
console.log('─'.repeat(96));
console.log('نسخه            بالا/پایین    گستره   نت‌ها  درست   کوک(سنت)  هم‌رأیی  کیفیت   زمان');
for (const [label, deg] of CASES) {
  const data = synthVoice(deg);
  const r = evaluate(label, data);
  reports[label] = r.report;
  rows.push(r);
  if (!r.hiOk || !r.loOk || !r.rawOk) fails++;
  if (r.correctPct < 85) fails++;
  console.log(
    label.padEnd(15) +
    `${r.hi}/${r.lo}`.padEnd(13) +
    String(r.span).padEnd(8) +
    String(r.notes).padEnd(6) +
    (r.correctPct + '%').padEnd(7) +
    String(r.medCents).padEnd(10) +
    String(r.agree).padEnd(8) +
    r.quality.padEnd(9) +
    (r.ms / 1000).toFixed(1) + 's'
  );
}

// یکسانی گستره بین همهٔ نسخه‌ها
const spans = rows.map(r => r.span);
const spanSpread = Math.max(...spans) - Math.min(...spans);
console.log('─'.repeat(96));
console.log('پراکندگی گستره بین نسخه‌ها:', spanSpread.toFixed(2), 'نیم‌پرده');
if (spanSpread > 0.6) { console.log('✖ گستره بین نسخه‌ها ناهمخوان است'); fails++; }

// تغییرهای «بی‌ربط»: سکوت اضافه در ابتدا، نصف بلندی، قطبیت برعکس
function withLeadingSilence(data, sec) {
  const n = Math.round(data.sr * sec);
  const out = new Float32Array(n + data.channels[0].length);
  out.set(data.channels[0], n);
  return { channels: [out], sr: data.sr };
}
function scaled(data, g) {
  return { channels: data.channels.map(c => c.map(v => v * g)), sr: data.sr };
}
function inverted(data) {
  return { channels: data.channels.map(c => c.map(v => -v)), sr: data.sr };
}

const base = synthVoice(null);
const baseEval = rows[0];
const variants = [
  ['+۲ ثانیه سکوت', withLeadingSilence(base, 2.0)],
  ['+۶۰ ثانیه سکوت', withLeadingSilence(base, 60.0)],
  ['نصف بلندی', scaled(base, 0.5)],
  ['قطبیت برعکس', inverted(base)],
];
console.log('─'.repeat(96));
for (const [label, data] of variants) {
  const r = evaluate(label, data);
  const dSpan = Math.abs(r.span - baseEval.span);
  const dHi = Math.abs(1200 * Math.log2(((r.report.range.highest || {}).f0_hz || 1) / ((baseEval.report.range.highest || {}).f0_hz || 1)));
  const dLo = Math.abs(1200 * Math.log2(((r.report.range.lowest || {}).f0_hz || 1) / ((baseEval.report.range.lowest || {}).f0_hz || 1)));
  const same = dSpan <= 0.05 && dHi < 3 && dLo < 3 && r.notes === baseEval.notes;
  if (!same) fails++;
  console.log(
    label.padEnd(20) +
    `${r.hi}/${r.lo}`.padEnd(13) +
    `Δspan=${dSpan.toFixed(2)}  Δhi=${dHi.toFixed(2)}c  Δlo=${dLo.toFixed(2)}c  نت‌ها=${r.notes}/${baseEval.notes}  ` +
    (same ? '✓ یکسان' : '✖ متفاوت')
  );
}

if (verbose) {
  const rep = reports.clean;
  console.log('\nنمونهٔ گزارش (نسخهٔ clean):');
  console.log(JSON.stringify({
    meta: rep.meta, quality: rep.quality, basic: rep.basic,
    range: rep.range, notes: rep.notes.slice(0, 3),
    note_counts: rep.note_counts.slice(0, 3), pauses: rep.pauses.slice(0, 2)
  }, null, 1));
}

console.log('─'.repeat(96));
if (fails) { console.log(`✖ ${fails} مورد ناموفق`); process.exit(1); }
console.log('✅ همهٔ موارد درست — بالاترین/پایین‌ترین نت درست، گستره یکسان، خطای کوک کم');
