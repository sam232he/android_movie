# Phase 8 — Pre-flight and UI flow

Phase 8 now requires an explicit review before work is queued.

## Review flow

1. Browse and multi-select videos.
2. Choose the exact trusted USB destination.
3. Select 480p or 720p.
4. Review file count, source total, estimated converted-output total, USB free space, phone free space, destination, resolution, and warnings.
5. Confirm Move to enqueue the selected files as one sequential WorkManager chain.
6. Open Results to see each durable file state, output completion, and manual-attention messages.

The output estimate is conservative and is not treated as a guarantee; actual encoder output remains authoritative. Unknown sizes are reported as warnings instead of being presented as checked. Insufficient USB or phone space blocks confirmation.

The current chain still processes one file at a time. Device acceptance tests, including real storage pressure and final result timings, remain unrun by design.
