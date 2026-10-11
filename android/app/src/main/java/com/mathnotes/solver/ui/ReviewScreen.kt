package com.mathnotes.solver.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

/**
 * Step 4: the human in the loop. Shows the preprocessed crop, the recognized
 * LaTeX (editable) and its live typeset preview, the confidence, and the topic.
 */
@Composable
fun ReviewScreen(vm: SolverViewModel, onSolved: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.result) { if (state.result != null) onSolved() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Review recognition", style = MaterialTheme.typography.headlineSmall)

        state.imageFile?.let {
            Text(
                "Preprocessed — exactly what was sent to the recognizer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AsyncImage(model = it, contentDescription = "Preprocessed page", modifier = Modifier.fillMaxWidth().height(180.dp), contentScale = ContentScale.Fit)
        }

        if (state.busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(); Text(state.status) }
            return@Column
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.latex.isNotEmpty()) {
            Text(
                "Confidence ${(state.confidence * 100).toInt()}%" + if (state.lowConfidence) " — please check carefully" else "",
                color = if (state.lowConfidence) Color(0xFFEB6834) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card { LatexView(state.latex, Modifier.padding(12.dp)) }
            OutlinedTextField(
                value = state.latex,
                onValueChange = vm::updateLatex,
                label = { Text("LaTeX (edit if wrong)") },
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 2,
            )
            Text("Topic", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("derivative", "integral", "series").forEach { t ->
                    FilterChip(selected = state.topic == t, onClick = { vm.updateTopic(t) }, label = { Text(t) })
                }
            }
            Button(onClick = vm::solve, modifier = Modifier.fillMaxWidth(), enabled = state.latex.isNotBlank()) { Text("Solve") }
        }
    }
}
