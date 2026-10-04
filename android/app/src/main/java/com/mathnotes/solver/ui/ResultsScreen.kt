package com.mathnotes.solver.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mathnotes.solver.export.PdfExporter

/**
 * Step 6–7: each step as typeset math with its rule name; tap a step to see
 * the explanation; verification badge on top; export to PDF.
 */
@Composable
fun ResultsScreen(vm: SolverViewModel, onNewProblem: () -> Unit) {
    val state by vm.state.collectAsState()
    val result = state.result ?: return
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Solution — ${result.topic}", style = MaterialTheme.typography.headlineSmall)

        val (badge, color) = when {
            result.verified -> "Verified: every step agrees with SymPy" to Color(0xFF008300)
            result.mismatchAt != null -> "Mismatch at step ${result.mismatchAt + 1} — tap it to see why" to Color(0xFFB3261E)
            else -> "Not verified" to Color(0xFFEB6834)
        }
        Card(colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f))) {
            Text(badge, Modifier.padding(12.dp), color = color, style = MaterialTheme.typography.labelLarge)
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(result.steps) { i, step ->
                val flagged = result.mismatchAt == i
                Card(
                    Modifier.fillMaxWidth().clickable { expanded = if (expanded == i) null else i },
                    colors = CardDefaults.cardColors(
                        containerColor = if (flagged) Color(0xFFB3261E).copy(alpha = 0.10f) else MaterialTheme.colorScheme.surfaceVariant
                    ),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${i + 1}. ${step.rule}", style = MaterialTheme.typography.labelLarge)
                        LatexView(step.afterLatex, textSizePx = 40f)
                        if (expanded == i) {
                            Text("Why: ${step.note}", style = MaterialTheme.typography.bodySmall)
                            Text("From:", style = MaterialTheme.typography.labelSmall)
                            LatexView(step.beforeLatex, textSizePx = 32f)
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Answer", style = MaterialTheme.typography.labelLarge)
                        LatexView(result.answerLatex, textSizePx = 48f)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { PdfExporter.exportAndShare(ctx, state) }, modifier = Modifier.weight(1f)) { Text("Export PDF") }
            OutlinedButton(onClick = { vm.reset(); onNewProblem() }, modifier = Modifier.weight(1f)) { Text("New problem") }
        }
    }
}
