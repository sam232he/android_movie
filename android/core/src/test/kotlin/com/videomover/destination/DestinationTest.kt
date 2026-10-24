package com.videomover.destination

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DestinationTest {
    @Test
    fun noDestinationIsNotTrustedAndNamesNothingElse() {
        val assessment = assessDestination(null, listOf(file("a", "A.mp4", 10)))
        assertEquals(DestinationStatus.NONE, assessment.status)
        assertFalse(assessment.trusted)
        assertEquals(DestinationWarningCode.NO_DESTINATION, assessment.warnings.single().code)
    }

    @Test
    fun fat32AllowsTheLastLegalByteAndBlocksOnePastIt() {
        assertEquals(FOUR_GIB_BYTES - 1, FAT32_MAX_FILE_BYTES)
        val legal = assessDestination(fat32(), listOf(file("ok", "Ok.mp4", FAT32_MAX_FILE_BYTES)))
        assertFalse(legal.warnings.any { it.code == DestinationWarningCode.FAT32_FILE_TOO_LARGE })
        assertTrue(legal.trusted)

        val over = assessDestination(fat32(), listOf(file("big", "Stage_raw.mov", FAT32_MAX_FILE_BYTES + 1)))
        assertEquals(setOf("big"), over.blockedFileIds)
        assertFalse(over.trusted)
        assertTrue(over.warnings.any { it.code == DestinationWarningCode.FAT32_FILE_TOO_LARGE && it.fileId == "big" })
    }

    @Test
    fun fat32BlocksAFileWhoseSizeIsUnknown() {
        val assessment = assessDestination(fat32(), listOf(file("mystery", "Mystery.mp4", null)))
        assertEquals(DestinationWarningCode.FAT32_SIZE_UNKNOWN, assessment.warnings.first { it.fileId == "mystery" }.code)
        assertFalse(assessment.trusted)
    }

    @Test
    fun exfatDoesNotInheritTheFat32Limit() {
        val assessment = assessDestination(
            ready(filesystemRaw = "exfat", freeBytes = 80_000_000_000),
            listOf(file("big", "Stage_raw.mov", 5_000_000_000)),
        )
        assertEquals(FilesystemKind.EXFAT, assessment.filesystem)
        assertTrue(assessment.trusted)
        assertFalse(assessment.warnings.any { it.code == DestinationWarningCode.FAT32_FILE_TOO_LARGE })
    }

    @Test
    fun filesystemsAreNotAssumedFromANameAlone() {
        assertEquals(FilesystemKind.FAT32, classifyFilesystem("vfat"))
        assertEquals(FilesystemKind.FAT32, classifyFilesystem("msdos"))
        assertEquals(FilesystemKind.EXFAT, classifyFilesystem("sdfat"))
        assertEquals(FilesystemKind.NTFS, classifyFilesystem("ntfs"))
        assertEquals(FilesystemKind.OTHER, classifyFilesystem("fuse"))
        assertEquals(FilesystemKind.OTHER, classifyFilesystem("fuseblk"))
        assertEquals(FilesystemKind.UNKNOWN, classifyFilesystem(null))
    }

    @Test
    fun ntfsWithoutASuccessfulProbeIsNotTrusted() {
        val unprobed = assessDestination(ready(filesystemRaw = "ntfs", writeProbeSucceeded = null), emptyList())
        assertEquals(Writability.UNSUPPORTED, unprobed.writability)
        assertFalse(unprobed.trusted)
        assertTrue(unprobed.warnings.any { it.code == DestinationWarningCode.WRITE_UNCONFIRMED && it.blocking })

        val failed = assessDestination(ready(filesystemRaw = "ntfs", writeProbeSucceeded = false, canWriteFlag = true), emptyList())
        assertEquals(Writability.UNSUPPORTED, failed.writability)
        assertFalse(failed.trusted)
    }

    @Test
    fun ntfsWithAProbeIsWritableButTheNextDriveIsNotAssumed() {
        val assessment = assessDestination(ready(filesystemRaw = "ntfs"), emptyList())
        assertEquals(Writability.WRITABLE, assessment.writability)
        assertTrue(assessment.trusted)
        assertEquals(DestinationStatus.WARNING, assessment.status)
        assertTrue(assessment.warnings.any { it.code == DestinationWarningCode.NTFS_WRITE_CONFIRMED && !it.blocking })
    }

    @Test
    fun exfatAndFat32AreNotTrustedUntilAWriteProbeSucceeds() {
        for (raw in listOf("exfat", "vfat")) {
            val assessment = assessDestination(ready(filesystemRaw = raw, writeProbeSucceeded = null), emptyList())
            assertFalse(assessment.trusted, raw)
            assertEquals(Writability.UNCONFIRMED, assessment.writability)
        }
    }

    @Test
    fun readOnlyProbeOrFlagBlocks() {
        val flagged = assessDestination(ready(canWriteFlag = false, writeProbeSucceeded = null), emptyList())
        assertEquals(Writability.READ_ONLY, flagged.writability)
        assertFalse(flagged.trusted)

        val probed = assessDestination(ready(filesystemRaw = "exfat", writeProbeSucceeded = false), emptyList())
        assertEquals(DestinationWarningCode.READ_ONLY, probed.warnings.single { it.blocking }.code)
    }

    @Test
    fun unknownFilesystemBlocksAFourGibFileAndNotASmallerOne() {
        val big = assessDestination(
            ready(filesystemRaw = null, freeBytes = 80_000_000_000),
            listOf(file("big", "Stage_raw.mov", FOUR_GIB_BYTES)),
        )
        assertEquals(FilesystemKind.UNKNOWN, big.filesystem)
        assertFalse(big.trusted)
        assertTrue(big.warnings.any { it.code == DestinationWarningCode.UNKNOWN_FILESYSTEM_LARGE_FILE })

        val small = assessDestination(ready(filesystemRaw = null), listOf(file("s", "Short.mp4", 20_000)))
        assertTrue(small.trusted)
        assertTrue(small.warnings.any { it.code == DestinationWarningCode.CAPACITY_UNKNOWN && !it.blocking })
    }

    @Test
    fun disagreeingMountTypesAreUnknownAndFuseAloneIsNotAFormat() {
        val text = """
            /dev/block/vold/public:8,1 /mnt/media_rw/ABCD-1234 vfat rw
            /mnt/media_rw/ABCD-1234 /storage/ABCD-1234 fuse rw
            /dev/fuse /storage/EEEE-FFFF fuse rw
            /dev/block/vold/public:8,2 /mnt/media_rw/9999-AAAA exfat rw
            /dev/block/vold/public:8,2 /mnt/media_rw/9999-AAAA ntfs rw
        """.trimIndent()
        val mounts = parseMounts(text)
        assertEquals("vfat", filesystemRawForVolume(mounts, "/storage/ABCD-1234", "ABCD-1234"))
        assertNull(filesystemRawForVolume(mounts, "/storage/EEEE-FFFF", "EEEE-FFFF"))
        assertNull(filesystemRawForVolume(mounts, null, "9999-AAAA"))
        assertEquals(FilesystemKind.FAT32, classifyFilesystem(filesystemRawForVolume(mounts, null, "ABCD-1234")))
    }

    @Test
    fun notEnoughSpaceBlocksAndUnknownCapacityDoesNotInventANumber() {
        val tight = assessDestination(
            ready(freeBytes = 1_000),
            listOf(file("a", "A.mp4", 800), file("b", "B.mp4", 800)),
        )
        assertTrue(tight.warnings.any { it.code == DestinationWarningCode.NOT_ENOUGH_SPACE && it.blocking })
        assertFalse(tight.trusted)

        val unknown = assessDestination(ready(freeBytes = null), listOf(file("a", "A.mp4", 800)))
        assertTrue(unknown.trusted)
        assertEquals(DestinationWarningCode.CAPACITY_UNKNOWN, unknown.warnings.single().code)
        assertFalse(unknown.warnings.single().message.any { it.isDigit() })
    }

    @Test
    fun disconnectAndRevokeKeepTheFolderAndBlockIt() {
        val plugged = ready()
        val gone = assessDestination(markDisconnected(plugged), listOf(file("a", "A.mp4", 10)))
        assertEquals(plugged.treeUri, markDisconnected(plugged).treeUri)
        assertEquals(DestinationWarningCode.DISCONNECTED, gone.warnings.single().code)
        assertFalse(gone.trusted)
        assertFalse(gone.warnings.any { it.code == DestinationWarningCode.FAT32_FILE_TOO_LARGE })

        val revoked = assessDestination(markRevoked(plugged), emptyList())
        assertEquals(DestinationWarningCode.PERMISSION_REVOKED, revoked.warnings.single().code)
        assertEquals(plugged.treeUri, markRevoked(plugged).treeUri)
        assertFalse(revoked.trusted)
    }

    @Test
    fun internalStorageAndMissingWriteAreBlocked() {
        val internal = assessDestination(ready(internalStorage = true, filesystemRaw = "ext4"), emptyList())
        assertEquals(DestinationWarningCode.INTERNAL_STORAGE, internal.warnings.single().code)
        assertFalse(internal.trusted)

        val readGrantOnly = assessDestination(
            ready(grantedWrite = false, grantedRead = true, persisted = false, writeProbeSucceeded = null, canWriteFlag = null),
            emptyList(),
        )
        assertEquals(DestinationWarningCode.NO_WRITE_PERMISSION, readGrantOnly.warnings.single().code)
    }

    @Test
    fun unsavedPermissionIsAWarningNotADifferentFolder() {
        val sessionOnly = assessDestination(
            ready(persisted = false, persistable = false, freeBytes = 1_000_000),
            emptyList(),
        )
        assertTrue(sessionOnly.trusted)
        assertEquals(DestinationWarningCode.PERMISSION_NOT_PERSISTED, sessionOnly.warnings.single().code)
        assertTrue(shouldPersist(ready(persisted = false, persistable = true)))
        assertFalse(shouldPersist(ready(persisted = true)))
    }

    @Test
    fun anotherConnectedDriveIsNeverSubstituted() {
        val chosen = RememberedDestination("content://usb/tree/AAAA", "USB A")
        val other = "content://usb/tree/BBBB"
        val missing = resolveRememberedDestination(chosen, setOf(other))
        assertIs<ResolvedDestination.Missing>(missing)
        assertEquals(chosen, missing.destination)
        assertNull(destinationUriToUse(missing))

        val present = resolveRememberedDestination(chosen, setOf(chosen.treeUri, other))
        assertEquals(chosen.treeUri, destinationUriToUse(present))
        assertNull(destinationUriToUse(resolveRememberedDestination(null, setOf(other))))
        assertEquals("USB A is not available. Another folder will not be chosen.", missingDestinationMessage(chosen.displayName))
    }

    @Test
    fun cancelledPickerKeepsThePreviousFolder() {
        val previous = RememberedDestination("content://usb/tree/AAAA", "USB A")
        assertEquals(previous, adoptPickerOutcome(previous, PickerOutcome(treeUri = null, displayName = "USB B")))
        val picked = adoptPickerOutcome(previous, PickerOutcome("content://usb/tree/CCCC", "USB C"))
        assertEquals("content://usb/tree/CCCC", picked?.treeUri)
        assertEquals("USB C", picked?.displayName)
    }

    @Test
    fun pickerFlagsMatchThePlatformValues() {
        assertEquals("android.intent.action.OPEN_DOCUMENT_TREE", DestinationPicker.ACTION)
        assertEquals(1 or 2 or 64 or 128, DestinationPicker.pickerFlags())
        assertEquals(1 or 2, DestinationPicker.persistFlags())
    }
}

private fun file(id: String, name: String, size: Long?) = SelectedFileSize(id, name, size)

private fun ready(
    filesystemRaw: String? = "exfat",
    grantedRead: Boolean = true,
    grantedWrite: Boolean = true,
    persisted: Boolean = true,
    persistable: Boolean = true,
    volumePresent: Boolean = true,
    permissionRevoked: Boolean = false,
    internalStorage: Boolean = false,
    canWriteFlag: Boolean? = true,
    writeProbeSucceeded: Boolean? = true,
    freeBytes: Long? = null,
    totalBytes: Long? = 128L * 1024 * 1024 * 1024,
) = VolumeFacts(
    treeUri = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3A",
    displayName = "USB Drive",
    grantedRead = grantedRead,
    grantedWrite = grantedWrite,
    persisted = persisted,
    persistable = persistable,
    volumePresent = volumePresent,
    permissionRevoked = permissionRevoked,
    internalStorage = internalStorage,
    filesystemRaw = filesystemRaw,
    canWriteFlag = canWriteFlag,
    writeProbeSucceeded = writeProbeSucceeded,
    freeBytes = freeBytes,
    totalBytes = totalBytes,
)

private fun fat32() = ready(filesystemRaw = "vfat", freeBytes = 20_000_000_000)
