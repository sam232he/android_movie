package com.videomover.transfer

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

private const val BUFFER_SIZE = 128 * 1024

enum class CollisionAction {
    ASK,
    RENAME,
    SKIP,
    CANCEL,
}

data class CollisionResolution(
    val action: CollisionAction,
    val replacementName: String? = null,
)

enum class SourceDeleteResult {
    DELETED,
    NEEDS_CONFIRMATION,
    FAILED,
    DISCONNECTED,
}

interface TransferSource {
    val uri: String
    val name: String
    fun open(): InputStream
    fun delete(): SourceDeleteResult
}

interface TransferDestination {
    /** Returns the existing final document URI, if any. */
    fun find(name: String): String?

    /** Creates a new, empty document. Implementations must never overwrite. */
    fun createTemp(name: String): String

    fun openForWrite(uri: String): OutputStream
    fun openForRead(uri: String): InputStream

    /** Renames a temp document to a final name without replacing an existing document. */
    fun renameTemp(uri: String, finalName: String): String

    fun deleteTemp(uri: String): Boolean
}

sealed class TransferResult {
    data class Verified(
        val destinationUri: String,
        val finalName: String,
        val sourceSha256: String,
        val destinationSha256: String,
    ) : TransferResult()

    data class Completed(
        val destinationUri: String,
        val finalName: String,
        val sourceSha256: String,
        val destinationSha256: String,
    ) : TransferResult()

    data class NeedsAttention(
        val reason: Attention,
        val destinationUri: String? = null,
        val sourceSha256: String? = null,
        val destinationSha256: String? = null,
        val message: String,
    ) : TransferResult()

    data class Collision(
        val existingUri: String,
        val requestedName: String,
    ) : TransferResult()

    data object Skipped : TransferResult()
    data object Cancelled : TransferResult()
}

enum class Attention {
    CHECKSUM_MISMATCH,
    TRANSFER_FAILED,
    SOURCE_DELETE_FAILED,
    SOURCE_DELETE_CONFIRMATION,
    USB_DISCONNECTED,
}

/**
 * Verified move-only transfer. No source deletion occurs until the destination
 * has been read back and its SHA-256 equals the source SHA-256.
 */
class VerifiedTransfer(
    private val destination: TransferDestination,
    private val tempName: (String) -> String = { ".videomover.$it.part" },
) {
    fun move(
        source: TransferSource,
        requestedName: String = source.name,
        collision: CollisionResolution = CollisionResolution(CollisionAction.ASK),
        isCancelled: () -> Boolean = { false },
    ): TransferResult {
        if (isCancelled()) return TransferResult.Cancelled

        val existing = runCatching { destination.find(requestedName) }.getOrElse {
            return TransferResult.NeedsAttention(
                reason = Attention.TRANSFER_FAILED,
                message = "Could not check the destination: ${it.message ?: it.javaClass.simpleName}.",
            )
        }
        var finalName = requestedName
        if (existing != null) {
            when (collision.action) {
                CollisionAction.ASK -> return TransferResult.Collision(existing, requestedName)
                CollisionAction.SKIP -> return TransferResult.Skipped
                CollisionAction.CANCEL -> return TransferResult.Cancelled
                CollisionAction.RENAME -> {
                    finalName = collision.replacementName?.trim().orEmpty()
                    if (finalName.isEmpty() || destination.find(finalName) != null) {
                        return TransferResult.Collision(existing, requestedName)
                    }
                }
            }
        }

        val tempUri = runCatching { destination.createTemp(tempName(finalName)) }.getOrElse {
            return TransferResult.NeedsAttention(
                reason = if (isDisconnected(it)) Attention.USB_DISCONNECTED else Attention.TRANSFER_FAILED,
                message = "Could not create a temporary destination file: ${it.message ?: it.javaClass.simpleName}.",
            )
        }
        var keepDestination = false
        return try {
            val sourceSha = streamAndHash(source.open(), destination.openForWrite(tempUri), isCancelled)
                ?: return TransferResult.Cancelled
            if (isCancelled()) return TransferResult.Cancelled

            val destinationSha = runCatching { hash(destination.openForRead(tempUri)) }.getOrElse {
                return TransferResult.NeedsAttention(
                    reason = if (isDisconnected(it)) Attention.USB_DISCONNECTED else Attention.TRANSFER_FAILED,
                    destinationUri = tempUri,
                    sourceSha256 = sourceSha,
                    message = "Could not verify the temporary destination file: ${it.message ?: it.javaClass.simpleName}.",
                )
            }
            if (sourceSha != destinationSha) {
                return TransferResult.NeedsAttention(
                    reason = Attention.CHECKSUM_MISMATCH,
                    sourceSha256 = sourceSha,
                    destinationSha256 = destinationSha,
                    message = "Checksum verification failed. The phone source was kept.",
                )
            }
            if (isCancelled()) return TransferResult.Cancelled
            if (destination.find(finalName) != null) {
                return TransferResult.Collision(destination.find(finalName)!!, finalName)
            }
            val finalUri = runCatching { destination.renameTemp(tempUri, finalName) }.getOrElse {
                return TransferResult.NeedsAttention(
                    reason = if (isDisconnected(it)) Attention.USB_DISCONNECTED else Attention.TRANSFER_FAILED,
                    destinationUri = tempUri,
                    sourceSha256 = sourceSha,
                    destinationSha256 = destinationSha,
                    message = "Could not finalize the verified destination file: ${it.message ?: it.javaClass.simpleName}.",
                )
            }
            keepDestination = true
            when (val deleted = runCatching { source.delete() }.getOrElse { SourceDeleteResult.FAILED }) {
                SourceDeleteResult.DELETED -> TransferResult.Completed(finalUri, finalName, sourceSha, destinationSha)
                SourceDeleteResult.NEEDS_CONFIRMATION -> TransferResult.NeedsAttention(
                    reason = Attention.SOURCE_DELETE_CONFIRMATION,
                    destinationUri = finalUri,
                    sourceSha256 = sourceSha,
                    destinationSha256 = destinationSha,
                    message = "Destination verified. Android requires confirmation before deleting the phone source.",
                )
                SourceDeleteResult.DISCONNECTED -> TransferResult.NeedsAttention(
                    reason = Attention.USB_DISCONNECTED,
                    destinationUri = finalUri,
                    sourceSha256 = sourceSha,
                    destinationSha256 = destinationSha,
                    message = "Destination verified, but the USB drive disconnected before completion.",
                )
                SourceDeleteResult.FAILED -> TransferResult.NeedsAttention(
                    reason = Attention.SOURCE_DELETE_FAILED,
                    destinationUri = finalUri,
                    sourceSha256 = sourceSha,
                    destinationSha256 = destinationSha,
                    message = "Destination verified, but the phone source could not be deleted. Both copies were kept.",
                )
            }
        } catch (error: Exception) {
            TransferResult.NeedsAttention(
                reason = if (isDisconnected(error)) Attention.USB_DISCONNECTED else Attention.TRANSFER_FAILED,
                message = "Transfer failed. The phone source was kept: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            if (!keepDestination) runCatching { destination.deleteTemp(tempUri) }
        }
    }

    private fun streamAndHash(input: InputStream, output: OutputStream, isCancelled: () -> Boolean): String? {
        input.use { source ->
            output.use { target ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) return null
                    val read = source.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    target.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                }
                target.flush()
                return digest.hex()
            }
        }
    }

    private fun hash(input: InputStream): String {
        input.use { source ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
            return digest.hex()
        }
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }

    private fun isDisconnected(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("disconnected") || message.contains("removed") || message.contains("not found")
    }
}
