#!/usr/bin/env node
/* ============================================================================
   تست ساخت ویدیوی «ردِّ صدا» در حالت آفلاین (بدون مرورگر واقعی)
   ----------------------------------------------------------------------------
   MediaRecorder و canvas.captureStream را با استاب شبیه‌سازی می‌کنیم و
   بررسی می‌کنیم که:
     • فریم‌ها در طول زمان کشیده می‌شوند و پیشرفت گزارش می‌شود
     • ضبط شروع/تمام می‌شود و یک Blob ویدیو برمی‌گردد
     • نام فایل بر اساس کدک انتخاب‌شده درست است (mp4/webm)

   اجرا: node web/tests/video_test.js
   ========================================================================== */
'use strict';

/* ── استاب‌های محیط ───────────────────────────────────────────────────── */
const drawnFrames = [];
function makeGradient() { return { addColorStop() { } }; }
function makeCtx(canvas) {
  const base = {
    canvas, createLinearGradient: makeGradient, createRadialGradient: makeGradient,
    measureText: t => ({ width: String(t).length * 8 })
  };
  return new Proxy(base, {
    get(t, p) {
      if (p in t) return t[p];
      if (p === 'drawImage' || p === 'fillRect') return () => drawnFrames.push('frame');
      if (typeof p === 'symbol') return undefined;
      return function () { };
    },
    set(t, p, v) { t[p] = v; return true; }
  });
}

const clock = { t0: Date.now() };
global.performance = { now: () => Date.now() };
global.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);

global.document = {
  createElement(tag) {
    if (tag !== 'canvas') return {};
    const canvas = {
      width: 1280, height: 720,
      getContext: () => makeCtx(canvas),
      toBlob: (cb) => cb(new Blob([new Uint8Array(16)], { type: 'image/png' })),
      captureStream: () => ({
        getAudioTracks: () => [],
        getVideoTracks: () => [{ kind: 'video', stop() { } }],
        getTracks: () => [{ kind: 'video', stop() { } }],
        addTrack() { }
      })
    };
    return canvas;
  }
};
global.window = global;
global.location = { search: '', href: 'https://appassets.local/', origin: 'https://appassets.local' };
global.URL = URL;
global.URL.createObjectURL = () => 'blob:fake';
global.URL.revokeObjectURL = () => { };

/* AudioContext ساختگی با ساعت واقعی */
function FakeAudioContext() {
  this.state = 'running';
  this.sampleRate = 22050;
  this._t0 = Date.now();
  this.currentTime = 0;
  const self = this;
  setInterval(() => { self.currentTime = (Date.now() - self._t0) / 1000; }, 10);
}
FakeAudioContext.prototype.resume = () => Promise.resolve();
FakeAudioContext.prototype.close = () => { };
FakeAudioContext.prototype.createBuffer = function (ch, len, sr) {
  const data = new Float32Array(len);
  return {
    numberOfChannels: ch, length: len, sampleRate: sr,
    getChannelData: () => data,
    copyToChannel: (src) => data.set(src.subarray(0, Math.min(src.length, len)))
  };
};
FakeAudioContext.prototype.createBufferSource = function () {
  return {
    buffer: null, connect() { }, start() { }, stop() { },
    onended: null
  };
};
FakeAudioContext.prototype.createMediaStreamDestination = function () {
  return { stream: { getAudioTracks: () => [{ kind: 'audio' }] } };
};
global.AudioContext = FakeAudioContext;
global.webkitAudioContext = FakeAudioContext;

/* MediaRecorder ساختگی */
const recState = { started: false, stopped: false, chunks: 0 };
global.MediaRecorder = function (stream, opts) {
  recState.mime = opts && opts.mimeType;
  this.start = () => {
    recState.started = true;
    this._timer = setInterval(() => {
      recState.chunks++;
      if (this.ondataavailable) this.ondataavailable({ data: new Blob(['x'.repeat(256)], { type: 'video/webm' }) });
    }, 60);
  };
  this.stop = () => {
    clearInterval(this._timer);
    recState.stopped = true;
    if (this.onstop) this.onstop();
  };
};
global.MediaRecorder.isTypeSupported = (m) => /webm/.test(m);

require('../engine.js');
require('../offline.js');

/* ── اجرا ─────────────────────────────────────────────────────────────── */
(async () => {
  const CORE = global.LocalEngineCore;
  const OFF = global.LocalOffline;
  const checks = [];
  const check = (n, ok, extra) => checks.push([n, ok, extra]);

  check('لایهٔ آفلاین فعال', !!(OFF && OFF.enabled));

  // سیگنال کوتاه (۱٫۲ ثانیه، دو نت)
  const sr = 22050;
  const n = Math.round(sr * 1.2);
  const y = new Float32Array(n);
  for (let i = 0; i < n; i++) {
    const t = i / sr;
    const f = t < 0.55 ? 220 : 330;
    let s = 0;
    for (let k = 1; k <= 10; k++) s += Math.sin(2 * Math.PI * f * k * t) / k;
    y[i] = s * 0.2;
  }
  const res = CORE.analyzeChannels([y], sr, {});

  const progress = [];
  const t0 = Date.now();
  const out = await OFF.renderVideo(res, res.notes, res.wav, res.sr, (f) => progress.push(f));
  const took = (Date.now() - t0) / 1000;

  check('ویدیو ساخته شد', out && out.blob && out.blob.size > 0, out && out.blob.size + ' بایت');
  check('ضبط شروع شد', recState.started);
  check('ضبط تمام شد', recState.stopped);
  check('فریم‌ها کشیده شدند', drawnFrames.length > 10, drawnFrames.length + ' فریم');
  check('پیشرفت گزارش شد', progress.length > 3, progress.length + ' گزارش');
  check('نام فایل درست است', /^radde-seda\.(mp4|webm)$/.test(out.name), out.name);
  check('زمان ضبط ≈ طول صدا', took >= 1.0 && took <= 6.0, took.toFixed(2) + 's برای ' + res.duration.toFixed(2) + 's صدا');

  let bad = 0;
  console.log('تست ساخت ویدیوی آفلاین');
  console.log('─'.repeat(70));
  for (const [name, ok, extra] of checks) {
    if (!ok) bad++;
    console.log((ok ? '✓ ' : '✖ ') + name + (extra !== undefined ? '  → ' + extra : ''));
  }
  console.log('─'.repeat(70));
  if (bad) { console.log('✖ ' + bad + ' مورد ناموفق'); process.exit(1); }
  console.log('✅ ساخت ویدیو آفلاین سالم است');
  process.exit(0);
})().catch(e => { console.error(e); process.exit(1); });
