# Video Mover Repair Plan

## Goal

Ship a safe, guided workflow:

1. Select videos.
2. Press **Next**, then choose and validate the destination folder.
3. Press **Next** to open Results immediately while conversion continues in the background.
4. Confirm source deletion, then find converted MP4 files in the original source folder.

The chosen folder stores verified copies of the original videos. After Android confirms deletion of the source files, conversion runs from those verified copies and writes converted MP4 files back to the original source folders.

## Target User Flow

### Step 1 — Select videos

- Keep the existing library, search, sorting, and selection features.
- Display selected count and total size.
- Show **Next (N selected)**; disable it until at least one accessible video is selected.
- Preserve selection when navigating back.

### Step 2 — Choose destination

- Allow a writable USB or internal-storage **subfolder** as the output destination.
- Explain that Android 11+ may not permit the storage root or `Download`; suggest a subfolder such as `Movies/Converted`.
- Persist SAF read/write permission when available and verify that the exact selected folder is writable.
- Select conversion resolution and collision policy:
  - `Auto rename` — default
  - `Skip duplicates`
  - `Review conflicts`
- Show the review summary, then enable **Next** only when permission, free space, and preflight checks pass.

### Step 3 — Results

- Start one durable batch and open Results immediately.
- Show overall progress and one persistent row per selected file.
- Each row shows source name, planned result name, phase, progress, output folder, final URI, and a recovery action if needed.
- Keep Results scoped to the new batch by default, with optional history for older batches.
- On completion, offer **Start a new move** and refresh the library.

## Current Defects and Their Fixes

### P0 — SAF final rename can fail after an otherwise valid copy

`SafTransferDestination.renameTemp` calls `DocumentFile.fromSingleUri(...).renameTo(...)`. A single-document `DocumentFile` does not reliably support document-tree operations, and an unsupported rename can throw before the existing `DocumentsContract.renameDocument` fallback is reached. The transfer then fails after copying and hashing the full file.

**Fix**

- Call `DocumentsContract.renameDocument(resolver, tempUri, finalName)` directly as the primary finalization path.
- Verify that it returns a URI and that the document’s final display name matches `finalName`.
- If rename is unavailable, create a new final SAF document, copy the temp bytes, verify the final length/checksum, then delete the temp document.
- For directory work, always use `DocumentFile.fromTreeUri`, not `fromSingleUri`.

### P0 — one failed file can stop the rest of a selected batch

The app enqueues one worker per file into one unique WorkManager chain. The worker returns `Result.failure()` for per-file collisions, delete approvals, and other recoverable conditions. A failed worker prevents later chained files from running.

**Fix**

- Replace the per-file appended worker chain with one `BatchWorker` for a batch.
- The batch worker processes files sequentially and stores each file’s result in Room.
- Per-file failures become `FAILED` or `NEEDS_ATTENTION` records; the next file still runs.
- Reserve `Result.failure()` for a batch-wide worker failure that makes no further progress possible.

### P0 — Android deletion confirmation is impossible from the current worker

On Android 10+ some MediaStore deletions require an Activity-owned system confirmation. The worker receives the need for confirmation but cannot show it. Conversion currently requires `SOURCE_DELETED`, so a verified transfer can never advance.

**Fix**

- Make deletion optional and default it to off.
- First convert and verify output in the chosen folder.
- For an opted-in deletion, set files to `WAITING_FOR_DELETE_APPROVAL` only after output verification.
- From Results, group eligible URIs into a `MediaStore.createDeleteRequest` owned by `MainActivity`; chunk large requests if necessary.
- Arm a deletion transition before launching the request; on approval mark each source deleted, and on denial leave both copies intact with a retryable state.
- Never launch, repeat, or infer deletion from a worker.

### P0 — output sizing and applied scale disagree

The sizing logic may calculate `854×480` for a 1080p source but exposes `0.5` as the scale. The converter applies that scale to a `1920×1080` frame, producing `960×540`; validation then rejects it as the wrong dimensions. This can make normal 480p/720p conversions fail.

**Fix**

- Use the actual dimension scale: `min(1f, targetShortEdge / sourceShortEdge)`.
- Prefer a Media3 effect/configuration that uses the computed output dimensions rather than a preset-based scale.
- Update tests for 1080p, 720p, 4K, portrait/rotation, and small inputs.
- Allow a small encoder-alignment tolerance during validation only if Media3 cannot produce the exact even target dimensions.

### P0 — recovery can mark a live job as interrupted

`RecoveryCoordinator.reconcile()` runs whenever the activity is created. It can change a row that a live worker still owns, for example after app reopen or rotation.

**Fix**

- Store a worker id or batch ownership marker.
- Do not reconcile rows owned by `RUNNING` or `ENQUEUED` WorkManager jobs.
- Reconcile only stale work after checking WorkManager ownership, or on a verified cold-start recovery path.

### P1 — destination and output location disagree

The selected destination must retain the verified original copy. Conversion must read that copy only after the source is deleted, then place the converted MP4 back in the original phone directory.

**Fix**

- Keep `OriginalOutputRepository` for final conversion placement in the original source directory.
- Convert the verified destination copy into an app-private temporary file, validate it, then place the verified converted MP4 beside the original source location.
- Prevent selecting the exact same folder as a selected source folder.
- When the destination is internal storage, calculate source and output space as one shared volume rather than independent USB/phone pools.

### P1 — rename and collision handling cannot reliably finish

The worker encounters a collision but cannot show the app’s old dialog. The old rename suggestion only tries `(1)`, can repeat forever, and output rename can produce `Beach.mp4 (1)` rather than `Beach (1).mp4`.

**Fix**

- Create a pure `OutputNamePlanner` in `core`.
- Convert output names to `<stem> — converted.mp4`.
- Allocate the first free name: `(1)`, `(2)`, and onward.
- Check existing destination names and names reserved by the same batch.
- Persist the planned final filename before scheduling conversion.
- Use case-insensitive collision matching where the SAF provider is ambiguous (especially FAT/exFAT-like storage).
- For rare races after planning, Results exposes Rename, Skip, or Cancel and safely resumes that file.

### P1 — temporary-file behavior is provider-sensitive

Creating temp files as `video/mp4` can cause some providers to append `.mp4` or silently alter the requested `.part` name. Two same-named items can also collide on temp names.

**Fix**

- Create temp files as `application/octet-stream`.
- Use a record-id temp name, for example `.videomover-<record-id>.part`.
- Verify the returned temporary display name when possible.
- Remove only stale temp files belonging to the same record during safe retry/recovery.

## Target Per-File Contract

1. Persist a file record and a unique name for the original copy in the chosen destination.
2. Copy and checksum-verify that original copy in the chosen destination.
3. Wait for activity-owned Android approval, then delete the original source.
4. Convert the verified destination copy to an app-private temporary MP4.
5. Validate codec, duration, dimensions, and non-zero output.
6. Place the converted MP4 in the original source directory without overwriting an existing file.
7. Persist `COMPLETED` with the converted source-directory URI.

Never overwrite a user file. Never delete a source after failed, skipped, cancelled, or unverified output.

## Data and State Changes

### Batch model

Add a durable `MoveBatch` with:

- batch id and timestamps;
- output destination tree URI and display name;
- selected resolution;
- collision policy;
- delete-originals preference;
- batch status and active worker id.

### File model

Extend `MoveFileRecord` with:

- planned output name;
- output temporary URI and final output URI;
- per-file phase/progress;
- output verification metadata;
- delete-approval status;
- ownership/work id for recovery.

Add a Room migration and keep old rows readable as legacy/recovery records.

### State-machine integration

- Add `MoveRecordStore.apply(recordId, command, reason, detail)`.
- It loads the record, runs the tested core `transition`, and persists only valid transitions.
- Remove hand-written state-string transitions from repositories, the worker, and activity code.
- Explicitly support pending, converting, completed, attention, cancellation, deletion approval, and recovery states.

## Implementation Order

1. Fix output scale/dimension handling and add tests for real input sizes.
2. Repair SAF temporary/final file handling and write verification.
3. Introduce durable batches, state-machine-backed record updates, and safe migration.
4. Replace chained per-file workers with a batch worker that continues after per-file errors.
5. Change normal output placement to the chosen output folder; keep originals by default.
6. Implement the guided Select → Destination → Results workflow and live batch-scoped Results.
7. Add filename planning, collision policy, and Results actions for post-plan races.
8. Add optional, activity-owned original deletion approval.
9. Make recovery aware of live WorkManager ownership and remove obsolete manual move/collision paths.

Each step must leave the Android project buildable and tests passing.

## Tests to Add

### Core/JVM

- Correct sizing for 1080p → 480p/720p, 4K, rotated portrait, 720p → 480p, and small videos.
- Filename planning: multiple extensions, no extension, invalid characters, long names, case conflicts, and existing `(1)` / `(2)` names.
- Output rename format: `Beach.mkv → Beach — converted.mp4` and `Beach — converted (1).mp4`.
- Same-batch name reservation and collision-policy behavior.
- Same-volume preflight space calculation.
- State transitions for output verification, deletion approval/denial, retry, and no second automatic delete.
- Batch-worker continuation when one file needs attention.
- SAF destinations that rename normally, return a different temp name, and do not support direct rename.

### Device checks

- Move three videos—including a portrait video and a duplicate name—to a permitted internal-storage subfolder on Android 12+.
- Confirm Results opens immediately after the second Next action.
- Confirm verified converted MP4s appear in the selected output folder with the planned names and correct resolution.
- Confirm originals remain with deletion off.
- Turn deletion on, approve/deny the Android prompt, and verify the exact resulting states.
- Rotate, background, and reopen during conversion; verify active records do not become falsely interrupted.
- Kill the app during writing/conversion; verify safe recovery with no duplicate source deletion.

## Completion Criteria

- The chosen destination holds verified original copies; original source folders hold the final converted MP4 files.
- The UI follows Select → Next → Destination → Next → Results.
- A rename, provider limitation, or one-file failure cannot silently stop the rest of a batch.
- No file is overwritten.
- Source deletion is opt-in, follows verified output, and requires Android approval where needed.
- Results accurately persists and explains every file’s state across restart.
