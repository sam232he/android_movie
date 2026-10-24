package com.videomover.library

import java.text.Collator
import java.util.Locale

enum class SortKey {
    NAME,
    SIZE,
    DATE_ADDED,
    DURATION,
}

enum class SortDirection {
    ASC,
    DESC,
}

enum class LibraryViewMode {
    GRID,
    LIST,
}

data class LibraryQuery(
    val search: String = "",
    val sortKey: SortKey = SortKey.DATE_ADDED,
    val direction: SortDirection = SortDirection.DESC,
)

/**
 * One playable row. [id] is the content URI so selection survives a reload
 * of the same file. Bytes of the video itself are never held here.
 */
data class VideoItem(
    val id: String,
    val uri: String,
    val fileName: String,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val dateAddedEpochSec: Long?,
    val relativePath: String?,
    val mimeType: String?,
    val accessible: Boolean,
    val metadataWarning: String?,
    val possibleDuplicate: Boolean = false,
) {
    val selectable: Boolean get() = accessible

    fun resolutionLabel(): String {
        val w = width
        val h = height
        if (w == null || h == null) return "—"
        return "${w}×${h}"
    }
}

/** A raw cursor or document row before dedupe and cleanup. */
data class VideoRow(
    val uri: String,
    val fileName: String?,
    val sizeBytes: Long?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    /** Seconds since epoch, or milliseconds if an OEM stored milliseconds. */
    val dateAddedRaw: Long?,
    val relativePath: String?,
    val mimeType: String?,
    val accessible: Boolean = true,
    val metadataWarning: String? = null,
)

data class LibraryLoadResult(
    val items: List<VideoItem>,
    val droppedRows: Int,
    val warnings: List<String>,
)

private val NAME_COLLATOR: Collator = Collator.getInstance(Locale.ROOT).apply {
    strength = Collator.PRIMARY
}

fun applyLibraryQuery(items: List<VideoItem>, query: LibraryQuery): List<VideoItem> {
    val needle = query.search.trim()
    val filtered = if (needle.isEmpty()) {
        items
    } else {
        items.filter { it.fileName.contains(needle, ignoreCase = true) }
    }
    return filtered.sortedWith(comparator(query))
}

fun toggleSelection(selected: Set<String>, id: String, item: VideoItem?): Set<String> {
    if (item == null || item.id != id || !item.selectable) return selected
    return if (id in selected) selected - id else selected + id
}

/** Adds every selectable id in the current result. Hidden selections stay selected. */
fun selectAllVisible(selected: Set<String>, visible: List<VideoItem>): Set<String> {
    return selected + visible.filter { it.selectable }.map { it.id }
}

fun deselectAll(): Set<String> = emptySet()

fun normalizeRows(rows: List<VideoRow>): LibraryLoadResult {
    var dropped = 0
    val warnings = mutableListOf<String>()
    val byUri = LinkedHashMap<String, VideoItem>()

    for (row in rows) {
        val uri = row.uri.trim()
        if (uri.isEmpty()) {
            dropped += 1
            warnings += "Dropped a row with an empty URI."
            continue
        }
        val cleaned = cleanRow(uri, row)
        val existing = byUri[uri]
        byUri[uri] = if (existing == null) cleaned else richer(existing, cleaned)
    }

    val items = flagDuplicates(byUri.values.toList())
    return LibraryLoadResult(items = items, droppedRows = dropped, warnings = warnings.distinct())
}

/** Dates above this are treated as milliseconds. 10^10 seconds is year 2286. */
internal fun normalizeDateAdded(raw: Long?): Long? {
    if (raw == null || raw <= 0L) return null
    return if (raw >= 10_000_000_000L) raw / 1000L else raw
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0L) return "—"
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    if (unit < 0) return "$bytes B"
    val text = if (value >= 100 || value == value.toLong().toDouble()) {
        value.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", value)
    }
    return "$text ${units[unit]}"
}

fun formatDuration(durationMs: Long?): String {
    if (durationMs == null || durationMs < 0L) return "—"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun cleanRow(uri: String, row: VideoRow): VideoItem {
    val notes = mutableListOf<String>()
    row.metadataWarning?.takeIf { it.isNotBlank() }?.let { notes += it }

    val name = row.fileName?.trim().orEmpty().ifEmpty {
        notes += "Missing filename."
        "(unnamed video)"
    }
    val size = when {
        row.sizeBytes == null -> null
        row.sizeBytes < 0L -> {
            notes += "Unreadable file size."
            null
        }
        else -> row.sizeBytes
    }
    val duration = when {
        row.durationMs == null -> null
        row.durationMs < 0L -> {
            notes += "Unreadable duration."
            null
        }
        else -> row.durationMs
    }
    val width = positiveOrNull(row.width)
    val height = positiveOrNull(row.height)
    if ((row.width != null && width == null) || (row.height != null && height == null)) {
        notes += "Unreadable resolution."
    }

    return VideoItem(
        id = uri,
        uri = uri,
        fileName = name,
        sizeBytes = size,
        durationMs = duration,
        width = width,
        height = height,
        dateAddedEpochSec = normalizeDateAdded(row.dateAddedRaw),
        relativePath = row.relativePath?.trim()?.ifEmpty { null },
        mimeType = row.mimeType?.trim()?.ifEmpty { null },
        accessible = row.accessible,
        metadataWarning = notes.distinct().joinToString(" ").ifEmpty { null },
    )
}

private fun positiveOrNull(value: Int?): Int? = if (value != null && value > 0) value else null

private fun richer(a: VideoItem, b: VideoItem): VideoItem {
    fun score(item: VideoItem): Int {
        var s = 0
        if (item.fileName != "(unnamed video)") s += 4
        if (item.sizeBytes != null) s += 2
        if (item.durationMs != null) s += 2
        if (item.width != null && item.height != null) s += 2
        if (item.dateAddedEpochSec != null) s += 1
        if (item.relativePath != null) s += 1
        if (item.accessible) s += 1
        return s
    }
    val primary = if (score(b) > score(a)) b else a
    val secondary = if (primary === a) b else a
    val warning = listOfNotNull(primary.metadataWarning, secondary.metadataWarning)
        .distinct()
        .joinToString(" ")
        .ifEmpty { null }
    return primary.copy(
        sizeBytes = primary.sizeBytes ?: secondary.sizeBytes,
        durationMs = primary.durationMs ?: secondary.durationMs,
        width = primary.width ?: secondary.width,
        height = primary.height ?: secondary.height,
        dateAddedEpochSec = primary.dateAddedEpochSec ?: secondary.dateAddedEpochSec,
        relativePath = primary.relativePath ?: secondary.relativePath,
        mimeType = primary.mimeType ?: secondary.mimeType,
        accessible = primary.accessible && secondary.accessible,
        metadataWarning = warning,
    )
}

private fun flagDuplicates(items: List<VideoItem>): List<VideoItem> {
    val groups = items.groupBy { Triple(it.fileName.lowercase(Locale.ROOT), it.sizeBytes, it.relativePath) }
    return items.map { item ->
        val key = Triple(item.fileName.lowercase(Locale.ROOT), item.sizeBytes, item.relativePath)
        val dup = item.sizeBytes != null && item.relativePath != null && (groups[key]?.size ?: 0) > 1
        if (dup) item.copy(possibleDuplicate = true) else item
    }
}

private fun comparator(query: LibraryQuery): Comparator<VideoItem> {
    val base = when (query.sortKey) {
        SortKey.NAME -> Comparator<VideoItem> { a, b ->
            NAME_COLLATOR.compare(a.fileName, b.fileName)
        }
        SortKey.SIZE -> nullsLastNumber(query.direction) { it.sizeBytes }
        SortKey.DATE_ADDED -> nullsLastNumber(query.direction) { it.dateAddedEpochSec }
        SortKey.DURATION -> nullsLastNumber(query.direction) { it.durationMs }
    }
    val directed = if (query.sortKey == SortKey.NAME && query.direction == SortDirection.DESC) {
        base.reversed()
    } else {
        base
    }
    return directed.thenBy { it.id }
}

private fun nullsLastNumber(
    direction: SortDirection,
    selector: (VideoItem) -> Long?,
): Comparator<VideoItem> {
    return Comparator { a, b ->
        val av = selector(a)
        val bv = selector(b)
        when {
            av == null && bv == null -> 0
            av == null -> 1
            bv == null -> -1
            direction == SortDirection.ASC -> av.compareTo(bv)
            else -> bv.compareTo(av)
        }
    }
}
