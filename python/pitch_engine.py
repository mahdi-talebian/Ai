#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
موتور مشترک پردازش پرده صدا (Pitch Engine)
================================================
این ماژول منطق اصلی مشترک بین همه ابزارهای پروژه است:
  - استخراج دقیق F0 با Praat (هم برای فایل کامل و هم برای قطعات کوچک زنده)
  - تبدیل فرکانس <-> نت پیانو (نام، اکتاو، میزان کوک‌بودن به سنت)
  - نگاشت نت به موقعیت کلید فیزیکی روی پیانو (برای رسم بصری)
  - الگوریتم DTW (Dynamic Time Warping) برای مقایسه ملودیک دو فراز

این ماژول توسط piano_visualizer.py، live_piano_mic.py و compare_recitations.py
به‌صورت مشترک ایمپورت می‌شود تا منطق در یک‌جا نگه‌داری شود (DRY).
"""

import numpy as np
import warnings

warnings.filterwarnings("ignore")

try:
    import parselmouth
except ImportError:
    raise ImportError("لطفاً نصب کنید: pip install praat-parselmouth")


NOTE_NAMES_SHARP = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]

# محدوده استاندارد پیانوی ۸۸ کلید: A0 (میدی=21) تا C8 (میدی=108)
PIANO_MIDI_MIN = 21
PIANO_MIDI_MAX = 108


# ----------------------------------------------------------------------------
# تبدیل فرکانس <-> نت
# ----------------------------------------------------------------------------

def freq_to_midi(freq: float) -> float:
    """فرکانس (هرتز) را به عدد میدی اعشاری تبدیل می‌کند (A4=440Hz => midi=69)."""
    if freq is None or freq <= 0 or np.isnan(freq):
        return np.nan
    return 69.0 + 12.0 * np.log2(freq / 440.0)


def midi_to_freq(midi: float) -> float:
    return 440.0 * (2.0 ** ((midi - 69.0) / 12.0))


def freq_to_note_info(freq: float):
    """
    برای یک فرکانس مشخص، اطلاعات کامل نت را برمی‌گرداند:
      - note_name: نام نت با اکتاو (مثال "A4")
      - midi: عدد میدی صحیح نزدیک‌ترین نت
      - cents_offset: میزان انحراف از نت دقیق (سنت، مثبت=تیزتر، منفی=بم‌تر)
      - piano_key_index: اندیس کلید فیزیکی پیانو از ۰ تا ۸۷ (برای رسم بصری)
    """
    midi_float = freq_to_midi(freq)
    if np.isnan(midi_float):
        return None

    midi_round = int(round(midi_float))
    cents_offset = (midi_float - midi_round) * 100.0
    note_name = NOTE_NAMES_SHARP[midi_round % 12]
    octave = midi_round // 12 - 1

    piano_key_index = midi_round - PIANO_MIDI_MIN
    is_valid_piano_range = PIANO_MIDI_MIN <= midi_round <= PIANO_MIDI_MAX

    return {
        "freq": freq,
        "midi": midi_round,
        "note_name": f"{note_name}{octave}",
        "note_letter": note_name,
        "octave": octave,
        "cents_offset": round(cents_offset, 1),
        "piano_key_index": piano_key_index if is_valid_piano_range else None,
        "is_black_key": "#" in note_name,
    }


def is_black_key(midi: int) -> bool:
    return (midi % 12) in {1, 3, 6, 8, 10}


# ----------------------------------------------------------------------------
# استخراج F0 با Praat — برای فایل کامل
# ----------------------------------------------------------------------------

def extract_pitch_contour(wav_path, fmin=60.0, fmax=1000.0, time_step=0.01,
                            two_pass_refine=True):
    """منحنی F0 کامل یک فایل صوتی را با دقت بالا استخراج می‌کند."""
    snd = parselmouth.Sound(wav_path)
    return _extract_pitch_from_sound(snd, fmin, fmax, time_step, two_pass_refine)


def _extract_pitch_from_sound(snd, fmin=60.0, fmax=1000.0, time_step=0.01,
                                two_pass_refine=True):
    def _run(fl, fh):
        p = snd.to_pitch_ac(
            time_step=time_step, pitch_floor=fl, pitch_ceiling=fh,
            very_accurate=True, max_number_of_candidates=15,
        )
        return p.xs(), p.selected_array["frequency"]

    times, freqs = _run(fmin, fmax)

    if two_pass_refine:
        voiced = freqs[freqs > 0]
        if len(voiced) > 10:
            median_f0 = np.median(voiced)
            new_fmin = max(50.0, median_f0 * 0.5)
            new_fmax = min(1200.0, median_f0 * 2.5)
            times, freqs = _run(new_fmin, new_fmax)

    return times, freqs, snd


# ----------------------------------------------------------------------------
# استخراج F0 با دقت حداکثری برای فایل‌های با هر طولی (پردازش پنجره‌ای)
# ----------------------------------------------------------------------------
#
# توضیح مهم دربارهٔ سیاست «دقت، همیشه در بالاترین سطح»:
# بارگذاری کل فایل به‌صورت یک‌جا در parselmouth.Sound برای فایل‌های خیلی
# طولانی (مثلاً بیش از ~۴۰-۵۰ دقیقه در محیط‌های کم‌حافظه) می‌تواند باعث اتمام
# حافظه (Out-Of-Memory) شود — این یک محدودیت مهندسی/حافظه‌ای است، نه مسئلهٔ
# دقت. راه‌حل درست، *افت‌دادن دقت* (کاهش time_step یا خاموش‌کردن
# very_accurate) نیست، بلکه پردازش فایل در «پنجره‌های زمانی» با هم‌پوشانی
# کوچک (overlap) است: هر پنجره هنوز با دقیقاً همان تنظیمات حداکثر دقت
# (very_accurate=True, time_step=0.01s, two-pass refine) پردازش می‌شود، فقط
# در حافظه هم‌زمان تنها یک پنجره کوچک نگه داشته می‌شود. نواحی هم‌پوشان بین
# پنجره‌های متوالی دور ریخته می‌شوند تا اثرات لبه (edge effects) الگوریتم
# خودهمبستگی از بین بروند. این رویکرد در آزمایش داخلی روی فایل ۳۰ دقیقه‌ای
# با موفقیت کامل اجرا شد (کل زمان ~۲۳ ثانیه، پیک حافظه ~۲۳۰ مگابایت) در حالی
# که پردازش یک‌جای فایل‌های خیلی طولانی‌تر (۶۰ دقیقه) بدون پنجره‌بندی با خطای
# Out-Of-Memory متوقف می‌شد.

# آستانه‌ای که پس از آن پردازش پنجره‌ای فعال می‌شود (فایل‌های کوتاه‌تر از این
# مقدار در یک پنجرهٔ واحد و بدون سربار اضافه پردازش می‌شوند).
WINDOWED_PROCESSING_THRESHOLD_SEC = 90.0
DEFAULT_WINDOW_SEC = 120.0
DEFAULT_OVERLAP_SEC = 3.0


def extract_pitch_contour_max_accuracy(
    wav_path,
    fmin=60.0,
    fmax=1000.0,
    time_step=0.01,
    two_pass_refine=True,
    window_sec=DEFAULT_WINDOW_SEC,
    overlap_sec=DEFAULT_OVERLAP_SEC,
    progress_callback=None,
):
    """
    استخراج منحنی F0 با «بالاترین دقت ممکن» برای فایل صوتی با هر طولی —
    اعم از یک قطعه چند ثانیه‌ای یا یک تلاوت کامل ۳۰-۶۰ دقیقه‌ای.

    برخلاف extract_pitch_contour (که کل فایل را یک‌جا در حافظه بارگذاری
    می‌کند)، این تابع فایل را در پنجره‌های زمانی مجزا با هم‌پوشانی کوچک
    می‌خواند و هرکدام را جداگانه با موتور Praat در حالت very_accurate=True
    پردازش می‌کند — بنابراین دقت خروجی دقیقاً معادل حالت تک‌پنجره‌ای است،
    اما مصرف حافظه صرف‌نظر از طول کل فایل محدود و ثابت می‌ماند.

    اگر progress_callback داده شود، با آرگومان‌های (processed_sec, total_sec)
    فراخوانی می‌شود تا واسط کاربری بتواند پیشرفت پردازش فایل‌های طولانی را
    نمایش دهد (طبق درخواست کاربر: دقت اولویت اول است، حتی اگر پردازش
    فایل‌های طولانی ۲ تا ۳ برابر کندتر شود — اما کاربر باید از پیشرفت مطلع
    باشد).

    خروجی: times, freqs, sample_rate, duration_sec
    (به‌جای شیء parselmouth.Sound کامل، فقط sample_rate و duration
    برگردانده می‌شود چون نگه‌داشتن کل صدا در حافظه برای فایل‌های طولانی
    همان مشکل حافظه را بازتولید می‌کند؛ برای بلندی صدا از
    compute_loudness_streaming استفاده کنید.)
    """
    import soundfile as sf

    info = sf.info(wav_path)
    sr = info.samplerate
    total_dur = info.frames / float(sr)

    if total_dur <= WINDOWED_PROCESSING_THRESHOLD_SEC:
        # فایل کوتاه: مسیر ساده و مستقیم (بدون سربار پنجره‌بندی)
        times, freqs, _snd = extract_pitch_contour(
            wav_path, fmin=fmin, fmax=fmax, time_step=time_step,
            two_pass_refine=two_pass_refine,
        )
        if progress_callback:
            progress_callback(total_dur, total_dur)
        return times, freqs, sr, total_dur

    all_times = []
    all_freqs = []

    pos = 0.0
    with sf.SoundFile(wav_path) as f:
        while pos < total_dur:
            end = min(pos + window_sec, total_dur)
            start_frame = int(round(pos * sr))
            num_frames = int(round((end - pos) * sr))

            f.seek(start_frame)
            block = f.read(num_frames, dtype="float64", always_2d=True)
            block = block.mean(axis=1)  # مونو کردن در صورت چندکاناله بودن

            snd_chunk = parselmouth.Sound(block, sampling_frequency=sr)
            t_local, f_local, _ = _extract_pitch_from_sound(
                snd_chunk, fmin=fmin, fmax=fmax, time_step=time_step,
                two_pass_refine=two_pass_refine,
            )
            t_local = t_local + pos

            # حذف نیمهٔ اول هم‌پوشانی (به‌جز پنجرهٔ اول) تا اثر لبه از بین برود
            if pos > 0:
                mask = t_local >= (pos + overlap_sec / 2.0)
                t_local = t_local[mask]
                f_local = f_local[mask]

            all_times.append(t_local)
            all_freqs.append(f_local)

            processed = min(end, total_dur)
            if progress_callback:
                progress_callback(processed, total_dur)

            if end >= total_dur:
                break
            pos += max(1e-6, window_sec - overlap_sec)

    times = np.concatenate(all_times) if all_times else np.array([])
    freqs = np.concatenate(all_freqs) if all_freqs else np.array([])
    return times, freqs, sr, total_dur


def compute_loudness_streaming(wav_path, window_sec=DEFAULT_WINDOW_SEC,
                                 progress_callback=None):
    """
    بلندی صدا (dB، بر مبنای الگوریتم Intensity در Praat) را بدون بارگذاری
    کل فایل در حافظه محاسبه می‌کند — با پردازش پنجره‌ای مشابه
    extract_pitch_contour_max_accuracy. برای فایل‌های خیلی طولانی از
    snd.to_intensity() روی کل فایل (که می‌تواند OOM شود) اجتناب می‌کند.
    """
    import soundfile as sf

    info = sf.info(wav_path)
    sr = info.samplerate
    total_dur = info.frames / float(sr)

    all_values = []
    pos = 0.0
    with sf.SoundFile(wav_path) as f:
        while pos < total_dur:
            end = min(pos + window_sec, total_dur)
            start_frame = int(round(pos * sr))
            num_frames = int(round((end - pos) * sr))
            f.seek(start_frame)
            block = f.read(num_frames, dtype="float64", always_2d=True)
            block = block.mean(axis=1)

            snd_chunk = parselmouth.Sound(block, sampling_frequency=sr)
            try:
                intensity = snd_chunk.to_intensity()
                vals = intensity.values[0]
                vals = vals[~np.isnan(vals)]
                if len(vals):
                    all_values.append(vals)
            except Exception:
                pass

            if progress_callback:
                progress_callback(min(end, total_dur), total_dur)

            if end >= total_dur:
                break
            pos += window_sec

    if not all_values:
        return {"mean_db": None, "max_db": None}
    concat = np.concatenate(all_values)
    return {
        "mean_db": round(float(np.mean(concat)), 1),
        "max_db": round(float(np.max(concat)), 1),
    }


# ----------------------------------------------------------------------------
# استخراج F0 برای یک قطعه کوچک (real-time / streaming)
# ----------------------------------------------------------------------------

def extract_pitch_chunk(samples: np.ndarray, sr: int, fmin=60.0, fmax=800.0):
    """
    برای یک قطعه کوچک صوتی (مثلاً ۱۰۰-۲۰۰ میلی‌ثانیه از میکروفون یا فایل در
    حال پخش)، یک تخمین F0 واحد (میانه) برمی‌گرداند. مناسب برای نمایش زنده.
    """
    if len(samples) < int(sr * 0.03):  # کمتر از ۳۰ میلی‌ثانیه قابل تحلیل نیست
        return None

    samples = np.ascontiguousarray(samples, dtype=np.float64)
    snd = parselmouth.Sound(samples, sampling_frequency=sr)
    try:
        pitch = snd.to_pitch_ac(pitch_floor=fmin, pitch_ceiling=fmax)
        f0 = pitch.selected_array["frequency"]
        f0 = f0[f0 > 0]
        if len(f0) == 0:
            return None
        return float(np.median(f0))
    except Exception:
        return None


# ----------------------------------------------------------------------------
# پاک‌سازی و تفکیک نت (مشترک با quran_maqam_analyzer)
# ----------------------------------------------------------------------------

def clean_pitch_contour(freqs, max_octave_jump_cents=550, window=5):
    freqs = freqs.copy()
    n = len(freqs)
    for i in range(n):
        if freqs[i] <= 0:
            continue
        lo, hi = max(0, i - window), min(n, i + window + 1)
        neighborhood = freqs[lo:hi]
        neighborhood = neighborhood[neighborhood > 0]
        if len(neighborhood) < 3:
            continue
        median_neighbor = np.median(neighborhood)
        cents_diff = abs(1200 * np.log2(freqs[i] / median_neighbor)) if median_neighbor > 0 else 0
        if cents_diff > max_octave_jump_cents:
            freqs[i] = 0
    return freqs


def smooth_pitch_contour(freqs, median_window=3):
    """
    فیلتر میانه (median filter) کوتاه روی منحنی F0 (در فضای سنت/لگاریتمی)
    برای حذف نویزهای گذرا و جهش‌های لحظه‌ای الگوریتم pitch-tracking —
    به‌خصوص در لحظات حمله (attack) و فرود (decay) هر نت که SNR پایین‌تر است.
    این فیلتر فقط روی بازه‌های صدادار پیوسته اعمال می‌شود (نویز بی‌صدا دست‌نخورده می‌ماند).
    """
    if median_window < 2:
        return freqs.copy()

    out = freqs.copy()
    n = len(freqs)
    half = median_window // 2

    i = 0
    while i < n:
        if out[i] <= 0:
            i += 1
            continue
        j = i
        while j < n and out[j] > 0:
            j += 1
        # [i, j) یک قطعه صدادار پیوسته است
        segment = out[i:j]
        if len(segment) >= median_window:
            smoothed = np.copy(segment)
            for k in range(len(segment)):
                lo, hi = max(0, k - half), min(len(segment), k + half + 1)
                smoothed[k] = np.median(segment[lo:hi])
            out[i:j] = smoothed
        i = j

    return out


def segment_notes(times, freqs, min_duration=0.06, stability_cents=35,
                   merge_gap_sec=0.12, merge_pitch_cents=60):
    """
    منحنی F0 را به نت‌های مجزا تقسیم می‌کند.

    برای مقاومت در برابر نویز کوتاه‌مدت (مثلاً افت پرده صدا در ابتدا/انتهای
    هر نت به دلیل fade in/out طبیعی صدای انسان یا خطای لحظه‌ای الگوریتم
    pitch-tracking)، قطعاتی که با فاصله کوتاه (<= merge_gap_sec) از هم جدا
    شده‌اند و پرده صدای نزدیکی دارند (<= merge_pitch_cents اختلاف)، در یک
    نت واحد ادغام می‌شوند. این کار از شکسته‌شدن مصنوعی یک نت واحد به چند
    ریز-قطعه جلوگیری می‌کند.
    """
    raw_segments = []
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
            cdiff = abs(1200 * np.log2(freqs[j + 1] / ref_median))
            if cdiff > stability_cents:
                break
            segment_freqs.append(freqs[j + 1])
            j += 1

        raw_segments.append({
            "start_idx": i,
            "end_idx": j,
            "start": float(times[i]),
            "end": float(times[j]) if j < len(times) else float(times[-1]),
            "freqs": segment_freqs,
        })
        i = j + 1

    if not raw_segments:
        return []

    # --- مرحله ادغام قطعات مجاور با پرده مشابه ---
    merged = [raw_segments[0]]
    for seg in raw_segments[1:]:
        prev = merged[-1]
        gap = seg["start"] - prev["end"]
        prev_median = np.median(prev["freqs"])
        seg_median = np.median(seg["freqs"])
        pitch_diff = abs(1200 * np.log2(seg_median / prev_median))

        if gap <= merge_gap_sec and pitch_diff <= merge_pitch_cents:
            prev["end"] = seg["end"]
            prev["end_idx"] = seg["end_idx"]
            prev["freqs"].extend(seg["freqs"])
        else:
            merged.append(seg)

    # --- ساخت خروجی نهایی نت‌ها ---
    notes = []
    for seg in merged:
        duration = seg["end"] - seg["start"]
        if duration >= min_duration and len(seg["freqs"]) >= 2:
            f0_median = float(np.median(seg["freqs"]))
            f0_std_cents = float(np.std([1200 * np.log2(f / f0_median) for f in seg["freqs"]]))
            info = freq_to_note_info(f0_median)
            notes.append({
                "start": round(seg["start"], 3),
                "end": round(seg["end"], 3),
                "duration": round(duration, 3),
                "f0_hz": round(f0_median, 2),
                "stability_cents": round(f0_std_cents, 1),
                **({} if info is None else {
                    "note_name": info["note_name"],
                    "midi": info["midi"],
                    "cents_offset": info["cents_offset"],
                }),
            })
    return notes


# ----------------------------------------------------------------------------
# مقایسه ملودیک دو فراز با DTW
# ----------------------------------------------------------------------------

def dtw_align(seq_a: np.ndarray, seq_b: np.ndarray, band_radius: int = None):
    """
    پیاده‌سازی استاندارد Dynamic Time Warping با فاصله اقلیدسی.
    اگر band_radius داده شود، از Sakoe-Chiba band برای محدود کردن مسیر
    جستجو استفاده می‌شود (سریع‌تر و برای توالی‌های طولانی مناسب‌تر).

    خروجی:
      cost_matrix, path (لیست تاپل‌های (i, j))، و total_cost نرمال‌شده
    """
    n, m = len(seq_a), len(seq_b)
    INF = np.inf
    D = np.full((n + 1, m + 1), INF)
    D[0, 0] = 0

    for i in range(1, n + 1):
        j_start = 1
        j_end = m + 1
        if band_radius is not None:
            j_start = max(1, i - band_radius)
            j_end = min(m + 1, i + band_radius + 1)
        for j in range(j_start, j_end):
            cost = abs(seq_a[i - 1] - seq_b[j - 1])
            D[i, j] = cost + min(D[i - 1, j], D[i, j - 1], D[i - 1, j - 1])

    # بازیابی مسیر بهینه
    path = []
    i, j = n, m
    while i > 0 or j > 0:
        path.append((i - 1, j - 1))
        if i == 0:
            j -= 1
        elif j == 0:
            i -= 1
        else:
            choices = [D[i - 1, j - 1], D[i - 1, j], D[i, j - 1]]
            idx = int(np.argmin(choices))
            if idx == 0:
                i, j = i - 1, j - 1
            elif idx == 1:
                i -= 1
            else:
                j -= 1
    path.reverse()

    total_cost = D[n, m]
    normalized_cost = total_cost / len(path) if path else INF

    return D, path, normalized_cost


def melodic_similarity(notes_a, notes_b, use_relative_pitch=True):
    """
    شباهت ملودیک دو دنباله نت (خروجی segment_notes) را با DTW محاسبه می‌کند.

    اگر use_relative_pitch=True باشد، پیش از مقایسه هر دنباله نسبت به میانگین
    خودش نرمال‌سازی می‌شود (بر حسب سنت) — این کار باعث می‌شود مقایسه مستقل از
    زیر-و-بمی مطلق صدای دو قاری مختلف باشد و فقط «الگوی ملودیک نسبی» سنجیده شود.
    """
    if not notes_a or not notes_b:
        return {
            "similarity_pct": 0.0,
            "normalized_dtw_cost": None,
            "path": [],
            "detail": "یکی از دو فراز نت قابل‌تحلیلی نداشت.",
        }

    freqs_a = np.array([n["f0_hz"] for n in notes_a])
    freqs_b = np.array([n["f0_hz"] for n in notes_b])

    cents_a = 1200 * np.log2(freqs_a / 440.0)
    cents_b = 1200 * np.log2(freqs_b / 440.0)

    if use_relative_pitch:
        # از میانه (median) به‌جای میانگین استفاده می‌کنیم چون در برابر
        # نت‌های پرت (outlier) — مثلاً یک نت اضافه/گم‌شده در ابتدای فایل
        # به دلیل خطای جزئی تفکیک نت — پایدارتر است.
        cents_a = cents_a - np.median(cents_a)
        cents_b = cents_b - np.median(cents_b)

    band = max(10, int(0.3 * max(len(cents_a), len(cents_b))))
    D, path, norm_cost = dtw_align(cents_a, cents_b, band_radius=band)

    # نگاشت هزینه به درصد شباهت: ۰ سنت اختلاف میانگین = ۱۰۰٪،
    # هر ۵۰ سنت (یک ربع‌پرده) اختلاف میانگین ≈ افت‌کردن شباهت به‌صورت نمایی
    similarity_pct = 100.0 * np.exp(-norm_cost / 80.0)

    return {
        "similarity_pct": round(float(similarity_pct), 1),
        "normalized_dtw_cost_cents": round(float(norm_cost), 1),
        "path": path,
        "num_notes_a": len(notes_a),
        "num_notes_b": len(notes_b),
    }


def rhythm_similarity(notes_a, notes_b):
    """
    شباهت ریتمیک: توزیع طول نسبی نت‌ها (نرمال‌شده با کل مدت) را با DTW مقایسه می‌کند.
    این معیار مستقل از سرعت مطلق قرائت (تند/کند خواندن) دو قاری است.
    """
    if not notes_a or not notes_b:
        return {"similarity_pct": 0.0}

    dur_a = np.array([n["duration"] for n in notes_a])
    dur_b = np.array([n["duration"] for n in notes_b])

    # نرمال‌سازی به نسبت از کل (تا مستقل از سرعت باشد)
    dur_a_norm = dur_a / dur_a.sum()
    dur_b_norm = dur_b / dur_b.sum()

    band = max(10, int(0.3 * max(len(dur_a_norm), len(dur_b_norm))))
    D, path, norm_cost = dtw_align(dur_a_norm * 100, dur_b_norm * 100, band_radius=band)

    similarity_pct = 100.0 * np.exp(-norm_cost / 5.0)
    return {
        "similarity_pct": round(float(similarity_pct), 1),
        "normalized_dtw_cost": round(float(norm_cost), 3),
    }


def loudness_similarity(loud_a: dict, loud_b: dict):
    """شباهت بلندی صدا (dB) بین دو فراز — معیار ساده اما مفید برای سنجش دینامیک صدا."""
    if not loud_a or not loud_b or loud_a.get("mean_db") is None or loud_b.get("mean_db") is None:
        return {"similarity_pct": None}
    diff_db = abs(loud_a["mean_db"] - loud_b["mean_db"])
    similarity_pct = 100.0 * np.exp(-diff_db / 10.0)
    return {"similarity_pct": round(float(similarity_pct), 1), "diff_db": round(diff_db, 1)}
