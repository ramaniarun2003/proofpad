package com.mathnotes.solver.ui.setup

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts

/** The 3-segment wizard progress bar shown under the header: 4dp tall, ink for done, #E2D7C0 for the rest. */
@Composable
fun SetupProgress(step: Int, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(NotepadColors.Paper)
            .padding(start = 56.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(3) { i ->
            Row(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i < step) NotepadColors.Ink else NotepadColors.HeaderBorder),
            ) {}
        }
    }
}

/** Secondary (outlined, ink) action for a setup bottom bar. */
@Composable
fun RowScope.SetupSecondaryButton(text: String, onClick: () -> Unit, weight: Float = 1f) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.weight(weight).height(48.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.5.dp, NotepadColors.Ink),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
    ) { Text(text, fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
}

/** Primary (filled ink) action for a setup bottom bar. */
@Composable
fun RowScope.SetupPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, weight: Float = 1.4f) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.weight(weight).height(48.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
    ) { Text(text, fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}
