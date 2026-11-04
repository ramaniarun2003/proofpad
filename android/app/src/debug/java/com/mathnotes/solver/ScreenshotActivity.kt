package com.mathnotes.solver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderEntity
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.ui.FolderDetailContent
import com.mathnotes.solver.ui.GalleryHomeContent
import com.mathnotes.solver.ui.HistoryContent
import com.mathnotes.solver.ui.ResultsContent
import com.mathnotes.solver.ui.SaveToGalleryContent
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
        val state = intent.getStringExtra("state")
        setContent {
            NotepadTheme {
                Surface(color = NotepadColors.Paper) {
                    when (state) {
                        "flagged" -> FlaggedSample()
                        "history" -> HistorySample()
                        "save" -> SaveSample()
                        "gallery" -> GallerySample()
                        "folder" -> FolderSample()
                        else -> VerifiedSample()
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun HistorySample() {
    val now = System.currentTimeMillis()
    val day = 24L * 60 * 60 * 1000
    val samples = listOf(
        SessionEntity("h1", now - 60_000, null, "", 0.0, "\\int 2x \\, dx", "integral", "[]", "x^{2} + C", true, null),
        SessionEntity("h2", now - 900_000, null, "", 0.0, "\\frac{d}{dx}[x^{2}\\sin x]", "derivative", "[]", "", false, 1),
        SessionEntity("h3", now - day, null, "", 0.0, "\\frac{d}{dx}\\ln(x^{2}+1)", "derivative", "[]", "\\frac{2x}{x^{2}+1}", true, null),
        SessionEntity("h4", now - day - 600_000, null, "", 0.0, "\\int \\sin x \\cos x \\, dx", "integral", "[]", "\\frac{\\sin^{2} x}{2} + C", true, null),
        SessionEntity("h5", now - day - 1_200_000, null, "", 0.0, "\\sum \\frac{(-1)^{n}}{n}", "series", "[]", "converges", true, null),
    )
    val stepCounts = mapOf("h1" to 3, "h3" to 2, "h4" to 4)
    HistoryContent(
        history = samples,
        stepCountOf = { stepCounts[it.sessionId] ?: 0 },
        onOpen = {}, onSettings = {}, onSnap = {},
    )
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
        difficulty = Difficulty.MEDIUM,
        onExport = {},
        onNewProblem = {},
    )
}

@androidx.compose.runtime.Composable
private fun FolderSample() {
    val now = System.currentTimeMillis()
    val day = 86_400_000L
    fun n(id: String, latex: String, answer: String, verified: Boolean, mismatchAt: Int?, diff: Difficulty, tag: String, daysAgo: Int, topic: String = "integral") =
        SessionEntity(sessionId = id, recognizedLatex = latex, confirmedLatex = latex, topic = topic,
            answerLatex = answer, verified = verified, mismatchAt = mismatchAt, difficulty = diff,
            tags = listOf(tag), savedAt = now - daysAgo * day, folderId = 1)
    val notes = listOf(
        n("1", "\\int 2x \\, dx", "x^{2} + C", true, null, Difficulty.EASY, "HW 7", 3),
        n("2", "\\frac{d}{dx}[x^{2}\\sin x]", "", false, 1, Difficulty.EASY, "HW 7", 3, "derivative"),
        n("3", "\\int \\sin x \\cos x \\, dx", "\\frac{\\sin^{2}x}{2} + C", true, null, Difficulty.MEDIUM, "HW 7", 4),
        n("4", "\\sum \\frac{(-1)^{n}}{n}", "converges", true, null, Difficulty.MEDIUM, "Quiz prep", 6, "series"),
        n("5", "\\int x e^{x} \\, dx", "(x-1)e^{x} + C", true, null, Difficulty.HARD, "Quiz prep", 7),
    )
    val counts = mapOf("1" to 3, "2" to 3, "3" to 4, "4" to 2, "5" to 2)
    FolderDetailContent(
        folderName = "Calc II", colorIndex = 0, term = "Fall 26", noteCount = 11, notes = notes,
        flaggedOnly = false, topicFilter = null, difficultyFilter = null,
        onToggleFlagged = {}, onTopic = {}, onDifficulty = {}, stepCountOf = { counts[it.sessionId] ?: 0 },
        onBack = {}, onOpenNote = {}, onAddNote = {}, onRename = {}, onDelete = {},
    )
}

@androidx.compose.runtime.Composable
private fun GallerySample() {
    val now = System.currentTimeMillis()
    val h = 3_600_000L
    val day = 86_400_000L
    val folders = listOf(
        FolderWithCount(FolderEntity(id = 1, name = "Calc II", colorIndex = 0), 11),
        FolderWithCount(FolderEntity(id = 2, name = "Diff Eq", colorIndex = 1), 7),
        FolderWithCount(FolderEntity(id = 3, name = "Physics", colorIndex = 2), 5),
    )
    fun note(id: String, latex: String, folderId: Long, diff: Difficulty, verified: Boolean, mismatchAt: Int? = null, viewed: Long? = null, saved: Long? = null) =
        SessionEntity(sessionId = id, recognizedLatex = latex, confirmedLatex = latex, topic = "integral",
            verified = verified, mismatchAt = mismatchAt, folderId = folderId, difficulty = diff, savedAt = saved, lastViewedAt = viewed)
    val recent = listOf(
        note("r1", "\\int 2x \\, dx", 1, Difficulty.EASY, true, viewed = now - 2 * h, saved = now - 2 * h),
        note("r2", "\\frac{d}{dx}[x^{2}\\sin x]", 1, Difficulty.MEDIUM, false, 1, viewed = now - 3 * h, saved = now - 3 * h),
        note("r3", "y' + 2y = e^{x}", 2, Difficulty.HARD, true, viewed = now - day - h, saved = now - day),
    )
    val week = listOf(
        note("w1", "\\int \\sin x \\cos x \\, dx", 1, Difficulty.MEDIUM, true, saved = now - day),
        note("w2", "\\sum \\frac{(-1)^{n}}{n}", 1, Difficulty.MEDIUM, true, saved = now - 2 * day),
        note("w3", "\\frac{d}{dx}\\ln(x^{2}+1)", 1, Difficulty.EASY, true, saved = now - 3 * day),
        note("w4", "\\int x e^{x} \\, dx", 1, Difficulty.HARD, true, saved = now - 4 * day),
    )
    GalleryHomeContent(
        totalNotes = 23, query = "", onQueryChange = {}, searchResults = emptyList(),
        folders = folders, recentlyViewed = recent, savedThisWeek = week,
        onCreateFolder = { _, _, _ -> }, onOpenFolder = {}, onOpenNote = {}, onSettings = {}, onSnap = {}, nowOverride = now,
    )
}

@androidx.compose.runtime.Composable
private fun SaveSample() {
    val folders = listOf(
        FolderWithCount(FolderEntity(id = 1, name = "Calc II", colorIndex = 0), 11),
        FolderWithCount(FolderEntity(id = 2, name = "Diff Eq", colorIndex = 1), 7),
        FolderWithCount(FolderEntity(id = 3, name = "Physics", colorIndex = 2), 5),
    )
    SaveToGalleryContent(
        problemLatex = "\\int 2x \\, dx",
        answerLatex = "x^{2} + C",
        folders = folders,
        knownTags = listOf("HW 7", "Quiz prep", "Exam 2"),
        onSkip = {},
        onCreateFolder = { _, _, _ -> },
        onSave = { _, _, _, _, _, _ -> },
        initialTitle = "HW 7 problem 3",
        initialFolderId = 1,
        initialDifficulty = Difficulty.MEDIUM,
        initialTags = listOf("HW 7"),
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
