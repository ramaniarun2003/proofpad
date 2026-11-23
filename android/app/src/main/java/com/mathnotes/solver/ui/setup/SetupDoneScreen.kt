package com.mathnotes.solver.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mathnotes.solver.ui.components.CheckIcon
import com.mathnotes.solver.ui.components.NotepadPage
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import java.io.File

/**
 * Step 3 of the wizard (mockup 10_setup_3_saved): confirms the samples were saved, shows the
 * captured page, and lists what the recognizer learned, then drops into the first real capture.
 */
@Composable
fun SetupDoneScreen(setupVm: SetupViewModel, onDone: () -> Unit) {
    val state by setupVm.state.collectAsState()
    val corrections = setupVm.derivedRuleLines()

    NotepadPage(
        title = "Got it",
        caption = "3 of 3",
        underHeader = { SetupProgress(step = 3) },
        bottomBar = {
            SetupPrimaryButton("Snap my first problem", onDone, weight = 1f)
        },
    ) {
        // The captured page, pinned to the notebook at a slight angle.
        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .graphicsLayer { rotationZ = -2.5f }
                    .fillMaxWidth(0.66f)
                    .height(190.dp)
                    .shadow(6.dp)
                    .background(NotepadColors.PaperCard)
                    .border(1.dp, NotepadColors.HeaderBorder),
            ) {
                state.pageImagePath?.let { path ->
                    val f = File(path)
                    if (f.exists()) AsyncImage(f, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Crop)
                }
            }
        }

        Text(
            "I saved ${state.lines.size} samples of your handwriting.",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 22.sp,
            color = NotepadColors.Ink,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, end = 8.dp),
        )
        Text(
            "Next time you snap a problem I'll lean on these to read your hand more accurately — " +
                "especially the symbols you just wrote out.",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 16.sp,
            color = NotepadColors.Muted,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp, bottom = 6.dp),
        )

        val lines = corrections.ifEmpty { listOf("Mathpix read all five correctly, nothing to fix yet.") }
        lines.forEach { line ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CheckIcon(NotepadColors.Green, 18.dp)
                Text(line, fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Ink)
            }
        }

        StickyNote(Modifier.padding(top = 14.dp).fillMaxWidth(0.8f), rotationDeg = 1.2f) {
            Text(
                "You can redo this any time from Settings, or add more samples.",
                fontFamily = NotepadFonts.PatrickHand,
                fontSize = 15.sp,
                color = NotepadColors.NoteInkYellow,
            )
        }
    }
}
