package com.mathnotes.solver.export

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.mathnotes.solver.ui.SolverUiState
import com.mathnotes.solver.ui.displayProblem
import ru.noties.jlatexmath.JLatexMathDrawable
import java.io.File

/**
 * Step 7: original (preprocessed) image + confirmed LaTeX + each step + answer → PDF,
 * rendered on-device with PdfDocument and JLatexMath, then handed to the share sheet.
 * Starter layout is single-column US Letter; pagination is naive (new page when full).
 */
object PdfExporter {
    private const val PAGE_W = 612   // 8.5in @ 72dpi
    private const val PAGE_H = 792
    private const val MARGIN = 40

    fun exportAndShare(context: Context, state: SolverUiState) {
        val file = export(context, state)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share solution PDF"))
    }

    fun export(context: Context, state: SolverUiState): File {
        val doc = PdfDocument()
        val title = Paint().apply { color = Color.BLACK; textSize = 16f; isFakeBoldText = true }
        val body = Paint().apply { color = Color.DKGRAY; textSize = 11f }
        var pageNo = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create())
        var y = MARGIN.toFloat()

        fun newPageIfNeeded(needed: Float) {
            if (y + needed > PAGE_H - MARGIN) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, ++pageNo).create())
                y = MARGIN.toFloat()
            }
        }
        fun text(s: String, p: Paint) { newPageIfNeeded(p.textSize + 6); page.canvas.drawText(s, MARGIN.toFloat(), y + p.textSize, p); y += p.textSize + 6 }
        fun latex(raw: String, size: Float) {
            // Same display form as the screens: a slash derivative (d/dx) is drawn as a fraction.
            val s = displayProblem(raw)
            val d = runCatching { JLatexMathDrawable.builder(s).textSize(size).padding(4).build() }.getOrNull()
            if (d == null) { text(s, body); return }
            val scale = minOf(1f, (PAGE_W - 2 * MARGIN).toFloat() / d.intrinsicWidth)
            val h = d.intrinsicHeight * scale
            newPageIfNeeded(h + 6)
            val c: Canvas = page.canvas
            c.save(); c.translate(MARGIN.toFloat(), y); c.scale(scale, scale)
            d.setBounds(0, 0, d.intrinsicWidth, d.intrinsicHeight); d.draw(c); c.restore()
            y += h + 6
        }

        // Verdict stamp, top right — the same three outcomes as the Results screen.
        state.result?.let { r ->
            val own = r.steps.filterNot { it.isSolution }
            val student = own.any { it.isStudent }
            val label = when {
                r.verified -> "VERIFIED"
                student && r.mismatchAt == null -> "ALMOST, finish it"
                else -> "CHECK STEP ${(r.mismatchAt ?: 0) + 1}"
            }
            val ink = when {
                r.verified -> Color.rgb(0x1F, 0x7A, 0x3A)
                student && r.mismatchAt == null -> Color.rgb(0xE0, 0xA0, 0x00)
                else -> Color.rgb(0xC0, 0x39, 0x2B)
            }
            val stampText = Paint().apply { color = ink; textSize = 13f; isFakeBoldText = true; isAntiAlias = true }
            val stampBox = Paint().apply { color = ink; style = Paint.Style.STROKE; strokeWidth = 2f; isAntiAlias = true }
            val w = stampText.measureText(label)
            val right = (PAGE_W - MARGIN).toFloat()
            page.canvas.drawRoundRect(right - w - 16f, y - 2f, right, y + 22f, 5f, 5f, stampBox)
            page.canvas.drawText(label, right - w - 8f, y + 15f, stampText)
        }
        text("Handwritten Math Notes — solution", title)
        text("Session ${state.sessionId} · topic: ${state.topic}", body)
        y += 8

        state.imageFile?.takeIf { it.exists() }?.let { f ->
            val bmp = BitmapFactory.decodeFile(f.absolutePath)
            val scale = minOf((PAGE_W - 2 * MARGIN).toFloat() / bmp.width, 220f / bmp.height)
            val w = (bmp.width * scale).toInt(); val h = (bmp.height * scale).toInt()
            newPageIfNeeded(h + 10f)
            page.canvas.drawBitmap(bmp, null, Rect(MARGIN, y.toInt(), MARGIN + w, y.toInt() + h), null)
            y += h + 10
        }

        text("Recognized expression", title)
        latex(state.latex, 22f)
        y += 8
        val result = state.result
        val own = result?.steps.orEmpty().filterNot { it.isSolution }
        val ours = result?.steps.orEmpty().filter { it.isSolution }
        text(if (own.any { it.isStudent }) "Your steps" else "Steps", title)
        own.forEachIndexed { i, s ->
            val status = when {
                !s.isStudent -> if (result?.mismatchAt == i) "  (verification mismatch)" else ""
                s.status == "ok" -> "  [correct]"
                s.status == "unparseable" -> "  [could not be read]"
                else -> "  [wrong]"
            }
            text("${i + 1}. ${s.rule}$status", body)
            latex(s.afterLatex, 18f)
            if (s.isStudent && s.note.isNotBlank()) text("   ${s.note.replace("$", "")}", body)
        }
        if (ours.isNotEmpty()) {
            y += 8
            text("Full solution", title)
            ours.forEachIndexed { i, s -> text("${i + 1}. ${s.rule}", body); latex(s.afterLatex, 18f) }
        }
        y += 8
        if (own.any { it.isStudent }) {
            // A student's note: never present an unverified last line as "the answer".
            when {
                result?.verified == true -> { text("Answer (verified)", title); latex(result.answerLatex, 24f) }
                result?.mismatchAt == null -> { text("Your last line (almost, not finished)", title); latex(result?.answerLatex ?: "", 24f) }
                else -> { text("Your last line (not verified: check step ${result.mismatchAt + 1})", title); latex(result.answerLatex, 24f) }
            }
            if (result?.verified != true && ours.isNotEmpty()) {
                y += 8
                text("Correct answer", title)
                latex(state.solutionAnswer.ifBlank { ours.last().afterLatex }, 24f)
            }
        } else {
            text("Answer" + if (result?.verified == true) " (verified)" else "", title)
            latex(result?.answerLatex ?: "", 24f)
        }

        doc.finishPage(page)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "solution_${state.sessionId.take(8)}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
