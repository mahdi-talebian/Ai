/* ============================================================================
   آزمون «اجرای زنده» — سکوت، نویز، هام، و صدای واقعی
   ----------------------------------------------------------------------------
   مسئله‌ای که این آزمون از آن محافظت می‌کند:
   «در سکوت/نویز، اپ خودش بی‌دلیل نت بالا و پایین می‌پراند.»
   پس باید: سکوت، نویز، هام و رامبل ⇒ هیچ نتی تولید نکنند، و تُن واقعی ⇒
   همان نت درست، بدون پرش اکتاو، با نمایش پایدار.

   اجرا:  node web/tests/live_test.js
   ========================================================================== */
'use strict';

const path = require('path');
const CORE = require(path.join(__dirname, '..', 'engine.js'));

const SR = 48000;             // نرخ معمول میکروفون
const CHUNK = 0.12;           // طول هر بسته (ثانیه) — مثل خود اپ
const N = Math.round(SR * CHUNK);

let pass = 0, fail = 0;
function check(name, ok, info) {
  if (ok) { pass++; console.log('✓ ' + name + (info ? '  → ' + info : '')); }
  else { fail++; console.log('✗ ' + name + (info ? '  → ' + info : '')); }
}

/* ── تولید بستهٔ آزمایشی ────────────────────────────────────────────────── */
function chunk(kind, t0) {
  const x = new Float32Array(N);
  for (let i = 0; i < N; i++) {
    const t = t0 + i / SR;
    let v = 0, k;
    if (kind === 'silence') {
      v = 0;
    } else if (kind === 'quiet') {                       // نویز −۶۲ دسی‌بل
      v = (Math.random() * 2 - 1) * 0.0008;
    } else if (kind === 'noise') {                       // هیس −۲۶ دسی‌بل
      v = (Math.random() * 2 - 1) * 0.05;
    } else if (kind === 'hum') {                         // هام ۵۰ هرتز + هیس
      v = 0.02 * Math.sin(2 * Math.PI * 50 * t) + (Math.random() * 2 - 1) * 0.004;
    } else if (kind === 'rumble') {                      // رامبل ۳۸ هرتز
      v = 0.05 * Math.sin(2 * Math.PI * 38 * t) + 0.02 * Math.sin(2 * Math.PI * 76 * t)
        + (Math.random() * 2 - 1) * 0.006;
    } else if (kind === 'tone') {                        // A3 با هارمونیک
      for (k = 1; k <= 8; k++) v += Math.sin(2 * Math.PI * 220 * k * t) / k;
      v *= 0.12;
    } else if (kind === 'vibrato') {                     // A3 با ویبراتوی ۵ هرتز
      // فاز = انتگرال فرکانس (وگرنه سیگنال آزمایشی خودش بی‌معنی است)
      const ph = 2 * Math.PI * 220 * t + 0.35 * Math.sin(2 * Math.PI * 5 * t) * 2 / 5;
      for (let h = 1; h <= 6; h++) v += 0.2 * Math.sin(h * ph + h * 0.2) / h;
    } else if (kind === 'voice') {                       // فورمانت‌دار شبیه واکل
      const f0 = 196;
      const formants = [[700, 1.0], [1220, 0.5], [2600, 0.25]];
      for (k = 1; k <= 24; k++) {
        let amp = 1 / k;
        for (const [fc, g] of formants) amp += (g / k) * Math.exp(-Math.pow((f0 * k - fc) / 220, 2));
        v += amp * Math.sin(2 * Math.PI * f0 * k * t + k * 0.3);
      }
      v *= 0.05;
    }
    x[i] = v;
  }
  return x;
}

/* ── اجرای یک سناریو ────────────────────────────────────────────────────── */
function session(kinds, opts) {
  const tr = new CORE.LiveTracker(opts);
  const notes = [], history = [];
  for (let c = 0; c < kinds.length; c++) {
    const info = CORE.extractPitchChunkInfo(chunk(kinds[c], c * CHUNK), SR, {});
    const f = tr.push(info);
    history.push(f);
    if (f.note) notes.push(f.note);
  }
  return { tr, notes, history };
}

/* ═════════ ۱) سکوت و نویز: هیچ نتی نباید ساخته شود ═════════ */
['silence', 'quiet', 'noise', 'hum', 'rumble'].forEach(kind => {
  const { tr, notes, history } = session(Array(40).fill(kind));
  const uniq = [...new Set(notes.map(n => n.midi))];
  check('بدون نت در «' + kind + '»', notes.length === 0,
    'نت‌ها=' + notes.length + ' (یکتا=' + uniq.length + ')');
  check('جلسه در «' + kind + '» خالی می‌ماند', tr.hi === null && tr.lo === null);
  check('وضعیت «ساکت» گزارش می‌شود', history[history.length - 1].silent === true);
});

/* ═════════ ۲) صدای واقعی: نت درست و پایدار ═════════ */
{
  const { tr, notes } = session(Array(30).fill('tone'));
  const names = [...new Set(notes.map(n => n.note_name))];
  const midis = notes.map(n => n.midi_exact);
  const spread = Math.max(...midis) - Math.min(...midis);
  check('تُن A3 تشخیص داده شد', names.length === 1 && names[0] === 'A3', names.join(','));
  check('هیچ پرش اکتاوی در تُن نیست', spread < 0.35, 'پراکندگی=' + spread.toFixed(3) + ' نیم‌پرده');
  check('رکورد جلسه برای تُن ثبت شد',
    !!tr.hi && !!tr.lo && Math.round(tr.hi.midi) === Math.round(tr.lo.midi),
    tr.hi && tr.lo ? CORE.midiToName(tr.hi.midi) + '/' + CORE.midiToName(tr.lo.midi) : '-');
  check('نت بعد از ۳ بسته پایدار ظاهر می‌شود', notes.length >= 25, 'نت‌ها=' + notes.length + '/30');
}
{
  const { notes } = session(Array(40).fill('vibrato'));
  const names = [...new Set(notes.map(n => n.note_name))];
  check('ویبراتو یک نت می‌ماند', names.length === 1 && names[0] === 'A3', names.join(','));
}
{
  const { notes } = session(Array(40).fill('voice'));
  const names = [...new Set(notes.map(n => n.note_name))];
  check('صدای واکل‌مانند درست خوانده می‌شود', names.length === 1 && names[0] === 'G3', names.join(','));
}

/* ═════════ ۳) گذار سکوت → صدا → سکوت: بدون پرش و بدون نت جعلی ═════════ */
{
  const kinds = [
    ...Array(10).fill('noise'),
    ...Array(15).fill('tone'),
    ...Array(15).fill('noise')
  ];
  const { tr, notes, history } = session(kinds);
  const midiOfTone = notes.filter(n => n.midi === 57);
  const spurious = notes.filter(n => n.midi !== 57);
  check('در گذار، نت جعلی ساخته نمی‌شود', spurious.length === 0,
    'نت‌های اضافی=' + spurious.map(n => n.note_name).join(','));
  check('نتِ درست در بخش صدادار پیدا شد', midiOfTone.length >= 10, 'A3 ×' + midiOfTone.length);
  check('بعد از قطع صدا نمایش خاموش می‌شود',
    history[history.length - 1].silent === true && history[history.length - 1].note === null);
  check('مهلت کوتاه بعد از قطع صدا (بدون پرش)', (() => {
    const iEnd = 24;                       // آخرین بستهٔ صدادار
    const after = history.slice(iEnd + 1, iEnd + 3);   // ≈۰٫۲۴ ثانیهٔ اول سکوت
    return after.every(f => !f.silent) && after.some(f => f.note && (f.note.hold || f.note.midi === 57));
  })());
  check('رکورد جلسه فقط A3 است',
    !!tr.hi && Math.round(tr.hi.midi) === 57 && Math.round(tr.lo.midi) === 57,
    tr.hi && tr.lo ? CORE.midiToName(tr.lo.midi) + ' ↕ ' + CORE.midiToName(tr.hi.midi) : '-');
}

/* ═════════ ۴) پرش اکتاو: خطای ۲× نباید نمایش را بپراند ═════════ */
{
  const tr = new CORE.LiveTracker();
  const feed = (f0, n) => { for (let i = 0; i < n; i++) tr.push({ f0, strength: 0.9, rms: 0.05, reason: 'ok' }); };
  feed(220, 6);                 // A3
  feed(440, 1);                 // یک بستهٔ خطای اکتاو
  feed(220, 6);
  check('خطای اکتاویِ یک‌بسته‌ای دیده نمی‌شود', tr.hi && Math.round(tr.hi.midi) === 57,
    'بالاترین=' + (tr.hi ? CORE.midiToName(tr.hi.midi) : '-'));
}

/* ═════════ ۵) جزئیات رکورد و لاگ ═════════ */
{
  const { history } = session(Array(20).fill('noise'));
  const f = history[history.length - 1];
  check('پیام جلسه شکل درست دارد',
    !!f.session && 'highest' in f.session && 'lowest' in f.session && 'span_semitones' in f.session);
  check('rms و کف نویز گزارش می‌شوند', typeof f.rms === 'number' && typeof f.floor === 'number',
    'rms=' + f.rms + ' floor=' + f.floor);
  check('کف نویز با نویز بالا می‌رود (تطبیقی)', f.floor > 0.0025, 'floor=' + f.floor);
}

/* ═════════ ۶) extractPitchChunk قدیمی هم سازگار بماند ═════════ */
{
  const ok = CORE.extractPitchChunk(chunk('tone', 0), SR, 60, 1000);
  const no = CORE.extractPitchChunk(chunk('silence', 0), SR, 60, 1000);
  check('extractPitchChunk روی تُن عدد می‌دهد', ok > 200 && ok < 240, ok && ok.toFixed(1));
  check('extractPitchChunk روی سکوت null می‌دهد', no === null);
}

/* ═════════ ۷) کارایی: هر بسته باید سریع تحلیل شود (بدون لگ) ═════════ */
{
  const chunks = Array.from({ length: 30 }, (_, i) => chunk('tone', i * CHUNK));
  const tr = new CORE.LiveTracker();
  const t0 = Date.now();
  for (const c of chunks) tr.push(CORE.extractPitchChunkInfo(c, SR, {}));
  const ms = Date.now() - t0;
  const per = ms / chunks.length;
  check('تحلیل هر بسته < ۲۵ میلی‌ثانیه (بدون لگ)', per < 25,
    per.toFixed(1) + ' ms/بسته (' + (CHUNK * 1000 / per).toFixed(0) + '× سرعت بیدرنگ)');
}

console.log('─'.repeat(70));
if (fail) { console.log('❌ ' + fail + ' بررسی ناموفق، ' + pass + ' موفق'); process.exit(1); }
console.log('✅ اجرای زنده در سکوت/نویز/صدا درست و بدون پرش است (' + pass + ' بررسی)');
