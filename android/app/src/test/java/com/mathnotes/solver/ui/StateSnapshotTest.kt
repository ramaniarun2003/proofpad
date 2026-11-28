package com.mathnotes.solver.ui

import com.mathnotes.solver.data.api.ProblemSplit
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.api.Verdict
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.image.CropSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The capture in progress and the result must come back after the process is killed. */
class StateSnapshotTest {

    private fun roundTrip(state: SolverUiState, capture: CaptureSnapshot? = null, pending: File? = null, folderId: Long = -1, query: String = ""): StateSnapshot =
        snapshotFromJson(state.toSnapshot(capture, pending, folderId, query).toJson())!!

    private val review = SolverUiState(
        imageFile = File("/data/user/0/app/files/images/a_pre.jpg"),
        sessionId = "s-1",
        latex = """\\frac{d}{dx} x^{2} \\sin x + 1""",          // edited by the student
        rawLatex = """d / d x x^{2} \\sin x""",
        confidence = 0.83, lowConfidence = false,
        topic = "derivative", topicHint = "derivative",
        userSteps = listOf("""2x\\sin x + x^2\\cos x""", "CONVERGES", ""),
        problems = listOf(ProblemSplit("a", listOf("b"), splitAt = "=", topicGuess = "derivative", bbox = listOf(1, 2, 3, 4)), ProblemSplit("c")),
        problemIndex = 1, splitAt = "⇒", splitUncertain = true,
        parseError = "could not parse",
    )

    @Test fun review_state_survives() {
        val back = roundTrip(review).toUiState()
        assertEquals(review, back)
    }

    @Test fun results_state_survives_with_student_and_solution_steps() {
        val steps = listOf(
            Step(0, "product rule (skipped 2 steps)", "p", "a", note = "correct", status = "ok", source = Step.SOURCE_STUDENT),
            Step(1, "simplify", "a", "b", status = "wrong", source = Step.SOURCE_STUDENT),
            Step(0, "product rule", "p", "a", source = Step.SOURCE_SOLUTION),
        )
        val results = review.copy(
            parseError = null, difficulty = Difficulty.HARD, savedTo = "Calc II", solutionAnswer = "b",
            result = SolveResponse("s-1", "derivative", steps, "b", verified = false, mismatchAt = 1, details = listOf("step 1: x"), verdict = Verdict.MISMATCH),
        )
        assertEquals(results, roundTrip(results).toUiState())
    }

    @Test fun nothing_is_busy_after_a_restart() {
        val solving = review.copy(busy = true, status = "Verifying steps…", solutionBusy = true, exporting = true)
        val back = roundTrip(solving).toUiState()
        assertFalse(back.busy || back.solutionBusy || back.exporting)
        assertEquals("", back.status)
        assertEquals(review.latex, back.latex)          // the reading is still there to check again
        assertNull(back.recognizeError)
    }

    @Test fun a_recognition_cut_off_mid_flight_comes_back_as_a_retry() {
        val reading = SolverUiState(busy = true, status = "Recognizing…", imageFile = File("/cache/capture_1.jpg"))
        val capture = CaptureSnapshot.of(File("/cache/capture_1.jpg"), CropSpec.NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f), "series")
        val snap = roundTrip(reading, capture)
        val back = snap.toUiState()
        assertEquals(INTERRUPTED_NOTE, back.recognizeError)
        assertTrue(back.hasCapture)
        // …and Retry has what it needs: the photo and exactly how it was framed.
        assertEquals("/cache/capture_1.jpg", snap.lastCapture!!.raw)
        assertEquals(CropSpec.NormalizedRect(0.1f, 0.2f, 0.9f, 0.8f), snap.lastCapture!!.crop)
        assertEquals("series", snap.lastCapture!!.topicHint)
    }

    @Test fun the_camera_guide_box_crop_survives_too() {
        val box = CropSpec.GuideBox(1080f, 2400f, 0.08f, 0.25f, 0.84f, 0.23f, 0.05f)
        val snap = roundTrip(SolverUiState(), CaptureSnapshot.of(File("/c.jpg"), box, "unknown"))
        assertEquals(box, snap.lastCapture!!.crop)
    }

    @Test fun pending_picked_photo_open_folder_and_search_survive() {
        val snap = roundTrip(SolverUiState(), pending = File("/cache/picked_9.jpg"), folderId = 7, query = "u-sub")
        assertEquals("/cache/picked_9.jpg", snap.pendingGallery)
        assertEquals(7L, snap.folderId)
        assertEquals("u-sub", snap.galleryQuery)
    }

    @Test fun an_empty_or_unreadable_snapshot_restores_nothing() {
        assertNull(snapshotFromJson(null))
        assertNull(snapshotFromJson("not json"))
        assertNotNull(snapshotFromJson("{}"))                       // an older build's smaller snapshot still loads
        assertFalse(snapshotFromJson("{}")!!.toUiState().hasCapture)   // …and sends Review / Results back to Capture
        assertFalse(SolverUiState().hasCapture)
    }
}
