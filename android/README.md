# Video Mover

Android app that will move videos to a USB drive, verify them, then write a 480p or 720p MP4 back to the original folder. Phases 0–3 are implemented in this tree. Later phases are not.

Move only. There is no copy mode. Output resolutions are 480p and 720p only.

## What is implemented

- Compatibility notes and the SDK decision: [docs/PHASE0_COMPATIBILITY.md](docs/PHASE0_COMPATIBILITY.md)
- State machine, Room row, and engine choice: [docs/PHASE1_ARCHITECTURE.md](docs/PHASE1_ARCHITECTURE.md)
- USB destination rules: [docs/PHASE3_DESTINATION.md](docs/PHASE3_DESTINATION.md)
- Video library: MediaStore query, SAF "Add folder", grid and list, search, sort, multi-select
- SAF-added videos retain their exact source parent directory URI when available
- USB destination: one SAF tree, persisted grant, filesystem and free-space warnings, no silent fallback
- Pure tests for selection, sorting, the state machine, and destination warnings

Not implemented: transfer, delete, conversion, notifications, the batch summary, and device-only verification.

## Build an APK

This project was not built into an APK here. The environment had JDK 17 and network, and did not have the Android SDK.

1. Install Android Studio with JDK 17 and Android SDK 35.
2. Open this `android` directory (the folder that contains `settings.gradle.kts`).
3. Let Studio write `local.properties` with `sdk.dir`. Do not pass `-PskipApp`.
4. Run `./gradlew :core:test` for the unit tests.
5. Run the `app` configuration on the phone.

`minSdk` is 28 so an unconfirmed Galaxy A30s still on Android 9 is included. `targetSdk` and `compileSdk` are 35. Say which phone you actually have if that should be narrowed.

Versions: Gradle 8.11.1, Kotlin 2.1.10, Android Gradle Plugin 8.7.2, Compose BOM 2024.12.01.

## Verification status

The repository contains pure JVM tests for library behavior, the state machine, and destination rules. The historical report in [docs/PHASE2_TEST_RESULTS.txt](docs/PHASE2_TEST_RESULTS.txt) records 20 manually executed tests passing; the current GitHub workflow is the authoritative fresh run because this environment cannot run the Gradle wrapper reliably.

No test has run on a Galaxy A72 or A30s. The USB picker has not been opened on a device. The target phone still needs to be confirmed before narrowing `minSdk` or documenting device-specific behavior.
