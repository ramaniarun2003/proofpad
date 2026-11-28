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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import coil.compose.AsyncImage
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mathnotes.solver.data.api.BackendState
import com.mathnotes.solver.ui.components.BackendSheet
import com.mathnotes.solver.ui.components.BackendStatusChip
import com.mathnotes.solver.data.api.Claim
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.NotepadHeader
import com.mathnotes.solver.ui.components.NotepadLabel
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts

/**
 * Insert helpers mirroring the token row in the mockup; tapping appends the snippet.
 * Includes the multivariable operators the solver accepts: \iint / \iiint for double and
 * triple integrals, and \partial for partial derivatives (build ∂/∂x with \frac{\partial}{\partial x}).
 */
private val LATEX_TOKENS = listOf("\\int", "\\iint", "\\iiint", "\\partial", "\\frac{}{}", "^{}", "\\sqrt{}", "\\sin", "\\ln", "\\sum")

/** Topic chip label → the canonical topic value the backend expects. */
private val TOPIC_OPTIONS = listOf("Integrate" to "integral", "Differentiate" to "derivative", "Series" to "series", "Limit" to "limit")

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
    // Restarted with nothing to restore (an old or unreadable saved state): go back to Capture.
    LaunchedEffect(Unit) { if (!vm.state.value.hasCapture) onRetake() }

    // Recognition failed: the error note below offers Retry and the connection chip + sheet.
    val backendState by vm.backend.collectAsState()
    var showBackendSheet by remember { mutableStateOf(false) }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.setBackendUrl(it) }
    }
    // The connection came back while the note is up: send the kept photo again.
    LaunchedEffect(backendState, state.recognizeError) {
        if (state.recognizeError == CANT_REACH_NOTE && backendState is BackendState.Connected) vm.retryRecognize()
    }
    if (showBackendSheet) {
        BackendSheet(
            state = backendState,
            savedUrl = vm.savedBackendUrl,
            onSearchAgain = vm::searchBackend,
            onScanQr = {
                qrScanner.launch(
                    ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt("Scan the QR code your solver printed").setBeepEnabled(false).setOrientationLocked(false),
                )
            },
            onSaveUrl = vm::setBackendUrl,
            onDismiss = { showBackendSheet = false },
            onUseDefault = vm::useDefaultBackend,
        )
    }

    Column(Modifier.fillMaxSize()) {
        NotepadHeader(title = "Check my reading", trailing = "step 2 of 3")

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

            state.recognizeError?.let { message ->
                StickyNote(Modifier.fillMaxWidth(0.94f), background = NotepadColors.StickyPink, rotationDeg = -1f) {
                    Text("I couldn't read this yet", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.NoteInkPink)
                    Text(message, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkPink)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = vm::retryRecognize,
                        modifier = Modifier.height(48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
                    ) { Text("Retry", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
                    BackendStatusChip(backendState, onClick = { showBackendSheet = true }, onPaper = true)
                }
            }

            if (state.latex.isNotEmpty()) {
                // A page of several problems: one is loaded, the others are a tap away.
                if (state.problems.size > 1) {
                    NotepadLabel("I found ${state.problems.size} problems here, which one?", fontSize = 16)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.problems.indices.forEach { i ->
                            TopicPill("${i + 1}", selected = i == state.problemIndex, modifier = Modifier.weight(1f)) {
                                vm.selectProblem(i)
                            }
                        }
                    }
                }
                // "I read this as" + the typeset reading, with a confidence pill.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        NotepadLabel("I read this as", fontSize = 16)
                        // Long-press to compare against the raw Mathpix string (before corrections).
                        RawLatexTooltip(state.rawLatex) {
                            LatexView(displayProblem(state.latex), Modifier.padding(top = 4.dp), textSizePx = 56f)
                        }
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
                splitCaption(state.splitAt)?.let { NotepadLabel(it, fontSize = 14) }
                if (state.parseError != null) {
                    StickyNote(Modifier.fillMaxWidth(0.92f), rotationDeg = -1f) {
                        Text(
                            parseNote(state.parseError),
                            fontFamily = NotepadFonts.PatrickHand,
                            fontSize = 15.sp,
                            color = NotepadColors.NoteInkYellow,
                        )
                    }
                }
                if (state.splitUncertain) {
                    StickyNote(Modifier.fillMaxWidth(0.92f), rotationDeg = -1f) {
                        Text(
                            "I could not tell the problem from your work, check the split",
                            fontFamily = NotepadFonts.PatrickHand,
                            fontSize = 15.sp,
                            color = NotepadColors.NoteInkYellow,
                        )
                    }
                }

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

                NotepadLabel("Your step-by-step working:")
                state.userSteps.forEachIndexed { index, stepText ->
                    // A series step is a claim, not an expression: a toggle, prefilled from the read.
                    val isClaim = Claim.isClaim(stepText) || (state.topic == "series" && stepText.isBlank())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isClaim) {
                            TopicPill("Converges", selected = stepText.trim() == Claim.CONVERGES, modifier = Modifier.weight(1f)) {
                                vm.updateUserStep(index, Claim.CONVERGES)
                            }
                            TopicPill("Diverges", selected = stepText.trim() == Claim.DIVERGES, modifier = Modifier.weight(1f)) {
                                vm.updateUserStep(index, Claim.DIVERGES)
                            }
                        } else OutlinedTextField(
                            value = stepText,
                            onValueChange = { vm.updateUserStep(index, it) },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Step ${index + 1} LaTeX") },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = NotepadColors.Ink),
                            shape = RoundedCornerShape(8.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = NotepadColors.Ink,
                                unfocusedBorderColor = NotepadColors.ChipBorder,
                                cursorColor = NotepadColors.Ink,
                            ),
                            minLines = 1,
                        )
                        StepOverflowMenu(
                            onMerge = if (isClaim) null else ({ vm.mergeStepIntoProblem(index) }),
                            onRemove = { vm.removeUserStep(index) },
                        )
                    }
                }
                OutlinedButton(
                    onClick = vm::addUserStep,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, NotepadColors.ChipBorder),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
                ) { Text("+ Add Step", fontFamily = NotepadFonts.Inter, fontSize = 14.sp) }

                // Per-user correction suggestions (SUGGEST rules) — one-tap swap.
                state.suggestions.forEach { s ->
                    StickyNote(
                        Modifier.clickable { vm.applySuggestion(s) }.fillMaxWidth(0.92f),
                        rotationDeg = -1f,
                    ) {
                        Text(
                            s.message,
                            fontFamily = NotepadFonts.PatrickHand,
                            fontSize = 15.sp,
                            color = NotepadColors.NoteInkYellow,
                        )
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
                onClick = vm::verify,
                enabled = state.latex.isNotBlank() && !state.busy,
                modifier = Modifier.weight(1.4f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) { Text(if (state.userSteps.any { it.isNotBlank() }) "Check my steps" else "Solve it", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        }
    }
}

/** Per-step ⋮ menu: fold the step back into the problem (undo a wrong split) or delete it. */
@Composable
private fun StepOverflowMenu(onMerge: (() -> Unit)?, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .semantics { contentDescription = "Step options" }
                .padding(horizontal = 10.dp, vertical = 12.dp),
        ) { Text("⋮", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = NotepadColors.Ink) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onMerge != null) {
                DropdownMenuItem(
                    text = { Text("Merge into problem", fontFamily = NotepadFonts.Inter) },
                    onClick = { open = false; onMerge() },
                )
            }
            DropdownMenuItem(
                text = { Text("Remove step", fontFamily = NotepadFonts.Inter) },
                onClick = { open = false; onRemove() },
            )
        }
    }
}

/** Wraps [content] so a long-press shows the raw Mathpix LaTeX (before the correction map). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RawLatexTooltip(raw: String, content: @Composable () -> Unit) {
    if (raw.isBlank()) { content(); return }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text("Mathpix read:  $raw") } },
        state = rememberTooltipState(isPersistent = false),
    ) { content() }
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
