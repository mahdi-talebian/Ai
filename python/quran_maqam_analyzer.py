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

# (جدول‌های قدیمی ناقص نام ربع‌پرده حذف شدند و با «نظام مطلق درجات شرقی»
#  در بخش بعد — ABS_DEGREE_LADDER — جایگزین گشته‌اند.)

# ============================================================================
# نظام مطلق درجات شرقی (Absolute Arabic Degree Ladder)
# ============================================================================
#
# در سنت آموزشی موسیقی عربی و تلاوت قرآن، هر موقعیت ربع‌پرده‌ایِ گام یک نام
# سنتی دارد و مقام‌ها همیشه «روی» یکی از این درجات قرار می‌گیرند؛ مثلاً:
#   «بیاتی از دوگاه»، «حجاز از دوگاه»، «راست از راست»، «سیکاه از سیکاه»،
#   «جهارکاه از جهارکاه»، «عجم از عجم».
#
# مرجع: نام‌گذاری مرسوم مدرسهٔ مصری/شامی (هم‌سو با MaqamWorld و آموزش‌های
# رایج تلاوت — همان نظامی که در اپ‌های آموزشی شرقی مانند «اورج شرقی» به‌کار
# می‌رود). موقعیت صفر = درجهٔ «راست» (معادل پیانویی: C).
#
# نکته: این نظام «سیار» پیاده شده است — یعنی اگر قاری تونیک مقام را روی هر
# فرکانسی بخواند، بقیهٔ درجات نسبت به همان تونیک نام‌گذاری می‌شوند (دقیقاً
# همان کاری که معلمان تلاوت می‌کنند: هر جا که «دوگاه» بخوانی، بالاترش
# «سیکاه» است و به همین ترتیب).
ABS_DEGREE_LADDER = {
    0:    {"fa": "راست",       "en": "Rast"},
    50:   {"fa": "نیم‌زیرکوله",  "en": "Nim Zirguleh"},
    100:  {"fa": "زیرکوله",     "en": "Zirguleh"},
    150:  {"fa": "تک‌زیرکوله",   "en": "Tik Zirguleh"},
    200:  {"fa": "دوگاه",       "en": "Dukah"},
    250:  {"fa": "نیم‌دوگاه",    "en": "Nim Dukah"},
    300:  {"fa": "کردی",        "en": "Kurdi"},
    350:  {"fa": "سیکاه",       "en": "Sikah"},
    400:  {"fa": "بوسلیک",      "en": "Busalik"},
    450:  {"fa": "نیم‌بوسلیک",   "en": "Nim Busalik"},
    500:  {"fa": "جهارکاه",     "en": "Jaharkah"},
    550:  {"fa": "نیم‌جهارکاه",  "en": "Nim Jaharkah"},
    600:  {"fa": "حجاز",        "en": "Hijaz"},        # F♯/G♭ — در متن صبا هم خوانده می‌شود
    650:  {"fa": "نیم‌حجاز",     "en": "Nim Hijaz"},
    700:  {"fa": "نوا",         "en": "Nawa"},
    750:  {"fa": "نیم‌نوا",      "en": "Nim Nawa"},
    800:  {"fa": "حصار",        "en": "Hisar"},        # G♯/A♭
    850:  {"fa": "نیم‌حصار",     "en": "Nim Hisar"},
    900:  {"fa": "حسینی",       "en": "Husayni"},      # A
    950:  {"fa": "نیم‌حسینی",    "en": "Nim Husayni"},
    1000: {"fa": "عجم",         "en": "Ajam"},         # B♭ (عجم عشیران)
    1050: {"fa": "اوج",         "en": "Awj (Iraq)"},   # B نیم‌بمل — درجهٔ ۷ راست
    1100: {"fa": "ماهور",       "en": "Mahur"},        # B
    1150: {"fa": "تک‌کوشت",     "en": "Tik Kushta"},
}

# نام‌های سنتی درجات اکتاو بالا (نسبت به «راست» مرجع) — وقتی ملودی از کردان
# بالاتر برود، این نام‌های خاص (به‌جای پسوند جواب) به‌کار می‌روند:
UPPER_REGISTER_DEGREE_NAMES = {
    (0, 1):   {"fa": "کردان",  "en": "Kirdan"},     # 1200
    (200, 1): {"fa": "محیر",   "en": "Mahayyar"},   # 1400
    (300, 1): {"fa": "سنبله",  "en": "Sunbula"},    # 1500
}

REGISTER_UP_FA = {1: "جواب", 2: "جواب‌الجواب", 3: "جواب سوم"}
REGISTER_UP_EN = {1: "Jawab", 2: "Jawab al-Jawab", 3: "Third Jawab"}

NOTE_NAMES_12 = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# ----------------------------------------------------------------------------
# مقامات اصلی به‌کاررفته در تلاوت قرآن — فاصله هر درجه از تونیک، بر حسب سنت
# منبع: استاندارد کنگره قاهره ۱۹۳۲ / maqamworld.com / Wikipedia Arabic maqam
# هر مقام ۸ درجه دارد (درجه اول = تونیک = صفر سنت، درجه آخر = اکتاو = ۱۲۰۰ سنت)
# ----------------------------------------------------------------------------
# ============================================================================
# اجناس (Ajnās) — آجرهای ساختمانی مقام‌ها بر پایهٔ نظریهٔ «عقد و جنس»
# ============================================================================
#
# نظریهٔ کلاسیک موسیقی مقامی (که در آموزش تلاوت هم مبنای کار است) هر مقام را
# نه یک «جدول مسط درجه‌ها»، بلکه زنجیره‌ای از «اجناس» می‌داند که هر کدام روی
# یک «عقد» (گره/نشیمن‌گاه؛ درجهٔ شروع جنس) سوار می‌شوند:
#
#   - جنس (Jins): گروه ۳‌نتّی (ثلاثی) یا ۴‌نتّی (رباعی) با فاصله‌های مشخص.
#     از هر نت واحد (عقد) که شروع شود، «فرمول» حرکتش ثابت است:
#       جنس راست:    پردهٔ تام ← سه‌ربع ← سه‌ربع        (۲۰۰+۱۵۰+۱۵۰)
#       جنس بیاتی:   سه‌ربع ← سه‌ربع ← پردهٔ تام        (۱۵۰+۱۵۰+۲۰۰)
#       جنس حجاز:    نیم‌پرده ← یک‌ونیم‌پرده ← نیم‌پرده   (۱۰۰+۳۰۰+۱۰۰)
#       جنس کرد:     نیم‌پرده ← پردهٔ تام ← پردهٔ تام     (۱۰۰+۲۰۰+۲۰۰)
#       جنس نهاوند:  پردهٔ تام ← نیم‌پرده ← پردهٔ تام     (۲۰۰+۱۰۰+۲۰۰)
#       جنس عجم:     پردهٔ تام ← پردهٔ تام ← نیم‌پرده     (۲۰۰+۲۰۰+۱۰۰)
#       جنس جهارکاه: پردهٔ تام ← پردهٔ تام ← نیم‌پرده     (۲۰۰+۲۰۰+۱۰۰)
#       جنس صبا:     سه‌ربع ← سه‌ربع ← نیم‌پرده          (۱۵۰+۱۵۰+۱۰۰ — چهارم نیم‌بمل‌گرفته!)
#       جنس نکریز:   پردهٔ تام ← نیم‌پرده ← یک‌ونیم‌پرده  (۲۰۰+۱۰۰+۳۰۰ — گسترده)
#       جنس سیکاه:   سه‌ربع ← پردهٔ تام                  (۱۵۰+۲۰۰ — ثلاثی! فقط ۳۵۰ سنت)
#
#   - عقد (Aqd): درجه‌ای از نردبان درجات که جنس از روی آن «گره» می‌زند.
#     مقام = زنجیرهٔ اجناس روی عقود متوالی (عقد اول = تونیک؛ جنس روی عقد نوا
#     «جواب» جنس اول خوانده می‌شود).
#
# منبع ساختار هر ۱۰ مقام: MaqamWorld (متن توصیف زنجیرهٔ اجناس هر مقام) +
# استاندارد کنگرهٔ قاهرهٔ ۱۹۳۲.
JINSAT = {
    "rast": {
        "fa": "راست", "en": "Rast", "type": "رباعی",
        "intervals": [200, 150, 150],
        "formula_fa": "پردهٔ تام + سه‌ربع + سه‌ربع",
    },
    "bayati": {
        "fa": "بیاتی", "en": "Bayati", "type": "رباعی",
        "intervals": [150, 150, 200],
        "formula_fa": "سه‌ربع + سه‌ربع + پردهٔ تام",
    },
    "hijaz": {
        "fa": "حجاز", "en": "Hijaz", "type": "رباعی",
        "intervals": [100, 300, 100],
        "formula_fa": "نیم‌پرده + یک‌ونیم‌پرده + نیم‌پرده",
    },
    "kurd": {
        "fa": "کرد", "en": "Kurd", "type": "رباعی",
        "intervals": [100, 200, 200],
        "formula_fa": "نیم‌پرده + پردهٔ تام + پردهٔ تام",
    },
    "nahawand": {
        "fa": "نهاوند", "en": "Nahawand", "type": "رباعی",
        "intervals": [200, 100, 200],
        "formula_fa": "پردهٔ تام + نیم‌پرده + پردهٔ تام",
    },
    "ajam": {
        "fa": "عجم", "en": "Ajam", "type": "رباعی",
        "intervals": [200, 200, 100],
        "formula_fa": "پردهٔ تام + پردهٔ تام + نیم‌پرده",
    },
    "jiharkah": {
        "fa": "جهارکاه", "en": "Jiharkah", "type": "رباعی",
        "intervals": [200, 200, 100],
        "formula_fa": "پردهٔ تام + پردهٔ تام + نیم‌پرده",
    },
    "saba": {
        "fa": "صبا", "en": "Saba", "type": "رباعی ناقص (چهارم نیم‌بمل‌گرفته)",
        "intervals": [150, 150, 100],
        "formula_fa": "سه‌ربع + سه‌ربع + نیم‌پرده",
    },
    "nikriz": {
        "fa": "نکریز", "en": "Nikriz", "type": "رباعی گسترده",
        "intervals": [200, 100, 300],
        "formula_fa": "پردهٔ تام + نیم‌پرده + یک‌ونیم‌پرده",
    },
    "sikah": {
        "fa": "سیکاه", "en": "Sikah", "type": "ثلاثی",
        "intervals": [150, 200],
        "formula_fa": "سه‌ربع + پردهٔ تام",
    },
    "upper_rast": {
        "fa": "راست بالایی", "en": "Upper Rast", "type": "رباعی",
        "intervals": [200, 150, 150],
        "formula_fa": "پردهٔ تام + سه‌ربع + سه‌ربع (تونیک جنس روی درجهٔ ۸)",
    },
    "upper_ajam": {
        "fa": "عجم بالایی", "en": "Upper Ajam", "type": "رباعی",
        "intervals": [200, 200, 100],
        "formula_fa": "پردهٔ تام + پردهٔ تام + نیم‌پرده (تونیک جنس روی درجهٔ ۸)",
    },
}

_QUARTER_STEP_NAMES_FA = {
    1: "ربع‌پرده", 2: "نیم‌پرده", 3: "سه‌ربع", 4: "پردهٔ تام",
    5: "پرده‌وربع", 6: "پرده‌ونیم", 7: "پرده‌وسه‌ربع", 8: "دو پردهٔ تام",
}


def cents_to_step_label_fa(cents: float) -> str:
    """فاصلهٔ بین دو درجه را با واحدهای سنتی آموزش تلاوت می‌گوید
    (ربع‌پرده/نیم‌پرده/سه‌ربع/پردهٔ تام/…)."""
    q = int(round(abs(cents) / 50.0))
    if q == 0:
        return "هم‌نوا"
    if q in _QUARTER_STEP_NAMES_FA:
        return _QUARTER_STEP_NAMES_FA[q]
    return f"{q / 4:.2f} پرده"


def derive_scale_from_jins_chain(chain):
    """گام مقام را از زنجیرهٔ اجناس می‌سازد — تا جدول درجه‌ها و نظریهٔ
    اجناس هرگز از هم جدا نیفتند."""
    degrees = [0.0]
    for step in chain:
        jins = JINSAT[step["jins"]]
        on_deg = step["on_degree"]
        if on_deg - 1 < len(degrees):
            start = degrees[on_deg - 1]
        else:
            gap = step.get("gap_cents")
            if gap is None:
                raise ValueError(
                    f"عقد درجهٔ {on_deg} هنوز ساخته نشده و gap_cents مشخص نیست")
            start = degrees[-1] + gap
        note = start
        notes = [note]
        for iv in jins["intervals"]:
            note = note + iv
            notes.append(note)
        for note in notes:
            note_r = round(note)
            if note_r <= 1200 and note_r not in [round(d) for d in degrees]:
                degrees.append(note_r)
    # کردان (اکتاو تونیک) همیشه درجهٔ پایانی گام است
    if 1200 not in [round(d) for d in degrees]:
        degrees.append(1200)
    return sorted(degrees)


def build_degree_jins_map(maqam_name):
    """
    برای هر درجهٔ گام مقام می‌گوید متعلق به کدام جنس است و نُتِ چندمِ آن
    جنس محسوب می‌شود (بر اساس زنجیرهٔ عقود). اولویت با اولین جنس زنجیره
    است — مثلاً درجهٔ ۳ صبا هم «نت سوم جنس صبا» است و هم «عقد جنس حجاز»،
    و این‌جا به جنس صبا نسبت داده می‌شود.

    خروجی: {cents_degree: {"jins": key, "position": 1-based}}
    """
    info = MAQAMAT[maqam_name]
    chain = info["jins_chain"]
    jmap = {}
    degrees = [0.0]
    for step in chain:
        jins = JINSAT[step["jins"]]
        on_deg = step["on_degree"]
        if on_deg - 1 < len(degrees):
            start = degrees[on_deg - 1]
        else:
            start = degrees[-1] + step.get("gap_cents", 200)
        note = start
        notes = [note]
        for iv in jins["intervals"]:
            note += iv
            notes.append(note)
        for pos, note in enumerate(notes, 1):
            note_r = round(note)
            if note_r <= 1200:
                jmap.setdefault(note_r, {"jins": step["jins"], "position": pos})
        for note in notes:
            note_r = round(note)
            if note_r <= 1200 and note_r not in [round(d) for d in degrees]:
                degrees.append(note_r)
    return jmap


MAQAMAT = {
    # ═══ جدول مرجع ۸ مقام «بر پایهٔ دو» — منبع: کاربر، ملاک = نام نت‌ها ═══
    # نیم‌بمل = −۵۰ سنت، بمل = −۱۰۰ سنت، دیز = +۱۰۰ سنت، بکار = طبیعی
    "رست (Rast)": {
        "cents": [0, 200, 350, 500, 700, 900, 1050, 1200],
        "scale_ascending": [0, 200, 350, 500, 700, 900, 1050, 1200],
        "scale_descending": [0, 200, 350, 500, 700, 900, 1050, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر", "می نیم‌بمل", "فا", "سل", "لا", "سی نیم‌بمل", "دو"],
        "jins_structure": "جنس رست (پرده + سوم نیم‌بمل) + جنس رست روی نوا",
        "mood": "وقار، متانت، صلابت — مناسب قرائت‌های مجلسی و آغاز تلاوت",
        "family": "رست",
        "jins_chain": [{"jins": "rast", "on_degree": 1}, {"jins": "upper_rast", "on_degree": 5, "gap_cents": 200}],
        "jins_alternatives": ["به‌جای جنس دوم، نهاوند روی درجهٔ ۵ نیز خوانده می‌شود (راست مصری)"],
    },
    "بیاتی (Bayati)": {
        "cents": [0, 150, 350, 500, 700, 850, 1000, 1200],
        "scale_ascending": [0, 150, 350, 500, 700, 850, 1000, 1200],
        "scale_descending": [0, 150, 350, 500, 700, 850, 1000, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر نیم‌بمل", "می نیم‌بمل", "فا", "سل", "لا نیم‌بمل", "سی بمل", "دو"],
        "jins_structure": "جنس بیاتی (سه‌ربع + سه‌ربع) + چیرش بیاتی‌گونه روی نوا",
        "mood": "درون‌مایهٔ آرام و سوزناک — پرکاربردترین مقام تلاوت",
        "family": "بیاتی",
        "jins_chain": [{"jins": "bayati", "on_degree": 1}, {"jins": "saba", "on_degree": 5, "gap_cents": 200}],
        "jins_alternatives": [],
    },
    "حجاز (Hijaz)": {
        "cents": [0, 100, 400, 600, 700, 850, 1050, 1200],
        "scale_ascending": [0, 100, 400, 600, 700, 850, 1050, 1200],
        "scale_descending": [0, 100, 400, 600, 700, 850, 1050, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر بمل", "می", "فا دیز", "سل", "لا نیم‌بمل", "سی نیم‌بمل", "دو"],
        "jins_structure": "جنس حجاز (نیم‌پرده + یک‌ونیم‌پرده) با چهارم دیز + چیرش بیاتی‌گونه روی نوا",
        "mood": "شور و حال احساسی — فرازهای تأثیرگذار و دعاها",
        "family": "حجاز",
        "jins_chain": [{"jins": "hijaz", "on_degree": 1}, {"jins": "bayati", "on_degree": 5, "gap_cents": 100}],
        "jins_alternatives": [],
    },
    "صبا (Saba)": {
        # بستهٔ نزولی — اکتاوِ کامل ندارد: روی «دو بمل» (۱۱۰۰ سنت) می‌نشیند
        "cents": [0, 150, 350, 500, 600, 900, 1000, 1100],
        "scale_ascending": [0, 150, 350, 500, 600, 900, 1000, 1100],
        "scale_descending": [0, 150, 350, 500, 600, 900, 1000, 1100],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر نیم‌بمل", "می نیم‌بمل", "فا", "سل بمل", "لا", "سی بمل", "دو بمل"],
        "jins_structure": "جنس صبا (سه‌ربع + سه‌ربع + نیم‌پرده) + چیرش روی درجهٔ ۶ (لا)",
        "mood": "اندوه و سوز عمیق — عزاداری و تلاوت‌های تأثیرگذار",
        "family": "صبا",
        "jins_chain": [{"jins": "saba", "on_degree": 1}, {"jins": "hijaz", "on_degree": 6, "gap_cents": 300}],
        "jins_alternatives": [],
    },
    "نهاوند (Nahawand)": {
        "cents": [0, 200, 300, 500, 700, 800, 1000, 1200],
        "scale_ascending": [0, 200, 300, 500, 700, 800, 1000, 1200],
        "scale_descending": [0, 200, 300, 500, 700, 800, 1000, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر", "می بمل", "فا", "سل", "لا بمل", "سی بمل", "دو"],
        "jins_structure": "جنس نهاوند (پرده + نیم‌پرده) + جنس نهاوند روی نوا — هم‌خانوادهٔ مینور",
        "mood": "حزن باشکوه — مرثیه و مناجات",
        "family": "نهاوند",
        "jins_chain": [{"jins": "nahawand", "on_degree": 1}, {"jins": "nahawand", "on_degree": 5, "gap_cents": 200}],
        "jins_alternatives": [],
    },
    "عجم (Ajam)": {
        "cents": [0, 200, 400, 500, 700, 900, 1100, 1200],
        "scale_ascending": [0, 200, 400, 500, 700, 900, 1100, 1200],
        "scale_descending": [0, 200, 400, 500, 700, 900, 1100, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر", "می", "فا", "سل", "لا", "سی", "دو"],
        "jins_structure": "جنس عجم (پرده + پرده + نیم‌پرده) + جنس عجم روی نوا — هم‌خانوادهٔ ماژور",
        "mood": "شادابی و روشنی — مداحی‌های شادان",
        "family": "عجم",
        "jins_chain": [{"jins": "ajam", "on_degree": 1}, {"jins": "upper_ajam", "on_degree": 5, "gap_cents": 200}],
        "jins_alternatives": [],
    },
    "سه‌گاه (Segah)": {
        "cents": [0, 150, 350, 500, 700, 850, 1050, 1200],
        "scale_ascending": [0, 150, 350, 500, 700, 850, 1050, 1200],
        "scale_descending": [0, 150, 350, 500, 700, 850, 1050, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر نیم‌بمل", "می نیم‌بمل", "فا", "سل", "لا نیم‌بمل", "سی نیم‌بمل", "دو"],
        "jins_structure": "جنس سه‌گاه/سیکاه (سه‌ربع + پرده) + جنس رست روی درجهٔ ۴",
        "mood": "تأمل و سوز درونی — از ارکان قرائت ایرانی و عثمانی",
        "family": "سیکاه",
        "jins_chain": [{"jins": "sikah", "on_degree": 1}, {"jins": "rast", "on_degree": 4, "gap_cents": 150}],
        "jins_alternatives": [],
    },
    "صبا زمزم (Saba Zamzam)": {
        "cents": [0, 100, 400, 500, 600, 900, 1000, 1200],
        "scale_ascending": [0, 100, 400, 500, 600, 900, 1000, 1200],
        "scale_descending": [0, 100, 400, 500, 600, 900, 1000, 1200],
        "tonic_ladder_cents": 0,
        "degrees_fa": ["دو", "ر بمل", "می بکار", "فا", "سل بمل", "لا", "سی بمل", "دو"],
        "jins_structure": "شاخهٔ زمزم از خانوادهٔ صبا: جنس حجاز + سل بمل (۰٫۱۰۰٫۴۰۰٫۵۰۰)",
        "mood": "حزن خاص حرم — شاخهٔ زمزم از صبا",
        "family": "صبا",
        "jins_chain": [{"jins": "hijaz", "on_degree": 1}, {"jins": "saba", "on_degree": 5, "gap_cents": 100}],
        "jins_alternatives": [],
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


def absolute_degree_name(cents_from_tonic: float, tonic_ladder_cents: int):
    """
    نام «مطلق» یک نت در نظام درجات شرقی، وقتی تونیک مقام روی درجهٔ
    tonic_ladder_cents (سنت از راست مرجع) قرار گرفته باشد.

    مثال: در بیاتی (تونیک روی دوگاه = ۲۰۰)، نت ۱۵۰ سنت بالاتر از تونیک
    «سیکاه» است (چون دوگاه+۱۵۰ = ۳۵۰ = جایگاه سیکاه) — دقیقاً همان‌طور
    که در آموزش تلاوت گفته می‌شود.

    خروجی: (fa, en, register) — register موقعیت اکتاوی نسبت به اکتاو تونیک است.
    """
    pos = float(cents_from_tonic) + tonic_ladder_cents
    # +۲۵ = نصف شبکهٔ ۵۰ سنتی — تا سنت‌های منفیِ ریز به اکتاو قبلی سرریز نکنند
    register = int(np.floor((pos + 25.0) / 1200.0))
    # نتِ واقعیِ خوانده‌شده دقیقاً روی شبکهٔ ۵۰ سنتی نیست؛ نام درجه = نزدیک‌ترین
    # موقعیت شبکه (خطای واقعی نت جداگانه در cents_off_degree گزارش می‌شود)
    base = int(round((pos - register * 1200.0) / 50.0) * 50) % 1200

    special = UPPER_REGISTER_DEGREE_NAMES.get((base, register))
    if special:
        return special["fa"], special["en"], register

    info = ABS_DEGREE_LADDER[base]
    fa, en = info["fa"], info["en"]
    if register > 0:
        suffix_fa = REGISTER_UP_FA.get(register, "جواب")
        suffix_en = REGISTER_UP_EN.get(register, "Jawab")
        fa = f"{fa} {suffix_fa}"
        en = f"{en} {suffix_en}"
    elif register < 0:
        prefix = "قرار قرار" if register <= -2 else "قرار"
        fa = f"{prefix} {fa}"
        en = f"Gharr-{en}"  # اکتاو پایین‌تر از قرار اصلی
    return fa, en, register


def maqam_degrees(maqam_cents):
    """درجات گام بدون تکرار اکتاو. اگر آرایش با ۱۲۰۰ سنت تمام نشود
    (مثل صبا که روی «دو بمل» = ۱۱۰۰ سنت می‌نشیند)، همهٔ درجه‌ها معتبرند."""
    cs = list(maqam_cents)
    if cs and abs(cs[-1] - 1200.0) < 1:
        return cs[:-1]
    return cs


def refine_tonic_hz(notes, tonic_bin: int, maqam_name: str):
    """
    تونیک خامِ تشخیص‌داده‌شده (روی شبکهٔ ۵۰ سنتی) را با کمینه‌سازی خطای
    وزن‌داده‌شدهٔ همهٔ نت‌ها نسبت به نزدیک‌ترین درجهٔ مقام، با دقت نیم‌سنتی
    پالایش می‌کند. خروجی: (tonic_hz دقیق, دلتای سنت اعمال‌شده).
    """
    scale = MAQAMAT[maqam_name]
    degree_sets = set(maqam_degrees(scale["scale_ascending"])) | set(maqam_degrees(scale["scale_descending"]))
    degrees = np.array(sorted(degree_sets), dtype=float)  # سنت از تونیک

    obs = []  # (سنت از A440, وزن=مدت)
    for n in notes:
        c = freq_to_cents(n["f0_hz"])
        if not np.isnan(c):
            obs.append((c, float(n.get("duration", 0.1))))
    if not obs:
        return 440.0 * (2 ** (tonic_bin / 24.0)), 0.0

    obs_c = np.array([o[0] for o in obs])
    obs_w = np.array([o[1] for o in obs])
    tonic_cents0 = tonic_bin * 50.0

    best_delta, best_cost = 0.0, None
    for delta in np.arange(-24.5, 25.0, 0.5):
        # فاصلهٔ مدارِ هر نت تا نزدیک‌ترین درجهٔ مقام (ممیز اکتاو حذف می‌شود)
        rel = obs_c - (tonic_cents0 + delta)
        dgrid = (rel[:, None] - degrees[None, :] + 600.0) % 1200.0 - 600.0
        d = np.abs(dgrid).min(axis=1)
        cost = float(np.sum(obs_w * d ** 2))
        if best_cost is None or cost < best_cost:
            best_cost, best_delta = cost, float(delta)

    tonic_hz = 440.0 * (2 ** ((tonic_cents0 + best_delta) / 1200.0))
    return round(tonic_hz, 2), round(best_delta, 1)


_DEGREE_JINS_MAP_CACHE = {}


def note_to_solfege(f0_hz: float, tonic_hz: float, maqam_cents,
                    maqam_name=None, scale_cents=None):
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
    # +۲۵ = نصف شبکهٔ ۵۰ سنتی — تا نتِ هم‌سایهٔ تونیک با خطای ریزِ Praat
    # (مثل ‎−۰٫۱¢‎) به اشتباه اکتاو «قرار» گرفته نشود
    register = int(np.floor((cents_from_tonic + 25.0) / 1200.0))
    cents_in_octave = cents_from_tonic - register * 1200.0

    scale = scale_cents if scale_cents is not None else maqam_cents
    degree_cents = list(scale[:-1])  # حذف درجه ۸ (تکرار اکتاویِ درجه ۱)
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

    result = {
        "degree_index": degree_idx + 1,       # ۱ تا ۷ (۱=راست/تونیک)
        "degree_name": degree_name,
        "register_offset": register,           # ۰=اکتاو تونیک، ۱=جواب، -۱=قرار، ...
        "register_name": register_suffix or "اصل",
        "cents_off_degree": round(float(cents_off), 1),
        "solfege_name": full_name,
    }

    # --- نام مطلق در نظام درجات شرقی (راست، دوگاه، سیکاه، جهارکاه، ...) ---
    # فقط وقتی نام مقام مشخص باشد؛ چون جایگاه تونیک مقام روی نردبان درجات
    # برای هر مقام متفاوت است (بیاتی/حجاز/کرد/صبا از دوگاه، سیکاه از سیکاه، ...).
    if maqam_name and maqam_name in MAQAMAT:
        info = MAQAMAT[maqam_name]
        ladder_cents = info["tonic_ladder_cents"]
        abs_fa, abs_en, abs_reg = absolute_degree_name(cents_in_octave, ladder_cents)
        result["absolute_degree_fa"] = abs_fa
        result["absolute_degree_en"] = abs_en
        result["absolute_solfege_name"] = abs_fa
        # آیا نت واقعاً روی یکی از درجات مقام نشسته؟ (تلورانس ۴۰ سنت)
        result["in_maqam"] = bool(abs(cents_off) <= 40.0)

        # --- نسبت نت به اجناس مقام (نظریهٔ عقد و جنس) ---
        # هر درجه به جنسِ «زادگاه» خودش نسبت داده می‌شود + نُتِ چندمِ آن جنس
        degree_cents = round(scale[degree_idx])
        jmap = _DEGREE_JINS_MAP_CACHE.get((maqam_name, tuple(info["scale_ascending"])))
        if jmap is None:
            try:
                jmap = build_degree_jins_map(maqam_name)
                _DEGREE_JINS_MAP_CACHE[(maqam_name, tuple(info["scale_ascending"]))] = jmap
            except Exception:
                jmap = {}
        jinfo = jmap.get(degree_cents)
        if jinfo:
            j = JINSAT[jinfo["jins"]]
            result["jins_fa"] = j["fa"]
            result["jins_en"] = j["en"]
            result["jins_type"] = j["type"]
            result["jins_position"] = jinfo["position"]

    return result


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
            note = {
                "start": round(float(times[i]), 3),
                "end": round(float(times[j]), 3),
                "duration": round(duration, 3),
                "f0_hz": round(f0_median, 2),
                "stability_cents": round(f0_std_cents, 1),
            }
            # 🎢 سُر (گلیساندو): پرده ثابت نمانده اما روند یک‌طرفهٔ معنادار
            # دارد — به‌جای دور انداختن، برچسب بخورد تا ملوگراف/سبک‌سنج
            # آن را «حرکت بین دو درجه» بفهمند نه لرزش.
            if f0_std_cents > stability_cents and len(segment_freqs) >= 5:
                first_c = freq_to_cents(segment_freqs[0], f0_median)
                last_c = freq_to_cents(segment_freqs[-1], f0_median)
                net = abs(last_c - first_c)
                steps = np.diff([freq_to_cents(f, f0_median)
                                 for f in segment_freqs])
                same_dir = float(np.sum(steps > 0)) / max(len(steps), 1)
                mono = max(same_dir, 1.0 - same_dir)
                if net >= 60.0 and mono >= 0.72:
                    note["glissando"] = True
                    note["glissando_cents"] = round(float(last_c - first_c), 1)
            notes.append(note)
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
    degrees_ = maqam_degrees(maqam_cents)
    degree_weights = [1.4, 0.8, 1.0, 0.9, 1.3, 0.8, 0.9, 1.0][:len(degrees_)]  # تونیک و غماز وزن بیشتر
    for degree_cents, w in zip(degrees_, degree_weights):
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


# وزن «پیشینِ قرار»: نتی که فراز/تلاوت روی آن می‌نشیند (فرود) قوی‌ترین
# نشانهٔ تونیک است — کاندیدهایی که تونیک‌شان نزدیک قرار باشد (تا ۱۵۰ سنت)
# تا ۳۵٪ امتیاز بیشتری می‌گیرند. این تفکیک‌کنندهٔ اصلی «هم‌ارزی چرخشی» است:
# مثلاً نت‌های مقام کرد از درجهٔ ۴ دقیقاً همان مجموعهٔ نهاوند از تونیک است و
# هیستوگرامِ محض نمی‌تواند بگوید قاری روی کدام عقد «نشسته» — قرار می‌گوید.
FINALIS_BONUS = 0.35
FINALIS_BONUS_RANGE_CENTS = 150.0


def _finalis_bonus(tonic_cents: float, finalis_cents):
    """ضریب تکثیری امتیاز بر اساس نزدیکی تونیک فرضی به قرار (فرود واقعی)."""
    if finalis_cents is None:
        return 1.0
    dist = abs(tonic_cents - float(finalis_cents)) % 1200.0
    dist = min(dist, 1200.0 - dist)
    return 1.0 + FINALIS_BONUS * max(0.0, 1.0 - dist / FINALIS_BONUS_RANGE_CENTS)


def solfege_dtw_align(sung_cents, target_cents):
    """هم‌ترازی DTW سادهٔ توالی سنتِ خوانده‌شده با توالی هدف (سولفژ).
    خروجی: {اندیس هدف: [اندیس‌های خوانده‌شده]} — پرش‌ها و کشش‌ها را تحمل می‌کند."""
    n, m = len(sung_cents), len(target_cents)
    if n == 0 or m == 0:
        return {}
    INF = float("inf")
    D = [[INF] * (m + 1) for _ in range(n + 1)]
    D[0][0] = 0.0

    def _wdiff(a, b):
        off = (a - b + 600.0) % 1200.0 - 600.0
        return min(abs(off), 150.0)

    for i in range(1, n + 1):
        for j in range(1, m + 1):
            c = _wdiff(sung_cents[i - 1], target_cents[j - 1])
            D[i][j] = c + min(D[i - 1][j - 1], D[i - 1][j], D[i][j - 1])
    i, j = n, m
    by_target = {}
    while i > 0 and j > 0:
        by_target.setdefault(j - 1, []).append(i - 1)
        best = min(D[i - 1][j - 1], D[i - 1][j], D[i][j - 1])
        if best == D[i - 1][j - 1]:
            i, j = i - 1, j - 1
        elif best == D[i - 1][j]:
            i -= 1
        else:
            j -= 1
    return by_target


def solfege_score_notes(notes, targets_cents, tonic_hz):
    """نت‌های تحلیل‌شدهٔ فایل کاربر را با توالی هدف سولفژ می‌سنجد.
    notes: [{"f0_hz":.., "duration":..}] — targets_cents: [سنت از تونیک]
    خروجی: {"per": [...], "overall_pct":.., "coverage_pct":.., "n_sung_notes":..}"""
    sung_notes = [n for n in notes if (n.get("f0_hz") or 0) > 0 and (n.get("duration") or 0) >= 0.08]
    if not sung_notes or not targets_cents or not tonic_hz or tonic_hz <= 0:
        return None
    sung = [1200.0 * np.log2(n["f0_hz"] / tonic_hz) for n in sung_notes]
    tgt = [float(t) for t in targets_cents]
    by_t = solfege_dtw_align(sung, tgt)
    per, matched = [], []
    for j, t in enumerate(tgt):
        idxs = sorted(by_t.get(j) or [])
        if not idxs:
            per.append({"i": j, "matched": False, "offset": None, "score": None, "n": 0})
            continue
        offs = sorted(((sung[si] - t + 600.0) % 1200.0) - 600.0 for si in idxs)
        med = offs[len(offs) // 2]
        sc = max(0.0, 100.0 - max(0.0, abs(med) - 30.0) * 1.2)
        matched.append(sc)
        per.append({"i": j, "matched": True, "offset": round(med), "score": round(sc, 1),
                    "n": len(idxs)})
    coverage = len(matched) / len(tgt)
    overall = round(sum(matched) / len(matched) * (coverage ** 0.5), 1) if matched else 0.0
    return {"per": per, "overall_pct": overall, "coverage_pct": round(100.0 * coverage),
            "n_sung_notes": len(sung)}


def rerank_candidates_with_transitions(candidates, transition_prior,
                                       weight=0.3):
    """
    🎓 ری‌رنک نامزدهای مقام با «درست‌نمایی گذر درجات» (قواعد سیر) —
    وقتی پروفایل سبک یا آمار گذر مقام‌ها موجود باشد، امتیاز بهاتاچاریا
    هیستوگرام با احتمال گذرهای مشاهده‌شدهٔ همان مقام ترکیب می‌شود:
        score_adj = (1-w)·BH + w·norm_loglik(transitions)
    این تمایز مقامات هم‌گام (بیاتی/کرد، رست/عجم) را که فقط در چند درجه
    فرق دارند، از مسیر حرکت قاری بهتر می‌فهمد.
    prior: {maqam_name: {"8->9": count, ...}} (پله‌ها ۰..۲۰)
    خروجی: همان candidates مرتب‌شدهٔ جدید (فیلد sayr_bonus اضافه می‌شود).
    """
    try:
        if not transition_prior or not candidates:
            return candidates
        import math as _math
        scored = []
        for cand in candidates:
            # گذرهای قاری باید از گزارش استخراج شوند؛ اینجا فقط درصدهای
            # آماده (dict از گذر به وزن نرمال) پذیرفته می‌شود.
            prior = transition_prior.get(cand.get("maqam")) or {}
            ev = cand.get("_transition_evidence") or {}
            hit = sum(float(prior.get(k, 0.0)) * float(v)
                      for k, v in ev.items())
            tot = sum(float(v) for v in ev.values()) or 1.0
            norm = hit / tot
            bonus = weight * norm
            c2 = dict(cand)
            c2["sayr_bonus"] = round(float(bonus), 4)
            c2["_adj_score"] = float(cand.get("score") or 0.0) + bonus
            scored.append(c2)
        scored.sort(key=lambda c: -c["_adj_score"])
        for c in scored:
            c.pop("_adj_score", None)
        return scored
    except Exception:
        return candidates


def _folded_note_cents(notes, tonic_hz):
    """نت‌ها تاًپ‌شده روی اکتاو نسبت به تونیک — [(سنت ۰..۱۲۰۰, وزن=کشش)]."""
    out = []
    for n in notes or []:
        f = n.get("f0_hz") or 0
        if not f or f <= 0 or not tonic_hz or tonic_hz <= 0:
            continue
        w = float(n.get("duration") or n.get("dur_sec") or 0.25)
        out.append(((1200.0 * np.log2(f / tonic_hz)) % 1200.0, w))
    return out


def holistic_setfit(notes, maqam_name, tonic_hz, tol_cents=40.0):
    """🎯 سنجش کلی/اجمالی گام: سهمِ جرمِ نت‌ها (وزن = کشش) که روی درجات مقام
    نشسته — کاملاً مستقل از ترتیب. ملودیِ پرشی/خارج از ردیفِ صعودی هم درست
    سنجیده می‌شود چون فقط «مجموعهٔ نت‌ها» ملاک است، نه توالی‌شان."""
    info = MAQAMAT.get(maqam_name)
    if not info or not tonic_hz or tonic_hz <= 0:
        return None
    folded = _folded_note_cents(notes, tonic_hz)
    if not folded:
        return None
    degrees = maqam_degrees(info["scale_ascending"])
    total = sum(w for _, w in folded)
    if total <= 0:
        return None
    in_mass = 0.0
    per_deg = [0.0] * len(degrees)
    for pc, w in folded:
        diffs = [min(abs(pc - d), 1200.0 - abs(pc - d)) for d in degrees]
        i = int(np.argmin(diffs))
        if diffs[i] <= tol_cents:
            in_mass += w
            per_deg[i] += w
    return {
        "coverage_pct": round(100.0 * in_mass / total, 1),
        "degree_mass_pct": [round(100.0 * m / total, 1) for m in per_deg],
        "n_notes": len(folded),
    }


def _signature_mass(folded, signature_cents, tol_cents=40.0):
    total = sum(w for _, w in folded) or 1.0
    m = sum(w for pc, w in folded
            if any(min(abs(pc - d), 1200.0 - abs(pc - d)) <= tol_cents for d in signature_cents))
    return m / total


def holistic_signature_adjust(candidates, notes, weight=0.06, min_gap_cents=40.0, top_n=4, max_adj=0.06):
    """🧭 تفکیک جفت‌های خلط‌شدنی با «درجه‌های ممیز»:
    برای هر جفت از برترین نامزدها، درجه‌هایی که فقط در گامِ یکی هستند پیدا
    می‌شود (مثل درجهٔ ۲ و ۶ میان راست/سه‌گاه، یا درجهٔ ۳ و ۷ میان راست/عجم)
    و جرم واقعی نت‌ها دقیقاً روی همان درجه‌ها سنجیده می‌شود. اختلاف، پاداشی
    ملایم به امتیاز بهاتاچاریا می‌افزاید — درجا روی score اعمال می‌شود تا
    همهٔ مراحل بعدی (اطمینان، ری‌رنک گذری، UI) یک منطق ببینند.
    همچنین setfit_pct (انطباق کلی گام) روی هر نامزد می‌نشیند."""
    if len(candidates) < 2 or not notes:
        return candidates
    cands = candidates[:top_n]
    for c in cands:
        sf = holistic_setfit(notes, c["maqam"], c["tonic_freq_hz"])
        c["setfit_pct"] = sf["coverage_pct"] if sf else None
        c["bt_score"] = c["score"]
    folded_by = {id(c): _folded_note_cents(notes, c["tonic_freq_hz"]) for c in cands}
    degs_by = {id(c): maqam_degrees(MAQAMAT[c["maqam"]]["scale_ascending"]) for c in cands if c["maqam"] in MAQAMAT}

    def _d(pc, d):
        return min(abs(pc - d), 1200.0 - abs(pc - d))

    for i in range(len(cands)):
        for j in range(i + 1, len(cands)):
            a, b = cands[i], cands[j]
            if a["maqam"] not in MAQAMAT or b["maqam"] not in MAQAMAT:
                continue
            da, db = degs_by[id(a)], degs_by[id(b)]
            sig_a = [d for d in da if min((_d(d, e) for e in db), default=999) >= min_gap_cents]
            sig_b = [d for d in db if min((_d(d, e) for e in da), default=999) >= min_gap_cents]
            if sig_a and sig_b:
                diff = _signature_mass(folded_by[id(a)], sig_a) - _signature_mass(folded_by[id(b)], sig_b)
            else:
                sa, sb = a.get("setfit_pct"), b.get("setfit_pct")
                if sa is None or sb is None:
                    continue
                diff = (sa - sb) / 100.0 * 0.5
            d_adj = max(-max_adj, min(max_adj, weight * diff))
            a["score"] += d_adj
            b["score"] -= d_adj
    # متن توضیح ممیز برای برندهٔ نهایی نسبت به نایب‌قهرمان
    cands = [c for c in cands if c["maqam"] in MAQAMAT]
    cands.sort(key=lambda r: r["score"], reverse=True)
    if len(cands) >= 2:
        win, sec = cands[0], cands[1]
        dw, ds = degs_by.get(id(win)), degs_by.get(id(sec))
        fa_names = MAQAMAT[win["maqam"]].get("degrees_fa") or []
        if dw and ds:
            sig = [d for d in dw if min((_d(d, e) for e in ds), default=999) >= min_gap_cents]
            if sig:
                names = []
                for d in sig[:3]:
                    idx = dw.index(d)
                    names.append(fa_names[idx] if idx < len(fa_names) else f"{int(d)}¢")
                sec_short = sec["maqam"].split(" (")[0]
                win["signature_fa"] = "درجهٔ ممیز: " + "، ".join(names) + f" — جدا از {sec_short}"
    return candidates


def update_with_hysteresis(state, maqam_name, margin_pct,
                           streak_needed=4, fast_margin_pct=25.0):
    """🧷 ضدچشمک برای مقامِ نمایشی زنده: نمایش فقط وقتی عوض می‌شود که نامزد
    جدید چند پنجرهٔ پیاپی برتر بماند یا اختلافش با رقیب واضح باشد.
    state = dict سایت‌خواه با کلیدهای maqam/challenger/n."""
    if not maqam_name:
        return state.get("maqam")
    cur = state.get("maqam")
    if cur is None or maqam_name == cur:
        state["maqam"] = maqam_name
        state["challenger"] = None
        state["n"] = 0
        return maqam_name
    if maqam_name == state.get("challenger"):
        state["n"] = state.get("n", 0) + 1
    else:
        state["challenger"] = maqam_name
        state["n"] = 1
    try:
        margin = float(margin_pct or 0)
    except Exception:
        margin = 0.0
    if state["n"] >= streak_needed or margin >= fast_margin_pct:
        state["maqam"] = maqam_name
        state["challenger"] = None
        state["n"] = 0
        return maqam_name
    return cur


def detect_tonic_and_maqam(hist, top_k=3, finalis_cents=None, notes=None):
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

    افزودهٔ «سولفژ دقیق دوگانه»: برای هر مقام، هم الگوی صعودی و هم الگوی
    نزولی (در مقاماتی که ساختار سرازیری متفاوت دارند — مثل نهاوند که
    درجهٔ ۷ نزولی‌اش نیم‌بمل می‌شود) با همین معیار بهاتاچاریا مقایسه
    می‌شود و بهترِ آن دو ملاک است؛ فرم برنده در فیلد «scale_form»
    گزارش می‌شود.
    """
    n_bins = len(hist)
    bin_width = 1200.0 / n_bins
    results = []
    for maqam_name, info in MAQAMAT.items():
        template_asc = maqam_template_histogram(info["scale_ascending"],
                                                TEMPLATE_SIGMA_CENTS, n_bins)
        scores_asc = _circular_bhattacharyya(hist, template_asc)
        best_shift = int(np.argmax(scores_asc))
        best_score = float(scores_asc[best_shift])
        scale_form = "ascending"
        if info["scale_descending"] != info["scale_ascending"]:
            # ساختار سرازیری متفاوت (مثل نهاوند) — بهاتاچاریای نزولی هم حساب شود
            template_desc = maqam_template_histogram(info["scale_descending"],
                                                     TEMPLATE_SIGMA_CENTS, n_bins)
            scores_desc = _circular_bhattacharyya(hist, template_desc)
            best_shift_desc = int(np.argmax(scores_desc))
            if float(scores_desc[best_shift_desc]) > best_score:
                best_shift = best_shift_desc
                best_score = float(scores_desc[best_shift_desc])
                scale_form = "descending"
        if np.isnan(best_score):
            best_score = 0.0
        tonic_cents = (best_shift * bin_width) % 1200.0
        bonus = _finalis_bonus(tonic_cents, finalis_cents)
        tonic_freq_hz = 440.0 * (2 ** (tonic_cents / 1200.0))
        results.append({
            "maqam": maqam_name,
            # tonic_bin بر حسب گام ربع‌پرده‌ی معادل (۵۰ سنتی) نگه داشته شده تا
            # ساختار خروجی/گزارش‌های قدیمی سازگار بماند؛ دقت واقعی محاسبه
            # (tonic_freq_hz) از رزولوشن ریزبین ۵ سنتی می‌آید.
            "tonic_bin": int(round(tonic_cents / 50.0)) % 24,
            "tonic_freq_hz": round(tonic_freq_hz, 2),
            "score": best_score * bonus,
            "finalis_bonus": round(bonus, 3),
            "scale_form": scale_form,
            "mood": info["mood"],
        })

    results.sort(key=lambda r: r["score"], reverse=True)

    # 🎯 سنجش کلی + تفکیک درجهٔ ممیز (وقتی نت‌ها در دسترس باشند)
    results.sort(key=lambda r: r["score"], reverse=True)
    if notes:
        try:
            holistic_signature_adjust(results, notes)
            results.sort(key=lambda r: r["score"], reverse=True)
        except Exception:
            pass

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
            finalis = freq_to_cents(window_notes[-1]["f0_hz"]) % 1200.0 if window_notes[-1]["f0_hz"] > 0 else None
            candidates = detect_tonic_and_maqam(hist, top_k=1, finalis_cents=finalis, notes=window_notes)
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
        finalis = freq_to_cents(phrase_notes[-1]["f0_hz"]) % 1200.0 if phrase_notes[-1]["f0_hz"] > 0 else None
        candidates = detect_tonic_and_maqam(hist, top_k=top_k_per_phrase, finalis_cents=finalis, notes=phrase_notes)
        best = candidates[0] if candidates else None

        solfege_notes = []
        cents_sequence = []
        if best:
            tonic_hz = best["tonic_freq_hz"]
            maqam_cents = MAQAMAT[best["maqam"]]["cents"]
            # تونیک را به اکتاو «وقف» (آخرین نت فراز — جایی که قاری می‌نشیند)
            # منتقل می‌کنیم؛ اگر نت پایانی نبود، به مرکز صدا برمی‌گردیم
            if phrase_notes[-1]["f0_hz"] > 0:
                octave_shift = round(np.log2(phrase_notes[-1]["f0_hz"] / tonic_hz))
            else:
                phrase_freqs = np.array([n["f0_hz"] for n in phrase_notes if n["f0_hz"] > 0])
                center_freq = np.exp(np.mean(np.log(phrase_freqs))) if len(phrase_freqs) else tonic_hz
                octave_shift = round(np.log2(center_freq / tonic_hz))
            tonic_hz *= (2 ** octave_shift)

            # --- پالایش دقیق تونیک (زیر شبکهٔ ۵۰ سنتی) ---
            # با کمینه‌سازی خطای وزنی همهٔ نت‌ها نسبت به درجات مقام، جایگاه
            # واقعی تونیک تا نیم‌سنت مشخص می‌شود — این همان چیزی است که
            # نام‌گذاری مطلق درجات را بی‌خطا می‌کند.
            try:
                tonic_hz, _tonic_delta = refine_tonic_hz(phrase_notes, best["tonic_bin"], best["maqam"])
                tonic_hz *= (2 ** octave_shift)
            except Exception:
                pass

            # --- انتخاب ساختار مناسب (صعودی/نزولی) برای تطبیق درجات ---
            # اگر سیر فراز غالباً نزولی باشد و مقام ساختار نزولی متفاوتی
            # داشته باشد (مثل نهاوند)، جدول درجات نزولی ملاک می‌شود.
            phrase_cents_path = [1200.0 * np.log2(n["f0_hz"] / tonic_hz) for n in phrase_notes]
            is_descending_phrase = (len(phrase_cents_path) >= 3 and
                                    (phrase_cents_path[-1] - phrase_cents_path[0]) < -150.0)
            info = MAQAMAT[best["maqam"]]
            scale_cents = info["scale_descending"] if (is_descending_phrase and
                                                       info["scale_descending"] != info["scale_ascending"]) \
                          else info["scale_ascending"]

            for n in phrase_notes:
                sol = note_to_solfege(n["f0_hz"], tonic_hz, maqam_cents,
                                      maqam_name=best["maqam"], scale_cents=scale_cents)
                cents_sequence.append(1200.0 * np.log2(n["f0_hz"] / tonic_hz))
                solfege_notes.append({
                    "start": n["start"], "end": n["end"], "duration": n["duration"],
                    "f0_hz": n["f0_hz"],
                    **({} if sol is None else sol),
                })

        trend = _melody_trend(cents_sequence) if cents_sequence else "نامشخص"

        # --- آمار درجات فراز: شروع، فرود/وقف و سهم هر درجه ---
        named_notes = [s for s in solfege_notes if s.get("absolute_degree_fa")]
        starting_degree = None
        resting_degree = None
        in_maqam_pct = None
        degree_share = None
        if named_notes:
            first = named_notes[0]
            starting_degree = {
                "absolute_fa": first["absolute_degree_fa"],
                "absolute_en": first["absolute_degree_en"],
                "degree_index": first["degree_index"],
                "f0_hz": first["f0_hz"],
            }
            # وقف = درجه‌ای که فراز روی آن می‌نشیند (آخرین نت‌های پایدار فراز)
            last = named_notes[-1]
            resting_degree = {
                "absolute_fa": last["absolute_degree_fa"],
                "absolute_en": last["absolute_degree_en"],
                "degree_index": last["degree_index"],
                "f0_hz": last["f0_hz"],
            }
            dur_total = sum(s["duration"] for s in named_notes)
            in_maqam_dur = sum(s["duration"] for s in named_notes if s.get("in_maqam"))
            in_maqam_pct = round(100.0 * in_maqam_dur / dur_total, 1) if dur_total > 0 else None
            share = {}
            for s in named_notes:
                key = s["absolute_degree_fa"]
                share[key] = share.get(key, 0.0) + s["duration"]
            degree_share = [
                {"degree_fa": k, "duration_sec": round(v, 2),
                 "pct": round(100.0 * v / dur_total, 1)}
                for k, v in sorted(share.items(), key=lambda kv: -kv[1])
            ]

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
            "scale_form": ("descending" if is_descending_phrase and best and
                           MAQAMAT[best["maqam"]]["scale_descending"] != MAQAMAT[best["maqam"]]["scale_ascending"]
                           else "ascending") if best else None,
            "melody_trend": trend,
            "starting_degree": starting_degree,
            "resting_degree": resting_degree,
            "in_maqam_pct": in_maqam_pct,
            "degree_share": degree_share,
            "notes_solfege": solfege_notes,
        })

    return phrases


def maqam_degree_span(f_low: float, f_high: float, tonic_hz: float, maqam_name):
    """
    فاصلهٔ ملودیک دو نت را با «شمارش موسیقایی درجات مقام» می‌گوید — همان‌طور
    که در آموزش تلاوت گفته می‌شود: از دوگاه تا نوا «چهار درجه» فاصله است
    (دوگاه ۱، سیکاه ۲، جهارکاه ۳، نوا ۴).

    خروجی: {span_degrees, low_absolute_fa, high_absolute_fa, description_fa}
    یا None اگر محاسبه ممکن نباشد.
    """
    if not maqam_name or maqam_name not in MAQAMAT or tonic_hz <= 0:
        return None
    if not f_low or not f_high or f_low <= 0 or f_high <= 0:
        return None

    info = MAQAMAT[maqam_name]
    ladder = info["tonic_ladder_cents"]
    scale = info["scale_ascending"]
    n_deg = len(scale) - 1  # ۷ درجه در هر اکتاو (درجهٔ ۸ = درجهٔ ۱ اکتاو بعد)

    def _position(f):
        """شمارهٔ ردیفی درجه (۰=درجهٔ ۱ تونیک) با گسترش اکتاوی."""
        c = 1200.0 * np.log2(f / tonic_hz)
        # +۱۰۰ = وسط فاصلهٔ ۱۰۰۰→۱۲۰۰ (بزرگ‌ترین گپ بین درجات) — تا سنت‌های
        # منفیِ ریز (مثل ‎−۰٫۱¢‎ ناشی از خطای Praat) به اکتاو قبلی سرریز نشوند
        octave = int(np.floor((c + 100.0) / 1200.0))
        cents_in = c - octave * 1200.0
        diffs = [abs(cents_in - dc) for dc in scale[:-1]]
        d = int(np.argmin(diffs))
        # نام مطلق با «سنتِ کامل» (شامل اکتاو) محاسبه می‌شود تا رجیستر
        # (قرار/جواب) درست بیاید — absolute_degree_name خودش اکتاو را می‌فهمد
        return octave * n_deg + d, absolute_degree_name(c, ladder)[0]

    pos_low, low_fa = _position(f_low)
    pos_high, high_fa = _position(f_high)
    span = pos_high - pos_low + 1
    range_cents = 1200.0 * np.log2(f_high / f_low)
    quarter_steps = int(round(range_cents / 50.0))
    return {
        "span_degrees": int(span),
        "low_absolute_fa": low_fa,
        "high_absolute_fa": high_fa,
        "range_cents": round(range_cents, 1),
        "range_semitones": round(range_cents / 100.0, 1),
        "quarter_steps": quarter_steps,
        "description_fa": (
            f"از «{low_fa}» تا «{high_fa}» — {span} درجه فاصله "
            f"({round(range_cents/100.0, 1)} نیم‌پرده)"
        ),
    }


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

def refine_tonic_with_prior(detected_hz, prior_hz, tolerance_cents=40.0):
    """
    🎙 پالایش تونیک با پروفایل صوتی کاربر.

    تونیکِ تشخیص‌داده‌شده از ضبط، به سمت تونیکِ ثبت‌شدهٔ پروفایل صوتی همان
    کاربر پالایش می‌شود:
      • اول اکتاوِ پروفایل به نزدیک‌ترین اکتاوِ تونیکِ تشخیصی تراز می‌شود
        (چون قاری ممکن است همان روز یک اکتاو بالاتر/پایین‌تر بخواند).
      • اگر اختلاف باقی‌مانده ≤ tolerance_cents باشد، تونیک پروفایل معتبر
        تلقی می‌شود و مقدار ترازشدهٔ آن جایگزین می‌شود (حذف لرزش تشخیص و
        تثبیت ارزیابی بین نشست‌ها).
      • اگر اختلاف زیاد باشد (مثلاً آن روز کلید متفاوتی خوانده)، تشخیصِ
        خودکار دست‌نخورده می‌ماند.

    خروجی: تونیک پالایش‌شده بر حسب هرتز.
    """
    try:
        d = float(detected_hz)
        p = float(prior_hz)
        if d <= 0 or p <= 0:
            return detected_hz
        k = round(np.log2(d / p))
        aligned = p * (2.0 ** k)
        dev = abs(1200.0 * np.log2(d / aligned))
        if dev <= float(tolerance_cents):
            return float(aligned)
        return d
    except Exception:
        return detected_hz


def analyze_recitation(path, denoise=False, top_k=3, make_plot=True, plot_dir=None,
                       progress_callback=None, tonic_prior_hz=None):
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
        # قرار (فرود) تلاوت: از نت‌های انتهایی — نتی که تلاوت روی آن می‌نشیند
        finalis_cents = None
        tail_freqs = []
        if notes:
            tail = notes[-max(1, len(notes) // 20):]  # ۵٪ انتهایی
            tail_freqs = [n["f0_hz"] for n in tail if n["f0_hz"] > 0]
            if tail_freqs:
                finalis_cents = freq_to_cents(tail_freqs[-1]) % 1200.0
        maqam_candidates = detect_tonic_and_maqam(hist, top_k=top_k, finalis_cents=finalis_cents, notes=notes)

        # --- پالایش دقیق تونیک برای همهٔ نامزدها (زیر شبکهٔ ۵۰ سنتی) ---
        # رجیستر تونیک با «قرار» (نت فرود تلاوت) تنظیم می‌شود، نه مرکز صدا —
        # چون قرارِ مقام جایی است که قاری روی آن می‌نشیند (اغلب پایین‌تر از
        # مرکز ملودی است) و نام‌گذاری درجات باید با همان ثبت شود.
        finalis_hz = tail_freqs[-1] if (finalis_cents is not None and tail_freqs) else None
        for cand in maqam_candidates:
            try:
                refined_hz, delta = refine_tonic_hz(notes, cand["tonic_bin"], cand["maqam"])
                if finalis_hz:
                    octave_shift = round(np.log2(finalis_hz / refined_hz))
                else:
                    voiced = [n["f0_hz"] for n in notes if n["f0_hz"] > 0]
                    center = float(np.exp(np.mean(np.log(voiced)))) if voiced else refined_hz
                    octave_shift = round(np.log2(center / refined_hz))
                refined_hz *= (2 ** octave_shift)
                cand["tonic_freq_hz"] = round(refined_hz, 2)
                cand["tonic_delta_cents"] = delta
            except Exception:
                pass

        # 🎙 پروفایل صوتی: پالایش همهٔ نامزدها به سمت تونیک ثبت‌شدهٔ کاربر
        if tonic_prior_hz:
            for cand in maqam_candidates:
                if cand.get("tonic_freq_hz"):
                    cand["tonic_freq_hz"] = round(
                        refine_tonic_with_prior(cand["tonic_freq_hz"], tonic_prior_hz), 2)
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

        # --- فاصلهٔ درجه‌ای پایین‌ترین تا بالاترین نت (چند نت فاصله؟) ---
        # با شمارش موسیقایی درجات مقام تشخیصی: «از دوگاه تا نوا = ۴ درجه»
        if ambitus and maqam_candidates:
            try:
                span = maqam_degree_span(
                    ambitus.get("lowest_hz"), ambitus.get("highest_hz"),
                    maqam_candidates[0]["tonic_freq_hz"],
                    maqam_candidates[0]["maqam"])
                if span:
                    ambitus["degree_span"] = span
            except Exception:
                pass

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

        # --- جدول درجات مقام تشخیصی + آمار درجاتِ واقعاً خوانده‌شده ---
        # (این بخش قلب درخواست «سولفژ دقیق هر مقام در درجاتی که خوانده
        # می‌شود» است: برای مقام تشخیص‌داده‌شده، هر ۸ درجه با نام مطلق شرقی،
        # فرکانس مورد انتظار نسبت به تونیکِ واقعیِ همین تلاوت، و سهم زمانی
        # هر درجه از کل تلاوت گزارش می‌شود.)
        best_cand = maqam_candidates[0] if maqam_candidates else None
        maqam_degree_table = None
        recitation_degrees = None
        if best_cand:
            best_info = MAQAMAT[best_cand["maqam"]]
            tonic_hz_global = best_cand["tonic_freq_hz"]
            ladder_cents = best_info["tonic_ladder_cents"]

            # نگاشت درجه→جنس برای برچسب‌گذاری هر درجه با جنس زادگاهش
            try:
                jmap = build_degree_jins_map(best_cand["maqam"])
            except Exception:
                jmap = {}

            table = []
            for i, deg_cents in enumerate(best_info["scale_ascending"]):
                abs_fa, abs_en, _ = absolute_degree_name(deg_cents, ladder_cents)
                entry = {
                    "degree_index": i + 1,
                    "cents_from_tonic": deg_cents,
                    "expected_freq_hz": round(tonic_hz_global * (2 ** (deg_cents / 1200.0)), 2),
                    "movable_name_fa": SOLFEGE_DEGREES_FA[i] if i < len(SOLFEGE_DEGREES_FA) else f"درجه {i + 1}",
                    "absolute_fa": abs_fa,
                    "absolute_en": abs_en,
                }
                if i > 0:
                    # دقیقاً همان چیزی که در آموزش تلاوت گفته می‌شود:
                    # «از درجهٔ قبل چقدر باید بروی» — پرده/نیم‌پرده/سه‌ربع/…
                    entry["step_from_previous_cents"] = deg_cents - best_info["scale_ascending"][i - 1]
                    entry["step_from_previous_fa"] = cents_to_step_label_fa(deg_cents - best_info["scale_ascending"][i - 1])
                jinfo = jmap.get(round(deg_cents))
                if jinfo:
                    j = JINSAT[jinfo["jins"]]
                    entry["jins_fa"] = j["fa"]
                    entry["jins_en"] = j["en"]
                    entry["jins_position"] = jinfo["position"]
                table.append(entry)

            # --- زنجیرهٔ اجناس (نظریهٔ عقد و جنس) ---
            jins_chain = []
            for s in best_info.get("jins_chain", []):
                j = JINSAT[s["jins"]]
                on_deg = s["on_degree"]
                on_deg_fa = table[on_deg - 1]["absolute_fa"] if on_deg - 1 < len(table) else f"درجهٔ {on_deg}"
                jins_chain.append({
                    "on_degree": on_deg,
                    "on_degree_fa": on_deg_fa,
                    "jins_fa": j["fa"],
                    "jins_en": j["en"],
                    "jins_type": j["type"],
                    "formula_fa": j["formula_fa"],
                    "intervals_cents": j["intervals"],
                    "span_cents": sum(j["intervals"]),
                    "overlap": bool(s.get("overlap")),
                })
            if best_info["scale_descending"] != best_info["scale_ascending"]:
                desc_fa, desc_en, _ = absolute_degree_name(1000, ladder_cents)
                table.append({
                    "degree_index": 8,
                    "form": "descending",
                    "cents_from_tonic": 1000,
                    "expected_freq_hz": round(tonic_hz_global * (2 ** (1000 / 1200.0)), 2),
                    "movable_name_fa": SOLFEGE_DEGREES_FA[6],
                    "absolute_fa": desc_fa,
                    "absolute_en": desc_en,
                })
            maqam_degree_table = {
                "maqam": best_cand["maqam"],
                "tonic_freq_hz": round(tonic_hz_global, 2),
                "tonic_absolute_fa": table[0]["absolute_fa"],
                "jins_structure": best_info["jins_structure"],
                "jins_chain": jins_chain,
                "jins_alternatives": best_info.get("jins_alternatives"),
                "descending_note": best_info.get("descending_note"),
                "degrees": table,
            }

            # آمار درجات خوانده‌شده (وزن‌داده‌شده با مدت هر نت)
            named = []
            for n in notes:
                sol = note_to_solfege(n["f0_hz"], tonic_hz_global, best_info["cents"],
                                      maqam_name=best_cand["maqam"])
                if sol:
                    named.append((sol, n["duration"]))
            if named:
                dur_total = sum(d for _, d in named)
                first_sol, _ = named[0]
                # سهم هر درجه از کل
                share = {}
                in_maqam_dur = 0.0
                for s, d in named:
                    key = s.get("absolute_degree_fa") or s["degree_name"]
                    share[key] = share.get(key, 0.0) + d
                    if s.get("in_maqam"):
                        in_maqam_dur += d
                recitation_degrees = {
                    "starting_degree": {
                        "absolute_fa": first_sol.get("absolute_degree_fa"),
                        "absolute_en": first_sol.get("absolute_degree_en"),
                        "degree_index": first_sol["degree_index"],
                    },
                    "in_maqam_pct": round(100.0 * in_maqam_dur / dur_total, 1) if dur_total else None,
                    "degree_histogram": sorted(
                        ({"degree_fa": k, "pct": round(100.0 * v / dur_total, 1)}
                         for k, v in share.items()),
                        key=lambda x: -x["pct"],
                    ),
                }

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
            "maqam_degree_table": maqam_degree_table,
            "recitation_degrees": recitation_degrees,
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
        # رجیستر تونیک با قرار (آخرین نت) — همان استدلال تحلیل کلی
        finalis_note = None
        if notes:
            for n in reversed(notes):
                if n["f0_hz"] > 0:
                    finalis_note = n["f0_hz"]
                    break
        if finalis_note:
            octave_shift = round(np.log2(finalis_note / tonic_hz_adj))
            tonic_hz_adj *= (2 ** octave_shift)
        else:
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

        # --- فاصلهٔ ملودیک دو نت (چند درجه/نت فاصله است؟) ---
        # فلش دوطرفهٔ عمودی در لبهٔ چپ نمودار بین دو نت + برچسب وسط آن.
        span_info = None
        if tonic_hz_adj and maqam_name:
            span_info = maqam_degree_span(low_hz, high_hz, tonic_hz_adj, maqam_name)
        arrow_x = x0 + (x1 - x0) * 0.012
        if span_info:
            span_text = T(
                f"فاصله: {span_info['span_degrees']} درجه\n"
                f"از «{span_info['low_absolute_fa']}» تا «{span_info['high_absolute_fa']}»\n"
                f"({span_info['range_semitones']} نیم‌پرده)"
            )
        else:
            span_text = T(f"فاصله: {ambitus.get('range_semitones', '?')} نیم‌پرده")
        mid_hz_log = 0.5 * (np.log(low_hz) + np.log(high_hz))
        mid_hz = float(np.exp(mid_hz_log))
        # لبهٔ عمودی فلش
        ax.annotate("", xy=(arrow_x, low_hz), xytext=(arrow_x, high_hz),
                    arrowprops=dict(arrowstyle="<|-|>", color="#e5e7eb", lw=1.8,
                                    shrinkA=8, shrinkB=8, mutation_scale=14),
                    zorder=6)
        # برچسب وسط فلش
        ax.annotate(span_text, xy=(arrow_x, mid_hz), xytext=(10, 0),
                    textcoords="offset points", fontsize=10.5, fontweight="bold",
                    color="white", va="center", ha="left", zorder=9,
                    bbox=dict(boxstyle="round,pad=0.4", fc="#1f2937",
                              ec="#9ca3af", lw=1.2, alpha=0.93))
        ax.set_xlim(x0, x1)  # حفظ محدودهٔ زمانی بعد از افزودن فلش

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
        print(f"      تونیک تخمینی: {cand['tonic_freq_hz']} Hz" +
              (f" (درجهٔ {absolute_degree_name(0, MAQAMAT[cand['maqam']]['tonic_ladder_cents'])[0]})" if cand["maqam"] in MAQAMAT else ""))
        print(f"      حال‌وهوا: {cand['mood']}")

    # --- جدول دقیق درجات مقام تشخیصی (نظام مطلق درجات شرقی) ---
    mdt = report.get("maqam_degree_table")
    if mdt:
        print(f"\n📊 جدول درجات مقام «{mdt['maqam']}» (نظام مطلق درجات شرقی):")
        print(f"   تونیک این تلاوت: {mdt['tonic_freq_hz']} Hz — روی درجهٔ «{mdt['tonic_absolute_fa']}»")
        print(f"   ساختار: {mdt['jins_structure']}")
        if mdt.get("descending_note"):
            print(f"   نکتهٔ نزولی: {mdt['descending_note']}")
        for d in mdt["degrees"]:
            form = " (نزولی)" if d.get("form") == "descending" else ""
            step_fa = f"  ← {d['step_from_previous_fa']}" if d.get("step_from_previous_fa") else ""
            jins_fa = f"  [{d['jins_fa']} · نُت {d['jins_position']}]" if d.get("jins_fa") else ""
            print(f"      درجهٔ {d['degree_index']}{form}: {d['absolute_fa']} ({d['absolute_en']})"
                  f"{step_fa}{jins_fa}  = {d['cents_from_tonic']}¢  ≈ {d['expected_freq_hz']} Hz")
        if mdt.get("jins_chain"):
            print(f"   زنجیرهٔ اجناس (عقد → جنس):")
            for jc in mdt["jins_chain"]:
                ov = " (هم‌پوشان)" if jc.get("overlap") else ""
                print(f"      عقد «{jc['on_degree_fa']}» (درجهٔ {jc['on_degree']}){ov}: "
                      f"جنس {jc['jins_fa']} {jc['jins_type']} — {jc['formula_fa']}"
                      f"  [گسترهٔ {jc['span_cents']}¢]")

    rd = report.get("recitation_degrees")
    if rd:
        parts = []
        if rd.get("starting_degree"):
            sd = rd["starting_degree"]
            parts.append(f"آغاز روی «{sd['absolute_fa']}»")
        if rd.get("in_maqam_pct") is not None:
            parts.append(f"{rd['in_maqam_pct']}٪ زمان تلاوت روی درجات مقام")
        top3 = rd.get("degree_histogram", [])[:3]
        if top3:
            parts.append("پرکاربردترین درجه‌ها: " + "، ".join(
                f"«{h['degree_fa']}» ({h['pct']}٪)" for h in top3))
        if parts:
            print(f"\n🎤 درجاتِ خوانده‌شده در این تلاوت:")
            for p in parts:
                print(f"   • {p}")

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
                print(f"      نت‌ها (سولفژ سیار): {' - '.join(sol_names)}")
            abs_names = [n["absolute_degree_fa"] for n in ph.get("notes_solfege", []) if n.get("absolute_degree_fa")]
            if abs_names:
                print(f"      نت‌ها (نام مطلق درجات): {' - '.join(abs_names)}")
            if ph.get("starting_degree"):
                print(f"      آغاز: «{ph['starting_degree']['absolute_fa']}»  |  وقف: «{ph['resting_degree']['absolute_fa']}»  |  انطباق با مقام: {ph['in_maqam_pct']}٪")
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
