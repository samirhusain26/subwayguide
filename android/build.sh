#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
OUTPUT="${SUBWAY_APK_OUTPUT:-$ROOT/dist/SubwayBoard.apk}"
RELEASE_CERT_SHA256="4db5dc092306435fa48f70fbefe7a73d6e4095a30a2f6ee3a67af7454920abc8"

fail() { printf 'android build: %s\n' "$*" >&2; exit 1; }
[[ -d "$SDK/platforms/android-36" ]] || fail "Android API 36 platform missing from $SDK"
[[ -x "$ANDROID_DIR/gradlew" ]] || fail "Gradle wrapper missing: $ANDROID_DIR/gradlew"
[[ -n "${TMPDIR:-}" ]] || fail "run through dev-storage so Gradle uses external scratch"
JDK="${JAVA_HOME:-}"
if [[ -z "$JDK" ]]; then
  JAVA_BIN="$(command -v java || true)"
  [[ -n "$JAVA_BIN" ]] || fail "JDK 17 or 21 is required to build Android"
  JDK="$(cd "$(dirname "$JAVA_BIN")/.." && pwd)"
fi
[[ -x "$JDK/bin/java" ]] || fail "JAVA_HOME does not point to a JDK"
JAVA_MAJOR="$("$JDK/bin/java" -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -n 1)"
[[ "$JAVA_MAJOR" == 17 || "$JAVA_MAJOR" == 21 ]] || fail "JDK 17 or 21 required; JAVA_HOME reports ${JAVA_MAJOR:-unknown}"
PY311="$(uv python find 3.11 2>/dev/null || true)"
[[ -x "$PY311" ]] || fail "Python 3.11 is required by Chaquopy"

KEYSTORE="${SUBWAY_KEYSTORE:-$HOME/.local/share/subwayguide/debug.keystore}"
[[ -s "$KEYSTORE" ]] || fail "Original release keystore is missing."
[[ -n "${SUBWAY_SIGN_STORE_PASSWORD:-}" ]] || fail "Set SUBWAY_SIGN_STORE_PASSWORD from local signing credentials."
export SUBWAY_SIGN_KEY_PASSWORD="${SUBWAY_SIGN_KEY_PASSWORD:-$SUBWAY_SIGN_STORE_PASSWORD}"

JOB_ROOT="$(mktemp -d "${SUBWAY_BUILD_SCRATCH:-${TMPDIR%/}}/subwayguide-android.XXXXXX")"
PROJECT_CACHE="$JOB_ROOT/gradle-project-cache"
trap 'rm -rf "$JOB_ROOT"' EXIT

cd "$ANDROID_DIR"
JAVA_HOME="$JDK" PATH="$JDK/bin:$PATH" CHAQUOPY_BUILD_PYTHON="$PY311" \
  ./gradlew --no-daemon --project-cache-dir "$PROJECT_CACHE" \
  -PsubwayboardBuildDir="$JOB_ROOT/app" \
  -Pandroid.aapt2FromMavenOverride="$SDK/build-tools/36.0.0/aapt2" \
  --stacktrace assembleRelease
UNSIGNED="$JOB_ROOT/app/outputs/apk/release/app-release-unsigned.apk"
APK="$JOB_ROOT/SubwayBoard.apk"
[[ -s "$UNSIGNED" ]] || fail "Gradle did not produce $APK"

BUILD_TOOLS="$SDK/build-tools/36.0.0"
[[ -x "$BUILD_TOOLS/apksigner" ]] || fail "Android build-tools 36.0.0 missing from $SDK"
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE" --ks-key-alias "${SUBWAY_KEY_ALIAS:-androiddebugkey}" \
  --ks-pass env:SUBWAY_SIGN_STORE_PASSWORD --key-pass env:SUBWAY_SIGN_KEY_PASSWORD \
  --out "$APK" "$UNSIGNED"
CERTS="$("$BUILD_TOOLS/apksigner" verify --min-sdk-version 22 --print-certs "$APK")"
DIGEST="$(printf '%s\n' "$CERTS" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p')"
[[ "$DIGEST" == "$RELEASE_CERT_SHA256" ]] || fail "Release signing certificate does not match existing installations."
BADGING="$("$BUILD_TOOLS/aapt" dump badging "$APK")"
[[ "$BADGING" == *"sdkVersion:'22'"* ]] || fail "APK minSdk is not 22"
NATIVE="$(printf '%s\n' "$BADGING" | sed -n '/^native-code:/p')"
[[ "$NATIVE" == *armeabi-v7a* && "$NATIVE" == *arm64-v8a* ]] || fail "APK ABI filters are incorrect"
APK_FILES="$(unzip -Z1 "$APK")"
has_entry() { [[ "$APK_FILES" == *$'\n'"$1"$'\n'* ]]; }
has_entry 'lib/armeabi-v7a/libpython3.11.so' || fail "32-bit Python runtime missing from APK"
has_entry 'lib/arm64-v8a/libpython3.11.so' || fail "ARM64 Python runtime missing from APK"
has_entry 'assets/stations.json' || fail "bundled station directory missing from APK"
"$PY311" -I -S "$ROOT/docs/qa/host/check_package.py" "$APK"
mkdir -p "$(dirname "$OUTPUT")"
OUTPUT_TMP="$(mktemp "${OUTPUT}.XXXXXX")"
trap 'rm -rf "$JOB_ROOT"; rm -f "${OUTPUT_TMP:-}"' EXIT
install -m 0644 "$APK" "$OUTPUT_TMP"
mv -f "$OUTPUT_TMP" "$OUTPUT"
printf 'Built and verified %s\n' "$OUTPUT"
