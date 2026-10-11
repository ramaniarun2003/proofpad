# Preprocessor samples

Drop **5–10 JPEGs of handwritten notebook pages** here (shadows, tilt, ruled
lines welcome — that's the point). Then run the debug harness to see what
`Preprocessor.process` sends to the recognizer:

```bash
cd android
scripts/preprocess_samples.sh          # needs a connected device/emulator
```

For each `foo.jpg` you drop here, the script writes `foo_pre.jpg` next to it —
the exact JPEG the app would upload. Eyeball the pairs side by side.

## Why a device is required

OpenCV resolves here as an Android `.aar` (native libs are Android `.so` only),
so the pipeline can't run in a plain JVM unit test. The harness is therefore an
instrumented test (`app/src/androidTest/.../image/PreprocessorSampleTest.kt`)
that runs on the phone. The script stages these images into the test APK's
assets (adb-pushed files under `Android/data/<pkg>` aren't reliably readable by
the app), runs the test, and pulls the `_pre.jpg` results back here.

Photos and `_pre.jpg` outputs are git-ignored (see `.gitignore`).
