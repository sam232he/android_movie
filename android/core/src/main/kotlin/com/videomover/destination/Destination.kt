package com.videomover.destination

/** Largest file FAT32 can store: 2^32 − 1 bytes. */
const val FAT32_MAX_FILE_BYTES: Long = 4_294_967_295L

/** 4 GiB. A file this large is unsafe when the filesystem was not identified. */
const val FOUR_GIB_BYTES: Long = 4L * 1024 * 1024 * 1024

enum class FilesystemKind {
    FAT32,
    EXFAT,
    NTFS,
    EXT4,
    OTHER,
    UNKNOWN,
}

enum class Writability {
    WRITABLE,
    READ_ONLY,
    UNSUPPORTED,
    UNCONFIRMED,
}

enum class DestinationStatus {
    NONE,
    READY,
    WARNING,
    BLOCKED,
}

enum class DestinationWarningCode {
    NO_DESTINATION,
    DISCONNECTED,
    PERMISSION_REVOKED,
    INTERNAL_STORAGE,
    NO_WRITE_PERMISSION,
    PERMISSION_NOT_PERSISTED,
    PERMISSION_NOT_SAVED,
    READ_ONLY,
    WRITE_UNCONFIRMED,
    NTFS_WRITE_CONFIRMED,
    FAT32_FILE_TOO_LARGE,
    FAT32_SIZE_UNKNOWN,
    UNKNOWN_FILESYSTEM_LARGE_FILE,
    UNKNOWN_SIZE_UNCHECKED,
    NOT_ENOUGH_SPACE,
    SPACE_NOT_FULLY_CHECKED,
    CAPACITY_UNKNOWN,
}

data class DestinationWarning(
    val code: DestinationWarningCode,
    val message: String,
    val blocking: Boolean,
    val fileId: String? = null,
)

/**
 * Facts collected from SAF and, when Android exposes them, the mounted volume.
 * Null probe or null filesystem means "not known", never "fine".
 */
data class VolumeFacts(
    val treeUri: String,
    val displayName: String,
    val grantedRead: Boolean,
    val grantedWrite: Boolean,
    val persisted: Boolean,
    val persistable: Boolean,
    val volumePresent: Boolean,
    val permissionRevoked: Boolean,
    val internalStorage: Boolean,
    val filesystemRaw: String?,
    val canWriteFlag: Boolean?,
    val writeProbeSucceeded: Boolean?,
    val freeBytes: Long?,
    val totalBytes: Long?,
)

data class SelectedFileSize(
    val id: String,
    val fileName: String,
    val sizeBytes: Long?,
)

data class DestinationAssessment(
    val status: DestinationStatus,
    val filesystem: FilesystemKind,
    val writability: Writability,
    val warnings: List<DestinationWarning>,
    val trusted: Boolean,
    val blockedFileIds: Set<String>,
)

data class RememberedDestination(
    val treeUri: String,
    val displayName: String,
)

sealed class ResolvedDestination {
    data class Selected(val destination: RememberedDestination) : ResolvedDestination()
    data object None : ResolvedDestination()
    data class Missing(val destination: RememberedDestination) : ResolvedDestination()
}

data class PickerOutcome(
    val treeUri: String?,
    val displayName: String?,
)

data class MountEntry(
    val device: String,
    val mountPoint: String,
    val fsType: String,
)

/** Flags that must be on ACTION_OPEN_DOCUMENT_TREE. Values match android.content.Intent. */
object DestinationPicker {
    const val ACTION = "android.intent.action.OPEN_DOCUMENT_TREE"
    const val FLAG_READ = 1
    const val FLAG_WRITE = 2
    const val FLAG_PERSISTABLE = 64
    const val FLAG_PREFIX = 128

    fun pickerFlags(): Int = FLAG_READ or FLAG_WRITE or FLAG_PERSISTABLE or FLAG_PREFIX

    fun persistFlags(): Int = FLAG_READ or FLAG_WRITE
}

private val CONCRETE_FILESYSTEMS = setOf(
    "vfat",
    "fat32",
    "msdos",
    "exfat",
    "sdfat",
    "ntfs",
    "ext4",
    "ext3",
    "ext2",
    "f2fs",
)

fun classifyFilesystem(raw: String?): FilesystemKind {
    if (raw.isNullOrBlank()) return FilesystemKind.UNKNOWN
    val token = raw.trim().lowercase()
    return when {
        token == "vfat" || token == "fat32" || token == "msdos" -> FilesystemKind.FAT32
        token == "exfat" || token == "sdfat" -> FilesystemKind.EXFAT
        token == "ntfs" -> FilesystemKind.NTFS
        token == "ext4" || token == "ext3" || token == "ext2" || token == "f2fs" -> FilesystemKind.EXT4
        else -> FilesystemKind.OTHER
    }
}

fun parseMounts(text: String): List<MountEntry> {
    val entries = ArrayList<MountEntry>()
    for (line in text.lineSequence()) {
        if (line.isBlank() || line.startsWith("#")) continue
        val parts = line.split(Regex("\\s+"))
        if (parts.size < 3) continue
        entries += MountEntry(parts[0], unescapeMount(parts[1]), parts[2])
    }
    return entries
}

/**
 * Picks a disk filesystem for this volume. `fuse` and `sdcardfs` are views, not formats.
 * Two disagreeing concrete types become unknown rather than a guess.
 */
fun filesystemRawForVolume(mounts: List<MountEntry>, volumePath: String?, uuid: String?): String? {
    val path = volumePath?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val id = uuid?.trim()?.takeIf { it.length >= 4 }
    if (path == null && id == null) return null
    val related = mounts.filter { entry ->
        val point = entry.mountPoint.trimEnd('/')
        val byPath = path != null && (point == path || point.startsWith("$path/") || path.startsWith("$point/"))
        val byUuid = id != null && (
            point.endsWith("/$id") ||
                entry.mountPoint.contains("/$id") ||
                entry.device.contains(id)
            )
        byPath || byUuid
    }
    val concrete = related.map { it.fsType.lowercase() }.filter { it in CONCRETE_FILESYSTEMS }.distinct()
    return if (concrete.size == 1) concrete[0] else null
}

fun assessDestination(facts: VolumeFacts?, selected: List<SelectedFileSize>): DestinationAssessment {
    if (facts == null) {
        val warning = DestinationWarning(
            DestinationWarningCode.NO_DESTINATION,
            "No destination has been chosen.",
            blocking = true,
        )
        return DestinationAssessment(
            status = DestinationStatus.NONE,
            filesystem = FilesystemKind.UNKNOWN,
            writability = Writability.UNCONFIRMED,
            warnings = listOf(warning),
            trusted = false,
            blockedFileIds = emptySet(),
        )
    }

    val filesystem = classifyFilesystem(facts.filesystemRaw)
    val warnings = ArrayList<DestinationWarning>()
    val writability: Writability

    if (facts.permissionRevoked || (!facts.grantedRead && !facts.grantedWrite)) {
        writability = Writability.UNCONFIRMED
        warnings += DestinationWarning(
            DestinationWarningCode.PERMISSION_REVOKED,
            "Permission for ${facts.displayName} was revoked. Another destination will not be used.",
            blocking = true,
        )
    } else if (!facts.volumePresent) {
        writability = Writability.UNCONFIRMED
        warnings += DestinationWarning(
            DestinationWarningCode.DISCONNECTED,
            "${facts.displayName} is not connected. Another destination will not be used.",
            blocking = true,
        )
    } else if (facts.internalStorage) {
        writability = Writability.UNCONFIRMED
        warnings += DestinationWarning(
            DestinationWarningCode.INTERNAL_STORAGE,
            "This folder is on internal storage, not a removable drive. It will not be used as the USB destination.",
            blocking = true,
        )
    } else if (!facts.grantedWrite) {
        writability = Writability.UNCONFIRMED
        warnings += DestinationWarning(
            DestinationWarningCode.NO_WRITE_PERMISSION,
            "This folder was opened without write access.",
            blocking = true,
        )
    } else {
        writability = decideWritability(facts, filesystem)
        warnings += writabilityWarnings(filesystem, writability)
        if (!facts.persisted) {
            warnings += if (facts.persistable) {
                DestinationWarning(
                    DestinationWarningCode.PERMISSION_NOT_SAVED,
                    "Write access is granted for this session and still needs to be saved.",
                    blocking = false,
                )
            } else {
                DestinationWarning(
                    DestinationWarningCode.PERMISSION_NOT_PERSISTED,
                    "Write access was granted, but Android will not keep it. The folder has to be chosen again next time.",
                    blocking = false,
                )
            }
        }
        warnings += fileWarnings(filesystem, selected)
        warnings += spaceWarnings(facts, selected)
    }

    val blocking = warnings.any { it.blocking }
    val status = when {
        blocking -> DestinationStatus.BLOCKED
        warnings.isNotEmpty() -> DestinationStatus.WARNING
        else -> DestinationStatus.READY
    }
    return DestinationAssessment(
        status = status,
        filesystem = filesystem,
        writability = writability,
        warnings = warnings,
        trusted = !blocking,
        blockedFileIds = warnings.filter { it.blocking && it.fileId != null }.mapNotNull { it.fileId }.toSet(),
    )
}

fun shouldPersist(facts: VolumeFacts): Boolean {
    return facts.volumePresent &&
        facts.grantedWrite &&
        facts.persistable &&
        !facts.persisted &&
        !facts.permissionRevoked &&
        !facts.internalStorage
}

fun markDisconnected(facts: VolumeFacts): VolumeFacts {
    return facts.copy(
        volumePresent = false,
        freeBytes = null,
        totalBytes = null,
        canWriteFlag = null,
        writeProbeSucceeded = null,
    )
}

fun markRevoked(facts: VolumeFacts): VolumeFacts {
    return facts.copy(
        grantedRead = false,
        grantedWrite = false,
        persisted = false,
        permissionRevoked = true,
        canWriteFlag = null,
        writeProbeSucceeded = null,
        freeBytes = null,
        totalBytes = null,
    )
}

/**
 * Returns the remembered folder only when that exact tree is still connected.
 * Any other connected tree is ignored.
 */
fun resolveRememberedDestination(
    remembered: RememberedDestination?,
    connectedTreeUris: Set<String>,
): ResolvedDestination {
    if (remembered == null) return ResolvedDestination.None
    return if (remembered.treeUri in connectedTreeUris) {
        ResolvedDestination.Selected(remembered)
    } else {
        ResolvedDestination.Missing(remembered)
    }
}

/** The only URI a caller may use. Missing and empty both mean none, not "the other drive". */
fun destinationUriToUse(resolved: ResolvedDestination): String? {
    return (resolved as? ResolvedDestination.Selected)?.destination?.treeUri
}

/** A cancelled picker keeps the previous folder. A new URI replaces it because the user picked it. */
fun adoptPickerOutcome(previous: RememberedDestination?, outcome: PickerOutcome): RememberedDestination? {
    val uri = outcome.treeUri?.takeIf { it.isNotBlank() } ?: return previous
    val name = outcome.displayName?.takeIf { it.isNotBlank() } ?: uri
    return RememberedDestination(uri, name)
}

fun missingDestinationMessage(name: String): String {
    return "$name is not available. Another folder will not be chosen."
}

private fun decideWritability(facts: VolumeFacts, filesystem: FilesystemKind): Writability {
    if (facts.canWriteFlag == false || facts.writeProbeSucceeded == false) {
        return if (filesystem == FilesystemKind.NTFS && facts.writeProbeSucceeded != true) {
            Writability.UNSUPPORTED
        } else {
            Writability.READ_ONLY
        }
    }
    if (facts.writeProbeSucceeded == true && facts.canWriteFlag != false) {
        return Writability.WRITABLE
    }
    return if (filesystem == FilesystemKind.NTFS) Writability.UNSUPPORTED else Writability.UNCONFIRMED
}

private fun writabilityWarnings(
    filesystem: FilesystemKind,
    writability: Writability,
): List<DestinationWarning> {
    val warnings = ArrayList<DestinationWarning>()
    when (writability) {
        Writability.WRITABLE -> {
            if (filesystem == FilesystemKind.NTFS) {
                warnings += DestinationWarning(
                    DestinationWarningCode.NTFS_WRITE_CONFIRMED,
                    "A write test succeeded. NTFS is still not assumed on the next drive.",
                    blocking = false,
                )
            }
        }
        Writability.READ_ONLY -> warnings += DestinationWarning(
            DestinationWarningCode.READ_ONLY,
            "This drive is read-only.",
            blocking = true,
        )
        Writability.UNSUPPORTED -> warnings += DestinationWarning(
            DestinationWarningCode.WRITE_UNCONFIRMED,
            "NTFS is not assumed to be writable. A write test has not succeeded on this drive.",
            blocking = true,
        )
        Writability.UNCONFIRMED -> warnings += DestinationWarning(
            DestinationWarningCode.WRITE_UNCONFIRMED,
            "Writability has not been tested. exFAT, FAT32, and NTFS are not assumed to be writable.",
            blocking = true,
        )
    }
    return warnings
}

private fun fileWarnings(filesystem: FilesystemKind, selected: List<SelectedFileSize>): List<DestinationWarning> {
    val warnings = ArrayList<DestinationWarning>()
    var unknownOnUnknownFs = false
    for (file in selected) {
        val size = file.sizeBytes
        if (filesystem == FilesystemKind.FAT32) {
            if (size == null || size < 0) {
                warnings += DestinationWarning(
                    DestinationWarningCode.FAT32_SIZE_UNKNOWN,
                    "${file.fileName} has no size yet. On FAT32 a file over 4 GiB would fail, so it is blocked.",
                    blocking = true,
                    fileId = file.id,
                )
            } else if (size > FAT32_MAX_FILE_BYTES) {
                warnings += DestinationWarning(
                    DestinationWarningCode.FAT32_FILE_TOO_LARGE,
                    "${file.fileName} is $size bytes. FAT32 cannot store a file over $FAT32_MAX_FILE_BYTES bytes.",
                    blocking = true,
                    fileId = file.id,
                )
            }
        } else if (filesystem == FilesystemKind.UNKNOWN || filesystem == FilesystemKind.OTHER) {
            if (size != null && size >= FOUR_GIB_BYTES) {
                warnings += DestinationWarning(
                    DestinationWarningCode.UNKNOWN_FILESYSTEM_LARGE_FILE,
                    "The filesystem was not identified. ${file.fileName} is at least 4 GiB, and this drive is not assumed to be exFAT.",
                    blocking = true,
                    fileId = file.id,
                )
            } else if (size == null || size < 0) {
                unknownOnUnknownFs = true
            }
        }
    }
    if (unknownOnUnknownFs) {
        warnings += DestinationWarning(
            DestinationWarningCode.UNKNOWN_SIZE_UNCHECKED,
            "Some selected files have no size. The filesystem is also unknown, so a 4 GiB limit was not checked for them.",
            blocking = false,
        )
    }
    return warnings
}

private fun spaceWarnings(facts: VolumeFacts, selected: List<SelectedFileSize>): List<DestinationWarning> {
    val warnings = ArrayList<DestinationWarning>()
    val free = facts.freeBytes
    if (free == null || free < 0) {
        warnings += DestinationWarning(
            DestinationWarningCode.CAPACITY_UNKNOWN,
            "Android did not report free space for this folder.",
            blocking = false,
        )
        return warnings
    }
    var known = 0L
    var overflow = false
    var unknown = 0
    for (file in selected) {
        val size = file.sizeBytes
        if (size == null || size < 0) {
            unknown += 1
            continue
        }
        if (known > Long.MAX_VALUE - size) {
            overflow = true
        } else {
            known += size
        }
    }
    if (overflow || known > free) {
        warnings += DestinationWarning(
            DestinationWarningCode.NOT_ENOUGH_SPACE,
            "The selected files need more than the $free bytes this drive reports free.",
            blocking = true,
        )
    }
    if (unknown > 0) {
        warnings += DestinationWarning(
            DestinationWarningCode.SPACE_NOT_FULLY_CHECKED,
            "At least one selected file has no size, so free space was not fully checked.",
            blocking = false,
        )
    }
    return warnings
}

private fun unescapeMount(path: String): String {
    return path
        .replace("\\040", " ")
        .replace("\\011", "\t")
        .replace("\\012", "\n")
        .replace("\\134", "\\")
}
