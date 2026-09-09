#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🎓 موتور یادگیری سبک تلاوت (Style Learner)
===========================================
از گزارش‌های تحلیل چند تلاوتِ یک قاری، «پروفایل سبک» می‌سازد:

  • ماتریس گذر درجات  — پس از هر درجهٔ مقام، قاری بیشتر به کدام درجه می‌رود؟
    (امضای ملودیک قاری؛ بر حسب «پله» = اکتاو×۷ + اندیس درجه، مثل موتور ملودیک)
  • توزیع شروع فرازها و قرارها (درجهٔ پایان)
  • آمار کشش نت‌ها (چارک‌ها) و طول فرازها
  • آمار ویبرو (نرخ و دامنه + پوشش)
  • میزان استفاده از هر مقام (وزن مدت)

و برای سنجش شباهت تلاوت کاربر با سبک قاری، «امتیاز شباهت سبک» می‌دهد
(کاواینس توزیع‌ها: گذر، قرار، ریتم، مقام) همراه با نزدیک‌ترین جفت‌گذرهای
مشترک و متفاوت — برای نمایش در کارت سبک.
"""

import json
import os
from collections import Counter, defaultdict

import numpy as np

STYLES_DIR_DEFAULT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "styles")


# ============================================================================
# استخراج ویژگی‌های یک گزارش
# ============================================================================

def _step_of(sol):
    """پلهٔ گام از رکورد سولفژ: اکتاو×۷ + اندیس درجه (۱..۷)."""
    try:
        return int(sol["register_offset"]) * 7 + int(sol["degree_index"])
    except Exception:
        return None


def extract_features(report):
    """از گزارش analyze_recitation بردارهای خام سبک را استخراج می‌کند."""
    phrases = report.get("phrase_breakdown") or []
    notes = report.get("notes") or []

    dur_by_maqam = defaultdict(float)
    transitions = defaultdict(Counter)      # maqam -> Counter("a->b")
    start_steps = defaultdict(Counter)      # maqam -> Counter(step)
    end_steps = defaultdict(Counter)        # maqam -> Counter(step)
    intervals = defaultdict(Counter)        # maqam -> Counter(delta)
    phrase_lens = defaultdict(list)         # maqam -> [n_notes]
    all_durs = []
    vib_rates, vib_extents = [], []
    n_vib_notes = 0

    for ph in phrases:
        maqam = ph.get("maqam") or "نامشخص"
        sols = [s for s in (ph.get("notes_solfege") or []) if s.get("degree_index")]
        steps = [s for s in (_step_of(x) for x in sols) if s is not None]
        if not steps:
            continue
        dur_by_maqam[maqam] += float(ph.get("duration") or 0.0)
        phrase_lens[maqam].append(len(steps))
        start_steps[maqam][steps[0]] += 1
        end_steps[maqam][steps[-1]] += 1
        for a, b in zip(steps, steps[1:]):
            transitions[maqam][f"{a}->{b}"] += 1
            intervals[maqam][b - a] += 1

    for n in notes:
        d = float(n.get("duration") or 0.0)
        if d > 0:
            all_durs.append(d)
        if n.get("vibrato"):
            n_vib_notes += 1
            v = n["vibrato"]
            if v.get("rate_hz"):
                vib_rates.append(float(v["rate_hz"]))
            if v.get("extent_cents"):
                vib_extents.append(float(v["extent_cents"]))

    tonics = []
    cand = (report.get("maqam_candidates") or [{}])[0].get("tonic_freq_hz")
    if cand:
        tonics.append(float(cand))

    return {
        "dur_by_maqam": dict(dur_by_maqam),
        "transitions": {k: dict(v) for k, v in transitions.items()},
        "start_steps": {k: dict(v) for k, v in start_steps.items()},
        "end_steps": {k: dict(v) for k, v in end_steps.items()},
        "intervals": {k: dict(v) for k, v in intervals.items()},
        "phrase_lens": {k: v for k, v in phrase_lens.items()},
        "durations": all_durs,
        "vib": {"n": n_vib_notes, "rates": vib_rates, "extents": vib_extents},
        "tonics": tonics,
        "n_notes": len(notes),
    }


def _pct(vals, q):
    return round(float(np.percentile(vals, q)), 3) if vals else None


# ============================================================================
# ساخت پروفایل سبک از چند گزارش
# ============================================================================

def build_profile(reports, name, style_id=None, source="files"):
    """reports = لیست گزارش‌های analyze_recitation → پروفایل سبک JSON-سازگار."""
    feats = [extract_features(r) for r in reports if r]
    feats = [f for f in feats if f["n_notes"] > 0]
    if not feats:
        raise ValueError("هیچ گزارش قابل‌استفاده‌ای برای ساخت سبک نبود")

    # --- تجمیع ---
    maqam_usage = defaultdict(float)
    transitions = defaultdict(Counter)
    start_steps = defaultdict(Counter)
    end_steps = defaultdict(Counter)
    intervals = defaultdict(Counter)
    phrase_lens = defaultdict(list)
    all_durs = []
    vib_rates, vib_extents = [], []
    n_vib_total = 0
    n_notes_total = 0
    tonics = []

    for f in feats:
        n_notes_total += f["n_notes"]
        tonics.extend(f["tonics"])
        all_durs.extend(f["durations"])
        n_vib_total += f["vib"]["n"]
        vib_rates.extend(f["vib"]["rates"])
        vib_extents.extend(f["vib"]["extents"])
        for m, w in f["dur_by_maqam"].items():
            maqam_usage[m] += w
        for m, c in f["transitions"].items():
            transitions[m].update(c)
        for m, c in f["start_steps"].items():
            start_steps[m].update(c)
        for m, c in f["end_steps"].items():
            end_steps[m].update(c)
        for m, c in f["intervals"].items():
            intervals[m].update(c)
        for m, v in f["phrase_lens"].items():
            phrase_lens[m].extend(v)

    profile = {
        "schema": "style-profile-v1",
        "id": style_id or ("style_" + str(abs(hash(name)) % 10 ** 8)),
        "name": name,
        "source": source,
        "n_files": len(feats),
        "n_notes": n_notes_total,
        "n_transitions": int(sum(sum(c.values()) for c in transitions.values())),
        "tonic_median_hz": round(float(np.median(tonics)), 2) if tonics else None,
        "maqam_usage": {m: round(w, 2) for m, w in
                        sorted(maqam_usage.items(), key=lambda kv: -kv[1])},
        "transitions": {m: dict(c) for m, c in transitions.items()},
        "start_steps": {m: dict(c) for m, c in start_steps.items()},
        "end_steps": {m: dict(c) for m, c in end_steps.items()},
        "intervals": {m: dict(c) for m, c in intervals.items()},
        "dur_stats": {"p25": _pct(all_durs, 25), "p50": _pct(all_durs, 50),
                      "p75": _pct(all_durs, 75)},
        "phrase_len_median": _pct([x for v in phrase_lens.values() for x in v], 50),
        "vibrato": {
            "coverage_pct": round(100.0 * n_vib_total / max(n_notes_total, 1), 1),
            "rate_median_hz": _pct(vib_rates, 50),
            "extent_median_cents": _pct(vib_extents, 50),
        },
    }
    return profile


def save_profile(profile, styles_dir=STYLES_DIR_DEFAULT):
    os.makedirs(styles_dir, exist_ok=True)
    path = os.path.join(styles_dir, f"{profile['id']}.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(profile, f, ensure_ascii=False, indent=1)
    return path


def load_profile(style_id, styles_dir=STYLES_DIR_DEFAULT):
    path = os.path.join(styles_dir, f"{style_id}.json")
    if not os.path.exists(path):
        return None
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def list_profiles(styles_dir=STYLES_DIR_DEFAULT):
    out = []
    if not os.path.isdir(styles_dir):
        return out
    for fn in sorted(os.listdir(styles_dir)):
        if not fn.endswith(".json"):
            continue
        try:
            with open(os.path.join(styles_dir, fn), encoding="utf-8") as f:
                p = json.load(f)
            out.append({
                "id": p.get("id"), "name": p.get("name"), "source": p.get("source"),
                "n_files": p.get("n_files"), "n_notes": p.get("n_notes"),
                "top_maqams": list((p.get("maqam_usage") or {}).keys())[:3],
                "vibrato_pct": (p.get("vibrato") or {}).get("coverage_pct"),
            })
        except Exception:
            continue
    return out


# ============================================================================
# امتیاز شباهت سبک
# ============================================================================

def _cosine(c1: Counter, c2: Counter) -> float:
    if not c1 or not c2:
        return 0.0
    keys = set(c1) | set(c2)
    dot = sum(c1.get(k, 0) * c2.get(k, 0) for k in keys)
    n1 = np.sqrt(sum(v * v for v in c1.values()))
    n2 = np.sqrt(sum(v * v for v in c2.values()))
    if n1 == 0 or n2 == 0:
        return 0.0
    return float(dot / (n1 * n2))


def _norm_counter(d):
    return Counter(d or {})


def style_similarity(report, profile):
    """
    شباهت تلاوت کاربر با پروفایل سبک — خروجی برای کارت «شباهت سبک».
    مقایسه روی مقامِ غالبِ پروفایل (یا غالبِ کاربر اگر آن مقام در پروفایل نبود).
    """
    feats = extract_features(report)
    if not profile or feats["n_notes"] < 4:
        return None

    user_maqams = feats["dur_by_maqam"] or {}
    prof_maqams = profile.get("maqam_usage") or {}

    # مقام مبنا: غالب مشترک؛ وگرنه غالب پروفایل
    common = [m for m, _ in sorted(user_maqams.items(), key=lambda kv: -kv[1])
              if m in prof_maqams]
    maqam = common[0] if common else (
        prof_maqams and next(iter(prof_maqams))) or (
        next(iter(user_maqams), None))
    if not maqam:
        return None

    u_tr = _norm_counter(feats["transitions"].get(maqam))
    p_tr = _norm_counter((profile.get("transitions") or {}).get(maqam))
    trans_sim = _cosine(u_tr, p_tr)
    n_user_tr = int(sum(u_tr.values()))

    # اجزای کم‌داده → None (بازوزن‌دهی بعداً) — یک فراز تنها آمار قرار/فاصله
    # معتبر نمی‌دهد
    u_end = _norm_counter(feats["end_steps"].get(maqam))
    p_end = _norm_counter((profile.get("end_steps") or {}).get(maqam))
    n_user_phr = int(sum(u_end.values()))
    rest_sim = _cosine(u_end, p_end) if n_user_phr >= 2 else None

    u_iv = _norm_counter(feats["intervals"].get(maqam))
    p_iv = _norm_counter((profile.get("intervals") or {}).get(maqam))
    iv_sim = _cosine(u_iv, p_iv) if n_user_tr >= 6 else None

    # ریتم: نزدیکی میانهٔ کشش + نسبت چابکی (p75/p50)
    u_d = feats["durations"]
    rhythm_sim = None
    ps = profile.get("dur_stats") or {}
    if u_d and ps.get("p50"):
        u50 = float(np.median(u_d))
        u75 = _pct(u_d, 75) or u50
        p50, p75 = float(ps["p50"]), float(ps.get("p75") or ps["p50"])
        med_closeness = np.exp(-abs(np.log2(max(u50, 1e-6) / max(p50, 1e-6))) / np.log(2.0))
        u_ag = u75 / max(u50, 1e-6)
        p_ag = p75 / max(p50, 1e-6)
        ag_closeness = np.exp(-abs(np.log2(max(u_ag, 1e-6) / max(p_ag, 1e-6))) / np.log(2.0))
        rhythm_sim = float(0.6 * med_closeness + 0.4 * ag_closeness)

    maqam_sim = _cosine(_norm_counter(user_maqams), _norm_counter(prof_maqams))

    parts = [(trans_sim, 0.50)]
    if rest_sim is not None:
        parts.append((rest_sim, 0.15))
    if iv_sim is not None:
        parts.append((iv_sim, 0.15))
    parts.append((rhythm_sim if rhythm_sim is not None else trans_sim, 0.20))
    parts.append((maqam_sim, 0.15))
    overall = float(sum(v * w for v, w in parts) / sum(w for _, w in parts))
    overall_pct = round(100.0 * max(0.0, min(1.0, overall)), 1)

    # جفت‌گذرهای مشترک برتر (امضاهای مشترک) و اختلافی‌ها
    joint = [(k, min(u_tr.get(k, 0), p_tr.get(k, 0)))
             for k in set(u_tr) & set(p_tr)]
    joint.sort(key=lambda kv: -kv[1])
    top_shared = [k for k, _ in joint[:4]]
    only_user = [k for k in u_tr if k not in p_tr][:4]

    if overall_pct >= 70:
        verdict = f"سبک خواندنت به «{profile.get('name')}» خیلی نزدیک است ✓"
    elif overall_pct >= 50:
        verdict = f"ریشه‌های سبک تو با «{profile.get('name')}» هم‌خوان است"
    elif overall_pct >= 30:
        verdict = f"شباهت متوسط با «{profile.get('name')}» — مسیر سیر متفاوتی رفته‌ای"
    else:
        verdict = f"سبک تو فعلاً با «{profile.get('name')}» تفاوت زیادی دارد"

    return {
        "style_id": profile.get("id"),
        "style_name": profile.get("name"),
        "basis_maqam": maqam,
        "overall_pct": overall_pct,
        "verdict_fa": verdict,
        "components": {
            "transitions_pct": round(100.0 * trans_sim, 1),
            "resting_pct": round(100.0 * rest_sim, 1) if rest_sim is not None else None,
            "intervals_pct": round(100.0 * iv_sim, 1) if iv_sim is not None else None,
            "rhythm_pct": round(100.0 * rhythm_sim, 1) if rhythm_sim is not None else None,
            "maqam_pct": round(100.0 * maqam_sim, 1),
        },
        "shared_moves_fa": top_shared,
        "only_yours_fa": only_user,
        "n_user_notes": feats["n_notes"],
    }
