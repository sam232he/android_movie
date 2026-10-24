package com.videomover.ui.library

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.videomover.library.LibraryViewMode
import com.videomover.library.SortKey
import com.videomover.library.VideoItem
import com.videomover.library.formatBytes
import com.videomover.library.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val Paper = Color(0xFF12110E)
private val Panel = Color(0xFF1C1A16)
private val Ink = Color(0xFFF4EFE6)
private val Muted = Color(0xFFA39B8C)
private val Signal = Color(0xFFEF6A33)

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onSearch: (String) -> Unit,
    onSort: (SortKey) -> Unit,
    onMode: (LibraryViewMode) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddFolder: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Paper)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text("Videos", style = MaterialTheme.typography.headlineMedium, color = Ink)
        Text(
            "Choose what to move. Nothing is copied or deleted from this screen.",
            color = Muted,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))

        when (state.access) {
            AccessLevel.CHECKING -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Signal)
                }
            }
            AccessLevel.DENIED -> Denied(onRequestAccess)
            AccessLevel.GRANTED -> GrantedLibrary(
                state = state,
                onSearch = onSearch,
                onSort = onSort,
                onMode = onMode,
                onToggle = onToggle,
                onSelectAll = onSelectAll,
                onDeselectAll = onDeselectAll,
                onAddFolder = onAddFolder,
                onRequestAccess = onRequestAccess,
            )
        }
    }
}

@Composable
private fun Denied(onRequestAccess: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("Video access is off", color = Ink, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "The library reads videos through the system media library. Nothing is uploaded.",
            color = Muted,
        )
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onRequestAccess,
            colors = ButtonDefaults.buttonColors(containerColor = Signal, contentColor = Color(0xFF1A0D07)),
        ) {
            Text("Allow video access")
        }
    }
}

@Composable
private fun GrantedLibrary(
    state: LibraryUiState,
    onSearch: (String) -> Unit,
    onSort: (SortKey) -> Unit,
    onMode: (LibraryViewMode) -> Unit,
    onToggle: (String) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onAddFolder: () -> Unit,
    onRequestAccess: () -> Unit,
) {
    if (state.partialAccess) {
        Notice("Only the videos you picked are visible.")
        TextButton(onClick = onRequestAccess) { Text("Allow all videos", color = Signal) }
    }
    state.warnings.forEach { warning ->
        Notice(warning)
        Spacer(Modifier.height(6.dp))
    }
    OutlinedTextField(
        value = state.query.search,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Search filenames") },
    )
    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SortKey.entries.forEach { key ->
            FilterChip(
                selected = state.query.sortKey == key,
                onClick = { onSort(key) },
                label = {
                    val arrow = if (state.query.sortKey == key) {
                        if (state.query.direction.name == "ASC") " ↑" else " ↓"
                    } else {
                        ""
                    }
                    Text(key.title() + arrow)
                },
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = onSelectAll) { Text("Select all", color = Ink) }
        TextButton(onClick = onDeselectAll) { Text("Deselect all", color = Ink) }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { onMode(LibraryViewMode.GRID) }) { Text("Grid", color = if (state.mode == LibraryViewMode.GRID) Signal else Muted) }
        IconButton(onClick = { onMode(LibraryViewMode.LIST) }) { Text("List", color = if (state.mode == LibraryViewMode.LIST) Signal else Muted) }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${state.selection.size} selected", color = Muted, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onAddFolder) { Text("Add folder", color = Signal) }
    }
    Spacer(Modifier.height(8.dp))
    when {
        state.loading && state.items.isEmpty() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Signal)
            }
        }
        state.visible.isEmpty() -> {
            Text(
                if (state.query.search.isBlank()) "No videos found." else "No filenames match that search.",
                color = Muted,
            )
        }
        state.mode == LibraryViewMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.visible, key = { it.id }) { item ->
                    GridCard(item, item.id in state.selection, onToggle)
                }
            }
        }
        else -> {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(state.visible, key = { it.id }) { item ->
                    ListCard(item, item.id in state.selection, onToggle)
                }
            }
        }
    }
}

@Composable
private fun GridCard(item: VideoItem, selected: Boolean, onToggle: (String) -> Unit) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(enabled = item.selectable) { onToggle(item.id) },
    ) {
        Column(Modifier.padding(8.dp)) {
            Box {
                VideoThumbnail(item.uri, item.fileName, Modifier.aspectRatio(16f / 10f))
                if (selected) {
                    Text(
                        "Selected",
                        color = Color(0xFF1A0D07),
                        modifier = Modifier
                            .padding(6.dp)
                            .background(Signal, RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            FileFacts(item)
        }
    }
}

@Composable
private fun ListCard(item: VideoItem, selected: Boolean, onToggle: (String) -> Unit) {
    Surface(
        color = Panel,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clickable(enabled = item.selectable) { onToggle(item.id) },
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            VideoThumbnail(item.uri, item.fileName, Modifier.size(84.dp, 56.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) { FileFacts(item) }
            Text(if (selected) "On" else if (item.selectable) "Off" else "—", color = if (selected) Signal else Muted)
        }
    }
}

@Composable
private fun FileFacts(item: VideoItem) {
    Text(item.fileName, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
    Text(
        "${formatBytes(item.sizeBytes)}  ·  ${formatDuration(item.durationMs)}  ·  ${item.resolutionLabel()}",
        color = Muted,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = MaterialTheme.typography.labelMedium,
    )
    item.relativePath?.let {
        Text(it, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
    item.metadataWarning?.let {
        Text(it, color = Signal, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
    }
    if (item.possibleDuplicate) {
        Text("Possible duplicate", color = Signal, style = MaterialTheme.typography.labelSmall)
    }
    if (!item.accessible) {
        Text("Inaccessible", color = Signal, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        color = Ink,
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(10.dp))
            .padding(10.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun VideoThumbnail(uri: String, description: String, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { loadThumbnail(context, uri) }
    }.value
    Box(
        modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A261F)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = description, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text("No preview", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun loadThumbnail(context: Context, uriString: String): androidx.compose.ui.graphics.ImageBitmap? {
    val uri = try {
        Uri.parse(uriString)
    } catch (_: Exception) {
        return null
    }
    return try {
        val bitmap: Bitmap? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.loadThumbnail(uri, Size(256, 256), null)
        } else {
            val id = uri.lastPathSegment?.toLongOrNull() ?: return null
            @Suppress("DEPRECATION")
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                id,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null,
            )
        }
        bitmap?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

private fun SortKey.title(): String = when (this) {
    SortKey.NAME -> "Name"
    SortKey.SIZE -> "Size"
    SortKey.DATE_ADDED -> "Date"
    SortKey.DURATION -> "Duration"
}
