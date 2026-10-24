# Phase 0 — Compatibility

Checked 20 October 2026. The phone was not confirmed, so the project targets both devices that have been named.

## Build capability

**An APK cannot be built in this environment. No.**

| Tool | Result |
| --- | --- |
| JDK | Present. `openjdk 17.0.20.1` (Debian). `javac` matches. |
| Kotlin compiler on `PATH` | Missing. Gradle downloaded Kotlin 2.0.21 while compiling the `core` module. |
| Gradle on `PATH` | Missing. Gradle 8.11.1 was downloaded and the wrapper was generated (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`). |
| Android SDK | Missing. `ANDROID_HOME` and `ANDROID_SDK_ROOT` are unset. No `sdkmanager`, no `adb`. |
| Network | Present. `https://dl.google.com` returned HTTP 200. Maven Central resolved the Kotlin test dependencies. |

The `app` module was not compiled. There is no APK anywhere in this project. Install and run it from Android Studio using the steps in [README.md](../README.md).

`./gradlew :core:test` compiled, then failed before any test method ran: the Gradle test worker could not connect to its local socket (`Connection refused`, and `Unexpected type tag 71` on the daemon channel). The classes that compile produced were executed in one JVM with JUnit Platform Console 1.10.1. Result: **20 tests, 0 failed**. Output is in [PHASE2_TEST_RESULTS.txt](PHASE2_TEST_RESULTS.txt). That command is not a device test and not an APK build.

## Devices

| | Galaxy A72 | Galaxy A30s |
| --- | --- | --- |
| Launch | Android 11 (2021) | Android 9 (2019) |
| Last major OS update | Android 14, 6 January 2024. No further OS upgrades. | Android 11. No further OS upgrades. |
| Chip | Snapdragon 720G (Adreno 618) | Exynos 7904 is the usual listing; at least one spec table says Exynos 7885. Either way it is a weaker encoder than the A72. |
| USB OTG | Yes | Yes |

**SDK choice, because the phone is still unconfirmed:** `minSdk 28`, `targetSdk 35`, `compileSdk 35`.

minSdk 28 keeps an A30s that was never updated off Android 9 inside the supported set. If the only phone is an A72, minSdk can later move to 30 without a behavior change for that phone. Do not raise it until the phone is confirmed.

## Storage by Android version

The app targets API 35, so `requestLegacyExternalStorage` is ignored on every device. Scoped storage applies on Android 10 and later even if the phone once allowed the legacy flag.

| Version | What the library and a later delete must do |
| --- | --- |
| 9 (API 28) | `READ_EXTERNAL_STORAGE` and, when deletion is implemented, `WRITE_EXTERNAL_STORAGE`. A verified delete may use the file or MediaStore directly. SAF exists but is not required for the camera roll. |
| 10 (API 29) | Scoped storage. Deleting another app's video throws `RecoverableSecurityException` and needs a one-file consent intent. `RELATIVE_PATH` exists. Neither target phone shipped on 10 as its final release, but a half-updated A30s can be here. |
| 11 (API 30) | Legacy storage is gone. Other apps' videos are deleted only through `MediaStore.createDeleteRequest`, which shows a system dialog. The dialog is a foreground activity result. A service cannot confirm it. One request can list many URIs. |
| 12 (API 31–32) | Same delete dialog. `MANAGE_MEDIA` can skip later prompts. This app will not request it. The confirmation dialog stays. |
| 13 (API 33) | `READ_MEDIA_VIDEO` replaces `READ_EXTERNAL_STORAGE` for video. Delete dialog unchanged. |
| 14 (API 34) | The user can grant only some videos (`READ_MEDIA_VISUAL_USER_SELECTED`). The library must list that subset and say so. A72 on Android 14 can be in this state. |
| 15 (API 35, target) | Same media rules, plus foreground-service types when background work is added later. `mediaProcessing` is the type for a later transcode; `dataSync` covers the copy to USB. |

`MANAGE_EXTERNAL_STORAGE` will not be requested.

### Delete versus background work

`createDeleteRequest` on API 30+ always shows a system confirmation. That does not fit inside a silent batch. The designed rule:

1. Finish and verify the USB copy first.
2. Arm the delete in the database (`deleteDispatched`) before calling the API.
3. Open the system dialog from the activity. On API 28, delete directly after the same arm.
4. Only a successful result sets `sourceDeletedAt` and `SOURCE_DELETED`.
5. Denial or failure keeps both copies and sets `NEEDS_ATTENTION`.
6. If the process dies after the arm and before the result, the restart must not call delete again. The file stays `NEEDS_ATTENTION` until someone looks.

## SAF

On API 30 and higher, with this app's target, `ACTION_OPEN_DOCUMENT_TREE` cannot be granted for:

- the root of internal storage
- the root of an SD card the OEM treats as reliable
- the `Download` directory
- `Android/data` and `Android/obb` (also blocked for `ACTION_OPEN_DOCUMENT`)

A subfolder the user can still open (for example `Movies/Trips`) is allowed. A USB volume is a different volume; its tree can be granted. That picker is Phase 3 and is not in the app yet. Phase 2 uses the tree picker only as an extra **source** folder ("Add folder"), and it persists a read grant when the provider allows it.

## Where a later MP4 may be written

Decided now so Phase 6 does not invent a folder:

| Original location | How to write the MP4 |
| --- | --- |
| Shared storage with a `RELATIVE_PATH` (`DCIM/Camera/`, `Movies/Trips/`, `Download/…`) | MediaStore insert using that relative path and the new display name. On API 28, the data column / file parent is the fallback. |
| A folder the user granted with SAF | Create the document in that tree. |
| `Android/data`, a missing folder, or a denied grant | `NEEDS_ATTENTION`. Ask. Never write somewhere else. |

The USB copy is not an output location. There will be no single global output folder and no default of `DCIM/Camera` unless that was the source.

`Download` cannot be granted as a tree on API 30+, but MediaStore can still insert `Download/file.mp4`. Use MediaStore there, not SAF.

## USB filesystems

Stock Android, including these Samsung phones, generally writes FAT32 and exFAT over OTG. NTFS is often read-only or invisible to the system picker. SAF does not reliably report the filesystem type, so the app must not assume exFAT, FAT32, or NTFS.

Phase 3 rules, not built yet:

- Probe by creating and deleting a temp file. A failed probe means not writable. Do not switch destinations.
- If any selected file is 4 GiB or larger, warn that a FAT32 stick will reject it, even when the filesystem could not be identified.
- FAT32's limit is 4 GiB minus 1 byte.

## Not claimed

No APK, no install, no on-device timing, no proof of hardware encoding, no proof One UI will keep a later foreground service alive.
