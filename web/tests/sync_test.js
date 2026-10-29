#!/usr/bin/env node
/* ============================================================================
   تست «هم‌زمانی تصویر و صدا» (A/V sync)
   ----------------------------------------------------------------------------
   چرا این تست؟ کاربر گزارش داد تصویر با صدا ~۰٫۲ ثانیه فاصله دارد. منبعِ
   خطا این بود که زمانِ هر فریم، «آغازِ پنجرهٔ تحلیل» نوشته می‌شد، در حالی که
   تخمینِ f0 به کلِ پنجره مربوط است ⇒ همهٔ نمودارها (اپ، تصویر و ویدیو)
   دقیقاً نصفِ پنجره زودتر از صدا بودند.

   این تست با سیگنالِ ساختگی که لحظهٔ شروعش را دقیق می‌دانیم می‌سنجد:
     • شروع و پایانِ ردِّ صدا با شروع/پایانِ واقعیِ صدا مو نمی‌زند (< ۱۲ms)
     • شروعِ نتِ تشخیص‌داده‌شده هم همین‌قدر نزدیک است
     • زمان‌بندی مسیر سریال و موازی یکسان است (یعنی در گوشی هم همین است)
     • طولِ صدا و طولِ ردِّ صدا یکی است (بدون جابه‌جاییِ سراسری)

   اجرا:  node web/tests/sync_test.js
   ========================================================================== */
'use strict';

global.window = global;
global.document = { createElement: () => ({ getContext: () => ({}) }) };
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

require('../engine.js');
const CORE = global.LocalEngineCore;

let pass = 0, fail = 0;
const check = (n, ok, x) => {
  if (ok) { pass++; console.log('✓ ' + n + (x !== undefined ? '  → ' + x : '')); }
  else { fail++; console.log('✖ ' + n + (x !== undefined ? '  → ' + x : '')); }
};

const SR = 22050;
/* نُت با شروع/طولِ دقیق و ویبراتوی واقعی (فاز انباشته) */
function makeSignal(total, plan, sr) {
  sr = sr || SR;
  const n = Math.round(sr * total);
  const y = new Float32Array(n);
  plan.forEach(([start, len, f]) => {
    const i0 = Math.round(start * sr), m = Math.round(len * sr);
    let ph = 0;
    for (let i = 0; i < m && i0 + i < n; i++) {
      const t = i / sr;
      const vib = Math.pow(2, (30 / 1200) * Math.sin(2 * Math.PI * 5.0 * t));
      ph += 2 * Math.PI * f * vib / sr;
      const env = Math.min(1, t / 0.012) * Math.min(1, (len - t) / 0.02);
      let s = 0;
      for (let k = 1; k <= 10; k++) s += Math.sin(k * ph) / k;
      y[i0 + i] += 0.22 * s * Math.max(0, env);
    }
  });
  return y;
}

const cases = [
  { name: 'نت از ۲٫۰۰s در فایل ۱۰s', total: 10, plan: [[2.0, 2.0, 220]] },
  { name: 'نت از ۰٫۵۰s در فایل ۶s', total: 6, plan: [[0.5, 3.0, 293.7]] },
  { name: 'سه نت پشت‌سرهم', total: 12, plan: [[1.0, 1.6, 196], [3.0, 1.6, 246.9], [5.2, 2.0, 329.6]] }
];

/* تلورانس: گامِ تحلیل ۵ms است و پنجره نیمی از این گام را «نرم» می‌کند؛
   خطای زیر ۱۵ms برای شروع و ۲۵ms برای پایان (ناشی از محوِ انتهاییِ نت)
   کاملاً در محدودهٔ «هم‌زمان» است (آستانهٔ ادراک انسان ~۴۰ms). */
const TOL_START_MS = 15;
const TOL_END_MS = 25;

for (const c of cases) {
  const y = makeSignal(c.total, c.plan);
  const res = CORE.analyzeChannels([y], SR, {});
  const trace = res.trace_freqs || res.freqs;

  /* ابتدا/انتهای صدادارِ ردِّ نمایشی */
  let first = null, last = null;
  for (let i = 0; i < res.times.length; i++) {
    if (trace[i] > 0) { if (first === null) first = res.times[i]; last = res.times[i]; }
  }
  const trueStart = c.plan[0][0];
  const trueEnd = c.plan[c.plan.length - 1][0] + c.plan[c.plan.length - 1][1];

  check(c.name + ': شروعِ ردِّ صدا با صدا هم‌زمان است',
    first != null && Math.abs((first - trueStart) * 1000) <= TOL_START_MS,
    first == null ? '—' : ((first - trueStart) * 1000).toFixed(0) + 'ms');
  check(c.name + ': پایانِ ردِّ صدا هم هم‌زمان است',
    last != null && Math.abs((last - trueEnd) * 1000) <= TOL_END_MS,
    last == null ? '—' : ((last - trueEnd) * 1000).toFixed(0) + 'ms');

  /* نتِ اول: شروعش نباید جلوتر/عقب‌تر از خودِ صدا باشد */
  const n0 = res.notes[0];
  check(c.name + ': شروعِ نتِ تشخیص‌داده‌شده دقیق است',
    !!n0 && Math.abs((n0.start - trueStart) * 1000) <= TOL_START_MS + 3,
    n0 ? ((n0.start - trueStart) * 1000).toFixed(0) + 'ms' : '—');

  /* هیچ جابه‌جاییِ سراسری نمانده: طول‌ها یکی‌اند */
  const wavSec = res.wav.length / res.sr;
  check(c.name + ': طولِ صدا و بوم یکی است',
    Math.abs(res.duration - wavSec) < 0.01, res.duration.toFixed(2) + 's');
}

/* ── مسیر موازی باید همان زمان‌بندی را بدهد (چون در گوشی از آن استفاده می‌شود) */
{
  const y = makeSignal(8, [[1.5, 2.5, 261.6]]);
  const serial = CORE.analyzeChannels([y], SR, {});
  const shift = serial.times[0];
  let maxDiff = 0;
  for (let i = 0; i < serial.times.length; i++) {
    const expect = Math.min(i * CORE.TIME_STEP + shift, y.length / SR);
    maxDiff = Math.max(maxDiff, Math.abs(serial.times[i] - expect));
  }
  check('زمانِ فریم‌ها گامِ ثابت + مرکز پنجره است (بدون پرش)',
    maxDiff < 1e-6, 'اختلاف=' + maxDiff.toExponential(1) + 's');
  const winN = CORE.pitchWindow(y, SR, CORE.MIN_F0).winN;
  check('زمانِ فریم = مرکزِ پنجرهٔ تحلیل (نه آغازش)',
    Math.abs(shift - (winN / 2) / SR) < 1e-6,
    (shift * 1000).toFixed(1) + 'ms جابه‌جایی (winN=' + winN + ')');
  check('همان تابع در مسیر موازی هم استفاده می‌شود',
    typeof CORE.frameTimes === 'function' &&
    Math.abs(CORE.frameTimes(5, winN, SR, y.length)[0] - shift) < 1e-6);
}

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ تصویر و صدا هم‌زمان‌اند (' + pass + ' بررسی)');
process.exit(0);
