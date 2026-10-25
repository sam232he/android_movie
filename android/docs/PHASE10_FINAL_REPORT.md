# Phase 10 final report

## Source and build configuration

The complete Android Studio project is the `android/` directory in this repository. It includes:

- Gradle wrapper scripts: `gradlew` and `gradlew.bat`
- Gradle wrapper configuration for Gradle 8.11.1
- `settings.gradle.kts`, root build configuration, `app`, and `core` modules
- Android SDK compile/target configuration documented in `README.md`
- GitHub Actions workflow at `.github/workflows/android.yml`

The workflow runs the JVM test suite and builds a debug APK. It uploads test reports as `core-test-reports` and, when assembly succeeds, uploads `app-debug.apk` as `video-mover-debug-apk`.

## Build artifact status

No APK was produced in this workspace. The exact expected successful local artifact path is:

`android/app/build/outputs/apk/debug/app-debug.apk`

The local Gradle attempt was:

`android\\gradlew.bat :core:test -PskipApp=true --no-daemon --stacktrace`

It could not complete because the Gradle 8.11.1 distribution download from `services.gradle.org` timed out. Therefore this report does not claim a local APK build or a fresh local test pass.

## How to build reproducibly

In Android Studio, open the `android/` directory, allow Gradle to sync, install Android SDK 35, and run the `app` debug configuration. For command-line CI-equivalent verification:

```text
cd android
./gradlew :core:test -PskipApp=true --no-daemon --stacktrace
./gradlew :app:assembleDebug --no-daemon --stacktrace
```

The GitHub workflow installs Java 17, Android SDK platform/build tools 35, runs the core tests, and assembles the debug APK.

## Completed features

- MediaStore video library with search, sorting, list/grid view, and multi-select.
- SAF folder addition retaining source directory information where available.
- One explicit USB destination with persisted access, writability/free-space checks, and no silent fallback.
- Streaming transfer to a temporary destination file, SHA-256 read-back verification, collision protection, and source deletion only after verification.
- Media3 H.264/AAC MP4 conversion at 480p or 720p with proportional sizing and no upscale.
- Output finalization beside the original through MediaStore or SAF, with pending publication and collision handling.
- WorkManager foreground processing, persisted records, cancellation, recovery reconciliation, and durable results.
- Pre-flight estimates and explicit confirmation before a batch move.
- Actionable failure messages and troubleshooting documentation.

## Automated verification

The repository contains JVM tests for library selection/sorting, destination rules, the move state machine, transfer verification and failure paths, conversion sizing, pre-flight rules, and failure-message mapping. The workflow is configured to upload both HTML/XML test reports.

The current environment did not produce a fresh Phase 10 Gradle result because the wrapper distribution download timed out. Any pass/fail result from GitHub Actions must be taken from the workflow run itself.

## Device tests

Performed: none. No Android device was connected or used for this phase.

Still required: USB disconnect/reconnect, real 480p/720p conversions, portrait/landscape/rotation and silent clips, A/V sync, gallery visibility, storage exhaustion, background/lock execution, battery restrictions, process kill/restart, cancellation/recovery, and confirmation that the USB original remains intact.

## Known limitations

- The queue processes selected files sequentially.
- Android vendor battery policies may delay WorkManager execution.
- SAF and removable-storage behavior must be validated with the actual phone and USB filesystem.
- Media3 conversion behavior and output compatibility require real acceptance clips and device testing.
- No 1080p option, Copy mode, silent overwrite, or silent relocation is implemented.
