package com.mathnotes.solver.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.mathnotes.solver.image.CropSpec
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import java.io.File

/**
 * Framing guide geometry, shared by the drawn box and the crop that follows it.
 * Positioned to match mockup 01_capture (a wide, short box in the upper-middle);
 * the crop maps this same rectangle to image pixels, so the two stay in lockstep.
 */
private const val GUIDE_LEFT = 0.08f
private const val GUIDE_TOP = 0.25f
private const val GUIDE_WIDTH = 0.84f
private const val GUIDE_HEIGHT = 0.23f
private const val GUIDE_MARGIN = 0.05f

/** Chip label → the topic value the backend expects ("unknown" = Auto / let the recognizer guess). */
private val TOPICS = listOf(
    "Auto" to "unknown",
    "Derivative" to "derivative",
    "Integral" to "integral",
    "Series" to "series",
)

/**
 * Step 1 of the workflow, styled as mockup 01_capture: a full-bleed camera view
 * with a yellow framing guide (and dimming scrim), a handwritten header, topic
 * chips, and the gallery / shutter / settings control row. A "pick from gallery"
 * path routes to the adjustable crop screen; History is the demo fallback.
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
    // Topic hint chips — the pick overrides the recognizer's guess (see captureAndRecognize).
    var selectedTopic by remember { mutableStateOf("Auto") }
    // Bound camera + torch state, for the flash button.
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { camera?.cameraControl?.enableTorch(false) } }

    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val f = File(ctx.cacheDir, "picked_${System.currentTimeMillis()}.jpg")
        ctx.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        // No framing guide for picked photos — send to the adjustable crop screen.
        vm.setPendingGallery(f)
        onGalleryPicked()
    }

    fun capture() {
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
                    val topicHint = TOPICS.firstOrNull { it.first == selectedTopic }?.second ?: "unknown"
                    vm.captureAndRecognize(f, crop, topicHint); onCaptured()
                }
                override fun onError(exception: ImageCaptureException) { /* surface via snackbar */ }
            },
        )
    }

    val vignette = Brush.radialGradient(
        0.0f to NotepadColors.CaptureVignetteInner,
        0.45f to NotepadColors.CaptureVignetteMid,
        1.0f to NotepadColors.CaptureVignetteOuter,
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(vignette)) {
        val h = maxHeight

        // Camera preview fills the frame (vignette shows through until it binds).
        if (hasPermission) {
            AndroidView(
                modifier = Modifier.fillMaxSize().onSizeChanged { previewSize = it },
                factory = { context ->
                    PreviewView(context).also { view ->
                        val providerFuture = ProcessCameraProvider.getInstance(context)
                        providerFuture.addListener({
                            val provider = providerFuture.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            provider.unbindAll()
                            camera = provider.bindToLifecycle(lifecycle, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                            camera?.cameraControl?.enableTorch(torchOn)
                        }, ContextCompat.getMainExecutor(context))
                    }
                },
            )
        } else {
            Text(
                "Camera permission needed",
                Modifier.align(Alignment.Center),
                color = NotepadColors.Paper,
                fontFamily = NotepadFonts.PatrickHand,
                fontSize = 18.sp,
            )
        }

        // Dimming scrim with a clear window, the yellow guide border, and corner brackets.
        Canvas(Modifier.fillMaxSize()) { drawGuide() }

        // Header: wordmark + flash / history icon buttons.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ProofPad", fontFamily = NotepadFonts.PatrickHand, fontSize = 28.sp, color = NotepadColors.Paper)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RoundIconButton(
                    onClick = {
                        val cam = camera
                        if (cam != null && cam.cameraInfo.hasFlashUnit()) {
                            torchOn = !torchOn
                            cam.cameraControl.enableTorch(torchOn)
                        }
                    },
                    highlighted = torchOn,
                ) { if (torchOn) drawFlashOn() else drawFlashOff() }
                RoundIconButton(onClick = onHistory) { drawHistory() }
            }
        }

        // Caption under the header.
        Text(
            "Put one problem inside the box, page flat",
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .offset(y = 52.dp)
                .padding(horizontal = 20.dp),
            color = NotepadColors.StickyYellow,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 18.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Status line just below the guide box.
        Text(
            if (state.busy) state.status.ifBlank { "Recognizing…" } else "page found · hold still",
            Modifier
                .fillMaxWidth()
                .offset(y = h * (GUIDE_TOP + GUIDE_HEIGHT) + 10.dp)
                .padding(horizontal = 20.dp),
            color = NotepadColors.CaptureCaption,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Topic hint chips.
        Row(
            Modifier
                .fillMaxWidth()
                .offset(y = h * 0.77f)
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        ) {
            TOPICS.forEach { (label, _) -> TopicChip(label, selected = selectedTopic == label) { selectedTopic = label } }
        }

        // Bottom control row: gallery thumb · shutter · settings.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 44.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Gallery thumbnail button.
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFD9D2C0))
                    .border(2.dp, NotepadColors.Paper.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { galleryLauncher.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) { Canvas(Modifier.size(28.dp)) { drawGalleryGlyph(NotepadColors.Ink) } }

            // Shutter.
            val shutterEnabled = hasPermission && !state.busy
            Box(
                Modifier
                    .size(82.dp)
                    .border(4.dp, NotepadColors.Paper.copy(alpha = if (shutterEnabled) 1f else 0.5f), CircleShape)
                    .clickable(enabled = shutterEnabled) { capture() },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(NotepadColors.Paper.copy(alpha = if (shutterEnabled) 1f else 0.5f)),
                )
            }

            // Settings.
            Box(
                Modifier.size(52.dp).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Canvas(Modifier.size(26.dp)) { drawGear(NotepadColors.Paper) } }
        }

        // Busy spinner over the guide.
        if (state.busy) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = NotepadColors.StickyYellow,
            )
        }
    }
}

/** Dimming scrim outside the guide, plus the yellow border and corner brackets. */
private fun DrawScope.drawGuide() {
    val gl = size.width * GUIDE_LEFT
    val gt = size.height * GUIDE_TOP
    val gw = size.width * GUIDE_WIDTH
    val gh = size.height * GUIDE_HEIGHT
    val scrim = NotepadColors.CaptureScrim
    // Four bands around the clear window.
    drawRect(scrim, Offset(0f, 0f), Size(size.width, gt))
    drawRect(scrim, Offset(0f, gt + gh), Size(size.width, size.height - (gt + gh)))
    drawRect(scrim, Offset(0f, gt), Size(gl, gh))
    drawRect(scrim, Offset(gl + gw, gt), Size(size.width - (gl + gw), gh))

    val yellow = NotepadColors.StickyYellow
    val r = 6.dp.toPx()
    drawRoundRect(
        color = yellow,
        topLeft = Offset(gl, gt),
        size = Size(gw, gh),
        cornerRadius = CornerRadius(r, r),
        style = Stroke(width = 2.dp.toPx()),
    )
    // Thicker L-brackets at each corner.
    val arm = 20.dp.toPx()
    val sw = 4.dp.toPx()
    fun corner(cx: Float, cy: Float, dx: Int, dy: Int) {
        drawLine(yellow, Offset(cx, cy), Offset(cx + dx * arm, cy), strokeWidth = sw, cap = StrokeCap.Round)
        drawLine(yellow, Offset(cx, cy), Offset(cx, cy + dy * arm), strokeWidth = sw, cap = StrokeCap.Round)
    }
    corner(gl, gt, 1, 1)
    corner(gl + gw, gt, -1, 1)
    corner(gl, gt + gh, 1, -1)
    corner(gl + gw, gt + gh, -1, -1)
}

@Composable
private fun RoundIconButton(onClick: () -> Unit, highlighted: Boolean = false, icon: DrawScope.() -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlighted) NotepadColors.StickyYellow.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Canvas(Modifier.size(20.dp)) { icon() } }
}

@Composable
private fun TopicChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val mod = if (selected) {
        Modifier.clip(RoundedCornerShape(999.dp)).background(NotepadColors.StickyYellow)
    } else {
        Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, Color.White.copy(alpha = 0.5f), RoundedCornerShape(999.dp))
    }
    Box(mod.clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 7.dp)) {
        Text(
            label,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 17.sp,
            color = if (selected) NotepadColors.Ink else NotepadColors.Paper,
        )
    }
}

// --- Line-art icons, drawn to match the mockup's 2px stroked SVGs (24-unit viewport). ---

/** Stroke a polyline given in 24×24 viewport coordinates, scaled to the canvas. */
private fun DrawScope.poly(color: Color, pts: List<Pair<Float, Float>>, close: Boolean = false, widthDp: Float = 2f) {
    val f = size.minDimension / 24f
    val path = Path()
    pts.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * f, y * f) else path.lineTo(x * f, y * f) }
    if (close) path.close()
    drawPath(path, color, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawFlashOff() {
    poly(NotepadColors.Paper, listOf(13f to 2f, 4f to 14f, 11f to 14f, 10f to 22f, 19f to 10f, 12f to 10f, 13f to 2f), close = true)
    poly(NotepadColors.Paper, listOf(3f to 3f, 21f to 21f))
}

private fun DrawScope.drawFlashOn() {
    // Bolt without the slash, in ink so it reads on the yellow "on" highlight.
    poly(NotepadColors.Ink, listOf(13f to 2f, 4f to 14f, 11f to 14f, 10f to 22f, 19f to 10f, 12f to 10f, 13f to 2f), close = true)
}

private fun DrawScope.drawHistory() {
    val c = NotepadColors.Paper
    val s = size.minDimension
    // Circular arrow: most of a ring with an arrowhead at the top-left.
    drawArc(c, startAngle = 150f, sweepAngle = 300f, useCenter = false,
        topLeft = Offset(s * 0.12f, s * 0.12f), size = Size(s * 0.76f, s * 0.76f),
        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
    poly(c, listOf(3f to 3f, 3f to 8f, 8f to 8f))        // arrowhead
    poly(c, listOf(12f to 7f, 12f to 12f, 15f to 15f))   // clock hands
}

private fun DrawScope.drawGalleryGlyph(color: Color) {
    val s = size.minDimension
    drawRoundRect(color, topLeft = Offset(s * 0.12f, s * 0.16f), size = Size(s * 0.76f, s * 0.68f),
        cornerRadius = CornerRadius(s * 0.08f, s * 0.08f), style = Stroke(width = 2.dp.toPx()))
    drawCircle(color, radius = s * 0.07f, center = Offset(s * 0.34f, s * 0.38f), style = Stroke(width = 2.dp.toPx()))
    poly(color, listOf(21f to 16f, 16f to 11f, 8f to 19f)) // mountain
}

private fun DrawScope.drawGear(color: Color) {
    val s = size.minDimension
    val cx = s / 2f; val cy = s / 2f
    val rOuter = s * 0.42f; val rInner = s * 0.30f
    // Eight teeth as short radial lines.
    for (i in 0 until 8) {
        val a = Math.toRadians((i * 45).toDouble())
        val dx = Math.cos(a).toFloat(); val dy = Math.sin(a).toFloat()
        drawLine(color, Offset(cx + dx * rInner, cy + dy * rInner), Offset(cx + dx * rOuter, cy + dy * rOuter),
            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    }
    drawCircle(color, radius = rInner, style = Stroke(width = 2.dp.toPx()))
    drawCircle(color, radius = s * 0.12f, style = Stroke(width = 2.dp.toPx()))
}
