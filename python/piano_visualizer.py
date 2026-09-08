#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
نمایشگر پیانوی زنده (Real-time Piano Visualizer)
======================================================
یک کلاویه پیانوی ۸۸-کلیدی رسم می‌کند و هم‌زمان با پخش صدا (یا میکروفون)،
نت فعلی را روی کلاویه هایلایت می‌کند — دقیقاً مثل یک "پیانوی شبح" (ghost
piano) که در نرم‌افزارهای آموزش موسیقی رایج است.

دو حالت اجرا:
  1) از فایل صوتی (هم‌زمان با پخش صدا از بلندگو)
  2) از میکروفون زنده

نیازمندی‌ها:
    pip install praat-parselmouth numpy matplotlib sounddevice soundfile librosa

اجرا:
    python3 piano_visualizer.py --file recitation.mp3
    python3 piano_visualizer.py --mic
"""

import argparse
import queue
import sys
import threading
import time
import warnings

import numpy as np

warnings.filterwarnings("ignore")

import matplotlib
try:
    matplotlib.use("TkAgg")  # برای نمایش پنجره زنده تعاملی
except Exception:
    matplotlib.use("Agg")    # اگر محیط GUI در دسترس نبود
import matplotlib.pyplot as plt
import matplotlib.patches as patches
import matplotlib.animation as animation

from pitch_engine import (
    freq_to_note_info, is_black_key, PIANO_MIDI_MIN, PIANO_MIDI_MAX,
    extract_pitch_chunk,
)


def _setup_persian_font():
    import os
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


_HAS_PERSIAN_FONT = _setup_persian_font()


def T(text):
    return _fa(text) if _HAS_PERSIAN_FONT else text

try:
    import sounddevice as sd
except ImportError:
    sd = None

try:
    import soundfile as sf
except ImportError:
    sf = None


# ============================================================================
# رسم کلاویه پیانو
# ============================================================================

class PianoKeyboard:
    """رسم یک کلاویه ۸۸-کلیدی و هایلایت‌کردن نت فعال بر روی آن."""

    WHITE_KEY_WIDTH = 1.0
    WHITE_KEY_HEIGHT = 4.0
    BLACK_KEY_WIDTH = 0.6
    BLACK_KEY_HEIGHT = 2.5

    def __init__(self, ax):
        self.ax = ax
        self.white_key_patches = {}
        self.black_key_patches = {}
        self.active_patch = None
        self._build_keyboard()

    def _build_keyboard(self):
        white_x = 0
        midi_to_white_x = {}

        # مرحله ۱: کلیدهای سفید
        for midi in range(PIANO_MIDI_MIN, PIANO_MIDI_MAX + 1):
            if not is_black_key(midi):
                rect = patches.Rectangle(
                    (white_x, 0), self.WHITE_KEY_WIDTH, self.WHITE_KEY_HEIGHT,
                    facecolor="white", edgecolor="#333333", linewidth=0.8, zorder=1,
                )
                self.ax.add_patch(rect)
                self.white_key_patches[midi] = rect
                midi_to_white_x[midi] = white_x
                white_x += self.WHITE_KEY_WIDTH

        self.total_width = white_x

        # مرحله ۲: کلیدهای سیاه (روی مرز بین دو کلید سفید مجاور قرار می‌گیرند)
        for midi in range(PIANO_MIDI_MIN, PIANO_MIDI_MAX + 1):
            if is_black_key(midi):
                # پیدا کردن نزدیک‌ترین کلید سفید قبلی برای محاسبه موقعیت
                prev_white = midi - 1
                while is_black_key(prev_white) and prev_white >= PIANO_MIDI_MIN:
                    prev_white -= 1
                if prev_white not in midi_to_white_x:
                    continue
                x = midi_to_white_x[prev_white] + self.WHITE_KEY_WIDTH - self.BLACK_KEY_WIDTH / 2
                rect = patches.Rectangle(
                    (x, self.WHITE_KEY_HEIGHT - self.BLACK_KEY_HEIGHT),
                    self.BLACK_KEY_WIDTH, self.BLACK_KEY_HEIGHT,
                    facecolor="#1a1a1a", edgecolor="#000000", linewidth=0.6, zorder=2,
                )
                self.ax.add_patch(rect)
                self.black_key_patches[midi] = rect

        self.ax.set_xlim(-0.5, self.total_width + 0.5)
        self.ax.set_ylim(-0.3, self.WHITE_KEY_HEIGHT + 1.2)
        self.ax.set_aspect("equal")
        self.ax.axis("off")

        self.label_text = self.ax.text(
            self.total_width / 2, self.WHITE_KEY_HEIGHT + 0.6, "",
            ha="center", va="bottom", fontsize=22, fontweight="bold", color="#7c3aed",
        )
        self.cents_text = self.ax.text(
            self.total_width / 2, self.WHITE_KEY_HEIGHT + 0.15, "",
            ha="center", va="bottom", fontsize=11, color="#555555",
        )

    def reset_colors(self):
        for midi, rect in self.white_key_patches.items():
            rect.set_facecolor("white")
        for midi, rect in self.black_key_patches.items():
            rect.set_facecolor("#1a1a1a")

    def highlight_note(self, note_info):
        self.reset_colors()
        if note_info is None or note_info.get("midi") is None:
            self.label_text.set_text("—")
            self.cents_text.set_text("")
            return

        midi = note_info["midi"]
        color = "#22c55e" if abs(note_info["cents_offset"]) < 15 else "#f59e0b"
        if abs(note_info["cents_offset"]) > 35:
            color = "#ef4444"

        if midi in self.white_key_patches:
            self.white_key_patches[midi].set_facecolor(color)
        elif midi in self.black_key_patches:
            self.black_key_patches[midi].set_facecolor(color)

        self.label_text.set_text(note_info["note_name"])
        sign = "+" if note_info["cents_offset"] >= 0 else ""
        self.cents_text.set_text(f"{note_info['freq']:.1f} Hz   |   {T('انحراف کوک')}: {sign}{note_info['cents_offset']:.0f}¢")
        self.label_text.set_color(color if color != "white" else "#7c3aed")


# ============================================================================
# حالت ۱: پخش فایل صوتی + نمایش هم‌زمان پیانو
# ============================================================================

def run_from_file(file_path, chunk_ms=100):
    if sd is None or sf is None:
        print("خطا: sounddevice و soundfile لازم است. نصب کنید: pip install sounddevice soundfile")
        sys.exit(1)

    import librosa
    print(f"در حال بارگذاری {file_path} ...")
    y, sr = librosa.load(file_path, sr=44100, mono=True)
    y = y.astype(np.float32)

    chunk_size = int(sr * chunk_ms / 1000)
    num_chunks = len(y) // chunk_size

    fig, ax = plt.subplots(figsize=(14, 5))
    fig.suptitle(T("پیانوی زنده — نمایش هم‌زمان نت در حال پخش"), fontsize=14)
    keyboard = PianoKeyboard(ax)

    pitch_queue = queue.Queue()
    stop_flag = threading.Event()

    def audio_playback_thread():
        sd.play(y, sr)
        sd.wait()
        stop_flag.set()

    def pitch_analysis_thread():
        for i in range(num_chunks):
            if stop_flag.is_set():
                break
            start = i * chunk_size
            end = start + chunk_size
            chunk = y[start:end]
            f0 = extract_pitch_chunk(chunk, sr)
            note_info = freq_to_note_info(f0) if f0 else None
            pitch_queue.put(note_info)
            time.sleep(chunk_ms / 1000.0 * 0.95)  # هم‌گام‌سازی تقریبی با زمان واقعی پخش

    playback_t = threading.Thread(target=audio_playback_thread, daemon=True)
    analysis_t = threading.Thread(target=pitch_analysis_thread, daemon=True)
    playback_t.start()
    analysis_t.start()

    def update(frame):
        note_info = None
        try:
            while True:
                note_info = pitch_queue.get_nowait()
        except queue.Empty:
            pass
        if note_info is not None or not pitch_queue.empty():
            keyboard.highlight_note(note_info)
        return []

    ani = animation.FuncAnimation(fig, update, interval=50, blit=False, cache_frame_data=False)
    plt.tight_layout()
    plt.show()


# ============================================================================
# حالت ۲: ورودی زنده میکروفون
# ============================================================================

def run_from_mic(chunk_ms=150, fmin=60, fmax=800):
    if sd is None:
        print("خطا: sounddevice لازم است. نصب کنید: pip install sounddevice")
        sys.exit(1)

    sr = 44100
    chunk_size = int(sr * chunk_ms / 1000)

    fig, ax = plt.subplots(figsize=(14, 5))
    fig.suptitle(T("پیانوی زنده — ورودی میکروفون"), fontsize=14)
    keyboard = PianoKeyboard(ax)

    audio_queue = queue.Queue()

    def callback(indata, frames, time_info, status):
        audio_queue.put(indata[:, 0].copy())

    stream = sd.InputStream(samplerate=sr, channels=1, callback=callback, blocksize=chunk_size)
    stream.start()

    def update(frame):
        try:
            chunk = audio_queue.get_nowait()
        except queue.Empty:
            return []
        f0 = extract_pitch_chunk(chunk, sr, fmin=fmin, fmax=fmax)
        note_info = freq_to_note_info(f0) if f0 else None
        keyboard.highlight_note(note_info)
        return []

    ani = animation.FuncAnimation(fig, update, interval=int(chunk_ms * 0.8), blit=False, cache_frame_data=False)
    plt.tight_layout()

    try:
        plt.show()
    finally:
        stream.stop()
        stream.close()


# ============================================================================
# CLI
# ============================================================================

def main():
    parser = argparse.ArgumentParser(description="نمایشگر زنده پیانو برای تحلیل صوت")
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--file", help="مسیر فایل صوتی برای پخش + نمایش هم‌زمان پیانو")
    group.add_argument("--mic", action="store_true", help="استفاده از میکروفون زنده")
    parser.add_argument("--chunk-ms", type=int, default=120, help="اندازه هر قطعه تحلیل (میلی‌ثانیه)")

    args = parser.parse_args()

    if args.file:
        run_from_file(args.file, chunk_ms=args.chunk_ms)
    else:
        run_from_mic(chunk_ms=args.chunk_ms)


if __name__ == "__main__":
    main()
