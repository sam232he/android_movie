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
import androidx.activity.result.IntentSenderRequest
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.videomover.data.DestinationRepository
import com.videomover.data.DeviceVideoRepository
import com.videomover.data.MoveRecordStore
import com.videomover.data.RecoveryCoordinator
import com.videomover.data.SafTransferRepository
import com.videomover.data.TransferRun
import com.videomover.destination.SelectedFileSize
import com.videomover.library.VideoItem
import com.videomover.library.formatBytes
import com.videomover.model.OutputResolution
import com.videomover.preflight.estimatePreflight
import com.videomover.transfer.CollisionAction
import com.videomover.transfer.CollisionResolution
import com.videomover.transfer.TransferResult
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
    private val transfers by lazy { SafTransferRepository(applicationContext, moveRecords) }
    private val outputs by lazy { com.videomover.data.OriginalOutputRepository(applicationContext, moveRecords) }
    private val conversions by lazy { com.videomover.data.Media3ConversionRepository(applicationContext, moveRecords) }
    private var pendingDeleteRecord: com.videomover.model.MoveFileRecord? = null
    private var pendingDeleteCallback: ((String) -> Unit)? = null
    private var pendingOutputCollisionCallback: ((OutputCollisionPrompt) -> Unit)? = null

    private val deleteRequest = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val record = pendingDeleteRecord
        val callback = pendingDeleteCallback
        val outputCollisionCallback = pendingOutputCollisionCallback
        pendingDeleteRecord = null
        pendingDeleteCallback = null
        pendingOutputCollisionCallback = null
        if (record == null) return@registerForActivityResult
        val deleted = result.resultCode == RESULT_OK
        lifecycleScope.launch {
            moveRecords.upsert(
                record.copy(
                    state = if (deleted) com.videomover.machine.FileState.SOURCE_DELETED.name else com.videomover.machine.FileState.NEEDS_ATTENTION.name,
                    sourceDeletedAtEpochMs = if (deleted) System.currentTimeMillis() else null,
                    attentionReason = if (deleted) null else com.videomover.machine.AttentionReason.DELETE_CONFIRMATION.name,
                    errorMessage = if (deleted) null else "Android delete confirmation was denied. Both copies were kept.",
                    updatedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            if (deleted) {
                convertRecord(record.id, callback, outputCollisionCallback)
            } else {
                callback?.invoke("Delete was denied; both copies were kept.")
            }
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
                var screen by remember { mutableStateOf(AppScreen.LIBRARY) }
                var moving by remember { mutableStateOf(false) }
                var transferMessage by remember { mutableStateOf<String?>(null) }
                var collision by remember { mutableStateOf<CollisionPrompt?>(null) }
                var outputCollision by remember { mutableStateOf<OutputCollisionPrompt?>(null) }
                var resolution by remember { mutableStateOf(OutputResolution.P480) }
                var summaryVisible by remember { mutableStateOf(false) }
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
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = screen == AppScreen.LIBRARY,
                                onClick = { screen = AppScreen.LIBRARY },
                                icon = { Text("Lib") },
                                label = { Text("Library") },
                            )
                            NavigationBarItem(
                                selected = screen == AppScreen.DESTINATION,
                                onClick = { screen = AppScreen.DESTINATION },
                                icon = { Text("Dst") },
                                label = { Text("Destination") },
                            )
                            NavigationBarItem(
                                selected = screen == AppScreen.RESULTS,
                                onClick = { screen = AppScreen.RESULTS },
                                icon = { Text("Res") },
                                label = { Text("Results") },
                            )
                        }
                    },
                ) { padding ->
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
                            )
                            AppScreen.DESTINATION -> DestinationScreen(
                                state = destination,
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
                            AppScreen.RESULTS -> ResultsScreen(records)
                        }
                    }
                }
                if (summaryVisible) {
                    AlertDialog(
                        onDismissRequest = { summaryVisible = false },
                        title = { Text("Review move") },
                        text = {
                            Column {
                                Text("Files: ${preflight.fileCount}")
                                Text("Source size: ${formatBytes(preflight.selectedBytes)}")
                                Text("Estimated converted output: ${formatBytes(preflight.estimatedOutputBytes)}")
                                Text("USB free: ${formatBytes(preflight.usbFreeBytes)}")
                                Text("Phone free: ${formatBytes(preflight.phoneFreeBytes)}")
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
                                        val batchId = "batch-${System.currentTimeMillis()}"
                                        selectedItems.forEach { item ->
                                            com.videomover.data.MoveWorkScheduler.enqueue(
                                                context = applicationContext,
                                                batchId = batchId,
                                                item = item,
                                                destinationTreeUri = tree,
                                                resolution = resolution,
                                            )
                                        }
                                        transferMessage = "${selectedItems.size} file${if (selectedItems.size == 1) "" else "s"} queued at ${resolution.label}. Progress and Cancel are available in the notification."
                                        summaryVisible = false
                                    }
                                },
                            ) { Text("Confirm Move") }
                        },
                        dismissButton = { TextButton(onClick = { summaryVisible = false }) { Text("Back") } },
                    )
                }
                outputCollision?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { outputCollision = null },
                        title = { Text("Output filename already exists") },
                        text = { Text("${prompt.fileName} already exists beside the original. Nothing will be overwritten.") },
                        confirmButton = {
                            TextButton(onClick = {
                                outputCollision = null
                                 placeConverted(
                                     recordId = prompt.recordId,
                                     file = prompt.file,
                                     collision = CollisionResolution(CollisionAction.RENAME, suggestedName(prompt.fileName)),
                                     onMessage = { transferMessage = it },
                                 )
                            }) { Text("Rename") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    outputCollision = null
                                     placeConverted(
                                         recordId = prompt.recordId,
                                         file = prompt.file,
                                         collision = CollisionResolution(CollisionAction.SKIP),
                                         onMessage = { transferMessage = it },
                                     )
                                }) { Text("Skip") }
                                TextButton(onClick = {
                                    outputCollision = null
                                     placeConverted(
                                         recordId = prompt.recordId,
                                         file = prompt.file,
                                         collision = CollisionResolution(CollisionAction.CANCEL),
                                         onMessage = { transferMessage = it },
                                     )
                                }) { Text("Cancel") }
                            }
                        },
                    )
                }
                collision?.let { prompt ->
                    AlertDialog(
                        onDismissRequest = { collision = null },
                        title = { Text("Destination filename already exists") },
                        text = { Text("${prompt.item.fileName} will not be overwritten. Choose what to do with this file.") },
                        confirmButton = {
                            TextButton(onClick = {
                                collision = null
                                moveOne(
                                    item = prompt.item,
                                    treeUri = prompt.treeUri,
                                    collisionResolution = CollisionResolution(CollisionAction.RENAME, suggestedName(prompt.item.fileName)),
                                    onMessage = { transferMessage = it },
                                    onCollision = { collision = it },
                                    onMoving = { moving = it },
                                    onOutputCollision = { outputCollision = it },
                                )
                            }) { Text("Rename") }
                        },
                        dismissButton = {
                            Row {
                                TextButton(onClick = {
                                    collision = null
                                    moveOne(prompt.item, prompt.treeUri, CollisionResolution(CollisionAction.SKIP), { transferMessage = it }, { collision = it }, { moving = it }, { outputCollision = it })
                                }) { Text("Skip") }
                                TextButton(onClick = {
                                    collision = null
                                    moveOne(prompt.item, prompt.treeUri, CollisionResolution(CollisionAction.CANCEL), { transferMessage = it }, { collision = it }, { moving = it }, { outputCollision = it })
                                }) { Text("Cancel") }
                            }
                        },
                    )
                }
            }
        }
        lifecycleScope.launch {
            RecoveryCoordinator(moveRecords).reconcile()
        }
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

    private data class CollisionPrompt(val item: VideoItem, val treeUri: String)
    private data class OutputCollisionPrompt(val recordId: String, val file: java.io.File, val fileName: String)

    private fun moveOne(
        item: VideoItem,
        treeUri: String,
        collisionResolution: CollisionResolution,
        onMessage: (String) -> Unit,
        onCollision: (CollisionPrompt) -> Unit,
        onMoving: (Boolean) -> Unit,
        onOutputCollision: (OutputCollisionPrompt) -> Unit = {},
    ) {
        lifecycleScope.launch {
            onMoving(true)
            val run = transfers.moveOne(
                batchId = "manual-${System.currentTimeMillis()}",
                item = item,
                destinationTreeUri = treeUri,
                resolution = OutputResolution.P480,
                collision = collisionResolution,
            )
            when (val result = run.result) {
                is TransferResult.Collision -> onCollision(CollisionPrompt(item, treeUri))
                is TransferResult.Completed -> convertRecord(run.record.id, { onMessage(it) }, onOutputCollision)
                is TransferResult.NeedsAttention -> {
                    if (run.deleteIntentSender != null) {
                        requestDeleteConfirmation(run, onMessage, onOutputCollision)
                    } else {
                        onMessage(result.message)
                    }
                }
                TransferResult.Cancelled -> onMessage("${item.fileName} cancelled. Nothing was deleted.")
                TransferResult.Skipped -> onMessage("${item.fileName} skipped. Nothing was deleted.")
                is TransferResult.Verified -> onMessage("${item.fileName} transferred and verified.")
            }
            onMoving(false)
        }
    }

    private fun convertRecord(
        recordId: String,
        onMessage: ((String) -> Unit)? = null,
        onCollision: ((OutputCollisionPrompt) -> Unit)? = null,
    ) {
        lifecycleScope.launch {
            val conversion = conversions.convert(recordId)
            val file = conversion.outputFile
            if (file != null) {
                placeConverted(conversion.record.id, file, CollisionResolution(CollisionAction.ASK), onMessage, onCollision, conversion.outputSize)
            } else {
                onMessage?.invoke(conversion.record.errorMessage ?: "conversion failed; USB original was kept.")
            }
        }
    }

    private fun placeConverted(
        recordId: String,
        file: java.io.File,
        collision: CollisionResolution,
        onMessage: ((String) -> Unit)? = null,
        onCollision: ((OutputCollisionPrompt) -> Unit)? = null,
        outputSize: String? = null,
    ) {
        lifecycleScope.launch {
            when (val result = outputs.place(recordId, file, collision)) {
                is com.videomover.data.OutputPlacementResult.Placed -> onMessage?.invoke("moved, verified, converted, and saved beside the original (${outputSize ?: "MP4"}).")
                is com.videomover.data.OutputPlacementResult.Collision -> onCollision?.invoke(OutputCollisionPrompt(recordId, file, result.fileName))
                com.videomover.data.OutputPlacementResult.Skipped -> onMessage?.invoke("Output skipped; the USB original remains untouched.")
                com.videomover.data.OutputPlacementResult.Cancelled -> onMessage?.invoke("Output cancelled; the USB original remains untouched.")
                is com.videomover.data.OutputPlacementResult.NeedsAttention -> onMessage?.invoke(result.message)
            }
        }
    }

    private fun requestDeleteConfirmation(
        run: TransferRun,
        onMessage: (String) -> Unit,
        onOutputCollision: (OutputCollisionPrompt) -> Unit,
    ) {
        val sender = run.deleteIntentSender ?: return
        pendingDeleteRecord = run.record
        pendingDeleteCallback = onMessage
        pendingOutputCollisionCallback = onOutputCollision
        deleteRequest.launch(IntentSenderRequest.Builder(sender).build())
    }

    private fun suggestedName(original: String): String {
        val dot = original.lastIndexOf('.')
        return if (dot > 0) {
            original.substring(0, dot) + " (1)" + original.substring(dot)
        } else {
            "$original (1)"
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
