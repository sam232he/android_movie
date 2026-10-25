package com.videomover.ui.destination

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videomover.destination.DestinationStatus
import com.videomover.destination.FilesystemKind
import com.videomover.model.OutputResolution

@Composable
fun DestinationScreen(
    state: DestinationUiState,
    onChoose: () -> Unit,
    onForget: () -> Unit,
    onRefresh: () -> Unit,
    onMove: () -> Unit,
    resolution: OutputResolution,
    onResolutionChange: (OutputResolution) -> Unit,
    moveEnabled: Boolean,
    moving: Boolean,
    message: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("USB destination", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Choose the folder on the USB drive. If that drive is missing or the permission is revoked, nothing else is used.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onChoose, enabled = !state.checking) { Text("Choose USB folder") }
            if (state.treeUri != null) {
                Button(onClick = onForget, enabled = !state.checking) { Text("Forget") }
            }
            Button(onClick = onRefresh, enabled = !state.checking && state.treeUri != null) { Text("Check again") }
        }
        if (state.checking) {
            CircularProgressIndicator()
        }
        if (state.treeUri == null) {
            Text("No folder chosen.")
        } else {
            Text(state.displayName ?: "Chosen folder", style = MaterialTheme.typography.titleMedium)
            Text(state.treeUri, style = MaterialTheme.typography.bodySmall)
            Text("Filesystem: ${filesystemLabel(state.filesystem)}")
            Text("Free space: ${formatBytes(state.freeBytes)} of ${formatBytes(state.totalBytes)}")
            Text(if (state.persisted) "Permission saved on this phone." else "Permission is not saved for next time.")
            Text("Selected videos checked: ${state.selectedCount}")
            Text("Status: ${statusLabel(state.status)}")
            Text("Output resolution", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutputResolution.entries.forEach { option ->
                    Button(onClick = { onResolutionChange(option) }, enabled = option != resolution) {
                        Text(if (option == resolution) "${option.label} selected" else option.label)
                    }
                }
            }
            Button(onClick = onMove, enabled = moveEnabled && !moving) {
                Text(if (moving) "Moving…" else "Move next selected")
            }
        }
        message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        state.assessment.warnings.forEach { warning ->
            Text(
                warning.message,
                color = if (warning.blocking) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            "Nothing is moved from this screen. A write test may create and delete an empty file named .videomover-write-probe in the chosen folder.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun filesystemLabel(kind: FilesystemKind): String {
    return when (kind) {
        FilesystemKind.FAT32 -> "FAT32"
        FilesystemKind.EXFAT -> "exFAT"
        FilesystemKind.NTFS -> "NTFS"
        FilesystemKind.EXT4 -> "ext4"
        FilesystemKind.OTHER -> "Unrecognized"
        FilesystemKind.UNKNOWN -> "Not reported"
    }
}

private fun statusLabel(status: DestinationStatus): String {
    return when (status) {
        DestinationStatus.NONE -> "None"
        DestinationStatus.READY -> "Ready"
        DestinationStatus.WARNING -> "Ready, with a warning"
        DestinationStatus.BLOCKED -> "Blocked"
    }
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "not reported"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024.0
        unit += 1
    }
    val text = if (value >= 100 || value == kotlin.math.floor(value)) {
        value.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", value)
    }
    return "$text ${units[unit]}"
}
