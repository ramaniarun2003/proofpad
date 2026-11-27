package com.mathnotes.solver.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.api.Verdict
import com.squareup.moshi.Types
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A checked student note must come back from Room exactly as it was shown: the student's own
 * lines with status, rule caption and hint, our solution steps (when shown), and the verdict —
 * with no backend involved. Steps are stored as JSON in `sessions.stepsJson`.
 */
@RunWith(AndroidJUnit4::class)
class StudentStepsRoundTripTest {

    private lateinit var db: AppDatabase
    private lateinit var sessions: SessionDao
    private val steps = ApiClient.moshi.adapter<List<Step>>(Types.newParameterizedType(List::class.java, Step::class.java))

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        sessions = db.sessions()
    }

    @After
    fun tearDown() = db.close()

    private val problem = """\frac{d}{d x} \sin (3 x)"""

    private fun student(index: Int, latex: String, status: String, rule: String, hint: String = "") = Step(
        index = index, rule = rule, beforeLatex = problem, afterLatex = latex, note = hint,
        status = status, source = Step.SOURCE_STUDENT,
    )

    @Test
    fun flaggedStudentNoteRoundTrips() = runBlocking {
        val hint = "chain rule missing: you differentiated the outside only; multiply by the derivative of the inside, 3"
        val saved = listOf(
            student(0, """\cos (3 x)""", "wrong", "your step 1", hint),
            student(1, """3 \cos (3 x)""", "ok", "skipped 2 steps", "correct; this line does several rules at once"),
            Step(0, "chain rule with sin", problem, """\cos (3 x) \frac{d}{d x} 3 x""", "", source = Step.SOURCE_SOLUTION),
            Step(1, "identity", """\cos (3 x) \frac{d}{d x} 3 x""", """3 \cos (3 x)""", "", source = Step.SOURCE_SOLUTION),
        )
        sessions.upsert(
            SessionEntity(
                sessionId = "student-1", confirmedLatex = problem, topic = "derivative",
                stepsJson = steps.toJson(saved), answerLatex = """3 \cos (3 x)""",
                verified = false, mismatchAt = 0,
            ),
        )

        val row = sessions.get("student-1")!!
        val back = steps.fromJson(row.stepsJson)!!
        assertEquals(saved, back)                                   // every field, in order

        val own = back.filter { it.isStudent }
        assertEquals(listOf("wrong", "ok"), own.map { it.status })
        assertEquals(listOf("your step 1", "skipped 2 steps"), own.map { it.rule })
        assertEquals(hint, own[0].note)
        assertEquals(2, back.count { it.isSolution })
        assertEquals(Verdict.MISMATCH, Verdict.of(row.verified, row.mismatchAt))
        assertEquals(0, row.mismatchAt)
    }

    @Test
    fun almostAndVerifiedVerdictsRoundTrip() = runBlocking {
        val almost = listOf(student(0, "x^{2}", "ok", "skipped 1 step", "missing constant of integration: add + C"))
        sessions.upsert(SessionEntity(sessionId = "almost", confirmedLatex = """\int 2 x d x""", topic = "integral",
            stepsJson = steps.toJson(almost), verified = false, mismatchAt = null))
        val done = listOf(student(0, """3 \cos (3 x)""", "ok", "skipped 2 steps"))
        sessions.upsert(SessionEntity(sessionId = "done", confirmedLatex = problem, topic = "derivative",
            stepsJson = steps.toJson(done), verified = true, mismatchAt = null))

        val a = sessions.get("almost")!!
        assertEquals(Verdict.INCOMPLETE, Verdict.of(a.verified, a.mismatchAt))
        assertNull(a.mismatchAt)
        assertEquals(almost, steps.fromJson(a.stepsJson))

        val d = sessions.get("done")!!
        assertEquals(Verdict.VERIFIED, Verdict.of(d.verified, d.mismatchAt))
        assertEquals(done, steps.fromJson(d.stepsJson))
    }

    /** Filing the note into a folder (the save sheet) must not disturb the steps or the verdict. */
    @Test
    fun savingToTheGalleryKeepsStepsAndVerdict() = runBlocking {
        val gallery = db.gallery()
        val saved = listOf(student(0, """\cos (3 x)""", "wrong", "your step 1", "chain rule missing"))
        sessions.upsert(SessionEntity(sessionId = "g", confirmedLatex = problem, topic = "derivative",
            stepsJson = steps.toJson(saved), verified = false, mismatchAt = 0))
        val folderId = gallery.insertFolder(FolderEntity(name = "Calc 1", colorIndex = 0))
        gallery.saveToGallery("g", folderId, "Chain rule slip", Difficulty.MEDIUM, listOf("chain-rule"), true, 1_700_000_000_000L)

        val row = sessions.get("g")!!
        assertEquals(folderId, row.folderId)
        assertEquals(Difficulty.MEDIUM, row.difficulty)
        assertEquals(saved, steps.fromJson(row.stepsJson))
        assertFalse(row.verified!!)
        assertEquals(0, row.mismatchAt)
        assertTrue(steps.fromJson(row.stepsJson)!!.all { it.isStudent })
    }

    /** Notes saved before student checking existed have no status/source and still load. */
    @Test
    fun olderGeneratedStepsStillLoad() = runBlocking {
        val json = """[{"index":0,"rule":"power rule","before_latex":"a","after_latex":"b","note":""}]"""
        sessions.upsert(SessionEntity(sessionId = "old", stepsJson = json, verified = true))
        val back = steps.fromJson(sessions.get("old")!!.stepsJson)!!
        assertNull(back[0].status)
        assertFalse(back[0].isStudent || back[0].isSolution)
    }
}
