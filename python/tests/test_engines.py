#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🧪 تست‌های موتورها (بدون وابستگی به سرویس)
اجرا:  python python/tests/test_engines.py
"""
import os
import sys

import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(HERE))

import melody_engine as mel          # noqa: E402
import practice_scorer as scorer     # noqa: E402
import style_learner as stl          # noqa: E402
import quran_maqam_analyzer as qma   # noqa: E402
from pitch_engine import dtw_align_cost_matrix  # noqa: E402

FAILED = []


def check(name, cond, detail=""):
    mark = "✓" if cond else "✗"
    print(f"  {mark} {name}" + (f" — {detail}" if detail and not cond else ""))
    if not cond:
        FAILED.append(name)


TONIC = 220.0


def _notes(cents_seq, dur=0.5):
    return [{"start": i * dur, "duration": dur,
             "f0_hz": TONIC * (2 ** (c / 1200.0))} for i, c in enumerate(cents_seq)]


# ─────────────────────────────────────────────
print("== melody_engine ==")
base = _notes([0, 150, 300, 500, 300, 150])
r1 = mel.suggest_melody(base, "بیاتی (Bayati)", TONIC, seed=7)
r2 = mel.suggest_melody(base, "بیاتی (Bayati)", TONIC, seed=7)
seq1 = [n["cents"] for c in r1["continuations"] for n in c["notes"]]
seq2 = [n["cents"] for c in r2["continuations"] for n in c["notes"]]
check("قطعیت seed", seq1 == seq2)
check("دو واریانت", len(r1["continuations"]) == 2)
check("پرسش/پاسخ (قرار/غماز — غماز بیاتی روی نوا=۷۰۰)",
      r1["continuations"][0]["notes"][-1]["cents"] % 1200 == 0
      and r1["continuations"][1]["notes"][-1]["cents"] % 1200 == 700)
# ریتم کاربر: کشش‌های اول = الگوی IOI خود کاربر
pat_notes = _notes([0, 150, 300, 500, 300], dur=0.4)
pat_notes[1]["duration"] = 0.8
pat_notes[3]["duration"] = 0.7
r3 = mel.suggest_melody(pat_notes, "بیاتی (Bayati)", TONIC, seed=1)
durs = [n["dur"] for n in r3["continuations"][0]["notes"]]
check("ریتم از خود کاربر", abs(durs[0] - 0.4) < 0.01 and abs(durs[1] - 0.8) < 0.01,
      str(durs[:3]))

# ─────────────────────────────────────────────
print("== practice_scorer ==")
def sol(start, dur, cents, dev, idx, fa):
    return {"start": start, "end": start + dur, "duration": dur,
            "f0_hz": TONIC * (2 ** (cents / 1200)),
            "absolute_degree_fa": fa, "degree_index": idx,
            "cents_off_degree": dev, "in_maqam": abs(dev) <= 40}

rep = {
    "phrase_breakdown": [{
        "resting_degree": {"degree_index": 1}, "tonic_freq_hz": TONIC,
        "in_maqam_pct": 95.0, "start": 0.0, "end": 2.7,
        "notes_solfege": [
            sol(0, .5, 0, 5, 1, "دوگاه"), sol(.5, .5, 150, 10, 2, "سیکاه"),
            sol(1, .8, 500, 8, 4, "نوا"), sol(1.8, .9, 0, 4, 1, "دوگاه")],
    }],
    "notes": [
        {"start": 0, "duration": .5, "f0_hz": TONIC, "stability_cents": 12,
         "vibrato": {"rate_hz": 6.0, "extent_cents": 42}},
        {"start": .5, "duration": .5, "f0_hz": TONIC * (2 ** (150/1200)),
         "stability_cents": 15, "vibrato": {"rate_hz": 5.5, "extent_cents": 38}},
        {"start": 1, "duration": .8, "f0_hz": TONIC * (2 ** (500/1200)),
         "stability_cents": 14, "vibrato": {"rate_hz": 6.2, "extent_cents": 45}},
        {"start": 1.8, "duration": .9, "f0_hz": TONIC * (2 ** (15/1200)),
         "stability_cents": 10},
    ],
    "maqam_candidates": [{"tonic_freq_hz": TONIC, "maqam": "بیاتی (Bayati)"}],
}
ps = scorer.score_recitation(rep)
check("زیرامتیاز ویبرو", ps["subscores"]["vibrato"] is not None
      and ps["subscores"]["vibrato"] > 60, str(ps["subscores"]))
check("فیدبک فرازی", len(ps.get("phrase_feedback") or []) == 1
      and ps["phrase_feedback"][0]["start"] is not None)
check("ثبات تونیک تک‌فرازی", ps["subscores"]["tonic_steadiness"] is not None)

# ─────────────────────────────────────────────
print("== style_learner ==")
sm = stl.smooth_counter({"7->8": 10})
check("هموارسازی", sm["7->8"] == 10 and sm["6->8"] > 0)
# شباهت: صفرِ سخت حذف شده — گذر همسایه اعتبار جزئی دارد
prof = {"id": "t", "name": "تست", "maqam_usage": {"بیاتی (Bayati)": 10.0},
        "transitions": {"بیاتی (Bayati)": {"7->8": 20, "8->9": 10}},
        "end_steps": {"بیاتی (Bayati)": {}},
        "intervals": {"بیاتی (Bayati)": {"1": 20}},
        "dur_stats": {"p25": 0.2, "p50": 0.3, "p75": 0.5}}
urep = {"notes": base, "phrase_breakdown": [{
    "maqam": "بیاتی (Bayati)", "duration": 3.0,
    "notes_solfege": [
        {"degree_index": 2, "register_offset": 1, "cents_off_degree": 5, "duration": .3},
        {"degree_index": 3, "register_offset": 1, "cents_off_degree": 6, "duration": .3},
        {"degree_index": 4, "register_offset": 1, "cents_off_degree": 4, "duration": .3},
        {"degree_index": 3, "register_offset": 1, "cents_off_degree": 5, "duration": .3},
    ]}],
    "maqam_candidates": [{"maqam": "بیاتی (Bayati)", "tonic_freq_hz": TONIC}]}
s = stl.style_similarity(urep, prof)
check("شباهت معتبر", s is not None and 0 < s["overall_pct"] <= 100, str(s))

# ─────────────────────────────────────────────
print("== quran_maqam_analyzer ==")
check("refine پالایش نزدیک",
      abs(qma.refine_tonic_with_prior(155.25, 156.9) - 156.9) < 0.01)
check("refine اکتاو",
      abs(qma.refine_tonic_with_prior(155.25, 310.5) - 155.25) < 0.01)
check("refine دور (کلید متفاوت)",
      abs(qma.refine_tonic_with_prior(155.25, 233.0) - 155.25) < 0.01)
cands = [{"maqam": "بیاتی (Bayati)", "score": 0.5, "_transition_evidence": {"7->8": 1.0}},
         {"maqam": "رست (Rast)", "score": 0.55, "_transition_evidence": {"7->8": 1.0}}]
rr = qma.rerank_candidates_with_transitions(
    cands, {"بیاتی (Bayati)": {"7->8": 0.9}, "رست (Rast)": {"7->8": 0.1}})
check("ری‌رنک گذری", rr[0]["maqam"] == "بیاتی (Bayati)", str([c["maqam"] for c in rr]))

# ─────────────────────────────────────────────
print("== pitch_engine: DTW band ==")
rng = np.random.RandomState(0)
cm = rng.rand(10, 14)
a = dtw_align_cost_matrix(cm)
b = dtw_align_cost_matrix(cm, dtw_band=0.25)
check("نوار ساکو-چیبا اجرا", a[1] and b[1] and len(a[1]) > 0 and len(b[1]) > 0)

# ─────────────────────────────────────────────
print("== جدول ۸ مقام بر پایهٔ دو ==")
names = list(qma.MAQAMAT.keys())
check("۸ مقام مرجع", len(names) == 8, f"{len(names)}")
for n, info in qma.MAQAMAT.items():
    assert info.get("degrees_fa") and len(info["degrees_fa"]) >= 7, n
check("نام نت‌های فارسی برای همه", True)
deg_saba = qma.maqam_degrees(qma.MAQAMAT["صبا (Saba)"]["scale_ascending"])
check("صبا: بستهٔ نزولی (آخر ۱۱۰۰)", abs(deg_saba[-1] - 1100) < 1 and len(deg_saba) == 8, str(deg_saba))
check("رست: سی نیم‌بمل ۱۰۵۰", qma.MAQAMAT["رست (Rast)"]["scale_ascending"][6] == 1050)
check("بیاتی: میِ نیم‌بمل ۳۵۰", qma.MAQAMAT["بیاتی (Bayati)"]["scale_ascending"][2] == 350)
check("سه‌گاه افزوده شد", "سه‌گاه (Segah)" in qma.MAQAMAT)
check("صبا زمزم افزوده شد", "صبا زمزم (Saba Zamzam)" in qma.MAQAMAT)

print("== سنجش کلی (setfit) مستقل از ترتیب ==")
rng2 = np.random.RandomState(42)
tonic_hz = 220.0
rast_degs = [0, 200, 350, 500, 700, 900, 1050]
perm = list(rng2.permutation(7))
seq_c = [rast_degs[i] for i in perm] + [700 + 1200, 0, 900, 0]
notes_r = [{"f0_hz": tonic_hz * 2 ** (c / 1200.0), "duration": 0.4} for c in seq_c]
sf = qma.holistic_setfit(notes_r, "رست (Rast)", tonic_hz)
check("پوشش کامل راست", sf and sf["coverage_pct"] >= 99, str(sf))
noise_notes = notes_r + [{"f0_hz": tonic_hz * 2 ** (650 / 1200.0), "duration": 1.2}]
sf2 = qma.holistic_setfit(noise_notes, "رست (Rast)", tonic_hz)
check("نت بیرون‌گام پوشش را کم می‌کند", sf2["coverage_pct"] < sf["coverage_pct"] - 3,
      f"{sf['coverage_pct']} → {sf2['coverage_pct']}")

print("== تشخیص اجمالی ملودی پرشی (جملهٔ به‌هم‌ریخته) ==")
def perm_melody(degs, seed):
    r = np.random.RandomState(seed)
    perm = list(r.permutation(len(degs)))
    seq = [degs[i] for i in perm] + [0, degs[4] + 1200, 0, degs[-1], 0]
    jit = r.normal(0, 10, len(seq))
    return [{"f0_hz": tonic_hz * 2 ** ((c + j) / 1200.0), "duration": 0.4}
            for c, j in zip(seq, jit)]
def top_maqam(degs, seed):
    notes = perm_melody(degs, seed)
    hist = qma.build_qtet_histogram(notes)
    cands = qma.detect_tonic_and_maqam(hist, top_k=3, finalis_cents=0.0, notes=notes)
    return cands
wins = 0
for s in range(8):
    if top_maqam(rast_degs, s)[0]["maqam"] == "رست (Rast)":
        wins += 1
check("راست پرشی → راست", wins >= 7, f"{wins}/8")
c_r = top_maqam(rast_degs, 3)
check("setfit در نامزد اول هست", c_r[0].get("setfit_pct") is not None)
seg_degs = [0, 150, 350, 500, 700, 850, 1050]
w_seg = sum(1 for s in range(8) if top_maqam(seg_degs, s)[0]["maqam"] == "سه‌گاه (Segah)")
check("سه‌گاه پرشی → سه‌گاه", w_seg >= 6, f"{w_seg}/8")
w_aj = sum(1 for s in range(8) if top_maqam([0, 200, 400, 500, 700, 900, 1100], s)[0]["maqam"] == "عجم (Ajam)")
check("عجم پرشی → عجم", w_aj >= 6, f"{w_aj}/8")

print("== ضدچشمک مقام زنده ==")
st = {}
check("اولین مقام فوراً", qma.update_with_hysteresis(st, "بیاتی (Bayati)", 30) == "بیاتی (Bayati)")
for _ in range(3):
    r = qma.update_with_hysteresis(st, "حجاز (Hijaz)", 5)
check("چالشگر کم‌اختلاف عوضش نمی‌کند", r == "بیاتی (Bayati)", str(r))
r = qma.update_with_hysteresis(st, "حجاز (Hijaz)", 5)
check("پس از ۴ پنجرهٔ پیاپی عوض می‌شود", r == "حجاز (Hijaz)", str(r))
st2 = {}
qma.update_with_hysteresis(st2, "بیاتی (Bayati)", 30)
r2 = qma.update_with_hysteresis(st2, "حجاز (Hijaz)", 45)
check("اختلاف واضح فوری عوض می‌کند", r2 == "حجاز (Hijaz)", str(r2))

# ─────────────────────────────────────────────
print("== امتیازدهی سولفژ با فایل (DTW) ==")
tgt = [0, 150, 350, 500, 700, 850, 1000]
exact = [{"f0_hz": TONIC * 2 ** (c / 1200.0), "duration": 0.5} for c in tgt]
r_ok = qma.solfege_score_notes(exact, tgt, TONIC)
check("خوانندهٔ دقیق → ۱۰۰", r_ok and r_ok["overall_pct"] >= 95, str(r_ok and r_ok["overall_pct"]))
flat = [{"f0_hz": TONIC * 2 ** ((c - 80) / 1200.0), "duration": 0.5} for c in tgt]
r_flat = qma.solfege_score_notes(flat, tgt, TONIC)
check("۸۰ سنت زیر → همهٔ انحراف‌ها −۸۰", all(p["offset"] == -80 for p in r_flat["per"]),
      str([p["offset"] for p in r_flat["per"]]))
check("فایل بی‌صدا → None", qma.solfege_score_notes([], tgt, TONIC) is None)

# ─────────────────────────────────────────────
print()
if FAILED:
    print(f"✗ {len(FAILED)} تست شکست: {FAILED}")
    sys.exit(1)
print("✅ همهٔ تست‌های موتورها سبز است")
