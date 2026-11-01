#!/usr/bin/env bash
# تستِ شبیه‌سازی‌شدهٔ ساخت ویدیو روی کامپیوتر (بدون گوشی)
# اجرا:  bash android-qari/test/sim/run_tests.sh
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/../.." && pwd)"
JAVA="${JAVA_HOME:-/tmp/jdkx/jdk4py/java-runtime}/bin/java"
KOTLINC="${KOTLINC_JAR:-/tmp/kotlinc/lib/kotlin-compiler.jar}"
STDLIB="${KOTLIN_STDLIB:-/tmp/kotlinc/lib/kotlin-stdlib.jar}"
OUT="$DIR/build-test/classes"

# کامپایل تمیز: خروجی قبلی پاک می‌شود تا هرگز کلاسِ کهنه از ساختِ قبلی اجرا نشود
rm -rf "$DIR/build-test"
mkdir -p "$OUT"

# خطای کامپایل باید غیرصفر خارج شود (بدون pipe و بدون ‏|| true‏)
"$JAVA" -cp "$KOTLINC" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -jvm-target 1.8 -nowarn -d "$OUT" \
  "$ROOT"/src/ir/hamyareqari/dsp/*.kt \
  "$ROOT"/src/ir/hamyareqari/dsp/pitch/*.kt \
  "$ROOT"/src/ir/hamyareqari/app/ChartView.kt \
  "$ROOT"/src/ir/hamyareqari/app/VideoMaker.kt \
  "$DIR"/stubs/*.kt "$DIR"/VideoTest.kt "$DIR"/LongTest.kt "$DIR"/ZoomFollowTest.kt \
  "$DIR"/PitchV1Test.kt "$DIR"/PipelineTest.kt "$DIR"/PitchRangeTest.kt "$DIR"/RangeGuideTest.kt

# همهٔ کلاس‌های لازم باید از همین کامپایل ساخته شده باشند (نه ساختِ قبلی)
for cls in VideoTest ZoomFollowTest LongRun RangeGuideTest \
           ir/hamyareqari/dsp/pitch/PipelineTest \
           ir/hamyareqari/dsp/pitch/PitchV1Test \
           ir/hamyareqari/dsp/pitch/PitchRangeTest; do
  if [ ! -f "$OUT/$cls.class" ]; then
    echo "خطا: کلاس کامپایل‌نشده: $cls" >&2
    exit 1
  fi
done

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
echo
echo "── تست‌های واحد لولهٔ V1 (gate/stabilizer/resampler/fallback) ──"
"$JAVA" -Dstdout.encoding=UTF-8 -cp "$OUT:$STDLIB" ir.hamyareqari.dsp.pitch.PipelineTest
echo
echo "── آزمون سناریوهای موتور V1 (معیارهای دقت/نویز/تحریر/تأخیر) ──"
"$JAVA" -Xmx2g -Dstdout.encoding=UTF-8 -cp "$OUT:$STDLIB" ir.hamyareqari.dsp.pitch.PitchV1Test
echo
echo "── آزمون محدودهٔ صوتی (۱۵ سناریوی GT + demo.wav) ──"
"$JAVA" -Xmx2g -Dstdout.encoding=UTF-8 -Ddemo.wav="$DIR/../../assets/demo.wav" \
         -cp "$OUT:$STDLIB" ir.hamyareqari.dsp.pitch.PitchRangeTest
echo
echo "── آزمون خروجیِ محدودهٔ دستی (PNG + MP4) ──"
"$JAVA" -Djava.awt.headless=true -Dstdout.encoding=UTF-8 -cp "$OUT:$STDLIB" RangeGuideTest
