#!/usr/bin/env node
/* ============================================================================
   تست رمزگشایی فایل‌های صوتی در حالت آفلاین (بدون سرور)
   ----------------------------------------------------------------------------
   WAV در همهٔ شکل‌های رایج (۸/۱۶/۲۴/۳۲ بیت، float، استریو، فازمخالف) باید
   درست خوانده شود و همان نتیجهٔ تحلیل را بدهد.

   اجرا: node web/tests/decode_test.js
   ========================================================================== */
'use strict';

global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URL.createObjectURL = () => 'blob:fake';
global.document = { createElement: () => ({ getContext: () => null, toBlob: (cb) => cb(null) }) };
global.AudioContext = function () {
  return { decodeAudioData: (b, ok, err) => err && err(new Error('nope')) };
};

require('../engine.js');
require('../offline.js');

const OFF = global.LocalOffline;

/* ── سازندهٔ WAV با فرمت‌های مختلف ────────────────────────────────────── */
function buildWav(samples, sr, { bits = 16, float = false, channels = 1, phaseInvert = false } = {}) {
  const n = samples.length;
  const bytesPer = bits / 8;
  const dataBytes = n * bytesPer * channels;
  const buf = Buffer.alloc(44 + dataBytes);
  const fmt = float ? 3 : 1;
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + dataBytes, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(fmt, 20);
  buf.writeUInt16LE(channels, 22); buf.writeUInt32LE(sr, 24);
  buf.writeUInt32LE(sr * bytesPer * channels, 28);
  buf.writeUInt16LE(bytesPer * channels, 32); buf.writeUInt16LE(bits, 34);
  buf.write('data', 36); buf.writeUInt32LE(dataBytes, 40);

  let o = 44;
  for (let i = 0; i < n; i++) {
    for (let c = 0; c < channels; c++) {
      let v = samples[i];
      if (c === 1 && phaseInvert) v = -v;      // کانال دوم با فاز معکوس
      if (float) { buf.writeFloatLE(v, o); o += 4; }
      else if (bits === 8) { buf.writeUInt8(Math.max(0, Math.min(255, Math.round(v * 127 + 128))), o); o += 1; }
      else if (bits === 16) { buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(v * 32767))), o); o += 2; }
      else if (bits === 24) {
        const iv = Math.max(-8388608, Math.min(8388607, Math.round(v * 8388607)));
        buf.writeUIntLE(iv < 0 ? iv + 0x1000000 : iv, o, 3); o += 3;
      } else { buf.writeInt32LE(Math.max(-2147483648, Math.min(2147483647, Math.round(v * 2147483647))), o); o += 4; }
    }
  }
  return new Uint8Array(buf);
}

 /* سیگنال مرجع: ۲ نت (۲۲۰ و ۳۳۰ هرتز) با هارمونیک */
function tone(sr, dur) {
  const n = Math.round(sr * dur);
  const y = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const f = t < dur / 2 ? 220 : 330;
    let s = 0;
    for (let k = 1; k <= 10; k++) s += Math.sin(2 * Math.PI * f * k * t) / k;
    y[i] = s * 0.2;
  }
  return y;
}

(async () => {
  const checks = [];
  const check = (n, ok, extra) => checks.push([n, ok, extra]);

  const cases = [
    ['WAV 16 بیت مونو ۴۴٫۱k', buildWav(tone(44100, 1.2), 44100, { bits: 16 }), 22050],
    ['WAV 24 بیت مونو', buildWav(tone(48000, 1.2), 48000, { bits: 24 }), 22050],
    ['WAV 32 بیت اینت مونو', buildWav(tone(22050, 1.2), 22050, { bits: 32 }), 22050],
    ['WAV float32', buildWav(tone(44100, 1.2), 44100, { bits: 32, float: true }), 22050],
    ['WAV 8 بیت', buildWav(tone(22050, 1.2), 22050, { bits: 8 }), 22050],
    ['WAV استریو ۴۸k', buildWav(tone(48000, 1.2), 48000, { channels: 2 }), 22050],
    ['WAV استریوی فازمخالف', buildWav(tone(44100, 1.2), 44100, { channels: 2, phaseInvert: true }), 22050],
  ];

  console.log('تست رمزگشایی و استانداردسازی آفلاین');
  console.log('─'.repeat(72));
  let bad = 0;

  for (const [label, bytes, _sr] of cases) {
    const file = new File([bytes], 'x.wav', { type: 'audio/wav' });
    try {
      const decoded = await OFF.decodeFile(file);
      const res = global.LocalEngineCore.analyzeChannels(decoded.channels, decoded.sr, {});
      const rep = global.LocalEngineCore.buildReport(res, { file: label });
      const hi = rep.range.highest, lo = rep.range.lowest;
      const ok = decoded.sr > 0
        && Math.abs(hi.f0_hz - 330) / 330 < 0.03
        && Math.abs(lo.f0_hz - 220) / 220 < 0.03
        && Math.abs(res.sr - 22050) < 1;
      if (!ok) bad++;
      console.log((ok ? '✓ ' : '✖ ') + label.padEnd(26) +
        ' کانال=' + decoded.channels.length +
        ' نرخ ورودی=' + decoded.sr +
        ' → ' + res.sr +
        ' | ' + hi.note_name + ' ↕ ' + lo.note_name +
        ' | span=' + rep.range.span_semitones);
    } catch (e) {
      bad++;
      console.log('✖ ' + label + ' — خطا: ' + e.message);
    }
  }

  // فایل ناشناخته باید پیام فارسی روشن بدهد
  try {
    await OFF.decodeFile(new File([new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8])], 'x.mp4', { type: 'video/mp4' }));
    check('فایل ناشناخته پیام خطا داد', false, 'بدون خطا برگشت');
  } catch (e) {
    check('فایل ناشناخته پیام خطا داد', /ویدیو|فرمت|خوانده/.test(e.message), e.message.slice(0, 60));
  }
  for (const [n, ok, extra] of checks) {
    if (!ok) bad++;
    console.log((ok ? '✓ ' : '✖ ') + n + (extra ? '  → ' + extra : ''));
  }

  console.log('─'.repeat(72));
  if (bad) { console.log('✖ ' + bad + ' مورد ناموفق'); process.exit(1); }
  console.log('✅ همهٔ فرمت‌های WAV درست خوانده و استاندارد شدند');
})();
