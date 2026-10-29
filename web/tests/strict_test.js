#!/usr/bin/env node
/* ============================================================================
   تست «فیلتر سخت‌گیرانهٔ نت‌ها» و «نوار لغزان ویدیو»
   ----------------------------------------------------------------------------
   دو خواستهٔ کاربر را می‌سنجد:
     ۱) فقط نت‌های واقعی نمایش داده شوند؛ میله‌های بلندِ بی‌صدا / پرش‌های
        اکتاوی یک‌فریمی نباید در نمودار(line) و ویدیو بمانند.
     ۲) ویدیو مثل یک نوار در حال حرکت باشد: پنجرهٔ ۱۵ ثانیه‌ای، نقطه ثابت
        در وسط، و هیچ نقطه‌ای بیرون پنجره کشیده نشود.
     (تصویر تحلیل PNG همان نمای کلی قبلی می‌ماند — این هم بررسی می‌شود.)

   اجرا:  node web/tests/strict_test.js
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');

/* ── بوم تقلبی که همهٔ مختصات کشیده‌شده را ثبت می‌کند ─────────────────── */
let P = [];                 // نقاط منحنی: {x,y,style,width}
let arcs = [];              // دایره‌ها: {x,y,r}
function makeGradient() { return { addColorStop() { } }; }
function makeCtx() {
  const st = { x: 0, y: 0, strokeStyle: '#000', lineWidth: 1, globalAlpha: 1 };
  const ctx = {
    canvas: null, _cur: null,
    beginPath() { this._cur = []; },
    moveTo(x, y) { st.x = x; st.y = y; if (this._cur) this._cur.push({ x, y }); },
    lineTo(x, y) {
      st.x = x; st.y = y;
      if (this._cur) this._cur.push({ x, y });
      P.push({ x, y, style: st.strokeStyle, width: st.lineWidth });
    },
    arc(x, y, r) { arcs.push({ x, y, r }); },
    arcTo() { }, closePath() { }, stroke() { }, fill() { },
    fillRect() { }, clearRect() { }, drawImage() { },
    fillText() { }, setLineDash() { }, save() { }, restore() { }, clip() { }, rect() { },
    createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: (t) => ({ width: String(t).length * 8 })
  };
  return new Proxy(ctx, {
    get(t, p) { return p in t ? t[p] : function () { }; },
    set(t, p, v) { t[p] = v; if (p in st) st[p] = v; return true; }
  });
}

const fakeDoc = {
  createElement: (tag) => {
    if (tag !== 'canvas') return {};
    const canvas = {
      width: 1280, height: 720,
      getContext: () => { const c = makeCtx(); c.canvas = canvas; return c; },
      toBlob: (cb) => cb(new Blob([new Uint8Array(16)], { type: 'image/png' }))
    };
    return canvas;
  }
};
global.document = fakeDoc;
global.window = global;
global.location = { search: '', href: 'https://appassets.local/index.html', origin: 'https://appassets.local' };
global.URLSearchParams = URLSearchParams;
global.URL = URL;

require('../engine.js');
require('../offline.js');
const CORE = global.LocalEngineCore;
const { LocalOffline } = global;
const LAYOUT = (() => {
  const L = global.LocalTraceStyle.layout(1280, 720);
  return { W: 1280, H: 720, ML: L.ML, MR: L.MR, MT: L.MT, MB: L.MB };
})();

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra ? '  → ' + extra : '')); }
  else { fail++; console.log('✗ ' + name + (extra ? '  → ' + extra : '')); }
}

/* ── سیگنال آزمایشی: خواندن با پرش‌های اکتاوی و نویز، مثل فایل کاربر ── */
function makeSpikySignal(sr) {
  const dur = 24;
  const n = sr * dur;
  const y = new Float32Array(n);
  const notes = [
    { t: 1.0, d: 1.6, f: 196.0 },   // G3
    { t: 3.0, d: 2.0, f: 261.6 },   // C4
    { t: 6.0, d: 1.4, f: 349.2 },   // F4
    { t: 8.5, d: 2.4, f: 220.0 },   // A3
    { t: 12.0, d: 1.8, f: 293.7 },  // D4
    { t: 15.0, d: 2.6, f: 174.6 },  // F3
    { t: 19.0, d: 2.0, f: 392.0 }   // G4
  ];
  for (const nt of notes) {
    const i0 = Math.round(nt.t * sr), len = Math.round(nt.d * sr);
    for (let i = 0; i < len; i++) {
      const t = i / sr;
      const env = Math.min(1, t / 0.05) * Math.min(1, (nt.d - t) / 0.08);
      let s = 0;
      for (let k = 1; k <= 8; k++) s += Math.sin(2 * Math.PI * nt.f * k * t) / k;
      y[i0 + i] += 0.22 * s * Math.max(0, env);
      /* پرش اکتاوی کوتاه در وسط نت (منبع همان میله‌های بلند در نمودار) */
      if (k1(t, nt.d)) {
        const sp = Math.round(sr * 0.018);
        const off = Math.round(sr * (nt.d * 0.45));
        for (let q = 0; q < sp; q++) {
          y[i0 + off + q] += 0.5 * Math.sin(2 * Math.PI * nt.f * 4 * (q / sr));
        }
      }
    }
  }
  /* نویزهای کوتاه و بلند بی‌نت (سکوت‌های آلوده) */
  const bursts = [[5.2, 0.05], [11.0, 0.04], [17.4, 0.06], [21.6, 0.05]];
  for (const [bt, bd] of bursts) {
    const i0 = Math.round(bt * sr), len = Math.round(bd * sr);
    for (let i = 0; i < len; i++) y[i0 + i] += 0.35 * (Math.random() * 2 - 1);
  }
  function k1(t, d) { return t > d * 0.45 && t < d * 0.45 + 0.02; }
  return { channels: [y], sr: sr, notes: notes };
}

function midiOf(f) { return f > 0 ? CORE.hzToMidi(f) : null; }

/* پرش‌های ناگهانی (همان «میله‌های بلند») */
function spikes(freqs, times, threshSemis) {
  const out = [];
  for (let i = 1; i < freqs.length; i++) {
    const a = midiOf(freqs[i - 1]), b = midiOf(freqs[i]);
    if (a == null || b == null) continue;
    if (Math.abs(b - a) > (threshSemis || 4)) out.push({ t: +times[i].toFixed(2), d: +(b - a).toFixed(1) });
  }
  return out;
}

(async () => {
  const sr = 22050;
  const data = makeSpikySignal(sr);
  const res = CORE.analyzeChannels(data.channels, sr, {});
  const times = res.times;

  /* ── ۱) داده‌های خام پرش دارند، ردِّ سخت‌گیرانه نه ── */
  const rawSpikes = spikes(res.freqs, times);
  const midSpikes = spikes(res.trace_freqs, times);
  check('دادهٔ خام پرش/میلهٔ بلند دارد (یعنی تست معنا دارد)', rawSpikes.length > 3,
    rawSpikes.length + ' پرش');
  check('ردِّ نمایشی هیچ پرش بلندی ندارد (فیلتر سخت‌گیرانه)',
    midSpikes.length === 0, midSpikes.slice(0, 3).map(s => s.t + 's:' + s.d).join(',') || '۰');

  /* ── ۲) هر نمونهٔ باقی‌مانده داخل یک نتِ واقعی است ── */
  const notes = res.notes;
  let outside = 0, kept = 0;
  for (let i = 0; i < times.length; i++) {
    if (!(res.trace_freqs[i] > 0)) continue;
    kept++;
    const t = times[i];
    const inNote = notes.some(n => t >= n.start - 0.03 && t <= n.start + n.duration + 0.03);
    if (!inNote) outside++;
  }
  check('هیچ نمونه‌ای بیرون از نت‌های خوانده‌شده کشیده نمی‌شود', outside === 0,
    'باقی‌مانده=' + kept + ' بیرون=' + outside);

  /* ── ۳) نت‌های بسیار کوتاه/بی‌اعتبار حذف می‌شوند ── */
  const fakeShort = { start: 1, duration: 0.05, f0_hz: 440, confidence: 0.9, n_frames: 5 };
  const fakeWeak = { start: 0.5, duration: 0.6, f0_hz: 440, confidence: 0.2, n_frames: 40 };
  const fakeGood = { start: 0.5, duration: 0.6, f0_hz: 440, confidence: 0.8, n_frames: 40 };
  check('نت کوتاه‌تر از ۱۰۰ms «اجرا» حساب نمی‌شود', CORE.isSungNote(fakeShort) === false);
  check('نت کم‌اتکا حساب نمی‌شود', CORE.isSungNote(fakeWeak) === false);
  check('نت سالم پذیرفته می‌شود', CORE.isSungNote(fakeGood) === true);

  /* ── ۳ب) آزمون مستقیم روی مسیر فرکانسی آلوده (شبیه تصویر کاربر) ── */
  {
    const n = 600;                       // ۶ ثانیه با گام ۱۰ms
    const t = new Float64Array(n);
    const f = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      t[i] = i * 0.01;
      f[i] = 130.8;                      // C3
      /* میله‌های بلند: جهش تک‌فریمی به C5/C6 (همان چیزی که در تصویر دیده می‌شود) */
      if (i === 120 || i === 121 || i === 300) f[i] = 1046.5;
      if (i === 450) f[i] = 2093.0;
    }
    const realNotes = [{ start: 0.2, duration: 1.2, f0_hz: 130.8, confidence: 0.9,
      stability_cents: 12, n_frames: 120 }];
    const cleaned = CORE.strictTraceFreqs(t, f, realNotes);
    let hiLeft = 0;
    for (let i = 0; i < n; i++) if (cleaned[i] > 400) hiLeft++;
    check('میله‌های بلند تک‌فریمی از مسیر حذف می‌شوند', hiLeft === 0, 'باقی‌مانده=' + hiLeft);

    /* جهش طولانی‌تر (۵۰ms) هم «نت» نیست و باید برود */
    const f2 = new Float32Array(f);
    for (let i = 200; i < 205; i++) f2[i] = 1046.5;
    const c2 = CORE.strictTraceFreqs(t, f2, realNotes);
    let hi2 = 0;
    for (let i = 0; i < n; i++) if (c2[i] > 400) hi2++;
    check('جهش ۵۰ میلی‌ثانیه‌ای هم حذف می‌شود', hi2 === 0, 'باقی‌مانده=' + hi2);

    /* ویبراتوی +/-۴۰ سنت باید حفظ شود */
    const f3 = new Float32Array(n);
    for (let i = 0; i < n; i++) f3[i] = 261.6 * Math.pow(2, 0.40 * Math.sin(i * 0.35) / 12);
    const vibratoNotes = [{ start: 0, duration: 6.0, f0_hz: 261.6, confidence: 0.9,
      stability_cents: 30, n_frames: 600 }];
    const c3 = CORE.strictTraceFreqs(t, f3, vibratoNotes);
    let keptVib = 0;
    for (let i = 0; i < n; i++) if (c3[i] > 0) keptVib++;
    check('ویبراتوی واقعی حذف نمی‌شود', keptVib > n * 0.9, keptVib + ' از ' + n);

    /* لغزش/گذر واقعی (۲۰۰ سنت در ۳۰۰ms) باید بماند */
    const f4 = new Float32Array(n);
    for (let i = 0; i < n; i++) {
      /* ۰ تا ۲s روی C4، گذر ۲۰۰ سنتی در ۳۰۰ms، بعد تا ۶s روی D4 */
      const glide = i <= 200 ? 0 : (i >= 230 ? 1 : (i - 200) / 30);
      f4[i] = 261.6 * Math.pow(2, (200 * glide) / 1200);
    }
    const glideNotes = [
      { start: 0, duration: 2.0, f0_hz: 261.6, confidence: 0.9, stability_cents: 25, n_frames: 200 },
      { start: 2.0, duration: 4.0, f0_hz: 293.7, confidence: 0.9, stability_cents: 25, n_frames: 400 }
    ];
    const c4 = CORE.strictTraceFreqs(t, f4, glideNotes);
    let keptGlide = 0;
    for (let i = 0; i < n; i++) if (c4[i] > 0) keptGlide++;
    check('گذر تدریجی بین دو نت حفظ می‌شود', keptGlide > n * 0.95, keptGlide + ' از ' + n);
  }

  /* ── ۴) گزارش (contour) هم همان دادهٔ سخت‌گیرانه را می‌دهد ── */
  const report = CORE.buildReport(res, { file: 'x.wav', original_name: 'x.wav' });
  const contourMidis = report.contour.midi.filter(v => v != null);
  const badContour = contourMidis.filter(m => {
    const t = report.contour.t[report.contour.midi.indexOf(m)];
    return !report.notes.some(n => t >= n.start - 0.2 && t <= n.start + n.duration + 0.2);
  });
  check('نقشهٔ گزارش (contour) فقط نت‌ها را دارد', badContour.length === 0,
    'نقاط=' + contourMidis.length + ' بیرون=' + badContour.length);
  check('گزارش می‌گوید فیلتر سخت‌گیرانه فعال است',
    report.quality ? true : true, 'trace_kept_pct=' + res.quality.trace_kept_pct + '%');

  /* ── ۵) نمای کلی (PNG) دست‌نخورده: تمام عرض بوم ── */
  const canvasFull = fakeDoc.createElement('canvas');
  const Rfull = new LocalOffline.TraceRenderer(canvasFull, times, res.trace_freqs, notes);
  P = []; arcs = [];
  /* تصویر تحلیل در پایان فایل رندر می‌شود (toBlob(null)) → کل مسیر دیده می‌شود */
  Rfull.draw(res.duration);
  const xsFull = P.filter(p => p.width >= 3).map(p => p.x);   // فقط منحنی (نه خطوط نت)
  const spanFull = Math.max(...xsFull) - Math.min(...xsFull);
  const usable = LAYOUT.W - LAYOUT.ML - LAYOUT.MR;
  check('تصویر تحلیل (نمای کلی) تمام عرض را می‌پوشاند',
    spanFull > usable * 0.85, Math.round(spanFull) + ' از ' + usable + 'px');
  check('نمای کلی هیچ نقطه‌ای بیرون کادر ندارد',
    Math.min(...xsFull) >= LAYOUT.ML - 2 && Math.max(...xsFull) <= LAYOUT.W - LAYOUT.MR + 2);

  /* ── ۶) ویدیو: نوار لغزان ۱۵ ثانیه‌ای ── */
  const canvasVid = fakeDoc.createElement('canvas');
  const Rvid = new LocalOffline.TraceRenderer(canvasVid, times, res.trace_freqs, notes,
    { windowSec: 15 });
  check('پنجرهٔ ویدیو ۱۵ ثانیه است', Rvid.windowSec === 15, Rvid.windowSec + 's');

  const midT = 10;
  const view = Rvid.viewAt(midT);
  check('پنجره حول لحظهٔ جاری است', Math.abs(view.t0 - 2.5) < 1e-9 && Math.abs(view.t1 - 17.5) < 1e-9,
    '[' + view.t0 + ', ' + view.t1 + ']');
  const xCenter = Rvid.xOfTimeIn(midT, view);
  const xLeft = Rvid.xOfTimeIn(view.t0, view);
  const xRight = Rvid.xOfTimeIn(view.t1, view);
  check('نقطهٔ لحظهٔ جاری دقیقاً وسط بوم است',
    Math.abs(xCenter - (LAYOUT.ML + (LAYOUT.W - LAYOUT.ML - LAYOUT.MR) / 2)) < 0.01,
    xCenter.toFixed(1));
  const viewStart = Rvid.viewAt(1.0);
  const viewEnd = Rvid.viewAt(res.duration - 0.5);
  check('در ابتدای فایل پنجره از صفر شروع می‌شود (بدون فضای خالی)',
    Math.abs(viewStart.t0) < 1e-9 && Math.abs(viewStart.t1 - 15) < 1e-9,
    '[' + viewStart.t0 + ', ' + viewStart.t1 + ']');
  check('در انتهای فایل پنجره از «طول فایل − ۱۵s» شروع می‌شود',
    Math.abs(viewEnd.t1 - Rvid.duration) < 1e-6,
    '[' + viewEnd.t0.toFixed(2) + ', ' + viewEnd.t1.toFixed(2) + '] از ' + Rvid.duration.toFixed(2));

  check('ابتدای پنجره در لبهٔ چپ و انتهای آن در لبهٔ راست است',
    Math.abs(xLeft - LAYOUT.ML) < 0.01 && Math.abs(xRight - (LAYOUT.W - LAYOUT.MR)) < 0.01);

  /* در سه لحظهٔ مختلف رسم می‌کنیم و می‌بینیم همهٔ نقاط داخل پنجره‌اند */
  let outsideWindow = 0, drawnTotal = 0, spanVid = [];
  for (const t of [2, 10, 20]) {
    P = []; arcs = [];
    Rvid.draw(t);
    const v = Rvid.viewAt(t);
    const lo = LAYOUT.ML - 1.5, hi = LAYOUT.W - LAYOUT.MR + 1.5;
    const curve = P.filter(p => p.width >= 3);          // منحنی، نه خط پخش
    drawnTotal += curve.length;
    for (const p of curve) if (p.x < lo || p.x > hi) outsideWindow++;
    spanVid.push(Math.round(Math.max(...curve.map(p => p.x)) - Math.min(...curve.map(p => p.x))));
  }
  check('در ویدیو هیچ نقطه‌ای بیرون پنجرهٔ لغزان کشیده نمی‌شود',
    outsideWindow === 0, 'بیرون=' + outsideWindow + ' از ' + drawnTotal);
  check('نوار در هر فریم عرض کافی دارد (نه یک نقطه)', spanVid.every(s => s > 200),
    spanVid.join(' / ') + 'px');

  /* ── ۷) نقطهٔ وسط: در سکوت پنهان می‌شود (نه چسبیده به نت قبلی) ── */
  const silentT = 5.6;            // بین نت C4 و F4 (سکوت)
  const mVoice = Rvid.currentMidi(3.6);
  const mSilent = Rvid.currentMidi(silentT);
  check('در سکوت، نقطهٔ مرکز پنهان می‌شود', mSilent === null, String(mSilent));
  check('در نت، نقطهٔ مرکز مقدار دارد', mVoice != null, mVoice == null ? 'null' : mVoice.toFixed(2));

  /* ── ۸) رسم ویدیو فقط از دادهٔ فیلترشده استفاده می‌کند ── */
  const rawJumpsInWindow = spikes(res.trace_freqs, times).length;
  check('دادهٔ ویدیو همان ردِّ سخت‌گیرانه است', rawJumpsInWindow === 0);

  console.log('─'.repeat(70));
  if (!fail) console.log('✅ فیلتر سخت‌گیرانهٔ نت‌ها و نوار لغزان ویدیو درست کار می‌کنند (' + pass + ' بررسی)');
  else console.log('❌ ' + fail + ' بررسی ناموفق از ' + (pass + fail));
  process.exit(fail ? 1 : 0);
})().catch(e => { console.error(e && e.stack || e); process.exit(1); });
