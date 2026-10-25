# Phase 6 — Original-directory output

Phase 6 places each validated MP4 beside the source that was moved.

## Placement rules

- MediaStore sources use the recorded `RELATIVE_PATH` and the same MediaStore volume. On Android 9 and lower, the recorded legacy `DATA` parent is used.
- SAF sources use the exact parent directory URI captured while scanning the tree.
- The USB destination URI is never used as the output location.
- MediaStore outputs are inserted as `video/mp4` with `IS_PENDING=1`, written, then published with `IS_PENDING=0` only after the write completes.
- SAF outputs are written to a uniquely named temporary document, length-checked, and renamed to the final `.mp4` name.
- Existing names never get overwritten. The UI offers Rename, Skip, and Cancel.
- Missing grants, missing source-directory metadata, failed writes, and finalization errors become `NEEDS_ATTENTION`; there is no fallback folder.
- The validated USB original remains untouched.

The output placement implementation is in `OriginalOutputRepository`. The Phase 5 cache artifact is deleted only after the original-directory placement succeeds or the user explicitly skips/cancels it.

## Verification boundary

The placement logic and persistence are implemented, but real acceptance clips and Android-device verification are intentionally not claimed yet. The current CI workflow remains the build authority; the app has not been run on a device in this workspace.
