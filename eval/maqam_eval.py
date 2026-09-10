#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
هارنس اعتبارسنجی تشخیص مقام
=============================
تشخیص‌دهندهٔ مقام این پروژه (quran_maqam_analyzer) را روی دیتاست آزاد
OTMM Makam Recognition Dataset (MTG UPF) می‌سنجد:
https://github.com/MTG/otmm_makam_recognition_dataset

این دیتاست شامل ۱۰۰ ترک makam عثمانی است؛ به‌دلیل کپی‌رایت، فقط
«منحنی پردهٔ غالب» (pitch track) هر ترک توزیع شده — دقیقاً همان ورودی‌ای
که موتور ما نیاز دارد. مکام‌های ترکی که معادل مستقیم در فهرست ۱۰گانهٔ
مقام‌های عربی ما دارند نگاشت می‌شوند (Rast→رست، Hicaz→حجاز، Segah→سه‌گاه،
Saba→صبا، Nihavent→نهاوند، Beyati→بیات) و بقیه فقط برای گزارش
«ابهام بین‌مقامی» نگه داشته می‌شوند.

هشدار metodological: این هارنس «تمایل آماری» مدل را می‌سنجد، نه صحت نهایی
روی تلاوت عربی: تنظیم‌های ترکی (کوما ۱۷گانهٔ ارل-ازگی) با عربی (کنگرهٔ
قاهره ۱۹۳۲) تفاوت‌های ریز دارند و ترک‌های OTMM اغلب سازی/چندصدایی هستند.

اجرا:
    python3 eval/maqam_eval.py [--max-per-maqam 50] [--output eval/results_otmm.json]
"""

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "python"))
import quran_maqam_analyzer as qma  # noqa: E402

DEFAULT_DATA = Path("/home/user/.cache/eval_data/otmm_makam_recognition_dataset-main")
HOP_SEC = 0.0029  # طبق README دیتاست

# نگاشت مکام ترکی (نام پوشه در OTMM) -> کلید مقام در MAQAMAT ما
MAP = {
    "Rast": "رست (Rast)",
    "Hicaz": "حجاز (Hijaz)",
    "Segah": "سه‌گاه (Sikah)",
    "Saba": "صبا (Saba)",
    "Nihavent": "نهاوند (Nahawand)",
    "Beyati": "بیات (Bayati)",
}


def pitch_to_notes(path: Path, note_dur: float = 0.25):
    """فریم‌های pitch track را به نت‌های note_dur ثانیه‌ای تجمیع می‌کند."""
    freqs = np.loadtxt(path, dtype=float)
    freqs = freqs[freqs > 0]
    if len(freqs) < 16:
        return []
    per = max(1, int(note_dur / HOP_SEC))
    notes = []
    for i in range(0, len(freqs) - per + 1, per):
        chunk = freqs[i:i + per]
        voiced = chunk[chunk > 0]
        if len(voiced) >= per * 0.6:
            notes.append({"f0_hz": float(np.median(voiced)), "duration": note_dur})
    return notes


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    ap.add_argument("--max-per-maqam", type=int, default=50)
    ap.add_argument("--top-k", type=int, default=3)
    ap.add_argument("--output", type=Path,
                    default=Path(__file__).resolve().parent / "results_otmm.json")
    args = ap.parse_args()

    anns = json.load(open(args.data_dir / "annotations.json", encoding="utf-8"))
    by_mbid = {a["mbid"].rsplit("/", 1)[-1]: a for a in anns}

    per_maqam = defaultdict(int)
    rows, confusion = [], defaultdict(lambda: defaultdict(int))
    tonic_errs, top1 = [], 0

    for makam_tr, target in MAP.items():
        folder = args.data_dir / "data" / makam_tr
        if not folder.exists():
            continue
        for pfile in sorted(folder.glob("*.pitch")):
            if per_maqam[makam_tr] >= args.max_per_maqam:
                break
            per_maqam[makam_tr] += 1
            mbid = pfile.stem
            gt_tonic = by_mbid.get(mbid, {}).get("tonic")
            notes = pitch_to_notes(pfile)
            if not notes:
                continue
            hist = qma.build_qtet_histogram(notes)
            cands = qma.detect_tonic_and_maqam(hist, top_k=args.top_k)
            if not cands:
                continue
            pred = cands[0]["maqam"]
            hit = pred == target
            top1 += hit
            confusion[makam_tr][pred] += 1
            if gt_tonic:
                # خطا را modulo اکتاو تا می‌کنیم: اختلاف register میان تلاوت/تک‌نوازی
                # و آنوتیشن کنسرتی طبیعی است و نباید به‌پای مدل نوشته شود.
                c = 1200.0 * np.log2(cands[0]["tonic_freq_hz"] / gt_tonic)
                tonic_errs.append(abs(((c + 600.0) % 1200.0) - 600.0))
            rows.append({
                "mbid": mbid, "makam_otmm": makam_tr, "expected": target,
                "top1": pred, "top_k": [c["maqam"] for c in cands],
                "tonic_pred_hz": cands[0]["tonic_freq_hz"], "tonic_gt_hz": gt_tonic,
            })

    n = len(rows)
    top3 = sum(r["expected"] in r["top_k"] for r in rows)
    report = {
        "dataset": "MTG/otmm_makam_recognition_dataset",
        "n_evaluated": n,
        "top1_accuracy_pct": round(100.0 * top1 / n, 1) if n else 0,
        "top3_accuracy_pct": round(100.0 * top3 / n, 1) if n else 0,
        "tonic_error_cents_median": round(float(np.median(tonic_errs)), 1) if tonic_errs else None,
        "tonic_error_cents_p90": round(float(np.percentile(tonic_errs, 90)), 1) if tonic_errs else None,
        "per_maqam_recall": {
            tr: round(100.0 * confusion[tr].get(tg, 0) / max(1, sum(confusion[tr].values())), 1)
            for tr, tg in MAP.items() if confusion[tr]
        },
        "confusion": {tr: dict(cc) for tr, cc in confusion.items()},
        "rows": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    json.dump(report, open(args.output, "w", encoding="utf-8"), ensure_ascii=False, indent=1)

    print(f"# گزارش اعتبارسنجی مقام روی OTMM (n={n})")
    print(f"- Top-1: {report['top1_accuracy_pct']}%   Top-3: {report['top3_accuracy_pct']}%")
    print(f"- خطای تونیک: میانه {report['tonic_error_cents_median']} سنت، "
          f"P90 {report['tonic_error_cents_p90']} سنت")
    print("- recall به تفکیک مکام:", json.dumps(report["per_maqam_recall"], ensure_ascii=False))
    for tr, cc in confusion.items():
        best = sorted(cc.items(), key=lambda kv: -kv[1])[:3]
        print(f"  {tr:10s} -> " + ", ".join(f"{k}:{v}" for k, v in best))


if __name__ == "__main__":
    main()
