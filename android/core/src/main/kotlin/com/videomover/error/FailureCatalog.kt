package com.videomover.error

import com.videomover.machine.AttentionReason

enum class FailureAction {
    RETRY,
    RECONNECT_USB,
    GRANT_PERMISSION,
    RESOLVE_FILENAME,
    REVIEW_OUTPUT,
    NONE,
}

data class UserFacingFailure(
    val title: String,
    val message: String,
    val action: FailureAction,
)

fun describeFailure(reason: AttentionReason?, detail: String? = null): UserFacingFailure {
    val failure = when (reason) {
        AttentionReason.NAME_CONFLICT -> UserFacingFailure("Filename conflict", "A file with this name already exists. Choose Rename, Skip, or Cancel.", FailureAction.RESOLVE_FILENAME)
        AttentionReason.USB_DISCONNECTED -> UserFacingFailure("USB unavailable", "Reconnect the same USB drive and check the destination again. No other drive will be selected.", FailureAction.RECONNECT_USB)
        AttentionReason.PERMISSION_REVOKED,
        AttentionReason.DELETE_CONFIRMATION -> UserFacingFailure("Permission needed", "Android permission or delete confirmation was not completed. Reopen the app and grant the requested access.", FailureAction.GRANT_PERMISSION)
        AttentionReason.OUTPUT_DIRECTORY_INACCESSIBLE -> UserFacingFailure("Original folder unavailable", "The converted file could not be finalized beside the source. The USB original remains untouched.", FailureAction.GRANT_PERMISSION)
        AttentionReason.DELETE_FAILED,
        AttentionReason.CHECKSUM_MISMATCH,
        AttentionReason.SOURCE_MISSING,
        AttentionReason.DESTINATION_NOT_WRITABLE,
        AttentionReason.OTHER,
        null -> UserFacingFailure("Move needs attention", "The operation did not reach a verified finished state. Review the file and retry only after fixing the reported condition.", FailureAction.RETRY)
    }
    return if (detail.isNullOrBlank()) failure else failure.copy(message = "${failure.message} $detail")
}
