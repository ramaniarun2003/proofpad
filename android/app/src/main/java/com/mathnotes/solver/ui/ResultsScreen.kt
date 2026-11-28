package com.mathnotes.solver.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.api.Verdict
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.export.PdfExporter
import com.mathnotes.solver.ui.components.HandDrawnBox
import com.mathnotes.solver.ui.components.NotepadLabel
import com.mathnotes.solver.ui.components.NotepadPage
import com.mathnotes.solver.ui.components.Stamp
import com.mathnotes.solver.ui.components.StepRow
import com.mathnotes.solver.ui.components.StepStatus
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Rules too mechanical to be the default "why this step" explanation. */
private val TRIVIAL_RULES = setOf(
    "constant multiple", "linearity (constant multiple)", "linearity (sum)",
    "constant rule", "identity", "sum rule",
)

private fun firstNonTrivial(steps: List<Step>): Int? {
    if (steps.isEmpty()) return null
    val idx = steps.indexOfFirst { it.rule !in TRIVIAL_RULES }
    return if (idx < 0) 0 else idx
}

private fun sympyFn(topic: String): String = when (topic) {
    "integral" -> "integrate"
    "derivative" -> "diff"
    "limit" -> "limit"
    "series" -> "is_convergent"
    else -> "simplify"
}

/**
 * Step 6–7, styled as a notepad page (mockups 03_results_verified / 04_results_flagged).
 * Stateless so both states can be previewed and screenshotted; [ResultsScreen] binds it to the VM.
 */
@Composable
fun ResultsScreen(vm: SolverViewModel, onNewProblem: () -> Unit, onSaveToGallery: () -> Unit, onFixReading: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    // Restarted with no result to restore: go back to Capture instead of a blank page.
    LaunchedEffect(Unit) { if (vm.state.value.result == null) onNewProblem() }
    val result = state.result ?: return
    val ctx = LocalContext.current
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d")) }

    // A finished PDF goes to the share sheet; a failed one opens the error sheet.
    LaunchedEffect(state.exportedFile) {
        state.exportedFile?.let { file ->
            vm.exportShared()
            runCatching { PdfExporter.share(ctx, file) }.onFailure { vm.notify(UiMessage("I couldn't open the share sheet")) }
        }
    }
    if (state.exportFailed) ExportErrorSheet(onRetry = vm::exportPdf, onDismiss = vm::dismissExportError)

    if (result.verdict == Verdict.UNSOLVED) {
        UnsolvedContent(
            problemLatex = state.latex,
            topic = result.topic,
            dateLabel = date,
            busy = state.busy,
            // Never the server's text: a plain sentence for a reading that will not parse, or the mapped error.
            problem = state.parseError ?: state.error,
            onSolve = vm::solve,
            onCancel = vm::cancelRequest,
            onFixReading = { vm.reviewUnsolved(); onFixReading() },
            onNewProblem = { vm.reset(); onNewProblem() },
        )
        return
    }

    ResultsContent(
        problemLatex = state.latex,
        topic = result.topic,
        dateLabel = date,
        steps = result.steps,
        answerLatex = result.answerLatex,
        verified = result.verified,
        mismatchAt = result.mismatchAt,
        details = result.details,
        verdict = result.verdict,
        solutionAnswerLatex = state.solutionAnswer,
        solutionBusy = state.solutionBusy,
        onShowSolution = { if (state.solutionBusy) vm.cancelRequest() else vm.showFullSolution() },
        difficulty = state.difficulty,
        onRate = vm::setDifficulty,
        onExport = vm::exportPdf,
        exporting = state.exporting,
        onNewProblem = { vm.reset(); onNewProblem() },
        onSaveToGallery = onSaveToGallery,
        savedTo = state.savedTo,
    )
}

/**
 * A capture that was read but never solved or checked. Nothing has been verified, so there is no
 * stamp, no answer box and no "agrees at every step": only the problem and a way to solve it.
 */
@Composable
fun UnsolvedContent(
    problemLatex: String,
    topic: String,
    dateLabel: String,
    busy: Boolean,
    problem: String?,
    onSolve: () -> Unit,
    onFixReading: () -> Unit,
    onNewProblem: () -> Unit,
    onCancel: () -> Unit = {},
) {
    val density = LocalDensity.current
    NotepadPage(
        title = "ProofPad",
        caption = "$dateLabel · $topic",
        bottomBar = {
            OutlinedButton(
                onClick = if (busy) onCancel else onFixReading,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) { Text(if (busy) "Cancel" else "Fix the reading", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
            Button(
                onClick = onSolve,
                enabled = !busy && problemLatex.isNotBlank(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) { Text(if (busy) "Solving…" else "Solve it", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotepadLabel("Problem", fontSize = 17)
            NotepadLabel("not solved yet", fontSize = 17)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LatexView(displayProblem(problemLatex), textSizePx = with(density) { 26.sp.toPx() }, fillWidth = false)
        }
        Spacer(Modifier.height(16.dp))
        StickyNote(Modifier.fillMaxWidth(0.94f), rotationDeg = -1f) {
            Text(
                "Not solved yet. I read this problem but it was never solved or checked.",
                fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkYellow,
            )
        }
        if (problem != null) {
            Spacer(Modifier.height(12.dp))
            StickyNote(Modifier.fillMaxWidth(0.94f), background = NotepadColors.StickyPink, rotationDeg = 1f) {
                Text(problem, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkPink)
            }
        }
        Spacer(Modifier.height(16.dp))
        OutlinedButton(
            onClick = onNewProblem,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(1.dp, NotepadColors.ChipBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
        ) { Text("New problem", fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Ink) }
    }
}

@Composable
fun ResultsContent(
    problemLatex: String,
    topic: String,
    dateLabel: String,
    steps: List<Step>,
    answerLatex: String,
    verified: Boolean,
    mismatchAt: Int?,
    details: List<String>,
    verdict: String = "",
    solutionAnswerLatex: String = "",
    solutionBusy: Boolean = false,
    onShowSolution: (() -> Unit)? = null,
    difficulty: Difficulty? = null,
    onRate: (Difficulty) -> Unit = {},
    onExport: () -> Unit,
    onNewProblem: () -> Unit,
    onSaveToGallery: (() -> Unit)? = null,
    savedTo: String? = null,
    exporting: Boolean = false,
) {
    // A student-chain check: the rows are the student's own lines, each with its own status;
    // our solution (if asked for) is listed separately below, muted.
    val allSteps = steps
    val solutionSteps = allSteps.filter { it.isSolution }
    @Suppress("NAME_SHADOWING")
    val steps = allSteps.filterNot { it.isSolution }
    val isStudentChain = steps.any { it.isStudent }
    val isIncomplete = isStudentChain && verdict == Verdict.INCOMPLETE

    val flaggedIndex = if (!verified) mismatchAt else null
    val isFlagged = flaggedIndex != null
    var selected by remember(steps) { mutableStateOf<Int?>(if (isStudentChain) null else firstNonTrivial(steps)) }
    val density = LocalDensity.current

    NotepadPage(
        title = "ProofPad",
        caption = "$dateLabel · $topic",
        bottomBar = {
            OutlinedButton(
                onClick = onExport,
                // A flagged generated solution is held back; a student's own checked work can always be exported.
                enabled = (!isFlagged || isStudentChain) && !exporting,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, if (isFlagged && !isStudentChain) NotepadColors.Held else NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NotepadColors.Ink,
                    disabledContentColor = NotepadColors.Held,
                ),
            ) {
                if (exporting) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = NotepadColors.Ink, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (exporting) "Making PDF…" else "Export PDF", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }

            Button(
                onClick = onNewProblem,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) { Text("New problem", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
    ) {
        // Header row: "Problem" on the left, the stamp on the right, so the stamp never sits on the math.
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NotepadLabel("Problem", fontSize = 17)
            Stamp(
                text = when {
                    verified -> "VERIFIED"
                    isIncomplete -> "ALMOST, finish it"
                    else -> "CHECK STEP ${(flaggedIndex ?: 0) + 1}"
                },
                verified = verified,
                tint = if (isIncomplete) NotepadColors.DifficultyMedium else null,
            )
        }
        // The problem at its own size (26sp, as in the mockup); a long one scrolls sideways
        // instead of being scaled to the page width.
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            LatexView(displayProblem(problemLatex), textSizePx = with(density) { 26.sp.toPx() }, fillWidth = false)
        }

        Spacer(Modifier.height(12.dp))

        // Steps.
        steps.forEachIndexed { i, step ->
            val status = when {
                // The verifier judged each of the student's lines on its own.
                step.isStudent && step.status == "ok" -> StepStatus.Verified
                step.isStudent -> StepStatus.Flagged
                isFlagged && i < flaggedIndex!! -> StepStatus.Verified
                isFlagged && i == flaggedIndex -> StepStatus.Flagged
                isFlagged -> StepStatus.Pending
                verified -> StepStatus.Verified
                else -> StepStatus.Plain
            }
            StepRow(
                number = i + 1,
                rule = step.rule,
                exprLatex = step.afterLatex,
                status = status,
                selected = !isFlagged && selected == i,
                onClick = { selected = if (selected == i) null else i },
            )
        }

        Spacer(Modifier.height(10.dp))

        // Explanation / verifier note.
        if (isFlagged && isStudentChain) {
            // The hint for the first wrong step, in the pink verifier note.
            val n = flaggedIndex!! + 1
            val bad = steps.getOrNull(flaggedIndex)
            val fallback = if (bad?.status == "unparseable") "I could not read step $n." else
                "Step $n doesn't follow from ${if (n == 1) "the problem" else "step ${n - 1}"}."
            VerifierNote(message = bad?.note?.ifBlank { fallback } ?: fallback)
        } else if (isIncomplete) {
            StickyNote(rotationDeg = 1.5f) {
                Text("almost there", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)
                NoteText(
                    "Every step is right, but it is " + (steps.lastOrNull()?.note?.ifBlank { null } ?: "not the final answer yet") + ".",
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 17.sp,
                    color = NotepadColors.NoteInkYellow,
                )
            }
        } else if (isFlagged) {
            val n = flaggedIndex!! + 1
            VerifierNote(message = details.joinToString(" ").ifBlank { "Step $n doesn't match step ${n - 1}." })
        } else if (selected != null && selected!! < steps.size) {
            val s = steps[selected!!]
            StickyNote(rotationDeg = 1.5f) {
                Text("why step ${selected!! + 1}", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)
                // Notes are sentences with $…$ math spans; typeset those instead of showing raw LaTeX.
                NoteText(
                    s.note.ifBlank { "No extra explanation for this step." },
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 17.sp,
                    color = NotepadColors.NoteInkYellow,
                )
                Text("tap a step to hide this", fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Muted)
            }
        }

        Spacer(Modifier.height(16.dp))

        // Answer.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(28.dp).height(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "answer",
                    Modifier.graphicsLayer { rotationZ = -90f }.wrapContentWidth(unbounded = true),
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 16.sp,
                    color = NotepadColors.Muted,
                )
            }
            Spacer(Modifier.width(8.dp))
            if (isIncomplete) {
                HandDrawnBox(dashed = true) {
                    Text(
                        "not the final answer yet",
                        fontFamily = NotepadFonts.PatrickHand,
                        fontSize = 20.sp,
                        color = NotepadColors.Held,
                    )
                }
            } else if (isFlagged) {
                HandDrawnBox(dashed = true) {
                    Text(
                        "held until step ${flaggedIndex!! + 1} is fixed",
                        fontFamily = NotepadFonts.PatrickHand,
                        fontSize = 20.sp,
                        color = NotepadColors.Held,
                    )
                }
            } else {
                HandDrawnBox(dashed = false) {
                    LatexView(answerLatex, textSizePx = with(density) { 30.sp.toPx() })
                }
            }
        }

        // Secondary action for a student chain: our own worked solution, listed below theirs.
        if (isStudentChain && onShowSolution != null && solutionSteps.isEmpty()) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onShowSolution,   // while it is solving, the same button cancels
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, NotepadColors.ChipBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) {
                Text(
                    if (solutionBusy) "Solving…  tap to cancel" else "Show me the full solution",
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Ink,
                )
            }
        }
        if (solutionSteps.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            NotepadLabel("the full solution", fontSize = 16)
            Column(Modifier.alpha(0.6f)) {
                solutionSteps.forEachIndexed { i, step ->
                    StepRow(
                        number = i + 1,
                        rule = step.rule,
                        exprLatex = step.afterLatex,
                        status = StepStatus.Plain,
                        selected = false,
                        onClick = {},
                    )
                }
                if (solutionAnswerLatex.isNotBlank()) {
                    Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        NotepadLabel("answer", fontSize = 16)
                        Spacer(Modifier.width(10.dp))
                        LatexView(solutionAnswerLatex, textSizePx = with(density) { 22.sp.toPx() }, fillWidth = false)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        DifficultyRatingRow(difficulty = difficulty, onRate = onRate)

        Spacer(Modifier.height(10.dp))

        // Muted footnote.
        val footnote = if (isIncomplete) {
            "your steps agree with SymPy ${sympyFn(topic)}(); the last line is not final yet"
        } else if (isFlagged) {
            "expected final shown once step ${flaggedIndex!! + 1} is fixed"
        } else if (isStudentChain) {
            "your steps checked against SymPy ${sympyFn(topic)}(): every line agrees"
        } else {
            "checked against SymPy ${sympyFn(topic)}(): agrees at every step"
        }
        NotepadLabel(footnote, fontSize = 16)

        // Entry to the gallery save sheet (mockup 06).
        if (onSaveToGallery != null) {
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onSaveToGallery,
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) {
                Text(
                    if (savedTo != null) "Saved to $savedTo ✓  ·  change" else "＋ Save to gallery",
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun VerifierNote(message: String) {
    StickyNote(background = NotepadColors.StickyPink, rotationDeg = -1.5f) {
        Text("verifier says", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.NoteInkPink)
        NoteText(message, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkPink)
    }
}


/**
 * Difficulty stars — one widget, one data field. Backs the gallery [Difficulty]
 * (EASY/MEDIUM/HARD → 1/2/3 stars) so Results and the Save sheet stay in sync.
 */
@Composable
private fun DifficultyRatingRow(difficulty: Difficulty?, onRate: (Difficulty) -> Unit) {
    val stars = when (difficulty) {
        Difficulty.EASY -> 1; Difficulty.MEDIUM -> 2; Difficulty.HARD -> 3; null -> 0
    }
    val accent = when (difficulty) {
        Difficulty.EASY -> NotepadColors.DifficultyEasy
        Difficulty.MEDIUM -> NotepadColors.DifficultyMedium
        Difficulty.HARD -> NotepadColors.DifficultyHard
        null -> NotepadColors.Muted
    }
    val label = when (difficulty) {
        Difficulty.EASY -> "Easy"; Difficulty.MEDIUM -> "Medium"; Difficulty.HARD -> "Hard"; null -> ""
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        NotepadLabel("How hard was it?", fontSize = 16)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            for (i in 1..3) {
                val d = when (i) { 1 -> Difficulty.EASY; 2 -> Difficulty.MEDIUM; else -> Difficulty.HARD }
                Text(
                    text = if (i <= stars) "★" else "☆",
                    fontSize = 32.sp,
                    color = if (i <= stars) accent else Color(0xFFDCDCDC),
                    modifier = Modifier.clickable { onRate(d) }.padding(horizontal = 4.dp, vertical = 2.dp),
                )
            }
            if (difficulty != null) {
                Spacer(Modifier.width(8.dp))
                Text("$label ($stars / 3)", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
            }
        }
    }
}


/** Notepad-style sheet for a PDF that could not be written, with Retry. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportErrorSheet(onRetry: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NotepadColors.Paper) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("I couldn't make the PDF", fontFamily = NotepadFonts.PatrickHand, fontSize = 26.sp, color = NotepadColors.Ink)
            StickyNote(Modifier.fillMaxWidth(), background = NotepadColors.StickyPink, rotationDeg = -1f) {
                Text(
                    "Something went wrong while writing the file. Your note is safe. If the phone is low on space, free some up, then try again.",
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkPink,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
                ) { Text("Close", fontFamily = NotepadFonts.Inter, fontSize = 15.sp) }
                Button(
                    onClick = { onDismiss(); onRetry() },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
                ) { Text("Retry", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
        }
    }
}
