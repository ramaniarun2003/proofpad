package com.mathnotes.solver.ui.onboarding

import android.app.Activity
import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Cover-only colours (mockup 09_splash_b_cover); the rest of the app is the notepad palette.
private val CoverBlack = Color(Marble.BLACK)
private val SpineColor = Color(0xFF1A1A1A)
private val LabelCard = Color(0xFFF7F5EF)
private val LabelInk = Color(0xFF1B1B1B)
private val LabelRule = Color(0xFF2B2B2B)

private val SPINE_WIDTH = 38.dp
private val SPINE_SHADOW = 12.dp
private val LABEL_START = 66.dp
private val LABEL_END = 32.dp

/** What the label's first line says until the user has a class folder of their own. */
internal const val DEFAULT_COVER_LINE = "Calc II  ·  Fall 2026"

/**
 * The welcome screen: the cover of a marbled composition book (mockup 09_splash_b_cover), with a
 * spine down the left, a paper label, "Get started" and a strip of topics along the bottom. First
 * in the one-time first-launch flow; [onGetStarted] leads to the guide.
 *
 * [folderName] is the user's most recent class folder, written on the label's first line. There
 * is none on a first launch, so it only shows when the cover is replayed from Settings.
 */
@Composable
fun WelcomeScreen(folderName: String?, onGetStarted: () -> Unit) {
    // A dark cover: light system-bar icons while it is up, and whatever was there before afterwards.
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as Activity).window
        val controller = WindowCompat.getInsetsController(window, view)
        val priorStatus = controller.isAppearanceLightStatusBars
        val priorNav = controller.isAppearanceLightNavigationBars
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = priorStatus
            controller.isAppearanceLightNavigationBars = priorNav
        }
    }

    Box(Modifier.fillMaxSize().background(CoverBlack)) {
        MarbleBackground()
        Spine()

        Column(Modifier.fillMaxSize()) {
            // The mockup's proportions: 236 above the label, 154 between the button and the strip.
            Spacer(Modifier.weight(236f))
            CoverLabel(folderName?.takeIf { it.isNotBlank() } ?: DEFAULT_COVER_LINE)
            Spacer(Modifier.height(44.dp))
            GetStartedButton(onGetStarted)
            Spacer(Modifier.weight(154f))
            TopicStrip()
        }
    }
}

/**
 * The marbling, full bleed. It is computed once for the screen's size, off the main thread, into
 * one bitmap that every later frame just draws; until it is ready (about a tenth of a second)
 * the cover is its plain black, which is also the system splash colour.
 */
@Composable
private fun MarbleBackground() {
    var size by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val marble by produceState<ImageBitmap?>(initialValue = null, size, density) {
        if (size.width > 0 && size.height > 0) {
            value = withContext(Dispatchers.Default) {
                Bitmap.createBitmap(Marble.pixels(size.width, size.height, density), size.width, size.height, Bitmap.Config.ARGB_8888)
                    .asImageBitmap()
            }
        }
    }
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val alpha by animateFloatAsState(
        targetValue = if (marble == null) 0f else 1f,
        animationSpec = if (reducedMotion) snap() else tween(180),
        label = "marble",
    )
    Canvas(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        marble?.let { drawImage(it, alpha = alpha) }
    }
}

/** The book's cloth spine: a dark strip with a light edge, a stitch line, and its shadow on the cover. */
@Composable
private fun Spine() {
    Box(
        Modifier.fillMaxHeight().width(SPINE_WIDTH).background(SpineColor).drawBehind {
            val edge = 1.dp.toPx()
            drawLine(Color.White.copy(alpha = 0.12f), Offset(size.width - edge / 2, 0f), Offset(size.width - edge / 2, size.height), strokeWidth = edge)
            val stitchX = 13.dp.toPx()   // a 2dp line whose left side is 12dp in
            drawLine(
                Color.White.copy(alpha = 0.08f), Offset(stitchX, 0f), Offset(stitchX, size.height), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())),
            )
        },
    )
    Box(
        Modifier.offset(x = SPINE_WIDTH).fillMaxHeight().width(SPINE_SHADOW)
            .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent))),
    )
}

/** The paper label: the ProofPad tag, "composition book", and four ruled lines. */
@Composable
private fun CoverLabel(firstLine: String) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = LABEL_START, end = LABEL_END)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(LabelCard)
            .heightIn(min = 300.dp)
            .padding(start = 24.dp, end = 24.dp, top = 26.dp, bottom = 20.dp),
    ) {
        Row(
            Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp, bottomEnd = 2.dp, bottomStart = 10.dp))
                .background(LabelInk)
                .padding(start = 10.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("∫", fontFamily = NotepadFonts.StixTwoText, fontStyle = FontStyle.Italic, fontSize = 17.sp, color = LabelCard)
            Spacer(Modifier.width(6.dp))
            Text(
                "ProofPad",
                fontFamily = NotepadFonts.InterBold, fontWeight = FontWeight.Bold, fontSize = 15.sp, letterSpacing = 0.2.sp, color = LabelCard,
            )
        }

        Spacer(Modifier.height(26.dp))
        Text("composition", fontFamily = NotepadFonts.Inter, fontSize = 20.sp, lineHeight = 24.sp, color = LabelRule)
        Text("book", fontFamily = NotepadFonts.InterBold, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, color = LabelInk)

        RuledLine(firstLine, gapAbove = 20.dp)
        RuledLine("every step, checked")
        RuledLine("checks your calculus homework", fontSize = 16.sp, color = NotepadColors.Muted)
        Rule()   // the last line of the label is left empty
    }
}

@Composable
private fun Rule() = Box(Modifier.fillMaxWidth().height(1.5.dp).background(LabelRule))

/** One handwritten line of the label, under its rule. */
@Composable
private fun RuledLine(text: String, gapAbove: Dp = 10.dp, fontSize: TextUnit = 19.sp, color: Color = NotepadColors.Ink) {
    Spacer(Modifier.height(gapAbove))
    Rule()
    Spacer(Modifier.height(8.dp))
    Text(
        text,
        fontFamily = NotepadFonts.PatrickHand, fontSize = fontSize, lineHeight = 20.sp, color = color,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GetStartedButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = LABEL_START, end = LABEL_END)
            .heightIn(min = 54.dp)
            .shadow(4.dp, shape)
            .clip(shape)
            .background(LabelCard)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Get started", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = LabelInk)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.size(18.dp)) {
            val u = size.width / 24f
            val arrow = Path().apply {
                moveTo(5 * u, 12 * u); lineTo(19 * u, 12 * u)
                moveTo(13 * u, 6 * u); lineTo(19 * u, 12 * u); lineTo(13 * u, 18 * u)
            }
            drawPath(arrow, LabelInk, style = Stroke(width = 2.2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** The strip along the bottom, from the spine's shadow to the right edge, naming what the app covers. */
@Composable
private fun TopicStrip() {
    Row(
        Modifier
            .padding(start = SPINE_WIDTH + SPINE_SHADOW)
            .fillMaxWidth()
            .background(LabelInk)
            .navigationBarsPadding()   // the strip's colour runs on under the navigation bar
            .height(56.dp)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf("DERIVATIVES", "INTEGRALS", "LIMITS", "SERIES").forEachIndexed { i, topic ->
            if (i > 0) StripText("|", alpha = 0.4f)
            StripText(topic)
        }
    }
}

@Composable
private fun StripText(text: String, alpha: Float = 1f) {
    Text(
        text,
        fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, letterSpacing = 0.6.sp,
        color = LabelCard.copy(alpha = alpha), maxLines = 1, softWrap = false,
    )
}
