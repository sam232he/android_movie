# Phase 7 — Background processing and recovery

Phase 7 runs the one-file pipeline through WorkManager's `CoroutineWorker` with a foreground progress notification.

## Behavior

- The Move action queues a durable unique WorkManager request. The current UI still selects one file at a time; the worker boundary is ready for later batch chaining.
- The notification covers transfer, verification, conversion, and cancellation. Its Cancel action cancels the WorkRequest.
- Each worker persists progress in Room before crossing the transfer, deletion, conversion, and output-placement boundaries.
- Android delete confirmation, filename collision, missing permissions, and USB/provider failures stop that file and post a notification. No destructive action is replayed automatically.
- On app startup, `RecoveryCoordinator` reconciles interrupted rows: uncertain transfers become `NEEDS_ATTENTION`; interrupted conversions return to `SOURCE_DELETED` for safe restart; an interrupted delete confirmation becomes `NEEDS_ATTENTION` without repeating deletion.
- The worker never treats an enqueued or scheduled operation as proof of success.

## Limitations

WorkManager and foreground execution improve survival across app switching, screen lock, and process recreation, but Android and One UI battery optimization, job quotas, provider behavior, and USB disconnects can still pause or stop work. The app does not claim background execution is guaranteed under every device policy.

The required kill-mid-transfer and kill-mid-conversion acceptance checks remain device tests and have not been run in this workspace.
