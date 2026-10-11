#!/usr/bin/env bash
#
# Run Preprocessor.process on the sample notebook photos and bring the results
# back to the laptop so they can be eyeballed without poking at the phone.
#
# OpenCV is an Android .aar here, so the pipeline runs on-device via the
# instrumented test PreprocessorSampleTest. This script:
#   1. installs the debug app (creates its external files dir),
#   2. pushes app/src/test/resources/samples/*.jpg to that dir,
#   3. runs the instrumented test (writes <name>_pre.jpg on-device),
#   4. pulls the whole samples dir back, so <name>_pre.jpg lands next to <name>.jpg.
#
# Usage:  cd android && scripts/preprocess_samples.sh
set -euo pipefail

cd "$(dirname "$0")/.."                      # android/
APP_ID="com.mathnotes.solver"
LOCAL_SAMPLES="app/src/test/resources/samples"
DEVICE_SAMPLES="/sdcard/Android/data/$APP_ID/files/samples"

# Find adb: PATH, then the SDK location in local.properties, then the default.
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

shopt -s nullglob
imgs=("$LOCAL_SAMPLES"/*.jpg "$LOCAL_SAMPLES"/*.jpeg "$LOCAL_SAMPLES"/*.png)
# Drop any previously generated *_pre.* so we only push real inputs.
inputs=()
for f in "${imgs[@]}"; do
  case "$f" in *_pre.jpg|*_pre.jpeg|*_pre.png) ;; *) inputs+=("$f") ;; esac
done
[ ${#inputs[@]} -gt 0 ] || { echo "No input images in $LOCAL_SAMPLES (drop some JPEGs there first)." >&2; exit 1; }

echo "==> Installing debug app"
./gradlew -q :app:installDebug

echo "==> Pushing ${#inputs[@]} image(s) to $DEVICE_SAMPLES"
"$ADB" shell rm -rf "$DEVICE_SAMPLES" 2>/dev/null || true
"$ADB" shell mkdir -p "$DEVICE_SAMPLES"
for f in "${inputs[@]}"; do "$ADB" push "$f" "$DEVICE_SAMPLES/" >/dev/null; done

echo "==> Running PreprocessorSampleTest (logs dimensions + KB per sample)"
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.mathnotes.solver.image.PreprocessorSampleTest

echo "==> Pulling results back to $LOCAL_SAMPLES"
"$ADB" pull "$DEVICE_SAMPLES/." "$LOCAL_SAMPLES" >/dev/null
echo "Done. Compare each foo.jpg with its foo_pre.jpg in $LOCAL_SAMPLES/"
