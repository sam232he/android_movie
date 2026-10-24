package com.videomover

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import com.videomover.data.DestinationRepository
import com.videomover.data.DeviceVideoRepository
import com.videomover.destination.SelectedFileSize
import com.videomover.ui.destination.DestinationScreen
import com.videomover.ui.destination.DestinationViewModel
import com.videomover.ui.library.LibraryScreen
import com.videomover.ui.library.LibraryViewModel

private enum class AppScreen {
    LIBRARY,
    DESTINATION,
}

class MainActivity : ComponentActivity() {
    private val repository by lazy { DeviceVideoRepository(applicationContext) }
    private val destinations by lazy { DestinationRepository(applicationContext) }

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
                var screen by remember { mutableStateOf(AppScreen.LIBRARY) }
                LaunchedEffect(library.selection, library.items) {
                    destinationModel.setSelected(
                        library.items
                            .filter { it.id in library.selection }
                            .map { SelectedFileSize(it.id, it.fileName, it.sizeBytes) },
                    )
                }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        TextButton(onClick = { screen = AppScreen.LIBRARY }) {
                            Text(
                                "Library",
                                color = if (screen == AppScreen.LIBRARY) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                        TextButton(onClick = { screen = AppScreen.DESTINATION }) {
                            Text(
                                "USB",
                                color = if (screen == AppScreen.DESTINATION) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
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
                            )
                        }
                    }
                }
            }
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

    private fun askForVideos() {
        val needed = permissionsToRequest()
        if (needed.isEmpty()) {
            model.onAccessResult(granted = hasFullVideoAccess(), partial = hasPartialAccess())
        } else {
            accessRequest.launch(needed)
        }
    }

    private fun reportAccess(result: Map<String, Boolean>) {
        val full = hasFullVideoAccess() || result[currentReadPermission()] == true
        val partial = hasPartialAccess() || result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true
        model.onAccessResult(granted = full, partial = partial && !full)
    }

    private fun permissionsToRequest(): Array<String> {
        val missing = ArrayList<String>()
        val read = currentReadPermission()
        if (!isGranted(read)) missing += read
        if (Build.VERSION.SDK_INT >= 34 && !isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)) {
            missing += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
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
