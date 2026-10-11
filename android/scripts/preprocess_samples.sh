#!/usr/bin/env bash
#
# Run Preprocessor.process on the sample notebook photos and bring the results
# back to the laptop so they can be eyeballed without poking at the phone.
#
# OpenCV is an Android .aar here, so the pipeline runs on-device via the
# instrumented test PreprocessorSampleTest. Inputs ride into the test APK as
# assets (adb-pushed files in Android/data aren't reliably readable by the app);
# outputs go to the app's external files dir, which pulls back cleanly.
#
# This script:
#   1. syncs app/src/test/resources/samples/*.{jpg,jpeg,png} -> src/androidTest/assets/samples/
#   2. builds + installs the app and test APKs,
#   3. runs the test with `am instrument` (not connectedDebugAndroidTest, which
#      reinstalls/cleans and would wipe device state mid-run),
#   4. pulls the results so <name>_pre.jpg lands next to each <name>.jpg.
#
# Usage:  cd android && scripts/preprocess_samples.sh
set -euo pipefail

cd "$(dirname "$0")/.."                       # android/
APP_ID="com.mathnotes.solver"
TEST_ID="$APP_ID.test"
RUNNER="androidx.test.runner.AndroidJUnitRunner"
TEST_CLASS="com.mathnotes.solver.image.PreprocessorSampleTest"
LOCAL_SAMPLES="app/src/test/resources/samples"
ASSET_SAMPLES="app/src/androidTest/assets/samples"
DEVICE_OUT="/sdcard/Android/data/$APP_ID/files/samples"
APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"

ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  SDK="$(grep -s '^sdk.dir=' local.properties | cut -d= -f2- || true)"
  SDK="${SDK:-$HOME/Library/Android/sdk}"
  ADB="$SDK/platform-tools/adb"
fi
[ -x "$ADB" ] || { echo "adb not found; set it on PATH or sdk.dir in local.properties" >&2; exit 1; }

if [ "$("$ADB" get-state 2>/dev/null || true)" != "device" ]; then
  echo "No device/emulator connected (adb devices). Plug in the Pixel and enable USB debugging." >&2
  exit 1
fi

# 1. Sync real inputs (skip generated *_pre.*) into the test APK's assets.
shopt -s nullglob
imgs=("$LOCAL_SAMPLES"/*.jpg "$LOCAL_SAMPLES"/*.jpeg "$LOCAL_SAMPLES"/*.png)
inputs=()
for f in "${imgs[@]}"; do
  case "$f" in *_pre.jpg|*_pre.jpeg|*_pre.png) ;; *) inputs+=("$f") ;; esac
done
[ ${#inputs[@]} -gt 0 ] || { echo "No input images in $LOCAL_SAMPLES (drop some JPEGs there first)." >&2; exit 1; }
mkdir -p "$ASSET_SAMPLES"
find "$ASSET_SAMPLES" -type f ! -name '.gitignore' -delete   # clear stale inputs, keep .gitignore
for f in "${inputs[@]}"; do cp "$f" "$ASSET_SAMPLES/"; done
echo "==> Staged ${#inputs[@]} image(s) into the test APK assets"

echo "==> Building app + instrumented-test APKs"
./gradlew -q :app:assembleDebug :app:assembleDebugAndroidTest

echo "==> Installing both APKs"
"$ADB" install -r -g "$APP_APK" >/dev/null
"$ADB" install -r "$TEST_APK" >/dev/null
"$ADB" shell rm -rf "$DEVICE_OUT" 2>/dev/null || true

echo "==> Running $TEST_CLASS (logs dimensions + KB per sample under tag PreprocSamples)"
"$ADB" shell am instrument -w -r -e class "$TEST_CLASS" "$TEST_ID/$RUNNER" | tee /tmp/proofpad_instrument.txt
if ! grep -qE "OK \(|Failures: 0" /tmp/proofpad_instrument.txt; then
  echo "WARNING: test reported a failure — see output above and 'adb logcat -s PreprocSamples'." >&2
fi

echo "==> Pulling results back to $LOCAL_SAMPLES"
"$ADB" pull "$DEVICE_OUT/." "$LOCAL_SAMPLES" >/dev/null 2>&1 || true
echo "Done. Compare each foo.jpg with its foo_pre.jpg in $LOCAL_SAMPLES/"
