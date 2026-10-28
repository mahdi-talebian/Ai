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

    /* بالاترین/پایین‌ترین نت برای راهنماها (از روی همان نت‌های سخت‌گیرانه) */
    var best = null, worst = null;
    this.notes.forEach(function (n) {
      if (!best || n.f0_hz > best.f0_hz) best = n;
      if (!worst || n.f0_hz < worst.f0_hz) worst = n;
    });
    this.highest = best;
    this.lowest = worst;
    this.range = null;
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

  /* اندازهٔ قاب: پیش‌فرض «خودکار» = ۱۰۸۰p برای فایل‌های تا ۴ دقیقه (تصویر
     پهن‌تر و واضح‌تر)، و ۷۲۰p برای فایل‌های خیلی بلند تا حافظه پر نشود.
     اگر دستگاه کدگذاری ۱۰۸۰p ندهد، خودکار به ۷۲۰p برمی‌گردد. */
  function videoSize(durationSec, quality) {
    var q = quality || 'auto';
    if (q === '720p') return { W: VIDEO.W, H: VIDEO.H, bitrate: VIDEO.bitrate };
    if (q === '1080p') return { W: VIDEO_HD.W, H: VIDEO_HD.H, bitrate: VIDEO_HD.bitrate };
    return (durationSec <= 240) ? { W: VIDEO_HD.W, H: VIDEO_HD.H, bitrate: VIDEO_HD.bitrate }
      : { W: VIDEO.W, H: VIDEO.H, bitrate: VIDEO.bitrate };
  }

  /* برای فایل‌های بلند: نرخ بیت و (اگر لازم شد) فریم را کم می‌کنیم تا
     حافظهٔ گوشی پر نشود؛ برای فایل کوتاه همان ۷۲۰p۶۰ و کیفیت بالا می‌ماند. */
  function videoProfile(durationSec, askedFps) {
    var fps = askedFps > 0 ? askedFps : VIDEO.fps;
    var bitrate = VIDEO.bitrate;
    if (durationSec > 300) { bitrate = 1800000; fps = Math.min(fps, 24); }
    else if (durationSec > 150) { bitrate = 2800000; fps = Math.min(fps, 30); }
    else if (durationSec > 75) { bitrate = 3800000; fps = Math.min(fps, 60); }
    return { fps: Math.round(fps), bitrate: Math.round(bitrate) };
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
        bitrate: bitrate || VIDEO.bitrate, latencyMode: 'quality',
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

  async function renderVideoFast(pitch, notes, wav, sr, onProgress, opts) {
    var trace = pitch.trace_freqs || (CORE.strictTraceFreqs
      ? CORE.strictTraceFreqs(pitch.times, pitch.freqs, notes) : pitch.freqs);
    var duration = Math.max(
      (pitch.times && pitch.times.length) ? pitch.times[pitch.times.length - 1] : 1, 0.2);
    var prof = videoProfile(duration, opts.fps);
    var fps = prof.fps;
    var size = videoSize(duration, opts.quality);
    var canvas = document.createElement('canvas');
    function build(W, H) {
      canvas.width = W; canvas.height = H;
      return new TraceRenderer(canvas, pitch.times, trace, notes, {
        windowSec: opts.windowSec || 0,
        tightness: opts.tightness || 1
      });
    }
    var W = size.W, H = size.H;
    var renderer = build(W, H);
    duration = Math.max(renderer.duration, 0.2);
    var totalFrames = Math.max(1, Math.round(duration * fps));

    var vcfg = await pickAvcConfig(W, H, fps, Math.min(prof.bitrate, size.bitrate));
    if (!vcfg && W !== VIDEO.W) {                 /* دستگاه ۱۰۸۰p نداد ⇒ ۷۲۰p */
      W = VIDEO.W; H = VIDEO.H;
      renderer = build(W, H);
      vcfg = await pickAvcConfig(W, H, fps, VIDEO.bitrate);
    }
    if (!vcfg) throw new Error('کدک H.264 در این دستگاه نیست');
    var acfg = await pickAacConfig(sr);
    if (!acfg) throw new Error('کدک AAC در این دستگاه نیست');

    var muxer = new global.Mp4Muxer.Muxer({
      target: new global.Mp4Muxer.ArrayBufferTarget(),
      video: { codec: 'avc', width: W, height: H, frameRate: fps },
      audio: { codec: 'aac', sampleRate: sr, numberOfChannels: 1 },
      fastStart: 'in-memory'
    });

    var vChunks = [], aChunks = [], encError = null;
    var venc = new global.VideoEncoder({
      output: function (chunk, meta) { vChunks.push({ c: chunk, m: meta, ts: chunk.timestamp }); },
      error: function (e) { encError = e; }
    });
    venc.configure(vcfg);
    var aenc = new global.AudioEncoder({
      output: function (chunk, meta) { aChunks.push({ c: chunk, m: meta, ts: chunk.timestamp }); },
      error: function (e) { encError = e; }
    });
    aenc.configure(acfg);

    /* ── فریم‌ها: رسم + کدگذاری با برچسب زمانی دقیق i/fps ── */
    var t0 = Date.now();
    for (var i = 0; i < totalFrames; i++) {
      if (encError) throw encError;
      var t = Math.min(i / fps, duration);
      renderer.draw(t);
      var vf = new global.VideoFrame(canvas, {
        timestamp: Math.round((i / fps) * 1e6),
        duration: Math.round(1e6 / fps)
      });
      venc.encode(vf, { keyFrame: (i % (fps * 2) === 0) });
      vf.close();
      if (venc.encodeQueueSize > (W * H > 1280 * 720 ? 6 : 12)) await yieldTick();
      if ((i & 7) === 0) {
        if (onProgress) onProgress(0.04 + 0.76 * (i / totalFrames));
        await yieldTick();
      }
    }
    await venc.flush();
    if (encError) throw encError;

    /* ── صدا: همان طول، بدون درز ── */
    var CHUNK = 1024;
    for (var off = 0; off < wav.length; off += CHUNK) {
      var n = Math.min(CHUNK, wav.length - off);
      var data = new Float32Array(CHUNK);              /* صفر-پُر کردن آخرین تکه */
      data.set(wav.subarray(off, off + n));
      var ad = new global.AudioData({
        format: 'f32', sampleRate: sr, numberOfFrames: CHUNK, numberOfChannels: 1,
        timestamp: Math.round((off / sr) * 1e6), data: data
      });
      aenc.encode(ad);
      ad.close();
      if (aenc.encodeQueueSize > 24) await yieldTick();
      if ((off % (CHUNK * 96)) === 0) {
        if (onProgress) onProgress(0.80 + 0.16 * (off / wav.length));
        await yieldTick();
      }
    }
    await aenc.flush();
    if (encError) throw encError;

    /* ── نوشتن در MP4 به ترتیب زمان ── */
    var all = [];
    for (var v = 0; v < vChunks.length; v++) { var vv = vChunks[v]; vv.kind = 'v'; all.push(vv); }
    for (var a = 0; a < aChunks.length; a++) { var aa = aChunks[a]; aa.kind = 'a'; all.push(aa); }
    all.sort(function (x, y) { return x.ts - y.ts; });
    for (var z = 0; z < all.length; z++) {
      if (all[z].kind === 'v') muxer.addVideoChunk(all[z].c, all[z].m);
      else muxer.addAudioChunk(all[z].c, all[z].m);
    }
    muxer.finalize();
    try { venc.close(); aenc.close(); } catch (e) { }
    if (onProgress) onProgress(0.99);

    return {
      blob: new Blob([muxer.target.buffer], { type: 'video/mp4' }),
      mime: 'video/mp4',
      name: 'radde-seda.mp4',
      fast: true,
      width: W, height: H, fps: fps,
      frames: totalFrames,
      seconds: Math.round((Date.now() - t0) / 100) / 10
    };
  }

  /* ── مسیر پشتیبان: ضبط هم‌زمان با پخش (همان روش قبلی، با کیفیت بالاتر) ── */
  async function renderVideoRealtime(pitch, notes, wav, sr, onProgress, opts) {
    var canvas = document.createElement('canvas');
    canvas.width = VIDEO.W;
    canvas.height = VIDEO.H;
    var trace = pitch.trace_freqs || (CORE.strictTraceFreqs
      ? CORE.strictTraceFreqs(pitch.times, pitch.freqs, notes) : pitch.freqs);
    var renderer = new TraceRenderer(canvas, pitch.times, trace, notes, {
      windowSec: opts.windowSec || 0,
      tightness: opts.tightness || 1
    });

    var fps = Math.min(opts.fps > 0 ? opts.fps : VIDEO.fps, 60);
    var duration = Math.max(renderer.duration, 0.2);

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
      mimeType: mime, videoBitsPerSecond: VIDEO.bitrate, audioBitsPerSecond: 160000
    } : { videoBitsPerSecond: VIDEO.bitrate, audioBitsPerSecond: 160000 });

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
    allTracks.forEach(function (t2) { try { t2.stop(); } catch (e) { } });
    if (onProgress) onProgress(1.0);
    return {
      blob: blob,
      mime: mime || 'video/webm',
      name: (mime.indexOf('mp4') >= 0 ? 'radde-seda.mp4' : 'radde-seda.webm'),
      fast: false
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
      job.result = {
        video_url: job.videoUrl,
        video_name: out.name,
        video_type: out.mime,
        fast: !!out.fast,
        frames: out.frames || null,
        render_sec: out.seconds != null ? out.seconds : null
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
    VIDEO: VIDEO,
    webCodecsReady: webCodecsReady,
    jobs: jobs,
    version: '1.0.0'
  };
})(typeof window !== 'undefined' ? window : globalThis);
