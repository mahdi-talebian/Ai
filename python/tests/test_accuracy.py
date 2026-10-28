#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🧪 بنچمارک «هم‌خوانی و دقت نت» (Consistency & Note-Accuracy Benchmark)
=======================================================================
این تست، ریشهٔ شکایت «تحلیل با بعضی فایل‌ها درست است و با بعضی نه» را
عددی می‌کند: یک «تلاوت مصنوعی» با نت‌های مشخص ساخته می‌شود، سپس همان
اجرا در قالب‌های مختلف (WAV/MP3، ۱۶k/۴۴٫۱k/۴۸k، استریوی فازمحافظ،
کم‌صدا، نویزی، با رامبل، کلیپ‌شده، باند تلفنی) به موتور داده می‌شود.

معیارها:
  • بالاترین/پایین‌ترین نت درست باشد و در همهٔ نسخه‌ها **یکسان** بماند
  • درصد نت‌های درست‌تشخیص‌داده‌شده (شروع نزدیک + کوک درست)
  • میانهٔ خطای کوک هر نت (سنت)
  • گسترهٔ صدا (نیم‌پرده) یکسان باشد

اجرا:
    python3 tests/test_accuracy.py            # جدول کامل + خروج ۰/۱
    python3 tests/test_accuracy.py -v         # با جزئیات هر نسخه
"""

import os
import subprocess
import sys
import tempfile

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import soundfile as sf  # noqa: E402

from analysis_engine import analyze_pitch  # noqa: E402

SR_GEN = 44100

# ── نت‌های اجرای مرجع (تونیک A3 = ۲۲۰ هرتز) ──────────────────────────────
# (نیم‌پرده از تونیک، کشش ثانیه)
SCORE = [
    (0, 0.55), (2, 0.45), (3, 0.60), (5, 0.50), (7, 0.65),
    (5, 0.45), (3, 0.50), (2, 0.40), (0, 0.70),
    (12, 0.55), (10, 0.45), (7, 0.55), (5, 0.40), (0, 0.75),
]
TONIC = 220.0
GAP = 0.12


def _vowel(x, sr):
    """فیلتر فرمанتی تقریبی (شبیه صدای انسان: F1≈۷۰۰, F2≈۱۲۰۰, F3≈۲۶۰۰)."""
    from scipy.signal import lfilter
    y = x
    for fc, bw, g in ((700, 130, 1.0), (1220, 160, 0.7), (2600, 250, 0.35)):
        r = np.exp(-np.pi * bw / sr)
        theta = 2 * np.pi * fc / sr
        b = [1 - r]
        a = [1, -2 * r * np.cos(theta), r * r]
        y = lfilter(b, a, y * g) + 0.25 * y
    return y


def synth_voice(degrade=None, seed=7):
    """
    اجرای مصنوعی «صدای انسان»: پالس گلوتال با جیتر/شیمر، ویبراتوی ۵٫۵ هرتز،
    فیلتر فرمанتی، پوش دامنه؛ سپس خرابی موردنظر اعمال می‌شود.
    """
    rng = np.random.default_rng(seed)
    total = sum(d for _, d in SCORE) + GAP * len(SCORE) + 0.3
    y = np.zeros(int(SR_GEN * total))
    t_cur = 0.15

    for semi, dur in SCORE:
        f0 = TONIC * 2 ** (semi / 12.0)
        n = int(SR_GEN * dur)
        t = np.arange(n) / SR_GEN
        vib = 1 + 0.0075 * np.sin(2 * np.pi * 5.5 * t + rng.uniform(0, 6))  # ±~۱۳ سنت
        jitter = 1 + 0.0015 * rng.standard_normal(n).cumsum() / np.sqrt(n)
        f_inst = f0 * vib * jitter
        phase = 2 * np.pi * np.cumsum(f_inst) / SR_GEN
        # پالس گلوتال ساده (ساوتوث با هارمونیک‌های نزولی) + تنفس
        src = np.zeros(n)
        for k in range(1, 14):
            src += (1.0 / k) * np.sin(k * phase)
        src += 0.02 * rng.standard_normal(n)
        env = np.minimum(1, np.minimum(t / 0.03, (dur - t) / 0.05))
        seg = _vowel(src, SR_GEN) * env
        i0 = int(SR_GEN * t_cur)
        y[i0:i0 + n] += seg[:max(0, min(n, len(y) - i0))]
        t_cur += dur + GAP

    y = y / (np.max(np.abs(y)) + 1e-9) * 0.7
    out_sr = SR_GEN

    if degrade == "quiet":
        y = y * 0.012
    elif degrade == "noisy":
        y = y + 0.05 * rng.standard_normal(len(y))
    elif degrade == "rumble":
        t = np.arange(len(y)) / SR_GEN
        y = y + 0.25 * np.sin(2 * np.pi * 42 * t) + 0.06 * np.sin(2 * np.pi * 50 * t)
    elif degrade == "clip":
        y = np.clip(y * 3.2, -1, 1)
    elif degrade == "phone":
        from scipy.signal import butter, sosfiltfilt
        sos_hp = butter(2, 320 / (SR_GEN / 2), btype="highpass", output="sos")
        sos_lp = butter(4, 3300 / (SR_GEN / 2), btype="lowpass", output="sos")
        y = sosfiltfilt(sos_lp, sosfiltfilt(sos_hp, y))
    elif degrade == "stereo_phase":
        return np.stack([y, -y], axis=1), out_sr
    elif degrade == "16k":
        out_sr = 16000
        import librosa
        y = librosa.resample(y.astype(np.float32), orig_sr=SR_GEN, target_sr=out_sr)
    elif degrade == "8k":
        out_sr = 8000
        import librosa
        y = librosa.resample(y.astype(np.float32), orig_sr=SR_GEN, target_sr=out_sr)
    elif degrade == "hum":
        t = np.arange(len(y)) / SR_GEN
        y = y + 0.20 + 0.12 * np.sin(2 * np.pi * 50 * t) + 0.05 * np.sin(2 * np.pi * 150 * t)
    elif degrade == "hiss":
        y = y + 0.004 * rng.standard_normal(len(y))
    elif degrade == "48k":
        out_sr = 48000
        import librosa
        y = librosa.resample(y.astype(np.float32), orig_sr=SR_GEN, target_sr=out_sr)
    elif degrade == "mp3":
        return y, out_sr          # در ذخیره‌سازی به mp3 تبدیل می‌شود

    return y.astype(np.float32), out_sr


def expected_notes():
    """نت‌های درست با زمان شروع و فرکانس."""
    out, t = [], 0.15
    for semi, dur in SCORE:
        out.append({"start": t, "end": t + dur, "f0": TONIC * 2 ** (semi / 12.0)})
        t += dur + GAP
    return out


def note_accuracy(notes, truth, tol_start=0.12, tol_cents=45):
    """
    درصد نت‌های مرجع که درست تشخیص داده شده‌اند (شروع نزدیک + کوک درست)
    و میانهٔ خطای کوک نت‌های منطبق.
    """
    matched = 0
    errs = []
    for tr in truth:
        cands = [n for n in notes
                 if abs(n["start"] - tr["start"]) <= tol_start + (tr["end"] - tr["start"]) * 0.5]
        if not cands:
            continue
        # نزدیک‌ترین به نظر فرکانسی/زمانی
        best = min(cands, key=lambda n: abs(n["start"] - tr["start"]))
        cents = abs(1200 * np.log2(best["f0_hz"] / tr["f0"]))
        if cents <= tol_cents:
            matched += 1
            errs.append(cents)
    pct = 100.0 * matched / max(1, len(truth))
    med = float(np.median(errs)) if errs else float("nan")
    return pct, med


def run_case(name, degrade, tmpdir, verbose=False):
    data, sr = synth_voice(degrade=degrade)
    if data.ndim == 2:
        path = os.path.join(tmpdir, f"{name}.wav")
        sf.write(path, data, sr)
    else:
        wav = os.path.join(tmpdir, f"{name}.wav")
        sf.write(wav, data, sr)
        path = wav
        if degrade == "mp3":
            path = os.path.join(tmpdir, f"{name}.mp3")
            ff = None
            try:
                import imageio_ffmpeg
                ff = imageio_ffmpeg.get_ffmpeg_exe()
            except Exception:
                import shutil
                ff = shutil.which("ffmpeg")
            subprocess.run([ff, "-y", "-loglevel", "error", "-i", wav,
                            "-b:a", "96k", path], check=True)

    rep = analyze_pitch(path, work_dir=os.path.join(tmpdir, f"work_{name}"))
    notes = rep["notes"]
    q = rep["quality"]
    hi = max(notes, key=lambda n: n["f0_hz"])
    lo = min(notes, key=lambda n: n["f0_hz"])
    span = 1200 * np.log2(hi["f0_hz"] / lo["f0_hz"]) / 100.0
    acc, med = note_accuracy(notes, expected_notes())
    return {
        "case": name,
        "hi": hi["f0_hz"],
        "lo": lo["f0_hz"],
        "span": span,
        "acc": acc,
        "err": med,
        "conf": q["mean_note_confidence"],
        "status": q["status"],
        "oct": q["octave_fixes"],
        "agree": q.get("pyin_agreement_pct"),
    }


CASES = [
    ("wav_44k", None),
    ("wav_16k", "16k"),
    ("wav_48k", "48k"),
    ("mp3_96k", "mp3"),
    ("stereo_outofphase", "stereo_phase"),
    ("quiet_-38dB", "quiet"),
    ("snr_~12dB", "noisy"),
    ("rumble_42Hz", "rumble"),
    ("clipped", "clip"),
    ("phone_band", "phone"),
    ("sr_8k_telephone", "8k"),
    ("dc_+_hum50", "hum"),
    ("hiss_-40dB", "hiss"),
]


def main():
    verbose = "-v" in sys.argv
    tmpdir = tempfile.mkdtemp(prefix="acc_")
    truth = expected_notes()
    exp_hi = max(t["f0"] for t in truth)
    exp_lo = min(t["f0"] for t in truth)
    exp_span = 1200 * np.log2(exp_hi / exp_lo) / 100.0

    print("=" * 88)
    print(f"مرجع: بالاترین {exp_hi:.1f} Hz | پایین‌ترین {exp_lo:.1f} Hz | "
          f"گسترهٔ درست {exp_span:.2f} نیم‌پرده | تعداد نت {len(truth)}")
    print("=" * 88)
    print(f"{'نسخهٔ ورودی':<20}{'بالاترین':>9}{'پایین‌ترین':>11}{'گستره':>8}"
          f"{'نت درست':>9}{'خطای کوک':>10}{'اطمینان':>9}{'اکتاو':>8}  وضعیت")
    print("-" * 88)

    rows = []
    for name, deg in CASES:
        try:
            r = run_case(name, deg, tmpdir, verbose)
        except Exception as e:
            print(f"{name:<20}  ❌ خطا: {e}")
            rows.append(None)
            continue
        rows.append(r)
        print(f"{name:<20}{r['hi']:>9.1f}{r['lo']:>11.1f}{r['span']:>8.2f}"
              f"{r['acc']:>8.0f}%{(r['err'] if r['err'] == r['err'] else 0):>9.1f}¢"
              f"{r['conf']:>9.2f}{r['oct']['halved']:>8}  {r['status']}")

    print("-" * 88)

    ok = True
    valid = [r for r in rows if r]
    if len(valid) < len(CASES):
        ok = False

    # ۱) درستی مطلق
    for r in valid:
        hi_ok = abs(1200 * np.log2(r["hi"] / exp_hi)) < 60
        lo_ok = abs(1200 * np.log2(r["lo"] / exp_lo)) < 60
        span_ok = abs(r["span"] - exp_span) < 0.5
        acc_ok = r["acc"] >= 85
        if not (hi_ok and lo_ok and span_ok and acc_ok):
            ok = False
            print(f"✗ {r['case']}: hi_ok={hi_ok} lo_ok={lo_ok} span_ok={span_ok} acc={r['acc']:.0f}%")

    # ۲) یکسانی بین نسخه‌ها
    if valid:
        his = np.array([r["hi"] for r in valid])
        los = np.array([r["lo"] for r in valid])
        spans = np.array([r["span"] for r in valid])
        hi_spread = 1200 * np.log2(his.max() / his.min())
        lo_spread = 1200 * np.log2(los.max() / los.min())
        span_spread = spans.max() - spans.min()
        print(f"یکسانی بین نسخه‌ها: پراکندگی بالاترین {hi_spread:.1f}¢ · "
              f"پایین‌ترین {lo_spread:.1f}¢ · گستره {span_spread:.2f} نیم‌پرده")
        if hi_spread > 60 or lo_spread > 60 or span_spread > 0.5:
            ok = False
            print("✗ نتیجه بین نسخه‌های مختلف یکسان نیست")

    print("=" * 88)
    print("✅ همهٔ نسخه‌ها درست و یکسان" if ok else "❌ ناهمخوانی یا خطا دیده شد")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
