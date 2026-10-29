#!/usr/bin/env node
/* ============================================================================
   تست «سنجشِ پخش روی همان دستگاه + سبک‌سازیِ خودکار»
   ----------------------------------------------------------------------------
   چرا؟ کاربر گفت لگ هنوز هست. «فایلِ سالم» کافی نیست؛ باید همان دستگاه
   فایل را روان پخش کند. این تست:
     • نردبانِ تنظیماتِ کدگذار را می‌سنجد (اگر WebView کلیدِ اختیاری را قبول
       نکند، به مسیرِ ضبطِ زمان‌واقعی نمی‌افتد)
     • سنجشِ پخش را با پخش‌کنندهٔ قلابیِ «روان» و «لگ‌دار» می‌سنجد
     • مطمئن می‌شود وقتی پخش لگ دارد، خودکار نسخهٔ سبک‌تر ساخته می‌شود
       (۱۰۸۰p۶۰ ⇒ ۷۲۰p۶۰ ⇒ ۷۲۰p۳۰) و این تصمیم در خروجی گزارش می‌شود

   اجرا:  node web/tests/video_probe_test.js
   ========================================================================== */
'use strict';

/* ── بوم/زمینهٔ قلابی ────────────────────────────────────────────────────── */
function makeGradient() { return { addColorStop() { } }; }
function makeCtx() {
  const base = {
    createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(base, {
    get(t, p) {
      if (p in t) return t[p];
      if (typeof p === 'symbol') return undefined;
      return function () { };
    },
    set(t, p, v) { t[p] = v; return true; }
  });
}

/* ── پخش‌کنندهٔ قلابی: smooth = روان، stutter = لگ‌دار ─────────────────── */
const PLAYER = { mode: 'smooth', created: 0 };
function makeVideo() {
  const ev = {};
  const mode = PLAYER.mode;
  PLAYER.created++;
  const v = {
    duration: 6, muted: false, playsInline: false, preload: '', currentTime: 0,
    addEventListener(name, fn) { (ev[name] = ev[name] || []).push(fn); },
    setAttribute() { },
    play() { return Promise.resolve(); },
    pause() { },
    getVideoPlaybackQuality() {
      return { totalVideoFrames: 180, droppedVideoFrames: mode === 'stutter' ? 40 : 0 };
    },
    requestVideoFrameCallback(cb) {
      setTimeout(() => cb(Date.now()), mode === 'stutter' ? 90 : 16);
      return 1;
    },
    set src(_u) { setTimeout(() => (ev['loadeddata'] || []).forEach(f => f()), 5); },
    get src() { return ''; }
  };
  return v;
}

global.document = {
  createElement(tag) {
    if (tag === 'video') return makeVideo();
    if (tag !== 'canvas') return {};
    return {
      width: 0, height: 0, getContext: () => makeCtx(),
      toBlob: cb => cb(new Blob([])),
      captureStream: () => ({ getTracks: () => [], addTrack() { } })
    };
  }
};
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;
global.requestAnimationFrame = cb => setTimeout(() => cb(Date.now()), 8);

/* ── WebCodecs + مالتی‌پلکسرِ واقعی ─────────────────────────────────────── */
const MP4 = require('../vendor/mp4-muxer.js');
const AVC_DESC = new Uint8Array([1, 0x64, 0, 0x28, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0, 0x28, 1, 0, 4, 0x68, 0xee, 0x3c, 0x80]);
const AAC_DESC = new Uint8Array([0x12, 0x10]);
const enc = { configs: [], frames: 0 };

global.VideoFrame = class { constructor(s, o) { this.timestamp = o.timestamp; this.duration = o.duration; } close() { } };
global.AudioData = class { constructor(o) { this.timestamp = o.timestamp; this.sampleRate = o.sampleRate; this.numberOfFrames = o.numberOfFrames; } close() { } };

global.VideoEncoder = class {
  static async isConfigSupported(cfg) {
    /* دستگاهی که کلیدهای اختیاری را قبول نمی‌کند */
    if (global.__STRICT_CFG && (cfg.bitrateMode || cfg.avc || cfg.hardwareAcceleration)) {
      return { supported: false, config: cfg };
    }
    return { supported: /^avc1\./.test(cfg.codec), config: cfg };
  }
  constructor(opts) { this._out = opts.output; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; enc.configs.push(cfg); }
  encode(frame, o) {
    enc.frames++;
    const key = !!(o && o.keyFrame);
    const chunk = {
      timestamp: frame.timestamp, duration: frame.duration,
      type: key ? 'key' : 'delta', byteLength: key ? 500 : 90,
      copyTo(dst) { if (dst && dst.fill) dst.fill(key ? 3 : 1); }
    };
    this._out(chunk, key ? {
      decoderConfig: { codec: this.cfg.codec, width: this.cfg.width, height: this.cfg.height, description: AVC_DESC }
    } : null);
  }
  async flush() { }
  close() { }
};

global.AudioEncoder = class {
  static async isConfigSupported(cfg) { return { supported: /^mp4a\./.test(cfg.codec), config: cfg }; }
  constructor(opts) { this._out = opts.output; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; }
  encode(ad) {
    const first = !this._sent; this._sent = true;
    this._out({
      timestamp: ad.timestamp, duration: Math.round(1024 / ad.sampleRate * 1e6),
      type: 'key', byteLength: 60, copyTo(dst) { if (dst && dst.fill) dst.fill(9); }
    }, first ? {
      decoderConfig: { codec: this.cfg.codec, sampleRate: ad.sampleRate, numberOfChannels: 1, description: AAC_DESC }
    } : null);
  }
  async flush() { }
  close() { }
};

global.Mp4Muxer = { Muxer: MP4.Muxer, ArrayBufferTarget: MP4.ArrayBufferTarget };

require('../engine.js');
require('../trace_style.js');
require('../offline.js');
const OFF = global.LocalOffline;

let pass = 0, fail = 0;
function check(name, ok, extra) {
  if (ok) { pass++; console.log('✓ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
  else { fail++; console.log('✖ ' + name + (extra !== undefined ? '  → ' + extra : '')); }
}

/* ── ۱) تصمیمِ سبک‌سازی ─────────────────────────────────────────────────── */
const steps = [
  [{ W: 1920, H: 1080, fps: 60 }, { quality: '720p', fps: 60 }],
  [{ W: 1280, H: 720, fps: 60 }, { quality: '720p', fps: 30 }],
  [{ W: 1280, H: 720, fps: 30 }, { quality: '720p', fps: 24 }],
  [{ W: 1280, H: 720, fps: 24 }, null]
];
let stepOk = true, stepTxt = [];
for (const [inp, want] of steps) {
  const got = OFF.nextPlanAfterStutter(inp);
  const same = (want === null) ? got === null
    : (!!got && got.quality === want.quality && got.fps === want.fps);
  if (!same) stepOk = false;
  stepTxt.push(inp.W + '×' + inp.H + '@' + inp.fps + '→' + (got ? got.quality + '@' + got.fps : 'توقف'));
}
check('زنجیرهٔ سبک‌سازی درست است', stepOk, stepTxt.join(' · '));

/* ── ۲) نردبانِ تنظیماتِ کدگذار ─────────────────────────────────────────── */
const cands = OFF.avcCandidates(1920, 1080, 60, 11000000);
check('نردبان شاملِ ترکیبِ کامل و ترکیب‌های ساده است',
  cands.length >= 15 && cands[0].bitrateMode === 'variable' &&
  cands.some(c => !c.bitrateMode && !c.avc && !c.hardwareAcceleration),
  cands.length + ' ترکیب · اولی=' + cands[0].codec);
check('همهٔ ترکیب‌ها ابعاد/نرخ/بیتِ درست دارند',
  cands.every(c => c.width === 1920 && c.height === 1080 && c.framerate === 60 && c.bitrate === 11000000));

/* ── ۳) سنجشِ پخش ───────────────────────────────────────────────────────── */
const SR = 22050, DUR = 3;
const wav = new Float32Array(Math.round(SR * DUR));
for (let i = 0; i < wav.length; i++) wav[i] = 0.2 * Math.sin(2 * Math.PI * 220 * i / SR);
const times = [], freqs = [];
for (let t = 0; t <= DUR; t += 0.01) { times.push(t); freqs.push(220); }
const pitch = { times, freqs, trace_freqs: freqs };

(async () => {
  PLAYER.mode = 'smooth';
  const blob = new Blob([new Uint8Array(1024)], { type: 'video/mp4' });
  const good = await OFF.playbackProbe(blob, 60, 0.5);
  check('پخش‌کنندهٔ روان ⇒ سنجش «روان»', good.ok === true,
    'فریمِ نمایش‌داده‌شده=' + good.presented + ' · بیشترین فاصله=' + good.max_gap_ms + 'ms');

  PLAYER.mode = 'stutter';
  const bad = await OFF.playbackProbe(blob, 60, 0.5);
  check('پخش‌کنندهٔ لگ‌دار ⇒ سنجش «لگ»', bad.ok === false,
    'بیشترین فاصله=' + bad.max_gap_ms + 'ms (نرمال ' + bad.expected_ms + 'ms) · ' + bad.bad_gaps + ' وقفه');

  /* ── ۴) رندرِ کامل با پخش‌کنندهٔ لگ‌دار ⇒ سبک‌سازیِ خودکار ── */
  PLAYER.mode = 'stutter';
  enc.configs.length = 0; enc.frames = 0;
  const out = await OFF.renderVideo(pitch, [], wav, SR, null,
    { windowSec: 6, tightness: 2, fps: 60, probeSec: 0.5 });
  check('با پخشِ لگ‌دار، نسخهٔ سبک‌تر خودکار ساخته شد',
    out.auto_downgraded === true && out.width === 1280 && out.height === 720,
    out.width + '×' + out.height + '@' + out.fps +
    ' (از ' + out.downgraded_from + ') · دلیل: ' + out.downgrade_reason);
  check('تلاشِ دوم با ۷۲۰p انجام شد', enc.configs.some(c => c.width === 1280 && c.height === 720),
    enc.configs.filter(c => c.width).map(c => c.width + '×' + c.height).join(' → '));
  check('فایلِ سبک‌شده هم از گیتِ طول رد می‌شود',
    !!(out.verify && out.verify.ok), out.verify ? (out.verify.ok ? 'بدونِ ایراد' : out.verify.problems.join(' | ')) : '—');

  /* ── ۵) پخش‌کنندهٔ روان ⇒ هیچ سبک‌سازی ── */
  PLAYER.mode = 'smooth';
  enc.configs.length = 0; enc.frames = 0;
  const out2 = await OFF.renderVideo(pitch, [], wav, SR, null,
    { windowSec: 6, tightness: 2, fps: 60, probeSec: 0.5 });
  check('پخش‌کنندهٔ روان ⇒ بدونِ سبک‌سازی (۱۰۸۰p می‌ماند)',
    !out2.auto_downgraded && out2.width === 1920,
    out2.width + '×' + out2.height + '@' + out2.fps + ' · ' + (out2.probe ? out2.probe.reason : '—'));

  /* ── ۶) دستگاهی که کلیدهای اختیاریِ کدگذار را قبول نمی‌کند ── */
  global.__STRICT_CFG = true;
  enc.configs.length = 0;
  const out3 = await OFF.renderVideo(pitch, [], wav, SR, null,
    { windowSec: 6, tightness: 2, fps: 60, probe: false });
  global.__STRICT_CFG = false;
  check('اگر WebView کلیدهای اختیاری را قبول نکند، باز هم مسیرِ سریع می‌ماند',
    out3.path === 'fast' && out3.mime === 'video/mp4',
    'path=' + out3.path + ' · cfg=' + (enc.configs[0] ? Object.keys(enc.configs[0]).join(',') : '—'));
  check('در آن حالت هم طولِ فایل درست است',
    !!(out3.verify && out3.verify.ok), out3.verify ? out3.verify.movie_duration_sec + 's' : '—');

  console.log('─'.repeat(70));
  if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
  console.log('✅ سنجشِ پخش و سبک‌سازیِ خودکار درست کار می‌کند (' + pass + ' بررسی)');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
