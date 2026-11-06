# AutoDJ

Personal Android app that automatically DJ-mixes your local music files at the pace you choose.

## Status
- **Step 1:** library scan; per-song BPM, beat grid, key (Camelot) and energy analysis;
  pace presets (Chill / Cruise / Workout / Peak); song preview with beat clicks; tempo fixes (×2, ÷2, tap tempo).
- **Step 2 (this build):** two-deck mixing engine — phrase-aligned, beatmatched 32-beat blends with bass swap, tempo nudged at most ±6% with pitch preserved and glided back afterwards, live beat fine-tuning, crossfades for songs without a steady beat; background playback with lock-screen controls.
- Step 3: pace presets + fine-tune, shuffle-by-pace, automatic next-song choice.
- Step 4: polish.

## Build
Requires JDK 17+ and the Android SDK (platform 36).

    ./gradlew :app:testDebugUnitTest   # analysis tests
    ./gradlew :app:assembleRelease     # app/build/outputs/apk/release/app-release.apk

`keystore/autodj.jks` (plus `keystore/keystore.properties`; both kept out of git) signs every build so new APKs install as updates. Keep it — losing it
means uninstalling the app (and its analysis database) before the next version can be installed.

## Layout
- `analysis/` – pure-Kotlin DSP (FFT, onset detection, tempo/beat grid, key, energy). JVM-testable.
- `audio/` – MediaCodec decoding (MP3, M4A/AAC, FLAC, OGG/Opus, WAV).
- `data/` – SQLite library + MediaStore sync, pace definitions.
- `scan/` – background analysis worker (WorkManager, foreground notification).
- `player/` – preview player with beat-click overlay.
- `ui/` – Jetpack Compose screens.
