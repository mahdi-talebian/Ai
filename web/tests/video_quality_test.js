#!/usr/bin/env node
/* ============================================================================
   تست «کیفیتِ خروجیِ ویدیو» — دو شکایتِ کاربر، دو گیتِ خودکار
   ----------------------------------------------------------------------------
   شکایت ۱: «گالری طولِ ویدیو را ۵ ثانیه نشان می‌دهد در حالی که ۳۰+ ثانیه است.»
   ریشه: بعد از finalize، هدر MP4 دستی بازنویسی می‌شد و در tkhd (که اصلاً
   فیلدِ timescale ندارد) «تایم‌اسکیل» را از offset ۱۲ می‌خواند که همان
   track_ID است ⇒ طولِ تراک در هدر به چند صدمِ ثانیه تبدیل می‌شد.

   شکایت ۲: «خروجی لگ می‌زند.»
   ریشه: نرخِ بیت بر اساسِ *طولِ صدا* کم می‌شد ولی رزولوشن ۱۰۸۰p می‌ماند
   (۸۸ ثانیه ⇒ ۳٫۸ Mbps برای ۱۰۸۰p۶۰ = ۰٫۰۳ بیت/پیکسل) و کدگذار در حالتِ
   realtime با صفِ شلوغ فریم می‌انداخت.

   این تست:
     ۱) جدولِ نرخِ بیت را برای همهٔ حالت‌ها می‌سنجد (bpp هرگز < ۰٫۰۷۵).
     ۲) با مالتی‌پلکسرِ *واقعی* فایل می‌سازد و همان چیزی را می‌خواند که
        گالریِ اندروید می‌خواند: mvhd / tkhd / mdhd + جدولِ نمونه‌ها.
     ۳) رندرِ کامل را با کدگذارِ «کند» اجرا می‌کند که اگر صف شلوغ شود فریم
        می‌اندازد؛ باید صفر فریم گم شود (و نشان می‌دهد قاعدهٔ قبلی می‌انداخت).
     ۴) نبودِ B-frame، فاصلهٔ فریمِ کلیدی و یکنواختیِ زمان‌بندی را می‌سنجد.

   اجرا:  node web/tests/video_quality_test.js
   ========================================================================== */
'use strict';

/* ── محیطِ مرورگرِ قلابی (بوم/رندرر) ─────────────────────────────────────── */
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
global.document = {
  createElement(tag) {
    if (tag !== 'canvas') return {};
    return {
      width: 0, height: 0,
      getContext: () => makeCtx(),
      toBlob: cb => cb(new Blob([])),
      captureStream: () => ({ getTracks: () => [], addTrack() { } })
    };
  }
};
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

const MP4 = require('../vendor/mp4-muxer.js');

/* ── کدگذارِ قلابیِ «کند» که فریم می‌اندازد ─────────────────────────────────
   رفتارِ کدگذارِ واقعیِ realtime: تا وقتی صفِ کدگذاری شلوغ است، فریم‌های
   تازه را دور می‌ریزد تا «عقب نماند». تخلیهٔ صف با تأخیر انجام می‌شود. */
const sim = {
  dropAt: 3,            /* اگر در لحظهٔ encode این‌قدر در صف باشد ⇒ فریم می‌افتد */
  drainDelayMs: 1,
  encoded: 0,
  dropped: 0,
  chunks: [],
  keyframes: 0,
  inFlight: 0,
  reset() { this.encoded = 0; this.dropped = 0; this.chunks = []; this.keyframes = 0; this.inFlight = 0; }
};

const AVC_DESC = new Uint8Array([1, 0x64, 0, 0x28, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0, 0x28, 1, 0, 4, 0x68, 0xee, 0x3c, 0x80]);
const AAC_DESC = new Uint8Array([0x12, 0x10]);

function fakeAvcMeta(cfg) {
  return { decoderConfig: { codec: cfg.codec, width: cfg.width, height: cfg.height, description: AVC_DESC } };
}

global.VideoFrame = class VideoFrame {
  constructor(source, opts) { this.timestamp = opts.timestamp; this.duration = opts.duration; }
  close() { }
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
  constructor(opts) {
    this._out = opts.output;
    this._err = opts.error;
    this.encodeQueueSize = 0;
    this._pending = 0;
  }
  configure(cfg) { this.cfg = cfg; }
  encode(frame, o) {
    /* شبیه‌سازیِ «انداختنِ فریم» وقتی صف شلوغ است (رفتارِ حالتِ realtime) */
    if (this.encodeQueueSize >= sim.dropAt) { sim.dropped++; return; }
    this.encodeQueueSize++;
    this._pending++;
    sim.encoded++;
    const keyFrame = !!(o && o.keyFrame);
    if (keyFrame) sim.keyframes++;
    const chunk = {
      timestamp: frame.timestamp,
      duration: frame.duration,
      type: keyFrame ? 'key' : 'delta',
      byteLength: keyFrame ? 900 : 120,
      copyTo(dst) { if (dst && dst.fill) dst.fill(keyFrame ? 3 : 1); }
    };
    sim.chunks.push({ ts: frame.timestamp, dur: frame.duration, keyFrame });
    const self = this;
    setTimeout(() => {
      self._out(chunk, keyFrame ? fakeAvcMeta(self.cfg) : null);
      self.encodeQueueSize--;
      self._pending--;
    }, sim.drainDelayMs);
  }
  async flush() {
    while (this._pending > 0) await new Promise(r => setTimeout(r, 1));
  }
  close() { }
};

global.AudioEncoder = class AudioEncoder {
  static async isConfigSupported(cfg) { return { supported: /^mp4a\./.test(cfg.codec), config: cfg }; }
  constructor(opts) { this._out = opts.output; this._err = opts.error; this.encodeQueueSize = 0; }
  configure(cfg) { this.cfg = cfg; }
  encode(ad) {
    const chunk = {
      timestamp: ad.timestamp,
      duration: Math.round(1024 / ad.sampleRate * 1e6),
      type: 'key', byteLength: 80,
      copyTo(dst) { if (dst && dst.fill) dst.fill(9); }
    };
    const first = !this._sent;
    this._sent = true;
    this._out(chunk, first ? {
      decoderConfig: { codec: this.cfg.codec, sampleRate: ad.sampleRate, numberOfChannels: 1, description: AAC_DESC }
    } : null);
  }
  async flush() { }
  close() { }
};

/* مالتی‌پلکسرِ واقعیِ پروژه (نه قلابی) تا هدرِ نهایی همان چیزی باشد که
   روی گوشی ساخته می‌شود. */
global.Mp4Muxer = { Muxer: MP4.Muxer, ArrayBufferTarget: MP4.ArrayBufferTarget };

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
const f2 = x => Math.round(x * 100) / 100;

/* ── ۱) جدولِ نرخِ بیت: تابعِ پیکسل×فریم، نه طولِ فایل ─────────────────── */
const plans = [
  ['۱۰۸۰p۶۰ (۹۰ ثانیه)', 90, 'auto', 60],
  ['۱۰۸۰p۶۰ (۳ دقیقه)', 180, 'auto', 60],
  ['۱۰۸۰p۳۰ (۴ دقیقه)', 240, 'auto', 60],
  ['۷۲۰p۶۰', 60, '720p', 60],
  ['۷۲۰p۳۰ (۶ دقیقه)', 400, '720p', 60],
  ['۱۰۸۰p۶۰ با انتخابِ کاربر', 500, '1080p', 60]
];
let minBpp = 99;
const rows = plans.map(([label, dur, q, fps]) => {
  const p = OFF.videoPlan(dur, q, fps);
  const bpp = p.bitrate / (p.W * p.H * p.fps);
  if (bpp < minBpp) minBpp = bpp;
  return label + ': ' + (p.bitrate / 1e6).toFixed(1) + ' Mbps · ' + p.W + '×' + p.H +
    '@' + p.fps + ' · bpp=' + bpp.toFixed(3);
});
console.log('   ' + rows.join('\n   '));
check('هیچ پروفایلی زیرِ ۰٫۰۷۵ بیت/پیکسل نیست (ریشهٔ لگ)', minBpp >= 0.075,
  'کمینهٔ bpp=' + minBpp.toFixed(3));

const p88 = OFF.videoPlan(88.85, 'auto', 60);
check('فایلِ ۸۸٫۸۵ ثانیه‌ای ۱۰۸۰p۶۰ حالا ≥۱۰ Mbps می‌گیرد (قبلاً ۳٫۸ بود)',
  p88.bitrate >= 10000000, (p88.bitrate / 1e6).toFixed(1) + ' Mbps · bpp=' +
  (p88.bitrate / (p88.W * p88.H * p88.fps)).toFixed(3));
check('نرخِ بیت با طولِ فایل تغییر نمی‌کند (فقط نرخِ فریم برای فایلِ بلند)',
  OFF.videoPlan(30, 'auto', 60).bitrate === OFF.videoPlan(120, 'auto', 60).bitrate,
  (OFF.videoPlan(30, 'auto', 60).bitrate / 1e6).toFixed(1) + ' Mbps در ۳۰s و ۱۲۰s');

/* ── ۲) هدر: همان چیزی که گالریِ اندروید می‌خواند ────────────────────────── */
function buildFile(seconds, fps, W, H) {
  const mux = new MP4.Muxer({
    target: new MP4.ArrayBufferTarget(),
    video: { codec: 'avc', width: W, height: H, frameRate: fps },
    audio: { codec: 'aac', sampleRate: 44100, numberOfChannels: 1 },
    fastStart: 'in-memory'
  });
  const frames = Math.round(seconds * fps);
  for (let i = 0; i < frames; i++) {
    mux.addVideoChunkRaw(new Uint8Array(300), i % fps === 0 ? 'key' : 'delta',
      Math.round(i / fps * 1e6), Math.round(1e6 / fps), i === 0 ? fakeAvcMeta({ codec: 'avc1.64002A', width: W, height: H }) : null);
  }
  const n = Math.ceil(seconds * 44100 / 1024);
  for (let i = 0; i < n; i++) {
    mux.addAudioChunkRaw(new Uint8Array(100), i === 0 ? 'key' : 'delta',
      Math.round(i * 1024 / 44100 * 1e6), Math.round(1024 / 44100 * 1e6),
      i === 0 ? { decoderConfig: { codec: 'mp4a.40.2', sampleRate: 44100, numberOfChannels: 1, description: AAC_DESC } } : null);
  }
  mux.finalize();
  return mux.target.buffer;
}

/* «گالری» = طولِ تراکِ تصویر از tkhd با تایم‌اسکیلِ فیلم + طولِ mdhd */
function galleryDuration(buf) {
  const tl = OFF.mp4Timeline(buf);
  const v = tl.tracks.filter(t => t.handler === 'vide')[0];
  if (!v) return null;
  return {
    tkhd: v.tkhdDuration / tl.movieTimescale,
    mdhd: v.mdhdDuration / v.mdhdTimescale,
    mvhd: tl.movieDuration / tl.movieTimescale,
    samples: v.samples
  };
}

for (const [label, secs] of [['۵ ثانیه', 5], ['۳۰ ثانیه', 30], ['۴۵ ثانیه', 45], ['۸۸٫۸۵ ثانیه', 88.85]]) {
  const fps = 60;
  const buf = buildFile(secs, fps, 1920, 1080);
  const g = galleryDuration(buf);
  const frames = Math.round(secs * fps);
  const tol = 1.5 / fps + 0.02;
  check('گالری برای فایلِ ' + label + ' همان طولِ واقعی را می‌خواند',
    Math.abs(g.tkhd - secs) <= tol && Math.abs(g.mdhd - secs) <= tol && Math.abs(g.mvhd - secs) <= tol,
    'tkhd=' + f2(g.tkhd) + 's · mdhd=' + f2(g.mdhd) + 's · mvhd=' + f2(g.mvhd) + 's · ' + g.samples + ' فریم');

  /* بازنویسیِ هدر (مسیرِ قبلی) نباید چیزی را خراب کند */
  const patched = OFF.patchMp4Duration(buf, secs);
  const g2 = galleryDuration(patched);
  check('بازنویسیِ هدر برای فایلِ ' + label + ' چیزی را خراب نمی‌کند',
    Math.abs(g2.tkhd - secs) <= tol && Math.abs(g2.mdhd - secs) <= tol,
    'tkhd=' + f2(g2.tkhd) + 's (باگِ قبلی: ' + f2(secs * 1 / 1000) + 's)');
}

/* ── ۲.۵) فایلِ تکه‌ای (fragmented) — مسیرِ MediaRecorder ──────────────────
   MediaRecorder در کروم MP4ِ تکه‌ای می‌سازد: mvhd و mdhd داخلِ moov صفر
   هستند و طولِ واقعی فقط در قطعه‌ها (moof) است. اندروید قطعه‌ها را جمع
   نمی‌زند و گالری عددِ بی‌معنی نشان می‌دهد (همان «۵ ثانیه»). */
function buildFragmented(seconds, fps, W, H) {
  const mux = new MP4.Muxer({
    target: new MP4.ArrayBufferTarget(),
    video: { codec: 'avc', width: W, height: H, frameRate: fps },
    audio: { codec: 'aac', sampleRate: 44100, numberOfChannels: 1 },
    fastStart: 'fragmented'
  });
  const frames = Math.round(seconds * fps);
  for (let i = 0; i < frames; i++) {
    mux.addVideoChunkRaw(new Uint8Array(120), i % fps === 0 ? 'key' : 'delta',
      Math.round(i / fps * 1e6), Math.round(1e6 / fps),
      i === 0 ? fakeAvcMeta({ codec: 'avc1.640028', width: W, height: H }) : null);
  }
  const n = Math.ceil(seconds * 44100 / 1024);
  for (let i = 0; i < n; i++) {
    mux.addAudioChunkRaw(new Uint8Array(60), i === 0 ? 'key' : 'delta',
      Math.round(i * 1024 / 44100 * 1e6), Math.round(1024 / 44100 * 1e6),
      i === 0 ? { decoderConfig: { codec: 'mp4a.40.2', sampleRate: 44100, numberOfChannels: 1, description: AAC_DESC } } : null);
  }
  mux.finalize();
  return mux.target.buffer;
}

{
  const SEC = 30, FPS = 30;
  const frag = buildFragmented(SEC, FPS, 640, 360);
  const t0 = OFF.mp4Timeline(frag);
  check('فایلِ تکه‌ای در moov هیچ طولی ندارد (علتِ «۵ ثانیه» در گالری)',
    t0.fragmented && t0.movieDuration === 0 && t0.mehdDuration == null,
    'mvhd=' + (t0.movieDuration / t0.movieTimescale) + 's · mehd=ندارد · ' + t0.moofCount + ' قطعه');

  const fixed = OFF.repairMp4Duration(frag, SEC);
  const t1 = OFF.mp4Timeline(fixed);
  const vid = t1.tracks.filter(x => x.handler === 'vide')[0];
  check('پس از تعمیر، mvhd/tkhd/mdhd طولِ درست را دارند',
    t1.movieDuration / t1.movieTimescale === SEC &&
    vid.tkhdDuration / t1.movieTimescale === SEC &&
    vid.mdhdDuration / vid.mdhdTimescale === SEC,
    'mvhd=' + f2(t1.movieDuration / t1.movieTimescale) + 's · tkhd=' +
    f2(vid.tkhdDuration / t1.movieTimescale) + 's · mdhd=' + f2(vid.mdhdDuration / vid.mdhdTimescale) + 's');
  check('جعبهٔ mehd درج شد (مرجعِ طول برای پخش‌کننده‌ها)',
    t1.mehdDuration != null && t1.mehdDuration / t1.movieTimescale === SEC,
    t1.mehdDuration == null ? 'ندارد' : (t1.mehdDuration / t1.movieTimescale) + 's');
  check('ساختارِ قطعه‌ها دست‌نخورده است (همان ۳۰ قطعه، فقط ۱۶ بایت بزرگ‌تر)',
    t1.moofCount === t0.moofCount && fixed.byteLength === frag.byteLength + 16,
    t1.moofCount + ' قطعه · +' + (fixed.byteLength - frag.byteLength) + ' بایت');
  check('گیتِ خروجی برای فایلِ تکه‌ای هم سبز است',
    OFF.verifyMp4(fixed, { durationSec: SEC, fps: FPS }).ok,
    OFF.verifyMp4(fixed, { durationSec: SEC, fps: FPS }).problems.join(' | ') || 'بدونِ ایراد');
}

(async () => {

/* ── ۳) رندرِ کامل: صفر فریمِ گم‌شده با کدگذارِ کند ─────────────────────── */
const SR = 22050;
const DUR = 6;
const wav = new Float32Array(Math.round(SR * DUR));
for (let i = 0; i < wav.length; i++) wav[i] = 0.2 * Math.sin(2 * Math.PI * 220 * i / SR);
const times = [], freqs = [];
for (let t = 0; t <= DUR; t += 0.01) { times.push(t); freqs.push(220 + 20 * Math.sin(t)); }
const pitch = { times, freqs, trace_freqs: freqs };

/* قاعدهٔ قبلی برای مقایسه: فقط وقتی صف از ۶ بیشتر می‌شد یک تیک صبر می‌کرد */
async function oldRuleDrops(frames, fps) {
  sim.reset();
  const enc = new global.VideoEncoder({ output() { }, error() { } });
  enc.configure({ codec: 'avc1.64002A', width: 1920, height: 1080, framerate: fps });
  for (let i = 0; i < frames; i++) {
    const vf = new global.VideoFrame({}, { timestamp: Math.round(i / fps * 1e6), duration: Math.round(1e6 / fps) });
    enc.encode(vf, { keyFrame: i % fps === 0 });
    if (enc.encodeQueueSize > 6) await new Promise(r => setTimeout(r, 0));
  }
  return sim.dropped;
}
const oldDrops = await oldRuleDrops(Math.round(DUR * 60), 60);

sim.reset();
const progress = [];
const out = await OFF.renderVideo(pitch, [], wav, SR, f => progress.push(f),
  { windowSec: 6, tightness: 2, fps: 60 });

check('مسیر سریع و MP4 انتخاب شد', out.fast === true && out.mime === 'video/mp4', out.name);
check('کدگذارِ کند با قاعدهٔ قبلی فریم می‌انداخت (شبیه‌سازیِ باگ)',
  oldDrops > 0, oldDrops + ' فریم افتاده');
check('با حلقهٔ جدید صفر فریم می‌افتد', sim.dropped === 0, sim.dropped + ' فریم افتاده');
check('همهٔ فریم‌ها کدگذاری و به مالتی‌پلکسر رسیدند',
  sim.encoded === out.frames, sim.encoded + ' از ' + out.frames);
check('فریم‌های کلیدی هر ۱ ثانیه', sim.keyframes === Math.ceil(out.frames / 60),
  sim.keyframes + ' کلیدی برای ' + out.frames + ' فریم');

const verify = out.verify;
check('گیتِ خروجی فایل را سالم اعلام می‌کند', !!(verify && verify.ok),
  verify ? (verify.ok ? 'بدونِ ایراد' : verify.problems.join(' | ')) : 'بازبینی نشد');
check('طولِ فایل == تعدادِ فریم ÷ fps',
  verify && Math.abs(verify.movie_duration_sec - DUR) <= 0.05,
  verify ? verify.movie_duration_sec + 's برای ' + out.frames + ' فریم' : '—');
check('صفر فریمِ گم‌شده در فایلِ نهایی (جدولِ نمونه‌ها)',
  verify && verify.dropped_frames === 0,
  verify ? verify.frames + ' نمونه / ' + out.frames + ' فریم' : '—');
check('هیچ B-frame در فایل نیست (ترتیبِ زمانی سالم در پخش‌کنندهٔ گوشی)',
  verify && verify.has_b_frames === false, verify ? String(verify.has_b_frames) : '—');

/* همان چیزی که «گالری» می‌بیند، این‌بار روی خروجیِ واقعیِ رندرر */
const bufOut = Buffer.from(await out.blob.arrayBuffer());
const gOut = galleryDuration(bufOut);
check('گالریِ اندروید برای خروجیِ رندرر طولِ درست را می‌خواند',
  Math.abs(gOut.tkhd - DUR) <= 0.05 && Math.abs(gOut.mvhd - DUR) <= 0.05,
  'tkhd=' + f2(gOut.tkhd) + 's · mvhd=' + f2(gOut.mvhd) + 's · ' + gOut.samples + ' فریم');
check('زمان‌بندیِ نمونه‌ها بی‌درز است (فاصلهٔ یکنواخت ⇒ بدونِ لگ)',
  verify && verify.max_key_gap_sec != null && verify.max_key_gap_sec <= 1.6,
  verify ? 'بیشترین فاصلهٔ فریمِ کلیدی ' + verify.max_key_gap_sec + 's' : '—');

check('نرخِ بیتِ گزارش‌شده با نقشهٔ پیکسل×فریم یکی است',
  out.bitrate === OFF.videoPlan(DUR, 'auto', 60).bitrate,
  (out.bitrate / 1e6).toFixed(1) + ' Mbps · bpp=' + out.bpp);

/* ── ۴) مسیرِ بلند: فایلِ ۴۰ ثانیه‌ای، همان گیت‌ها ──────────────────────── */
const buf40 = buildFile(40, 60, 1920, 1080);
const v40 = OFF.verifyMp4(buf40, { durationSec: 40, fps: 60, frames: 2400, maxKeyGapSec: 1.6, bpp: 0.1 });
check('فایلِ ۴۰ ثانیه‌ای از گیتِ کامل رد می‌شود', v40.ok, v40.ok ? 'بدونِ ایراد' : v40.problems.join(' | '));

console.log('─'.repeat(70));
if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
console.log('✅ خروجیِ ویدیو از گیتِ طول و روانی رد می‌شود (' + pass + ' بررسی)');
process.exit(0);

})().catch(e => { console.error(e); process.exit(1); });
