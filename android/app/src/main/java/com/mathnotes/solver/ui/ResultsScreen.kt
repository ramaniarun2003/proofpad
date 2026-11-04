package com.mathnotes.solver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.api.Step
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

private fun firstNonTrivial(steps: List<Step>): Int =
    steps.indexOfFirst { it.rule !in TRIVIAL_RULES }.let { if (it < 0) 0 else it }

private fun sympyFn(topic: String): String = when (topic) {
    "integral" -> "integrate"
    "derivative" -> "diff"
    "series" -> "is_convergent"
    else -> "simplify"
}

/**
 * Step 6–7, styled as a notepad page (mockups 03_results_verified / 04_results_flagged).
 * Stateless so both states can be previewed and screenshotted; [ResultsScreen] binds it to the VM.
 */
@Composable
fun ResultsScreen(vm: SolverViewModel, onNewProblem: () -> Unit, onSaveToGallery: () -> Unit) {
    val state by vm.state.collectAsState()
    val result = state.result ?: return
    val ctx = LocalContext.current
    val date = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("MMM d")) }

    ResultsContent(
        problemLatex = state.latex,
        topic = result.topic,
        dateLabel = date,
        steps = result.steps,
        answerLatex = result.answerLatex,
        verified = result.verified,
        mismatchAt = result.mismatchAt,
        details = result.details,
        onExport = { PdfExporter.exportAndShare(ctx, state) },
        onNewProblem = { vm.reset(); onNewProblem() },
        onSaveToGallery = onSaveToGallery,
    )
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
    onExport: () -> Unit,
    onNewProblem: () -> Unit,
    onSaveToGallery: (() -> Unit)? = null,
) {
    val flaggedIndex = if (!verified) mismatchAt else null
    val isFlagged = flaggedIndex != null
    var selected by remember(steps) { mutableStateOf<Int?>(firstNonTrivial(steps)) }
    val density = LocalDensity.current

    NotepadPage(
        title = "ProofPad",
        caption = "$dateLabel · $topic",
        bottomBar = {
            OutlinedButton(
                onClick = onExport,
                enabled = !isFlagged,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, if (isFlagged) NotepadColors.Held else NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = NotepadColors.Ink,
                    disabledContentColor = NotepadColors.Held,
                ),
            ) { Text("Export PDF", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }

            Button(
                onClick = onNewProblem,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) { Text("New problem", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
        },
    ) {
        // Problem + stamp.
        Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Column {
                NotepadLabel("Problem", fontSize = 17)
                LatexView(problemLatex, Modifier.padding(top = 2.dp), textSizePx = with(density) { 26.sp.toPx() })
            }
            Stamp(
                text = if (verified) "VERIFIED" else "CHECK STEP ${(flaggedIndex ?: 0) + 1}",
                verified = verified,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 6.dp),
            )
        }

        Spacer(Modifier.height(12.dp))

        // Steps.
        steps.forEachIndexed { i, step ->
            val status = when {
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
        if (isFlagged) {
            val n = flaggedIndex!! + 1
            VerifierNote(
                message = details.joinToString(" ").ifBlank { "Step $n doesn't match step ${n - 1}." },
                resolveFromStep = n - 1,
            )
        } else if (selected != null) {
            val s = steps[selected!!]
            StickyNote(rotationDeg = 1.5f) {
                Text("why step ${selected!! + 1}", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)
                Text(
                    s.note.ifBlank { "No extra explanation for this step." } + "  Tap a step to hide this.",
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 17.sp,
                    color = NotepadColors.NoteInkYellow,
                )
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
            if (isFlagged) {
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

        Spacer(Modifier.height(10.dp))

        // Muted footnote.
        val footnote = if (isFlagged) {
            "expected final shown once step ${flaggedIndex!! + 1} is fixed"
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
                Text("＋ Save to gallery", fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Ink)
            }
        }
    }
}

@Composable
private fun VerifierNote(message: String, resolveFromStep: Int) {
    StickyNote(background = NotepadColors.StickyPink, rotationDeg = -1.5f) {
        Text("verifier says", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.NoteInkPink)
        Text(message, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkPink)
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NoteAction("Edit step", filled = false)
            NoteAction("Re-solve from step $resolveFromStep", filled = true)
        }
    }
}

/** Small action chip inside the pink verifier note (visual only in this restyle). */
@Composable
private fun NoteAction(label: String, filled: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    val base = if (filled) {
        Modifier.clip(shape).background(NotepadColors.NoteInkPink)
    } else {
        Modifier.clip(shape).border(1.5.dp, NotepadColors.NoteInkPink, shape)
    }
    Box(base.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            label,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 15.sp,
            color = if (filled) NotepadColors.StickyPink else NotepadColors.NoteInkPink,
        )
    }
}
