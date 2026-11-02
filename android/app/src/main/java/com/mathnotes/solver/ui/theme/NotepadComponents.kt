package com.mathnotes.solver.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Reusable notepad chrome shared by the screens: a ruled-paper background, the
 * header band, the bottom action bar, and the sticky note. Tokens come from
 * [NotepadColors] / [NotepadFonts] (see android/design/DESIGN.md).
 */

/** Paper fill with blue horizontal rule lines every 36dp and a red vertical margin at 48dp. */
fun Modifier.ruledPaper(): Modifier = this
    .background(NotepadColors.Paper)
    .drawBehind {
        val pitch = 36.dp.toPx()
        val line = 1.dp.toPx()
        var y = pitch
        while (y < size.height) {
            drawLine(NotepadColors.RuleLine, Offset(0f, y), Offset(size.width, y), strokeWidth = line)
            y += pitch
        }
        val marginX = 48.dp.toPx()
        drawLine(NotepadColors.RedMargin, Offset(marginX, 0f), Offset(marginX, size.height), strokeWidth = 2.dp.toPx())
    }

/** The 96dp cream header band: handwritten title at left, muted caption at right. */
@Composable
fun NotepadHeader(title: String, trailing: String? = null) {
    Column(Modifier.fillMaxWidth().background(NotepadColors.HeaderBand)) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 60.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, fontFamily = NotepadFonts.PatrickHand, fontSize = 30.sp, lineHeight = 32.sp, color = NotepadColors.Ink)
            if (trailing != null) {
                Text(trailing, fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Muted)
            }
        }
        HorizontalDivider(color = NotepadColors.HeaderBorder, thickness = 1.dp)
    }
}

/** The 92dp bottom action bar: cream band, top divider, holds 48dp buttons. */
@Composable
fun NotepadBottomBar(content: @Composable RowScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(NotepadColors.HeaderBand)) {
        HorizontalDivider(color = NotepadColors.HeaderBorder, thickness = 1.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** A tilted sticky note with a soft shadow — yellow for hints, pink for the verifier flag. */
@Composable
fun StickyNote(
    modifier: Modifier = Modifier,
    background: Color = NotepadColors.StickyYellow,
    rotationDeg: Float = 1f,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .graphicsLayer { rotationZ = rotationDeg }
            .shadow(6.dp)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        content = content,
    )
}

/** Patrick Hand caption/label text in the muted ink used throughout the mockups. */
@Composable
fun NotepadLabel(text: String, modifier: Modifier = Modifier, fontSize: Int = 15, color: Color = NotepadColors.Muted, fontFamily: FontFamily = NotepadFonts.PatrickHand) {
    Text(text, modifier = modifier, fontFamily = fontFamily, fontSize = fontSize.sp, color = color)
}
