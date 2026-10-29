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

  /* ── ساخت نقطه‌های منحنی در فضای پیکسل (نسبت به بازهٔ دید) ───────────── */
  function buildSegments(times, mids, view, xOf, yOf) {
    var runs = [], cur = [], lastT = null, i;
    for (i = 0; i < times.length; i++) {
      var t = times[i], m = mids[i];
      var inside = (t >= view.t0 - 1e-9) && (t <= view.t1 + 1e-9);
      if (m == null || !isFinite(m) || !inside) {
        if (cur.length > 1) runs.push(cur);
        cur = []; lastT = null; continue;
      }
      if (lastT != null && t - lastT > GAP_BRIDGE_SEC) {
        if (cur.length > 1) runs.push(cur);
        cur = [];
      }
      cur.push({ x: xOf(t), y: yOf(m), t: t });
      lastT = t;
    }
    if (cur.length > 1) runs.push(cur);

    /* پر کردن شکاف‌های کوتاه با درون‌یابی خطی (نمای پیوسته) */
    var segs = [];
    for (i = 0; i < runs.length; i++) {
      var run = runs[i];
      if (!segs.length) { segs.push(run.slice()); continue; }
      var prev = segs[segs.length - 1];
      var gap = run[0].t - prev[prev.length - 1].t;
      if (gap <= GAP_BRIDGE_SEC) {
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
    return segs.filter(function (s) { return s.length > 1; });
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

    /* برچسب‌ها */
    g.textAlign = 'left';
    g.fillStyle = THEME.label;
    g.font = '800 ' + Math.round(17 * k) + 'px ' + FONT;
    var label = (mNow == null || !isFinite(mNow))
      ? '—'
      : nameOf(mNow) + '   ' + hzOf(mNow).toFixed(1) + ' Hz';
    g.fillText(label, 10 * k, 24 * k);

    g.font = '500 ' + Math.round(11 * k) + 'px ' + FONT;
    g.fillStyle = THEME.faint;
    g.textAlign = 'right';
    g.fillText(t.toFixed(1) + ' / ' + (spec.duration || 0).toFixed(1) + 's',
      W - 10 * k, 24 * k);

    if (rg.highest && rg.lowest) {
      var spanTxt = (rg.span_semitones != null ? rg.span_semitones : '');
      g.fillStyle = THEME.hint;
      g.fillText(rg.highest.note_name + ' ↕ ' + rg.lowest.note_name +
        (spanTxt !== '' ? '  ·  ' + spanTxt + ' st' : ''), W - 10 * k, H - 9 * k);
    }
    g.textAlign = 'left';
  }

  function drawRangeGuide(g, spec, L, note, tag, colorPrefix) {
    if (!note || !note.f0_hz) return;
    var mm = midiOf(note.f0_hz);
    if (mm == null) return;
    var k = L.k;
    var y = L.yOf(mm);
    var t0 = note.start || 0;
    if (spec.view && (t0 < spec.view.t0 || t0 > spec.view.t1)) return;
    var x0 = L.xOf(t0);
    g.save();
    g.setLineDash([5, 5]);
    g.strokeStyle = colorPrefix + '.40)';
    g.lineWidth = 1;
    g.beginPath(); g.moveTo(x0, y); g.lineTo(spec.W - 8, y); g.stroke();
    g.restore();
    g.fillStyle = colorPrefix + '.92)';
    g.font = '700 ' + Math.round(9.5 * k) + 'px ' + FONT;
    var lx = tag === 'HIGH' ? L.ML : L.W - 130 * k;
    g.fillText(tag + ' ' + note.note_name, lx, y - 4 * k);
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
    var lim = pitchLimits(spec.mids, view, spec.times, spec.tightness, spec.t, spec.notes);
    L.zoomEff = lim.zoomEff || 1;
    L.loLim = lim.loLim;
    L.hiLim = lim.hiLim;
    L.view = view;
    var usableW = spec.W - L.ML - L.MR;
    var usableH = spec.H - L.MT - L.MB;
    var span = Math.max(L.hiLim - L.loLim, 1e-6);
    var spanT = Math.max(view.t1 - view.t0, 1e-6);
    L.xOf = function (tt) { return L.ML + ((tt - view.t0) / spanT) * usableW; };
    L.yOf = function (mm) { return L.MT + (1 - (mm - L.loLim) / span) * usableH; };
    L.segs = buildSegments(spec.times, spec.mids, view, L.xOf, L.yOf);
    return L;
  }

  function draw(g, spec) {
    var L = prepare(spec);
    drawBase(g, spec, L, L.segs);
    drawDynamic(g, spec, L, L.segs);
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
    currentMidi: currentMidiOf,
    midiOf: midiOf,
    nameOf: nameOf,
    hzOf: hzOf
  };
})(typeof window !== 'undefined' ? window : globalThis);
