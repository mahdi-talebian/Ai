#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
تشخیص فعالیت صدا (VAD) با Silero-VAD (ONNX)
============================================
مرزهای فراز/سکوت در تحلیلگر فعلی از «گاپ‌های unvoiced موتور Praat» به‌دست
می‌آید؛ روی ضبط‌های نویزی (میکروفون گوشی، پژواک حرم) Praat بخش‌هایی از گفتار
را unvoiced می‌زند و فرازها اشتباه می‌شکنند. Silero VAD یک مدل ~۲ مگابایتی
ONNX است که روی تک‌ترد CPU هر چانک ۳۰ms را در کمتر از ۱ms classificaion
می‌کند و به نویز مقاوم است.

این ماژول اختیاری است: اگر onnxruntime یا فایل مدل موجود نباشد،
`speech_intervals` مقدار None برمی‌گرداند و تحلیلگر به روش قبلی برمی‌گردد.

مدل: https://github.com/snakers4/silero-vad (MIT) — مسیر پیش‌فرض
server/models/silero_vad.onnx یا متغیر محیطی SILERO_VAD_ONNX.
"""

import os
from pathlib import Path

import numpy as np

_CANDIDATE_PATHS = [
    os.environ.get("SILERO_VAD_ONNX", ""),
    str(Path(__file__).resolve().parent.parent / "server" / "models" / "silero_vad.onnx"),
    os.path.expanduser("~/.cache/silero_vad.onnx"),
]

_sess = None
_sess_tried = False


def _get_session():
    global _sess, _sess_tried
    if _sess_tried:
        return _sess
    _sess_tried = True
    try:
        import onnxruntime as ort
    except ImportError:
        return None
    for p in _CANDIDATE_PATHS:
        if p and Path(p).exists():
            opts = ort.SessionOptions()
            opts.inter_op_num_threads = 1
            opts.intra_op_num_threads = 1
            _sess = ort.InferenceSession(p, opts, providers=["CPUExecutionProvider"])
            break
    return _sess


def speech_intervals(y: np.ndarray, sr: int, min_silence_sec: float = 0.25,
                     threshold: float = 0.5) -> list | None:
    """
    فهرست بازه‌های گفتار [(start, end), ...] به ثانیه.
    ورودی: سیگنال mono float32. خروجی None یعنی VAD در دسترس نیست.
    """
    sess = _get_session()
    if sess is None:
        return None

    # سیlero روی 16k (یا 8k) کار می‌کند
    if sr not in (8000, 16000):
        import soundfile as sf  # noqa: F401  (resample ساده با numpy)
        factor = 16000 / sr
        n = int(len(y) * factor)
        idx = np.clip((np.arange(n) / factor).astype(int), 0, len(y) - 1)
        y = y[idx]
        sr = 16000

    chunk = 512 if sr == 16000 else 256
    ctx = 64 if sr == 16000 else 32   # مدل v6 ONNX: ورودی = کانتکست + فریم
    y = y.astype(np.float32)
    pad = (-len(y)) % chunk
    if pad:
        y = np.concatenate([y, np.zeros(pad, np.float32)])

    in_names = [i.name for i in sess.get_inputs()]
    probs = []
    # مدل‌های جدید silero: ورودی‌ها (input, state, sr) و خروجی (output, stateN)
    state = np.zeros([d if isinstance(d, int) else 1 for d in sess.get_inputs()[1].shape], np.float32)
    sr_arr = np.array(sr, np.int64)

    prev_ctx = np.zeros(ctx, np.float32)
    for i in range(0, len(y), chunk):
        frame = y[i:i + chunk]
        x = np.concatenate([prev_ctx, frame])[np.newaxis, :].astype(np.float32)
        prev_ctx = frame[-ctx:].copy()
        feed = {"input": x, "state": state, "sr": sr_arr}
        out = sess.run(None, {n_: feed[n_] for n_ in in_names})
        probs.append(float(np.asarray(out[0]).ravel()[0]))
        state = out[1]

    p = np.array(probs)
    voiced = p > threshold
    # حذف اسپایک: حداقل ۳ چانک (~۹۶ms)
    voiced = np.convolve(voiced.astype(np.int8), np.ones(3, np.int8), "same") >= 2
    dur_chunk = chunk / sr
    intervals, start = [], None
    for i, v in enumerate(voiced):
        if v and start is None:
            start = i * dur_chunk
        elif not v and start is not None:
            if i * dur_chunk - start >= 0.12:
                intervals.append((round(start, 3), round(i * dur_chunk, 3)))
            start = None
    if start is not None:
        intervals.append((round(start, 3), round(len(voiced) * dur_chunk, 3)))
    # ادغام فاصله‌های کمتر از min_silence_sec
    merged = []
    for iv in intervals:
        if merged and iv[0] - merged[-1][1] < min_silence_sec:
            merged[-1] = (merged[-1][0], iv[1])
        else:
            merged.append(list(iv))
    return [tuple(m) for m in merged]


def pauses_from_intervals(intervals, duration_sec, min_pause: float = 0.25):
    """از بازه‌های گفتار، سکوت‌های میان آن‌ها را می‌سازد (هم‌شکل detect_pauses)."""
    pauses = []
    prev_end = 0.0
    for (s, e) in intervals:
        if s - prev_end >= min_pause:
            pauses.append({"start": round(prev_end, 3), "end": round(s, 3),
                           "duration": round(s - prev_end, 3)})
        prev_end = e
    if duration_sec - prev_end >= min_pause:
        pauses.append({"start": round(prev_end, 3), "end": round(duration_sec, 3),
                       "duration": round(duration_sec - prev_end, 3)})
    return pauses
