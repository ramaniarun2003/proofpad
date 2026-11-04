package com.mathnotes.solver.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mathnotes.solver.data.db.Difficulty
import com.mathnotes.solver.data.db.FolderWithCount
import com.mathnotes.solver.export.PdfExporter
import com.mathnotes.solver.ui.theme.FolderPalette
import com.mathnotes.solver.ui.theme.NotepadColors
import com.mathnotes.solver.ui.theme.NotepadFonts
import com.mathnotes.solver.ui.theme.folderColor

/** VM-bound entry: files the current solved note into the gallery (mockup 06). */
@Composable
fun SaveToGalleryScreen(vm: SolverViewModel, onDone: () -> Unit) {
    val state by vm.state.collectAsState()
    val folders by vm.folders.collectAsState()
    val knownTags by vm.knownTags.collectAsState()
    val ctx = LocalContext.current

    SaveToGalleryContent(
        problemLatex = state.latex,
        answerLatex = state.result?.answerLatex ?: "",
        folders = folders,
        knownTags = knownTags,
        onSkip = onDone,
        onCreateFolder = { name, color, term -> vm.createFolder(name, color, term) },
        onSave = { folderId, title, difficulty, tags, keepPhoto, alsoExport ->
            vm.saveToGallery(folderId, title, difficulty, tags, keepPhoto) {
                if (alsoExport) PdfExporter.exportAndShare(ctx, state)
                onDone()
            }
        },
    )
}

/**
 * Stateless save form, styled as mockup 06's bottom sheet: title, folder picker,
 * required difficulty, optional tags, and a keep-photo toggle. Folder and
 * difficulty are required before saving.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaveToGalleryContent(
    problemLatex: String,
    answerLatex: String,
    folders: List<FolderWithCount>,
    knownTags: List<String>,
    onSkip: () -> Unit,
    onCreateFolder: (name: String, colorIndex: Int, term: String?) -> Unit,
    onSave: (folderId: Long, title: String?, difficulty: Difficulty, tags: List<String>, keepPhoto: Boolean, alsoExport: Boolean) -> Unit,
    initialTitle: String = "",
    initialFolderId: Long? = null,
    initialDifficulty: Difficulty? = null,
    initialTags: List<String> = emptyList(),
) {
    val density = LocalDensity.current
    var title by remember { mutableStateOf(initialTitle) }
    var selectedFolderId by remember { mutableStateOf(initialFolderId) }
    var difficulty by remember { mutableStateOf(initialDifficulty) }
    var selectedTags by remember { mutableStateOf(initialTags.toSet()) }
    var keepPhoto by remember { mutableStateOf(true) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showAddTag by remember { mutableStateOf(false) }
    var awaitingNewFolder by remember { mutableStateOf(false) }

    // Auto-select a folder created from the "+ new" dialog (newest = highest id).
    LaunchedEffect(folders) {
        if (awaitingNewFolder && folders.isNotEmpty()) {
            selectedFolderId = folders.maxByOrNull { it.folder.id }?.folder?.id
            awaitingNewFolder = false
        }
    }

    val canSave = selectedFolderId != null && difficulty != null
    val selectedFolderName = folders.firstOrNull { it.folder.id == selectedFolderId }?.folder?.name

    Box(Modifier.fillMaxSize().background(NotepadColors.CaptureScrim)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                .background(NotepadColors.Paper)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        ) {
            // Drag handle.
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 14.dp)
                    .size(width = 40.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(NotepadColors.ChipBorder),
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Save to gallery", fontFamily = NotepadFonts.PatrickHand, fontSize = 26.sp, color = NotepadColors.Ink)
                Text("skip", Modifier.clickable(onClick = onSkip), fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Muted)
            }

            // Thumbnail + title.
            Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.graphicsLayer { rotationZ = -3f }.size(width = 64.dp, height = 76.dp)
                        .background(Color.White).border(1.dp, NotepadColors.HeaderBorder).padding(6.dp),
                ) {
                    Column {
                        LatexView(problemLatex, textSizePx = with(density) { 12.sp.toPx() })
                        if (answerLatex.isNotBlank()) LatexView("= $answerLatex", textSizePx = with(density) { 11.sp.toPx() })
                    }
                }
                Column(Modifier.weight(1f)) {
                    FieldLabel("Title")
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        placeholder = { Text("e.g. HW 7 problem 3", fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Held) },
                        textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp, color = NotepadColors.Ink),
                        singleLine = true,
                        shape = RoundedCornerShape(8.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = NotepadColors.Ink, unfocusedBorderColor = NotepadColors.Ink, cursorColor = NotepadColors.Ink),
                    )
                }
            }

            // Folder.
            Spacer(Modifier.height(16.dp))
            FieldLabel("Folder")
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                folders.forEach { fc ->
                    FolderPick(
                        name = fc.folder.name,
                        colorIndex = fc.folder.colorIndex,
                        selected = selectedFolderId == fc.folder.id,
                        onClick = { selectedFolderId = fc.folder.id },
                    )
                }
                NewFolderPick { showNewFolder = true }
            }

            // Difficulty (required).
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                FieldLabel("How hard was it?")
                Text("required", fontFamily = NotepadFonts.PatrickHand, fontSize = 13.sp, color = NotepadColors.Held)
            }
            Row(Modifier.padding(top = 6.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DifficultyButton("Easy", NotepadColors.DifficultyEasy, difficulty == Difficulty.EASY, Modifier.weight(1f)) { difficulty = Difficulty.EASY }
                DifficultyButton("Medium", NotepadColors.DifficultyMedium, difficulty == Difficulty.MEDIUM, Modifier.weight(1f)) { difficulty = Difficulty.MEDIUM }
                DifficultyButton("Hard", NotepadColors.DifficultyHard, difficulty == Difficulty.HARD, Modifier.weight(1f)) { difficulty = Difficulty.HARD }
            }

            // Tags (optional).
            Spacer(Modifier.height(14.dp))
            FieldLabel("Tags (optional)")
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (knownTags + selectedTags.filter { it !in knownTags }).distinct().forEach { tag ->
                    TagChip(tag, selected = tag in selectedTags) {
                        selectedTags = if (tag in selectedTags) selectedTags - tag else selectedTags + tag
                    }
                }
                AddTagChip { showAddTag = true }
            }

            // Keep photo.
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.clickable { keepPhoto = !keepPhoto },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CheckBoxGlyph(keepPhoto)
                Text("keep the original photo with the note", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.NoteInkYellow)
            }

            // Actions.
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { selectedFolderId?.let { fid -> difficulty?.let { d -> onSave(fid, title, d, selectedTags.toList(), keepPhoto, true) } } },
                    enabled = canSave,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.5.dp, if (canSave) NotepadColors.Ink else NotepadColors.Held),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = NotepadColors.Ink, disabledContentColor = NotepadColors.Held),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                ) { Text("Save + export PDF", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1, softWrap = false) }

                Button(
                    onClick = { selectedFolderId?.let { fid -> difficulty?.let { d -> onSave(fid, title, d, selectedTags.toList(), keepPhoto, false) } } },
                    enabled = canSave,
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = NotepadColors.Ink, contentColor = NotepadColors.Paper, disabledContainerColor = NotepadColors.Held),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
                ) { Text(selectedFolderName?.let { "Save to $it" } ?: "Save", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false) }
            }
        }
    }

    if (showNewFolder) {
        NewFolderDialog(
            onDismiss = { showNewFolder = false },
            onCreate = { name, color, term ->
                onCreateFolder(name, color, term)
                awaitingNewFolder = true
                showNewFolder = false
            },
        )
    }
    if (showAddTag) {
        AddTagDialog(
            onDismiss = { showAddTag = false },
            onAdd = { tag -> selectedTags = selectedTags + tag; showAddTag = false },
        )
    }
}

@Composable
private fun FieldLabel(text: String) =
    Text(text, fontFamily = NotepadFonts.PatrickHand, fontSize = 14.sp, color = NotepadColors.Muted)

@Composable
private fun FolderPick(name: String, colorIndex: Int, selected: Boolean, onClick: () -> Unit) {
    val c = folderColor(colorIndex)
    Column(Modifier.width(96.dp).clickable(onClick = onClick)) {
        Box(Modifier.size(width = 34.dp, height = 11.dp).clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp)).background(c.tab))
        Row(
            Modifier.fillMaxWidth().height(58.dp)
                .clip(RoundedCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp))
                .background(c.body)
                .then(if (selected) Modifier.border(2.dp, NotepadColors.Ink, RoundedCornerShape(topStart = 0.dp, topEnd = 8.dp, bottomEnd = 8.dp, bottomStart = 8.dp)) else Modifier)
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(name, Modifier.weight(1f), fontFamily = NotepadFonts.PatrickHand, fontSize = 16.sp, color = NotepadColors.Ink)
            if (selected) com.mathnotes.solver.ui.components.CheckIcon(NotepadColors.Green, 16.dp)
        }
    }
}

@Composable
private fun NewFolderPick(onClick: () -> Unit) {
    Box(
        Modifier.width(70.dp).height(58.dp).padding(top = 11.dp)
            .border(2.dp, NotepadColors.ChipBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text("+ new", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Held) }
}

@Composable
private fun DifficultyButton(label: String, dot: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) Color(0xFFFFF0C2) else Color.Transparent)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) NotepadColors.Ink else NotepadColors.ChipBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(dot))
        Spacer(Modifier.width(8.dp))
        Text(label, fontFamily = NotepadFonts.PatrickHand, fontSize = 17.sp, color = NotepadColors.Ink)
    }
}

@Composable
private fun TagChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val mod = if (selected) Modifier.clip(RoundedCornerShape(999.dp)).background(NotepadColors.Ink)
    else Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(999.dp))
    Box(mod.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp)) {
        Text(label, fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = if (selected) NotepadColors.Paper else NotepadColors.Ink)
    }
}

@Composable
private fun AddTagChip(onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(999.dp)).border(1.5.dp, NotepadColors.ChipBorder, RoundedCornerShape(999.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 5.dp),
    ) { Text("+ tag", fontFamily = NotepadFonts.PatrickHand, fontSize = 15.sp, color = NotepadColors.Held) }
}

@Composable
private fun CheckBoxGlyph(checked: Boolean) {
    Box(
        Modifier.size(20.dp).clip(RoundedCornerShape(4.dp))
            .background(if (checked) NotepadColors.Ink else Color.Transparent)
            .border(2.dp, NotepadColors.Ink, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) { if (checked) com.mathnotes.solver.ui.components.CheckIcon(NotepadColors.Paper, 14.dp) }
}

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (name: String, colorIndex: Int, term: String?) -> Unit) {
    var name by remember { mutableStateOf("") }
    var term by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim(), color, term.trim().ifBlank { null }) }, enabled = name.isNotBlank()) {
                Text("Create", fontFamily = NotepadFonts.Inter, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", fontFamily = NotepadFonts.Inter) } },
        title = { Text("New folder", fontFamily = NotepadFonts.PatrickHand, fontSize = 22.sp) },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Name, e.g. Calc II") }, textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp))
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(value = term, onValueChange = { term = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Term (optional), e.g. Fall 26") }, textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FolderPalette.forEachIndexed { i, fc ->
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)).background(fc.body)
                                .then(if (color == i) Modifier.border(2.dp, NotepadColors.Ink, RoundedCornerShape(8.dp)) else Modifier)
                                .clickable { color = i },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun AddTagDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var tag by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { if (tag.isNotBlank()) onAdd(tag.trim()) }, enabled = tag.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Add tag", fontFamily = NotepadFonts.PatrickHand, fontSize = 22.sp) },
        text = {
            OutlinedTextField(value = tag, onValueChange = { tag = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Tag, e.g. HW 7") }, textStyle = TextStyle(fontFamily = NotepadFonts.PatrickHand, fontSize = 18.sp))
        },
    )
}
