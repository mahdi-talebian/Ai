#!/usr/bin/env node
/* ============================================================================
   تست سرتاسری صفحه در حالت آفلاین (jsdom)
   ----------------------------------------------------------------------------
   کل مسیر کاربر شبیه‌سازی می‌شود:
     انتخاب فایل → آپلود محلی → تحلیل محلی → گزارش در صفحه
     → لینک‌های ذخیره (بلاب) → ساخت تصویر → وضعیت‌ها
   هیچ سروری در کار نیست؛ اگر جایی کد درخواست شبکه بزند، تست شکست می‌خورد.

   اجرا:  node web/tests/page_test.js
   ========================================================================== */
'use strict';
const fs = require('fs');
const path = require('path');
const { JSDOM, VirtualConsole } = require('jsdom');

const ROOT = path.resolve(__dirname, '../..');
const html = fs.readFileSync(path.join(ROOT, 'web/index.html'), 'utf8');

/* فایل‌های بیرونی را درون صفحه تزریق می‌کنیم تا jsdom آن‌ها را اجرا کند */
function inlineScripts(html) {
  return html.replace(/<script src="([^"]+)"><\/script>/g, (m, src) => {
    const p = path.join(ROOT, 'web', src);
    if (!fs.existsSync(p)) return '';
    return '<script>\n' + fs.readFileSync(p, 'utf8') + '\n</script>';
  });
}

/* ── نمونهٔ صوتی کوتاه (۳ نت) ─────────────────────────────────────────── */
function makeWavFile(sr) {
  const notes = [[0, 0.5], [4, 0.5], [7, 0.5]];
  const gap = 0.1;
  const total = notes.reduce((a, [, d]) => a + d + gap, 0.2);
  const n = Math.round(sr * total);
  const y = new Float32Array(n);
  let pos = 0.1;
  for (const [semi, dur] of notes) {
    const f = 220 * Math.pow(2, semi / 12);
    const nn = Math.round(sr * dur);
    for (let i = 0; i < nn; i++) {
      const t = i / sr;
      let s = 0;
      for (let k = 1; k <= 10; k++) s += Math.sin(2 * Math.PI * f * k * t) / k;
      y[pos * sr + i | 0] = s * 0.2;
    }
    pos += dur + gap;
  }
  // WAV 16-bit
  const bytes = n * 2;
  const buf = Buffer.alloc(44 + bytes);
  buf.write('RIFF', 0); buf.writeUInt32LE(36 + bytes, 4); buf.write('WAVE', 8);
  buf.write('fmt ', 12); buf.writeUInt32LE(16, 16); buf.writeUInt16LE(1, 20); buf.writeUInt16LE(1, 22);
  buf.writeUInt32LE(sr, 24); buf.writeUInt32LE(sr * 2, 28); buf.writeUInt16LE(2, 32); buf.writeUInt16LE(16, 34);
  buf.write('data', 36); buf.writeUInt32LE(bytes, 40);
  for (let i = 0; i < n; i++) {
    buf.writeInt16LE(Math.max(-32768, Math.min(32767, Math.round(y[i] * 32767))), 44 + i * 2);
  }
  return new Uint8Array(buf);
}

/* ── استاب‌های مرورگر ─────────────────────────────────────────────────── */
function makeGradient() { return { addColorStop() { } }; }
function installStubs(w) {
  /* بوم ساختگی: هر متد ناشناخته هم no-op است (Proxy) */
  function makeCtx(canvas) {
    const base = {
      canvas,
      createLinearGradient: makeGradient,
      createRadialGradient: makeGradient,
      createPattern: () => null,
      measureText: (t) => ({ width: String(t).length * 8 }),
      getImageData: (x, y, w2, h2) => ({ data: new Uint8ClampedArray(w2 * h2 * 4), width: w2, height: h2 }),
      putImageData: () => { }
    };
    return new Proxy(base, {
      get(t, prop) {
        if (prop in t) return t[prop];
        if (typeof prop === 'symbol') return undefined;
        return function () { };
      },
      set(t, prop, val) { t[prop] = val; return true; }
    });
  }

  const canvasProto = w.HTMLCanvasElement.prototype;
  canvasProto.getContext = function () { return makeCtx(this); };

  let blobSeq = 0;
  w.URL.createObjectURL = (b) => 'blob:https://appassets.local/' + (++blobSeq);
  w.URL.revokeObjectURL = () => { };
  canvasProto.toBlob = function (cb) { cb(new w.Blob([new Uint8Array(32)], { type: 'image/png' })); };
  w.HTMLMediaElement.prototype.play = () => Promise.resolve();
  w.HTMLMediaElement.prototype.pause = () => { };
  w.HTMLMediaElement.prototype.load = () => { };
  w.Element.prototype.scrollIntoView = function () { };

  // AudioContext برای decodeAudioData (مسیر WAV استفاده نمی‌کند، ولی لازم است نبود)
  w.AudioContext = function () {
    return {
      state: 'running', currentTime: 0, sampleRate: 48000,
      resume: () => Promise.resolve(),
      createBuffer: (ch, len, sr) => ({
        numberOfChannels: ch, length: len, sampleRate: sr,
        getChannelData: () => new Float32Array(len), copyToChannel() { }
      }),
      createBufferSource: () => ({ buffer: null, connect() { }, start() { }, stop() { } }),
      createMediaStreamDestination: () => ({ stream: { getAudioTracks: () => [] } }),
      decodeAudioData: (buf, ok, err) => { if (err) err(new Error('no decode')); }
    };
  };
  w.MediaRecorder = function () {
    return { start() { }, stop() { }, ondataavailable: null, onstop: null };
  };
  w.MediaRecorder.isTypeSupported = () => false;

  /* Response کوچک (jsdom ندارد؛ شبیه رفتار مرورگر برای مسیرهای ما) */
  w.Response = class Response {
    constructor(body, init) {
      init = init || {};
      this._body = body;
      this.status = init.status || 200;
      this.ok = this.status >= 200 && this.status < 300;
      const h = init.headers || {};
      this.headers = {
        get: (k) => {
          const key = String(k).toLowerCase();
          for (const name in h) if (name.toLowerCase() === key) return h[name];
          return null;
        }
      };
    }
    async _read() {
      const b = this._body;
      if (b && typeof b === 'object') {
        if (typeof b.text === 'function') return await b.text();
        if (typeof b.arrayBuffer === 'function') return new TextDecoder().decode(await b.arrayBuffer());
      }
      return String(b);
    }
    async json() { return JSON.parse(await this._read()); }
    async text() { return await this._read(); }
    async blob() { return this._body; }
  };
}

/* ── اجرا ─────────────────────────────────────────────────────────────── */
const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => {
  const msg = (e && e.detail && e.detail.message) || e.message || String(e);
  if (/Not implemented: HTMLCanvasElement/.test(msg)) return;
  errors.push(msg);
});

const dom = new JSDOM(inlineScripts(html), {
  url: 'https://appassets.local/index.html',
  runScripts: 'dangerously',
  pretendToBeVisual: true,
  virtualConsole: vc,
  beforeParse: installStubs
});
const w = dom.window;

/* مراقب درخواست شبکه: هر fetch بیرونی = شکست */
const netCalls = [];
const realFetch = w.fetch;
w.fetch = function (input, init) {
  const url = String(typeof input === 'string' ? input : (input && input.url));
  netCalls.push(url);
  return realFetch.apply(w, arguments);
};

const checks = [];
const check = (name, ok, extra) => { checks.push([name, ok, extra]); };

function printResults() {
  let bad = 0;
  console.log('تست سرتاسری صفحه (حالت آفلاین)');
  console.log('─'.repeat(70));
  for (const [name, ok, extra] of checks) {
    if (!ok) bad++;
    console.log((ok ? '✓ ' : '✖ ') + name + (extra !== undefined && String(extra).length < 90 ? '  → ' + extra : ''));
  }
  if (errors.length) {
    console.log('\nخطاهای صفحه:');
    errors.slice(0, 8).forEach(e => console.log('  •', String(e).slice(0, 220)));
    bad++;
  }
  console.log('─'.repeat(70));
  if (bad) { console.log('✖ ' + bad + ' مورد ناموفق'); process.exit(1); }
  console.log('✅ همهٔ مسیرهای آفلاین درست کار می‌کنند');
  process.exit(0);
}

(async () => {
  await new Promise(r => setTimeout(r, 300));
  const d = w.document;

  check('لایهٔ آفلاین فعال', !!(w.LocalOffline && w.LocalOffline.enabled));
  check('موتور بارگذاری شده', !!w.LocalEngineCore);
  check('پنجرهٔ «سرور» نمایش داده نمی‌شود (آفلاین)', !d.getElementById('nativeCfgBtn'));
  check('fetch جایگزین شده', w.fetch !== undefined);

  // فایل را در input بگذار و رویداد change بده
  const wav = makeWavFile(22050);
  const file = new w.File([wav], 'test-recitation.wav', { type: 'audio/wav' });
  const input = d.getElementById('fileInput');
  Object.defineProperty(input, 'files', { value: [file], configurable: true });
  input.dispatchEvent(new w.Event('change', { bubbles: true }));
  await new Promise(r => setTimeout(r, 200));
  check('فایل انتخاب شد', /test-recitation/.test(d.getElementById('fileInfo').textContent || ''),
    d.getElementById('fileInfo').textContent);

  d.getElementById('analyzeBtn').click();
  // انتظار برای پایان تحلیل
  let waited = 0;
  while (d.getElementById('results').style.display !== 'block' && waited < 240000) {
    await new Promise(r => setTimeout(r, 250));
    waited += 250;
    if (/❌/.test(d.getElementById('statusBox').textContent || '')) break;
  }

  const status = d.getElementById('statusBox').textContent || '';
  check('تحلیل بدون خطا تمام شد', !/❌/.test(status), status);
  check('نتایج نمایش داده شد', d.getElementById('results').style.display === 'block');
  check('بالاترین نت پر شد', (d.getElementById('hiNote').textContent || '—') !== '—', d.getElementById('hiNote').textContent);
  check('پایین‌ترین نت پر شد', (d.getElementById('loNote').textContent || '—') !== '—', d.getElementById('loNote').textContent);
  check('گستره پر شد', /نیم‌پرده/.test(d.getElementById('spanMain').textContent || ''),
    d.getElementById('spanMain').textContent);
  check('جدول نت‌ها پر شد', d.querySelectorAll('#notesTable tbody tr').length > 0,
    d.querySelectorAll('#notesTable tbody tr').length + ' ردیف');
  check('نشان کیفیت نمایش داده شد', d.getElementById('qualBox').style.display === 'flex',
    d.getElementById('qualBadge').textContent);
  check('پخش‌کننده صدا بلاب محلی دارد', String(d.getElementById('audioPlayer').src).indexOf('blob:') === 0,
    d.getElementById('audioPlayer').src);
  check('تصویر تحلیل بلاب محلی دارد',
    String(d.getElementById('dlImage').getAttribute('href')).indexOf('blob:') === 0,
    d.getElementById('dlImage').getAttribute('href'));
  check('دکمهٔ فایل اصلی بلاب محلی دارد',
    String(d.getElementById('dlOriginal').getAttribute('href')).indexOf('blob:') === 0,
    d.getElementById('dlOriginal').getAttribute('href'));
  check('گزارش JSON لینک دارد',
    String(d.getElementById('dlReport').getAttribute('href')).indexOf('blob:') === 0);

  // دانلود گزارش از مسیر محلی
  const repRes = await w.fetch('/api/download/' + w.eval('STATE.jobId') + '/report');
  const repJson = await repRes.json();
  check('گزارش JSON سالم است', !!(repJson.range && repJson.notes), 'نتیجه: ' +
    (repJson.range ? repJson.range.highest.note_name + ' ↕ ' + repJson.range.lowest.note_name : 'نامعلوم'));
  check('گزارش می‌گوید آفلاین است', repJson.meta && repJson.meta.offline === true);

  // ── اجرای زنده: همان پروتکل، ولی محلی ──
  const liveMsg = await new Promise((resolve) => {
    const ws = new w.WebSocket(w.eval("wsUrl('/ws/live-pitch')"));
    const sr = 48000;
    ws.onopen = () => {
      ws.send(JSON.stringify({ sample_rate: sr, reset: true }));
      // سه بستهٔ ۱۲۰ میلی‌ثانیه‌ای با تن ۴۴۰ هرتز
      for (let round = 0; round < 3; round++) {
        const n = Math.round(sr * 0.12);
        const pkt = new Uint8Array(n * 4 + 1);
        pkt[0] = 0;
        const f32 = new Float32Array(n);
        for (let i = 0; i < n; i++) {
          let v = 0;
          for (let k = 1; k <= 8; k++) v += Math.sin(2 * Math.PI * 440 * k * (i / sr)) / k;
          f32[i] = v * 0.2;
        }
        pkt.set(new Uint8Array(f32.buffer), 1);
        ws.send(pkt);
      }
    };
    ws.onmessage = (ev) => {
      try {
        const data = JSON.parse(ev.data);
        if (data.note) { ws.close(); resolve(data); }
      } catch (e) { }
    };
    setTimeout(() => { try { ws.close(); } catch (e) { } resolve(null); }, 4000);
  });
  check('اجرای زنده محلی: نت تشخیص داده شد',
    !!(liveMsg && liveMsg.note && /A4/.test(liveMsg.note.note_name)),
    liveMsg ? (liveMsg.note && liveMsg.note.note_name) + ' ' + (liveMsg.note && liveMsg.note.f0_hz) + 'Hz' : 'بدون پاسخ');
  check('اجرای زنده: رکورد جلسه (بالا/پایین) پر شد',
    !!(liveMsg && liveMsg.session && (liveMsg.session.highest || liveMsg.session.span_semitones != null)),
    liveMsg && liveMsg.session ? JSON.stringify(liveMsg.session.highest) : '-');

  // هیچ درخواست بیرونی به سرور نرفته باشد
  const external = netCalls.filter(u => /^https?:/.test(u) && !/appassets\.local/.test(u));
  check('هیچ درخواست اینترنتی نرفته', external.length === 0, external.join(', '));

  printResults();
})().catch(e => {
  console.error(e && e.stack || e);
  try { printResults(); } catch (e2) { }
  process.exit(1);
});
