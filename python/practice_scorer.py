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

    # ------------------------------------------------------------------
    # امتیاز کل — وزن‌ها: انطباق مهم‌ترین است
    # ------------------------------------------------------------------
    parts = []
    if adherence is not None:
        parts.append((adherence, 0.40))
    if stability is not None:
        parts.append((stability, 0.25))
    if resting is not None:
        parts.append((resting, 0.20))
    if tonic_steadiness is not None:
        parts.append((tonic_steadiness, 0.15))
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
        },
        "phrase_resting_counts": {"ok": n_rest_ok, "total": n_rest_total},
        "weakest_phrases": weakest,
        "best_phrases": best,
        "badges": badges,
        "tips": tips,
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
