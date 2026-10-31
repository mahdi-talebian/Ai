package ir.hamyareqari.dsp

import kotlin.math.roundToInt

/** نویسندهٔ JSON دست‌ساز — بدون وابستگی، مناسب گزارش قابل‌اشتراک */
object Json {

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 8)
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append(String.format(java.util.Locale.US, "\\u%04x", c.code)) else sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun num(v: Double, digits: Int = 2): String {
        if (!v.isFinite()) return "null"
        val r = Math.round(v * Math.pow(10.0, digits.toDouble())) / Math.pow(10.0, digits.toDouble())
        if (r == r.toLong().toDouble() && Math.abs(r) < 1e15) return r.toLong().toString()
        return String.format(java.util.Locale.US, "%." + digits + "f", r)
    }

    fun report(
        r: Report,
        sourceName: String,
        appVersion: String,
        /** قرارداد مشترک نمونه‌ها (مسیر V1) — اختیاری و افزودنی؛ کلیدهای قبلی دست‌نخورده */
        samples: List<ir.hamyareqari.dsp.pitch.PitchSample>? = null
    ): String {
        val sb = StringBuilder(8192)
        sb.append("{\n")
        sb.append("  \"app\": \"همیار قاری\",\n")
        sb.append("  \"version\": \"").append(esc(appVersion)).append("\",\n")
        sb.append("  \"source\": \"").append(esc(sourceName)).append("\",\n")
        sb.append("  \"duration_sec\": ").append(num(r.durationSec)).append(",\n")
        sb.append("  \"sample_rate\": ").append(r.sampleRate).append(",\n")
        sb.append("  \"hop_ms\": ").append(num(r.hopMs, 1)).append(",\n")
        sb.append("  \"sung_sec\": ").append(num(r.sungSec)).append(",\n")

        sb.append("  \"range\": {\n")
        sb.append("    \"highest\": ").append(noteJson(r.highest)).append(",\n")
        sb.append("    \"lowest\": ").append(noteJson(r.lowest)).append(",\n")
        sb.append("    \"raw_highest\": ").append(noteJson(r.rawHighest)).append(",\n")
        sb.append("    \"raw_lowest\": ").append(noteJson(r.rawLowest)).append(",\n")
        sb.append("    \"span_semitones\": ").append(num(r.spanSemitones)).append(",\n")
        sb.append("    \"span_octaves\": ").append(num(r.spanOctaves)).append(",\n")
        sb.append("    \"span_cents\": ").append(num(r.spanCents)).append(",\n")
        sb.append("    \"full_octaves\": ").append(r.fullOctaves).append(",\n")
        sb.append("    \"rest_semitones\": ").append(num(r.restSemitones)).append(",\n")
        sb.append("    \"quarter_tones\": ").append(num(r.quarterTones)).append(",\n")
        sb.append("    \"of_octave_pct\": ").append(num(r.ofOctavePct)).append(",\n")
        sb.append("    \"center_note\": \"").append(esc(r.centerName)).append("\",\n")
        sb.append("    \"center_hz\": ").append(num(r.centerHz)).append("\n")
        sb.append("  },\n")

        sb.append("  \"quality\": {\n")
        sb.append("    \"status\": \"").append(esc(r.quality.status)).append("\",\n")
        sb.append("    \"snr_db\": ").append(num(r.quality.snrDb)).append(",\n")
        sb.append("    \"engine_agreement_pct\": ").append(num(r.quality.agreementPct)).append(",\n")
        sb.append("    \"mean_note_confidence\": ").append(num(r.quality.meanConfidence)).append(",\n")
        sb.append("    \"octave_fixes\": {\"halved\": ").append(r.quality.octaveDown)
            .append(", \"doubled\": ").append(r.quality.octaveUp).append("},\n")
        sb.append("    \"warnings\": [")
        for ((i, w) in r.quality.warnings.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append('"').append(esc(w)).append('"')
        }
        sb.append("]\n  },\n")

        // ── اعتبارسنجی صدا (افزوده؛ کلیدهای قبلی دست‌نخورده) ──
        sb.append("  \"analysis\": {\n")
        sb.append("    \"engine\": \"").append(esc(r.engineName)).append("\",\n")
        sb.append("    \"valid_share_pct\": ").append(num(r.validShare * 100.0)).append(",\n")
        sb.append("    \"uncertain_share_pct\": ").append(num(r.uncertainShare * 100.0)).append(",\n")
        sb.append("    \"estimated_share_pct\": ").append(num(r.estimatedShare * 100.0)).append("\n")
        sb.append("  },\n")

        sb.append("  \"pauses\": {\n")
        sb.append("    \"count\": ").append(r.pauseCount).append(",\n")
        sb.append("    \"longest_sec\": ").append(num(r.longestPause)).append(",\n")
        sb.append("    \"list\": [")
        for ((i, p) in r.pauses.withIndex()) {
            if (i > 0) sb.append(", ")
            sb.append(num(p))
        }
        sb.append("]\n  },\n")

        sb.append("  \"notes\": [\n")
        for ((i, n) in r.notes.withIndex()) {
            sb.append("    {\"note\": \"").append(esc(n.nameAscii)).append("\", ")
            sb.append("\"start\": ").append(num(n.start, 3)).append(", ")
            sb.append("\"duration\": ").append(num(n.duration, 3)).append(", ")
            sb.append("\"f0_hz\": ").append(num(n.f0)).append(", ")
            sb.append("\"cents_off\": ").append(num(PitchMath.centsOffPitch(n.f0), 1)).append(", ")
            sb.append("\"stability_cents\": ").append(num(n.stability, 1)).append(", ")
            sb.append("\"confidence\": ").append(num(n.confidence, 3)).append(", ")
            sb.append("\"harmonics\": ").append(n.harmonics).append("}")
            if (i < r.notes.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("  ],\n")

        sb.append("  \"note_counts\": [\n")
        for ((i, s) in r.shares.withIndex()) {
            sb.append("    {\"note\": \"").append(esc(s.name)).append("\", ")
            sb.append("\"sung_sec\": ").append(num(s.sungSec)).append(", ")
            sb.append("\"share_pct\": ").append(num(s.sharePct, 1)).append(", ")
            sb.append("\"count\": ").append(s.count).append("}")
            if (i < r.shares.size - 1) sb.append(",")
            sb.append("\n")
        }
        // ── قرارداد مشترک نمونه‌ها (V1) — فقط اگر داده‌شده باشد؛ افزودنی ──
        if (samples != null && samples.isNotEmpty()) {
            sb.append("  ],\n")
            sb.append("  \"pitch_samples\": {\n")
            sb.append("    \"fields\": [\"timestamp_sec\", \"raw_f0_hz\", \"stable_f0_hz\", \"note\", \"note_midi\", \"cents\", \"quality_score\", \"loudness_rms\", \"status\", \"backend\"],\n")
            sb.append("    \"list\": [\n")
            for ((i, s) in samples.withIndex()) {
                sb.append("      {\"timestamp_sec\": ").append(num(s.timestampSec, 3))
                    .append(", \"raw_f0_hz\": ").append(num(s.rawF0Hz))
                    .append(", \"stable_f0_hz\": ").append(num(s.stableF0Hz))
                    .append(", \"note\": \"").append(esc(s.noteName)).append('"')
                    .append(", \"note_midi\": ").append(s.noteMidi)
                    .append(", \"cents\": ").append(num(s.cents, 1))
                    .append(", \"quality_score\": ").append(num(s.qualityScore, 3))
                    .append(", \"loudness_rms\": ").append(num(s.loudnessRms, 4))
                    .append(", \"status\": \"").append(ir.hamyareqari.dsp.pitch.FrameStatus.name(s.status)).append('"')
                    .append(", \"backend\": \"").append(esc(s.backend)).append("\"}")
                if (i < samples.size - 1) sb.append(",")
                sb.append("\n")
            }
            sb.append("    ]\n")
            sb.append("  }\n}\n")
        } else {
            sb.append("  ]\n}\n")
        }
        return sb.toString()
    }

    private fun noteJson(n: NoteHit?): String {
        if (n == null) return "null"
        val sb = StringBuilder()
        sb.append("{\"note\": \"").append(esc(n.nameAscii)).append("\", ")
        sb.append("\"f0_hz\": ").append(num(n.f0)).append(", ")
        sb.append("\"start\": ").append(num(n.start, 2)).append(", ")
        sb.append("\"duration\": ").append(num(n.duration, 2)).append(", ")
        sb.append("\"confidence\": ").append(num(n.confidence, 3)).append("}")
        return sb.toString()
    }

    fun summaryLine(r: Report): String {
        val hi = r.highest?.name ?: "—"
        val lo = r.lowest?.name ?: "—"
        return "بالاترین: $hi · پایین‌ترین: $lo · گستره: ${(r.spanSemitones * 10).roundToInt() / 10.0} نیم‌پرده"
    }
}
