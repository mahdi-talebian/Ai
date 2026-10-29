#!/usr/bin/env bash
# ============================================================================
#  ساخت APK «همیار قاری» — Kotlin خالص، بدون Gradle، بدون Android Studio
# ----------------------------------------------------------------------------
#  پیش‌نیاز (یک‌بار):   bash android-qari/tools/fetch_toolchain.sh
#  ساخت:                bash android-qari/build_apk.sh
#  خروجی:               release/hamyar-qari-<version>.apk
# ============================================================================
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$DIR/.." && pwd)"
BUILD="$DIR/build"
OUT="$ROOT/release"
TOOLS="${ANDROID_TOOLS:-/tmp/android}"

VERSION_NAME="${VERSION_NAME:-1.1.6}"
VERSION_CODE="${VERSION_CODE:-8}"
MIN_SDK="${MIN_SDK:-26}"
TARGET_SDK="${TARGET_SDK:-34}"
SHRINK="${SHRINK:-0}"

KS="$DIR/keystore/hamyar-qari.jks"
KS_PASS="${KS_PASS:-hamyarqari}"
KS_ALIAS="${KS_ALIAS:-hamyarqari}"
KS_KEY_PASS="${KS_KEY_PASS:-hamyarqari}"

if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  echo "✖ java پیدا نشد. اول: bash android-qari/tools/fetch_toolchain.sh" >&2
  exit 1
fi

KOTLINC_JAR="$TOOLS/kotlin/lib/kotlin-compiler.jar"
KOTLIN_STDLIB="$TOOLS/kotlin/lib/kotlin-stdlib.jar"
for f in "$TOOLS/aapt2" "$TOOLS/android.jar" "$TOOLS/d8.jar" "$TOOLS/apksigner.jar" "$KOTLINC_JAR" "$KOTLIN_STDLIB"; do
  [ -f "$f" ] || { echo "✖ ابزار گم‌شده: $f (tools/fetch_toolchain.sh را اجرا کنید)" >&2; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/dex" "$OUT" "$DIR/assets"

# ── ۱) منابع ──────────────────────────────────────────────────────────────
echo "▸ aapt2 compile (res)"
"$TOOLS/aapt2" compile --dir "$DIR/res" -o "$BUILD/res/resources.zip"

echo "▸ aapt2 link"
"$TOOLS/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$TOOLS/android.jar" \
  --manifest "$DIR/AndroidManifest.xml" \
  -A "$DIR/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --replace-version \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res/resources.zip"

# ── ۲) Kotlin → کلاس ─────────────────────────────────────────────────────
echo "▸ کامپایل Kotlin"
find "$DIR/src" -name '*.kt' > "$BUILD/sources.txt"
"$JAVA" -Xmx1g -cp "$KOTLINC_JAR" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-reflect -jvm-target 1.8 -nowarn \
  -classpath "$TOOLS/android.jar:$KOTLIN_STDLIB" \
  -d "$BUILD/classes" \
  @"$BUILD/sources.txt" 2>&1 | grep -v "^warning:" | grep -v "WARNING:" || true
if ! find "$BUILD/classes" -name '*.class' | grep -q .; then
  echo "✖ کامپایل شکست خورد" >&2
  exit 1
fi

# ── ۳) dex ───────────────────────────────────────────────────────────────
echo "▸ d8 (dex)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
if [ "$SHRINK" = "1" ]; then
  echo "  • R8 با کوچک‌سازی"
  cat > "$BUILD/keep.pro" <<'PRO'
-keep class ir.hamyareqari.app.MainActivity { *; }
-keep class ir.hamyareqari.app.** { *; }
-keep class ir.hamyareqari.dsp.** { *; }
-dontwarn **
PRO
  "$JAVA" -Xmx2g -cp "$TOOLS/d8.jar" com.android.tools.r8.R8 \
    --release --min-api "$MIN_SDK" --lib "$TOOLS/android.jar" \
    --pg-conf "$BUILD/keep.pro" \
    --output "$BUILD/dex" \
    "$KOTLIN_STDLIB" @"$BUILD/classes.txt"
else
  "$JAVA" -Xmx2g -cp "$TOOLS/d8.jar" com.android.tools.r8.D8 \
    --min-api "$MIN_SDK" --lib "$TOOLS/android.jar" \
    --output "$BUILD/dex" \
    "$KOTLIN_STDLIB" @"$BUILD/classes.txt"
fi

# ── ۴) بسته‌بندی ─────────────────────────────────────────────────────────
echo "▸ افزودن classes.dex"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

echo "▸ zipalign"
python3 "$DIR/tools/zipalign.py" "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

echo "▸ امضا"
APK="$OUT/hamyar-qari-$VERSION_NAME.apk"
"$JAVA" -jar "$TOOLS/apksigner.jar" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEY_PASS" \
  --ks-key-alias "$KS_ALIAS" \
  --min-sdk-version "$MIN_SDK" \
  --out "$APK" "$BUILD/aligned.apk"

"$JAVA" -jar "$TOOLS/apksigner.jar" verify --print-certs "$APK" | head -4
SIZE=$(stat -c%s "$APK")
MD5=$(md5sum "$APK" | cut -d' ' -f1)
echo
echo "✓ ساخته شد: $APK"
echo "  حجم: $SIZE بایت   MD5: $MD5   versionCode: $VERSION_CODE"
