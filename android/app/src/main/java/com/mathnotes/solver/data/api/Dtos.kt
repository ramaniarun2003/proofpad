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
    val topic: String,
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
