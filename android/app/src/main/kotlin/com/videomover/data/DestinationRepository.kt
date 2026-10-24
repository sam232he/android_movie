package com.videomover.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.videomover.destination.RememberedDestination
import com.videomover.destination.VolumeFacts
import com.videomover.destination.filesystemRawForVolume
import com.videomover.destination.parseMounts
import java.io.File

/**
 * Remembers one SAF tree. A missing grant or a missing volume never selects a different tree.
 */
class DestinationRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val storage: StorageManager = context.getSystemService(StorageManager::class.java)

    fun remembered(): RememberedDestination? {
        val uri = prefs.getString(KEY_URI, null) ?: return null
        val name = prefs.getString(KEY_NAME, null) ?: uri
        return RememberedDestination(uri, name)
    }

    fun remember(destination: RememberedDestination) {
        prefs.edit()
            .putString(KEY_URI, destination.treeUri)
            .putString(KEY_NAME, destination.displayName)
            .apply()
    }

    fun forget() {
        prefs.edit().remove(KEY_URI).remove(KEY_NAME).apply()
    }

    /** False when this provider will not persist. The URI itself is left as the user chose it. */
    fun persist(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun displayName(uri: Uri): String {
        val fromDocument = runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
        if (!fromDocument.isNullOrBlank()) return fromDocument
        val id = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
        return id ?: uri.toString()
    }

    fun inspect(remembered: RememberedDestination, freshPickerGrant: Boolean, persistable: Boolean): VolumeFacts {
        val uri = Uri.parse(remembered.treeUri)
        val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
        val grantedRead = grant?.isReadPermission == true || freshPickerGrant
        val grantedWrite = grant?.isWritePermission == true || freshPickerGrant
        val persisted = grant?.isWritePermission == true
        val revoked = !freshPickerGrant && grant == null

        val doc = if (grantedRead || grantedWrite) {
            runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()
        } else {
            null
        }
        val root = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()?.substringBefore(':')
        val volume = findVolume(root)
        val internal = root == "primary" || (volume != null && !volume.isRemovable)
        val readOnlyMounted = volume?.state == Environment.MEDIA_MOUNTED_READ_ONLY
        val present = volumeIsPresent(volume, doc, grantedRead || grantedWrite)
        val path = volumePath(volume)
        val filesystem = if (root != null && root != "primary") {
            filesystemRawForVolume(readMounts(), path, root)
        } else {
            null
        }
        val canWrite = when {
            !present || revoked || !grantedWrite -> null
            readOnlyMounted -> false
            doc == null -> null
            else -> runCatching { doc.canWrite() }.getOrNull()
        }
        val capacity = if (present && !internal && path != null) readCapacity(path) else null to null
        val probe = if (present && grantedWrite && !internal && !revoked && doc != null && canWrite == true) {
            probeWrite(doc)
        } else {
            null
        }
        return VolumeFacts(
            treeUri = remembered.treeUri,
            displayName = remembered.displayName,
            grantedRead = grantedRead,
            grantedWrite = grantedWrite,
            persisted = persisted,
            persistable = persistable || persisted,
            volumePresent = present,
            permissionRevoked = revoked,
            internalStorage = internal,
            filesystemRaw = filesystem,
            canWriteFlag = canWrite,
            writeProbeSucceeded = probe,
            freeBytes = capacity.first,
            totalBytes = capacity.second,
        )
    }

    private fun volumeIsPresent(volume: StorageVolume?, doc: DocumentFile?, haveGrant: Boolean): Boolean {
        when (volume?.state) {
            Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY -> return true
            Environment.MEDIA_UNMOUNTED,
            Environment.MEDIA_REMOVED,
            Environment.MEDIA_BAD_REMOVAL,
            Environment.MEDIA_EJECTING,
            Environment.MEDIA_UNMOUNTABLE,
            -> return false
        }
        if (!haveGrant || doc == null) return false
        return runCatching { doc.exists() }.getOrDefault(false)
    }

    private fun findVolume(root: String?): StorageVolume? {
        if (root == null) return null
        val volumes = storage.storageVolumes
        if (root == "primary") return volumes.firstOrNull { !it.isRemovable }
        return volumes.firstOrNull { volume ->
            val path = volumePath(volume)?.trimEnd('/')
            path != null && path.endsWith("/$root")
        }
    }

    private fun volumePath(volume: StorageVolume?): String? {
        if (volume == null || Build.VERSION.SDK_INT < 30) return null
        return volume.directory?.path
    }

    private fun readCapacity(path: String): Pair<Long?, Long?> {
        return try {
            val stat = StatFs(path)
            stat.availableBytes to stat.totalBytes
        } catch (_: IllegalArgumentException) {
            null to null
        }
    }

    private fun readMounts() = parseMounts(runCatching { File("/proc/mounts").readText() }.getOrDefault(""))

    private fun probeWrite(tree: DocumentFile): Boolean {
        val existing = runCatching { tree.findFile(PROBE_NAME) }.getOrNull()
        if (existing != null && !existing.delete()) return false
        val created = runCatching {
            tree.createFile("application/octet-stream", PROBE_NAME)
        }.getOrNull() ?: return false
        created.delete()
        return true
    }

    private companion object {
        const val PREFS = "videomover_destination"
        const val KEY_URI = "tree_uri"
        const val KEY_NAME = "display_name"
        const val PROBE_NAME = ".videomover-write-probe"
    }
}
