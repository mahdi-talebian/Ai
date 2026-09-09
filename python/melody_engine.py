#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
🎼 موتور پیشنهاد ملودیک (Melody Suggestion Engine)
====================================================
فراز خوانده‌شدهٔ کاربر را نت‌به‌نت تحلیل می‌کند و دو نوع پیشنهاد می‌سازد:

  ۱) «نسخهٔ اصلاحی» — همان فراز کاربر با نت‌های ترازشده به شبکهٔ دقیق مقام
     (نزدیک‌ترین درجهٔ مجاز بر حسب سنت) + پایان‌بندی گام‌به‌گام روی قرار
     (تونیک یا پنجم) اگر فراز روی قرار ننشسته باشد.

  ۲) «ادامهٔ پیشنهادی» — ملودی‌های کوتاه جدید که:
       • از نت پایانی فراز کاربر شروع می‌شوند (اتصال نرم)
       • فقط روی درجات مجاز مقام حرکت می‌کنند
       • طبق قواعد سیر مقامی: صعود تا غماز (آغاز جنس دوم از jins_chain)
         و فرود گام‌به‌گام به قرار
       • موتیف ملودیک خودِ کاربر (الگوی فاصله‌ای غالب) را بازمی‌گویند
       • نت پایانی روی قرار با کشش بیشتر (اقدام)

نمایندگی نت در خروجی: «سنت از تونیک» (برای پخش دقیق با Web Audio) +
شمارهٔ MIDI گردشده (برای روشن‌کردن کلید پیانو) + نام درجهٔ شرقی/سولفژ.
"""

import math
import random
from collections import Counter

import numpy as np

import quran_maqam_analyzer as qma


# ============================================================================
# ابزارهای پایهٔ گام
# ============================================================================

def _cents(f0, tonic_hz):
    """فاصله بر حسب سنت از تونیک (می‌تواند منفی یا بزرگ‌تر از ۱۲۰۰ باشد)."""
    return 1200.0 * math.log2(max(f0, 1e-6) / tonic_hz)


def _scale_degrees(maqam_name):
    """درجات گام صعودی بدون تکرار اکتاو — لیست سنت (۷ درجه)."""
    return list(qma.MAQAMAT[maqam_name]["scale_ascending"][:-1])


def _ghammaz_cents(maqam_name):
    """
    غماز = آغاز جنس دوم در زنجیرهٔ اجناس (عقد→جنس) همان مقام.
    مثال: رست → رست روی ۱ و جنس بعدی روی درجهٔ ۵ ⇒ غماز ۷۰۰
          بیاتی/حجاز → جنس دوم روی درجهٔ ۴ ⇒ غماز ۵۰۰
    """
    info = qma.MAQAMAT[maqam_name]
    chain = info.get("jins_chain") or []
    scale = info["scale_ascending"]
    if len(chain) >= 2:
        n = int(chain[1]["on_degree"])
        idx = min(max(n - 1, 0), len(scale) - 1)
        return scale[idx]
    return 500


def _karar_indices(scale):
    """
    اندیس‌های قرار (فرود) در گام: تونیک (۰) + پنجم اگر نزدیک ۷۰۰ سنت باشد.
    خروجی: لیست اندیس ۰..۶
    """
    ks = [0]
    for i, c in enumerate(scale):
        if abs(c - 700) <= 50:
            ks.append(i)
            break
    return ks


def _nearest_index(cents_in_octave, scale):
    """نزدیک‌ترین اندیس درجه به سنتِ درون‌اکتاوی."""
    diffs = [min(abs(cents_in_octave - dc), 1200 - abs(cents_in_octave - dc))
             for dc in scale]
    return int(np.argmin(diffs))


def _snap_cents(cents_abs, scale):
    """
    تراز کردن سنت مطلق به نزدیک‌ترین درجهٔ گام (با حفظ اکتاو نزدیک به اصل).
    خروجی: (سنت ترازشده، اندیس درون‌اکتاوی)
    """
    c = ((cents_abs % 1200.0) + 1200.0) % 1200.0
    idx = _nearest_index(c, scale)
    octave = round((cents_abs - c) / 1200.0)
    best, best_d = None, None
    for k in (-1, 0, 1):
        cand = (octave + k) * 1200.0 + scale[idx]
        d = abs(cand - cents_abs)
        if best_d is None or d < best_d:
            best, best_d = cand, d
    return float(best), idx


def _step_to_cents(step, scale):
    """شمارهٔ پله (اکتاو*۷ + اندیس) → سنت مطلق."""
    octave, idx = divmod(step, 7)
    return octave * 1200.0 + scale[idx]


def _step_to_midi(step, scale, tonic_midi):
    """شمارهٔ پله → MIDI گرد برای پیانو."""
    cents = _step_to_cents(step, scale)
    return int(round(tonic_midi + cents / 100.0))


def _label(maqam_name, tonic_hz, cents):
    """نام درجه/سولفژ برای یک سنت از تونیک."""
    f0 = tonic_hz * (2 ** (cents / 1200.0))
    info = qma.MAQAMAT[maqam_name]
    sol = qma.note_to_solfege(f0, tonic_hz, info["cents"], maqam_name=maqam_name)
    if not sol:
        return "—", "—"
    return (sol.get("absolute_degree_fa") or sol["degree_name"],
            sol.get("solfege_name") or sol["degree_name"])


# ============================================================================
# موتیف‌یابی از فراز کاربر
# ============================================================================

def _user_steps(notes, maqam_name, tonic_hz, scale):
    """هر نت کاربر → شمارهٔ پلهٔ گام (اکتاو*۷+اندیس)."""
    steps = []
    for n in notes:
        c = _cents(n["f0_hz"], tonic_hz)
        snapped, idx = _snap_cents(c, scale)
        octave = int(round((snapped - scale[idx]) / 1200.0))
        steps.append(octave * 7 + idx)
    return steps


def _extract_motif(steps, max_len=3):
    """
    غالب‌ترین الگوی فاصله‌ای متوالی (مستقل از جابه‌جایی) در فراز کاربر.
    اگر فراز کوتاه/یک‌نتی باشد، الگوی پیش‌فرض سیری برمی‌گردد.
    """
    if len(steps) >= 4:
        ivs = [steps[i + 1] - steps[i] for i in range(len(steps) - 1)]
        windows = [tuple(ivs[i:i + max_len]) for i in range(len(ivs) - max_len + 1)]
        windows = [w for w in windows if any(x != 0 for x in w)]
        if windows:
            return list(Counter(windows).most_common(1)[0][0])
    return [1, 1, -1]


# ============================================================================
# ۱) نسخهٔ اصلاحی فراز کاربر
# ============================================================================

def _ending_to_karar(last_step, scale, karar_idx_list, base_dur, rng):
    """۱ تا ۳ نت گام‌به‌گام تا نزدیک‌ترین قرار (برای پایان‌بندی)."""
    # نزدیک‌ترین قرار در همین اکتاو یا پایین‌تر
    cands = []
    for ki in karar_idx_list:
        octv = math.floor(last_step / 7)
        for k in (0, -1):
            cand = octv * 7 + ki + k * 7
            if cand < last_step:
                cands.append(cand)
    if not cands:
        return []
    target = max(cands)  # نزدیک‌ترین قرار پایین‌تر
    extra = []
    cur = last_step
    guard = 0
    while cur > target and guard < 4:
        cur -= 1
        extra.append(cur)
        guard += 1
    return extra


def make_corrected(notes, maqam_name, tonic_hz, tonic_midi):
    """نسخهٔ اصلاحی: همان ریتم/کانتور + نت‌های ترازشده + پایان‌بندی روی قرار."""
    scale = _scale_degrees(maqam_name)
    karars = _karar_indices(scale)
    rng = random.Random(1234)
    durs = [n["duration"] for n in notes if n.get("duration")]
    base_dur = float(np.median(durs)) if durs else 0.4

    out = []
    n_fixed = 0
    t0 = notes[0]["start"] if notes and notes[0].get("start") is not None else 0.0
    last_step = None
    for n in notes:
        c = _cents(n["f0_hz"], tonic_hz)
        snapped, _ = _snap_cents(c, scale)
        dev = snapped - c
        if abs(dev) > 30:
            n_fixed += 1
        step = int(round(snapped / 1200.0 * 7)) if False else None
        # پله معادل: از سنت ترازشده
        octave, rem = divmod(int(round(snapped)), 1200)
        idx = _nearest_index(rem % 1200 if rem % 1200 not in scale else rem % 1200, scale)
        # چون snapped دقیقاً روی درجه است:
        idx = scale.index(rem % 1200) if (rem % 1200) in scale else _nearest_index(rem % 1200, scale)
        last_step = octave * 7 + idx
        deg_fa, solfa = _label(maqam_name, tonic_hz, snapped)
        out.append({
            "start": round((n.get("start") or 0.0) - t0, 3),
            "dur": round(n.get("duration") or base_dur, 3),
            "cents": int(round(snapped)),
            "midi": int(round(tonic_midi + snapped / 100.0)),
            "degree_fa": deg_fa,
            "solfege": solfa,
            "dev_cents": int(round(dev)),
            "added": False,
        })

    # پایان‌بندی: اگر نت آخر روی (یا نزدیک) قرار نبود
    if last_step is not None and not any(
            (last_step % 7) == ki for ki in karars):
        extra_steps = _ending_to_karar(last_step, scale, karars, base_dur, rng)
        t = (out[-1]["start"] + out[-1]["dur"]) if out else 0.0
        for j, st in enumerate(extra_steps):
            cents = _step_to_cents(st, scale)
            deg_fa, solfa = _label(maqam_name, tonic_hz, cents)
            dur = base_dur if j < len(extra_steps) - 1 else base_dur * 1.8
            out.append({
                "start": round(t, 3), "dur": round(dur, 3),
                "cents": int(round(cents)),
                "midi": _step_to_midi(st, scale, tonic_midi),
                "degree_fa": deg_fa, "solfege": solfa,
                "dev_cents": 0, "added": True,
            })
            t += dur

    return {
        "kind": "corrected",
        "title_fa": "🩺 نسخهٔ اصلاحی فراز شما",
        "desc_fa": (f"نت‌ها به نزدیک‌ترین درجهٔ مجاز مقام {maqam_name} تراز شد — "
                    f"{n_fixed} نت بیش از ۳۰ سنت اختلاف داشت"
                    + (" + پایان‌بندی روی قرار" if any(n["added"] for n in out) else "")),
        "notes": out,
    }


# ============================================================================
# ۲) ادامهٔ پیشنهادی (ساخت ملودی بر پایهٔ سیر مقامی)
# ============================================================================

def _gen_path(start_step, scale, karar_idx_list, ghammaz_idx, motif,
              target_len, rng):
    """
    ساخت یک مسیر پله‌ای: صعود تا غماز (کمان ملودیک) + فرود گام‌به‌گام به قرار.
    خروجی: لیست پله‌ها (اکتاو*۷+اندیس)
    """
    # اوج = غماز در اکتاوی که طول مسیر را به target_len نزدیک می‌کند
    # (صعود ≈ فاصله تا اوج، فرود ≈ ۰٫۷×فاصله به‌خاطر پرش‌های ۲تایی)
    deltas = [d for d in range(2, 11)
              if (start_step + d) % 7 == ghammaz_idx]
    if not deltas:
        deltas = [4, 5, 6]
    best_apex, best_err = None, None
    for d in deltas:
        apex_c = start_step + d
        desc_len = max(2, int(round((apex_c - start_step) * 0.7)))
        err = abs((d + desc_len) - target_len)
        if best_err is None or err < best_err:
            best_apex, best_err = apex_c, err
    apex = best_apex

    path = [start_step]
    cur = start_step

    # --- فاز صعود (با امکان بازگویی موتیف کاربر) ---
    up_bias = 0.75
    while cur < apex and len(path) < target_len + 2:
        r = rng.random()
        if r < 0.30 and len(path) >= 2:
            # تلاش برای اجرای موتیف کاربر از جایگاه فعلی
            cand = [cur]
            ok = True
            for iv in motif:
                nxt = cand[-1] + int(iv)
                if nxt < 0 or nxt > max(apex, cur) + 2:
                    ok = False
                    break
                cand.append(nxt)
            if ok and cand[-1] <= apex + 1:
                path.extend(cand[1:])
                cur = cand[-1]
                continue
        if rng.random() < up_bias:
            step = rng.choice([1, 1, 1, 2])
        else:
            step = -1
        nxt = cur + step
        if nxt < 0 or (len(path) > 2 and nxt == path[-2]):
            nxt = cur + 1
        path.append(nxt)
        cur = nxt

    # --- فاز فرود به قرار ---
    karar = 0 if rng.random() < 0.6 else (karar_idx_list[-1] if len(karar_idx_list) > 1 else 0)
    octv = apex // 7
    target = octv * 7 + karar
    if target > apex:
        target -= 7
    guard = 0
    while cur > target and guard < 14:
        step = rng.choice([1, 1, 1, 2])
        nxt = max(target, cur - step)
        path.append(nxt)
        cur = nxt
        guard += 1

    # نت پایانی = قرار (اقدام) — حداکثر یک تکرار
    if path[-1] != target:
        path.append(target)
    elif rng.random() < 0.5:
        path.append(target)
    return path


def _score_path(path, scale, ghammaz_idx, motif, start_step):
    """امتیاز سیری مسیر — بالاتر = مقبول‌تر."""
    s = 0.0
    if path[-1] % 7 == 0:
        s += 3.0
    elif path[-1] % 7 in (4,):  # پنجم
        s += 2.0
    # غماز لمس شده؟
    ghammaz_c = scale[ghammaz_idx]
    for p in path:
        if abs((_step_to_cents(p, scale) % 1200) - ghammaz_c) < 1:
            s += 1.5
            break
    # موتیف کاربر بازگو شده؟
    ivs = [path[i + 1] - path[i] for i in range(len(path) - 1)]
    if tuple(motif[:2]) in [(ivs[i], ivs[i + 1]) for i in range(len(ivs) - 1)]:
        s += 1.0
    # شروع نرم از فراز کاربر
    if abs(path[0] - start_step) <= 2:
        s += 1.0
    # دامنهٔ معقول
    ambitus = max(path) - min(path)
    if ambitus <= 10:
        s += 0.5
    # پرش‌های زیاد جریمه
    big = sum(1 for iv in ivs if abs(iv) >= 4)
    s -= 0.5 * big
    return s


def _style_dur(style, rng, fallback):
    """کشش نت از توزیع سبک (مثلثی بین چارک‌ها حول میانه)."""
    try:
        ps = style.get("dur_stats") or {}
        lo, med, hi = ps.get("p25"), ps.get("p50"), ps.get("p75")
        if lo and med and hi:
            return max(0.15, round(rng.triangular(lo, hi, med) * 20) / 20)
    except Exception:
        pass
    return fallback


def _style_path(start_step, trans_counter, scale, karar_idx_list, ghammaz_idx,
                target_len, rng):
    """
    گام‌زدن در «ماتریس گذر قاری»: از هر پله، پلهٔ بعدی با وزن گذرهای ثبت‌شدهٔ
    همان قاری نمونه‌گیری می‌شود؛ سپس همان فرود به قرارِ قواعد سیر اعمال می‌شود.
    اگر گذری از پلهٔ فعلی ثبت نشده باشد، از نزدیک‌ترین پلهٔ دارای گذر ادامه می‌دهیم.
    """
    out = [start_step]
    cur = start_step
    guard = 0
    while len(out) < target_len and guard < target_len * 3:
        guard += 1
        cands = []
        for key, w in (trans_counter or {}).items():
            try:
                a_s, b_s = key.split("->")
                a, b = int(a_s), int(b_s)
            except Exception:
                continue
            if a == cur:
                cands.append((b, w))
        if not cands:
            # پرش به منبعی که گذر دارد (نزدیک‌ترین به cur)
            sources = sorted({int(k.split("->")[0]) for k in trans_counter
                              if "->" in k} ) if trans_counter else []
            if not sources:
                break
            cur = min(sources, key=lambda s: abs(s - cur))
            continue
        total = sum(w for _, w in cands)
        r = rng.uniform(0, total)
        acc = 0.0
        nxt = cands[0][0]
        for b, w in cands:
            acc += w
            if r <= acc:
                nxt = b
                break
        out.append(nxt)
        cur = nxt

    # --- فرود به قرار (مثل مسیر قاعده‌مبنا) ---
    karar = 0 if rng.random() < 0.6 else (karar_idx_list[-1] if len(karar_idx_list) > 1 else 0)
    octv = max(out) // 7
    target = octv * 7 + karar
    if target > max(out):
        target -= 7
    cur = out[-1]
    g = 0
    while cur > target and g < 14:
        nxt = max(target, cur - rng.choice([1, 1, 1, 2]))
        out.append(nxt)
        cur = nxt
        g += 1
    if out[-1] != target:
        out.append(target)
    return out


def _user_ioi_pattern(notes, max_k=6):
    """🥁 الگوی کشش خود کاربر: IOIها نرمال‌شده به میانه (خودِ ریتمش)."""
    durs = [float(n.get("duration") or 0) for n in notes or []]
    durs = [d for d in durs if d > 0.05]
    if len(durs) < 4:
        return None
    med = float(np.median(durs))
    if med <= 0:
        return None
    rel = [max(0.4, min(2.5, d / med)) for d in durs]
    tail = rel[-max_k:]
    return [round(x, 2) for x in tail]


def make_continuations(notes, maqam_name, tonic_hz, tonic_midi,
                       n_variants=2, seed=42, style=None):
    scale = _scale_degrees(maqam_name)
    ioi_pattern = _user_ioi_pattern(notes)
    karars = _karar_indices(scale)
    ghammaz_c = _ghammaz_cents(maqam_name)
    ghammaz_idx = _nearest_index(ghammaz_c, scale)

    steps = _user_steps(notes, maqam_name, tonic_hz, scale)
    if not steps:
        return []
    motif = _extract_motif(steps)
    start_step = steps[-1]

    durs = [n.get("duration") or 0 for n in notes if n.get("duration")]
    base_dur = float(np.median(durs)) if durs else 0.4
    t_after = (notes[-1].get("start") or 0) + (notes[-1].get("duration") or 0) \
        if notes and notes[-1].get("start") is not None else 0.0

    style_tr = ((style or {}).get("transitions") or {}).get(maqam_name)
    style_len = None
    try:
        style_len = int(style.get("phrase_len_median") or 0) or None
    except Exception:
        style_len = None

    candidates = []
    for k in range(8):
        rng = random.Random(seed * 100 + k)
        target_len = rng.randint(7, 10) if not style_len else \
            max(6, min(14, style_len + rng.randint(-2, 2)))
        if style_tr:
            path = _style_path(start_step, style_tr, scale, karars, ghammaz_idx,
                               target_len, rng)
        else:
            path = _gen_path(start_step, scale, karars, ghammaz_idx, motif,
                             target_len, rng)
        sc = _score_path(path, scale, ghammaz_idx, motif, start_step)
        if style_tr:
            # جایزهٔ وفاداری به گذرهای سبک: سهم گذرهای ثبت‌شده در مسیر
            keys = {f"{a}->{b}" for a, b in zip(path, path[1:])}
            hit = sum(style_tr.get(kk, 0) for kk in keys)
            sc += min(2.0, hit / max(1, len(path)))
        candidates.append((sc, k, path))
    candidates.sort(key=lambda x: -x[0])

    # دو واریانت متمایز: اولی بهترین؛ دومی ترجیحاً با اوج یا قرارِ متفاوت
    picked, seen = [], set()
    for sc, k, path in candidates:
        sig = tuple(path[:4]) + (path[-1],)
        if sig in seen:
            continue
        seen.add(sig)
        picked.append((sc, k, path))
        if len(picked) >= n_variants:
            break
    if len(picked) >= 2:
        best = picked[0]
        best_apex, best_end = max(best[2]), best[2][-1]
        ghammaz_step = None
        for pth in (best[2],):
            for p in pth:
                if _step_to_cents(p, scale) % 1200 == scale[ghammaz_idx]:
                    ghammaz_step = p
        for sc, k, path in candidates:
            if any(k == p[1] for p in picked):
                continue
            if max(path) != best_apex or path[-1] != best_end:
                # 🎭 واریانت دوم = «پاسخِ نیمه» — روی غماز می‌ماند تا ادامه‌پذیر باشد
                if ghammaz_step is not None:
                    path = path[:-1] + [ghammaz_step]
                picked[1] = (sc, k, path)
                break

    out = []
    for rank, (sc, k, path) in enumerate(picked, 1):
        rng = random.Random(seed * 100 + k + 500)
        notes_out = []
        t = t_after + 0.15
        pat = ioi_pattern or []
        for j, st in enumerate(path):
            cents = _step_to_cents(st, scale)
            if j < len(pat):
                dur = base_dur * pat[j]                 # 🥁 ریتم خود کاربر
            elif style:
                dur = _style_dur(style, rng, base_dur)  # یا توزیع سبک قاری
            else:
                dur = base_dur * rng.uniform(0.75, 1.25)
            if j == len(path) - 1:
                dur = base_dur * 1.9   # اقدام روی قرار
            dur = max(0.18, round(dur * 20) / 20)
            deg_fa, solfa = _label(maqam_name, tonic_hz, cents)
            notes_out.append({
                "start": round(t, 3), "dur": round(dur, 3),
                "cents": int(round(cents)),
                "midi": _step_to_midi(st, scale, tonic_midi),
                "degree_fa": deg_fa, "solfege": solfa,
                "dev_cents": 0, "added": False,
            })
            t += dur
        apex_deg_fa, _ = _label(maqam_name, tonic_hz,
                                max(_step_to_cents(p, scale) for p in path))
        style_tag = f" — به سبک {(style or {}).get('name')}" if style else ""
        qa_tag = (" · 🎭 جملهٔ کامل (روی قرار)" if rank == 1
                  else " · 🎭 جملهٔ نیمه (روی غماز — ادامه‌پذیر)") if len(picked) > 1 else ""
        out.append({
            "kind": "continuation",
            "title_fa": f"🎹 ادامهٔ پیشنهادی {rank}{style_tag}{qa_tag}",
            "desc_fa": (f"شروع از «{notes_out[0]['degree_fa']}» (ادامهٔ فراز شما) — "
                        f"اوج روی «{apex_deg_fa}» (غماز) و فرود به قرار؛ "
                        f"با بازگویی موتیف خودت"),
            "notes": notes_out,
            "score": round(sc, 2),
        })
    return out


# ============================================================================
# ورودی‌های زنده: تبدیل chunk های f0 به نت
# ============================================================================

def notes_from_chunks(chunks, group_cents=70.0, min_dur=0.12):
    """
    chunk = {"f0_hz": ..., "dur_sec": ...} — قطعات پیوستهٔ نزدیک‌به‌هم
    (در محدودهٔ group_cents) یکی می‌شوند و یک نت با میانهٔ f0 می‌سازند.
    """
    notes = []
    cur = []
    for ch in chunks or []:
        f = float(ch.get("f0_hz") or 0)
        d = float(ch.get("dur_sec") or 0)
        if f <= 0:
            if cur:
                notes.append(cur)
                cur = []
            continue
        if cur and abs(_cents(f, np.median([x[0] for x in cur]))) > group_cents:
            notes.append(cur)
            cur = []
        cur.append((f, d))
    if cur:
        notes.append(cur)
    out = []
    t = 0.0
    for grp in notes:
        dur = sum(x[1] for x in grp)
        if dur < min_dur:
            t += dur
            continue
        out.append({
            "start": round(t, 3),
            "duration": round(dur, 3),
            "f0_hz": round(float(np.median([x[0] for x in grp])), 2),
        })
        t += dur
    return out


# ============================================================================
# API اصلی
# ============================================================================

def suggest_melody(notes, maqam_name, tonic_hz, n_variants=2, seed=42, style=None):
    """
    notes: [{"start": s, "duration": d, "f0_hz": f}, ...]
    خروجی: {"maqam", "tonic_hz", "tonic_midi", "corrected", "continuations",
            "user_summary"}
    """
    if maqam_name not in qma.MAQAMAT:
        raise ValueError(f"مقام نامعتبر: {maqam_name}")
    if not notes:
        raise ValueError("هیچ نتی در فراز پیدا نشد")
    if not tonic_hz or tonic_hz <= 0:
        raise ValueError("تونیک نامعتبر")

    tonic_midi = int(round(69.0 + 12.0 * math.log2(tonic_hz / 440.0)))
    scale = _scale_degrees(maqam_name)

    corrected = make_corrected(notes, maqam_name, tonic_hz, tonic_midi)
    continuations = make_continuations(notes, maqam_name, tonic_hz,
                                       tonic_midi, n_variants=n_variants,
                                       seed=seed, style=style)

    in_scale = 0
    for n in notes:
        c = _cents(n["f0_hz"], tonic_hz)
        snapped, _ = _snap_cents(c, scale)
        if abs(snapped - c) <= 40:
            in_scale += 1
    summary = {
        "n_notes": len(notes),
        "in_scale_pct": round(100.0 * in_scale / len(notes), 1) if notes else 0.0,
        "ghammaz_fa": _label(maqam_name, tonic_hz, _ghammaz_cents(maqam_name))[0],
    }

    return {
        "maqam": maqam_name,
        "tonic_hz": round(float(tonic_hz), 2),
        "tonic_midi": tonic_midi,
        "corrected": corrected,
        "continuations": continuations,
        "user_summary": summary,
    }
