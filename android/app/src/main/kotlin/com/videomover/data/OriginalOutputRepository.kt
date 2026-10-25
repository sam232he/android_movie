package com.videomover.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.videomover.machine.AttentionReason
import com.videomover.machine.FileState
import com.videomover.model.MoveFileRecord
import com.videomover.transfer.CollisionAction
import com.videomover.transfer.CollisionResolution
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File

sealed class OutputPlacementResult {
    data class Placed(val uri: String, val fileName: String) : OutputPlacementResult()
    data class Collision(val fileName: String) : OutputPlacementResult()
    data object Skipped : OutputPlacementResult()
    data object Cancelled : OutputPlacementResult()
    data class NeedsAttention(val message: String) : OutputPlacementResult()
}

/** Writes a validated conversion beside its source; it never uses the USB tree as an output target. */
class OriginalOutputRepository(
    private val context: Context,
    private val records: MoveRecordStore,
) {
    suspend fun place(
        recordId: String,
        convertedFile: File,
        collision: CollisionResolution = CollisionResolution(CollisionAction.ASK),
    ): OutputPlacementResult = withContext(Dispatchers.IO) {
        val record = records.find(recordId) ?: return@withContext OutputPlacementResult.NeedsAttention("Move record was not found.")
        if (record.state != FileState.CONVERTED.name || !convertedFile.isFile) {
            return@withContext OutputPlacementResult.NeedsAttention("The validated conversion is no longer available.")
        }
        if (record.sourceParentDirectoryUri == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && record.sourceRelativePath.isNullOrBlank()) {
            return@withContext attention(record, "The original MediaStore directory was not recorded; no fallback folder was used.")
        }
        if (record.sourceParentDirectoryUri == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && record.sourceDataPath.isNullOrBlank()) {
            return@withContext attention(record, "The legacy original directory was not recorded; no fallback folder was used.")
        }
        val baseName = mp4Name(record.originalFileName)
        val name = when (collision.action) {
            CollisionAction.RENAME -> collision.replacementName?.takeIf { it.isNotBlank() } ?: "$baseName (1)"
            else -> baseName
        }
        try {
            val result = if (record.sourceParentDirectoryUri != null) {
                placeSaf(record, convertedFile, name, collision)
            } else {
                placeMediaStore(record, convertedFile, name, collision)
            }
            if (result is OutputPlacementResult.Placed) {
                records.upsert(
                    record.copy(
                        outputUri = result.uri,
                        errorMessage = null,
                        attentionReason = null,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                convertedFile.delete()
            } else if (result is OutputPlacementResult.Skipped || result is OutputPlacementResult.Cancelled) {
                records.upsert(
                    record.copy(
                        state = FileState.NEEDS_ATTENTION.name,
                        resumeState = FileState.CONVERTED.name,
                        attentionReason = AttentionReason.OUTPUT_DIRECTORY_INACCESSIBLE.name,
                        errorMessage = "The converted file was not placed in the original directory; the USB original remains untouched.",
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
                convertedFile.delete()
            } else if (result is OutputPlacementResult.NeedsAttention) {
                records.upsert(
                    record.copy(
                        state = FileState.NEEDS_ATTENTION.name,
                        resumeState = FileState.CONVERTED.name,
                        attentionReason = AttentionReason.OUTPUT_DIRECTORY_INACCESSIBLE.name,
                        errorMessage = result.message,
                        updatedAtEpochMs = System.currentTimeMillis(),
                    ),
                )
            }
            result
        } catch (error: SecurityException) {
            attention(record, "Original directory access was denied. Grant access again; no alternate folder was used.")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            attention(record, "Could not write beside the original video: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun placeSaf(
        record: MoveFileRecord,
        source: File,
        name: String,
        collision: CollisionResolution,
    ): OutputPlacementResult {
        val parent = DocumentFile.fromSingleUri(context, Uri.parse(record.sourceParentDirectoryUri))
            ?: return OutputPlacementResult.NeedsAttention("The original SAF directory is no longer available.")
        if (parent.findFile(name) != null) return collisionResult(name, collision)
        val tempName = ".videomover-${record.id}.mp4.part"
        if (parent.findFile(tempName) != null) return OutputPlacementResult.NeedsAttention("A previous partial output exists; it was not overwritten.")
        val temp = parent.createFile("application/octet-stream", tempName)
            ?: return OutputPlacementResult.NeedsAttention("The original SAF directory cannot create files.")
        try {
            context.contentResolver.openOutputStream(temp.uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
            } ?: error("The original SAF output could not be opened for writing.")
            if (temp.length() != source.length()) error("The original SAF output length did not verify.")
            if (!temp.renameTo(name)) error("The temporary SAF output could not be finalized.")
            return OutputPlacementResult.Placed(temp.uri.toString(), name)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    private fun placeMediaStore(
        record: MoveFileRecord,
        source: File,
        name: String,
        collision: CollisionResolution,
    ): OutputPlacementResult {
        val resolver = context.contentResolver
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val volume = runCatching { MediaStore.getVolumeName(Uri.parse(record.sourceUri)) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: MediaStore.VOLUME_EXTERNAL_PRIMARY
            MediaStore.Video.Media.getContentUri(volume)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        if (mediaStoreCollision(resolver, collection, name, record.sourceRelativePath, record.sourceDataPath)) {
            return collisionResult(name, collision)
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, requireNotNull(record.sourceRelativePath))
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                put(MediaStore.Video.Media.DATA, legacyOutputPath(record, name))
            }
        }
        val outputUri = resolver.insert(collection, values)
            ?: return OutputPlacementResult.NeedsAttention("MediaStore could not create the original-directory output.")
        try {
            resolver.openOutputStream(outputUri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output, BUFFER_SIZE) }
            } ?: error("MediaStore output could not be opened for writing.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(outputUri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
            return OutputPlacementResult.Placed(outputUri.toString(), name)
        } catch (error: Exception) {
            resolver.delete(outputUri, null, null)
            throw error
        }
    }

    private fun mediaStoreCollision(
        resolver: android.content.ContentResolver,
        collection: Uri,
        name: String,
        relativePath: String?,
        legacyPath: String?,
    ): Boolean {
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null) {
            "${MediaStore.Video.Media.DISPLAY_NAME} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} = ?"
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && legacyPath != null) {
            "${MediaStore.Video.Media.DATA} = ?"
        } else {
            "${MediaStore.Video.Media.DISPLAY_NAME} = ?"
        }
        val args = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && relativePath != null -> arrayOf(name, relativePath)
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && legacyPath != null -> arrayOf(legacyPath)
            else -> arrayOf(name)
        }
        resolver.query(collection, arrayOf(MediaStore.Video.Media._ID), selection, args, null)?.use { return it.moveToFirst() }
        return false
    }

    private fun legacyOutputPath(record: MoveFileRecord, name: String): String {
        val original = requireNotNull(record.sourceDataPath) { "The legacy source path was not recorded." }
        return File(original).parentFile?.resolve(name)?.path
            ?: error("The legacy source directory is unavailable.")
    }

    private fun collisionResult(name: String, collision: CollisionResolution): OutputPlacementResult = when (collision.action) {
        CollisionAction.ASK -> OutputPlacementResult.Collision(name)
        CollisionAction.SKIP -> OutputPlacementResult.Skipped
        CollisionAction.CANCEL -> OutputPlacementResult.Cancelled
        CollisionAction.RENAME -> OutputPlacementResult.NeedsAttention("The requested replacement name still collides: $name")
    }

    private suspend fun attention(record: MoveFileRecord, message: String): OutputPlacementResult {
        records.upsert(
            record.copy(
                state = FileState.NEEDS_ATTENTION.name,
                resumeState = FileState.CONVERTED.name,
                attentionReason = AttentionReason.OUTPUT_DIRECTORY_INACCESSIBLE.name,
                errorMessage = message,
                updatedAtEpochMs = System.currentTimeMillis(),
            ),
        )
        return OutputPlacementResult.NeedsAttention(message)
    }

    private fun mp4Name(original: String): String {
        val dot = original.lastIndexOf('.')
        return if (dot > 0) original.substring(0, dot) + ".mp4" else "$original.mp4"
    }

    private companion object { const val BUFFER_SIZE = 128 * 1024 }
}
