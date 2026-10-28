#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
سرور بک‌اند «ردِّ صدا» — بالاترین و پایین‌ترین نتِ خوانده‌شده
=============================================================
این سرور نسخهٔ سبک‌شدهٔ پروژه است: تمام تحلیل مقام/سولفژ/امتیازدهی حذف
شده و فقط سه کار انجام می‌دهد:

  ۱) آپلود فایل صوتی و تحلیل «دامنهٔ صدا» (بالاترین/پایین‌ترین نت،
     گسترهٔ صدا، نت‌به‌نت، جدول نت‌ها) با موتور Praat
  ۲) رندر ویدیوی «ردِّ صدا» (منحنی نرم + نقطهٔ نورانی متحرک روی خطوط نت)
  ۳) پیانوی زنده با میکروفون: نمایش زندهٔ نت + بالاترین/پایین‌ترین نتِ
     همان جلسه (تحلیل با همان موتور Praat روی سرور)

اجرا:
    cd server && python3 main.py           # http://0.0.0.0:8080
    یا: uvicorn main:app --host 0.0.0.0 --port 8080
"""

import asyncio
import hashlib
import json
import os
import shutil
import sys
import threading
import time
import traceback
import uuid
from pathlib import Path

import numpy as np
from fastapi import FastAPI, File, Form, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

# نوع MIME برای فایل‌های PWA (مانیفست و سرویس‌ورکر) — بعضی سیستم‌ها نمی‌شناسند
import mimetypes
mimetypes.add_type("application/manifest+json", ".webmanifest")
mimetypes.add_type("text/javascript", ".js")

SERVER_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SERVER_DIR.parent
PYTHON_DIR = PROJECT_DIR / "python"
WEB_DIR = PROJECT_DIR / "web"
JOBS_DIR = SERVER_DIR / "jobs"
JOBS_DIR.mkdir(exist_ok=True)
CACHE_DIR = SERVER_DIR / "cache"     # منحنی‌های محاسبه‌شده (md5 → npz)
CACHE_DIR.mkdir(exist_ok=True)

sys.path.insert(0, str(PYTHON_DIR))

from range_analyzer import analyze_range, public_report          # noqa: E402
from pitch_engine import extract_pitch_chunk, freq_to_note_info  # noqa: E402
import trace_video                                               # noqa: E402

app = FastAPI(title="ردِّ صدا — API")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

JOB_TTL_SEC = 24 * 3600
ANALYSIS_CACHE = {}     # md5 → گزارش عمومی (برای فایل‌های تکراری)
CACHE_MAX = 32


# ============================================================================
# مدیریت Job
# ============================================================================

class Job:
    def __init__(self, job_id: str, kind: str):
        self.id = job_id
        self.kind = kind            # "analyze" | "video"
        self.status = "pending"     # pending → running → done | error
        self.stage = ""
        self.progress = 0.0
        self.result = None
        self.error = None
        self.dir = JOBS_DIR / job_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self._loop = None

    def update(self, stage: str, frac: float):
        self.stage = stage
        self.progress = max(0.0, min(1.0, float(frac)))
        if self._loop is None:
            return
        payload = {"job_id": self.id, "kind": self.kind, "status": self.status,
                   "stage": self.stage, "progress": self.progress}
        try:
            self._loop.call_soon_threadsafe(
                lambda: asyncio.ensure_future(_broadcast(self.id, payload)))
        except Exception:
            pass

    def finish(self, result):
        self.result = result
        self.status = "done"
        self.stage = "done"
        self.progress = 1.0
        if self._loop:
            payload = {"job_id": self.id, "kind": self.kind, "status": "done",
                       "stage": "done", "progress": 1.0, "result": result}
            try:
                self._loop.call_soon_threadsafe(
                    lambda: asyncio.ensure_future(_broadcast(self.id, payload)))
            except Exception:
                pass

    def fail(self, msg: str):
        self.error = str(msg)
        self.status = "error"
        self.stage = "error"
        if self._loop:
            payload = {"job_id": self.id, "kind": self.kind, "status": "error",
                       "stage": "error", "error": self.error}
            try:
                self._loop.call_soon_threadsafe(
                    lambda: asyncio.ensure_future(_broadcast(self.id, payload)))
            except Exception:
                pass


JOBS = {}
WS_CLIENTS = {}


async def _broadcast(job_id, payload):
    for ws in list(WS_CLIENTS.get(job_id, [])):
        try:
            await ws.send_json(payload)
        except Exception:
            pass


@app.on_event("startup")
async def _on_startup():
    _cleanup_old_jobs()
    _reload_done_jobs()


def _cleanup_old_jobs():
    """پاک‌سازی jobهای قدیمی‌تر از ۲۴ ساعت (+ کش منحنی)."""
    try:
        now = time.time()
        removed = 0
        for d in JOBS_DIR.iterdir():
            if d.is_dir() and now - d.stat().st_mtime > JOB_TTL_SEC:
                shutil.rmtree(d, ignore_errors=True)
                removed += 1
        for f in CACHE_DIR.iterdir():
            try:
                if f.is_file() and now - f.stat().st_mtime > JOB_TTL_SEC:
                    f.unlink()
            except Exception:
                continue
        if removed:
            print(f"🧹 {removed} job کهنه پاک شد")
    except Exception:
        pass


def _reload_done_jobs():
    """پس از ری‌استارت، گزارش‌های تمام‌شده از report.json برمی‌گردند."""
    restored = 0
    try:
        for d in JOBS_DIR.iterdir():
            rp = d / "report.json"
            if not d.is_dir() or not rp.exists():
                continue
            try:
                with open(rp, encoding="utf-8") as f:
                    report = json.load(f)
                job = Job(d.name, "analyze")
                job.status, job.progress, job.result = "done", 1.0, report
                JOBS[d.name] = job
                restored += 1
            except Exception:
                continue
        if restored:
            print(f"♻️ {restored} گزارش قبلی بازیابی شد")
    except Exception:
        pass


def _md5_of_file(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def _cache_put(key, value):
    if len(ANALYSIS_CACHE) >= CACHE_MAX:
        ANALYSIS_CACHE.pop(next(iter(ANALYSIS_CACHE)))
    ANALYSIS_CACHE[key] = value


# ============================================================================
# مرحله ۱: آپلود
# ============================================================================

VIDEO_EXT = {".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".3gp", ".mpeg", ".mpg", ".ts"}


def _write_original_name(job_dir: Path, name: str):
    try:
        (job_dir / "original_name.txt").write_text(name or "", encoding="utf-8")
    except Exception:
        pass


def _read_original_name(job_dir: Path, fallback="input") -> str:
    try:
        p = job_dir / "original_name.txt"
        if p.exists():
            name = p.read_text(encoding="utf-8").strip()
            if name:
                return name
    except Exception:
        pass
    return fallback


@app.post("/api/upload")
async def upload_file(file: UploadFile = File(...)):
    job_id = uuid.uuid4().hex[:12]
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    original_name = Path(file.filename or "input.wav").name
    ext = Path(original_name).suffix or ".wav"
    saved_path = job_dir / f"input{ext}"
    with open(saved_path, "wb") as f:
        shutil.copyfileobj(file.file, f)

    size = saved_path.stat().st_size
    if size == 0:
        return JSONResponse({"error": "فایل خالی است"}, status_code=400)

    _write_original_name(job_dir, original_name)

    return {"job_id": job_id, "filename": original_name,
            "saved_as": saved_path.name, "size_bytes": size,
            "kind": "video" if ext.lower() in VIDEO_EXT else "audio"}


# ============================================================================
# مرحله ۲: تحلیل دامنهٔ صدا
# ============================================================================

@app.post("/api/analyze/{job_id}")
async def start_analyze(job_id: str, denoise: bool = Form(False)):
    job_dir = JOBS_DIR / job_id
    if not job_dir.exists():
        return JSONResponse({"error": "job پیدا نشد"}, status_code=404)

    input_files = list(job_dir.glob("input.*"))
    if not input_files:
        return JSONResponse({"error": "فایل ورودی پیدا نشد"}, status_code=404)
    input_path = str(input_files[0])

    job = Job(job_id, "analyze")
    job._loop = asyncio.get_running_loop()
    JOBS[job_id] = job
    job.status = "running"

    def _worker():
        try:
            md5 = _md5_of_file(input_path)
            cache_key = (md5, bool(denoise))
            cached = ANALYSIS_CACHE.get(cache_key)
            contour = job_dir / "contour.npz"

            if cached is not None:
                job.update("plot", 0.9)
                cached_npz = CACHE_DIR / f"{md5}_{int(bool(denoise))}.npz"
                if not contour.exists():
                    if cached_npz.exists():
                        shutil.copyfile(cached_npz, contour)   # کش دیسکی
                    else:
                        _extract_contour_only(input_path, contour, bool(denoise))
                        try:
                            shutil.copyfile(contour, cached_npz)
                        except Exception:
                            pass
                report = json.loads(json.dumps(cached))
                _make_plot_from_contour(contour, report, job_dir)
            else:
                report = analyze_range(
                    input_path, denoise=bool(denoise),
                    make_plot=True, plot_dir=str(job_dir),
                    progress_callback=job.update, work_dir=str(job_dir),
                    keep_work_wav=True,
                )
                # منحنی برای مرحلهٔ ویدیو ذخیره می‌شود (تحلیل دوباره لازم نیست).
                # صدای mux همان صدای تحلیل‌شده است: audio_for_video.wav
                # (اگر ورودی خودش WAV بود، همان فایل ورودی).
                try:
                    np.savez_compressed(contour, times=report["_times"],
                                        freqs=report["_freqs"])
                    shutil.copyfile(contour, CACHE_DIR / f"{md5}_{int(bool(denoise))}.npz")
                except Exception:
                    pass
                pub = public_report(report)
                _cache_put(cache_key, pub)
                report = pub

            original_name = _read_original_name(job_dir, Path(input_path).name)
            is_video = Path(input_path).suffix.lower() in VIDEO_EXT
            report["meta"].update({
                "original_name": original_name,
                "original_kind": "video" if is_video else "audio",
                "visualization_url": f"/api/file/{job_id}/range_trace.png",
                "audio_url": f"/api/file/{job_id}/{Path(input_path).name}",
                "original_url": f"/api/file/{job_id}/{Path(input_path).name}",
                # لینک‌های «ذخیره روی دستگاه» (برای استفادهٔ آفلاین)
                "downloads": {
                    "original": f"/api/download/{job_id}/original",
                    "image": f"/api/download/{job_id}/image",
                    "report": f"/api/download/{job_id}/report",
                },
            })

            with open(job_dir / "report.json", "w", encoding="utf-8") as f:
                json.dump(report, f, ensure_ascii=False, indent=1)

            job.finish(report)
        except Exception as e:
            traceback.print_exc()
            job.fail(str(e))

    threading.Thread(target=_worker, daemon=True).start()
    return {"job_id": job_id, "status": "started"}


def _extract_contour_only(path, out_npz, denoise):
    """فقط منحنی F0 (برای کش) — بدون ساخت گزارش کامل."""
    rep = analyze_range(path, denoise=denoise, make_plot=False)
    np.savez_compressed(out_npz, times=rep["_times"], freqs=rep["_freqs"])


def _make_plot_from_contour(contour_path, report, job_dir):
    try:
        data = np.load(contour_path)
        trace_video.render_static_png(data["times"], data["freqs"],
                                      report.get("notes") or [],
                                      str(job_dir / "range_trace.png"))
    except Exception as e:
        print("هشدار: رسم تصویر از کش ناموفق بود:", e)


# ============================================================================
# مرحله ۳: ویدیوی ردِّ صدا
# ============================================================================

@app.post("/api/video/{job_id}")
async def start_video(job_id: str, fps: int = Form(0)):
    job_dir = JOBS_DIR / job_id
    input_files = list(job_dir.glob("input.*"))
    if not input_files:
        return JSONResponse({"error": "فایل ورودی پیدا نشد"}, status_code=404)

    report_path = job_dir / "report.json"
    if not report_path.exists():
        return JSONResponse(
            {"error": "اول تحلیل را اجرا کنید (گزارش پیدا نشد)"}, status_code=400)

    with open(report_path, encoding="utf-8") as f:
        report = json.load(f)

    out_path = str(job_dir / "range_trace.mp4")
    job = Job(job_id + "_video", "video")
    job._loop = asyncio.get_running_loop()
    JOBS[job.id] = job
    job.status = "running"

    def _worker():
        try:
            contour = job_dir / "contour.npz"
            if contour.exists():
                data = np.load(contour)
                times, freqs = data["times"], data["freqs"]
                job.update("video", 0.02)
            else:
                rep = analyze_range(
                    str(input_files[0]), make_plot=False,
                    work_dir=str(job_dir),
                    progress_callback=lambda st, fr: job.update(st, fr * 0.6))
                times, freqs = rep["_times"], rep["_freqs"]
                np.savez_compressed(contour, times=times, freqs=freqs)

            wav = job_dir / "audio_for_video.wav"
            audio = str(wav) if wav.exists() else str(input_files[0])
            # fps=0 (پیش‌فرض) یعنی انتخاب خودکار بر اساس طول فایل
            trace_video.render_video(times, freqs, report.get("notes") or [],
                                     audio, out_path,
                                     fps=(int(fps) if fps and fps > 0 else None),
                                     progress_callback=job.update)
            job.finish({"video_url": f"/api/file/{job_id}/range_trace.mp4"})
        except Exception as e:
            traceback.print_exc()
            job.fail(str(e))

    threading.Thread(target=_worker, daemon=True).start()
    return {"job_id": job.id, "status": "started"}


# ============================================================================
# وضعیت / فایل / سلامت
# ============================================================================

@app.get("/api/health")
async def health():
    return {"ok": True, "jobs": len(JOBS)}


@app.get("/api/status/{job_id}")
async def get_status(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        return JSONResponse({"error": "job پیدا نشد"}, status_code=404)
    return {"id": job.id, "kind": job.kind, "status": job.status,
            "stage": job.stage, "progress": job.progress,
            "result": job.result, "error": job.error}


@app.get("/api/download/{job_id}/{kind}")
async def download_asset(job_id: str, kind: str):
    """
    ذخیرهٔ خروجی‌ها روی دستگاه کاربر (برنامه آفلاین):
      original → همان فایل صوتی/ویدیویی که آپلود شده (با نام اصلی)
      image    → تصویر تحلیل‌شده (PNG)
      report   → گزارش کامل (JSON)
      video    → ویدیوی «ردِّ صدا» (MP4) — اگر ساخته شده باشد
    """
    job_dir = JOBS_DIR / job_id
    if not job_dir.exists():
        return JSONResponse({"error": "job پیدا نشد"}, status_code=404)

    original_name = _read_original_name(job_dir, "input")

    if kind == "original":
        files = [p for p in job_dir.glob("input.*") if p.is_file()]
        if not files:
            return JSONResponse({"error": "فایل ورودی پیدا نشد"}, status_code=404)
        path = files[0]
        name = original_name if Path(original_name).suffix else path.name
    elif kind == "image":
        path = job_dir / "range_trace.png"
        name = "range-trace.png"
    elif kind == "report":
        path = job_dir / "report.json"
        name = "range-report.json"
    elif kind == "video":
        path = job_dir / "range_trace.mp4"
        name = "range-trace.mp4"
    else:
        return JSONResponse({"error": "نوع خروجی نامعتبر است"}, status_code=400)

    if not path.exists():
        return JSONResponse({"error": "این خروجی هنوز ساخته نشده است"}, status_code=404)

    return FileResponse(str(path), filename=name,
                        media_type="application/octet-stream" if kind == "report" else None,
                        headers={"Cache-Control": "public, max-age=3600"})


@app.get("/api/file/{job_id}/{filename}")
async def get_file(job_id: str, filename: str, download: bool = False):
    # جلوگیری از path traversal
    if "/" in filename or "\\" in filename or filename.startswith("."):
        return JSONResponse({"error": "مسیر نامعتبر"}, status_code=400)
    path = JOBS_DIR / job_id / filename
    if not path.exists():
        return JSONResponse({"error": "فایل پیدا نشد"}, status_code=404)
    headers = {"Cache-Control": "public, max-age=3600"}
    if download:
        headers["Content-Disposition"] = f'attachment; filename="{filename}"'
    return FileResponse(str(path), headers=headers)


# ============================================================================
# پیشرفت زندهٔ job
# ============================================================================

@app.websocket("/ws/job/{job_id}")
async def ws_job_progress(websocket: WebSocket, job_id: str):
    await websocket.accept()
    WS_CLIENTS.setdefault(job_id, []).append(websocket)
    job = JOBS.get(job_id)
    if job:
        await websocket.send_json({
            "job_id": job.id, "kind": job.kind, "status": job.status,
            "stage": job.stage, "progress": job.progress,
            "result": job.result, "error": job.error,
        })
    try:
        while True:
            await websocket.receive_text()
    except WebSocketDisconnect:
        pass
    except Exception:
        pass
    finally:
        try:
            WS_CLIENTS.get(job_id, []).remove(websocket)
        except Exception:
            pass


# ============================================================================
# پیانوی زنده با میکروفون (Praat روی سرور)
# ============================================================================

def _note_payload(f0, session_lo, session_hi):
    info = freq_to_note_info(f0)
    if info is None:
        return None
    return {
        "note_name": info["note_name"],
        "midi": info["midi"],
        "midi_exact": round(69.0 + 12.0 * np.log2(f0 / 440.0), 3),
        "f0_hz": round(float(f0), 2),
        "cents_offset": info["cents_offset"],
        "octave": info["octave"],
        "is_black_key": info["is_black_key"],
        "session_low": session_lo,
        "session_high": session_hi,
    }


@app.websocket("/ws/live-pitch")
async def ws_live_pitch(websocket: WebSocket):
    """
    کلاینت قطعه‌های PCM خام (Float32 یا Int16) را با نرخ نمونهٔ خودش
    می‌فرستد؛ سرور با Praat f0 را برمی‌گرداند.

    نمایش: با «قفل سریع» هموار می‌شود (پرش‌های بزرگ بلافاصله اعمال
    می‌شوند، لرزش‌های کوچک نرم می‌شوند) تا نقطه روی خطوط نت نلرزد.

    رکورد جلسه (بالاترین/پایین‌ترین نت): فقط وقتی ثبت می‌شود که یک نت
    برای چند بستهٔ پیاپی پایدار بماند — بنابراین نت‌های واسطِ گذار
    (که فقط حاصل حرکت صدا بین دو نت هستند) رکورد را خراب نمی‌کنند.
    """
    await websocket.accept()
    sample_rate = 16000
    smooth = None
    stable_midi = None
    stable_count = 0
    session_lo = None      # (midi, name, hz)
    session_hi = None
    STABLE_CHUNKS = 3      # ~۰٫۳۶ ثانیه پایداری

    try:
        while True:
            msg = await websocket.receive()
            if msg.get("text") is not None:
                try:
                    cfg = json.loads(msg["text"])
                    if "sample_rate" in cfg:
                        sample_rate = int(cfg["sample_rate"])
                    if cfg.get("reset"):
                        smooth = None
                        stable_midi, stable_count = None, 0
                        session_lo = session_hi = None
                        await websocket.send_json({"reset": True})
                except Exception:
                    pass
                continue

            raw = msg.get("bytes")
            if not raw:
                continue

            # پیام ۱ بایتی نوع را جدا کن: 0 = float32، 1 = int16
            fmt, data = raw[0], raw[1:]
            if fmt == 0:
                samples = np.frombuffer(data, dtype=np.float32)
            else:
                samples = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0

            f0 = extract_pitch_chunk(samples, sample_rate, fmin=60.0, fmax=1000.0)
            note = None

            if f0:
                midi = 69.0 + 12.0 * np.log2(f0 / 440.0)
                if smooth is None or abs(midi - smooth) > 1.0:
                    smooth = midi            # پرش واقعی: بلافاصله
                else:
                    smooth += 0.5 * (midi - smooth)   # نرم‌سازی لرزش

                info = freq_to_note_info(440.0 * 2 ** ((smooth - 69.0) / 12.0))
                if info:
                    # ---- پایداری نت: چند بستهٔ پیاپی روی یک نت ----
                    rounded = int(round(midi))
                    if rounded == stable_midi:
                        stable_count += 1
                    else:
                        stable_midi, stable_count = rounded, 1
                    if stable_count >= STABLE_CHUNKS:
                        if session_hi is None or midi > session_hi[0]:
                            session_hi = (midi, info["note_name"], float(f0))
                        if session_lo is None or midi < session_lo[0]:
                            session_lo = (midi, info["note_name"], float(f0))

                    note = _note_payload(
                        440.0 * 2 ** ((smooth - 69.0) / 12.0),
                        session_lo[0] if session_lo else None,
                        session_hi[0] if session_hi else None,
                    )
                    note["note_name"] = info["note_name"]
                    note["stable"] = stable_count >= STABLE_CHUNKS

            await websocket.send_json({
                "note": note,
                "session": {
                    "lowest": None if not session_lo else {
                        "note_name": session_lo[1],
                        "midi": round(session_lo[0], 2),
                        "f0_hz": round(session_lo[2], 2)},
                    "highest": None if not session_hi else {
                        "note_name": session_hi[1],
                        "midi": round(session_hi[0], 2),
                        "f0_hz": round(session_hi[2], 2)},
                    "span_semitones": (round(session_hi[0] - session_lo[0], 2)
                                       if (session_lo and session_hi) else 0.0),
                },
            })
    except WebSocketDisconnect:
        pass
    except Exception:
        pass


# ============================================================================
# سرو فایل‌های استاتیک وب (آخر از همه mount شود)
# ============================================================================

app.mount("/", StaticFiles(directory=str(WEB_DIR), html=True), name="web")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
