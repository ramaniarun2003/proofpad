package com.mathnotes.solver.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** A piece of a note: plain words, or a `$…$` span to typeset. */
data class NotePart(val text: String, val isMath: Boolean)

/**
 * Split a step note into plain text and `$…$` math spans. The backend writes notes as sentences
 * with any math wrapped in single dollars; an unmatched `$` is left as text.
 */
fun splitNote(note: String): List<NotePart> {
    val parts = mutableListOf<NotePart>()
    var i = 0
    while (i < note.length) {
        val open = note.indexOf('$', i)
        val close = if (open >= 0) note.indexOf('$', open + 1) else -1
        if (open < 0 || close < 0) {
            parts += NotePart(note.substring(i), isMath = false)
            break
        }
        if (open > i) parts += NotePart(note.substring(i, open), isMath = false)
        val math = note.substring(open + 1, close).trim()
        if (math.isNotEmpty()) parts += NotePart(math, isMath = true)
        i = close + 1
    }
    return parts.filter { it.isMath || it.text.isNotBlank() }
}

/**
 * A note rendered as running text with its `$…$` spans typeset (not shown as raw LaTeX).
 * Words and formulas flow and wrap together.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NoteText(note: String, fontFamily: FontFamily, fontSize: TextUnit, color: Color, modifier: Modifier = Modifier) {
    val parts = splitNote(note)
    if (parts.none { it.isMath }) {
        Text(note, modifier = modifier, fontFamily = fontFamily, fontSize = fontSize, color = color)
        return
    }
    val mathPx = with(LocalDensity.current) { fontSize.toPx() } * 1.05f
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        parts.forEach { part ->
            if (part.isMath) {
                LatexView(part.text, Modifier.align(Alignment.CenterVertically), textSizePx = mathPx, fillWidth = false)
            } else {
                part.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { word ->
                    Text(word, Modifier.align(Alignment.CenterVertically), fontFamily = fontFamily, fontSize = fontSize, color = color)
                }
            }
        }
    }
}


private val SLASH_DERIVATIVE = Regex("""(?<![a-zA-Z\\])d\s*/\s*d\s*([a-zA-Z])(?![a-zA-Z])\s*""")
private val SLASH_PARTIAL = Regex("""\\partial\s*/\s*\\partial\s*([a-zA-Z])(?![a-zA-Z])\s*""")

/**
 * LaTeX as shown on Review, Results and the PDF: a derivative written with a slash (`d/dx`, as
 * Mathpix reads handwriting) is typeset as a fraction. Display only; the stored LaTeX, and the
 * editable field on Review, are not changed.
 */
fun displayProblem(latex: String): String =
    latex
        .replace(SLASH_DERIVATIVE) { "\\frac{d}{d${it.groupValues[1]}} " }
        .replace(SLASH_PARTIAL) { "\\frac{\\partial}{\\partial ${it.groupValues[1]}} " }
        .trim()
