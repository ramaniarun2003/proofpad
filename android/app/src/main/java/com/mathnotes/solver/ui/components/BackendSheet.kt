package com.mathnotes.solver.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.api.BackendState
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts

private val DOT_GREEN = NotepadColors.Green
private val DOT_AMBER = Color(0xFFE0A030)
private val DOT_RED = NotepadColors.Red

private fun dotColor(state: BackendState) = when (state) {
    is BackendState.Connected -> DOT_GREEN
    is BackendState.Searching -> DOT_AMBER
    is BackendState.NotFound -> DOT_RED
}

/** Header status chip: a colored dot (green/amber-pulsing/red) + "backend". Tap to open the sheet. */
@Composable
fun BackendStatusChip(state: BackendState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulseAlpha",
    )
    val busy = state is BackendState.Searching
    val alpha = if (busy) pulse else 1f
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            Modifier.size(9.dp).graphicsLayer { this.alpha = alpha }.clip(RoundedCornerShape(999.dp)).background(dotColor(state)),
        ) {}
        Text(
            "backend",
            fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Paper,
        )
    }
}

/** Notepad-style discovery sheet: current URL + latency, search again, scan QR, and a manual field. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendSheet(
    state: BackendState,
    savedUrl: String,
    onSearchAgain: () -> Unit,
    onScanQr: () -> Unit,
    onSaveUrl: (String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var url by remember { mutableStateOf(savedUrl) }
    var invalid by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = NotepadColors.Paper) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Backend", fontFamily = NotepadFonts.PatrickHand, fontSize = 28.sp, color = NotepadColors.Ink)

            when (state) {
                is BackendState.Connected -> {
                    Text("Connected ✓  ${state.latencyMs}ms", fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Green)
                    Text(state.url, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = NotepadColors.Muted)
                }
                is BackendState.Searching -> Text("Checking the backend URL…", fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Muted)
                is BackendState.NotFound -> Text("Can't reach that URL. Type your backend's address below, or scan its QR code.", fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Red)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onSearchAgain,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
                ) { Text("Test again", fontFamily = NotepadFonts.Inter, fontSize = 14.sp) }
                Button(
                    onClick = onScanQr,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
                ) { Text("Scan QR code", fontFamily = NotepadFonts.Inter, fontSize = 14.sp) }
            }

            Text("Backend URL", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Muted)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; invalid = false },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                isError = invalid,
                placeholder = { Text("http://192.168.1.42:8000", fontFamily = FontFamily.Monospace, fontSize = 14.sp) },
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = NotepadColors.Ink),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NotepadColors.Ink,
                    unfocusedBorderColor = NotepadColors.ChipBorder,
                    cursorColor = NotepadColors.Ink,
                ),
                supportingText = if (invalid) ({ Text("Enter a valid HTTP or HTTPS URL.", color = NotepadColors.Red) }) else null,
            )
            Button(
                onClick = { if (onSaveUrl(url)) onDismiss() else invalid = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
                enabled = url.isNotBlank(),
            ) { Text("Use this URL", fontFamily = NotepadFonts.Inter, fontSize = 15.sp) }
        }
    }
}
