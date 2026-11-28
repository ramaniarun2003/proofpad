package com.mathnotes.solver.data.repository

import android.content.Context
import android.provider.Settings
import com.mathnotes.solver.data.api.BackendLocator
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.Claim
import com.mathnotes.solver.data.api.PageRecognizeResponse
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

    private val prefs = app.getSharedPreferences("solver_settings", android.content.Context.MODE_PRIVATE)
    /** Kill switch for the per-user correction map; default on, toggled in Settings. */
    var useCorrections: Boolean
        get() = prefs.getBoolean("use_corrections", true)
        set(v) { prefs.edit().putBoolean("use_corrections", v).apply() }

    /** The recognizer's output plus the raw (pre-correction) LaTeX, for the Review tooltip. */
    data class RecognizeResult(val response: RecognizeResponse, val rawLatex: String)

    /**
     * Upload one expression (mode=formula). [persist] mirrors the result into a Room session so it
     * shows in History; the handwriting setup wizard passes false so its page scans stay out of it.
     */
    suspend fun recognize(imageFile: File, persist: Boolean = true): RecognizeResult = withContext(Dispatchers.IO) {
        val part = MultipartBody.Part.createFormData(
            "image", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaType())
        )
        val resp = api.recognize(part, deviceId.toRequestBody("text/plain".toMediaType()))
        val corrected = correctLatex(resp.latex, resp.confidence)
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
        // The per-user corrections apply to the split pieces too, so Review shows them corrected.
        val problem = correctLatex(resp.problemLatex.ifBlank { resp.latex }, resp.confidence)
        val steps = resp.steps.map { if (Claim.isClaim(it)) it else correctLatex(it, resp.confidence) }
        Timber.i("recognize split: problem=%s | steps=%s | at=%s | uncertain=%s | parse_error=%s",
            problem, steps, resp.splitAt, resp.splitUncertain, resp.parseError)
        RecognizeResult(resp.copy(latex = corrected, problemLatex = problem, steps = steps), rawLatex = resp.latex)
    }

    /** Upload a whole page (mode=page): per-line LaTeX + pixel bboxes. Never persisted to History. */
    suspend fun recognizePage(imageFile: File): PageRecognizeResponse = withContext(Dispatchers.IO) {
        val part = MultipartBody.Part.createFormData(
            "image", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaType())
        )
        val mode = "page"
        val url = "${BackendLocator.peek()?.currentBaseUrl ?: "<no backend>/"}recognize"
        Timber.i(
            "page recognize request: POST %s | form: mode=%s device_id=%s image=%s (%d bytes, image/jpeg)",
            url, mode, deviceId, imageFile.name, imageFile.length(),
        )
        val resp = api.recognizePage(
            part,
            deviceId.toRequestBody("text/plain".toMediaType()),
            mode.toRequestBody("text/plain".toMediaType()),
        )
        // The line count exactly as the backend returned it, before any app-side handling.
        Timber.i(
            "page recognize response: lines=%d image=%dx%d session=%s | %s",
            resp.lines.size, resp.imageW, resp.imageH, resp.sessionId,
            resp.lines.joinToString(" | ") { it.latex.take(40) },
        )
        resp
    }

    /**
     * Apply the per-user correction map (built from the handwriting samples) to a recognizer result
     * before it is shown/solved, keeping the backend stateless. With no samples the built-in
     * `\times`→`x` heuristic stands in. The kill switch disables it entirely. Logs raw → corrected
     * and the rules that fired at INFO on every result.
     */
    private suspend fun correctLatex(latex: String, confidence: Double): String {
        if (!useCorrections) {
            Timber.i("recognize: raw=%s | corrections OFF", latex)
            return latex
        }
        val samples = handwritingDao.getAll()
        val base = if (samples.isEmpty()) {
            DefaultRules.correctTimes(latex, confidence, DefaultRules.looksParseable(latex))
        } else {
            latex
        }
        val result = CorrectionMap.fromSamples(samples).applyTo(base)
        val fired = result.rewrites.joinToString(", ") { "${it.from}->${it.to}${it.context?.let { c -> "@$c" } ?: ""}" }
        Timber.i("recognize: raw=%s | corrected=%s | rules=[%s]", latex, result.latex, fired)
        return result.latex
    }

    /** SUGGEST-mode rules whose from-token appears in [latex]; shown on Review for a one-tap swap. */
    suspend fun suggestionsFor(latex: String): List<CorrectionSuggestion> = withContext(Dispatchers.IO) {
        if (!useCorrections) return@withContext emptyList()
        CorrectionMap.fromSamples(handwritingDao.getAll()).suggestionsFor(latex)
    }

    /** Recompute the correction map now (Settings "Rebuild rules"); returns a short human summary. */
    suspend fun rebuildCorrectionRules(): String = withContext(Dispatchers.IO) {
        val rules = CorrectionMap.fromSamples(handwritingDao.getAll()).rules
        val apply = rules.count { it.mode.name == "APPLY" }
        Timber.i("rebuilt correction rules: %d apply, %d suggest", apply, rules.size - apply)
        "${rules.size} rules ($apply applied, ${rules.size - apply} suggested)"
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

    /** Check the student's own chain: the problem plus their steps, in order. */
    suspend fun verify(sessionId: String, problemLatex: String, topic: String, userSteps: List<String>): VerifyResponse =
        withContext(Dispatchers.IO) {
            api.verify(VerifyRequest(sessionId = sessionId, problemLatex = problemLatex, topic = topic, userSteps = userSteps))
        }

    /** Our own solution for the same problem, without touching the stored session (the caller saves). */
    suspend fun solveOnly(sessionId: String, latex: String, topic: String): SolveResponse =
        withContext(Dispatchers.IO) { api.solve(SolveRequest(sessionId = sessionId, latex = latex, topic = topic)) }

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
                answerLatex = steps.lastOrNull { !it.isSolution }?.afterLatex ?: latex,
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
