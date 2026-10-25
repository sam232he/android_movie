# Phase 5 — MP4 conversion

Phase 5 is implemented in the Android app and is deliberately device-independent at build time.

## Conversion contract

- Media3 Transformer is configured for MP4 with H.264/AVC video and AAC audio.
- A conversion is accepted only for a Room record in `SOURCE_DELETED`, with a recorded deletion timestamp and a verified USB `destinationFileUri`.
- The phone source URI is never passed to the converter and the verified USB original is never deleted by conversion.
- Only `480p` and `720p` are accepted. Dimensions are proportional, even-numbered, rotation-aware, and never upscale a smaller source.
- Audio is preserved when present; silent video is allowed and remains silent.
- `Mutex` serializes conversions so only one Transformer export runs at once.
- Every output is checked as an MP4 containing H.264 video, a positive video duration, and AAC audio when an audio track exists before the row becomes `CONVERTED`.

## Output boundary

The Phase 5 export is initially written to the app cache and validated there. Phase 6 then places it into the recorded original phone directory and persists the final MediaStore/SAF URI. A failed conversion or placement leaves the already-verified USB original intact.

## Automated coverage

`core` tests cover landscape 480p sizing, rotated portrait sizing, proportional sizing, and no-upscale behavior. Android instrumentation with real clips is intentionally not claimed yet because the project is currently validated through GitHub builds, not an Android device.
