package com.videomover.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videomover.error.describeFailure
import com.videomover.machine.AttentionReason
import com.videomover.model.MoveFileRecord

@Composable
fun ResultsScreen(
    records: List<MoveFileRecord>,
    onStartNew: () -> Unit,
    onConfirmDelete: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Conversion results", style = MaterialTheme.typography.headlineSmall)
        Button(onClick = onStartNew, modifier = Modifier.fillMaxWidth()) { Text("Start a new move") }
        if (onConfirmDelete != null) {
            Button(onClick = onConfirmDelete, modifier = Modifier.fillMaxWidth()) {
                Text("Confirm deletion, then convert")
            }
        }
        if (records.isEmpty()) {
            Text("No queued files yet.")
        } else {
            Text("${records.size} file${if (records.size == 1) "" else "s"} tracked")
            records.forEach { record ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(record.originalFileName, style = MaterialTheme.typography.titleMedium)
                    record.destinationFileName?.let { Text("Verified original copy: $it") }
                    Text("${record.progressPercent}% - ${stateLabel(record.state)}")
                    Text("Resolution: ${record.outputResolution.label}")
                    record.errorMessage?.let { detail ->
                        val reason = record.attentionReason?.let { value ->
                            runCatching { AttentionReason.valueOf(value) }.getOrNull()
                        }
                        val failure = describeFailure(reason, detail)
                        Text(failure.title, style = MaterialTheme.typography.labelLarge)
                        Text(failure.message, color = MaterialTheme.colorScheme.primary)
                        if (failure.action.name != "NONE") {
                            Text("Next step: ${failure.action.name.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    record.outputUri?.let { Text("Converted MP4 saved in the original folder", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

private fun stateLabel(state: String): String = when (state) {
    "PENDING" -> "Queued to copy"
    "TRANSFER_VERIFIED" -> "Waiting for source deletion approval"
    "SOURCE_DELETED" -> "Ready to convert"
    "CONVERTING" -> "Converting"
    "CONVERTED" -> "Saved"
    "NEEDS_ATTENTION" -> "Needs attention"
    "FAILED" -> "Failed"
    "CANCELLED" -> "Cancelled"
    else -> state.lowercase().replace('_', ' ')
}
