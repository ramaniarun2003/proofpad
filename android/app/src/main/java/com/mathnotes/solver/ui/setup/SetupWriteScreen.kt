package com.mathnotes.solver.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.repository.HandwritingRepository
import com.mathnotes.solver.ui.LatexView
import com.mathnotes.solver.ui.components.NotepadPage
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import androidx.compose.material3.Text

/**
 * Step 1 of the handwriting setup wizard (mockup 10_setup_1_write): shows the five
 * expressions we want the user to copy onto a sheet, then sends them to capture.
 */
@Composable
fun SetupWriteScreen(onSkip: () -> Unit, onCapture: () -> Unit) {
    NotepadPage(
        title = "Learn my handwriting",
        caption = "1 of 3",
        underHeader = { SetupProgress(step = 1) },
        bottomBar = {
            SetupSecondaryButton("Skip for now", onSkip)
            SetupPrimaryButton("Done writing, capture", onCapture)
        },
    ) {
        Text(
            "Copy these five lines onto one sheet, big and clear, then snap the whole page.",
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 19.sp,
            color = NotepadColors.Ink,
            modifier = Modifier.fillMaxWidth().padding(end = 8.dp, bottom = 6.dp),
        )

        val latexPx = with(LocalDensity.current) { 26.sp.toPx() }
        HandwritingRepository.EXPECTED.forEachIndexed { i, expr ->
            Row(
                Modifier.fillMaxWidth().height(72.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${i + 1}",
                    Modifier.width(24.dp),
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 20.sp,
                    color = NotepadColors.Red,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    LatexView(expr.latex, textSizePx = latexPx)
                }
                Text(
                    expr.hint,
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 13.sp,
                    color = NotepadColors.Muted,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(96.dp).padding(start = 6.dp),
                )
            }
        }

        StickyNote(Modifier.padding(top = 14.dp).fillMaxWidth(0.82f), rotationDeg = -1.2f) {
            Text(
                "These cover the symbols I trip on most — integrals, sums, limits, fractions, " +
                    "powers and e. One good sample teaches me your hand.",
                fontFamily = NotepadFonts.PatrickHand,
                fontSize = 15.sp,
                color = NotepadColors.NoteInkYellow,
            )
        }
    }
}
