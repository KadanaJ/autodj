# AutoDJ – handover for the next session

Read this first. It replaces the earlier "AutoDJ_Project_Handover.txt" (that plan was dropped).

## What AutoDJ is (agreed with the owner)
- Personal Android app (owner only, sideloaded; no Play Store, no accounts, no backend).
- Owner's phone: **Nothing Phone (4a)**, latest Android (16). minSdk 33, targetSdk 36.
- Plays **local music files only** (~1,000 songs: MP3/M4A/FLAC/OGG/WAV). Spotify / YouTube Music
  are ruled out: their terms forbid third-party mixing of their audio.
- Core feature: automatic DJ transitions — **beatmatched blends** (tempo nudged at most ±6 %, pitch
  preserved, glided back to the song's own tempo after the blend), crossfade fallback.
- **Pace control**: presets Chill (<100) / Cruise (100–118) / Workout (118–135) / Peak (135+),
  plus fine-tune; pace can change live. Tempo "feel" uses BPM + energy.
- **Shuffle** = random songs from the whole library that fit the current pace.
- Auto-built sessions only for now (no manual playlists).
- The owner is new to coding: explain plainly, give exact steps, test before handing over.

## Status
- Step 1 ✅ library scan + analysis (BPM, beat grid, downbeat, key/Camelot, energy, per-beat
  loudness), library UI, preview player with beat clicks, tempo fixes (×2, ÷2, tap tempo).
- Step 2 ✅ (v0.2.0) two-deck engine: WSOLA time stretch, transition planner (phrase-aligned
  32-beat blends, earliest-bar 16-beat blend on "Mix now"), beat-locked blending with bass swap and
  live onset alignment, crossfades, foreground playback service + media session, DJ screen.
  **Not yet run on a real device** — the cloud emulator was unusable.
- Step 3 ⏳ pace presets + fine-tune dial wired to song selection, live pace changes, shuffle by
  pace, key-aware (harmonic) next-song choice. Currently `player/LibraryFeed.kt` just chains songs
  by nearest tempo.
- Step 4 ⏳ polish.

## Immediate task on the Mac: test on the real phone
1. Build: `./gradlew :app:testDebugUnitTest :app:assembleRelease`
   (needs JDK 17+ and the Android SDK — Android Studio provides both).
2. Signing key: `keystore/` is NOT in git. The owner has `autodj.jks` and `keystore.properties` in
   Google Drive (folder "AutoDJ signing key"). Put both in `keystore/`. Builds made without it get
   a different signature and won't install over the existing app.
3. Phone via USB with USB debugging: `adb install -r app/build/outputs/apk/release/app-release.apk`
4. Watch logs: `adb logcat -s AndroidRuntime:E` plus the app's own output; check:
   - first scan completes, speed per song, no crashes, BPMs plausible on real music;
   - DJ: start, "Hear next mix", "Mix now", dropouts counter, beat alignment by ear;
   - screen off / lock-screen controls / headphone unplug pause.

## Code map
- `analysis/` pure-Kotlin DSP, JVM-tested (`app/src/test/.../analysis`).
- `engine/` pure-Kotlin mixer (Wsola, Deck, BassFilter, Limiter, TransitionPlanner, MixEngine,
  OnsetAligner), JVM-tested (`app/src/test/.../engine`). `DemoRenderTest` renders a mix to WAV when
  `AUTODJ_DEMO_DIR` is set.
- `audio/StreamDecoder.kt` MediaCodec decode; `player/DecoderSource.kt` threaded ring-buffer source.
- `player/DjController.kt` engine + AudioTrack thread; `player/DjService.kt` foreground service.
- `scan/AnalysisWorker.kt` background analysis (WorkManager).
- `data/` SQLite library (`library.db`), MediaStore sync, `Pace` enum.
- `ui/` Compose screens (Home, Library, TrackSheet, DjScreen), dark Shazam-inspired theme.

## Gotchas
- AndroidX versions are pinned to late-2025 releases: newer ones need compileSdk 37 / AGP 9.
- Bump `versionCode` for every APK given to the owner.
