package com.mathnotes.solver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.ui.LatexView
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts

/** A tilted ink stamp: green "VERIFIED" or red "CHECK STEP n". */
@Composable
fun Stamp(text: String, verified: Boolean, modifier: Modifier = Modifier) {
    val color = if (verified) NotepadColors.Green else NotepadColors.Red
    Box(
        modifier
            .graphicsLayer { rotationZ = -8f; alpha = 0.88f }
            .drawBehind {
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
            .padding(horizontal = 12.dp, vertical = 3.dp),
    ) {
        Text(text, fontFamily = NotepadFonts.PatrickHand, fontSize = 20.sp, color = color, letterSpacing = 1.5.sp)
    }
}

/** Verification state of one step in the chain. */
enum class StepStatus { Verified, Flagged, Pending, Plain }

/** One 72dp step row: index · (rule + typeset expression) · status icon. Tappable to select. */
@Composable
fun StepRow(
    number: Int,
    rule: String,
    exprLatex: String,
    status: StepStatus,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rowBg = when {
        status == StepStatus.Flagged -> NotepadColors.Red.copy(alpha = 0.07f)
        selected -> NotepadColors.Ink.copy(alpha = 0.04f)
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(rowBg)
            .clickable(onClick = onClick)
            .alpha(if (status == StepStatus.Pending) 0.45f else 1f),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(Modifier.width(48.dp).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
            Text(
                number.toString(),
                Modifier.padding(bottom = 8.dp),
                fontFamily = NotepadFonts.PatrickHand,
                fontSize = 18.sp,
                color = NotepadColors.StepIndex,
            )
        }
        val exprPx = with(androidx.compose.ui.platform.LocalDensity.current) { 24.sp.toPx() }
        Column(Modifier.weight(1f).padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                rule,
                fontFamily = NotepadFonts.PatrickHand,
                fontSize = 15.sp,
                color = if (status == StepStatus.Flagged) NotepadColors.Red else NotepadColors.Muted,
            )
            LatexView(exprLatex, textSizePx = exprPx)
        }
        Box(Modifier.width(46.dp).fillMaxHeight().padding(end = 18.dp, bottom = 6.dp), contentAlignment = Alignment.BottomCenter) {
            when (status) {
                StepStatus.Verified -> CheckIcon(NotepadColors.Green)
                StepStatus.Flagged -> CrossIcon(NotepadColors.Red)
                StepStatus.Pending -> Text("?", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                StepStatus.Plain -> {}
            }
        }
    }
}

/** A box with a hand-drawn feel: solid ink border, or dashed muted when the answer is held. */
@Composable
fun HandDrawnBox(modifier: Modifier = Modifier, dashed: Boolean = false, content: @Composable () -> Unit) {
    val color = if (dashed) NotepadColors.Held else NotepadColors.Ink
    Box(
        modifier
            .drawBehind {
                val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f) else null
                drawRoundRect(
                    color = color,
                    cornerRadius = CornerRadius(16.dp.toPx(), 16.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = effect),
                )
            }
            .padding(horizontal = 26.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Green verification check — reused by [StepRow] and the History list. */
@Composable
fun CheckIcon(color: Color, side: Dp = 26.dp) {
    Canvas(Modifier.size(side)) {
        val w = size.width; val h = size.height
        val p = Path().apply {
            moveTo(w * 0.18f, h * 0.55f); lineTo(w * 0.42f, h * 0.78f); lineTo(w * 0.84f, h * 0.26f)
        }
        drawPath(p, color, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Red mismatch cross — reused by [StepRow] and the History list. */
@Composable
fun CrossIcon(color: Color, side: Dp = 26.dp) {
    Canvas(Modifier.size(side)) {
        val w = size.width; val h = size.height
        val s = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.26f, h * 0.26f), Offset(w * 0.74f, h * 0.74f), strokeWidth = s.width, cap = StrokeCap.Round)
        drawLine(color, Offset(w * 0.74f, h * 0.26f), Offset(w * 0.26f, h * 0.74f), strokeWidth = s.width, cap = StrokeCap.Round)
    }
}
