#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
سرور بک‌اند تحلیلگر تلاوت قرآن
====================================
این سرور موتور پایتونی موجود پروژه (pitch_engine, quran_maqam_analyzer,
piano_visualizer, compare_recitations) را از طریق یک HTTP/WebSocket API
در دسترس رابط کاربری وب (web/quran.html) قرار می‌دهد.

معماری:
  - آپلود فایل -> ذخیره موقت -> اجرای تحلیل در ترد پس‌زمینه (چون تحلیل
    فایل‌های طولانی می‌تواند دقیقه‌ها طول بکشد و نباید event loop را
    مسدود کند) -> ارسال پیشرفت زنده از طریق WebSocket -> در پایان،
    نتیجه (JSON + مسیر تصاویر/ویدیو) در دسترس قرار می‌گیرد.
  - میکروفون زنده: صدا به‌صورت قطعه‌قطعه (chunk) از طریق WebSocket به سرور
    استریم می‌شود، سرور با extract_pitch_chunk (همان موتور Praat) پرده صدا
    را تشخیص می‌دهد و اطلاعات نت را برای رسم زنده پیانو در مرورگر برمی‌گرداند.

اجرا:
    cd server && python3 main.py
    (یا: uvicorn main:app --host 0.0.0.0 --port 8080)
"""

import asyncio
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
from fastapi import FastAPI, File, UploadFile, WebSocket, WebSocketDisconnect, Form
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.middleware.cors import CORSMiddleware

SERVER_DIR = Path(__file__).resolve().parent
PROJECT_DIR = SERVER_DIR.parent
PYTHON_DIR = PROJECT_DIR / "python"
WEB_DIR = PROJECT_DIR / "web"
JOBS_DIR = SERVER_DIR / "jobs"      # فایل‌های آپلودشده + خروجی هر job
JOBS_DIR.mkdir(exist_ok=True)

sys.path.insert(0, str(PYTHON_DIR))

import quran_maqam_analyzer as qma          # noqa: E402
import piano_visualizer as pv               # noqa: E402
import compare_recitations as cr            # noqa: E402
from pitch_engine import extract_pitch_chunk, freq_to_note_info  # noqa: E402

app = FastAPI(title="تحلیلگر تلاوت قرآن - API")
app.add_middleware(
    CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)


# ============================================================================
# مدیریت Job ها (هر تحلیل = یک job با شناسه یکتا)
# ============================================================================

class Job:
    def __init__(self, job_id: str, kind: str):
        self.id = job_id
        self.kind = kind  # "analyze" | "video" | "compare"
        self.status = "pending"  # pending -> running -> done | error
        self.stage = ""
        self.progress = 0.0
        self.result = None
        self.error = None
        self.dir = JOBS_DIR / job_id
        self.dir.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._loop = None  # event loop اصلی، برای زمان‌بندی امن پیام به وب‌سوکت‌ها از ترد جانبی
        self.subscribers = []  # لیست WebSocket های مشترک این job

    def update(self, stage, progress):
        with self._lock:
            self.stage = stage
            self.progress = progress
        self._broadcast()

    def finish(self, result):
        with self._lock:
            self.status = "done"
            self.progress = 1.0
            self.result = result
        self._broadcast()

    def fail(self, error_msg):
        with self._lock:
            self.status = "error"
            self.error = error_msg
        self._broadcast()

    def snapshot(self):
        with self._lock:
            return {
                "id": self.id, "kind": self.kind, "status": self.status,
                "stage": self.stage, "progress": round(self.progress, 4),
                "result": self.result, "error": self.error,
            }

    def _broadcast(self):
        if self._loop is None:
            return
        payload = self.snapshot()
        for ws in list(self.subscribers):
            asyncio.run_coroutine_threadsafe(_safe_send(ws, payload), self._loop)


async def _safe_send(ws: WebSocket, payload: dict):
    try:
        await ws.send_json(payload)
    except Exception:
        pass


JOBS: dict[str, Job] = {}


# ============================================================================
# مرحله ۱: آپلود فایل
# ============================================================================

@app.post("/api/upload")
async def upload_file(file: UploadFile = File(...)):
    job_id = uuid.uuid4().hex[:12]
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    ext = Path(file.filename).suffix or ".wav"
    saved_path = job_dir / f"input{ext}"
    with open(saved_path, "wb") as f:
        shutil.copyfileobj(file.file, f)

    return {"job_id": job_id, "filename": file.filename, "saved_as": str(saved_path.name)}


# ============================================================================
# مرحله ۲: شروع تحلیل کامل (گزارش + ملوگراف + تایم‌لاین مقام)
# ============================================================================

@app.post("/api/analyze/{job_id}")
async def start_analyze(job_id: str, denoise: bool = Form(False), top_k: int = Form(3)):
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
            def progress_cb(stage, frac):
                job.update(stage, frac)

            report = qma.analyze_recitation(
                input_path, denoise=denoise, top_k=top_k,
                make_plot=True, plot_dir=str(job_dir),
                progress_callback=progress_cb,
            )
            # مسیر تصویر را به مسیر قابل‌دانلود از API تبدیل کن
            if "visualization_file" in report["meta"]:
                png_name = Path(report["meta"]["visualization_file"]).name
                report["meta"]["visualization_url"] = f"/api/file/{job_id}/{png_name}"

            with open(job_dir / "report.json", "w", encoding="utf-8") as f:
                json.dump(report, f, ensure_ascii=False, indent=2)

            job.finish(report)
        except Exception as e:
            traceback.print_exc()
            job.fail(str(e))

    threading.Thread(target=_worker, daemon=True).start()
    return {"job_id": job_id, "status": "started"}


# ============================================================================
# مرحله ۳: شروع صادرات ویدیو (پیانو هم‌گام با صدا)
# ============================================================================

@app.post("/api/video/{job_id}")
async def start_video(job_id: str, fps: int = Form(12)):
    job_dir = JOBS_DIR / job_id
    input_files = list(job_dir.glob("input.*"))
    if not input_files:
        return JSONResponse({"error": "فایل ورودی پیدا نشد"}, status_code=404)
    input_path = str(input_files[0])
    out_path = str(job_dir / "piano_video.mp4")

    job = Job(job_id + "_video", "video")
    job._loop = asyncio.get_running_loop()
    JOBS[job.id] = job
    job.status = "running"

    def _worker():
        try:
            def progress_cb(stage, frac):
                job.update(stage, frac)

            pv.export_video_from_file(input_path, out_path=out_path, fps=fps,
                                        progress_callback=progress_cb)
            job.finish({"video_url": f"/api/file/{job_id}/piano_video.mp4"})
        except Exception as e:
            traceback.print_exc()
            job.fail(str(e))

    threading.Thread(target=_worker, daemon=True).start()
    return {"job_id": job.id, "status": "started"}


# ============================================================================
# مرحله ۴: مقایسه دو فراز (مرجع + کاربر، با برش زمانی اختیاری)
# ============================================================================

@app.post("/api/compare")
async def start_compare(
    reference: UploadFile = File(...),
    performance: UploadFile = File(...),
    ref_start: float = Form(None),
    ref_end: float = Form(None),
    perf_start: float = Form(None),
    perf_end: float = Form(None),
):
    job_id = uuid.uuid4().hex[:12]
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    ref_ext = Path(reference.filename).suffix or ".wav"
    perf_ext = Path(performance.filename).suffix or ".wav"
    ref_path = job_dir / f"reference{ref_ext}"
    perf_path = job_dir / f"performance{perf_ext}"
    with open(ref_path, "wb") as f:
        shutil.copyfileobj(reference.file, f)
    with open(perf_path, "wb") as f:
        shutil.copyfileobj(performance.file, f)

    job = Job(job_id, "compare")
    job._loop = asyncio.get_running_loop()
    JOBS[job_id] = job
    job.status = "running"

    def _worker():
        try:
            job.update("trim", 0.02)
            ref_final = _maybe_trim(str(ref_path), ref_start, ref_end, job_dir, "reference_trimmed")
            perf_final = _maybe_trim(str(perf_path), perf_start, perf_end, job_dir, "performance_trimmed")

            job.update("analyzing_reference", 0.1)
            result, ref, perf, mel_sim = cr.compare_files(ref_final, perf_final)

            plot_path = str(job_dir / "comparison_result.png")
            job.update("plotting", 0.9)
            cr.plot_comparison(ref, perf, result, plot_path)
            result["meta"]["visualization_url"] = f"/api/file/{job_id}/comparison_result.png"

            phrase_plot_path = str(job_dir / "comparison_phrases.png")
            cr.plot_phrase_comparison(ref, perf, result, phrase_plot_path)
            result["meta"]["phrase_visualization_url"] = f"/api/file/{job_id}/comparison_phrases.png"

            with open(job_dir / "compare_report.json", "w", encoding="utf-8") as f:
                json.dump(result, f, ensure_ascii=False, indent=2)

            job.finish(result)
        except Exception as e:
            traceback.print_exc()
            job.fail(str(e))

    threading.Thread(target=_worker, daemon=True).start()
    return {"job_id": job_id, "status": "started"}


def _maybe_trim(path, start, end, job_dir, out_name):
    """در صورتی که کاربر بازه زمانی مشخص کرده باشد، فایل را با ffmpeg برش می‌زند."""
    if start is None and end is None:
        return path
    if start is not None and end is not None and end <= start:
        # اعتبارسنجی سمت سرور (دفاع دوم، مستقل از اعتبارسنجی سمت مرورگر) —
        # بدون این بررسی، ffmpeg بی‌صدا یک کلیپ تقریباً خالی (۰.۱ ثانیه)
        # می‌سازد که نتیجهٔ تحلیل/مقایسهٔ روی آن کاملاً بی‌معنا خواهد بود.
        raise ValueError(
            f"بازهٔ زمانی نامعتبر برای «{out_name}»: زمان پایان ({end}s) باید "
            f"بزرگ‌تر از زمان شروع ({start}s) باشد."
        )
    import subprocess
    out_path = str(job_dir / f"{out_name}.wav")
    cmd = ["ffmpeg", "-y", "-i", path]
    if start is not None:
        cmd += ["-ss", str(start)]
    if end is not None:
        duration = end - (start or 0)
        cmd += ["-t", str(max(duration, 0.1))]
    cmd += [out_path]
    subprocess.run(cmd, capture_output=True, check=True)
    return out_path


# ============================================================================
# دریافت وضعیت job (fallback در صورت عدم استفاده از وب‌سوکت)
# ============================================================================

@app.get("/api/status/{job_id}")
async def get_status(job_id: str):
    job = JOBS.get(job_id)
    if not job:
        return JSONResponse({"error": "job پیدا نشد"}, status_code=404)
    return job.snapshot()


@app.get("/api/file/{job_id}/{filename}")
async def get_file(job_id: str, filename: str, download: bool = False):
    path = JOBS_DIR / job_id / filename
    if not path.exists():
        return JSONResponse({"error": "فایل پیدا نشد"}, status_code=404)

    ext = path.suffix.lower()
    media_type = {
        ".mp4": "video/mp4",
        ".png": "image/png",
        ".json": "application/json",
        ".wav": "audio/wav",
    }.get(ext)

    # FileResponse به‌صورت پیش‌فرض از HTTP Range requests پشتیبانی می‌کند
    # (لازم برای Seek کردن در ویدیو/صدا در مرورگر بدون دانلود کامل فایل).
    headers = {}
    if download:
        headers["Content-Disposition"] = f'attachment; filename="{filename}"'

    return FileResponse(str(path), media_type=media_type, headers=headers)


@app.websocket("/ws/job/{job_id}")
async def ws_job_progress(websocket: WebSocket, job_id: str):
    await websocket.accept()
    job = JOBS.get(job_id)
    if not job:
        await websocket.send_json({"error": "job پیدا نشد"})
        await websocket.close()
        return

    job._loop = asyncio.get_running_loop()
    job.subscribers.append(websocket)
    await websocket.send_json(job.snapshot())

    try:
        while True:
            await websocket.receive_text()  # فقط برای نگه‌داشتن اتصال؛ کلاینت چیزی نمی‌فرستد
    except WebSocketDisconnect:
        pass
    finally:
        if websocket in job.subscribers:
            job.subscribers.remove(websocket)


# ============================================================================
# میکروفون زنده: استریم صدا از مرورگر -> تشخیص نت با Praat -> پاسخ زنده
# ============================================================================

@app.websocket("/ws/live-pitch")
async def ws_live_pitch(websocket: WebSocket):
    """
    مرورگر قطعات صوتی خام (Float32Array به‌صورت PCM، نرخ نمونه‌برداری ثابت
    مثلاً ۱۶۰۰۰ هرتز) را از میکروفون به این وب‌سوکت ارسال می‌کند. سرور با
    استفاده از همان موتور Praat (extract_pitch_chunk) فرکانس پایه (F0) هر
    قطعه را تشخیص می‌دهد و برای هایلایت زنده کلاویه پیانو برمی‌گرداند.

    علاوه بر آن، یک بافرِ رونده (rolling buffer) از نمونه‌های F0 اخیر نگه
    می‌دارد تا:
      ۱) مقام لحظه‌ای (بر اساس چند ثانیهٔ اخیر) و سولفژ مقامی نت جاری را
         محاسبه و همراه هر پیام F0 برگرداند؛
      ۲) با تشخیص مکث/سکوت واقعی (دقیقاً مشابه تحلیل فایل آپلودی)، پایان
         هر «فراز» طبیعی زنده را شناسایی کرده و یک خلاصهٔ فراز (مقام غالب
         آن فراز + دنبالهٔ سولفژ نت‌هایش + روند ملودی) به مرورگر بفرستد.
    """
    await websocket.accept()
    sample_rate = 16000
    chunk_dur_sec = 4096 / sample_rate  # طول تقریبی هر قطعهٔ دریافتی از ScriptProcessor

    # بافر رونده برای مقام لحظه‌ای (چند ثانیهٔ اخیر)
    LIVE_WINDOW_SEC = 6.0
    recent_freqs = []   # [(t, f0), ...]

    # بافر فراز جاری (از آخرین پایان فراز تا الان)
    phrase_freqs = []    # [(t, f0), ...] از ابتدای فراز جاری
    silence_run_sec = 0.0
    t_cursor = 0.0
    PHRASE_SILENCE_GATE_SEC = 0.4  # این‌مقدار سکوت پیاپی = پایان فراز

    try:
        while True:
            msg = await websocket.receive()
            if msg.get("type") == "websocket.disconnect":
                break

            if "bytes" in msg and msg["bytes"] is not None:
                raw = msg["bytes"]
                samples = np.frombuffer(raw, dtype=np.float32)
                dur = len(samples) / sample_rate if sample_rate else chunk_dur_sec

                f0 = await asyncio.get_running_loop().run_in_executor(
                    None, extract_pitch_chunk, samples, sample_rate,
                )
                note_info = freq_to_note_info(f0) if f0 else None

                t_cursor += dur

                # --- به‌روزرسانی بافر رونده مقام لحظه‌ای ---
                if f0:
                    recent_freqs.append((t_cursor, f0))
                cutoff = t_cursor - LIVE_WINDOW_SEC
                recent_freqs = [(t, f) for (t, f) in recent_freqs if t >= cutoff]

                live_maqam = None
                solfege = None
                if len(recent_freqs) >= 3:
                    pseudo_notes = [{"f0_hz": f, "duration": dur} for (_, f) in recent_freqs]
                    hist = qma.build_qtet_histogram(pseudo_notes)
                    candidates = qma.detect_tonic_and_maqam(hist, top_k=1)
                    if candidates:
                        best = candidates[0]
                        live_maqam = {
                            "maqam": best["maqam"],
                            "confidence_pct": best["confidence_pct"],
                            "tonic_freq_hz": best["tonic_freq_hz"],
                        }
                        if f0:
                            tonic_hz = best["tonic_freq_hz"]
                            maqam_cents = qma.MAQAMAT[best["maqam"]]["cents"]
                            octave_shift = round(np.log2(f0 / tonic_hz)) if tonic_hz > 0 else 0
                            tonic_adj = tonic_hz * (2 ** octave_shift)
                            solfege = qma.note_to_solfege(f0, tonic_adj, maqam_cents)

                # --- ردیابی فراز جاری بر اساس سکوت واقعی ---
                phrase_completed = None
                if f0:
                    phrase_freqs.append((t_cursor, f0, dur))
                    silence_run_sec = 0.0
                else:
                    silence_run_sec += dur
                    if silence_run_sec >= PHRASE_SILENCE_GATE_SEC and len(phrase_freqs) >= 2:
                        pseudo_notes = [{"f0_hz": f, "duration": d} for (_, f, d) in phrase_freqs]
                        hist = qma.build_qtet_histogram(pseudo_notes)
                        candidates = qma.detect_tonic_and_maqam(hist, top_k=1)
                        best = candidates[0] if candidates else None
                        sol_sequence = []
                        trend = None
                        if best:
                            tonic_hz = best["tonic_freq_hz"]
                            maqam_cents = qma.MAQAMAT[best["maqam"]]["cents"]
                            freqs_only = np.array([f for (_, f, _) in phrase_freqs])
                            center_freq = float(np.exp(np.mean(np.log(freqs_only))))
                            octave_shift = round(np.log2(center_freq / tonic_hz)) if tonic_hz > 0 else 0
                            tonic_adj = tonic_hz * (2 ** octave_shift)
                            cents_seq = []
                            for (_, f, _) in phrase_freqs:
                                sol = qma.note_to_solfege(f, tonic_adj, maqam_cents)
                                if sol:
                                    sol_sequence.append(sol["solfege_name"])
                                cents_seq.append(1200.0 * np.log2(f / tonic_adj))
                            trend = qma._melody_trend(cents_seq)
                        phrase_completed = {
                            "duration_sec": round(phrase_freqs[-1][0] - phrase_freqs[0][0], 2),
                            "maqam": best["maqam"] if best else None,
                            "confidence_pct": best["confidence_pct"] if best else None,
                            "melody_trend": trend,
                            "solfege_sequence": sol_sequence,
                        }
                        phrase_freqs = []

                await websocket.send_json({
                    "note": note_info,
                    "live_maqam": live_maqam,
                    "solfege": solfege,
                    "phrase_completed": phrase_completed,
                })
            elif "text" in msg and msg["text"] is not None:
                try:
                    cfg = json.loads(msg["text"])
                    if "sample_rate" in cfg:
                        sample_rate = int(cfg["sample_rate"])
                except Exception:
                    pass
    except WebSocketDisconnect:
        pass



# ============================================================================
# سرو فایل‌های استاتیک وب (باید آخر از همه mount شود)
# ============================================================================

app.mount("/", StaticFiles(directory=str(WEB_DIR), html=True), name="web")


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8080)
