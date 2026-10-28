#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🎚 تحلیل دامنهٔ صدا — بالاترین و پایین‌ترین نتِ خوانده‌شده
========================================================
این ماژول جای تحلیل مقام/سولفژ را گرفته است: فقط و فقط یک سؤال دارد —

    «بالاترین نتی که خوانده شده چیست؟ پایین‌ترین نت کدام است؟ و صدا در
     این دو حد، چه گستره‌ای (چند نیم‌پرده / چند اکتاو) را پوشش می‌دهد؟»

مسیر کار:
  ۱) استخراج F0 با موتور Praat (همان هستهٔ دقیق پروژه، پردازش پنجره‌ای
     برای فایل‌های طولانی تا حافظه سرریز نکند)
  ۲) پاک‌سازی جهش‌های اکتاوی + هموارسازی میانه
  ۳) تفکیک نت‌ها با کمینهٔ کشش (نت‌های گذرا و نویز حذف می‌شوند)
  ۴) بالاترین/پایین‌ترین نتِ «پایدار» + گسترهٔ صدا + توزیع نت‌ها
  ۵) یک تصویر PNG با سبک «ردِّ صدا» (trace) — همان سبک ویدیو

خروجی یک دیکشنری JSON-سازگار است (به‌جز کلیدهای خصوصیِ `_times`،
`_freqs`، `_wav` که برای رندر ویدیو نگه داشته می‌شوند و باید پیش از
ارسال به مرورگر حذف شوند).

استفاده:
    python3 range_analyzer.py recitation.mp3
    python3 range_analyzer.py recitation.wav --denoise --output report.json
"""

import argparse
import json
import math
import os
from datetime import datetime

import numpy as np

from pitch_engine import freq_to_note_info

# نت‌هایی که کمتر از این کشش دارند «گذرا» هستند و در تعیین بالاترین/پایین‌ترین
# نتِ خوانده‌شدهٔ کاربر حساب نمی‌شوند (جلوگیری از اثر نویز و لرزش‌های لحظه‌ای).
MIN_NOTE_SEC = 0.10
MAX_STABILITY_CENTS = 70.0      # نت‌های بسیار ناپایدار (لرزش شدید) کنار گذاشته می‌شوند
PAUSE_MIN_SEC = 0.30            # فاصلهٔ سکوت برای شمردن «نفس/مکث»

# ---- آستانهٔ «نت واقعی» برای بالاترین/پایین‌ترین نت ----
# تجربهٔ فایل‌های واقعی: جیغ/لغزش یک‌بارمصرف یا خطای اکتاوی Praat می‌تواند
# یک نت خیلی بالا/پایین بسازد که فقط ۰٫۱ ثانیه دوام دارد و «دامنهٔ صدا» را
# بی‌معنا بزرگ می‌کند. بنابراین بالاترین/پایین‌ترین نت از میان نت‌هایی
# انتخاب می‌شود که دست‌کم چند بار و در مجموع به‌اندازهٔ کافی خوانده شده‌اند؛
# نت‌های شدیدِ لحظه‌ای جداگانه (raw_highest/raw_lowest) گزارش می‌شوند.
ROBUST_MIN_TOTAL_SEC = 0.35     # حداقل مجموع زمانِ خواندنِ یک نت
ROBUST_MIN_TOTAL_PCT = 0.2      # یا حداقل این درصد از کل زمان خوانده‌شده
ROBUST_MIN_COUNT = 2            # و حداقل این تعداد بار


# ============================================================================
# آماده‌سازی صدا
# ============================================================================

AUDIO_NATIVE_EXT = {".wav", ".flac"}


def _ffmpeg_exe():
    """ffmpeg را از PATH یا متغیر محیطی یا wheel رسمی imageio-ffmpeg پیدا می‌کند."""
    import shutil as _sh
    for cand in (os.environ.get("FFMPEG_BIN"), _sh.which("ffmpeg")):
        if cand and os.path.exists(cand):
            return cand
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def _extract_audio_ffmpeg(path, out_path, sr=None):
    """
    صدا را از هر فایل ورودی (حتی ویدیو: mp4/mkv/mov/webm) با ffmpeg بیرون
    می‌کشد — مونو، ۱۶ بیت. برای فایل‌هایی که soundfile/librosa مستقیم
    نمی‌توانند بخوانند (ویدیو، برخی کانتینرهای خاص) لازم است.
    """
    import subprocess

    ff = _ffmpeg_exe()
    if not ff:
        raise RuntimeError("ffmpeg پیدا نشد — برای خواندن این فایل لازم است.")
    cmd = [ff, "-y", "-loglevel", "error", "-i", path, "-vn",
           "-ac", "1"]
    if sr:
        cmd += ["-ar", str(int(sr))]
    cmd += [out_path]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL,
                   stderr=subprocess.PIPE)
    return out_path


# ============================================================================
# تحلیل
# ============================================================================

def _fmt_note(note):
    """اطلاعات نمایشی یک نت برای خروجی JSON."""
    f0 = float(note.get("f0_hz") or 0.0)
    if f0 <= 0:
        return None
    info = freq_to_note_info(f0)
    if info is None:
        return None
    return {
        "note_name": info["note_name"],
        "midi": info["midi"],
        "midi_exact": round(69.0 + 12.0 * np.log2(f0 / 440.0), 3),
        "f0_hz": round(f0, 2),
        "cents_offset": info["cents_offset"],
        "octave": info["octave"],
        "start": round(float(note.get("start") or 0.0), 2),
        "duration": round(float(note.get("duration") or 0.0), 2),
        "stability_cents": round(float(note.get("stability_cents") or 0.0), 1),
        "confidence": round(float(note.get("confidence") or 0.0), 2),
        "glide_cents": round(float(note.get("glide_cents") or 0.0), 1),
    }


def _pick(notes, choose_max):
    """بالاترین/پایین‌ترین نت پایدار."""
    usable = [n for n in notes
              if (n.get("duration") or 0) >= MIN_NOTE_SEC
              and (n.get("stability_cents") is None
                   or n.get("stability_cents") <= MAX_STABILITY_CENTS)
              and (n.get("f0_hz") or 0) > 0]
    if not usable:
        usable = [n for n in notes if (n.get("f0_hz") or 0) > 0]
    if not usable:
        return None
    return (max if choose_max else min)(usable, key=lambda n: n["f0_hz"])


def _robust_extremes(notes, sung_total):
    """
    بالاترین/پایین‌ترین نتِ «واقعاً خوانده‌شده».

    برای هر نتِ گردشده (MIDI) مجموع زمان و تعداد دفعات را جمع می‌زند و نت‌هایی
    را که فقط یک لغزش/نویز لحظه‌ای‌اند کنار می‌گذارد. خروجی: (high, low, stats)
    که هر کدام یک نتِ نمونه (بلندترین نمونهٔ همان نت) + مجموع زمان + تعداد است.
    """
    agg = {}
    for n in notes:
        if not (n.get("f0_hz") or 0) > 0:
            continue
        key = int(n["midi"])
        row = agg.setdefault(key, {"sec": 0.0, "count": 0, "best": n})
        row["sec"] += float(n.get("duration") or 0.0)
        row["count"] += 1
        if (n.get("duration") or 0.0) > (row["best"].get("duration") or 0.0):
            row["best"] = n

    if not agg:
        return None, None, {"candidates": 0}

    thresh = max(ROBUST_MIN_TOTAL_SEC, (ROBUST_MIN_TOTAL_PCT / 100.0) * sung_total)
    stable = {m: r for m, r in agg.items()
              if r["sec"] >= thresh and r["count"] >= ROBUST_MIN_COUNT}
    if not stable:                       # فایل خیلی کوتاه/کم‌نت
        stable = agg

    def _pack(row):
        n = dict(row["best"])
        n["total_sung_sec"] = round(row["sec"], 2)
        n["count"] = row["count"]
        return n

    hi = _pack(stable[max(stable)])
    lo = _pack(stable[min(stable)])
    stats = {
        "candidates": len(stable),
        "ignored_notes": len(agg) - len(stable),
        "threshold_sec": round(thresh, 2),
    }
    return hi, lo, stats


def _downsample_contour(times, freqs, max_points=1800):
    """منحنی را برای رسم سریع در مرورگر نازک می‌کند (فرکانس صفر = بی‌صدا)."""
    if len(times) == 0:
        return {"t": [], "midi": []}
    step = max(1, int(np.ceil(len(times) / max_points)))
    t = times[::step]
    f = freqs[::step]
    midi = []
    for v in f:
        if v and v > 0:
            midi.append(round(69.0 + 12.0 * np.log2(v / 440.0), 2))
        else:
            midi.append(None)
    return {"t": [round(float(x), 2) for x in t], "midi": midi}


def analyze_range(path, denoise=False, make_plot=True, plot_dir=None,
                  progress_callback=None, work_dir=None, keep_contour=True,
                  keep_work_wav=False):
    """
    تحلیل کامل دامنهٔ صدا.

    progress_callback(stage: str, frac: float) برای نوار پیشرفت.
    خروجی: dict — شامل کلیدهای خصوصی `_times`، `_freqs`، `_wav` برای رندر ویدیو.
    """
    if not os.path.exists(path):
        raise FileNotFoundError(f"فایل پیدا نشد: {path}")

    def _cb(stage, frac):
        if progress_callback:
            progress_callback(stage, frac)

    _cb("loading", 0.02)
    tmp_path = None

    try:
        # ── موتور استاندارد (analysis_engine) ────────────────────────────────
        # تفاوت کلیدی با نسخهٔ قبلی: پیش از هر تحلیلی، ورودی به یک «مبنای
        # ثابت» تبدیل می‌شود (مونو، ۲۲٬۰۵۰ هرتز، بدون DC، بالاگذر ۴۵ هرتز،
        # پیک ۰٫۹). بنابراین همان اجرا فارغ از نرخ نمونه/تعداد کانال/بلندی/
        # فرمت، خروجی یکسان می‌دهد. سپس F0 با Praat (very_accurate)،
        # اصلاح «نردبان هارمونیک» (اکتاو/پنجم/چهارم و گم‌شدن فرکانس پایه)،
        # هم‌سنجی با pYIN و تفکیک نت با هیسترزیس انجام می‌شود.
        from analysis_engine import analyze_pitch

        def _pitch_progress(stage, frac):
            _cb(stage, 0.02 + 0.80 * float(frac))

        pitch = analyze_pitch(
            path, work_dir=(work_dir or os.path.dirname(os.path.abspath(path))),
            denoise=denoise, progress_callback=_pitch_progress,
        )
        times = np.asarray(pitch["times"], dtype=float)
        freqs = np.asarray(pitch["freqs"], dtype=float)
        sr = pitch["sr"]
        duration = pitch["duration"]
        wav_path = pitch["wav_path"]
        quality = pitch.get("quality") or {}

        _cb("notes", 0.85)
        notes_raw = pitch["notes"]
        notes = [n for n in (_fmt_note(n) for n in notes_raw) if n]
        notes.sort(key=lambda n: n["start"])

        # ---- بالاترین / پایین‌ترین نت ----
        high_raw = _pick(notes_raw, True)
        low_raw = _pick(notes_raw, False)

        voiced = freqs[freqs > 0]
        voiced_ratio = 100.0 * len(voiced) / max(1, len(freqs))
        sung_sec_all = sum(n["duration"] for n in notes)

        hi_note, lo_note, robust_stats = _robust_extremes(notes, sung_sec_all)
        if hi_note and lo_note:
            hi_hz, lo_hz = hi_note["f0_hz"], lo_note["f0_hz"]
        elif len(voiced):
            # پشتیبان: صدک‌های مقاوم روی منحنی
            lo_hz = float(np.percentile(voiced, 2))
            hi_hz = float(np.percentile(voiced, 98))
            hi_note = _fmt_note({"f0_hz": hi_hz})
            lo_note = _fmt_note({"f0_hz": lo_hz})
        else:
            hi_note = lo_note = None
            hi_hz = lo_hz = 0.0

        raw_hi = _fmt_note(high_raw) if high_raw else None
        raw_lo = _fmt_note(low_raw) if low_raw else None

        span_cents = 1200.0 * np.log2(hi_hz / lo_hz) if lo_hz and hi_hz else 0.0
        span_semitones = span_cents / 100.0
        full_octaves = int(span_semitones // 12)
        rest_semitones = round(span_semitones - full_octaves * 12, 2)
        if rest_semitones >= 11.995:      # مثلاً ۱۲٫۰۰ نیم‌پرده = یک اکتاو کامل
            full_octaves += 1
            rest_semitones = 0.0
        center_hz = float(np.median(voiced)) if len(voiced) else 0.0
        center_note = _fmt_note({"f0_hz": center_hz}) if center_hz > 0 else None

        # ---- سکوت‌ها / نفس‌ها ----
        pauses = []
        if len(times) > 1:
            voiced_mask = freqs > 0
            i = 0
            n = len(voiced_mask)
            while i < n:
                if voiced_mask[i]:
                    i += 1
                    continue
                j = i
                while j < n and not voiced_mask[j]:
                    j += 1
                if i > 0 and j < n:
                    gap = times[min(j, n - 1)] - times[i]
                    if gap >= PAUSE_MIN_SEC:
                        pauses.append({"start": round(float(times[i]), 2),
                                       "duration": round(float(gap), 2)})
                i = j

        # ---- توزیع نت‌ها (کدام نت چقدر خوانده شده) ----
        agg = {}
        for note in notes:
            key = note["midi"]
            row = agg.setdefault(key, {
                "note_name": note["note_name"], "midi": key,
                "sung_sec": 0.0, "count": 0,
            })
            row["sung_sec"] += note["duration"]
            row["count"] += 1
        sung_total = sum(r["sung_sec"] for r in agg.values()) or 1.0
        note_counts = sorted(agg.values(), key=lambda r: -r["sung_sec"])
        for row in note_counts:
            row["sung_sec"] = round(row["sung_sec"], 2)
            row["share_pct"] = round(100.0 * row["sung_sec"] / sung_total, 1)

        _cb("plot", 0.9)

        meta = {
            "file": os.path.basename(path),
            "analyzed_at": datetime.now().isoformat(timespec="seconds"),
            "engine": "radde-seda v2 — standardized input + Praat (very_accurate) "
                      "+ harmonic-ladder octave correction + pYIN cross-check",
            "sample_rate": int(sr),
            "denoise": bool(denoise),
        }

        report = {
            "meta": meta,
            "quality": quality,
            "basic": {
                "duration_sec": round(float(duration), 2),
                "voiced_ratio_pct": round(voiced_ratio, 1),
                "num_notes_detected": len(notes),
                "num_pauses_detected": len(pauses),
                "sung_sec": round(sung_total, 2),
                "longest_pause_sec": round(max((p["duration"] for p in pauses), default=0.0), 2),
            },
            "range": {
                "highest": hi_note,
                "lowest": lo_note,
                "raw_highest": raw_hi,
                "raw_lowest": raw_lo,
                "robust": robust_stats,
                # گستره به سه شکل: نیم‌پرده، اکتاو اعشاری و «اکتاو + باقی‌مانده»
                "span_semitones": round(span_semitones, 2),
                "span_octaves": round(span_semitones / 12.0, 2),
                "span_cents": round(span_cents, 1),
                "full_octaves": full_octaves,
                "rest_semitones": rest_semitones,
                "quarter_tones": round(span_semitones * 2, 1),
                "of_octave_pct": round(100.0 * span_semitones / 12.0, 1),
                "center": center_note,
            },
            "notes": notes,
            "note_counts": note_counts,
            "pauses": pauses[:50],
            "contour": _downsample_contour(times, freqs),
        }

        # ---- تصویر PNG به سبک ردِّ صدا ----
        if make_plot:
            out_dir = plot_dir or (work_dir or os.path.dirname(os.path.abspath(path)))
            os.makedirs(out_dir, exist_ok=True)
            png_path = os.path.join(out_dir, "range_trace.png")
            try:
                from trace_video import render_static_png
                render_static_png(times, freqs, notes, png_path)
                report["meta"]["visualization_file"] = png_path
            except Exception as e:      # تصویر نباید تحلیل را از کار بیندازد
                print(f"هشدار: ساخت تصویر ناموفق بود: {e}")

        if keep_contour:
            report["_times"] = times
            report["_freqs"] = freqs
            report["_wav"] = wav_path

        _cb("done", 1.0)
        return report

    finally:
        if tmp_path:
            try:
                os.unlink(tmp_path)
            except Exception:
                pass


def json_safe(obj):
    """
    هر مقدار را به چیزی تبدیل می‌کند که در JSON معتبر باشد.

    مقادیر NaN/بی‌نهایت (که از نهیچ محاسبهٔ آماری‌ای نباید به مرورگر برسند)
    به null تبدیل می‌شوند؛ در غیر این صورت پاسخ API با خطای ۵۰۰ می‌شکند.
    """
    if isinstance(obj, dict):
        return {k: json_safe(v) for k, v in obj.items()}
    if isinstance(obj, (list, tuple)):
        return [json_safe(v) for v in obj]
    if isinstance(obj, np.ndarray):
        return json_safe(obj.tolist())
    if isinstance(obj, (np.bool_, bool)):
        return bool(obj)
    if isinstance(obj, (np.integer,)):
        return int(obj)
    if isinstance(obj, (np.floating, float)):
        f = float(obj)
        return f if math.isfinite(f) else None
    return obj


def public_report(report):
    """نسخهٔ JSON-سازگار گزارش (حذف کلیدهای خصوصی + پاک‌سازی NaN)."""
    return json_safe({k: v for k, v in report.items() if not k.startswith("_")})


# ============================================================================
# CLI
# ============================================================================

def main():
    ap = argparse.ArgumentParser(description="بالاترین/پایین‌ترین نت و گسترهٔ صدا")
    ap.add_argument("file")
    ap.add_argument("--denoise", action="store_true", help="کاهش نویز پس‌زمینه")
    ap.add_argument("--output", "-o", default=None, help="مسیر گزارش JSON")
    ap.add_argument("--no-plot", action="store_true")
    args = ap.parse_args()

    rep = analyze_range(args.file, denoise=args.denoise, make_plot=not args.no_plot)
    pub = public_report(rep)

    r = pub["range"]
    hi, lo = r["highest"], r["lowest"]
    print("─" * 52)
    if hi:
        print(f"بالاترین نت : {hi['note_name']}  ({hi['f0_hz']} Hz)")
    if lo:
        print(f"پایین‌ترین نت: {lo['note_name']}  ({lo['f0_hz']} Hz)")
    print(f"گسترهٔ صدا  : {r['span_semitones']} نیم‌پرده "
          f"({r['span_octaves']} اکتاو = {r['full_octaves']} اکتاو کامل + "
          f"{r['rest_semitones']} نیم‌پرده، {r.get('of_octave_pct')}٪ یک اکتاو)")
    print(f"مدت        : {pub['basic']['duration_sec']} ثانیه "
          f"| نت‌های تشخیص‌داده‌شده: {pub['basic']['num_notes_detected']}")
    print("─" * 52)

    if args.output:
        with open(args.output, "w", encoding="utf-8") as f:
            json.dump(pub, f, ensure_ascii=False, indent=2)
        print("گزارش JSON ذخیره شد:", args.output)


if __name__ == "__main__":
    main()
