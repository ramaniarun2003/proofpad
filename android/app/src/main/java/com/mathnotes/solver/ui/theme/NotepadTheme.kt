package com.mathnotes.solver.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.R

/**
 * Notepad design tokens from android/design/DESIGN.md (sourced from the mockups).
 * See [NotepadColors], [NotepadFonts], and the [NotepadTheme] wrapper.
 */
object NotepadColors {
    // Headline tokens.
    val Paper = Color(0xFFFBF8F1)
    val HeaderBand = Color(0xFFF3ECDC)
    val HeaderBorder = Color(0xFFE2D7C0)
    val RuleLine = Color(0xFFCFE0F0)
    val RedMargin = Color(0xFFE3A3A3)
    val Ink = Color(0xFF2A2419)
    val Muted = Color(0xFF7A6A4A)
    val Green = Color(0xFF1F7A3A)
    val Red = Color(0xFFC0392B)
    val StickyYellow = Color(0xFFFFF3A3)
    val StickyPink = Color(0xFFFFD6D1)

    // Supporting tokens (see DESIGN.md).
    val StepIndex = Color(0xFFC0504D)
    val ChipBorder = Color(0xFFC9BDA4)
    val NoteInkYellow = Color(0xFF4A3D1E)
    val NoteInkPink = Color(0xFF5A2420)
    val Held = Color(0xFFA09480)
    val PaperCard = Color(0xFFECE6D6)
    val CaptureScrim = Color(0x73000000) // rgba(0,0,0,0.45)

    // Capture screen (the one dark screen — see mockup 01_capture).
    val CaptureVignetteInner = Color(0xFF5C554A)
    val CaptureVignetteMid = Color(0xFF3A352D)
    val CaptureVignetteOuter = Color(0xFF1B1915)
    val CaptureCaption = Color(0xFFE8DCC0)
}

/** The three bundled families. STIX Two Text also carries an italic; Inter carries 400/500/600. */
object NotepadFonts {
    /** Patrick Hand — captions and labels. */
    val PatrickHand = FontFamily(
        Font(R.font.patrick_hand_regular, FontWeight.Normal),
    )

    /** STIX Two Text — math. */
    val StixTwoText = FontFamily(
        Font(R.font.stix_two_text_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.stix_two_text_italic, FontWeight.Normal, FontStyle.Italic),
    )

    /** Inter — buttons and body UI text. */
    val Inter = FontFamily(
        Font(R.font.inter_regular, FontWeight.Normal),
        Font(R.font.inter_medium, FontWeight.Medium),
        Font(R.font.inter_semibold, FontWeight.SemiBold),
    )
}

private val NotepadColorScheme = lightColorScheme(
    primary = NotepadColors.Ink,
    onPrimary = NotepadColors.Paper,
    secondary = NotepadColors.Muted,
    onSecondary = NotepadColors.Paper,
    tertiary = NotepadColors.Green,
    onTertiary = NotepadColors.Paper,
    background = NotepadColors.Paper,
    onBackground = NotepadColors.Ink,
    surface = NotepadColors.Paper,
    onSurface = NotepadColors.Ink,
    surfaceVariant = NotepadColors.HeaderBand,
    onSurfaceVariant = NotepadColors.Muted,
    error = NotepadColors.Red,
    onError = NotepadColors.Paper,
    outline = NotepadColors.ChipBorder,
    outlineVariant = NotepadColors.HeaderBorder,
)

/**
 * Typography mapped to the notepad roles:
 *  - display/headline/title → Patrick Hand (handwritten headers and captions)
 *  - body → Inter (regular UI text)
 *  - label → Inter for buttons (labelLarge), Patrick Hand for small captions (label M/S)
 * Math (STIX Two Text) has no Material text role; use [NotepadFonts.StixTwoText] directly
 * in the math composables. Sizes follow the mockups where a role maps cleanly.
 */
private val NotepadTypography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = NotepadFonts.PatrickHand),
        displayMedium = displayMedium.copy(fontFamily = NotepadFonts.PatrickHand),
        displaySmall = displaySmall.copy(fontFamily = NotepadFonts.PatrickHand),
        headlineLarge = headlineLarge.copy(fontFamily = NotepadFonts.PatrickHand),
        headlineMedium = headlineMedium.copy(fontFamily = NotepadFonts.PatrickHand),
        headlineSmall = headlineSmall.copy(fontFamily = NotepadFonts.PatrickHand, fontSize = 30.sp, lineHeight = 32.sp),
        titleLarge = titleLarge.copy(fontFamily = NotepadFonts.PatrickHand),
        titleMedium = titleMedium.copy(fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium),
        titleSmall = titleSmall.copy(fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium),
        bodyLarge = bodyLarge.copy(fontFamily = NotepadFonts.Inter),
        bodyMedium = bodyMedium.copy(fontFamily = NotepadFonts.Inter),
        bodySmall = bodySmall.copy(fontFamily = NotepadFonts.Inter),
        // Buttons render labelLarge → Inter. Small labels are the handwritten captions.
        labelLarge = labelLarge.copy(fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        labelMedium = labelMedium.copy(fontFamily = NotepadFonts.PatrickHand),
        labelSmall = labelSmall.copy(fontFamily = NotepadFonts.PatrickHand),
    )
}

/** Wraps [MaterialTheme] with the notepad colors and typography. Screens are restyled separately. */
@Composable
fun NotepadTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = NotepadColorScheme,
        typography = NotepadTypography,
        content = content,
    )
}
