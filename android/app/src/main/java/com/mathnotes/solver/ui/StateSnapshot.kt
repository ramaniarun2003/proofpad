package com.mathnotes.solver.ui

import com.mathnotes.solver.data.api.ApiClient
import com.mathnotes.solver.data.api.ProblemSplit
import com.mathnotes.solver.data.api.SolveResponse
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.image.CropSpec
import java.io.File

/**
 * What survives the process being killed in the background (Android does this routinely to a
 * backgrounded app): the capture in progress on Review, or the result on Results. The view model
 * keeps a JSON copy of this in its SavedStateHandle and rebuilds its state from it on restart.
 *
 * Deliberately left out: anything in flight (busy, a PDF being written) and the correction
 * suggestions, which are recomputed.
 */
data class StateSnapshot(
    val imagePath: String? = null,
    val sessionId: String = "",
    val latex: String = "",
    val rawLatex: String = "",
    val confidence: Double = 0.0,
    val lowConfidence: Boolean = false,
    val topic: String = "unknown",
    val topicHint: String = "unknown",
    val userSteps: List<String> = emptyList(),
    val problems: List<ProblemSplit> = emptyList(),
    val problemIndex: Int = 0,
    val splitAt: String = "",
    val splitUncertain: Boolean = false,
    val parseError: String? = null,
    val recognizeError: String? = null,
    val error: String? = null,
    val result: SolveResponse? = null,
    val solutionAnswer: String = "",
    val difficulty: String? = null,
    val savedTo: String? = null,
    /** A request was in flight when the snapshot was taken; its answer is lost with the process. */
    val wasBusy: Boolean = false,
    val lastCapture: CaptureSnapshot? = null,
    /** A picked photo waiting on the crop screen. */
    val pendingGallery: String? = null,
    /** The folder open on the folder screen (-1 = none) and the Gallery search text. */
    val folderId: Long = -1,
    val galleryQuery: String = "",
)

/** The photo behind the current capture and how it was framed, so Retry can send it again. */
data class CaptureSnapshot(
    val raw: String,
    val topicHint: String = "unknown",
    val guide: CropSpec.GuideBox? = null,
    val rect: CropSpec.NormalizedRect? = null,
) {
    val crop: CropSpec? get() = guide ?: rect

    companion object {
        fun of(raw: File, crop: CropSpec?, topicHint: String) = CaptureSnapshot(
            raw = raw.absolutePath, topicHint = topicHint,
            guide = crop as? CropSpec.GuideBox, rect = crop as? CropSpec.NormalizedRect,
        )
    }
}

const val INTERRUPTED_NOTE = "I was interrupted while reading this. Tap Retry and I'll read it again. Your photo is kept."

private val snapshotAdapter by lazy { ApiClient.moshi.adapter(StateSnapshot::class.java) }

fun StateSnapshot.toJson(): String = snapshotAdapter.toJson(this)

/** Null for anything that is not a snapshot this build can read (an older build's, for example). */
fun snapshotFromJson(json: String?): StateSnapshot? =
    json?.let { runCatching { snapshotAdapter.fromJson(it) }.getOrNull() }

fun SolverUiState.toSnapshot(
    lastCapture: CaptureSnapshot?,
    pendingGallery: File?,
    folderId: Long,
    galleryQuery: String,
) = StateSnapshot(
    imagePath = imageFile?.absolutePath,
    sessionId = sessionId, latex = latex, rawLatex = rawLatex,
    confidence = confidence, lowConfidence = lowConfidence,
    topic = topic, topicHint = topicHint,
    userSteps = userSteps, problems = problems, problemIndex = problemIndex,
    splitAt = splitAt, splitUncertain = splitUncertain,
    parseError = parseError, recognizeError = recognizeError, error = error,
    result = result, solutionAnswer = solutionAnswer,
    difficulty = difficulty?.name, savedTo = savedTo,
    wasBusy = busy || solutionBusy,
    lastCapture = lastCapture, pendingGallery = pendingGallery?.absolutePath,
    folderId = folderId, galleryQuery = galleryQuery,
)

/**
 * The UI state to come back to. Nothing is busy any more. A recognition that was cut off (a photo,
 * no reading, no error) becomes the Review error note with Retry, which resends the kept photo.
 */
fun StateSnapshot.toUiState(): SolverUiState {
    val cutOffWhileReading = wasBusy && result == null && latex.isBlank() && recognizeError == null && lastCapture != null
    return SolverUiState(
        imageFile = imagePath?.let(::File),
        sessionId = sessionId, latex = latex, rawLatex = rawLatex,
        confidence = confidence, lowConfidence = lowConfidence,
        topic = topic, topicHint = topicHint,
        userSteps = userSteps, problems = problems, problemIndex = problemIndex,
        splitAt = splitAt, splitUncertain = splitUncertain,
        parseError = parseError,
        recognizeError = if (cutOffWhileReading) INTERRUPTED_NOTE else recognizeError,
        error = error,
        result = result, solutionAnswer = solutionAnswer,
        difficulty = difficulty?.let { name -> Difficulty.entries.firstOrNull { it.name == name } },
        savedTo = savedTo,
    )
}

/** Is there anything on screen worth restoring (so Review / Results need not fall back to Capture)? */
val SolverUiState.hasCapture: Boolean
    get() = result != null || latex.isNotBlank() || recognizeError != null || busy || imageFile != null
