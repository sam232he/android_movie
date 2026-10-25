package com.videomover.ui.results

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.videomover.error.describeFailure
import com.videomover.machine.AttentionReason
import com.videomover.model.MoveFileRecord

@Composable
fun ResultsScreen(records: List<MoveFileRecord>) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Move results", style = MaterialTheme.typography.headlineSmall)
        if (records.isEmpty()) {
            Text("No queued files yet.")
        } else {
            Text("${records.size} file${if (records.size == 1) "" else "s"} tracked")
            records.forEach { record ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(record.originalFileName, style = MaterialTheme.typography.titleMedium)
                    Text("State: ${record.state}")
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
                    record.outputUri?.let { Text("Output saved", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}
