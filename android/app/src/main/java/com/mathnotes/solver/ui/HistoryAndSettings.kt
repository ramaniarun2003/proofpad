package com.mathnotes.solver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.ui.components.CheckIcon
import com.mathnotes.solver.ui.components.CrossIcon
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.NotepadHeader
import com.mathnotes.solver.ui.components.StickyNote
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FILTERS = listOf("All" to null, "Derivatives" to "derivative", "Integrals" to "integral", "Series" to "series")
private val THUMB_TILTS = listOf(-4f, 3f, -2f, 5f, -3f)

/** Cached sessions from Room, styled as the notebook (mockup 05_history). */
@Composable
fun HistoryScreen(vm: SolverViewModel, onOpen: () -> Unit, onSettings: () -> Unit, onSnap: () -> Unit) {
    val history by vm.history.collectAsState()
    HistoryContent(
        history = history,
        stepCountOf = vm::stepCount,
        onOpen = { vm.openCached(it); onOpen() },
        onSettings = onSettings,
        onSnap = onSnap,
    )
}

/** Stateless body — so both the populated and empty states can be previewed/screenshotted. */
@Composable
fun HistoryContent(
    history: List<SessionEntity>,
    stepCountOf: (SessionEntity) -> Int,
    onOpen: (SessionEntity) -> Unit,
    onSettings: () -> Unit,
    onSnap: () -> Unit,
) {
    var filter by remember { mutableStateOf("All") }

    val topicFilter = FILTERS.firstOrNull { it.first == filter }?.second
    val filtered = if (topicFilter == null) history else history.filter { it.topic == topicFilter }
    val rows = remember(filtered) { groupByDay(filtered) }

    Column(Modifier.fillMaxSize()) {
        NotepadHeader("My notebook", "${history.size} problem" + if (history.size == 1) "" else "s")

        // Filter chips.
        Row(
            Modifier.fillMaxWidth().background(NotepadColors.Paper).padding(start = 56.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FILTERS.forEach { (label, _) -> FilterChip(label, selected = filter == label) { filter = label } }
        }

        Box(Modifier.weight(1f).fillMaxWidth().ruledPaper()) {
            if (rows.isEmpty()) {
                StickyNote(Modifier.align(Alignment.Center).padding(24.dp), rotationDeg = -1.5f) {
                    Text(
                        if (history.isEmpty()) "No saved problems yet.\nSnap one to get started." else "Nothing in this filter.",
                        fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.NoteInkYellow,
                    )
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        when (row) {
                            is HistoryRow.Section -> Text(
                                row.label,
                                Modifier.padding(start = 60.dp).height(36.dp),
                                fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp,
                                lineHeight = 36.sp, color = NotepadColors.Muted,
                            )
                            is HistoryRow.Entry -> HistoryItem(row.session, row.index, stepCountOf) { onOpen(row.session) }
                        }
                    }
                    item {
                        StickyNote(Modifier.padding(start = 64.dp, end = 24.dp, top = 12.dp, bottom = 16.dp).fillMaxWidth(0.62f), rotationDeg = -1.5f) {
                            Text(
                                "Tap a problem to reopen it — saved offline, even with no network.",
                                fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.NoteInkYellow,
                            )
                        }
                    }
                }
            }
        }

        NotepadBottomBar {
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) { Text("Settings", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }

            Button(
                onClick = onSnap,
                modifier = Modifier.weight(1.4f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Canvas(Modifier.size(18.dp)) { drawCamera(NotepadColors.Paper) }
                    Text("Snap a problem", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun HistoryItem(s: SessionEntity, index: Int, stepCountOf: (SessionEntity) -> Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            when (s.verified) {
                true -> CheckIcon(NotepadColors.Green, 24.dp)
                false -> CrossIcon(NotepadColors.Red, 24.dp)
                null -> Text("·", fontFamily = NotepadFonts.PatrickHand, fontSize = 22.sp, color = NotepadColors.Muted)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            LatexView(mathLine(s), textSizePx = with(LocalDensity.current) { 18.sp.toPx() })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    metaLine(s, stepCountOf),
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp,
                    color = if (s.verified == false) NotepadColors.Red else NotepadColors.Muted,
                )
                if (s.rating > 0) {
                    val ratingColor = when (s.rating) {
                        1 -> Color(0xFF4CAF50)
                        2 -> Color(0xFF8BC34A)
                        3 -> Color(0xFFFFB300)
                        4 -> Color(0xFFFF9800)
                        5 -> Color(0xFFF44336)
                        else -> Color(0xFFFFB300)
                    }
                    Text("★".repeat(s.rating), color = ratingColor, fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp)
                }
            }
        }
        // Thumbnail card, pinned at a slight angle.
        Box(
            Modifier
                .padding(end = 16.dp)
                .size(44.dp)
                .graphicsLayer { rotationZ = THUMB_TILTS[index % THUMB_TILTS.size] }
                .shadow(2.dp)
                .background(NotepadColors.PaperCard)
                .border(1.dp, NotepadColors.HeaderBorder),
        ) {
            val f = s.imagePath?.let(::File)
            if (f != null && f.exists()) {
                AsyncImage(model = f, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val mod = if (selected) {
        Modifier.clip(RoundedCornerShape(999.dp)).background(NotepadColors.Ink)
    } else {
        Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(999.dp))
    }
    Box(mod.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(
            label,
            fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp,
            color = if (selected) NotepadColors.Paper else NotepadColors.Ink,
        )
    }
}

// --- History data shaping ---

private sealed interface HistoryRow {
    data class Section(val label: String) : HistoryRow
    data class Entry(val session: SessionEntity, val index: Int) : HistoryRow
}

/** Flatten newest-first sessions into day-grouped rows (Today / Yesterday / MMM d). */
private fun groupByDay(sessions: List<SessionEntity>): List<HistoryRow> {
    val rows = mutableListOf<HistoryRow>()
    var current: String? = null
    sessions.forEachIndexed { i, s ->
        val label = dayLabel(s.createdAt)
        if (label != current) { rows += HistoryRow.Section(label); current = label }
        rows += HistoryRow.Entry(s, i)
    }
    return rows
}

private fun dayLabel(ts: Long): String {
    val d = Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).toLocalDate()
    val today = LocalDate.now()
    return when (d) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> d.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun timeLabel(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a")).lowercase()

/** "problem = answer" (or "problem  <verdict>" for series); problem alone if unsolved. */
private fun mathLine(s: SessionEntity): String {
    val problem = s.confirmedLatex.ifEmpty { s.recognizedLatex }
    if (s.answerLatex.isBlank()) return problem
    return if (s.topic == "series") "$problem \\;\\; \\text{${s.answerLatex}}" else "$problem = ${s.answerLatex}"
}

private fun metaLine(s: SessionEntity, stepCountOf: (SessionEntity) -> Int): String {
    val status = when (s.verified) {
        true -> "verified"
        false -> "step ${(s.mismatchAt ?: 0) + 1} flagged"
        null -> "not solved"
    }
    val parts = mutableListOf<String>()
    if (s.verified == true && s.topic != "series") parts += "${stepCountOf(s)} steps"
    parts += status
    parts += timeLabel(s.createdAt)
    return parts.joinToString(" · ")
}

private fun DrawScope.drawCamera(color: Color) {
    val s = size.minDimension
    val f = s / 24f
    val path = Path().apply {
        moveTo(4f * f, 7f * f); lineTo(7f * f, 7f * f); lineTo(9f * f, 4f * f); lineTo(15f * f, 4f * f)
        lineTo(17f * f, 7f * f); lineTo(20f * f, 7f * f); lineTo(20f * f, 19f * f); lineTo(4f * f, 19f * f); close()
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawCircle(color, radius = s * 0.15f, center = Offset(s * 0.5f, s * 0.54f), style = Stroke(width = 2.dp.toPx()))
}

/** Where the backend lives on today's LAN. */
@Composable
fun SettingsScreen(vm: SolverViewModel, onDone: () -> Unit) {
    var url by remember { mutableStateOf(vm.backendUrl) }
    var debugImages by remember { mutableStateOf(vm.debugImages) }
    var adaptiveThreshold by remember { mutableStateOf(vm.useAdaptiveThreshold) }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Backend URL", style = MaterialTheme.typography.headlineSmall)
        Text("Laptop running uvicorn on the same Wi-Fi, e.g. http://192.168.1.42:8000/. Emulator → http://10.0.2.2:8000/.")
        OutlinedTextField(value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)

        Text("Debug", style = MaterialTheme.typography.titleMedium)
        SettingToggle(
            label = "Save preprocessing stages",
            help = "Write each stage (raw/crop/gray/flat/final) to filesDir/debug.",
            checked = debugImages,
            onChange = { debugImages = it; vm.debugImages = it },
        )
        SettingToggle(
            label = "Use adaptive threshold",
            help = "Binarize instead of the gentle grayscale pipeline — for A/B comparison.",
            checked = adaptiveThreshold,
            onChange = { adaptiveThreshold = it; vm.useAdaptiveThreshold = it },
        )

        Button(onClick = { vm.setBackendUrl(url.trim()); onDone() }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
    }
}

@Composable
private fun SettingToggle(label: String, help: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
