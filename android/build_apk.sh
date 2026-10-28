#!/usr/bin/env bash
# ============================================================================
#  ساخت APK اندروید «ردِّ صدا» — بدون Gradle، بدون Android Studio
# ----------------------------------------------------------------------------
#  پیش‌نیازها (یک‌بار):  bash android/tools/fetch_toolchain.sh
#  ساخت:                 bash android/build_apk.sh
#  خروجی:                release/radde-seda-<version>.apk
#
#  مراحل: res → aapt2 compile/link → ecj (javac جایگزین) → d8 → بسته‌بندی
#         → zipalign (پایتونی) → apksigner
# ============================================================================
set -euo pipefail

ANDROID_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$ANDROID_DIR/.." && pwd)"
BUILD="$ANDROID_DIR/build"
OUT_DIR="$ROOT_DIR/release"
TOOLS="${ANDROID_TOOLS:-/tmp/android}"

VERSION_NAME="${VERSION_NAME:-1.0.0}"
VERSION_CODE="${VERSION_CODE:-1}"
MIN_SDK="${MIN_SDK:-24}"
TARGET_SDK="${TARGET_SDK:-34}"

KS="$ANDROID_DIR/keystore/radde-seda.jks"
KS_PASS="${KS_PASS:-raddeseda}"
KS_ALIAS="${KS_ALIAS:-raddeseda}"
KS_KEY_PASS="${KS_KEY_PASS:-raddeseda}"

# ── ابزارها ────────────────────────────────────────────────────────────────
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  JAVA="$(command -v java)"
else
  echo "✖ java پیدا نشد. اول این را اجرا کن: bash android/tools/fetch_toolchain.sh" >&2
  exit 1
fi

AAPT2="$TOOLS/aapt2"
ANDROID_JAR="$TOOLS/android.jar"
D8_JAR="$TOOLS/d8.jar"
APKSIGNER_JAR="$TOOLS/apksigner.jar"
ECJ_JAR="$TOOLS/ecj.jar"

for f in "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR" "$ECJ_JAR"; do
  if [ ! -f "$f" ]; then
    echo "✖ ابزار گم‌شده: $f" >&2
    echo "  اجرا کن: bash android/tools/fetch_toolchain.sh" >&2
    exit 1
  fi
done

# ── پاک‌سازی ───────────────────────────────────────────────────────────────
rm -rf "$BUILD"
mkdir -p "$BUILD/res" "$BUILD/classes" "$BUILD/dex" "$ANDROID_DIR/assets/web" "$OUT_DIR"

# ── ۱) رابط وب داخل assets (همیشه از نسخهٔ فعلی web/) ────────────────────
echo "▸ کپی رابط وب به assets"
rm -rf "$ANDROID_DIR/assets/web"
mkdir -p "$ANDROID_DIR/assets/web"
cp -R "$ROOT_DIR/web/." "$ANDROID_DIR/assets/web/"

# ── ۲) منابع ──────────────────────────────────────────────────────────────
echo "▸ aapt2 compile (res)"
"$AAPT2" compile --dir "$ANDROID_DIR/res" -o "$BUILD/res/resources.zip"

echo "▸ aapt2 link"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$ANDROID_DIR/AndroidManifest.xml" \
  -A "$ANDROID_DIR/assets" \
  --min-sdk-version "$MIN_SDK" \
  --target-sdk-version "$TARGET_SDK" \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  --auto-add-overlay \
  "$BUILD/res/resources.zip"

# ── ۳) جاوا → کلاس → dex ─────────────────────────────────────────────────
echo "▸ کامپایل جاوا (ecj)"
# کپی منابع تا بتوانیم مقادیر ساخت را بدون دست‌زدن به مخزن بازنویسی کنیم
rm -rf "$BUILD/src"
cp -R "$ANDROID_DIR/java" "$BUILD/src"
if [ -n "${DEFAULT_API:-}" ]; then
  echo "  • پیش‌فرض سرور: $DEFAULT_API"
  sed -i "s|DEFAULT_API = \"\"|DEFAULT_API = \"$DEFAULT_API\"|" \
    "$BUILD/src/ir/raddeseda/app/BuildConfig.java"
fi
if [ -n "${APP_VERSION:-}" ]; then
  sed -i "s|VERSION = \"[^\"]*\"|VERSION = \"$APP_VERSION\"|" \
    "$BUILD/src/ir/raddeseda/app/BuildConfig.java"
fi
find "$BUILD/src" -name '*.java' > "$BUILD/sources.txt"
"$JAVA" -jar "$ECJ_JAR" -source 1.8 -target 1.8 -nowarn \
  -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "▸ d8 (dex)"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 \
  --min-api "$MIN_SDK" --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" @"$BUILD/classes.txt"

# ── ۴) بسته‌بندی dex در APK ───────────────────────────────────────────────
echo "▸ افزودن classes.dex"
python3 - "$BUILD/base.apk" "$BUILD/dex/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import shutil, sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
shutil.copy(base, out)
with zipfile.ZipFile(out, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex, "classes.dex")
PY

# ── ۵) هم‌ترازی ───────────────────────────────────────────────────────────
echo "▸ zipalign"
python3 "$ANDROID_DIR/tools/zipalign.py" "$BUILD/unsigned.apk" "$BUILD/aligned.apk"

# ── ۶) امضا ───────────────────────────────────────────────────────────────
APK="$OUT_DIR/radde-seda-$VERSION_NAME.apk"
if [ ! -f "$KS" ]; then
  echo "▸ ساخت کلید امضا (یک‌بار)"
  "$JAVA" -cp "$TOOLS" >/dev/null 2>&1 || true
  "${JAVA%java}keytool" -genkeypair -keystore "$KS" -alias "$KS_ALIAS" \
    -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass "$KS_PASS" -keypass "$KS_KEY_PASS" \
    -dname "CN=Radde Seda, O=Radde Seda, C=IR" >/dev/null
fi

echo "▸ apksigner"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-key-alias "$KS_ALIAS" \
  --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_KEY_PASS" \
  --v1-signing-enabled false --v2-signing-enabled true \
  --out "$APK" "$BUILD/aligned.apk"

echo "▸ بررسی امضا"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$APK" | head -6

echo
echo "✅ آماده است: $APK"
ls -lh "$APK"
