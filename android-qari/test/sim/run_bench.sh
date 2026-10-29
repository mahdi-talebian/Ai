#!/usr/bin/env bash
# ============================================================================
#  بنچمارک F0 و آزمون رگرسیون موتور تحلیل (بدون گوشی، بدون اندروید)
# ----------------------------------------------------------------------------
#  اجرا:  bash android-qari/test/sim/run_bench.sh
#  پیش‌نیاز: JAVA_HOME و KOTLINC_JAR (مثل run_tests.sh) یا fetch_toolchain.sh
#
#  خروجی: جدول معیارها برای دو مسیر legacy و enhanced + بازگشت غیرصفر در صورت
#  رگرسیون (بدتر شدن دقت/اکتاو/رد اشتباه/پذیرش نویز/حفظ تحریر).
# ============================================================================
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
TOOLS="${ANDROID_TOOLS:-/tmp/android}"
JAVA="${JAVA_HOME:-$TOOLS/jdk}/bin/java"
KOTLINC="${KOTLINC_JAR:-$TOOLS/kotlin/lib/kotlin-compiler.jar}"
STDLIB="${KOTLIN_STDLIB:-$TOOLS/kotlin/lib/kotlin-stdlib.jar}"
OUT="$DIR/build-bench/classes"
mkdir -p "$OUT"

"$JAVA" -Xmx1g -cp "$KOTLINC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -jvm-target 1.8 -nowarn -d "$OUT" \
  "$ROOT"/src/ir/hamyareqari/dsp/*.kt "$ROOT"/tools/PitchBench.kt 2>&1 | grep -v '^warning:' || true

"$JAVA" -Xmx2g -Dstdout.encoding=UTF-8 -cp "$OUT:$STDLIB" ir.hamyareqari.tools.PitchBench "$@"
