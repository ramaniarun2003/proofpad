#!/usr/bin/env bash
# Stage real notebook photos into the instrumented CTC-1 crop test, run it on a
# connected device, and pull the preprocessed outputs back for eyeballing.
#
#   scripts/preprocess_samples.sh [SRC_DIR]
#
# SRC_DIR defaults to the on-device debug raws pulled to backend/files/debug
# (named "<ts>_0raw.jpg" by Preprocessor.saveDebug when "Save preprocessing
# stages" is on). If that directory has no raws — as on a fresh checkout — it
# falls back to the real captures the backend already stored under backend/files.
#
# It copies 3–5 photos into android/app/src/androidTest/assets/samples/ as
# sampleN_*.jpg, writes a samples.json with a default guide-box crop rect per
# photo (normalized fractions), then builds + runs PreprocessorSampleTest and
# pulls each "<name>_pre.jpg" into backend/files/preprocessed/.
#
# The three curated samples committed to git are left untouched unless SRC has
# its own photos; this only ADDS to the asset dir.
set -euo pipefail
cd "$(dirname "$0")/.."

SRC="${1:-backend/files/debug}"
ASSETS="android/app/src/androidTest/assets/samples"
mkdir -p "$ASSETS"

mapfile -t RAWS < <(ls "$SRC"/*_0raw.jpg 2>/dev/null | head -5 || true)
if [ "${#RAWS[@]}" -eq 0 ]; then
  echo "no *_0raw.jpg in $SRC — falling back to the largest real captures in backend/files"
  mapfile -t RAWS < <(ls -S backend/files/*.jpg 2>/dev/null | head -5)
fi
if [ "${#RAWS[@]}" -eq 0 ]; then
  echo "no photos to stage; nothing to do" >&2; exit 1
fi

# Build samples.json. A sensible default guide box: a centred horizontal band
# (matches how a single problem is framed), which has a clearly different aspect
# ratio than the full frame, so the test can tell the crop actually happened.
json="["
i=0
for raw in "${RAWS[@]}"; do
  i=$((i+1))
  dest="$ASSETS/sampleN${i}_$(basename "$raw")"
  cp "$raw" "$dest"
  [ "$i" -gt 1 ] && json="$json,"
  json="$json
  { \"file\": \"$(basename "$dest")\", \"leftFrac\": 0.08, \"topFrac\": 0.32, \"rightFrac\": 0.92, \"bottomFrac\": 0.66, \"marginFrac\": 0.0 }"
done
json="$json
]"
# Merge with any committed samples.json would be nicer; for a staging run we just
# overwrite with the newly staged set (the committed samples remain on disk).
echo "$json" > "$ASSETS/samples.json"
echo "staged ${#RAWS[@]} photo(s) into $ASSETS"

echo "running PreprocessorSampleTest on the connected device…"
( cd android && ./gradlew :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.mathnotes.solver.image.PreprocessorSampleTest )

OUT="backend/files/preprocessed"; mkdir -p "$OUT"
adb shell 'run-as com.mathnotes.solver ls files/samples 2>/dev/null || true' >/dev/null 2>&1 || true
adb pull "$(adb shell 'echo ${EXTERNAL_STORAGE:-/sdcard}')/Android/data/com.mathnotes.solver/files/samples" "$OUT" 2>/dev/null || true
echo "done — preprocessed outputs (if any) under $OUT"
