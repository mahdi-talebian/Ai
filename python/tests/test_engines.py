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
check("پرسش/پاسخ (قرار/غماز)",
      r1["continuations"][0]["notes"][-1]["cents"] % 1200 == 0
      and r1["continuations"][1]["notes"][-1]["cents"] % 1200 == 500)
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

print()
if FAILED:
    print(f"✗ {len(FAILED)} تست شکست: {FAILED}")
    sys.exit(1)
print("✅ همهٔ تست‌های موتورها سبز است")
