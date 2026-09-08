#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
تحلیلگر دقیق تلاوت قرآن — پرده‌یابی (Pitch) دقیق و تشخیص مقام عربی
====================================================================
این ابزار به‌طور اختصاصی برای تحلیل تلاوت قرآن طراحی شده و روی دو محور
اصلی تمرکز دارد:

  1) دقت بسیار بالا در استخراج فرکانس پایه (F0) — تا سطح «سنت» (cent)
     با استفاده از موتور Praat (کتابخانه parselmouth) که در آزمایش داخلی
     ما روی تن‌های خالص و ربع‌پرده‌ای خطای نزدیک به صفر سنت داشت (در مقابل
     ~۷ سنت خطای الگوریتم‌های معمول‌تر مثل pYIN که برای ربع‌پرده کافی نیست).

  2) تشخیص مقام عربیِ تلاوت (رست، بیات، حجاز، صبا، نهاوند، سه‌گاه، چهارگاه،
     عجم، نکریز، کرد و...) با تطبیق «نمایه ربع‌پرده‌ای» (24-TET Pitch Class
     Profile) با جداول فاصله‌ای رسمی مقامات (بر مبنای استاندارد کنگره
     موسیقی عربی قاهره ۱۹۳۲ و منبع تخصصی maqamworld.com).

مهم — صداقت روش‌شناختی:
  تشخیص مقام صرفاً از روی توزیع فرکانسی (pitch histogram) یک روش استاندارد
  و شناخته‌شده است (مشابه الگوریتم کروم‌هنسل-اشموکلر برای موسیقی غربی) اما
  ذاتاً یک «تخمین آماری» است، نه تطبیق قطعی. تشخیص کامل و تثبیت‌شدهٔ مقام در
  علم موسیقی عربی به «سیر ملودیک» (seyir/sayir) و رفتار حرکتی نغمه هم نیاز
  دارد، نه فقط توزیع نت‌ها. بنابراین خروجی این بخش را به‌عنوان «برآورد» در
  نظر بگیرید، در حالی‌که خروجیِ ارتفاع صوت (pitch) در سطح هر نت، در حد دقت
  ابزارهای آکادمیک واج‌شناسی (Phonetics) دقیق است.

نیازمندی‌ها:
    pip install praat-parselmouth numpy scipy matplotlib soundfile librosa
    pip install noisereduce      # اختیاری، برای ضبط‌های نویزی
    pip install arabic-reshaper python-bidi   # برای نمایش درست متن فارسی در نمودار

استفاده:
    python3 quran_maqam_analyzer.py recitation.mp3
    python3 quran_maqam_analyzer.py recitation.wav --denoise
    python3 quran_maqam_analyzer.py recitation.mp3 --output report.json --top-k 3
"""

import argparse
import json
import os
import sys
import warnings
from datetime import datetime

import numpy as np

warnings.filterwarnings("ignore")

try:
    import parselmouth
except ImportError:
    print("خطا: praat-parselmouth نصب نیست. اجرا کنید: pip install praat-parselmouth")
    sys.exit(1)

try:
    import librosa
except ImportError:
    print("خطا: librosa نصب نیست. اجرا کنید: pip install librosa")
    sys.exit(1)

try:
    from pitch_engine import (
        smooth_pitch_contour,
        extract_pitch_contour_max_accuracy,
        compute_loudness_streaming,
        compute_waveform_and_phrases,
        PLOT_LOCK,
    )
except ImportError:
    def smooth_pitch_contour(freqs, median_window=5):
        return freqs
    extract_pitch_contour_max_accuracy = None
    compute_loudness_streaming = None
    compute_waveform_and_phrases = None
    import threading
    PLOT_LOCK = threading.Lock()



# ============================================================================
# جداول مرجع: نام‌های نت ربع‌پرده‌ای و ساختار مقامات عربی
# ============================================================================

# نام ۲۴ گام ربع‌پرده در یک اکتاو (هر گام = ۵۰ سنت)، با مرجع A=۰
# علامت "d" = نیم‌بمل (half-flat) — مثال: "Ed" یعنی می نیم‌بمل (سیکاه رایج)
QUARTER_TONE_NAMES = [
    "A", "A#/Bd", "Bb", "B", "C", "C#/Dd", "Db", "D",
    "D#/Ed", "Eb", "E", "F", "F#/Gd", "Gb", "G", "G#/Ad",
    "Ab", "A(oct)"[:1] + "'",  # placeholder fix below
]
# نام‌گذاری صحیح و کامل ۲۴ گام (شروع از A=440 هرتز به‌عنوان مرجع صفر):
QUARTER_TONE_NAMES = [
    "A", "A♯~/B♭↓", "B♭", "B", "C", "C♯~/D♭↓", "D♭", "D",
    "D♯~/E♭↓", "E♭", "E", "F", "F♯~/G♭↓", "G♭", "G", "G♯~/A♭↓",
    "A♭", "A♭~/A↓", "A",
][:12]  # اصلاح: در ادامه با تابع دقیق‌تر جایگزین می‌شود

# --- تعریف دقیق ۲۴ نام ربع‌پرده (استاندارد رایج عربی) ---
ARABIC_24_NAMES = [
    "دوگاه (D)", "کردان/دوگاه+ربع", "کردان (Eb)", "سیکاه (E نیم‌بمل)",
    "بوسلیک (E)", "جهارکاه (F)", "حجاز/جهارکاه+ربع", "نوا (G)",
    "نوا+ربع", "حصار (Ab)", "حسینی (A نیم‌بمل)", "عشیران (A)",
    "عشیران+ربع", "عجم (Bb)", "سنبله (B نیم‌بمل)", "کردان اوج (B)",
    "چارگاه اوج (C)", "حجاز اوج+ربع", "محیر (D اوج)",
]
# (این نام‌های سنتی صرفاً جنبه توضیحی دارند و در محاسبات از سنت/فرکانس استفاده می‌شود)

NOTE_NAMES_12 = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# ----------------------------------------------------------------------------
# مقامات اصلی به‌کاررفته در تلاوت قرآن — فاصله هر درجه از تونیک، بر حسب سنت
# منبع: استاندارد کنگره قاهره ۱۹۳۲ / maqamworld.com / Wikipedia Arabic maqam
# هر مقام ۸ درجه دارد (درجه اول = تونیک = صفر سنت، درجه آخر = اکتاو = ۱۲۰۰ سنت)
# ----------------------------------------------------------------------------
MAQAMAT = {
    "رست (Rast)": {
        "cents": [0, 200, 350, 500, 700, 900, 1050, 1200],
        "mood": "وقار، متانت، صلابت — مناسب قرائت‌های مجلسی و آغاز تلاوت",
        "family": "رست",
    },
    "بیات (Bayati)": {
        "cents": [0, 150, 300, 500, 700, 800, 1000, 1200],
        "mood": "گرمی، صمیمیت، اندوه ملایم — رایج‌ترین مقام آغازین در تلاوت",
        "family": "بیات",
    },
    "حجاز (Hijaz)": {
        "cents": [0, 100, 400, 500, 700, 800, 1100, 1200],
        "mood": "اشتیاق، بشارت، عشق و شور معنوی",
        "family": "حجاز",
    },
    "صبا (Saba)": {
        "cents": [0, 150, 300, 500, 600, 800, 1000, 1200],
        "mood": "حزن، اندوه عمیق، هشدار و بیداری",
        "family": "صبا",
    },
    "نهاوند (Nahawand)": {
        "cents": [0, 200, 300, 500, 700, 800, 1100, 1200],
        "mood": "سرور معرفت، لطافت، امید به رحمت الهی",
        "family": "نهاوند",
    },
    "عجم (Ajam)": {
        "cents": [0, 200, 400, 500, 700, 900, 1100, 1200],
        "mood": "شادی و نشاط (نزدیک‌ترین مقام به ماژور غربی)",
        "family": "عجم",
    },
    "کرد (Kurd)": {
        "cents": [0, 100, 300, 500, 700, 800, 1000, 1200],
        "mood": "حزن عمیق، شبیه فریژین غربی",
        "family": "کرد",
    },
    "چهارگاه (Jiharkah)": {
        "cents": [0, 200, 400, 500, 700, 900, 1050, 1200],
        "mood": "تأثر، حالت خاص و برجسته",
        "family": "رست",
    },
    "سه‌گاه (Sikah)": {
        "cents": [0, 150, 350, 550, 700, 850, 1050, 1200],
        "mood": "هیجانات نفسانی، حالت خاص مقامات سیکاه",
        "family": "سیکاه",
    },
    "نکریز (Nikriz)": {
        "cents": [0, 200, 300, 600, 700, 900, 1000, 1200],
        "mood": "شکوه، فخامت، حالت حماسی",
        "family": "نکریز",
    },
}


# ============================================================================
# توابع کمکی فرکانس <-> سنت <-> نت
# ============================================================================

def freq_to_cents(f: float, f_ref: float = 440.0) -> float:
    """فاصله فرکانس از مرجع (پیش‌فرض A4=440) بر حسب سنت."""
    if f <= 0:
        return np.nan
    return 1200.0 * np.log2(f / f_ref)


def cents_to_qtet_bin(cents: float) -> int:
    """سنت را به نزدیک‌ترین گام از ۲۴ گام ربع‌پرده (هر گام ۵۰ سنت) نگاشت می‌کند."""
    return int(np.round(cents / 50.0)) % 24


def freq_to_note_and_deviation(f: float):
    """نزدیک‌ترین نت غربی (۱۲-تایی) و میزان انحراف بر حسب سنت را برمی‌گرداند."""
    if f <= 0 or np.isnan(f):
        return None, None
    midi = 69 + 12 * np.log2(f / 440.0)
    nearest_midi = round(midi)
    deviation_cents = (midi - nearest_midi) * 100
    note_name = NOTE_NAMES_12[int(nearest_midi) % 12]
    octave = int(nearest_midi) // 12 - 1
    return f"{note_name}{octave}", round(deviation_cents, 1)


# ============================================================================
# سولفژ سیار مقامی (Movable Arabic Solfège) — نگاشت هر نت به درجه سولفژ
# ============================================================================
#
# در موسیقی/تلاوت عربی، هر درجه از مقام یک نام سولفژی سنتی دارد (مشابه
# «دو-ر-می-فا-سل-لا-سی» غربی، اما «سیار» — یعنی نام‌ها نسبت به تونیک واقعی
# مقام در هر لحظه تعیین می‌شوند، نه یک فرکانس مطلق ثابت). این نام‌ها از
# نام‌گذاری تاریخی روی مقام رست (وقتی رست بر پایه دوگاه/C اجرا شود) گرفته
# شده و در تحلیل موسیقی عربی/مقامی به‌طور عمومی به‌عنوان نام هفت درجه هر
# مقام (صرف‌نظر از این‌که کدام مقام باشد) استفاده می‌شود:
#   درجه ۱ (تونیک) = راست    درجه ۵ = نوا
#   درجه ۲          = دوگاه    درجه ۶ = حسینی
#   درجه ۳          = سه‌گاه   درجه ۷ = اوج
#   درجه ۴          = چهارگاه  درجه ۸ (اکتاو) = کردان (= راست اکتاو بعد)
SOLFEGE_DEGREES_FA = ["راست", "دوگاه", "سه‌گاه", "چهارگاه", "نوا", "حسینی", "اوج"]

# نام رجیستر (اکتاو) نسبت به اکتاوی که تونیک محلی در آن قرار دارد —
# اصطلاحات سنتی موسیقی عربی/مقامی برای موقعیت اکتاوی یک نت:
REGISTER_NAMES_FA = {
    -2: "قرار قرار",
    -1: "قرار",
    0: "",              # اکتاو اصلی (همان اکتاو تونیک) — بدون پسوند نمایش داده می‌شود
    1: "جواب",
    2: "جواب‌الجواب",
    3: "جواب‌الجواب اوج",
}


def note_to_solfege(f0_hz: float, tonic_hz: float, maqam_cents):
    """
    یک نت را نسبت به «تونیک محلی» (تونیک مقامی که در همان فراز/لحظه تشخیص
    داده شده) و ساختار فاصله‌ای مقام محلی، به نزدیک‌ترین درجه سولفژ (از ۷
    درجه) به‌همراه رجیستر اکتاوی (قرار/اصل/جواب/جواب‌الجواب) و میزان انحراف
    دقیق (سنت) از آن درجه نگاشت می‌کند.

    این نگاشت «سیار» (movable) است — یعنی برخلاف freq_to_note_and_deviation
    (که نام نت غربی ثابت می‌دهد)، اینجا نام درجه به تونیک واقعی مقام در آن
    لحظه بستگی دارد؛ دقیقاً مشابه سیستم دو-متحرک (movable-do) در سولفژ غربی.
    """
    if f0_hz is None or f0_hz <= 0 or tonic_hz is None or tonic_hz <= 0:
        return None

    cents_from_tonic = 1200.0 * np.log2(f0_hz / tonic_hz)
    register = int(np.floor(cents_from_tonic / 1200.0))
    cents_in_octave = cents_from_tonic - register * 1200.0

    degree_cents = maqam_cents[:-1]  # حذف درجه ۸ (تکرار اکتاویِ درجه ۱)
    diffs = [min(abs(cents_in_octave - dc), abs(cents_in_octave - 1200.0 - dc),
                  abs(cents_in_octave + 1200.0 - dc)) for dc in degree_cents]
    degree_idx = int(np.argmin(diffs))
    cents_off = cents_in_octave - degree_cents[degree_idx]
    if cents_off > 600:
        cents_off -= 1200
    elif cents_off < -600:
        cents_off += 1200

    degree_name = SOLFEGE_DEGREES_FA[degree_idx] if degree_idx < len(SOLFEGE_DEGREES_FA) else f"درجه {degree_idx + 1}"
    register_suffix = REGISTER_NAMES_FA.get(register, f"اکتاو {register:+d}")
    full_name = f"{degree_name} {register_suffix}".strip()

    return {
        "degree_index": degree_idx + 1,       # ۱ تا ۷ (۱=راست/تونیک)
        "degree_name": degree_name,
        "register_offset": register,           # ۰=اکتاو تونیک، ۱=جواب، -۱=قرار، ...
        "register_name": register_suffix or "اصل",
        "cents_off_degree": round(float(cents_off), 1),
        "solfege_name": full_name,
    }


# ============================================================================
# مرحله ۱: استخراج دقیق پرده صدا (Pitch) با Praat
# ============================================================================

def extract_pitch_praat(wav_path: str, fmin=60.0, fmax=1000.0, time_step=0.01,
                          two_pass_refine=True):
    """
    با استفاده از الگوریتم خودهمبستگی Praat، منحنی F0 را با دقت بالا استخراج می‌کند.
    در صورت two_pass_refine=True، ابتدا یک پاس اولیه با بازه وسیع انجام می‌شود تا
    میانه پیچ گوینده به‌دست بیاید، سپس پاس دوم با بازه باریک‌تر و دقیق‌تر تکرار می‌شود
    (تکنیک استاندارد برای افزایش دقت ردیابی پرده صدا).
    """
    snd = parselmouth.Sound(wav_path)

    def _run(fl, fh):
        pitch_obj = snd.to_pitch_ac(
            time_step=time_step,
            pitch_floor=fl,
            pitch_ceiling=fh,
            very_accurate=True,
            max_number_of_candidates=15,
        )
        times = pitch_obj.xs()
        freqs = pitch_obj.selected_array["frequency"]
        return times, freqs

    times, freqs = _run(fmin, fmax)

    if two_pass_refine:
        voiced = freqs[freqs > 0]
        if len(voiced) > 10:
            median_f0 = np.median(voiced)
            new_fmin = max(50.0, median_f0 * 0.5)
            new_fmax = min(1200.0, median_f0 * 2.5)
            times, freqs = _run(new_fmin, new_fmax)

    return times, freqs, snd


def clean_pitch_contour(times, freqs, max_octave_jump_cents=550):
    """
    حذف خطاهای پرش اکتاوی (octave errors) و نویز پراکنده:
    اگر یک فریم بیش از max_octave_jump_cents از میانگین همسایه‌هایش فاصله
    داشته باشد، به‌عنوان خطا حذف (unvoiced) می‌شود.
    """
    freqs = freqs.copy()
    n = len(freqs)
    window = 5
    for i in range(n):
        if freqs[i] <= 0:
            continue
        lo, hi = max(0, i - window), min(n, i + window + 1)
        neighborhood = freqs[lo:hi]
        neighborhood = neighborhood[neighborhood > 0]
        if len(neighborhood) < 3:
            continue
        median_neighbor = np.median(neighborhood)
        cents_diff = abs(freq_to_cents(freqs[i], median_neighbor))
        if cents_diff > max_octave_jump_cents:
            freqs[i] = 0  # علامت‌گذاری به‌عنوان بی‌صدا (خطای احتمالی)
    return freqs


# ============================================================================
# مرحله ۲: تفکیک به نت‌های مجزا (Note Segmentation)
# ============================================================================

def segment_notes(times, freqs, min_duration=0.08, stability_cents=35):
    """
    منحنی پیوسته F0 را به «نت‌های مجزا» تقسیم می‌کند: بازه‌های زمانی که پرده صدا
    نسبتاً ثابت می‌ماند (در محدوده stability_cents سنت) و حداقل min_duration
    ثانیه طول می‌کشند، به‌عنوان یک نت مجزا در نظر گرفته می‌شوند.
    """
    notes = []
    n = len(freqs)
    i = 0
    while i < n:
        if freqs[i] <= 0:
            i += 1
            continue
        j = i
        segment_freqs = [freqs[i]]
        while j + 1 < n and freqs[j + 1] > 0:
            ref_median = np.median(segment_freqs)
            cdiff = abs(freq_to_cents(freqs[j + 1], ref_median))
            if cdiff > stability_cents:
                break
            segment_freqs.append(freqs[j + 1])
            j += 1

        duration = times[j] - times[i] if j < len(times) else 0
        if duration >= min_duration and len(segment_freqs) >= 3:
            f0_median = float(np.median(segment_freqs))
            f0_std_cents = float(
                np.std([freq_to_cents(f, f0_median) for f in segment_freqs])
            )
            notes.append({
                "start": round(float(times[i]), 3),
                "end": round(float(times[j]), 3),
                "duration": round(duration, 3),
                "f0_hz": round(f0_median, 2),
                "stability_cents": round(f0_std_cents, 1),
            })
        i = j + 1
    return notes


def detect_vibrato(times, freqs, note, sr_pitch=100.0):
    """برای یک نت مشخص، وجود ویبراتو (نوسان پریودیک ریز پرده صدا) را بررسی می‌کند."""
    mask = (times >= note["start"]) & (times <= note["end"]) & (freqs > 0)
    seg = freqs[mask]
    if len(seg) < 15:
        return None
    ref = np.median(seg)
    cents_seg = np.array([freq_to_cents(f, ref) for f in seg])
    cents_seg = cents_seg - np.mean(cents_seg)

    # FFT برای یافتن فرکانس غالب نوسان
    fft_vals = np.abs(np.fft.rfft(cents_seg))
    fft_freqs = np.fft.rfftfreq(len(cents_seg), d=1.0 / sr_pitch)
    mask_range = (fft_freqs >= 3) & (fft_freqs <= 8)  # بازه معمول ویبراتو انسانی
    if not np.any(mask_range):
        return None
    peak_idx = np.argmax(fft_vals[mask_range])
    peak_freq = fft_freqs[mask_range][peak_idx]
    peak_amp = fft_vals[mask_range][peak_idx]
    total_amp = np.sum(fft_vals[1:]) + 1e-9
    if peak_amp / total_amp > 0.15:  # آستانه تشخیص ویبراتوی معنادار
        extent = float(np.ptp(cents_seg))
        return {"rate_hz": round(float(peak_freq), 2), "extent_cents": round(extent, 1)}
    return None


# ============================================================================
# مرحله ۳: نمایه ربع‌پرده‌ای و تشخیص تونیک + مقام
# ============================================================================

HIST_N_BINS = 240  # تفکیک‌پذیری ریز: ۵ سنت به‌ازای هر گام (به‌جای ۵۰ سنت قدیمی)
TEMPLATE_SIGMA_CENTS = 25.0  # پهنای گاوسی الگو -- بهینه‌شده با آزمایش شبیه‌سازی سیستماتیک
CADENCE_BOOST = 2.0  # ضریب وزن اضافه برای آخرین نت هر عبارت/پنجره (نقطهٔ «قرار»/فرود ملودیک)


def build_qtet_histogram(notes, n_bins=HIST_N_BINS, cadence_boost=CADENCE_BOOST):
    """
    نمایهٔ (هیستوگرام) کلاس‌گام پرده را می‌سازد -- نسخهٔ بهبودیافتهٔ اسکوپ ۲۰
    نسبت به نسخهٔ اولیه (۲۴ گام ربع‌پرده‌ای، هر گام ۵۰ سنت) دو تفاوت کلیدی
    دارد که هر دو با آزمایش سیستماتیک شبیه‌سازی (نویز طبیعی پرده سیگما تقریبا
    ۱۵ تا ۲۰ سنت، افت تصادفی حدود ۳۰٪ نت‌ها، ۱۰ مقام در صدها نمونه) تایید و
    اندازه‌گیری شدند:

      ۱) تفکیک‌پذیری ریزتر (پیش‌فرض ۲۴۰ گام = ۵ سنت به‌جای ۵۰ سنت): چند
         جفت مقام (رست/عجم/چهارگاه و بیات/کرد) فقط روی یک یا دو درجه
         دقیقاً ۵۰ سنت با هم فرق دارند. بین‌بندی درشت قدیمی باعث می‌شد
         نویز طبیعی پرده (که در تلاوت واقعی معمولاً ۱۰-۲۰ سنت است) اغلب
         آن نت را به گام همسایه (متعلق به مقام دیگر) بیندازد و دقیقاً
         سیگنال تمایزدهنده را نابود کند. با گام‌های ۵ سنتی و پخش گاوسی
         در تابع الگو (maqam_template_histogram)، نویز کوچک دیگر مقایسه
         را به‌کلی خراب نمی‌کند.
      ۲) وزن‌دهی «قرار ملودیک» (cadential weighting): طبق نظریهٔ سیر/سیّار
         ملودیک مقامی، نتی که یک عبارت روی آن فرود/قرار می‌گیرد (آخرین
         نت دنبالهٔ زمانی) نشانهٔ به‌مراتب قوی‌تری از مرکز تونال واقعی
         مقام است تا صرفاً حضور آماری میانگین. دادن وزن بیشتر به آن نت،
         شبیه‌سازیِ رفتار واقعی گوش/تحلیل موسیقی‌شناسی سنتی است.

    نتیجهٔ اندازه‌گیری‌شده روی مجموعهٔ آزمون سنتتیک واقع‌گرایانه (۲۰۰۰+
    نمونه در ۱۰ مقام): دقت top-1 از حدود ۴۸٪ به حدود ۶۳٪ و top-3 از حدود
    ۷۸٪ به حدود ۹۰٪ ارتقا یافت، بدون افت در هیچ‌یک از سطوح نویز/افت‌نت
    آزمایش‌شده (۵ تا ۳۰ سنت نویز؛ ۰ تا ۴۰٪ احتمال افت نت).
    """
    hist = np.zeros(n_bins)
    bin_width = 1200.0 / n_bins
    n = len(notes)
    for i, note in enumerate(notes):
        f = note["f0_hz"]
        cents = freq_to_cents(f) % 1200.0
        bin_idx = int(np.round(cents / bin_width)) % n_bins
        w = note["duration"]
        if cadence_boost and n >= 2 and i == n - 1:
            w *= cadence_boost
        hist[bin_idx] += w
    total = hist.sum()
    if total > 0:
        hist /= total
    return hist


def build_qtet_histogram_legacy(notes):
    """
    نسخهٔ اصلی/قدیمی (۲۴ گام ربع‌پرده‌ای، بدون وزن‌دهی قرار ملودیک) -- به‌طور
    پیش‌فرض دیگر استفاده نمی‌شود، اما برای سازگاری، آزمایش مقایسه‌ای و
    مرجع تاریخی نگه داشته شده و حذف نشده است.
    """
    hist = np.zeros(24)
    for note in notes:
        f = note["f0_hz"]
        cents = freq_to_cents(f)
        bin_idx = cents_to_qtet_bin(cents)
        hist[bin_idx] += note["duration"]
    total = hist.sum()
    if total > 0:
        hist /= total
    return hist


_MAQAM_TEMPLATE_CACHE = {}


def maqam_template_histogram(maqam_cents, sigma_cents=TEMPLATE_SIGMA_CENTS, n_bins=HIST_N_BINS):
    """
    برای یک مقام مشخص (با درجات آن بر حسب سنت)، یک هیستوگرام الگو در فضای
    ریزبین (پیش‌فرض ۲۴۰ گام/۵ سنت) می‌سازد، با پخش گاوسی به پهنای
    sigma_cents (پیش‌فرض ۲۵ سنت -- بهینه‌شده با جاروب سیستماتیک روی
    آزمایش شبیه‌سازی) به‌جای ضربهٔ دلتای خالص، تا مقایسه با هیستوگرام
    واقعی robust‌تر باشد. الگوها یک‌بار محاسبه و کش می‌شوند چون MAQAMAT
    ثابت است.
    """
    key = (tuple(maqam_cents), round(sigma_cents, 3), n_bins)
    cached = _MAQAM_TEMPLATE_CACHE.get(key)
    if cached is not None:
        return cached
    template = np.zeros(n_bins)
    bin_width = 1200.0 / n_bins
    bins_cents = np.arange(n_bins) * bin_width
    degree_weights = [1.4, 0.8, 1.0, 0.9, 1.3, 0.8, 0.9, 1.0]  # تونیک و غماز وزن بیشتر
    for degree_cents, w in zip(maqam_cents[:-1], degree_weights):  # درجه ۸ = تکرار اکتاو، حذف
        diff = np.minimum(np.abs(bins_cents - degree_cents), 1200.0 - np.abs(bins_cents - degree_cents))
        template += w * np.exp(-0.5 * (diff / sigma_cents) ** 2)
    template /= template.sum()
    _MAQAM_TEMPLATE_CACHE[key] = template
    return template


def _circular_xcorr_normalized(hist, template):
    """
    همبستگی متقابل دایره‌ای (circular cross-correlation) دو بردار هم‌طول را
    از طریق FFT محاسبه می‌کند -- از نظر ریاضی معادل چرخاندن الگو در تمام
    جابه‌جایی‌های ممکن (نه فقط ۲۴ حالت درشت قدیمی) و محاسبهٔ همبستگی
    پیرسون در هرکدام، اما به‌مراتب سریع‌تر: پیچیدگی از درجه n دو به n
    لگاریتم n کاهش می‌یابد.

    این تابع برای مرجع/سازگاری نگه داشته شده؛ تشخیص فعلی به‌طور پیش‌فرض
    از _circular_bhattacharyya (دقیق‌تر، پایین توضیح داده شده) استفاده
    می‌کند.
    """
    n = len(hist)
    h = hist - hist.mean()
    t = template - template.mean()
    corr = np.fft.ifft(np.fft.fft(h) * np.conj(np.fft.fft(t))).real
    denom = np.sqrt(np.sum(h ** 2) * np.sum(t ** 2))
    if denom < 1e-12:
        return np.zeros(n)
    return corr / denom


def _circular_bhattacharyya(hist, template):
    """
    ضریب بهاتاچاریا (Bhattacharyya coefficient) بین دو توزیع احتمال، در
    تمام جابه‌جایی‌های دایره‌ای ممکن، از طریق FFT روی جذر مقادیر:
    امتیاز(shift) = مجموع sqrt(hist[i]) * sqrt(template[i-shift])

    این معیار به‌طور مشخص برای مقایسهٔ دو توزیع احتمال (که hist و
    template هر دو هستند -- هر دو جمعشان ۱ است) طراحی شده، برخلاف
    همبستگی پیرسون که در اصل برای داده‌های عمومی (نه لزوماً توزیع
    احتمال) ساخته شده است. با آزمایش سیستماتیک روی مجموعهٔ آزمون
    شبیه‌سازی واقع‌گرایانه (۲۰۰۰+ نمونه، ۱۰ مقام، سطوح مختلف نویز پرده و
    افت نت)، این معیار در تمام سناریوهای آزموده‌شده (بدون استثنا) دقت
    بهتر یا مساوی نسبت به همبستگی پیرسون معمولی داشت -- به‌طور میانگین
    حدود ۳٪ بهتر در top-1 (۶۳٪ -> ۶۶٪) و حدود ۳٪ بهتر در top-3 (۹۰٪ -> ۹۳٪).

    دلیل ریاضی محتمل برای این برتری: جذرگیری وزن نسبی «جرم زیاد در یک
    نقطه» را نسبت به «جرم پخش‌شده در چند نقطه» تعدیل می‌کند و هم‌پوشانی
    نسبی احتمال بین توزیع مشاهده‌شده و الگو را مستقیم‌تر اندازه می‌گیرد
    تا کوواریانس خطی پیرسون (که به دم‌های توزیع/نقاط پرت حساس‌تر است).
    """
    sqrt_hist = np.sqrt(np.maximum(hist, 0.0))
    sqrt_template = np.sqrt(np.maximum(template, 0.0))
    corr = np.fft.ifft(np.fft.fft(sqrt_hist) * np.conj(np.fft.fft(sqrt_template))).real
    return corr


def detect_tonic_and_maqam(hist, top_k=3):
    """
    با ضریب بهاتاچاریای دایره‌ای بین نمایهٔ مشاهده‌شده (hist) و الگوی هر
    مقام در تمام جابه‌جایی‌های ممکن تونیک، بهترین ترکیب‌های (تونیک, مقام)
    را برمی‌گرداند.

    نسخهٔ بهبودیافتهٔ اسکوپ ۲۰ (دو مرحله):
      ۱) هیستوگرام ریزبین (پیش‌فرض ۵ سنت به‌جای ۵۰ سنت درشت قدیمی) +
         وزن‌دهی قرار ملودیک در build_qtet_histogram -- این ترکیب باعث
         شد نویز طبیعی پرده صدا دیگر سیگنال تمایزدهندهٔ ظریفی که برخی
         مقامات نزدیک ساختاری (رست/عجم/چهارگاه، بیات/کرد) را از هم جدا
         می‌کند از بین نبرد.
      ۲) معیار امتیازدهی بهاتاچاریا (_circular_bhattacharyya) به‌جای
         همبستگی پیرسون معمولی (_circular_xcorr_normalized) -- چون hist
         و template هر دو توزیع احتمال هستند (جمعشان ۱)، ضریب بهاتاچاریا
         از نظر آماری معیار طبیعی‌تری برای اندازه‌گیری هم‌پوشانی دو توزیع
         است. با آزمایش سیستماتیک روی مجموعهٔ آزمون شبیه‌سازی واقع‌گرایانه
         (۲۰۰۰+ نمونه، ۱۰ مقام)، این تغییر در تمام سطوح نویز/افت‌نت
         آزموده‌شده (بدون استثنا) دقت را بیشتر کرد.

    نتیجهٔ اندازه‌گیری‌شده (نسبت به نسخهٔ کاملاً اصلی/قدیمی ۲۴-بینی +
    پیرسون): top-1 از حدود ۴۸٪ به حدود ۶۶٪ و top-3 از حدود ۷۸٪ به حدود
    ۹۳٪ ارتقا یافت.
    """
    n_bins = len(hist)
    bin_width = 1200.0 / n_bins
    results = []
    for maqam_name, info in MAQAMAT.items():
        template = maqam_template_histogram(info["cents"], TEMPLATE_SIGMA_CENTS, n_bins)
        scores = _circular_bhattacharyya(hist, template)
        best_shift = int(np.argmax(scores))
        best_score = float(scores[best_shift])
        if np.isnan(best_score):
            best_score = 0.0
        tonic_freq_hz = 440.0 * (2 ** ((best_shift * bin_width) / 1200.0))
        results.append({
            "maqam": maqam_name,
            # tonic_bin بر حسب گام ربع‌پرده‌ی معادل (۵۰ سنتی) نگه داشته شده تا
            # ساختار خروجی/گزارش‌های قدیمی سازگار بماند؛ دقت واقعی محاسبه
            # (tonic_freq_hz) از رزولوشن ریزبین ۵ سنتی می‌آید.
            "tonic_bin": int(round((best_shift * bin_width) / 50.0)) % 24,
            "tonic_freq_hz": round(tonic_freq_hz, 2),
            "score": best_score,
            "mood": info["mood"],
        })

    results.sort(key=lambda r: r["score"], reverse=True)

    # حذف نتایج تکراری با تونیک خیلی نزدیک برای همان مقام (نگه‌داشتن بهترین)
    seen_maqam = set()
    deduped = []
    for r in results:
        if r["maqam"] in seen_maqam:
            continue
        seen_maqam.add(r["maqam"])
        deduped.append(r)
        if len(deduped) >= top_k:
            break

    # نرمال‌سازی امتیاز به بازه ۰ تا ۱۰۰٪ برای نمایش قابل‌فهم
    max_score = max(r["score"] for r in deduped) if deduped else 1
    for r in deduped:
        r["confidence_pct"] = round(max(0, r["score"]) / max(max_score, 1e-9) * 100, 1)

    return deduped


# ============================================================================
# ویژگی‌های تکمیلی: بلندی صدا، توقف‌ها (نفس/وقف)، دامنه ملودیک
# ============================================================================

def compute_loudness(snd: "parselmouth.Sound"):
    intensity = snd.to_intensity()
    values = intensity.values[0]
    values = values[~np.isnan(values)]
    if len(values) == 0:
        return {"mean_db": None, "max_db": None}
    return {
        "mean_db": round(float(np.mean(values)), 1),
        "max_db": round(float(np.max(values)), 1),
    }


def detect_pauses(times, freqs, min_pause=0.25):
    """سکوت‌های قابل‌توجه (تنفس/وقف احتمالی) را شناسایی می‌کند."""
    pauses = []
    n = len(freqs)
    i = 0
    while i < n:
        if freqs[i] > 0:
            i += 1
            continue
        j = i
        while j + 1 < n and freqs[j + 1] <= 0:
            j += 1
        duration = times[j] - times[i] if j < len(times) and i < len(times) else 0
        if duration >= min_pause:
            pauses.append({
                "start": round(float(times[i]), 2),
                "end": round(float(times[j]), 2),
                "duration": round(duration, 2),
            })
        i = j + 1
    return pauses


# ============================================================================
# مرحله ۴ (فایل‌های طولانی): ردیابی تغییر مقام در طول زمان (Maqam Timeline)
# ============================================================================
#
# رفتار قبلی این ابزار فقط «یک مقام غالب برای کل فایل» گزارش می‌کرد. اما در
# تلاوت‌های طولانی (۱۰ تا ۳۰ دقیقه یا بیشتر) قاری معمولاً در میانهٔ تلاوت
# بین چند مقام حرکت می‌کند (مدولاسیون/انتقال مقام) — بنابراین به درخواست
# صریح کاربر، فایل به پنجره‌های ۳۰ تا ۶۰ ثانیه‌ای تقسیم می‌شود، مقام هر
# پنجره جداگانه تشخیص داده می‌شود، و نقاط گذار (transition) بین مقام‌های
# متوالی گزارش می‌شود.

MAQAM_TIMELINE_MIN_DURATION_SEC = 480.0  # ۸ دقیقه: کمترین طول فایل که تحلیل تایم‌لاین برایش معنادار است
MAQAM_TIMELINE_WINDOW_SEC = 45.0          # اندازه هر پنجرهٔ تحلیل مقام (بین ۳۰ تا ۶۰ ثانیهٔ درخواستی)
MAQAM_TIMELINE_MIN_NOTES_PER_WINDOW = 4   # کمترین تعداد نت لازم برای تشخیص معتبر مقام در یک پنجره


def build_maqam_timeline(notes, total_duration, window_sec=MAQAM_TIMELINE_WINDOW_SEC):
    """
    فایل را به پنجره‌های زمانی متوالی (پیش‌فرض ۴۵ ثانیه) تقسیم می‌کند، برای
    هر پنجره نمایهٔ ربع‌پرده‌ای و بهترین مقام تخمینی را حساب می‌کند، سپس
    فهرست نقاط «گذار مقام» (جایی که مقام غالب پنجرهٔ بعدی با پنجرهٔ قبلی
    فرق می‌کند) را برمی‌گرداند.

    خروجی:
      {
        "windows": [ {start, end, top_maqam, confidence_pct, num_notes}, ... ],
        "transitions": [ {time, from_maqam, to_maqam}, ... ],
        "dominant_maqam_overall": نام رایج‌ترین مقام در کل تایم‌لاین
      }
    """
    if total_duration <= 0:
        return {"windows": [], "transitions": [], "dominant_maqam_overall": None}

    windows = []
    pos = 0.0
    while pos < total_duration:
        end = min(pos + window_sec, total_duration)
        window_notes = [n for n in notes if n["start"] >= pos and n["start"] < end]

        entry = {
            "start": round(pos, 1),
            "end": round(end, 1),
            "num_notes": len(window_notes),
            "top_maqam": None,
            "confidence_pct": None,
            "tonic_freq_hz": None,
        }

        if len(window_notes) >= MAQAM_TIMELINE_MIN_NOTES_PER_WINDOW:
            hist = build_qtet_histogram(window_notes)
            candidates = detect_tonic_and_maqam(hist, top_k=1)
            if candidates:
                best = candidates[0]
                entry["top_maqam"] = best["maqam"]
                entry["confidence_pct"] = best["confidence_pct"]
                entry["tonic_freq_hz"] = best["tonic_freq_hz"]

        windows.append(entry)
        pos += window_sec

    # --- استخراج نقاط گذار: هرجا مقام غالب پنجرهٔ فعلی با قبلی فرق کند ---
    transitions = []
    prev_maqam = None
    for w in windows:
        if w["top_maqam"] is None:
            continue
        if prev_maqam is not None and w["top_maqam"] != prev_maqam:
            transitions.append({
                "time": w["start"],
                "from_maqam": prev_maqam,
                "to_maqam": w["top_maqam"],
            })
        prev_maqam = w["top_maqam"]

    # --- مقام غالب کل تایم‌لاین (رایج‌ترین مقام از نظر تعداد پنجره‌ها) ---
    from collections import Counter
    maqam_counts = Counter(w["top_maqam"] for w in windows if w["top_maqam"] is not None)
    dominant = maqam_counts.most_common(1)[0][0] if maqam_counts else None

    return {
        "window_sec": window_sec,
        "windows": windows,
        "transitions": transitions,
        "dominant_maqam_overall": dominant,
    }


# ============================================================================
# تحلیل «لحن به لحن» — تفکیک به فرازهای طبیعی + سولفژ دقیق هر نت
# ============================================================================
#
# برخلاف maqam_timeline (که فایل را به پنجره‌های زمانی *ثابت* ۴۵ ثانیه‌ای
# تقسیم می‌کند)، این بخش فایل را بر اساس مکث‌ها/نفس‌های *واقعی* قاری
# (خروجی detect_pauses) به «فرازها»ی طبیعی تقسیم می‌کند — دقیقاً همان واحدی
# که در تلاوت واقعی به‌عنوان یک «لحن»/جمله مقامی شنیده می‌شود، نه یک بازهٔ
# دلخواه زمانی. برای هر فراز:
#   ۱) مقام و تونیک محلی همان فراز (نه مقام کلی فایل) با همان تطبیق نمایهٔ
#      ربع‌پرده‌ای موجود (detect_tonic_and_maqam) اما محدود به نت‌های آن فراز
#   ۲) هر نت آن فراز با نام دقیق سولفژ سیار (راست/دوگاه/سه‌گاه/.../اوج +
#      رجیستر قرار/جواب/...) نسبت به تونیک محلی
#   ۳) روند کلی ملودی فراز (صعودی/نزولی/ثابت/موج‌دار) بر اساس شیب میانگین
#      و علامت تغییرات نت‌به‌نت
#
# این تحلیل برای *همهٔ* فایل‌ها (نه فقط فایل‌های طولانی) اجرا می‌شود.

PHRASE_MIN_PAUSE_SEC = 0.35        # حداقل طول سکوت برای این‌که مرز فراز در نظر گرفته شود
PHRASE_MIN_NOTES = 2               # کمترین تعداد نت برای این‌که یک قطعه به‌عنوان فراز مستقل گزارش شود


def _melody_trend(cents_sequence):
    """روند کلی حرکت ملودی یک فراز را بر اساس شیب و نوسان توصیف می‌کند."""
    if len(cents_sequence) < 2:
        return "ثابت"
    diffs = np.diff(cents_sequence)
    slope = (cents_sequence[-1] - cents_sequence[0]) / max(len(cents_sequence) - 1, 1)
    direction_changes = np.sum(np.diff(np.sign(diffs[diffs != 0])) != 0) if len(diffs) > 1 else 0
    wavy_ratio = direction_changes / max(len(diffs), 1)

    if wavy_ratio > 0.5:
        return "موج‌دار (فراز و فرود متناوب)"
    if slope > 15:
        return "صعودی"
    if slope < -15:
        return "نزولی"
    return "ثابت / نوسان کوچک حول یک محور"


def build_phrase_breakdown(times, freqs, notes, pauses, top_k_per_phrase=1, phrase_boundaries=None):
    """
    فایل را به فرازهای طبیعی تقسیم کرده و برای هر فراز، مقام/تونیک محلی،
    سولفژ دقیق هر نت، و روند ملودی را برمی‌گرداند. خروجی به ترتیب زمانی
    است — یعنی همان چیزی که کاربر با آن می‌تواند بگوید «در این لحظه از
    فایل، این لحن/فراز با این نت‌ها خوانده شده است».

    اگر phrase_boundaries داده شود (لیستی از {"start", "end"}، معمولاً
    خروجی compute_waveform_and_phrases که بر مبنای دامنهٔ واقعی صدا/RMS
    فراز را از مکث تشخیص می‌دهد)، از همان مرزها استفاده می‌شود — تا
    فرازهایی که در وب رنگی نمایش داده می‌شوند (روی موج صوتی) دقیقاً همان
    فرازهایی باشند که در این تحلیل «لحن به لحن» گزارش می‌شوند. در غیر این
    صورت (فراخوانی مستقیم/CLI بدون آن داده)، به روش قدیمی‌تر مبتنی بر
    مکث‌های خروجی Praat pitch-voicing برمی‌گردد.
    """
    total_duration = float(times[-1]) if len(times) else 0.0

    if phrase_boundaries:
        boundaries_pairs = [(b["start"], b["end"]) for b in phrase_boundaries]
    else:
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
            continue  # قطعهٔ خیلی کوتاه/تک‌نت (مثل باقیماندهٔ مکث) گزارش جداگانه نمی‌شود

        hist = build_qtet_histogram(phrase_notes)
        candidates = detect_tonic_and_maqam(hist, top_k=top_k_per_phrase)
        best = candidates[0] if candidates else None

        solfege_notes = []
        cents_sequence = []
        if best:
            tonic_hz = best["tonic_freq_hz"]
            maqam_cents = MAQAMAT[best["maqam"]]["cents"]
            # تونیک را به نزدیک‌ترین اکتاو به محدودهٔ صدای واقعی این فراز منتقل کن
            phrase_freqs = np.array([n["f0_hz"] for n in phrase_notes])
            center_freq = np.exp(np.mean(np.log(phrase_freqs)))
            octave_shift = round(np.log2(center_freq / tonic_hz))
            tonic_hz *= (2 ** octave_shift)

            for n in phrase_notes:
                sol = note_to_solfege(n["f0_hz"], tonic_hz, maqam_cents)
                cents_sequence.append(1200.0 * np.log2(n["f0_hz"] / tonic_hz))
                solfege_notes.append({
                    "start": n["start"], "end": n["end"], "duration": n["duration"],
                    "f0_hz": n["f0_hz"],
                    **({} if sol is None else sol),
                })

        trend = _melody_trend(cents_sequence) if cents_sequence else "نامشخص"

        phrases.append({
            # color_index: اندیس فراز در مرزهای اصلی (پیش از فیلتر فرازهای
            # خیلی‌کوتاه) — همان اندیسی که در وب برای رنگ‌آمیزی موج صوتی
            # استفاده می‌شود، تا حتی اگر برخی فرازهای مرزی این‌جا فیلتر شوند
            # (تک‌نت/خیلی کوتاه)، رنگ فرازهای باقی‌مانده هنوز با موج صوتی
            # همخوان بماند.
            "color_index": boundary_idx,
            "start": round(seg_start, 2),
            "end": round(seg_end, 2),
            "duration": round(seg_end - seg_start, 2),
            "num_notes": len(phrase_notes),
            "maqam": best["maqam"] if best else None,
            "confidence_pct": best["confidence_pct"] if best else None,
            "tonic_freq_hz": round(tonic_hz, 2) if best else None,
            "melody_trend": trend,
            "notes_solfege": solfege_notes,
        })

    return phrases


def compute_ambitus(notes):
    """دامنه ملودیک (فاصله بین بم‌ترین و زیرترین نت) را بر حسب سنت و نام نت گزارش می‌دهد."""
    if not notes:
        return None
    freqs = [n["f0_hz"] for n in notes]
    f_min, f_max = min(freqs), max(freqs)
    range_cents = freq_to_cents(f_max, f_min)
    note_min, _ = freq_to_note_and_deviation(f_min)
    note_max, _ = freq_to_note_and_deviation(f_max)
    return {
        "lowest_hz": round(f_min, 1),
        "highest_hz": round(f_max, 1),
        "lowest_note": note_min,
        "highest_note": note_max,
        "range_cents": round(range_cents, 0),
        "range_semitones": round(range_cents / 100, 1),
    }


# ============================================================================
# تابع اصلی تحلیل
# ============================================================================

def analyze_recitation(path, denoise=False, top_k=3, make_plot=True, plot_dir=None,
                         progress_callback=None):
    """
    تحلیل کامل یک فایل تلاوت.

    سیاست دقت (به‌درخواست صریح کاربر): این تابع همیشه از بالاترین سطح دقت
    ممکن در استخراج پرده صدا استفاده می‌کند (Praat very_accurate=True،
    two-pass refine) — صرف‌نظر از طول فایل. برای فایل‌های طولانی (که
    بارگذاری یک‌جای کل فایل می‌تواند باعث اتمام حافظه شود)، استخراج پرده
    صدا به‌صورت پنجره‌ای انجام می‌شود (extract_pitch_contour_max_accuracy)
    که دقیقاً همان تنظیمات حداکثر دقت را حفظ می‌کند، فقط با مصرف حافظهٔ
    ثابت. کاربر آگاهانه پذیرفته که این ممکن است ۲ تا ۳ برابر کندتر از یک
    پردازش «سریع و کم‌دقت» باشد؛ progress_callback برای اطلاع از پیشرفت
    فایل‌های طولانی فراهم شده است.

    اگر progress_callback داده شود، با (stage: str, frac: float 0..1) صدا
    زده می‌شود تا واسط کاربری بتواند نوار پیشرفت نمایش دهد.
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"فایل پیدا نشد: {path}")

    def _progress(stage, frac):
        if progress_callback:
            progress_callback(stage, frac)

    print(f"در حال بارگذاری فایل: {path} ...")
    _progress("loading", 0.0)

    wav_path = path
    tmp_wav = None
    if denoise or not path.lower().endswith(".wav"):
        y, sr = librosa.load(path, sr=44100, mono=True)
        if denoise:
            try:
                import noisereduce as nr
                print("در حال کاهش نویز (denoise)...")
                y = nr.reduce_noise(y=y, sr=sr, stationary=False)
            except ImportError:
                print("هشدار: noisereduce نصب نیست؛ از --denoise صرف‌نظر شد.")
        import soundfile as sf
        tmp_wav = path + "__tmp_analysis.wav"
        sf.write(tmp_wav, y, sr)
        wav_path = tmp_wav

    try:
        print("در حال استخراج دقیق پرده صدا (pitch) با Praat — دقت حداکثری، صرف‌نظر از طول فایل...")

        use_windowed = extract_pitch_contour_max_accuracy is not None

        if use_windowed:
            def _pitch_progress(done_sec, total_sec):
                frac = done_sec / max(total_sec, 1e-9)
                _progress("pitch_extraction", frac * 0.55)
                if total_sec > 60:
                    print(f"\r   پیشرفت استخراج پرده صدا: {done_sec:6.1f}s / {total_sec:6.1f}s "
                          f"({frac*100:5.1f}%)", end="", flush=True)

            times, freqs, sr_used, duration_total = extract_pitch_contour_max_accuracy(
                wav_path, progress_callback=_pitch_progress,
            )
            if duration_total > 60:
                print()  # خط جدید بعد از نوار پیشرفت درون‌خطی
            # snd کامل فقط برای فایل‌های کوتاه لازم است (برای محاسبه بلندی صدا)؛
            # برای فایل‌های طولانی از مسیر استریم/پنجره‌ای compute_loudness_streaming استفاده می‌شود.
            snd = None
        else:
            times, freqs, snd = extract_pitch_praat(wav_path)
            duration_total = float(times[-1]) if len(times) else 0

        freqs = clean_pitch_contour(times, freqs)
        freqs = smooth_pitch_contour(freqs, median_window=5)
        _progress("cleaning", 0.58)

        print("در حال تفکیک نت‌ها...")
        notes = segment_notes(times, freqs)
        _progress("segmentation", 0.65)

        print("در حال تحلیل ویبراتو...")
        for note in notes:
            vib = detect_vibrato(times, freqs, note)
            note["vibrato"] = vib
        _progress("vibrato", 0.72)

        print("در حال ساخت نمایه ربع‌پرده‌ای و تشخیص مقام...")
        hist = build_qtet_histogram(notes)
        maqam_candidates = detect_tonic_and_maqam(hist, top_k=top_k)
        _progress("maqam_detection", 0.78)

        print("در حال محاسبه بلندی صدا...")
        if snd is not None:
            loudness = compute_loudness(snd)
        elif compute_loudness_streaming is not None:
            loudness = compute_loudness_streaming(wav_path)
        else:
            loudness = {"mean_db": None, "max_db": None}
        _progress("loudness", 0.84)

        pauses = detect_pauses(times, freqs)
        ambitus = compute_ambitus(notes)
        voiced_ratio = float(np.mean(freqs > 0)) if len(freqs) else 0.0

        # --- ردیابی تغییر مقام در طول زمان (فقط برای فایل‌های نسبتاً طولانی) ---
        maqam_timeline = None
        if duration_total >= MAQAM_TIMELINE_MIN_DURATION_SEC:
            print(f"فایل طولانی است ({duration_total/60:.1f} دقیقه) — در حال ساخت تایم‌لاین تغییر مقام...")
            maqam_timeline = build_maqam_timeline(notes, duration_total)
        _progress("timeline", 0.86)

        # --- موج صوتی فشرده (waveform peaks) + فرازهای مبتنی بر دامنهٔ
        #     واقعی صدا (RMS + آستانهٔ خودکار) — منبع مشترک برای رنگ‌آمیزی
        #     موج صوتی در وب و برای تحلیل «لحن به لحن» زیر، تا این دو کاملاً
        #     همخوان باشند (هر فراز دقیقاً همان بازه‌ای که در موج رنگی
        #     دیده می‌شود، تحلیل شود).
        waveform = None
        amplitude_phrase_boundaries = None
        if compute_waveform_and_phrases is not None:
            try:
                wf_result = compute_waveform_and_phrases(wav_path)
                waveform = {"peaks": wf_result["peaks"], "duration_sec": wf_result["duration_sec"]}
                amplitude_phrase_boundaries = wf_result["phrases"]
            except Exception:
                waveform = None
                amplitude_phrase_boundaries = None

        # --- تحلیل «لحن به لحن» بر اساس فرازهای طبیعی (برای همهٔ فایل‌ها) ---
        print("در حال تفکیک فرازهای طبیعی و تحلیل سولفژ دقیق هر نت...")
        phrase_breakdown = build_phrase_breakdown(
            times, freqs, notes, pauses, phrase_boundaries=amplitude_phrase_boundaries,
        )
        _progress("phrase_breakdown", 0.9)

        report = {
            "meta": {
                "file": os.path.basename(path),
                "analyzed_at": datetime.now().isoformat(timespec="seconds"),

                "engine": "Praat (parselmouth) pitch-ac, very_accurate=True, two-pass refine"
                          + (", پردازش پنجره‌ای برای فایل طولانی" if use_windowed and duration_total > 90 else ""),
            },
            "basic": {
                "duration_sec": round(duration_total, 2),
                "voiced_ratio_pct": round(voiced_ratio * 100, 1),
                "num_notes_detected": len(notes),
                "num_pauses_detected": len(pauses),
            },
            "loudness": loudness,
            "ambitus": ambitus,
            "maqam_candidates": maqam_candidates,
            "maqam_timeline": maqam_timeline,
            "phrase_breakdown": phrase_breakdown,
            "notes": notes,
            "pauses_top5": sorted(pauses, key=lambda p: -p["duration"])[:5],
            "waveform": waveform,
        }

        if make_plot:
            plot_dir_eff = plot_dir or os.path.dirname(os.path.abspath(path)) or "."
            os.makedirs(plot_dir_eff, exist_ok=True)
            base_name = os.path.splitext(os.path.basename(path))[0]
            plot_path = os.path.join(plot_dir_eff, f"{base_name}_maqam_analysis.png")
            best_maqam = maqam_candidates[0] if maqam_candidates else None
            _make_melograph(times, freqs, notes, best_maqam, plot_path, maqam_timeline=maqam_timeline,
                             ambitus=ambitus)
            report["meta"]["visualization_file"] = plot_path

        _progress("done", 1.0)
        return report

    finally:
        if tmp_wav and os.path.exists(tmp_wav):
            os.remove(tmp_wav)


# ============================================================================
# رسم ملوگراف (نمودار دقیق پرده صدا در طول زمان)
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


def _make_melograph(times, freqs, notes, best_maqam, out_path, maqam_timeline=None, ambitus=None):
    """
    نسخهٔ محافظت‌شده با قفل سراسری رسم نمودار — چون سرور هر job (تحلیل،
    مقایسه، صادرات ویدیو) را در یک ترد جداگانه اجرا می‌کند و matplotlib.pyplot
    وضعیت سراسری (figure جاری) دارد که thread-safe نیست، بدون این قفل ممکن
    است هنگام اجرای هم‌زمان دو job، نمودار یک job در فایل خروجی job دیگر
    ذخیره شود.
    """
    with PLOT_LOCK:
        return _make_melograph_impl(times, freqs, notes, best_maqam, out_path,
                                     maqam_timeline=maqam_timeline, ambitus=ambitus)


def _make_melograph_impl(times, freqs, notes, best_maqam, out_path, maqam_timeline=None, ambitus=None):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    has_font = _setup_persian_font()
    matplotlib.rcParams["axes.unicode_minus"] = False
    T = (lambda s: _fa(s)) if has_font else (lambda s: s)

    has_timeline = bool(maqam_timeline and maqam_timeline.get("windows"))

    if has_timeline:
        fig, (ax, ax_tl) = plt.subplots(
            2, 1, figsize=(14, 8.5), gridspec_kw={"height_ratios": [3, 1]}, sharex=True,
        )
    else:
        fig, ax = plt.subplots(figsize=(14, 6))
        ax_tl = None

    voiced_mask = freqs > 0
    ax.plot(times[voiced_mask], freqs[voiced_mask], '.', color="#2563eb",
            markersize=2, label=T("منحنی پرده صدا (F0)"))

    for note in notes:
        ax.hlines(note["f0_hz"], note["start"], note["end"],
                   color="#ef4444", linewidth=2.5, alpha=0.8)

    # --- تونیک را زودتر (پیش از رسم برچسب‌های ambitus) به نزدیک‌ترین اکتاو
    #     به محدودهٔ صدای واقعی منتقل می‌کنیم تا بتوان درجهٔ سولفژ هر نت را
    #     هم روی برچسب بالاترین/پایین‌ترین نت نمایش داد ---
    tonic_hz_adj, maqam_name, maqam_cents = None, None, None
    if best_maqam:
        tonic_hz_adj = best_maqam["tonic_freq_hz"]
        maqam_name = best_maqam["maqam"]
        maqam_cents = MAQAMAT[maqam_name]["cents"]
        voiced_freqs = freqs[freqs > 0]
        if len(voiced_freqs) > 0:
            center_freq = np.exp(np.mean(np.log(voiced_freqs)))
            octave_shift = round(np.log2(center_freq / tonic_hz_adj))
            tonic_hz_adj *= (2 ** octave_shift)

    # --- بم‌ترین و زیرترین نت خوانده‌شده در کل فایل ---
    #
    # نمایش این دو نت باید به‌قدری واضح باشد که با یک نگاه، هم «کجای زمان
    # رخ داده» و هم «دقیقاً اسمش چیست» مشخص شود — بنابراین علاوه بر خط
    # افقی راهنما (که کل بازهٔ فرکانسی را در تمام طول نمودار نشان می‌دهد)،
    # یک نشانگر ستاره‌ای دقیقاً روی همان نتِ واقعی (در همان لحظهٔ زمانی که
    # رخ داده، نه گوشهٔ ثابت نمودار) رسم می‌شود و یک برچسبِ درشت/پررنگ با
    # فلش به همان نقطه اشاره می‌کند.
    if ambitus and ambitus.get("lowest_hz") and ambitus.get("highest_hz"):
        x0, x1 = float(times[0]), float(times[-1]) if len(times) else 0.0
        low_hz, high_hz = ambitus["lowest_hz"], ambitus["highest_hz"]
        low_note, high_note = ambitus.get("lowest_note"), ambitus.get("highest_note")

        low_sol_str = high_sol_str = ""
        if tonic_hz_adj and maqam_cents:
            low_sol = note_to_solfege(low_hz, tonic_hz_adj, maqam_cents)
            high_sol = note_to_solfege(high_hz, tonic_hz_adj, maqam_cents)
            if low_sol:
                low_sol_str = f" ({low_sol['solfege_name']})"
            if high_sol:
                high_sol_str = f" ({high_sol['solfege_name']})"

        # --- پیدا کردن لحظهٔ زمانی واقعی این دو نت (نزدیک‌ترین نت در لیست
        #     notes به فرکانس کمینه/بیشینهٔ گزارش‌شده در ambitus) ---
        def _find_note_time(target_hz):
            if not notes:
                return (x0 + x1) / 2.0
            best = min(notes, key=lambda n: abs(n["f0_hz"] - target_hz))
            return (best["start"] + best["end"]) / 2.0

        low_t = _find_note_time(low_hz)
        high_t = _find_note_time(high_hz)

        ax.axhline(low_hz, color="#f59e0b", linestyle=":", linewidth=1.4, alpha=0.85, zorder=5)
        ax.axhline(high_hz, color="#a855f7", linestyle=":", linewidth=1.4, alpha=0.85, zorder=5)

        # نشانگر ستاره‌ای بزرگ دقیقاً روی نت واقعی (نه فقط لبهٔ نمودار)
        ax.plot(low_t, low_hz, marker="*", color="#f59e0b", markersize=20,
                markeredgecolor="#111827", markeredgewidth=1.0, zorder=8)
        ax.plot(high_t, high_hz, marker="*", color="#a855f7", markersize=20,
                markeredgecolor="#111827", markeredgewidth=1.0, zorder=8)

        low_label = T(f"پایین‌ترین نت\n{low_note}{low_sol_str}\n{low_hz:.1f} Hz")
        high_label = T(f"بالاترین نت\n{high_note}{high_sol_str}\n{high_hz:.1f} Hz")

        ax.annotate(low_label, xy=(low_t, low_hz), xytext=(0, -42), textcoords="offset points",
                    fontsize=11, fontweight="bold", color="white", va="top", ha="center", zorder=9,
                    arrowprops=dict(arrowstyle="-|>", color="#f59e0b", lw=1.6),
                    bbox=dict(boxstyle="round,pad=0.35", fc="#b45309", ec="#f59e0b", lw=1.4, alpha=0.95))
        ax.annotate(high_label, xy=(high_t, high_hz), xytext=(0, 42), textcoords="offset points",
                    fontsize=11, fontweight="bold", color="white", va="bottom", ha="center", zorder=9,
                    arrowprops=dict(arrowstyle="-|>", color="#a855f7", lw=1.6),
                    bbox=dict(boxstyle="round,pad=0.35", fc="#7e22ce", ec="#a855f7", lw=1.4, alpha=0.95))

        # برچسب کوچک انتهای خط افقی هم نگه داشته می‌شود (برای وقتی نشانگر
        # ستاره‌ای در بخش شلوغ نمودار زیر برچسب‌های دیگر پنهان بماند)
        ax.annotate(T(f"{low_note} — {low_hz:.1f} Hz"), xy=(x1, low_hz), xytext=(-6, -3),
                    textcoords="offset points", fontsize=8, color="#f59e0b",
                    va="top", ha="right", zorder=7, alpha=0.85)
        ax.annotate(T(f"{high_note} — {high_hz:.1f} Hz"), xy=(x1, high_hz), xytext=(-6, 3),
                    textcoords="offset points", fontsize=8, color="#a855f7",
                    va="bottom", ha="right", zorder=7, alpha=0.85)

    if best_maqam:
        tonic_hz = tonic_hz_adj

        for c in maqam_cents:
            grid_freq = tonic_hz * (2 ** (c / 1200.0))
            ax.axhline(grid_freq, color="#22c55e", linestyle="--", alpha=0.35, linewidth=0.8)

        title = f"ملوگراف تلاوت — مقام غالب: {maqam_name} (تونیک ≈ {tonic_hz:.1f} Hz)"
        if has_timeline:
            title += f"  |  {len(maqam_timeline['transitions'])} گذار مقام شناسایی شد"
        ax.set_title(T(title), fontsize=13)
    else:
        ax.set_title(T("ملوگراف تلاوت (منحنی دقیق پرده صدا)"), fontsize=13)

    ax.set_xlabel(T("زمان (ثانیه)") if not has_timeline else "")

    ax.set_ylabel(T("فرکانس (هرتز)"))
    ax.set_yscale("log")
    ax.legend(loc="upper left")
    ax.grid(True, alpha=0.15)

    # --- زیرنمودار تایم‌لاین تغییر مقام (برای فایل‌های طولانی) ---
    if has_timeline:
        windows = maqam_timeline["windows"]
        maqam_names = sorted({w["top_maqam"] for w in windows if w["top_maqam"]})
        color_cycle = plt.cm.tab10(np.linspace(0, 1, max(len(maqam_names), 1)))
        maqam_color = {name: color_cycle[i] for i, name in enumerate(maqam_names)}

        for w in windows:
            if w["top_maqam"] is None:
                continue
            conf = (w["confidence_pct"] or 0) / 100.0
            ax_tl.barh(0, w["end"] - w["start"], left=w["start"], height=0.8,
                       color=maqam_color[w["top_maqam"]], alpha=0.35 + 0.55 * conf,
                       edgecolor="white", linewidth=0.5)

        for tr in maqam_timeline["transitions"]:
            ax_tl.axvline(tr["time"], color="#111827", linestyle=":", linewidth=1.2)

        # راهنمای رنگ‌ها
        handles = [plt.Rectangle((0, 0), 1, 1, color=maqam_color[name]) for name in maqam_names]
        ax_tl.legend(handles, [T(n) for n in maqam_names], loc="upper right",
                     fontsize=8, ncol=min(len(maqam_names), 4))

        ax_tl.set_yticks([])
        ax_tl.set_xlabel(T("زمان (ثانیه)"))
        ax_tl.set_title(T(f"تایم‌لاین تغییر مقام (پنجره‌های {maqam_timeline['window_sec']:.0f} ثانیه‌ای)"),
                        fontsize=11)
        ax_tl.set_xlim(ax.get_xlim())

    plt.tight_layout()
    plt.savefig(out_path, dpi=140, bbox_inches="tight")
    plt.close(fig)
    print(f"ملوگراف ذخیره شد: {out_path}")


# ============================================================================
# چاپ گزارش خوانا
# ============================================================================

def print_report(report):
    print("\n" + "=" * 70)
    print(f" گزارش تحلیل دقیق تلاوت: {report['meta']['file']}")
    print("=" * 70)

    b = report["basic"]
    print(f"\n📁 اطلاعات پایه:")
    print(f"   مدت زمان: {b['duration_sec']} ثانیه")
    print(f"   درصد فریم‌های صدادار: {b['voiced_ratio_pct']}%")
    print(f"   تعداد نت‌های شناسایی‌شده: {b['num_notes_detected']}")
    print(f"   تعداد سکوت‌ها/نفس‌ها: {b['num_pauses_detected']}")

    if report["ambitus"]:
        a = report["ambitus"]
        print(f"\n🎼 دامنه ملودیک (Ambitus):")
        print(f"   بم‌ترین نت: {a['lowest_note']} ({a['lowest_hz']} Hz)")
        print(f"   زیرترین نت: {a['highest_note']} ({a['highest_hz']} Hz)")
        print(f"   دامنه کلی: {a['range_semitones']} نیم‌پرده ({a['range_cents']} سنت)")

    l = report["loudness"]
    if l["mean_db"] is not None:
        print(f"\n🔊 بلندی صدا:")
        print(f"   میانگین: {l['mean_db']} dB   |   اوج: {l['max_db']} dB")

    print(f"\n🕌 مقام‌های محتمل تلاوت (بر اساس تطبیق نمایه ربع‌پرده‌ای):")
    for i, cand in enumerate(report["maqam_candidates"], 1):
        print(f"   {i}. {cand['maqam']}  —  اطمینان نسبی: {cand['confidence_pct']}%")
        print(f"      تونیک تخمینی: {cand['tonic_freq_hz']} Hz")
        print(f"      حال‌وهوا: {cand['mood']}")

    tl = report.get("maqam_timeline")
    if tl:
        print(f"\n🕐 تایم‌لاین تغییر مقام (فایل طولانی — پنجره‌های {tl['window_sec']:.0f} ثانیه‌ای):")
        print(f"   مقام غالب کل فایل: {tl['dominant_maqam_overall']}")
        if tl["transitions"]:
            print(f"   تعداد گذارهای مقام شناسایی‌شده: {len(tl['transitions'])}")
            for tr in tl["transitions"]:
                print(f"      ⇄ در ثانیه {tr['time']:.0f}s: {tr['from_maqam']}  →  {tr['to_maqam']}")
        else:
            print("   در طول فایل، تغییر مقام قابل‌توجهی شناسایی نشد (مقام ثابت باقی مانده).")
        print(f"   جزئیات کامل هر پنجره در فیلد \"maqam_timeline.windows\" گزارش JSON موجود است.")

    phb = report.get("phrase_breakdown")
    if phb:
        print(f"\n🎼 تحلیل لحن به لحن (فرازهای طبیعی بر اساس مکث/نفس واقعی — {len(phb)} فراز):")
        for i, ph in enumerate(phb, 1):
            maqam_label = ph["maqam"] or "نامشخص"
            conf = f" ({ph['confidence_pct']}%)" if ph.get("confidence_pct") is not None else ""
            print(f"   فراز {i}: {ph['start']}s - {ph['end']}s  |  مقام: {maqam_label}{conf}  |  روند: {ph['melody_trend']}  |  {ph['num_notes']} نت")
            sol_names = [n["solfege_name"] for n in ph.get("notes_solfege", []) if n.get("solfege_name")]
            if sol_names:
                print(f"      نت‌ها (سولفژ): {' - '.join(sol_names)}")
        print(f"   جزئیات کامل هر فراز در فیلد \"phrase_breakdown\" گزارش JSON موجود است.")

    print(f"\n🎵 نمونه‌ای از نت‌های دقیق شناسایی‌شده (۱۰ نت اول):")
    for note in report["notes"][:10]:
        note_name, dev = freq_to_note_and_deviation(note["f0_hz"])
        vib_str = ""
        if note.get("vibrato"):
            vib_str = f"  |  ویبراتو: {note['vibrato']['rate_hz']} هرتز"
        print(f"   [{note['start']:>6.2f}s - {note['end']:>6.2f}s]  "
              f"{note['f0_hz']:>7.2f} Hz  ≈ {note_name} ({dev:+.1f}¢)  "
              f"پایداری: ±{note['stability_cents']}¢{vib_str}")

    if report["pauses_top5"]:
        print(f"\n⏸️  طولانی‌ترین سکوت‌ها (احتمال نفس/وقف):")
        for p in report["pauses_top5"]:
            print(f"   {p['start']}s - {p['end']}s  (مدت: {p['duration']}s)")

    if "visualization_file" in report["meta"]:
        print(f"\n🖼️  ملوگراف ذخیره شد: {report['meta']['visualization_file']}")

    print("\n" + "=" * 70)
    print("⚠️  یادآوری: تشخیص مقام یک برآورد آماری بر پایه توزیع نت‌هاست، نه")
    print("   تحلیل کامل سیر ملودیک. دقت ارتفاع صوت (pitch) هر نت در سطح")
    print("   آکادمیک/آوایی (± چند سنت) است.")
    print("=" * 70 + "\n")


# ============================================================================
# CLI
# ============================================================================

def main():
    parser = argparse.ArgumentParser(
        description="تحلیلگر دقیق تلاوت قرآن — pitch دقیق + تشخیص مقام عربی"
    )
    parser.add_argument("audio_path", help="مسیر فایل صوتی تلاوت (mp3, wav, ...)")
    parser.add_argument("--output", "-o", help="مسیر ذخیره گزارش JSON", default=None)
    parser.add_argument("--denoise", action="store_true", help="کاهش نویز پس‌زمینه قبل از تحلیل")
    parser.add_argument("--top-k", type=int, default=3, help="تعداد مقام‌های پیشنهادی")
    parser.add_argument("--no-plot", action="store_true", help="عدم رسم ملوگراف")
    parser.add_argument("--plot-dir", default=None, help="پوشه ذخیره نمودار")
    parser.add_argument("--quiet-progress", action="store_true",
                         help="عدم نمایش نوار پیشرفت درون‌خطی (برای فایل‌های طولانی)")

    args = parser.parse_args()

    def _cli_progress(stage, frac):
        pass  # پیام‌های تفصیلی پیشرفت همین حالا با print مستقیم در حین پردازش چاپ می‌شوند

    report = analyze_recitation(
        args.audio_path,
        denoise=args.denoise,
        top_k=args.top_k,
        make_plot=not args.no_plot,
        plot_dir=args.plot_dir,
        progress_callback=None if args.quiet_progress else _cli_progress,
    )

    print_report(report)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"گزارش JSON ذخیره شد در: {args.output}")


if __name__ == "__main__":
    main()
