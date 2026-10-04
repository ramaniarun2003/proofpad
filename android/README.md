# Android app — Math Notes Solver

Kotlin · Jetpack Compose · CameraX · OpenCV · Retrofit/Moshi · Room · JLatexMath.

## Open it
1. Android Studio → **Open** → this `android/` folder. Accept the Gradle wrapper / SDK prompts
   (the wrapper jar is not checked in; Studio generates it, or run `gradle wrapper` once).
2. Point the app at the backend. Either add to `local.properties` (not committed):
   ```
   BACKEND_URL=http://192.168.1.42:8000/
   ```
   or change it at runtime from the **Backend** button on the capture screen.
   Emulator → `http://10.0.2.2:8000/` (the default).
3. Run on a device with a camera (a Pixel is what the report assumes). Camera permission is requested on first launch.

## Where things are
| Path | Role (report §4.1) |
|---|---|
| `ui/CaptureScreen.kt` | Capture module — CameraX preview, framing guide, gallery fallback |
| `image/Preprocessor.kt` | Preprocessing — OpenCV page detection, perspective warp, adaptive threshold |
| `data/api/ApiService.kt`, `Dtos.kt` | Networking client — the five endpoints in `docs/api.md` |
| `ui/ReviewScreen.kt` | Review & Edit — editable LaTeX with live typeset preview, topic chips |
| `ui/ResultsScreen.kt` | Results / Steps — rule names, tap-to-explain, verification badge |
| `export/PdfExporter.kt` | PDF Export — PdfDocument + JLatexMath, share sheet |
| `data/db/AppDatabase.kt` | Room — session history and demo fallback |
| `data/repository/SolverRepository.kt` | Repository between ViewModel and network/DB |
| `ui/SolverViewModel.kt` | One state object for the whole flow |

## First tasks (from the Gantt)
- **M1 (Oct 30):** capture → preprocess → `/recognize` → editable LaTeX. Everything is wired; the work
  is making `Preprocessor` robust on real notebook photos and tuning the framing guide.
- `LatexView` falls back to raw text when JLatexMath cannot parse an edit — good enough for review;
  replace with a debounce if the preview flickers while typing.
- `Preprocessor` is written against OpenCV 4.10 from Maven Central (`org.opencv:opencv`). If the
  artifact does not resolve, import the OpenCV Android SDK as a module instead (File → New → Import Module).
- Cleartext HTTP to the LAN is enabled in `res/xml/network_security_config.xml` for the demo; do not ship that config.
