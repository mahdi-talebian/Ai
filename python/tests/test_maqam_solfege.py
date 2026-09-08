# -*- coding: utf-8 -*-
"""
تست جامع «سولفژ دقیق دوگانه + تشخیص مقام مستقل از درجهٔ آغاز»
=============================================================
اجرا:  python3 tests/test_maqam_solfege.py

بخش ۱ — نگاشت درجه‌به‌نام‌مطلق برای هر ۱۰ مقام (نظر معلمان تلاوت):
        «بیاتی از دوگاه»، «صبا از دوگاه»، «سیکاه از سیکاه»، ...
بخش ۲ — برای هر مقام یک تلاوت مصنوعی واقع‌گرایانه (ویبراتو + لگاتو) که:
        - تونیکش روی فرکانس غیرمعمول است (استقلال از رجیستر)
        - ملودی از درجهٔ ۴ (غماز) شروع می‌شود، نه تونیک
        و کل خط تحلیل Praat اجرا می‌شود؛ انتظار: تشخیص درست مقام،
        خطای تونیک < ۲۰ سنت، و نام مطلق درست برای درجهٔ آغاز.
"""
import os
import sys
import tempfile

import numpy as np
import soundfile as sf

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import quran_maqam_analyzer as qma  # noqa: E402

SR = 16000

# ---------------------------------------------------------------------------
# بخش ۱: نگاشت مطلق درجات
# ---------------------------------------------------------------------------

EXPECTED_LADDER = {
    "رست (Rast)":         ["راست", "دوگاه", "سیکاه", "جهارکاه", "نوا", "حسینی", "اوج", "کردان"],
    "بیاتی (Bayati)":     ["دوگاه", "سیکاه", "جهارکاه", "نوا", "حسینی", "عجم", "کردان", "محیر"],
    "حجاز (Hijaz)":       ["دوگاه", "کردی", "حجاز", "نوا", "حسینی", "عجم", "کردان", "محیر"],
    "صبا (Saba)":         ["دوگاه", "سیکاه", "جهارکاه", "حجاز", "حسینی", "عجم", "کردان", "محیر"],
    "نهاوند (Nahawand)":  ["راست", "دوگاه", "کردی", "جهارکاه", "نوا", "حصار", "ماهور", "کردان"],
    "عجم (Ajam)":         ["عجم", "کردان", "محیر", "سنبله", "جهارکاه جواب", "نوا جواب", "حسینی جواب", "عجم جواب"],
    "کرد (Kurd)":         ["دوگاه", "کردی", "جهارکاه", "نوا", "حسینی", "عجم", "کردان", "محیر"],
    "جهارکاه (Jiharkah)": ["جهارکاه", "نوا", "حسینی", "عجم", "کردان", "محیر", "سیکاه جواب", "جهارکاه جواب"],
    "سیکاه (Sikah)":      ["سیکاه", "جهارکاه", "نوا", "حسینی", "عجم", "کردان", "محیر", "سیکاه جواب"],
    "نکریز (Nikriz)":     ["راست", "دوگاه", "کردی", "حجاز", "نوا", "حسینی", "عجم", "کردان"],
}


def test_ladder_mapping():
    failures = []
    for name, expected in EXPECTED_LADDER.items():
        info = qma.MAQAMAT[name]
        got = [qma.absolute_degree_name(c, info["tonic_ladder_cents"])[0]
               for c in info["scale_ascending"]]
        if got != expected:
            failures.append(f"{name}\n    انتظار: {expected}\n    دریافتی: {got}")
    # نزولی نهاوند: درجهٔ ۷ نزولی = عجم (نیم‌بمل)
    fa, _, _ = qma.absolute_degree_name(1000, 0)
    if fa != "عجم":
        failures.append(f"نهاوند نزولی درجهٔ ۷ باید «عجم» باشد، نه «{fa}»")
    return failures


# ---------------------------------------------------------------------------
# بخش ۲: تشخیص از تلاوت مصنوعی
# ---------------------------------------------------------------------------

def synth_note(f0, dur, vibrato_cents=8.0, vib_rate=5.5):
    n = int(SR * dur)
    t = np.arange(n) / SR
    vib = 2 ** ((vibrato_cents * np.sin(2 * np.pi * vib_rate * t + np.random.rand() * 6.28)) / 1200.0)
    drift = 2 ** (np.cumsum(np.random.randn(n) * 0.000004))
    freq = f0 * vib * drift
    phase = 2 * np.pi * np.cumsum(freq) / SR
    sig = (np.sin(phase) + 0.5 * np.sin(2 * phase) + 0.22 * np.sin(3 * phase)
           + 0.1 * np.sin(4 * phase) + 0.05 * np.sin(5 * phase))
    env = np.ones(n)
    a, r = int(0.03 * SR), int(0.06 * SR)
    env[:a] = np.linspace(0, 1, a) ** 1.5
    env[-r:] = np.linspace(1, 0, r) ** 1.2
    return (sig * env * 0.42 + np.random.randn(n) * 0.004).astype(np.float32)


# آغاز از درجهٔ ۴ (غماز) — نه تونیک — سپس اوج و فرود به تونیک
DEGREE_SEQ = [3, 3, 2, 4, 4, 5, 4, 2, 1, 2, 0, 1, 0, 5, 6, 5, 4, 3, 2, 1, 0, 0]

UNUSUAL_TONICS = {
    "رست (Rast)": 233.8, "بیاتی (Bayati)": 155.9, "حجاز (Hijaz)": 174.9,
    "صبا (Saba)": 146.2, "نهاوند (Nahawand)": 208.3, "عجم (Ajam)": 116.7,
    "کرد (Kurd)": 184.3, "جهارکاه (Jiharkah)": 164.2, "سیکاه (Sikah)": 132.1,
    "نکریز (Nikriz)": 196.6,
}


def test_detection_from_synthetic():
    tmpdir = tempfile.mkdtemp(prefix="maqam_test_")
    failures = []
    for maqam_name, info in qma.MAQAMAT.items():
        tonic = UNUSUAL_TONICS[maqam_name]
        scale = info["scale_ascending"]
        parts = [synth_note(tonic * 2 ** (scale[i] / 1200.0), 0.55) for i in DEGREE_SEQ]
        wav_path = os.path.join(tmpdir, f"{abs(hash(maqam_name))}.wav")
        sf.write(wav_path, np.concatenate(parts), SR)

        report = qma.analyze_recitation(wav_path, make_plot=False,
                                        progress_callback=lambda s, f: None)
        top = report["maqam_candidates"][0]
        tonic_err = abs(1200.0 * np.log2(top["tonic_freq_hz"] / tonic))

        if top["maqam"] != maqam_name:
            failures.append(f"{maqam_name}: به‌جای آن «{top['maqam']}» تشخیص داده شد")
        if tonic_err > 20:
            failures.append(f"{maqam_name}: خطای تونیک {tonic_err:.1f}¢ (> 20¢)")
        ph0 = (report.get("phrase_breakdown") or [{}])[0]
        sols = ph0.get("notes_solfege") or []
        expected_start = qma.absolute_degree_name(scale[DEGREE_SEQ[0]],
                                                  info["tonic_ladder_cents"])[0]
        if not sols or sols[0].get("absolute_degree_fa") != expected_start:
            got = sols[0].get("absolute_degree_fa") if sols else None
            failures.append(f"{maqam_name}: درجهٔ آغاز باید «{expected_start}» باشد، نه «{got}»")
        print(f"  ✓ {maqam_name:24s} تونیک: {top['tonic_freq_hz']} Hz (خطا {tonic_err:.1f}¢)"
              if top["maqam"] == maqam_name and tonic_err <= 20 else f"  ✗ {maqam_name}")
    return failures


if __name__ == "__main__":
    print("بخش ۱: نگاشت مطلق درجات...")
    f1 = test_ladder_mapping()
    print("  " + ("✓ همهٔ ۱۰ مقام درست" if not f1 else "✗ خطا"))
    for msg in f1:
        print("    " + msg)

    print("بخش ۲: تشخیص از تلاوت مصنوعی (آغاز از غماز، تونیک غیرمعمول)...")
    f2 = test_detection_from_synthetic()

    total = len(f1) + len(f2)
    print(f"\n=== نتیجه: {total} خطا ===")
    for msg in f1 + f2:
        print("  ✗ " + msg)
    sys.exit(1 if total else 0)
