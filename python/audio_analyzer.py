#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
تحلیلگر هوشمند صوت / موسیقی
=================================
یک ابزار خط‌فرمان برای تحلیل کامل فایل‌های صوتی و موسیقی:
  - اطلاعات پایه (مدت زمان، نرخ نمونه‌برداری، کانال‌ها، بلندی صدا)
  - تشخیص ضرب‌آهنگ (BPM / Tempo) و زمان‌بندی ضربان‌ها (Beats)
  - تشخیص کلید (Key) و گام (Scale: Major/Minor) موسیقی
  - ویژگی‌های طیفی (Spectral Centroid, Bandwidth, Rolloff, Zero-Crossing Rate)
  - انرژی/بلندی صدا (RMS و تخمین دسی‌بل)
  - MFCC (ضرایب کپستروم فرکانس ملی) برای بصری‌سازی تیمبر صدا
  - تشخیص نت‌های غالب / کروماگرام
  - رسم و ذخیره نمودارهای بصری (Waveform, Spectrogram, Chromagram, Tempogram)
  - خروجی گزارش کامل JSON و متنی

نیازمندی‌ها:
    pip install librosa numpy scipy matplotlib soundfile

نحوه استفاده:
    python audio_analyzer.py path/to/song.mp3
    python audio_analyzer.py path/to/song.wav --no-plots
    python audio_analyzer.py path/to/song.mp3 --output report.json
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
    import librosa
    import librosa.display
except ImportError:
    print("خطا: کتابخانه librosa نصب نیست. اجرا کنید: pip install librosa")
    sys.exit(1)


# ----------------------------------------------------------------------------
# ثابت‌ها و جداول مرجع
# ----------------------------------------------------------------------------

NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# پروفایل‌های کروما کروم‌هنسل-اشموکلر (Krumhansl-Schmuckler) برای تشخیص کلید
MAJOR_PROFILE = np.array(
    [6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88]
)
MINOR_PROFILE = np.array(
    [6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17]
)

GENRE_TEMPO_HINTS = [
    (60, 76, "بالاد / آهسته (Ballad)"),
    (76, 108, "آهسته تا متوسط (Andante/Moderato)"),
    (108, 120, "متوسط (Moderato/Pop معمولی)"),
    (120, 135, "پرانرژی (Pop/Rock/Dance)"),
    (135, 150, "تند (Uptempo)"),
    (150, 190, "خیلی تند (Drum & Bass / Hardstyle)"),
]


# ----------------------------------------------------------------------------
# توابع کمکی
# ----------------------------------------------------------------------------

def human_time(seconds: float) -> str:
    m = int(seconds // 60)
    s = seconds % 60
    return f"{m:02d}:{s:05.2f}"


def describe_tempo(bpm: float) -> str:
    for lo, hi, desc in GENRE_TEMPO_HINTS:
        if lo <= bpm < hi:
            return desc
    if bpm < 60:
        return "بسیار آهسته (Largo/Grave)"
    return "بسیار تند"


def estimate_key(chroma: np.ndarray):
    """با استفاده از الگوریتم کروم‌هنسل-اشموکلر، کلید موسیقی را تخمین می‌زند."""
    chroma_mean = chroma.mean(axis=1)
    chroma_mean = chroma_mean / (np.linalg.norm(chroma_mean) + 1e-9)

    best_score = -np.inf
    best_key = None
    best_mode = None

    for i in range(12):
        major_shifted = np.roll(MAJOR_PROFILE, i)
        minor_shifted = np.roll(MINOR_PROFILE, i)

        major_shifted = major_shifted / np.linalg.norm(major_shifted)
        minor_shifted = minor_shifted / np.linalg.norm(minor_shifted)

        major_score = np.dot(chroma_mean, major_shifted)
        minor_score = np.dot(chroma_mean, minor_shifted)

        if major_score > best_score:
            best_score = major_score
            best_key = NOTE_NAMES[i]
            best_mode = "ماژور (Major)"

        if minor_score > best_score:
            best_score = minor_score
            best_key = NOTE_NAMES[i]
            best_mode = "مینور (Minor)"

    confidence = float(np.clip((best_score + 1) / 2, 0, 1))
    return best_key, best_mode, confidence


def dominant_notes(chroma: np.ndarray, top_n: int = 3):
    chroma_mean = chroma.mean(axis=1)
    idx_sorted = np.argsort(chroma_mean)[::-1][:top_n]
    return [(NOTE_NAMES[i], float(chroma_mean[i])) for i in idx_sorted]


def rms_to_db(rms: float) -> float:
    if rms <= 1e-10:
        return -120.0
    return float(20 * np.log10(rms))


# ----------------------------------------------------------------------------
# تحلیل اصلی
# ----------------------------------------------------------------------------

def analyze_audio(path: str, make_plots: bool = True, plot_dir: str = None):
    if not os.path.exists(path):
        raise FileNotFoundError(f"فایل پیدا نشد: {path}")

    print(f"در حال بارگذاری فایل: {path} ...")
    y, sr = librosa.load(path, sr=None, mono=True)
    duration = librosa.get_duration(y=y, sr=sr)

    print("در حال تحلیل ویژگی‌های صوتی... (ممکن است چند ثانیه طول بکشد)")

    # --- ضرب‌آهنگ و ضربان‌ها ---
    tempo, beat_frames = librosa.beat.beat_track(y=y, sr=sr)
    tempo = float(np.atleast_1d(tempo)[0])
    beat_times = librosa.frames_to_time(beat_frames, sr=sr)

    # --- کروما و کلید ---
    chroma = librosa.feature.chroma_cqt(y=y, sr=sr)
    key, mode, key_confidence = estimate_key(chroma)
    top_notes = dominant_notes(chroma)

    # --- ویژگی‌های طیفی ---
    spectral_centroid = librosa.feature.spectral_centroid(y=y, sr=sr)[0]
    spectral_bandwidth = librosa.feature.spectral_bandwidth(y=y, sr=sr)[0]
    spectral_rolloff = librosa.feature.spectral_rolloff(y=y, sr=sr)[0]
    zcr = librosa.feature.zero_crossing_rate(y)[0]
    rms = librosa.feature.rms(y=y)[0]

    # --- MFCC ---
    mfcc = librosa.feature.mfcc(y=y, sr=sr, n_mfcc=13)

    # --- انرژی هارمونیک در برابر ضربان (Percussive) ---
    y_harmonic, y_percussive = librosa.effects.hpss(y)
    harmonic_energy = float(np.sum(y_harmonic ** 2))
    percussive_energy = float(np.sum(y_percussive ** 2))
    total_energy = harmonic_energy + percussive_energy + 1e-9
    harmonic_ratio = harmonic_energy / total_energy
    percussive_ratio = percussive_energy / total_energy

    # --- انرژی کلی و بلندی ---
    mean_rms = float(np.mean(rms))
    max_rms = float(np.max(rms))
    mean_db = rms_to_db(mean_rms)
    peak_db = rms_to_db(max_rms)

    # --- شروع نت‌ها (Onsets) ---
    onset_frames = librosa.onset.onset_detect(y=y, sr=sr)
    onset_times = librosa.frames_to_time(onset_frames, sr=sr)

    # --- ساختار گزارش ---
    report = {
        "meta": {
            "file": os.path.basename(path),
            "analyzed_at": datetime.now().isoformat(timespec="seconds"),
        },
        "basic": {
            "duration_sec": round(duration, 2),
            "duration_human": human_time(duration),
            "sample_rate_hz": int(sr),
            "num_beats_detected": int(len(beat_times)),
            "num_onsets_detected": int(len(onset_times)),
        },
        "tempo": {
            "bpm": round(tempo, 2),
            "style_hint": describe_tempo(tempo),
            "first_beats_sec": [round(t, 2) for t in beat_times[:10].tolist()],
        },
        "key": {
            "estimated_key": key,
            "mode": mode,
            "confidence": round(key_confidence, 3),
            "dominant_notes": [
                {"note": n, "strength": round(s, 3)} for n, s in top_notes
            ],
        },
        "loudness": {
            "mean_rms": round(mean_rms, 5),
            "peak_rms": round(max_rms, 5),
            "mean_db": round(mean_db, 2),
            "peak_db": round(peak_db, 2),
        },
        "spectral": {
            "mean_spectral_centroid_hz": round(float(np.mean(spectral_centroid)), 2),
            "mean_spectral_bandwidth_hz": round(float(np.mean(spectral_bandwidth)), 2),
            "mean_spectral_rolloff_hz": round(float(np.mean(spectral_rolloff)), 2),
            "mean_zero_crossing_rate": round(float(np.mean(zcr)), 5),
        },
        "texture": {
            "harmonic_ratio": round(harmonic_ratio, 3),
            "percussive_ratio": round(percussive_ratio, 3),
            "interpretation": (
                "صدا بیشتر هارمونیک/ملودیک است (مثل آواز یا سازهای ملودیک)"
                if harmonic_ratio > 0.6
                else "صدا بیشتر ریتمیک/پرکاشن است (مثل درام یا بیت)"
                if percussive_ratio > 0.6
                else "ترکیبی متعادل از عناصر هارمونیک و ریتمیک"
            ),
        },
        "mfcc_summary": {
            f"mfcc_{i+1}_mean": round(float(np.mean(mfcc[i])), 3)
            for i in range(mfcc.shape[0])
        },
    }

    if make_plots:
        plot_dir = plot_dir or os.path.dirname(os.path.abspath(path)) or "."
        os.makedirs(plot_dir, exist_ok=True)
        base_name = os.path.splitext(os.path.basename(path))[0]
        plot_path = os.path.join(plot_dir, f"{base_name}_analysis.png")
        _make_plots(y, sr, chroma, beat_times, mfcc, plot_path)
        report["meta"]["visualization_file"] = plot_path

    return report, y, sr


def _setup_persian_font():
    """در صورت وجود فونت فارسی (Vazirmatn) آن را برای matplotlib فعال می‌کند."""
    import matplotlib
    import matplotlib.font_manager as fm

    candidates = [
        os.path.expanduser("~/.fonts/Vazirmatn-Regular.ttf"),
        "/usr/share/fonts/truetype/vazirmatn/Vazirmatn-Regular.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            fm.fontManager.addfont(path)
            matplotlib.rcParams["font.family"] = "Vazirmatn"
            return True
    return False


def _fa(text: str) -> str:
    """متن فارسی/عربی را برای نمایش صحیح در matplotlib بازآرایی می‌کند
    (اتصال حروف + راست‌به‌چپ). اگر کتابخانه‌های لازم نصب نباشند، متن اصلی
    را برمی‌گرداند (ممکن است حروف به‌هم‌نچسبیده نمایش داده شود)."""
    try:
        import arabic_reshaper
        from bidi.algorithm import get_display

        reshaped = arabic_reshaper.reshape(text)
        return get_display(reshaped)
    except ImportError:
        return text


def _make_plots(y, sr, chroma, beat_times, mfcc, out_path):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    has_persian_font = _setup_persian_font()
    matplotlib.rcParams["axes.unicode_minus"] = False

    def T(text):
        return _fa(text) if has_persian_font else text

    fig, axes = plt.subplots(4, 1, figsize=(12, 14))
    fig.suptitle(T("گزارش تحلیل صوتی"), fontsize=16)

    # 1) Waveform
    times = np.linspace(0, len(y) / sr, num=len(y))
    axes[0].plot(times, y, linewidth=0.5, color="#3b82f6")
    for bt in beat_times:
        axes[0].axvline(bt, color="red", alpha=0.15, linewidth=0.8)
    axes[0].set_title(T("شکل موج (Waveform) + ضربان‌های تشخیص داده‌شده (خطوط قرمز)"))
    axes[0].set_xlabel(T("زمان (ثانیه)"))
    axes[0].set_ylabel(T("دامنه"))

    # 2) Spectrogram (log)
    D = librosa.amplitude_to_db(np.abs(librosa.stft(y)), ref=np.max)
    img1 = librosa.display.specshow(D, sr=sr, x_axis="time", y_axis="log", ax=axes[1], cmap="magma")
    axes[1].set_title(T("طیف‌نگاشت (Spectrogram)"))
    fig.colorbar(img1, ax=axes[1], format="%+2.0f dB")

    # 3) Chromagram
    img2 = librosa.display.specshow(chroma, sr=sr, x_axis="time", y_axis="chroma", ax=axes[2], cmap="coolwarm")
    axes[2].set_title(T("کروماگرام (توزیع نت‌ها در طول زمان)"))
    fig.colorbar(img2, ax=axes[2])

    # 4) MFCC
    img3 = librosa.display.specshow(mfcc, x_axis="time", ax=axes[3], cmap="viridis")
    axes[3].set_title(T("MFCC (ویژگی تیمبر / رنگ صدا)"))
    axes[3].set_ylabel(T("ضریب MFCC"))
    fig.colorbar(img3, ax=axes[3])

    plt.tight_layout(rect=[0, 0, 1, 0.97])
    plt.savefig(out_path, dpi=130)
    plt.close(fig)
    print(f"نمودار تحلیل ذخیره شد: {out_path}")


# ----------------------------------------------------------------------------
# چاپ گزارش خوانا در ترمینال
# ----------------------------------------------------------------------------

def print_report(report: dict):
    print("\n" + "=" * 60)
    print(f" گزارش تحلیل صوتی: {report['meta']['file']}")
    print("=" * 60)

    b = report["basic"]
    print(f"\n📁 اطلاعات پایه:")
    print(f"   مدت زمان: {b['duration_human']} ({b['duration_sec']} ثانیه)")
    print(f"   نرخ نمونه‌برداری: {b['sample_rate_hz']} Hz")
    print(f"   تعداد ضربان‌های شناسایی‌شده: {b['num_beats_detected']}")
    print(f"   تعداد رخدادهای صوتی (Onsets): {b['num_onsets_detected']}")

    t = report["tempo"]
    print(f"\n🥁 ضرب‌آهنگ (Tempo):")
    print(f"   BPM: {t['bpm']}")
    print(f"   سبک تقریبی: {t['style_hint']}")

    k = report["key"]
    print(f"\n🎹 کلید موسیقی:")
    print(f"   کلید تخمینی: {k['estimated_key']} {k['mode']}")
    print(f"   اطمینان: {k['confidence'] * 100:.1f}%")
    notes_str = ", ".join(f"{n['note']} ({n['strength']})" for n in k["dominant_notes"])
    print(f"   نت‌های غالب: {notes_str}")

    l = report["loudness"]
    print(f"\n🔊 بلندی صدا:")
    print(f"   میانگین: {l['mean_db']} dB   |   اوج: {l['peak_db']} dB")

    s = report["spectral"]
    print(f"\n📊 ویژگی‌های طیفی:")
    print(f"   مرکز طیفی (روشنی صدا): {s['mean_spectral_centroid_hz']} Hz")
    print(f"   پهنای باند طیفی: {s['mean_spectral_bandwidth_hz']} Hz")
    print(f"   Rolloff طیفی: {s['mean_spectral_rolloff_hz']} Hz")
    print(f"   نرخ عبور از صفر: {s['mean_zero_crossing_rate']}")

    tx = report["texture"]
    print(f"\n🎼 بافت صدا:")
    print(f"   نسبت هارمونیک: {tx['harmonic_ratio']*100:.1f}%   |   نسبت ریتمیک: {tx['percussive_ratio']*100:.1f}%")
    print(f"   تفسیر: {tx['interpretation']}")

    if "visualization_file" in report["meta"]:
        print(f"\n🖼️  نمودار بصری ذخیره شد: {report['meta']['visualization_file']}")

    print("\n" + "=" * 60 + "\n")


# ----------------------------------------------------------------------------
# نقطه ورود CLI
# ----------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="تحلیلگر هوشمند صوت/موسیقی — تشخیص BPM، کلید، طیف و بلندی صدا"
    )
    parser.add_argument("audio_path", help="مسیر فایل صوتی (wav, mp3, flac, ogg, ...)")
    parser.add_argument("--output", "-o", help="مسیر ذخیره گزارش JSON", default=None)
    parser.add_argument("--no-plots", action="store_true", help="عدم رسم نمودارهای بصری")
    parser.add_argument("--plot-dir", help="پوشه ذخیره نمودار", default=None)

    args = parser.parse_args()

    report, y, sr = analyze_audio(
        args.audio_path, make_plots=not args.no_plots, plot_dir=args.plot_dir
    )

    print_report(report)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(report, f, ensure_ascii=False, indent=2)
        print(f"گزارش JSON ذخیره شد در: {args.output}")


if __name__ == "__main__":
    main()
