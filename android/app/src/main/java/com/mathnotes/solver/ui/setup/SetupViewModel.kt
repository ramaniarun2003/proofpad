package com.mathnotes.solver.ui.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import com.mathnotes.solver.ui.NOTHING_TO_READ_NOTE
import com.mathnotes.solver.ui.failureNote
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.data.api.PageRecognizeResponse
import com.mathnotes.solver.data.db.HandwritingSample
import com.mathnotes.solver.data.handwriting.CorrectionMap
import com.mathnotes.solver.data.repository.HandwritingRepository
import com.mathnotes.solver.data.repository.SolverRepository
import com.mathnotes.solver.image.CropSpec
import com.mathnotes.solver.image.Preprocessor
import com.mathnotes.solver.setup.SetupMatching
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/** One row of the wizard: the expected line, what was read, an optional correction, and its strip. */
data class SetupLine(
    val expectedLatex: String,
    val hint: String,
    val recognizedLatex: String = "",
    val correctedLatex: String? = null,
    val cropPath: String? = null,
    val confidence: Float = 0f,
    /** True when the page read didn't produce a line here (3–4 lines came back): retake just this one. */
    val notFound: Boolean = false,
) {
    /** What the user says they actually wrote, if they corrected it; otherwise the recognizer's read. */
    val effectiveLatex: String get() = correctedLatex ?: recognizedLatex
    val isMatch: Boolean get() = recognizedLatex.isNotBlank() && SetupMatching.matches(expectedLatex, effectiveLatex)
}

data class SetupUiState(
    val busy: Boolean = false,
    val status: String = "",
    val pageImagePath: String? = null,
    val lines: List<SetupLine> = HandwritingRepository.EXPECTED.map { SetupLine(it.latex, it.hint) },
)

/** One-shot signals the page-capture flow sends back to the UI. */
sealed interface SetupEvent {
    object Recognized : SetupEvent
    data class Error(val message: String) : SetupEvent
}

/**
 * Drives the handwriting setup wizard across its screens: holds the five expected entries,
 * the recognized strings, corrections and crop paths. Screens read [state] and call these
 * methods; they never touch the repositories directly.
 */
class SetupViewModel(app: Application) : AndroidViewModel(app) {
    private val solver = SolverRepository(app)
    private val handwriting = HandwritingRepository(app)

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state

    private val _events = MutableSharedFlow<SetupEvent>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    /** Number of lines the recognizer (as corrected) read the way we asked for. */
    val correctCount: Int get() = _state.value.lines.count { it.isMatch }

    /**
     * Plain-word descriptions of the rules the correction map derives from the samples (max 3),
     * for the done screen. Empty when the recognizer read every line correctly.
     */
    fun derivedRuleLines(): List<String> = CorrectionMap.fromSamples(
        _state.value.lines.map {
            HandwritingSample(
                expectedLatex = it.expectedLatex,
                recognizedLatex = it.recognizedLatex,
                correctedLatex = it.correctedLatex,
            )
        },
    ).phrasings(3)

    private val debug: Boolean get() =
        getApplication<Application>().getSharedPreferences("solver_settings", android.content.Context.MODE_PRIVATE)
            .getBoolean("debug_images", false)

    /**
     * Preprocess the whole page (no single-problem crop) and recognize it with Mathpix line_data
     * (mode=page): each line comes back separately with a pixel bbox, which we crop for the thumbnail.
     * Fewer than three lines → [SetupEvent.Error] (stay on capture). 3–4 → continue; the missing rows
     * are marked "not found" for a single-line retake. Success → [SetupEvent.Recognized].
     */
    fun capturePage(raw: File) {
        _state.update { it.copy(busy = true, status = "Reading your page…") }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.Default) { Preprocessor.processPage(getApplication(), raw, debug) }
                val resp = solver.recognizePage(page)
                Timber.i("setup page recognize: %d lines, image %dx%d", resp.lines.size, resp.imageW, resp.imageH)
                if (resp.lines.size < 3) {
                    _state.update { it.copy(busy = false, status = "") }
                    // The app does no line splitting of its own: this count is the backend's.
                    Timber.w(
                        "setup page: showing \"could only find %d lines\" — backend returned lines=%d (raw_text=%s)",
                        resp.lines.size, resp.lines.size, resp.rawText.take(120),
                    )
                    _events.emit(SetupEvent.Error("I could only find ${resp.lines.size} lines, try again with the page flat"))
                    return@launch
                }
                val crops = withContext(Dispatchers.Default) { cropBboxThumbnails(page, resp) }
                // Pair each expected line with the line the page read actually has for it, so the row
                // marked "not found" is the one that is really missing (not always the last), and a
                // second set of lines on the page does not shift everything.
                val slots = SetupMatching.align(_state.value.lines.map { it.expectedLatex }, resp.lines.map { it.latex })
                Timber.i("setup page: %d lines from the backend → slots %s (null = retake that line)", resp.lines.size, slots)
                _state.update { s ->
                    s.copy(
                        busy = false, status = "", pageImagePath = page.absolutePath,
                        lines = s.lines.mapIndexed { i, line ->
                            val at = slots.getOrNull(i)
                            val read = at?.let { resp.lines.getOrNull(it) }
                            if (read != null) line.copy(
                                recognizedLatex = read.latex, correctedLatex = null,
                                cropPath = crops.getOrNull(at), confidence = read.confidence.toFloat(), notFound = false,
                            ) else line.copy(
                                recognizedLatex = "", correctedLatex = null, cropPath = null, confidence = 0f, notFound = true,
                            )
                        },
                    )
                }
                _events.emit(SetupEvent.Recognized)
            } catch (e: CancellationException) {
                throw e   // cancelled on purpose: not an error to show
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "") }
                Timber.w(e, "setup page recognition failed")
                _events.emit(SetupEvent.Error(if (e is retrofit2.HttpException && e.code() == 422) NOTHING_TO_READ_NOTE else failureNote(e)))
            }
        }
    }

    /**
     * Test seam: stands in for a page read that found all five lines as written, so a UI test can
     * finish the wizard without a camera or a backend.
     */
    @VisibleForTesting
    internal fun deliverPageReadForTest() {
        _state.update { s ->
            s.copy(busy = false, status = "", lines = s.lines.map { it.copy(recognizedLatex = it.expectedLatex, confidence = 1f, notFound = false) })
        }
        _events.tryEmit(SetupEvent.Recognized)
    }

    private var retakeIndex: Int = -1

    /** Enter single-line retake for a "not found" row; the next formula capture fills it. */
    fun beginRetake(index: Int) { retakeIndex = index }

    /** Fill the retake row from a single-line formula-mode capture. */
    fun fillRetakeLine(raw: File, crop: CropSpec) {
        val index = retakeIndex
        if (index !in _state.value.lines.indices) return
        _state.update { it.copy(busy = true, status = "Reading that line…") }
        requestJob?.cancel()
        requestJob = viewModelScope.launch {
            try {
                val clean = withContext(Dispatchers.Default) { Preprocessor.process(getApplication(), raw, crop, debug) }
                val result = solver.recognize(clean, persist = false)
                val thumb = withContext(Dispatchers.Default) { copyThumb(clean, index) }
                _state.update { s ->
                    s.copy(busy = false, status = "", lines = s.lines.mapIndexed { i, line ->
                        if (i == index) line.copy(
                            recognizedLatex = result.response.latex, correctedLatex = null,
                            cropPath = thumb, confidence = result.response.confidence.toFloat(), notFound = false,
                        ) else line
                    })
                }
                retakeIndex = -1
                _events.emit(SetupEvent.Recognized)
            } catch (e: CancellationException) {
                throw e   // cancelled on purpose: not an error to show
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "") }
                Timber.w(e, "setup line retake failed")
                _events.emit(SetupEvent.Error(if (e is retrofit2.HttpException && e.code() == 422) NOTHING_TO_READ_NOTE else failureNote(e)))
            }
        }
    }

    /** The user confirms what they actually wrote on a mismatched row. */
    fun setCorrection(index: Int, latex: String) = _state.update { s ->
        s.copy(lines = s.lines.mapIndexed { i, line -> if (i == index) line.copy(correctedLatex = latex, notFound = false) else line })
    }

    /** Persist the captured samples to Room (skipping any line the page read never found). */
    fun saveSamples() {
        val lines = _state.value.lines.filter { it.recognizedLatex.isNotBlank() }
        viewModelScope.launch {
            handwriting.saveSamples(
                lines.map {
                    HandwritingSample(
                        expectedLatex = it.expectedLatex,
                        recognizedLatex = it.recognizedLatex,
                        correctedLatex = it.correctedLatex,
                        cropPath = it.cropPath,
                        confidence = it.confidence,
                    )
                },
            )
        }
    }

    /** Clear wizard state so a Settings "Redo setup" starts fresh. */
    private var requestJob: Job? = null

    /** Stop the page or line read in flight; its answer, if it still arrives, is dropped. */
    fun cancelRequest() {
        requestJob?.cancel()
        requestJob = null
        _state.update { it.copy(busy = false, status = "") }
    }

    fun reset() {
        cancelRequest()
        _state.update { SetupUiState() }
    }


    /** Crop each line's thumbnail straight from its bbox (with 8% padding), clamped to the image. */
    private fun cropBboxThumbnails(page: File, resp: PageRecognizeResponse): List<String?> {
        val bmp = BitmapFactory.decodeFile(page.absolutePath) ?: return emptyList()
        val dir = File(getApplication<Application>().filesDir, "handwriting").apply { mkdirs() }
        val stamp = page.nameWithoutExtension
        val paths = resp.lines.mapIndexed { i, line ->
            val b = line.bbox
            if (b.size < 4) return@mapIndexed null
            val padX = ((b[2] - b[0]) * 0.08f).toInt()
            val padY = ((b[3] - b[1]) * 0.08f).toInt()
            val left = (b[0] - padX).coerceIn(0, bmp.width - 1)
            val top = (b[1] - padY).coerceIn(0, bmp.height - 1)
            val right = (b[2] + padX).coerceIn(left + 1, bmp.width)
            val bottom = (b[3] + padY).coerceIn(top + 1, bmp.height)
            val crop = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
            val f = File(dir, "line_${stamp}_$i.jpg")
            FileOutputStream(f).use { crop.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            crop.recycle()
            f.absolutePath
        }
        bmp.recycle()
        return paths
    }

    /** Copy a preprocessed single-line image as a retake thumbnail. */
    private fun copyThumb(src: File, index: Int): String? {
        val bmp = BitmapFactory.decodeFile(src.absolutePath) ?: return null
        val dir = File(getApplication<Application>().filesDir, "handwriting").apply { mkdirs() }
        val f = File(dir, "retake_${src.nameWithoutExtension}_$index.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        return f.absolutePath
    }
}
