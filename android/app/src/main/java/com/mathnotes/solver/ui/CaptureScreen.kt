package com.mathnotes.solver.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mathnotes.solver.image.CropSpec
import java.io.File

/** Framing guide geometry, shared by the drawn box and the crop that follows it. */
private const val GUIDE_LEFT = 0.05f
private const val GUIDE_TOP = 0.325f
private const val GUIDE_WIDTH = 0.90f
private const val GUIDE_HEIGHT = 0.35f
private const val GUIDE_MARGIN = 0.05f

/**
 * Step 1 of the workflow: CameraX preview with a page-framing guide.
 * A "pick from gallery" button lets teammates without a phone camera test with
 * saved photos, and the History button is the demo fallback.
 */
@Composable
fun CaptureScreen(
    vm: SolverViewModel,
    onCaptured: () -> Unit,
    onGalleryPicked: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    // PreviewView pixel size, needed to map the guide box to image coordinates.
    var previewSize by remember { mutableStateOf(IntSize.Zero) }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val f = File(ctx.cacheDir, "picked_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        // No framing guide for picked photos — send to the adjustable crop screen.
        vm.setPendingGallery(f)
        onGalleryPicked()
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { previewSize = it }) {
            if (hasPermission) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        PreviewView(context).also { view ->
                            val providerFuture = ProcessCameraProvider.getInstance(context)
                            providerFuture.addListener({
                                val provider = providerFuture.get()
                                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                                provider.unbindAll()
                                provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                            }, ContextCompat.getMainExecutor(context))
                        }
                    },
                )
            } else {
                Text("Camera permission needed", Modifier.align(Alignment.Center))
            }
            // Framing guide: keep one expression inside the box.
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width * GUIDE_WIDTH
                val h = size.height * GUIDE_HEIGHT
                drawRect(
                    color = Color(0xFF2A78D6),
                    topLeft = Offset(size.width * GUIDE_LEFT, size.height * GUIDE_TOP),
                    size = Size(w, h),
                    style = Stroke(width = 6f),
                )
            }
            if (state.busy) {
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(state.status, color = Color.White)
                }
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Frame one expression inside the box", style = MaterialTheme.typography.bodyMedium)
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = hasPermission && !state.busy,
                onClick = {
                    val f = File(ctx.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
                    imageCapture.takePicture(
                        ImageCapture.OutputFileOptions.Builder(f).build(),
                        ContextCompat.getMainExecutor(ctx),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                val crop = CropSpec.GuideBox(
                                    viewWidth = previewSize.width.toFloat(),
                                    viewHeight = previewSize.height.toFloat(),
                                    leftFrac = GUIDE_LEFT, topFrac = GUIDE_TOP,
                                    widthFrac = GUIDE_WIDTH, heightFrac = GUIDE_HEIGHT,
                                    marginFrac = GUIDE_MARGIN,
                                )
                                vm.captureAndRecognize(f, crop); onCaptured()
                            }
                            override fun onError(exception: ImageCaptureException) { /* surface via snackbar */ }
                        },
                    )
                },
            ) { Text("Capture") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { galleryLauncher.launch("image/*") }, modifier = Modifier.weight(1f)) { Text("Gallery") }
                OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("History") }
                TextButton(onClick = onSettings) { Text("Backend") }
            }
        }
    }
}
