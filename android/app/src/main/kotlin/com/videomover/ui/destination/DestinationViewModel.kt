package com.videomover.ui.destination

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.videomover.data.DestinationRepository
import com.videomover.destination.DestinationAssessment
import com.videomover.destination.DestinationStatus
import com.videomover.destination.FilesystemKind
import com.videomover.destination.RememberedDestination
import com.videomover.destination.SelectedFileSize
import com.videomover.destination.VolumeFacts
import com.videomover.destination.assessDestination
import com.videomover.destination.markDisconnected
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DestinationUiState(
    val assessment: DestinationAssessment = assessDestination(null, emptyList()),
    val displayName: String? = null,
    val treeUri: String? = null,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    val filesystem: FilesystemKind = FilesystemKind.UNKNOWN,
    val persisted: Boolean = false,
    val checking: Boolean = false,
    val selectedCount: Int = 0,
    val status: DestinationStatus = DestinationStatus.NONE,
)

class DestinationViewModel(
    private val repository: DestinationRepository,
) : ViewModel() {
    private val state = MutableStateFlow(DestinationUiState(checking = true))
    val uiState: StateFlow<DestinationUiState> = state.asStateFlow()

    private var facts: VolumeFacts? = null
    private var selected: List<SelectedFileSize> = emptyList()

    init {
        refresh()
    }

    fun setSelected(files: List<SelectedFileSize>) {
        selected = files
        publish(state.value.checking)
    }

    fun onPicked(uri: Uri) {
        viewModelScope.launch {
            state.value = state.value.copy(checking = true)
            val persistable = withContext(Dispatchers.IO) { repository.persist(uri) }
            val name = withContext(Dispatchers.IO) { repository.displayName(uri) }
            val remembered = RememberedDestination(uri.toString(), name)
            repository.remember(remembered)
            facts = withContext(Dispatchers.IO) {
                repository.inspect(remembered, freshPickerGrant = true, persistable = persistable)
            }
            publish(checking = false)
        }
    }

    fun refresh() {
        val remembered = repository.remembered() ?: run {
            facts = null
            publish(checking = false)
            return
        }
        viewModelScope.launch {
            state.value = state.value.copy(checking = true)
            val previous = facts
            facts = withContext(Dispatchers.IO) {
                runCatching {
                    repository.inspect(remembered, freshPickerGrant = false, persistable = true)
                }.getOrElse {
                    previous?.let { markDisconnected(it) }
                }
            }
            publish(checking = false)
        }
    }

    /** Explicit user action. This does not choose a replacement. */
    fun forget() {
        repository.forget()
        facts = null
        publish(checking = false)
    }

    private fun publish(checking: Boolean) {
        val current = facts
        val assessment = assessDestination(current, selected)
        state.value = DestinationUiState(
            assessment = assessment,
            displayName = current?.displayName,
            treeUri = current?.treeUri,
            freeBytes = current?.freeBytes,
            totalBytes = current?.totalBytes,
            filesystem = assessment.filesystem,
            persisted = current?.persisted == true,
            checking = checking,
            selectedCount = selected.size,
            status = assessment.status,
        )
    }

    companion object {
        fun factory(repository: DestinationRepository): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return DestinationViewModel(repository) as T
                }
            }
        }
    }
}
