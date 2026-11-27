package com.mathnotes.solver.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.BackendLocator
import com.mathnotes.solver.data.api.BackendState
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.api.Step
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.data.db.HandwritingSample
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.data.handwriting.CorrectionMap
import com.mathnotes.solver.data.handwriting.CorrectionRule
import com.mathnotes.solver.data.handwriting.CorrectionSuggestion
import com.mathnotes.solver.data.handwriting.RuleMode
import com.mathnotes.solver.data.repository.GalleryRepository
import com.mathnotes.solver.data.repository.HandwritingRepository
import com.mathnotes.solver.data.repository.SolverRepository
import com.mathnotes.solver.image.CropSpec
import com.mathnotes.solver.image.Preprocessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import timber.log.Timber
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
    /** The student's own working, one LaTeX step per row (prefilled from the Identify split). */
    val userSteps: List<String> = emptyList(),
    /** The capture was split into problem + steps at an `=`; Review explains and offers an undo. */
    val splitAtEquals: Boolean = false,
    /** `=` signs but no operator found: the problem field may hold the student's work too. */
    val splitUncertain: Boolean = false,
    val isVerifyMode: Boolean = false,
    /** SUGGEST-mode corrections the Review screen offers as a one-tap swap. */
    val suggestions: List<CorrectionSuggestion> = emptyList(),
    /** The raw Mathpix LaTeX before the correction map ran — shown on long-press in Review. */
    val rawLatex: String = "",
)

private const val BACKEND_URL_PREF = "backend_url"

/** A photo taken while the backend was unreachable, retried automatically once connected. */
private data class PendingCapture(val raw: File, val crop: CropSpec?, val topicHint: String)

/** One-shot signals the capture flow sends to the UI. */
sealed interface CaptureEvent {
    data object NavigateToReview : CaptureEvent
    data object OpenBackendSheet : CaptureEvent
}

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
    private val handwritingRepo = HandwritingRepository(app)
    private val prefs = app.getSharedPreferences("solver_settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(SolverUiState())
    val state: StateFlow<SolverUiState> = _state

    // --- Backend connection ---
    private val locator = BackendLocator.get(app)
    val backend: StateFlow<BackendState> = locator.state

    private val _captureEvents = MutableSharedFlow<CaptureEvent>(extraBufferCapacity = 4)
    val captureEvents = _captureEvents.asSharedFlow()
    private var pending: PendingCapture? = null

    init {
        // When the backend comes up and a photo is waiting, recognize it automatically.
        viewModelScope.launch {
            backend.collect { if (it is BackendState.Connected) pending?.let { _ -> retryPendingCapture() } }
        }
        // On upgrade, drop handwriting samples built from the old (broken) page read and re-prompt setup.
        viewModelScope.launch {
            if (WelcomePrefs.consumeRuleResetNeeded(getApplication())) {
                handwritingRepo.clear()
                WelcomePrefs.setSetupComplete(getApplication(), false)
                Timber.i("cleared handwriting samples on upgrade (built from the pre-line_data page read)")
            }
        }
    }

    /** Settings kill switch for the per-user correction map. */
    var useCorrections: Boolean
        get() = repo.useCorrections
        set(v) { repo.useCorrections = v }

    /** Settings "Rebuild rules": recompute from the samples and report a short summary. */
    fun rebuildCorrectionRules(onResult: (String) -> Unit) = viewModelScope.launch {
        onResult(repo.rebuildCorrectionRules())
    }

    /** Re-run discovery (the sheet's "Search again"). */
    fun searchBackend() = locator.locate()

    /** Current base URL typed in Settings (the override), for the manual field. */
    val savedBackendUrl: String get() = prefs.getString(BACKEND_URL_PREF, null).orEmpty()

    val history: StateFlow<List<SessionEntity>> =
        repo.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Handwriting samples from the setup wizard — surfaced on the Settings screen. */
    val handwritingSamples: StateFlow<List<HandwritingSample>> =
        handwritingRepo.samples.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun clearHandwriting() = viewModelScope.launch { handwritingRepo.clear() }

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

    /** Manual override (Settings field or a scanned QR): validate, save as the "saved" strategy, re-locate. */
    fun setBackendUrl(url: String): Boolean {
        val normalized = ApiClient.parseBaseUrl(url)?.trimEnd('/') ?: return false
        prefs.edit().putString(BACKEND_URL_PREF, normalized).apply()
        locator.locate()
        return true
    }

    /** One-time welcome cover: mark it seen after "Get started", or clear it to re-demo from Settings. */
    fun markWelcomeSeen() = viewModelScope.launch { WelcomePrefs.setSeen(getApplication(), true) }
    fun resetWelcome() = viewModelScope.launch { WelcomePrefs.setSeen(getApplication(), false) }

    /** Debug toggles exposed on the Settings screen; persisted so they survive restarts. */
    var debugImages: Boolean
        get() = prefs.getBoolean("debug_images", false)
        set(v) { prefs.edit().putBoolean("debug_images", v).apply() }
    var useAdaptiveThreshold: Boolean
        get() = prefs.getBoolean("adaptive_threshold", false)
        set(v) { prefs.edit().putBoolean("adaptive_threshold", v).apply() }

    /**
     * A photo was taken. If the backend is up, recognize and go to Review. Otherwise keep the
     * photo, open the discovery sheet, and recognize automatically once a backend connects.
     */
    fun submitCapture(raw: File, crop: CropSpec? = null, topicHint: String = "unknown") {
        if (backend.value is BackendState.Connected) {
            captureAndRecognize(raw, crop, topicHint)
            _captureEvents.tryEmit(CaptureEvent.NavigateToReview)
        } else {
            pending = PendingCapture(raw, crop, topicHint)
            _captureEvents.tryEmit(CaptureEvent.OpenBackendSheet)
            searchBackend()
        }
    }

    private fun retryPendingCapture() {
        val p = pending ?: return
        pending = null
        captureAndRecognize(p.raw, p.crop, p.topicHint)
        _captureEvents.tryEmit(CaptureEvent.NavigateToReview)
    }

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
                val recResult = repo.recognize(clean)
                val rec = recResult.response
                // The backend's Identify split: the problem, then the student's own steps in order.
                val originalProblem = rec.problemLatex.ifBlank { rec.latex.trim() }
                val finalSteps = rec.steps
                val topicToUse = if (topicHint != "unknown") topicHint else rec.topicGuess
                val suggestions = repo.suggestionsFor(originalProblem)

                _state.update {
                    it.copy(
                        busy = false, status = "",
                        sessionId = rec.sessionId,
                        latex = originalProblem,
                        confidence = rec.confidence,
                        lowConfidence = rec.lowConfidence,
                        topic = topicToUse,
                        userSteps = finalSteps,
                        splitAtEquals = finalSteps.isNotEmpty(),
                        splitUncertain = rec.splitUncertain,
                        suggestions = suggestions,
                        rawLatex = recResult.rawLatex,
                    )
                }
            } catch (e: Exception) {
                if (e is IOException) {
                    // Connectivity problem → re-locate and open the discovery sheet, not a dead-end error.
                    _state.update { it.copy(busy = false, status = "", error = null) }
                    searchBackend()
                    _captureEvents.tryEmit(CaptureEvent.OpenBackendSheet)
                } else {
                    _state.update { it.copy(busy = false, status = "", error = describe(e)) }
                }
            }
        }
    }

    fun addUserStep() = _state.update { it.copy(userSteps = it.userSteps + "") }
    fun updateUserStep(index: Int, text: String) = _state.update {
        it.copy(userSteps = it.userSteps.toMutableList().apply { if (index in indices) set(index, text) })
    }
    fun removeUserStep(index: Int) = _state.update {
        it.copy(userSteps = it.userSteps.toMutableList().apply { if (index in indices) removeAt(index) })
    }

    /**
     * The split put too much into the steps: fold rows 0..[index] back into the problem
     * (`problem = step0 = …`), keeping the rest as steps.
     */
    fun mergeStepIntoProblem(index: Int) = _state.update { st ->
        if (index !in st.userSteps.indices) return@update st
        val moved = st.userSteps.take(index + 1).filter { it.isNotBlank() }
        val rest = st.userSteps.drop(index + 1)
        st.copy(
            latex = (listOf(st.latex) + moved).joinToString(" = "),
            userSteps = rest,
            splitAtEquals = rest.isNotEmpty(),
        )
    }

    /** "Check my steps": the problem plus the user's (edited) chain go to /verify. No steps → solve. */
    fun verify() {
        val s = _state.value
        val chain = s.userSteps.map { it.trim().removePrefix("=").trim() }.filter { it.isNotEmpty() }
        if (chain.isEmpty()) { solve(); return }
        _state.update { it.copy(busy = true, status = "Verifying steps…", error = null) }
        viewModelScope.launch {
            try {
                val stepObjects = chain.mapIndexed { index, expr ->
                    Step(
                        index = index,
                        rule = "user step ${index + 1}",
                        beforeLatex = if (index == 0) s.latex else chain[index - 1],
                        afterLatex = expr,
                        note = "",
                    )
                }
                Timber.i("verify: problem=%s | steps=%s", s.latex, chain)
                val verifyResp = repo.verify(s.sessionId, s.latex, "unknown", stepObjects)
                repo.saveVerification(s.sessionId, s.latex, s.topic, stepObjects, verifyResp.verified, verifyResp.mismatchAt)

                val solveResp = SolveResponse(
                    sessionId = s.sessionId,
                    topic = s.topic,
                    steps = stepObjects,
                    answerLatex = stepObjects.lastOrNull()?.afterLatex ?: s.latex,
                    verified = verifyResp.verified,
                    mismatchAt = verifyResp.mismatchAt,
                    details = verifyResp.details,
                )

                _state.update { it.copy(busy = false, status = "", result = solveResp) }
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

    /** One-tap accept of a Review suggestion: rewrite the token and drop the suggestion. */
    fun applySuggestion(s: CorrectionSuggestion) = _state.update { st ->
        val map = CorrectionMap(listOf(CorrectionRule(s.from, s.to, s.context, 1, RuleMode.APPLY)))
        st.copy(latex = map.applyTo(st.latex).latex, suggestions = st.suggestions - s)
    }

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
        is IOException -> "Can't reach the backend. Tap the status chip to find or set it."
        else -> e.message ?: e.toString()
    }
}
