package com.mathnotes.solver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.NotepadHeader
import com.mathnotes.solver.ui.components.NotepadLabel
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts

/** Insert helpers mirroring the token row in the mockup; tapping appends the snippet. */
private val LATEX_TOKENS = listOf("\\int", "\\frac{}{}", "^{}", "\\sqrt{}", "\\sin", "\\ln", "\\sum")

/** Topic chip label → the canonical topic value the backend expects. */
private val TOPIC_OPTIONS = listOf("Integrate" to "integral", "Differentiate" to "derivative", "Series" to "series")

/**
 * Step 2: the human in the loop, styled as a page of notepaper (mockup 02_review).
 * Shows the preprocessed crop, the recognized LaTeX (editable with a live typeset
 * preview), its confidence, and the topic; Retake / solve live in the bottom bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(vm: SolverViewModel, onSolved: () -> Unit, onRetake: () -> Unit) {
    val state by vm.state.collectAsState()
    LaunchedEffect(state.result) { if (state.result != null) onSolved() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Review recognition", style = MaterialTheme.typography.headlineSmall)

        state.imageFile?.let {
            Text(
                "Preprocessed crop — exactly what was sent to the recognizer",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Each capture writes a uniquely-named *_pre.jpg, so this is the post-crop
            // file for this problem — exactly the bytes uploaded to /recognize.
            AsyncImage(
                model = it,
                contentDescription = "Preprocessed crop",
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentScale = ContentScale.Fit,
            )
            OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Re-take") }
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .ruledPaper()
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, end = 20.dp, top = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // The scanned/preprocessed crop, pinned to the page at a slight angle.
            state.imageFile?.let { file ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Preprocessed crop",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .graphicsLayer { rotationZ = -2f }
                            .fillMaxWidth(0.84f)
                            .height(150.dp)
                            .shadow(6.dp)
                            .background(NotepadColors.PaperCard)
                            .border(1.dp, NotepadColors.HeaderBorder),
                    )
                }
            }

            if (state.busy) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = NotepadColors.Ink)
                    NotepadLabel(state.status, fontSize = 16)
                }
                return@Column
            }

            state.error?.let { NotepadLabel(it, fontSize = 16, color = NotepadColors.Red) }

            if (state.latex.isNotEmpty()) {
                // "I read this as" + the typeset reading, with a confidence pill.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        NotepadLabel("I read this as", fontSize = 16)
                        LatexView(state.latex, Modifier.padding(top = 4.dp), textSizePx = 56f)
                    }
                    ConfidencePill(state.confidence, state.lowConfidence)
                }

                NotepadLabel("LaTeX, fix it if I got it wrong")
                OutlinedTextField(
                    value = state.latex,
                    onValueChange = vm::updateLatex,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = NotepadColors.Ink),
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NotepadColors.Ink,
                        unfocusedBorderColor = NotepadColors.Ink,
                        cursorColor = NotepadColors.Ink,
                    ),
                    minLines = 1,
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LATEX_TOKENS.forEach { tok ->
                        TokenChip(tok) { vm.updateLatex(state.latex + tok) }
                    }
                }

                NotepadLabel("What should I do with it?")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TOPIC_OPTIONS.forEach { (label, value) ->
                        TopicPill(label, selected = state.topic == value, modifier = Modifier.weight(1f)) { vm.updateTopic(value) }
                    }
                }

                if (state.lowConfidence) {
                    StickyNote(rotationDeg = 1f) {
                        Text(
                            "Low confidence? Retake with the page flat and the problem inside the box.",
                            fontFamily = NotepadFonts.PatrickHand,
                            fontSize = 15.sp,
                            color = NotepadColors.NoteInkYellow,
                        )
                    }
                }
            }
        }

        NotepadBottomBar {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) { Text("Retake", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }

            Button(
                onClick = vm::solve,
                enabled = state.latex.isNotBlank(),
                modifier = Modifier.weight(1.4f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) { Text("Looks right, solve it", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        }
    }
}

@Composable
private fun ConfidencePill(confidence: Double, lowConfidence: Boolean) {
    val accent = if (lowConfidence) NotepadColors.Red else NotepadColors.Green
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.5.dp, accent, RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            "${(confidence * 100).toInt()}% sure",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 15.sp,
            color = accent,
        )
    }
}

@Composable
private fun TokenChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(NotepadColors.HeaderBand)
            .border(1.dp, NotepadColors.ChipBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NotepadColors.Ink)
    }
}

@Composable
private fun TopicPill(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val base = modifier
        .clip(RoundedCornerShape(999.dp))
        .background(if (selected) NotepadColors.Ink else Color.Transparent)
    val bordered = if (selected) base else base.border(1.5.dp, NotepadColors.Ink, RoundedCornerShape(999.dp))
    Box(
        bordered.clickable(onClick = onClick).padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 16.sp,
            color = if (selected) NotepadColors.Paper else NotepadColors.Ink,
        )
    }
}
