package com.videomover.machine

enum class FileState {
    PENDING,
    TRANSFERRING,
    TRANSFER_VERIFIED,
    SOURCE_DELETED,
    CONVERTING,
    CONVERTED,
    FAILED,
    CANCELLED,
    NEEDS_ATTENTION,
}

enum class AttentionReason {
    NAME_CONFLICT,
    DELETE_CONFIRMATION,
    DELETE_FAILED,
    USB_DISCONNECTED,
    PERMISSION_REVOKED,
    DESTINATION_NOT_WRITABLE,
    OUTPUT_DIRECTORY_INACCESSIBLE,
    CHECKSUM_MISMATCH,
    SOURCE_MISSING,
    OTHER,
}

enum class MoveCommand {
    START_TRANSFER,
    MARK_TRANSFER_VERIFIED,
    FAIL_TRANSFER,
    ARM_SOURCE_DELETE,
    MARK_SOURCE_DELETED,
    FAIL_SOURCE_DELETE,
    RETRY_DELETE,
    START_CONVERT,
    MARK_CONVERTED,
    FAIL_CONVERT,
    CANCEL,
    REQUIRE_ATTENTION,
    RESOLVE_ATTENTION,
    RETRY,
}

data class FileJob(
    val state: FileState = FileState.PENDING,
    val resumeState: FileState? = null,
    val sourceDeleted: Boolean = false,
    val deleteDispatched: Boolean = false,
    val errorMessage: String? = null,
    val attentionReason: AttentionReason? = null,
)

sealed class Transition {
    abstract val job: FileJob

    data class Applied(override val job: FileJob, val changed: Boolean) : Transition()
    data class Rejected(override val job: FileJob, val reason: String) : Transition()
}

/**
 * Pure, idempotent transitions for one file.
 *
 * Deletion rule: [MoveCommand.ARM_SOURCE_DELETE] may flip [FileJob.deleteDispatched]
 * at most once, and only from [FileState.TRANSFER_VERIFIED], and only when the
 * source is not already deleted. A second arm does not change the job, which is
 * how a restart refuses to delete again. [MoveCommand.MARK_SOURCE_DELETED] is
 * refused unless that arm already happened.
 */
fun transition(
    job: FileJob,
    command: MoveCommand,
    reason: AttentionReason? = null,
    detail: String? = null,
): Transition {
    return when (command) {
        MoveCommand.START_TRANSFER -> when (job.state) {
            FileState.PENDING -> apply(job.copy(state = FileState.TRANSFERRING, errorMessage = null))
            FileState.TRANSFERRING -> same(job)
            else -> reject(job, "Transfer can start only from PENDING.")
        }

        MoveCommand.MARK_TRANSFER_VERIFIED -> when (job.state) {
            FileState.TRANSFERRING -> apply(job.copy(state = FileState.TRANSFER_VERIFIED, errorMessage = null))
            FileState.TRANSFER_VERIFIED -> same(job)
            else -> reject(job, "A verified transfer can be recorded only while transferring.")
        }

        MoveCommand.FAIL_TRANSFER -> when (job.state) {
            FileState.TRANSFERRING -> apply(
                job.copy(state = FileState.FAILED, errorMessage = detail ?: "Transfer failed. Original kept."),
            )
            FileState.FAILED -> same(job)
            else -> reject(job, "Transfer failure only applies while transferring.")
        }

        MoveCommand.ARM_SOURCE_DELETE -> when {
            job.sourceDeleted -> same(job)
            job.deleteDispatched -> same(job)
            job.state == FileState.TRANSFER_VERIFIED -> apply(job.copy(deleteDispatched = true))
            else -> reject(job, "Source delete can be armed only after a verified transfer.")
        }

        MoveCommand.MARK_SOURCE_DELETED -> when {
            job.state == FileState.SOURCE_DELETED ||
                job.state == FileState.CONVERTING ||
                job.state == FileState.CONVERTED ||
                (job.sourceDeleted && job.state != FileState.TRANSFER_VERIFIED) -> {
                if (job.sourceDeleted) same(job) else apply(job.copy(sourceDeleted = true))
            }
            job.state == FileState.TRANSFER_VERIFIED && job.deleteDispatched -> apply(
                job.copy(state = FileState.SOURCE_DELETED, sourceDeleted = true, errorMessage = null),
            )
            job.state == FileState.TRANSFER_VERIFIED -> reject(
                job,
                "Refusing to record a deletion that was not armed.",
            )
            else -> reject(job, "Source deletion can be recorded only from TRANSFER_VERIFIED.")
        }

        MoveCommand.FAIL_SOURCE_DELETE -> when {
            job.sourceDeleted -> same(job)
            job.state == FileState.TRANSFER_VERIFIED && job.deleteDispatched -> apply(
                job.copy(
                    state = FileState.NEEDS_ATTENTION,
                    resumeState = FileState.TRANSFER_VERIFIED,
                    attentionReason = AttentionReason.DELETE_FAILED,
                    errorMessage = detail ?: "Source delete failed. Both copies were kept.",
                ),
            )
            else -> reject(job, "A delete failure only applies after an armed delete.")
        }

        MoveCommand.RETRY_DELETE -> when {
            job.sourceDeleted -> reject(job, "Source is already deleted. Refusing to delete again.")
            job.state == FileState.NEEDS_ATTENTION &&
                job.attentionReason == AttentionReason.DELETE_FAILED -> apply(
                job.copy(
                    state = FileState.TRANSFER_VERIFIED,
                    deleteDispatched = false,
                    resumeState = null,
                    attentionReason = null,
                    errorMessage = null,
                ),
            )
            else -> reject(job, "Delete can be retried only after a failed delete that needs attention.")
        }

        MoveCommand.START_CONVERT -> when {
            !job.sourceDeleted -> reject(job, "Conversion starts only after the phone source is deleted.")
            job.state == FileState.SOURCE_DELETED -> apply(job.copy(state = FileState.CONVERTING, errorMessage = null))
            job.state == FileState.CONVERTING -> same(job)
            else -> reject(job, "Conversion can start only from SOURCE_DELETED.")
        }

        MoveCommand.MARK_CONVERTED -> when (job.state) {
            FileState.CONVERTING -> apply(job.copy(state = FileState.CONVERTED, errorMessage = null))
            FileState.CONVERTED -> same(job)
            else -> reject(job, "Converted can be recorded only while converting.")
        }

        MoveCommand.FAIL_CONVERT -> when (job.state) {
            FileState.CONVERTING -> apply(
                job.copy(
                    state = FileState.FAILED,
                    errorMessage = detail ?: "Conversion failed. USB original was not deleted.",
                ),
            )
            FileState.FAILED -> same(job)
            else -> reject(job, "Conversion failure only applies while converting.")
        }

        MoveCommand.CANCEL -> when (job.state) {
            FileState.PENDING,
            FileState.TRANSFERRING,
            FileState.TRANSFER_VERIFIED,
            FileState.SOURCE_DELETED,
            FileState.CONVERTING,
            FileState.NEEDS_ATTENTION,
            -> apply(
                job.copy(
                    state = FileState.CANCELLED,
                    resumeState = null,
                    attentionReason = null,
                    errorMessage = detail ?: "Cancelled. Nothing was overwritten.",
                ),
            )
            FileState.CANCELLED -> same(job)
            FileState.CONVERTED -> reject(job, "A finished file cannot be cancelled.")
            FileState.FAILED -> reject(job, "A failed file stays failed until retry.")
        }

        MoveCommand.REQUIRE_ATTENTION -> when (job.state) {
            FileState.CONVERTED, FileState.CANCELLED -> reject(job, "This file is finished and cannot be paused.")
            FileState.NEEDS_ATTENTION -> {
                val next = job.copy(
                    attentionReason = reason ?: job.attentionReason,
                    errorMessage = detail ?: job.errorMessage,
                )
                if (next == job) same(job) else apply(next)
            }
            else -> {
                if (reason == null) {
                    reject(job, "Attention requires a reason.")
                } else {
                    apply(
                        job.copy(
                            state = FileState.NEEDS_ATTENTION,
                            resumeState = job.state,
                            attentionReason = reason,
                            errorMessage = detail,
                        ),
                    )
                }
            }
        }

        MoveCommand.RESOLVE_ATTENTION -> when (job.state) {
            FileState.NEEDS_ATTENTION -> {
                val back = job.resumeState
                if (back == null || back == FileState.NEEDS_ATTENTION || back == FileState.CONVERTED) {
                    reject(job, "There is no safe state to resume.")
                } else {
                    apply(
                        job.copy(
                            state = back,
                            resumeState = null,
                            attentionReason = null,
                            errorMessage = null,
                        ),
                    )
                }
            }
            else -> reject(job, "Only a file that needs attention can be resolved.")
        }

        MoveCommand.RETRY -> when (job.state) {
            FileState.FAILED, FileState.CANCELLED -> {
                val next = if (job.sourceDeleted) FileState.SOURCE_DELETED else FileState.PENDING
                apply(
                    job.copy(
                        state = next,
                        errorMessage = null,
                        attentionReason = null,
                        resumeState = null,
                        deleteDispatched = if (job.sourceDeleted) job.deleteDispatched else false,
                    ),
                )
            }
            else -> reject(job, "Retry is only available after failure or cancellation.")
        }
    }
}

/** True only on the single transition that arms a delete. Callers delete only then. */
fun deleteShouldRun(before: FileJob, result: Transition): Boolean {
    return result is Transition.Applied &&
        result.changed &&
        result.job.deleteDispatched &&
        !before.deleteDispatched &&
        !before.sourceDeleted &&
        before.state == FileState.TRANSFER_VERIFIED
}

private fun apply(job: FileJob): Transition = Transition.Applied(job, changed = true)

private fun same(job: FileJob): Transition = Transition.Applied(job, changed = false)

private fun reject(job: FileJob, reason: String): Transition = Transition.Rejected(job, reason)
