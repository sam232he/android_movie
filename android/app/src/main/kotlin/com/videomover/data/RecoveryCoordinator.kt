package com.videomover.data

import com.videomover.machine.AttentionReason
import com.videomover.machine.FileState

data class RecoverySummary(val manualRecoveryCount: Int)

/** Reconciles durable rows after process death without replaying a destructive action. */
class RecoveryCoordinator(private val records: MoveRecordStore) {
    suspend fun reconcile(): RecoverySummary {
        var manual = 0
        for (record in records.all()) {
            val next = when (record.state) {
                // The transfer may have created bytes, but its checksum/delete boundary is unknown.
                FileState.TRANSFERRING.name -> record.copy(
                    state = FileState.NEEDS_ATTENTION.name,
                    resumeState = FileState.TRANSFERRING.name,
                    attentionReason = AttentionReason.OTHER.name,
                    errorMessage = "Transfer was interrupted. The original was not deleted; inspect the destination before retrying.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                // New batches convert the source before deletion; legacy rows may have already
                // deleted their source and must retain their old recovery state.
                FileState.CONVERTING.name -> record.copy(
                    state = if (record.sourceDeletedAtEpochMs == null) FileState.PENDING.name else FileState.SOURCE_DELETED.name,
                    resumeState = null,
                    attentionReason = null,
                    errorMessage = "Conversion was interrupted and is ready to restart.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                )
                // A delete request may have been dispatched before Android showed its confirmation UI.
                FileState.TRANSFER_VERIFIED.name -> if (record.deleteDispatched && record.sourceDeletedAtEpochMs == null) {
                    record.copy(
                        state = FileState.NEEDS_ATTENTION.name,
                        resumeState = FileState.TRANSFER_VERIFIED.name,
                        attentionReason = AttentionReason.DELETE_CONFIRMATION.name,
                        errorMessage = "Delete confirmation was interrupted. The phone source was kept; confirm deletion again from the app.",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    )
                } else record
                else -> record
            }
            if (next != record) {
                records.upsert(next)
                manual += 1
            }
        }
        return RecoverySummary(manual)
    }
}
