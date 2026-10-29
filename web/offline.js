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

  /* سبک مشترک رسم (trace_style.js) — در نود برای تست‌ها هم بارگذاری می‌شود */
  if (!global.LocalTraceStyle && typeof require === 'function') {
    try { require('./trace_style.js'); } catch (e) { /* مرورگر: باید با <script> آمده باشد */ }
  }
  if (!global.LocalTraceStyle) {
    console.error('trace_style.js بارگذاری نشده — آن را قبل از offline.js بگذارید');
    return;
  }

  /* ── آفلاین همیشه، مگر در حالت توسعهٔ وب ─────────────────────────────────
     داخل APK صفحه از https://appassets.local باز می‌شود و پل بومی
     (window.NativeApp) هم هست؛ در آن حالت حتی اگر کسی آدرس را با
     ?engine=server دستی عوض کند، موتور آفلاین خاموش نمی‌شود. */
  var params = new URLSearchParams(global.location ? location.search : '');
  var IN_APP = !!global.NativeApp ||
    /^https?:\/\/appassets\.local/i.test(String((global.location && global.location.href) || ''));
  var FORCE_SERVER = !IN_APP && params.get('engine') === 'server';
  if (FORCE_SERVER) {
    global.LocalOffline = { enabled: false };
    return;                                  // مسیر سرور (فقط دمو/توسعهٔ وب)
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
  /* اندازهٔ بومِ خروجی (تصویر/ویدیو)؛ حاشیه‌ها از LocalTraceStyle.layout می‌آید */
  var LAYOUT = { W: 1280, H: 720 };
  /* عرض پنجرهٔ نوار لغزان در ویدیو (ثانیه) */
  var VIDEO_WINDOW_SEC = 15;

  /* ══════════════════════════════════════════════════════════════════════
     TraceRenderer — پوستهٔ نازک روی سبک مشترک (trace_style.js)
     ----------------------------------------------------------------------
     رسم واقعی در LocalTraceStyle است؛ پس تصویر PNG، ویدیو و نمودار داخل اپ
     عیناً یک چیز را نشان می‌دهند (همان نقطهٔ نورانی، همان رنگ‌ها، همان برچسب‌ها).
     ══════════════════════════════════════════════════════════════════════ */
  function TraceRenderer(canvas, times, freqs, notes, opts) {
    opts = opts || {};
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.W = canvas.width;
    this.H = canvas.height;
    this.notes = (notes || []).filter(function (n) { return n && n.f0_hz > 0; });
    /* پنجرهٔ لغزان (ویدیو): ۰ = نمای کامل (تصویر تحلیل) */
    this.windowSec = opts.windowSec > 0 ? opts.windowSec : 0;
    this.tightness = opts.tightness > 0 ? opts.tightness : 1;

    var t = Array.from(times), f = Array.from(freqs);
    var order = t.map(function (_, i) { return i; }).sort(function (a, b) { return t[a] - t[b]; });
    this.times = order.map(function (i) { return t[i]; });
    this.midi = order.map(function (i) { return f[i] > 0 ? CORE.hzToMidi(f[i]) : null; });
    this.duration = this.times.length ? this.times[this.times.length - 1] : 1;
    if (!(this.duration > 0)) this.duration = 1;

    /* بالاترین/پایین‌ترین نت برای راهنماها.
       مهم: از «نسخهٔ پاک‌شده» همان نت‌ها استفاده می‌شود تا نقطه‌های پرتِ
       اکتاوی (که در نمودار هم رسم نمی‌شوند) خط‌چین را چند اکتاو جابه‌جا
       نکنند و ویدیو دقیقاً مثل اپ باشد. اگر گزارش، گسترهٔ خودش را بدهد
       (اپ همین کار را می‌کند) همان اولویت دارد. */
    var list = this.notes;
    if (LocalTraceStyle.cleanNotes) {
      var cl = LocalTraceStyle.cleanNotes(list, this.times, this.midi);
      if (cl && cl.length) list = cl;
    }
    var best = null, worst = null;
    list.forEach(function (n) {
      if (!best || n.f0_hz > best.f0_hz) best = n;
      if (!worst || n.f0_hz < worst.f0_hz) worst = n;
    });
    this.highest = best;
    this.lowest = worst;
    this.range = opts.range || null;
  }

  TraceRenderer.prototype.viewAt = function (t) {
    if (!this.windowSec) return { t0: 0, t1: this.duration, half: 0 };
    var half = this.windowSec / 2;
    var t0 = Math.max(0, Math.min(t - half, this.duration - this.windowSec));
    if (this.duration < this.windowSec) t0 = 0;
    return { t0: t0, t1: t0 + this.windowSec, half: half };
  };

  TraceRenderer.prototype.xOfTimeIn = function (t, view) {
    var lay = LocalTraceStyle.layout(this.W, this.H);
    var usable = this.W - lay.ML - lay.MR;
    var span = Math.max(1e-6, view.t1 - view.t0);
    return lay.ML + ((t - view.t0) / span) * usable;
  };

  TraceRenderer.prototype.xOfTime = function (t) {
    return this.xOfTimeIn(t, { t0: 0, t1: this.duration });
  };

  /* نسخهٔ پاک‌شدهٔ نت‌ها (بدون نقطه‌های پرتِ اکتاوی) — برای شمارش و جدول */
  TraceRenderer.prototype.cleanNoteList = function () {
    var mids = this.midi;
    return LocalTraceStyle.cleanNotes(this.notes, this.times, mids);
  };

  TraceRenderer.prototype.currentMidi = function (t) {
    return LocalTraceStyle.currentMidi({
      times: this.times, mids: this.midi
    }, t);
  };

  /* مشخصات رسم برای سبک مشترک */
  TraceRenderer.prototype._spec = function (t) {
    var view = this.viewAt(t);
    var range = this.range;
    if (!range) {
      var self = this;
      range = {
        highest: self.highest ? { note_name: CORE.midiToName(CORE.hzToMidi(self.highest.f0_hz)),
          f0_hz: self.highest.f0_hz, start: self.highest.start } : null,
        lowest: self.lowest ? { note_name: CORE.midiToName(CORE.hzToMidi(self.lowest.f0_hz)),
          f0_hz: self.lowest.f0_hz, start: self.lowest.start } : null,
        span_semitones: (self.highest && self.lowest)
          ? Math.round(12 * Math.log2(self.highest.f0_hz / self.lowest.f0_hz) * 10) / 10 : null
      };
      this.range = range;
    }
    return {
      W: this.W, H: this.H, dpr: 1,
      times: this.times, mids: this.midi, notes: this.notes,
      range: range,
      duration: this.duration,
      t: Math.max(0, Math.min(t, this.duration)),
      view: view,
      tightness: this.tightness
    };
  };

  TraceRenderer.prototype.draw = function (time) {
    var t = (time == null) ? this.duration : time;
    var spec = this._spec(t);
    var g = this.ctx;
    /* سبک مشترک با کلیپ ناحیهٔ نمودار (هیچ نقطه‌ای بیرون پنجره کشیده نشود) */
    LocalTraceStyle.draw(g, spec);
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
     ۳) ویدیو — دو مسیر، یک خروجی
     ----------------------------------------------------------------------
     مسیر سریع (WebCodecs): فریم‌ها بدون پخش زنده رسم و کدگذاری می‌شوند،
     پس یک ویدیوی ۳ دقیقه‌ای در چند ده ثانیه آماده می‌شود و برچسب زمانی هر
     فریم دقیقاً i/fps است ⇒ صوت و تصویر بدون تأخیر هم‌زمان می‌مانند.
     مسیر پشتیبان (MediaRecorder): همان روش قبلی (ضبط هم‌زمان با پخش)، برای
     دستگاه‌هایی که WebCodecs/AAC ندارند.
     ══════════════════════════════════════════════════════════════════════ */
  var VIDEO = { W: 1280, H: 720, fps: 60, bitrate: 5500000 };
  var VIDEO_HD = { W: 1920, H: 1080, bitrate: 9000000 };

  /* ══════════════════════════════════════════════════════════════════════
     نرخِ بیت: تابعِ «پیکسل در ثانیه»، نه طولِ فایل
     ----------------------------------------------------------------------
     ریشهٔ لگِ خروجی همین‌جا بود: نرخِ بیت بر اساسِ *طولِ صدا* کم می‌شد ولی
     رزولوشن ۱۰۸۰p می‌ماند. فایلِ ۸۸ثانیه‌ای با ۳٫۸ Mbps و ۱۰۸۰p۶۰ کدگذاری
     می‌شد، یعنی ۰٫۰۳۱ بیت بر پیکسل در هر فریم — سه تا چهار برابر کمتر از
     حدِ سالمِ H.264 (~۰٫۰۸). نتیجه: مکروبلوک و ایست‌های ریزِ کدگشا.
     حالا نرخِ بیت فقط از رویِ W×H×fps می‌آید و هیچ‌وقت زیرِ BPP_MIN نمی‌رود.
     ══════════════════════════════════════════════════════════════════════ */
  var BPP_HD = 0.09;       /* هدف برای ۱۰۸۰p (۹٪ بیت بر پیکسل در هر فریم) */
  var BPP_SD = 0.11;       /* هدف برای ۷۲۰p — ریزتر ولی کیفیتِ برابر */
  var BPP_MIN = 0.075;     /* خطِ قرمز: هیچ خروجی زیرِ این نمی‌رود */
  var BR_MIN = 1600000;    /* کفِ مطلق (۷۲۰p۲۴ با bpp=۰٫۰۷۵ هم بالاتر است) */
  var BR_MAX = 20000000;   /* سقفِ مطلق تا حرارت/حافظهٔ گوشی اذیت نشود */
  /* سقفِ حجم/حافظهٔ فایل: کلِ خروجی در حافظه ساخته می‌شود، پس بی‌نهایت
     بزرگ‌شدن جایز نیست. اگر ترکیبِ «رزولوشن × فریم» از این سقف رد شود،
     اول نرخِ فریم و بعد رزولوشن پایین می‌آید — نه کیفیتِ هر فریم. */
  var VIDEO_MAX_BYTES = 180 * 1024 * 1024;      /* حالتِ خودکار */
  var VIDEO_MAX_BYTES_EXPLICIT = 300 * 1024 * 1024;  /* انتخابِ صریحِ کاربر */

  function bppTarget(W, H) {
    return (W * H >= 1920 * 1080) ? BPP_HD : BPP_SD;
  }

  function bitrateFor(W, H, fps) {
    var px = W * H * Math.max(1, fps);
    return Math.round(Math.max(BR_MIN, Math.min(BR_MAX, px * bppTarget(W, H))));
  }

  /* ── نقشهٔ خروجی: (ابعاد، نرخِ فریم، نرخِ بیت) — همه هم‌خوان ─────────────
     ورودی: طولِ صدا، انتخابِ کیفیتِ کاربر، نرخِ فریمِ درخواستی.
     قاعده: نرخِ بیت = تابعِ پیکسل×فریم (نه طولِ فایل)؛ اگر ترکیب از سقفِ
     حافظه رد شود، نرخِ فریم و بعد رزولوشن کم می‌شود ولی bpp هرگز زیرِ
     BPP_MIN نمی‌رود. */
  function videoPlan(durationSec, quality, askedFps) {
    var q = quality || 'auto';
    var dur = Math.max(0.2, durationSec || 0);
    var maxFps = askedFps > 0 ? Math.min(60, askedFps) : 60;
    var explicit = (q === '720p' || q === '1080p');
    var resList;
    if (q === '720p') resList = [VIDEO];
    else if (q === '1080p') resList = [VIDEO_HD];
    else resList = (dur <= 120) ? [VIDEO_HD, VIDEO] : [VIDEO];

    var fpsList = [60, 30, 24].filter(function (f) { return f <= maxFps; });
    if (!fpsList.length) fpsList = [Math.max(12, Math.round(maxFps))];

    function out(W, H, fps, br) {
      return { W: W, H: H, fps: fps, bitrate: Math.round(br), bpp: Math.round(1e4 * br / (W * H * fps)) / 1e4 };
    }

    for (var i = 0; i < resList.length; i++) {
      for (var j = 0; j < fpsList.length; j++) {
        var W = resList[i].W, H = resList[i].H, fps = fpsList[j];
        var br = bitrateFor(W, H, fps);
        /* انتخابِ صریحِ کاربر مقدم است، ولی حتی در آن حالت هم نرخِ فریم
           پایین می‌آید تا فایل از سقفِ حافظهٔ گوشی رد نشود. */
        var cap = explicit ? VIDEO_MAX_BYTES_EXPLICIT : VIDEO_MAX_BYTES;
        if (br * dur / 8 <= cap) return out(W, H, fps, br);
      }
    }
    /* خیلی بلند: پایین‌ترین ترکیب با نرخِ بیتی که در سقفِ حجم جا شود
       (bpp همچنان بالای کفِ کیفیت می‌ماند) */
    var W2 = VIDEO.W, H2 = VIDEO.H, fps2 = fpsList[fpsList.length - 1];
    var capBits = VIDEO_MAX_BYTES * 8;
    var br2 = Math.max(BR_MIN, Math.min(bitrateFor(W2, H2, fps2), Math.floor(capBits / dur)));
    return out(W2, H2, fps2, br2);
  }

  /* اندازهٔ قاب: پیش‌فرض «خودکار» = ۱۰۸۰p برای فایل‌های تا ۲ دقیقه، و ۷۲۰p
     برای بلندتر‌ها. اگر دستگاه کدگذاری ۱۰۸۰p ندهد، خودکار به ۷۲۰p برمی‌گردد. */
  function videoSize(durationSec, quality) {
    var p = videoPlan(durationSec, quality, VIDEO.fps);
    return { W: p.W, H: p.H, bitrate: p.bitrate };
  }

  /* سازگاری با کدِ قدیمی: پروفایل = نرخِ فریم + نرخِ بیتِ همان پیکسل‌ها */
  function videoProfile(durationSec, askedFps, quality) {
    var plan = videoPlan(durationSec, quality, askedFps);
    return { fps: plan.fps, bitrate: plan.bitrate, bpp: plan.bpp };
  }

  function pickVideoMime() {
    var cands = [
      'video/mp4;codecs=avc1.42E01E,mp4a.40.2',
      'video/mp4',
      'video/webm;codecs=vp9,opus',
      'video/webm;codecs=vp8,opus',
      'video/webm'
    ];
    if (!global.MediaRecorder) return '';
    for (var i = 0; i < cands.length; i++) {
      if (MediaRecorder.isTypeSupported(cands[i])) return cands[i];
    }
    return '';
  }

  function webCodecsReady() {
    return !!(global.VideoEncoder && global.AudioEncoder && global.VideoFrame &&
      global.AudioData && global.Mp4Muxer && global.Mp4Muxer.Muxer && global.Mp4Muxer.ArrayBufferTarget);
  }

  /* کدک H.264: از بهترین به سازگارترین */
  async function pickAvcConfig(w, h, fps, bitrate) {
    /* ۱۰۸۰p@60 به سطح ۴٫۲ نیاز دارد؛ ۷۲۰p@60 با ۴٫۰ هم کار می‌کند */
    var cands = (w * h > 1280 * 720)
      ? ['avc1.64002A', 'avc1.4D002A', 'avc1.640033', 'avc1.42002A', 'avc1.640028']
      : ['avc1.640028', 'avc1.4D0028', 'avc1.42001F', 'avc1.42E01E'];
    for (var i = 0; i < cands.length; i++) {
      var cfg = {
        codec: cands[i], width: w, height: h, framerate: fps,
        bitrate: bitrate || VIDEO.bitrate,
        /* 'realtime' ⇒ کدگذار B-frame نمی‌سازد. با B-frame ترتیبِ زمانی
           فایل MP4 می‌شکند؛ همان چیزی که باعث «قفل‌کردن» و طولِ غلط در
           پخش‌کننده‌های گوشی می‌شد. */
        latencyMode: 'realtime',
        /* نرخِ بیت «هدف» است نه سقفِ سفت: صحنهٔ ثابت (نمودار) بیتِ کمتری
           مصرف می‌کند و صحنهٔ شلوغ کیفیتش را نگه می‌دارد. */
        bitrateMode: 'variable',
        hardwareAcceleration: 'prefer-hardware', avc: { format: 'avc' }
      };
      try {
        if (!global.VideoEncoder.isConfigSupported) return cfg;
        var sup = await global.VideoEncoder.isConfigSupported(cfg);
        if (sup && sup.supported) return sup.config || cfg;
      } catch (e) { /* کدک بعدی */ }
    }
    return null;
  }

  async function pickAacConfig(sr) {
    var cands = ['mp4a.40.2', 'mp4a.40.5'];
    for (var i = 0; i < cands.length; i++) {
      var cfg = { codec: cands[i], sampleRate: sr, numberOfChannels: 1, bitrate: 128000 };
      try {
        if (!global.AudioEncoder.isConfigSupported) return cfg;
        var sup = await global.AudioEncoder.isConfigSupported(cfg);
        if (sup && sup.supported) return sup.config || cfg;
      } catch (e) { /* کدک بعدی */ }
    }
    return null;
  }

  function yieldTick() {
    return new Promise(function (r) { setTimeout(r, 0); });
  }

  /* ══════════════════════════════════════════════════════════════════════
     اصلاح «طولِ فایل» در هدر خروجی
     ----------------------------------------------------------------------
     بعضی پخش‌کننده‌های گوشی طول را فقط از هدر می‌خوانند. اگر هدر ناقص
     باشد، گالری عددی مثل «۴ ثانیه» نشان می‌دهد و پخش را نصفه رها می‌کند.
     این‌جا طولِ درست را در mvhd / tkhd / mdhd (MP4) و Info/Duration (WebM)
     می‌نویسیم تا برچسب و رفتارِ پخش‌کننده با واقعیت یکی باشد.
     ══════════════════════════════════════════════════════════════════════ */
  function fourcc(dv, off) {
    return String.fromCharCode(dv.getUint8(off), dv.getUint8(off + 1),
      dv.getUint8(off + 2), dv.getUint8(off + 3));
  }

  /* پیمایش جعبه‌های MP4 (هر عمق) */
  function walkBoxes(dv, start, end, visit, depth) {
    var off = start;
    while (off + 8 <= end) {
      var size = dv.getUint32(off);
      var type = fourcc(dv, off + 4);
      var hdr = 8;
      if (size === 1) {
        if (off + 16 > end) break;
        size = Number(dv.getBigUint64(off + 8));
        hdr = 16;
      } else if (size === 0) {
        size = end - off;
      }
      if (size < hdr || off + size > end) break;
      visit(type, off, size, hdr);
      off += size;
    }
  }

  function toDataView(buf) {
    if (!buf) return null;
    if (typeof ArrayBuffer !== 'undefined' && buf instanceof ArrayBuffer) return new DataView(buf);
    if (buf.buffer) return new DataView(buf.buffer, buf.byteOffset || 0, buf.byteLength);
    return null;
  }

  /* ══════════════════════════════════════════════════════════════════════
     خواندنِ «هندسهٔ زمانی» فایلِ MP4
     ----------------------------------------------------------------------
     نکتهٔ کلیدی که باعثِ «۵ ثانیه در گالری» می‌شد:
       • mvhd.timescale  = تایم‌اسکیلِ فیلم
       • tkhd.duration   با *همان* تایم‌اسکیلِ فیلم سنجیده می‌شود؛
                         tkhd اصلاً فیلدِ timescale ندارد
       • mdhd.timescale/duration مالِ خودِ تراک است
     کدِ قبلی برای tkhd هم «تایم‌اسکیل» را از offset ۱۲ می‌خواند که در
     tkhd همان track_ID است ⇒ طول در هدر به چند صدمِ ثانیه تبدیل می‌شد.
     ══════════════════════════════════════════════════════════════════════ */
  function mp4Timeline(buf) {
    var dv = toDataView(buf);
    var info = {
      ok: false, byteLength: dv ? dv.byteLength : 0,
      movieTimescale: 0, movieDuration: 0,
      fragmented: false, moofCount: 0, mehdDuration: null,
      tracks: []
    };
    if (!dv) return info;
    walkBoxes(dv, 0, dv.byteLength, function (type, off, size, hdr) {
      if (type === 'moof') { info.moofCount++; info.fragmented = true; return; }
      if (type !== 'moov') return;
      walkBoxes(dv, off + hdr, off + size, function (t2, o2, s2, h2) {
        var b = o2 + h2, v = dv.getUint8(b);
        if (t2 === 'mvhd') {
          info.movieTimescale = dv.getUint32(b + (v === 1 ? 20 : 12));
          info.movieDuration = v === 1
            ? Number(dv.getBigUint64(b + 24)) : dv.getUint32(b + 16);
        } else if (t2 === 'mvex') {
          walkBoxes(dv, b, o2 + s2, function (t3, o3, s3, h3) {
            if (t3 !== 'mehd') return;
            var b3 = o3 + h3, v3 = dv.getUint8(b3);
            info.mehdDuration = v3 === 1
              ? Number(dv.getBigUint64(b3 + 4)) : dv.getUint32(b3 + 4);
          });
        } else if (t2 === 'trak') {
          var trk = {
            handler: '', tkhdDuration: 0, mdhdTimescale: 0, mdhdDuration: 0,
            samples: 0, totalTicks: 0, ctts: false, keyframes: [], sttsCount: 0
          };
          walkBoxes(dv, b, o2 + s2, function (t3, o3, s3, h3) {
            var b3 = o3 + h3, v3 = dv.getUint8(b3);
            if (t3 === 'tkhd') {
              trk.tkhdDuration = v3 === 1
                ? Number(dv.getBigUint64(b3 + 28)) : dv.getUint32(b3 + 20);
            } else if (t3 === 'mdia') {
              walkBoxes(dv, b3, o3 + s3, function (t4, o4, s4, h4) {
                var b4 = o4 + h4, v4 = dv.getUint8(b4);
                if (t4 === 'mdhd') {
                  trk.mdhdTimescale = dv.getUint32(b4 + (v4 === 1 ? 20 : 12));
                  trk.mdhdDuration = v4 === 1
                    ? Number(dv.getBigUint64(b4 + 24)) : dv.getUint32(b4 + 16);
                } else if (t4 === 'hdlr') {
                  trk.handler = fourcc(dv, b4 + 8);
                } else if (t4 === 'minf') {
                  walkBoxes(dv, b4, o4 + s4, function (t5, o5, s5, h5) {
                    if (t5 !== 'stbl') return;
                    walkBoxes(dv, o5 + h5, o5 + s5, function (t6, o6, s6, h6) {
                      var b6 = o6 + h6;
                      if (t6 === 'stts') {
                        var n = dv.getUint32(b6 + 4);
                        trk.sttsCount = n;
                        for (var i = 0; i < n; i++) {
                          var cnt = dv.getUint32(b6 + 8 + i * 8);
                          var delta = dv.getUint32(b6 + 12 + i * 8);
                          trk.samples += cnt;
                          trk.totalTicks += cnt * delta;
                        }
                      } else if (t6 === 'ctts') {
                        trk.ctts = true;
                      } else if (t6 === 'stss') {
                        var k = dv.getUint32(b6 + 4);
                        for (var j = 0; j < k; j++) trk.keyframes.push(dv.getUint32(b6 + 8 + j * 4));
                      }
                    });
                  });
                }
              });
            }
          });
          if (trk.mdhdTimescale || trk.samples) info.tracks.push(trk);
        }
      });
    });
    info.ok = info.movieTimescale > 0 && info.tracks.length > 0;
    return info;
  }

  /* ── نوشتنِ طول در هدر، با تایم‌اسکیلِ درستِ هر جعبه ──────────────────── */
  function patchMp4Duration(buffer, seconds) {
    if (!(seconds > 0) || !buffer) return buffer;
    var copy = buffer.slice(0);
    var dv = toDataView(copy);
    var tl = mp4Timeline(copy);
    if (!dv || !tl.ok || !tl.movieTimescale) return buffer;
    var tsMovie = tl.movieTimescale;
    var patched = 0;
    function put(boxBodyOff, ver, tsOffset, durOffset, fixedTs) {
      var ts = fixedTs || dv.getUint32(boxBodyOff + tsOffset);
      if (!ts) return;
      var units = Math.round(seconds * ts);
      if (ver === 1) {
        if (units > 0xFFFFFFFF) dv.setBigUint64(boxBodyOff + durOffset, BigInt(units));
        else dv.setUint32(boxBodyOff + durOffset, units);
      } else {
        dv.setUint32(boxBodyOff + durOffset, Math.min(0xFFFFFFFF, units));
      }
      patched++;
    }
    walkBoxes(dv, 0, dv.byteLength, function (type, off, size, hdr) {
      if (type !== 'moov') return;
      walkBoxes(dv, off + hdr, off + size, function (t2, o2, s2, h2) {
        var b = o2 + h2, v = dv.getUint8(b);
        if (t2 === 'mvhd') {
          put(b, v, v === 1 ? 20 : 12, v === 1 ? 24 : 16, 0);
        } else if (t2 === 'mvex') {
          walkBoxes(dv, b, o2 + s2, function (t3, o3, s3, h3) {
            if (t3 !== 'mehd') return;
            var b3 = o3 + h3, v3 = dv.getUint8(b3);
            put(b3, v3, 0, v3 === 1 ? 8 : 4, tsMovie);   /* mehd: بدونِ ts */
          });
        } else if (t2 === 'trak') {
          walkBoxes(dv, b, o2 + s2, function (t3, o3, s3, h3) {
            var b3 = o3 + h3, v3 = dv.getUint8(b3);
            if (t3 === 'tkhd') {
              /* tkhd: طول با تایم‌اسکیلِ فیلم — هیچ فیلدِ ts در خودش نیست */
              put(b3, v3, 0, v3 === 1 ? 28 : 20, tsMovie);
            } else if (t3 === 'mdia') {
              walkBoxes(dv, b3, o3 + s3, function (t4, o4, s4, h4) {
                if (t4 !== 'mdhd') return;
                var b4 = o4 + h4, v4 = dv.getUint8(b4);
                put(b4, v4, v4 === 1 ? 20 : 12, v4 === 1 ? 24 : 16, 0);
              });
            }
          });
        }
      });
    });
    return patched ? copy : buffer;
  }

  /* ══════════════════════════════════════════════════════════════════════
     MP4 تکه‌ای (fragmented) — همان چیزی که MediaRecorder می‌سازد
     ----------------------------------------------------------------------
     در فایلِ تکه‌ای، `mvhd` و `mdhd` داخلِ moov طولِ **صفر** دارند و طولِ
     واقعی در جدولِ قطعه‌ها (moof) است. اندروید (MediaScanner و
     MediaMetadataRetriever) قطعه‌ها را جمع نمی‌زند و فقط mvhd/mdhd را
     می‌خواند ⇒ گالری عددِ بی‌معنی نشان می‌دهد (همان «۵ ثانیه»).
     راه‌حل: نوشتنِ طول در mvhd/tkhd/mdhd و افزودنِ جعبهٔ `mehd`
     (fragment_duration) به `mvex` اگر نبود.
     امنیت: اگر فایل از آفستِ مطلق استفاده کند (base_data_offset در tfhd یا
     stco/co64 غیرخالی)، درجِ بایت‌ها آفست‌ها را خراب می‌کند ⇒ دست نمی‌زنیم.
     ══════════════════════════════════════════════════════════════════════ */
  function mp4HasAbsoluteOffsets(dv, start, end) {
    var risky = false;
    walkBoxes(dv, start, end, function (t, off, size, hdr) {
      var b = off + hdr;
      if (t === 'tfhd') {
        var flags = dv.getUint32(b) & 0xFFFFFF;
        if (flags & 0x000001) risky = true;         /* base-data-offset-present */
      } else if (t === 'stco' || t === 'co64') {
        if (dv.getUint32(b + 4) > 0) risky = true;  /* آفستِ مطلقِ واقعی */
      } else if (t === 'moof' || t === 'traf' || t === 'moov' || t === 'trak') {
        if (t !== 'moof' && t !== 'traf') return;
        walkBoxes(dv, b, off + size, function (t2, o2, s2, h2) {
          var b2 = o2 + h2;
          if (t2 === 'tfhd') {
            var f2 = dv.getUint32(b2) & 0xFFFFFF;
            if (f2 & 0x000001) risky = true;
          } else if (t2 === 'trun' || t2 === 'traf') {
            walkBoxes(dv, b2, o2 + s2, function (t3, o3, s3, h3) {
              if (t3 === 'tfhd') {
                var f3 = dv.getUint32(o3 + h3) & 0xFFFFFF;
                if (f3 & 0x000001) risky = true;
              }
            });
          }
        });
      }
    });
    return risky;
  }

  /* درجِ `mehd` در mvex و اصلاحِ طول‌ها؛ فقط برای فایلِ تکه‌ایِ بی‌خطر */
  function ensureMp4FragmentDuration(buffer, seconds) {
    var src = buffer;
    var dv = toDataView(src);
    var tl = mp4Timeline(src);
    if (!dv || !tl.ok || !tl.fragmented || !(seconds > 0)) return src;

    /* ۱) طول‌ها را در mvhd/tkhd/mdhd بنویس (اندروید همین را می‌خواند) */
    var out = patchMp4Duration(src, seconds);

    /* ۲) اگر mehd هست، همان‌جا نوشته شد؛ اگر نیست، درجش کن */
    var tl2 = mp4Timeline(out);
    if (tl2.mehdDuration != null && tl2.mehdDuration > 0) return out;
    if (mp4HasAbsoluteOffsets(toDataView(out), 0, out.byteLength)) return out;

    var dv2 = toDataView(out);
    var pos = null;
    walkBoxes(dv2, 0, dv2.byteLength, function (type, off, size, hdr) {
      if (type !== 'moov' || pos) return;
      walkBoxes(dv2, off + hdr, off + size, function (t2, o2, s2, h2) {
        if (t2 === 'mvex' && !pos) {
          pos = { moovOff: off, moovHdr: hdr, moovSize: size, mvexOff: o2, mvexHdr: h2, mvexSize: s2 };
        }
      });
    });
    if (!pos) return out;
    if (pos.moovSize === 1 || pos.mvexSize === 1) return out;   /* largesize: دست نزن */

    var ts = tl2.movieTimescale || tl.movieTimescale;
    var mehd = new Uint8Array(16);
    var mdv = new DataView(mehd.buffer);
    mdv.setUint32(0, 16);                 /* size */
    mehd[4] = 0x6D; mehd[5] = 0x65; mehd[6] = 0x68; mehd[7] = 0x64;  /* 'mehd' */
    mdv.setUint8(8, 0);                   /* version 0 */
    mdv.setUint32(9, 0);                  /* flags */
    mdv.setUint32(12, Math.min(0xFFFFFFFF, Math.round(seconds * ts)));

    var insertAt = pos.mvexOff + pos.mvexHdr;
    var res = new Uint8Array(out.byteLength + mehd.length);
    res.set(new Uint8Array(out, 0, insertAt), 0);
    res.set(mehd, insertAt);
    res.set(new Uint8Array(out, insertAt), insertAt + mehd.length);

    var rdv = new DataView(res.buffer);
    /* اندازهٔ mvex و moov هر دو ۱۶ بایت بزرگ‌تر می‌شوند */
    rdv.setUint32(pos.mvexOff, pos.mvexSize + mehd.length);
    rdv.setUint32(pos.moovOff, pos.moovSize + mehd.length);
    return res.buffer;
  }

  /* تعمیرِ یکپارچه: تکه‌ای ⇒ mehd + هدرها، معمولی ⇒ هدرها */
  function repairMp4Duration(buffer, seconds) {
    var tl = mp4Timeline(buffer);
    if (tl.fragmented) return ensureMp4FragmentDuration(buffer, seconds);
    return patchMp4Duration(buffer, seconds);
  }

  /* ══════════════════════════════════════════════════════════════════════
     بازبینیِ خودکارِ فایلِ ساخته‌شده — «درِ خروجی» ویدیو
     ----------------------------------------------------------------------
     هر خروجی قبل از تحویل از این گیت می‌گذرد؛ اگر حتی یک شرط نقض شود،
     مشکل به‌صورتِ فهرست برمی‌گردد (و در تست/اپ دیده می‌شود) به‌جای اینکه
     کاربر در گالری کشفش کند.
       • طولِ mvhd / tkhd / mdhd == طولِ واقعی
       • تعدادِ نمونه‌های تصویر == تعدادِ فریمِ رسم‌شده (فریمِ گم‌شده = لگ)
       • نبودِ ctts (B-frame) ⇒ ترتیبِ زمانی سالم در پخش‌کننده‌های گوشی
       • فاصلهٔ فریم‌های کلیدی ≤ سقف (پرشِ نرم، بدونِ وقفهٔ ۲ثانیه‌ای)
     ══════════════════════════════════════════════════════════════════════ */
  /* ══════════════════════════════════════════════════════════════════════
     اندازه‌گیریِ زمان‌بندیِ فریم‌ها از خودِ فایل (عدد، نه ادعا)
     ----------------------------------------------------------------------
     اگر جدولِ stts نامنظم باشد (VFR) پخش‌کننده برای هر فریم منتظرِ زمانِ
     خودش می‌ماند ⇒ پرش/توقف. این تابع کمینه/بیشینه/میانگینِ گامِ فریم‌ها
     و «لرزش» (اختلافِ بیشینه و کمینه) را برمی‌گرداند.
     ══════════════════════════════════════════════════════════════════════ */
  function frameTimingStats(buf) {
    var dv = toDataView(buf);
    if (!dv) return null;
    var out = null;
    walkBoxes(dv, 0, dv.byteLength, function (t, off, size, hdr) {
      if (t !== 'moov' || out) return;
      walkBoxes(dv, off + hdr, off + size, function (t2, o2, s2, h2) {
        if (t2 !== 'trak' || out) return;
        var handler = '', ts = 0, stts = -1;
        walkBoxes(dv, o2 + h2, o2 + s2, function (t3, o3, s3, h3) {
          if (t3 !== 'mdia') return;
          walkBoxes(dv, o3 + h3, o3 + s3, function (t4, o4, s4, h4) {
            var b4 = o4 + h4;
            if (t4 === 'mdhd') ts = dv.getUint32(b4 + (dv.getUint8(b4) === 1 ? 20 : 12));
            else if (t4 === 'hdlr') handler = fourcc(dv, b4 + 8);
            else if (t4 === 'minf') {
              walkBoxes(dv, b4, o4 + s4, function (t5, o5, s5, h5) {
                if (t5 !== 'stbl') return;
                walkBoxes(dv, o5 + h5, o5 + s5, function (t6, o6, s6, h6) {
                  if (t6 === 'stts') stts = o6 + h6;
                });
              });
            }
          });
        });
        if (handler !== 'vide' || stts < 0 || !ts) return;
        var n = dv.getUint32(stts + 4);
        var frames = 0, minD = Infinity, maxD = 0, sum = 0;
        for (var i = 0; i < n && i < 8192; i++) {
          var cnt = dv.getUint32(stts + 8 + i * 8);
          var d = dv.getUint32(stts + 12 + i * 8);
          frames += cnt; sum += cnt * d;
          if (d < minD) minD = d;
          if (d > maxD) maxD = d;
        }
        if (!frames) return;
        out = {
          ok: true, frames: frames, timescale: ts,
          minMs: +(minD * 1000 / ts).toFixed(1),
          maxMs: +(maxD * 1000 / ts).toFixed(1),
          meanMs: +(sum / frames * 1000 / ts).toFixed(1)
        };
        out.jitterMs = +(out.maxMs - out.minMs).toFixed(1);
        out.constant = out.jitterMs <= 0.6;
        out.cfr = out.constant;
      });
    });
    return out;
  }

  function verifyMp4(buffer, expect) {
    expect = expect || {};
    var tl = mp4Timeline(buffer);
    var problems = [];
    var res = {
      ok: false, problems: problems, parsed: tl.ok,
      movie_duration_sec: null, tracks: [], frames: null, dropped_frames: null,
      max_key_gap_sec: null, has_b_frames: null, bpp: expect.bpp == null ? null : expect.bpp
    };
    if (!tl.ok) {
      problems.push('هدرِ MP4 خوانده نشد');
      return res;
    }
    var fps = expect.fps > 0 ? expect.fps : 0;
    var tol = expect.toleranceSec != null ? expect.toleranceSec : (fps ? (1.5 / fps + 0.02) : 0.25);
    /* در فایلِ تکه‌ای، نمونه‌ها داخلِ moof هستند و moov صفر می‌دهد ⇒
       شمارشِ فریم از moov معنا ندارد؛ مرجع، mehd است. */
    res.fragmented = !!tl.fragmented;
    function sec(d, ts) { return ts ? d / ts : 0; }

    res.movie_duration_sec = Math.round(sec(tl.movieDuration, tl.movieTimescale) * 1000) / 1000;
    res.mehd_duration_sec = tl.mehdDuration == null ? null
      : Math.round(sec(tl.mehdDuration, tl.movieTimescale) * 1000) / 1000;
    var want = expect.durationSec;
    if (want > 0) {
      if (Math.abs(res.movie_duration_sec - want) > tol)
        problems.push('طولِ mvhd=' + res.movie_duration_sec + 's ≠ ' + Math.round(want * 1000) / 1000 + 's');
      if (res.mehd_duration_sec != null && Math.abs(res.mehd_duration_sec - want) > tol)
        problems.push('طولِ mehd=' + res.mehd_duration_sec + 's');
    }
    tl.tracks.forEach(function (t) {
      var row = {
        handler: t.handler,
        tkhd_sec: Math.round(sec(t.tkhdDuration, tl.movieTimescale) * 1000) / 1000,
        mdhd_sec: Math.round(sec(t.mdhdDuration, t.mdhdTimescale) * 1000) / 1000,
        samples: t.samples, b_frames: t.ctts, keyframes: t.keyframes.length
      };
      res.tracks.push(row);
      if (want > 0) {
        if (Math.abs(row.mdhd_sec - want) > tol)
          problems.push('طولِ mdhd(' + t.handler + ')=' + row.mdhd_sec + 's');
        /* tkhd در پخش‌کننده‌های گوشی مرجع است ⇒ باید درست باشد */
        if (Math.abs(row.tkhd_sec - want) > tol)
          problems.push('طولِ tkhd(' + t.handler + ')=' + row.tkhd_sec + 's');
      }
      if (t.ctts) problems.push('ctts/B-frame در تراکِ ' + t.handler);
      if (t.handler === 'vide' && !tl.fragmented) {
        res.frames = t.samples;
        if (expect.frames > 0) {
          res.dropped_frames = Math.max(0, expect.frames - t.samples);
          if (t.samples < expect.frames)
            problems.push('فریمِ گم‌شده: ' + t.samples + ' از ' + expect.frames);
        }
        /* فاصلهٔ فریم‌های کلیدی از جدولِ stss */
        if (t.keyframes.length > 1 && t.samples > 0) {
          var avgTicks = t.totalTicks / t.samples;
          var maxGap = 0;
          for (var i = 1; i < t.keyframes.length; i++)
            maxGap = Math.max(maxGap, t.keyframes[i] - t.keyframes[i - 1]);
          res.max_key_gap_sec = Math.round((maxGap * avgTicks / t.mdhdTimescale) * 1000) / 1000;
          var cap = expect.maxKeyGapSec > 0 ? expect.maxKeyGapSec : 2.0;
          if (res.max_key_gap_sec > cap)
            problems.push('فاصلهٔ فریمِ کلیدی ' + res.max_key_gap_sec + 's > ' + cap + 's');
        }
      }
    });
    res.has_b_frames = res.tracks.some(function (r) { return r.b_frames; });
    if (tl.fragmented) {
      if (res.mehd_duration_sec == null) problems.push('فایلِ تکه‌ای بدونِ mehd (گالری طول را نمی‌خواند)');
      else if (want > 0 && Math.abs(res.mehd_duration_sec - want) > tol)
        problems.push('طولِ mehd=' + res.mehd_duration_sec + 's');
      if (!(res.movie_duration_sec > 0) && want > 0)
        problems.push('mvhd طول ندارد (گالری طول را نمی‌خواند)');
    }
    res.ok = problems.length === 0;
    return res;
  }

  /* ── WebM (خروجی MediaRecorder): نوشتن Duration در Segment/Info ──
     اگر عنصر Duration باشد بازنویسی می‌شود، وگرنه به‌صورت معتبر درست
     می‌شود (با اصلاح اندازهٔ Info). */
  var ID_SEGMENT = 0x18538067, ID_INFO = 0x1549A966,
    ID_DURATION = 0x4489, ID_VOID = 0xEC;

  function readVint(b, off, keepMarker) {
    var first = b[off];
    if (first === undefined) return null;
    var len = 1, mask = 0x80;
    while (len <= 8 && !(first & mask)) { mask >>= 1; len++; }
    if (len > 8) return null;
    var value = keepMarker ? first : (first & (mask - 1));
    var unknown = (first & (mask - 1)) === mask - 1;
    for (var i = 1; i < len; i++) {
      var v = b[off + i];
      if (v === undefined) return null;
      if (unknown && v !== 0xFF) unknown = false;
      value = value * 256 + v;
    }
    return { value: value, len: len, unknown: unknown };
  }

  function writeVint(b, off, value) {
    /* کدگذاری کوتاه‌ترین طولِ ممکن (بدون all-ones) */
    var len = 1;
    while (value >= Math.pow(2, 7 * len) - 1 && len < 8) len++;
    for (var i = len - 1; i >= 0; i--) { b[off + i] = value & 0xFF; value = Math.floor(value / 256); }
    b[off] |= (0x80 >> (len - 1));
    return len;
  }

  function readId(b, off) {
    var first = b[off];
    if (first === undefined) return null;
    var len = 1, mask = 0x80;
    while (len <= 4 && !(first & mask)) { mask >>= 1; len++; }
    if (len > 4) return null;
    var id = first;
    for (var i = 1; i < len; i++) id = id * 256 + b[off + i];
    return { id: id, len: len };
  }

  function patchWebmDuration(buffer, seconds) {
    if (!(seconds > 0) || !buffer) return buffer;
    var src = new Uint8Array(buffer);
    var out = new Uint8Array(src);                     /* نسخهٔ دست‌کاری‌شدنی */
    var i = 0, id, size;
    function scan(off, end, wantId) {
      while (off < end) {
        id = readId(out, off);
        if (!id) return null;
        var s = readVint(out, off + id.len);
        if (!s) return null;
        var bodyOff = off + id.len + s.len;
        var bodyEnd = s.unknown ? end : bodyOff + s.value;
        if (id.id === wantId) return { idOff: off, bodyOff: bodyOff, bodyEnd: Math.min(bodyEnd, end), sizeOff: off + id.len, sizeLen: s.len, unknown: s.unknown, headerLen: id.len + s.len };
        off = bodyEnd;
      }
      return null;
    }
    var seg = scan(0, out.length, ID_SEGMENT);
    if (!seg) return buffer;
    var info = scan(seg.bodyOff, seg.bodyEnd, ID_INFO);
    if (!info) return buffer;
    var dur = scan(info.bodyOff, info.bodyEnd, ID_DURATION);
    if (dur) {
      /* Duration = float ۸ بایتی (یا ۴ بایتی) */
      var dlen = dur.bodyEnd - dur.bodyOff;
      var dv2 = new DataView(out.buffer);
      if (dlen === 8) dv2.setFloat64(dur.bodyOff, seconds);
      else if (dlen === 4) dv2.setFloat32(dur.bodyOff, seconds);
      else return buffer;
      return out.buffer;
    }
    /* نبود ⇒ درست کردن: ۰x۴۴۸۹ + ۸ + float64 در انتهای Info */
    var elem = new Uint8Array(11);
    elem[0] = 0x44; elem[1] = 0x89;
    elem[2] = 0x88;                                    /* اندازه = ۸ */
    new DataView(elem.buffer).setFloat64(3, seconds);
    var need = elem.length;
    var infoEnd = info.bodyEnd;
    var newLen = out.length + need;
    var res = new Uint8Array(newLen);
    res.set(out.subarray(0, infoEnd), 0);
    res.set(elem, infoEnd);
    res.set(out.subarray(infoEnd), infoEnd + need);
    /* اندازهٔ Info را اصلاح کن (اگر نامعلوم نبود) */
    if (!info.unknown) {
      var newSize = info.bodyEnd - info.bodyOff + need;
      if (info.sizeLen >= 8) {
        res[info.sizeOff] = 0x01;                      /* هشت‌بایتی */
        for (var k = 0; k < 7; k++) res[info.sizeOff + 1 + k] = 0;
        writeVint(res, info.sizeOff, newSize);
      } else {
        writeVint(res, info.sizeOff, newSize);
      }
    }
    return res.buffer;
  }

  /* طولِ واقعیِ فایلی که ساختیم، از نگاه خود پخش‌کننده */
  function probeDuration(blob, timeoutMs) {
    return new Promise(function (resolve) {
      var v = null;
      try { v = document.createElement('video'); } catch (e) { v = null; }
      /* فقط جایی که یک <video> واقعی و createObjectURL هست (مرورگر/WebView) */
      if (!v || typeof v.load !== 'function' || typeof v.addEventListener !== 'function' ||
        !global.URL || typeof URL.createObjectURL !== 'function') {
        resolve(null); return;
      }
      var url = URL.createObjectURL(blob);
      var done = false;
      function finish(val) {
        if (done) return;
        done = true;
        try { v.removeAttribute('src'); v.load(); } catch (e) { }
        try { URL.revokeObjectURL(url); } catch (e) { }
        resolve(val);
      }
      v.preload = 'metadata';
      v.muted = true;
      v.onloadedmetadata = function () {
        var d = v.duration;
        finish ((isFinite(d) && d > 0) ? d : null);
      };
      v.onerror = function () { finish(null); };
      setTimeout(function () { finish(null); }, timeoutMs || 4000);
      v.src = url;
    });
  }

  /* تخلیهٔ صفِ کدگذار: هیچ‌وقت نگذاریم صف شلوغ شود.
     چرا؟ کدگذارِ H.264 در حالتِ realtime وقتی صف پُر شود برای «عقب نماندن»
     فریم می‌اندازد؛ همان چیزی که در خروجی به‌شکلِ «لگ» دیده می‌شد.
     با نگه‌داشتنِ صف روی ۱ فریم، هیچ فریمی قابلِ‌انداختن نیست. */
  function drainEncoder(enc, maxQ) {
    return new Promise(function (resolve) {
      if (!enc || !(enc.encodeQueueSize > maxQ)) { resolve(true); return; }
      var done = false;
      function tick() {
        if (done) return;
        if (!enc || !(enc.encodeQueueSize > maxQ)) { done = true; resolve(true); return; }
        setTimeout(tick, 0);
      }
      tick();
    });
  }

  async function renderVideoFast(pitch, notes, wav, sr, onProgress, opts) {
    var trace = pitch.trace_freqs || (CORE.strictTraceFreqs
      ? CORE.strictTraceFreqs(pitch.times, pitch.freqs, notes) : pitch.freqs);
    var duration = Math.max(
      (pitch.times && pitch.times.length) ? pitch.times[pitch.times.length - 1] : 1, 0.2);
    var canvas = document.createElement('canvas');
    function build(W, H) {
      canvas.width = W; canvas.height = H;
      return new TraceRenderer(canvas, pitch.times, trace, notes, {
        windowSec: opts.windowSec || 0,
        tightness: opts.tightness || 1
      });
    }
    var plan = videoPlan(duration, opts.quality, opts.fps);
    var W = plan.W, H = plan.H, fps = plan.fps, bitrate = plan.bitrate;
    var renderer = build(W, H);
    duration = Math.max(renderer.duration, 0.2);
    var totalFrames = Math.max(1, Math.round(duration * fps));
    var frameUs = Math.round(1e6 / fps);
    var keyEvery = Math.max(1, Math.round(fps));        /* فریمِ کلیدی هر ۱ ثانیه */

    var vcfg = await pickAvcConfig(W, H, fps, bitrate);
    if (!vcfg && W !== VIDEO.W) {                 /* دستگاه ۱۰۸۰p نداد ⇒ ۷۲۰p */
      W = VIDEO.W; H = VIDEO.H;
      plan = videoPlan(duration, '720p', fps);
      bitrate = plan.bitrate;
      renderer = build(W, H);
      vcfg = await pickAvcConfig(W, H, fps, bitrate);
    }
    if (!vcfg) throw new Error('کدک H.264 در این دستگاه نیست');
    var acfg = await pickAacConfig(sr);
    if (!acfg) throw new Error('کدک AAC در این دستگاه نیست');

    var vChunks = [], aChunks = [], encError = null;
    function takeChunk(list, chunk, meta, fallbackDur) {
      var data = new Uint8Array(chunk.byteLength);
      try { chunk.copyTo(data); } catch (e) { data = null; }
      if (!data) return;
      var dur = chunk.duration;
      if (!(dur > 0)) dur = fallbackDur;
      list.push({
        data: data, type: chunk.type || 'delta',
        ts: chunk.timestamp || 0, dur: dur, meta: meta || null
      });
    }

    /* ── مرحلهٔ ۱: تصویر ─────────────────────────────────────────────────
       هر فریم یک‌بار رسم و با برچسبِ زمانیِ دقیقِ i/fps کدگذاری می‌شود.
       بعد از هر فریم صبر می‌کنیم تا صف به ≤۱ برسد ⇒ صفر فریمِ گم‌شده. */
    async function encodeVideo(cfg) {
      vChunks = []; encError = null;
      var venc = new global.VideoEncoder({
        output: function (chunk, meta) { takeChunk(vChunks, chunk, meta, frameUs); },
        error: function (e) { encError = e; }
      });
      venc.configure(cfg);
      for (var i = 0; i < totalFrames; i++) {
        if (encError) throw encError;
        renderer.draw(Math.min(i / fps, duration));
        var vf = new global.VideoFrame(canvas, {
          timestamp: Math.round((i / fps) * 1e6),
          duration: frameUs
        });
        venc.encode(vf, { keyFrame: (i % keyEvery === 0) });
        vf.close();
        if (venc.encodeQueueSize > 1) await drainEncoder(venc, 1);
        if ((i & 7) === 0) {
          if (onProgress) onProgress(0.04 + 0.74 * (i / totalFrames));
          await yieldTick();
        }
      }
      await venc.flush();
      if (encError) throw encError;
      try { venc.close(); } catch (e) { }
      return vChunks.length;
    }

    /* اولین تلاش با تنظیمِ ترجیحی؛ اگر کدگذار حتی یک فریم انداخت،
       یک‌بار با کدگذاریِ نرم‌افزاری (که فریم نمی‌اندازد) دوباره می‌سازیم. */
    var made = await encodeVideo(vcfg);
    if (made < totalFrames) {
      var swCfg = {};
      for (var key in vcfg) if (Object.prototype.hasOwnProperty.call(vcfg, key)) swCfg[key] = vcfg[key];
      swCfg.hardwareAcceleration = 'prefer-software';
      made = await encodeVideo(swCfg);
    }
    if (made < totalFrames)
      throw new Error('کدگذار فریم انداخت: ' + made + ' از ' + totalFrames);
    /* ترتیبِ زمانی: چانک‌ها باید به همان ترتیبِ *رمزگشایی* بمانند؛ اگر
       timestampها صعودی نباشند یعنی کدگذار B-frame ساخته. در آن حالت
       مرتب‌سازی ممنوع است (ترتیبِ رمزگشایی به‌هم می‌ریزد)؛ فقط پرچم می‌زنیم
       تا گیتِ خروجی (ستونِ ctts) هشدار بدهد. با latencyMode:'realtime'
       این حالت نباید پیش بیاید. */
    var reordered = false;
    for (var ci = 1; ci < vChunks.length; ci++) {
      if (vChunks[ci].ts < vChunks[ci - 1].ts) { reordered = true; break; }
    }

    /* ── مرحلهٔ ۲: صدا (همان طول، بی‌درز) ───────────────────────────── */
    var CHUNK = 1024;
    var aenc = new global.AudioEncoder({
      output: function (chunk, meta) { takeChunk(aChunks, chunk, meta, Math.round(1024 / sr * 1e6)); },
      error: function (e) { encError = e; }
    });
    aenc.configure(acfg);
    for (var off = 0; off < wav.length; off += CHUNK) {
      if (encError) throw encError;
      var n = Math.min(CHUNK, wav.length - off);
      var data = new Float32Array(CHUNK);              /* صفر-پُر کردن آخرین تکه */
      data.set(wav.subarray(off, off + n));
      var ad = new global.AudioData({
        format: 'f32', sampleRate: sr, numberOfFrames: CHUNK, numberOfChannels: 1,
        timestamp: Math.round((off / sr) * 1e6), data: data
      });
      aenc.encode(ad);
      ad.close();
      if (aenc.encodeQueueSize > 2) await drainEncoder(aenc, 2);
      if ((off % (CHUNK * 96)) === 0) {
        if (onProgress) onProgress(0.80 + 0.15 * (off / wav.length));
        await yieldTick();
      }
    }
    await aenc.flush();
    if (encError) throw encError;
    try { aenc.close(); } catch (e) { }

    /* ── مرحلهٔ ۳: نوشتن در MP4 با طولِ صریحِ هر نمونه ── */
    var muxer = new global.Mp4Muxer.Muxer({
      target: new global.Mp4Muxer.ArrayBufferTarget(),
      video: { codec: 'avc', width: W, height: H, frameRate: fps },
      audio: { codec: 'aac', sampleRate: sr, numberOfChannels: 1 },
      fastStart: 'in-memory'
    });
    var endUs = Math.round((totalFrames / fps) * 1e6);
    if (vChunks.length) {
      var lastV = vChunks[vChunks.length - 1];
      lastV.dur = Math.max(1, endUs - lastV.ts);
    }
    /* صدا: دقیقاً تا انتهای تصویر (نه بیشتر، نه کمتر) */
    aChunks = aChunks.filter(function (c) { return c.ts < endUs; });
    if (aChunks.length) {
      var lastA = aChunks[aChunks.length - 1];
      lastA.dur = Math.max(1, endUs - lastA.ts);
    }

    var all = [];
    for (var v = 0; v < vChunks.length; v++) { vChunks[v].kind = 'v'; all.push(vChunks[v]); }
    for (var a = 0; a < aChunks.length; a++) { aChunks[a].kind = 'a'; all.push(aChunks[a]); }
    all.sort(function (x, y) { return (x.ts - y.ts) || (x.kind === 'v' ? -1 : 1); });
    for (var z = 0; z < all.length; z++) {
      var ch = all[z];
      if (ch.kind === 'v') muxer.addVideoChunkRaw(ch.data, ch.type, ch.ts, ch.dur, ch.meta || undefined);
      else muxer.addAudioChunkRaw(ch.data, ch.type, ch.ts, ch.dur, ch.meta || undefined);
    }
    muxer.finalize();
    if (onProgress) onProgress(0.96);

    /* ── مرحلهٔ ۴: گیتِ خروجی ────────────────────────────────────────────
       خودِ مالتی‌پلکسر طول را از رویِ همین نمونه‌ها درست می‌نویسد؛ پس اول
       فقط *بازبینی* می‌کنیم و تنها اگر چیزی نادرست بود، هدر را با
       تایم‌اسکیلِ درست اصلاح می‌کنیم. (قبلاً بی‌قیدوشرط بازنویسی می‌شد و
       همین باعثِ «۵ ثانیه در گالری» بود: tkhd با track_ID سنجیده می‌شد.) */
    var exactSec = totalFrames / fps;
    var expect = { durationSec: exactSec, fps: fps, frames: totalFrames, maxKeyGapSec: 1.6, bpp: plan.bpp };
    var outBuf = muxer.target.buffer;
    var vfy = verifyMp4(outBuf, expect);
    if (vfy.parsed && !vfy.ok) {
      var fixedBuf = repairMp4Duration(outBuf, exactSec);
      var vfy2 = verifyMp4(fixedBuf, expect);
      if (vfy2.parsed) { outBuf = fixedBuf; vfy = vfy2; }
    }
    if (vfy.parsed && Math.abs((vfy.movie_duration_sec || 0) - exactSec) > 0.5)
      throw new Error('طولِ فایل در هدر درست نشد (' + vfy.movie_duration_sec + 's)');

    var blob = new Blob([outBuf], { type: 'video/mp4' });

    /* ── سنجشِ نهایی با خودِ پخش‌کننده (اگر ممکن باشد) ── */
    var probed = await probeDuration(blob, 4000);
    var durOk = (probed == null) ? null : Math.abs(probed - exactSec) <= 0.35;
    if (onProgress) onProgress(0.99);

    return {
      blob: blob,
      mime: 'video/mp4',
      name: 'radde-seda.mp4',
      fast: true,
      width: W, height: H, fps: fps,
      frames: totalFrames,
      bitrate: bitrate,
      bpp: plan.bpp,
      keyframe_sec: keyEvery / fps,
      reordered: reordered,
      duration: exactSec,
      probed_duration: probed,
      duration_ok: durOk,
      verify: vfy
    };
  }

  /* ── مسیر پشتیبان: ضبط هم‌زمان با پخش (همان روش قبلی، با کیفیت بالاتر) ── */
  async function renderVideoRealtime(pitch, notes, wav, sr, onProgress, opts) {
    var canvas = document.createElement('canvas');
    /* همان نقشهٔ مسیرِ سریع (ابعاد/نرخِ فریم/نرخِ بیت) تا مسیرِ پشتیبان هم
       کیفیتِ درست بگیرد و نرخِ بیت برای رزولوشن کافی باشد. */
    var planR = videoPlan(
      Math.max((pitch.times && pitch.times.length) ? pitch.times[pitch.times.length - 1] : 1, 0.2),
      opts.quality, opts.fps);
    canvas.width = planR.W;
    canvas.height = planR.H;
    var trace = pitch.trace_freqs || (CORE.strictTraceFreqs
      ? CORE.strictTraceFreqs(pitch.times, pitch.freqs, notes) : pitch.freqs);
    var renderer = new TraceRenderer(canvas, pitch.times, trace, notes, {
      windowSec: opts.windowSec || 0,
      tightness: opts.tightness || 1
    });

    var fps = planR.fps;
    var duration = Math.max(renderer.duration, 0.2);

    /* ══ ریشهٔ لگ: نرخِ ثابتِ فریم (CFR) ═══════════════════════════════
       قبلاً با captureStream(0) + requestFrame هر فریم «هر وقت rAF اجازه
       می‌داد» ثبت می‌شد؛ روی گوشیِ کند نمونه‌ها زمان‌بندیِ نامنظم (VFR)
       می‌گرفتند و پخش‌کننده باید برای هر فریم منتظرِ زمانِ خودش می‌ماند
       ⇒ پرش و توقفِ تصویر، هم در اپ و هم در گالری.
       حالا خودِ مرورگر با گامِ دقیقِ ۱/fps عکس می‌گیرد؛ اگر دستگاه یک فریم
       را دیر بکشد، همان فریم تکرار می‌شود و زمان‌بندی به‌هم نمی‌ریزد. */
    var stream = null, vtrack = null, cfr = false;
    try {
      stream = canvas.captureStream(fps);
      cfr = !!stream;
    } catch (e) { stream = null; }
    if (!stream) {
      /* پشتیبانِ قدیمی: بوم قابلیتِ نرخِ ثابت ندارد */
      stream = canvas.captureStream(0);
      try {
        var vts = stream.getVideoTracks ? stream.getVideoTracks() : [];
        vtrack = vts && vts[0];
      } catch (e) { vtrack = null; }
    }
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
      mimeType: mime, videoBitsPerSecond: planR.bitrate, audioBitsPerSecond: 160000
    } : { videoBitsPerSecond: planR.bitrate, audioBitsPerSecond: 160000 });

    var chunks = [];
    rec.ondataavailable = function (e) { if (e.data && e.data.size) chunks.push(e.data); };

    var done = new Promise(function (resolve) {
      rec.onstop = function () { resolve(new Blob(chunks, { type: mime || 'video/webm' })); };
    });

    renderer.draw(0);
    rec.start(250);
    var t0 = actx.currentTime;
    src.start();
    if (vtrack) { try { vtrack.requestFrame(); } catch (e) { } }

    var frameGap = 1 / fps;
    var lastSent = -1;
    await new Promise(function (resolve) {
      function step() {
        var el = Math.max(0, actx.currentTime - t0);
        if (el - lastSent >= frameGap - 0.002 || lastSent < 0) {
          renderer.draw(Math.min(el, duration));
          if (vtrack) { try { vtrack.requestFrame(); } catch (e) { } }
          lastSent = el;
        }
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
    allTracks.forEach(function (t2) { try { t2.stop(); } catch (e) { } });
    /* ── هدر: پخش‌کنندهٔ گوشی باید طولِ درست را ببیند ──
       MediaRecorder طول را در هدر نمی‌نویسد (یا صفر می‌گذارد)؛ نتیجه‌اش
       «۴ ثانیه» در گالری و پخشِ نیمه‌کاره بود. این‌جا خودمان می‌نویسیم. */
    var outBlob = blob, fixedBuf = null;
    var vfyR = null;
    try {
      var raw = await blob.arrayBuffer();
      var fixed;
      if (mime.indexOf('webm') >= 0) {
        fixed = patchWebmDuration(raw, duration);
      } else {
        /* اول بازبینی، بعد (فقط در صورتِ نیاز) اصلاح — با تایم‌اسکیلِ درست.
           اگر MediaRecorder فایلِ تکه‌ای (fMP4) داده باشد، mehd هم درج می‌شود
           تا گالریِ اندروید طول را از moov بخواند. */
        var expR = { durationSec: duration, fps: fps, maxKeyGapSec: 2.5 };
        vfyR = verifyMp4(raw, expR);
        fixed = (vfyR.parsed && vfyR.ok) ? raw : repairMp4Duration(raw, duration);
        if (vfyR.parsed && !vfyR.ok) {
          var vfyR2 = verifyMp4(fixed, expR);
          if (vfyR2.parsed) vfyR = vfyR2;
        }
      }
      fixedBuf = fixed;
      outBlob = new Blob([fixed], { type: mime || 'video/webm' });
    } catch (e) { outBlob = blob; fixedBuf = null; }

    var timing = null;
    try {
      timing = (mime.indexOf('webm') >= 0) ? null : frameTimingStats(fixedBuf || outBlob);
    } catch (e) { timing = null; }
    var probed = await probeDuration(outBlob, 4000);
    var durOk = (probed == null) ? null : Math.abs(probed - duration) <= 0.4;
    if (onProgress) onProgress(1.0);
    return {
      blob: outBlob,
      mime: mime || 'video/webm',
      name: (mime.indexOf('mp4') >= 0 ? 'radde-seda.mp4' : 'radde-seda.webm'),
      fast: false,
      width: planR.W, height: planR.H, fps: fps,
      bitrate: planR.bitrate,
      bpp: planR.bpp,
      duration: duration,
      probed_duration: probed,
      duration_ok: durOk,
      verify: vfyR,
      timing: timing,
      cfr: cfr,
      path: 'realtime'
    };
  }

  async function renderVideoBlob(pitch, notes, wav, sr, onProgress, opts) {
    opts = opts || {};
    if (opts.fast !== false && webCodecsReady()) {
      try {
        return await renderVideoFast(pitch, notes, wav, sr, onProgress, opts);
      } catch (e) {
        if (global.console) console.warn('رندر سریع ممکن نشد، مسیر پشتیبان:', e && e.message);
      }
    }
    return renderVideoRealtime(pitch, notes, wav, sr, onProgress, opts);
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

      // موازی (Web Worker) — اگر در دسترس نباشد، خودش به مسیر سریال برمی‌گردد
      var res = await CORE.analyzeChannelsAsync(decoded.channels, decoded.sr, {
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
      var renderer = new TraceRenderer(canvas, res.times,
        res.trace_freqs || res.freqs, res.notes);
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
      }, job.videoOpts || {});
      job.videoBlob = out.blob;
      job.videoUrl = URL.createObjectURL(out.blob);
      job.videoName = out.name;
      job.report.meta.video_url = job.videoUrl;
      job.report.meta.video_name = out.name;
      job.report.meta.video_fast = !!out.fast;
      job.report.meta.video_frames = out.frames || null;
      job.report.meta.video_render_sec = out.seconds != null ? out.seconds : null;
      job.report.meta.video_window_sec = (job.videoOpts && job.videoOpts.windowSec) || 0;
      job.report.meta.video_tightness = (job.videoOpts && job.videoOpts.tightness) || 1;
      job.report.meta.video_duration_sec = out.duration != null ? out.duration : null;
      job.report.meta.video_probed_duration_sec = out.probed_duration != null ? out.probed_duration : null;
      job.report.meta.video_duration_ok = out.duration_ok;
      job.report.meta.video_size = (out.width || 0) + 'x' + (out.height || 0);
      job.report.meta.video_bitrate = out.bitrate || null;
      job.report.meta.video_bpp = out.bpp != null ? out.bpp : null;
      job.report.meta.video_keyframe_sec = out.keyframe_sec != null ? out.keyframe_sec : null;
      job.report.meta.video_verify = out.verify || null;
      job.result = {
        video_url: job.videoUrl,
        video_name: out.name,
        video_type: out.mime,
        fast: !!out.fast,
        frames: out.frames || null,
        render_sec: out.seconds != null ? out.seconds : null,
        duration: out.duration != null ? out.duration : null,
        probed_duration: out.probed_duration != null ? out.probed_duration : null,
        duration_ok: out.duration_ok,
        width: out.width || null,
        height: out.height || null,
        fps: out.fps || null,
        bitrate: out.bitrate || null,
        bpp: out.bpp != null ? out.bpp : null,
        keyframe_sec: out.keyframe_sec != null ? out.keyframe_sec : null,
        seconds: out.seconds != null ? out.seconds : null,
        verify: out.verify || null
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

    // POST /api/video/{id}  (پارامترهای زوم هم از همین‌جا می‌آیند)
    if ((m = path.match(/\/api\/video\/([^/]+)$/)) && method === 'POST') {
      var jv = jobs[m[1]];
      if (!jv) return jsonResponse({ error: 'کار پیدا نشد' }, 404);
      var vopts = {};
      if (init && init.body && typeof init.body.get === 'function') {
        var ws = parseFloat(init.body.get('window_sec'));
        var tg = parseFloat(init.body.get('tightness'));
        var fpr = parseFloat(init.body.get('fps'));
        if (isFinite(ws) && ws > 0) vopts.windowSec = ws;
        if (isFinite(tg) && tg > 0) vopts.tightness = tg;
        if (isFinite(fpr) && fpr > 0) vopts.fps = fpr;
        var q = init.body.get('quality');
        if (q === '1080p' || q === '720p') vopts.quality = q;
      }
      jv.videoOpts = vopts;
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

  /* ── اجرای زنده: همان پروتکل سرور، ولی محلی و ضدپرش ───────────────────
     تصمیم‌گیری در CORE.LiveTracker است (engine.js): دروازهٔ بلندی تطبیقی،
     میانهٔ زمانی، ردِ پرش اکتاو و پایداری سه‌بسته‌ای. این‌جا فقط بستهٔ صوتی
     به آن داده می‌شود و پیام پروتکل ساخته می‌شود.
     ───────────────────────────────────────────────────────────────────── */
  function LiveSession(ws) {
    this.ws = ws;
    this.sr = 16000;
    this.chunkSec = 0.12;
    this.tracker = new CORE.LiveTracker({ chunkSec: this.chunkSec });
    this.dead = false;
    this.frames = 0;
    this.voicedFrames = 0;
  }

  LiveSession.prototype.stop = function () { this.dead = true; };

  LiveSession.prototype.handle = function (data) {
    if (this.dead) return;
    if (typeof data === 'string') {
      try {
        var cfg = JSON.parse(data);
        if (cfg.sample_rate) this.sr = cfg.sample_rate;
        if (cfg.chunk_sec) {
          this.chunkSec = cfg.chunk_sec;
          this.tracker.chunkSec = cfg.chunk_sec;
        }
        if (cfg.reset) {
          this.tracker.reset();
          this.frames = 0;
          this.voicedFrames = 0;
          this.ws._sendMsg(JSON.stringify({ reset: true, silent: true, note: null }));
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

    this.frames++;
    var info = CORE.extractPitchChunkInfo(samples, this.sr, {});
    if (info.f0 > 0) this.voicedFrames++;
    var frame = this.tracker.push(info);

    var msg = {
      note: frame.note,
      silent: frame.silent,
      voiced: frame.voiced,
      level: Math.round(Math.min(1, info.rms * 8) * 100) / 100,
      session: frame.session
    };
    this.ws._sendMsg(JSON.stringify(msg));
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
      return CORE.analyzeChannelsAsync(decoded.channels, decoded.sr, opts || {});
    },
    analyzeChannels: CORE.analyzeChannels,
    analyzeChannelsAsync: CORE.analyzeChannelsAsync,
    parallel: function () {
      var P = global.LocalParallel;
      return P ? { available: !!P.available, cores: P.cores || 0, reason: P.reason || '' }
        : { available: false, cores: 0, reason: 'بارگذاری نشده' };
    },
    report: CORE.buildReport,
    TraceRenderer: TraceRenderer,
    renderVideo: renderVideoBlob,
    /* ابزارهای هدر/طول فایل (برای تست و استفاده در گزارش) */
    patchMp4Duration: patchMp4Duration,
    patchWebmDuration: patchWebmDuration,
    mp4Timeline: mp4Timeline,
    verifyMp4: verifyMp4,
    frameTimingStats: frameTimingStats,
    ensureMp4FragmentDuration: ensureMp4FragmentDuration,
    repairMp4Duration: repairMp4Duration,
    bitrateFor: bitrateFor,
    videoPlan: videoPlan,
    probeDuration: probeDuration,
    videoProfile: videoProfile,
    videoSize: videoSize,
    VIDEO: VIDEO,
    VIDEO_HD: VIDEO_HD,
    webCodecsReady: webCodecsReady,
    jobs: jobs,
    version: '1.0.0'
  };
})(typeof window !== 'undefined' ? window : globalThis);
