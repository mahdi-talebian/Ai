# -*- coding: utf-8 -*-
"""
موتور امتیازدهی تمرین تلاوت
=============================
خروجی تحلیل مقامی (گزارش quran_maqam_analyzer) را به یک «کارت امتیاز»
بازی‌وار تبدیل می‌کند:

  ۱) انطباق مقامی   — چند درصد از زمانِ خواندن روی درجات خود مقام بوده؟
  ۲) پایداری نت‌ها  — لرزش/نا‌ثباتی هر نت (stability_cents از Praat)
  ۳) نشستن روی قرار — فرازها روی تونیک یا درجهٔ ۵ (نوا) تمام شده‌اند؟
  ۴) ثبات مرکز صدا  — تونیکِ تشخیصی فرازها چقدر به هم نزدیک‌اند؟
                       (اگر قاری وسط خواندن «بکِشد» بالا/پایین، این نمره می‌آید پایین)

خروجی: امتیاز کل ۰-۱۰۰ + چهار زیرامتیاز + رتبه (A تا E) + ستاره + نشان‌ها
+ فهرست ضعیف‌ترین/بهترین فرازها + توصیه‌های فارسی برای هر نقطهٔ ضعف.
"""
import numpy as np


def _clamp(v, lo=0.0, hi=100.0):
    return float(max(lo, min(hi, v)))


def score_recitation(report: dict) -> dict:
    """
    کارت امتیاز تمرین را از گزارش تحلیل می‌سازد. هرگز exception نمی‌دهد —
    در بدترین حالت زیرامتیاز None و امتیاز کل بر مبنای بقیه محاسبه می‌شود.
    """
    phrases = report.get("phrase_breakdown") or []
    notes = report.get("notes") or []

    # ------------------------------------------------------------------
    # ۱) انطباق مقامی — وزن‌دهی با مدت هر نت (فیلد in_maqam هر نت)
    # ------------------------------------------------------------------
    adherence = None
    dur_total = 0.0
    dur_in = 0.0
    for ph in phrases:
        for n in ph.get("notes_solfege", []):
            d = float(n.get("duration") or 0.0)
            if d <= 0:
                continue
            dur_total += d
            if n.get("in_maqam"):
                dur_in += d
    if dur_total > 0:
        adherence = _clamp(100.0 * dur_in / dur_total)

    # ------------------------------------------------------------------
    # ۲) پایداری نت‌ها — stability_cents = پراکندگی پرده درون هر نت
    #    (۰ سنت = سنگ؛ ۵۰ سنت یا بیشتر = خیلی ناپایدار)
    # ------------------------------------------------------------------
    stability = None
    w_sum, s_sum = 0.0, 0.0
    for n in notes:
        d = float(n.get("duration") or 0.0)
        st = n.get("stability_cents")
        if d <= 0 or st is None:
            continue
        w_sum += d
        s_sum += d * _clamp(100.0 * (1.0 - float(st) / 50.0))
    if w_sum > 0:
        stability = _clamp(s_sum / w_sum)

    # ------------------------------------------------------------------
    # ۳) نشستن فرازها روی قرار (تونیک = درجهٔ ۱، یا غماز = درجهٔ ۵)
    # ------------------------------------------------------------------
    resting = None
    n_rest_ok = 0
    n_rest_total = 0
    for ph in phrases:
        rd = ph.get("resting_degree")
        if not rd:
            continue
        n_rest_total += 1
        if rd.get("degree_index") in (1, 5):
            n_rest_ok += 1
    if n_rest_total:
        resting = _clamp(100.0 * n_rest_ok / n_rest_total)

    # ------------------------------------------------------------------
    # ۴) ثبات مرکز صدا — پخش تونیکِ فرازها بر حسب سنت
    # ------------------------------------------------------------------
    tonic_steadiness = None
    tonics = [ph["tonic_freq_hz"] for ph in phrases
              if ph.get("tonic_freq_hz")]
    if len(tonics) >= 2:
        spread_cents = 1200.0 * np.log2(max(tonics) / min(tonics))
        # ۰ سنت = ۱۰۰؛ هر ۳۰۰ سنت پخش، نمره را به ~۳۷٪ می‌رساند
        tonic_steadiness = _clamp(100.0 * float(np.exp(-spread_cents / 300.0)))
    elif len(tonics) == 1 or (not tonics and (report.get("maqam_candidates") or [{}])[0].get("tonic_freq_hz")):
        # رفع محدودیت فراز تک‌جمله‌ای: «چقدر روی تونیک می‌نشینی» =
        # فاصلهٔ قرار (میانهٔ نت‌های پایانی) تا تونیک — صفر سنت = ۱۰۰
        tonic_hz = tonics[0] if tonics else (report.get("maqam_candidates") or [{}])[0].get("tonic_freq_hz")
        voiced = sorted([n for n in notes if n.get("f0_hz") and n.get("start") is not None],
                        key=lambda n: n["start"])
        if tonic_hz and voiced:
            tail = voiced[-max(1, len(voiced) // 7):]   # ~۱۵٪ انتهایی
            tail_f0 = float(np.median([n["f0_hz"] for n in tail]))
            dev = abs(1200.0 * np.log2(tail_f0 / tonic_hz)) % 1200.0
            dev = min(dev, 1200.0 - dev)
            tonic_steadiness = _clamp(100.0 * float(np.exp(-dev / 150.0)))

    # ------------------------------------------------------------------
    # ۵) زینت (ویبرو) — پوشش + نرخ مطلوب ۵–۷Hz و دامنهٔ ۳۰–۶۰¢
    # ------------------------------------------------------------------
    vibrato = None
    try:
        v_notes = [n for n in notes if n.get("vibrato")]
        v_total = len([n for n in notes if n.get("duration")])
        if v_total >= 4:
            coverage = len(v_notes) / v_total
            rates = [float(n["vibrato"]["rate_hz"]) for n in v_notes
                     if (n.get("vibrato") or {}).get("rate_hz")]
            extents = [float(n["vibrato"]["extent_cents"]) for n in v_notes
                       if (n.get("vibrato") or {}).get("extent_cents")]
            def _band(vals, lo, hi):
                if not vals:
                    return 0.5
                ok = sum(1 for v in vals if lo <= v <= hi)
                # نزدیکی نرم به بازه (هر ۲۰¢/۰٫۵Hz بیرون = ۱۰٪ جریمه)
                soft = sum(
                    max(0.0, 1.0 - (min(abs(v - lo), abs(v - hi)) / 20.0) * 0.1)
                    for v in vals) / len(vals)
                return 0.5 * (ok / len(vals)) + 0.5 * soft
            rate_score = _band(rates, 5.0, 7.0)
            extent_score = _band(extents, 30.0, 60.0)
            vibrato = _clamp(100.0 * (0.4 * min(1.0, coverage / 0.45)
                                      + 0.3 * rate_score + 0.3 * extent_score))
    except Exception:
        vibrato = None

    # ------------------------------------------------------------------
    # امتیاز کل — وزن‌ها: انطباق مهم‌ترین است (ویبرو به‌عنوان جزء پنجم)
    # ------------------------------------------------------------------
    parts = []
    if adherence is not None:
        parts.append((adherence, 0.36))
    if stability is not None:
        parts.append((stability, 0.22))
    if resting is not None:
        parts.append((resting, 0.18))
    if tonic_steadiness is not None:
        parts.append((tonic_steadiness, 0.13))
    if vibrato is not None:
        parts.append((vibrato, 0.11))
    total = round(sum(v * w for v, w in parts) / sum(w for _, w in parts), 1) if parts else None

    grade, stars = _grade_of(total)

    # ------------------------------------------------------------------
    # فرازهای برتر و ضعیف (برای هایلایت در کارت)
    # ------------------------------------------------------------------
    phrase_scores = []
    for i, ph in enumerate(phrases, 1):
        if ph.get("in_maqam_pct") is None:
            continue
        phrase_scores.append({
            "index": i,
            "start": ph.get("start"),
            "end": ph.get("end"),
            "maqam": ph.get("maqam"),
            "in_maqam_pct": ph.get("in_maqam_pct"),
            "resting_fa": (ph.get("resting_degree") or {}).get("absolute_fa"),
            "score": round(0.7 * (ph.get("in_maqam_pct") or 0) +
                           0.3 * (100.0 if ph.get("resting_degree", {}).get("degree_index") in (1, 5) else 30.0), 1),
        })
    phrase_scores.sort(key=lambda p: p["score"])
    weakest = phrase_scores[:3]
    best = list(reversed(phrase_scores[-3:])) if len(phrase_scores) >= 2 else []

    # ------------------------------------------------------------------
    # توصیه‌های فارسی برای نقطه‌های ضعف
    # ------------------------------------------------------------------
    tips = []
    if adherence is not None and adherence < 85:
        tips.append("چند جا از درجات مقام بیرون زدی — روی درجه‌های ۲ و ۶ (لبه‌های جنس‌ها) دقت کن؛ "
                    "همین‌ها بیشترین جا را برای لیز خوردن دارند.")
    if stability is not None and stability < 80:
        tips.append("نت‌ها کمی لرزش/نا‌ثباتی دارند — نت‌های بلند را با نفس آرام نگه دار و اجازه بده صدا جا بیفتد.")
    if resting is not None and resting < 70:
        tips.append("چند فراز روی درجهٔ نامنشسته تمام شده — سعی کن هر فراز را روی تونیک یا «نوا» بنشانی؛ "
                    "این همان چیزی است که به خواندن «قرار» می‌دهد.")
    if tonic_steadiness is not None and tonic_steadiness < 80:
        tips.append("مرکز صدات در میانهٔ تلاوت جابه‌جا شده — اول تونیک را محکم در ذهن بساز و اولِ هر فراز یک لحظه روی آن توقف کن.")
    if not tips:
        tips.append("عالی بود! برای چالش بعدی، طول فرازها را بیشتر کن و سیر ملودیک را تا غماز ببر.")

    # ------------------------------------------------------------------
    # نشان‌ها (badges)
    # ------------------------------------------------------------------
    badges = []
    if resting is not None and n_rest_total >= 3 and resting >= 99.9:
        badges.append({"id": "golden_finalis", "fa": "قرار طلایی", "icon": "🏅",
                       "desc": "همهٔ فرازها روی تونیک یا نوا نشستند"})
    if adherence is not None and adherence >= 90:
        badges.append({"id": "maqam_master", "fa": "مقام‌شناس", "icon": "🎯",
                       "desc": "بیش از ۹۰٪ زمان خواندن روی درجات مقام"})
    if stability is not None and stability >= 85:
        badges.append({"id": "steady_voice", "fa": "صدای باثبات", "icon": "🪨",
                       "desc": "پایداری نت‌ها بالای ۸۵٪"})
    if tonic_steadiness is not None and tonic_steadiness >= 90:
        badges.append({"id": "steady_center", "fa": "مرکز ثابت", "icon": "🧲",
                       "desc": "تونیک در طول تلاوت جابه‌جا نشد"})
    if total is not None and total >= 95:
        badges.append({"id": "flawless", "fa": "بی‌نقص", "icon": "💎",
                       "desc": "امتیاز کل بالای ۹۵"})

    return {
        "total": total,
        "grade": grade,
        "stars": stars,
        "subscores": {
            "maqam_adherence": _round1(adherence),
            "note_stability": _round1(stability),
            "phrase_resting": _round1(resting),
            "tonic_steadiness": _round1(tonic_steadiness),
            "vibrato": _round1(vibrato),
        },
        "phrase_resting_counts": {"ok": n_rest_ok, "total": n_rest_total},
        "weakest_phrases": weakest,
        "best_phrases": best,
        "badges": badges,
        "tips": tips,
        "degree_accuracy": _degree_accuracy(phrases),
        "tonic_advisor": _tonic_advisor(report, notes, tonics),
        "phrase_feedback": [
            {
                "index": i,
                "start": ph.get("start"),
                "end": ph.get("end"),
                "maqam": ph.get("maqam"),
                "in_maqam_pct": ph.get("in_maqam_pct"),
                "resting_fa": (ph.get("resting_degree") or {}).get("absolute_fa"),
                "score": round(0.7 * (ph.get("in_maqam_pct") or 0) +
                               0.3 * (100.0 if (ph.get("resting_degree") or {}).get("degree_index") in (1, 5) else 30.0), 1),
            }
            for i, ph in enumerate(phrases, 1)
            if ph.get("in_maqam_pct") is not None
        ],
    }


def _round1(v):
    return round(v, 1) if v is not None else None


def _grade_of(total):
    if total is None:
        return None, None
    if total >= 90:
        return "A", 5
    if total >= 80:
        return "B", 4
    if total >= 70:
        return "C", 3
    if total >= 60:
        return "D", 2
    return "E", 1

def _degree_accuracy(phrases):
    """
    🎯 دادهٔ رادار دقت درجات: برای هر درجهٔ شرقی (دوگاه، سیکاه، ...) که کاربر
    خوانده: میانگین |انحراف سنت| وزن‌دار با مدت + درصد نشستن روی درجه
    (تلورانس ۴۰ سنت) + مجموع مدت.
    """
    agg = {}
    for ph in phrases or []:
        for n in (ph.get("notes_solfege") or []):
            fa = n.get("absolute_degree_fa")
            idx = n.get("degree_index")
            off = n.get("cents_off_degree")
            d = float(n.get("duration") or 0.0)
            if not fa or idx is None or off is None or d <= 0:
                continue
            a = agg.setdefault(fa, {"degree_index": idx, "w": 0.0, "dev": 0.0, "ok": 0.0})
            a["w"] += d
            a["dev"] += d * abs(float(off))
            if abs(float(off)) <= 40.0:
                a["ok"] += d
    out = []
    for fa, a in agg.items():
        if a["w"] <= 0:
            continue
        out.append({
            "degree_fa": fa,
            "degree_index": a["degree_index"],
            "mean_abs_dev_cents": round(a["dev"] / a["w"], 1),
            "in_tune_pct": _round1(_clamp(100.0 * a["ok"] / a["w"])),
            "dur_sec": round(a["w"], 2),
        })
    out.sort(key=lambda x: (x["degree_index"], -x["dur_sec"]))
    return out


def _tonic_advisor(report, notes, tonics):
    """
    🎚 مشاور تونیک: تشخیص فشار رجیستر از روی توزیع نت‌ها.
    اگر نت‌ها به سقف رنج فشرده شده‌اند (اوج > ~۱۰۵۰¢ بالای تونیک و ازدحام
    زیر سقف) → تونیک پایین‌تر؛ اگر به کف کوبیده شده‌اند → بالاتر؛ وگرنه
    تونیک فعلی با رنج صدای کاربر هم‌خوان است.
    """
    try:
        freqs = sorted(float(n["f0_hz"]) for n in (notes or [])
                       if n.get("f0_hz") and n["f0_hz"] > 0)
        if len(freqs) < 4:
            return None
        current = (tonics[0] if tonics else None) or \
            ((report.get("maqam_candidates") or [{}])[0].get("tonic_freq_hz"))
        if not current:
            return None

        p05 = float(np.percentile(freqs, 5))
        p95 = float(np.percentile(freqs, 95))
        top_cents = 1200.0 * np.log2(p95 / current)
        low_cents = 1200.0 * np.log2(p05 / current)
        n = len(freqs)
        top_crowd = sum(1 for f in freqs if f >= p95 / (2 ** (150.0 / 1200.0))) / n
        low_crowd = sum(1 for f in freqs if f <= p05 * (2 ** (150.0 / 1200.0))) / n

        # فقط فشار واقعی: سقف/کفِ بالا + ازدحام چندنت زیر آن + فراز به‌قدر کافی بلند
        shift_cents = 0
        if len(freqs) >= 8 and top_cents > 1300.0 and top_crowd >= 0.20:
            shift_cents = -min(200.0, 100.0 * round((top_cents - 1250.0) / 100.0))
        elif len(freqs) >= 8 and low_cents < -500.0 and low_crowd >= 0.18:
            shift_cents = min(200.0, 100.0 * round((-low_cents - 500.0) / 100.0))

        suggested = current * (2 ** (shift_cents / 1200.0))
        import quran_maqam_analyzer as _qma
        cur_name, _ = _qma.freq_to_note_and_deviation(current)
        sug_name, _ = _qma.freq_to_note_and_deviation(suggested)
        semi = abs(round(shift_cents / 100.0, 1))
        if abs(shift_cents) < 50:
            verdict = "ok"
            hint = (f"تونیک فعلی ({cur_name}) با رنج این فراز هم‌خوان است ✓ "
                    f"— اوج تا {round(top_cents / 100.0, 1)} پرده بالای تونیک رفته")
        elif shift_cents < 0:
            verdict = "lower"
            hint = (f"نت‌های اوجت به سقف رنج فشرده شده‌اند — تونیک را ~{semi} پرده پایین بیاور "
                    f"(≈ {sug_name} / {suggested:.0f}Hz) تا اوج‌ها راحت‌تر شود")
        else:
            verdict = "higher"
            hint = (f"نت‌های بمت به کف رنج کوبیده شده‌اند — تونیک را ~{semi} پرده بالا بیاور "
                    f"(≈ {sug_name} / {suggested:.0f}Hz) تا بم‌ها واضح بماند")
        return {
            "current_tonic_hz": round(float(current), 1),
            "current_note": cur_name,
            "suggested_tonic_hz": round(float(suggested), 1),
            "suggested_note": sug_name,
            "delta_cents": round(shift_cents),
            "range_low_hz": round(p05, 1),
            "range_high_hz": round(p95, 1),
            "verdict": verdict,
            "hint_fa": hint,
        }
    except Exception:
        return None
