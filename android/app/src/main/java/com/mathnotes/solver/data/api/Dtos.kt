package com.mathnotes.solver.data.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/** Mirrors backend/app/models.py — keep the two in sync (docs/api.md is the contract). */

@JsonClass(generateAdapter = false)
data class RecognizeResponse(
    @Json(name = "session_id") val sessionId: String,
    val latex: String,
    val confidence: Double,
    @Json(name = "topic_guess") val topicGuess: String,
    @Json(name = "low_confidence") val lowConfidence: Boolean,
    // Identify split (backend services/identify.py): the problem, then the student's own steps.
    // `latex` above stays the full recognized string. Defaults keep older backends working.
    @Json(name = "problem_latex") val problemLatex: String = "",
    val steps: List<String> = emptyList(),
    val segments: List<Segment> = emptyList(),
    @Json(name = "split_uncertain") val splitUncertain: Boolean = false,
    // What the split was made at: "=", "⇒", "∴", "→", a word, or "line". Empty when not split.
    @Json(name = "split_at") val splitAt: String = "",
    // The problem did not parse: still a 200, with no steps; Review asks for a fix.
    @Json(name = "parse_error") val parseError: String? = null,
    // Every problem found in the capture, top to bottom; the fields above repeat problems[problemIndex].
    val problems: List<ProblemSplit> = emptyList(),
    @Json(name = "problem_index") val problemIndex: Int = 0,
)

/** One problem of a capture that holds several, with its own steps. */
@JsonClass(generateAdapter = false)
data class ProblemSplit(
    @Json(name = "problem_latex") val problemLatex: String,
    val steps: List<String> = emptyList(),
    @Json(name = "split_at") val splitAt: String = "",
    @Json(name = "split_uncertain") val splitUncertain: Boolean = false,
    @Json(name = "topic_guess") val topicGuess: String = "unknown",
    @Json(name = "parse_error") val parseError: String? = null,
    val bbox: List<Int>? = null,
)

/** A series step is a claim, not an expression: one of these two tokens. */
object Claim {
    const val CONVERGES = "CONVERGES"
    const val DIVERGES = "DIVERGES"
    fun isClaim(step: String): Boolean = step.trim().let { it == CONVERGES || it == DIVERGES }
}

/** One piece of the capture after the Identify split: a line, cut at its top-level `=`. */
@JsonClass(generateAdapter = false)
data class Segment(
    val latex: String,
    val line: Int,
    val bbox: List<Int>? = null,   // the source line's [x0, y0, x1, y1], when known
)

@JsonClass(generateAdapter = false)
data class PageLine(
    val latex: String,
    val confidence: Double,
    val bbox: List<Int>,   // [x0, y0, x1, y1] in pixels of the uploaded image
)

@JsonClass(generateAdapter = false)
data class PageRecognizeResponse(
    @Json(name = "session_id") val sessionId: String,
    val lines: List<PageLine>,
    @Json(name = "raw_text") val rawText: String,
    @Json(name = "image_w") val imageW: Int,
    @Json(name = "image_h") val imageH: Int,
)

@JsonClass(generateAdapter = false)
data class Step(
    val index: Int,
    val rule: String,
    @Json(name = "before_latex") val beforeLatex: String,
    @Json(name = "after_latex") val afterLatex: String,
    val note: String = "",
    // Set only on steps the app builds from a student-chain /verify (never sent by /solve):
    // status = "ok" | "wrong" | "unparseable"; source = "student" | "solution" (ours, shown below).
    val status: String? = null,
    val source: String? = null,
) {
    val isStudent: Boolean get() = source == SOURCE_STUDENT
    val isSolution: Boolean get() = source == SOURCE_SOLUTION

    companion object {
        const val SOURCE_STUDENT = "student"
        const val SOURCE_SOLUTION = "solution"
    }
}

/** The three outcomes of checking a student's chain, and how a stored session maps onto them. */
object Verdict {
    const val VERIFIED = "verified"
    const val MISMATCH = "mismatch"
    const val INCOMPLETE = "incomplete"
    /** App-side only: a capture that was read but never solved or checked (opened from My notebook). */
    const val UNSOLVED = "unsolved"

    /** Stored sessions keep (verified, mismatchAt): false with no flagged step means "almost". */
    fun of(verified: Boolean?, mismatchAt: Int?): String = when {
        verified == true -> VERIFIED
        verified == false && mismatchAt == null -> INCOMPLETE
        verified == false -> MISMATCH
        else -> ""
    }
}

@JsonClass(generateAdapter = false)
data class SolveRequest(
    @Json(name = "session_id") val sessionId: String,
    val latex: String,
    val topic: String = "unknown",
    val variable: String = "x",
)

@JsonClass(generateAdapter = false)
data class SolveResponse(
    @Json(name = "session_id") val sessionId: String,
    val topic: String,
    val steps: List<Step>,
    @Json(name = "answer_latex") val answerLatex: String,
    val verified: Boolean,
    @Json(name = "mismatch_at") val mismatchAt: Int?,
    val details: List<String> = emptyList(),
    /** App-side only: the verdict of a student-chain check ("" for a generated solution). */
    val verdict: String = "",
)

/** Student chain: [problemLatex] + [userSteps], the student's own lines in order. */
@JsonClass(generateAdapter = false)
data class VerifyRequest(
    @Json(name = "session_id") val sessionId: String,
    @Json(name = "problem_latex") val problemLatex: String,
    val topic: String = "unknown",
    val variable: String = "x",
    @Json(name = "user_steps") val userSteps: List<String>,
)

@JsonClass(generateAdapter = false)
data class UserStepCheck(
    val latex: String,
    val status: String,        // "ok" | "wrong" | "unparseable"
    val rule: String = "",     // rule name, "skipped n steps …", or "unrecognized but correct"
    val hint: String = "",     // common-mistake hint, or what the last step still lacks
)

@JsonClass(generateAdapter = false)
data class VerifyResponse(
    val verified: Boolean,
    @Json(name = "mismatch_at") val mismatchAt: Int?,
    val details: List<String> = emptyList(),
    val verdict: String = "",  // "verified" | "mismatch" | "incomplete"
    val steps: List<UserStepCheck> = emptyList(),
    @Json(name = "expected_final") val expectedFinal: String = "",
    @Json(name = "user_final_equals_expected") val userFinalEqualsExpected: Boolean = false,
    // Series only: our convergence tests, listed under the student's claim as the explanation.
    @Json(name = "solution_steps") val solutionSteps: List<Step> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class HealthResponse(
    val status: String,
    @Json(name = "stub_recognition") val stubRecognition: Boolean,
    @Json(name = "mathpix_reachable") val mathpixReachable: Boolean?,
)
