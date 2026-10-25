# Phase 9: error handling, tests, and release notes

Phase 9 is implemented in source and is intended to be verified by GitHub Actions. No Android device run is claimed here.

## Error and recovery behavior

- A disconnected or changed USB destination stops the move and reports that the same USB must be reconnected. The app does not silently choose another drive.
- Permission loss, an inaccessible original directory, and a failed source deletion remain visible in the durable result row. The original is not treated as moved unless verification and deletion both complete.
- Duplicate names are refused or require an explicit collision decision. Background work does not overwrite an existing file.
- Transfer checksum failures, missing sources, non-writable destinations, output finalization failures, conversion failures, and encoder initialization failures remain retryable attention states or failed records with the underlying detail preserved.
- Coroutine cancellation is rethrown by transfer, conversion, and output repositories so cancellation is not misreported as a normal transfer failure. WorkManager owns restart and cancellation delivery.
- Results translate known attention reasons into a short title, explanation, and next action. Raw diagnostic detail is retained below that explanation.

## Automated coverage

The `:core:test` suite covers selection and sorting, source URI propagation, destination warnings, state transitions and idempotency, checksum verification, collision handling, source-delete ordering, disconnect/failure paths, conversion sizing, pre-flight estimates, and failure-message mapping. The workflow uploads both HTML/XML test reports even when a later APK step fails.

The repository does not contain a successful local Gradle run for this phase: the available environment timed out while downloading the Gradle 8.11.1 distribution. GitHub Actions is the authoritative run and must be checked after pushing.

## Device acceptance still required

These checks are deliberately not claimed as complete: USB disconnect/reconnect, real 480p and 720p conversions, portrait/landscape/rotated and silent clips, audio/video synchronization, gallery visibility, low-storage behavior, lock/background execution, battery restrictions, kill-and-restart recovery, cancellation, and confirmation that the USB original remains intact.

## Troubleshooting

1. If the destination warning says USB unavailable, reconnect the same drive and reselect its tree through the system picker.
2. If permission is revoked, grant video access again and reopen the destination folder; do not select a different drive as a workaround.
3. If a filename conflict is shown, rename or skip the conflicting output explicitly.
4. If conversion fails, keep the original and retry after confirming the source still opens and the phone has enough free space.
5. If background work is delayed, check Android battery restrictions for the app. WorkManager is durable but cannot guarantee immediate execution under vendor power policies.

## Media and licensing note

Conversion uses AndroidX Media3 Transformer with H.264 video and AAC audio in an MP4 container. The app does not bundle a custom native codec. Dependency versions are declared in `app/build.gradle.kts`; review the resolved dependency licenses before distribution. WorkManager handles foreground execution and persisted retry/restart behavior.
