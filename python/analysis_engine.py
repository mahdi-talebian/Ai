#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🎯 موتور استاندارد تحلیل پردهٔ صدا (Analysis Engine)
=====================================================
هدف این ماژول یک چیز است: **همیشه یک نتیجهٔ یکسان و دقیق، مستقل از فایل ورودی.**

پیش از این، دقت تحلیل به خودِ آپلود بستگی داشت: یک فایل با نرخ نمونهٔ ۴۸k،
یک استریوی با فاز معکوس، یک ضبط کم‌صدا یا پر از رامبل، یا فایل MP3 فشرده
می‌توانست نتِ اکتاو بالا/پایین یا نت‌های اضافی بدهد. ریشهٔ مشکل «نبود
استانداردسازی ورودی» و «نبود اصلاح خطای اکتاو در سطح کل خط صدا» بود، نه دقت
خود Praat.

مسیر استاندارد (برای هر فایل، بدون استثنا):

  ۱) رمزگشایی مطمئن (soundfile، و در صورت شکست ffmpeg — حتی ویدیو)
  ۲) مونو کردن «فازمحافظ» (اگر دو کانال معکوس هم باشند، میانگین آن‌ها صدا را
     حذف می‌کند؛ در آن حالت یک کانال انتخاب می‌شود)
  ۳) یکسان‌سازی نرخ نمونه روی ۲۲٬۰۵۰ هرتز (نایکوئیست ۱۱ کیلوهرتز، کافی برای F0)
  ۴) حذف DC و فیلتر بالاگذر ۴۵ هرتز (رامبل/هوم برق/باد میکروفون)
  ۵) نرمال‌سازی دامنه به ۰٫۹ پیک (ضبط‌های کم‌صدا و بلند یکسان می‌شوند)
  ۶) محدودهٔ جست‌وجوی F0 «سازگار با صدا» (تخمین سریع + حاشیه)، حداقل دو اکتاو
  ۷) Praat pitch-ac با very_accurate + پاس دوگانه + گام زمانی ۵ میلی‌ثانیه
  ۸) اصلاح خطای اکتاو با شاهدِ طیفی زیرهارمونیک (روی کل خط صدا، نه فقط جهش‌های محلی)
  ۹) هم‌سنجی با pYIN (برای فایل‌های تا ۳ دقیقه) + ادغام و محاسبهٔ اطمینان هر فریم
 ۱۰) هموارسازی و تفکیک نت با هیسترزیس + اطمینان هر نت

خروجی این ماژول شامل «گزارش کیفیت» است تا کاربر بداند نتیجه تا چه حد
قابل اتکاست (و اگر نه، بداند مشکل از فایل ورودی است، نه از موتور).
"""

import os
import shutil
import subprocess

import numpy as np

import warnings

warnings.filterwarnings("ignore")

TARGET_SR = 22050          # نرخ نمونهٔ استاندارد تحلیل
HIGHPASS_HZ = 45.0         # حذف رامبل/هوم/باد
PEAK_TARGET = 0.9          # نرمال‌سازی پیک
MIN_F0 = 55.0
MAX_F0 = 1200.0
TIME_STEP_SHORT = 0.005    # ≤۹۰ ثانیه: دقت دوبرابر ویبراتو/گلیساندو
TIME_STEP_LONG = 0.01
SHORT_FILE_SEC = 90.0
PYIN_MAX_SEC = 180.0       # برای فایل‌های بلندتر، pYIN اجرا نمی‌شود (هزینه)
WINDOW_SEC = 120.0
OVERLAP_SEC = 3.0


# ============================================================================
# ۱) رمزگشایی و استانداردسازی صدا
# ============================================================================

def _ffmpeg_exe():
    for cand in (os.environ.get("FFMPEG_BIN"), shutil.which("ffmpeg")):
        if cand and os.path.exists(cand):
            return cand
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        return None


def _decode_ffmpeg(path, out_wav, sr=TARGET_SR):
    ff = _ffmpeg_exe()
    if not ff:
        raise RuntimeError("ffmpeg پیدا نشد — برای خواندن این فایل لازم است.")
    subprocess.run([ff, "-y", "-loglevel", "error", "-i", path, "-vn",
                    "-ac", "1", "-ar", str(int(sr)), out_wav],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)
    return out_wav


def _read_audio(path):
    """(samples float64 مونو یا مالتی‌کانال, sr) — با پشتیبان ffmpeg."""
    import soundfile as sf

    try:
        data, sr = sf.read(path, dtype="float64", always_2d=True)
        return data, int(sr)
    except Exception:
        tmp = os.path.join(os.path.dirname(os.path.abspath(path)) or ".",
                           f"_decode_{os.getpid()}.wav")
        _decode_ffmpeg(path, tmp)
        data, sr = sf.read(tmp, dtype="float64", always_2d=True)
        try:
            os.unlink(tmp)
        except Exception:
            pass
        return data, int(sr)


def _safe_downmix(data):
    """
    مونو کردن فازمحافظ.

    اگر دو کانال هم‌فاز باشند، میانگین درست است. اگر فاز معکوس باشند
    (کابل/آداپتور معیوب یا رکورد دولایه)، میانگین صدا را حذف می‌کند و
    تحلیل بی‌معنا می‌شود؛ در آن حالت کانال قوی‌تر انتخاب می‌شود.
    """
    if data.ndim == 1 or data.shape[1] == 1:
        return data.reshape(-1), "mono"

    ch = data.shape[1]
    # همبستگی فاز بین کانال ۰ و ۱ (اگر قوی و منفی بود = فاز معکوس)
    a, b = data[:, 0], data[:, 1]
    denom = (np.std(a) * np.std(b)) + 1e-12
    corr = float(np.mean((a - a.mean()) * (b - b.mean())) / denom)

    if corr < -0.35:
        rms = np.sqrt(np.mean(data ** 2, axis=0))
        best = int(np.argmax(rms))
        return data[:, best], f"channel{best + 1}_phase_safe"

    return data.mean(axis=1), "mean"


def _decode_to_wav(path, out_wav, sr):
    """
    هر ورودی (صدا یا ویدیو) را با ffmpeg به WAV شناور ۳۲بیتی با **همان
    تعداد کانال** و **نرخ نمونهٔ استاندارد** تبدیل می‌کند.

    چرا ffmpeg و نه librosa؟ چون رمزگشایی جریانی است (فایل چهل‌دقیقه‌ای هم
    حافظه را پر نمی‌کند) و همهٔ ورودی‌ها از یک «درِ ورودی» یکسان عبور
    می‌کنند: mp3/wav/m4a/mp4/opus همه به یک نمایش میانی یکسان می‌رسند.
    """
    ff = _ffmpeg_exe()
    if not ff:
        return False
    try:
        subprocess.run([ff, "-y", "-loglevel", "error", "-i", str(path),
                        "-vn", "-map", "0:a:0", "-ar", str(int(sr)),
                        "-c:a", "pcm_f32le", str(out_wav)],
                       check=True, stdout=subprocess.DEVNULL,
                       stderr=subprocess.PIPE)
        return True
    except Exception:
        return False


def _probe_downmix(wav_path, sr, probe_sec=60.0):
    """تشخیص فازمحافظ بودن استریو روی ابتدای فایل (۶۰ ثانیهٔ اول کافی است)."""
    import soundfile as sf

    with sf.SoundFile(wav_path) as f:
        n = min(len(f), int(probe_sec * sr))
        if n <= 0:
            return "mean", None
        f.seek(0)
        data = f.read(frames=n, dtype="float64", always_2d=True)
    if data.shape[1] == 1:
        return "mono", None
    a, b = data[:, 0], data[:, 1]
    denom = (np.std(a) * np.std(b)) + 1e-12
    corr = float(np.mean((a - a.mean()) * (b - b.mean())) / denom)
    if corr < -0.35:
        rms = np.sqrt(np.mean(data ** 2, axis=0))
        return f"ch{int(np.argmax(rms))}", round(corr, 3)
    return "mean", round(corr, 3)


def _iter_mono(wav_path, mode, blocksize=1 << 20):
    """بلوک‌های مونو (float64) با حالت تبدیل انتخاب‌شده."""
    import soundfile as sf

    with sf.SoundFile(wav_path) as f:
        while True:
            d = f.read(frames=blocksize, dtype="float32", always_2d=True)
            if len(d) == 0:
                break
            if d.shape[1] == 1:
                y = d[:, 0]
            elif mode.startswith("ch"):
                y = d[:, int(mode[2:])]
            else:
                y = d.mean(axis=1)
            yield np.asarray(y, dtype=np.float64)


def prepare_audio(path, work_dir=None, denoise=False, target_sr=TARGET_SR):
    """
    هر ورودی را به یک «مبنای ثابت» تبدیل می‌کند:

        مونو (فازمحافظ) · ۲۲٬۰۵۰ هرتز · بدون DC · بالاگذر ۴۵ هرتز · پیک ۰٫۹

    همهٔ پردازش‌ها بلوکی‌اند (بدون بارگذاری کل فایل در حافظه)، بنابراین
    فایل چهل‌دقیقه‌ای هم مثل یک کلیپ ۵ ثانیه‌ای پردازش می‌شود و خروجی
    برای همهٔ نرخ‌های نمونه/کانال/فرمت‌ها یکسان است.

    خروجی: dict(path, sr, duration, diag)
    """
    import soundfile as sf
    from scipy.signal import butter, sosfilt

    work_dir = work_dir or (os.path.dirname(os.path.abspath(path)) or ".")
    os.makedirs(work_dir, exist_ok=True)
    out_path = os.path.join(work_dir, "audio_for_video.wav")
    dec_path = os.path.join(work_dir, f"_decoded_{os.getpid()}.wav")

    diag = {"sample_rate_used": target_sr, "denoise": bool(denoise)}

    # نرخ/کانال «فایل اصلی» (برای نمایش به کاربر) — پیش از رمزگشایی خوانده
    # می‌شود، چون ffmpeg خروجی را به ۲۲٬۰۵۰ تبدیل می‌کند و آن اطلاعات از
    # دست می‌رفت.
    try:
        inf = sf.info(str(path))
        diag["sample_rate_in"] = int(inf.samplerate)
        diag["channels_in"] = int(inf.channels)
    except Exception:
        pass

    ok = _decode_to_wav(path, dec_path, target_sr)
    if not ok:                       # بدون ffmpeg: تلاش با soundfile
        try:
            data, sr_in = sf.read(path, dtype="float64", always_2d=True)
            import librosa
            y = _safe_downmix(data)[0]
            if sr_in != target_sr:
                y = librosa.resample(y.astype(np.float32), orig_sr=sr_in,
                                     target_sr=target_sr, res_type="soxr_hq")
            sf.write(dec_path, np.asarray(y, dtype=np.float32), target_sr,
                     subtype="FLOAT")   # نسخهٔ میانی: شناور (بدون کوانتیزهٔ دوباره)
            diag["sample_rate_in"] = int(sr_in)
            diag["channels_in"] = int(data.shape[1])
            diag["decoder"] = "soundfile"
        except Exception as e:
            raise RuntimeError(f"رمزگشایی فایل ناموفق بود: {e}")
    else:
        diag["decoder"] = "ffmpeg"

    try:
        with sf.SoundFile(dec_path) as f:
            n_total = len(f)
            ch_in = f.channels
            sr_in_hdr = f.samplerate
        diag.setdefault("sample_rate_in", int(sr_in_hdr))
        diag.setdefault("channels_in", int(ch_in))
        diag["channels_decoded"] = int(ch_in)
        duration = n_total / float(target_sr)

        mode, corr = _probe_downmix(dec_path, target_sr)
        diag["downmix"] = ("mono" if mode == "mono" else
                           ("mean" if mode == "mean" else f"channel{mode[2:]}_phase_safe"))
        diag["channel_correlation"] = corr

        sos = butter(2, HIGHPASS_HZ / (target_sr / 2.0), btype="highpass", output="sos")

        if not denoise:
            # ── گذر ۱: آماره‌ها (DC، کلیپ، پیک پس از بالاگذر) ──
            n = 0
            ssum = 0.0
            clip = 0
            peak_raw = 0.0
            peak_hp = 0.0
            zi = np.zeros((sos.shape[0], 2))
            for y in _iter_mono(dec_path, mode):
                ssum += float(np.sum(y))
                n += len(y)
                peak_raw = max(peak_raw, float(np.max(np.abs(y))) if len(y) else 0.0)
                clip += int(np.sum(np.abs(y) >= 0.999))
                yh, zi = sosfilt(sos, y, zi=zi)
                peak_hp = max(peak_hp, float(np.max(np.abs(yh))) if len(yh) else 0.0)
            dc = ssum / max(1, n)

            # ── گذر ۲: نوشتن نسخهٔ استاندارد ──
            gain = (PEAK_TARGET / peak_hp) if peak_hp > 1e-6 else 0.0
            zi = np.zeros((sos.shape[0], 2))
            with sf.SoundFile(out_path, mode="w", samplerate=target_sr,
                              channels=1, subtype="PCM_16") as out:
                for y in _iter_mono(dec_path, mode):
                    yh, zi = sosfilt(sos, y - dc, zi=zi)
                    out.write((yh * gain).astype(np.float32))
        else:
            # ── کاهش نویز (نیازمند کل سیگنال؛ فقط برای فایل‌های معقول) ──
            y = np.concatenate(list(_iter_mono(dec_path, mode)))
            dc = float(np.mean(y))
            peak_raw = float(np.max(np.abs(y))) if len(y) else 0.0
            clip = int(np.sum(np.abs(y) >= 0.999))
            try:
                import noisereduce as nr
                y = np.asarray(nr.reduce_noise(y=y.astype(np.float32),
                                               sr=target_sr, stationary=False),
                               dtype=np.float64)
                diag["denoise"] = True
            except Exception:
                diag["denoise"] = False
            yh = sosfilt(sos, y - dc)
            peak_hp = float(np.max(np.abs(yh))) if len(yh) else 0.0
            gain = (PEAK_TARGET / peak_hp) if peak_hp > 1e-6 else 0.0
            sf.write(out_path, (yh * gain).astype(np.float32), target_sr,
                     subtype="PCM_16")

        diag.update({
            "dc_offset": round(dc, 5),
            "clipping_pct": round(100.0 * clip / max(1, n_total), 3),
            "peak_dbfs": round(20 * np.log10(max(peak_raw, 1e-9)), 1),
            "normalized_gain_db": round(20 * np.log10(gain), 1) if gain > 0 else 0.0,
            "duration_sec": round(duration, 2),
        })
        diag["snr_db"] = _estimate_snr_blocks(out_path, target_sr)
        diag["snr_method"] = "frame-percentile"
        diag["standard"] = {"sample_rate": target_sr, "channels": 1,
                            "highpass_hz": HIGHPASS_HZ, "peak": PEAK_TARGET}

        return {"path": out_path, "sr": target_sr, "duration": duration,
                "diag": diag}
    finally:
        try:
            os.unlink(dec_path)
        except Exception:
            pass


def _estimate_snr_blocks(wav_path, sr, frame=0.05, max_frames=60000):
    """
    SNR تقریبی: فاصلهٔ dB بین فریم‌های بلند و ساکت.

    اگر فایل یکنواخت باشد (نُت کشیده یا تلاوت بی‌مکث) «ساکت» وجود ندارد و
    این عدد بی‌معنی می‌شود؛ آن‌گاه None برمی‌گردد تا هشدار نویزِ نادرست به
    کاربر نشان داده نشود.
    """
    import soundfile as sf

    hop = max(1, int(sr * frame))
    with sf.SoundFile(wav_path) as f:
        total = len(f)
        step = hop * 4096
        rms_list = []
        pos = 0
        while pos < total and len(rms_list) * 1 < max_frames:
            f.seek(pos)
            d = f.read(frames=step, dtype="float32")
            if len(d) == 0:
                break
            m = len(d) // hop
            if m >= 1:
                fr = d[: m * hop].reshape(m, hop)
                rms_list.append(np.sqrt(np.mean(fr.astype(np.float64) ** 2, axis=1)))
            pos += step
    if not rms_list:
        return None
    rms = np.concatenate(rms_list) + 1e-12
    if len(rms) < 4:
        return None
    loud = float(np.percentile(rms, 90))
    quiet = float(np.percentile(rms, 10))
    snr = 20.0 * np.log10(loud / quiet)
    if snr < 2.0:
        return None
    return round(float(min(snr, 45.0)), 1)


def _estimate_snr(y, sr, frame=0.05):
    """
    SNR تقریبی: فاصلهٔ dB بین فریم‌های بلند و ساکت.

    اگر فایل یکنواخت باشد (مثلاً یک نُت کشیده یا تلاوت بی‌مکث) «ساکت» وجود
    ندارد و این عدد بی‌معنی می‌شود؛ در آن حالت None برمی‌گردد تا هشدار
    نویزِ نادرست به کاربر نشان داده نشود.
    """
    hop = max(1, int(sr * frame))
    n = len(y) // hop
    if n < 4:
        return None
    frames = y[: n * hop].reshape(n, hop)
    rms = np.sqrt(np.mean(frames ** 2, axis=1)) + 1e-12
    loud = float(np.percentile(rms, 90))
    quiet = float(np.percentile(rms, 10))
    if quiet <= 0 or loud <= 0:
        return None
    snr = 20.0 * np.log10(loud / quiet)
    if snr < 2.0:            # دامنهٔ دینامیکی تقریباً صفر ⇒ قابل تخمین نیست
        return None
    return round(float(min(snr, 45.0)), 1)


# ============================================================================
# ۲) استخراج F0 با Praat (تک‌پنجره و پنجره‌ای)
# ============================================================================

def _praat_pitch_on_sound(snd, fmin, fmax, time_step):
    """F0 + قدرت (strength) از Praat pitch-ac در حالت very_accurate."""
    p = snd.to_pitch_ac(time_step=time_step, pitch_floor=fmin,
                        pitch_ceiling=fmax, very_accurate=True,
                        max_number_of_candidates=20)
    sa = p.selected_array
    return p.xs(), np.asarray(sa["frequency"], dtype=float), \
        np.asarray(sa["strength"], dtype=float)


def estimate_f0_bounds(wav_path, sr, duration):
    """
    محدودهٔ جست‌وجوی F0 متناسب با همین صدا (زن/مرد/کودک).

    از چند نقطهٔ فایل نمونه می‌گیرد (نه فقط ابتدای آن) و بعد **با حاشیهٔ
    سخاوتمندانه** محدوده را باز می‌کند؛ ولی هرگز از سه اکتاو کمتر، چون
    محدودهٔ تنگ باعث می‌شود نت‌های بالا/پایین واقعی به نزدیک‌ترین لبه
    «چسبانده» شوند — همان چیزی که نتیجه را به فایل وابسته می‌کرد.
    """
    import librosa

    try:
        span = min(15.0, max(5.0, duration / 6.0))
        offsets = [0.0]
        if duration > span * 2:
            offsets = [max(0.0, duration * p - span / 2) for p in (0.1, 0.35, 0.6, 0.85)]
        vals_all = []
        for off in offsets:
            y, _ = librosa.load(wav_path, sr=sr, mono=True,
                                offset=float(off), duration=span)
            if len(y) < 2048:
                continue
            f0, _vflag, vprob = librosa.pyin(
                y, fmin=MIN_F0, fmax=1000.0, sr=sr, frame_length=2048,
                hop_length=256, fill_na=np.nan)
            ok = ~np.isnan(f0)
            if len(vprob) == len(f0):
                ok &= vprob > 0.3
            vals_all.append(f0[ok])
        vals = np.concatenate(vals_all) if vals_all else np.array([])
        vals = vals[np.isfinite(vals)]
        if len(vals) < 20:
            return MIN_F0, MAX_F0
        lo = float(np.percentile(vals, 3))
        hi = float(np.percentile(vals, 97))
        if hi <= lo:
            return MIN_F0, MAX_F0
        fmin = max(MIN_F0, lo / 2.2)
        fmax = min(MAX_F0, hi * 2.2)
        mid = float(np.sqrt(fmin * fmax))
        while fmax / fmin < 3.0:                 # حداقل سه اکتاو
            fmin = max(MIN_F0, fmin / 1.15)
            fmax = min(MAX_F0, fmax * 1.15)
            if fmin <= MIN_F0 and fmax >= MAX_F0:
                break
            if abs(np.log2(fmax / fmin) - 3.0) < 1e-9:
                break
        fmin = max(MIN_F0, float(round(fmin)))
        fmax = min(MAX_F0, float(round(fmax)))
        if fmax <= fmin * 1.5:
            return MIN_F0, MAX_F0
        return fmin, fmax
    except Exception:
        return MIN_F0, MAX_F0


def extract_pitch_praat(wav_path, sr, duration, fmin, fmax, time_step=0.01,
                        progress_callback=None):
    """استخراج F0 پنجره‌ای (حافظهٔ ثابت) با همان دقت حالت تک‌پنجره‌ای."""
    import parselmouth
    import soundfile as sf

    all_t, all_f, all_s = [], [], []

    if duration <= WINDOW_SEC:
        y, _ = sf.read(wav_path, dtype="float64", always_2d=True)
        y = y.mean(axis=1)
        snd = parselmouth.Sound(np.ascontiguousarray(y), sampling_frequency=sr)
        t, f, s = _praat_pitch_on_sound(snd, fmin, fmax, time_step)
        if progress_callback:
            progress_callback(duration, duration)
        return t, f, s

    pos = 0.0
    with sf.SoundFile(wav_path) as fh:
        while pos < duration:
            end = min(pos + WINDOW_SEC, duration)
            start_frame = int(round(pos * sr))
            n = int(round((end - pos) * sr))
            fh.seek(start_frame)
            block = fh.read(n, dtype="float64", always_2d=True).mean(axis=1)
            snd = parselmouth.Sound(np.ascontiguousarray(block), sampling_frequency=sr)
            t, f, s = _praat_pitch_on_sound(snd, fmin, fmax, time_step)
            t = t + pos
            if pos > 0:
                keep = t >= (pos + OVERLAP_SEC / 2.0)
                t, f, s = t[keep], f[keep], s[keep]
            all_t.append(t); all_f.append(f); all_s.append(s)
            if progress_callback:
                progress_callback(min(end, duration), duration)
            if end >= duration:
                break
            pos += max(1e-6, WINDOW_SEC - OVERLAP_SEC)

    return (np.concatenate(all_t), np.concatenate(all_f), np.concatenate(all_s))


# ============================================================================
# ۳) اصلاح خطای اکتاو با شاهد طیفی (ریشه‌ای‌ترین بخش)
# ============================================================================

LADDER = (0.25, 1.0 / 3.0, 0.5, 1.0, 2.0, 3.0, 4.0)


def _frame_scores(fh, sr, t_frames, base_f, ratios, win_sec=0.046, nfft=4096,
                  max_hz=3600.0, max_h=12):
    """
    امتیاز «هماهنگی هارمونیک» هر فرض نسبی، **برای هر فریم جداگانه**.

    چرا فریم‌به‌فریم و نه طیف میانگینِ ران؟ چون در ویبراتو/گلیساندو
    فرکانس در طول ران تغییر می‌کند و طیف میانگین «محو» می‌شود؛ آن‌وقت
    هارمونیک‌ها روی هم نمی‌افتند و امتیاز بی‌معنا می‌شود. با پنجرهٔ کوتاه
    و فرضِ نسبی به فرکانس همان فریم، هر لحظه جداگانه سنجیده می‌شود.

    خروجی: آرایهٔ (n_frames × n_ratios) از امتیاز در بازهٔ [-1, 0]
    """
    win_n = max(256, int(round(sr * win_sec)))
    t_frames = np.asarray(t_frames, dtype=float)
    base_f = np.asarray(base_f, dtype=float)
    ratios = np.asarray(ratios, dtype=float)
    if len(t_frames) == 0:
        return np.zeros((0, len(ratios)))

    n_total = len(fh)
    i0 = int(max(0, round((t_frames.min() - win_sec / 2) * sr)))
    i1 = int(min(n_total, round((t_frames.max() + win_sec / 2) * sr) + win_n))
    if i1 - i0 < win_n:
        return np.full((len(t_frames), len(ratios)), -1.0)
    fh.seek(i0)
    seg = np.asarray(fh.read(i1 - i0, dtype="float32", always_2d=False),
                     dtype=np.float32).reshape(-1)
    window = np.hanning(win_n).astype(np.float32)
    starts = np.round(t_frames * sr).astype(int) - i0 - win_n // 2
    starts = np.clip(starts, 0, max(0, len(seg) - win_n))
    idx = starts[:, None] + np.arange(win_n)[None, :]
    S = np.abs(np.fft.rfft(seg[idx] * window, n=nfft, axis=1))     # (nf, bins)
    bin_hz = sr / float(nfft)
    n_bins = S.shape[1]

    band_lo = max(1, int(MIN_F0 * 0.9 / bin_hz))
    band_hi = min(n_bins - 1, int(4000.0 / bin_hz))
    ref = np.max(S[:, band_lo:band_hi], axis=1) + 1e-12            # per frame

    pos = (base_f[:, None, None] * ratios[None, :, None]
           * np.arange(1, max_h + 1)[None, None, :]) / bin_hz
    valid = (pos >= 1) & (pos < n_bins - 2) & (pos * bin_hz <= max_hz)
    ip = np.clip(np.round(pos).astype(int), 1, n_bins - 2)
    rows = np.arange(len(t_frames))[:, None, None]
    peak = np.zeros(pos.shape, dtype=np.float32)
    for off in (-2, -1, 0, 1, 2):
        peak = np.maximum(peak, S[rows, np.clip(ip + off, 0, n_bins - 1)])
    peak = np.where(valid, peak, 0.0)

    db = 20.0 * np.log10(np.maximum(peak, 1e-12) / ref[:, None, None])
    s_k = np.clip(db / 25.0, -1.0, 0.0)
    w = 1.0 / np.arange(1, max_h + 1, dtype=float)
    w[0] = 0.0          # هارمونیک اول وزن ندارد: «گم‌شدن فرکانس پایه» (بلندگوی
    #                     گوشی، بالاگذر، صدای سینه‌ای ضعیف) طبیعی است و نباید
    #                     امتیاز f₀ واقعی را پایین بیاورد
    wb = np.broadcast_to(w[None, None, :], s_k.shape) * valid
    num = np.sum(s_k * wb, axis=2)
    den = np.sum(wb, axis=2)
    scores = np.where(den > 0.35, num / np.maximum(den, 1e-9), -1.0)
    return scores


def spectral_octave_fix(wav_path, sr, times, freqs, hop=None, win=0.046,
                        fmin=None, fmax=None):
    """
    اصلاح ریشه‌ای خطای «سطح هارمونیک» (اکتاو / پنجم / چهارم و معکوس‌ها).

    برای هر ران صدادار:
      ۱) طیف میانگین همان ران محاسبه می‌شود،
      ۲) از میان فرض‌های f×(۱/۴، ۱/۳، ۱/۲، ۱، ۲، ۳، ۴) فرضی که همهٔ
         هارمونیک‌هایش در طیف حاضرند انتخاب می‌شود (امتیاز هارمونیک)،
      ۳) همهٔ فریم‌های آن ران با همان نسبت اصلاح می‌شوند (نه جهش‌های محلی).

    خروجی: (freqs اصلاح‌شده, n_up, n_down, ratio هر فریم)
    """
    import soundfile as sf

    freqs = np.asarray(freqs, dtype=float)
    if len(freqs) == 0:
        return freqs, 0, 0, np.zeros(0)

    if hop is None:
        dt = np.median(np.diff(times)) if len(times) > 2 else 0.01
        hop = float(dt)
    fmin = float(fmin if fmin else MIN_F0)
    fmax = float(fmax if fmax else MAX_F0)

    voiced = freqs > 0
    out = freqs.copy()
    ratio = np.ones(len(freqs))
    n_up = n_down = 0

    # ران‌های پیوستهٔ صدادار (با تحمل شکاف ۸۰ میلی‌ثانیه‌ای)
    runs = []
    i, n = 0, len(freqs)
    while i < n:
        if not voiced[i]:
            i += 1
            continue
        j = i + 1
        while j < n and voiced[j] and (times[j] - times[j - 1]) < 0.08:
            j += 1
        if j - i >= 3 and (times[j - 1] - times[i]) >= 0.10:
            runs.append((i, j))
        i = j

    with sf.SoundFile(wav_path) as fh:
        for i, j in runs:
            seg_f = freqs[i:j]
            med = float(np.median(seg_f))
            if med <= 0:
                continue
            # حداکثر ۴۸ فریمِ یکنواخت‌پخش‌شده از ران نمونه‌گیری می‌شود
            stride = max(1, int(np.ceil((j - i) / 48.0)))
            kk = np.arange(i, j, stride)
            if len(kk) < 2:
                continue
            sc = _frame_scores(fh, sr, times[kk], freqs[kk], LADDER)
            if sc.size == 0:
                continue
            pooled = np.median(sc, axis=0)            # امتیاز هر نسبت برای ران
            best = float(np.max(pooled))
            if best < -0.5:
                # هیچ فرضی هارمونیک‌های این ران را توجیه نمی‌کند (نویز/بی‌صدا)
                continue
            # قاعدهٔ کلیدی: هر «مضرب» هارمونیکِ F0 هم همهٔ هارمونیک‌هایش
            # حاضرند (۲f₀، ۳f₀ …)، پس با پوشش تنها نمی‌شود f₀ را از مضربش
            # جدا کرد. ولی «زیرمضرب»ها هارمونیک‌های فرد خود را ندارند و
            # جریمه می‌شوند. بنابراین از میان فرض‌هایی که تقریباً به‌یک‌اند،
            # **پایین‌ترین** درست است.
            order = np.argsort(pooled)[::-1]
            ok = [LADDER[t] for t in order if pooled[t] >= best - 0.10]
            target_r = min(ok)
            if abs(target_r - 1.0) < 1e-6:
                continue
            target = med * target_r
            scale = target / med

            # اصلاح همهٔ فریم‌های ران با همان نسبت؛ فریم‌هایی که با میانهٔ ران
            # رابطهٔ نردبانی دارند نیز هم‌تراز می‌شوند
            for k in range(i, j):
                f = freqs[k]
                q = f / med
                rr = min(LADDER, key=lambda r: abs(1200.0 * np.log2(max(q, 1e-9) / r)))
                if abs(1200.0 * np.log2(max(q, 1e-9) / rr)) <= 70.0:
                    new = target * rr
                else:
                    new = f * scale
                if abs(1200.0 * np.log2(max(new, 1e-9) / max(f, 1e-9))) < 15.0:
                    new = f
                if new < fmin * 0.95 or new > fmax * 1.05:
                    new = f
                ratio[k] = new / f if f > 0 else 1.0
                if new < f * 0.98:
                    n_down += 1
                elif new > f * 1.02:
                    n_up += 1
                out[k] = new

    return out, n_up, n_down, ratio


# ============================================================================
# ۴) هم‌سنجی با pYIN و ادغام
# ============================================================================

def pyin_curve(wav_path, sr, fmin, fmax, duration, hop=0.01):
    """F0 و احتمال صداداری با pYIN، هم‌تراز با شبکهٔ زمانی Praat."""
    import librosa

    if duration > PYIN_MAX_SEC:
        return None, None

    y, _ = librosa.load(wav_path, sr=sr, mono=True)
    f0, vflag, vprob = librosa.pyin(
        y, fmin=fmin, fmax=fmax, sr=sr,
        frame_length=2048, hop_length=max(1, int(round(sr * hop))),
        fill_na=np.nan, center=True)
    return f0, vprob


def _cents(a, b):
    return 1200.0 * np.log2(np.maximum(a, 1e-9) / np.maximum(b, 1e-9))


def fuse_with_pyin(times, freqs, strength, f0_pyin, prob_pyin, hop=0.01):
    """
    ادغام Praat و pYIN.

    قاعده: Praat مرجع دقت سنتی است؛ pYIN فقط در دو حالت وارد می‌شود:
      • پرکردن فریم‌هایی که Praat آن‌ها را بی‌صدا گرفته ولی pYIN مطمئن است
      • اصلاح اختلاف اکتاوی (وقتی شاهد طیفی مطمئن نیست)
    خروجی: freqs, confidence(0..1), agreement_pct
    """
    n = len(times)
    conf = np.clip(strength, 0, 1).astype(float)
    if f0_pyin is None or len(f0_pyin) == 0:
        conf = np.where(freqs > 0, np.maximum(conf, 0.55), 0.0)
        return freqs, conf, None

    m = len(f0_pyin)

    def align(a):
        out = np.full(n, np.nan)
        idx = np.round(times / hop).astype(int)
        ok = (idx >= 0) & (idx < m)
        out[ok] = a[idx[ok]]
        return out

    p_f0 = align(f0_pyin)
    p_prob = align(prob_pyin) if prob_pyin is not None else np.ones(n)

    both = (freqs > 0) & np.isfinite(p_f0) & (np.nan_to_num(p_prob) > 0.25)
    agree = both & (np.abs(_cents(np.where(both, freqs, 1.0),
                                 np.where(both, np.nan_to_num(p_f0), 1.0))) <= 70.0)

    out = freqs.copy()

    # اختلاف اکتاوی: به pYIN اعتماد نکن مگر سیگنال ضعیف باشد
    octave_diff = both & (np.abs(np.abs(_cents(np.where(both, freqs, 1.0),
                                              np.where(both, np.nan_to_num(p_f0), 1.0))) - 1200.0) < 120.0)
    weak = strength < 0.45
    fix_mask = octave_diff & weak & (np.nan_to_num(p_prob) > 0.6)
    out[fix_mask] = p_f0[fix_mask]

    # پرکردن جاهای بی‌صدا که pYIN مطمئن است (ضبط‌های کم‌صدا)
    fill = (freqs <= 0) & np.isfinite(p_f0) & (np.nan_to_num(p_prob) > 0.72)
    out[fill] = p_f0[fill]

    conf = np.where(agree, np.minimum(1.0, conf + 0.25), conf)
    conf = np.where(both & ~agree, conf * 0.75, conf)
    conf = np.where(out > 0, np.maximum(conf, 0.25), 0.0)

    voiced = (freqs > 0)
    agreement_pct = (100.0 * float(np.sum(agree)) / float(np.sum(voiced))) if np.sum(voiced) else 0.0
    return out, np.clip(conf, 0, 1), round(agreement_pct, 1)


# ============================================================================
# ۵) پاک‌سازی، هموارسازی و تفکیک نت
# ============================================================================

def median_smooth(freqs, window=3):
    """میانه‌گیری فقط داخل بازه‌های صدادار (بدون دست‌زدن به سکوت)."""
    from pitch_engine import smooth_pitch_contour
    return smooth_pitch_contour(freqs, median_window=window)


def remove_octave_specks(times, freqs, span_sec=0.25, tol_cents=900):
    """
    حذف «جرقه‌های اکتاوی»: فریم‌هایی که با میانهٔ محلی خود اختلاف نزدیک به
    یک اکتاو دارند و کمتر از span_sec دوام دارند (خطای گذرای موتور).
    """
    out = freqs.copy()
    n = len(freqs)
    if n < 5:
        return out
    win = max(3, int(round(span_sec / max(1e-3, np.median(np.diff(times))))))
    half = win // 2
    i = 0
    while i < n:
        if out[i] <= 0:
            i += 1
            continue
        lo, hi = max(0, i - half), min(n, i + half + 1)
        neigh = out[lo:hi]
        neigh = neigh[neigh > 0]
        if len(neigh) < 3:
            i += 1
            continue
        med = float(np.median(neigh))
        c = 1200.0 * np.log2(out[i] / med)
        if abs(abs(c) - 1200.0) < (1200.0 - tol_cents):
            out[i] = med
        i += 1
    return out


def segment_notes_v2(times, freqs, confidence=None, sr=22050,
                     min_duration=0.08, stability_cents=45,
                     merge_gap=0.10, merge_cents=55):
    """
    تفکیک نت با هیسترزیس (آستانهٔ شروع/ادامهٔ جدا) + اطمینان هر نت.

    نسبت به نسخهٔ قبلی:
      • آستانهٔ «ادامه» ۱٫۶ برابر آستانهٔ «شروع» است → نت لرزان به قطعات
        ریز شکسته نمی‌شود و نتِ واقعاً جدید جا نمی‌افتد.
      • قطع نت با سکوت کوتاه‌تر از merge_gap به‌هم نمی‌خورد.
      • برای هر نت: f0 میانهٔ وزنی با اطمینان + پایداری + اطمینان میانگین.
    """
    if len(freqs) == 0:
        return []

    conf = np.ones(len(freqs)) if confidence is None else np.asarray(confidence, dtype=float)
    dt = float(np.median(np.diff(times))) if len(times) > 1 else 0.01
    cont_cents = stability_cents * 1.6

    raw = []
    n = len(freqs)
    i = 0
    while i < n:
        if freqs[i] <= 0 or conf[i] < 0.12:
            i += 1
            continue
        j = i
        vals = [freqs[i]]
        weights = [max(conf[i], 1e-3)]
        while j + 1 < n and freqs[j + 1] > 0 and conf[j + 1] >= 0.12:
            ref = float(np.average(vals[-min(len(vals), 25):], weights=weights[-min(len(weights), 25):]))
            if abs(1200.0 * np.log2(freqs[j + 1] / ref)) > cont_cents:
                break
            j += 1
            vals.append(freqs[j])
            weights.append(max(conf[j], 1e-3))
        raw.append({"i": i, "j": j, "t0": float(times[i]), "t1": float(times[j])})
        i = j + 1

    # ادغام قطعات مجاور
    merged = []
    for seg in raw:
        if not merged:
            merged.append(seg); continue
        prev = merged[-1]
        gap = seg["t0"] - prev["t1"]
        a = freqs[prev["i"]:prev["j"] + 1]
        b = freqs[seg["i"]:seg["j"] + 1]
        if gap <= merge_gap and len(a) and len(b):
            if abs(1200.0 * np.log2(np.median(b) / np.median(a))) <= merge_cents:
                prev["j"] = seg["j"]; prev["t1"] = seg["t1"]; continue
        merged.append(seg)

    notes = []
    for seg in merged:
        t0, t1 = seg["t0"], seg["t1"]
        duration = t1 - t0 + dt
        if duration < min_duration:
            continue
        f = freqs[seg["i"]:seg["j"] + 1]
        c = conf[seg["i"]:seg["j"] + 1]
        if len(f) < 2:
            continue
        # فقط فریم‌های معتبر (صدا دار و متناهی) در آماره‌های نت شمرده می‌شوند؛
        # وگرنه یک فریم صفر/بی‌نهایت، f0 میانه را NaN و گزارش JSON را خراب می‌کند.
        good = np.isfinite(f) & (f > 0)
        if not np.any(good):
            continue
        f_g, c_g = f[good], np.maximum(c[good], 1e-3)
        f0 = float(np.average(f_g, weights=c_g))
        if not np.isfinite(f0) or f0 <= 0:
            continue
        cents_dev = 1200.0 * np.log2(f_g / f0)
        stability = float(np.nanpercentile(np.abs(cents_dev), 75))      # IQR-ish
        # گلیساندو: روند یکنواخت و بزرگ
        trend = float(cents_dev[-1] - cents_dev[0]) if len(cents_dev) > 1 else 0.0
        notes.append({
            "start": round(t0, 3),
            "end": round(t1, 3),
            "duration": round(duration, 3),
            "f0_hz": round(f0, 2),
            "stability_cents": round(stability, 1),
            "confidence": round(float(np.mean(c_g)), 2),
            "glide_cents": round(trend, 1),
            "n_frames": int(len(f_g)),
        })
    return notes


# ============================================================================
# ۶) خط لولهٔ کامل
# ============================================================================

def analyze_pitch(path, work_dir=None, denoise=False, progress_callback=None,
                  keep_wav=True):
    """
    خط لولهٔ استاندارد: استانداردسازی → Praat → اصلاح اکتاو → pYIN → نت‌ها.

    خروجی: dict با
      times, freqs (پس از پاک‌سازی), confidence, notes, sr, duration,
      wav_path, quality{...}
    """
    def _cb(stage, frac):
        if progress_callback:
            progress_callback(stage, frac)

    _cb("preparing", 0.02)
    prep = prepare_audio(path, work_dir=work_dir, denoise=denoise)
    wav, sr, duration, diag = prep["path"], prep["sr"], prep["duration"], prep["diag"]

    _cb("range", 0.06)
    fmin, fmax = estimate_f0_bounds(wav, sr, duration)
    diag["f0_range_hz"] = [round(fmin), round(fmax)]

    time_step = TIME_STEP_SHORT if duration <= SHORT_FILE_SEC else TIME_STEP_LONG
    diag["time_step_sec"] = time_step

    _cb("pitch", 0.10)

    def _pitch_progress(done, total):
        _cb("pitch", 0.10 + 0.55 * (done / max(total, 1e-9)))

    times, freqs, strength = extract_pitch_praat(
        wav, sr, duration, fmin, fmax, time_step=time_step,
        progress_callback=_pitch_progress)

    _cb("octave", 0.68)
    freqs, n_up, n_down, sub_ratio = spectral_octave_fix(
        wav, sr, times, freqs, fmin=fmin, fmax=fmax)
    diag["octave_fixes"] = {"halved": int(n_down), "doubled": int(n_up)}

    _cb("pyin", 0.74)
    f0_pyin, prob_pyin = pyin_curve(wav, sr, fmin, fmax, duration)
    freqs, conf, agreement = fuse_with_pyin(times, freqs, strength, f0_pyin, prob_pyin)
    diag["pyin_agreement_pct"] = agreement
    diag["pyin_used"] = agreement is not None

    _cb("clean", 0.86)
    freqs = remove_octave_specks(times, freqs)
    freqs = median_smooth(freqs, window=3)

    _cb("notes", 0.92)
    notes = segment_notes_v2(times, freqs, conf, sr=sr)

    voiced = freqs > 0
    diag.update({
        "voiced_ratio_pct": round(100.0 * float(np.mean(voiced)), 1) if len(voiced) else 0.0,
        "mean_note_confidence": round(float(np.mean([n["confidence"] for n in notes])), 2) if notes else 0.0,
        "n_notes": len(notes),
    })
    diag["status"], diag["warnings"] = _quality_status(diag)

    _cb("done", 1.0)
    return {
        "times": times,
        "freqs": freqs,
        "confidence": conf,
        "notes": notes,
        "sr": sr,
        "duration": duration,
        "wav_path": wav if keep_wav else None,
        "quality": diag,
    }


def _quality_status(diag):
    """ارزیابی کیفیت ورودی + هشدارهای فارسی (تا کاربر بداند نتیجه چقدر قابل اتکاست)."""
    warnings = []
    score = 100.0

    snr = diag.get("snr_db")
    diag["snr_estimated"] = snr is not None
    if snr is None:
        pass                                   # یکنواخت/بی‌مکث: تخمین نویز ممکن نیست
    elif snr < 8:
        warnings.append("نویز پس‌زمینه زیاد است — «کاهش نویز» را روشن کن یا نزدیک‌تر/بلندتر ضبط کن.")
        score -= 30
    elif snr < 15:
        warnings.append("نسبت سیگنال به نویز متوسط است؛ چند نت ممکن است محافظه‌کارانه حذف شوند.")
        score -= 12

    clip = diag.get("clipping_pct", 0) or 0
    if clip > 0.5:
        warnings.append("صدا کلیپ (اشباع) شده — هارمونیک‌های اضافه دقت نت را کم می‌کند.")
        score -= 18
    elif clip > 0.05:
        score -= 5

    if diag.get("peak_dbfs", 0) < -32:
        warnings.append("سطح ضبط خیلی کم است (نرمال‌سازی انجام شد، اما نویز نسبی بیشتر است).")
        score -= 8

    vr = diag.get("voiced_ratio_pct", 100) or 0
    if vr < 25:
        warnings.append("بخش کمی از فایل صدادار است — تحلیل روی همان بخش انجام شد.")
        score -= 10

    agree = diag.get("pyin_agreement_pct")
    if agree is not None and agree < 70:
        warnings.append("دو موتور مستقل در بخشی از فایل هم‌رأی نبودند (احتمال ویبراتوی شدید/نویز).")
        score -= 10

    mc = diag.get("mean_note_confidence", 0) or 0
    if mc and mc < 0.5:
        score -= 8

    score = max(0.0, min(100.0, score))
    status = ("excellent" if score >= 88 else
              "good" if score >= 74 else
              "fair" if score >= 55 else "poor")
    return status, warnings
