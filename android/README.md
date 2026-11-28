# Android app — Math Notes Solver

Kotlin · Jetpack Compose · CameraX · OpenCV · Retrofit/Moshi · Room · JLatexMath.

## Open it
1. Android Studio → **Open** → this `android/` folder. Accept the Gradle wrapper / SDK prompts
   (the wrapper jar is not checked in; Studio generates it, or run `gradle wrapper` once).
2. Point the app at the backend. Either add to `local.properties` (not committed):
   ```
   BACKEND_URL=http://192.168.1.42:8000/
   ```
   or use the **Backend** button on the capture screen to enter the URL or scan a connection QR code.
   Emulator → `http://10.0.2.2:8000/` (the default).
3. Run on a device with a camera (a Pixel is what the report assumes). Camera permission is requested on first launch.

## Connect to a backend running in WSL

Run `./run.sh` from `backend/` in WSL. In a Windows terminal, check that the backend responds with
`curl.exe http://127.0.0.1:8000/health`.

- **Emulator** uses `http://10.0.2.2:8000/` by default.
- **USB phone** can use `http://127.0.0.1:8000/`. Enable USB debugging and run
  `adb reverse tcp:8000 tcp:8000` with Windows ADB.
- **Wi-Fi phone** needs a URL it can reach. Open `http://<host-wifi-ip>:8000/health` in the phone’s
  browser, then enter `http://<host-wifi-ip>:8000/` in **Backend** if the health check responds.

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

## On-device (instrumented) tests

`./gradlew :app:connectedDebugAndroidTest` installs the debug build over the app on the connected
device and **uninstalls it when it finishes**, deleting every note on that device. The task refuses
to run unless you pass `-PallowDeviceWipe`. Use an emulator or a spare phone, never the demo phone:

```
./gradlew :app:connectedDebugAndroidTest -PallowDeviceWipe
```
