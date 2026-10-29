#!/usr/bin/env node
/* ============================================================================
   تست «هدر و طولِ فایلِ ویدیو» — با خودِ مالتی‌پلکسر واقعی (mp4-muxer)
   ----------------------------------------------------------------------------
   این تست به‌جای استاب، `web/vendor/mp4-muxer.js` واقعی را اجرا می‌کند
   (کدگذارها استاب‌اند) و بعد خودِ فایل MP4 را با یک پارسر کوچک می‌خواند:

     • mvhd / mdhd هر دو مسیر: طولِ فایل = طولِ واقعیِ ویدیو (نه ۴ یا ۷ ثانیه)
     • نمونهٔ آخرِ ویدیو و صدا طولِ صفر ندارد (علتِ «قفل‌کردن در انتها»)
     • صدا دقیقاً تا انتهای تصویر تمام می‌شود (نه کوتاه‌تر، نه بلندتر)
     • هدرِ اصلاح‌شده (patchMp4Duration) همان طولِ درست را می‌دهد
     • WebM: عنصر Duration در Segment/Info نوشته/اصلاح می‌شود
     • کدک بدون B-frame درخواست می‌شود (latencyMode=realtime)

   اجرا:  node web/tests/mp4_meta_test.js
   ========================================================================== */
'use strict';

/* ── استاب حداقلی ─────────────────────────────────────────────────────── */
function makeGradient() { return { addColorStop() { } }; }
function makeCtx() {
  const base = {
    createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(base, {
    get(t, p) { return p in t ? t[p] : (typeof p === 'symbol' ? undefined : function () { }); },
    set(t, p, v) { t[p] = v; return true; }
  });
}
global.document = {
  createElement(tag) {
    if (tag !== 'canvas') return {};
    const c = { width: 0, height: 0, getContext: () => makeCtx(), toBlob: cb => cb(new Blob([])) };
    c.captureStream = () => ({ getTracks: () => [], addTrack() { }, getAudioTracks: () => [], getVideoTracks: () => [] });
    return c;
  }
};
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

/* ── کدگذارهای قلابی (چانک ساختگی با طول؛ گاهی بدون duration) ─────────── */
let vidCount = 0, audCount = 0;
let videoCfg = null;
global.VideoFrame = class {
  constructor(src, opts) { this.timestamp = opts.timestamp; this.duration = opts.duration; }
  close() { }
};
global.AudioData = class {
  constructor(opts) { this.timestamp = opts.timestamp; this.numberOfFrames = opts.numberOfFrames; }
  close() { }
};
global.VideoEncoder = class {
  static async isConfigSupported(cfg) { return { supported: /^avc1\./.test(cfg.codec), config: cfg }; }
  constructor(o) { this._out = o.output; this.encodeQueueSize = 0; }
  configure(cfg) { videoCfg = cfg; }
  encode(frame) {
    const n = ++vidCount;
    const chunk = {
      timestamp: frame.timestamp, duration: frame.duration, type: n % 120 === 0 ? 'key' : 'delta',
      byteLength: 120, copyTo(d) { d.fill(1); }
    };
    this._out(chunk, { decoderConfig: { codec: videoCfg.codec } });
  }
  async flush() { }
  close() { }
};
global.AudioEncoder = class {
  static async isConfigSupported(cfg) { return { supported: /^mp4a\./.test(cfg.codec), config: cfg }; }
  constructor(o) { this._out = o.output; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; }
  encode(ad) {
    const n = ++audCount;
    const chunk = {
      timestamp: ad.timestamp,
      /* مثل کروم: بعضی چانک‌ها طول ندارند */
      duration: n % 4 === 0 ? undefined : Math.round(1024 / this.cfg.sampleRate * 1e6),
      type: 'key', byteLength: 60, copyTo(d) { d.fill(2); }
    };
    this._out(chunk, { decoderConfig: { codec: this.cfg.codec } });
  }
  async flush() { }
  close() { }
};

require('../engine.js');
require('../trace_style.js');
global.Mp4Muxer = require('../vendor/mp4-muxer.js');   /* مالتی‌پلکسرِ واقعی */
require('../offline.js');
const CORE = global.LocalEngineCore;
const OFF = global.LocalOffline;

let pass = 0, fail = 0;
const check = (n, ok, x) => {
  if (ok) { pass++; console.log('✓ ' + n + (x !== undefined ? '  → ' + x : '')); }
  else { fail++; console.log('✖ ' + n + (x !== undefined ? '  → ' + x : '')); }
};

/* ── پارسر MP4: طولِ mvhd و mdhd ──────────────────────────────────────── */
function mp4Info(buf) {
  const dv = new DataView(buf);
  const fourcc = o => String.fromCharCode(dv.getUint8(o), dv.getUint8(o + 1), dv.getUint8(o + 2), dv.getUint8(o + 3));
  function walk(start, end, fn) {
    let off = start;
    while (off + 8 <= end) {
      let size = dv.getUint32(off), hdr = 8;
      if (size === 1) { size = Number(dv.getBigUint64(off + 8)); hdr = 16; }
      else if (size === 0) size = end - off;
      if (size < hdr || off + size > end) break;
      fn(fourcc(off + 4), off, size, hdr);
      off += size;
    }
  }
  const out = { mvhd: null, mdhd: [] };
  walk(0, buf.byteLength, (type, off, size, hdr) => {
    if (type !== 'moov') return;
    walk(off + hdr, off + size, (t2, o2, s2, h2) => {
      const body = o2 + h2, ver = dv.getUint8(body);
      if (t2 === 'mvhd') {
        out.mvhd = ver === 0
          ? { ts: dv.getUint32(body + 12), dur: dv.getUint32(body + 16) }
          : { ts: dv.getUint32(body + 20), dur: Number(dv.getBigUint64(body + 24)) };
      } else if (t2 === 'trak') {
        walk(o2 + h2, o2 + s2, (t3, o3, s3, h3) => {
          if (t3 !== 'mdia') return;
          walk(o3 + h3, o3 + s3, (t4, o4, s4, h4) => {
            if (t4 !== 'mdhd') return;
            const b4 = o4 + h4, v4 = dv.getUint8(b4);
            out.mdhd.push(v4 === 0
              ? { ts: dv.getUint32(b4 + 12), dur: dv.getUint32(b4 + 16) }
              : { ts: dv.getUint32(b4 + 20), dur: Number(dv.getBigUint64(b4 + 24)) });
          });
        });
      }
    });
  });
  return out;
}

function tone(seconds, sr, plan) {
  const n = Math.round(sr * seconds);
  const y = new Float32Array(n);
  plan.forEach(([st, len, f]) => {
    const i0 = Math.round(st * sr), m = Math.round(len * sr);
    let ph = 0;
    for (let i = 0; i < m && i0 + i < n; i++) {
      ph += 2 * Math.PI * f / sr;
      const env = Math.min(1, i / sr / 0.02) * Math.min(1, (len - i / sr) / 0.03);
      let s = 0;
      for (let k = 1; k <= 8; k++) s += Math.sin(k * ph) / k;
      y[i0 + i] += 0.2 * s * Math.max(0, env);
    }
  });
  return y;
}

(async () => {
  const sr = 22050;
  const dur = 9.0;
  const y = tone(dur, sr, [[0.2, 2.0, 220], [2.6, 2.0, 293.7], [5.0, 2.4, 349.2], [7.7, 1.0, 261.6]]);
  const res = CORE.analyzeChannels([y], sr, {});

  vidCount = 0; audCount = 0;
  const out = await OFF.renderVideo(res, res.notes, res.wav, res.sr, null,
    { windowSec: 0, tightness: 1, fps: 60, quality: '720p' });
  const buf = await out.blob.arrayBuffer();
  const info = mp4Info(buf);

  const exact = out.frames / out.fps;
  check('خروجی مسیر سریع (WebCodecs) است', out.fast === true && out.mime === 'video/mp4');
  check('طولِ اعلام‌شده = تعداد فریم ÷ نرخ فریم', Math.abs(out.duration - exact) < 1e-9,
    exact.toFixed(3) + 's');
  check('mvhd طولِ درست را می‌دهد', info.mvhd && Math.abs(info.mvhd.dur / info.mvhd.ts - exact) < 0.02,
    info.mvhd ? (info.mvhd.dur / info.mvhd.ts).toFixed(2) + 's (انتظار ' + exact.toFixed(2) + 's)' : '—');
  check('هر دو مسیر (تصویر و صدا) در بسته هست',
    info.mdhd.length === 2, info.mdhd.length + ' مسیر');
  const secs = info.mdhd.map(m => m.dur / m.ts);
  check('طولِ مسیر تصویر = طولِ کامل', Math.abs(secs[0] - exact) < 0.02, secs[0].toFixed(2) + 's');
  check('طولِ مسیر صدا هم = طولِ کامل (نه کوتاه‌تر)',
    Math.abs(secs[1] - exact) < 0.05, secs[1].toFixed(2) + 's');
  check('هیچ مسیری طولِ صفر یا خیلی کوتاه ندارد',
    secs.every(s => s > exact * 0.98), secs.map(s => s.toFixed(2)).join(' | '));

  /* هدرِ اصلاح‌شده */
  const fake = new ArrayBuffer(0);
  const patched = OFF.patchMp4Duration(buf, exact + 3);
  const pInfo = mp4Info(patched);
  check('patchMp4Duration طولِ هدر را عوض می‌کند',
    Math.abs(pInfo.mvhd.dur / pInfo.mvhd.ts - (exact + 3)) < 0.02,
    (pInfo.mvhd.dur / pInfo.mvhd.ts).toFixed(2) + 's');
  check('patch روی امضای اصلی اثری ندارد', mp4Info(buf).mvhd.dur === info.mvhd.dur);

  /* کدک بدون B-frame */
  check('کدگذار با latencyMode=realtime (بدون B-frame) تنظیم می‌شود',
    videoCfg && videoCfg.latencyMode === 'realtime', videoCfg && videoCfg.latencyMode);

  /* ── WebM: نوشتن Duration ── */
  {
    /* یک WebM کوچک با Segment/Info و بدون Duration می‌سازیم */
    const bytes = [];
    const push = (...v) => v.forEach(x => bytes.push(x));
    /* EBML header */
    push(0x1A, 0x45, 0xDF, 0xA3, 0x84, 0x42, 0x86, 0x81, 0x01, 0x42, 0xF7, 0x81, 0x01,
      0x42, 0xF2, 0x81, 0x04, 0x42, 0xF3, 0x81, 0x08, 0x42, 0x82, 0x84, 0x77, 0x65, 0x62, 0x6D);
    /* Segment (اندازهٔ نامعلوم) */
    push(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF);
    /* Info با یک عنصر TimecodeScale */
    push(0x15, 0x49, 0xA9, 0x66, 0x89, 0x2A, 0xD7, 0xB1, 0x83, 0x0F, 0x42, 0x40);
    const src = new Uint8Array(bytes).buffer;
    const fixed = OFF.patchWebmDuration(src, 12.5);
    const fb = new Uint8Array(fixed);
    let pos = -1;
    for (let i = 0; i + 2 < fb.length; i++) if (fb[i] === 0x44 && fb[i + 1] === 0x89) { pos = i; break; }
    check('WebM: عنصر Duration اضافه می‌شود', pos > 0, pos > 0 ? 'در بایت ' + pos : 'پیدا نشد');
    if (pos > 0) {
      const v = new DataView(fixed).getFloat64(pos + 3);
      check('WebM: مقدار Duration دقیقاً نوشته می‌شود', Math.abs(v - 12.5) < 1e-6, v + 's');
    }
  }

  console.log('─'.repeat(70));
  if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
  console.log('✅ هدر و طولِ فایل ویدیو درست است (' + pass + ' بررسی)');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
