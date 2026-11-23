package com.mathnotes.solver.ui.setup

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mathnotes.solver.data.WelcomePrefs
import com.mathnotes.solver.data.db.HandwritingSample
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

    /** Up to three "x is sometimes read as ×" lines for the done screen, from the mismatches. */
    fun correctionLines(): List<String> = SetupMatching.correctionLines(
        _state.value.lines.filterNot { it.isMatch }.map { it.expectedLatex to it.effectiveLatex },
    )

    /**
     * Preprocess the captured page, recognize it as one multi-line image, split into five
     * lines and slice the page into five strips for thumbnails. Fewer than three lines →
     * [SetupEvent.Error] (the caller stays on capture); success → [SetupEvent.Recognized].
     */
    fun capturePage(raw: File, crop: CropSpec) {
        _state.update { it.copy(busy = true, status = "Reading your page…") }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.Default) { Preprocessor.process(getApplication(), raw, crop) }
                val resp = solver.recognize(page, persist = false)
                val rawLines = SetupMatching.splitLines(resp.latex)
                if (rawLines.size < 3) {
                    _state.update { it.copy(busy = false, status = "") }
                    _events.emit(SetupEvent.Error("I could only find ${rawLines.size} lines, try again with the page flat"))
                    return@launch
                }
                val n = HandwritingRepository.EXPECTED.size
                val recognized = (0 until n).map { rawLines.getOrElse(it) { "" } }   // pad/truncate to five
                val crops = withContext(Dispatchers.Default) { sliceStrips(page, n) }
                _state.update { s ->
                    s.copy(
                        busy = false, status = "", pageImagePath = page.absolutePath,
                        lines = s.lines.mapIndexed { i, line ->
                            line.copy(
                                recognizedLatex = recognized[i],
                                correctedLatex = null,
                                cropPath = crops.getOrNull(i),
                                confidence = resp.confidence.toFloat(),
                            )
                        },
                    )
                }
                _events.emit(SetupEvent.Recognized)
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, status = "") }
                _events.emit(SetupEvent.Error(e.message ?: "Couldn't read the page, try again"))
            }
        }
    }

    /** The user confirms what they actually wrote on a mismatched row. */
    fun setCorrection(index: Int, latex: String) = _state.update { s ->
        s.copy(lines = s.lines.mapIndexed { i, line -> if (i == index) line.copy(correctedLatex = latex) else line })
    }

    /** Persist the five samples to Room (called from the done screen's save action). */
    fun saveSamples() {
        val lines = _state.value.lines
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
    fun reset() = _state.update { SetupUiState() }

    /** Onboarding flag: set when the wizard finishes or is skipped; cleared to re-run it. */
    fun markSetupComplete() = viewModelScope.launch { WelcomePrefs.setSetupComplete(getApplication(), true) }

    /** Slice the page bitmap into [n] equal horizontal strips, saved as JPEGs for the thumbnails. */
    private fun sliceStrips(page: File, n: Int): List<String> {
        val bmp = BitmapFactory.decodeFile(page.absolutePath) ?: return emptyList()
        val dir = File(getApplication<Application>().filesDir, "handwriting").apply { mkdirs() }
        val stripH = bmp.height / n
        val stamp = page.nameWithoutExtension
        val paths = (0 until n).map { i ->
            val top = i * stripH
            val h = if (i == n - 1) bmp.height - top else stripH
            val strip = Bitmap.createBitmap(bmp, 0, top, bmp.width, h)
            val f = File(dir, "strip_${stamp}_$i.jpg")
            FileOutputStream(f).use { strip.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            strip.recycle()
            f.absolutePath
        }
        bmp.recycle()
        return paths
    }
}
