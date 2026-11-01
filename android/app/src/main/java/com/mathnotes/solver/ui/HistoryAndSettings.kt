package com.mathnotes.solver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/** Cached sessions from Room — offline review and the demo-day fallback. */
@Composable
fun HistoryScreen(vm: SolverViewModel, onOpen: () -> Unit) {
    val history by vm.history.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = MaterialTheme.typography.headlineSmall)
        if (history.isEmpty()) Text("No saved sessions yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(history) { s ->
                Card(Modifier.fillMaxWidth().clickable { vm.openCached(s); onOpen() }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(s.createdAt)), style = MaterialTheme.typography.labelSmall)
                        LatexView(s.confirmedLatex.ifEmpty { s.recognizedLatex }, textSizePx = 36f)
                        Text(
                            "${s.topic} · " + when (s.verified) { true -> "verified"; false -> "mismatch"; null -> "not solved" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

/** Where the backend lives on today's LAN. */
@Composable
fun SettingsScreen(vm: SolverViewModel, onDone: () -> Unit) {
    var url by remember { mutableStateOf(vm.backendUrl) }
    var debugImages by remember { mutableStateOf(vm.debugImages) }
    var adaptiveThreshold by remember { mutableStateOf(vm.useAdaptiveThreshold) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backend URL", style = MaterialTheme.typography.headlineSmall)
        Text("Laptop running uvicorn on the same Wi-Fi, e.g. http://192.168.1.42:8000/. Emulator → http://10.0.2.2:8000/.")
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Text("Debug", style = MaterialTheme.typography.titleMedium)
        SettingToggle(
            label = "Save preprocessing stages",
            help = "Write each stage (raw/crop/gray/flat/final) to filesDir/debug.",
            checked = debugImages,
            onChange = { debugImages = it; vm.debugImages = it },
        )
        SettingToggle(
            label = "Use adaptive threshold",
            help = "Binarize instead of the gentle grayscale pipeline — for A/B comparison.",
            checked = adaptiveThreshold,
            onChange = { adaptiveThreshold = it; vm.useAdaptiveThreshold = it },
        )

        Button(onClick = { vm.setBackendUrl(url.trim()); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

@Composable
private fun SettingToggle(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
