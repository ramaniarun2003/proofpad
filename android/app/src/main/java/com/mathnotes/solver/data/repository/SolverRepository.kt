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
import com.mathnotes.solver.data.db.SessionEntity
import com.squareup.moshi.Types
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
    private val api get() = ApiClient.service
    private val stepsAdapter = ApiClient.moshi.adapter<List<Step>>(Types.newParameterizedType(List::class.java, Step::class.java))
    private val deviceId: String = Settings.Secure.getString(app.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"

    val history: Flow<List<SessionEntity>> = dao.observeAll()

    suspend fun recognize(imageFile: File): RecognizeResponse = withContext(Dispatchers.IO) {
        val part = MultipartBody.Part.createFormData(
            "image", imageFile.name, imageFile.asRequestBody("image/jpeg".toMediaType())
        )
        val resp = api.recognize(part, deviceId.toRequestBody("text/plain".toMediaType()))
        dao.upsert(
            SessionEntity(
                sessionId = resp.sessionId,
                imagePath = imageFile.absolutePath,
                recognizedLatex = resp.latex,
                confidence = resp.confidence,
                topic = resp.topicGuess,
            )
        )
        resp
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

    suspend fun verify(sessionId: String, latex: String, topic: String, steps: List<Step>): VerifyResponse =
        withContext(Dispatchers.IO) { api.verify(VerifyRequest(sessionId = sessionId, latex = latex, topic = topic, steps = steps)) }

    suspend fun backendReachable(): Boolean = withContext(Dispatchers.IO) {
        runCatching { api.health().status == "ok" }.getOrDefault(false)
    }

    suspend fun cached(sessionId: String): SessionEntity? = dao.get(sessionId)

    fun stepsOf(session: SessionEntity): List<Step> = stepsAdapter.fromJson(session.stepsJson) ?: emptyList()
}
