package com.mathnotes.solver.ui.onboarding

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import kotlinx.coroutines.launch

/** One page of the guide: its words, and which illustration goes with them. */
private data class GuidePage(val headline: String, val body: String, val illustration: @Composable BoxScope.() -> Unit)

private val PAGES = listOf(
    GuidePage(
        "Snap one problem",
        "Write the problem and your working in your notebook, line it up in the yellow box, and tap the shutter. " +
            "Auto picks the topic for you; the chips let you choose.",
    ) { SnapIllustration() },
    GuidePage(
        "I check every step",
        "I read your handwriting, you confirm it, then each line of your work gets a check or an X with the rule it used. " +
            "Wrong step? I say which one and why, and I can show the full solution.",
    ) { CheckIllustration() },
    GuidePage(
        "Keep it in your notebook",
        "Save solved problems into class folders, rate how hard they were, and find them again by week or by search. " +
            "Export any note as a PDF when it is time to study.",
    ) { SaveIllustration() },
)

/**
 * The one-time three-page guide between the notebook cover and the handwriting setup wizard
 * (mockups 11_guide_1_snap / 2_check / 3_save). Swipe or use the buttons. Skip on any page, and
 * the last page's button, go on to the wizard ([onToSetup]); Back on the first page returns to
 * the cover ([onToCover]).
 *
 * The page is kept across process death (the pager state is saveable), and with animations
 * turned off in system settings the buttons change page without sliding.
 */
@Composable
fun GuideScreen(onToCover: () -> Unit, onToSetup: () -> Unit) {
    val pager = rememberPagerState(pageCount = { Onboarding.GUIDE_PAGES })
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }

    fun go(move: Onboarding.GuideMove) {
        when (move) {
            is Onboarding.GuideMove.ToPage -> scope.launch {
                if (reducedMotion) pager.scrollToPage(move.page) else pager.animateScrollToPage(move.page)
            }
            Onboarding.GuideMove.ToSetup -> onToSetup()
            Onboarding.GuideMove.ToCover -> onToCover()
        }
    }
    // System back: the previous page, and from the first page the cover.
    BackHandler { go(Onboarding.onBack(pager.currentPage)) }

    Column(Modifier.fillMaxSize()) {
        // The blank header band of the page, under the status bar.
        Column(Modifier.fillMaxWidth().background(NotepadColors.HeaderBand).statusBarsPadding()) {
            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = NotepadColors.HeaderBorder, thickness = 1.dp)
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth().ruledPaper()) { index ->
            GuidePageContent(index, PAGES[index], onSkip = { go(Onboarding.onSkip()) })
        }

        val page = pager.currentPage
        NotepadBottomBar {
            OutlinedButton(
                onClick = { go(if (page == 0) Onboarding.onSkip() else Onboarding.onBack(page)) },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) { Text(if (page == 0) "Skip" else "Back", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
            Button(
                onClick = { go(Onboarding.onNext(page)) },
                modifier = Modifier.weight(1.4f).heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
                // The last page's label is long: tighter side padding keeps it on one line, and at
                // large text sizes it wraps (the button grows) instead of being cut off.
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    if (page == Onboarding.GUIDE_PAGES - 1) "Set up my handwriting" else "Next",
                    fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun GuidePageContent(index: Int, page: GuidePage, onSkip: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())   // short screens and large text scroll instead of clipping
            .padding(start = 56.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
    ) {
        // Step number in a 44dp ink circle, "step n of 3", and Skip at the top right (not on the last page).
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(NotepadColors.Ink), contentAlignment = Alignment.Center) {
                Text("${index + 1}", fontFamily = NotepadFonts.PatrickHand, fontSize = 26.sp, color = NotepadColors.Paper)
            }
            Spacer(Modifier.width(10.dp))
            Text("step ${index + 1} of ${Onboarding.GUIDE_PAGES}", fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Muted)
            Spacer(Modifier.weight(1f))
            if (Onboarding.showsSkip(index)) {
                Box(
                    Modifier.testTag("guide_skip_$index").heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onSkip).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Skip", fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Muted) }
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(ILLUSTRATION_HEIGHT)) { page.illustration(this) }
        Spacer(Modifier.height(16.dp))

        Text(
            page.headline,
            Modifier.semantics { heading() },
            fontFamily = NotepadFonts.PatrickHand, fontSize = 32.sp, lineHeight = 38.sp, color = NotepadColors.Ink,
        )
        Spacer(Modifier.height(10.dp))
        Text(page.body, fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, lineHeight = 24.sp, color = NotepadColors.NoteInkYellow)

        Spacer(Modifier.height(22.dp))
        // Page dots: a 26x8 ink pill for this page, 8dp dots for the others.
        Row(
            Modifier.semantics { contentDescription = "Page ${index + 1} of ${Onboarding.GUIDE_PAGES}" },
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(Onboarding.GUIDE_PAGES) { dot ->
                Box(
                    Modifier
                        .size(width = if (dot == index) 26.dp else 8.dp, height = 8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (dot == index) NotepadColors.Ink else NotepadColors.ChipBorder),
                )
            }
        }
    }
}
