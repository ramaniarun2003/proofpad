package com.mathnotes.solver.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.data.repository.GalleryRepository
import com.mathnotes.solver.data.repository.SolverRepository
import com.mathnotes.solver.image.CropSpec
import com.mathnotes.solver.image.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
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
    val difficulty: Difficulty? = null,
)

/** Active filters for the folder-detail screen (folderId < 0 means "no folder open"). */
data class FolderFilter(
    val folderId: Long = -1,
    val flaggedOnly: Boolean = false,
    val topic: String? = null,
    val difficulty: Difficulty? = null,
)

class SolverViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SolverRepository(app)
    private val galleryRepo = GalleryRepository(app)
    private val prefs = app.getSharedPreferences("solver_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SolverUiState())
    val state: StateFlow<SolverUiState> = _state

    val history: StateFlow<List<SessionEntity>> =
        repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // --- Gallery ---
    val folders: StateFlow<List<FolderWithCount>> =
        galleryRepo.folders.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val knownTags: StateFlow<List<String>> =
        galleryRepo.allTags.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentlyViewed: StateFlow<List<SessionEntity>> =
        galleryRepo.recentlyViewed.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val savedThisWeek: StateFlow<List<SessionEntity>> =
        galleryRepo.savedThisWeek().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _galleryQuery = MutableStateFlow("")
    val galleryQuery: StateFlow<String> = _galleryQuery
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val searchResults: StateFlow<List<SessionEntity>> =
        _galleryQuery.flatMapLatest { q ->
            if (q.isBlank()) kotlinx.coroutines.flow.flowOf(emptyList()) else galleryRepo.search(q)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setGalleryQuery(q: String) { _galleryQuery.value = q }

    // --- Folder detail (filtered notes in one folder) ---
    private val _folderFilter = MutableStateFlow(FolderFilter())
    val folderFilter: StateFlow<FolderFilter> = _folderFilter

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val folderNotes: StateFlow<List<SessionEntity>> =
        _folderFilter.flatMapLatest { f ->
            if (f.folderId < 0) kotlinx.coroutines.flow.flowOf(emptyList())
            else galleryRepo.notesInFolder(f.folderId, f.flaggedOnly, f.topic, f.difficulty)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Enter a folder (resets filters). */
    fun openFolder(folderId: Long) { _folderFilter.value = FolderFilter(folderId = folderId) }
    fun setFolderFlagged(on: Boolean) = _folderFilter.update { it.copy(flaggedOnly = on) }
    fun setFolderTopic(topic: String?) = _folderFilter.update { it.copy(topic = topic) }
    fun setFolderDifficulty(d: Difficulty?) = _folderFilter.update { it.copy(difficulty = d) }
    fun renameFolder(id: Long, name: String) { viewModelScope.launch { galleryRepo.renameFolder(id, name) } }
    fun deleteFolder(id: Long) { viewModelScope.launch { galleryRepo.deleteFolder(id) } }

    fun createFolder(name: String, colorIndex: Int, term: String? = null) {
        viewModelScope.launch { galleryRepo.createFolder(name, colorIndex, term) }
    }

    /** Reopen a saved note in Results, marking it recently viewed. */
    fun openNote(session: SessionEntity) {
        openCached(session)
        viewModelScope.launch { galleryRepo.touchViewed(session.sessionId) }
    }

    /** File the current solved note into the gallery, then invoke [onDone] on the main thread. */
    fun saveToGallery(
        folderId: Long,
        title: String?,
        difficulty: Difficulty,
        tags: List<String>,
        keepPhoto: Boolean,
        onDone: () -> Unit,
    ) {
        val id = _state.value.sessionId
        if (id.isBlank()) { onDone(); return }
        _state.update { it.copy(difficulty = difficulty) } // keep the Results stars in sync
        viewModelScope.launch {
            galleryRepo.saveToGallery(id, folderId, title?.ifBlank { null }, difficulty, tags, keepPhoto)
            onDone()
        }
    }

    fun setBackendUrl(url: String) { ApiClient.baseUrl = url }
    val backendUrl: String get() = ApiClient.baseUrl

    /** Debug toggles exposed on the Settings screen; persisted so they survive restarts. */
    var debugImages: Boolean
        get() = prefs.getBoolean("debug_images", false)
        set(v) { prefs.edit().putBoolean("debug_images", v).apply() }
    var useAdaptiveThreshold: Boolean
        get() = prefs.getBoolean("adaptive_threshold", false)
        set(v) { prefs.edit().putBoolean("adaptive_threshold", v).apply() }

    /**
     * Step 1–3 of the workflow: preprocess on device, upload, receive LaTeX.
     * [topicHint] is the user's topic choice from the capture chips ("unknown" =
     * Auto); when set, it overrides the recognizer's guess so Review and Solve use it.
     */
    fun captureAndRecognize(raw: File, crop: CropSpec? = null, topicHint: String = "unknown") {
        _state.update { it.copy(busy = true, status = "Preprocessing…", error = null, result = null) }
        val debug = debugImages
        val threshold = useAdaptiveThreshold
        viewModelScope.launch {
            try {
                val clean = withContext(Dispatchers.Default) {
                    Preprocessor.process(getApplication(), raw, crop, debug, threshold)
                }
                _state.update { it.copy(imageFile = clean, status = "Recognizing…") }
                val rec = repo.recognize(clean)
                _state.update {
                    it.copy(
                        busy = false, status = "",
                        sessionId = rec.sessionId, latex = rec.latex,
                        confidence = rec.confidence, lowConfidence = rec.lowConfidence,
                        topic = if (topicHint != "unknown") topicHint else rec.topicGuess,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "", error = describe(e)) }
            }
        }
    }

    /** A gallery photo awaiting the adjustable-crop screen (no framing guide for picked images). */
    var pendingGalleryFile: File? = null
        private set
    fun setPendingGallery(f: File) { pendingGalleryFile = f }

    /** Read-only: number of solution steps stored for a cached session (for History). */
    fun stepCount(session: SessionEntity): Int = repo.stepsOf(session).size

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
                difficulty = session.difficulty,
                result = SolveResponse(
                    sessionId = session.sessionId, topic = session.topic, steps = repo.stepsOf(session),
                    answerLatex = session.answerLatex, verified = session.verified ?: false, mismatchAt = session.mismatchAt,
                ),
            )
        }
    }

    /** Set the current note's difficulty (from the Results stars); persists it on the session. */
    fun setDifficulty(difficulty: Difficulty?) {
        val sessionId = _state.value.sessionId
        if (sessionId.isBlank()) return
        _state.update { it.copy(difficulty = difficulty) }
        viewModelScope.launch { repo.setDifficulty(sessionId, difficulty) }
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
