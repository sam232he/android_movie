package com.videomover.model

/**
 * Persistent per-file record. The Android app stores this as the Room move_files row.
 * Fields stay in the pure module so the state machine can enforce them without Android.
 *
 * A restart must be able to reconstruct this from the database and must not infer
 * success from the fact that work was scheduled.
 */
data class MoveFileRecord(
    val id: String,
    val batchId: String,
    val sourceUri: String,
    /** SAF tree of the original parent, when the source was authorized that way. Null for MediaStore-only files. */
    val sourceParentDirectoryUri: String?,
    /** MediaStore RELATIVE_PATH (for example "DCIM/Camera/"). Null when the platform does not provide one. */
    val sourceRelativePath: String?,
    /** Legacy MediaStore DATA path, retained for Android 9 and lower output placement. */
    val sourceDataPath: String? = null,
    val originalFileName: String,
    val externalDestinationTreeUri: String,
    val destinationFileUri: String?,
    val destinationTempUri: String?,
    val outputResolution: OutputResolution,
    val sourceChecksumSha256: String?,
    val destinationChecksumSha256: String?,
    /** Null until both checksums exist and have been compared. */
    val checksumMatch: Boolean?,
    /** Converted MP4 in the original directory. Never derived from the USB destination. */
    val outputUri: String?,
    val state: String,
    val resumeState: String?,
    val errorMessage: String?,
    val attentionReason: String?,
    /** Set only after the delete API returns success. Presence is the proof, not a scheduled task. */
    val sourceDeletedAtEpochMs: Long?,
    /**
     * Set immediately before the delete API is invoked.
     * If a restart sees this set while [sourceDeletedAtEpochMs] is still null,
     * the delete must not be issued again.
     */
    val deleteDispatched: Boolean,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
