package com.mathnotes.solver.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import com.mathnotes.solver.image.CropSpec

/**
 * Minimal adjustable crop for gallery photos: picked images have no framing guide,
 * so the user drags a rectangle over the image. Corners (top-left / bottom-right)
 * are draggable; dragging inside moves the whole box. The result is a normalized
 * rectangle in image fractions, applied by the same [CropSpec] path as the camera.
 *
 * Pure Compose, no new libraries.
 */
@Composable
fun GalleryCropScreen(vm: SolverViewModel, onCropped: () -> Unit, onCancel: () -> Unit) {
    val file = vm.pendingGalleryFile
    if (file == null) { onCancel(); return }

    val bitmap = remember(file.path) { decodeUprightSampled(file.path, 1600) }
    if (bitmap == null) { onCancel(); return }

    // Crop rectangle in image fractions (0..1).
    var left by remember { mutableStateOf(0.1f) }
    var top by remember { mutableStateOf(0.3f) }
    var right by remember { mutableStateOf(0.9f) }
    var bottom by remember { mutableStateOf(0.7f) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Crop to the expression", style = MaterialTheme.typography.headlineSmall)
        Text("Drag the corners to frame one expression.", style = MaterialTheme.typography.bodyMedium)

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val density = LocalDensity.current
            val boxW = with(density) { maxWidth.toPx() }
            val boxH = with(density) { maxHeight.toPx() }
            // Fit the image (letterbox) inside the box; this is the displayed rect.
            val scale = minOf(boxW / bitmap.width, boxH / bitmap.height)
            val dispW = bitmap.width * scale
            val dispH = bitmap.height * scale
            val offX = (boxW - dispW) / 2f
            val offY = (boxH - dispH) / 2f

            fun toPx(fx: Float, fy: Float) = Offset(offX + fx * dispW, offY + fy * dispH)
            fun toFracX(px: Float) = ((px - offX) / dispW).coerceIn(0f, 1f)
            fun toFracY(py: Float) = ((py - offY) / dispH).coerceIn(0f, 1f)

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Picked photo",
                modifier = Modifier.fillMaxSize(),
            )

            val handleR = with(density) { 14.dp.toPx() }
            Box(
                Modifier.fillMaxSize().pointerInput(bitmap) {
                    // 0 = move, 1 = top-left corner, 2 = bottom-right corner.
                    var mode = 0
                    detectDragGestures(
                        onDragStart = { pos ->
                            val tl = toPx(left, top)
                            val br = toPx(right, bottom)
                            mode = when {
                                (pos - tl).getDistance() < handleR * 2 -> 1
                                (pos - br).getDistance() < handleR * 2 -> 2
                                else -> 0
                            }
                        },
                        onDrag = { change, drag ->
                            change.consume()
                            val dfx = drag.x / dispW
                            val dfy = drag.y / dispH
                            when (mode) {
                                1 -> { left = (left + dfx).coerceIn(0f, right - 0.05f); top = (top + dfy).coerceIn(0f, bottom - 0.05f) }
                                2 -> { right = (right + dfx).coerceIn(left + 0.05f, 1f); bottom = (bottom + dfy).coerceIn(top + 0.05f, 1f) }
                                else -> {
                                    val w = right - left; val h = bottom - top
                                    left = (left + dfx).coerceIn(0f, 1f - w); right = left + w
                                    top = (top + dfy).coerceIn(0f, 1f - h); bottom = top + h
                                }
                            }
                        },
                    )
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val tl = toPx(left, top)
                    val br = toPx(right, bottom)
                    drawRect(
                        color = Color(0xFF2A78D6),
                        topLeft = tl,
                        size = Size(br.x - tl.x, br.y - tl.y),
                        style = Stroke(width = 5f),
                    )
                    listOf(tl, br).forEach { drawCircle(Color(0xFF2A78D6), radius = handleR, center = it) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick = {
                    vm.captureAndRecognize(
                        file,
                        CropSpec.NormalizedRect(left, top, right, bottom, marginFrac = 0.05f),
                    )
                    onCropped()
                },
                modifier = Modifier.weight(1f),
            ) { Text("Use crop") }
        }
    }
}

/** Decode a downsampled, EXIF-upright bitmap for display (fractions are resolution-independent). */
private fun decodeUprightSampled(path: String, reqLongSide: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    if (bounds.outWidth <= 0) return null
    var sample = 1
    val longest = maxOf(bounds.outWidth, bounds.outHeight)
    while (longest / sample > reqLongSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    val bmp = BitmapFactory.decodeFile(path, opts) ?: return null
    val orientation = ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }
    if (degrees == 0f) return bmp
    val m = Matrix().apply { postRotate(degrees) }
    return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true).also { if (it !== bmp) bmp.recycle() }
}
