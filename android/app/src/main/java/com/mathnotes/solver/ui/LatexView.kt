package com.mathnotes.solver.ui

import android.graphics.Color
import android.widget.ImageView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mathnotes.solver.ui.theme.NotepadColors
import ru.noties.jlatexmath.JLatexMathDrawable

/**
 * Renders a LaTeX string as typeset math using JLatexMath-Android.
 * If the LaTeX is malformed (common while the user is editing), falls back
 * to showing the raw string in monospace instead of crashing.
 */
@Composable
fun LatexView(latex: String, modifier: Modifier = Modifier, textSizePx: Float = 44f, fillWidth: Boolean = true) {
    val drawable = runCatching {
        JLatexMathDrawable.builder(latex)
            .textSize(textSizePx)
            .padding(8)
            .background(Color.TRANSPARENT)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.getOrNull()

    if (drawable == null) {
        Text(latex, fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = NotepadColors.Ink, modifier = modifier)
        return
    }
    AndroidView(
        modifier = if (fillWidth) modifier.fillMaxWidth() else modifier,
        factory = { ctx -> ImageView(ctx).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_START } },
        update = { it.setImageDrawable(drawable) },
    )
}
