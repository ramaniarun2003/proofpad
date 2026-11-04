package com.mathnotes.solver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.export.PdfExporter
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import com.mathnotes.solver.ui.theme.folderColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TILTS = listOf(-1f, 1.2f, 0.6f, -1.4f, 0.8f)

/** VM-bound folder detail (mockup 08). */
@Composable
fun FolderDetailScreen(vm: SolverViewModel, onBack: () -> Unit, onOpenNote: () -> Unit, onAddNote: () -> Unit) {
    val filter by vm.folderFilter.collectAsState()
    val folders by vm.folders.collectAsState()
    val notes by vm.folderNotes.collectAsState()
    val folder = folders.firstOrNull { it.folder.id == filter.folderId }

    FolderDetailContent(
        folderName = folder?.folder?.name ?: "",
        colorIndex = folder?.folder?.colorIndex ?: 0,
        term = folder?.folder?.term,
        noteCount = folder?.noteCount ?: notes.size,
        notes = notes,
        flaggedOnly = filter.flaggedOnly,
        topicFilter = filter.topic,
        difficultyFilter = filter.difficulty,
        onToggleFlagged = { vm.setFolderFlagged(!filter.flaggedOnly) },
        onTopic = { vm.setFolderTopic(if (filter.topic == it) null else it) },
        onDifficulty = { vm.setFolderDifficulty(if (filter.difficulty == it) null else it) },
        stepCountOf = vm::stepCount,
        onBack = onBack,
        onOpenNote = { vm.openNote(it); onOpenNote() },
        onAddNote = onAddNote,
        onRename = { name -> vm.renameFolder(filter.folderId, name) },
        onDelete = { vm.deleteFolder(filter.folderId); onBack() },
    )
}

@Composable
fun FolderDetailContent(
    folderName: String,
    colorIndex: Int,
    term: String?,
    noteCount: Int,
    notes: List<SessionEntity>,
    flaggedOnly: Boolean,
    topicFilter: String?,
    difficultyFilter: Difficulty?,
    onToggleFlagged: () -> Unit,
    onTopic: (String) -> Unit,
    onDifficulty: (Difficulty) -> Unit,
    stepCountOf: (SessionEntity) -> Int,
    onBack: () -> Unit,
    onOpenNote: (SessionEntity) -> Unit,
    onAddNote: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var showRename by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        // Colored header band.
        Column(Modifier.fillMaxWidth().background(folderColor(colorIndex).body)) {
            Row(
                Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Canvas(Modifier.size(24.dp).clickable(onClick = onBack)) { drawBack(NotepadColors.Ink) }
                    Text(folderName, fontFamily = NotepadFonts.PatrickHand, fontSize = 30.sp, color = NotepadColors.Ink)
                }
                Text(
                    "$noteCount note" + (if (noteCount == 1) "" else "s") + (term?.let { " · $it" } ?: ""),
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Muted,
                )
            }
            HorizontalDivider(color = NotepadColors.HeaderBorder, thickness = 1.dp)
        }

        // Filter chips + options.
        Row(
            Modifier.fillMaxWidth().background(NotepadColors.Paper).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Chip("Flagged", selected = flaggedOnly, onClick = onToggleFlagged)
                Chip("Derivatives", selected = topicFilter == "derivative") { onTopic("derivative") }
                Chip("Integrals", selected = topicFilter == "integral") { onTopic("integral") }
                Chip("Series", selected = topicFilter == "series") { onTopic("series") }
                DiffChip("Easy", Difficulty.EASY, difficultyFilter, onDifficulty)
                DiffChip("Medium", Difficulty.MEDIUM, difficultyFilter, onDifficulty)
                DiffChip("Hard", Difficulty.HARD, difficultyFilter, onDifficulty)
            }
            FolderOptions(onRename = { showRename = true }, onDelete = onDelete)
        }

        // Grid of note cards (2 columns) + "add" card.
        Column(
            Modifier.weight(1f).fillMaxWidth().background(NotepadColors.Paper).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (notes.isEmpty()) {
                Text(
                    if (flaggedOnly || topicFilter != null || difficultyFilter != null) "No notes match these filters." else "No notes here yet.",
                    fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted,
                )
            }
            val cells: List<SessionEntity?> = notes + listOf(null) // null = the add card
            cells.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    pair.forEachIndexed { i, cell ->
                        if (cell == null) AddCard(folderName, onAddNote, Modifier.weight(1f))
                        else NoteCard(cell, stepCountOf, TILTS[(notes.indexOf(cell)) % TILTS.size], onOpenNote, Modifier.weight(1f))
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        // Bottom bar.
        Column(Modifier.fillMaxWidth().background(NotepadColors.HeaderBand)) {
            HorizontalDivider(color = NotepadColors.HeaderBorder, thickness = 1.dp)
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { /* multi-select: deferred */ },
                    enabled = false,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, NotepadColors.Held),
                    colors = ButtonDefaults.outlinedButtonColors(disabledContentColor = NotepadColors.Held),
                ) { Text("Select", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }

                Button(
                    onClick = { /* folder PDF export: deferred */ },
                    enabled = false,
                    modifier = Modifier.weight(1.4f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(disabledContainerColor = NotepadColors.Held, disabledContentColor = NotepadColors.Paper),
                ) { Text("Export folder as PDF", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
            }
        }
    }

    if (showRename) {
        RenameFolderDialog(current = folderName, onDismiss = { showRename = false }, onRename = { onRename(it); showRename = false })
    }
}

@Composable
private fun NoteCard(note: SessionEntity, stepCountOf: (SessionEntity) -> Int, tilt: Float, onClick: (SessionEntity) -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val flagged = note.verified == false
    Box(
        modifier.height(158.dp).graphicsLayer { rotationZ = tilt }.shadow(2.dp)
            .clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, NotepadColors.HeaderBorder)
            .clickable { onClick(note) },
    ) {
        Column(Modifier.fillMaxSize().padding(10.dp)) {
            // Problem + answer take the remaining space; an over-tall integral is
            // clipped here rather than pushing the difficulty/tag meta off the card.
            Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                Column {
                    LatexView(problemOf(note), textSizePx = with(density) { 13.sp.toPx() })
                    when {
                        flagged -> Text("unresolved", fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Red)
                        note.answerLatex.isBlank() -> {}
                        note.topic == "series" -> Text(note.answerLatex, fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Muted)
                        else -> LatexView("= ${note.answerLatex}", textSizePx = with(density) { 11.sp.toPx() })
                    }
                }
            }
            Text(
                listOfNotNull(difficultyLabel(note.difficulty).ifBlank { null }, "${stepCountOf(note)} steps").joinToString(" · "),
                fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = difficultyColor(note.difficulty),
            )
            Text(
                listOfNotNull(note.tags.firstOrNull(), note.savedAt?.let(::dateShort)).joinToString(" · "),
                fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Muted,
            )
        }
        CardStamp(flagged, note.mismatchAt, Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

@Composable
private fun AddCard(folderName: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier.height(158.dp).clip(RoundedCornerShape(6.dp))
            .border(2.dp, NotepadColors.ChipBorder, RoundedCornerShape(6.dp)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(30.dp)) { drawCamera(NotepadColors.Held) }
            Text("add to $folderName", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Held)
        }
    }
}

@Composable
private fun CardStamp(flagged: Boolean, mismatchAt: Int?, modifier: Modifier) {
    val color = if (flagged) NotepadColors.Red else NotepadColors.Green
    val text = if (flagged) "STEP ${(mismatchAt ?: 0) + 1}" else "OK"
    Box(
        modifier.graphicsLayer { rotationZ = 10f }.border(2.dp, color, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
    ) { Text(text, fontFamily = NotepadFonts.PatrickHand, fontSize = 11.sp, color = color, letterSpacing = 1.sp) }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    val mod = if (selected) Modifier.clip(RoundedCornerShape(999.dp)).background(NotepadColors.Ink)
    else Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(999.dp))
    Box(mod.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 5.dp)) {
        Text(label, fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = if (selected) NotepadColors.Paper else NotepadColors.Ink)
    }
}

@Composable
private fun DiffChip(label: String, d: Difficulty, current: Difficulty?, onDifficulty: (Difficulty) -> Unit) {
    val selected = current == d
    val dot = difficultyColor(d)
    val mod = if (selected) Modifier.clip(RoundedCornerShape(999.dp)).background(NotepadColors.Ink)
    else Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(999.dp))
    Row(
        mod.clickable { onDifficulty(d) }.padding(horizontal = 14.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(dot))
        Text(label, fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = if (selected) NotepadColors.Paper else NotepadColors.Ink)
    }
}

@Composable
private fun FolderOptions(onRename: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Canvas(Modifier.size(28.dp).clickable { open = true }) {
            val s = size.minDimension
            listOf(0.25f, 0.5f, 0.75f).forEach { f ->
                drawCircle(NotepadColors.Ink, radius = s * 0.06f, center = Offset(s / 2, s * f))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("Rename folder") }, onClick = { open = false; onRename() })
            DropdownMenuItem(text = { Text("Delete folder") }, onClick = { open = false; onDelete() })
        }
    }
}

@Composable
private fun RenameFolderDialog(current: String, onDismiss: () -> Unit, onRename: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (name.isNotBlank()) onRename(name.trim()) }, enabled = name.isNotBlank()) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename folder", fontFamily = NotepadFonts.PatrickHand, fontSize = 22.sp) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp))
        },
    )
}

// --- helpers ---

private fun problemOf(n: SessionEntity): String = n.confirmedLatex.ifEmpty { n.recognizedLatex }

private fun difficultyLabel(d: Difficulty?): String = when (d) {
    Difficulty.EASY -> "Easy"; Difficulty.MEDIUM -> "Medium"; Difficulty.HARD -> "Hard"; null -> ""
}

private fun difficultyColor(d: Difficulty?): Color = when (d) {
    Difficulty.EASY -> NotepadColors.DifficultyEasy
    Difficulty.MEDIUM -> NotepadColors.DifficultyMedium
    Difficulty.HARD -> NotepadColors.DifficultyHard
    null -> NotepadColors.Muted
}

private fun dateShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))

private fun DrawScope.drawBack(color: Color) {
    val s = size.minDimension
    val f = s / 24f
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(15f * f, 5f * f); lineTo(8f * f, 12f * f); lineTo(15f * f, 19f * f)
    }
    drawPath(path, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}
