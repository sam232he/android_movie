package com.videomover.data

import android.content.Context
import android.database.Cursor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import com.videomover.library.LibraryLoadResult
import com.videomover.library.VideoRow
import com.videomover.library.normalizeRows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads video metadata only. Thumbnails are loaded separately, one frame at a time.
 * A bad row is recorded and skipped; it must not fail the whole library.
 */
class DeviceVideoRepository(private val context: Context) {

    suspend fun loadMediaStore(): LibraryLoadResult = withContext(Dispatchers.IO) {
        try {
            normalizeRows(queryMediaStore())
        } catch (error: SecurityException) {
            LibraryLoadResult(
                items = emptyList(),
                droppedRows = 0,
                warnings = listOf(error.message ?: "Video permission was denied."),
            )
        } catch (error: Exception) {
            LibraryLoadResult(
                items = emptyList(),
                droppedRows = 0,
                warnings = listOf("Could not read videos: ${error.message ?: error.javaClass.simpleName}."),
            )
        }
    }

    suspend fun loadTree(treeUri: Uri): LibraryLoadResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext LibraryLoadResult(emptyList(), 0, listOf("That folder could not be opened."))
        if (!root.canRead()) {
            return@withContext LibraryLoadResult(emptyList(), 0, listOf("That folder is not readable."))
        }
        val rows = ArrayList<VideoRow>()
        var unreadable = 0
        walk(root, depth = 0, rows = rows, onUnreadable = { unreadable += 1 })
        val normalized = normalizeRows(rows)
        val extra = if (unreadable > 0) {
            listOf("$unreadable item${if (unreadable == 1) "" else "s"} in that folder could not be read.")
        } else {
            emptyList()
        }
        normalized.copy(
            droppedRows = normalized.droppedRows + unreadable,
            warnings = normalized.warnings + extra,
        )
    }

    private fun queryMediaStore(): List<VideoRow> {
        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = ArrayList<String>().apply {
            add(MediaStore.Video.Media._ID)
            add(MediaStore.Video.Media.DISPLAY_NAME)
            add(MediaStore.Video.Media.SIZE)
            add(MediaStore.Video.Media.DURATION)
            add(MediaStore.Video.Media.WIDTH)
            add(MediaStore.Video.Media.HEIGHT)
            add(MediaStore.Video.Media.DATE_ADDED)
            add(MediaStore.Video.Media.MIME_TYPE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.Video.Media.RELATIVE_PATH)
                add(MediaStore.MediaColumns.IS_PENDING)
            } else {
                add(MediaStore.Video.Media.DATA)
            }
        }
        val rows = ArrayList<VideoRow>()
        context.contentResolver.query(
            collection,
            projection.toTypedArray(),
            null,
            null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += try {
                    rowFromCursor(cursor, collection)
                } catch (error: Exception) {
                    VideoRow(
                        uri = "",
                        fileName = null,
                        sizeBytes = null,
                        durationMs = null,
                        width = null,
                        height = null,
                        dateAddedRaw = null,
                        relativePath = null,
                        mimeType = null,
                        accessible = false,
                        metadataWarning = "Skipped a row: ${error.message ?: error.javaClass.simpleName}.",
                    )
                }
            }
        } ?: throw IllegalStateException("MediaStore returned no cursor.")
        return rows
    }

    private fun rowFromCursor(cursor: Cursor, collection: Uri): VideoRow {
        val id = cursor.longOrNull(MediaStore.Video.Media._ID)
            ?: return VideoRow(
                uri = "",
                fileName = cursor.stringOrNull(MediaStore.Video.Media.DISPLAY_NAME),
                sizeBytes = null,
                durationMs = null,
                width = null,
                height = null,
                dateAddedRaw = null,
                relativePath = null,
                mimeType = null,
                accessible = false,
                metadataWarning = "Skipped a row with no media id.",
            )
        val uri = Uri.withAppendedPath(collection, id.toString()).toString()
        val pending = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.longOrNull(MediaStore.MediaColumns.IS_PENDING) == 1L
        } else {
            false
        }
        val relative = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            cursor.stringOrNull(MediaStore.Video.Media.RELATIVE_PATH)
        } else {
            relativeHintFromData(cursor.stringOrNull(MediaStore.Video.Media.DATA))
        }
        return VideoRow(
            uri = uri,
            fileName = cursor.stringOrNull(MediaStore.Video.Media.DISPLAY_NAME),
            sizeBytes = cursor.longOrNull(MediaStore.Video.Media.SIZE),
            durationMs = cursor.longOrNull(MediaStore.Video.Media.DURATION),
            width = cursor.longOrNull(MediaStore.Video.Media.WIDTH)?.toInt(),
            height = cursor.longOrNull(MediaStore.Video.Media.HEIGHT)?.toInt(),
            dateAddedRaw = cursor.longOrNull(MediaStore.Video.Media.DATE_ADDED),
            relativePath = relative,
            mimeType = cursor.stringOrNull(MediaStore.Video.Media.MIME_TYPE),
            accessible = !pending,
            metadataWarning = if (pending) "Still being saved by another app." else null,
        )
    }

    private fun walk(
        directory: DocumentFile,
        depth: Int,
        rows: MutableList<VideoRow>,
        onUnreadable: () -> Unit,
    ) {
        if (depth > MAX_DEPTH) return
        val children = try {
            directory.listFiles()
        } catch (error: Exception) {
            onUnreadable()
            return
        }
        for (child in children) {
            val name = child.name
            if (child.isDirectory) {
                if (name == "Android" || name == "data" || name == "obb") continue
                walk(child, depth + 1, rows, onUnreadable)
                continue
            }
            if (!child.isFile || !isVideo(child)) continue
            rows += try {
                rowFromDocument(child)
            } catch (error: Exception) {
                onUnreadable()
                VideoRow(
                    uri = "",
                    fileName = name,
                    sizeBytes = null,
                    durationMs = null,
                    width = null,
                    height = null,
                    dateAddedRaw = null,
                    relativePath = null,
                    mimeType = child.type,
                    accessible = false,
                    metadataWarning = "Could not read ${name ?: "a file"}: ${error.message ?: error.javaClass.simpleName}.",
                )
            }
        }
    }

    private fun rowFromDocument(file: DocumentFile): VideoRow {
        val uri = file.uri
        var duration: Long? = null
        var width: Int? = null
        var height: Int? = null
        var warning: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            var rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            var rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
            if (rotation == 90 || rotation == 270) {
                val swap = rawWidth
                rawWidth = rawHeight
                rawHeight = swap
            }
            width = rawWidth
            height = rawHeight
        } catch (error: Exception) {
            warning = "Metadata could not be read."
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
                // The row is still usable without a frame.
            }
        }
        val length = try {
            file.length().takeIf { it >= 0L }
        } catch (_: Exception) {
            null
        }
        return VideoRow(
            uri = uri.toString(),
            fileName = file.name,
            sizeBytes = length,
            durationMs = duration,
            width = width,
            height = height,
            dateAddedRaw = null,
            relativePath = null,
            mimeType = file.type,
            accessible = file.canRead(),
            metadataWarning = warning,
        )
    }

    private fun isVideo(file: DocumentFile): Boolean {
        val type = file.type?.lowercase()
        if (type != null && type.startsWith("video/")) return true
        val extension = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return extension in VIDEO_EXTENSIONS
    }

    private fun relativeHintFromData(data: String?): String? {
        if (data.isNullOrBlank()) return null
        val parent = java.io.File(data).parent ?: return null
        val markers = listOf("/DCIM/", "/Movies/", "/Download/", "/Pictures/")
        val marker = markers.firstOrNull { parent.contains(it) } ?: return null
        val start = parent.indexOf(marker)
        return parent.substring(start + 1).trimEnd('/') + "/"
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getLong(index)
        } catch (_: Exception) {
            null
        }
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        if (index < 0 || isNull(index)) return null
        return try {
            getString(index)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val MAX_DEPTH = 8
        val VIDEO_EXTENSIONS = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "avi", "3gp", "3gpp", "ts", "mpeg", "mpg", "wmv",
        )
    }
}
