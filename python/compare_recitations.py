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
import librosa

warnings.filterwarnings("ignore")

from pitch_engine import (
    extract_pitch_contour, clean_pitch_contour, smooth_pitch_contour, segment_notes,
    melodic_similarity, rhythm_similarity, loudness_similarity,
    freq_to_note_info, extract_pitch_contour_max_accuracy, compute_loudness_streaming,
    dtw_align_cost_matrix, PLOT_LOCK, compute_waveform_and_phrases,
)

try:
    import parselmouth
except ImportError:
    print("خطا: praat-parselmouth نصب نیست. اجرا کنید: pip install praat-parselmouth")
    sys.exit(1)

try:
    # برای استفاده از همان منطق تشخیص مکث/فراز که برای تحلیل تک‌فایلی
    # (quran_maqam_analyzer.py) پیاده‌سازی شده — به‌جای بازنویسی مجدد آن،
    # اینجا فقط دوباره استفاده می‌شود تا مرزبندی فرازها در هر دو ابزار
    # همیشه یکسان و سازگار بماند.
    from quran_maqam_analyzer import detect_pauses, PHRASE_MIN_PAUSE_SEC, PHRASE_MIN_NOTES
except ImportError:
    detect_pauses = None
    PHRASE_MIN_PAUSE_SEC = 0.35
    PHRASE_MIN_NOTES = 2


# ============================================================================
# استخراج پروفایل کامل یک فایل صوتی (برای مقایسه)
# ============================================================================

def _ensure_readable_wav(path):
    """
    اگر فایل ورودی از فرمتی باشد که parselmouth/libsndfile مستقیماً
    نمی‌تواند بخواند (مثلاً OGG/Opus یا WebM که مرورگر هنگام ضبط از
    میکروفون تولید می‌کند)، آن را با librosa (که روی ffmpeg/audioread
    تکیه دارد و طیف وسیع‌تری از فرمت‌ها را می‌فهمد) به یک فایل WAV موقت
    تبدیل می‌کند. اگر فایل از قبل WAV/فرمت پشتیبانی‌شده باشد، مسیر اصلی
    بدون تغییر برگردانده می‌شود.

    این دقیقاً همان تبدیلی است که quran_maqam_analyzer.analyze_recitation
    برای آپلود تکی فایل انجام می‌دهد؛ بدون آن، مقایسهٔ دو فراز با فایل‌های
    غیر-WAV (مثل ضبط زندهٔ مرورگر با فرمت .ogg/.webm) با خطای
    «Not an audio file» از parselmouth متوقف می‌شد.

    خروجی: (مسیر_قابل‌خواندن, مسیر_موقت_یا_None)
    """
    try:
        # تست مستقیم با همان خوانندهٔ داخلی parselmouth (نه soundfile/libsndfile) —
        # چون این دو کتابخانه پشتیبانی متفاوتی از فرمت‌ها دارند: مثلاً یک فایل
        # OGG/Opus ممکن است با soundfile.info() بدون خطا خوانده شود اما
        # parselmouth.Sound() روی همان فایل با خطای «Not an audio file» شکست
        # بخورد. باید دقیقاً همان مسیری که بعداً استفاده می‌شود تست شود.
        parselmouth.Sound(path)
        return path, None
    except Exception:
        pass

    y, sr = librosa.load(path, sr=44100, mono=True)
    tmp_wav = path + "__tmp_compare.wav"
    import soundfile as sf
    sf.write(tmp_wav, y, sr)
    return tmp_wav, tmp_wav


def analyze_single_file(path, label="فایل", progress_callback=None):
    """
    یک فایل (مرجع یا اجرای کاربر) را با بالاترین دقت ممکن تحلیل می‌کند.

    از extract_pitch_contour_max_accuracy استفاده می‌شود که برای فایل‌های
    کوتاه (فرازهای معمولاً کوتاه مقایسه) بدون هیچ سربار اضافه مسیر مستقیم
    را طی می‌کند، اما برای فایل‌های مرجع/کاربر طولانی‌تر (که کاربر ممکن است
    یک تلاوت کامل را به‌عنوان مرجع بدهد)، به‌صورت خودکار پردازش پنجره‌ای
    با همان تنظیمات حداکثر دقت (very_accurate=True) به کار می‌رود — بدون
    افت کیفیت و بدون خطر اتمام حافظه.
    """
    print(f"در حال تحلیل {label}: {path} ...")

    readable_path, tmp_wav = _ensure_readable_wav(path)

    def _pp(done_sec, total_sec):
        if progress_callback:
            progress_callback(label, done_sec, total_sec)
        if total_sec > 60:
            print(f"\r   [{label}] پیشرفت: {done_sec:6.1f}s / {total_sec:6.1f}s "
                  f"({100*done_sec/max(total_sec,1e-9):5.1f}%)", end="", flush=True)

    try:
        times, freqs, sr, duration = extract_pitch_contour_max_accuracy(
            readable_path, progress_callback=_pp,
        )
        if duration > 60:
            print()

        freqs = clean_pitch_contour(freqs)
        freqs = smooth_pitch_contour(freqs, median_window=5)
        notes = segment_notes(times, freqs)

        loudness = compute_loudness_streaming(readable_path)

        # موج صوتی فشرده + فرازهای مبتنی بر دامنهٔ واقعی صدا (RMS + آستانهٔ
        # خودکار) — منبع مشترک برای رنگ‌آمیزی موج صوتی و برای تفکیک فراز در
        # مقایسهٔ فراز-به-فراز، تا در تب مقایسه هم رنگ‌ها کاملاً همخوان باشند.
        waveform = None
        amplitude_phrase_boundaries = None
        try:
            wf_result = compute_waveform_and_phrases(readable_path)
            waveform = {"peaks": wf_result["peaks"], "duration_sec": wf_result["duration_sec"]}
            amplitude_phrase_boundaries = wf_result["phrases"]
        except Exception:
            waveform = None
            amplitude_phrase_boundaries = None
    finally:
        if tmp_wav and os.path.exists(tmp_wav):
            os.remove(tmp_wav)

    return {
        "label": label,
        "path": path,
        "amplitude_phrase_boundaries": amplitude_phrase_boundaries,
        "times": times,
        "freqs": freqs,
        "notes": notes,
        "loudness": loudness,
        "duration": duration,
        "waveform": waveform,
    }



# ============================================================================
# تقسیم به فراز (Phrase) و مقایسه فراز-به-فراز با DTW
# ============================================================================

def segment_phrases_from_notes(times, freqs, notes, phrase_boundaries=None):
    """
    دنباله نت‌های یک فایل را به «فرازهای» طبیعی تقسیم می‌کند.

    اگر phrase_boundaries داده شود (خروجی compute_waveform_and_phrases که
    بر مبنای دامنهٔ واقعی صدا/RMS + آستانهٔ خودکار Otsu فراز را از مکث
    تشخیص می‌دهد)، از همان مرزها استفاده می‌شود — تا فرازهایی که در وب
    روی موج صوتی رنگی نمایش داده می‌شوند دقیقاً همان فرازهایی باشند که در
    مقایسهٔ فراز-به-فراز گزارش می‌شوند. در غیر این صورت، به روش قدیمی‌تر
    مبتنی بر مکث‌های خروجی Praat pitch-voicing برمی‌گردد (سازگاری عقب‌رو).

    خروجی: لیستی از دیکشنری‌های {index, color_index, start, end, duration, num_notes, notes}
    """
    total_duration = float(times[-1]) if len(times) else 0.0

    if phrase_boundaries:
        boundaries_pairs = [(b["start"], b["end"]) for b in phrase_boundaries]
    else:
        if detect_pauses is not None and len(times):
            pauses = detect_pauses(times, freqs, min_pause=PHRASE_MIN_PAUSE_SEC)
        else:
            pauses = []
        significant_pauses = [p for p in pauses if p["duration"] >= PHRASE_MIN_PAUSE_SEC]

        boundaries = [0.0]
        for p in significant_pauses:
            boundaries.append(p["start"])
            boundaries.append(p["end"])
        boundaries.append(total_duration)
        boundaries = sorted(set(round(b, 3) for b in boundaries))
        boundaries_pairs = [(boundaries[i], boundaries[i + 1]) for i in range(len(boundaries) - 1)]

    phrases = []
    for boundary_idx, (seg_start, seg_end) in enumerate(boundaries_pairs):
        if seg_end - seg_start < 0.05:
            continue
        phrase_notes = [n for n in notes if n["start"] >= seg_start - 0.01 and n["start"] < seg_end + 0.01]
        if len(phrase_notes) < PHRASE_MIN_NOTES:
            continue
        phrases.append({
            "index": len(phrases),
            # color_index: اندیس فراز در مرزهای اصلی (پیش از فیلتر فرازهای
            # خیلی‌کوتاه) — همان اندیسی که موج صوتی در وب برای رنگ‌آمیزی
            # استفاده می‌کند.
            "color_index": boundary_idx,
            "start": round(seg_start, 2),
            "end": round(seg_end, 2),
            "duration": round(seg_end - seg_start, 2),
            "num_notes": len(phrase_notes),
            "notes": phrase_notes,
        })

    # اگر هیچ مکث معناداری پیدا نشد (مثلاً یک فراز کوتاه بدون سکوت داخلی)،
    # کل فایل را به‌عنوان یک فراز واحد در نظر بگیر تا مقایسه فراز-به-فراز
    # حداقل روی همان یک فراز انجام شود.
    if not phrases and notes:
        phrases.append({
            "index": 0,
            "color_index": 0,
            "start": round(notes[0]["start"], 2),
            "end": round(notes[-1]["end"], 2),
            "duration": round(notes[-1]["end"] - notes[0]["start"], 2),
            "num_notes": len(notes),
            "notes": notes,
        })

    return phrases


def phrase_level_comparison(ref_phrases, perf_phrases):
    """
    فرازهای مرجع و کاربر را با DTW در سطح فراز (نه نت) تراز می‌کند — یعنی
    برخلاف تطبیق ساده و ترتیبی (فراز اول با اول، دوم با دوم و...)، حتی اگر
    تعداد فرازهای دو فایل متفاوت باشد (مثلاً کاربر یک تنفس اضافه یا کمتر
    داشته)، بهترین ترازبندی احتمالی بین دو دنباله فراز پیدا می‌شود.

    هزینه هر جفت (فراز مرجع i، فراز کاربر j) برابر «۱۰۰ - شباهت ملودیک
    نت‌به‌نت آن دو فراز» است — یعنی هرچه دو فراز از نظر الگوی ملودیک به هم
    نزدیک‌تر باشند، هزینه ترازبندی آن‌ها کمتر است و DTW ترجیح می‌دهد
    آن‌ها را به هم متصل کند.
    """
    if not ref_phrases or not perf_phrases:
        return {
            "num_ref_phrases": len(ref_phrases),
            "num_perf_phrases": len(perf_phrases),
            "pairs": [],
            "best_matches": [],
            "worst_matches": [],
            "avg_phrase_similarity_pct": None,
            "detail": "تعداد فرازهای قابل‌تشخیص برای مقایسه فراز-به-فراز کافی نبود.",
        }

    n, m = len(ref_phrases), len(perf_phrases)
    sim_matrix = np.zeros((n, m))
    cost_matrix = np.zeros((n, m))
    for i, rp in enumerate(ref_phrases):
        for j, pp in enumerate(perf_phrases):
            ms = melodic_similarity(rp["notes"], pp["notes"])
            s = ms["similarity_pct"] or 0.0
            sim_matrix[i, j] = s
            cost_matrix[i, j] = 100.0 - s

    _, path, _ = dtw_align_cost_matrix(cost_matrix)

    pairs = []
    seen = set()
    for i, j in path:
        if i >= n or j >= m or (i, j) in seen:
            continue
        seen.add((i, j))
        pairs.append({
            # pair_index: شناسهٔ مشترک برای این جفت فراز — واسط کاربری وب از
            # این عدد برای نسبت‌دادن یک رنگ یکسان به دو فراز متناظر (یکی در
            # موج صوتی مرجع، یکی در موج صوتی کاربر) استفاده می‌کند تا چشم
            # به‌راحتی تطبیق را ببیند.
            "pair_index": len(pairs),
            "ref_phrase_index": i,
            "perf_phrase_index": j,
            "ref_start": ref_phrases[i]["start"],
            "ref_end": ref_phrases[i]["end"],
            "perf_start": perf_phrases[j]["start"],
            "perf_end": perf_phrases[j]["end"],
            "similarity_pct": round(float(sim_matrix[i, j]), 1),
        })

    pairs.sort(key=lambda p: p["ref_phrase_index"])
    best_matches = sorted(pairs, key=lambda p: -p["similarity_pct"])[:3]
    worst_matches = sorted(pairs, key=lambda p: p["similarity_pct"])[:3]
    avg_sim = float(np.mean([p["similarity_pct"] for p in pairs])) if pairs else None

    return {
        "num_ref_phrases": n,
        "num_perf_phrases": m,
        "pairs": pairs,
        "best_matches": best_matches,
        "worst_matches": worst_matches,
        "avg_phrase_similarity_pct": round(avg_sim, 1) if avg_sim is not None else None,
    }


def _phrase_comparison_summary(phrase_cmp):
    """یک خلاصه متنی خوانا از نتیجه مقایسه فراز-به-فراز می‌سازد."""
    pairs = phrase_cmp.get("pairs") or []
    if not pairs:
        return "تحلیل فراز-به-فراز به دلیل نبود مکث‌های کافی برای تفکیک فراز در یکی از دو فایل امکان‌پذیر نبود."

    best = phrase_cmp["best_matches"][0]
    lines = [
        f"بهترین تطابق فرازی: فراز مرجع [{best['ref_start']}s تا {best['ref_end']}s] "
        f"با فراز کاربر [{best['perf_start']}s تا {best['perf_end']}s] — شباهت {best['similarity_pct']}٪"
    ]

    if len(pairs) > 1:
        worst = phrase_cmp["worst_matches"][0]
        if worst["similarity_pct"] < best["similarity_pct"]:
            lines.append(
                f"ضعیف‌ترین تطابق فرازی: فراز مرجع [{worst['ref_start']}s تا {worst['ref_end']}s] "
                f"با فراز کاربر [{worst['perf_start']}s تا {worst['perf_end']}s] — شباهت {worst['similarity_pct']}٪"
            )

    return " — ".join(lines)


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

    # --- مقایسه فراز-به-فراز (تراز خودکار با DTW در سطح فراز) ---
    print("در حال تفکیک فرازها و مقایسه فراز-به-فراز (DTW)...")
    ref_phrases = segment_phrases_from_notes(
        ref["times"], ref["freqs"], ref["notes"],
        phrase_boundaries=ref.get("amplitude_phrase_boundaries"),
    )
    perf_phrases = segment_phrases_from_notes(
        perf["times"], perf["freqs"], perf["notes"],
        phrase_boundaries=perf.get("amplitude_phrase_boundaries"),
    )
    phrase_cmp = phrase_level_comparison(ref_phrases, perf_phrases)
    phrase_cmp["summary"] = _phrase_comparison_summary(phrase_cmp)
    # برای رسم نمودار به مرزهای فراز هم نیاز داریم (بدون لیست کامل نت‌ها که حجیم است)
    ref["phrases"] = [{k: v for k, v in p.items() if k != "notes"} for p in ref_phrases]
    perf["phrases"] = [{k: v for k, v in p.items() if k != "notes"} for p in perf_phrases]

    # هر فراز را به pair_index مشترکش (طبق تراز DTW) وصل می‌کنیم — تا واسط
    # کاربری وب بتواند رنگ یکسانی به دو فراز متناظر (در موج صوتی مرجع و
    # موج صوتی کاربر) بدهد. فرازهایی که در تراز DTW شرکت نکرده‌اند
    # (نظری نباید پیش بیاید چون هر فراز حداقل یک‌بار در مسیر DTW ظاهر
    # می‌شود، اما برای اطمینان) با pair_index=None مشخص می‌شوند.
    ref_pair_map = {p["ref_phrase_index"]: p["pair_index"] for p in phrase_cmp.get("pairs", [])}
    perf_pair_map = {p["perf_phrase_index"]: p["pair_index"] for p in phrase_cmp.get("pairs", [])}
    for idx, ph in enumerate(ref["phrases"]):
        ph["pair_index"] = ref_pair_map.get(idx)
    for idx, ph in enumerate(perf["phrases"]):
        ph["pair_index"] = perf_pair_map.get(idx)

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
        "phrase_level_comparison": phrase_cmp,
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
    """
    قفل سراسری رسم نمودار (PLOT_LOCK) این تابع را در برابر اجرای هم‌زمان
    چند job رسم نمودار (تحلیل تک‌فایلی، مقایسه، صادرات ویدیو در تردهای
    جداگانه سرور) محافظت می‌کند — matplotlib.pyplot وضعیت سراسری (figure
    جاری) دارد و بدون این قفل ممکن است نمودار یک job در فایل خروجی job
    دیگر ذخیره شود.
    """
    with PLOT_LOCK:
        _plot_comparison_impl(ref, perf, result, out_path)


def _plot_comparison_impl(ref, perf, result, out_path):
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


def plot_phrase_comparison(ref, perf, result, out_path):
    """
    نمودار اختصاصی مقایسه فراز-به-فراز — شامل دو بخش:

      ۱) نمودار میله‌ای شباهت هر جفت فراز (تراز شده با DTW فرازی) — سبز
         برای تطابق بالا، قرمز/نارنجی برای تطابق پایین — تا مشخص شود دقیقاً
         کدام فراز(ها) بهتر/بدتر از بقیه خوانده شده‌اند.
      ۲) ملوگراف هم‌پوشانی (overlay) دو فایل با رنگ‌بندی و خط‌چین مرزهای
         هر فراز، تا محل دقیق هر فراز روی محور زمان هم قابل مشاهده باشد.

    محافظت‌شده با PLOT_LOCK سراسری (نگاه کنید به توضیح در plot_comparison).
    """
    with PLOT_LOCK:
        _plot_phrase_comparison_impl(ref, perf, result, out_path)


def _plot_phrase_comparison_impl(ref, perf, result, out_path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    has_font = _setup_persian_font()
    matplotlib.rcParams["axes.unicode_minus"] = False
    T = (lambda s: _fa(s)) if has_font else (lambda s: s)

    phrase_cmp = result.get("phrase_level_comparison") or {}
    pairs = phrase_cmp.get("pairs") or []

    fig, axes = plt.subplots(2, 1, figsize=(14, 11))

    # --- نمودار بالا: میله‌ای شباهت هر جفت فراز ---
    ax1 = axes[0]
    if pairs:
        labels = [f"{p['ref_phrase_index']+1}↔{p['perf_phrase_index']+1}" for p in pairs]
        values = [p["similarity_pct"] for p in pairs]

        def _color_for(v):
            if v >= 80:
                return "#16a34a"       # سبز: تطابق بالا
            if v >= 55:
                return "#f59e0b"       # نارنجی: تطابق متوسط
            return "#dc2626"           # قرمز: تطابق پایین

        colors = [_color_for(v) for v in values]
        bars = ax1.bar(labels, values, color=colors)
        ax1.set_ylim(0, 105)
        ax1.set_ylabel(T("درصد شباهت فراز"))
        ax1.set_xlabel(T("شماره فراز مرجع ↔ شماره فراز کاربر"))
        ax1.set_title(T(f"شباهت فراز-به-فراز (تراز خودکار DTW) — میانگین: "
                         f"{phrase_cmp.get('avg_phrase_similarity_pct', 0)}%"), fontsize=13)
        for bar, v in zip(bars, values):
            ax1.text(bar.get_x() + bar.get_width() / 2, v + 2, f"{v:.0f}%",
                      ha="center", fontsize=10, fontweight="bold")
        ax1.grid(True, axis="y", alpha=0.2)
    else:
        ax1.text(0.5, 0.5, T("فرازی برای مقایسه یافت نشد"), ha="center", va="center",
                  transform=ax1.transAxes, fontsize=12)
        ax1.set_axis_off()

    # --- نمودار پایین: ملوگراف هم‌پوشانی با مرزهای فراز ---
    ax2 = axes[1]

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

    ax2.plot(ref_t, ref_c, '.', color="#2563eb", markersize=2, label=T("فراز مرجع"))
    ax2.plot(perf_t, perf_c, '.', color="#ef4444", markersize=2, label=T("فراز کاربر"), alpha=0.7)

    y_lo, y_hi = ax2.get_ylim()
    ref_phrases = ref.get("phrases") or []
    perf_phrases = perf.get("phrases") or []

    for idx, p in enumerate(ref_phrases):
        ax2.axvline(p["start"], color="#2563eb", linestyle="--", alpha=0.35, linewidth=1)
        ax2.text(p["start"], y_hi, f"R{idx+1}", color="#2563eb", fontsize=8,
                  va="bottom", ha="left")
    for idx, p in enumerate(perf_phrases):
        ax2.axvline(p["start"], color="#ef4444", linestyle=":", alpha=0.35, linewidth=1)
        ax2.text(p["start"], y_lo, f"U{idx+1}", color="#ef4444", fontsize=8,
                  va="top", ha="left")

    ax2.set_title(T("ملوگراف هم‌پوشانی با مرزهای فراز (R=مرجع، U=کاربر)"), fontsize=13)
    ax2.set_xlabel(T("زمان (ثانیه)"))
    ax2.set_ylabel(T("انحراف نسبی از میانگین (سنت)"))
    ax2.legend(loc="upper right")
    ax2.grid(True, alpha=0.2)

    plt.tight_layout()
    plt.savefig(out_path, dpi=140)
    plt.close(fig)
    print(f"نمودار مقایسه فراز-به-فراز ذخیره شد: {out_path}")


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

    phrase_cmp = result.get("phrase_level_comparison")
    if phrase_cmp:
        print(f"\n🎼 مقایسه فراز-به-فراز (تراز خودکار DTW):")
        print(f"   تعداد فراز مرجع: {phrase_cmp['num_ref_phrases']}   |   "
              f"تعداد فراز کاربر: {phrase_cmp['num_perf_phrases']}")
        if phrase_cmp.get("avg_phrase_similarity_pct") is not None:
            print(f"   میانگین شباهت فرازی: {phrase_cmp['avg_phrase_similarity_pct']}%")
        print(f"   {phrase_cmp.get('summary', '')}")

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

        phrase_plot_path = os.path.splitext(plot_path)[0] + "_phrases.png"
        plot_phrase_comparison(ref, perf, result, phrase_plot_path)
        result["meta"]["phrase_visualization_file"] = phrase_plot_path

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(result, f, ensure_ascii=False, indent=2)
        print(f"گزارش JSON ذخیره شد در: {args.output}")


if __name__ == "__main__":
    main()
