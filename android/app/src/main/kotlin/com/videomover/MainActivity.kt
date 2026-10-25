package com.videomover

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.videomover.data.DestinationRepository
import com.videomover.data.DeviceVideoRepository
import com.videomover.data.MoveRecordStore
import com.videomover.data.MoveWorkScheduler
import com.videomover.destination.SelectedFileSize
import com.videomover.library.formatBytes
import com.videomover.model.OutputResolution
import com.videomover.model.MoveFileRecord
import com.videomover.machine.FileState
import com.videomover.output.nextAvailableName
import java.util.UUID
import com.videomover.preflight.estimatePreflight
import com.videomover.ui.destination.DestinationScreen
import com.videomover.ui.destination.DestinationViewModel
import com.videomover.ui.library.LibraryScreen
import com.videomover.ui.library.LibraryViewModel
import com.videomover.ui.results.ResultsScreen

private enum class AppScreen {
    LIBRARY,
    DESTINATION,
    RESULTS,
}

class MainActivity : ComponentActivity() {
    private val repository by lazy { DeviceVideoRepository(applicationContext) }
    private val destinations by lazy { DestinationRepository(applicationContext) }
    private val moveRecords by lazy { MoveRecordStore(applicationContext) }
    private var pendingDeleteRecordIds: List<String> = emptyList()
    private var pendingDeleteBatchId: String? = null

    private val deleteRequest = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val ids = pendingDeleteRecordIds
        val batchId = pendingDeleteBatchId
        pendingDeleteRecordIds = emptyList()
        pendingDeleteBatchId = null
        if (ids.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val approved = result.resultCode == RESULT_OK
            ids.forEach { id ->
                val record = moveRecords.find(id) ?: return@forEach
                moveRecords.upsert(record.copy(
                    state = if (approved) FileState.SOURCE_DELETED.name else FileState.TRANSFER_VERIFIED.name,
                    sourceDeletedAtEpochMs = if (approved) System.currentTimeMillis() else null,
                    deleteDispatched = approved,
                    progressPercent = if (approved) 45 else record.progressPercent,
                    resumeState = null,
                    attentionReason = if (approved) null else record.attentionReason,
                    errorMessage = if (approved) null else "Deletion was not approved. The source and verified destination copy were kept.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                ))
            }
            if (approved && batchId != null) MoveWorkScheduler.enqueueBatch(applicationContext, batchId)
        }
    }
    private val model: LibraryViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return LibraryViewModel(repository) as T
            }
        }
    }

    private val destinationModel: DestinationViewModel by viewModels {
        DestinationViewModel.factory(destinations)
    }

    private var watchingVolumes = false

    private val mediaWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            destinationModel.refresh()
        }
    }

    private val usbWatch = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            destinationModel.refresh()
        }
    }

    private val accessRequest = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        reportAccess(granted)
    }

    private val folderRequest = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        try {
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Some providers only grant access for this session. The folder is still listed.
        }
        model.addFolder(uri)
    }

    private val destinationRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
        destinationModel.onPicked(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = scheme) {
                val library by model.uiState.collectAsState()
                val destination by destinationModel.uiState.collectAsState()
                val records by remember { moveRecords.observeAll() }.collectAsState(initial = emptyList())
                var screen by rememberSaveable { mutableStateOf(AppScreen.LIBRARY) }
                var moving by remember { mutableStateOf(false) }
                var transferMessage by remember { mutableStateOf<String?>(null) }
                var resolution by remember { mutableStateOf(OutputResolution.P480) }
                var summaryVisible by remember { mutableStateOf(false) }
                var currentBatchId by rememberSaveable { mutableStateOf<String?>(null) }
                val activeRecords = currentBatchId?.let { id -> records.filter { it.batchId == id } } ?: records
                val selectedItems = library.items.filter { it.id in library.selection }
                val selectedSizes = selectedItems.map { SelectedFileSize(it.id, it.fileName, it.sizeBytes) }
                val preflight = estimatePreflight(
                    selected = selectedSizes,
                    resolution = resolution,
                    usbFreeBytes = destination.freeBytes,
                    phoneFreeBytes = runCatching { StatFs(filesDir.path).availableBytes }.getOrNull(),
                )
                LaunchedEffect(library.selection, library.items) {
                    destinationModel.setSelected(
                        library.items
                            .filter { it.id in library.selection }
                            .map { SelectedFileSize(it.id, it.fileName, it.sizeBytes) },
                    )
                }
                Scaffold { padding ->
                    Box(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    ) {
                        when (screen) {
                            AppScreen.LIBRARY -> LibraryScreen(
                                state = library,
                                onSearch = model::setSearch,
                                onSort = model::setSort,
                                onMode = model::setMode,
                                onToggle = model::toggle,
                                onSelectAll = model::selectAll,
                                onDeselectAll = model::deselect,
                                onAddFolder = { folderRequest.launch(null) },
                                onRequestAccess = { askForVideos() },
                                onNext = { if (library.selection.isNotEmpty()) screen = AppScreen.DESTINATION },
                            )
                            AppScreen.DESTINATION -> DestinationScreen(
                                state = destination,
                                onBack = { screen = AppScreen.LIBRARY },
                                onChoose = { launchDestinationPicker() },
                                onForget = destinationModel::forget,
                                onRefresh = destinationModel::refresh,
                                onMove = {
                                    val tree = destination.treeUri
                                    if (tree == null || !destination.assessment.trusted || selectedItems.isEmpty()) {
                                        transferMessage = "Choose a trusted destination folder and select at least one accessible video."
                                    } else {
                                        summaryVisible = true
                                    }
                                },
                                resolution = resolution,
                                onResolutionChange = { resolution = it },
                                moveEnabled = destination.assessment.trusted && library.selection.isNotEmpty(),
                                moving = moving,
                                message = transferMessage,
                            )
                            AppScreen.RESULTS -> ResultsScreen(
                                activeRecords,
                                onStartNew = {
                                    model.deselect()
                                    currentBatchId = null
                                    transferMessage = null
                                    screen = AppScreen.LIBRARY
                                },
                                onConfirmDelete = currentBatchId
                                    ?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.R }
                                    ?.takeIf { activeRecords.any { it.state == FileState.TRANSFER_VERIFIED.name } }
                                    ?.let { batchId -> { confirmSourceDeletion(batchId) } },
                            )
                        }
                    }
                }
                if (summaryVisible) {
                    AlertDialog(
                        onDismissRequest = { summaryVisible = false },
                        title = { Text("Review conversion") },
                        text = {
                            Column {
                                Text("Files: ${preflight.fileCount}")
                                Text("Source size: ${formatBytes(preflight.selectedBytes)}")
                                Text("Estimated converted output: ${formatBytes(preflight.estimatedOutputBytes)}")
                                Text("Transfer-folder free: ${formatBytes(preflight.usbFreeBytes)}")
                                Text("Original-folder conversion space: ${formatBytes(preflight.phoneFreeBytes)}")
                                Text("Resolution: ${resolution.label}")
                                Text("Destination: ${destination.displayName ?: "not selected"}")
                                preflight.warnings.forEach { warning ->
                                    Text(warning, color = if (warning.startsWith("BLOCKING:")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(
                                enabled = !preflight.blocking && destination.treeUri != null,
                                onClick = {
                                    val tree = destination.treeUri
                                    if (tree != null) {
                                        lifecycleScope.launch {
                                            val batchId = "batch-${UUID.randomUUID()}"
                                            val existingNames = DocumentFile.fromTreeUri(applicationContext, android.net.Uri.parse(tree))
                                                ?.listFiles()
                                                ?.mapNotNull { it.name }
                                                ?.toMutableSet()
                                                ?: mutableSetOf()
                                            selectedItems.forEach { item ->
                                                val preferred = item.fileName
                                                val outputName = nextAvailableName(preferred) { candidate ->
                                                    existingNames.any { it.equals(candidate, ignoreCase = true) }
                                                }
                                                existingNames += outputName
                                                val now = System.currentTimeMillis()
                                                moveRecords.upsert(
                                                    MoveFileRecord(
                                                        id = UUID.randomUUID().toString(),
                                                        batchId = batchId,
                                                        sourceUri = item.uri,
                                                        sourceParentDirectoryUri = item.parentDirectoryUri,
                                                        sourceRelativePath = item.relativePath,
                                                        sourceDataPath = item.sourceDataPath,
                                                        originalFileName = item.fileName,
                                                        destinationFileName = outputName,
                                                        plannedOutputName = null,
                                                        externalDestinationTreeUri = tree,
                                                        destinationFileUri = null,
                                                        destinationTempUri = null,
                                                        outputResolution = resolution,
                                                        sourceChecksumSha256 = null,
                                                        destinationChecksumSha256 = null,
                                                        checksumMatch = null,
                                                        outputUri = null,
                                                        state = FileState.PENDING.name,
                                                        resumeState = null,
                                                        errorMessage = null,
                                                        attentionReason = null,
                                                        sourceDeletedAtEpochMs = null,
                                                        deleteDispatched = false,
                                                        createdAtEpochMs = now,
                                                        updatedAtEpochMs = now,
                                                    ),
                                                )
                                            }
                                            MoveWorkScheduler.enqueueBatch(applicationContext, batchId)
                                            currentBatchId = batchId
                                            transferMessage = "${selectedItems.size} file${if (selectedItems.size == 1) "" else "s"} queued to copy into ${destination.displayName ?: "the chosen folder"}."
                                            summaryVisible = false
                                            screen = AppScreen.RESULTS
                                        }
                                    }
                                },
                            ) { Text("Start transfer") }
                        },
                        dismissButton = { TextButton(onClick = { summaryVisible = false }) { Text("Back") } },
                    )
                }
            }
        }
        // Do not reconcile blindly here: an Activity recreation can happen while the
        // foreground batch worker owns a conversion. The worker resumes durable PENDING
        // records itself, without rewriting a live CONVERTING record.
        askForVideos()
    }

    override fun onStart() {
        super.onStart()
        if (!watchingVolumes) {
            val media = IntentFilter().apply {
                addAction(Intent.ACTION_MEDIA_EJECT)
                addAction(Intent.ACTION_MEDIA_UNMOUNTED)
                addAction(Intent.ACTION_MEDIA_REMOVED)
                addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
                addAction(Intent.ACTION_MEDIA_MOUNTED)
                addDataScheme("file")
            }
            val usb = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(mediaWatch, media, RECEIVER_NOT_EXPORTED)
                registerReceiver(usbWatch, usb, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(mediaWatch, media)
                registerReceiver(usbWatch, usb)
            }
            watchingVolumes = true
        }
        destinationModel.refresh()
    }

    override fun onStop() {
        if (watchingVolumes) {
            unregisterReceiver(mediaWatch)
            unregisterReceiver(usbWatch)
            watchingVolumes = false
        }
        super.onStop()
    }

    private fun launchDestinationPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        destinationRequest.launch(intent)
    }

    private fun confirmSourceDeletion(batchId: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        lifecycleScope.launch {
            val records = moveRecords.forBatch(batchId)
                .filter { it.state == FileState.TRANSFER_VERIFIED.name }
            val uris = records.mapNotNull { record ->
                runCatching { android.net.Uri.parse(record.sourceUri) }.getOrNull()
                    ?.takeIf { it.authority?.startsWith("media") == true }
            }
            if (uris.isEmpty()) return@launch
            pendingDeleteRecordIds = records.map { it.id }
            pendingDeleteBatchId = batchId
            val request = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteRequest.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }
    }

    private fun askForVideos() {
        val needed = permissionsToRequest()
        if (needed.isEmpty()) {
            model.onAccessResult(granted = hasFullVideoAccess(), partial = hasPartialAccess())
        } else {
            accessRequest.launch(needed)
        }
    }

    private fun reportAccess(result: Map<String, Boolean>) {
        val full = hasFullVideoAccess() && hasDeletePermission() ||
            (result[currentReadPermission()] == true && hasDeletePermission())
        val partial = hasPartialAccess() || result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        model.onAccessResult(granted = full, partial = partial && !full)
    }

    private fun permissionsToRequest(): Array<String> {
        val missing = ArrayList<String>()
        val read = currentReadPermission()
        if (!isGranted(read)) missing += read
        if (Build.VERSION.SDK_INT <= 28 && !isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        if (Build.VERSION.SDK_INT >= 34 && !isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            missing += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        if (Build.VERSION.SDK_INT >= 33 && !isGranted(Manifest.permission.POST_NOTIFICATIONS)) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        return missing.toTypedArray()
    }

    private fun currentReadPermission(): String {
        return if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
    }

    private fun hasFullVideoAccess(): Boolean = isGranted(currentReadPermission())

    private fun hasDeletePermission(): Boolean =
        Build.VERSION.SDK_INT > 28 || isGranted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun hasPartialAccess(): Boolean {
        return Build.VERSION.SDK_INT >= 34 && isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun isGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        val scheme = darkColorScheme(
            primary = Color(0xFFEF6A33),
            onPrimary = Color(0xFF1A0D07),
            background = Color(0xFF12110E),
            surface = Color(0xFF1C1A16),
            onBackground = Color(0xFFF4EFE6),
            onSurface = Color(0xFFF4EFE6),
        )
    }
}
