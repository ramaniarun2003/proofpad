package com.mathnotes.solver.ui.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.ui.LatexView
import com.mathnotes.solver.ui.components.CheckIcon
import com.mathnotes.solver.ui.components.CrossIcon
import com.mathnotes.solver.ui.components.HandDrawnBox
import com.mathnotes.solver.ui.components.Stamp
import com.mathnotes.solver.ui.components.StepRow
import com.mathnotes.solver.ui.components.StepStatus
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import com.mathnotes.solver.ui.theme.folderColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/*
 * The guide's three illustrations. They are drawn with the app's own colours and components, not
 * screenshots, so they follow the theme. Each is a 200x360dp "mini phone": the screen inside is
 * laid out at a 360x648dp design size with the real components (Stamp, StepRow, StickyNote,
 * HandDrawnBox, LatexView) and scaled down as a whole. Two sticky-note callouts sit beside it.
 * A screen reader gets one description of each picture, then the callouts as ordinary text.
 */

internal val ILLUSTRATION_HEIGHT = 384.dp

private val PHONE_W = 200.dp
private val PHONE_H = 360.dp
private val DESIGN_W = 360.dp
private val DESIGN_H = 648.dp
private const val SCALE = 200f / 360f
private val PHONE_X = 6.dp
private val PHONE_Y = 10.dp
private val NOTE_X = 176.dp
private val NOTE_W = 150.dp

/** The rounded phone body with [screen] drawn inside at the design size, scaled to fit. */
@Composable
private fun BoxScope.MiniPhone(background: Color, description: String, screen: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .offset(PHONE_X, PHONE_Y)
            .shadow(10.dp, RoundedCornerShape(22.dp))
            .size(PHONE_W, PHONE_H)
            .clip(RoundedCornerShape(22.dp))
            .background(background)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        Box(
            Modifier
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .requiredSize(DESIGN_W, DESIGN_H)
                .graphicsLayer { scaleX = SCALE; scaleY = SCALE; transformOrigin = TransformOrigin(0f, 0f) },
            content = screen,
        )
    }
}

/** A yellow sticky-note callout beside the phone. */
@Composable
private fun BoxScope.Callout(text: String, y: Dp, tilt: Float) {
    StickyNote(Modifier.offset(NOTE_X, y).width(NOTE_W), rotationDeg = tilt) {
        Text(text, fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, lineHeight = 16.sp, color = NotepadColors.NoteInkYellow)
    }
}

/** A small hand-drawn arrow: a slightly bowed ink stroke from [from] to [to] with an open head. */
@Composable
private fun BoxScope.HandArrow(from: Pair<Dp, Dp>, to: Pair<Dp, Dp>, bow: Dp = 8.dp) {
    Canvas(Modifier.matchParentSize()) {
        val start = Offset(from.first.toPx(), from.second.toPx())
        val end = Offset(to.first.toPx(), to.second.toPx())
        val mid = Offset((start.x + end.x) / 2, (start.y + end.y) / 2 + bow.toPx())
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(start.x, start.y); quadraticTo(mid.x, mid.y, end.x, end.y) }, NotepadColors.Ink, style = stroke)
        val angle = atan2(end.y - mid.y, end.x - mid.x)
        val head = 7.dp.toPx()
        val wing = Path().apply {
            moveTo(end.x - head * cos(angle - 0.5f), end.y - head * sin(angle - 0.5f))
            lineTo(end.x, end.y)
            lineTo(end.x - head * cos(angle + 0.5f), end.y - head * sin(angle + 0.5f))
        }
        drawPath(wing, NotepadColors.Ink, style = stroke)
    }
}

@Composable
private fun Math(latex: String, size: Int) {
    LatexView(latex, textSizePx = with(LocalDensity.current) { size.sp.toPx() }, fillWidth = false)
}

/** A flat pill button as it appears in the bottom bars, at design size. */
@Composable
private fun BarButton(label: String, filled: Boolean, modifier: Modifier) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(48.dp).clip(shape)
            .then(if (filled) Modifier.background(NotepadColors.Ink) else Modifier.border(1.5.dp, NotepadColors.Ink, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, fontFamily = NotepadFonts.Inter, fontSize = 15.sp, maxLines = 1,
            fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium,
            color = if (filled) NotepadColors.Paper else NotepadColors.Ink,
        )
    }
}

@Composable
private fun BoxScope.BottomBar(left: String, right: String, rightWeight: Float = 1.4f) {
    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(NotepadColors.HeaderBand)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(NotepadColors.HeaderBorder))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BarButton(left, filled = false, modifier = Modifier.weight(1f))
            BarButton(right, filled = true, modifier = Modifier.weight(rightWeight))
        }
    }
}

@Composable
private fun ScreenHeader(title: String, trailing: String? = null) {
    Column(Modifier.fillMaxWidth().background(NotepadColors.HeaderBand)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 56.dp, end = 20.dp, top = 44.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom,
        ) {
            Text(title, fontFamily = NotepadFonts.PatrickHand, fontSize = 30.sp, color = NotepadColors.Ink)
            if (trailing != null) Text(trailing, fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(NotepadColors.HeaderBorder))
    }
}

// --- Page 1: Capture -------------------------------------------------------------------------

@Composable
internal fun BoxScope.SnapIllustration() {
    Box(Modifier.matchParentSize()) {
        MiniPhone(
            NotepadColors.CaptureVignetteOuter,
            "Picture of the camera screen: a handwritten problem lined up inside a yellow box, topic chips, and the shutter button.",
        ) {
            // The dark viewfinder.
            Box(
                Modifier.matchParentSize().background(
                    Brush.radialGradient(
                        0f to NotepadColors.CaptureVignetteInner, 0.55f to NotepadColors.CaptureVignetteMid, 1f to NotepadColors.CaptureVignetteOuter,
                    ),
                ),
            )
            // A sheet of ruled paper, slightly tilted, as seen through the camera.
            Box(
                Modifier.offset(30.dp, 126.dp).size(300.dp, 380.dp).graphicsLayer { rotationZ = -3f }
                    .background(NotepadColors.PaperCard)
                    .drawBehind {
                        var y = 30.dp.toPx()
                        while (y < size.height) {
                            drawLine(NotepadColors.RuleLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx())
                            y += 31.dp.toPx()
                        }
                    },
            )
            Box(Modifier.matchParentSize().background(NotepadColors.CaptureScrim))
            // The guide box: what is inside it is what gets read.
            Box(
                Modifier.offset(32.dp, 212.dp).size(296.dp, 126.dp).clip(RoundedCornerShape(7.dp))
                    .background(NotepadColors.PaperCard).border(3.dp, NotepadColors.StickyYellow, RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.graphicsLayer { rotationZ = -2f }) { Math("""\int 2x\,dx = x^{2} + C""", 30) }
            }
            Text(
                "page found · hold still",
                Modifier.align(Alignment.TopCenter).offset(y = 356.dp),
                fontFamily = NotepadFonts.PatrickHand, fontSize = 20.sp, color = NotepadColors.CaptureCaption,
            )
            // Topic chips.
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 132.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Auto", "Derivative", "Integral").forEachIndexed { i, label ->
                    val shape = RoundedCornerShape(999.dp)
                    Box(
                        Modifier.clip(shape)
                            .then(if (i == 0) Modifier.background(NotepadColors.StickyYellow) else Modifier.border(1.5.dp, NotepadColors.Paper.copy(alpha = 0.5f), shape))
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                    ) { Text(label, fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = if (i == 0) NotepadColors.Ink else NotepadColors.Paper) }
                }
            }
            // Shutter.
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 32.dp).size(86.dp).border(5.dp, NotepadColors.Paper, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(66.dp).clip(CircleShape).background(NotepadColors.Paper)) }
        }
        Callout("Only what is inside the yellow box gets read", y = 44.dp, tilt = 3f)
        HandArrow(from = 236.dp to 148.dp, to = 194.dp to 166.dp)
        Callout("Tap the round button to take the photo", y = 296.dp, tilt = -2f)
        HandArrow(from = 182.dp to 322.dp, to = 138.dp to 328.dp, bow = (-6).dp)
    }
}

// --- Page 2: Results -------------------------------------------------------------------------

@Composable
internal fun BoxScope.CheckIllustration() {
    Box(Modifier.matchParentSize()) {
        MiniPhone(
            NotepadColors.Paper,
            "Picture of the results screen: a verified stamp, a checked step with its rule, the answer in a box, and a note about a wrong step.",
        ) {
            Column(Modifier.matchParentSize()) {
                ScreenHeader("ProofPad")
                Column(Modifier.weight(1f).fillMaxWidth().ruledPaper().padding(start = 56.dp, end = 16.dp, top = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Problem", fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Muted)
                        Stamp("VERIFIED", verified = true, fontSize = 16.sp)
                    }
                    Math("""\int 2x\,dx""", 24)
                    Spacer(Modifier.height(6.dp))
                    StepRow(number = 1, rule = "power rule", exprLatex = """x^{2} + C""", status = StepStatus.Verified, selected = false, onClick = {})
                    StickyNote(Modifier.padding(top = 4.dp), rotationDeg = 1.5f) {
                        Text("why step 1", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)
                        Math("""\int x^{n}\,dx = \frac{x^{n+1}}{n+1},\; n = 1""", 14)
                    }
                    Spacer(Modifier.height(14.dp))
                    HandDrawnBox { Math("""x^{2} + C""", 24) }
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Box(Modifier.width(4.dp).height(44.dp).background(NotepadColors.Red))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "if a step is wrong you get CHECK STEP n and a note saying why",
                            fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, lineHeight = 20.sp, color = NotepadColors.Red,
                        )
                    }
                }
            }
            BottomBar("Export PDF", "New problem", rightWeight = 1f)
        }
        Callout("First you see what was read. Fix any wrong symbol, then tap Check my steps", y = 30.dp, tilt = 2f)
        Callout("Green check: this line is right. Red X: something is off", y = 236.dp, tilt = -1.5f)
        HandArrow(from = 192.dp to 238.dp, to = 183.dp to 172.dp, bow = 2.dp)
    }
}

// --- Page 3: Gallery -------------------------------------------------------------------------

@Composable
internal fun BoxScope.SaveIllustration() {
    Box(Modifier.matchParentSize()) {
        MiniPhone(
            NotepadColors.Paper,
            "Picture of the gallery: three class folders, recently viewed notes marked easy, medium and hard, and this week's notes.",
        ) {
            Column(Modifier.matchParentSize()) {
                ScreenHeader("Gallery", "23 notes")
                Column(Modifier.weight(1f).fillMaxWidth().ruledPaper().padding(start = 56.dp, end = 12.dp, top = 12.dp)) {
                    Text("My classes", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Calc II" to "11 notes", "Diff Eq" to "7 notes", "Physics" to "5 notes").forEachIndexed { i, (name, count) ->
                            val c = folderColor(i)
                            Column {
                                Box(Modifier.size(34.dp, 9.dp).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(c.tab))
                                Column(
                                    Modifier.size(88.dp, 66.dp).clip(RoundedCornerShape(topEnd = 7.dp, bottomStart = 7.dp, bottomEnd = 7.dp))
                                        .background(c.body).padding(8.dp),
                                    verticalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(name, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Ink, maxLines = 1)
                                    Text(count, fontFamily = NotepadFonts.PatrickHand, fontSize = 12.sp, color = NotepadColors.Muted)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Recently viewed", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            Triple("""\int 2x\,dx""", "Calc II", "Easy" to NotepadColors.DifficultyEasy),
                            Triple("""\frac{d}{dx}\,x^{2}\sin x""", "Calc II", "Medium" to NotepadColors.DifficultyMedium),
                            Triple("""y' + 2y = e^{x}""", "Diff Eq", "Hard" to NotepadColors.DifficultyHard),
                        ).forEach { (latex, folder, level) ->
                            Column(
                                Modifier.size(88.dp, 96.dp).shadow(2.dp).background(Color.White)
                                    .border(1.dp, NotepadColors.HeaderBorder).padding(6.dp),
                                verticalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Box(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(0.dp))) { Math(latex, 11) }
                                Row {
                                    Text("$folder · ", fontFamily = NotepadFonts.PatrickHand, fontSize = 11.sp, color = NotepadColors.Muted)
                                    Text(level.first, fontFamily = NotepadFonts.PatrickHand, fontSize = 11.sp, color = level.second)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("This past week", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                    listOf(
                        true to """\int \sin x \cos x\,dx""",
                        true to """\sum \frac{(-1)^{n}}{n}""",
                        false to """\frac{d}{dx}\ln(x^{2}+1)""",
                    ).forEach { (ok, latex) ->
                        Row(Modifier.height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (ok) CheckIcon(NotepadColors.Green, 18.dp) else CrossIcon(NotepadColors.Red, 18.dp)
                            Spacer(Modifier.width(8.dp))
                            Math(latex, 14)
                        }
                    }
                }
            }
            BottomBar("Settings", "Take a photo")
        }
        Callout("Tap Save to put the problem in a folder for your class", y = 16.dp, tilt = 2f)
        HandArrow(from = 224.dp to 92.dp, to = 197.dp to 110.dp, bow = 6.dp)
        Callout("Before a test, open a folder or export it as one PDF", y = 246.dp, tilt = -2f)
    }
}
