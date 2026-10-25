# Video Mover

Android app that moves videos to a USB drive, verifies them, converts a 480p or 720p MP4, places it beside the original source, queues long-running work through WorkManager, and requires a pre-flight confirmation. Phases 0–8 are implemented in this tree.

Move only. There is no copy mode. Output resolutions are 480p and 720p only.

## What is implemented

Phase 9 adds actionable failure messages, cancellation-safe repository handling, and CI test-result artifacts. See [docs/PHASE9_QUALITY.md](docs/PHASE9_QUALITY.md).

- Compatibility notes and the SDK decision: [docs/PHASE0_COMPATIBILITY.md](docs/PHASE0_COMPATIBILITY.md)
- State machine, Room row, and engine choice: [docs/PHASE1_ARCHITECTURE.md](docs/PHASE1_ARCHITECTURE.md)
- USB destination rules: [docs/PHASE3_DESTINATION.md](docs/PHASE3_DESTINATION.md)
- Video library: MediaStore query, SAF "Add folder", grid and list, search, sort, multi-select
- SAF-added videos retain their exact source parent directory URI when available
- USB destination: one SAF tree, persisted grant, filesystem and free-space warnings, no silent fallback
- Phase 4 transfer core: Room records, temp-file streaming, SHA-256 read-back verification, collision refusal, and source deletion only after verification
- Phase 5 conversion: Media3 H.264/AAC MP4 export, rotation-aware proportional sizing, no-upscale behavior, output validation, and one-at-a-time conversion
- Phase 6 output placement: exact MediaStore/SAF original-directory output, pending MediaStore publishing, temp-file SAF writes, collision choices, and no fallback folder
- Phase 7 background processing: WorkManager foreground worker, progress/cancel notification, persisted recovery reconciliation, and safe interruption handling
- Phase 8 pre-flight/UI flow: 480p/720p choice, USB and phone-space estimates, blocking warnings, explicit batch confirmation, sequential queueing, and durable results screen
- Phase 9 error catalog, cancellation safety, actionable results, troubleshooting guidance, and CI test-result artifacts
- Pure tests for selection, sorting, the state machine, and destination warnings

Not implemented: device-only verification and real acceptance clips. The current queue processes files sequentially; Phases 4–8 are documented in [docs/PHASE4_TRANSFER.md](docs/PHASE4_TRANSFER.md), [docs/PHASE5_CONVERSION.md](docs/PHASE5_CONVERSION.md), [docs/PHASE6_OUTPUT.md](docs/PHASE6_OUTPUT.md), [docs/PHASE7_BACKGROUND.md](docs/PHASE7_BACKGROUND.md), and [docs/PHASE8_PREFLIGHT.md](docs/PHASE8_PREFLIGHT.md).

## Build an APK

This project was not built into an APK here. The environment had JDK 17 and network, and did not have the Android SDK.

1. Install Android Studio with JDK 17 and Android SDK 35.
2. Open this `android` directory (the folder that contains `settings.gradle.kts`).
3. Let Studio write `local.properties` with `sdk.dir`. Do not pass `-PskipApp`.
4. Run `./gradlew :core:test` for the unit tests.
5. Run the `app` configuration on the phone.

`minSdk` is 28 so an unconfirmed Galaxy A30s still on Android 9 is included. `targetSdk` and `compileSdk` are 35. Say which phone you actually have if that should be narrowed.

Versions: Gradle 8.11.1, Kotlin 2.0.21, Android Gradle Plugin 8.7.2, Compose BOM 2024.12.01.

## Verification status

Phase 10 final report: [docs/PHASE10_FINAL_REPORT.md](docs/PHASE10_FINAL_REPORT.md). No APK was generated in this workspace; the expected successful artifact is `app/build/outputs/apk/debug/app-debug.apk` under the Android project.

The repository contains pure JVM tests for library behavior, the state machine, and destination rules. The historical report in [docs/PHASE2_TEST_RESULTS.txt](docs/PHASE2_TEST_RESULTS.txt) records 20 manually executed tests passing; the current GitHub workflow is the authoritative fresh run because this environment cannot run the Gradle wrapper reliably.

No test has run on a Galaxy A72 or A30s. The USB picker has not been opened on a device. The target phone still needs to be confirmed before narrowing `minSdk` or documenting device-specific behavior.
