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
        fun latex(s: String, size: Float) {
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
        text("Steps", title)
        val result = state.result
        result?.steps?.forEachIndexed { i, s ->
            text("${i + 1}. ${s.rule}${if (result.mismatchAt == i) "  (verification mismatch)" else ""}", body)
            latex(s.afterLatex, 18f)
        }
        y += 8
        text("Answer" + if (result?.verified == true) " (verified)" else "", title)
        latex(result?.answerLatex ?: "", 24f)

        doc.finishPage(page)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val out = File(dir, "solution_${state.sessionId.take(8)}.pdf")
        out.outputStream().use { doc.writeTo(it) }
        doc.close()
        return out
    }
}
