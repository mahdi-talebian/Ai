#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
مقایسه دقیق دو تلاوت/فراز (Reference vs Performance)
==========================================================
این ابزار یک فراز مرجع (مثلاً تلاوت یک قاری معروف) را با یک فراز کاربر
(یا هر فایل صوتی دیگر) مقایسه می‌کند و میزان شباهت را از سه بعد مجزا
گزارش می‌دهد:

  1) شباهت ملودیک (Pitch/Melodic Similarity) — با الگوریتم DTW روی
     دنباله نت‌ها، به‌صورت «نسبی» (مستقل از زیر-و-بمی مطلق صدای دو نفر)
  2) شباهت ریتمیک (Rhythm Similarity) — الگوی طول/کشش نسبی نت‌ها،
     مستقل از سرعت مطلق قرائت
  3) شباهت بلندی و دینامیک صدا (Loudness/Dynamics Similarity)

همچنین یک نمودار هم‌پوشانی (Overlay) دو ملوگراف رسم می‌کند تا تفاوت‌ها
به‌صورت بصری هم قابل مشاهده باشند.

نیازمندی‌ها:
    pip install praat-parselmouth librosa numpy scipy matplotlib soundfile
    pip install arabic-reshaper python-bidi   # نمایش صحیح فارسی در نمودار

استفاده:
    python3 compare_recitations.py reference.mp3 performance.mp3
    python3 compare_recitations.py reference.mp3 performance.mp3 --output compare.json
"""

import argparse
import json
import os
import sys
import warnings
from datetime import datetime

import numpy as np

warnings.filterwarnings("ignore")

from pitch_engine import (
    extract_pitch_contour, clean_pitch_contour, smooth_pitch_contour, segment_notes,
    melodic_similarity, rhythm_similarity, loudness_similarity,
    freq_to_note_info,
)

try:
    import parselmouth
except ImportError:
    print("خطا: praat-parselmouth نصب نیست. اجرا کنید: pip install praat-parselmouth")
    sys.exit(1)


# ============================================================================
# استخراج پروفایل کامل یک فایل صوتی (برای مقایسه)
# ============================================================================

def analyze_single_file(path, label="فایل"):
    print(f"در حال تحلیل {label}: {path} ...")
    times, freqs, snd = extract_pitch_contour(path)
    freqs = clean_pitch_contour(freqs)
    freqs = smooth_pitch_contour(freqs, median_window=5)
    notes = segment_notes(times, freqs)

    intensity = snd.to_intensity()
    values = intensity.values[0]
    values = values[~np.isnan(values)]
    loudness = {
        "mean_db": round(float(np.mean(values)), 1) if len(values) else None,
        "max_db": round(float(np.max(values)), 1) if len(values) else None,
    }

    duration = float(times[-1]) if len(times) else 0

    return {
        "label": label,
        "path": path,
        "times": times,
        "freqs": freqs,
        "notes": notes,
        "loudness": loudness,
        "duration": duration,
    }


# ============================================================================
# مقایسه کامل
# ============================================================================

def compare_files(ref_path, perf_path, align_start=True):
    ref = analyze_single_file(ref_path, "فراز مرجع")
    perf = analyze_single_file(perf_path, "فراز کاربر")

    print("در حال محاسبه شباهت ملودیک (DTW)...")
    mel_sim = melodic_similarity(ref["notes"], perf["notes"])

    print("در حال محاسبه شباهت ریتمیک (DTW)...")
    rhy_sim = rhythm_similarity(ref["notes"], perf["notes"])

    print("در حال محاسبه شباهت بلندی صدا...")
    loud_sim = loudness_similarity(ref["loudness"], perf["loudness"])

    # امتیاز کلی وزن‌دار: ملودی مهم‌ترین معیار در تلاوت است
    weights = {"melodic": 0.55, "rhythm": 0.30, "loudness": 0.15}
    components = []
    if mel_sim["similarity_pct"] is not None:
        components.append((mel_sim["similarity_pct"], weights["melodic"]))
    if rhy_sim["similarity_pct"] is not None:
        components.append((rhy_sim["similarity_pct"], weights["rhythm"]))
    if loud_sim["similarity_pct"] is not None:
        components.append((loud_sim["similarity_pct"], weights["loudness"]))

    if components:
        total_weight = sum(w for _, w in components)
        overall = sum(s * w for s, w in components) / total_weight
    else:
        overall = 0.0

    # تحلیل نت‌به‌نت تفاوت (کجا بیشترین انحراف بوده)
    note_diffs = _per_note_deviation(ref["notes"], perf["notes"], mel_sim.get("path", []))

    verdict = _generate_verdict(overall, mel_sim, rhy_sim)

    result = {
        "meta": {
            "reference_file": os.path.basename(ref_path),
            "performance_file": os.path.basename(perf_path),
            "analyzed_at": datetime.now().isoformat(timespec="seconds"),
        },
        "durations": {
            "reference_sec": round(ref["duration"], 2),
            "performance_sec": round(perf["duration"], 2),
        },
        "overall_similarity_pct": round(float(overall), 1),
        "verdict": verdict,
        "melodic_similarity": {k: v for k, v in mel_sim.items() if k != "path"},
        "rhythm_similarity": rhy_sim,
        "loudness_similarity": loud_sim,
        "note_level_deviations": note_diffs[:20],  # حداکثر ۲۰ نمونه برای خوانایی
        "num_notes_reference": len(ref["notes"]),
        "num_notes_performance": len(perf["notes"]),
    }

    return result, ref, perf, mel_sim


def _per_note_deviation(notes_a, notes_b, dtw_path):
    """با استفاده از مسیر DTW، نت‌های متناظر دو دنباله را پیدا کرده و اختلاف سنتی آن‌ها را برمی‌گرداند."""
    if not dtw_path or not notes_a or not notes_b:
        return []

    diffs = []
    seen_pairs = set()
    for i, j in dtw_path:
        if i >= len(notes_a) or j >= len(notes_b):
            continue
        if (i, j) in seen_pairs:
            continue
        seen_pairs.add((i, j))
        f_a = notes_a[i]["f0_hz"]
        f_b = notes_b[j]["f0_hz"]
        cents_diff = 1200 * np.log2(f_b / f_a)
        diffs.append({
            "ref_note_index": i,
            "perf_note_index": j,
            "ref_time": notes_a[i]["start"],
            "perf_time": notes_b[j]["start"],
            "ref_note": notes_a[i].get("note_name", "?"),
            "perf_note": notes_b[j].get("note_name", "?"),
            "cents_diff": round(float(cents_diff), 1),
        })

    # مرتب‌سازی بر اساس بزرگ‌ترین اختلاف (بیشترین خطا اول)
    diffs.sort(key=lambda d: abs(d["cents_diff"]), reverse=True)
    return diffs


def _generate_verdict(overall, mel_sim, rhy_sim):
    if overall >= 90:
        base = "تطابق بسیار بالا — فراز کاربر از نظر ملودیک و ریتمیک بسیار نزدیک به مرجع است."
    elif overall >= 75:
        base = "تطابق خوب — الگوی کلی حفظ شده، اما انحرافات جزئی در برخی نت‌ها وجود دارد."
    elif overall >= 55:
        base = "تطابق متوسط — شباهت کلی وجود دارد اما تفاوت‌های ملموسی در ملودی یا ریتم دیده می‌شود."
    else:
        base = "تطابق پایین — الگوی ملودیک/ریتمیک تفاوت قابل‌توجهی با مرجع دارد."

    detail = []
    if mel_sim["similarity_pct"] < 70:
        detail.append("پیشنهاد می‌شود روی دقت پرده صدا (کوک بودن نسبت به فراز مرجع) بیشتر تمرکز شود.")
    if rhy_sim["similarity_pct"] < 70:
        detail.append("ریتم و مکث‌ها (مد و وقف) نسبت به فراز مرجع تفاوت محسوسی دارد.")

    return {"summary": base, "suggestions": detail}


# ============================================================================
# رسم نمودار هم‌پوشانی (Overlay)
# ============================================================================

def _setup_persian_font():
    import matplotlib
    import matplotlib.font_manager as fm
    candidates = [
        os.path.expanduser("~/.fonts/Vazirmatn-Regular.ttf"),
        "/usr/share/fonts/truetype/vazirmatn/Vazirmatn-Regular.ttf",
    ]
    for p in candidates:
        if os.path.exists(p):
            fm.fontManager.addfont(p)
            matplotlib.rcParams["font.family"] = "Vazirmatn"
            return True
    return False


def _fa(text):
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display
        return get_display(arabic_reshaper.reshape(text))
    except ImportError:
        return text


def plot_comparison(ref, perf, result, out_path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    has_font = _setup_persian_font()
    matplotlib.rcParams["axes.unicode_minus"] = False
    T = (lambda s: _fa(s)) if has_font else (lambda s: s)

    fig, axes = plt.subplots(2, 1, figsize=(14, 10))

    # نمودار بالا: دو ملوگراف به‌صورت نسبی (سنت از میانگین خودشان) روی هم
    ax1 = axes[0]

    def to_relative_cents(times, freqs):
        voiced = freqs > 0
        if not np.any(voiced):
            return times, freqs
        mean_f0 = np.exp(np.mean(np.log(freqs[voiced])))
        cents = np.full_like(freqs, np.nan)
        cents[voiced] = 1200 * np.log2(freqs[voiced] / mean_f0)
        return times, cents

    ref_t, ref_c = to_relative_cents(ref["times"], ref["freqs"])
    perf_t, perf_c = to_relative_cents(perf["times"], perf["freqs"])

    ax1.plot(ref_t, ref_c, '.', color="#2563eb", markersize=2, label=T("فراز مرجع"))
    ax1.plot(perf_t, perf_c, '.', color="#ef4444", markersize=2, label=T("فراز کاربر"), alpha=0.7)
    ax1.set_title(T(f"مقایسه ملودیک نسبی — شباهت کلی: {result['overall_similarity_pct']}%"), fontsize=13)
    ax1.set_xlabel(T("زمان (ثانیه)"))
    ax1.set_ylabel(T("انحراف نسبی از میانگین (سنت)"))
    ax1.legend(loc="upper right")
    ax1.grid(True, alpha=0.2)

    # نمودار پایین: میله‌ای امتیازهای سه‌گانه
    ax2 = axes[1]
    categories = [T("ملودی"), T("ریتم"), T("بلندی صدا"), T("کلی")]
    values = [
        result["melodic_similarity"]["similarity_pct"] or 0,
        result["rhythm_similarity"]["similarity_pct"] or 0,
        result["loudness_similarity"]["similarity_pct"] or 0,
        result["overall_similarity_pct"],
    ]
    colors = ["#3b82f6", "#f59e0b", "#10b981", "#7c3aed"]
    bars = ax2.bar(categories, values, color=colors)
    ax2.set_ylim(0, 105)
    ax2.set_ylabel(T("درصد شباهت"))
    ax2.set_title(T("تفکیک امتیاز شباهت"), fontsize=13)
    for bar, v in zip(bars, values):
        ax2.text(bar.get_x() + bar.get_width() / 2, v + 2, f"{v:.1f}%",
                  ha="center", fontsize=11, fontweight="bold")
    ax2.grid(True, axis="y", alpha=0.2)

    plt.tight_layout()
    plt.savefig(out_path, dpi=140)
    plt.close(fig)
    print(f"نمودار مقایسه ذخیره شد: {out_path}")


# ============================================================================
# چاپ گزارش
# ============================================================================

def print_report(result):
    print("\n" + "=" * 70)
    print(f" گزارش مقایسه: {result['meta']['reference_file']}  <->  {result['meta']['performance_file']}")
    print("=" * 70)

    print(f"\n⭐ امتیاز کلی شباهت: {result['overall_similarity_pct']}%")
    print(f"   {result['verdict']['summary']}")
    for s in result["verdict"]["suggestions"]:
        print(f"   💡 {s}")

    print(f"\n📊 تفکیک امتیازها:")
    print(f"   شباهت ملودیک (Pitch/DTW): {result['melodic_similarity']['similarity_pct']}%")
    print(f"   شباهت ریتمیک:              {result['rhythm_similarity']['similarity_pct']}%")
    ls = result['loudness_similarity']['similarity_pct']
    print(f"   شباهت بلندی صدا:            {ls if ls is not None else 'نامشخص'}%")

    print(f"\n🎵 تعداد نت‌ها:")
    print(f"   مرجع: {result['num_notes_reference']}   |   کاربر: {result['num_notes_performance']}")

    if result["note_level_deviations"]:
        print(f"\n🔍 بزرگ‌ترین انحرافات نت‌به‌نت (بیشترین اختلاف اول):")
        for d in result["note_level_deviations"][:8]:
            print(f"   مرجع[{d['ref_time']:>6.2f}s] {d['ref_note']:>4}  <->  "
                  f"کاربر[{d['perf_time']:>6.2f}s] {d['perf_note']:>4}   "
                  f"اختلاف: {d['cents_diff']:+.1f}¢")

    print("\n" + "=" * 70 + "\n")


# ============================================================================
# CLI
# ============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="مقایسه دقیق ملودیک/ریتمیک دو فراز صوتی (مرجع در برابر اجرای کاربر)"
    )
    parser.add_argument("reference", help="مسیر فایل صوتی مرجع (مثلاً تلاوت قاری معروف)")
    parser.add_argument("performance", help="مسیر فایل صوتی برای مقایسه (اجرای کاربر)")
    parser.add_argument("--output", "-o", help="مسیر ذخیره گزارش JSON کامل", default=None)
    parser.add_argument("--no-plot", action="store_true", help="عدم رسم نمودار مقایسه")
    parser.add_argument("--plot-path", default=None, help="مسیر ذخیره نمودار مقایسه")

    args = parser.parse_args()

    result, ref, perf, mel_sim = compare_files(args.reference, args.performance)

    print_report(result)

    if not args.no_plot:
        plot_path = args.plot_path or "comparison_result.png"
        plot_comparison(ref, perf, result, plot_path)
        result["meta"]["visualization_file"] = plot_path

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"گزارش JSON ذخیره شد در: {args.output}")


if __name__ == "__main__":
    main()
