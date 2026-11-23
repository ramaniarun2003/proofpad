package com.mathnotes.solver.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mathnotes.solver.setup.SetupMatching
import com.mathnotes.solver.ui.LatexView
import com.mathnotes.solver.ui.components.CheckIcon
import com.mathnotes.solver.ui.components.CrossIcon
import com.mathnotes.solver.ui.components.NotepadPage
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import java.io.File

private val THUMB_TILTS = listOf(-3f, 2f, -2f, 3f, -1.5f)

/**
 * Step 2 of the wizard (mockup 10_setup_2_check): shows what the recognizer read for each
 * line against a thumbnail of the strip, flags mismatches, and lets the user tap to correct.
 */
@Composable
fun SetupCheckScreen(setupVm: SetupViewModel, onRetake: () -> Unit, onSave: () -> Unit, onRetakeLine: (Int) -> Unit = {}) {
    val state by setupVm.state.collectAsState()
    var editing by remember { mutableStateOf<Int?>(null) }
    val correct = state.lines.count { it.isMatch }

    NotepadPage(
        title = "Did I read these right?",
        caption = "2 of 3",
        underHeader = { SetupProgress(step = 2) },
        bottomBar = {
            SetupSecondaryButton("Retake photo", onRetake)
            SetupPrimaryButton("Save my handwriting", onSave)
        },
    ) {
        Text(
            "Tap any line I got wrong and tell me what you actually wrote.",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 17.sp,
            color = NotepadColors.Ink,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp),
        )

        state.lines.forEachIndexed { i, line ->
            val match = line.isMatch
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 86.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (match) androidx.compose.ui.graphics.Color.Transparent else NotepadColors.Red.copy(alpha = 0.07f))
                    .clickable { if (line.notFound) onRetakeLine(i) else editing = i }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
                    if (match) CheckIcon(NotepadColors.Green, 22.dp) else CrossIcon(NotepadColors.Red, 22.dp)
                }
                // Strip thumbnail, pinned at a slight angle.
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .graphicsLayer { rotationZ = THUMB_TILTS[i % THUMB_TILTS.size] }
                        .size(width = 120.dp, height = 56.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(NotepadColors.PaperCard)
                        .border(1.dp, NotepadColors.HeaderBorder, RoundedCornerShape(3.dp)),
                ) {
                    line.cropPath?.let { path ->
                        val f = File(path)
                        if (f.exists()) AsyncImage(f, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (line.notFound) {
                        LatexView(line.expectedLatex, textSizePx = with(LocalDensity.current) { 20.sp.toPx() })
                        Text(
                            "not found, tap to retake just this line",
                            fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Red,
                        )
                    } else {
                        LatexView(line.effectiveLatex.ifBlank { "?" }, textSizePx = with(LocalDensity.current) { 22.sp.toPx() })
                        if (!match) MismatchDetail(expected = line.expectedLatex, recognized = line.effectiveLatex)
                    }
                }
            }
        }

        Text(
            "$correct of ${state.lines.size} read correctly",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 16.sp,
            color = if (correct == state.lines.size) NotepadColors.Green else NotepadColors.Muted,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
        )
    }

    editing?.let { index ->
        val line = state.lines[index]
        var text by remember(index) { mutableStateOf(line.effectiveLatex) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("What did you write?", fontFamily = NotepadFonts.PatrickHand, fontSize = 22.sp) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Line ${index + 1} — fix the LaTeX to match your handwriting.", fontFamily = NotepadFonts.Inter, fontSize = 13.sp, color = NotepadColors.Muted)
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 15.sp, color = NotepadColors.Ink),
                        shape = RoundedCornerShape(8.dp),
                    )
                }
            },
            confirmButton = { TextButton(onClick = { setupVm.setCorrection(index, text); editing = null }) { Text("That's right") } },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } },
        )
    }
}

/** Mismatch hint: the first misread token highlighted in #FFD6D1, then the expected form. */
@Composable
private fun MismatchDetail(expected: String, recognized: String) {
    val diff = SetupMatching.firstDiffToken(expected, recognized)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!diff.isNullOrBlank()) {
            Box(
                Modifier.clip(RoundedCornerShape(4.dp)).background(NotepadColors.StickyPink).padding(horizontal = 6.dp, vertical = 1.dp),
            ) { Text(diff, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NotepadColors.NoteInkPink) }
        }
        Text("should be", fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Red)
        LatexView(expected, textSizePx = with(LocalDensity.current) { 15.sp.toPx() }, fillWidth = false)
        Text("· tap to fix", fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Red)
    }
}
