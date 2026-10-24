# Android Video Mover and MP4 Converter: Phased Implementation Plan

**How to use this file:** Complete the phases in order. Do not start a phase until the previous phase's acceptance check passes. Tick each checkbox only when the work is actually done and verified. The original project spec is the source of truth; this plan only sequences it.

---

## Global Rules (apply to every phase)

- [ ] Kotlin + Jetpack Compose. Use the Storage Access Framework (SAF) and MediaStore. Never bypass Android storage restrictions.
- [ ] **Move-only.** There is no Copy/Move mode selector anywhere.
- [ ] **Resolutions: 480p and 720p only.** 1080p must not appear anywhere in the UI or code paths.
- [ ] Never silently delete, overwrite, relocate output, or switch destinations.
- [ ] Never report success without verification.
- [ ] Never claim a build, test, physical-device check, hardware acceleration, or guaranteed background execution that has not actually happened. Show real output.
- [ ] All processing is local. No cloud uploads, no accounts.
- [ ] Data integrity takes priority over speed.

---

## Phase 0: Environment and Compatibility Research

**Goal:** Know what can be built here and what the target device allows.

### Tasks
- [ ] Check for JDK, Android SDK, Gradle, and internet access. Report exactly what is missing.
- [ ] State clearly whether an APK can be built in this environment (yes/no). If no, deliver full source plus Android Studio build instructions. Do not fake an APK or invent a download link.
- [ ] **Confirm the target device with the user.** The spec says Samsung Galaxy A72, but the user has also mentioned a Galaxy A30s. They differ in Android version, performance, and storage behavior.
- [ ] Set `minSdk` / `targetSdk` based on the confirmed device. Document behavior differences across Android 9, 10, 11, and 12+.
- [ ] Research and document these constraints before designing anything:
  - [ ] Deleting MediaStore-owned videos from other apps on Android 11+ (`MediaStore.createDeleteRequest` shows a **user confirmation dialog**). Work out how this fits batch and background processing.
  - [ ] Which folders SAF will and will not grant (storage root, Download, Android/data are restricted on newer versions).
  - [ ] Whether writing the converted MP4 via MediaStore `RELATIVE_PATH` or via a SAF tree grant is more reliable for each kind of original location.
  - [ ] USB filesystem support (exFAT, FAT32, NTFS) on the target device, and the FAT32 4 GB file-size limit.

### Acceptance check
- [ ] A written compatibility note exists, plus a clear yes/no on build capability.

---

## Phase 1: Architecture and State Machine

**Goal:** Lock down the design of everything safety-critical before writing UI.

### Tasks
- [ ] Choose the conversion engine by comparing **Media3 Transformer (MediaCodec)** against a **maintained FFmpeg-Kit fork**. Compare compatibility, speed, APK size, and licensing. Note that FFmpeg-Kit is officially retired and that libx264 builds are GPL. Document the decision and reasons.
- [ ] Define the per-file state machine:
  `PENDING → TRANSFERRING → TRANSFER_VERIFIED → SOURCE_DELETED → CONVERTING → CONVERTED`
  plus `FAILED`, `CANCELLED`, `NEEDS_ATTENTION`.
- [ ] Document every allowed transition and make each **idempotent**. A restart must never repeat a deletion.
- [ ] Design the Room database. Per file, persist:
  - source URI
  - original parent directory URI / relative path
  - original filename
  - external destination URI
  - output resolution
  - source and destination checksums and verification result
  - output URI
  - state
  - error message
- [ ] Choose the background strategy (foreground service, WorkManager, or both) and justify it.

### Acceptance check
- [ ] Architecture doc and state diagram written and reviewed **before** any UI code.

---

## Phase 2: Video Library and Selection

**Goal:** Browse and select videos safely.

### Tasks
- [ ] Query videos through MediaStore: name, size, duration, resolution, date added, thumbnail, and parent directory (`RELATIVE_PATH`).
- [ ] Build **grid** and **list** views with lazily loaded thumbnails. Never load whole video files into memory.
- [ ] Multi-select, **Select All**, **Deselect All**.
- [ ] Search by filename.
- [ ] Sort by filename, file size, date, and duration.
- [ ] Show filename, size, duration, and resolution where available.
- [ ] Handle missing files, duplicate entries, inaccessible files, and metadata errors without crashing.
- [ ] Support other user-authorized locations through SAF where technically possible.

### Acceptance check
- [ ] Unit tests for selection and sorting logic pass (real results reported).

---

## Phase 3: USB OTG Destination

**Goal:** Let the user pick and trust an external destination.

### Tasks
- [ ] Add a directory picker using `ACTION_OPEN_DOCUMENT_TREE`.
- [ ] Persist the permission (`takePersistableUriPermission`) where Android allows it.
- [ ] Display the selected destination and available capacity when accessible.
- [ ] Detect filesystem type where possible. **Do not assume** exFAT, FAT32, and NTFS are all writable.
- [ ] Warn if any selected file exceeds the FAT32 4 GB limit.
- [ ] Detect read-only or unsupported drives.
- [ ] Handle USB disconnection and permission revocation.
- [ ] **Never silently fall back to another destination.**

### Acceptance check
- [ ] Picker works, permission persists, and warnings appear for the correct cases.

---

## Phase 4: Verified Move-Only Transfer

**Goal:** Move originals to the USB drive without any chance of data loss.

### Per-file procedure (exact order)
1. [ ] Record the original parent directory and filename in the database **before touching anything**.
2. [ ] Check for a name collision on the destination. If one exists, ask the user: rename / skip / cancel. **Never overwrite.**
3. [ ] Stream the source to a **temporary name** on the external drive, computing SHA-256 of the source while streaming.
4. [ ] Read the destination back and compute its SHA-256.
5. [ ] Compare checksums. Only on a match, rename the temp file to its final name and set `TRANSFER_VERIFIED`.
6. [ ] Only then delete the source, using the correct deletion API for the Android version. Set `SOURCE_DELETED`.
7. [ ] If deletion fails, **keep both copies**, set `NEEDS_ATTENTION`, and report it.
8. [ ] On any failure, keep the original and clean up only the temp file.

### Additional requirements
- [ ] An incomplete temp file must never be treated as a completed move.
- [ ] The source must never be deleted twice after a restart.
- [ ] Continue with unrelated files after a single-file failure, where safe.

### Acceptance check
- [ ] Tests exist and pass for: checksum mismatch, failed transfer preserving the original, failed source deletion, and simulated USB disconnect.

---

## Phase 5: MP4 Conversion (480p / 720p)

**Goal:** Produce correct MP4 outputs.

### Tasks
- [ ] Output container: **MP4 only**.
- [ ] Video: H.264/AVC. Audio: AAC when an audio track exists.
- [ ] Resolutions: **854×480** or **1280×720** for landscape, with matching portrait dimensions or equivalent orientation-aware handling.
- [ ] Preserve aspect ratio. Never stretch.
- [ ] Handle rotation metadata correctly.
- [ ] Handle silent videos.
- [ ] Maintain audio/video sync.
- [ ] Never upscale a source smaller than the selected output.
- [ ] Use sensible quality-oriented encoding settings.
- [ ] Default to **one conversion at a time**.
- [ ] Conversion for a file starts only after its transfer is verified and its source handled (`SOURCE_DELETED`).
- [ ] **Never delete the verified original on the USB drive** after conversion.
- [ ] Validate each output (opens, has duration, has a video track) before marking `CONVERTED`.

### Acceptance check
- [ ] Test clips covering 480p, 720p, portrait, landscape, rotated, and silent videos convert correctly (real results reported).

---

## Phase 6: Save Converted Files to the Original Directory

**Goal:** The critical requirement. Every MP4 returns to where its source came from.

### Tasks
- [ ] Create each MP4 in the directory recorded in Phase 4, using MediaStore `RELATIVE_PATH` or a SAF tree grant, whichever Phase 0 found reliable.
- [ ] **Do not** derive the output location from the USB destination.
- [ ] **Do not** use a single global output folder or default everything to `DCIM/Camera`.
- [ ] Preserve nested folder structure where authorized APIs allow it.
- [ ] If the original folder is missing, ask permission to recreate it where possible, or report the issue.
- [ ] If access is denied, set `NEEDS_ATTENTION`, ask the user to grant access or pick a supported recovery action. **Never silently save elsewhere.**
- [ ] On filename collision (for example, `Beach.mp4` already exists), ask: rename automatically / skip conversion / cancel. **Never overwrite.**
- [ ] Make finished files visible in the media gallery (MediaStore insert or scan).

### Acceptance check
- [ ] `DCIM/Camera/VID_001.mov` → `DCIM/Camera/VID_001.mp4`
- [ ] `Movies/Trips/Beach.mkv` → `Movies/Trips/Beach.mp4`
- [ ] USB original remains untouched in both cases.

---

## Phase 7: Background Processing and Recovery

**Goal:** Long batches survive app switching, screen lock, and process death.

### Tasks
- [ ] Run the pipeline as a foreground service and/or WorkManager job.
- [ ] Persistent progress notification covering transfer and conversion, with a **Cancel** action.
- [ ] Persist all job state in the database.
- [ ] On startup, **reconcile** database state against files that actually exist. Never assume an operation succeeded because a task was scheduled.
- [ ] Detect incomplete transfers and conversions. Resume or restart them safely.
- [ ] Never repeat a destructive action after restart.
- [ ] When user input is needed (name conflict, permission, USB reconnect, delete confirmation), pause **that file only**, post a notification, and make the action available when the app is reopened.
- [ ] Safe handling of USB disconnect and permission revocation mid-job.
- [ ] Show files needing manual recovery.
- [ ] Do not claim background execution is guaranteed under all Android or One UI conditions. Document battery-optimization caveats.

### Acceptance check
- [ ] Process is killed mid-transfer and mid-conversion, and recovery is clean with no duplicate deletions.

---

## Phase 8: Pre-Flight Checks and UI Flow

**Goal:** Prevent bad batches before they start, and report results clearly.

### Tasks
- [ ] Before a batch, estimate: total selected size, expected converted-output size, free space on the USB drive, free space on the phone.
- [ ] Warn if the batch cannot reasonably complete. The phone must retain enough room for the converted outputs.
- [ ] Batch summary screen showing: file count, total size, destination, resolution, and warnings. **User must confirm.**
- [ ] Per-file result screen and overall batch summary, including errors and files requiring attention.
- [ ] Meaningful per-file error messages.

### Required user workflow
1. [ ] Open app
2. [ ] Browse (grid/list)
3. [ ] Multi-select
4. [ ] Choose USB destination
5. [ ] Choose 480p or 720p
6. [ ] Review batch summary
7. [ ] Confirm Move
8. [ ] Transfer + verify
9. [ ] Delete phone source only after verification
10. [ ] Convert
11. [ ] Save MP4 to original directory
12. [ ] Show results

---

## Phase 9: Error Handling, Testing, and Documentation

**Goal:** Prove it works, and be honest about what is unproven.

### Error cases to handle and test
- [ ] USB unavailable / disconnected
- [ ] Unsupported or read-only filesystem
- [ ] Insufficient external or internal storage
- [ ] Permission revoked
- [ ] Source deleted or changed during processing
- [ ] Unsupported input codec
- [ ] Corrupted video
- [ ] Encoder initialization failure
- [ ] Conversion failure
- [ ] Duplicate filenames
- [ ] Original directory inaccessible after move
- [ ] Failed source deletion
- [ ] Failed checksum verification
- [ ] App termination or device restart
- [ ] Cancellation
- [ ] Output finalization failure
- [ ] Filename conflict while the app is in the background

### Automated tests (off-device)
- [ ] Multi-selection and sorting
- [ ] Move-only behavior
- [ ] Successful transfer and checksum verification
- [ ] Source deletion only after verification
- [ ] Failed transfer preserves original
- [ ] Filename collisions
- [ ] State machine transitions and idempotency
- [ ] Failure paths
- [ ] Run all tests and report **real** results. Do not claim passes without output.

### Device-test checklist (requires the physical phone)
- [ ] USB disconnect during transfer
- [ ] 480p and 720p real conversions, with timing
- [ ] Portrait, landscape, rotated, and silent videos
- [ ] A/V sync check
- [ ] Output lands in the correct original directory, including nested folders
- [ ] Media gallery visibility
- [ ] Insufficient storage behavior
- [ ] Background and lock-screen execution
- [ ] One UI battery restriction behavior
- [ ] Process kill and restart recovery
- [ ] Cancellation and recovery
- [ ] USB originals remain intact after conversion

### Documentation
- [ ] README: supported Android versions, permissions, build and install steps
- [ ] Conversion engine, codec support, and licensing document
- [ ] Known limitations and troubleshooting
- [ ] Clear list of device tests performed vs. still required

---

## Phase 10: Build and Final Report

**Goal:** Deliver artifacts honestly.

### Tasks
- [ ] Include the Gradle wrapper and a reproducible build configuration.
- [ ] Build a debug APK if the environment allows. Report the **exact artifact path** and the real build output. If it cannot be built, say so and provide Android Studio build steps instead.
- [ ] Provide the complete Android Studio source project.
- [ ] Final report covering:
  - completed features
  - build artifacts and exact locations
  - automated test results
  - device tests performed
  - device tests still required
  - unresolved limitations and known issues

### Final acceptance
- [ ] No claim in the report is unsupported by actual output.
- [ ] No 1080p option, no Copy mode, no silent overwrite, no silent relocation anywhere in the app.
