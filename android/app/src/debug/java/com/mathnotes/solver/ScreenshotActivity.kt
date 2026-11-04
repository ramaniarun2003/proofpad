package com.mathnotes.solver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.ui.ResultsContent
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadTheme

/**
 * Debug-only harness to screenshot the Results screen in states the normal flow
 * can't reach (the solver never emits a wrong step, so "flagged" needs faking).
 * Launch with:
 *   adb shell am start -n com.mathnotes.solver/.ScreenshotActivity --es state verified
 *   adb shell am start -n com.mathnotes.solver/.ScreenshotActivity --es state flagged
 * then grab the frame with `adb exec-out screencap -p`. Not part of the release build.
 */
class ScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val flagged = intent.getStringExtra("state") == "flagged"
        setContent {
            NotepadTheme {
                Surface(color = NotepadColors.Paper) {
                    if (flagged) FlaggedSample() else VerifiedSample()
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun VerifiedSample() {
    ResultsContent(
        problemLatex = "\\int 2x \\, dx",
        topic = "integral",
        dateLabel = "Nov 2",
        steps = listOf(
            Step(0, "constant multiple", "\\int 2x \\, dx", "2 \\int x \\, dx"),
            Step(1, "power rule", "2 \\int x \\, dx", "2 \\cdot \\frac{x^{2}}{2}",
                note = "\\int x^{n} dx = x^{n+1}/(n+1), with n = 1."),
            Step(2, "simplify", "2 \\cdot \\frac{x^{2}}{2}", "x^{2}"),
        ),
        answerLatex = "x^{2} + C",
        verified = true,
        mismatchAt = null,
        details = emptyList(),
        onExport = {},
        onNewProblem = {},
    )
}

@androidx.compose.runtime.Composable
private fun FlaggedSample() {
    ResultsContent(
        problemLatex = "\\frac{d}{dx}\\left[x^{2} \\sin x\\right]",
        topic = "derivative",
        dateLabel = "Nov 2",
        steps = listOf(
            Step(0, "product rule", "\\frac{d}{dx}[x^{2}\\sin x]", "(x^{2})' \\sin x + x^{2} (\\sin x)'"),
            Step(1, "power rule, chain rule", "(x^{2})' \\sin x + x^{2} (\\sin x)'", "2x \\sin x + x^{2} \\sin x"),
            Step(2, "factor", "2x \\sin x + x^{2} \\sin x", "x(2 \\sin x + x \\sin x)"),
        ),
        answerLatex = "",
        verified = false,
        mismatchAt = 1,
        details = listOf(
            "Step 2 doesn't match step 1. (sin x)' should be cos x, not sin x. Checked numerically at 5 points, max error 1.3.",
        ),
        onExport = {},
        onNewProblem = {},
    )
}
