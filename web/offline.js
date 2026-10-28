/* ============================================================================
   🔌 لایهٔ آفلاین «ردِّ صدا» — web/offline.js
   ----------------------------------------------------------------------------
   بعد از این فایل، هیچ درخواستی به هیچ سروری نمی‌رود. همهٔ کارها — رمزگشایی
   فایل، تحلیل، ساخت تصویر، ساخت ویدیو، اجرای زنده — روی همین دستگاه انجام
   می‌شود.

   دو کار انجام می‌دهد:
     ۱) پیاده‌سازی محلی همان API سرور (upload / analyze / video / status /
        download)، با جایگزین‌کردن fetch و WebSocket ⇒ صفحهٔ رابط بدون
        تغییر رفتار، آفلاین کار می‌کند.
     ۲) رمزگشایی فایل‌های صوتی/ویدیویی که موتور به آن‌ها نیاز دارد.

   حالت «سرور» فقط اگر با ?engine=server صریحاً خواسته شود فعال می‌ماند.
   ========================================================================== */
(function (global) {
  'use strict';

  var CORE = global.LocalEngineCore;
  if (!CORE) { console.error('LocalEngineCore بارگذاری نشده — engine.js را قبل از offline.js بگذارید'); return; }

  var params = new URLSearchParams(global.location ? location.search : '');
  var FORCE_SERVER = params.get('engine') === 'server';
  if (FORCE_SERVER) {
    global.LocalOffline = { enabled: false };
    return;                                  // مسیر سرور (Docker/توسعه) دست‌نخورده
  }

  var SR = CORE.SR;

  /* ══════════════════════════════════════════════════════════════════════
     ۱) رمزگشایی فایل → کانال‌های نمونه (بدون شبکه، بدون ffmpeg)
     ══════════════════════════════════════════════════════════════════════ */

  function readWav(buf) {
    var dv = new DataView(buf);
    if (dv.getUint32(0, false) !== 0x52494646) return null;      // 'RIFF'
    if (dv.getUint32(8, false) !== 0x57415645) return null;      // 'WAVE'
    var offset = 12, fmt = null, dataOff = -1, dataLen = 0;
    while (offset + 8 <= buf.byteLength) {
      var id = dv.getUint32(offset, false);
      var size = dv.getUint32(offset + 4, true);
      var body = offset + 8;
      if (id === 0x666d7420) {                                    // 'fmt '
        fmt = {
          audioFormat: dv.getUint16(body, true),
          channels: dv.getUint16(body + 2, true),
          sampleRate: dv.getUint32(body + 4, true),
          bitsPerSample: dv.getUint16(body + 14, true)
        };
        if (fmt.audioFormat === 0xFFFE && size >= 40) {           // WAVE_FORMAT_EXTENSIBLE
          fmt.audioFormat = dv.getUint16(body + 24, true);
        }
      } else if (id === 0x64617461) {                             // 'data'
        dataOff = body;
        dataLen = Math.min(size, buf.byteLength - body);
        if (fmt) break;
      }
      offset = body + size + (size % 2);
    }
    if (!fmt || dataOff < 0) return null;

    var nCh = Math.max(1, fmt.channels);
    var bps = Math.max(1, Math.floor(fmt.bitsPerSample / 8));
    var frames = Math.floor(dataLen / (bps * nCh));
    var channels = [];
    for (var c = 0; c < nCh; c++) channels.push(new Float32Array(frames));

    var isFloat = (fmt.audioFormat === 3);
    for (var i = 0; i < frames; i++) {
      for (var ch = 0; ch < nCh; ch++) {
        var p = dataOff + (i * nCh + ch) * bps;
        var v;
        if (isFloat) {
          v = (bps === 8) ? dv.getFloat64(p, true) : dv.getFloat32(p, true);
        } else if (bps === 1) {
          v = (dv.getUint8(p) - 128) / 128;
        } else if (bps === 2) {
          v = dv.getInt16(p, true) / 32768;
        } else if (bps === 3) {
          var b0 = dv.getUint8(p), b1 = dv.getUint8(p + 1), b2 = dv.getUint8(p + 2);
          var ival = (b2 << 16) | (b1 << 8) | b0;
          if (ival & 0x800000) ival -= 0x1000000;
          v = ival / 8388608;
        } else {
          v = dv.getInt32(p, true) / 2147483648;
        }
        channels[ch][i] = v;
      }
    }
    return { channels: channels, sr: fmt.sampleRate, format: 'wav' };
  }

  function deinterleave(buffer) {
    var channels = [];
    for (var c = 0; c < buffer.numberOfChannels; c++) {
      channels.push(new Float32Array(buffer.getChannelData(c)));
    }
    return { channels: channels, sr: buffer.sampleRate, format: 'decoded' };
  }

  function audioCtx() {
    if (!audioCtx._ctx) {
      var AC = global.AudioContext || global.webkitAudioContext;
      audioCtx._ctx = new AC();
    }
    return audioCtx._ctx;
  }

  function decodeWithBrowser(arrayBuffer) {
    return new Promise(function (resolve) {
      var ctx = audioCtx();
      var done = false;
      var tryCb = function (buf) { if (!done) { done = true; resolve(buf ? deinterleave(buf) : null); } };
      try {
        var p = ctx.decodeAudioData(arrayBuffer.slice(0), tryCb, function () { tryCb(null); });
        if (p && p.then) p.then(function (b) { tryCb(b); }, function () { tryCb(null); });
      } catch (e) { tryCb(null); }
    });
  }

  /* ── MP4/MOV: بیرون‌کشیدن نمونه‌های صوتی (AAC) و رمزگشایی با WebCodecs ── */
  function boxes(dv, start, end) {
    var out = [];
    var p = start;
    while (p + 8 <= end) {
      var size = dv.getUint32(p, false);
      var type = dv.getUint32(p + 4, false);
      var header = 8;
      if (size === 1) { size = Number(dv.getBigUint64(p + 8, false)); header = 16; }
      else if (size === 0) { size = end - p; }
      if (size < 8) break;
      out.push({ type: type, start: p + header, end: Math.min(p + size, end) });
      p += size;
    }
    return out;
  }

  function fourcc(n) {
    return String.fromCharCode((n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255);
  }

  function demuxMp4Audio(arrayBuffer) {
    var dv = new DataView(arrayBuffer);
    var top = boxes(dv, 0, arrayBuffer.byteLength);
    var moov = top.filter(function (b) { return fourcc(b.type) === 'moov'; })[0];
    if (!moov) {
      var mdat = top.filter(function (b) { return fourcc(b.type) === 'moov' || fourcc(b.type) === 'moof'; });
      if (!mdat.length) return null;
      moov = mdat[0];
    }
    var traks = boxes(dv, moov.start, moov.end).filter(function (b) { return fourcc(b.type) === 'trak'; });

    for (var t = 0; t < traks.length; t++) {
      var trak = traks[t];
      var kids = boxes(dv, trak.start, trak.end);
      var mdia = kids.filter(function (b) { return fourcc(b.type) === 'mdia'; })[0];
      if (!mdia) continue;
      var mdiaKids = boxes(dv, mdia.start, mdia.end);
      var hdlr = mdiaKids.filter(function (b) { return fourcc(b.type) === 'hdlr'; })[0];
      if (!hdlr) continue;
      var handler = fourcc(dv.getUint32(hdlr.start + 8, false));
      if (handler !== 'soun') continue;

      var mdhd = mdiaKids.filter(function (b) { return fourcc(b.type) === 'mdhd'; })[0];
      var timescale = 44100;
      if (mdhd) {
        var version = dv.getUint8(mdhd.start);
        timescale = version === 1 ? dv.getUint32(mdhd.start + 20, false) : dv.getUint32(mdhd.start + 12, false);
      }
      var minf = mdiaKids.filter(function (b) { return fourcc(b.type) === 'minf'; })[0];
      if (!minf) continue;
      var stbl = boxes(dv, minf.start, minf.end).filter(function (b) { return fourcc(b.type) === 'stbl'; })[0];
      if (!stbl) continue;
      var st = boxes(dv, stbl.start, stbl.end);
      function pick(name) { return st.filter(function (b) { return fourcc(b.type) === name; })[0]; }

      var stsd = pick('stsd');
      if (!stsd) continue;
      var entry = boxes(dv, stsd.start + 8, stsd.end)[0];
      if (!entry) continue;
      var codecFourcc = fourcc(entry.type);
      if (codecFourcc !== 'mp4a') continue;

      // پیکربندی AAC از esds → AudioSpecificConfig
      var esds = boxes(dv, entry.start + 28, entry.end).filter(function (b) { return fourcc(b.type) === 'esds'; })[0];
      var asc = null, sampleRate = timescale, channels = 2;
      if (esds) {
        var p = esds.start + 4;                    // version/flags
        var parsed = null;
        // پیمایش توصیف‌گرهای ES (نه‌چندان سختگیرانه)
        for (var q = p; q < esds.end - 2; q++) {
          var tag = dv.getUint8(q);
          if (tag === 0x05) {                     // DecoderSpecificInfo
            var len = 0, off = q + 1, b;
            do { b = dv.getUint8(off++); len = (len << 7) | (b & 0x7F); } while (b & 0x80);
            parsed = new Uint8Array(arrayBuffer, off, Math.min(len, esds.end - off));
            break;
          }
        }
        asc = parsed;
      }
      if (asc && asc.length >= 2) {
        var objType = asc[0] >> 3;
        var srIndex = ((asc[0] & 0x07) << 1) | (asc[1] >> 7);
        var rates = [96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350];
        if (srIndex < rates.length) sampleRate = rates[srIndex];
        channels = (asc[1] >> 3) & 0x0F;
        if (objType === 5 || objType === 29) { /* SBR: نرخ خروجی دو برابر */ sampleRate *= 2; }
      }

      var stsz = pick('stsz'), stsc = pick('stsc'), stco = pick('stco') || pick('co64'), stts = pick('stts');
      if (!stsz || !stsc || !stco) continue;

      var fixedSize = dv.getUint32(stsz.start + 4, false);
      var sampleCount = dv.getUint32(stsz.start + 8, false);
      var sizes = new Array(sampleCount);
      for (var s = 0; s < sampleCount; s++) {
        sizes[s] = fixedSize || dv.getUint32(stsz.start + 12 + s * 4, false);
      }

      var chunkCount = dv.getUint32(stsc.start + 4, false);
      var stscEntries = [];
      for (var e = 0; e < chunkCount; e++) {
        var o = stsc.start + 8 + e * 12;
        stscEntries.push({
          firstChunk: dv.getUint32(o, false),
          samplesPerChunk: dv.getUint32(o + 4, false)
        });
      }

      var is64 = fourcc(stco.type) === 'co64';
      var chunkOffsetsCount = dv.getUint32(stco.start + 4, false);
      var offsets = new Array(chunkOffsetsCount);
      for (var k = 0; k < chunkOffsetsCount; k++) {
        offsets[k] = is64 ? Number(dv.getBigUint64(stco.start + 8 + k * 8, false))
          : dv.getUint32(stco.start + 8 + k * 4, false);
      }

      // زمان‌بندی هر نمونه (برای برچسب زمانی به میکروثانیه)
      var durations = [];
      if (stts) {
        var entryCount = dv.getUint32(stts.start + 4, false);
        for (var ti = 0; ti < entryCount; ti++) {
          var oo = stts.start + 8 + ti * 8;
          var count = dv.getUint32(oo, false), delta = dv.getUint32(oo + 4, false);
          for (var cc = 0; cc < count; cc++) durations.push(delta);
        }
      }

      var samples = [];
      var sampleIndex = 0;
      for (var ci = 0; ci < offsets.length && sampleIndex < sampleCount; ci++) {
        var spc = 0;
        for (var se = 0; se < stscEntries.length; se++) {
          if (stscEntries[se].firstChunk <= ci + 1) spc = stscEntries[se].samplesPerChunk;
        }
        var pos = offsets[ci];
        for (var sc = 0; sc < spc && sampleIndex < sampleCount; sc++) {
          var sz = sizes[sampleIndex];
          if (pos + sz > arrayBuffer.byteLength) break;
          samples.push({
            data: new Uint8Array(arrayBuffer, pos, sz),
            tsUs: 0,
            durUs: 0
          });
          pos += sz;
          sampleIndex++;
        }
      }
      // برچسب زمانی
      var tUs = 0;
      for (var si = 0; si < samples.length; si++) {
        var dSec = (durations[si] || 1024) / timescale;
        samples[si].tsUs = Math.round(tUs * 1e6);
        samples[si].durUs = Math.round(dSec * 1e6);
        tUs += dSec;
      }

      return {
        codec: 'mp4a.40.2',
        description: asc,
        sampleRate: sampleRate,
        channels: channels || 2,
        samples: samples,
        source: 'mp4'
      };
    }
    return null;
  }

  function decodeWithWebCodecs(track) {
    if (typeof global.AudioDecoder === 'undefined') return Promise.resolve(null);
    return new Promise(function (resolve) {
      var chunks = [];
      var totalSamples = 0;
      var cfg = {
        codec: track.codec,
        sampleRate: track.sampleRate,
        numberOfChannels: track.channels
      };
      if (track.description) cfg.description = track.description;
      var decoder;
      try { decoder = new global.AudioDecoder(cfg); } catch (e) { resolve(null); return; }
      var finished = false;

      decoder.onerror = function () { if (!finished) { finished = true; resolve(null); } };
      decoder.output = function (audioData) {
        try {
          var n = audioData.numberOfFrames;
          var nch = audioData.numberOfChannels;
          if (!chunks.length) {
            for (var c = 0; c < nch; c++) chunks.push(new Float32Array(0));
          }
          var fmt = audioData.format;
          for (var ch = 0; ch < nch && ch < chunks.length; ch++) {
            var dst = new Float32Array(chunks[ch].length + n);
            dst.set(chunks[ch]);
            if (fmt === 'f32-planar') {
              var arr = new Float32Array(n);
              audioData.copyTo(arr, { planeIndex: ch, format: 'f32-planar' });
              dst.set(arr, chunks[ch].length);
            } else {
              var inter = new Float32Array(n * nch);
              audioData.copyTo(inter, { planeIndex: 0, format: 'f32' });
              for (var i = 0; i < n; i++) dst[chunks[ch].length + i] = inter[i * nch + ch];
            }
            chunks[ch] = dst;
          }
          totalSamples += n;
        } finally {
          try { audioData.close(); } catch (e) { }
        }
      };

      var i = 0;
      function feed() {
        if (i >= track.samples.length) {
          decoder.flush().then(function () {
            if (finished) return;
            if (!totalSamples) { finished = true; resolve(null); return; }
            finished = true;
            resolve({ channels: chunks, sr: track.sampleRate, format: track.source });
          }).catch(function () { if (!finished) { finished = true; resolve(null); } });
          return;
        }
        var s = track.samples[i++];
        try {
          decoder.decode(new EncodedAudioChunk({
            type: 'key',
            timestamp: s.tsUs,
            duration: s.durUs,
            data: s.data
          }));
        } catch (e) { /* نمونهٔ خراب: رد شود */ }
        if (i % 40 === 0) setTimeout(feed, 0); else feed();
      }
      feed();
    });
  }

  function isMp4(buf) {
    if (buf.byteLength < 12) return false;
    var dv = new DataView(buf);
    var t = fourcc(dv.getUint32(4, false));
    return t === 'ftyp' || t === 'moov' || t === 'skip' || t === 'free' || t === 'mdat';
  }

  async function decodeFile(file) {
    var buf = await file.arrayBuffer();
    var wav = null;
    try { wav = readWav(buf); } catch (e) { wav = null; }
    if (wav) return wav;

    var decoded = await decodeWithBrowser(buf);
    if (decoded) return decoded;

    // ویدیو/صدای MP4 / MOV / M4A: دمکس داخلی + WebCodecs
    if (isMp4(buf)) {
      var track = null;
      try { track = demuxMp4Audio(buf); } catch (e) { track = null; }
      if (track) {
        var r = await decodeWithWebCodecs(track);
        if (r) return r;
      }
    }

    var name = (file.name || '').toLowerCase();
    var hint = /\.(mp4|mov|m4v|mkv|webm|avi)$/.test(name)
      ? 'صدای این ویدیو در مرورگر قابل استخراج نیست. صدا را با یک برنامهٔ ساده جدا کن (یا فایل WAV/MP3 بده).'
      : 'این فایل خوانده نشد. فرمت‌های MP3 · WAV · M4A · OGG · FLAC را امتحان کن.';
    throw new Error(hint);
  }

  /* ══════════════════════════════════════════════════════════════════════
     ۲) رندرر «ردِّ صدا» روی canvas (پورت trace_video.py)
     ══════════════════════════════════════════════════════════════════════ */
  var LAYOUT = { W: 1280, H: 720, ML: 74, MR: 46, MT: 78, MB: 64 };
  var GAP_BRIDGE_SEC = 0.75;

  function TraceRenderer(canvas, times, freqs, notes) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.W = canvas.width;
    this.H = canvas.height;
    this.notes = notes || [];

    var t = Array.from(times), f = Array.from(freqs);
    var order = t.map(function (_, i) { return i; }).sort(function (a, b) { return t[a] - t[b]; });
    this.times = order.map(function (i) { return t[i]; });
    this.midi = order.map(function (i) { return f[i] > 0 ? CORE.hzToMidi(f[i]) : NaN; });
    this.duration = this.times.length ? this.times[this.times.length - 1] : 1;
    if (!(this.duration > 0)) this.duration = 1;

    var valid = this.midi.filter(function (m) { return isFinite(m); });
    var lo = valid.length ? Math.min.apply(null, valid) : 48;
    var hi = valid.length ? Math.max.apply(null, valid) : 60;
    var span = Math.max(hi - lo, 11.9);
    var center = (lo + hi) / 2;
    var pad = Math.max(1.6, span * 0.10);
    this.loMidi = Math.floor(center - span / 2 - pad);
    this.hiMidi = Math.ceil(center + span / 2 + pad);

    if (this.notes.length) {
      this.highest = this.notes.reduce(function (a, n) {
        return (!a || (n.f0_hz || 0) > (a.f0_hz || 0)) ? n : a;
      }, null);
      this.lowest = this.notes.reduce(function (a, n) {
        return (!a || (n.f0_hz || 1e9) < (a.f0_hz || 1e9)) ? n : a;
      }, null);
    } else { this.highest = this.lowest = null; }

    this.px = this.times.map(this.xOfTime.bind(this));
    var py = this.midi.map(function (m) { return isFinite(m) ? null : NaN; });
    var self = this;
    this.py = this.midi.map(function (m) { return isFinite(m) ? self.yOfMidi(m) : NaN; });
    this.py = this.smoothPy(this.py, 5);
    this.segs = this.buildSegments();
    this._cache = null;
  }

  TraceRenderer.prototype.xOfTime = function (t) {
    var usable = this.W - LAYOUT.ML - LAYOUT.MR;
    return LAYOUT.ML + (t / this.duration) * usable;
  };
  TraceRenderer.prototype.yOfMidi = function (m) {
    var usable = this.H - LAYOUT.MT - LAYOUT.MB;
    var frac = (m - this.loMidi) / Math.max(1e-6, this.hiMidi - this.loMidi);
    return LAYOUT.MT + (1 - frac) * usable;
  };

  TraceRenderer.prototype.smoothPy = function (py, win) {
    var out = Float64Array.from(py);
    var n = py.length, half = win >> 1, i = 0;
    while (i < n) {
      if (!isFinite(out[i])) { i++; continue; }
      var j = i;
      while (j < n && isFinite(out[j])) j++;
      var seg = out.subarray(i, j);
      if (seg.length >= 3) {
        var copy = Float64Array.from(seg);
        var k = Math.min(win, seg.length), pad = k >> 1;
        for (var s = 0; s < seg.length; s++) {
          var acc = 0, cnt = 0;
          for (var q = s - pad; q <= s + pad; q++) {
            var idx = Math.max(0, Math.min(seg.length - 1, q));
            acc += copy[idx]; cnt++;
          }
          seg[s] = acc / cnt;
        }
      }
      i = j;
    }
    return out;
  };

  TraceRenderer.prototype.buildSegments = function () {
    var runs = [], cur = [], lastT = null;
    for (var i = 0; i < this.px.length; i++) {
      if (!isFinite(this.py[i])) {
        if (cur.length > 1) runs.push(cur);
        cur = []; lastT = null; continue;
      }
      var t = this.times[i];
      if (lastT != null && (t - lastT) > GAP_BRIDGE_SEC) {
        if (cur.length > 1) runs.push(cur);
        cur = [];
      }
      cur.push({ x: this.px[i], y: this.py[i], t: t });
      lastT = t;
    }
    if (cur.length > 1) runs.push(cur);
    if (!runs.length) return [];

    var segs = [runs[0].slice()];
    for (var r = 1; r < runs.length; r++) {
      var run = runs[r], prev = segs[segs.length - 1];
      var gap = run[0].t - prev[prev.length - 1].t;
      if (gap <= GAP_BRIDGE_SEC) {
        var a = prev[prev.length - 1], b = run[0];
        var n = Math.max(2, Math.min(24, Math.round(gap * 40) + 2));
        for (var j = 1; j < n; j++) {
          var fr = j / n;
          prev.push({
            x: a.x + (b.x - a.x) * fr,
            y: a.y + (b.y - a.y) * fr,
            t: a.t + (b.t - a.t) * fr
          });
        }
        prev.push.apply(prev, run);
      } else {
        segs.push(run.slice());
      }
    }
    return segs.filter(function (s) { return s.length > 1; });
  };

  /* پس‌زمینه: گرادیان + هاله + قاب + خطوط نت (یک‌بار ساخته و کش می‌شود) */
  TraceRenderer.prototype.buildBackground = function () {
    var W = this.W, H = this.H;
    var c = document.createElement('canvas');
    c.width = W; c.height = H;
    var g = c.getContext('2d');

    var grad = g.createLinearGradient(0, 0, 0, H);
    grad.addColorStop(0, 'rgb(13,23,48)');
    grad.addColorStop(1, 'rgb(23,36,66)');
    g.fillStyle = grad;
    g.fillRect(0, 0, W, H);

    var halo = g.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, 0.62 * Math.max(W, H));
    halo.addColorStop(0, 'rgba(26,40,68,1)');
    halo.addColorStop(0.5, 'rgba(13,20,34,0.55)');
    halo.addColorStop(1, 'rgba(0,0,0,0)');
    g.globalCompositeOperation = 'lighter';
    g.fillStyle = halo;
    g.fillRect(0, 0, W, H);
    g.globalCompositeOperation = 'source-over';

    // قاب گرد
    g.strokeStyle = 'rgba(120,152,205,0.18)';
    g.lineWidth = 2;
    this.roundRect(g, 10, 10, W - 20, H - 20, 26);
    g.stroke();

    // خطوط نت
    for (var m = Math.ceil(this.loMidi); m <= Math.floor(this.hiMidi); m++) {
      var y = this.yOfMidi(m);
      var isC = ((m % 12) + 12) % 12 === 0;
      g.strokeStyle = isC ? 'rgba(255,255,255,0.10)' : 'rgba(255,255,255,0.05)';
      g.lineWidth = isC ? 2 : 1;
      g.beginPath();
      g.moveTo(22, y);
      g.lineTo(W - 22, y);
      g.stroke();
    }

    // لایهٔ «آینده»: منحنی محو + نقطه‌های محو نت‌ها
    g.strokeStyle = 'rgba(86,120,160,0.41)';
    g.lineWidth = 4;
    g.lineJoin = 'round';
    g.lineCap = 'round';
    for (var s = 0; s < this.segs.length; s++) {
      var seg = this.segs[s];
      g.beginPath();
      g.moveTo(seg[0].x, seg[0].y);
      for (var i = 1; i < seg.length; i++) g.lineTo(seg[i].x, seg[i].y);
      g.stroke();
    }

    var self = this;
    (this.notes || []).forEach(function (n) {
      var f0 = n.f0_hz;
      if (!f0 || f0 <= 0) return;
      var x = self.xOfTime(n.start || 0);
      var y = self.yOfMidi(CORE.hzToMidi(f0));
      var rg = g.createRadialGradient(x, y, 0, x, y, 8.5);
      rg.addColorStop(0, 'rgba(104,138,176,0.72)');
      rg.addColorStop(1, 'rgba(104,138,176,0)');
      g.fillStyle = rg;
      g.beginPath();
      g.arc(x, y, 8.5, 0, Math.PI * 2);
      g.fill();
    });

    return c;
  };

  TraceRenderer.prototype.roundRect = function (g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  };

  TraceRenderer.prototype.currentMidi = function (t) {
    var times = this.times, mids = this.midi;
    if (!times.length) return null;
    var lo = 0, hi = times.length - 1, idx = 0;
    // searchsorted
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (times[mid] <= t) { idx = mid; lo = mid + 1; } else { hi = mid - 1; }
    }
    if (isFinite(mids[idx])) {
      if (idx >= times.length - 1) return mids[idx];
      var t0 = times[idx], t1 = times[idx + 1], m0 = mids[idx], m1 = mids[idx + 1];
      if (isFinite(m1) && t1 > t0) {
        var fr = (t - t0) / (t1 - t0);
        return m0 + (m1 - m0) * Math.max(0, Math.min(1, fr));
      }
      return m0;
    }
    for (var i = idx; i >= 0; i--) if (isFinite(mids[i])) return mids[i];
    return null;
  };

  TraceRenderer.prototype.draw = function (time) {
    var t = Math.max(0, Math.min(time, this.duration));
    var g = this.ctx, W = this.W, H = this.H;
    if (!this._bg) this._bg = this.buildBackground();
    g.clearRect(0, 0, W, H);
    g.drawImage(this._bg, 0, 0);

    // منحنی طلایی تا لحظهٔ t (سه لایه = درخشش)
    g.lineJoin = 'round';
    g.lineCap = 'round';
    for (var s = 0; s < this.segs.length; s++) {
      var seg = this.segs[s];
      var pts = [];
      for (var i = 0; i < seg.length && seg[i].t <= t; i++) pts.push(seg[i]);
      if (pts.length < 2) continue;
      if (pts.length < seg.length) {
        var a = pts[pts.length - 1], b = seg[pts.length];
        pts = pts.concat([{ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }]);
      }
      var styles = [
        [12, 'rgba(255,214,126,0.13)'],
        [7, 'rgba(255,214,126,0.31)'],
        [4, 'rgba(247,209,113,1)']
      ];
      for (var k = 0; k < 3; k++) {
        g.strokeStyle = styles[k][1];
        g.lineWidth = styles[k][0];
        g.beginPath();
        g.moveTo(pts[0].x, pts[0].y);
        for (var j = 1; j < pts.length; j++) g.lineTo(pts[j].x, pts[j].y);
        g.stroke();
      }
    }

    // نقطه‌های طلایی روی نت‌های خوانده‌شده
    var self = this;
    (this.notes || []).forEach(function (n) {
      var start = n.start || 0;
      if (start > t) return;
      var f0 = n.f0_hz;
      if (!f0 || f0 <= 0) return;
      var x = self.xOfTime(start + (n.duration || 0) / 2);
      var y = self.yOfMidi(CORE.hzToMidi(f0));
      var r = (n.duration || 0) < 0.35 ? 4.6 : 6.0;
      g.fillStyle = 'rgb(255,228,155)';
      g.beginPath();
      g.arc(x, y, r, 0, Math.PI * 2);
      g.fill();
    });

    // خط پخش
    var xn = this.xOfTime(t);
    g.strokeStyle = 'rgba(255,255,255,0.16)';
    g.lineWidth = 6;
    g.beginPath(); g.moveTo(xn, 18); g.lineTo(xn, H - 18); g.stroke();
    g.strokeStyle = 'rgba(255,255,255,0.82)';
    g.lineWidth = 2;
    g.beginPath(); g.moveTo(xn, 18); g.lineTo(xn, H - 18); g.stroke();

    // نقطهٔ درخشان روی نت جاری
    var mNow = this.currentMidi(t);
    if (mNow != null) {
      var yn = this.yOfMidi(mNow);
      var halo = g.createRadialGradient(xn, yn, 0, xn, yn, 30);
      halo.addColorStop(0, 'rgba(255,228,155,0.55)');
      halo.addColorStop(0.45, 'rgba(255,228,155,0.18)');
      halo.addColorStop(1, 'rgba(255,228,155,0)');
      g.fillStyle = halo;
      g.beginPath(); g.arc(xn, yn, 30, 0, Math.PI * 2); g.fill();

      var core = g.createRadialGradient(xn, yn, 0, xn, yn, 12);
      core.addColorStop(0, 'rgb(255,250,232)');
      core.addColorStop(0.6, 'rgba(255,240,205,0.85)');
      core.addColorStop(1, 'rgba(255,240,205,0)');
      g.fillStyle = core;
      g.beginPath(); g.arc(xn, yn, 12, 0, Math.PI * 2); g.fill();
    }

    // راهنمای بالاترین/پایین‌ترین نت
    g.font = '15px system-ui, sans-serif';
    var guides = [[this.highest, 'rgba(255,226,160,', 'HIGH', LAYOUT.ML],
      [this.lowest, 'rgba(176,206,235,', 'LOW', W - 320]];
    guides.forEach(function (gd) {
      var note = gd[0];
      if (!note || !note.f0_hz) return;
      var y = self.yOfMidi(CORE.hzToMidi(note.f0_hz));
      var x0 = self.xOfTime(note.start || 0);
      g.strokeStyle = gd[1] + '0.22)';
      g.lineWidth = 1;
      g.setLineDash([7, 7]);
      g.beginPath(); g.moveTo(x0, y); g.lineTo(W - 30, y); g.stroke();
      g.setLineDash([]);
      g.fillStyle = gd[1] + '0.5)';
      g.fillText(gd[2] + ' ' + CORE.midiToName(CORE.hzToMidi(note.f0_hz)), gd[3], y - 8);
    });

    // برچسب‌ها
    var cur = (mNow == null) ? '—'
      : CORE.midiToName(mNow) + '   ' + (CORE.midiToHz(mNow)).toFixed(1) + ' Hz';
    g.font = 'bold 24px system-ui, sans-serif';
    g.fillStyle = 'rgba(238,234,214,0.92)';
    g.textAlign = 'left';
    g.fillText(cur, 40, 46);

    g.font = '16px system-ui, sans-serif';
    g.fillStyle = 'rgba(150,168,200,0.59)';
    g.fillText(t.toFixed(1) + ' / ' + this.duration.toFixed(1) + 's', W - 190, 56);

    if (this.highest && this.lowest && this.highest.f0_hz && this.lowest.f0_hz) {
      var span = 12 * Math.log2(this.highest.f0_hz / this.lowest.f0_hz);
      var hiName = CORE.midiToName(CORE.hzToMidi(this.highest.f0_hz));
      var loName = CORE.midiToName(CORE.hzToMidi(this.lowest.f0_hz));
      var txt = hiName + '  ↕  ' + loName +
        '   (' + span.toFixed(1) + ' semitones)';
      g.font = '17px system-ui, sans-serif';
      g.fillStyle = 'rgba(196,210,235,0.82)';
      g.fillText(txt, W - 420, H - 30);
    }
    g.textAlign = 'left';
    return this.canvas;
  };

  TraceRenderer.prototype.toBlob = function (t) {
    var self = this;
    this.draw(t == null ? this.duration : t);
    return new Promise(function (resolve) {
      self.canvas.toBlob(function (b) { resolve(b); }, 'image/png');
    });
  };

  /* ══════════════════════════════════════════════════════════════════════
     ۳) ساخت ویدیو (canvas + MediaRecorder + صدای اصلی)
     ══════════════════════════════════════════════════════════════════════ */
  function pickVideoMime() {
    var cands = [
      'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
      'video/mp4',
      'video/webm;codecs=vp9,opus',
      'video/webm;codecs=vp8,opus',
      'video/webm'
    ];
    for (var i = 0; i < cands.length; i++) {
      if (global.MediaRecorder && MediaRecorder.isTypeSupported(cands[i])) return cands[i];
    }
    return '';
  }

  async function renderVideoBlob(pitch, notes, wav, sr, onProgress) {
    var canvas = document.createElement('canvas');
    canvas.width = LAYOUT.W;
    canvas.height = LAYOUT.H;
    var renderer = new TraceRenderer(canvas, pitch.times, pitch.freqs, notes);

    var fps = 20;
    var duration = Math.max(renderer.duration, 0.2);
    if (duration > 60) fps = 15;
    if (duration > 300) fps = 10;

    var stream = canvas.captureStream(fps);
    var actx = new (global.AudioContext || global.webkitAudioContext)();
    if (actx.state === 'suspended') { try { await actx.resume(); } catch (e) { } }
    var abuf = actx.createBuffer(1, wav.length, sr);
    abuf.copyToChannel(wav, 0);
    var src = actx.createBufferSource();
    src.buffer = abuf;
    var dest = actx.createMediaStreamDestination();
    src.connect(dest);
    dest.stream.getAudioTracks().forEach(function (tr) { stream.addTrack(tr); });

    var mime = pickVideoMime();
    var rec = new MediaRecorder(stream, mime ? {
      mimeType: mime, videoBitsPerSecond: 4000000, audioBitsPerSecond: 160000
    } : { videoBitsPerSecond: 4000000, audioBitsPerSecond: 160000 });

    var chunks = [];
    rec.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };

    var done = new Promise(function (resolve) {
      rec.onstop = function () { resolve(new Blob(chunks, { type: mime || 'video/webm' })); };
    });

    renderer.draw(0);
    rec.start(250);
    var t0 = actx.currentTime;
    src.start();

    await new Promise(function (resolve) {
      function step() {
        var el = actx.currentTime - t0;
        renderer.draw(el);
        if (onProgress) onProgress(Math.min(0.99, el / duration));
        if (el >= duration) { resolve(); return; }
        requestAnimationFrame(step);
      }
      requestAnimationFrame(step);
    });

    rec.stop();
    var blob = await done;
    try { src.stop(); } catch (e) { }
    try { actx.close(); } catch (e) { }
    var allTracks = stream.getTracks ? stream.getTracks()
      : [].concat(stream.getAudioTracks ? stream.getAudioTracks() : [],
                  stream.getVideoTracks ? stream.getVideoTracks() : []);
    allTracks.forEach(function (t) { try { t.stop(); } catch (e) { } });
    if (onProgress) onProgress(1.0);
    return { blob: blob, mime: mime || 'video/webm', name: (mime.indexOf('mp4') >= 0 ? 'radde-seda.mp4' : 'radde-seda.webm') };
  }

  /* ══════════════════════════════════════════════════════════════════════
     ۴) مدیریت کارها (هم‌ارز مسیرهای API سرور، ولی محلی)
     ══════════════════════════════════════════════════════════════════════ */
  var jobs = {};

  function newJob(kind) {
    var id = 'local_' + Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
    jobs[id] = {
      id: id, kind: kind, status: 'pending', stage: 'queued', progress: 0,
      result: null, error: null, listeners: [],
      file: null, pitch: null, report: null, imageBlob: null,
      originalUrl: null, audioUrl: null, reportUrl: null, imageUrl: null,
      videoBlob: null, videoUrl: null, videoName: null
    };
    return jobs[id];
  }

  function jobUpdate(job) {
    (job.listeners || []).forEach(function (fn) {
      try { fn(job); } catch (e) { }
    });
  }

  function revoke(oldUrl) {
    if (oldUrl && String(oldUrl).indexOf('blob:') === 0) {
      try { URL.revokeObjectURL(oldUrl); } catch (e) { }
    }
  }

  async function runAnalysis(job, denoise) {
    job.status = 'running'; job.stage = 'decoding'; job.progress = 0.01; jobUpdate(job);
    try {
      var decoded = await decodeFile(job.file);

      job.stage = 'preparing'; job.progress = 0.02; jobUpdate(job);

      var res = CORE.analyzeChannels(decoded.channels, decoded.sr, {
        denoise: !!denoise,
        onProgress: function (stage, frac) {
          job.stage = stage;
          job.progress = Math.max(0.02, Math.min(0.98, frac));
          jobUpdate(job);
        }
      });

      job.stage = 'plot'; job.progress = 0.99; jobUpdate(job);

      // تصویر PNG
      var canvas = document.createElement('canvas');
      canvas.width = LAYOUT.W; canvas.height = LAYOUT.H;
      var renderer = new TraceRenderer(canvas, res.times, res.freqs, res.notes);
      var png = await renderer.toBlob(null);
      job.imageBlob = png;
      job.imageUrl = URL.createObjectURL(png);

      // فایل اصلی + گزارش JSON
      job.originalUrl = URL.createObjectURL(job.file);

      var report = CORE.buildReport(res, {
        file: job.file.name,
        original_name: job.file.name,
        denoise: !!denoise,
        audio_url: job.originalUrl,
        visualization_url: job.imageUrl
      });
      job.reportUrl = URL.createObjectURL(new Blob([JSON.stringify(report, null, 1)],
        { type: 'application/json;charset=utf-8' }));
      report.meta.downloads = {
        original: job.originalUrl,
        image: job.imageUrl,
        report: job.reportUrl
      };

      job.pitch = res;
      job.report = report;
      job.result = report;
      job.status = 'done'; job.stage = 'done'; job.progress = 1.0;
      jobUpdate(job);
    } catch (e) {
      job.status = 'error';
      job.error = (e && e.message) ? e.message : String(e);
      jobUpdate(job);
    }
  }

  async function runVideo(job) {
    job.status = 'running'; job.stage = 'video'; job.progress = 0.02; jobUpdate(job);
    try {
      var res = job.pitch;
      if (!res) throw new Error('اول تحلیل را اجرا کن');
      var out = await renderVideoBlob(res, res.notes, res.wav, res.sr, function (f) {
        job.progress = 0.02 + 0.96 * f;
        job.stage = 'video';
        jobUpdate(job);
      });
      job.videoBlob = out.blob;
      job.videoUrl = URL.createObjectURL(out.blob);
      job.videoName = out.name;
      job.report.meta.video_url = job.videoUrl;
      job.report.meta.video_name = out.name;
      job.result = {
        video_url: job.videoUrl,
        video_name: out.name,
        video_type: out.mime
      };
      if (job.report.meta.downloads) job.report.meta.downloads.video = job.videoUrl;
      job.status = 'done'; job.stage = 'done'; job.progress = 1; jobUpdate(job);
    } catch (e) {
      job.status = 'error';
      job.error = (e && e.message) ? e.message : String(e);
      jobUpdate(job);
    }
  }

  /* ══════════════════════════════════════════════════════════════════════
     ۵) جایگزین fetch (فقط مسیرهای /api/*)
     ══════════════════════════════════════════════════════════════════════ */
  function jsonResponse(obj, status) {
    return new Response(JSON.stringify(obj), {
      status: status || 200,
      headers: { 'Content-Type': 'application/json' }
    });
  }

  function blobResponse(blob, name) {
    var headers = { 'Content-Type': (blob && blob.type) || 'application/octet-stream' };
    if (name) headers['Content-Disposition'] = 'attachment; filename="' + name + '"';
    return new Response(blob, { status: 200, headers: headers });
  }

  var origFetch = global.fetch ? global.fetch.bind(global) : null;

  async function localFetch(input, init) {
    var url = (typeof input === 'string') ? input : (input && input.url) || '';
    var abs;
    try { abs = new URL(url, location.href); } catch (e) { return origFetch ? origFetch(input, init) : jsonResponse({ error: 'bad url' }, 400); }
    if (!/\/api\//.test(abs.pathname)) {
      return origFetch ? origFetch(input, init) : jsonResponse({ error: 'offline' }, 404);
    }

    var path = abs.pathname;
    var method = ((init && init.method) || (input && input.method) || 'GET').toUpperCase();
    var m;

    // POST /api/upload
    if (/\/api\/upload$/.test(path) && method === 'POST') {
      var body = init && init.body;
      var file = null;
      if (body && typeof body.get === 'function') file = body.get('file');
      if (!file) return jsonResponse({ error: 'فایلی انتخاب نشده' }, 400);
      var job = newJob('analyze');
      job.file = file;
      return jsonResponse({
        job_id: job.id, filename: file.name, saved_as: file.name,
        size_bytes: file.size, kind: /video/i.test(file.type) ? 'video' : 'audio'
      });
    }

    // POST /api/analyze/{id}
    if ((m = path.match(/\/api\/analyze\/([^/]+)$/)) && method === 'POST') {
      var j = jobs[m[1]];
      if (!j) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      var denoise = false;
      if (init && init.body && typeof init.body.get === 'function') {
        denoise = init.body.get('denoise') === 'true';
      }
      runAnalysis(j, denoise);
      return jsonResponse({ job_id: j.id, status: 'started' });
    }

    // POST /api/video/{id}
    if ((m = path.match(/\/api\/video\/([^/]+)$/)) && method === 'POST') {
      var jv = jobs[m[1]];
      if (!jv) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      runVideo(jv);
      return jsonResponse({ job_id: jv.id, status: 'started' });
    }

    // GET /api/status/{id}
    if ((m = path.match(/\/api\/status\/([^/]+)$/))) {
      var js = jobs[m[1]];
      if (!js) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      return jsonResponse({
        id: js.id, kind: js.kind, status: js.status, stage: js.stage,
        progress: js.progress, result: js.result, error: js.error
      });
    }

    // GET /api/download/{id}/{kind}
    if ((m = path.match(/\/api\/download\/([^/]+)\/([^/]+)$/))) {
      var jd = jobs[m[1]];
      if (!jd) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      var kind = m[2];
      if (kind === 'original') return blobResponse(jd.file, jd.file ? jd.file.name : 'original');
      if (kind === 'image') return blobResponse(jd.imageBlob, 'range_trace.png');
      if (kind === 'report') {
        var json = JSON.stringify(jd.report, null, 1);
        return blobResponse(new Blob([json], { type: 'application/json;charset=utf-8' }),
          ((jd.file && jd.file.name || 'report').replace(/\.[^.]+$/, '')) + '-report.json');
      }
      if (kind === 'video') return blobResponse(jd.videoBlob, jd.videoName || 'radde-seda.webm');
      return jsonResponse({ error: 'نوع نامعتبر' }, 400);
    }

    // GET /api/file/{id}/{name}
    if ((m = path.match(/\/api\/file\/([^/]+)\/([^/]+)$/))) {
      var jf = jobs[m[1]];
      if (!jf) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      var nm = decodeURIComponent(m[2]);
      if (/\.png$/.test(nm)) return blobResponse(jf.imageBlob, nm);
      if (/\.json$/.test(nm)) {
        return blobResponse(new Blob([JSON.stringify(jf.report, null, 1)],
          { type: 'application/json;charset=utf-8' }), nm);
      }
      if (/\.(mp4|webm)$/.test(nm)) return blobResponse(jf.videoBlob, nm);
      return blobResponse(jf.file, nm);
    }

    return jsonResponse({ error: 'مسیر ناشناخته (حالت آفلاین)' }, 404);
  }

  global.fetch = function (input, init) { return localFetch(input, init); };

  /* ══════════════════════════════════════════════════════════════════════
     ۶) جایگزین WebSocket (پیشرفت کار + اجرای زنده)
     ══════════════════════════════════════════════════════════════════════ */
  function FakeWebSocket(url) {
    this.url = url;
    this.readyState = 0;
    this.onopen = null; this.onmessage = null; this.onclose = null; this.onerror = null;
    this._listeners = {};
    this._closed = false;
    var self = this;
    var path = '';
    try { path = new URL(url, location.href).pathname; } catch (e) { path = url; }

    setTimeout(function () {
      if (self._closed) return;

      // اول اشتراک/جلسه را آماده کن، بعد رویداد open را بده تا پیام‌هایی که
      // کلاینت همان لحظهٔ open می‌فرستد گم نشوند
      var m = path.match(/\/ws\/job\/([^/]+)$/);
      if (m) {
        var job = jobs[m[1]];
        if (job) {
          var push = function (j) {
            if (self._closed) return;
            self._sendMsg(JSON.stringify({
              id: j.id, status: j.status, stage: j.stage,
              progress: j.progress, result: j.result, error: j.error
            }));
          };
          job.listeners = job.listeners || [];
          job.listeners.push(push);
          self._unsub = function () {
            job.listeners = (job.listeners || []).filter(function (f) { return f !== push; });
          };
          self._pushJob = push;
          self._job = job;
        }
      } else if (/\/ws\/live-pitch$/.test(path)) {
        self._live = new LiveSession(self);
      }

      self.readyState = 1;
      self._emit('open', {});
      if (self.onopen) self.onopen({});
      if (self._pushJob && self._job) self._pushJob(self._job);
    }, 0);
  }

  FakeWebSocket.prototype._emit = function (type, ev) {
    var arr = this._listeners[type] || [];
    arr.forEach(function (fn) { try { fn(ev); } catch (e) { } });
    var prop = this['on' + type];
    if (prop && type !== 'open' && type !== 'message' && type !== 'close') {
      try { prop(ev); } catch (e) { }
    }
  };

  FakeWebSocket.prototype._sendMsg = function (data) {
    if (this._closed) return;
    var ev = { data: data };
    if (this.onmessage) { try { this.onmessage(ev); } catch (e) { } }
    var arr = this._listeners['message'] || [];
    arr.forEach(function (fn) { try { fn(ev); } catch (e) { } });
  };

  FakeWebSocket.prototype.addEventListener = function (type, fn) {
    (this._listeners[type] = this._listeners[type] || []).push(fn);
  };
  FakeWebSocket.prototype.removeEventListener = function (type, fn) {
    this._listeners[type] = (this._listeners[type] || []).filter(function (f) { return f !== fn; });
  };

  FakeWebSocket.prototype.send = function (data) {
    if (this._live) this._live.handle(data);
  };

  FakeWebSocket.prototype.close = function () {
    this._closed = true;
    this.readyState = 3;
    if (this._unsub) { try { this._unsub(); } catch (e) { } }
    if (this._live) this._live.stop();
    var ev = { code: 1000, reason: 'closed' };
    if (this.onclose) { try { this.onclose(ev); } catch (e) { } }
    var arr = this._listeners['close'] || [];
    arr.forEach(function (fn) { try { fn(ev); } catch (e) { } });
  };

  /* ── اجرای زنده: همان پروتکل سرور، ولی محلی ─────────────────────────── */
  function LiveSession(ws) {
    this.ws = ws;
    this.sr = 16000;
    this.smooth = null;
    this.stableMidi = null;
    this.stableCount = 0;
    this.lo = null;
    this.hi = null;
    this.STABLE_CHUNKS = 3;
    this.dead = false;
  }

  LiveSession.prototype.stop = function () { this.dead = true; };

  LiveSession.prototype.handle = function (data) {
    if (this.dead) return;
    if (typeof data === 'string') {
      try {
        var cfg = JSON.parse(data);
        if (cfg.sample_rate) this.sr = cfg.sample_rate;
        if (cfg.reset) {
          this.smooth = null; this.stableMidi = null; this.stableCount = 0;
          this.lo = this.hi = null;
          this.ws._sendMsg(JSON.stringify({ reset: true }));
        }
      } catch (e) { }
      return;
    }

    var bytes = (data instanceof ArrayBuffer) ? new Uint8Array(data) : new Uint8Array(data.buffer || data);
    if (!bytes.length) return;
    var fmt = bytes[0];
    var samples;
    if (fmt === 0) {
      var copy = bytes.slice(1);
      samples = new Float32Array(copy.buffer, copy.byteOffset, copy.byteLength >> 2);
    } else {
      var c2 = bytes.slice(1);
      var iv = new Int16Array(c2.buffer, c2.byteOffset, c2.byteLength >> 1);
      samples = new Float32Array(iv.length);
      for (var i = 0; i < iv.length; i++) samples[i] = iv[i] / 32768;
    }

    var f0 = CORE.extractPitchChunk(samples, this.sr, 60.0, 1000.0);
    var note = null;
    if (f0) {
      var midi = CORE.hzToMidi(f0);
      if (this.smooth == null || Math.abs(midi - this.smooth) > 1.0) this.smooth = midi;
      else this.smooth += 0.5 * (midi - this.smooth);

      var hz = CORE.midiToHz(this.smooth);
      var midiF = CORE.hzToMidi(hz);
      var midiR = Math.round(midiF);
      var rounded = Math.round(midi);
      if (rounded === this.stableMidi) this.stableCount++;
      else { this.stableMidi = rounded; this.stableCount = 1; }
      if (this.stableCount >= this.STABLE_CHUNKS) {
        if (!this.hi || midi > this.hi.midi) this.hi = { midi: midi, f0: f0 };
        if (!this.lo || midi < this.lo.midi) this.lo = { midi: midi, f0: f0 };
      }
      note = {
        note_name: CORE.midiToName(midiF),
        midi: midiR,
        midi_exact: Math.round(midiF * 1000) / 1000,
        f0_hz: Math.round(hz * 100) / 100,
        cents_offset: Math.round((midiF - midiR) * 100),
        stable: this.stableCount >= this.STABLE_CHUNKS
      };
    }

    this.ws._sendMsg(JSON.stringify({
      note: note,
      session: {
        lowest: this.lo ? {
          note_name: CORE.midiToName(CORE.hzToMidi(this.lo.f0)),
          midi: Math.round(this.lo.midi * 100) / 100,
          f0_hz: Math.round(this.lo.f0 * 100) / 100
        } : null,
        highest: this.hi ? {
          note_name: CORE.midiToName(CORE.hzToMidi(this.hi.f0)),
          midi: Math.round(this.hi.midi * 100) / 100,
          f0_hz: Math.round(this.hi.f0 * 100) / 100
        } : null,
        span_semitones: (this.lo && this.hi) ? Math.round((this.hi.midi - this.lo.midi) * 100) / 100 : 0
      }
    }));
  };

  global.WebSocket = function (url, protocols) { return new FakeWebSocket(url, protocols); };
  global.WebSocket.CONNECTING = 0;
  global.WebSocket.OPEN = 1;
  global.WebSocket.CLOSING = 2;
  global.WebSocket.CLOSED = 3;

  /* ══════════════════════════════════════════════════════════════════════
     ۷) خروجی‌ها
     ══════════════════════════════════════════════════════════════════════ */
  // نشان «آفلاین» در بالای صفحه
  function showBadge() {
    try {
      var b = document.getElementById('offlineBadge');
      if (b) b.style.display = 'inline-flex';
    } catch (e) { }
  }
  if (typeof document !== 'undefined') {
    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', showBadge);
    } else {
      showBadge();
    }
  }

  global.LocalOffline = {
    enabled: true,
    decodeFile: decodeFile,
    analyzeFile: async function (file, opts) {
      var decoded = await decodeFile(file);
      return CORE.analyzeChannels(decoded.channels, decoded.sr, opts || {});
    },
    report: CORE.buildReport,
    TraceRenderer: TraceRenderer,
    renderVideo: renderVideoBlob,
    jobs: jobs,
    version: '1.0.0'
  };
})(typeof window !== 'undefined' ? window : globalThis);
