#!/usr/bin/env bash
# ============================================================================
#  زنجیرهٔ ابزار ساخت APK «همیار قاری» — بدون Android Studio، بدون Gradle
# ----------------------------------------------------------------------------
#  چه چیزهایی و از کجا:
#    java (JDK)         ← بستهٔ پایتونی jdk4py        (pypi.org)
#    aapt2              ← بستهٔ نُدِ aaptjs3          (registry.npmjs.org)
#    android.jar / d8 / apksigner ← بستهٔ نُدِ minapk (registry.npmjs.org)
#    kotlinc            ← بستهٔ نُدِ kotlin-compiler  (registry.npmjs.org)
#
#  همه‌چیز در $ANDROID_TOOLS (پیش‌فرض /tmp/android) نصب می‌شود تا هیچ فایل
#  باینری سنگینی داخل مخزن نماند.
# ============================================================================
set -euo pipefail

TOOLS="${ANDROID_TOOLS:-/tmp/android}"
TMP="$(mktemp -d)"
mkdir -p "$TOOLS"
echo "▸ ابزارها در: $TOOLS"

need() { [ -e "$TOOLS/$1" ]; }

if ! need aapt2 || ! need android.jar || ! need d8.jar || ! need apksigner.jar; then
  echo "▸ دانلود aapt2 / android.jar / d8 / apksigner از npm"
  ( cd "$TMP" && npm pack aaptjs3@2.0.2 --silent >/dev/null && npm pack @drxiaozhi/minapk@0.5.1 --silent >/dev/null )
  mkdir -p "$TMP/a" "$TMP/b"
  tar xzf "$TMP"/aaptjs3-*.tgz -C "$TMP/a"
  tar xzf "$TMP"/drxiaozhi-minapk-*.tgz -C "$TMP/b"
  cp "$TMP/a/package/bin/x64/linux/aapt2" "$TOOLS/aapt2"
  chmod +x "$TOOLS/aapt2"
  cp "$TMP/b/package/tools/android.jar"   "$TOOLS/android.jar"
  cp "$TMP/b/package/tools/d8.jar"        "$TOOLS/d8.jar"
  cp "$TMP/b/package/tools/apksigner.jar" "$TOOLS/apksigner.jar"
fi

if ! need kotlin/lib/kotlin-compiler.jar; then
  echo "▸ دانلود کامپایلر Kotlin از npm"
  ( cd "$TMP" && npm pack kotlin-compiler@2.4.21 --silent >/dev/null )
  mkdir -p "$TMP/k"
  tar xzf "$TMP"/kotlin-compiler-*.tgz -C "$TMP/k"
  mkdir -p "$TOOLS/kotlin"
  cp -R "$TMP/k/package/." "$TOOLS/kotlin/"
  chmod +x "$TOOLS/kotlin/bin/"* 2>/dev/null || true
fi

if [ -z "${JAVA_HOME:-}" ] || [ ! -x "${JAVA_HOME:-/nonexistent}/bin/java" ]; then
  if command -v java >/dev/null 2>&1; then
    export JAVA_HOME="$(dirname "$(dirname "$(command -v java)")")"
  else
    echo "▸ دانلود JDK (jdk4py از pypi)"
    python3 -m pip download jdk4py --no-deps -d "$TMP/jdk" -q
    mkdir -p "$TMP/jdkx" "$TOOLS/jdk"
    python3 -m zipfile -e "$TMP"/jdk/*.whl "$TMP/jdkx"
    cp -R "$TMP/jdkx/jdk4py/java-runtime/." "$TOOLS/jdk/"
    chmod -R +x "$TOOLS/jdk/bin" 2>/dev/null || true
    export JAVA_HOME="$TOOLS/jdk"
  fi
fi

echo
echo "✓ JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1
echo "✓ aapt2: $("$TOOLS/aapt2" version | head -1)"
echo
echo "حالا بساز:  JAVA_HOME=$JAVA_HOME ANDROID_TOOLS=$TOOLS bash android-qari/build_apk.sh"
