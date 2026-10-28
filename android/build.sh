#!/usr/bin/env bash
# =============================================================================
# ساخت APK اندروید «ردِّ صدا» — بدون Gradle و بدون Android SDK
# -----------------------------------------------------------------------------
# زنجیره: aapt2 (منابع/مانیفست) + smali (کد) + apksigner (امضا)
# ابزارها از متغیرهای زیر خوانده می‌شوند (پیش‌فرض: مسیرهای این سندباکس):
#   TOOLS       پوشهٔ aapt2 / smali.jar / apksigner.jar
#   ANDROID_JAR android.jar (پلتفرم API 28 — شامل resources.arsc)
#   JAVA_HOME   جاوای full-JRE (باید java و keytool داشته باشد)
#
# خروجی: android/dist/raddeseda.apk
# =============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
TOOLS="${TOOLS:-/tmp}"
ANDROID_JAR="${ANDROID_JAR:-/tmp/android.jar}"

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(python3 -c 'import jdk4py; print(jdk4py.JAVA_HOME)' 2>/dev/null || true)"
fi
JAVA="$JAVA_HOME/bin/java"
KEYTOOL="$JAVA_HOME/bin/keytool"
[ -x "$JAVA" ] || { echo "java پیدا نشد — JAVA_HOME را تنظیم کنید"; exit 1; }

BUILD="$HERE/build"
rm -rf "$BUILD"
mkdir -p "$BUILD" "$HERE/dist"

echo "۱/۶ — کامپایل منابع (aapt2 compile)"
"$TOOLS/aapt2" compile --dir "$HERE/res" -o "$BUILD/compiled.zip"

echo "۲/۶ — لینک مانیفست + منابع (aapt2 link)"
"$TOOLS/aapt2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$HERE/AndroidManifest.xml" \
  --min-sdk-version 21 \
  --target-sdk-version 28 \
  "$BUILD/compiled.zip"

echo "۳/۶ — مونتاژ smali → classes.dex"
"$JAVA" -jar "$TOOLS/smali.jar" --api-level 28 -o "$BUILD/classes.dex" "$HERE/src"

echo "۴/۶ — افزودن classes.dex"
python3 - "$BUILD/base.apk" "$BUILD/classes.dex" "$BUILD/unsigned.apk" <<'PY'
import sys, zipfile
base, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(base)
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as zout:
    for info in zin.infolist():
        zout.writestr(info, zin.read(info.filename))
    zout.write(dex, "classes.dex")
PY

echo "۵/۶ — امضا (کلید debug محلی)"
KS="$BUILD/debug.keystore"
if [ ! -f "$KS" ]; then
  "$KEYTOOL" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Radde Seda, O=RaddeSeda, C=FI" >/dev/null 2>&1
fi
"$JAVA" -cp "$TOOLS/apksigner.jar" com.android.apksigner.ApkSignerTool sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --out "$HERE/dist/raddeseda.apk" "$BUILD/unsigned.apk"

echo "۶/۶ — راستی‌آزمایی"
"$JAVA" -cp "$TOOLS/apksigner.jar" com.android.apksigner.ApkSignerTool verify \
  --verbose "$HERE/dist/raddeseda.apk" | head -8
"$TOOLS/aapt2" dump badging "$HERE/dist/raddeseda.apk" | head -6

echo
echo "✓ آماده: $HERE/dist/raddeseda.apk"
