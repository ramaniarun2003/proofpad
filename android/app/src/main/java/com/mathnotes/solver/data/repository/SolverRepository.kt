package com.mathnotes.solver.data.repository

import android.content.Context
import android.provider.Settings
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.RecognizeResponse
import com.mathnotes.solver.data.api.SolveRequest
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.api.VerifyRequest
import com.mathnotes.solver.data.api.VerifyResponse
import com.mathnotes.solver.data.db.AppDatabase
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.data.handwriting.CorrectionMap
import com.mathnotes.solver.data.handwriting.CorrectionSuggestion
import com.mathnotes.solver.data.handwriting.DefaultRules
import com.squareup.moshi.Types
import timber.log.Timber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Single entry point for the UI: talks to the backend and mirrors results into Room.
 * ViewModels call this; screens never touch Retrofit or Room directly.
 */
class SolverRepository(context: Context) {
    private val app = context.applicationContext
    private val dao = AppDatabase.get(app).sessions()
    private val handwritingDao = AppDatabase.get(app).handwriting()
    private val api get() = ApiClient.service
    private val stepsAdapter = ApiClient.moshi.adapter<List<Step>>(Types.newParameterizedType(List::class.java, Step::class.java))
    private val deviceId: String = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    val history: Flow<List<SessionEntity>> = dao.observeAll()

    /**
     * Upload an image for recognition. [persist] mirrors the result into a Room session so it
     * shows in History; the handwriting setup wizard passes false so its page scans stay out of it.
     */
    suspend fun recognize(imageFile: File, persist: Boolean = true): RecognizeResponse = withContext(Dispatchers.IO) {
        val part = MultipartBody.Part.createFormData(
            "image", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaType())
        )
        val resp = api.recognize(part, deviceId.toRequestBody("text/plain".toMediaType()))
        val corrected = if (persist) correctLatex(resp.latex, resp.confidence) else resp.latex
        if (persist) {
            dao.upsert(
                SessionEntity(
                    sessionId = resp.sessionId,
                    imagePath = imageFile.absolutePath,
                    recognizedLatex = corrected,
                    confidence = resp.confidence,
                    topic = resp.topicGuess,
                )
            )
        }
        resp.copy(latex = corrected)
    }

    /**
     * Apply the per-user correction map (built from the handwriting samples) to a recognizer
     * result before it is shown/solved, keeping the backend stateless. With no samples yet, the
     * built-in default `\times`→`x` heuristic stands in. Every rewrite is logged for the demo.
     */
    private suspend fun correctLatex(latex: String, confidence: Double): String {
        val samples = handwritingDao.getAll()
        val base = if (samples.isEmpty()) {
            DefaultRules.correctTimes(latex, confidence, DefaultRules.looksParseable(latex))
                .also { if (it != latex) Timber.d("correction(default): \\times -> x  |  %s -> %s", latex, it) }
        } else {
            latex
        }
        val result = CorrectionMap.fromSamples(samples).applyTo(base)
        result.rewrites.forEach { r ->
            Timber.d("correction(user): %s -> %s%s", r.from, r.to, r.context?.let { " after $it" } ?: "")
        }
        return result.latex
    }

    /** SUGGEST-mode rules whose from-token appears in [latex]; shown on Review for a one-tap swap. */
    suspend fun suggestionsFor(latex: String): List<CorrectionSuggestion> = withContext(Dispatchers.IO) {
        CorrectionMap.fromSamples(handwritingDao.getAll()).suggestionsFor(latex)
    }

    suspend fun solve(sessionId: String, latex: String, topic: String): SolveResponse = withContext(Dispatchers.IO) {
        val resp = api.solve(SolveRequest(sessionId = sessionId, latex = latex, topic = topic))
        val existing = dao.get(resp.sessionId) ?: SessionEntity(sessionId = resp.sessionId)
        dao.upsert(
            existing.copy(
                confirmedLatex = latex,
                topic = resp.topic,
                stepsJson = stepsAdapter.toJson(resp.steps),
                answerLatex = resp.answerLatex,
                verified = resp.verified,
                mismatchAt = resp.mismatchAt,
            )
        )
        resp
    }

    suspend fun verify(sessionId: String, latex: String, topic: String = "unknown", steps: List<Step>): VerifyResponse =
        withContext(Dispatchers.IO) { api.verify(VerifyRequest(sessionId = sessionId, latex = latex, topic = topic, steps = steps)) }

    suspend fun saveVerification(
        sessionId: String,
        latex: String,
        topic: String,
        steps: List<Step>,
        verified: Boolean,
        mismatchAt: Int?,
    ) = withContext(Dispatchers.IO) {
        val existing = dao.get(sessionId) ?: SessionEntity(sessionId = sessionId)
        dao.upsert(
            existing.copy(
                confirmedLatex = latex,
                topic = topic,
                stepsJson = stepsAdapter.toJson(steps),
                answerLatex = steps.lastOrNull()?.afterLatex ?: latex,
                verified = verified,
                mismatchAt = mismatchAt,
            )
        )
    }

    suspend fun backendReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.health().status == "ok" }.getOrDefault(false)
    }

    suspend fun cached(sessionId: String): SessionEntity? = dao.get(sessionId)

    suspend fun setDifficulty(sessionId: String, difficulty: Difficulty?) = withContext(Dispatchers.IO) {
        dao.get(sessionId)?.let { session ->
            dao.upsert(session.copy(difficulty = difficulty))
        }
    }

    fun stepsOf(session: SessionEntity): List<Step> = stepsAdapter.fromJson(session.stepsJson) ?: emptyList()
}
