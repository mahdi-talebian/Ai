/* ============================================================================
   سبک مشترک «ردِّ صدا» — یک رندرر برای هر سه خروجی
   ----------------------------------------------------------------------------
   چرا این فایل وجود دارد؟
     قبلاً دو رندرر جدا بود: یکی برای نمودار داخل اپ (index.html) و یکی برای
     تصویر PNG و ویدیو (offline.js). رنگ‌ها، ضخامت‌ها، نقطهٔ نورانی و برچسب‌ها
     کمی فرق داشتند. حالا **هر سه** از همین یک تابع رسم می‌کنند، پس ویدیو
     دقیقاً همان چیزی است که در اپ می‌بینی.

   ورودی رسم (spec):
     {
       W, H, dpr,                  // اندازهٔ بوم به CSS پیکسل
       times: [t...],              // زمان نمونه‌ها (ثانیه، صعودی)
       mids:  [midi|null...],      // ارتفاع هر نمونه (null = بی‌صدا)
       notes: [{start, duration, f0_hz}]،
       range: {highest, lowest, span_semitones},
       duration,                   // طول کل صدا
       t,                          // لحظهٔ جاری (خط پخش)
       view: {t0, t1},             // بازهٔ زمانی دیده‌شده (زوم زمانی)
       tightness                   // زوم عمودی (۱ = پیش‌فرض، بزرگ‌تر = نزدیک‌تر)
     }
   ========================================================================== */
(function (global) {
  'use strict';

  var THEME = {
    bgTop: '#0d172b',
    bgBottom: '#172440',
    bgGlow: 'rgba(70,110,180,.20)',
    gridC: 'rgba(255,255,255,.16)',
    gridN: 'rgba(255,255,255,.07)',
    gridLabel: 'rgba(180,200,235,.45)',
    dimCurve: 'rgba(96,132,175,.55)',
    dimShadow: 'rgba(96,132,175,.35)',
    noteDim: 'rgba(120,155,195,.38)',
    goldLayers: [[8.2, 'rgba(255,212,120,.15)'],
                 [4.6, 'rgba(255,212,120,.34)'],
                 [2.7, '#f7d171']],
    noteGold: '#ffe6a6',
    noteFuture: 'rgba(140,168,205,.55)',
    noteBar: '#f7d57f',
    noteBarGlow: 'rgba(255,198,92,.30)',
    high: 'rgba(255,226,160,',
    low: 'rgba(169,214,255,',
    border: 'rgba(120,152,205,.28)',
    playheadGlow: 'rgba(255,255,255,.18)',
    playhead: 'rgba(255,255,255,.85)',
    dotGlowIn: 'rgba(255,244,214,.95)',
    dotGlowMid: 'rgba(255,222,140,.55)',
    dotGlowOut: 'rgba(255,222,140,0)',
    dotCore: '#fffaf0',
    label: '#f3eeda',
    faint: 'rgba(160,178,210,.85)',
    hint: 'rgba(196,210,235,.85)'
  };

  var FONT = 'Estedad, Vazirmatn, Tahoma, system-ui, sans-serif';

  /* ══════════════════════════════════════════════════════════════════════
     نامِ نت‌ها (سل‌فژ) و واحدِ «درجه»
     ----------------------------------------------------------------------
     کاربر می‌خواهد به‌جای «پرده»، «درجه» ببیند و هر اکتاو ۸ درجه باشد
     (اکتاو = ۸ درجه = ۶ پرده = ۱۲ نیم‌پرده). پس:
         ۱ درجه = ۱۲ ÷ ۸ = ۱٫۵ نیم‌پرده
         درجه = نیم‌پرده × ۸⁄۱۲ = نیم‌پرده × ۲⁄۳
     و اکتاوها از تقسیمِ درجه‌ها بر ۸ درمی‌آیند.
     ══════════════════════════════════════════════════════════════════════ */
  var SOLFEGE = { 0: 'دو', 2: 'ر', 4: 'می', 5: 'فا', 7: 'سل', 9: 'لا', 11: 'سی' };
  var FA_DIGITS = ['۰', '۱', '۲', '۳', '۴', '۵', '۶', '۷', '۸', '۹'];
  var DEG_PER_OCTAVE = 8;
  var SEMI_PER_OCTAVE = 12;
  var DEG_PER_SEMI = DEG_PER_OCTAVE / SEMI_PER_OCTAVE;   /* ۲⁄۳ */

  function faNum(v) {
    var str = String(v);
    var out = '';
    for (var i = 0; i < str.length; i++) {
      var ch = str.charAt(i);
      out += (ch >= '0' && ch <= '9') ? FA_DIGITS[+ch] : ch;
    }
    return out;
  }

  function pcOf(midi) { var r = Math.round(midi); return ((r % 12) + 12) % 12; }

  /* نامِ سل‌فژ؛ نتِ نیم‌پرده‌ای به نزدیک‌ترین نام با «♯» می‌چسبد */
  function solfegeOf(midi, withOctave) {
    if (midi == null || !isFinite(midi)) return '—';
    var pc = pcOf(midi);
    var base = SOLFEGE[pc];
    if (!base) {
      var lower = (pc + 11) % 12;
      base = (SOLFEGE[lower] || 'دو') + '♯';
    }
    if (!withOctave) return base;
    var oct = Math.floor(Math.round(midi) / 12) - 1;
    return base + faNum(oct);
  }

  /* نیم‌پرده → درجه (اکتاو = ۸ درجه) */
  function degreesOfSemitones(semis) {
    if (semis == null || !isFinite(semis)) return null;
    return semis * DEG_PER_SEMI;
  }

  /* {total, octaves, degrees, text} — متنِ آماده مثل «۱ اکتاو و ۳ درجه» */
  function spanInDegrees(semis) {
    var total = degreesOfSemitones(semis);
    if (total == null) return null;
    var octaves = Math.floor(total / DEG_PER_OCTAVE + 1e-9);
    var rest = Math.round((total - octaves * DEG_PER_OCTAVE) * 10) / 10;
    if (rest >= DEG_PER_OCTAVE - 1e-9) { octaves += 1; rest = 0; }
    var text;
    if (octaves <= 0) text = faNum(rest) + ' درجه';
    else if (rest < 0.05) text = faNum(octaves) + ' اکتاو';
    else text = faNum(octaves) + ' اکتاو و ' + faNum(rest) + ' درجه';
    return { total: total, octaves: octaves, degrees: rest, text: text };
  }

  /* شمارهٔ درجهٔ یک نت نسبت به پایین‌ترین نتِ اجرا (۱ = پایین‌ترین) */
  function degreeFromLow(midi, lowMidi) {
    if (midi == null || !isFinite(midi)) return null;
    if (lowMidi == null || !isFinite(lowMidi)) return null;
    return Math.round((midi - lowMidi) * DEG_PER_SEMI) + 1;
  }
  var GAP_BRIDGE_SEC = 0.75;
  var MIN_SPAN_SEMI = 11.9;

  /* ── ابزارهای کوچک ────────────────────────────────────────────────────── */
  function midiOf(f0) { return (f0 > 0) ? 69 + 12 * Math.log2(f0 / 440) : null; }
  function clamp(v, a, b) { return v < a ? a : (v > b ? b : v); }
  function quantile(sorted, q) {
    if (!sorted.length) return null;
    var pos = (sorted.length - 1) * q;
    var lo = Math.floor(pos), hi = Math.ceil(pos);
    if (lo === hi) return sorted[lo];
    return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
  }
  function median(arr) {
    if (!arr.length) return null;
    var b = arr.slice().sort(function (x, y) { return x - y; });
    return b[b.length >> 1];
  }
  function roundRect(g, x, y, w, h, r) {
    g.beginPath();
    g.moveTo(x + r, y);
    g.arcTo(x + w, y, x + w, y + h, r);
    g.arcTo(x + w, y + h, x, y + h, r);
    g.arcTo(x, y + h, x, y, r);
    g.arcTo(x, y, x + w, y, r);
    g.closePath();
  }

  /* ── هندسه: همان نسبت‌های نسخهٔ قبلی (۱۲۸۰×۷۲۰ → ۷۴/۴۶/۷۸/۶۴) ───────── */
  function layout(W, H) {
    return {
      W: W, H: H,
      ML: Math.round(W * 0.0335),
      MR: Math.round(W * 0.0215),
      MT: Math.round(H * 0.1083),
      MB: Math.round(H * 0.0889),
      /* ضریب اندازه برای متن/خطوط: در ۷۰۰px = ۱، در ۱۲۸۰px ≈ ۱٫۸ */
      k: clamp(W / 700, 0.8, 2.0)
    };
  }

  /* ── بازهٔ ارتفاعِ نشان‌داده‌شده ────────────────────────────────────────
     بدون زوم: مرکز/پهنا از کل اجرا ⇒ با لغزش نوار، تصویر نمی‌لرزد.
     با زوم: کادر دنبال کوکِ «همین لحظه» می‌رود (مثل دوربین) و باریک می‌شود،
     ولی هیچ‌وقت نُتی که همین حالا خوانده می‌شود از کادر بیرون نمی‌افتد. */
  /* آمارهٔ محلی با درصدک: یک نمونهٔ پرت (مثلاً پرش اکتاوی) کادر را باز نمی‌کند */
  function localStats(times, mids, t0, t1) {
    var arr = [];
    for (var i = 0; i < mids.length; i++) {
      var m = mids[i];
      if (m == null || !isFinite(m)) continue;
      if (times[i] < t0 || times[i] > t1) continue;
      arr.push(m);
    }
    if (!arr.length) return { lo: Infinity, hi: -Infinity, med: null, n: 0 };
    arr.sort(function (a, b) { return a - b; });
    return {
      lo: arr.length > 12 ? quantile(arr, 0.03) : arr[0],
      hi: arr.length > 12 ? quantile(arr, 0.97) : arr[arr.length - 1],
      med: arr[arr.length >> 1],
      n: arr.length
    };
  }

  /* بازهٔ «نت‌های واقعی»: پایدارتر از کانتور خام (که پرش اکتاوی دارد) */
  function noteStats(notes) {
    var arr = [];
    (notes || []).forEach(function (n) {
      if (!n || !(n.f0_hz > 0)) return;
      arr.push(69 + 12 * Math.log2(n.f0_hz / 440));
    });
    if (!arr.length) return null;
    arr.sort(function (a, b) { return a - b; });
    return {
      lo: arr.length >= 6 ? quantile(arr, 0.03) : arr[0],
      hi: arr.length >= 6 ? quantile(arr, 0.97) : arr[arr.length - 1],
      med: arr[arr.length >> 1]
    };
  }

  function pitchLimits(mids, view, times, tightness, nowT, notes) {
    var i, m;
    var all = [];
    for (i = 0; i < mids.length; i++) {
      m = mids[i];
      if (m != null && isFinite(m)) all.push(m);
    }
    if (!all.length) return { loLim: 48, hiLim: 60, center: 54, shown: 12 };

    all.sort(function (a, b) { return a - b; });
    /* درصدک ۲–۹۸: نمونه‌های پرت (پرش یک‌فریمی) کادر را باز نمی‌کنند */
    var gLo = all.length > 40 ? quantile(all, 0.02) : all[0];
    var gHi = all.length > 40 ? quantile(all, 0.98) : all[all.length - 1];
    var gCenter = all[all.length >> 1];

    /* اگر نت‌های تأییدشده داریم، گستره از آن‌ها می‌آید (بدون پرش‌های اکتاوی) */
    var ns = noteStats(notes);
    if (ns && isFinite(ns.lo) && isFinite(ns.hi) && ns.hi > ns.lo) {
      gLo = Math.min(ns.lo, gLo + (gHi - gLo) * 0.15);
      gHi = Math.max(ns.hi, gHi - (gHi - gLo) * 0.15);
      if (gHi < ns.hi) gHi = ns.hi;
      if (gLo > ns.lo) gLo = ns.lo;
      gCenter = ns.med;
    }
    var base = Math.max(gHi - gLo, MIN_SPAN_SEMI) * 1.2;
    var z = (tightness && tightness > 0) ? tightness : 1;

    var inView = (view && times) ? localStats(times, mids, view.t0, view.t1) : { lo: gLo, hi: gHi, med: gCenter, n: 1 };
    var viewSpan = (inView.hi > inView.lo) ? (inView.hi - inView.lo) : 0;

    if (z <= 1.0001) {
      var shown0 = Math.max(base, viewSpan + 1.0);
      return {
        loLim: gCenter - shown0 / 2, hiLim: gCenter + shown0 / 2,
        center: gCenter, shown: shown0, zoomEff: 1
      };
    }

    /* کادر باریک، ولی خوانا: کف ۴ نیم‌پرده */
    var shown = base / z;
    if (shown < 4.0) shown = 4.0;
    if (shown > base) shown = base;

    /* مرکز: کوکِ همین لحظه (پنجرهٔ ±۱٫۲ ثانیه) — با احتیاط به میانهٔ کل */
    var near = { med: null, lo: Infinity, hi: -Infinity };
    if (times && isFinite(nowT)) near = localStats(times, mids, nowT - 1.2, nowT + 1.2);
    var center = (near.med != null) ? near.med
      : (inView.med != null) ? inView.med : gCenter;

    /* نیم‌پردهٔ کف برای خوانایی: ویبراتوی معمولی (±۱ نیم‌پرده) کامل جا می‌شود */
    var halfS = shown / 2;
    if (halfS < 2.0) halfS = Math.min(2.0, base / 2);
    /* اگر نُتِ همین لحظه به لبه چسبیده باشد، کادر کمی جابه‌جا می‌شود */
    if (near.hi > near.lo) {
      var needC = Math.max(Math.abs(near.hi - center), Math.abs(center - near.lo)) + 0.6;
      if (needC > halfS) {
        var shiftCase = (near.hi - center > center - near.lo) ? (center + (needC - halfS))
          : (center - (needC - halfS));
        center = shiftCase;
      }
    }
    center = Math.max(gLo - 0.5 + halfS, Math.min(gHi + 0.5 - halfS, center));
    if (!(center === center)) center = gCenter;

    return {
      loLim: center - halfS, hiLim: center + halfS,
      center: center, shown: halfS * 2, zoomEff: base / (halfS * 2)
    };
  }

  /* ══════════════════════════════════════════════════════════════════════
     پاک‌سازی «پرش‌های کاذب» قبل از رسم
     ----------------------------------------------------------------------
     چرا لازم است؟ تخمینِ f0 در چند فریم ممکن است روی هارمونیک دوم/سوم
     «قفل» کند و یک اکتاو یا پنجم جابه‌جا شود. آن چند فریمِ پرت، به‌صورت
     خطِ عمودیِ بلند رسم می‌شدند (همان «موج‌های سینوسیِ عمودی»).
     این‌جا — بی‌آنکه دادهٔ خامِ گزارش دست بخورد — برای *رسم*:
       ۱) پرشِ اکتاوی/دوازدهم را به نزدیک‌ترین اکتاوِ محلی برمی‌گردانیم
       ۲) «گشت کوتاه» (کمتر از ۸۰ms) که به مقدارِ محلی برمی‌گردد را حذف
          می‌کنیم (مقدارِ محلی میانه جایش می‌نشیند)
     نتیجه: منحنی همان چیزی است که شنیده می‌شود، بدون خطوط عمودیِ کاذب.
     ══════════════════════════════════════════════════════════════════════ */
  /* کلیدِ پاک‌سازی (برای تست: با setClean(false) رفتارِ قبلی بازتولید می‌شود) */
  var CFG = { clean: true };
  function setClean(on) { CFG.clean = !!on; }

  var SPIKE_SEMI = 2.6;           /* این‌قدر فاصله از «سطحِ محلی» = پرت */
  var SPikeHalfWin = 25;          /* نیم‌پنجرهٔ میانهٔ غلتان (۲۵ فریم ≈ ۱۲۵ms) */
  var SPIKE_MAX_RUN = 24;         /* حداکثر طولِ گشتی که حذف می‌شود (~۱۲۰ms) */

  function medianOf(arr) {
    if (!arr.length) return null;
    var b = arr.slice().sort(function (x, y) { return x - y; });
    return b[b.length >> 1];
  }

  /* ── سطحِ محلی با «میانهٔ غلتان» ─────────────────────────────────────────
     میانه غلتان دو ویژگی مهم دارد: پلهٔ واقعیِ بین نُت‌ها را جابه‌جا نمی‌کند
     (چون در هر پنجره اکثریت با یک سمت است) و گشتِ کوتاه‌تر از نیم‌پنجره را
     کامل نادیده می‌گیرد. پس مرجعِ خوبی برای تشخیص پرت است. */
  function rollingMedian(mids, half) {
    var n = mids.length, out = new Array(n);
    for (var i = 0; i < n; i++) {
      var vals = [];
      for (var j = Math.max(0, i - half); j <= Math.min(n - 1, i + half); j++) {
        var v = mids[j];
        if (v != null && isFinite(v)) vals.push(v);
      }
      out[i] = vals.length ? medianOf(vals) : null;
    }
    return out;
  }

  function cleanMids(times, mids) {
    var n = mids.length;
    if (!n) return mids;
    var orig = mids;                     /* داوریِ پاس دوم روی دادهٔ خام */
    var level = rollingMedian(mids, SPikeHalfWin);
    var out = new Array(n);
    var i, j;
    for (i = 0; i < n; i++) out[i] = mids[i];

    i = 0;
    while (i < n) {
      var m = out[i], lv = level[i];
      if (m == null || lv == null || Math.abs(m - lv) <= SPIKE_SEMI) { i++; continue; }
      /* شروعِ گشت: تا کجا ادامه دارد؟ */
      var k = i;
      while (k < n && out[k] != null && level[k] != null &&
        Math.abs(out[k] - level[k]) > SPIKE_SEMI) k++;
      var runLen = k - i;
      if (runLen <= SPIKE_MAX_RUN) {
        /* مقدارِ قبل و بعد از گشت (نزدیک‌ترین نمونه‌های سالم) */
        var beforeV = null, afterV = null;
        for (j = i - 1; j >= 0 && j > i - 8; j--) {
          if (out[j] != null && Math.abs(out[j] - (level[j] == null ? out[j] : level[j])) <= SPIKE_SEMI) {
            beforeV = out[j]; break;
          }
        }
        for (j = k; j < n && j < k + 8; j++) {
          if (out[j] != null && Math.abs(out[j] - (level[j] == null ? out[j] : level[j])) <= SPIKE_SEMI) {
            afterV = out[j]; break;
          }
        }
        var a = (beforeV != null) ? beforeV : level[i];
        var b = (afterV != null) ? afterV : level[Math.min(n - 1, k)];
        if (a != null && b != null) {
          if (runLen === 1) { out[i] = (a + b) / 2; }
          else {
            for (j = 0; j < runLen; j++) {
              var f = (j + 1) / (runLen + 1);
              out[i + j] = a + (b - a) * f;
            }
          }
        }
      }
      i = k > i ? k : i + 1;
    }

    /* ── پاس دوم: اصلاحِ «خطای اکتاو» در گشت‌های کوتاه ─────────────────────
       مشکلِ واقعیِ فایلِ خواننده همین بود: گاهی ۰٫۲–۰٫۵ ثانیه تخمین روی
       هارمونیکِ دوم قفل می‌کند و منحنی یک‌دفعه ۱۲ نیم‌پرده بالا می‌رود و
       برمی‌گردد. میانهٔ محلی این حالت را نمی‌بیند (چون خودش هم بالا رفته)،
       پس مرجع را از «گشت‌های بلندِ دو طرف» می‌گیریم: اگر گشتِ کوتاه از
       سطحِ قبل و بعد (که با هم هم‌سطح‌اند) بیش از ۸ نیم‌پرده فاصله داشته
       باشد، به نزدیک‌ترین اکتاو برگردانده می‌شود؛ اگر برنگشت، حذف می‌شود. */
    var runs = [], ri = 0;
    while (ri < n) {
      if (out[ri] == null || !isFinite(out[ri])) { ri++; continue; }
      var j0 = ri;
      while (ri < n && out[ri] != null && isFinite(out[ri])) ri++;
      var vals2 = [];
      for (j = j0; j < ri; j++) {
        if (orig[j] != null && isFinite(orig[j])) vals2.push(orig[j]);
      }
      if (!vals2.length) { for (j = j0; j < ri; j++) vals2.push(out[j]); }
      vals2.sort(function (a, b) { return a - b; });
      runs.push({ i0: j0, i1: ri - 1, med: vals2[vals2.length >> 1] });
    }
    if (runs.length >= 3) {
      var step = 1 / 30;
      if (times && times.length > 1) {
        step = (times[times.length - 1] - times[0]) / Math.max(1, times.length - 1);
        if (!(step > 0)) step = 1 / 30;
      }
      var fOf = function (r) { return times ? times[r.i0] : r.i0 * step; };
      var lOf = function (r) { return times ? times[r.i1] : r.i1 * step; };
      var dur = function (r) { return lOf(r) - fOf(r); };
      for (var a2 = 0; a2 < runs.length; a2++) {
        var r = runs[a2];
        if (dur(r) > 0.45) continue;                 /* نتِ کشیده = واقعی */
        var pre = null, post = null;
        for (var b2 = a2 - 1; b2 >= 0; b2--) {
          if (dur(runs[b2]) >= 0.25) { if (fOf(r) - lOf(runs[b2]) <= 3) pre = runs[b2]; break; }
        }
        for (b2 = a2 + 1; b2 < runs.length; b2++) {
          if (dur(runs[b2]) >= 0.25) { if (fOf(runs[b2]) - lOf(r) <= 3) post = runs[b2]; break; }
        }
        var ref = null, dev = 0, kOct = 0, shifted = 0;
        if (pre && post && Math.abs(pre.med - post.med) <= 2) {
          ref = (pre.med + post.med) / 2;
          dev = r.med - ref;
          kOct = Math.round(dev / 12);
          shifted = r.med - 12 * kOct;
          if (Math.abs(dev) >= 8 && kOct !== 0 && Math.abs(shifted - ref) <= 3) {
            for (j = r.i0; j <= r.i1; j++) {        /* برگشت به اکتاو */
              var rv = (orig[j] != null && isFinite(orig[j])) ? orig[j] : out[j];
              out[j] = rv - 12 * kOct;
            }
            continue;
          }
        }
        /* مرجعِ پشتیبان: میانهٔ وزنیِ گشت‌های ±۲٫۵ ثانیه. گشتِ کوتاهی که
           بیش از ۶ نیم‌پرده از آن دور بماند، پرت است (نه نُتِ واقعی). */
        var pool = [], tc = (fOf(r) + lOf(r)) / 2;
        for (b2 = 0; b2 < runs.length; b2++) {
          if (b2 === a2) continue;
          var o2 = runs[b2];
          var oc = (fOf(o2) + lOf(o2)) / 2;
          if (Math.abs(oc - tc) > 2.5) continue;
          var reps = Math.min(20, o2.i1 - o2.i0 + 1);
          for (var w = 0; w < reps; w++) pool.push(o2.med);
        }
        if (pool.length < 6) continue;
        pool.sort(function (x, y) { return x - y; });
        var ref2 = pool[pool.length >> 1];
        if (Math.abs(r.med - ref2) >= 4.5) {
          for (j = r.i0; j <= r.i1; j++) out[j] = null;        /* حذفِ پرت */
        }
      }
    }
    /* ── پاس چهارم: محدودیتِ سرعتِ تغییرِ گام ─────────────────────────────
       صدای انسان نمی‌تواند بیش از ~۶۰ نیم‌پرده در ثانیه تغییر کند؛ هر چیزی
       تندتر از این، پرشِ تخمین است و به‌صورت خطِ عمودی دیده می‌شود. مقدار را
       تا سقفِ مجاز به سمتِ مقدارِ قبلی می‌کشیم (بدون حذفِ داده). */
    /* ── پاس پنجم: صافیِ نمایشی ────────────────────────────────────────────
       دو کار، فقط برای «رسم» (دادهٔ گزارش دست نمی‌خورد):
         ۱) میانهٔ سه‌تایی در فضای نیم‌پرده → لرزشِ تک‌فریمی می‌رود، ویبراتو می‌ماند
         ۲) سقفِ سرعت ۴۵ نیم‌پرده/ثانیه → هیچ «کپسولِ عمودی» روی نمودار نمی‌ماند؛
            گذرِ تند به یک شیبِ کوتاهِ خوانا تبدیل می‌شود. */
    /* میانهٔ ۵ (و اگر نشد ۳): دندانه‌های دو-سه‌فریمیِ تخمین را می‌خوابانَد،
       ولی ویبراتو (۶Hz، دامنهٔ ±۰٫۵) و گذرهای واقعی را نگه می‌دارد. */
    var win = [];
    for (i = 1; i < n - 1; i++) {
      if (out[i] == null) continue;
      var w5 = [];
      var ok5 = true;
      for (j = i - 2; j <= i + 2; j++) {
        if (j < 0 || j >= n || out[j] == null) { ok5 = false; break; }
        w5.push(out[j]);
      }
      if (ok5) {
        w5.sort(function (a, b) { return a - b; });
        win.push([i, w5[2]]);
        continue;
      }
      if (out[i - 1] != null && out[i + 1] != null) {
        var t3 = [out[i - 1], out[i], out[i + 1]].sort(function (a, b) { return a - b; });
        win.push([i, t3[1]]);
      }
    }
    for (i = 0; i < win.length; i++) out[win[i][0]] = win[i][1];

    var MAX_RATE = 45;
    var prevI = -1;
    for (i = 0; i < n; i++) {
      if (out[i] == null || !isFinite(out[i])) continue;
      if (prevI >= 0) {
        var dt = times ? (times[i] - times[prevI]) : (i - prevI) / 30;
        if (dt >= 0.01 && dt < 0.5) {
          var capRate = MAX_RATE * dt;
          var d = out[i] - out[prevI];
          if (Math.abs(d) > capRate) out[i] = out[prevI] + (d > 0 ? capRate : -capRate);
        }
      }
      prevI = i;
    }

    /* ── پاس سوم: «تیغِ» تک‌فریمی ─────────────────────────────────────────
       اگر همسایهٔ چپ و راستِ نزدیک (زیر ۱۲۰ms) هم‌سطح باشند و فریم بیش از
       ۴ نیم‌پرده از آن‌ها فاصله داشته باشد، یک تیغِ عمودی است: با میانگینِ
       دو همسایه جای‌گزین می‌شود. */
    for (i = 0; i < n; i++) {
      if (out[i] == null || !isFinite(out[i])) continue;
      var lp = -1, rp = -1;
      for (j = i - 1; j >= 0 && j >= i - 6; j--) {
        if (out[j] != null && isFinite(out[j])) { lp = j; break; }
      }
      for (j = i + 1; j < n && j <= i + 6; j++) {
        if (out[j] != null && isFinite(out[j])) { rp = j; break; }
      }
      if (lp < 0 || rp < 0) continue;
      if (times) {
        if (times[i] - times[lp] > 0.12 || times[rp] - times[i] > 0.12) continue;
      }
      var lm = out[lp], rm = out[rp];
      if (Math.abs(lm - rm) > 2) continue;
      var mn = (lm + rm) / 2;
      if (Math.abs(out[i] - mn) > 4) out[i] = mn;
    }
    return out;
  }

  /* ── حذف نت‌های پرت (اکتاو/هارمونیک) ────────────────────────────────────
     هر نُتِ تشخیص‌داده‌شده با «سطحِ منحنی» در همان لحظه مقایسه می‌شود؛ اگر
     بیش از ۵ نیم‌پرده فاصله داشته باشد و از ۰٫۶ ثانیه کوتاه‌تر باشد، پرت است
     (قفلِ تخمین روی هارمونیک دوم/سوم). این نت‌ها هم نقطه‌های پرتِ روی نمودار
     می‌ساختند و هم در شمارش/جدولِ نت‌ها می‌آمدند. */
  var NOTE_OUTLIER_SEMI = 5.0;
  var NOTE_OUTLIER_MAX_SEC = 0.6;

  function cleanNotes(notes, times, mids) {
    var list = notes || [];
    if (!list.length || !times || !mids || !mids.length) return list;
    /* مرجع محلی: میانهٔ منحنیِ پاک‌شده در بازهٔ خودِ نت (±۰٫۳۵ ثانیه).
       اگر ناحیه بی‌صدا باشد، پنجره تا ±۱٫۲ ثانیه باز می‌شود. */
    function levelFor(start, dur) {
      var win = 0.35, vals = [];
      for (var pass = 0; pass < 2; pass++) {
        vals = [];
        var a = start - win, b = start + Math.max(dur, 0.06) + win;
        for (var i = 0; i < times.length; i++) {
          var t = times[i];
          if (t < a) continue;
          if (t > b) break;
          var v = mids[i];
          if (v != null && isFinite(v)) vals.push(v);
        }
        if (vals.length >= 3) break;
        win = 1.2;
      }
      if (vals.length < 3) return null;
      vals.sort(function (x, y) { return x - y; });
      return vals[vals.length >> 1];
    }
    var kept = [], folded = 0, removed = 0;
    for (var i = 0; i < list.length; i++) {
      var n = list[i];
      if (!n || !(n.f0_hz > 0)) continue;
      var m = midiOf(n.f0_hz);
      var start = n.start || 0;
      var dur = n.duration || 0;
      if (m == null || dur > NOTE_OUTLIER_MAX_SEC) { kept.push(n); continue; }
      var lvl = levelFor(start, dur);
      if (lvl == null) {
        /* ناحیهٔ بی‌صدا: مرجعِ آخر = میانهٔ گامِ نت‌های پایدارِ همسایه */
        var nb = [];
        for (var k2 = 0; k2 < list.length; k2++) {
          var o3 = list[k2];
          if (o3 === n || !(o3.f0_hz > 0)) continue;
          if (Math.abs((o3.start || 0) - start) > 2.0) continue;
          nb.push(midiOf(o3.f0_hz));
        }
        if (nb.length < 3) { kept.push(n); continue; }
        nb.sort(function (x, y) { return x - y; });
        lvl = nb[nb.length >> 1];
      }
      var dev = m - lvl;
      if (Math.abs(dev) <= NOTE_OUTLIER_SEMI) { kept.push(n); continue; }
      /* خطای اکتاو: اگر ÷۲ (یا ÷۳) نت را دقیقاً روی سطح محلی بنشاند،
         گام تصحیح می‌شود؛ وگرنه نتِ پرت حذف می‌شود. */
      var k = Math.round(dev / 12);
      if (k !== 0 && Math.abs(dev - 12 * k) <= 3) {
        var f = n.f0_hz / Math.pow(2, k);
        n = Object.assign({}, n, {
          f0_hz: f, f0_hz_folded: n.f0_hz,
          f0_hz_coarse: (n.f0_hz_coarse > 0 ? n.f0_hz_coarse / Math.pow(2, k) : n.f0_hz_coarse)
        });
        folded++;
        kept.push(n);
        continue;
      }
      removed++;
    }
    if (folded) kept.foldedOutliers = folded;
    if (removed) kept.removedOutliers = removed;
    return kept;
  }

  /* پاک‌سازی گران است (یک‌بار برای هر فایل کافی است)؛ نتیجه را بر اساس
     «همان آرایه» کش می‌کنیم تا رسمِ ۶۰fps و هزاران فریمِ ویدیو هزینهٔ اضافه
     ندهند. */
  var CLEAN_CACHE = { src: null, n: -1, out: null };
  function cleanedFor(mids) {
    if (!mids) return mids;
    if (CLEAN_CACHE.src === mids && CLEAN_CACHE.n === mids.length) return CLEAN_CACHE.out;
    var out = CFG.clean ? cleanMids(null, mids) : mids;
    CLEAN_CACHE.src = mids; CLEAN_CACHE.n = mids.length; CLEAN_CACHE.out = out;
    return out;
  }

  /* ── ساخت نقطه‌های منحنی در فضای پیکسل (نسبت به بازهٔ دید) ───────────── */
  function buildSegments(times, mids, view, xOf, yOf) {
    var runs = [], cur = [], lastT = null, i, curHard = false;
    for (i = 0; i < times.length; i++) {
      var t = times[i], m = mids[i];
      var inside = (t >= view.t0 - 1e-9) && (t <= view.t1 + 1e-9);
      if (m == null || !isFinite(m) || !inside) {
        if (cur.length > 1) { cur.hardStart = curHard; runs.push(cur); }
        cur = []; curHard = false; lastT = null; continue;
      }
      if (lastT != null && t - lastT > GAP_BRIDGE_SEC) {
        if (cur.length > 1) { cur.hardStart = curHard; runs.push(cur); }
        cur = []; curHard = false;            /* شکافِ بی‌صدا: پل‌زدن آزاد است */
      } else if (cur.length) {
        /* پرشِ غیرممکن: تغییر > ۷ نیم‌پرده در کمتر از ۲۵ms در صدای انسان
           رخ نمی‌دهد؛ به‌جای خطِ عمودیِ بلند، سگمنت را قطع می‌کنیم. */
        var prev = cur[cur.length - 1];
        if (Math.abs(m - (prev.m == null ? m : prev.m)) > 4 && (t - lastT) < 0.05) {
          if (cur.length > 1) { cur.hardStart = curHard; runs.push(cur); }
          cur = []; curHard = true;
        }
      }
      cur.push({ x: xOf(t), y: yOf(m), t: t, m: m });
      lastT = t;
    }
    if (cur.length > 1) { cur.hardStart = curHard; runs.push(cur); }

    /* پر کردن شکاف‌های کوتاه با درون‌یابی خطی (نمای پیوسته) */
    var segs = [];
    for (i = 0; i < runs.length; i++) {
      var run = runs[i];
      if (!segs.length) { segs.push(run.slice()); continue; }
      if (run.hardStart) { segs.push(run.slice()); continue; }
      var prev = segs[segs.length - 1];
      var gap = run[0].t - prev[prev.length - 1].t;
      /* اگر دو سرِ شکاف بیش از ۷ نیم‌پرده فاصله داشته باشند، پل‌زدن یک
         خطِ عمودیِ بلند می‌سازد؛ همان «موجِ سینوسیِ عمودی» که نباید دیده شود. */
      var prevM = prev[prev.length - 1].m, nextM = run[0].m;
      var leap = (prevM != null && nextM != null) ? Math.abs(nextM - prevM) : 0;
      /* پل‌زدن فقط وقتی «شیبِ معقول» داشته باشد؛ وگرنه خط قطع می‌شود
         (خطِ عمودیِ کپسولی همان چیزی است که کاربر به‌عنوان ضایعات می‌دید). */
      var slope = gap > 1e-6 ? leap / gap : 999;
      if (gap <= GAP_BRIDGE_SEC && leap <= 4 && slope <= 12) {
        var a = prev[prev.length - 1], b = run[0];
        var n = Math.max(2, Math.min(24, Math.round(gap * 40) + 2));
        for (var j = 1; j < n; j++) {
          var f = j / n;
          prev.push({
            x: a.x + (b.x - a.x) * f,
            y: a.y + (b.y - a.y) * f,
            t: a.t + (b.t - a.t) * f
          });
        }
        for (j = 0; j < run.length; j++) prev.push(run[j]);
      } else {
        segs.push(run.slice());
      }
    }
    /* ── صاف‌کردنِ هندسیِ مسیر (Chaikin) ──────────────────────────────────
       خطِ چندضلعی با ضخامتِ زیاد و سرِ گرد، روی هر پرشِ کوتاه یک «کپسولِ
       عمودی» می‌سازد. با دو مرحله برشِ گوشه‌ها، همان پرش به یک خمِ کوتاهِ
       طبیعی تبدیل می‌شود؛ طول/ارتفاعِ داده عوض نمی‌شود، فقط گوشه‌ها گرد
       می‌شوند (۲ ← ۴ ← ۸ نقطه به‌ازای هر جفت). */
    function chaikin(pts, iters) {
      var cur = pts;
      for (var it = 0; it < iters; it++) {
        if (cur.length < 2) break;
        var nxt = [cur[0]];
        for (var k = 1; k < cur.length; k++) {
          var a0 = cur[k - 1], b0 = cur[k];
          nxt.push({
            x: a0.x * 0.75 + b0.x * 0.25, y: a0.y * 0.75 + b0.y * 0.25,
            t: a0.t + (b0.t - a0.t) * 0.25, m: (a0.m != null && b0.m != null) ? a0.m + (b0.m - a0.m) * 0.25 : (a0.m != null ? a0.m : b0.m)
          });
          nxt.push({
            x: a0.x * 0.25 + b0.x * 0.75, y: a0.y * 0.25 + b0.y * 0.75,
            t: a0.t + (b0.t - a0.t) * 0.75, m: (a0.m != null && b0.m != null) ? a0.m + (b0.m - a0.m) * 0.75 : (a0.m != null ? a0.m : b0.m)
          });
        }
        nxt.push(cur[cur.length - 1]);
        cur = nxt;
      }
      return cur;
    }

    var smoothed = [];
    for (i = 0; i < segs.length; i++) smoothed.push(chaikin(segs[i], 2));
    return smoothed.filter(function (s) { return s.length > 1; });
  }

  /* ── لایهٔ ثابت: پس‌زمینه، خطوط نیم‌پرده، منحنی محو آینده، کادر ──────── */
  function drawBase(g, spec, L, segs) {
    var W = spec.W, H = spec.H, k = L.k;

    var bg = g.createLinearGradient(0, 0, 0, H);
    bg.addColorStop(0, THEME.bgTop);
    bg.addColorStop(1, THEME.bgBottom);
    g.fillStyle = bg;
    g.fillRect(0, 0, W, H);

    var halo = g.createRadialGradient(W / 2, H / 2, 0, W / 2, H / 2, Math.max(W, H) * 0.62);
    halo.addColorStop(0, THEME.bgGlow);
    halo.addColorStop(1, 'rgba(70,110,180,0)');
    g.fillStyle = halo;
    g.fillRect(0, 0, W, H);

    /* خطوط: هر نیم‌پرده یک خط، نت‌های C پررنگ‌تر با نام */
    var loI = Math.ceil(L.loLim), hiI = Math.floor(L.hiLim);
    /* با زوم عمودی، خطوط کم می‌شوند ⇒ همه برچسب می‌گیرند (خوانا) */
    L.labelAll = (L.hiLim - L.loLim) <= 9;
    if (hiI - loI > 96) {                       /* زوم خیلی باز ⇒ هر ۲ نیم‌پرده */
      for (var m = Math.ceil(loI / 2) * 2; m <= hiI; m += 2) drawGridLine(g, spec, L, m, true);
    } else {
      for (m = loI; m <= hiI; m++) drawGridLine(g, spec, L, m, false);
    }

    /* منحنی محو (آینده) — به‌جای shadowBlur (که روی گوشی هر فریم را بسیار
       کند می‌کند و تصویر را از صدا عقب می‌انداخت) یک لایهٔ پهنِ کم‌رنگ. */
    for (var s = 0; s < segs.length; s++) {
      var seg = segs[s];
      g.strokeStyle = THEME.dimShadow;
      g.lineWidth = 3.8 * k;
      g.beginPath();
      for (var i = 0; i < seg.length; i++) {
        if (i === 0) g.moveTo(seg[i].x, seg[i].y); else g.lineTo(seg[i].x, seg[i].y);
      }
      g.stroke();
      g.strokeStyle = THEME.dimCurve;
      g.lineWidth = 2.7 * k;
      g.beginPath();
      for (i = 0; i < seg.length; i++) {
        if (i === 0) g.moveTo(seg[i].x, seg[i].y); else g.lineTo(seg[i].x, seg[i].y);
      }
      g.stroke();
    }

    /* نت‌های محو: کشیده‌ها نوارِ محو، کوتاه‌ها نقطه */
    (spec.notes || []).forEach(function (n) {
      var mm = midiOf(n.f0_hz);
      if (mm == null) return;
      var tc = (n.start || 0) + (n.duration || 0) / 2;
      if (spec.view && (tc < spec.view.t0 || tc > spec.view.t1)) return;
      if ((n.duration || 0) >= BAR_MIN_SEC) {
        /* آینده: نوارِ نازک و محو (کپسولِ طلایی مالِ گذشته است) */
        drawNoteBar(g, L, n, null, THEME.noteFuture, null, 0.42);
        return;
      }
      var x = L.xOf(tc), y = L.yOf(mm);
      g.fillStyle = THEME.noteDim;
      g.beginPath();
      if (g.ellipse) g.ellipse(x, y, 4.1 * k, 2.4 * k, 0, 0, Math.PI * 2);
      else g.arc(x, y, 3.6 * k, 0, Math.PI * 2);
      g.fill();
    });

    /* کادر گرد */
    g.strokeStyle = THEME.border;
    g.lineWidth = 1.4;
    roundRect(g, 3, 3, W - 6, H - 6, 14);
    g.stroke();
  }

  function drawGridLine(g, spec, L, m, every2) {
    var y = L.yOf(m);
    if (!isFinite(y)) return;
    var isC = ((m % 12) + 12) % 12 === 0;
    g.strokeStyle = isC ? THEME.gridC : THEME.gridN;
    g.lineWidth = isC ? 1.4 : 1;
    g.beginPath();
    g.moveTo(6, y);
    g.lineTo(spec.W - 6, y);
    g.stroke();
    if ((isC || L.labelAll) && m > L.loLim + 0.3 && m < L.hiLim - 0.3) {
      g.fillStyle = THEME.gridLabel;
      g.font = '600 ' + Math.round(10 * L.k) + 'px ' + FONT;
      g.textAlign = 'left';
      g.fillText(nameOf(m), 8, y - 4 * L.k);
    }
    if (every2 && !isC) { /* بدون برچسب */ }
  }

  function nameOf(midi) {
    var names = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
    var r = Math.round(midi);
    return names[((r % 12) + 12) % 12] + (Math.floor(r / 12) - 1);
  }

  /* ── نوارِ نت‌های کشیده ────────────────────────────────────────────────
     نتی که «امتداد» دارد (≥ ۰٫۳۴ ثانیه) به‌جای نقطهٔ ریز، یک نوارِ ضخیمِ
     دو-سرگرد در همان ارتفاعِ خودش می‌گیرد — مثل نُت‌های کشیدهٔ عکس‌ها.
     هرچه نت طولانی‌تر، نوار کلفت‌تر؛ نتِ کوتاه همان نقطهٔ ریز می‌ماند. */
  var BAR_MIN_SEC = 0.34;

  function barWidth(dur, k) {
    var d = (dur > 0 ? dur : 0);
    if (d > 4.0) d = 4.0;                      /* بعد از ۴ ثانیه دیگر کلفت‌تر نمی‌شود */
    var w = 5.4 + 3.2 * d;                     /* ~۵٫۴px تا ~۱۸px در مقیاس k=۱ */
    if (w > 16.0) w = 16.0;
    return w * k;
  }
  function clampX(L, x) {
    var lo = L.ML, hi = L.W - L.MR;
    return x < lo ? lo : (x > hi ? hi : x);
  }

  /* tLimit = لحظهٔ جاری (نوارِ نتِ در حال اجرا تا همین‌جا کشیده می‌شود) */
  function drawNoteBar(g, L, note, tLimit, color, glowColor, widthScale) {
    var dur = note.duration || 0;
    if (dur < BAR_MIN_SEC) return false;
    var mm = midiOf(note.f0_hz);
    if (mm == null) return false;
    var start = note.start || 0;
    var end = start + dur;
    var growing = (tLimit != null && tLimit < end);
    if (growing) end = Math.max(start, tLimit);
    var inset = Math.min(dur * 0.18, 0.10);
    var x0 = clampX(L, L.xOf(start + inset));
    var x1 = clampX(L, L.xOf(end - (growing ? 0 : inset)));
    var y = L.yOf(mm);
    if (!isFinite(y) || !isFinite(x0) || !isFinite(x1)) return false;
    if (x1 < x0) { var tmp = x0; x0 = x1; x1 = tmp; }
    var w = barWidth(dur, L.k) * (widthScale > 0 ? widthScale : 1);
    g.lineCap = 'round';
    g.lineJoin = 'round';
    if (glowColor) {                       /* هالهٔ نرم، مثل خط طلایی */
      g.strokeStyle = glowColor;
      g.lineWidth = w * 1.55;
      g.beginPath(); g.moveTo(x0, y); g.lineTo(x1, y); g.stroke();
    }
    g.strokeStyle = color;
    g.lineWidth = w;
    g.beginPath(); g.moveTo(x0, y); g.lineTo(x1, y); g.stroke();
    return true;
  }

  /* ── لایهٔ متحرک: منحنی طلایی تا لحظهٔ t، نقطه‌ها، راهنماها، خط پخش ─── */
  function drawDynamic(g, spec, L, segs) {
    var k = L.k, t = spec.t, W = spec.W, H = spec.H;
    var endT = (spec.view && isFinite(t)) ? Math.min(t, spec.view.t1) : t;

    /* منحنی طلایی: سه لایه = درخشش */
    g.lineCap = 'round';
    g.lineJoin = 'round';
    for (var s = 0; s < segs.length; s++) {
      var seg = segs[s], pts = [];
      for (var i = 0; i < seg.length; i++) {
        if (seg[i].t <= endT) pts.push(seg[i]); else break;
      }
      if (!pts.length) continue;
      var nxt = null;
      for (i = 0; i < seg.length; i++) if (seg[i].t > endT) { nxt = seg[i]; break; }
      if (nxt) {
        var a = pts[pts.length - 1];
        pts = pts.concat([{ x: (a.x + nxt.x) / 2, y: (a.y + nxt.y) / 2, t: endT }]);
      }
      if (pts.length < 2) continue;
      for (var ly = 0; ly < THEME.goldLayers.length; ly++) {
        g.strokeStyle = THEME.goldLayers[ly][1];
        g.lineWidth = THEME.goldLayers[ly][0] * k;
        g.beginPath();
        for (i = 0; i < pts.length; i++) {
          if (i === 0) g.moveTo(pts[i].x, pts[i].y); else g.lineTo(pts[i].x, pts[i].y);
        }
        g.stroke();
      }
    }

    /* نت‌های خوانده‌شده: کشیده‌ها نوارِ طلایی (تا لحظهٔ جاری کشیده می‌شوند)،
       کوتاه‌ها همان نقطهٔ ریز */
    (spec.notes || []).forEach(function (n) {
      if ((n.start || 0) > t) return;
      if (spec.view && (n.start < spec.view.t0 || n.start > spec.view.t1)) return;
      if ((n.duration || 0) >= BAR_MIN_SEC) {
        drawNoteBar(g, L, n, endT, THEME.noteBar, THEME.noteBarGlow);
        return;
      }
      var mm = midiOf(n.f0_hz);
      if (mm == null) return;
      var x = L.xOf((n.start || 0) + (n.duration || 0) / 2);
      var y = L.yOf(mm);
      g.fillStyle = THEME.noteGold;
      var r = 2.6 * k;
      g.beginPath();
      if (g.ellipse) g.ellipse(x, y, r * 1.5, r, 0, 0, Math.PI * 2);
      else g.arc(x, y, r, 0, Math.PI * 2);
      g.fill();
    });

    /* راهنمای بالاترین/پایین‌ترین نت */
    var rg = spec.range || {};
    drawRangeGuide(g, spec, L, rg.highest, 'HIGH', THEME.high);
    drawRangeGuide(g, spec, L, rg.lowest, 'LOW', THEME.low);

    /* خط پخش */
    var xNow = L.xOf(endT);
    g.strokeStyle = THEME.playheadGlow;
    g.lineWidth = 4.2 * k;
    g.beginPath(); g.moveTo(xNow, 4); g.lineTo(xNow, H - 4); g.stroke();
    g.strokeStyle = THEME.playhead;
    g.lineWidth = 1.6 * k;
    g.beginPath(); g.moveTo(xNow, 4); g.lineTo(xNow, H - 4); g.stroke();

    /* نقطهٔ درخشان روی نت جاری */
    var mNow = currentMidiOf(spec, endT);
    if (mNow != null && isFinite(mNow)) {
      var yNow = L.yOf(mNow);
      var R = 25 * k;
      var glow = g.createRadialGradient(xNow, yNow, 0, xNow, yNow, R);
      glow.addColorStop(0, THEME.dotGlowIn);
      glow.addColorStop(0.35, THEME.dotGlowMid);
      glow.addColorStop(1, THEME.dotGlowOut);
      g.fillStyle = glow;
      g.beginPath(); g.arc(xNow, yNow, R, 0, Math.PI * 2); g.fill();
      g.fillStyle = THEME.dotCore;
      g.beginPath(); g.arc(xNow, yNow, 5.6 * k, 0, Math.PI * 2); g.fill();
    }

    /* ══ برچسبِ «نُتِ همین لحظه» ═══════════════════════════════════════
       یک کپسولِ کوچک بالا-چپ: نامِ سل‌فژ (درشت) + درجه + فرکانس.
       همان چیزی که در ویدیو هم دیده می‌شود (یک رندرر برای هر سه خروجی). */
    var liveMidi = (mNow == null || !isFinite(mNow)) ? null : mNow;
    var lowHz = (spec.range && spec.range.lowest) ? spec.range.lowest.f0_hz : null;
    var lowMidi = (lowHz > 0) ? 69 + 12 * Math.log2(lowHz / 440) : null;
    var degCur = (liveMidi != null && lowMidi != null) ? degreeFromLow(liveMidi, lowMidi) : null;
    var nameTxt = (liveMidi == null) ? 'سکوت' : solfegeOf(liveMidi, true);
    var degTxt = (liveMidi != null && degCur != null) ? 'درجهٔ ' + faNum(degCur) : '';
    var hzTxt = (liveMidi != null) ? hzOf(liveMidi).toFixed(1) + ' Hz' : '';
    drawNowPill(g, k, nameTxt, degTxt, hzTxt, liveMidi == null);

    g.font = '500 ' + Math.round(11 * k) + 'px ' + FONT;
    g.fillStyle = THEME.faint;
    g.textAlign = 'right';
    g.fillText(t.toFixed(1) + ' / ' + (spec.duration || 0).toFixed(1) + 's',
      W - 10 * k, 24 * k);

    if (rg.highest && rg.lowest) {
      var span = (rg.span_semitones != null) ? spanInDegrees(rg.span_semitones) : null;
      var hiM = rg.highest.f0_hz > 0 ? 69 + 12 * Math.log2(rg.highest.f0_hz / 440) : null;
      var loM = rg.lowest.f0_hz > 0 ? 69 + 12 * Math.log2(rg.lowest.f0_hz / 440) : null;
      g.fillStyle = THEME.hint;
      g.fillText((hiM != null ? solfegeOf(hiM, true) : '') + ' ↕ ' +
        (loM != null ? solfegeOf(loM, true) : '') +
        (span ? '  ·  ' + span.text : ''), W - 10 * k, H - 9 * k);
    }
    g.textAlign = 'left';
  }

  /* کپسولِ نُتِ لحظه — گوشهٔ بالا-چپ، خوانا روی هر پس‌زمینه‌ای */
  /* کپسولِ نُتِ لحظه: نام (بزرگ) + «درجهٔ n» + فرکانس، هر کدام یک قطعهٔ
     تک‌جهته تا ترتیبِ راست‌به‌چپ و عددهای لاتین قاطی نشوند. */
  function drawNowPill(g, k, nameTxt, degTxt, hzTxt, muted) {
    var padX = 11 * k, padY = 7 * k;
    var x = 9 * k, y = 8 * k;
    var nameFont = '800 ' + Math.round(20 * k) + 'px ' + FONT;
    var subFont = '600 ' + Math.round(10.5 * k) + 'px ' + FONT;
    var wName = 0, wDeg = 0, wHz = 0;
    g.font = nameFont; if (g.measureText) wName = g.measureText(nameTxt).width;
    g.font = subFont;
    if (g.measureText) { wDeg = g.measureText(degTxt || '').width; wHz = g.measureText(hzTxt || '').width; }
    var wSub = wDeg + (degTxt && hzTxt ? 8 * k : 0) + wHz;
    var innerW = Math.max(wName, wSub);
    var boxW = innerW + padX * 2;
    var boxH = 20 * k + 6 * k + 12 * k + padY * 2;
    roundRect(g, x, y, boxW, boxH, 12 * k);
    g.fillStyle = muted ? 'rgba(8,16,32,.62)' : 'rgba(8,16,32,.84)';
    g.fill();
    g.strokeStyle = muted ? 'rgba(150,170,200,.30)' : 'rgba(255,222,140,.38)';
    g.lineWidth = 1;
    g.stroke();

    g.textAlign = 'left';
    g.textBaseline = 'top';
    g.fillStyle = muted ? 'rgba(170,190,220,.62)' : THEME.label;
    g.font = nameFont;
    g.fillText(nameTxt, x + padX, y + padY);

    var subY = y + padY + 23 * k;
    g.font = subFont;
    if (degTxt) {
      g.fillStyle = 'rgba(255,226,160,.95)';
      g.fillText(degTxt, x + padX, subY);
    }
    if (hzTxt) {
      g.fillStyle = 'rgba(170,190,220,.85)';
      g.textAlign = 'right';
      g.fillText(hzTxt, x + boxW - padX, subY);
    }
    g.textAlign = 'left';
    g.textBaseline = 'alphabetic';
  }

  /* خطِ چینِ «بالاترین/پایین‌ترین نت» — تمامِ عرضِ نمودار، با برچسبِ سل‌فژ
     و شمارهٔ درجه. (قبلاً فقط از شروعِ همان نت به راست کشیده می‌شد و کاربر
     به‌سختی پیدایش می‌کرد.) */
  function drawRangeGuide(g, spec, L, note, tag, colorPrefix) {
    if (!note || !note.f0_hz) return;
    var mm = midiOf(note.f0_hz);
    if (mm == null) return;
    var k = L.k;
    var y = L.yOf(mm);
    if (!isFinite(y)) return;
    var yTop = L.MT + 6 * k, yBot = spec.H - L.MB - 6 * k;
    var edge = '';
    if (y < yTop) { y = yTop; edge = ' ▲'; }
    if (y > yBot) { y = yBot; edge = ' ▼'; }
    var lowHz = (spec.range && spec.range.lowest) ? spec.range.lowest.f0_hz : null;
    var lowMidi = (lowHz > 0) ? 69 + 12 * Math.log2(lowHz / 440) : null;
    var deg = (lowMidi != null) ? degreeFromLow(mm, lowMidi) : null;

    g.save();
    g.setLineDash([7 * k, 6 * k]);
    g.strokeStyle = colorPrefix + '.55)';
    g.lineWidth = 1.4 * k;
    g.beginPath();
    g.moveTo(6 * k, y);
    g.lineTo(spec.W - 6 * k, y);
    g.stroke();
    g.restore();

    var txt = (tag === 'HIGH' ? 'بالاترین ' : 'پایین‌ترین ') + solfegeOf(mm, true) +
      (deg != null ? ' · درجهٔ ' + faNum(deg) : '') + edge;
    g.font = '700 ' + Math.round(10 * k) + 'px ' + FONT;
    g.font = '700 ' + Math.round(10 * k) + 'px ' + FONT;
    var tw = g.measureText ? g.measureText(txt).width : 120 * k;
    var bx = tag === 'HIGH' ? (spec.W - tw - 18 * k) : (L.ML + 2 * k);
    /* برچسب همیشه سمتِ «داخلِ کادر»ِ خط می‌نشیند تا با خط و لبه تلاقی نکند */
    var by;
    if (tag === 'HIGH') by = (y + 13 * k < spec.H - L.MB) ? y + 13 * k : y - 6 * k;
    else by = (y - 8 * k > L.MT) ? y - 6 * k : y + 14 * k;
    g.fillStyle = 'rgba(8,16,32,.86)';
    roundRect(g, bx - 5 * k, by - 10 * k, tw + 10 * k, 14 * k, 6 * k);
    g.fill();
    g.strokeStyle = colorPrefix + '.35)';
    g.lineWidth = 1;
    g.stroke();
    g.fillStyle = colorPrefix + '.98)';
    g.fillText(txt, bx, by);
  }

  function hzOf(midi) { return 440 * Math.pow(2, (midi - 69) / 12); }

  /* ارتفاع لحظهٔ جاری با درون‌یابی؛ در سکوت null (نقطه پنهان می‌شود) */
  function currentMidiOf(spec, t) {
    var times = spec.times, mids = spec.mids;
    if (!times || !times.length) return null;
    var lo = 0, hi = times.length - 1, idx = 0;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (times[mid] <= t) { idx = mid; lo = mid + 1; } else hi = mid - 1;
    }
    var m0 = mids[idx];
    if (idx >= times.length - 1) return (m0 == null ? null : m0);
    var m1 = mids[idx + 1];
    if (m0 == null && m1 == null) return null;
    if (m0 == null) return m1;
    if (m1 == null) return m0;
    var t0 = times[idx], t1 = times[idx + 1];
    var f = t1 > t0 ? (t - t0) / (t1 - t0) : 0;
    return m0 + (m1 - m0) * clamp(f, 0, 1);
  }

  /* ── ساخت هندسه + رسم کامل یک فریم ───────────────────────────────────── */
  function prepare(spec) {
    var L = layout(spec.W, spec.H);
    var view = spec.view || { t0: 0, t1: Math.max(spec.duration || 1, 0.001) };
    /* برای رسم، پرش‌های کاذب پاک می‌شوند (دادهٔ گزارش دست‌نخورده می‌ماند) */
    var drawn = cleanedFor(spec.mids || []);
    /* نت‌ها هم پاک می‌شوند: نقطه/نوارِ پرت روی نمودار کشیده نشود */
    var drawnNotes = cleanNotes(spec.notes, spec.times, drawn);
    spec = Object.assign({}, spec, { notes: drawnNotes });
    L.mids = drawn;
    var lim = pitchLimits(drawn, view, spec.times, spec.tightness, spec.t, drawnNotes);
    L.zoomEff = lim.zoomEff || 1;
    L.loLim = lim.loLim;
    L.hiLim = lim.hiLim;
    /* در نمای کامل، گسترهٔ دید باید بالاترین و پایین‌ترین نت را هم بگیرد؛
       وگرنه خطِ چینِ آن‌ها بیرونِ کادر می‌افتد و «مشخص‌شده» به‌حساب نمی‌آید. */
    var tight = (spec.tightness && spec.tightness > 0) ? spec.tightness : 1;
    if (tight <= 1.0001 && spec.range) {
      var ends = [spec.range.highest, spec.range.lowest];
      for (var ie = 0; ie < ends.length; ie++) {
        var e = ends[ie];
        if (!e || !(e.f0_hz > 0)) continue;
        var em = midiOf(e.f0_hz);
        if (em == null || !isFinite(em)) continue;
        if (em > L.hiLim) L.hiLim = em + 0.6;
        if (em < L.loLim) L.loLim = em - 0.6;
      }
    }
    L.view = view;
    var usableW = spec.W - L.ML - L.MR;
    var usableH = spec.H - L.MT - L.MB;
    var span = Math.max(L.hiLim - L.loLim, 1e-6);
    var spanT = Math.max(view.t1 - view.t0, 1e-6);
    L.xOf = function (tt) { return L.ML + ((tt - view.t0) / spanT) * usableW; };
    L.yOf = function (mm) { return L.MT + (1 - (mm - L.loLim) / span) * usableH; };
    L.segs = buildSegments(spec.times, drawn, view, L.xOf, L.yOf);
    L.spec = spec;          /* spec با نت‌های پاک‌شده؛ لایه‌های رسم از این استفاده می‌کنند */
    return L;
  }

  function draw(g, spec) {
    var L = prepare(spec);
    var sp = L.spec || spec;     /* نت‌های پاک‌شده، نه فهرست خام */
    drawBase(g, sp, L, L.segs);
    drawDynamic(g, sp, L, L.segs);
    return L;
  }

  global.LocalTraceStyle = {
    THEME: THEME,
    FONT: FONT,
    layout: layout,
    prepare: prepare,
    draw: draw,
    drawBase: drawBase,
    drawDynamic: drawDynamic,
    pitchLimits: pitchLimits,
    buildSegments: buildSegments,
    barWidth: barWidth,
    BAR_MIN_SEC: BAR_MIN_SEC,
    cleanMids: cleanMids,
    cleanedFor: cleanedFor,
    cleanNotes: cleanNotes,
    /* واحدهای موسیقی مشترک (سل‌فژ و درجه) */
    solfegeOf: solfegeOf,
    degreesOfSemitones: degreesOfSemitones,
    spanInDegrees: spanInDegrees,
    degreeFromLow: degreeFromLow,
    faNum: faNum,
    DEG_PER_OCTAVE: DEG_PER_OCTAVE,
    setClean: function (on) { setClean(on); CLEAN_CACHE.src = null; CLEAN_CACHE.n = -1; },
    SPIKE_SEMI: SPIKE_SEMI,
    currentMidi: currentMidiOf,
    midiOf: midiOf,
    nameOf: nameOf,
    hzOf: hzOf
  };
})(typeof window !== 'undefined' ? window : globalThis);
