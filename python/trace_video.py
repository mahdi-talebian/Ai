#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🎬 رندر «ردِّ صدا» (Pitch Trace) — نمودار خطی نرم + نقطهٔ نورانی متحرک
=====================================================================
این ماژول فقط یک کار می‌کند: منحنی پردهٔ صدا را به شکل یک تصویر/ویدیوی
مینیمال و نرم نشان می‌دهد (به سبک تصاویر مرجع):

  • پس‌زمینهٔ سرمه‌ای تیره + خطوط افقی «نت» (یک خط برای هر نیم‌پرده)
  • منحنی پیوستهٔ صدا که به‌نرمی بین خطوط نت جابه‌جا می‌شود
  • نقطهٔ نورانی (glow) روی هر نتِ خوانده‌شده
  • خط عمودی «پخش» + نقطهٔ درخشان روی نت جاری
  • بخش خوانده‌شده طلایی، بخش آینده آبی محو

هیچ تحلیل مقام/سولفژی در این ماژول نیست — ورودی فقط زمان و فرکانس است.

استفادهٔ خط‌فرمان:
    python3 trace_video.py recitation.mp3 --output trace.mp4 --fps 20
    python3 trace_video.py recitation.mp3 --png trace.png
"""

import math
import os
import shutil
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# ============================================================================
# پالت و ابعاد (هم‌خوان با تصاویر مرجع: سرمه‌ای تیره + طلایی)
# ============================================================================

W, H = 1280, 720          # ابعاد خروجی (۱۶:۹)
MARGIN_L, MARGIN_R = 74, 46
MARGIN_T, MARGIN_B = 78, 64

BG_TOP = (13, 23, 48)
BG_BOTTOM = (23, 36, 66)
BORDER = (120, 152, 205, 46)
GRID_STRONG = (255, 255, 255, 26)
GRID_WEAK = (255, 255, 255, 13)
DIM_LINE = (86, 120, 160, 105)      # منحنی آینده (آبی محو)
DIM_DOT = (104, 138, 176, 110)
GOLD = (247, 209, 113, 255)         # منحنی خوانده‌شده
GOLD_SOFT = (255, 228, 155, 255)
PLAYHEAD = (255, 255, 255, 210)
LABEL = (196, 210, 235, 210)
LABEL_DIM = (150, 168, 200, 150)

DEFAULT_FPS = 20
GAP_BRIDGE_SEC = 0.75     # سکوت‌های کوتاه‌تر از این مقدار با خط نرم پر می‌شوند
GAP_SPLIT_SEC = 0.75      # سکوت‌های بلندتر، منحنی را به دو بخش جدا می‌شکنند

_NOTE_NAMES = ["C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"]


# ============================================================================
# ابزارهای پایه
# ============================================================================

def midi_to_name(midi):
    """عدد میدی (صحیح یا اعشاری) → نام نت با اکتاو، مثل A4."""
    if midi is None or (isinstance(midi, float) and math.isnan(midi)):
        return "—"
    m = int(round(midi))
    return f"{_NOTE_NAMES[m % 12]}{m // 12 - 1}"


def _font(size, bold=False):
    candidates = [
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if bold
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/TTF/DejaVuSans.ttf",
        "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
    ]
    for path in candidates:
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:
                continue
    return ImageFont.load_default()


def _ffmpeg_exe():
    """ffmpeg را از PATH، متغیر محیطی یا wheel رسمی imageio-ffmpeg پیدا می‌کند."""
    for cand in (os.environ.get("FFMPEG_BIN"), shutil.which("ffmpeg")):
        if cand and os.path.exists(cand):
            return cand
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except Exception:
        raise RuntimeError("ffmpeg پیدا نشد — نصبش کنید (apt install ffmpeg).")


def _glow_sprite(radius, color, core_alpha=255, falloff=2.2):
    """
    یک «نقطهٔ نورانی» نرم می‌سازد (هالهٔ گاوسی + هستهٔ روشن).
    محاسبه در بوم کوچک داخلی (۱۶۰px) انجام و سپس به اندازهٔ نهایی بزرگ
    می‌شود تا حافظه مصرفی مستقل از اندازهٔ نقطه باشد.
    """
    S = 160
    yy, xx = np.mgrid[0:S, 0:S]
    cx = cy = (S - 1) / 2.0
    dist = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / (S / 2.0)
    alpha = np.clip(1.0 - dist, 0.0, 1.0) ** falloff
    core = np.clip(1.0 - dist * 2.4, 0.0, 1.0) ** 1.1
    alpha = np.clip(alpha * 0.85 + core * 0.85, 0.0, 1.0) * (core_alpha / 255.0)

    arr = np.zeros((S, S, 4), dtype=np.uint8)
    arr[..., 0:3] = color[:3]
    arr[..., 3] = (alpha * 255).astype(np.uint8)
    img = Image.fromarray(arr, "RGBA")

    size = max(8, int(radius * 2 * 4))
    return img.resize((size, size), Image.LANCZOS)


class _Sprites:
    """کش اسپرایت‌ها برای جلوگیری از ساخت مکرر."""

    def __init__(self):
        self._cache = {}

    def get(self, radius, color, core_alpha=255):
        key = (round(radius, 1), tuple(color[:3]), core_alpha)
        if key not in self._cache:
            self._cache[key] = _glow_sprite(radius, color, core_alpha)
        return self._cache[key]


# ============================================================================
# رندرر اصلی
# ============================================================================

class TraceRenderer:
    """
    یک رندرر حالت‌دار: پس‌زمینه، خطوط نت و لایهٔ «آینده» را یک‌بار می‌سازد
    و برای هر لحظه فقط لایهٔ طلایی + نقطه + خط پخش را می‌کشد.
    """

    def __init__(self, times, freqs, notes=None, width=W, height=H,
                 show_labels=True, show_range_guides=True):
        self.W, self.H = width, height
        self.show_labels = show_labels
        self.show_range_guides = show_range_guides
        self.sprites = _Sprites()

        times = np.asarray(times, dtype=float)
        freqs = np.asarray(freqs, dtype=float)
        order = np.argsort(times)
        times, freqs = times[order], freqs[order]

        voiced = freqs > 0
        self.duration = float(times[-1]) if len(times) else 0.0
        if self.duration <= 0:
            self.duration = 1.0

        mids = np.full(len(freqs), np.nan)
        mids[voiced] = 69.0 + 12.0 * np.log2(freqs[voiced] / 440.0)

        self.times = times
        self.midi = mids
        self.notes = list(notes or [])

        # ---- دامنهٔ عمودی: کل محدودهٔ صدا + حاشیه، حداقل ۱۲ نیم‌پرده ----
        valid = mids[~np.isnan(mids)]
        if len(valid):
            lo, hi = float(np.min(valid)), float(np.max(valid))
        else:
            lo, hi = 48.0, 60.0
        span = max(hi - lo, 11.9)
        center = (lo + hi) / 2.0
        pad = max(1.6, span * 0.10)
        lo_lim = math.floor(center - span / 2.0 - pad)
        hi_lim = math.ceil(center + span / 2.0 + pad)
        self.lo_midi, self.hi_midi = float(lo_lim), float(hi_lim)
        self.range_lo = lo if len(valid) else None
        self.range_hi = hi if len(valid) else None

        if self.notes:
            self.highest = max(self.notes, key=lambda n: n.get("f0_hz") or 0)
            self.lowest = min(self.notes, key=lambda n: n.get("f0_hz") or 1e9)
            self._hi_hz = float(self.highest.get("f0_hz") or 0)
            self._lo_hz = float(self.lowest.get("f0_hz") or 0)
        else:
            self.highest = self.lowest = None
            self._hi_hz = self._lo_hz = 0.0

        # ---- مختصات پیکسلی منحنی ----
        self.px, self.py = self._to_pixels(times, mids)
        self.py = self._smooth_py(self.py)

        # قطع‌های پیوستهٔ منحنی یک‌بار محاسبه می‌شوند (در هر فریم ثابت‌اند)
        self._segs = self._segments()
        self._draw_segs = self._subsets()
        self.background = self._build_background()
        self.dim_layer = self._build_static_layer()
        self.base = self.background.copy()
        self.base.alpha_composite(self.dim_layer)

    # ---------------------------------------------------------------- ابزار
    def _x_of_time(self, t):
        usable = self.W - MARGIN_L - MARGIN_R
        return MARGIN_L + (t / self.duration) * usable

    def _y_of_midi(self, m):
        usable = self.H - MARGIN_T - MARGIN_B
        frac = (m - self.lo_midi) / max(1e-6, (self.hi_midi - self.lo_midi))
        return MARGIN_T + (1.0 - frac) * usable

    def _to_pixels(self, times, mids):
        px = np.array([self._x_of_time(t) for t in times], dtype=float)
        py = np.full(len(mids), np.nan)
        ok = ~np.isnan(mids)
        py[ok] = [self._y_of_midi(m) for m in mids[ok]]
        return px, py

    @staticmethod
    def _smooth_py(py, window=5):
        """
        هموارسازی سبک محور عمودی (فقط داخل بازه‌های صدادار) تا حرکت نقطه
        بین خطوط نت «نرم» دیده شود — بدون دست‌زدن به ارتفاع واقعی نت‌ها.
        """
        out = py.copy()
        n = len(py)
        half = max(1, window // 2)
        i = 0
        while i < n:
            if np.isnan(out[i]):
                i += 1
                continue
            j = i
            while j < n and not np.isnan(out[j]):
                j += 1
            seg = out[i:j]
            if len(seg) >= 3:
                kernel = np.ones(min(window, len(seg))) / min(window, len(seg))
                pad = len(kernel) // 2
                padded = np.pad(seg, pad, mode="edge")
                out[i:j] = np.convolve(padded, kernel, mode="valid")[:len(seg)]
            i = j
        return out

    # ------------------------------------------------------------ پس‌زمینه
    def _build_background(self):
        W, H = self.W, self.H
        # گرادیان نرم عمودی
        grad = np.zeros((H, 1, 3), dtype=np.float32)
        for i in range(H):
            f = i / max(1, H - 1)
            grad[i, 0, :] = np.array(BG_TOP) * (1 - f) + np.array(BG_BOTTOM) * f
        base = np.repeat(grad, W, axis=1).astype(np.float32)

        # هالهٔ نور مرکزی ملایم (محاسبهٔ برداری — بدون اسپرایت بزرگ)
        yy, xx = np.mgrid[0:H, 0:W]
        rr = np.sqrt((xx - W / 2.0) ** 2 + (yy - H / 2.0) ** 2) / (0.62 * max(W, H))
        halo = np.clip(1.0 - rr, 0.0, 1.0) ** 2.4
        base += halo[..., None] * np.array([26, 40, 68], dtype=np.float32)

        img = Image.fromarray(np.clip(base, 0, 255).astype(np.uint8), "RGB").convert("RGBA")

        # قاب گرد
        frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        fd = ImageDraw.Draw(frame)
        fd.rounded_rectangle([10, 10, W - 10, H - 10], radius=26, outline=BORDER, width=2)
        img.alpha_composite(frame)

        # خطوط نت: هر نیم‌پرده یک خط، C پررنگ‌تر؛ درجات بالا/پایین هم مشخص
        grid = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(grid)
        for m in range(int(self.lo_midi), int(self.hi_midi) + 1):
            y = self._y_of_midi(m)
            is_c = (m % 12) == 0
            color = GRID_STRONG if is_c else GRID_WEAK
            gd.line([(22, y), (W - 22, y)], fill=color, width=2 if is_c else 1)
        img.alpha_composite(grid)
        return img

    # ------------------------------------------------- لایهٔ ثابت (آینده)
    def _segments(self, upto_time=None):
        """
        منحنی پیوسته را به قطعات پیکسلی می‌شکند و سکوت‌های کوتاه را با خط
        نرم به هم وصل می‌کند تا حرکت بین نت‌ها «لغزیدن روی خطوط» دیده شود
        (به‌جای نقطه‌های جدا). سکوت‌های بلندتر از GAP_BRIDGE_SEC منحنی را
        قطع می‌کنند.
        """
        px, py, times = self.px, self.py, self.times
        valid = ~np.isnan(py)
        runs, cur, last_t = [], [], None
        for i in range(len(px)):
            if not valid[i]:
                if len(cur) > 1:
                    runs.append(cur)
                cur, last_t = [], None
                continue
            t = float(times[i])
            if last_t is not None and (t - last_t) > GAP_BRIDGE_SEC:
                if len(cur) > 1:
                    runs.append(cur)
                cur = []
            cur.append((float(px[i]), float(py[i]), t))
            last_t = t
        if len(cur) > 1:
            runs.append(cur)

        if not runs:
            return []

        # --- ادغام قطعات با پل درون‌یابی‌شده در سکوت‌های کوتاه ---
        segs = [list(runs[0])]
        for run in runs[1:]:
            prev = segs[-1]
            gap = run[0][2] - prev[-1][2]
            if gap <= GAP_BRIDGE_SEC:
                x0, y0, t0 = prev[-1]
                x1, y1, t1 = run[0]
                n = max(2, min(24, int(gap * 40) + 2))
                for j in range(1, n):
                    f = j / float(n)
                    prev.append((x0 + (x1 - x0) * f, y0 + (y1 - y0) * f,
                                 t0 + (t1 - t0) * f))
                prev.extend(run)
            else:
                segs.append(list(run))
        return [sg for sg in segs if len(sg) > 1]

    def _build_static_layer(self):
        """
        لایهٔ «آینده»: منحنی محو آبی + نقطه‌های محو نت‌ها.
        این لایه در همهٔ فریم‌ها ثابت است و فقط یک‌بار ساخته می‌شود.
        """
        W, H = self.W, self.H
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        curve = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        cd = ImageDraw.Draw(curve)
        for seg in self._segs:
            pts = [(x, y) for x, y, _ in seg]
            cd.line(pts, fill=DIM_LINE, width=4, joint="curve")
        curve = curve.filter(ImageFilter.GaussianBlur(0.7))
        layer.alpha_composite(curve)

        # نقطه‌های محو روی هر نت
        for note in self.notes:
            f0 = note.get("f0_hz")
            if not f0 or f0 <= 0:
                continue
            midi = 69.0 + 12.0 * math.log2(f0 / 440.0)
            x = self._x_of_time(note.get("start") or 0.0)
            y = self._y_of_midi(midi)
            spr = self.sprites.get(8.5, DIM_DOT, core_alpha=150)
            layer.alpha_composite(spr, (int(x - spr.width / 2), int(y - spr.height / 2)))
        return layer

    # ------------------------------------------------------- نقطهٔ جاری
    def _current_midi(self, t):
        """میدی جاری در لحظهٔ t (با درون‌یابی خطی — حرکت نرم)."""
        times, mids = self.times, self.midi
        if not len(times):
            return None
        idx = int(np.searchsorted(times, t, side="right")) - 1
        if idx < 0:
            idx = 0
        # اگر همین لحظه سکوت است، آخرین نت خوانده‌شده را نگه دار (نقطه ثابت نمی‌ماند/ناپدید نمی‌شود)
        if idx >= len(times) - 1:
            return mids[idx] if not np.isnan(mids[idx]) else None
        t0, t1 = times[idx], times[idx + 1]
        m0, m1 = mids[idx], mids[idx + 1]
        if np.isnan(m0) and np.isnan(m1):
            prev = mids[:idx + 1]
            prev = prev[~np.isnan(prev)]
            return float(prev[-1]) if len(prev) else None
        if np.isnan(m0):
            return m1
        if np.isnan(m1):
            return m0
        if t1 <= t0:
            return m0
        f = (t - t0) / (t1 - t0)
        return m0 + (m1 - m0) * f

    # ------------------------------------------------------------- یک فریم
    def _subsets(self):
        """نقاط زیرمجموعهٔ منحنی برای رسم سریع‌تر (حداکثر ~۶۰۰۰ نقطه)."""
        total = sum(len(sg) for sg in self._segs)
        step = 1 if total <= 6000 else int(math.ceil(total / 6000.0))
        if step == 1:
            return self._segs
        out = []
        for sg in self._segs:
            red = sg[::step]
            if red[-1] is not sg[-1]:
                red = red + [sg[-1]]
            out.append(red)
        return out

    def frame(self, t):
        t = max(0.0, min(t, self.duration))
        img = self.base.copy()

        overlay = Image.new("RGBA", (self.W, self.H), (0, 0, 0, 0))
        d = ImageDraw.Draw(overlay)

        # --- بخش خوانده‌شده: منحنی طلایی تا لحظهٔ t (سه لایه = درخشش نرم) ---
        for seg in self._draw_segs:
            pts = [(x, y) for x, y, tt in seg if tt <= t]
            if len(pts) >= 2:
                tail = pts
                if len(pts) < len(seg):
                    nxt = next((p for p in seg if p[2] > t), None)
                    if nxt is not None:
                        x0, y0 = pts[-1]
                        x1, y1, t1 = nxt
                        t0 = seg[-1][2] if seg[-1][2] <= t else None
                        f = 0.5
                        tail = pts + [(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f)]
                for width, alpha in ((12, 34), (7, 80), (4, 235)):
                    d.line(tail, fill=(255, 214, 126, alpha) if width != 4 else GOLD,
                           width=width, joint="curve")

        # --- نقطه‌های طلایی روی نت‌های خوانده‌شده (بیضی ساده = سریع) ---
        for note in self.notes:
            start = note.get("start") or 0.0
            if start > t:
                continue
            f0 = note.get("f0_hz")
            if not f0 or f0 <= 0:
                continue
            midi = 69.0 + 12.0 * math.log2(f0 / 440.0)
            x = self._x_of_time(start + (note.get("duration") or 0.0) / 2.0)
            y = self._y_of_midi(midi)
            r = 4.6 if (note.get("duration") or 0) < 0.35 else 6.0
            d.ellipse([x - r, y - r, x + r, y + r], fill=GOLD_SOFT)

        # --- خط پخش عمودی ---
        x_now = self._x_of_time(t)
        d.line([(x_now, 18), (x_now, self.H - 18)], fill=(255, 255, 255, 40), width=6)
        d.line([(x_now, 18), (x_now, self.H - 18)], fill=PLAYHEAD, width=2)

        img.alpha_composite(overlay)

        # --- نقطهٔ درخشان روی نت جاری ---
        m_now = self._current_midi(t)
        if m_now is not None:
            y_now = self._y_of_midi(m_now)
            halo = self.sprites.get(30.0, GOLD_SOFT, core_alpha=140)
            img.alpha_composite(halo, (int(x_now - halo.width / 2),
                                       int(y_now - halo.height / 2)))
            dot = self.sprites.get(12.0, (255, 250, 232), core_alpha=255)
            img.alpha_composite(dot, (int(x_now - dot.width / 2),
                                      int(y_now - dot.height / 2)))

        if self.show_range_guides:
            self._draw_range_guides(img, t)
        if self.show_labels:
            self._draw_labels(img, t, m_now)

        return img.convert("RGB")

    # ------------------------------------------------------ راهنمای دامنه
    def _draw_range_guides(self, img, t):
        """خطوط راهنمای ظریف برای بالاترین و پایین‌ترین نت (کم‌رنگ)."""
        d = ImageDraw.Draw(img)
        fnt = _font(15)
        for note, color, tag in ((self.highest, (255, 226, 160), "HIGH"),
                                 (self.lowest, (176, 206, 235), "LOW")):
            if not note or not note.get("f0_hz"):
                continue
            midi = 69.0 + 12.0 * math.log2(note["f0_hz"] / 440.0)
            y = self._y_of_midi(midi)
            x0 = self._x_of_time(note.get("start") or 0.0)
            # خط‌چین کم‌رنگ تا انتهای نمودار
            step = 14
            x = x0
            while x < self.W - 30:
                d.line([(x, y), (min(x + 7, self.W - 30), y)],
                       fill=(color[0], color[1], color[2], 55), width=1)
                x += step
            label = f"{tag} {midi_to_name(midi)}"
            d.text((MARGIN_L, y - 19), label, font=fnt,
                   fill=(color[0], color[1], color[2], 120))

    # ----------------------------------------------------------- برچسب‌ها
    def _draw_labels(self, img, t, m_now):
        d = ImageDraw.Draw(img)
        if m_now is None:
            cur = "—"
        else:
            name = midi_to_name(m_now)
            hz = 440.0 * (2.0 ** ((m_now - 69.0) / 12.0))
            cur = f"{name}   {hz:5.1f} Hz"
        d.text((40, 34), cur, font=_font(24, bold=True), fill=(238, 234, 214, 235))

        d.text((self.W - 190, 40), f"{t:5.1f} / {self.duration:.1f}s",
               font=_font(16), fill=LABEL_DIM)

        if self.highest and self.lowest:
            hi = self._hi_hz
            lo = self._lo_hz
            if hi > 0 and lo > 0:
                span = 12.0 * math.log2(hi / lo)
                txt = f"{midi_to_name(69 + 12 * math.log2(hi / 440))}  ↕  " \
                      f"{midi_to_name(69 + 12 * math.log2(lo / 440))}   " \
                      f"({span:.1f} semitones)"
                d.text((self.W - 420, self.H - 40), txt, font=_font(17), fill=LABEL)

    # ------------------------------------------------------------- استاتیک
    def static(self, t=None):
        """تصویر تک‌فریم (برای گزارش PNG)."""
        if t is None:
            t = self.duration
        return self.frame(t)


# ============================================================================
# تصویر PNG
# ============================================================================

def render_static_png(times, freqs, notes, out_path, t=None,
                      width=W, height=H):
    renderer = TraceRenderer(times, freqs, notes, width=width, height=height)
    img = renderer.static(t)
    os.makedirs(os.path.dirname(os.path.abspath(out_path)) or ".", exist_ok=True)
    img.save(out_path, "PNG")
    return out_path


# ============================================================================
# ویدیو
# ============================================================================

MAX_FRAMES = 24000     # سقف تعداد فریم (برای فایل‌های طولانی fps خودکار کم می‌شود)


def render_video(times, freqs, notes, wav_path, out_path, fps=None,
                 progress_callback=None, width=W, height=H):
    """
    ویدیوی mp4 می‌سازد: فریم‌ها با rawvideo به ffmpeg داده و هم‌زمان با صدای
    اصلی mux می‌شوند (بدون فایل میانی، سریع و کم‌حافظه).

    fps اگر None باشد خودکار انتخاب می‌شود: ۲۰ برای فایل‌های کوتاه و کمتر
    برای فایل‌های طولانی، تا تعداد فریم‌ها از MAX_FRAMES بیشتر نشود.
    """
    if fps is None:
        probe = max(float(times[-1]) if len(times) else 1.0, 0.2)
        fps = int(min(DEFAULT_FPS, max(8, MAX_FRAMES / probe)))
    fps = max(8, min(int(fps), 30))
    renderer = TraceRenderer(times, freqs, notes, width=width, height=height)
    duration = max(renderer.duration, 0.2)
    n_frames = max(1, int(math.ceil(duration * fps)))

    def _cb(stage, frac):
        if progress_callback:
            progress_callback(stage, frac)

    ff = _ffmpeg_exe()
    cmd = [
        ff, "-y", "-loglevel", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-s", f"{width}x{height}", "-r", str(fps), "-i", "-",
        "-i", wav_path,
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-b:a", "160k",
        "-shortest", "-movflags", "+faststart",
        out_path,
    ]
    _cb("video", 0.02)
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE,
                            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    try:
        for i in range(n_frames):
            t = i / float(fps)
            frame = renderer.frame(t)
            proc.stdin.write(frame.tobytes())
            if i % max(1, n_frames // 60) == 0:
                _cb("video", 0.02 + 0.94 * (i / float(n_frames)))
    except BrokenPipeError:
        pass
    finally:
        try:
            proc.stdin.close()
        except Exception:
            pass

    err = proc.stderr.read().decode("utf-8", "ignore") if proc.stderr else ""
    code = proc.wait()
    if code != 0:
        raise RuntimeError(f"ffmpeg با خطا تمام شد (کد {code}): {err[-800:]}")
    _cb("done", 1.0)
    return out_path


# ============================================================================
# CLI
# ============================================================================

def main():
    import argparse

    from range_analyzer import analyze_range

    ap = argparse.ArgumentParser(description="رندر ردِّ صدا (PNG/MP4)")
    ap.add_argument("file")
    ap.add_argument("--output", "-o", default=None, help="مسیر mp4")
    ap.add_argument("--png", default=None, help="فقط تصویر PNG")
    ap.add_argument("--fps", type=int, default=DEFAULT_FPS)
    ap.add_argument("--denoise", action="store_true")
    args = ap.parse_args()

    rep = analyze_range(args.file, denoise=args.denoise, make_plot=False)
    if args.png:
        print("PNG ساخته شد:", args.png)
    out = args.output or (os.path.splitext(args.file)[0] + "_trace.mp4")
    print("در حال رندر ویدیو...")
    render_video(rep["_times"], rep["_freqs"], rep["notes"], rep["_wav"], out, fps=args.fps)
    print("ویدیو ساخته شد:", out)


if __name__ == "__main__":
    sys.exit(main())
