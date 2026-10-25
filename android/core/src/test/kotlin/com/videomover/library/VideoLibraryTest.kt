package com.videomover.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoLibraryTest {
    @Test
    fun searchMatchesFilenameOnlyAndIgnoresCase() {
        val items = listOf(
            item("1", "Beach.mkv", relativePath = "Movies/Trips/"),
            item("2", "Interview.mov", relativePath = "DCIM/Camera/"),
        )
        val found = applyLibraryQuery(items, LibraryQuery(search = "  beach "))
        assertEquals(listOf("1"), found.map { it.id })
        val byFolder = applyLibraryQuery(items, LibraryQuery(search = "DCIM"))
        assertTrue(byFolder.isEmpty())
    }

    @Test
    fun sortsByNameSizeDateAndDuration() {
        val items = listOf(
            item("b", "b.mp4", size = 20, date = 200, duration = 5_000),
            item("a", "A.mp4", size = 10, date = 300, duration = null),
            item("c", "c.mp4", size = null, date = 100, duration = 90_000),
        )

        assertEquals(
            listOf("a", "b", "c"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.NAME, direction = SortDirection.ASC)).map { it.id },
        )
        assertEquals(
            listOf("c", "b", "a"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.NAME, direction = SortDirection.DESC)).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "c"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.SIZE, direction = SortDirection.ASC)).map { it.id },
        )
        assertEquals(
            listOf("b", "a", "c"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.SIZE, direction = SortDirection.DESC)).map { it.id },
        )
        assertEquals(
            listOf("a", "b", "c"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.DATE_ADDED, direction = SortDirection.DESC)).map { it.id },
        )
        assertEquals(
            listOf("b", "c", "a"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.DURATION, direction = SortDirection.ASC)).map { it.id },
        )
    }

    @Test
    fun unknownNumbersStayLastInBothDirections() {
        val items = listOf(
            item("known", "a.mp4", size = 5),
            item("unknown", "b.mp4", size = null),
        )
        assertEquals(
            listOf("known", "unknown"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.SIZE, direction = SortDirection.ASC)).map { it.id },
        )
        assertEquals(
            listOf("known", "unknown"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.SIZE, direction = SortDirection.DESC)).map { it.id },
        )
    }

    @Test
    fun selectAllVisibleKeepsHiddenSelectionAndSkipsInaccessible() {
        val visible = listOf(
            item("keep-hidden-not-here", "nope.mp4"),
            item("ok", "ok.mp4"),
            item("bad", "bad.mp4", accessible = false),
        )
        val selected = selectAllVisible(setOf("hidden"), visible.filter { it.id != "keep-hidden-not-here" })
        assertEquals(setOf("hidden", "ok"), selected)
        assertFalse("bad" in selected)
    }

    @Test
    fun toggleAndDeselect() {
        val ok = item("ok", "ok.mp4")
        val blocked = item("bad", "bad.mp4", accessible = false)
        var selected = toggleSelection(emptySet(), "ok", ok)
        assertEquals(setOf("ok"), selected)
        selected = toggleSelection(selected, "ok", ok)
        assertTrue(selected.isEmpty())
        selected = toggleSelection(setOf("ok"), "bad", blocked)
        assertEquals(setOf("ok"), selected)
        assertTrue(deselectAll().isEmpty())
        assertEquals(setOf("ok"), toggleSelection(setOf("ok"), "missing", null))
    }

    @Test
    fun duplicateUrisCollapseToTheRicherRow() {
        val result = normalizeRows(
            listOf(
                VideoRow(uri = "content://media/1", fileName = null, sizeBytes = null, durationMs = null, width = null, height = null, dateAddedRaw = null, relativePath = null, mimeType = null),
                VideoRow(uri = "content://media/1", fileName = "Beach.mkv", sizeBytes = 10, durationMs = 1000, width = 1280, height = 720, dateAddedRaw = 1_700_000_000, relativePath = "Movies/Trips/", mimeType = "video/x-matroska"),
            ),
        )
        assertEquals(1, result.items.size)
        assertEquals(0, result.droppedRows)
        val item = result.items.single()
        assertEquals("Beach.mkv", item.fileName)
        assertEquals(10L, item.sizeBytes)
        assertEquals(1280, item.width)
        assertEquals("Movies/Trips/", item.relativePath)
        assertTrue(item.metadataWarning?.contains("Missing filename") == true)
    }

    @Test
    fun safRowsKeepTheirExactParentDirectory() {
        val result = normalizeRows(
            listOf(
                VideoRow(
                    uri = "content://tree/file/7",
                    fileName = "Trip.mov",
                    sizeBytes = 10,
                    durationMs = 100,
                    width = 640,
                    height = 480,
                    dateAddedRaw = null,
                    relativePath = null,
                    mimeType = "video/quicktime",
                    parentDirectoryUri = "content://tree/dir/trips",
                ),
            ),
        )
        assertEquals("content://tree/dir/trips", result.items.single().parentDirectoryUri)
    }

    @Test
    fun legacyMediaStorePathIsRetainedForOutputPlacement() {
        val result = normalizeRows(
            listOf(
                VideoRow(
                    uri = "content://media/legacy/7",
                    fileName = "Trip.mov",
                    sizeBytes = 10,
                    durationMs = 100,
                    width = 640,
                    height = 480,
                    dateAddedRaw = null,
                    relativePath = "DCIM/Camera/",
                    mimeType = "video/quicktime",
                    sourceDataPath = "/storage/emulated/0/DCIM/Camera/Trip.mov",
                ),
            ),
        )
        assertEquals("/storage/emulated/0/DCIM/Camera/Trip.mov", result.items.single().sourceDataPath)
    }

    @Test
    fun emptyUriIsDroppedAndBadMetadataDoesNotThrow() {
        val result = normalizeRows(
            listOf(
                VideoRow("", "x.mp4", 1, 1, 1, 1, 1, "DCIM/", "video/mp4"),
                VideoRow("content://media/2", "  ", -5, -8, 0, -1, -4, "  ", "  ", metadataWarning = "Cursor failed."),
            ),
        )
        assertEquals(1, result.droppedRows)
        assertTrue(result.warnings.any { it.contains("empty URI") })
        val item = result.items.single()
        assertEquals("(unnamed video)", item.fileName)
        assertNull(item.sizeBytes)
        assertNull(item.durationMs)
        assertNull(item.width)
        assertNull(item.height)
        assertNull(item.dateAddedEpochSec)
        assertNull(item.relativePath)
        assertNull(item.mimeType)
        assertTrue(item.metadataWarning!!.contains("Cursor failed."))
        assertTrue(item.metadataWarning!!.contains("Missing filename."))
        assertEquals("—", item.resolutionLabel())
    }

    @Test
    fun millisecondDatesAndPossibleDuplicates() {
        assertEquals(1_700_000_000L, normalizeDateAdded(1_700_000_000_000L))
        assertEquals(1_700_000_000L, normalizeDateAdded(1_700_000_000L))
        assertNull(normalizeDateAdded(0))

        val result = normalizeRows(
            listOf(
                VideoRow("content://a", "Beach.mkv", 50, 1, 640, 480, 10, "Movies/Trips/", "video/x-matroska"),
                VideoRow("content://b", "Beach.mkv", 50, 1, 640, 480, 11, "Movies/Trips/", "video/x-matroska"),
                VideoRow("content://c", "Beach.mkv", 50, 1, 640, 480, 12, "DCIM/Camera/", "video/x-matroska"),
            ),
        )
        val flagged = result.items.associateBy { it.id }
        assertTrue(flagged.getValue("content://a").possibleDuplicate)
        assertTrue(flagged.getValue("content://b").possibleDuplicate)
        assertFalse(flagged.getValue("content://c").possibleDuplicate)
    }

    @Test
    fun formatsSizeAndDuration() {
        assertEquals("—", formatBytes(null))
        assertEquals("—", formatBytes(-1))
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1 MB", formatBytes(1024 * 1024))
        assertEquals("—", formatDuration(null))
        assertEquals("0:00", formatDuration(0))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }

    @Test
    fun tieBreakIsStableById() {
        val items = listOf(
            item("z", "Same.mp4", size = 1, date = 5),
            item("a", "Same.mp4", size = 1, date = 5),
        )
        assertEquals(
            listOf("a", "z"),
            applyLibraryQuery(items, LibraryQuery(sortKey = SortKey.SIZE)).map { it.id },
        )
    }

    private fun item(
        id: String,
        name: String,
        size: Long? = 1,
        date: Long? = 1,
        duration: Long? = 1,
        relativePath: String? = "DCIM/Camera/",
        accessible: Boolean = true,
    ) = VideoItem(
        id = id,
        uri = id,
        fileName = name,
        sizeBytes = size,
        durationMs = duration,
        width = 1280,
        height = 720,
        dateAddedEpochSec = date,
        relativePath = relativePath,
        mimeType = "video/mp4",
        accessible = accessible,
        metadataWarning = null,
    )
}
