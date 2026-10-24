# Phase 3 — USB destination

The destination is one SAF tree the user picks with `ACTION_OPEN_DOCUMENT_TREE`. The app asks for read, write, persistable, and prefix grants, then calls `takePersistableUriPermission`. The URI is stored in `videomover_destination`. On the next launch only that URI is restored.

If it is missing, revoked, or read-only, the screen says so. It does not pick another persisted URI, internal storage, or a second stick that happens to be plugged in. Cancelling the picker keeps the previous folder. Forgetting it is a separate button and clears the saved URI without choosing a replacement.

## What is trusted

`assessDestination` in `core` is the decision. A folder is trusted only when all of these hold:

- The volume is still present.
- The grant includes write and has not been revoked.
- It is not internal storage (`primary`, or a volume Android marks non-removable).
- A write probe succeeded: create and delete an empty `.videomover-write-probe` in that folder. `DocumentFile.canWrite() == true` without a probe is not enough.
- No selected file breaks the filesystem limit below.
- Known selected sizes fit in the free space Android actually reported.

A session grant that Android will not persist is still trusted for this session, with a warning that the folder must be chosen again next time.

## Filesystem

`/proc/mounts` is read when a volume path or UUID is available. `vfat` / `msdos` are FAT32. `exfat` / `sdfat` are exFAT. `ntfs` is NTFS. `fuse` and `sdcardfs` are not treated as the disk format. If two concrete types match the same volume, the type is unknown.

| Filesystem | Rule |
| --- | --- |
| FAT32 | Block a file larger than 4,294,967,295 bytes (2^32 − 1). Block a selected file whose size is still unknown. |
| exFAT, ext4 | No 4 GiB warning. Still require the write probe. |
| NTFS | Not writable unless the probe on this stick succeeded. The next stick is not assumed from that. |
| Unknown | Do not assume exFAT. A file of 4 GiB or more is blocked. |

Free space comes from `StatFs` on the volume directory. That directory is available from API 30. On older releases, or when only a tree URI is known, free space is "not reported". A number is never invented. If the known sizes already exceed a reported free value, the destination is blocked.

## Disconnect

`ACTION_MEDIA_EJECT`, `UNMOUNTED`, `REMOVED`, `BAD_REMOVAL`, `MOUNTED`, and USB attach/detach refresh the same URI. Unplugging marks it disconnected and drops the free-space figure. Plugging a different stick does not retarget the saved URI.

The picker, the probe, and these broadcasts have not been run on a Galaxy phone yet. They are covered off-device by the pure destination rules and tests. The GitHub workflow runs the complete core test task and builds the Android app; a successful CI run is required before treating the source as build-verified.
