#!/usr/bin/env node
/* ============================================================================
   تست «رندر سریع ویدیو» (WebCodecs) بدون مرورگر
   ----------------------------------------------------------------------------
   نود خودش WebCodecs ندارد، پس کدگذارها/مالتی‌پلکسر با استاب شبیه‌سازی
   می‌شوند و این‌ها بررسی می‌شود:
     • مسیر سریع انتخاب می‌شود و خروجی MP4 است (نه ضبط زمان‌واقعی)
     • تعداد فریم = طول صدا × ۶۰ و برچسب زمانی هر فریم دقیقاً i/60 ثانیه
       ⇒ صوت و تصویر بدون تأخیر هم‌زمان‌اند
     • فریم‌های کلیدی هر ۲ ثانیه
     • صدا کامل کدگذاری می‌شود (همان طول فایل)
     • رندر بسیار سریع‌تر از زمان‌واقعی تمام می‌شود
     • اگر WebCodecs نبود، خودکار به مسیر پشتیبان برمی‌گردد

   اجرا:  node web/tests/video_fast_test.js
   ========================================================================== */
'use strict';

/* ── استاب‌ها ─────────────────────────────────────────────────────────── */
const drawn = [];
function makeGradient() { return { addColorStop() { } }; }
function makeCtx() {
  const base = {
    createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(base, {
    get(t, p) {
      if (p in t) return t[p];
      if (p === 'fillRect') return () => drawn.push(1);
      if (typeof p === 'symbol') return undefined;
      return function () { };
    },
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

/* ── WebCodecs + muxer قلابی ──────────────────────────────────────────── */
const enc = { videoChunks: [], audioChunks: [], added: [], finalized: 0, configs: [] };

global.VideoFrame = class VideoFrame {
  constructor(source, opts) { this.timestamp = opts.timestamp; this.duration = opts.duration; this.closed = false; }
  close() { this.closed = true; }
};
global.AudioData = class AudioData {
  constructor(opts) {
    this.timestamp = opts.timestamp;
    this.numberOfFrames = opts.numberOfFrames;
    this.sampleRate = opts.sampleRate;
  }
  close() { }
};
global.VideoEncoder = class VideoEncoder {
  static async isConfigSupported(cfg) { return { supported: /^avc1\./.test(cfg.codec), config: cfg }; }
  constructor(opts) { this._out = opts.output; this._err = opts.error; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; enc.configs.push(cfg); }
  encode(frame, o) {
    const keyFrame = !!(o && o.keyFrame);
    this.encodeQueueSize++;
    const chunk = {
      timestamp: frame.timestamp, duration: frame.duration,
      type: keyFrame ? 'key' : 'delta', byteLength: 100,
      copyTo(dst) { if (dst && dst.fill) dst.fill(7); }
    };
    enc.videoChunks.push({ ts: frame.timestamp, keyFrame });
    this._out(chunk, { decoderConfig: { codec: this.cfg.codec } });
    this.encodeQueueSize--;
  }
  async flush() { }
  close() { }
};
global.AudioEncoder = class AudioEncoder {
  static async isConfigSupported(cfg) { return { supported: /^mp4a\./.test(cfg.codec), config: cfg }; }
  constructor(opts) { this._out = opts.output; this._err = opts.error; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; enc.configs.push(cfg); }
  encode(ad) {
    this.encodeQueueSize++;
    /* مثل مرورگرهای واقعی: برای بعضی چانک‌ها duration نمی‌دهد */
    const chunk = {
      timestamp: ad.timestamp, duration: (enc.audioChunks.length % 3 === 0 ? undefined : 46439),
      type: 'key', byteLength: 50,
      copyTo(dst) { if (dst && dst.fill) dst.fill(9); }
    };
    enc.audioChunks.push({ ts: ad.timestamp });
    this._out(chunk, { decoderConfig: { codec: this.cfg.codec } });
    this.encodeQueueSize--;
  }
  async flush() { }
  close() { }
};
global.Mp4Muxer = {
  ArrayBufferTarget: class { constructor() { this.buffer = new ArrayBuffer(2048); } },
  Muxer: class {
    constructor(opts) { this.opts = opts; this.target = opts.target; }
    addVideoChunkRaw(data, type, ts, dur, meta) {
      enc.added.push({ kind: 'v', ts: ts, dur: dur, type: type, bytes: data.byteLength });
    }
    addAudioChunkRaw(data, type, ts, dur, meta) {
      enc.added.push({ kind: 'a', ts: ts, dur: dur, type: type, bytes: data.byteLength });
    }
    addVideoChunk(c, m) { enc.added.push({ kind: 'v', ts: c.timestamp, dur: c.duration }); }
    addAudioChunk(c, m) { enc.added.push({ kind: 'a', ts: c.timestamp, dur: c.duration }); }
    finalize() { enc.finalized++; }
  }
};

require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const CORE = global.LocalEngineCore;
const OFF = global.LocalOffline;

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
  else { fail++; console.log('✖ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
}

function tone(seconds, sr, notes) {
  const n = Math.round(sr * seconds);
  const y = new Float32Array(n);
  notes.forEach(([start, dur, f]) => {
    const i0 = Math.round(start * sr), len = Math.round(dur * sr);
    for (let i = 0; i < len && i0 + i < n; i++) {
      const t = i / sr;
      const env = Math.min(1, t / 0.02) * Math.min(1, (dur - t) / 0.03);
      let s = 0;
      for (let k = 1; k <= 8; k++) s += Math.sin(2 * Math.PI * f * k * t) / k;
      y[i0 + i] += 0.2 * s * Math.max(0, env);
    }
  });
  return y;
}

(async () => {
  check('WebCodecs در دسترس تشخیص داده می‌شود', OFF.webCodecsReady() === true);
  check('نرخ فریم هدف ۶۰ و ابعاد پایه ۱۲۸۰×۷۲۰ (۱۰۸۰p پیش‌فرض)',
    OFF.VIDEO.W === 1280 && OFF.VIDEO.H === 720 && OFF.VIDEO.fps === 60,
    OFF.VIDEO.W + '×' + OFF.VIDEO.H + ' @' + OFF.VIDEO.fps);

  const sr = 22050;
  const dur = 3.0;
  const y = tone(dur, sr, [[0.1, 0.8, 220], [1.1, 0.8, 293.7], [2.1, 0.7, 349.2]]);
  const res = CORE.analyzeChannels([y], sr, {});

  const t0 = Date.now();
  const progress = [];
  const out = await OFF.renderVideo(res, res.notes, res.wav, res.sr, f => progress.push(f),
    { windowSec: 6, tightness: 2, fps: 60 });
  const wall = (Date.now() - t0) / 1000;

  check('مسیر سریع (WebCodecs) انتخاب شد', out.fast === true, out.fast ? 'fast' : 'realtime');
  check('خروجی MP4 است', out.mime === 'video/mp4' && /\.mp4$/.test(out.name), out.name);
  /* طول بوم = آخرین زمان نمونه (همان چیزی که رندرر می‌کشد) */
  const renderDur = res.times[res.times.length - 1];
  const expectFrames = Math.round(renderDur * 60);
  check('تعداد فریم = طول صدا × ۶۰', Math.abs(enc.videoChunks.length - expectFrames) <= 2,
    enc.videoChunks.length + ' فریم برای ' + renderDur.toFixed(2) + 's');

  /* برچسب زمانی: دقیقاً i/60 ثانیه و صعودی */
  let mono = true, maxErr = 0;
  for (let i = 1; i < enc.videoChunks.length; i++) {
    if (enc.videoChunks[i].ts <= enc.videoChunks[i - 1].ts) mono = false;
  }
  for (let i = 0; i < enc.videoChunks.length; i++) {
    const err = Math.abs(enc.videoChunks[i].ts - Math.round((i / 60) * 1e6));
    if (err > maxErr) maxErr = err;
  }
  check('برچسب زمانی فریم‌ها صعودی است', mono);
  check('برچسب زمانی دقیقاً i/60 ثانیه است (بدون تأخیر صوت/تصویر)', maxErr <= 1, maxErr + 'µs');

  const keys = enc.videoChunks.filter(c => c.keyFrame);
  check('فریم کلیدی هر ۲ ثانیه', keys.length === Math.ceil(enc.videoChunks.length / 120),
    keys.length + ' فریم کلیدی');

  const audioSec = enc.audioChunks.length * 1024 / sr;
  check('صدا کامل کدگذاری شد (هم‌طول فایل)', Math.abs(audioSec - res.duration) < 0.12,
    audioSec.toFixed(2) + 's از ' + res.duration.toFixed(2) + 's');

  check('همهٔ چانک‌ها به مالتی‌پلکسر رسیدند و فایل نهایی شد',
    enc.added.length === enc.videoChunks.length + enc.audioChunks.length && enc.finalized === 1,
    enc.added.length + ' چانک');
  const addSorted = enc.added.every((c, i) => i === 0 || c.ts >= enc.added[i - 1].ts);
  check('چانک‌ها به ترتیب زمان نوشته شدند', addSorted);

  check('پیشرفت گزارش شد', progress.length > 5 && progress[progress.length - 1] > 0.95,
    progress.length + ' گزارش');
  check('رندر سریع‌تر از زمان‌واقعی است', wall < res.duration,
    wall.toFixed(2) + 's برای ' + res.duration.toFixed(2) + 's صدا');

  const framesDrawn = drawn.length;
  check('فریم‌ها واقعاً رسم شدند', framesDrawn >= enc.videoChunks.length,
    framesDrawn + ' رسم');

  /* ── اندازهٔ قاب: ۱۰۸۰p پیش‌فرض، ۷۲۰p با انتخاب کاربر ── */
  check('پیش‌فرض = ۱۰۸۰p (قاب عریض‌تر)', out.width === 1920 && out.height === 1080,
    out.width + '×' + out.height + ' @' + out.fps);
  check('کدک ۱۰۸۰p@60 با سطح ۴٫۲ درخواست شد',
    enc.configs.some(c => c.width === 1920 && c.framerate === 60 && /2A|33/.test(c.codec)),
    enc.configs.map(c => c.codec).join(','));
  check('کدگذاری سخت‌افزاری ترجیح داده می‌شود',
    enc.configs.filter(c => c.width > 0).length > 0 &&
    enc.configs.filter(c => c.width > 0).every(c => c.hardwareAcceleration === 'prefer-hardware'));

  enc.videoChunks.length = 0; enc.added.length = 0; enc.finalized = 0;
  const out720 = await OFF.renderVideo(res, res.notes, res.wav, res.sr, null,
    { windowSec: 6, tightness: 1, fps: 60, quality: '720p' });
  check('انتخاب ۷۲۰p رعایت می‌شود', out720.width === 1280 && out720.height === 720,
    out720.width + '×' + out720.height);

  /* دستگاه ضعیف: ۱۰۸۰p پشتیبانی نمی‌شود ⇒ خودکار ۷۲۰p */
  const saveSup = global.VideoEncoder.isConfigSupported;
  global.VideoEncoder.isConfigSupported = async (cfg) =>
    ({ supported: /^avc1\./.test(cfg.codec) && cfg.width <= 1280, config: cfg });
  enc.videoChunks.length = 0; enc.added.length = 0; enc.finalized = 0;
  const outFall = await OFF.renderVideo(res, res.notes, res.wav, res.sr, null,
    { windowSec: 6, tightness: 1, fps: 60 });
  global.VideoEncoder.isConfigSupported = saveSup;
  check('اگر ۱۰۸۰p پشتیبانی نشود، خودکار ۷۲۰p می‌شود',
    outFall.width === 1280 && outFall.height === 720 && outFall.fast === true,
    outFall.width + '×' + outFall.height);
  check('در حالت بازگشتی هم فریم‌ها ۶۰fps و کامل‌اند',
    Math.abs(enc.videoChunks.length - Math.round(renderDur * 60)) <= 2,
    enc.videoChunks.length + ' فریم');

  /* ── پشتیبان: بدون WebCodecs باید مسیر زمان‌واقعی انتخاب شود ── */
  const saveVE = global.VideoEncoder, saveAE = global.AudioEncoder, saveMux = global.Mp4Muxer;
  global.VideoEncoder = undefined; global.AudioEncoder = undefined; global.Mp4Muxer = undefined;
  check('نبودِ WebCodecs تشخیص داده می‌شود', OFF.webCodecsReady() === false);
  global.VideoEncoder = saveVE; global.AudioEncoder = saveAE; global.Mp4Muxer = saveMux;

  console.log('─'.repeat(70));
  if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
  console.log('✅ رندر سریع ویدیو سالم است (' + pass + ' بررسی)');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
