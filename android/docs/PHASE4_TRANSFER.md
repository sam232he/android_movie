# Phase 4 — Verified move-only transfer

Phase 4 is implemented in two layers:

- `core/.../transfer/VerifiedTransfer.kt` contains the platform-independent safety algorithm.
- `app/.../data/SafTransferRepository.kt` adapts that algorithm to Android `ContentResolver` and SAF `DocumentFile` objects.
- `app/.../data/MoveRecordStore.kt` persists every file as a Room row in `video_mover.db` before any bytes are copied.

## Exact order

1. Create the Room row in `PENDING`, then set it to `TRANSFERRING`.
2. Check the exact destination filename before creating anything.
3. Create a unique `.videomover.<name>.part` document on the selected USB tree.
4. Stream the phone source to the temporary document while calculating SHA-256.
5. Read the temporary document back and calculate SHA-256 again.
6. On a match, re-check collisions and rename the temporary document to the final name.
7. Only then request or perform deletion of the phone source.

The source is never deleted on transfer failure, checksum mismatch, cancellation, collision, or USB disconnect. Temporary files are cleaned up on all non-success paths. A verified final USB copy is retained if source deletion fails or Android requires user confirmation.

## Android deletion behavior

- Android 9 attempts the direct `ContentResolver.delete` operation.
- Android 10 reports that the recoverable security confirmation is required.
- Android 11+ creates a `MediaStore.createDeleteRequest` intent sender and reports confirmation required. The app does not silently delete or claim completion before the activity receives the result.

## Tests

`VerifiedTransferTest` covers successful verified movement, checksum mismatch, failed transfer, failed source deletion, USB disconnect, collision refusal, and source-preservation rules. These are JVM tests; SAF provider behavior and the system delete dialog still require later device testing.

The multi-file batch UI, foreground/background execution, conversion, and final results screen are later phases and are intentionally not included here.
