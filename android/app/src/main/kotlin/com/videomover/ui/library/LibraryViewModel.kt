package com.videomover.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videomover.data.DeviceVideoRepository
import com.videomover.library.LibraryQuery
import com.videomover.library.LibraryViewMode
import com.videomover.library.SortDirection
import com.videomover.library.SortKey
import com.videomover.library.VideoItem
import com.videomover.library.applyLibraryQuery
import com.videomover.library.deselectAll
import com.videomover.library.selectAllVisible
import com.videomover.library.toggleSelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AccessLevel {
    CHECKING,
    DENIED,
    GRANTED,
}

data class LibraryUiState(
    val items: List<VideoItem> = emptyList(),
    val visible: List<VideoItem> = emptyList(),
    val query: LibraryQuery = LibraryQuery(),
    val selection: Set<String> = emptySet(),
    val mode: LibraryViewMode = LibraryViewMode.GRID,
    val loading: Boolean = false,
    val warnings: List<String> = emptyList(),
    val access: AccessLevel = AccessLevel.CHECKING,
    val partialAccess: Boolean = false,
)

class LibraryViewModel(
    private val repository: DeviceVideoRepository,
) : ViewModel() {
    private val state = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = state.asStateFlow()

    private var mediaStoreItems: List<VideoItem> = emptyList()
    private var folderItems: List<VideoItem> = emptyList()
    private var mediaWarnings: List<String> = emptyList()
    private var folderWarnings: List<String> = emptyList()

    fun onAccessResult(granted: Boolean, partial: Boolean) {
        state.update { it.copy(access = if (granted || partial) AccessLevel.GRANTED else AccessLevel.DENIED, partialAccess = partial && !granted) }
        if (granted || partial) {
            refreshMediaStore()
        } else {
            mediaStoreItems = emptyList()
            mediaWarnings = emptyList()
            publish()
        }
    }

    fun refreshMediaStore() {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            val result = repository.loadMediaStore()
            mediaStoreItems = result.items
            mediaWarnings = result.warnings
            publish()
        }
    }

    fun addFolder(treeUri: Uri) {
        viewModelScope.launch {
            state.update { it.copy(loading = true) }
            val result = repository.loadTree(treeUri)
            val incoming = result.items.filter { item ->
                folderItems.none { it.id == item.id } && mediaStoreItems.none { it.id == item.id }
            }
            folderItems = folderItems + incoming
            folderWarnings = (folderWarnings + result.warnings).distinct()
            publish()
        }
    }

    fun setSearch(value: String) {
        state.update { current ->
            val query = current.query.copy(search = value)
            current.copy(query = query, visible = applyLibraryQuery(current.items, query))
        }
    }

    fun setSort(key: SortKey) {
        state.update { current ->
            val direction = if (current.query.sortKey == key) {
                if (current.query.direction == SortDirection.ASC) SortDirection.DESC else SortDirection.ASC
            } else {
                defaultDirection(key)
            }
            val query = current.query.copy(sortKey = key, direction = direction)
            current.copy(query = query, visible = applyLibraryQuery(current.items, query))
        }
    }

    fun setMode(mode: LibraryViewMode) {
        state.update { it.copy(mode = mode) }
    }

    fun toggle(id: String) {
        state.update { current ->
            val item = current.items.firstOrNull { it.id == id }
            current.copy(selection = toggleSelection(current.selection, id, item))
        }
    }

    fun selectAll() {
        state.update { current ->
            current.copy(selection = selectAllVisible(current.selection, current.visible))
        }
    }

    fun deselect() {
        state.update { it.copy(selection = deselectAll()) }
    }

    private fun publish() {
        val merged = (mediaStoreItems + folderItems).distinctBy { it.id }
        state.update { current ->
            val kept = current.selection.filter { id -> merged.any { it.id == id && it.selectable } }.toSet()
            current.copy(
                items = merged,
                visible = applyLibraryQuery(merged, current.query),
                selection = kept,
                loading = false,
                warnings = (mediaWarnings + folderWarnings).distinct(),
            )
        }
    }

    private fun defaultDirection(key: SortKey): SortDirection {
        return if (key == SortKey.NAME) SortDirection.ASC else SortDirection.DESC
    }

    companion object {
        fun factory(repository: DeviceVideoRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LibraryViewModel(repository) as T
                }
            }
        }
    }
}
