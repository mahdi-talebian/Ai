/* ============================================================================
   🎯 موتور آفلاین «ردِّ صدا» — web/engine.js
   ----------------------------------------------------------------------------
   همهٔ تحلیل، همان‌جا روی گوشی/مرورگر انجام می‌شود؛ هیچ درخواستی به هیچ
   سروری فرستاده نمی‌شود. این ماژول همان خط لولهٔ Python پروژه را به
   جاوااسکریپت برگردانده است:

     python/analysis_engine.py  →  prepareAudio / pitchTrack / spectralOctaveFix
                                   / yinTrack / segmentNotes / refineNotes / کیفیت
     python/range_analyzer.py   →  analyzeRange (گزارش JSON با همان کلیدها)
     python/trace_video.py      →  TraceRenderer (PNG/MP4 با همان چیدمان)
     python/pitch_engine.py     →  extractPitchChunk (اجرای زنده)

   نکتهٔ مهم دربارهٔ «یکسان بودن نتیجه»: همان اصول ثابت پروژه رعایت شده —
   مونوی فازمحافظ، ۲۲٬۰۵۰ هرتز، حذف DC، بالاگذر ۴۵ هرتز، نرمال‌سازی پیک ۰٫۹،
   گام زمانی ثابت ۵ میلی‌ثانیه و بازهٔ جست‌وجوی ثابت ۵۵–۱۲۰۰ هرتز — بنابراین
   یک فایل همیشه یک نتیجه می‌دهد؛ مستقل از نرخ نمونه/کانال/بلندی/فرمت.
   ========================================================================== */
(function (global) {
  'use strict';

  /* ══════════════════════════════════════════════════════════════════════
     ثابت‌ها (هم‌نام و هم‌مقدار با Python)
     ══════════════════════════════════════════════════════════════════════ */
  var SR = 22050;
  var HIGHPASS_HZ = 45.0;
  var PEAK_TARGET = 0.9;
  var MIN_F0 = 55.0;
  var MAX_F0 = 1200.0;
  var TIME_STEP = 0.005;
  var PYIN_MAX_SEC = 180.0;
  var LADDER = [0.25, 1.0 / 3.0, 0.5, 1.0, 2.0, 3.0, 4.0];

  var MIN_NOTE_SEC = 0.10;
  var MAX_STABILITY_CENTS = 70.0;
  var PAUSE_MIN_SEC = 0.30;
  var ROBUST_MIN_TOTAL_SEC = 0.35;
  var ROBUST_MIN_TOTAL_PCT = 0.2;
  var ROBUST_MIN_COUNT = 2;

  var VOICING_THRESHOLD = 0.42;     // معادل voicing_threshold در Praat
  var WIN_PERIODS = 3.0;            // پنجره = ۳ دورهٔ fmin (مثل Praat)
  var NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];

  /* ══════════════════════════════════════════════════════════════════════
     ابزارهای پایه
     ══════════════════════════════════════════════════════════════════════ */
  function nextPow2(n) { var p = 1; while (p < n) p <<= 1; return p; }

  function zeros(n) { return new Float64Array(n); }

  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }

  function median(arr) {
    if (!arr.length) return 0;
    var a = Array.prototype.slice.call(arr).sort(function (x, y) { return x - y; });
    var m = a.length >> 1;
    return a.length % 2 ? a[m] : 0.5 * (a[m - 1] + a[m]);
  }

  function percentile(arr, p) {
    if (!arr.length) return 0;
    var a = Array.prototype.slice.call(arr).sort(function (x, y) { return x - y; });
    var k = clamp((a.length - 1) * p / 100.0, 0, a.length - 1);
    var lo = Math.floor(k), hi = Math.ceil(k);
    return lo === hi ? a[lo] : a[lo] + (a[hi] - a[lo]) * (k - lo);
  }

  function mean(arr) {
    if (!arr.length) return 0;
    var s = 0;
    for (var i = 0; i < arr.length; i++) s += arr[i];
    return s / arr.length;
  }

  function cents(a, b) { return 1200.0 * Math.log2(Math.max(a, 1e-9) / Math.max(b, 1e-9)); }

  function hzToMidi(f) { return 69.0 + 12.0 * Math.log2(f / 440.0); }
  function midiToHz(m) { return 440.0 * Math.pow(2.0, (m - 69.0) / 12.0); }
  function midiToName(m) {
    if (m == null || !isFinite(m)) return '—';
    var r = Math.round(m);
    return NOTE_NAMES[((r % 12) + 12) % 12] + (Math.floor(r / 12) - 1);
  }

  /* ── FFT (iterative radix-2، با کش جدول) ─────────────────────────────── */
  var _twCache = {};
  function twiddles(n) {
    if (_twCache[n]) return _twCache[n];
    var half = n >> 1, cos = new Float64Array(half), sin = new Float64Array(half);
    for (var i = 0; i < half; i++) {
      var ang = -2 * Math.PI * i / n;
      cos[i] = Math.cos(ang);
      sin[i] = Math.sin(ang);
    }
    _twCache[n] = { cos: cos, sin: sin };
    return _twCache[n];
  }

  function fft(re, im) {
    var n = re.length;
    if (n <= 1) return;
    // bit-reversal
    for (var i = 1, j = 0; i < n; i++) {
      var bit = n >> 1;
      for (; j & bit; bit >>= 1) j ^= bit;
      j ^= bit;
      if (i < j) {
        var tr = re[i]; re[i] = re[j]; re[j] = tr;
        var ti = im[i]; im[i] = im[j]; im[j] = ti;
      }
    }
    var tw = twiddles(n);
    for (var len = 2; len <= n; len <<= 1) {
      var half = len >> 1, step = n / len;
      for (var s = 0; s < n; s += len) {
        for (var k = 0; k < half; k++) {
          var wr = tw.cos[k * step], wi = tw.sin[k * step];
          var a = s + k, b = a + half;
          var vr = re[b] * wr - im[b] * wi;
          var vi = re[b] * wi + im[b] * wr;
          re[b] = re[a] - vr; im[b] = im[a] - vi;
          re[a] += vr; im[a] += vi;
        }
      }
    }
  }

  function ifft(re, im) {
    var n = re.length, i;
    for (i = 0; i < n; i++) im[i] = -im[i];
    fft(re, im);
    for (i = 0; i < n; i++) { re[i] /= n; im[i] = -im[i] / n; }
  }

  /** طیف دامنه برای یک پنجرهٔ حقیقی (nfft توان دو) */
  function rfftMag(frame, nfft, windowFn) {
    var re = new Float64Array(nfft), im = new Float64Array(nfft);
    var n = Math.min(frame.length, nfft);
    for (var i = 0; i < n; i++) re[i] = windowFn ? frame[i] * windowFn[i] : frame[i];
    fft(re, im);
    var half = (nfft >> 1) + 1, mag = new Float32Array(half);
    for (var k = 0; k < half; k++) mag[k] = Math.hypot(re[k], im[k]);
    return mag;
  }

  function hann(n) {
    var w = new Float64Array(n);
    for (var i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / Math.max(1, n - 1));
    return w;
  }

  /* ── بازنمونه‌برداری sinc پنجره‌ای (Kaiser) — نتیجهٔ قطعی و مستقل از مرورگر ── */
  function besselI0(x) {
    var sum = 1, term = 1, k = 1;
    while (term > 1e-12 * sum) {
      term *= (x / (2 * k)) * (x / (2 * k));
      sum += term;
      k++;
      if (k > 60) break;
    }
    return sum;
  }

  function resample(y, srIn, srOut, taps) {
    if (srIn === srOut) return y;
    taps = taps || 32;
    var ratio = srOut / srIn;
    var nOut = Math.max(1, Math.round(y.length * ratio));
    var out = new Float32Array(nOut);
    var cutoff = Math.min(1.0, ratio);              // ضد-باندگذر هنگام کاهش نرخ
    var beta = 5.0;
    var i0beta = besselI0(beta);
    var halfTaps = taps >> 1;

    for (var i = 0; i < nOut; i++) {
      var t = i / ratio;                            // موقعیت در ورودی
      var i0 = Math.floor(t);
      var acc = 0.0, norm = 0.0;
      for (var k = -halfTaps + 1; k <= halfTaps; k++) {
        var idx = i0 + k;
        if (idx < 0) idx = 0;
        if (idx >= y.length) idx = y.length - 1;
        var x = t - (i0 + k);
        var u = x / halfTaps;
        if (u <= -1 || u >= 1) continue;
        var w = besselI0(beta * Math.sqrt(1 - u * u)) / i0beta;
        var arg = Math.PI * cutoff * x;
        var sinc = (Math.abs(arg) < 1e-9) ? 1.0 : Math.sin(arg) / arg;
        var h = cutoff * sinc * w;
        acc += y[idx] * h;
        norm += h;
      }
      out[i] = norm > 1e-9 ? acc / norm : 0.0;
    }
    return out;
  }

  /* ── فیلتر بالاگذر باترورث مرتبهٔ ۲ (هم‌ارز scipy butter(2) + sosfilt) ── */
  function highpass(y, sr, fc) {
    var w0 = 2 * Math.PI * fc / sr;
    var cosw = Math.cos(w0), sinw = Math.sin(w0);
    var alpha = sinw / (2 * Math.SQRT1_2);          // Q = 1/√2 → باترورث مرتبهٔ ۲
    var a0 = 1 + alpha;
    var b0 = (1 + cosw) / 2 / a0, b1 = -(1 + cosw) / a0, b2 = (1 + cosw) / 2 / a0;
    var a1 = -2 * cosw / a0, a2 = (1 - alpha) / a0;
    var out = new Float32Array(y.length);
    var x1 = 0, x2 = 0, y1 = 0, y2 = 0;
    for (var i = 0; i < y.length; i++) {
      var x0 = y[i];
      var y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
      out[i] = y0;
      x2 = x1; x1 = x0; y2 = y1; y1 = y0;
    }
    return out;
  }

  /* ── کاهش نویز: گیت طیفی با تخمین نویز از فریم‌های ساکت ────────────────── */
  function denoise(y, sr) {
    var win = 1024, hop = 256, nfft = 1024;
    if (y.length < win * 2) return y;
    var w = hann(win);
    var nFrames = Math.floor((y.length - win) / hop) + 1;
    if (nFrames < 2) return y;

    // ذخیرهٔ طیف دامنهٔ همهٔ فریم‌ها
    var mags = new Array(nFrames);
    var phases = new Array(nFrames);
    var half = (nfft >> 1) + 1;
    for (var f = 0; f < nFrames; f++) {
      var re = new Float64Array(nfft), im = new Float64Array(nfft);
      for (var i = 0; i < win; i++) re[i] = y[f * hop + i] * w[i];
      fft(re, im);
      var mag = new Float32Array(half), ph = new Float32Array(half);
      for (var k = 0; k < half; k++) {
        mag[k] = Math.hypot(re[k], im[k]);
        ph[k] = Math.atan2(im[k], re[k]);
      }
      mags[f] = mag; phases[f] = ph;
    }

    // پروفایل نویز: صدک ۲۰٪ هر بین (نویز ایستا)
    var noise = new Float32Array(half);
    var col = new Float32Array(nFrames);
    for (var k2 = 0; k2 < half; k2++) {
      for (var f2 = 0; f2 < nFrames; f2++) col[f2] = mags[f2][k2];
      noise[k2] = percentile(col, 20);
    }

    var out = new Float32Array(y.length);
    var norm = new Float32Array(y.length);
    var over = 1.6;            // ضریب فراکاهش
    var floorGain = 0.06;      // کف (تا صدا کاملاً خشک نشود)
    for (var fr = 0; fr < nFrames; fr++) {
      var re2 = new Float64Array(nfft), im2 = new Float64Array(nfft);
      var mag2 = mags[fr], ph2 = phases[fr];
      for (var b = 0; b < half; b++) {
        var m = mag2[b] - over * noise[b];
        if (m < 0) m = 0;
        var gain = mag2[b] > 1e-12 ? Math.max(floorGain, m / mag2[b]) : floorGain;
        var val = mag2[b] * gain;
        re2[b] = val * Math.cos(ph2[b]);
        im2[b] = val * Math.sin(ph2[b]);
        if (b > 0 && b < half - 1) {              // تقارن هرمیتی
          re2[nfft - b] = re2[b];
          im2[nfft - b] = -im2[b];
        }
      }
      ifft(re2, im2);
      for (var s = 0; s < win; s++) {
        out[fr * hop + s] += re2[s] * w[s];
        norm[fr * hop + s] += w[s] * w[s];
      }
    }
    for (var i2 = 0; i2 < out.length; i2++) {
      if (norm[i2] > 1e-8) out[i2] /= norm[i2];
    }
    return out;
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۱ — استانداردسازی ورودی (هم‌ارز prepare_audio)
     ══════════════════════════════════════════════════════════════════════ */
  function safeDownmix(channels) {
    if (channels.length === 1) return { mono: channels[0], mode: 'mono', corr: null };
    var a = channels[0], b = channels[1];
    var n = Math.min(a.length, b.length);
    var ma = 0, mb = 0, i;
    for (i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
    ma /= n; mb /= n;
    var va = 0, vb = 0, cov = 0;
    for (i = 0; i < n; i++) {
      var da = a[i] - ma, db = b[i] - mb;
      va += da * da; vb += db * db; cov += da * db;
    }
    var corr = cov / (Math.sqrt(va * vb) + 1e-12);
    if (corr < -0.35) {
      // فاز معکوس: میانگین صدا را حذف می‌کند ⇒ کانال قوی‌تر
      var ra = 0, rb = 0;
      for (i = 0; i < n; i++) { ra += a[i] * a[i]; rb += b[i] * b[i]; }
      var best = ra >= rb ? 0 : 1;
      return { mono: channels[best], mode: 'ch' + (best + 1), corr: corr };
    }
    var mono = new Float32Array(n);
    for (i = 0; i < n; i++) mono[i] = 0.5 * (a[i] + b[i]);
    return { mono: mono, mode: 'mean', corr: corr };
  }

  function prepareAudio(channels, srIn, opts) {
    opts = opts || {};
    var diag = { sample_rate_in: srIn, channels_in: channels.length, sample_rate_used: SR };

    var dm = safeDownmix(channels);
    diag.downmix = (dm.mode === 'mono') ? 'mono'
      : (dm.mode === 'mean' ? 'mean' : 'channel' + dm.mode.slice(2) + '_phase_safe');
    if (dm.corr != null) diag.channel_correlation = Math.round(dm.corr * 1000) / 1000;

    var y = dm.mono;
    // DC
    var s = 0;
    for (var i = 0; i < y.length; i++) s += y[i];
    var dc = s / Math.max(1, y.length);

    var peakRaw = 0, clipCount = 0;
    for (i = 0; i < y.length; i++) {
      var v = Math.abs(y[i]);
      if (v > peakRaw) peakRaw = v;
      if (v >= 0.999) clipCount++;
    }

    // نرخ نمونه
    if (srIn !== SR) y = resample(y, srIn, SR, 32);
    diag.decoder = 'builtin';

    // بدون DC + بالاگذر
    var hp = highpass(y, SR, HIGHPASS_HZ);

    // کاهش نویز (اختیاری)
    if (opts.denoise) {
      hp = denoise(hp, SR);
      diag.denoise = true;
    } else {
      diag.denoise = false;
    }

    // نرمال‌سازی پیک
    var peakHp = 0;
    for (i = 0; i < hp.length; i++) {
      var av = Math.abs(hp[i]);
      if (av > peakHp) peakHp = av;
    }
    var gain = peakHp > 1e-6 ? PEAK_TARGET / peakHp : 0;
    var out = new Float32Array(hp.length);
    for (i = 0; i < hp.length; i++) out[i] = (hp[i] - 0) * gain;

    var duration = out.length / SR;
    diag.dc_offset = Math.round((dc + 0) * 1e5) / 1e5;
    diag.clipping_pct = Math.round(1000 * 100 * clipCount / Math.max(1, y.length)) / 1000;
    diag.peak_dbfs = Math.round(20 * Math.log10(Math.max(peakRaw, 1e-9)) * 10) / 10;
    diag.normalized_gain_db = gain > 0 ? Math.round(20 * Math.log10(gain) * 10) / 10 : 0;
    diag.duration_sec = Math.round(duration * 100) / 100;
    diag.snr_db = estimateSnr(out, SR);
    diag.snr_method = 'frame-percentile';
    diag.standard = { sample_rate: SR, channels: 1, highpass_hz: HIGHPASS_HZ, peak: PEAK_TARGET };

    return { y: out, sr: SR, duration: duration, diag: diag };
  }

  function estimateSnr(y, sr, frame) {
    frame = frame || 0.05;
    var hop = Math.max(1, Math.round(sr * frame));
    var n = Math.floor(y.length / hop);
    if (n < 4) return null;
    var rms = new Float64Array(n);
    for (var f = 0; f < n; f++) {
      var acc = 0;
      for (var i = 0; i < hop; i++) {
        var v = y[f * hop + i];
        acc += v * v;
      }
      rms[f] = Math.sqrt(acc / hop) + 1e-12;
    }
    var loud = percentile(rms, 90), quiet = percentile(rms, 10);
    if (!(quiet > 0) || !(loud > 0)) return null;
    var snr = 20 * Math.log10(loud / quiet);
    if (snr < 2.0) return null;
    return Math.round(Math.min(snr, 45.0) * 10) / 10;
  }

  /* ── خودهمبستگی با FFT حقیقی (نصف کارِ FFT مختلط) ────────────────────
     نمونه‌های زوج/فرد در یک آرایهٔ مختلطِ نصف‌اندازه بسته‌بندی می‌شوند؛
     از نظر ریاضی همان acf حاصل از FFT مختلط است (خطای ~1e-16)، ولی
     تقریباً دو برابر سریع‌تر. اعتبارسنجی: web/tests/parallel_test.js
     ────────────────────────────────────────────────────────────────────── */
  function rfftScratch(n) {
    var m = n >> 1, k;
    // جدول‌های چرخش، یک بار برای همیشه (بدون Math.cos در حلقهٔ فریم‌ها)
    var cw = new Float64Array(m), sw = new Float64Array(m);
    for (k = 0; k < m; k++) {
      var a = -Math.PI * k / m;
      cw[k] = Math.cos(a); sw[k] = Math.sin(a);
    }
    return {
      zr: new Float64Array(m), zi: new Float64Array(m),
      power: new Float64Array(m + 1), acf: new Float64Array(n),
      cw: cw, sw: sw
    };
  }

  /**
   * acf[τ] = Σ x[i]·x[i+τ]  (خطی، به شرط n ≥ len(x) + τmax)
   * @param {Float64Array} x نمونه‌ها (بقیهٔ x تا n صفر فرض می‌شود)
   * @param {number} len تعداد نمونه‌های معتبر x
   * @param {number} n اندازهٔ FFT (توان دو)
   */
  /** |X[k]|² برای k=0..n/2 از خروجی FFT مختلطِ بسته‌بندی‌شده */
  function rfftPowerInto(zr, zi, n, sc) {
    var m = n >> 1, k;
    var pw = sc.power;
    for (k = 0; k < m; k++) {
      var k2 = (m - k) % m;
      var ar = zr[k], ai = zi[k];
      var br = zr[k2], bi = -zi[k2];              // conj(Z[M-k])
      var er = 0.5 * (ar + br), ei = 0.5 * (ai + bi);
      var dr = ar - br, di = ai - bi;
      var orr = 0.5 * di, oi = -0.5 * dr;         // O = (Z - conj)/(2i)
      var wr = sc.cw[k], wi = sc.sw[k];           // W^k = e^{-2πik/n}
      var tr = wr * orr - wi * oi, ti = wr * oi + wi * orr;
      var xr = er + tr, xi = ei + ti;
      pw[k] = xr * xr + xi * xi;
    }
    var nyq = zr[0] - zi[0];                      // X[n/2] حقیقی است
    pw[m] = nyq * nyq;
    return pw;
  }

  /** بسته‌بندی نمونه‌های حقیقی + FFT نصف‌اندازه → طیف توان */
  function rfftPack(x, len, n, sc) {
    var m = n >> 1, i;
    var zr = sc.zr, zi = sc.zi;
    for (i = 0; i < m; i++) {
      var i0 = i << 1;
      zr[i] = i0 < len ? x[i0] : 0;
      zi[i] = (i0 + 1) < len ? x[i0 + 1] : 0;
    }
    fft(zr, zi);                                  // FFT مختلطِ اندازهٔ n/2
    return rfftPowerInto(zr, zi, n, sc);
  }

  function rfftAcf(x, len, n, sc) {
    var m = n >> 1, i, k;
    var zr = sc.zr, zi = sc.zi;
    var pw = rfftPack(x, len, n, sc);
    for (k = 0; k < m; k++) {
      var k2 = (m - k) % m;
      var ar = zr[k], ai = zi[k];
      var br = zr[k2], bi = -zi[k2];              // conj(Z[M-k])
      var er = 0.5 * (ar + br), ei = 0.5 * (ai + bi);
      var dr = ar - br, di = ai - bi;
      var orr = 0.5 * di, oi = -0.5 * dr;         // O = (Z - conj)/(2i)
      var wr = sc.cw[k], wi = sc.sw[k];           // W^k = e^{-2πik/n}
      var tr = wr * orr - wi * oi, ti = wr * oi + wi * orr;
      var xr = er + tr, xi = ei + ti;
      pw[k] = xr * xr + xi * xi;
    }
    var nyq = zr[0] - zi[0];                      // X[n/2] حقیقی است
    pw[m] = nyq * nyq;

    // معکوسِ حقیقی: acf = IDFT(power)
    for (k = 0; k < m; k++) {
      if (k === 0) {
        zr[0] = 0.5 * (pw[0] + pw[m]);
        zi[0] = 0.5 * (pw[0] - pw[m]);
        continue;
      }
      var a2 = pw[k], b2 = pw[m - k];
      var e2 = 0.5 * (a2 + b2), od = 0.5 * (a2 - b2);
      var wr2 = sc.cw[k], wi2 = -sc.sw[k];        // W^{-k}
      zr[k] = e2 - wi2 * od;
      zi[k] = wr2 * od;
    }
    ifft(zr, zi);
    var out = sc.acf;
    for (i = 0; i < m; i++) { out[i << 1] = zr[i]; out[(i << 1) + 1] = zi[i]; }
    return out;
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۲ — استخراج F0 (هم‌ارز Praat to_pitch_ac + very_accurate)
     روش: خودهمبستگی نرمال‌شدهٔ پنجره‌ای (Boersma) + گام ۵ میلی‌ثانیه
     ══════════════════════════════════════════════════════════════════════ */
  /** حافظهٔ موقتِ قابل استفادهٔ مجدد برای یک فریم (حذف تخصیص در حلقهٔ اصلی) */
  function pitchScratch(L, tauMax, nfft) {
    var sc = rfftScratch(nfft);
    sc.nfft = nfft;
    sc.cs = new Float64Array(L + 1);
    sc.rs = new Float64Array(tauMax + 2);
    return sc;
  }

  /**
   * محاسبهٔ F0 یک فریم با بافرهای از پیش تخصیص‌یافته.
   * دقیقاً همان ریاضیات pitchFrame؛ فقط بدون ساختن آرایهٔ تازه در هر فریم.
   */
  function pitchFrameInto(x, sr, fmin, fmax, sc, strict) {
    var L = x.length;
    var tauMin = Math.max(2, Math.floor(sr / fmax));
    var tauMax = Math.min(L - 2, Math.ceil(sr / fmin));
    if (tauMax <= tauMin) return { f0: 0, strength: 0 };

    var i;
    var acf = rfftAcf(x, L, sc.nfft, sc);    // acf[τ] = Σ x[i] x[i+τ]

    // انرژی تجمعی برای نرمال‌سازی دقیق (لبه‌های پنجره)
    var cs = sc.cs;
    cs[0] = 0;
    for (i = 0; i < L; i++) cs[i + 1] = cs[i] + x[i] * x[i];
    var etot = cs[L];

    var best = -1, bestTau = 0;
    var rs = sc.rs;
    for (var tau = tauMin; tau <= tauMax; tau++) {
      var e0 = cs[L - tau];                 // انرژی بخش اول
      var e1 = etot - cs[tau];              // انرژی بخش دوم
      var den = Math.sqrt(Math.max(e0 * e1, 1e-20));
      var r = den > 1e-12 ? acf[tau] / den : 0;
      rs[tau] = r;
      if (r > best) { best = r; bestTau = tau; }
    }
    if (bestTau === 0) return { f0: 0, strength: 0 };

    // قاعدهٔ «پیک زودتر با کیفیت نزدیک» — مثل Praat، از خطای زیراکتاو جلوگیری می‌کند
    var thr = best * 0.90;
    for (var t2 = tauMin; t2 <= bestTau; t2++) {
      if (rs[t2] >= thr) {
        var isPeak = (t2 > tauMin && t2 < tauMax && rs[t2] >= rs[t2 - 1] && rs[t2] >= rs[t2 + 1]);
        if (isPeak || Math.abs(rs[t2] - best) < 1e-6) { bestTau = t2; best = rs[t2]; break; }
      }
    }

    var f0 = 0, strength = clamp(best, 0, 1);

    // ── حالت سخت‌گیرانه (اجرای زنده) ──
    // اگر بهترین تأخیر روی لبهٔ بازهٔ جست‌وجو افتاده باشد، یعنی «دورهٔ واقعی»
    // بیرون از بازه است (هام/رامبل/نویز) — نه یک نت. همچنین پیک باید
    // یک ماکسیمم محلیِ واقعی باشد، نه سرازیری/سربالایی بریده‌شده.
    if (strict) {
      if (bestTau <= tauMin || bestTau >= tauMax) return { f0: 0, strength: strength };
      if (rs[bestTau] < rs[bestTau - 1] || rs[bestTau] <= rs[bestTau + 1]) {
        return { f0: 0, strength: strength };
      }
    }

    // درون‌یابی سهمی برای دقت زیر-نمونه
    if (bestTau > tauMin && bestTau < tauMax) {
      var a = rs[bestTau - 1], b = rs[bestTau], c = rs[bestTau + 1];
      var den2 = (a - 2 * b + c);
      var delta = Math.abs(den2) > 1e-12 ? 0.5 * (a - c) / den2 : 0;
      delta = clamp(delta, -0.5, 0.5);
      f0 = sr / (bestTau + delta);
    } else {
      f0 = sr / bestTau;
    }
    if (strength < VOICING_THRESHOLD || f0 < fmin * 0.98 || f0 > fmax * 1.02) {
      return { f0: 0, strength: strength };
    }
    return { f0: f0, strength: strength };
  }

  /** نسخهٔ ساده (بدون بافر مشترک) — همان نتیجه، برای مصارف تک‌فریمی */
  function pitchFrame(x, sr, fmin, fmax, strict) {
    var L = x.length;
    var tauMax = Math.min(L - 2, Math.ceil(sr / fmin));
    var nfft = nextPow2(L + tauMax + 1);
    return pitchFrameInto(x, sr, fmin, fmax, pitchScratch(L, tauMax, nfft), strict);
  }

  /* پارامترهای پنجره/گام — یک بار محاسبه، در همهٔ تکه‌ها یکسان */
  function pitchWindow(y, sr, fmin) {
    var winN = Math.max(256, Math.round(WIN_PERIODS / fmin * sr));
    if (winN % 2) winN++;
    var hopN = Math.round(TIME_STEP * sr);
    var nFrames = Math.max(1, Math.floor((y.length - winN) / hopN) + 1);
    return { winN: winN, hopN: hopN, nFrames: nFrames };
  }

  /**
   * محاسبهٔ F0 فقط برای بازهٔ فریم‌های [i0, i1) — پایهٔ پردازش موازی/جریانی.
   * خروجی بر حسب اندیس مطلق همان pitchTrack است (freqs[k] ↔ فریم i0+k).
   */
  function pitchTrackRange(y, sr, fmin, fmax, opt, i0, i1, win, yBase) {
    var strict = !!(opt && opt.strict);
    var winN = opt.winN, hopN = opt.hopN;
    var n = Math.max(0, i1 - i0);
    var freqs = new Float32Array(n);
    var strength = new Float32Array(n);
    if (!n) return { i0: i0, i1: i1, freqs: freqs, strength: strength };
    if (!win) win = hann(winN);
    yBase = yBase || 0;                 // آفست نمونه‌ای y در سیگنال اصلی
    var buf = new Float64Array(winN);
    var tauMax = Math.min(winN - 2, Math.ceil(sr / fmin));
    var sc = pitchScratch(winN, tauMax, nextPow2(winN + tauMax + 1));
    for (var k = 0; k < n; k++) {
      var i = i0 + k;
      var start = i * hopN - yBase;
      var m = 0, j;
      for (j = 0; j < winN; j++) {
        var v = y[start + j] || 0;
        buf[j] = v * win[j];
        m += buf[j];
      }
      m /= winN;
      for (j = 0; j < winN; j++) buf[j] -= m;

      var r = pitchFrameInto(buf, sr, fmin, fmax, sc, strict);
      freqs[k] = r.f0;
      strength[k] = r.strength;
    }
    return { i0: i0, i1: i1, freqs: freqs, strength: strength };
  }

  function pitchTrack(y, sr, fmin, fmax, onProgress) {
    fmin = fmin || MIN_F0;
    fmax = fmax || MAX_F0;
    var w = pitchWindow(y, sr, fmin);
    var nFrames = w.nFrames;
    var times = new Float32Array(nFrames);
    for (var i = 0; i < nFrames; i++) times[i] = i * TIME_STEP;

    var freqs = new Float32Array(nFrames);
    var strength = new Float32Array(nFrames);
    var win = hann(w.winN);

    // جریانی: قطعه‌قطعه تا حافظهٔ موقت کم بماند و پیشرفت زنده گزارش شود
    var CHUNK = 1200;   // ۶ ثانیه در هر گام
    for (var a = 0; a < nFrames; a += CHUNK) {
      var b = Math.min(nFrames, a + CHUNK);
      var part = pitchTrackRange(y, sr, fmin, fmax, w, a, b, win);
      freqs.set(part.freqs, a);
      strength.set(part.strength, a);
      if (onProgress) onProgress(b / nFrames);
    }
    if (onProgress) onProgress(1.0);
    return { times: times, freqs: freqs, strength: strength, winN: w.winN, hopN: w.hopN, nFrames: nFrames };
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۳ — اصلاح «نردبان هارمونیک» (اکتاو/پنجم/چهارم)
     ══════════════════════════════════════════════════════════════════════ */
  function frameScores(y, sr, tFrames, baseF, ratios, winSec, nfft, maxHz, maxH) {
    winSec = winSec || 0.046; nfft = nfft || 4096; maxHz = maxHz || 3600.0; maxH = maxH || 12;
    var winN = Math.max(256, Math.round(sr * winSec));
    var n = tFrames.length;
    var out = new Float64Array(n * ratios.length);
    if (!n) return { scores: out, n: 0, nRatios: ratios.length };
    var w = hann(winN);
    var binHz = sr / nfft;
    var nBins = (nfft >> 1) + 1;
    var bandLo = Math.max(1, Math.floor(MIN_F0 * 0.9 / binHz));
    var bandHi = Math.min(nBins - 1, Math.floor(4000.0 / binHz));
    var wgt = new Float64Array(maxH);
    for (var h = 0; h < maxH; h++) wgt[h] = 1.0 / (h + 1);
    wgt[0] = 0.0;   // هارمونیک اول وزن ندارد (گم‌شدن f0 طبیعی است)

    for (var f = 0; f < n; f++) {
      var center = Math.round(tFrames[f] * sr);
      var start = center - (winN >> 1);
      var frame = new Float32Array(winN);
      for (var i = 0; i < winN; i++) {
        var idx = start + i;
        frame[i] = (idx >= 0 && idx < y.length) ? y[idx] : 0;
      }
      var mag = rfftMag(frame, nfft, w);

      // مرجع هر فریم: بیشینهٔ باند
      var ref = 1e-12;
      for (var b = bandLo; b <= bandHi; b++) if (mag[b] > ref) ref = mag[b];

      for (var ri = 0; ri < ratios.length; ri++) {
        var num = 0, den = 0;
        for (var k = 1; k <= maxH; k++) {
          var pos = (baseF[f] * ratios[ri] * k) / binHz;
          if (pos < 1 || pos >= nBins - 2 || pos * binHz > maxHz) continue;
          var ip = Math.round(pos);
          var peak = 0;
          for (var off = -2; off <= 2; off++) {
            var j = ip + off;
            if (j < 0 || j >= nBins) continue;
            if (mag[j] > peak) peak = mag[j];
          }
          var db = 20.0 * Math.log10(Math.max(peak, 1e-12) / ref);
          var sk = clamp(db / 25.0, -1.0, 0.0);
          num += sk * wgt[k - 1];
          den += wgt[k - 1];
        }
        out[f * ratios.length + ri] = den > 0.35 ? num / Math.max(den, 1e-9) : -1.0;
      }
    }
    return { scores: out, n: n, nRatios: ratios.length };
  }

  function spectralOctaveFix(y, sr, times, freqs, fmin, fmax) {
    var n = freqs.length;
    var out = Float32Array.from(freqs);
    var ratio = new Float32Array(n).fill(1);
    var nUp = 0, nDown = 0;
    if (!n) return { freqs: out, nUp: 0, nDown: 0, ratio: ratio };
    fmin = fmin || MIN_F0; fmax = fmax || MAX_F0;

    // ران‌های پیوستهٔ صدادار
    var runs = [];
    var i = 0;
    while (i < n) {
      if (!(freqs[i] > 0)) { i++; continue; }
      var j = i + 1;
      while (j < n && freqs[j] > 0 && (times[j] - times[j - 1]) < 0.08) j++;
      if (j - i >= 3 && (times[j - 1] - times[i]) >= 0.10) runs.push([i, j]);
      i = j;
    }

    for (var r = 0; r < runs.length; r++) {
      var i0 = runs[r][0], i1 = runs[r][1];
      var seg = freqs.subarray(i0, i1);
      var med = percentile(seg, 50);
      if (!(med > 0)) continue;
      var stride = Math.max(1, Math.ceil((i1 - i0) / 48));
      var tSel = [], fSel = [];
      for (var k = i0; k < i1; k += stride) { tSel.push(times[k]); fSel.push(freqs[k]); }
      if (tSel.length < 2) continue;

      var sc = frameScores(y, sr, tSel, fSel, LADDER, 0.046, 4096, 3600.0, 12);
      var pooled = new Float64Array(LADDER.length);
      for (var ri = 0; ri < LADDER.length; ri++) {
        var col = new Float64Array(sc.n);
        for (var fI = 0; fI < sc.n; fI++) col[fI] = sc.scores[fI * LADDER.length + ri];
        pooled[ri] = median(col);
      }
      var best = -Infinity;
      for (var q = 0; q < pooled.length; q++) if (pooled[q] > best) best = pooled[q];
      if (best < -0.5) continue;

      // از میان فرض‌هایی که تقریباً هم‌امتیازند، «پایین‌ترین» درست است
      var targetR = Infinity;
      for (var q2 = 0; q2 < pooled.length; q2++) {
        if (pooled[q2] >= best - 0.10) targetR = Math.min(targetR, LADDER[q2]);
      }
      if (!isFinite(targetR) || Math.abs(targetR - 1.0) < 1e-6) continue;
      var target = med * targetR;
      var scale = target / med;

      for (var kk = i0; kk < i1; kk++) {
        var fv = freqs[kk];
        var qq = fv / med;
        var rr = LADDER[0];
        for (var L = 0; L < LADDER.length; L++) {
          if (Math.abs(cents(qq, LADDER[L])) < Math.abs(cents(qq, rr))) rr = LADDER[L];
        }
        var nv = (Math.abs(cents(qq, rr)) <= 70.0) ? target * rr : fv * scale;
        if (Math.abs(cents(nv, fv)) < 15.0) nv = fv;
        if (nv < fmin * 0.95 || nv > fmax * 1.05) nv = fv;
        ratio[kk] = fv > 0 ? nv / fv : 1.0;
        if (nv < fv * 0.98) nDown++;
        else if (nv > fv * 1.02) nUp++;
        out[kk] = nv;
      }
    }
    return { freqs: out, nUp: nUp, nDown: nDown, ratio: ratio };
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۴ — موتور دوم مستقل (YIN) فقط برای «هم‌رأیی/اطمینان»
     ══════════════════════════════════════════════════════════════════════ */
  function yinParams(y, sr, fmin, fmax, hopSec) {
    hopSec = hopSec || 0.01;
    var W = Math.round(0.025 * sr);
    var hop = Math.round(hopSec * sr);
    var tauMin = Math.max(2, Math.floor(sr / fmax));
    var tauMax = Math.min(W - 2, Math.ceil(sr / fmin));
    var nFrames = Math.max(1, Math.floor((y.length - W - tauMax) / hop) + 1);
    return { W: W, hop: hop, tauMin: tauMin, tauMax: tauMax, nFrames: nFrames, hopSec: hopSec };
  }

  /** YIN فقط برای بازهٔ فریم‌های [i0, i1) — پایهٔ اجرای موازی */
  function yinTrackRange(y, sr, fmin, fmax, p, i0, i1, yBase) {
    var W = p.W, hop = p.hop, tauMin = p.tauMin, tauMax = p.tauMax;
    var n = Math.max(0, i1 - i0);
    var f0 = new Float32Array(n);
    var prob = new Float32Array(n);
    if (!n) return { i0: i0, i1: i1, f0: f0, prob: prob };
    var nfft = nextPow2(W + tauMax + 1);
    var sc = rfftScratch(nfft);
    var x = new Float64Array(W);
    yBase = yBase || 0;
    for (var q = 0; q < n; q++) {
      var f = i0 + q;
      var start = f * hop - yBase;
      var i;
      for (i = 0; i < W; i++) x[i] = y[start + i] || 0;

      var acf = rfftAcf(x, W, nfft, sc);        // FFT حقیقی (نصف کار)

      var cs = new Float64Array(W + 1);
      for (i = 0; i < W; i++) cs[i + 1] = cs[i] + x[i] * x[i];
      var etot = cs[W];

      // تابع تفاضل نرمال‌شدهٔ YIN
      var d = new Float64Array(tauMax + 1);
      for (var tau = 1; tau <= tauMax; tau++) {
        var e1 = (tau < W) ? cs[tau] : etot;
        d[tau] = Math.max(0, etot - e1 + (etot - e1) - 2 * acf[tau]);
      }
      var cum = 0, bestTau = -1, bestVal = Infinity;
      var cmnd = new Float64Array(tauMax + 1);
      for (var t = 1; t <= tauMax; t++) {
        cum += d[t];
        cmnd[t] = cum > 1e-12 ? d[t] * t / cum : 1.0;
      }
      for (var t2 = tauMin; t2 <= tauMax; t2++) {
        if (cmnd[t2] < 0.15) { bestTau = t2; break; }
        if (cmnd[t2] < bestVal) { bestVal = cmnd[t2]; bestTau = t2; }
      }
      if (bestTau > 0) {
        // درون‌یابی سهمی
        var tt = bestTau;
        if (tt > 1 && tt < tauMax) {
          var a2 = cmnd[tt - 1], b2 = cmnd[tt], c2 = cmnd[tt + 1];
          var den2 = (a2 - 2 * b2 + c2);
          var dl = Math.abs(den2) > 1e-12 ? 0.5 * (a2 - c2) / den2 : 0;
          tt = tt + clamp(dl, -0.5, 0.5);
        }
        var hz = sr / tt;
        f0[q] = (hz >= fmin && hz <= fmax) ? hz : 0;
        prob[q] = clamp(1.0 - cmnd[bestTau], 0, 1);
      }
    }
    return { i0: i0, i1: i1, f0: f0, prob: prob };
  }

  function yinTrack(y, sr, fmin, fmax, duration, hopSec) {
    if (duration > PYIN_MAX_SEC) return null;
    var p = yinParams(y, sr, fmin, fmax, hopSec);
    var f0 = new Float32Array(p.nFrames);
    var prob = new Float32Array(p.nFrames);
    var CHUNK = 600;
    for (var a = 0; a < p.nFrames; a += CHUNK) {
      var b = Math.min(p.nFrames, a + CHUNK);
      var part = yinTrackRange(y, sr, fmin, fmax, p, a, b);
      f0.set(part.f0, a);
      prob.set(part.prob, a);
    }
    return { f0: f0, prob: prob, hop: p.hopSec };
  }

  function fuseWithSecond(times, freqs, strength, second, hop) {
    hop = hop || 0.01;
    var n = times.length;
    var conf = new Float32Array(n);
    for (var i = 0; i < n; i++) conf[i] = clamp(strength[i], 0, 1);
    if (!second || !second.f0.length) {
      for (i = 0; i < n; i++) conf[i] = freqs[i] > 0 ? Math.max(conf[i], 0.55) : 0;
      return { freqs: freqs, conf: conf, agreement: null };
    }
    var m = second.f0.length;
    var pF0 = new Float32Array(n).fill(NaN);
    var pProb = new Float32Array(n).fill(NaN);
    for (i = 0; i < n; i++) {
      var idx = Math.round(times[i] / hop);
      if (idx >= 0 && idx < m) { pF0[i] = second.f0[idx]; pProb[i] = second.prob[idx]; }
    }
    var voiced = 0, agreeN = 0;
    for (i = 0; i < n; i++) {
      var hasMain = freqs[i] > 0;
      var hasSecond = isFinite(pF0[i]) && pF0[i] > 0 && pProb[i] > 0.25;
      if (hasMain) voiced++;
      if (hasMain && hasSecond) {
        var ok = Math.abs(cents(freqs[i], pF0[i])) <= 70.0;
        if (ok) {
          agreeN++;
          conf[i] = Math.min(1.0, conf[i] + 0.25);
          if (strength[i] < 0.45) conf[i] = Math.min(1.0, conf[i] + 0.1);
        } else {
          conf[i] *= 0.75;
        }
      }
      if (hasMain) conf[i] = Math.max(conf[i], 0.25); else conf[i] = 0;
    }
    var agreement = voiced ? Math.round(1000 * 100 * agreeN / voiced) / 1000 : 0.0;
    return { freqs: freqs, conf: conf, agreement: Math.round(agreement * 10) / 10 };
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۵ — پاک‌سازی، هموارسازی، تفکیک نت، پالایش
     ══════════════════════════════════════════════════════════════════════ */
  function smoothPitchContour(freqs, windowSize) {
    windowSize = windowSize || 3;
    var n = freqs.length;
    var out = Float32Array.from(freqs);
    var half = Math.floor(windowSize / 2);
    var i = 0;
    while (i < n) {
      if (!(out[i] > 0)) { i++; continue; }
      var j = i;
      while (j < n && out[j] > 0) j++;
      var seg = out.subarray(i, j);
      if (seg.length >= windowSize) {
        var copy = Float32Array.from(seg);
        for (var k = 0; k < seg.length; k++) {
          var lo = Math.max(0, k - half), hi = Math.min(seg.length - 1, k + half);
          var tmp = [];
          for (var q = lo; q <= hi; q++) tmp.push(copy[q]);
          seg[k] = median(tmp);
        }
      }
      i = j;
    }
    return out;
  }

  function removeOctaveSpecks(times, freqs, spanSec, tolCents) {
    spanSec = spanSec || 0.25; tolCents = tolCents || 900;
    var out = Float32Array.from(freqs);
    var n = freqs.length;
    if (n < 5) return out;
    var dt = median(Array.from(times.subarray(1).map(function (v, i) { return v - times[i]; })));
    var win = Math.max(3, Math.round(spanSec / Math.max(1e-3, dt || 0.005)));
    var half = win >> 1;
    for (var i = 0; i < n; i++) {
      if (!(out[i] > 0)) continue;
      var lo = Math.max(0, i - half), hi = Math.min(n, i + half + 1);
      var neigh = [];
      for (var j = lo; j < hi; j++) if (out[j] > 0) neigh.push(out[j]);
      if (neigh.length < 3) continue;
      var med = median(neigh);
      var c = cents(out[i], med);
      if (Math.abs(Math.abs(c) - 1200.0) < (1200.0 - tolCents)) out[i] = med;
    }
    return out;
  }

  function segmentNotes(times, freqs, conf, opts) {
    opts = opts || {};
    var minDuration = opts.min_duration || 0.08;
    var stabilityCents = opts.stability_cents || 45;
    var mergeGap = opts.merge_gap != null ? opts.merge_gap : 0.10;
    var mergeCents = opts.merge_cents || 55;
    var n = freqs.length;
    if (!n) return [];
    conf = conf || new Float32Array(n).fill(1);

    var dt = n > 1 ? median(Array.from(times.subarray(1).map(function (v, i) { return v - times[i]; }))) : 0.01;
    var contCents = stabilityCents * 1.6;

    var raw = [];
    var i = 0;
    while (i < n) {
      if (!(freqs[i] > 0) || conf[i] < 0.12) { i++; continue; }
      var j = i;
      var vals = [freqs[i]], weights = [Math.max(conf[i], 1e-3)];
      while (j + 1 < n && freqs[j + 1] > 0 && conf[j + 1] >= 0.12) {
        var lo = Math.max(0, vals.length - 25);
        var num = 0, den = 0;
        for (var q = lo; q < vals.length; q++) { num += vals[q] * weights[q]; den += weights[q]; }
        var ref = den > 0 ? num / den : vals[vals.length - 1];
        if (Math.abs(cents(freqs[j + 1], ref)) > contCents) break;
        j++;
        vals.push(freqs[j]);
        weights.push(Math.max(conf[j], 1e-3));
      }
      raw.push({ i: i, j: j, t0: times[i], t1: times[j] });
      i = j + 1;
    }

    var merged = [];
    for (var r = 0; r < raw.length; r++) {
      var seg = raw[r];
      if (!merged.length) { merged.push(seg); continue; }
      var prev = merged[merged.length - 1];
      var gap = seg.t0 - prev.t1;
      var a = freqs.subarray(prev.i, prev.j + 1);
      var b = freqs.subarray(seg.i, seg.j + 1);
      if (gap <= mergeGap && a.length && b.length) {
        if (Math.abs(cents(median(b), median(a))) <= mergeCents) {
          prev.j = seg.j; prev.t1 = seg.t1;
          continue;
        }
      }
      merged.push(seg);
    }

    var notes = [];
    for (var m = 0; m < merged.length; m++) {
      var s = merged[m];
      var duration = s.t1 - s.t0 + dt;
      if (duration < minDuration) continue;
      var f = freqs.subarray(s.i, s.j + 1);
      var c = conf.subarray(s.i, s.j + 1);
      if (f.length < 2) continue;
      var gf = [], gc = [];
      for (var k = 0; k < f.length; k++) {
        if (isFinite(f[k]) && f[k] > 0) { gf.push(f[k]); gc.push(Math.max(c[k], 1e-3)); }
      }
      if (!gf.length) continue;
      var num2 = 0, den2 = 0;
      for (k = 0; k < gf.length; k++) { num2 += gf[k] * gc[k]; den2 += gc[k]; }
      var f0 = den2 > 0 ? num2 / den2 : 0;
      if (!isFinite(f0) || !(f0 > 0)) continue;
      var dev = gf.map(function (v) { return Math.abs(cents(v, f0)); });
      var stability = percentile(dev, 75);
      var trend = gf.length > 1 ? cents(gf[gf.length - 1], f0) - cents(gf[0], f0) : 0;
      notes.push({
        start: Math.round(s.t0 * 1000) / 1000,
        end: Math.round(s.t1 * 1000) / 1000,
        duration: Math.round(duration * 1000) / 1000,
        f0_hz: Math.round(f0 * 100) / 100,
        stability_cents: Math.round(stability * 10) / 10,
        confidence: Math.round(mean(gc) * 100) / 100,
        glide_cents: Math.round(trend * 10) / 10,
        n_frames: gf.length
      });
    }
    return notes;
  }

  function parabolicPeak(mag, i) {
    if (i <= 0 || i >= mag.length - 1) return i;
    var a = mag[i - 1], b = mag[i], c = mag[i + 1];
    var den = (a - 2 * b + c);
    if (Math.abs(den) < 1e-12) return i;
    return i + 0.5 * (a - c) / den;
  }

  /* بافرهای مشترک طیف (بدون تخصیص در حلقهٔ نت‌ها) */
  var _specScratch = {}, _specFrame = null;
  function spectrumScratch(nfft) {
    var k = 's' + nfft;
    if (!_specScratch[k]) {
      var sc = rfftScratch(nfft);
      sc.nfft = nfft;
      _specScratch[k] = sc;
    }
    return _specScratch[k];
  }
  function spectrumFrame(winN) {
    if (!_specFrame || _specFrame.length < winN) _specFrame = new Float64Array(winN);
    return _specFrame;
  }

  function noteSpectrum(y, sr, t0, t1, winSec, nfft) {
    winSec = winSec || 0.06; nfft = nfft || 16384;
    var dur = Math.max(0, t1 - t0);
    var pad = Math.min(0.15 * dur, 0.08);
    var a = t0 + pad, b = t1 - pad;
    if (b - a < 0.04) { a = t0; b = t1; }
    var winN = Math.round(sr * winSec);
    var i0 = Math.max(0, Math.round(a * sr) - (winN >> 1));
    var i1 = Math.min(y.length, Math.round(b * sr) + winN);
    if (i1 - i0 < winN) return null;

    var starts = [];
    for (var s = Math.max(0, Math.round(a * sr) - i0); s < Math.max(0, Math.round(b * sr) - i0); s += Math.max(1, winN >> 1)) {
      if (s + winN <= i1 - i0) starts.push(s);
    }
    if (!starts.length) starts = [Math.max(0, ((i1 - i0) >> 1) - (winN >> 1))];

    var w = hann(winN);
    var acc = new Float64Array((nfft >> 1) + 1);
    var sc = spectrumScratch(nfft);
    var frame = spectrumFrame(winN);
    for (var si = 0; si < starts.length; si++) {
      for (var k = 0; k < winN; k++) frame[k] = (y[i0 + starts[si] + k] || 0) * w[k];
      var pw = rfftPack(frame, winN, nfft, sc);   // FFT حقیقی (نصف کار)
      for (var q = 0; q < acc.length; q++) acc[q] += Math.sqrt(pw[q]);
    }
    for (q = 0; q < acc.length; q++) acc[q] /= starts.length;
    return acc;
  }

  function refineNoteF0(y, sr, t0, t1, f0, nfft, maxK, maxHz) {
    nfft = nfft || 16384; maxK = maxK || 10; maxHz = maxHz || 4000.0;
    if (!(f0 > 0) || t1 - t0 < 0.08) return { f0: null, support: 0 };
    var mag = noteSpectrum(y, sr, t0, t1, 0.06, nfft);
    if (!mag || mag.length < 16) return { f0: null, support: 0 };
    var binHz = sr / nfft;
    var nBins = mag.length;
    var support = 0;
    var maxMag = 1e-12;
    for (var i = 0; i < nBins; i++) if (mag[i] > maxMag) maxMag = mag[i];

    for (var k = 1; k <= maxK; k++) {
      var target = k * f0;
      if (target > Math.min(maxHz, binHz * (nBins - 3))) break;
      var span = target * (Math.pow(2, 45 / 1200.0) - 1);
      var lo = Math.max(1, Math.floor((target - span) / binHz));
      var hi = Math.min(nBins - 2, Math.ceil((target + span) / binHz));
      if (hi <= lo) continue;
      var pk = 0;
      for (var j = lo; j <= hi; j++) if (mag[j] > pk) pk = mag[j];
      if (20 * Math.log10(pk / maxMag) >= -30.0) support++;
    }

    var est = f0;
    for (var it = 0; it < 3; it++) {
      var num = 0, den = 0;
      for (var k2 = 1; k2 <= maxK; k2++) {
        var tgt = k2 * est;
        if (tgt > Math.min(maxHz, binHz * (nBins - 3))) break;
        var sp = tgt * (Math.pow(2, 45 / 1200.0) - 1);
        var l2 = Math.max(1, Math.floor((tgt - sp) / binHz));
        var h2 = Math.min(nBins - 2, Math.ceil((tgt + sp) / binHz));
        if (h2 <= l2) continue;
        var bi = l2, bv = -1;
        for (var jj = l2; jj <= h2; jj++) if (mag[jj] > bv) { bv = mag[jj]; bi = jj; }
        var peakHz = parabolicPeak(mag, bi) * binHz;
        if (!(peakHz > 0)) continue;
        if (Math.abs(cents(peakHz, tgt)) > 45.0) continue;
        var w2 = mag[bi] / Math.sqrt(k2);
        num += w2 * k2 * peakHz;
        den += w2 * k2 * k2;
      }
      if (den <= 0) break;
      var newEst = num / den;
      if (!isFinite(newEst) || !(newEst > 0)) break;
      if (Math.abs(cents(newEst, est)) < 0.05) { est = newEst; break; }
      est = newEst;
    }
    if (!isFinite(est) || !(est > 0)) return { f0: null, support: support };
    if (Math.abs(cents(est, f0)) > 70.0) return { f0: null, support: support };
    return { f0: est, support: support };
  }

  /**
   * پالایش کامل یک نت: f0 دقیق + اصلاح کلاس اکتاو.
   * (اگر ردیاب روی نصف/یک‌سوم فرکانس قفل کرده باشد، همین‌جا برمی‌گردد.)
   */
  function refineNoteF0WithOctave(y, sr, t0, t1, f0, nfft, maxK, maxHz) {
    var r = refineNoteF0(y, sr, t0, t1, f0, nfft, maxK, maxHz);
    nfft = nfft || 16384;
    var mag = noteSpectrum(y, sr, t0, t1, 0.06, nfft);
    if (!mag) return r;
    var binHz = sr / nfft;
    var cls = octaveClassOf(y, sr, t0, t1, f0, mag, nfft, binHz);
    if (!cls.changed) return r;
    var r2 = refineNoteF0(y, sr, t0, t1, cls.f0, nfft, maxK, maxHz);
    var out = {
      f0: (r2.f0 != null) ? r2.f0 : cls.f0,
      support: Math.max(r2.support || 0, r.support || 0),
      octave_fix: Math.round(cents(cls.f0, f0))
    };
    return out;
  }

  /* ══════════════════════════════════════════════════════════════════════
     سالینس هارمونیک + اصلاح «کلاس اکتاو» یک نت
     ----------------------------------------------------------------------
     مشکل واقعی: در بعضی نت‌ها ردیاب روی «نصف فرکانس» قفل می‌کند (اکتاوِ پایین)
     و هیچ‌کدام از مراحل بعدی آن را برنمی‌گرداند، چون پالایش فقط ±۷۰ سنت
     اطراف همان f0 غلط را نگاه می‌کرد.
     راه‌حل: برای هر نت، نامزدهای f0/3، f0/2، f0، 2f0، 3f0 سنجیده می‌شوند و
     نامزدی که «انرژی هارمونیک‌ها» را بهتر توضیح می‌دهد انتخاب می‌شود.
     امتیاز = میانگین وزنی قله‌های طیفی روی k·f (وزن 1/√k) با جریمهٔ نبودِ
     هارمونیک‌های فرد — همان چیزی که اکتاوِ اشتباه را لو می‌دهد.
     ══════════════════════════════════════════════════════════════════════ */
  function harmonicSalience(mag, binHz, f0, maxK, maxHz) {
    maxK = maxK || 12; maxHz = maxHz || 4000.0;
    var nBins = mag.length;
    var maxMag = 1e-12;
    for (var i = 0; i < nBins; i++) if (mag[i] > maxMag) maxMag = mag[i];
    var num = 0, den = 0, support = 0, oddMissing = 0, oddTotal = 0;
    for (var k = 1; k <= maxK; k++) {
      var target = k * f0;
      if (target > Math.min(maxHz, binHz * (nBins - 3))) break;
      var span = target * (Math.pow(2, 45 / 1200.0) - 1);
      var lo = Math.max(1, Math.floor((target - span) / binHz));
      var hi = Math.min(nBins - 2, Math.ceil((target + span) / binHz));
      if (hi <= lo) continue;
      var pk = 0;
      for (var j = lo; j <= hi; j++) if (mag[j] > pk) pk = mag[j];
      var rel = pk / maxMag;
      var w = 1 / Math.sqrt(k);
      num += w * rel;
      den += w;
      if (20 * Math.log10(Math.max(rel, 1e-12)) >= -32.0) support++;
      if (k % 2 === 1 && k > 1) {
        oddTotal++;
        if (rel < 0.06) oddMissing++;
      }
    }
    var score = den > 0 ? num / den : 0;
    /* اگر هارمونیک‌های فرد غایب باشند، این f0 نصفِ نتِ واقعی است */
    if (oddTotal >= 2) score *= Math.pow(0.45, oddMissing / oddTotal);
    return { score: score, support: support, oddMissing: oddMissing };
  }

  /** بهترین کلاس اکتاو برای یک نت (f/3, f/2, f, 2f, 3f) */
  function octaveClassOf(y, sr, t0, t1, f0, mag, nfft, binHz) {
    if (!(f0 > 0)) return { f0: f0, changed: false };
    mag = mag || noteSpectrum(y, sr, t0, t1, 0.06, nfft || 16384);
    if (!mag || mag.length < 16) return { f0: f0, changed: false };
    binHz = binHz || (sr / (nfft || 16384));
    var cands = [f0 / 3, f0 / 2, f0, 2 * f0, 3 * f0];
    var best = null;
    for (var i = 0; i < cands.length; i++) {
      var f = cands[i];
      if (!(f >= MIN_F0 * 0.98) || f > 4200) continue;
      var sc = harmonicSalience(mag, binHz, f);
      /* نامزدهای بالاتر باید امتیاز به‌مراتب بهتری بدهند تا «دو برابر» نشود */
      var bias = (f > f0 * 1.5) ? 0.72 : 1.0;
      sc.score *= bias;
      if (!best || sc.score > best.score) best = { f: f, score: sc.score, support: sc.support };
    }
    if (!best) return { f0: f0, changed: false };
    var base = harmonicSalience(mag, binHz, f0);
    /* تغییر فقط با شاهد روشن: ۱۲٪ بهتر، یا نصف‌شدگی با هارمونیک فردِ غایب */
    var gain = base.score > 0 ? best.score / base.score : 1;
    var down = best.f < f0 * 0.9, up = best.f > f0 * 1.4;
    var halfFix = (Math.abs(best.f / f0 - 0.5) < 0.02 || Math.abs(best.f / f0 - 0.3334) < 0.02)
      ? (base.oddMissing > 0 && gain > 0.9)
      : false;
    /* بالا بردن f0 (رفع اکتاوِ پایین) شاهد قوی‌تر می‌خواهد تا اکتاوِ بالا نسازیم */
    var need = up ? 1.22 : (down ? 1.10 : 1.12);
    if (gain >= need || halfFix) {
      return { f0: best.f, changed: Math.abs(best.f - f0) > 1e-6, gain: gain, support: best.support };
    }
    return { f0: f0, changed: false, gain: gain };
  }

  /** پالایش نت‌های [i0, i1) — فقط محاسبه، بدون تغییر (پایهٔ اجرای موازی) */
  function refineNotesRange(y, sr, notes, i0, i1, yBaseSec) {
    var n = Math.max(0, i1 - i0);
    var out = new Array(n);
    yBaseSec = yBaseSec || 0;
    for (var k = 0; k < n; k++) {
      var nt = notes[i0 + k];
      out[k] = refineNoteF0WithOctave(y, sr, nt.start - yBaseSec, nt.end - yBaseSec, nt.f0_hz);
    }
    return out;
  }

  function applyRefinements(notes, results, i0) {
    var refined = 0;
    for (var k = 0; k < results.length; k++) {
      var nt = notes[i0 + k], r = results[k];
      nt.harmonics = r.support;
      if (r.f0 == null) continue;
      if (r.octave_fix) nt.octave_fix_cents = r.octave_fix;
      nt.f0_hz_coarse = nt.f0_hz;
      nt.f0_hz = Math.round(r.f0 * 1000) / 1000;
      refined++;
    }
    return refined;
  }

  function refineNotes(y, sr, notes) {
    if (!notes || !notes.length) return { notes: notes, refined: 0 };
    var refined = applyRefinements(notes, refineNotesRange(y, sr, notes, 0, notes.length), 0);
    return { notes: notes, refined: refined };
  }

  /* ══════════════════════════════════════════════════════════════════════
     شار طیفی (spectral flux) — آشکارساز آغاز نت
     ----------------------------------------------------------------------
     پوش دامنه در «لگاتو» (نت بعدی بدون افت انرژی) لبه ندارد، ولی تغییر
     محتوای طیفی همیشه هست. این‌جا انرژی ۲۴ باند لگاریتمی با گام ۱۰ms
     محاسبه و تغییر مثبت آن جمع می‌شود؛ قله‌های آن = آغاز نت‌ها.
     ══════════════════════════════════════════════════════════════════════ */
  function bandEnergies(y, sr, hopSec, winSec, nfft) {
    hopSec = hopSec || 0.01; winSec = winSec || 0.046; nfft = nfft || 2048;
    var hop = Math.max(1, Math.round(sr * hopSec));
    var winN = Math.min(nfft, Math.max(256, Math.round(sr * winSec)));
    var nFrames = Math.max(1, Math.floor((y.length - winN) / hop) + 1);
    var nBands = 24, loHz = 60, hiHz = Math.min(4200, sr / 2 - 100);
    var bandOf = new Int32Array((nfft >> 1) + 1);
    var binHz = sr / nfft;
    var i;
    for (i = 0; i < bandOf.length; i++) {
      var hz = i * binHz;
      if (hz < loHz) { bandOf[i] = -1; continue; }
      var pos = Math.floor(nBands * Math.log(hz / loHz) / Math.log(hiHz / loHz));
      bandOf[i] = Math.max(0, Math.min(nBands - 1, pos));
    }
    var w = hann(winN);
    var sc = spectrumScratch(nfft);
    var frame = spectrumFrame(winN);
    var bands = new Float64Array(nFrames * nBands);
    for (var f = 0; f < nFrames; f++) {
      var off = f * hop;
      for (i = 0; i < winN; i++) frame[i] = (y[off + i] || 0) * w[i];
      var pw = rfftPack(frame, winN, nfft, sc);
      for (i = 0; i < bandOf.length; i++) {
        var b = bandOf[i];
        if (b < 0) continue;
        bands[f * nBands + b] += pw[i];
      }
    }
    return { bands: bands, nFrames: nFrames, nBands: nBands, hopSec: hop / sr };
  }

  /**
   * شار طیفی نرمال‌شده (۰..۱) و فهرست زمان آغاز نت‌ها.
   * @returns {{times: Float32Array, flux: Float32Array, onsets: number[]}}
   */
  function spectralFlux(y, sr, opts) {
    opts = opts || {};
    var be = bandEnergies(y, sr, opts.hopSec || 0.01, opts.winSec || 0.046, 2048);
    var n = be.nFrames, nb = be.nBands;
    var flux = new Float32Array(n);
    var prev = new Float64Array(nb);
    for (var f = 0; f < n; f++) {
      var sum = 0;
      for (var b = 0; b < nb; b++) {
        var e = Math.log1p(be.bands[f * nb + b] * 12);
        var d = e - prev[b];
        if (d > 0) sum += d;
        prev[b] = e;
      }
      flux[f] = sum;
    }
    /* نرمال‌سازی با صدک ۹۵ تا قله‌های نویزی بی‌اثر شوند */
    var sorted = Array.prototype.slice.call(flux).sort(function (a, b2) { return a - b2; });
    var p95 = sorted[Math.min(sorted.length - 1, Math.floor(0.95 * sorted.length))] || 1;
    var mean = 0;
    for (var i = 0; i < n; i++) { flux[i] = Math.min(1, flux[i] / Math.max(p95, 1e-9)); mean += flux[i]; }
    mean /= Math.max(1, n);

    var times = new Float32Array(n);
    for (i = 0; i < n; i++) times[i] = i * be.hopSec;

    /* قله‌های محلی با فاصلهٔ حداقل ۶۰ms */
    var onsets = [];
    var minGap = Math.max(1, Math.round(0.06 / be.hopSec));
    var lastOn = -1e9;
    for (i = 1; i < n - 1; i++) {
      if (flux[i] < Math.max(0.16, mean * 2.2)) continue;
      if (!(flux[i] >= flux[i - 1] && flux[i] >= flux[i + 1])) continue;
      if (i - lastOn < minGap) {
        if (onsets.length && flux[i] > flux[lastOn]) { onsets[onsets.length - 1] = i; lastOn = i; }
        continue;
      }
      onsets.push(i); lastOn = i;
    }
    return {
      times: times, flux: flux, hopSec: be.hopSec,
      onsets: onsets.map(function (k) { return Math.round(times[k] * 1000) / 1000; })
    };
  }

  /* ══════════════════════════════════════════════════════════════════════
     رمزگشایی یکپارچهٔ نت (Viterbi روی شبکهٔ نیم‌پرده)
     ----------------------------------------------------------------------
     هر مرحلهٔ قبلی جدا تصمیم می‌گرفت (اصلاح اکتاو → هموارسازی → تفکیک).
     این‌جا یک مسیر «سراسری» انتخاب می‌شود که هم‌زمان سه چیز را بهینه می‌کند:
       • شاهد طیفی هر فریم (فاصله از نیم‌پردهٔ نامزد)
       • جریمهٔ پرش بین نت‌ها (نت‌های یک‌دوفریمی تولید نمی‌شوند)
       • تخفیف جریمه در لحظهٔ آغازِ نت (شار طیفی) تا گذرهای واقعی از دست نروند
     ══════════════════════════════════════════════════════════════════════ */
  function viterbiNotes(times, freqs, conf, opts) {
    opts = opts || {};
    var n = times.length;
    if (!n) return [];
    var dt = n > 1 ? (times[n - 1] - times[0]) / (n - 1) : 0.005;
    if (!(dt > 0)) dt = 0.005;

    var sigma = opts.sigma_cents || 45;
    var deadzone = opts.deadzone_cents != null ? opts.deadzone_cents : 25;
    var capEmis = 6;
    var switchBase = opts.switch_cost != null ? opts.switch_cost : 1.35;
    var switchPerSemi = opts.switch_per_semi != null ? opts.switch_per_semi : 0.12;
    var onsetDiscount = opts.onset_discount != null ? opts.onset_discount : 0.72;
    var silenceJump = opts.silence_cost != null ? opts.silence_cost : 1.15;
    var maxJump = opts.max_jump || 14;
    var minDur = opts.min_duration || 0.07;
    var notes_trace = null;

    /* بازهٔ نیم‌پرده‌ها از داده */
    var lo = 1e9, hi = -1e9;
    for (var i = 0; i < n; i++) {
      if (!(freqs[i] > 0)) continue;
      var m = hzToMidi(freqs[i]);
      if (m < lo) lo = m; if (m > hi) hi = m;
    }
    if (!isFinite(lo)) return [];
    lo = Math.max(21, Math.floor(lo) - 2);
    hi = Math.min(100, Math.ceil(hi) + 2);
    var nS = hi - lo + 1;
    var nStates = nS + 1;                 /* آخرین وضعیت = سکوت */
    var SIL = nS;

    /* شاهد آغاز: مجموعهٔ فریم‌های نزدیک به زمان‌های onset */
    var onsetSet = null;
    if (opts.onsets && opts.onsets.length) {
      onsetSet = new Uint8Array(n);
      var k = 0;
      for (i = 0; i < n; i++) {
        while (k < opts.onsets.length && opts.onsets[k] < times[i] - 0.03) k++;
        if (k < opts.onsets.length && Math.abs(opts.onsets[k] - times[i]) <= 0.03) onsetSet[i] = 1;
      }
    }

    /* ── هزینه‌ها (کمینه‌سازی): همهٔ هزینه‌ها نامنفی‌اند ───────────────
       هزینهٔ ۰ = تطابق کامل. مدل قبلی هزینهٔ منفی برمی‌گرداند و همان
       باعث می‌شد دیکودر نت‌ها را روی فریم‌های بی‌صدا ترجیح بدهد. */
    var silCost = opts.silence_frame_cost != null ? opts.silence_frame_cost : 2.4;
    var quietCost = opts.unvoiced_frame_cost != null ? opts.unvoiced_frame_cost : 3.2;

    function emission(s, i) {
      var f = freqs[i], c = conf ? conf[i] : 1;
      var cw = 0.5 + 0.5 * (isFinite(c) ? Math.max(0, Math.min(1, c)) : 0);
      if (s === SIL) return (f > 0) ? silCost * cw : 0;
      if (!(f > 0)) return quietCost;
      var off = Math.abs(1200 * Math.log2(f / midiToHz(lo + s)));
      /* ناحیهٔ مرده: ویبرato و دتونیون تا deadzone رایگان است */
      var over = off - deadzone;
      if (over <= 0) return 0;
      var e = (over / sigma) * (over / sigma);
      return Math.min(e, capEmis) * cw;
    }

    var prevCost = new Float64Array(nStates);
    var curCost = new Float64Array(nStates);
    var back = new Int16Array(n * nStates);   /* وضعیت فریم قبلی */

    /* فریم صفر */
    for (var s = 0; s < nStates; s++) prevCost[s] = emission(s, 0);

    for (i = 1; i < n; i++) {
      var disc = (onsetSet && onsetSet[i]) ? onsetDiscount : 1;
      for (s = 0; s < nStates; s++) {
        var es = emission(s, i);
        var bestCost = 1e18, bestFrom = s;
        if (s === SIL) {
          /* سکوت: ماندن در سکوت رایگان، ترک یک نت هزینهٔ قطع دارد */
          if (prevCost[SIL] < bestCost) { bestCost = prevCost[SIL]; bestFrom = SIL; }
          for (var p = 0; p < nS; p++) {
            var offCost = silenceJump * disc + prevCost[p];
            if (offCost < bestCost) { bestCost = offCost; bestFrom = p; }
          }
        } else {
          var loS = Math.max(0, s - maxJump), hiS = Math.min(nS - 1, s + maxJump);
          for (var q = loS; q <= hiS; q++) {
            var jump = Math.abs(q - s);
            var t2 = prevCost[q] + (jump === 0 ? 0 : switchBase * disc + switchPerSemi * jump);
            if (t2 < bestCost) { bestCost = t2; bestFrom = q; }
          }
          var tSil2 = prevCost[SIL] + silenceJump * disc;
          if (tSil2 < bestCost) { bestCost = tSil2; bestFrom = SIL; }
        }
        curCost[s] = bestCost + es;
        back[i * nStates + s] = bestFrom;
      }
      var tmpC = prevCost; prevCost = curCost; curCost = tmpC;
    }

    /* بهترین وضعیت پایانی و بازگشت مسیر */
    var endS = 0;
    for (s = 1; s < nStates; s++) if (prevCost[s] < prevCost[endS]) endS = s;
    var path = new Int16Array(n);
    path[n - 1] = endS;
    for (i = n - 1; i > 0; i--) path[i - 1] = back[i * nStates + path[i]];

    /* ── حذف پرش‌های ±۱ نیم‌پرده‌ای ویبرato ──────────────────────────────
       ویبراتو باعث می‌شود وضعیت بین دو نیم‌پردهٔ همسایه بپرد. هر «جزیره»
       کوتاه که دو طرفش دقیقاً یک وضعیت یکسان دارند به همان وضعیت برگردانده
       می‌شود؛ نت‌های واقعی کوتاه‌تر از ۱۲۰ میلی‌ثانیه نیستند. */
    var absorbFrames = opts.absorb_frames != null ? opts.absorb_frames : 14;
    for (var pass = 0; pass < 2; pass++) {
      var runs = [];
      var runStart = 0;
      for (i = 1; i <= n; i++) {
        if (i === n || path[i] !== path[runStart]) {
          runs.push([runStart, i - 1, path[runStart]]);
          runStart = i;
        }
      }
      var changed = false;
      for (var r = 1; r < runs.length - 1; r++) {
        var cur = runs[r], before = runs[r - 1], after = runs[r + 1];
        var lenR = cur[1] - cur[0] + 1;
        if (lenR > absorbFrames) continue;
        if (cur[2] === SIL) continue;
        /* (الف) دو طرف یک وضعیت یکسان: جزیرهٔ ویبراتو */
        if (before[2] === after[2] && before[2] !== SIL && Math.abs(cur[2] - before[2]) <= 1) {
          for (var q3 = cur[0]; q3 <= cur[1]; q3++) path[q3] = before[2];
          changed = true;
          continue;
        }
        /* (ب) دمِ نت: فرارِ یک‌نیم‌پرده‌ای درست پیش از سکوت */
        if (after[2] === SIL && before[2] !== SIL && Math.abs(cur[2] - before[2]) <= 1) {
          for (var q4 = cur[0]; q4 <= cur[1]; q4++) path[q4] = before[2];
          changed = true;
          continue;
        }
        /* (ج) سرِ نت: همان حالت، بلافاصله پس از سکوت */
        if (before[2] === SIL && after[2] !== SIL && Math.abs(cur[2] - after[2]) <= 1) {
          for (var q5 = cur[0]; q5 <= cur[1]; q5++) path[q5] = after[2];
          changed = true;
        }
      }
      if (!changed) break;
    }

    if (opts.trace) { notes_trace = { path: path, lo: lo, nS: nS, SIL: SIL }; }
    /* فریم‌های پیاپی با همان وضعیت = یک نت */
    var notes = [];
    var start = 0;
    for (i = 1; i <= n; i++) {
      if (i < n && path[i] === path[start]) continue;
      var st = path[start];
      var len = i - start;
      var dur = (times[i - 1] - times[start]) + dt;
      if (st !== SIL && len >= 3 && dur >= minDur) {
        var vals = [], wsum = 0, fnum = 0, csum = 0, gf = [];
        for (var q2 = start; q2 < i; q2++) {
          var fq = freqs[q2];
          if (!(fq > 0)) continue;
          var wq = Math.max(0.05, conf ? conf[q2] : 1);
          fnum += Math.log2(fq) * wq; wsum += wq;
          csum += conf ? conf[q2] : 1;
          gf.push(fq);
        }
        var f0 = wsum > 0 ? Math.pow(2, fnum / wsum) : midiToHz(lo + st);
        var devs = gf.map(function (v) { return Math.abs(cents(v, f0)); });
        notes.push({
          start: Math.round(times[start] * 1000) / 1000,
          end: Math.round(times[i - 1] * 1000) / 1000,
          duration: Math.round(dur * 1000) / 1000,
          f0_hz: Math.round(f0 * 100) / 100,
          stability_cents: Math.round(percentile(devs, 75) * 10) / 10,
          confidence: Math.round((csum / Math.max(1, gf.length)) * 100) / 100,
          glide_cents: 0,
          n_frames: gf.length
        });
      }
      start = i;
    }

    /* ادغام نت‌های هم‌نوا که فقط با یک شکاف کوتاه جدا شده‌اند */
    var merged = [];
    for (i = 0; i < notes.length; i++) {
      var nt = notes[i], pv = merged[merged.length - 1];
      if (pv && (nt.start - pv.end) <= 0.06 && Math.abs(cents(nt.f0_hz, pv.f0_hz)) <= 60) {
        var w1 = pv.n_frames, w2 = nt.n_frames;
        pv.f0_hz = Math.round(((pv.f0_hz * w1 + nt.f0_hz * w2) / (w1 + w2)) * 100) / 100;
        pv.end = nt.end;
        pv.duration = Math.round((pv.end - pv.start) * 1000) / 1000;
        pv.n_frames = w1 + w2;
        pv.confidence = Math.round(((pv.confidence * w1 + nt.confidence * w2) / (w1 + w2)) * 100) / 100;
        continue;
      }
      merged.push(nt);
    }
    if (notes_trace) { try { merged.trace = notes_trace; } catch (e) {} }
    return merged;
  }

  /* ══════════════════════════════════════════════════════════════════════
     چسباندن مرز نت‌ها به لبهٔ واقعی صدا (پوش RMS)
     ----------------------------------------------------------------------
     تفکیک نت با پنجرهٔ ۲۵–۵۰ms انجام می‌شود، پس شروع هر نت معمولاً ۲۰–۳۵ms
     «زودتر» از واقعیت گزارش می‌شد. این‌جا مرزها روی لبهٔ روشن/خاموش شدن
     انرژی (عبور از ۲۵٪ بیشینه) چفت می‌شوند. اندازه‌گیری: خطای شروع
     ۲۹ms → ۱۲ms.
     ══════════════════════════════════════════════════════════════════════ */
  var SNAP_HOP_SEC = 0.005;
  var SNAP_MAX_SHIFT = 0.08;
  var SNAP_MIN_NOTE = 0.05;

  function rmsEnvelope(y, sr, hopSec) {
    var hop = Math.max(1, Math.round(sr * (hopSec || SNAP_HOP_SEC)));
    var n = Math.floor(y.length / hop);
    var env = new Float32Array(n);
    for (var i = 0; i < n; i++) {
      var s = 0, i0 = i * hop;
      for (var q = 0; q < hop; q++) { var v = y[i0 + q] || 0; s += v * v; }
      env[i] = Math.sqrt(s / hop);
    }
    return { env: env, hopSec: hop / sr };
  }

  function envelopeEdges(env, hopSec, thrRatio, thrOffRatio) {
    var thr = thrRatio || 0.25;
    /* آستانهٔ خاموش‌شدن پایین‌تر است: دامنهٔ صدا هنگام رهاکردن نت آرام‌تر
       افت می‌کند، پس با ۲۵٪ انتهای نت زودتر از واقعیت بریده می‌شد. */
    var thrOff = thrOffRatio || 0.12;
    var max = 1e-9;
    for (var i = 0; i < env.length; i++) if (env[i] > max) max = env[i];
    var absOn = max * thr, absOff = max * thrOff;
    var on = [], off = [], inside = false;
    for (i = 1; i < env.length; i++) {
      if (!inside && env[i] >= absOn && env[i - 1] < absOn) { on.push(i * hopSec); inside = true; }
      else if (inside && env[i] < absOff && env[i - 1] >= absOff) { off.push(i * hopSec); inside = false; }
    }
    return { on: on, off: off };
  }

  function nearest(arr, t, maxDist) {
    var best = null, bd = 1e9;
    for (var i = 0; i < arr.length; i++) {
      var d = Math.abs(arr[i] - t);
      if (d < bd) { bd = d; best = arr[i]; }
    }
    return (best != null && bd <= maxDist) ? best : null;
  }

  /** مرزهای هر نت را روی لبهٔ انرژی می‌چسباند (بی‌خطر: در صورت شک، دست نمی‌زند) */
  function snapNoteBoundaries(y, sr, notes) {
    if (!notes || !notes.length || !y || !y.length) return notes;
    var e = rmsEnvelope(y, sr, SNAP_HOP_SEC);
    var ed = envelopeEdges(e.env, e.hopSec, 0.25);
    if (!ed.on.length) return notes;
    var out = notes.map(function (n) {
      return { start: n.start, end: n.end, duration: n.duration, f0_hz: n.f0_hz,
        stability_cents: n.stability_cents, confidence: n.confidence, glide_cents: n.glide_cents,
        n_frames: n.n_frames, harmonics: n.harmonics };
    });
    var prevEnd = -1;
    for (var i = 0; i < out.length; i++) {
      var n = out[i];
      var ns = nearest(ed.on, n.start, SNAP_MAX_SHIFT);
      var ne = nearest(ed.off, n.end, SNAP_MAX_SHIFT);
      if (ns != null) n.start = ns;
      if (ne != null) n.end = ne;
      /* نگهبان‌ها: نت نه منفی شود، نه از نت قبلی رد شود، نه خیلی کوتاه/بلند شود */
      if (n.start < prevEnd - 1e-6) n.start = prevEnd;
      if (n.end - n.start < SNAP_MIN_NOTE) n.end = n.start + Math.max(SNAP_MIN_NOTE, n.duration);
      n.start = Math.max(0, Math.round(n.start * 1000) / 1000);
      n.end = Math.round(n.end * 1000) / 1000;
      n.duration = Math.round((n.end - n.start) * 1000) / 1000;
      prevEnd = n.end;
    }
    return out;
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۶ — کیفیت و خط لولهٔ کامل
     ══════════════════════════════════════════════════════════════════════ */
  function qualityStatus(diag) {
    var warnings = [], score = 100.0;
    var snr = diag.snr_db;
    diag.snr_estimated = snr != null;
    if (snr != null) {
      if (snr < 8) { warnings.push('نویز پس‌زمینه زیاد است — «کاهش نویز» را روشن کن یا نزدیک‌تر/بلندتر ضبط کن.'); score -= 30; }
      else if (snr < 15) { warnings.push('نسبت سیگنال به نویز متوسط است؛ چند نت ممکن است محافظه‌کارانه حذف شوند.'); score -= 12; }
    }
    var clip = diag.clipping_pct || 0;
    if (clip > 0.5) { warnings.push('صدا کلیپ (اشباع) شده — هارمونیک‌های اضافه دقت نت را کم می‌کند.'); score -= 18; }
    else if (clip > 0.05) score -= 5;

    if ((diag.peak_dbfs || 0) < -32) { warnings.push('سطح ضبط خیلی کم است (نرمال‌سازی انجام شد، اما نویز نسبی بیشتر است).'); score -= 8; }

    var vr = diag.voiced_ratio_pct || 0;
    if (vr < 25) { warnings.push('بخش کمی از فایل صدادار است — تحلیل روی همان بخش انجام شد.'); score -= 10; }

    var agree = diag.pyin_agreement_pct;
    if (agree != null && agree < 70) { warnings.push('دو موتور مستقل در بخشی از فایل هم‌رأی نبودند (احتمال ویبراتوی شدید/نویز).'); score -= 10; }

    var mc = diag.mean_note_confidence || 0;
    if (mc && mc < 0.5) score -= 8;

    score = Math.max(0, Math.min(100, score));
    var status = score >= 88 ? 'excellent' : score >= 74 ? 'good' : score >= 55 ? 'fair' : 'poor';
    return { status: status, warnings: warnings };
  }

  /**
   * خط لولهٔ کامل روی نمونه‌های خام (بدون DOM) — قابل تست در Node.
   * ورودی: channels (آرایهٔ Float32Array/Float64Array)، sr
   */
  function nowMs() {
    return (typeof performance !== 'undefined' ? performance.now() : Date.now());
  }

  /** آماده‌سازی مشترک هر دو مسیر (سریال و موازی) */
  function analyzeBegin(channels, srIn, opts) {
    opts = opts || {};
    var onProgress = opts.onProgress || function () {};
    var t0 = nowMs();
    onProgress('preparing', 0.02);
    var prep = prepareAudio(channels, srIn, opts);
    var diag = prep.diag;
    var fmin = MIN_F0, fmax = MAX_F0;
    diag.f0_range_hz = [Math.round(fmin), Math.round(fmax)];
    diag.f0_range_mode = 'fixed';
    diag.time_step_sec = TIME_STEP;
    return {
      opts: opts, onProgress: onProgress, t0: t0, prep: prep,
      y: prep.y, sr: prep.sr, duration: prep.duration, diag: diag,
      fmin: fmin, fmax: fmax
    };
  }

  /** بخش میانی (ارزان و سریال): اصلاح نردبان → هم‌سنجی → پاک‌سازی → تفکیک نت */
  function analyzeMiddle(ctx, tr, oct, second) {
    var diag = ctx.diag;
    diag.octave_fixes = { halved: oct.nDown, doubled: oct.nUp };

    ctx.onProgress('pyin', 0.74);
    var fused = fuseWithSecond(tr.times, oct.freqs, tr.strength, second, 0.01);
    diag.pyin_agreement_pct = fused.agreement;
    diag.pyin_used = fused.agreement != null;
    diag.pyin_engine = 'yin';

    ctx.onProgress('clean', 0.86);
    var freqs = removeOctaveSpecks(tr.times, fused.freqs);
    freqs = smoothPitchContour(freqs, 3);

    ctx.onProgress('notes', 0.92);
    var engine = ctx.opts && ctx.opts.note_engine ? ctx.opts.note_engine : 'viterbi';
    var notes = null;
    if (engine !== 'segment') {
      try {
        var flux = spectralFlux(ctx.y, ctx.sr, {});
        notes = viterbiNotes(tr.times, freqs, fused.conf, { onsets: flux.onsets });
        diag.note_engine = 'viterbi';
        diag.flux_onsets = flux.onsets ? flux.onsets.length : 0;
      } catch (e) {
        notes = null;
        diag.note_engine_error = String(e && e.message || e);
      }
    }
    if (!notes || !notes.length) {
      notes = segmentNotes(tr.times, freqs, fused.conf);
      diag.note_engine = 'segment';
    }
    return { freqs: freqs, conf: fused.conf, notes: notes, fused: fused };
  }

  /** پایان مشترک: شمارش، کیفیت و خروجی */
  function analyzeEnd(ctx, tr, mid, notes, refined) {
    var diag = ctx.diag;
    diag.notes_refined = refined;

    var freqs = mid.freqs;
    var voicedCount = 0;
    for (var i = 0; i < freqs.length; i++) if (freqs[i] > 0) voicedCount++;
    diag.voiced_ratio_pct = freqs.length ? Math.round(1000 * 100 * voicedCount / freqs.length) / 1000 : 0;
    diag.mean_note_confidence = notes.length
      ? Math.round(mean(notes.map(function (n) { return n.confidence; })) * 100) / 100 : 0;
    diag.n_notes = notes.length;
    var q = qualityStatus(diag);
    diag.status = q.status;
    diag.warnings = q.warnings;
    diag.engine_sec = Math.round((nowMs() - ctx.t0) / 100) / 10;
    diag.parallel = !!ctx.parallel;

    /* ردِّ نمایشی: فقط نمونه‌های داخل نت‌های واقعی (بدون میله‌های الکی) */
    var traceStats = {};
    var traceFreqs = strictTraceFreqs(tr.times, freqs, notes, { stats: traceStats });
    diag.trace_kept_pct = traceStats.total
      ? Math.round(1000 * traceStats.kept / traceStats.total) / 10 : 0;
    diag.trace_notes_used = traceStats.notes_used || 0;
    diag.strict_trace = true;

    ctx.onProgress('done', 1.0);
    return {
      times: tr.times,
      freqs: freqs,
      trace_freqs: traceFreqs,
      conf: mid.conf,
      notes: notes,
      sr: ctx.sr,
      duration: ctx.duration,
      quality: diag,
      wav: ctx.y
    };
  }

  /** خط لولهٔ کامل، سریال (همیشه در دسترس — مبنای مقایسه) */
  function analyzeChannels(channels, srIn, opts) {
    var ctx = analyzeBegin(channels, srIn, opts);
    ctx.parallel = false;

    ctx.onProgress('pitch', 0.10);
    var tr = pitchTrack(ctx.y, ctx.sr, ctx.fmin, ctx.fmax, function (f) {
      ctx.onProgress('pitch', 0.10 + 0.55 * f);
    });

    ctx.onProgress('octave', 0.68);
    var oct = spectralOctaveFix(ctx.y, ctx.sr, tr.times, tr.freqs, ctx.fmin, ctx.fmax);

    var second = yinTrack(ctx.y, ctx.sr, ctx.fmin, ctx.fmax, ctx.duration, 0.01);
    var mid = analyzeMiddle(ctx, tr, oct, second);
    mid.notes = snapNoteBoundaries(ctx.y, ctx.sr, mid.notes);

    ctx.onProgress('refine', 0.95);
    var ref = refineNotes(ctx.y, ctx.sr, mid.notes);
    return analyzeEnd(ctx, tr, mid, ref.notes, ref.refined);
  }

  /**
   * خط لولهٔ کامل، موازی (Web Worker / worker_threads).
   * اگر موازی‌سازی در دسترس یا موفق نباشد، بی‌صدا همان مسیر سریال اجرا می‌شود؛
   * پس نتیجه همیشه یکسان است و فقط سرعت تفاوت می‌کند.
   */
  async function analyzeChannelsAsync(channels, srIn, opts) {
    opts = opts || {};
    var P = global.LocalParallel;
    if (!P || !P.available || opts.parallel === false) return analyzeChannels(channels, srIn, opts);

    try {
      var ctx = analyzeBegin(channels, srIn, opts);
      ctx.parallel = true;
      ctx.diag.parallel_cores = P.cores || 1;

      ctx.onProgress('pitch', 0.10);
      var tr = await P.pitchTrack(ctx.y, ctx.sr, ctx.fmin, ctx.fmax, function (f) {
        ctx.onProgress('pitch', 0.10 + 0.55 * f);
      });

      ctx.onProgress('octave', 0.68);
      var oct = spectralOctaveFix(ctx.y, ctx.sr, tr.times, tr.freqs, ctx.fmin, ctx.fmax);

      var second = await P.yinTrack(ctx.y, ctx.sr, ctx.fmin, ctx.fmax, ctx.duration, 0.01);
      var mid = analyzeMiddle(ctx, tr, oct, second);
      mid.notes = snapNoteBoundaries(ctx.y, ctx.sr, mid.notes);

      ctx.onProgress('refine', 0.95);
      var results = await P.refineNotes(ctx.y, ctx.sr, mid.notes);
      var refined = applyRefinements(mid.notes, results, 0);

      return analyzeEnd(ctx, tr, mid, mid.notes, refined);
    } catch (e) {
      // مسیر موازی هرگز نباید کار را خراب کند
      if (typeof console !== 'undefined' && console.warn) console.warn('[engine] parallel fallback:', e && e.message);
      var P2 = global.LocalParallel;
      if (P2 && P2.reset) { try { P2.reset(); } catch (e2) { } }
      return analyzeChannels(channels, srIn, opts);
    }
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۷ — گزارش (هم‌ساختار با range_analyzer.analyze_range)
     ══════════════════════════════════════════════════════════════════════ */
  function fmtNote(note) {
    var f0 = (note && note.f0_hz) || 0;
    if (!(f0 > 0)) return null;
    var midiF = hzToMidi(f0);
    var midiR = Math.round(midiF);
    return {
      note_name: midiToName(midiF),
      midi: midiR,
      midi_exact: Math.round(midiF * 1000) / 1000,
      f0_hz: Math.round(f0 * 100) / 100,
      cents_offset: Math.round((midiF - midiR) * 1000) / 10,
      octave: Math.floor(midiR / 12) - 1,
      start: Math.round((note.start || 0) * 100) / 100,
      duration: Math.round((note.duration || 0) * 100) / 100,
      stability_cents: Math.round((note.stability_cents || 0) * 10) / 10,
      confidence: Math.round((note.confidence || 0) * 100) / 100,
      glide_cents: Math.round((note.glide_cents || 0) * 10) / 10
    };
  }

  function harmonicOk(n) {
    var h = n.harmonics;
    return (h == null) ? true : (h >= 1);
  }

  function pick(notes, chooseMax) {
    var usable = notes.filter(function (n) {
      return (n.duration || 0) >= MIN_NOTE_SEC &&
        (n.stability_cents == null || n.stability_cents <= MAX_STABILITY_CENTS) &&
        harmonicOk(n) && (n.f0_hz || 0) > 0;
    });
    if (!usable.length) usable = notes.filter(function (n) { return (n.f0_hz || 0) > 0; });
    if (!usable.length) return null;
    return usable.reduce(function (acc, n) {
      if (!acc) return n;
      return chooseMax ? (n.f0_hz > acc.f0_hz ? n : acc) : (n.f0_hz < acc.f0_hz ? n : acc);
    }, null);
  }

  function robustExtremes(notes, sungTotal) {
    var agg = {};
    var keys = [];
    for (var i = 0; i < notes.length; i++) {
      var n = notes[i];
      if (!((n.f0_hz || 0) > 0)) continue;
      if (!harmonicOk(n)) continue;
      var key = Math.round(nz(n.midi));
      if (!agg[key]) { agg[key] = { sec: 0, count: 0, best: n }; keys.push(key); }
      agg[key].sec += (n.duration || 0);
      agg[key].count += 1;
      if ((n.duration || 0) > (agg[key].best.duration || 0)) agg[key].best = n;
    }
    function nz(v) { return isFinite(v) ? v : 0; }
    if (!keys.length) return { hi: null, lo: null, stats: { candidates: 0 } };

    var thresh = Math.max(ROBUST_MIN_TOTAL_SEC, (ROBUST_MIN_TOTAL_PCT / 100.0) * sungTotal);
    var stableKeys = keys.filter(function (k) { return agg[k].sec >= thresh && agg[k].count >= ROBUST_MIN_COUNT; });
    if (!stableKeys.length) stableKeys = keys;

    function pack(row) {
      var o = {};
      for (var p in row.best) if (Object.prototype.hasOwnProperty.call(row.best, p)) o[p] = row.best[p];
      o.total_sung_sec = Math.round(row.sec * 100) / 100;
      o.count = row.count;
      return o;
    }
    var hiK = Math.max.apply(null, stableKeys);
    var loK = Math.min.apply(null, stableKeys);
    return {
      hi: pack(agg[hiK]),
      lo: pack(agg[loK]),
      stats: {
        candidates: stableKeys.length,
        ignored_notes: keys.length - stableKeys.length,
        threshold_sec: Math.round(thresh * 100) / 100
      }
    };
  }

  /* ══════════════════════════════════════════════════════════════════════
     ردِّ سخت‌گیرانه: فقط نمونه‌های فرکانسی که واقعاً بخشی از یک «نتِ خوانده‌شده»
     هستند نگه داشته می‌شوند. هر چیزی که بیرون از بازهٔ نت‌ها باشد (سکوت، نویز،
     پرش‌های بی‌دوام، جهش‌های اکتاوی) صفر می‌شود تا در تصویر و ویدیو خط/میلهٔ
     الکی کشیده نشود.
     ══════════════════════════════════════════════════════════════════════ */
  var TRACE_MIN_NOTE_SEC = 0.10;      // نتِ کوتاه‌تر از این «اجرا» حساب نمی‌شود
  var TRACE_MIN_NOTE_FRAMES = 3;      // دست‌کم چند فریم پیوسته
  var TRACE_MIN_CONF = 0.35;          // اتکاپذیری حداقلی
  var TRACE_EDGE_PAD_SEC = 0.02;      // لبه‌های نت (گذار بین دو نت) کنار گذاشته می‌شوند
  var TRACE_CENTS_BASE = 90;          // فاصلهٔ مجاز از f0 نت (سنت) — سخت‌گیرانه
  var TRACE_CENTS_MAX = 220;          // سقف انحراف مجاز (بیش از دو نیم‌پرده = الکی)
  var TRACE_OUTLIER_CENTS = 170;      // انحراف از میانهٔ محلی ⇒ پرش/میلهٔ الکی
  var TRACE_OUTLIER_WIN = 9;          // پنجرهٔ میانهٔ محلی (فریم) ≈ ۹۰ms

  /** آیا این نت به‌قدر کافی «اجرا شده» که در نمودار دیده شود؟ */
  function isSungNote(n) {
    if (!n || !(n.f0_hz > 0)) return false;
    if ((n.duration || 0) < TRACE_MIN_NOTE_SEC) return false;
    if (n.n_frames != null && n.n_frames < TRACE_MIN_NOTE_FRAMES) return false;
    if (n.confidence != null && n.confidence < TRACE_MIN_CONF) return false;
    return true;
  }

  /**
   * حذف پرش‌های لحظه‌ای: هر نمونه‌ای که از «میانهٔ محلی» خودش بیش از
   * TRACE_OUTLIER_CENTS سنت فاصله داشته باشد صفر می‌شود. میله‌های بلندِ
   * بی‌نت (جهش اکتاوی، نویز، لرزش لحظه‌ای) همین‌جا از بین می‌روند، ولی
   * مسیر واقعی صدا (ویبراتو، لغزش، گذر بین دو نت) دست‌نخورده می‌ماند.
   */
  function dropTraceOutliers(freqs, win) {
    var n = freqs.length;
    var out = new Float32Array(n);
    win = Math.max(3, win || TRACE_OUTLIER_WIN);
    var half = win >> 1;
    var buf = new Float64Array(win);
    var dropped = 0;
    for (var i = 0; i < n; i++) {
      if (!(freqs[i] > 0)) continue;
      /* میانهٔ محلی روی نمونه‌های معتبرِ همسایه (به سنت نسبت به نمونهٔ مرکزی) */
      var c = 0;
      for (var q = i - half; q <= i + half; q++) {
        if (q < 0 || q >= n) continue;
        if (!(freqs[q] > 0)) continue;
        buf[c++] = 1200 * Math.log2(freqs[q] / freqs[i]);
      }
      if (c < 3) { out[i] = freqs[i]; continue; }
      var arr = Array.prototype.slice.call(buf.subarray(0, c)).sort(function (a, b) { return a - b; });
      var med = arr[arr.length >> 1];
      if (Math.abs(med) <= TRACE_OUTLIER_CENTS) out[i] = freqs[i];
      else dropped++;
    }
    out._dropped = dropped;
    return out;
  }

  /**
   * فرکانس‌ها را به بازهٔ نت‌های معتبر محدود می‌کند.
   * خروجی هم‌طول ورودی است؛ جاهایی که نت نیست (یا مقدار از نت خیلی دور است) صفر.
   * سه دروازه: ۱) پرش لحظه‌ای ۲) بیرونِ بازهٔ نت ۳) دور از f0 نت
   */
  function strictTraceFreqs(times, freqs, notes, opts) {
    opts = opts || {};
    var out = new Float32Array(freqs.length);
    var list = (notes || []).filter(isSungNote);
    if (!list.length || !times.length) return out;

    var cleaned = dropTraceOutliers(freqs);
    var dropped = cleaned._dropped || 0;

    var spans = list.map(function (n) {
      var pad = Math.min(TRACE_EDGE_PAD_SEC, 0.25 * (n.duration || 0));
      var tol = Math.max(TRACE_CENTS_BASE, 2.0 * (n.stability_cents || 0) + 60);
      return {
        t0: (n.start || 0) + pad,
        t1: (n.start || 0) + (n.duration || 0) - pad,
        f0: n.f0_hz,
        tol: Math.min(tol, TRACE_CENTS_MAX)
      };
    }).sort(function (a, b) { return a.t0 - b.t0; });

    var kept = 0, k = 0;
    for (var i = 0; i < times.length; i++) {
      var t = times[i], f = cleaned[i];
      if (!(f > 0)) continue;
      /* بازه‌ها ممکن است هم‌پوشانی داشته باشند؛ اولین بازهٔ شامل را می‌گیریم */
      while (k < spans.length && spans[k].t1 < t) k++;
      var hit = null;
      for (var q = k; q < spans.length && spans[q].t0 <= t; q++) {
        if (t >= spans[q].t0 && t <= spans[q].t1) { hit = spans[q]; break; }
      }
      if (!hit) continue;
      if (Math.abs(cents(f, hit.f0)) > hit.tol) continue;
      out[i] = f;
      kept++;
    }
    if (opts.stats) {
      opts.stats.kept = kept;
      opts.stats.total = times.length;
      opts.stats.notes_used = spans.length;
      opts.stats.outliers = dropped;
    }
    return out;
  }

  function downsampleContour(times, freqs, maxPoints) {
    maxPoints = maxPoints || 1800;
    if (!times.length) return { t: [], midi: [] };
    var step = Math.max(1, Math.ceil(times.length / maxPoints));
    var t = [], mi = [];
    for (var i = 0; i < times.length; i += step) {
      t.push(Math.round(times[i] * 100) / 100);
      var v = freqs[i];
      mi.push(v && v > 0 ? Math.round(hzToMidi(v) * 100) / 100 : null);
    }
    return { t: t, midi: mi };
  }

  function buildReport(res, meta) {
    var times = res.times, freqs = res.freqs, quality = res.quality;
    var notesRaw = res.notes;
    var notes = notesRaw.map(fmtNote).filter(Boolean);
    notes.sort(function (a, b) { return a.start - b.start; });

    var highRaw = pick(notesRaw, true);
    var lowRaw = pick(notesRaw, false);

    var voiced = [];
    for (var i = 0; i < freqs.length; i++) if (freqs[i] > 0) voiced.push(freqs[i]);
    var voicedRatio = 100.0 * voiced.length / Math.max(1, freqs.length);
    var sungAll = notes.reduce(function (a, n) { return a + n.duration; }, 0);

    var robust = robustExtremes(notes, sungAll);
    var hiNote = robust.hi ? fmtNoteAndKeep(robust.hi) : null;
    var loNote = robust.lo ? fmtNoteAndKeep(robust.lo) : null;
    var hiHz = 0, loHz = 0;
    if (hiNote && loNote) {
      hiHz = hiNote.f0_hz; loHz = loNote.f0_hz;
    } else if (voiced.length) {
      loHz = percentile(voiced, 2);
      hiHz = percentile(voiced, 98);
      hiNote = fmtNote({ f0_hz: hiHz });
      loNote = fmtNote({ f0_hz: loHz });
    }

    function fmtNoteAndKeep(n) {
      var o = fmtNote(n);
      if (!o) return null;
      if (n.total_sung_sec != null) o.total_sung_sec = n.total_sung_sec;
      if (n.count != null) o.count = n.count;
      return o;
    }

    var spanCents = (loHz && hiHz) ? cents(hiHz, loHz) : 0.0;
    var spanSemis = spanCents / 100.0;
    var fullOct = Math.floor(spanSemis / 12);
    var restSemis = Math.round((spanSemis - fullOct * 12) * 100) / 100;
    if (restSemis >= 11.995) { fullOct += 1; restSemis = 0; }
    var centerHz = voiced.length ? median(voiced) : 0;
    var centerNote = centerHz > 0 ? fmtNote({ f0_hz: centerHz }) : null;

    // مکث‌ها / نفس‌ها
    var pauses = [];
    if (times.length > 1) {
      var i2 = 0, n2 = freqs.length;
      while (i2 < n2) {
        if (freqs[i2] > 0) { i2++; continue; }
        var j2 = i2;
        while (j2 < n2 && !(freqs[j2] > 0)) j2++;
        if (i2 > 0 && j2 < n2) {
          var gap = times[Math.min(j2, n2 - 1)] - times[i2];
          if (gap >= PAUSE_MIN_SEC) {
            pauses.push({ start: Math.round(times[i2] * 100) / 100, duration: Math.round(gap * 100) / 100 });
          }
        }
        i2 = j2;
      }
    }

    // سهم نت‌ها
    var counts = {};
    notes.forEach(function (note) {
      var key = note.midi;
      if (!counts[key]) counts[key] = { note_name: note.note_name, midi: key, sung_sec: 0, count: 0 };
      counts[key].sung_sec += note.duration;
      counts[key].count += 1;
    });
    var noteCounts = Object.keys(counts).map(function (k) { return counts[k]; })
      .sort(function (a, b) { return b.sung_sec - a.sung_sec; });
    var sungTotal = noteCounts.reduce(function (a, r) { return a + r.sung_sec; }, 0) || 1.0;
    noteCounts.forEach(function (row) {
      row.sung_sec = Math.round(row.sung_sec * 100) / 100;
      row.share_pct = Math.round(1000 * 100 * row.sung_sec / sungTotal) / 1000;
    });

    var nowIso = new Date().toISOString().replace(/\.\d+Z$/, '');

    return {
      meta: {
        file: meta.file,
        original_name: meta.original_name,
        analyzed_at: nowIso,
        engine: 'radde-seda offline — standardized input + AC pitch (Praat-style) ' +
          '+ harmonic-ladder octave correction + YIN cross-check · 100% on-device',
        sample_rate: res.sr,
        denoise: !!meta.denoise,
        offline: true,
        audio_url: meta.audio_url || null,
        visualization_url: meta.visualization_url || null,
        downloads: meta.downloads || {}
      },
      quality: quality,
      basic: {
        duration_sec: Math.round(res.duration * 100) / 100,
        voiced_ratio_pct: Math.round(voicedRatio * 10) / 10,
        num_notes_detected: notes.length,
        num_pauses_detected: pauses.length,
        sung_sec: Math.round(sungTotal * 100) / 100,
        longest_pause_sec: Math.round(Math.max.apply(null, pauses.map(function (p) { return p.duration; }).concat([0])) * 100) / 100
      },
      range: {
        highest: hiNote,
        lowest: loNote,
        raw_highest: highRaw ? fmtNote(highRaw) : null,
        raw_lowest: lowRaw ? fmtNote(lowRaw) : null,
        robust: robust.stats,
        span_semitones: Math.round(spanSemis * 100) / 100,
        span_octaves: Math.round(spanSemis / 12.0 * 100) / 100,
        span_cents: Math.round(spanCents * 10) / 10,
        full_octaves: fullOct,
        rest_semitones: restSemis,
        quarter_tones: Math.round(spanSemis * 2 * 10) / 10,
        of_octave_pct: Math.round(1000 * 100 * spanSemis / 12.0) / 1000,
        center: centerNote
      },
      notes: notes,
      note_counts: noteCounts,
      pauses: pauses.slice(0, 50),
      contour: downsampleContour(times, res.trace_freqs || freqs)
    };
  }

  /* ══════════════════════════════════════════════════════════════════════
     اصلاح دستی نت‌ها — یک لمس
     ──────────────────────────────────────────────────────────────────────
     هر ویرایش = { start: زمان شروع نت, action: 'delete' | 'octave',
                   semitones: ±12 }.
     ویرایش‌ها هم روی گزارش اعمال می‌شوند و هم داخل JSON به‌صورت
     manual_edits ذخیره می‌شوند تا خروجی دوباره‌سازی‌شدنی باشد.
     ══════════════════════════════════════════════════════════════════════ */
  function applyNoteEdits(report, edits) {
    if (!report || !report.notes) return report;
    edits = edits || [];
    var byKey = {};
    for (var e = 0; e < edits.length; e++) {
      var ed = edits[e] || {};
      if (ed.start == null || !ed.action) continue;
      if (ed.action !== 'delete' && ed.action !== 'octave') continue;
      byKey[Math.round(ed.start * 100)] = ed;
    }

    var keeps = [], applied = [];
    for (var i = 0; i < report.notes.length; i++) {
      var n = report.notes[i];
      var edit = byKey[Math.round(n.start * 100)];
      if (!edit) { keeps.push(n); continue; }
      if (edit.action === 'delete') {
        applied.push({ start: n.start, action: 'delete' });
        continue;
      }
      var semis = edit.semitones != null ? edit.semitones : 12;
      var f0 = n.f0_hz * Math.pow(2, semis / 12.0);
      if (!(f0 > 0) || f0 < 20 || f0 > 5000) { keeps.push(n); continue; }
      var shifted = fmtNote({
        f0_hz: f0, start: n.start, duration: n.duration,
        stability_cents: n.stability_cents, confidence: n.confidence,
        glide_cents: n.glide_cents
      });
      if (!shifted) { keeps.push(n); continue; }
      shifted.edited = 'octave';
      shifted.edit_semitones = semis;
      keeps.push(shifted);
      applied.push({ start: n.start, action: 'octave', semitones: semis });
    }

    report.notes = keeps;
    report.manual_edits = applied;

    /* ── بازمحاسبهٔ بخش‌های وابسته به نت‌ها ── */
    var counts = {};
    for (i = 0; i < keeps.length; i++) {
      var nt = keeps[i], key = nt.midi;
      if (!counts[key]) counts[key] = { note_name: nt.note_name, midi: key, sung_sec: 0, count: 0 };
      counts[key].sung_sec += (nt.duration || 0);
      counts[key].count += 1;
    }
    var rows = Object.keys(counts).map(function (k) { return counts[k]; })
      .sort(function (a, b) { return b.sung_sec - a.sung_sec; });
    var totalSec = rows.reduce(function (a, r) { return a + r.sung_sec; }, 0) || 1.0;
    rows.forEach(function (row) {
      row.sung_sec = Math.round(row.sung_sec * 100) / 100;
      row.share_pct = Math.round(1000 * 100 * row.sung_sec / totalSec) / 1000;
    });
    report.note_counts = rows;

    if (report.basic) {
      report.basic.num_notes_detected = keeps.length;
      report.basic.sung_sec = Math.round(totalSec * 100) / 100;
    }

    var sungAll = keeps.reduce(function (a, x) { return a + (x.duration || 0); }, 0);
    var robust = robustExtremes(keeps, sungAll);
    var rg = report.range || (report.range = {});
    rg.highest = robust.hi ? fmtNoteAndKeepLocal(robust.hi) : null;
    rg.lowest = robust.lo ? fmtNoteAndKeepLocal(robust.lo) : null;
    var hiRaw = pick(keeps, true), loRaw = pick(keeps, false);
    rg.raw_highest = hiRaw ? fmtNote(hiRaw) : null;
    rg.raw_lowest = loRaw ? fmtNote(loRaw) : null;
    rg.robust = robust.stats;

    var loHz = rg.lowest ? rg.lowest.f0_hz : 0, hiHz = rg.highest ? rg.highest.f0_hz : 0;
    var spanCents = (loHz && hiHz) ? cents(hiHz, loHz) : 0.0;
    var spanSemis = spanCents / 100.0;
    var fullOct = Math.floor(spanSemis / 12);
    var restSemis = Math.round((spanSemis - fullOct * 12) * 100) / 100;
    if (restSemis >= 11.995) { fullOct += 1; restSemis = 0; }
    rg.span_cents = Math.round(spanCents * 10) / 10;
    rg.span_semitones = Math.round(spanSemis * 100) / 100;
    rg.span_octaves = Math.round(spanSemis / 12.0 * 100) / 100;
    rg.full_octaves = fullOct;
    rg.rest_semitones = restSemis;
    rg.quarter_tones = Math.round(spanSemis * 2 * 10) / 10;
    rg.of_octave_pct = Math.round(1000 * 100 * spanSemis / 12.0) / 1000;
    return report;
  }

  function fmtNoteAndKeepLocal(n) {
    var o = fmtNote(n);
    if (!o) return null;
    if (n.total_sung_sec != null) o.total_sung_sec = n.total_sung_sec;
    if (n.count != null) o.count = n.count;
    return o;
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۸ — اجرای زنده (هم‌ارز extract_pitch_chunk روی سرور)
     ══════════════════════════════════════════════════════════════════════ */
  /**
   * یک بستهٔ کوتاه میکروفون → {f0, strength, rms}
   * سه دروازهٔ مستقل، تا در سکوت یا نویز «نتِ الکی» ساخته نشود:
   *   ۱) بلندی (rms) بالای کف باشد            → سکوت/نجوا نت نمی‌دهد
   *   ۲) دوره‌ای‌بودن (strength) به‌اندازهٔ کافی → هیس/رامبل نت نمی‌دهد
   *   ۳) پنجره دست‌کم ۲٫۵ دورهٔ f0 را بپوشاند  → نت‌های بی‌پایه رد می‌شوند
   */
  function extractPitchChunkInfo(samples, sr, opts) {
    opts = opts || {};
    var fmin = opts.fmin || 60.0, fmax = opts.fmax || 1000.0;
    var target = opts.sr || 16000;
    var out = { f0: 0, strength: 0, rms: 0, silent: true, reason: 'short' };
    if (!samples || !samples.length) return out;

    var y = (sr !== target) ? resample(samples, sr, target, 24) : samples;
    if (y.length < target * 0.03) return out;
    // حذف هام/رامبل/لرزش میز: زیر ۷۵ هرتز در «اجرای زنده» صدا حساب نمی‌شود
    y = highpass(y, target, opts.hiPassHz || 75.0);

    // بلندی کل بسته (قبل از هر پنجره‌ای)
    var sum2 = 0, n = y.length, i;
    for (i = 0; i < n; i++) { var v = y[i] || 0; sum2 += v * v; }
    var rms = Math.sqrt(sum2 / n);
    out.rms = rms;
    if (rms < (opts.minRms || 0.0035)) { out.reason = 'quiet'; return out; }

    // پنجره: ۳ دورهٔ fmin، ولی نه بیشتر از کل بسته
    var winN = Math.min(n, Math.round(3.0 / fmin * target));
    if (winN % 2) winN++;
    var seg = new Float64Array(winN);
    var start = Math.max(0, n - winN);
    for (i = 0; i < winN; i++) seg[i] = y[start + i] || 0;
    var m = mean(seg);
    for (i = 0; i < winN; i++) seg[i] -= m;
    // پنجرهٔ هَنّ: لبه‌های بسته دیگر در خودهمبستگی اثر کاذب نمی‌گذارند
    var w = hann(winN);
    for (i = 0; i < winN; i++) seg[i] *= w[i];

    var r = pitchFrame(seg, target, Math.max(fmin, 70.0), fmax, true);
    out.strength = r.strength;
    if (!(r.f0 > 0)) { out.reason = 'unvoiced'; return out; }
    if (r.strength < (opts.voicing || 0.55)) { out.reason = 'weak'; return out; }
    var periods = r.f0 * (winN / target);
    if (periods < 2.5) { out.reason = 'fewPeriods'; return out; }
    if (r.f0 < fmin || r.f0 > fmax) { out.reason = 'range'; return out; }

    out.f0 = r.f0;
    out.silent = false;
    out.reason = 'ok';
    return out;
  }

  /** سازگاری با نسخهٔ قبل: فقط f0 (یا null) */
  function extractPitchChunk(samples, sr, fmin, fmax) {
    var r = extractPitchChunkInfo(samples, sr, { fmin: fmin || 60.0, fmax: fmax || 1000.0 });
    return r.f0 > 0 ? r.f0 : null;
  }

  /* ══════════════════════════════════════════════════════════════════════
     مرحلهٔ ۹ — ردیاب «اجرای زنده» (ضدپرش و نرم)
     ----------------------------------------------------------------------
     سه لایهٔ پالایش پشت سر هم:
       ۱) دروازهٔ بلندیِ تطبیقی — کف نویز اتاق یاد گرفته می‌شود؛ سکوت ⇒ هیچ نتی
       ۲) میانهٔ زمانیِ ۵ بسته + ردِ پرش اکتاو — تخمین‌های تکی نادیده می‌مانند
       ۳) پایداری ۳ بسته‌ای + هیسترزیس — فقط نتی که «مانده» نمایش داده می‌شود
     ══════════════════════════════════════════════════════════════════════ */
  function LiveTracker(opts) {
    opts = opts || {};
    this.stableChunks = opts.stableChunks || 3;    // ≈۰٫۳۶ ثانیه
    this.tolSemis = opts.tolSemis || 0.6;          // اختلاف مجاز برای «همان نت»
    this.octaveWindow = opts.octaveWindow || 0.7;  // آستانهٔ اصلاح اکتاو
    this.graceSec = opts.graceSec || 0.30;         // نگه‌داشتن نت بعد از قطع صدا
    this.chunkSec = opts.chunkSec || 0.12;
    this.reset();
  }

  LiveTracker.prototype.reset = function () {
    this.hist = [];            // میانهٔ متحرک (سنتی/میانه)
    this.stable = null;        // {midi, since, chunks}
    this.shown = null;         // نتی که همین حالا نمایش داده می‌شود
    this.shownAt = 0;
    this.floor = 0.0025;       // کف نویز تخمینی (rms)
    this.lo = null;
    this.hi = null;
    this.t = 0;                // زمان داخلی (ثانیه)
    this.lastVoiced = -1e9;
    this.lastF0 = 0;
    this.reason = 'reset';
  };

  LiveTracker.prototype._medianHist = function () {
    if (!this.hist.length) return null;
    var a = this.hist.slice().sort(function (x, y) { return x - y; });
    return a[a.length >> 1];
  };

  /** فاصلهٔ اکتاوی: اگر تخمین نزدیک ۲×/۰٫۵× مقدار قبلی باشد، اصلاح می‌شود */
  LiveTracker.prototype._fixOctave = function (midi, ref) {
    if (ref == null) return midi;
    var cands = [midi - 12, midi, midi + 12];
    var best = midi, bestD = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var d = Math.abs(cands[i] - ref);
      if (d < bestD) { bestD = d; best = cands[i]; }
    }
    return bestD <= this.octaveWindow ? best : midi;
  };

  /**
   * یک بسته را وارد کن. ورودی: خروجی extractPitchChunkInfo
   * خروجی: {note, silent, stable, midi, session}
   */
  LiveTracker.prototype.push = function (info) {
    var dt = this.chunkSec;
    this.t += dt;
    var rms = (info && info.rms) || 0;

    // ── ۱) دروازهٔ بلندیِ تطبیقی ──
    var gate = Math.max(0.0035, this.floor * 1.8);
    var voiced = !!(info && info.f0 > 0 && rms >= gate);

    if (!voiced) {
      // هر بستهٔ «بی‌نت» شاهدِ نویز است ⇒ کف نویز آرام‌آرام بالا می‌آید
      // (با سقف، تا یک صدای بلندِ غیرصدا کف را برای همیشه بالا نبرد)
      if (rms > 0) this.floor = 0.90 * this.floor + 0.10 * Math.min(rms, 0.06);
      this.hist = [];
      this.reason = (info && info.reason) || 'quiet';
    } else {
      // کف نویز: کمی بالاتر از بلندترین «سکوتِ اخیر» نگه داشته می‌شود
      if (rms < this.floor * 1.4) this.floor = 0.97 * this.floor + 0.03 * rms;
      var midi = hzToMidi(info.f0);
      // ── ۲) میانهٔ زمانی + اصلاح اکتاو نسبت به نتِ فعلی ──
      midi = this._fixOctave(midi, this.stable ? this.stable.midi : (this.lastF0 > 0 ? hzToMidi(this.lastF0) : null));
      this.hist.push(midi);
      if (this.hist.length > 5) this.hist.shift();
      var med = this._medianHist();
      if (this.stable && Math.abs(med - this.stable.midi) <= this.tolSemis) {
        this.stable.chunks++;
        this.stable.midi = this.stable.midi + 0.35 * (med - this.stable.midi);   // نرم‌سازی
        this.stable.f0 = midiToHz(this.stable.midi);
      } else {
        this.stable = { midi: med, chunks: 1, f0: midiToHz(med) };
      }
      this.lastF0 = this.stable.f0;
      this.lastVoiced = this.t;
    }

    // ── ۳) تصمیم نمایش ──
    var stableEnough = voiced && this.stable && this.stable.chunks >= this.stableChunks;
    var fresh = (this.t - this.lastVoiced) <= this.graceSec;
    var note = null, silent = false;

    if (stableEnough) {
      var midiS = this.stable.midi;
      var f0S = midiToHz(midiS);
      var midiR = Math.round(midiS);
      this.shown = { midi: midiS, f0: f0S };
      this.shownAt = this.t;
      note = {
        note_name: midiToName(midiS),
        midi: midiR,
        midi_exact: Math.round(midiS * 1000) / 1000,
        f0_hz: Math.round(f0S * 100) / 100,
        cents_offset: Math.round((midiS - midiR) * 100),
        stable: true,
        quality: Math.round(Math.min(1, this.stable.chunks / (this.stableChunks + 2)) * 100) / 100
      };
      // رکورد جلسه فقط از نتِ پایدار
      var lo = this.lo, hi = this.hi;
      if (!hi || midiS > hi.midi) this.hi = { midi: midiS, f0: f0S };
      if (!lo || midiS < lo.midi) this.lo = { midi: midiS, f0: f0S };
      void lo; void hi;
    } else if (!fresh) {
      // صدا قطع شده و مهلت تمام شده ⇒ «چیزی خوانده نمی‌شود»
      silent = true;
      this.shown = null;
      this.stable = null;
    } else {
      // در مهلت: همان نت قبلی می‌ماند (بدون پرش)
      note = null;
      if (this.shown) {
        note = {
          note_name: midiToName(this.shown.midi),
          midi: Math.round(this.shown.midi),
          midi_exact: Math.round(this.shown.midi * 1000) / 1000,
          f0_hz: Math.round(this.shown.f0 * 100) / 100,
          cents_offset: Math.round((this.shown.midi - Math.round(this.shown.midi)) * 100),
          stable: false,
          hold: true
        };
      }
    }

    return {
      note: note,
      silent: silent,
      voiced: voiced,
      reason: this.reason,
      rms: Math.round(rms * 10000) / 10000,
      floor: Math.round(this.floor * 10000) / 10000,
      midi: note ? note.midi_exact : null,
      session: {
        lowest: this.lo ? {
          note_name: midiToName(this.lo.midi),
          midi: Math.round(this.lo.midi * 100) / 100,
          f0_hz: Math.round(this.lo.f0 * 100) / 100
        } : null,
        highest: this.hi ? {
          note_name: midiToName(this.hi.midi),
          midi: Math.round(this.hi.midi * 100) / 100,
          f0_hz: Math.round(this.hi.f0 * 100) / 100
        } : null,
        span_semitones: (this.lo && this.hi) ? Math.round((this.hi.midi - this.lo.midi) * 100) / 100 : 0
      }
    };
  };

  /* ══════════════════════════════════════════════════════════════════════
     خروجی
     ══════════════════════════════════════════════════════════════════════ */
  var api = {
    // ثابت‌ها
    SR: SR, MIN_F0: MIN_F0, MAX_F0: MAX_F0, TIME_STEP: TIME_STEP,
    // ابزارهای عمومی (برای تست)
    resample: resample, highpass: highpass, denoise: denoise,
    prepareAudio: prepareAudio, safeDownmix: safeDownmix,
    pitchTrack: pitchTrack, pitchFrame: pitchFrame,
    pitchWindow: pitchWindow, pitchTrackRange: pitchTrackRange,
    yinParams: yinParams, yinTrackRange: yinTrackRange,
    refineNotesRange: refineNotesRange, applyRefinements: applyRefinements,
    snapNoteBoundaries: snapNoteBoundaries, rmsEnvelope: rmsEnvelope, envelopeEdges: envelopeEdges,
    spectralFlux: spectralFlux, bandEnergies: bandEnergies, viterbiNotes: viterbiNotes,
    applyNoteEdits: applyNoteEdits,
    spectralOctaveFix: spectralOctaveFix, frameScores: frameScores,
    harmonicSalience: harmonicSalience, octaveClassOf: octaveClassOf,
    refineNoteF0WithOctave: refineNoteF0WithOctave,
    yinTrack: yinTrack, fuseWithSecond: fuseWithSecond,
    smoothPitchContour: smoothPitchContour, removeOctaveSpecks: removeOctaveSpecks,
    segmentNotes: segmentNotes, refineNotes: refineNotes, refineNoteF0: refineNoteF0,
    strictTraceFreqs: strictTraceFreqs, isSungNote: isSungNote,
    dropTraceOutliers: dropTraceOutliers,
    analyzeChannels: analyzeChannels, analyzeChannelsAsync: analyzeChannelsAsync,
    buildReport: buildReport,
    extractPitchChunk: extractPitchChunk,
    extractPitchChunkInfo: extractPitchChunkInfo,
    LiveTracker: LiveTracker,
    qualityStatus: qualityStatus,
    midiToName: midiToName, hzToMidi: hzToMidi, midiToHz: midiToHz, median: median, percentile: percentile
  };

  global.LocalEngineCore = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof window !== 'undefined' ? window : globalThis);
