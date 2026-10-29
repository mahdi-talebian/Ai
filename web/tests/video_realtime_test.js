#!/usr/bin/env node
/* ============================================================================
   تستِ «مسیر ضبطِ زمان‌واقعی» (همان مسیری که روی گوشیِ بدونِ WebCodecs کار می‌کند)
   ----------------------------------------------------------------------------
   ریشهٔ لگ این بود: با captureStream(0)+requestFrame نمونه‌ها زمان‌بندیِ
   نامنظم (VFR) می‌گرفتند. این تست ثابت می‌کند:
     • بوم با نرخِ ثابت (captureStream(fps)) ضبط می‌شود، نه ۰
     • خروجی طولِ درست دارد و «گامِ فریم» یکنواخت است (عدد، نه ادعا)
     • اگر MediaRecorder طول را صفر بگذارد، هدر با طولِ واقعی وصله می‌شود
     • طولِ واقعی به پلِ بومی می‌رود تا در MediaStore نوشته شود (گالری)

   اجرا:  node web/tests/video_realtime_test.js
   ========================================================================== */
'use strict';

/* ── محیطِ مرورگرِ قلابی ─────────────────────────────────────────────── */
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

let CLOCK = 0;               /* ساعتِ قلابیِ صدا */
let FRAME = 1 / 30;          /* گامِ ساعت در هر rAF */
const captureCalls = [];     /* آرگومانِ captureStream */

function makeCanvas() {
  const c = {
    width: 0, height: 0,
    getContext: () => makeCtx(),
    toBlob: cb => cb(new Blob([])),
    captureStream(arg) {
      captureCalls.push(arg);
      const tracks = [{ stop() { }, requestFrame() { } }];
      const stream = {
        _tracks: tracks.slice(),
        getVideoTracks: () => [tracks[0]],
        getAudioTracks: () => stream._audio || [],
        getTracks: () => stream._tracks,
        addTrack(t) { (stream._audio = stream._audio || []).push(t); }
      };
      return stream;
    }
  };
  return c;
}

global.window = global;
global.document = {
  createElement: tag => (tag === 'canvas' ? makeCanvas() : {}),
  addEventListener() { }, removeEventListener() { }
};
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URLSearchParams = URLSearchParams;

/* رندر حلقه‌ای: هر فراخوانی یک گامِ ساعت جلو می‌برد (بدونِ پشتهٔ عمیق) */
global.requestAnimationFrame = function (cb) {
  CLOCK += FRAME;
  return setTimeout(cb, 0);
};

class FakeAudioContext {
  constructor() { this.state = 'running'; this.sampleRate = 44100; }
  get currentTime() { return CLOCK; }
  resume() { return Promise.resolve(); }
  close() { return Promise.resolve(); }
  createBuffer(ch, len, sr) {
    return { length: len, sampleRate: sr, numberOfChannels: ch, copyToChannel() { } };
  }
  createBufferSource() { return { buffer: null, connect() { }, start() { }, stop() { } }; }
  createMediaStreamDestination() {
    return { stream: { getAudioTracks: () => [{ stop() { } }], addTrack() { } } };
  }
}
global.AudioContext = FakeAudioContext;

/* ── MediaRecorderِ قلابی: خروجیِ MP4ِ واقعی (با مالتی‌پلکسرِ خودِ پروژه) ── */
const MP4 = require('../vendor/mp4-muxer.js');
const AVC_DESC = new Uint8Array([1, 0x64, 0, 0x28, 0xff, 0xe1, 0, 4, 0x67, 0x64, 0, 0x28, 1, 0, 4, 0x68, 0xee, 0x3c, 0x80]);
const AAC_DESC = new Uint8Array([0x12, 0x10]);

function buildMp4(seconds, fps, W, H, opts) {
  opts = opts || {};
  const mux = new MP4.Muxer({
    target: new MP4.ArrayBufferTarget(),
    video: { codec: 'avc', width: W, height: H, frameRate: fps },
    audio: { codec: 'aac', sampleRate: 44100, numberOfChannels: 1 },
    fastStart: 'in-memory'
  });
  const frames = Math.max(1, Math.round(seconds * fps));
  for (let i = 0; i < frames; i++) {
    mux.addVideoChunkRaw(new Uint8Array(120), i % fps === 0 ? 'key' : 'delta',
      Math.round(i / fps * 1e6), Math.round(1e6 / fps),
      i === 0 ? { decoderConfig: { codec: 'avc1.64002A', width: W, height: H, description: AVC_DESC } } : null);
  }
  const n = Math.max(1, Math.ceil(seconds * 44100 / 1024));
  for (let i = 0; i < n; i++) {
    mux.addAudioChunkRaw(new Uint8Array(60), i === 0 ? 'key' : 'delta',
      Math.round(i * 1024 / 44100 * 1e6), Math.round(1024 / 44100 * 1e6),
      i === 0 ? { decoderConfig: { codec: 'mp4a.40.2', sampleRate: 44100, numberOfChannels: 1, description: AAC_DESC } } : null);
  }
  mux.finalize();
  let buf = mux.target.buffer;
  if (opts.zeroDuration) buf = zeroMvhdDuration(buf);
  return buf;
}

/* mvhd.duration را صفر می‌کند (همان کاری که MediaRecorder می‌کند) */
function zeroMvhdDuration(buffer) {
  const out = new Uint8Array(buffer.slice(0));
  const dv = new DataView(out.buffer);
  function walk(start, end) {
    let p = start;
    while (p + 8 <= end) {
      const size = dv.getUint32(p);
      const type = String.fromCharCode(out[p + 4], out[p + 5], out[p + 6], out[p + 7]);
      if (size < 8 || p + size > end) return;
      if (type === 'moov') { walk(p + 8, p + size); return; }
      if (type === 'mvhd') {
        const v = out[p + 8];
        const off = p + 8 + (v === 1 ? 24 : 16);
        if (v === 1) { dv.setUint32(off, 0); dv.setUint32(off + 4, 0); }
        else dv.setUint32(off, 0);
        return;
      }
      p += size;
    }
  }
  walk(0, out.byteLength);
  return out.buffer;
}

const recorderState = { mime: 'video/mp4;codecs=avc1.42E01E,mp4a.40.2', opts: null, built: 0 };
class FakeMediaRecorder {
  static isTypeSupported(t) { return /^video\/mp4/.test(t) || /^video\/webm/.test(t); }
  constructor(stream, opts) { this.stream = stream; this.opts = opts; recorderState.opts = opts; }
  start() { this.started = true; }
  stop() {
    const sec = Math.max(0.5, CLOCK);
    const buf = buildMp4(sec, Math.round(1 / FRAME), 1280, 720,
      { zeroDuration: recorderState.zeroDuration });
    recorderState.built = sec;
    const blob = new Blob([buf], { type: recorderState.mime });
    if (this.ondataavailable) this.ondataavailable({ data: blob });
    if (this.onstop) this.onstop();
  }
}
global.MediaRecorder = FakeMediaRecorder;

/* ── بوتِ کتابخانه‌ها ─────────────────────────────────────────────────── */
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
  console.log('══ مسیرِ ضبطِ زمان‌واقعی (بدونِ WebCodecs) ══\n');

  const sr = 22050, dur = 3.0, fps = 30;
  FRAME = 1 / fps;
  const y = tone(dur, sr, [[0.1, 0.8, 220], [1.1, 0.8, 293.7], [2.1, 0.7, 349.2]]);
  const res = CORE.analyzeChannels([y], sr, {});

  /* WebCodecs نیست ⇒ مسیرِ زمان‌واقعی */
  const saveVE = global.VideoEncoder, saveAE = global.AudioEncoder, saveMux = global.Mp4Muxer;
  global.VideoEncoder = undefined; global.AudioEncoder = undefined; global.Mp4Muxer = undefined;

  captureCalls.length = 0;
  CLOCK = 0;
  recorderState.zeroDuration = false;
  const out = await OFF.renderVideo(res, res.notes, res.wav, res.sr, null,
    { windowSec: 0, tightness: 1, fps: fps, quality: '720p' });

  /* ── ۱) ضبط با نرخِ ثابت ── */
  check('بوم با نرخِ ثابت ضبط شد (captureStream(fps)، نه ۰)',
    captureCalls.length > 0 && captureCalls[0] === fps, 'captureStream(' + captureCalls[0] + ')');
  check('مسیرِ خروجی «زمان‌واقعی» است', out.path === 'realtime' && out.fast === false, out.path);
  check('پرچمِ CFR روشن است', out.cfr === true);

  /* ── ۲) زمان‌بندیِ فریم‌ها: عدد ── */
  const tm = out.timing;
  check('زمان‌بندیِ فریم‌ها اندازه‌گیری شد', !!tm && tm.ok === true,
    tm ? tm.frames + ' فریم' : 'null');
  check('گامِ فریم‌ها یکنواخت است (بدونِ لرزش)',
    !!tm && tm.constant === true && tm.jitterMs === 0,
    tm ? tm.minMs + '–' + tm.maxMs + 'ms (لرزش ' + tm.jitterMs + ')' : '—');
  check('میانگینِ گام = ۱/fps', !!tm && Math.abs(tm.meanMs - 1000 / fps) < 0.6,
    tm ? tm.meanMs + 'ms' : '—');

  /* ── ۳) طولِ فایل ── */
  const tl = OFF.mp4Timeline(await out.blob.arrayBuffer());
  const movieSec = tl.movieDuration / tl.movieTimescale;
  check('طولِ هدر با طولِ واقعی می‌خواند', Math.abs(movieSec - out.duration) < 0.25,
    movieSec.toFixed(2) + 's ≈ ' + out.duration.toFixed(2) + 's');
  check('طولِ فیلم ≈ ۳ ثانیه', Math.abs(out.duration - dur) < 0.3, out.duration.toFixed(2) + 's');

  /* ── ۴) اگر MediaRecorder طول را صفر بگذارد، وصله می‌شود ── */
  recorderState.zeroDuration = true;
  captureCalls.length = 0;
  CLOCK = 0;
  const out2 = await OFF.renderVideo(res, res.notes, res.wav, res.sr, null,
    { windowSec: 0, tightness: 1, fps: fps, quality: '720p' });
  const tl2 = OFF.mp4Timeline(await out2.blob.arrayBuffer());
  const movieSec2 = tl2.movieDuration / tl2.movieTimescale;
  check('طولِ صفرِ خروجیِ ضبط‌کننده با طولِ واقعی وصله شد',
    Math.abs(movieSec2 - out2.duration) < 0.25,
    movieSec2.toFixed(2) + 's ≈ ' + out2.duration.toFixed(2) + 's');
  const vt = tl2.tracks.filter(t => t.handler === 'vide')[0];
  check('طولِ تراکِ تصویر (tkhd) هم درست است',
    Math.abs(vt.tkhdDuration / tl2.movieTimescale - out2.duration) < 0.25,
    (vt.tkhdDuration / tl2.movieTimescale).toFixed(2) + 's');

  global.VideoEncoder = saveVE; global.AudioEncoder = saveAE; global.Mp4Muxer = saveMux;

  /* ── ۵) طولِ واقعی به پلِ بومی می‌رسد (MediaStore.DURATION) ── */
  const naCalls = [];
  global.NativeApp = {
    toast() { },
    beginSaveToEx(name, mime, target, durationMs) {
      naCalls.push({ name, mime, target, durationMs });
      return 'tok_test';
    },
    beginSave(name, mime) { naCalls.push({ name, mime, target: 'downloads' }); return 'tok_test'; },
    appendSave() { return true; },
    endSave() { },
    abortSave() { }
  };
  global.FileReader = class {
    readAsDataURL(blob) {
      blob.arrayBuffer().then(ab => {
        this.result = 'data:application/octet-stream;base64,' + Buffer.from(ab).toString('base64');
        if (this.onload) this.onload();
      });
    }
  };
  require('../native.js');
  const NB = global.window.NativeBridge;
  check('پلِ بومی در دسترس است', !!NB && NB.available === true);
  const vblob = new Blob([new Uint8Array(64)], { type: 'video/mp4' });
  /* callback نهایی را خودِ اندروید صدا می‌زند؛ این‌جا فقط آرگومان‌های ارسالی مهم‌اند */
  NB.saveBlobToGallery(vblob, 'radde-seda.mp4', () => { }, 3456.7);
  await new Promise(r => setTimeout(r, 30));
  const call = naCalls[0];
  check('ذخیرهٔ گالری از مسیرِ «گالری» می‌رود', !!call && call.target === 'gallery',
    call ? call.target : '—');
  check('طولِ ویدیو (ms) به اندروید فرستاده می‌شود', !!call && call.durationMs === '3457',
    call ? call.durationMs : '—');
  check('نوعِ فایل video/mp4 است', !!call && call.mime === 'video/mp4', call ? call.mime : '—');

  console.log('─'.repeat(70));
  if (fail) { console.log('✖ ' + fail + ' بررسی ناموفق از ' + (pass + fail)); process.exit(1); }
  console.log('✅ مسیرِ زمان‌واقعی: نرخِ ثابت، طولِ درست، طول به گالری (' + pass + ' بررسی)');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
