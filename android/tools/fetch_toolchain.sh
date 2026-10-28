#!/usr/bin/env bash
# ============================================================================
#  گرفتن زنجیرهٔ ابزار ساخت APK  (بدون Android Studio / بدون Gradle)
# ----------------------------------------------------------------------------
#  چه چیزهایی لازم است و از کجا می‌آید؟
#    java (JRE)      ← بستهٔ پایتونی jdk4py  (pypi.org)
#    aapt2 (linux)   ← بستهٔ نُدِ aaptjs3    (registry.npmjs.org)
#    android.jar, d8.jar, apksigner.jar, ecj.jar
#                    ← بستهٔ نُدِ minapk     (registry.npmjs.org)
#
#  همه‌چیز در $ANDROID_TOOLS (پیش‌فرض /tmp/android) نصب می‌شود تا داخل مخزن
#  فایل باینری سنگین نماند. مسیر را با متغیر محیطی می‌توان عوض کرد.
# ============================================================================
set -euo pipefail

TOOLS="${ANDROID_TOOLS:-/tmp/android}"
TMP="$(mktemp -d)"
mkdir -p "$TOOLS"

echo "▸ ابزارها در: $TOOLS"

need() { [ -f "$TOOLS/$1" ]; }

# ── aapt2 و بردارهای Android (از npm) ──────────────────────────────────────
if ! need aapt2 || ! need android.jar || ! need d8.jar || ! need apksigner.jar || ! need ecj.jar; then
  echo "▸ دانلود aapt2 / android.jar / d8 / apksigner / ecj از npm"
  ( cd "$TMP" && npm pack aaptjs3@2.0.2 --silent >/dev/null && npm pack @drxiaozhi/minapk@0.5.1 --silent >/dev/null )
  mkdir -p "$TMP/a" "$TMP/b"
  tar xzf "$TMP"/aaptjs3-*.tgz -C "$TMP/a"
  tar xzf "$TMP"/drxiaozhi-minapk-*.tgz -C "$TMP/b"
  cp "$TMP/a/package/bin/x64/linux/aapt2" "$TOOLS/aapt2"
  chmod +x "$TOOLS/aapt2"
  cp "$TMP/b/package/tools/android.jar"      "$TOOLS/android.jar"
  cp "$TMP/b/package/tools/d8.jar"           "$TOOLS/d8.jar"
  cp "$TMP/b/package/tools/apksigner.jar"    "$TOOLS/apksigner.jar"
  cp "$TMP/b/package/tools/ecj-3.45.0.jar"   "$TOOLS/ecj.jar"
fi

# ── java (JRE) ─────────────────────────────────────────────────────────────
if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  if ! command -v java >/dev/null 2>&1; then
    echo "▸ دانلود JRE (jdk4py از pypi)"
    python3 -m pip download jdk4py --no-deps -d "$TMP/jdk" -q
    mkdir -p "$TMP/jdkx"
    python3 -m zipfile -e "$TMP"/jdk/*.whl "$TMP/jdkx"
    mkdir -p "$TOOLS/jdk"
    cp -R "$TMP/jdkx/jdk4py/java-runtime/." "$TOOLS/jdk/"
    chmod -R +x "$TOOLS/jdk/bin"
    export JAVA_HOME="$TOOLS/jdk"
  else
    export JAVA_HOME="$(dirname "$(dirname "$(command -v java)")")"
  fi
fi

echo
echo "✓ JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "✓ aapt2: $("$TOOLS/aapt2" version | head -1)"
echo
echo "حالا بساز:  Java_HOME=$JAVA_HOME ANDROID_TOOLS=$TOOLS bash android/build_apk.sh"
