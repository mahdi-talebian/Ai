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
import copy as _copy
import hashlib
import json
import os
import shutil
import sys
import subprocess
import threading
import time
from collections import OrderedDict
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
import melody_engine as mel            # noqa: E402
import style_learner as stl           # noqa: E402
import piano_visualizer as pv               # noqa: E402
import compare_recitations as cr            # noqa: E402
from pitch_engine import extract_pitch_chunk, freq_to_note_info  # noqa: E402
from practice_scorer import score_recitation  # noqa: E402

app = FastAPI(title="تحلیلگر تلاوت قرآن - API")


# ============================================================================
# 🧹 استارتاپ: پاک‌سازی jobهای کهنه (>۲۴ ساعت) + بازیابی گزارش‌های done
# ============================================================================

JOB_TTL_SEC = 24 * 3600


def _cleanup_old_jobs():
    try:
        now = time.time()
        removed = 0
        for d in JOBS_DIR.iterdir():
            if not d.is_dir():
                continue
            try:
                if now - d.stat().st_mtime > JOB_TTL_SEC:
                    shutil.rmtree(d, ignore_errors=True)
                    removed += 1
            except Exception:
                continue
        if removed:
            print(f"🧹 {removed} job کهنه پاک شد")
    except Exception:
        pass


def _reload_done_jobs():
    """♻️ ری‌استارت سرور، گزارش‌های تمام‌شده را از report.json برمی‌گرداند
    (فایل‌ها از قبل روی دیسک‌اند — کاربر لینک/نتایجش را از دست نمی‌دهد)."""
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
                job.status = "done"
                job.progress = 1.0
                job.result = report
                JOBS[d.name] = job
                restored += 1
            except Exception:
                continue
        if restored:
            print(f"♻️ {restored} گزارش قبلی بازیابی شد")
    except Exception:
        pass


@app.on_event("startup")
async def _on_startup():
    _cleanup_old_jobs()
    _reload_done_jobs()
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
# 🗄 کش تحلیل — کلید = (md5 فایل, denoise, top_k) → گزارش خام + فایل‌های پلات
#    فایل تکراری = پیشنهاد ملودیک و امتیازدهی لحظه‌ای (بدون تحلیل دوباره)
# ============================================================================
ANALYSIS_CACHE: "OrderedDict[tuple, dict]" = OrderedDict()
ANALYSIS_CACHE_MAX = 8


def _md5_of_file(path: str) -> str:
    h = hashlib.md5()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


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
async def start_analyze(job_id: str, denoise: bool = Form(False), top_k: int = Form(3),
                         mode: str = Form("analysis"), reference_job_id: str = Form(None),
                         voice_tonic_hz: float = Form(None), style_id: str = Form(None)):
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

            cache_key = None
            try:
                # voice_tonic_hz بخشی از کلید است (گزارشِ پالایش‌شده کش می‌شود)
                cache_key = (_md5_of_file(input_path), bool(denoise), int(top_k),
                             round(float(voice_tonic_hz), 1) if (voice_tonic_hz and voice_tonic_hz > 0) else None)
            except Exception:
                cache_key = None

            cached = ANALYSIS_CACHE.get(cache_key) if cache_key else None
            if cached is not None:
                # 🗄 تحلیل تکراری: فایل‌های پلات را به job جدید کپی کن و ادامه بده
                ANALYSIS_CACHE.move_to_end(cache_key)
                for extra in cached.get("extra_files", []):
                    try:
                        dst = job_dir / Path(extra).name
                        if Path(extra).exists():
                            shutil.copyfile(extra, dst)
                    except Exception:
                        traceback.print_exc()
                report = _copy.deepcopy(cached["report"])
                job.update("cached", 0.9)
            else:
                report = qma.analyze_recitation(
                    input_path, denoise=denoise, top_k=top_k,
                    make_plot=True, plot_dir=str(job_dir),
                    progress_callback=progress_cb,
                    tonic_prior_hz=voice_tonic_hz,
                )
                if cache_key:
                    ANALYSIS_CACHE[cache_key] = {
                        "report": _copy.deepcopy(report),
                        "extra_files": [
                            str(p) for p in sorted(job_dir.glob("*.png"))
                        ],
                    }
                    while len(ANALYSIS_CACHE) > ANALYSIS_CACHE_MAX:
                        ANALYSIS_CACHE.popitem(last=False)

            # مسیر تصویر را به مسیر قابل‌دانلود از API تبدیل کن
            if "visualization_file" in report["meta"]:
                png_name = Path(report["meta"]["visualization_file"]).name
                report["meta"]["visualization_url"] = f"/api/file/{job_id}/{png_name}"

            # مسیر فایل صوتی اصلی — برای پخش هم‌گام با نمایش waveform رنگی
            # فراز-به-فراز در وب (تب «آپلود و تحلیل»).
            report["meta"]["audio_url"] = f"/api/file/{job_id}/{Path(input_path).name}"

            # 🎙 پروفایل صوتی کاربر (برای نمایش و مشاور تونیک)
            if voice_tonic_hz and voice_tonic_hz > 0:
                report["voice_tonic_hz"] = float(voice_tonic_hz)

            # 🎓 ری‌رنک گذری نامزدها با آمار سبک‌های موجود (سیرمحور)
            try:
                _styles = stl.list_profiles()
                if _styles:
                    prior = {}
                    for _s in _styles:
                        _p = stl.load_profile(_s["id"])
                        for m, tr in (_p.get("transitions") or {}).items():
                            bucket = prior.setdefault(m, {})
                            for k, v in tr.items():
                                bucket[k] = bucket.get(k, 0.0) + float(v)
                    # نرمال‌سازی هر مقام به توزیع
                    for m, b in prior.items():
                        s = sum(b.values()) or 1.0
                        prior[m] = {k: v / s for k, v in b.items()}
                    # شواهد گذری کاربر از فرازهای خودش
                    evidence = {}
                    for ph in (report.get("phrase_breakdown") or []):
                        _m = ph.get("maqam")
                        if not _m:
                            continue
                        steps = []
                        for so in (ph.get("notes_solfege") or []):
                            try:
                                steps.append(int(so["register_offset"]) * 7 + int(so["degree_index"]))
                            except Exception:
                                continue
                        for aa, bb in zip(steps, steps[1:]):
                            key = f"{aa}->{bb}"
                            evidence[key] = evidence.get(key, 0.0) + 1.0
                    if evidence:
                        report["maqam_candidates"] = qma.rerank_candidates_with_transitions(
                            report["maqam_candidates"], prior, weight=0.3)
            except Exception:
                traceback.print_exc()

            # 🎓 شباهت سبک با قاری انتخابی
            if style_id:
                try:
                    _sp = stl.load_profile(style_id)
                    report["style_match"] = stl.style_similarity(report, _sp) if _sp else None
                except Exception:
                    traceback.print_exc()
                    report["style_match"] = None

            # --- حالت تمرین: کارت امتیاز بازی‌وار ---
            if mode == "practice":
                try:
                    report["practice_score"] = score_recitation(report)
                except Exception:
                    traceback.print_exc()
                    report["practice_score"] = None

            # --- مقایسه با فایل مرجع (استاد) در حالت تمرین ---
            if mode == "practice" and reference_job_id:
                ref_dir = JOBS_DIR / reference_job_id
                ref_files = list(ref_dir.glob("input.*")) if ref_dir.exists() else []
                if ref_files:
                    try:
                        job.update("comparing_with_reference", 0.95)
                        cmp_result, _ref, _perf, _mel = cr.compare_files(
                            str(ref_files[0]), input_path)
                        report["reference_comparison"] = {
                            "overall_similarity_pct": cmp_result.get("overall_similarity_pct"),
                            "verdict": cmp_result.get("verdict"),
                            "melodic_similarity_pct": (cmp_result.get("melodic_similarity") or {}).get("similarity_pct"),
                            "rhythm_similarity_pct": (cmp_result.get("rhythm_similarity") or {}).get("similarity_pct"),
                            "loudness_similarity_pct": (cmp_result.get("loudness_similarity") or {}).get("similarity_pct"),
                        }
                    except Exception:
                        traceback.print_exc()
                        report["reference_comparison"] = None

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

            # مسیر فایل‌های صوتی نهایی (پس از برش در صورت وجود) — برای پخش
            # هم‌گام با نمایش waveform رنگی فراز-به-فراز در تب مقایسه.
            result["meta"]["reference_audio_url"] = f"/api/file/{job_id}/{Path(ref_final).name}"
            result["meta"]["performance_audio_url"] = f"/api/file/{job_id}/{Path(perf_final).name}"
            result["reference_waveform"] = ref.get("waveform")
            result["performance_waveform"] = perf.get("waveform")
            result["reference_phrases"] = ref.get("phrases")
            result["performance_phrases"] = perf.get("phrases")

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
# 🎼 موتور پیشنهاد ملودیک
# ============================================================================

@app.get("/api/melody/suggest/{job_id}")
async def melody_suggest_job(job_id: str, maqam: str = None, tonic_hz: float = None,
                             voice_tonic_hz: float = None, style_id: str = None):
    """پیشنهاد ملودیک برای یک job تحلیل‌شده (فایل آپلودی در تب تمرین).

    maqam/tonic_hz اختیاری: اگر کاربر در سلکت مقام دستی انتخاب کرده باشد،
    از پارامتر override می‌شود؛ وگرنه مقامِ تشخیص‌داده‌شدهٔ همان job استفاده
    می‌شود.
    """
    job = JOBS.get(job_id)
    report = job.result if job else None
    if not isinstance(report, dict) or not report.get("notes"):
        return JSONResponse(
            {"error": "تحلیل این فراز در دسترس نیست — ابتدا تحلیل را اجرا کنید"},
            status_code=409)

    cand = (report.get("maqam_candidates") or [{}])[0]
    eff_maqam = maqam if (maqam and maqam in qma.MAQAMAT) else cand.get("maqam")
    eff_tonic = float(tonic_hz) if tonic_hz else cand.get("tonic_freq_hz")
    if eff_tonic and voice_tonic_hz and voice_tonic_hz > 0:
        eff_tonic = qma.refine_tonic_with_prior(float(eff_tonic), float(voice_tonic_hz))
    if not eff_maqam or not eff_tonic:
        return JSONResponse({"error": "مقام/تونیک مشخص نیست"}, status_code=400)

    style = stl.load_profile(style_id) if style_id else None
    try:
        return mel.suggest_melody(report["notes"], eff_maqam, float(eff_tonic),
                                  style=style)
    except Exception as e:
        return JSONResponse({"error": f"خطای موتور ملودیک: {e}"}, status_code=500)


@app.post("/api/melody/suggest-phrase")
async def melody_suggest_phrase(payload: dict):
    """پیشنهاد ملودیک برای فراز زندهٔ میکروفون (بدون job).

    بدنه: {"maqam": "...", "tonic_hz": 220.0,
           "notes": [{"f0_hz": 220, "dur_sec": 0.25}, ...]}
    """
    maqam = payload.get("maqam")
    tonic = payload.get("tonic_hz")
    voice_tonic = payload.get("voice_tonic_hz")
    style = stl.load_profile(payload.get("style_id")) if payload.get("style_id") else None
    chunks = payload.get("notes") or []
    if maqam not in qma.MAQAMAT:
        return JSONResponse({"error": "مقام نامعتبر است"}, status_code=400)
    if not tonic or float(tonic) <= 0:
        return JSONResponse({"error": "تونیک نامعتبر است"}, status_code=400)
    if not chunks:
        return JSONResponse({"error": "هیچ نتی در فراز نیست"}, status_code=400)

    if tonic and voice_tonic and float(voice_tonic) > 0:
        tonic = qma.refine_tonic_with_prior(float(tonic), float(voice_tonic))

    norm = [{"f0_hz": c.get("f0_hz"), "dur_sec": c.get("dur_sec") or c.get("duration") or 0.25}
            for c in chunks]
    notes = mel.notes_from_chunks(norm)
    if not notes:
        return JSONResponse({"error": "نت قابل‌استفاده‌ای یافت نشد"}, status_code=400)

    try:
        return mel.suggest_melody(notes, maqam, float(tonic), style=style)
    except Exception as e:
        return JSONResponse({"error": f"خطای موتور ملودیک: {e}"}, status_code=500)


# ============================================================================
# 🎓 سبک‌های قاری‌ها (پروفایل سبک + ساخت از فایل‌های کاربر)
# ==========================================================================

@app.get("/api/styles")
async def styles_list():
    return stl.list_profiles()


@app.post("/api/styles/build")
async def styles_build(name: str = Form(...), files: list[UploadFile] = File(...)):
    """🎓 یادگیری سبک از فایل‌های آپلودی: هر فایل تا ۹۰ ثانیهٔ اول تحلیل
    می‌شود (برای سرعت) و پروفایل سبک از مجموعهٔ همه ساخته و ذخیره می‌شود."""
    name = (name or "").strip()
    if not name:
        return JSONResponse({"error": "نام سبک لازم است"}, status_code=400)
    if not files:
        return JSONResponse({"error": "حداقل یک فایل لازم است"}, status_code=400)
    if len(files) > 8:
        return JSONResponse({"error": "حداکثر ۸ فایل در هر بار"}, status_code=400)

    tmp_dir = JOBS_DIR / "_stylebuild"
    tmp_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    per_file = []
    for uf in files[:8]:
        ext = Path(uf.filename).suffix or ".mp3"
        raw = tmp_dir / f"sb_{uuid.uuid4().hex[:8]}{ext}"
        with open(raw, "wb") as f:
            shutil.copyfileobj(uf.file, f)
        # برش به ۹۰ ثانیهٔ اول برای سرعت تحلیل
        trimmed = raw.with_suffix(".trim.wav")
        try:
            subprocess.run(
                ["ffmpeg", "-y", "-loglevel", "error", "-i", str(raw),
                 "-t", "90", "-ac", "1", str(trimmed)],
                check=True, timeout=120)
            use = str(trimmed)
        except Exception:
            use = str(raw)
        try:
            rep = qma.analyze_recitation(use, denoise=False, top_k=2, make_plot=False)
            reports.append(rep)
            per_file.append({"file": uf.filename,
                             "notes": rep.get("basic", {}).get("num_notes_detected")})
        except Exception as e:
            per_file.append({"file": uf.filename, "error": str(e)})
        finally:
            try:
                raw.unlink()
            except Exception:
                pass
            try:
                trimmed.unlink()
            except Exception:
                pass

    if not reports:
        return JSONResponse({"error": "هیچ فایلی تحلیل نشد", "detail": per_file},
                            status_code=400)
    try:
        profile = stl.build_profile(reports, name,
                                    style_id="style_" + uuid.uuid4().hex[:8],
                                    source="upload")
    except Exception as e:
        return JSONResponse({"error": f"ساخت سبک ناموفق: {e}"}, status_code=500)
    stl.save_profile(profile)
    return {"profile": {"id": profile["id"], "name": profile["name"],
                        "n_files": profile["n_files"], "n_notes": profile["n_notes"],
                        "top_maqams": list(profile["maqam_usage"])[:3]},
            "per_file": per_file}


@app.delete("/api/styles/{style_id}")
async def styles_delete(style_id: str):
    path = Path(stl.STYLES_DIR_DEFAULT) / f"{style_id}.json"
    if path.exists():
        path.unlink()
        return {"ok": True}
    return JSONResponse({"error": "سبک پیدا نشد"}, status_code=404)


# ============================================================================
# دریافت وضعیت job (fallback در صورت عدم استفاده از وب‌سوکت)
# ============================================================================

@app.get("/api/maqamat")
async def api_maqamat():
    """🎼 جدول مرجع ۸ مقام «بر پایهٔ دو» — درجات، نام نت‌ها و غماز.
    برای تب آموزش سولفژ و شفافیت منطق تشخیص."""
    out = []
    for name, info in qma.MAQAMAT.items():
        scale = info["scale_ascending"]
        chain = info.get("jins_chain") or []
        ghammaz = None
        if len(chain) >= 2:
            try:
                idx = min(max(int(chain[1]["on_degree"]) - 1, 0), len(scale) - 1)
                ghammaz = scale[idx]
            except Exception:
                pass
        out.append({
            "name": name,
            "scale_cents": scale,
            "degrees_fa": info.get("degrees_fa") or [],
            "mood": info.get("mood"),
            "family": info.get("family"),
            "ghammaz_cents": ghammaz,
        })
    return out


@app.post("/api/solfege/score")
async def api_solfege_score(file: UploadFile = File(...), tonic_hz: float = Form(...),
                            target: str = Form(...)):
    """📂 امتیازدهی سولفژ بدون میکروفون: فایل ضبط‌شدهٔ کاربر + توالی هدف
    (برای محیط‌های iframe که getUserMedia در آن‌ها بسته است)."""
    import json as _json, tempfile, os
    try:
        targets = _json.loads(target)
        assert isinstance(targets, list) and targets
    except Exception:
        return {"error": "توالی هدف سولفژ نامعتبر است"}
    suffix = os.path.splitext(file.filename or "")[1] or ".wav"
    with tempfile.NamedTemporaryFile(suffix=suffix, delete=False) as tf:
        tf.write(await file.read())
        tmp_path = tf.name
    try:
        rep = qma.analyze_recitation(tmp_path, make_plot=False)
    except Exception as e:
        return {"error": f"تحلیل فایل ناموفق بود: {e}"}
    finally:
        try:
            os.unlink(tmp_path)
        except Exception:
            pass
    try:
        tonic = float(tonic_hz)
        result = qma.solfege_score_notes(rep.get("notes") or [],
                                         [t.get("cents") for t in targets], tonic)
    except Exception as e:
        return {"error": f"امتیازدهی ناموفق بود: {e}"}
    if not result:
        return {"error": "نتِ خوانده‌شده‌ای در فایل پیدا نشد"}
    return result


@app.get("/api/health")
async def health():
    return {"ok": True, "jobs": len(JOBS), "styles": len(stl.list_profiles())}


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
        ".mp3": "audio/mpeg",
        ".ogg": "audio/ogg",
        ".oga": "audio/ogg",
        ".m4a": "audio/mp4",
        ".webm": "audio/webm",
        ".flac": "audio/flac",
        ".aac": "audio/aac",
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

    # 🎭 پایداری هینت: پیام فقط پس از انحرافِ تثبیت‌شده (بدون چشمک‌زدن)
    hint_state = {"dir": None, "n": 0, "ok_announced": False}
    maqam_view_state = {"maqam": None, "challenger": None, "n": 0}  # 🧷 ضدچشمک مقام

    # بافر رونده برای مقام لحظه‌ای (چون ثانیهٔ اخیر)
    LIVE_WINDOW_SEC = 6.0
    recent_freqs = []   # [(t, f0), ...]

    # بافر فراز جاری (از آخرین پایان فراز تا الان)
    phrase_freqs = []    # [(t, f0), ...] از ابتدای فراز جاری
    silence_run_sec = 0.0
    t_cursor = 0.0
    PHRASE_SILENCE_GATE_SEC = 0.4  # این‌مقدار سکوت پیاپی = پایان فراز

    # --- مربی زنده: مقام انتخابی کاربر (از پیام متنی کلاینت) ---
    coach_maqam = None      # نام مقام انتخابی یا None = تشخیص زنده
    coach_tonic_hz = None   # تونیک دستی (اختیاری — override مطلق)
    voice_tonic_hz = None   # 🎙 پروفایل صوتی کاربر (پالایش تونیک تشخیصی)

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
                    live_finalis = recent_freqs[-1][1] if recent_freqs[-1][1] > 0 else None
                    live_finalis_cents = (1200.0 * np.log2(live_finalis / 440.0)) % 1200.0 if live_finalis else None
                    candidates = qma.detect_tonic_and_maqam(hist, top_k=3, finalis_cents=live_finalis_cents,
                                                            notes=pseudo_notes)
                    if candidates:
                        best = candidates[0]
                        # 🧷 ضدچشمک: تغییر مقام نمایشی فقط با ثبات پیاپی یا اختلاف واضح
                        margin = (best.get("confidence_pct") or 0) - (
                            (candidates[1].get("confidence_pct") or 0) if len(candidates) > 1 else 0)
                        shown_name = qma.update_with_hysteresis(maqam_view_state, best["maqam"], margin)
                        shown = next((c for c in candidates if c["maqam"] == shown_name), best)
                        live_maqam = {
                            "maqam": shown["maqam"],
                            "confidence_pct": shown["confidence_pct"],
                            "tonic_freq_hz": shown["tonic_freq_hz"],
                            "setfit_pct": shown.get("setfit_pct"),
                        }
                        if f0:
                            tonic_hz = shown["tonic_freq_hz"]
                            maqam_cents = qma.MAQAMAT[shown["maqam"]]["cents"]
                            octave_shift = round(np.log2(f0 / tonic_hz)) if tonic_hz > 0 else 0
                            tonic_adj = tonic_hz * (2 ** octave_shift)
                            solfege = qma.note_to_solfege(f0, tonic_adj, maqam_cents,
                                                          maqam_name=best["maqam"])

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
                        ph_finalis = phrase_freqs[-1][1] if phrase_freqs[-1][1] > 0 else None
                        ph_finalis_cents = (1200.0 * np.log2(ph_finalis / 440.0)) % 1200.0 if ph_finalis else None
                        candidates = qma.detect_tonic_and_maqam(hist, top_k=1, finalis_cents=ph_finalis_cents,
                                            notes=[{"f0_hz": f, "duration": d} for (_, f, d) in phrase_freqs if f and f > 0])
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
                                sol = qma.note_to_solfege(f, tonic_adj, maqam_cents,
                                                          maqam_name=best["maqam"])
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
                            # نت‌های خام فراز برای موتور پیشنهاد ملودیک (🎼)
                            "notes": [
                                {"f0_hz": round(float(f), 2), "dur_sec": round(float(d), 3)}
                                for (_, f, d) in phrase_freqs if f and f > 0
                            ],
                            "tonic_hz": round(float(tonic_adj), 2) if best else None,
                        }
                        phrase_freqs = []

                # --- مربی زنده: راهنمای بالاتر/پایین‌تر نسبت به نزدیک‌ترین درجه ---
                coach = None
                c_maqam_name = coach_maqam or (live_maqam or {}).get("maqam")
                if c_maqam_name and c_maqam_name in qma.MAQAMAT:
                    if coach_tonic_hz:
                        c_tonic = coach_tonic_hz
                    elif live_maqam and live_maqam.get("tonic_freq_hz"):
                        c_tonic = live_maqam["tonic_freq_hz"]
                        if f0 and c_tonic > 0:
                            oct_shift = round(np.log2(f0 / c_tonic))
                            c_tonic *= (2 ** oct_shift)
                        # 🎙 پالایش با پروفایل صوتی — آستانهٔ بازتر چون نقش
                        # مربی «بازگرداندن به تونیک شخصی» است (تا نیم‌پرده)
                        if voice_tonic_hz:
                            c_tonic = qma.refine_tonic_with_prior(c_tonic, voice_tonic_hz,
                                                                  tolerance_cents=100.0)
                    else:
                        c_tonic = None
                    if f0 and c_tonic:
                        c_cents = 1200.0 * np.log2(f0 / c_tonic)
                        scale = qma.maqam_degrees(qma.MAQAMAT[c_maqam_name]["scale_ascending"])
                        diffs = [min(abs((c_cents % 1200) - dc), 1200 - abs((c_cents % 1200) - dc))
                                 for dc in scale]
                        nearest_c = scale[int(np.argmin(diffs))]
                        off = c_cents - nearest_c
                        if off > 600:
                            off -= 1200
                        elif off < -600:
                            off += 1200
                        t_fa, t_en, _ = qma.absolute_degree_name(nearest_c,
                                                qma.MAQAMAT[c_maqam_name]["tonic_ladder_cents"])
                        if abs(off) <= 30:
                            direction = "ok"
                        elif off < 0:
                            direction = "up"
                        else:
                            direction = "down"
                        # هینت فقط وقتی جهت ≥۴ قطعه پایدار ماند (~۱٫۳ ثانیه)
                        if direction == hint_state["dir"]:
                            hint_state["n"] += 1
                        else:
                            hint_state["dir"] = direction
                            hint_state["n"] = 1
                            hint_state["ok_announced"] = False
                        if direction == "ok":
                            hint = "درست است — نگه دار ✓" if not hint_state["ok_announced"] else ""
                            if hint:
                                hint_state["ok_announced"] = True
                        elif hint_state["n"] >= 4:
                            hint = "کمی بالاتر ↑" if direction == "up" else "کمی پایین‌تر ↓"
                        else:
                            hint = ""
                        coach = {
                            "active": True,
                            "maqam": c_maqam_name,
                            "target_degree_fa": t_fa,
                            "target_cents": nearest_c,
                            "cents_off": round(float(off), 1),
                            "direction": direction,
                            "hint_fa": hint,
                        }

                await websocket.send_json({
                    "note": note_info,
                    "live_maqam": live_maqam,
                    "solfege": solfege,
                    "phrase_completed": phrase_completed,
                    "coach": coach,
                })
            elif "text" in msg and msg["text"] is not None:
                try:
                    cfg = json.loads(msg["text"])
                    if "sample_rate" in cfg:
                        sample_rate = int(cfg["sample_rate"])
                    if "maqam" in cfg:
                        coach_maqam = cfg["maqam"] if cfg["maqam"] in qma.MAQAMAT else None
                    if "tonic_hz" in cfg:
                        v = float(cfg["tonic_hz"])
                        coach_tonic_hz = v if v > 0 else None
                    if "voice_tonic_hz" in cfg:
                        v = float(cfg["voice_tonic_hz"])
                        voice_tonic_hz = v if v > 0 else None
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
