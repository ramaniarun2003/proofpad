package com.mathnotes.solver.ui

import android.app.Activity
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.mathnotes.solver.ui.components.Stamp
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Cover-only tokens (the rest come from NotepadColors).
private val Spine = Color(0xFF1B1712)       // darker spine strip down the left edge
private val Band = Color(0xFFE8A0A0)        // the pink elastic closure band
private val Cream = NotepadColors.Paper     // #FBF8F1

/**
 * The notebook-cover welcome screen (mockup 09_splash_b_cover): a dark, textured cover
 * with a spine, a paper title label, a pink elastic band and a "Get started" CTA. Shown
 * once on first launch; "Get started" marks it seen and drops into Capture.
 */
@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    // Dark cover → light system-bar icons while this screen is up; restore on the way out.
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

    // Respect the system "remove animations" setting (animator duration scale = 0).
    val context = LocalContext.current
    val reduceMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    val labelIn = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val stampScale = remember { Animatable(if (reduceMotion) 1f else 1.4f) }
    val stampAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }
    val buttonAlpha = remember { Animatable(if (reduceMotion) 1f else 0f) }

    LaunchedEffect(Unit) {
        if (reduceMotion) return@LaunchedEffect
        // Label fades + slides up 16dp over 350ms.
        launch { labelIn.animateTo(1f, tween(350, easing = FastOutSlowInEasing)) }
        // Stamp pops in (1.4x → 1x with a quick overshoot) 200ms later.
        launch {
            delay(200)
            launch { stampAlpha.animateTo(1f, tween(150)) }
            stampScale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
        // Button fades in last.
        launch {
            delay(450)
            buttonAlpha.animateTo(1f, tween(300))
        }
    }

    Box(Modifier.fillMaxSize().background(NotepadColors.Ink)) {
        // Dot texture + a soft radial highlight near the top center.
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White.copy(alpha = 0.06f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.16f),
                    radius = size.width * 0.95f,
                ),
            )
            val step = 6.dp.toPx()
            val r = 0.5.dp.toPx()
            val dot = Color.White.copy(alpha = 0.06f)
            var y = 0f
            while (y < size.height) {
                var x = 0f
                while (x < size.width) {
                    drawCircle(dot, r, Offset(x, y))
                    x += step
                }
                y += step
            }
        }

        // Spine strip and its shadow gradient at the left edge.
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(34.dp).background(Spine))
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = 34.dp)
                .fillMaxHeight()
                .width(10.dp)
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.28f), Color.Transparent))),
        )

        // The pink elastic band, ~540dp down, spanning the label width, tilted -1°.
        Box(
            Modifier
                .align(Alignment.TopStart)
                .offset(y = 540.dp)
                .fillMaxWidth()
                .padding(start = 62.dp, end = 48.dp)
                .graphicsLayer { rotationZ = -1f }
                .shadow(3.dp, RoundedCornerShape(3.dp))
                .clip(RoundedCornerShape(3.dp))
                .background(Band)
                .height(36.dp),
        )

        // Foreground content, inset from the system bars.
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Spacer(Modifier.height(46.dp))
            TitleLabel(labelIn.value, stampScale.value, stampAlpha.value)
            Spacer(Modifier.weight(1f))

            // CTA block.
            Column(
                Modifier.fillMaxWidth().graphicsLayer { alpha = buttonAlpha.value },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 62.dp, end = 48.dp)
                        .height(52.dp)
                        .shadow(4.dp, RoundedCornerShape(12.dp))
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cream)
                        .clickable(onClick = onGetStarted)
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Get started",
                        fontFamily = NotepadFonts.Inter,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        color = NotepadColors.Ink,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("→", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = NotepadColors.Ink)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "open your notebook",
                    fontFamily = NotepadFonts.PatrickHand,
                    fontSize = 15.sp,
                    color = Cream.copy(alpha = 0.55f),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                "NUMERO UNOS · ECE 454",
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                fontFamily = NotepadFonts.Inter,
                fontSize = 11.sp,
                letterSpacing = 1.sp,
                color = Cream.copy(alpha = 0.40f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The cream paper title label: ruled lines, tape strip, title, formula, captions and a VERIFIED stamp. */
@Composable
private fun TitleLabel(labelIn: Float, stampScale: Float, stampAlpha: Float) {
    Box(
        Modifier
            .graphicsLayer {
                alpha = labelIn
                translationY = (1f - labelIn) * 16.dp.toPx()
            }
            .fillMaxWidth()
            .padding(start = 62.dp, end = 48.dp),
    ) {
        // The paper card with ruled lines.
        Box(
            Modifier
                .fillMaxWidth()
                .height(236.dp)
                .shadow(6.dp, RoundedCornerShape(4.dp))
                .clip(RoundedCornerShape(4.dp))
                .background(Cream)
                .drawBehind {
                    val pitch = 28.dp.toPx()
                    val inset = 18.dp.toPx()
                    var y = 44.dp.toPx()
                    while (y < size.height - 8.dp.toPx()) {
                        drawLine(
                            NotepadColors.RuleLine,
                            Offset(inset, y),
                            Offset(size.width - inset, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                        y += pitch
                    }
                },
        ) {
            Column(Modifier.fillMaxSize().padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 16.dp)) {
                Text("this notebook belongs to", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Muted)
                Spacer(Modifier.height(2.dp))
                Text("ProofPad", fontFamily = NotepadFonts.PatrickHand, fontSize = 46.sp, color = NotepadColors.Ink)
                Spacer(Modifier.height(6.dp))
                Text(
                    "∫ f′(x) dx = f(x) + C",
                    fontFamily = NotepadFonts.StixTwoText,
                    fontSize = 24.sp,
                    color = NotepadColors.Ink,
                )
                Spacer(Modifier.weight(1f))
                Text("derivatives · integrals · series · limits", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                Text("solved and checked, step by step", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
            }
        }

        // Translucent tape strip straddling the top edge.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-12).dp)
                .graphicsLayer { rotationZ = -2f }
                .width(96.dp)
                .height(26.dp)
                .background(Color.White.copy(alpha = 0.42f)),
        )

        // VERIFIED stamp, top-right corner, tilted +9° and popped in.
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = (-8).dp, y = 14.dp)
                .graphicsLayer {
                    scaleX = stampScale
                    scaleY = stampScale
                    alpha = stampAlpha
                },
        ) {
            Stamp("VERIFIED", verified = true, fontSize = 13.sp, rotation = 9f)
        }
    }
}
