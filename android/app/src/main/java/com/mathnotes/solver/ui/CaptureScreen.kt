package com.mathnotes.solver.ui

import androidx.compose.runtime.saveable.rememberSaveable
import timber.log.Timber
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.NotepadHeader
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.components.ruledPaper
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.mathnotes.solver.image.CropSpec
import com.mathnotes.solver.ui.components.BackendSheet
import com.mathnotes.solver.ui.components.BackendStatusChip
import com.mathnotes.solver.ui.setup.SetupEvent
import com.mathnotes.solver.ui.setup.SetupViewModel
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

/** Page-mode guide: a tall box covering most of the sheet, for the five-line handwriting page. */
private const val PAGE_LEFT = 0.07f
private const val PAGE_TOP = 0.12f
private const val PAGE_WIDTH = 0.86f
private const val PAGE_HEIGHT = 0.74f

/** Chip label → the topic value the backend expects ("unknown" = Auto / let the recognizer guess). */
private val TOPICS = listOf(
    "Auto" to "unknown",
    "Derivative" to "derivative",
    "Integral" to "integral",
    "Series" to "series",
    "Limit" to "limit",
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
    onGallery: () -> Unit,
    onSettings: () -> Unit,
    pageMode: Boolean = false,
    setupVm: SetupViewModel? = null,
    onPageRecognized: () -> Unit = {},
    onFormulaCaptured: ((File, CropSpec) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val state by vm.state.collectAsState()

    // Page mode (handwriting setup) drives its own busy/snackbar from the SetupViewModel.
    val setupState = setupVm?.state?.collectAsState()?.value
    val busy = if (pageMode) setupState?.busy == true else state.busy
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(setupVm, pageMode) {
        if (!pageMode || setupVm == null) return@LaunchedEffect
        setupVm.events.collect { ev ->
            when (ev) {
                is SetupEvent.Recognized -> onPageRecognized()
                is SetupEvent.Error -> snackbar.showSnackbar(ev.message)
            }
        }
    }

    // Backend discovery (normal mode): the header chip + sheet, and the keep-photo-and-retry flow.
    val backendState by vm.backend.collectAsState()
    var showBackendSheet by remember { mutableStateOf(false) }
    val qrScanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { vm.setBackendUrl(it) }
    }
    LaunchedEffect(vm, pageMode) {
        if (pageMode) return@LaunchedEffect
        vm.captureEvents.collect { ev ->
            when (ev) {
                is com.mathnotes.solver.ui.CaptureEvent.NavigateToReview -> onCaptured()
                is com.mathnotes.solver.ui.CaptureEvent.OpenBackendSheet -> showBackendSheet = true
            }
        }
    }

    // Active framing geometry: the small single-problem box, or the tall whole-page box.
    val gLeft = if (pageMode) PAGE_LEFT else GUIDE_LEFT
    val gTop = if (pageMode) PAGE_TOP else GUIDE_TOP
    val gWidth = if (pageMode) PAGE_WIDTH else GUIDE_WIDTH
    val gHeight = if (pageMode) PAGE_HEIGHT else GUIDE_HEIGHT

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    // Denied twice (or "don't ask again"): the system will not show its prompt any more, so the
    // only way forward is the app's page in system settings.
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permanentlyDenied = !granted && cameraPermanentlyDenied(ctx)
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }
    // Coming back from system settings (or any other app): look again.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                if (hasPermission) permanentlyDenied = false
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }

    // PreviewView pixel size, needed to map the guide box to image coordinates.
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    // Topic hint chips — the pick overrides the recognizer's guess (see captureAndRecognize).
    var selectedTopic by rememberSaveable { mutableStateOf("Auto") }
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

    // True from the shutter tap until CameraX has written the photo: `busy` only starts after that.
    var capturing by remember { mutableStateOf(false) }

    fun capture() {
        if (capturing) return
        capturing = true
        val f = File(ctx.cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(f).build(),
            ContextCompat.getMainExecutor(ctx),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    val crop = CropSpec.GuideBox(
                        viewWidth = previewSize.width.toFloat(),
                        viewHeight = previewSize.height.toFloat(),
                        leftFrac = gLeft, topFrac = gTop,
                        widthFrac = gWidth, heightFrac = gHeight,
                        marginFrac = GUIDE_MARGIN,
                    )
                    if (onFormulaCaptured != null) {
                        // Single-line retake inside the wizard: hand the photo to the setup flow.
                        onFormulaCaptured(f, crop)
                    } else if (pageMode) {
                        // Recognition result drives navigation via SetupEvent; stay here on failure.
                        setupVm?.capturePage(f)
                    } else {
                        val topicHint = TOPICS.firstOrNull { it.first == selectedTopic }?.second ?: "unknown"
                        // If the backend is down the photo is kept and retried once it connects.
                        vm.submitCapture(f, crop, topicHint)
                    }
                }
                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Timber.w(exception, "photo capture failed")
                    vm.notify(UiMessage("I couldn't take that photo. Try again."))
                }
            },
        )
    }

    if (!hasPermission) {
        // No camera, so no viewfinder, guide box or status line: a page that says what to do.
        CameraPermissionPage(
            pageMode = pageMode || onFormulaCaptured != null,
            permanentlyDenied = permanentlyDenied,
            onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) },
            onOpenSettings = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
            onPickPhoto = { galleryLauncher.launch("image/*") },
            onGallery = onGallery, onHistory = onHistory, onSettings = onSettings,
        )
        return
    }

    val vignette = Brush.radialGradient(
        0.0f to NotepadColors.CaptureVignetteInner,
        0.45f to NotepadColors.CaptureVignetteMid,
        1.0f to NotepadColors.CaptureVignetteOuter,
    )

    BoxWithConstraints(Modifier.fillMaxSize().background(vignette)) {
        val h = maxHeight

        // Camera preview fills the frame (vignette shows through until it binds).
        run {
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
        }

        // Dimming scrim with a clear window, the yellow guide border, and corner brackets.
        Canvas(Modifier.fillMaxSize()) { drawGuide(gLeft, gTop, gWidth, gHeight) }

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
                if (!pageMode) {
                    BackendStatusChip(backendState, onClick = { showBackendSheet = true })
                    RoundIconButton(onClick = onGallery) { drawGrid() }
                    RoundIconButton(onClick = onHistory) { drawHistory() }
                }
            }
        }

        if (showBackendSheet) {
            BackendSheet(
                state = backendState,
                savedUrl = vm.savedBackendUrl,
                onSearchAgain = vm::searchBackend,
                onScanQr = {
                    qrScanner.launch(
                        ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt("Scan the backend QR from the terminal").setBeepEnabled(false).setOrientationLocked(false),
                    )
                },
                onSaveUrl = vm::setBackendUrl,
                onDismiss = { showBackendSheet = false },
            )
        }

        // Caption under the header.
        Text(
            if (pageMode) "Fit the whole page of five lines in the box" else "Put one problem inside the box, page flat",
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
            if (busy) (if (pageMode) setupState?.status else state.status)?.ifBlank { "Recognizing…" } ?: "Recognizing…"
            // Nothing looks at the preview frames yet, so this must not claim a page was found.
            // Live page detection is a known gap (README, Known issues).
            else "hold still",
            Modifier
                .fillMaxWidth()
                .offset(y = h * (gTop + gHeight) + 10.dp)
                .padding(horizontal = 20.dp),
            color = NotepadColors.CaptureCaption,
            fontFamily = NotepadFonts.PatrickHand,
            fontSize = 16.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        // Topic hint chips — single-problem mode only.
        if (!pageMode) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .offset(y = h * 0.77f)
                    .horizontalScroll(rememberScrollState())   // 5 chips (incl. Limit) overflow a phone width
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TOPICS.forEach { (label, _) -> TopicChip(label, selected = selectedTopic == label) { selectedTopic = label } }
            }
        }

        // Bottom control row: gallery thumb · shutter · settings (shutter only in page mode).
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 44.dp, vertical = 24.dp),
            horizontalArrangement = if (pageMode) Arrangement.Center else Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Gallery thumbnail button.
            if (!pageMode) Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFD9D2C0))
                    .border(2.dp, NotepadColors.Paper.copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                    .clickable { galleryLauncher.launch("image/*") },
                contentAlignment = Alignment.Center,
            ) { Canvas(Modifier.size(28.dp)) { drawGalleryGlyph(NotepadColors.Ink) } }

            // Shutter.
            val shutterEnabled = hasPermission && !busy && !capturing
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
            if (!pageMode) Box(
                Modifier.size(52.dp).clickable(onClick = onSettings),
                contentAlignment = Alignment.Center,
            ) { Canvas(Modifier.size(26.dp)) { drawGear(NotepadColors.Paper) } }
        }

        // Busy spinner over the guide.
        if (busy) {
            CircularProgressIndicator(
                Modifier.align(Alignment.Center),
                color = NotepadColors.StickyYellow,
            )
        }

        // Snackbar (page mode: "I could only find n lines…").
        if (pageMode) {
            SnackbarHost(
                snackbar,
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 120.dp),
            )
        }
    }
}

/** Dimming scrim outside the guide, plus the yellow border and corner brackets. */
private fun DrawScope.drawGuide(leftFrac: Float, topFrac: Float, widthFrac: Float, heightFrac: Float) {
    val gl = size.width * leftFrac
    val gt = size.height * topFrac
    val gw = size.width * widthFrac
    val gh = size.height * heightFrac
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

private fun DrawScope.drawGrid() {
    // 2×2 rounded squares = the notes gallery.
    val c = NotepadColors.Paper
    val s = size.minDimension
    val d = s * 0.34f
    val gap = s * 0.1f
    val x0 = s * 0.14f
    val r = androidx.compose.ui.geometry.CornerRadius(s * 0.05f, s * 0.05f)
    listOf(x0 to x0, (x0 + d + gap) to x0, x0 to (x0 + d + gap), (x0 + d + gap) to (x0 + d + gap)).forEach { (x, y) ->
        drawRoundRect(c, topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(d, d), cornerRadius = r, style = Stroke(width = 2.dp.toPx()))
    }
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


/** True once the system will no longer show the camera prompt (denied twice, or "don't ask again"). */
private fun cameraPermanentlyDenied(context: Context): Boolean {
    var c: Context? = context
    while (c is ContextWrapper && c !is Activity) c = c.baseContext
    val activity = c as? Activity ?: return false
    return !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
}

/**
 * Shown in place of the viewfinder when the camera permission is missing: why it is needed, a
 * button to ask again, and once the system has stopped asking, a link to the app's settings page.
 * Outside the setup wizard a photo can still be picked, and the other screens stay reachable.
 */
@Composable
private fun CameraPermissionPage(
    pageMode: Boolean,
    permanentlyDenied: Boolean,
    onAllow: () -> Unit,
    onOpenSettings: () -> Unit,
    onPickPhoto: () -> Unit,
    onGallery: () -> Unit,
    onHistory: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        NotepadHeader(title = "ProofPad", trailing = "camera")
        Column(
            Modifier.weight(1f).fillMaxWidth().ruledPaper()
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, end = 20.dp, top = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("I need the camera to read your work", fontFamily = NotepadFonts.PatrickHand, fontSize = 26.sp, color = NotepadColors.Ink)
            StickyNote(Modifier.fillMaxWidth(0.94f), rotationDeg = -1f) {
                Text(
                    if (permanentlyDenied) {
                        "Camera access is turned off for ProofPad. Open settings, tap Permissions, then Camera, and choose Allow."
                    } else {
                        "ProofPad takes a photo of the problem you wrote so it can check your steps. Photos are only taken when you press the shutter."
                    },
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.NoteInkYellow,
                )
            }
            Button(
                onClick = if (permanentlyDenied) onOpenSettings else onAllow,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) {
                Text(if (permanentlyDenied) "Open settings" else "Allow camera", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            }
            if (!pageMode) {
                OutlinedButton(
                    onClick = onPickPhoto,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, NotepadColors.Ink),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
                ) { Text("Pick a photo instead", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }
            }
        }
        if (!pageMode) {
            NotepadBottomBar {
                listOf("Gallery" to onGallery, "My notebook" to onHistory, "Settings" to onSettings).forEach { (label, go) ->
                    OutlinedButton(
                        onClick = go,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, NotepadColors.ChipBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                    ) { Text(label, fontFamily = NotepadFonts.Inter, fontSize = 14.sp, maxLines = 1) }
                }
            }
        }
    }
}
