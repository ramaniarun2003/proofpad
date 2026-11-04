package com.mathnotes.solver.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.data.db.SessionEntity
import com.mathnotes.solver.ui.components.CheckIcon
import com.mathnotes.solver.ui.components.CrossIcon
import com.mathnotes.solver.ui.components.NotepadBottomBar
import com.mathnotes.solver.ui.components.NotepadHeader
import com.mathnotes.solver.ui.components.ruledPaper
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import com.mathnotes.solver.ui.theme.folderColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** VM-bound gallery home (mockup 07). */
@Composable
fun GalleryHomeScreen(
    vm: SolverViewModel,
    onOpenFolder: (Long) -> Unit,
    onOpenNote: () -> Unit,
    onSettings: () -> Unit,
    onSnap: () -> Unit,
) {
    val folders by vm.folders.collectAsState()
    val recent by vm.recentlyViewed.collectAsState()
    val week by vm.savedThisWeek.collectAsState()
    val query by vm.galleryQuery.collectAsState()
    val results by vm.searchResults.collectAsState()

    GalleryHomeContent(
        totalNotes = folders.sumOf { it.noteCount },
        query = query,
        onQueryChange = vm::setGalleryQuery,
        searchResults = results,
        folders = folders,
        recentlyViewed = recent,
        savedThisWeek = week,
        onCreateFolder = vm::createFolder,
        onOpenFolder = onOpenFolder,
        onOpenNote = { vm.openNote(it); onOpenNote() },
        onSettings = onSettings,
        onSnap = onSnap,
    )
}

@Composable
fun GalleryHomeContent(
    totalNotes: Int,
    query: String,
    onQueryChange: (String) -> Unit,
    searchResults: List<SessionEntity>,
    folders: List<FolderWithCount>,
    recentlyViewed: List<SessionEntity>,
    savedThisWeek: List<SessionEntity>,
    onCreateFolder: (name: String, colorIndex: Int, term: String?) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onOpenNote: (SessionEntity) -> Unit,
    onSettings: () -> Unit,
    onSnap: () -> Unit,
    nowOverride: Long? = null,
) {
    var showNewFolder by remember { mutableStateOf(false) }
    val now = nowOverride ?: System.currentTimeMillis()
    val folderNames = remember(folders) { folders.associate { it.folder.id to it.folder.name } }
    val searching = query.isNotBlank()

    Column(Modifier.fillMaxSize()) {
        NotepadHeader("Gallery", "$totalNotes note" + if (totalNotes == 1) "" else "s")

        Column(
            Modifier.weight(1f).fillMaxWidth().ruledPaper().verticalScroll(rememberScrollState())
                .padding(start = 56.dp, end = 20.dp, top = 12.dp, bottom = 20.dp),
        ) {
            SearchBar(query, onQueryChange)
            Spacer(Modifier.height(14.dp))

            if (searching) {
                if (searchResults.isEmpty()) {
                    Text("No notes match “$query”.", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
                } else {
                    searchResults.forEach { NoteRow(it, folderNames[it.folderId], now, onOpenNote) }
                }
            } else {
                // My classes.
                SectionHeader("My classes", trailing = "+ New folder", onTrailing = { showNewFolder = true })
                Spacer(Modifier.height(10.dp))
                if (folders.isEmpty()) {
                    Text("No folders yet. Save a solved note to start one.", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Muted)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        folders.forEach { FolderCard(it, onClick = { onOpenFolder(it.folder.id) }) }
                    }
                }

                // Recently viewed.
                if (recentlyViewed.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionHeader("Recently viewed")
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        recentlyViewed.forEach { NoteThumbCard(it, folderNames[it.folderId], now, onOpenNote) }
                    }
                }

                // This past week.
                if (savedThisWeek.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    SectionHeader("This past week", trailing = "${savedThisWeek.size} note" + if (savedThisWeek.size == 1) "" else "s")
                    Spacer(Modifier.height(4.dp))
                    savedThisWeek.forEach { NoteRow(it, folderNames[it.folderId], now, onOpenNote) }
                }
            }
        }

        NotepadBottomBar {
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, NotepadColors.Ink),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink),
            ) { Text("Settings", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp) }

            androidx.compose.material3.Button(
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

    if (showNewFolder) {
        NewFolderDialog(
            onDismiss = { showNewFolder = false },
            onCreate = { name, color, term -> onCreateFolder(name, color, term); showNewFolder = false },
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Canvas(Modifier.size(18.dp)) { drawSearch(NotepadColors.Muted) }
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text("search notes, e.g. u-sub", fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Held)
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Ink),
                cursorBrush = SolidColor(NotepadColors.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Text(title, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Muted)
        if (trailing != null) {
            val m = if (onTrailing != null) Modifier.clickable(onClick = onTrailing) else Modifier
            Text(trailing, m, fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp,
                color = if (onTrailing != null) NotepadColors.Ink else NotepadColors.Muted)
        }
    }
}

@Composable
private fun FolderCard(fc: FolderWithCount, onClick: () -> Unit) {
    val c = folderColor(fc.folder.colorIndex)
    Column(Modifier.width(118.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(width = 44.dp, height = 14.dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(c.tab))
        Column(
            Modifier.fillMaxWidth().height(86.dp)
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp))
                .background(c.body).padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fc.folder.name, fontFamily = NotepadFonts.PatrickHand, fontSize = 19.sp, color = NotepadColors.Ink)
            Text("${fc.noteCount} note" + if (fc.noteCount == 1) "" else "s", fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)
        }
    }
}

@Composable
private fun NoteThumbCard(note: SessionEntity, folderName: String?, now: Long, onClick: (SessionEntity) -> Unit) {
    val density = LocalDensity.current
    Column(
        Modifier.width(118.dp).height(138.dp).graphicsLayer { rotationZ = -1f }
            .clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, NotepadColors.HeaderBorder)
            .clickable { onClick(note) }.padding(8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        LatexView(problemOf(note), textSizePx = with(density) { 15.sp.toPx() })
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                StatusGlyph(note, 14.dp)
                Text(statusText(note), fontFamily = NotepadFonts.PatrickHand, fontSize = 12.sp, color = statusColor(note))
            }
            Text(
                listOfNotNull(folderName, difficultyLabel(note.difficulty)).joinToString(" · ") + " · " + relativeTime(note.lastViewedAt ?: note.savedAt ?: now, now),
                fontFamily = NotepadFonts.PatrickHand, fontSize = 12.sp, color = NotepadColors.Muted,
            )
        }
    }
}

@Composable
private fun NoteRow(note: SessionEntity, folderName: String?, now: Long, onClick: (SessionEntity) -> Unit) {
    val density = LocalDensity.current
    Row(
        Modifier.fillMaxWidth().height(54.dp).clickable { onClick(note) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(48.dp), contentAlignment = Alignment.Center) { StatusGlyph(note, 20.dp) }
        LatexView(problemOf(note), Modifier.weight(1f), textSizePx = with(density) { 17.sp.toPx() })
        Text(
            listOfNotNull(difficultyLabel(note.difficulty).ifBlank { null }, dayShort(note.savedAt ?: now)).joinToString(" · "),
            Modifier.padding(start = 8.dp, end = 4.dp),
            fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted,
        )
    }
}

@Composable
private fun StatusGlyph(note: SessionEntity, size: androidx.compose.ui.unit.Dp) {
    when (note.verified) {
        true -> CheckIcon(NotepadColors.Green, size)
        false -> CrossIcon(NotepadColors.Red, size)
        null -> Text("·", fontFamily = NotepadFonts.PatrickHand, color = NotepadColors.Muted)
    }
}

// --- helpers ---

private fun problemOf(n: SessionEntity): String = n.confirmedLatex.ifEmpty { n.recognizedLatex }

private fun difficultyLabel(d: Difficulty?): String = when (d) {
    Difficulty.EASY -> "Easy"; Difficulty.MEDIUM -> "Medium"; Difficulty.HARD -> "Hard"; null -> ""
}

private fun statusText(n: SessionEntity): String = when (n.verified) {
    true -> "verified"; false -> "step ${(n.mismatchAt ?: 0) + 1}"; null -> ""
}

private fun statusColor(n: SessionEntity): Color = when (n.verified) {
    true -> NotepadColors.Green; false -> NotepadColors.Red; null -> NotepadColors.Muted
}

private fun relativeTime(ts: Long, now: Long): String {
    val d = now - ts
    val h = d / 3_600_000
    return when {
        d < 3_600_000 -> "${(d / 60_000).coerceAtLeast(1)}m ago"
        d < 86_400_000 -> "${h}h ago"
        d < 172_800_000 -> "yest."
        else -> "${d / 86_400_000}d ago"
    }
}

private fun dayShort(ts: Long): String =
    Instant.ofEpochMilli(ts).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE"))

private fun DrawScope.drawSearch(color: Color) {
    val s = size.minDimension
    drawCircle(color, radius = s * 0.3f, center = Offset(s * 0.42f, s * 0.42f), style = Stroke(width = 2.dp.toPx()))
    drawLine(color, Offset(s * 0.64f, s * 0.64f), Offset(s * 0.9f, s * 0.9f), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}
