#!/usr/bin/env node
/* ============================================================================
   تست «درجه + نُتِ لحظه‌ای + خط‌چینِ بالا/پایین»
   ----------------------------------------------------------------------------
   قرارِ کار با کاربر:
     • هر اکتاو = ۸ درجه (و ۶ پرده = ۱۲ نیم‌پرده) ⇒ ۱ درجه = ۱٫۵ نیم‌پرده
       و درجه = نیم‌پرده × ۲⁄۳
     • نامِ نت‌ها سل‌فژ است: دو، ر، می، فا، سل، لا، سی
     • «چند درجه خوانده شد» با متنِ «۱ اکتاو و ۳ درجه» گزارش می‌شود
     • نُتِ همین لحظه در نمودار (و ویدیو) به‌شکلِ کپسول دیده می‌شود
     • بالاترین و پایین‌ترین نت با خطِ چین در تمام عرض مشخص می‌شوند
   اجرا:  node web/tests/degrees_test.js
   ========================================================================== */
'use strict';

/* بومِ ضبط‌کننده: متن‌ها، خطوط و dashها را نگه می‌دارد */
let TEXTS = [], LINES = [], DASHES = 0, ARCS = [];
function makeCtx() {
  const st = { strokeStyle: '#000', lineWidth: 1, fillStyle: '#fff', font: '10px x' };
  let cur = [];
  const api = {
    beginPath() { cur = []; },
    moveTo(x, y) { cur.push({ x, y }); },
    lineTo(x, y) { cur.push({ x, y }); },
    stroke() { if (cur.length >= 2) LINES.push({ p: cur.slice(), dashed: st._dash || false, w: st.lineWidth }); cur = []; },
    fill() { },
    setLineDash(a) { if (a && a.length) DASHES++; st._dash = !!(a && a.length); },
    save() { }, restore() { st._dash = false; },
    fillText(t, x, y) { TEXTS.push({ t: String(t), x, y }); },
    measureText(t) { return { width: String(t).length * 7 }; },
    arc(x, y, r) { ARCS.push({ x, y, r }); },
    ellipse() { }, rect() { }, clip() { }, closePath() { }, arcTo() { },
    fillRect() { }, clearRect() { }, drawImage() { },
    createLinearGradient: () => ({ addColorStop() { } }),
    createRadialGradient: () => ({ addColorStop() { } })
  };
  return new Proxy(api, {
    get(t, k) { return k in t ? t[k] : function () { }; },
    set(t, k, v) { t[k] = v; return true; }
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

/* ── ۱) ریاضیِ درجه: اکتاو = ۸ درجه ─────────────────────────────────── */
{
  const d12 = S.spanInDegrees(12);
  check('اکتاو (۱۲ نیم‌پرده) دقیقاً ۸ درجه است',
    Math.abs(d12.total - 8) < 1e-9 && d12.octaves === 1 && d12.degrees === 0,
    'درجه=' + d12.total + ' · متن=' + d12.text);
  const d155 = S.spanInDegrees(15.5);
  check('۱۵٫۵ نیم‌پرده = ۱ اکتاو و ۲٫۳ درجه (۱ درجه = ۱٫۵ نیم‌پرده)',
    d155.octaves === 1 && Math.abs(d155.degrees - 2.3) < 0.05,
    d155.text);
  const d45 = S.spanInDegrees(4.5);
  check('۴٫۵ نیم‌پرده = ۳ درجه (بدون اکتاو)', d45.octaves === 0 && Math.abs(d45.total - 3) < 1e-9, d45.text);
  check('۲۴ نیم‌پرده = ۲ اکتاو', S.spanInDegrees(24).octaves === 2, S.spanInDegrees(24).text);
  check('موتور و رندرر یک عدد می‌دهند',
    CORE.spanInDegrees(15.5).text === S.spanInDegrees(15.5).text,
    CORE.spanInDegrees(15.5).text + ' / ' + S.spanInDegrees(15.5).text);
}

/* ── ۲) نام‌های سل‌فژ ─────────────────────────────────────────────────── */
{
  const names = [0, 2, 4, 5, 7, 9, 11].map(pc => S.solfegeOf(60 + pc));
  check('هفت نامِ سل‌فژ درست است',
    names.join(',') === 'دو,ر,می,فا,سل,لا,سی', names.join('، '));
  check('A4 = لا۴ و C4 = دو۴',
    S.solfegeOf(69, true) === 'لا۴' && S.solfegeOf(60, true) === 'دو۴',
    S.solfegeOf(69, true) + ' · ' + S.solfegeOf(60, true));
  check('موتور همان نام را می‌دهد', CORE.solfegeOf(69, true) === S.solfegeOf(69, true),
    CORE.solfegeOf(69, true));
  check('نتِ نیم‌پرده‌ای نامِ نزدیک با ♯ می‌گیرد', S.solfegeOf(61, true) === 'دو♯۴', S.solfegeOf(61, true));
}

/* ── ۳) گزارش: درجهٔ کل + سل‌فژ بالا/پایین ───────────────────────────── */
const SR = 22050;
function tone(f, sec) {
  const n = Math.round(SR * sec);
  const y = new Float32Array(n);
  let ph = 0;
  for (let i = 0; i < n; i++) {
    ph += 2 * Math.PI * f / SR;
    let s = 0;
    for (let k = 1; k <= 8; k++) s += Math.sin(k * ph) / k;
    const t = i / SR;
    y[i] = 0.3 * s * Math.min(1, t / 0.02) * Math.min(1, (sec - t) / 0.05);
  }
  return y;
}
/* دو نت: ۲۲۰Hz (لا۳) و ۳۳۰Hz (می۴) ⇒ ~۷ نیم‌پرده */
const yMix = new Float32Array(SR * 6);
yMix.set(tone(220, 2.0), 0);
yMix.set(tone(330, 2.0), Math.round(SR * 2.5));
let res, rep;
{
  res = CORE.analyzeChannels([yMix], SR, {});
  rep = CORE.buildReport(res, {});
  const rg = rep.range || {};
  check('گزارش، درجه و اکتاو را دارد',
    rg.span_degrees != null && rg.span_text && rg.span_octaves != null,
    'نیم‌پرده=' + rg.span_semitones + ' · درجه=' + rg.span_degrees + ' · «' + rg.span_text + '»');
  check('درجه = نیم‌پرده × ۲⁄۳ در گزارش',
    Math.abs(rg.span_degrees - rg.span_semitones * (8 / 12)) < 0.11,
    rg.span_degrees + ' ≈ ' + (rg.span_semitones * 2 / 3).toFixed(1));
  check('بالاترین و پایین‌ترین نت، نامِ سل‌فژ دارند',
    /[درفسلمی]/.test(rg.highest_solfege || '') && /[درفسلمی]/.test(rg.lowest_solfege || ''),
    rg.highest_solfege + ' ↕ ' + rg.lowest_solfege);
  check('نت‌های گزارش هم سل‌فژ دارند',
    (rep.notes || []).every(n => !!n.solfege),
    (rep.notes || []).slice(0, 2).map(n => n.solfege + '(' + n.note_name + ')').join(' , '));
}

/* ── ۴) نُتِ لحظه‌ای: کپسول با نامِ سل‌فژ و شمارهٔ درجه ───────────────── */
{
  TEXTS = []; LINES = []; DASHES = 0;
  const c = mkCanvas(1280, 720);
  const times = Array.from(res.times);
  const mids = times.map((_, i) => res.trace_freqs[i] > 0 ? CORE.hzToMidi(res.trace_freqs[i]) : null);
  const spec = {
    W: 1280, H: 720, dpr: 1, times, mids, notes: rep.notes, range: rep.range,
    duration: 6, t: 1.0, view: { t0: 0, t1: 6 }, tightness: 1
  };
  new OFF.TraceRenderer(c, times, res.trace_freqs, rep.notes, {}).draw(1.0);
  const texts = TEXTS.map(x => x.t);
  const hasName = texts.some(t => /^(دو|ر|می|فا|سل|لا|سی)(♯)?[۰-۹]+$/.test(t));
  const hasDeg = texts.some(t => /درجهٔ/.test(t));
  check('کپسولِ نُت، نامِ سل‌فژِ همان لحظه را نشان می‌دهد', hasName,
    texts.filter(t => /[درفسلمی]/.test(t)).slice(0, 3).join(' | '));
  check('کپسول شمارهٔ درجه را هم نشان می‌دهد', hasDeg,
    texts.filter(t => /درجهٔ/.test(t))[0] || '—');

  /* خط‌چینِ بالا و پایین: باید تمامِ عرض را بپوشانند */
  const wide = LINES.filter(l => l.dashed && Math.abs(l.p[l.p.length - 1].x - l.p[0].x) > 1200);
  check('خطِ چینِ بالا و پایین تمام عرضِ نمودار را می‌پوشد', wide.length >= 2,
    wide.length + ' خطِ چین · ' + DASHES + ' setLineDash');
  const hiTxt = texts.find(t => /^بالاترین/.test(t));
  const loTxt = texts.find(t => /^پایین‌ترین/.test(t));
  check('برچسبِ خط‌چینِ بالا/پایین نامِ سل‌فژ و درجه دارد',
    !!hiTxt && !!loTxt && /درجهٔ/.test(hiTxt) && /درجهٔ/.test(loTxt),
    (hiTxt || '—') + ' / ' + (loTxt || '—'));
  check('«V» جای خودش را به «بالاترین/پایین‌ترین» داده', !texts.some(t => /^(HIGH|LOW)/.test(t)));
}

/* ── ۵) اجرای زنده: همان سل‌فژ و درجه ───────────────────────────────── */
{
  const tr = new CORE.LiveTracker({});
  let out = null;
  for (let k = 0; k < 16; k++) {
    out = tr.push({ f0: 220, rms: 0.08, reason: 'ok' });
  }
  check('خروجیِ زنده هم نامِ سل‌فژ و شمارهٔ درجه دارد',
    !!(out && out.note && out.note.solfege && out.note.degree >= 1),
    out && out.note ? out.note.solfege + ' (' + out.note.note_name + ') · درجهٔ ' + out.note.degree : '—');
  check('گسترهٔ جلسهٔ زنده هم به درجه گزارش می‌شود',
    !!(out && out.session && out.session.span_text),
    out && out.session ? out.session.span_text + ' · ' + out.session.span_degrees + ' درجه' : '—');
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ درجه، نُتِ لحظه‌ای و خط‌چینِ بالا/پایین درست کار می‌کنند (' + pass + ' بررسی)');
process.exit(0);
