#!/usr/bin/env node
/* ساخت «تلاوت مصنوعی» مرجع برای تست موتور (هم‌ارز synth_voice در پایتون) */
'use strict';
const E = require('../engine.js');

const SR_GEN = 44100;
const TONIC = 220.0;
const GAP = 0.12;
const SCORE = [
  [0, 0.55], [2, 0.45], [3, 0.60], [5, 0.50], [7, 0.65],
  [5, 0.45], [3, 0.50], [2, 0.40], [0, 0.70],
  [12, 0.55], [10, 0.45], [7, 0.55], [5, 0.40], [0, 0.75],
];
/* نکته: موتور (مثل نسخهٔ پایتون) نت‌هایی را که فقط یک بار و کوتاه خوانده
   شده‌اند از «گسترهٔ قابل اتکا» کنار می‌گذارد؛ A4 در این اجرا فقط یک بار
   (۰٫۵۵ ثانیه) خوانده می‌شود، پس بالاترین نتِ اتکاپذیر E4 است و A4 در
   raw_highest گزارش می‌شود. */
const EXPECT_HI = TONIC * Math.pow(2, 7 / 12);    // E4
const EXPECT_LO = TONIC;                           // A3
const EXPECT_RAW_HI = TONIC * Math.pow(2, 12 / 12); // A4 (لحظه‌ای)

/* ── ابزارها ─────────────────────────────────────────────────────────── */
function biquadLowpass(y, sr, fc, q) {
  const w0 = 2 * Math.PI * fc / sr, cw = Math.cos(w0), sw = Math.sin(w0);
  const alpha = sw / (2 * q);
  const a0 = 1 + alpha;
  const b0 = (1 - cw) / 2 / a0, b1 = (1 - cw) / a0, b2 = b0;
  const a1 = -2 * cw / a0, a2 = (1 - alpha) / a0;
  const out = new Float32Array(y.length);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < y.length; i++) {
    const x0 = y[i];
    const v = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
    out[i] = v; x2 = x1; x1 = x0; y2 = y1; y1 = v;
  }
  return out;
}

function resonator(y, sr, fc, bw, g) {
  const r = Math.exp(-Math.PI * bw / sr), th = 2 * Math.PI * fc / sr;
  const b0 = 1 - r, a1 = -2 * r * Math.cos(th), a2 = r * r;
  const out = new Float32Array(y.length);
  let x1 = 0, x2 = 0, y1 = 0, y2 = 0;
  for (let i = 0; i < y.length; i++) {
    const x0 = y[i] * g;
    const v = b0 * x0 - a1 * y1 - a2 * y2;
    out[i] = v + 0.25 * x0;      // همان ساختار _vowel در پایتون (x*g وارد فیلتر + ۰٫۲۵x)
    x2 = x1; x1 = x0; y2 = y1; y1 = v;
  }
  return out;
}

function vowel(y, sr) {
  let s = resonator(y, sr, 700, 130, 1.0);
  s = resonator(s, sr, 1220, 160, 0.7);
  s = resonator(s, sr, 2600, 250, 0.35);
  return s;
}

function rngFactory(seed) {          // PRNG ساده و قطعی
  let s = seed >>> 0;
  return function () {
    s ^= s << 13; s >>>= 0; s ^= s >> 17; s ^= s << 5; s >>>= 0;
    return (s >>> 0) / 4294967296;
  };
}

function gauss(rnd) {
  let u = 0, v = 0;
  while (u === 0) u = rnd();
  while (v === 0) v = rnd();
  return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
}

/* ── ساخت اجرای مرجع ──────────────────────────────────────────────────── */
function synthVoice(degrade, seed = 7) {
  const rnd = rngFactory(seed);
  const total = SCORE.reduce((a, [, d]) => a + d, 0) + GAP * SCORE.length + 0.3;
  const y = new Float64Array(Math.round(SR_GEN * total));
  let tCur = 0.15;

  for (const [semi, dur] of SCORE) {
    const f0 = TONIC * Math.pow(2, semi / 12);
    const n = Math.round(SR_GEN * dur);
    const t = new Float64Array(n);
    for (let i = 0; i < n; i++) t[i] = i / SR_GEN;
    const phase0 = rnd() * 6;
    const jitterWalk = new Float64Array(n);
    let acc = 0;
    for (let i = 0; i < n; i++) { acc += gauss(rnd); jitterWalk[i] = acc; }

    // پالس گلوتال (۱۴ هارمونیک) با ویبراتو/جیتر
    const src = new Float64Array(n);
    let phase = 0;
    for (let i = 0; i < n; i++) {
      const vib = 1 + 0.0075 * Math.sin(2 * Math.PI * 5.5 * t[i] + phase0);
      const jitter = 1 + 0.0015 * jitterWalk[i] / Math.sqrt(n);
      const fInst = f0 * vib * jitter;
      phase += 2 * Math.PI * fInst / SR_GEN;
      let s = 0;
      for (let k = 1; k <= 13; k++) s += Math.sin(k * phase) / k;
      s += 0.02 * gauss(rnd);
      const env = Math.min(1, Math.min(t[i] / 0.03, (dur - t[i]) / 0.05));
      src[i] = s * Math.max(0, env);
    }
    const seg = vowel(src, SR_GEN);
    const i0 = Math.round(SR_GEN * tCur);
    for (let i = 0; i < n && i0 + i < y.length; i++) y[i0 + i] += seg[i];
    tCur += dur + GAP;
  }

  let peak = 0;
  for (let i = 0; i < y.length; i++) peak = Math.max(peak, Math.abs(y[i]));
  for (let i = 0; i < y.length; i++) y[i] = y[i] / (peak + 1e-9) * 0.7;

  let out = new Float32Array(y);
  let sr = SR_GEN;

  if (degrade === 'quiet') {
    for (let i = 0; i < out.length; i++) out[i] *= 0.012;
  } else if (degrade === 'noisy') {
    for (let i = 0; i < out.length; i++) out[i] += 0.05 * gauss(rnd);
  } else if (degrade === 'rumble') {
    for (let i = 0; i < out.length; i++) {
      const t = i / SR_GEN;
      out[i] += 0.25 * Math.sin(2 * Math.PI * 42 * t) + 0.06 * Math.sin(2 * Math.PI * 50 * t);
    }
  } else if (degrade === 'clip') {
    for (let i = 0; i < out.length; i++) out[i] = Math.max(-1, Math.min(1, out[i] * 3.2));
  } else if (degrade === 'phone') {
    out = biquadLowpass(E.highpass(out, SR_GEN, 320), SR_GEN, 3300, Math.SQRT1_2);
    out = biquadLowpass(out, SR_GEN, 3300, Math.SQRT1_2);
  } else if (degrade === 'hum') {
    for (let i = 0; i < out.length; i++) {
      const t = i / SR_GEN;
      out[i] += 0.20 + 0.12 * Math.sin(2 * Math.PI * 50 * t) + 0.05 * Math.sin(2 * Math.PI * 150 * t);
    }
  } else if (degrade === '16k') {
    sr = 16000; out = E.resample(out, SR_GEN, sr, 32);
  } else if (degrade === '8k') {
    sr = 8000; out = E.resample(out, SR_GEN, sr, 32);
  } else if (degrade === 'stereo_phase') {
    return { channels: [out, out.map(v => -v)], sr };
  }

  return { channels: [out], sr };
}


module.exports = { synthVoice, SCORE, TONIC, EXPECT_HI, EXPECT_LO, EXPECT_RAW_HI, SR_GEN, GAP };
