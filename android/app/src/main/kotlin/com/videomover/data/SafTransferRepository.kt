package com.videomover.data

import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.app.RecoverableSecurityException
import androidx.documentfile.provider.DocumentFile
import com.videomover.library.VideoItem
import com.videomover.machine.AttentionReason
import com.videomover.machine.FileState
import com.videomover.model.MoveFileRecord
import com.videomover.model.OutputResolution
import com.videomover.transfer.Attention
import com.videomover.transfer.CollisionResolution
import com.videomover.transfer.SourceDeleteResult
import com.videomover.transfer.TransferDestination
import com.videomover.transfer.TransferResult
import com.videomover.transfer.TransferSource
import com.videomover.transfer.VerifiedTransfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class SafTransferDestination(
    private val context: Context,
    treeUri: Uri,
) : TransferDestination {
    private val root = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw IllegalStateException("The selected USB folder is not available.")
    private val resolver = context.contentResolver

    override fun find(name: String): String? = root.findFile(name)?.uri?.toString()

    override fun createTemp(name: String): String {
        if (find(name) != null) throw IllegalStateException("A temporary file with that name already exists.")
        return root.createFile("application/octet-stream", name)?.uri?.toString()
            ?: throw IllegalStateException("The USB folder could not create a temporary file.")
    }

    override fun openForWrite(uri: String): OutputStream = resolver.openOutputStream(Uri.parse(uri), "w")
        ?: throw IllegalStateException("The USB file could not be opened for writing.")

    override fun openForRead(uri: String): InputStream = resolver.openInputStream(Uri.parse(uri))
        ?: throw IllegalStateException("The USB file could not be opened for verification.")

    override fun renameTemp(uri: String, finalName: String): String {
        if (find(finalName) != null) throw IllegalStateException("A file named $finalName already exists.")
        val document = DocumentFile.fromSingleUri(context, Uri.parse(uri))
            ?: throw IllegalStateException("The temporary USB file is no longer available.")
        if (!document.renameTo(finalName)) {
            throw IllegalStateException("The verified temporary file could not be finalized.")
        }
        return document.uri.toString()
    }

    override fun deleteTemp(uri: String): Boolean =
        DocumentFile.fromSingleUri(context, Uri.parse(uri))?.delete() == true
}

class SafTransferSource(
    private val context: Context,
    override val uri: String,
    override val name: String,
) : TransferSource {
    private val resolver = context.contentResolver
    var pendingDeleteIntentSender: IntentSender? = null
        private set

    override fun open(): InputStream = resolver.openInputStream(Uri.parse(uri))
        ?: throw IllegalStateException("The phone source could not be opened.")

    override fun delete(): SourceDeleteResult {
        val source = Uri.parse(uri)
        return try {
            if (source.authority != null && source.authority!!.startsWith("media")) {
                if (Build.VERSION.SDK_INT >= 30) {
                    pendingDeleteIntentSender = MediaStore.createDeleteRequest(resolver, listOf(source)).intentSender
                    SourceDeleteResult.NEEDS_CONFIRMATION
                } else {
                    val deleted = resolver.delete(source, null, null)
                    if (deleted == 1) SourceDeleteResult.DELETED else SourceDeleteResult.FAILED
                }
            } else {
                val document = DocumentFile.fromSingleUri(context, source)
                if (document?.delete() == true) SourceDeleteResult.DELETED else SourceDeleteResult.FAILED
            }
        } catch (error: RecoverableSecurityException) {
            pendingDeleteIntentSender = error.userAction.actionIntent.intentSender
            SourceDeleteResult.NEEDS_CONFIRMATION
        } catch (_: SecurityException) {
            SourceDeleteResult.FAILED
        } catch (_: Exception) {
            SourceDeleteResult.DISCONNECTED
        }
    }
}

data class TransferRun(
    val record: MoveFileRecord,
    val result: TransferResult,
    val deleteIntentSender: IntentSender? = null,
)

/**
 * Runs one Phase 4 move and writes the durable row before any bytes move.
 * This is intentionally one file at a time; batching belongs to Phase 7/8.
 */
class SafTransferRepository(
    private val context: Context,
    private val records: MoveRecordStore,
) {
    suspend fun moveOne(
        batchId: String,
        item: VideoItem,
        destinationTreeUri: String,
        resolution: OutputResolution = OutputResolution.P480,
        collision: CollisionResolution = CollisionResolution(com.videomover.transfer.CollisionAction.ASK),
        isCancelled: () -> Boolean = { false },
    ): TransferRun = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val initial = MoveFileRecord(
            id = UUID.randomUUID().toString(),
            batchId = batchId,
            sourceUri = item.uri,
            sourceParentDirectoryUri = item.parentDirectoryUri,
            sourceRelativePath = item.relativePath,
            sourceDataPath = item.sourceDataPath,
            originalFileName = item.fileName,
            externalDestinationTreeUri = destinationTreeUri,
            destinationFileUri = null,
            destinationTempUri = null,
            outputResolution = resolution,
            sourceChecksumSha256 = null,
            destinationChecksumSha256 = null,
            checksumMatch = null,
            outputUri = null,
            state = FileState.PENDING.name,
            resumeState = null,
            errorMessage = null,
            attentionReason = null,
            sourceDeletedAtEpochMs = null,
            deleteDispatched = false,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        records.upsert(initial.copy(state = FileState.TRANSFERRING.name, updatedAtEpochMs = System.currentTimeMillis()))

        val source = SafTransferSource(context, item.uri, item.fileName)
        val result = runCatching {
            VerifiedTransfer(SafTransferDestination(context, Uri.parse(destinationTreeUri))).move(
                source = source,
                collision = collision,
                isCancelled = isCancelled,
            )
        }.getOrElse {
            if (it is CancellationException) throw it
            TransferResult.NeedsAttention(
                reason = Attention.TRANSFER_FAILED,
                message = "Transfer failed. The phone source was kept: ${it.message ?: it.javaClass.simpleName}.",
            )
        }
        val updated = recordForResult(initial, result)
        records.upsert(updated)
        TransferRun(updated, result, source.pendingDeleteIntentSender)
    }

    private fun recordForResult(initial: MoveFileRecord, result: TransferResult): MoveFileRecord {
        val now = System.currentTimeMillis()
        return when (result) {
            is TransferResult.Completed -> initial.copy(
                state = FileState.SOURCE_DELETED.name,
                destinationFileUri = result.destinationUri,
                sourceChecksumSha256 = result.sourceSha256,
                destinationChecksumSha256 = result.destinationSha256,
                checksumMatch = true,
                sourceDeletedAtEpochMs = now,
                updatedAtEpochMs = now,
            )
            is TransferResult.NeedsAttention -> initial.copy(
                state = when (result.reason) {
                    Attention.SOURCE_DELETE_CONFIRMATION -> FileState.TRANSFER_VERIFIED.name
                    Attention.CHECKSUM_MISMATCH,
                    Attention.TRANSFER_FAILED,
                    -> FileState.FAILED.name
                    Attention.SOURCE_DELETE_FAILED,
                    Attention.USB_DISCONNECTED,
                    -> FileState.NEEDS_ATTENTION.name
                },
                destinationFileUri = if (result.sourceSha256 != null && result.sourceSha256 == result.destinationSha256) {
                    result.destinationUri
                } else {
                    null
                },
                sourceChecksumSha256 = result.sourceSha256,
                destinationChecksumSha256 = result.destinationSha256,
                checksumMatch = result.sourceSha256 != null && result.sourceSha256 == result.destinationSha256,
                errorMessage = result.message,
                attentionReason = result.reason.toAttentionReason().name,
                deleteDispatched = result.reason == Attention.SOURCE_DELETE_CONFIRMATION,
                updatedAtEpochMs = now,
            )
            is TransferResult.Collision -> initial.copy(
                state = FileState.NEEDS_ATTENTION.name,
                errorMessage = "A destination file already exists. Choose rename, skip, or cancel.",
                attentionReason = AttentionReason.NAME_CONFLICT.name,
                updatedAtEpochMs = now,
            )
            TransferResult.Skipped -> initial.copy(
                state = FileState.CANCELLED.name,
                errorMessage = "Skipped by the user. Nothing was deleted.",
                updatedAtEpochMs = now,
            )
            TransferResult.Cancelled -> initial.copy(
                state = FileState.CANCELLED.name,
                errorMessage = "Cancelled. Nothing was deleted.",
                updatedAtEpochMs = now,
            )
            is TransferResult.Verified -> initial.copy(
                state = FileState.TRANSFER_VERIFIED.name,
                destinationFileUri = result.destinationUri,
                sourceChecksumSha256 = result.sourceSha256,
                destinationChecksumSha256 = result.destinationSha256,
                checksumMatch = true,
                updatedAtEpochMs = now,
            )
        }
    }
}

private fun Attention.toAttentionReason(): AttentionReason = when (this) {
    Attention.CHECKSUM_MISMATCH -> AttentionReason.CHECKSUM_MISMATCH
    Attention.TRANSFER_FAILED -> AttentionReason.OTHER
    Attention.SOURCE_DELETE_FAILED -> AttentionReason.DELETE_FAILED
    Attention.SOURCE_DELETE_CONFIRMATION -> AttentionReason.DELETE_CONFIRMATION
    Attention.USB_DISCONNECTED -> AttentionReason.USB_DISCONNECTED
}
