#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
همگام‌سازی آیات با تلاوت (Ayah Sync)
=====================================
با مدل ASR تخصصی قرآن (tarteel-ai/whisper-base-ar-quran، WER ≈ 5.75%) متن
تلاوت را بازنویسی می‌کند و هر بخش زمانی (segment) را با تطبیقファزی روی متن
مصحف به یک بازهٔ آیه نگاشت می‌دهد. خروجی: فهرست {ayah_start, ayah_end,
start_sec, end_sec, text, score} برای نمایش هم‌گام با موج صوتی در وب.

این ماژول «اختیاری» است: اگر torch/transformers یا وزن مدل موجود نباشد
(مثلاً روی سرور سبک یا آفلاین)، سرور پیام روشن برمی‌گرداند و بقیهٔ
قابلیت‌ها کار می‌کنند.

مدل (در استقرار):
    pip install torch transformers
    huggingface-cli download tarteel-ai/whisper-base-ar-quran --local-dir server/models/whisper-quran
"""

import json
import re
import unicodedata
from difflib import SequenceMatcher
from pathlib import Path

DATA_PATH = Path(__file__).resolve().parent / "data" / "quran_arabic.json"

_DIAC = re.compile(r"[\u064B-\u065F\u0670\u06D6-\u06ED\u0640]")


def normalize_ar(text: str) -> str:
    """حذف اعراب/تطویل و یکسان‌سازی الف/ی/ه برای تطبیق متنی مقاوم."""
    t = unicodedata.normalize("NFKD", text or "")
    t = _DIAC.sub("", t)
    t = (t.replace("أ", "ا").replace("إ", "ا").replace("آ", "ا")
          .replace("ى", "ي").replace("ة", "ه").replace("ٱ", "ا"))
    return re.sub(r"\s+", " ", t).strip()


def load_quran():
    rows = json.load(open(DATA_PATH, encoding="utf-8"))
    return [(r["key"], r["text"], normalize_ar(r["text"])) for r in rows]


def _find_surah_range(ayahs, surah: int):
    idx = [i for i, (k, _, _) in enumerate(ayahs) if int(k.split(":")[0]) == surah]
    return (idx[0], idx[-1]) if idx else (0, -1)


def transcribe(audio_path: str, progress=None):
    """بخش‌های زمانی‌دار whisper برمی‌گرداند؛ بدون torch/مدل -> raise."""
    from transformers import pipeline  # ImportError عمدی به بیرون
    model_dir = _model_dir()
    if model_dir is None:
        raise RuntimeError("MODEL_MISSING")
    pipe = pipeline("automatic-speech-recognition", model=str(model_dir),
                    device="cpu")
    out = pipe(audio_path, return_timestamps=True,
               chunk_length_s=30, stride_length_s=5, batch_size=4)
    segs = [{"text": s["text"], "start": float(s["timestamp"][0] or 0),
             "end": float(s["timestamp"][1] or s["timestamp"][0] or 0)}
            for s in out.get("chunks") or []]
    return segs


def _model_dir():
    import os
    cand = os.environ.get("AYAH_SYNC_MODEL", "")
    paths = ([Path(cand)] if cand else []) + [
        Path(__file__).resolve().parent / "models" / "whisper-quran",
        Path.home() / ".cache" / "whisper-quran",
    ]
    for p in paths:
        if (p / "config.json").exists():
            return p
    return None


def model_available() -> bool:
    try:
        import torch  # noqa: F401
        import transformers  # noqa: F401
    except ImportError:
        return False
    return _model_dir() is not None


def align_segments(segs, ayahs, window: int = 12):
    """
    نگاشت یکنوا (monotonic) بخش‌های whisper به بازهٔ آیات:
    اشاره‌گر روی متن مصحف فقط جلو می‌رود؛ برای هر segment بهترین پنجرهٔ
    پیوستهٔ آیات (۱ تا window آیهٔ پیش‌رو) با نسبت شباهت SequenceMatcher
    انتخاب می‌شود. امتیاز زیر آستانه -> همان اشاره‌گر قبلی (تکرار/تجویید).
    """
    results, ptr = [], 0
    n = len(ayahs)
    for s in segs:
        st = normalize_ar(s["text"])
        if len(st) < 3:
            continue
        best, best_score = None, 0.0
        hi = min(n, ptr + window)
        for i in range(ptr, hi):
            acc = ayahs[i][2]
            for j in range(i + 1, min(hi + 6, n + 1)):
                if j > i + 1:
                    acc = acc + " " + ayahs[j - 1][2]
                score = SequenceMatcher(None, st, acc).ratio()
                if score > best_score:
                    best_score, best = score, (i, j - 1)
                if len(acc) > len(st) * 1.6:
                    break
        if best and best_score >= 0.42:
            ptr = max(ptr, best[0])
            results.append({
                "ayah_start": ayahs[best[0]][0],
                "ayah_end": ayahs[best[1]][0],
                "start_sec": round(s["start"], 2),
                "end_sec": round(s["end"], 2),
                "text": " ".join(a[1] for a in ayahs[best[0]:best[1] + 1]),
                "score": round(best_score, 3),
            })
        elif results:
            results[-1]["end_sec"] = round(s["end"], 2)
    # ادغام ورودی‌های متوالی با همان بازهٔ آیه
    merged = []
    for r in results:
        if merged and (merged[-1]["ayah_start"], merged[-1]["ayah_end"]) == (r["ayah_start"], r["ayah_end"]):
            merged[-1]["end_sec"] = r["end_sec"]
            merged[-1]["score"] = max(merged[-1]["score"], r["score"])
        else:
            merged.append(r)
    return merged


def analyze_ayahs(audio_path: str, progress=None):
    ayahs = load_quran()
    if progress:
        progress("transcribe", 0.1)
    segs = transcribe(audio_path)
    if progress:
        progress("align", 0.7)
    return align_segments(segs, ayahs)
