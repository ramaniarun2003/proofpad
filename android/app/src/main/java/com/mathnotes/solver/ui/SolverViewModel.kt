package com.mathnotes.solver.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.data.repository.SolverRepository
import com.mathnotes.solver.image.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.File
import java.io.IOException

/** One state object for the whole capture → review → results flow. */
data class SolverUiState(
    val busy: Boolean = false,
    val status: String = "",
    val error: String? = null,
    val imageFile: File? = null,
    val sessionId: String = "",
    val latex: String = "",
    val confidence: Double = 0.0,
    val lowConfidence: Boolean = false,
    val topic: String = "unknown",
    val result: SolveResponse? = null,
)

class SolverViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SolverRepository(app)

    private val _state = MutableStateFlow(SolverUiState())
    val state: StateFlow<SolverUiState> = _state

    val history: StateFlow<List<SessionEntity>> =
        repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setBackendUrl(url: String) { ApiClient.baseUrl = url }
    val backendUrl: String get() = ApiClient.baseUrl

    /** Step 1–3 of the workflow: preprocess on device, upload, receive LaTeX. */
    fun captureAndRecognize(raw: File) {
        _state.update { it.copy(busy = true, status = "Preprocessing…", error = null, result = null) }
        viewModelScope.launch {
            try {
                val clean = withContext(Dispatchers.Default) { Preprocessor.process(getApplication(), raw) }
                _state.update { it.copy(imageFile = clean, status = "Recognizing…") }
                val rec = repo.recognize(clean)
                _state.update {
                    it.copy(
                        busy = false, status = "",
                        sessionId = rec.sessionId, latex = rec.latex,
                        confidence = rec.confidence, lowConfidence = rec.lowConfidence, topic = rec.topicGuess,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "", error = describe(e)) }
            }
        }
    }

    /** The user edits the recognized LaTeX on the Review screen. */
    fun updateLatex(latex: String) = _state.update { it.copy(latex = latex) }
    fun updateTopic(topic: String) = _state.update { it.copy(topic = topic) }

    /** Step 5–6: send the confirmed LaTeX; receive steps + verification. */
    fun solve() {
        val s = _state.value
        _state.update { it.copy(busy = true, status = "Solving…", error = null) }
        viewModelScope.launch {
            try {
                val resp = repo.solve(s.sessionId, s.latex, s.topic)
                _state.update { it.copy(busy = false, status = "", sessionId = resp.sessionId, topic = resp.topic, result = resp) }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "", error = describe(e)) }
            }
        }
    }

    /** Demo fallback: reopen a cached session without the network. */
    fun openCached(session: SessionEntity) {
        _state.update {
            it.copy(
                sessionId = session.sessionId, latex = session.confirmedLatex.ifEmpty { session.recognizedLatex },
                topic = session.topic, imageFile = session.imagePath?.let(::File),
                result = SolveResponse(
                    sessionId = session.sessionId, topic = session.topic, steps = repo.stepsOf(session),
                    answerLatex = session.answerLatex, verified = session.verified ?: false, mismatchAt = session.mismatchAt,
                ),
            )
        }
    }

    fun reset() = _state.update { SolverUiState() }

    private fun describe(e: Exception): String = when (e) {
        is HttpException -> when (e.code()) {
            413 -> "Image too large — move closer or crop."
            422 -> "The backend could not parse that expression: ${e.response()?.errorBody()?.string()?.take(120)}"
            503 -> "Recognition service unavailable — retry, or open a cached session from History."
            else -> "Server error ${e.code()}"
        }
        is IOException -> "Cannot reach the backend at ${ApiClient.baseUrl}. Same Wi-Fi? Correct IP in Settings?"
        else -> e.message ?: e.toString()
    }
}
