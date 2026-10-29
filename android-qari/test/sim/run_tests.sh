#!/usr/bin/env bash
# تستِ شبیه‌سازی‌شدهٔ ساخت ویدیو روی کامپیوتر (بدون گوشی)
# اجرا:  bash android-qari/test/sim/run_tests.sh
set -e
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
JAVA="${JAVA_HOME:-/tmp/jdkx/jdk4py/java-runtime}/bin/java"
KOTLINC="${KOTLINC_JAR:-/tmp/kotlinc/lib/kotlin-compiler.jar}"
STDLIB="${KOTLIN_STDLIB:-/tmp/kotlinc/lib/kotlin-stdlib.jar}"
OUT="$DIR/build-test/classes"
mkdir -p "$OUT"

"$JAVA" -cp "$KOTLINC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -jvm-target 1.8 -nowarn -d "$OUT" \
  "$ROOT"/src/ir/hamyareqari/dsp/*.kt \
  "$ROOT"/src/ir/hamyareqari/app/ChartView.kt \
  "$ROOT"/src/ir/hamyareqari/app/VideoMaker.kt \
  "$DIR"/stubs/*.kt "$DIR"/VideoTest.kt "$DIR"/LongTest.kt "$DIR"/ZoomFollowTest.kt 2>&1 | grep -v '^warning:' || true

cd "$DIR"
echo "── تست کوتاه (چهار پروفایل دستگاه) ──"
"$JAVA" -Dstdout.encoding=UTF-8 -Ddemo.wav="$DIR/../../assets/demo.wav" \
         -Dfont.bold="$DIR/../../assets/fonts/Estedad-Bold.ttf" \
         -Dfont.regular="$DIR/../../assets/fonts/Estedad-Regular.ttf" \
         -cp "$OUT:$STDLIB" VideoTest
echo
echo "── تست زوم و دنبال‌کردن پخش ──"
"$JAVA" -Dstdout.encoding=UTF-8 -Ddemo.wav="$DIR/../../assets/demo.wav" \
         -Dfont.bold="$DIR/../../assets/fonts/Estedad-Bold.ttf" \
         -Dfont.regular="$DIR/../../assets/fonts/Estedad-Regular.ttf" \
         -cp "$OUT:$STDLIB" ZoomFollowTest
echo
echo "── تست بلند (کل نمونهٔ ۳۸ ثانیه‌ای) ──"
"$JAVA" -Dstdout.encoding=UTF-8 -Ddemo.wav="$DIR/../../assets/demo.wav" \
         -Dfont.bold="$DIR/../../assets/fonts/Estedad-Bold.ttf" \
         -Dfont.regular="$DIR/../../assets/fonts/Estedad-Regular.ttf" \
         -cp "$OUT:$STDLIB" LongRun
