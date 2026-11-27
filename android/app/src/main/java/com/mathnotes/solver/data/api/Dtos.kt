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
)

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
)

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
)

@JsonClass(generateAdapter = false)
data class VerifyRequest(
    @Json(name = "session_id") val sessionId: String,
    val latex: String,
    val topic: String = "unknown",
    val variable: String = "x",
    val steps: List<Step>,
)

@JsonClass(generateAdapter = false)
data class VerifyResponse(
    val verified: Boolean,
    @Json(name = "mismatch_at") val mismatchAt: Int?,
    val details: List<String> = emptyList(),
)

@JsonClass(generateAdapter = false)
data class HealthResponse(
    val status: String,
    @Json(name = "stub_recognition") val stubRecognition: Boolean,
    @Json(name = "mathpix_reachable") val mathpixReachable: Boolean?,
)
