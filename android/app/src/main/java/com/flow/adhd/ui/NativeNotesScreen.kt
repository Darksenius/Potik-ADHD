package com.flow.adhd.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowDefaults
import com.flow.adhd.domain.FlowSnapshot
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The single notes workspace. The owner keeps the live draft and its autosave queue.
 * [onCommand] must invoke its completion callback only after a durable commit;
 * the two draft actions must flush that queue before changing the persisted draft.
 */
@Composable
fun NativeNotesScreen(
    snapshot: FlowSnapshot,
    notepadText: String,
    saveStatus: String,
    busy: Boolean,
    onNotepadChange: (String) -> Unit,
    onCommand: (FlowCommand, () -> Unit) -> Unit,
    onSaveNotepadAsNote: (String?, () -> Unit) -> Unit,
    onMakeTask: () -> Unit,
    modifier: Modifier = Modifier,
    openNoteId: Long? = null,
) {
    val folders = remember(snapshot) { notesFolders(snapshot) }
    val notes = remember(snapshot) { notesEntries(snapshot) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val storedDestination = snapshot.data.noteText("notepadFolder").orEmpty()
    val destination = folders.firstOrNull { it.id == storedDestination }?.id
        ?: folders.firstOrNull()?.id.orEmpty()
    var filterFolder by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editText by rememberSaveable { mutableStateOf("") }
    var editFolder by rememberSaveable { mutableStateOf("") }
    var deleteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteText by rememberSaveable { mutableStateOf("") }
    var copiedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current
    val filterIsEditable = !busy && editingId == null
    val search = query.trim()
    val visibleNotes = notes.filter { note ->
        (filterFolder == null || note.folder == filterFolder) &&
            (search.isEmpty() || note.text.contains(search, ignoreCase = true))
    }
    LaunchedEffect(openNoteId, notes.any { it.id == openNoteId }) {
        val note = notes.firstOrNull { it.id == openNoteId } ?: return@LaunchedEffect
        filterFolder = null; query = ""; editingId = note.id; editText = note.text; editFolder = note.folder
        listState.scrollToItem(3 + notes.indexOf(note))
    }

    LaunchedEffect(folders.map { it.id }) {
        if (filterFolder != null && folders.none { it.id == filterFolder }) filterFolder = null
    }
    LaunchedEffect(copiedKey) {
        if (copiedKey != null) {
            delay(1_600)
            copiedKey = null
        }
    }

    fun closeEditor() {
        editingId = null
        editText = ""
        editFolder = ""
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().imePadding().testTag("native-notes"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "heading") {
            Text(
                "Нотатки",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        if (editingId == null) item(key = "draft") {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        saveStatus.ifBlank { "Автозбереження увімкнено" },
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                            .testTag("native-notepad-save-status"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = notepadText,
                        onValueChange = onNotepadChange,
                        modifier = Modifier.fillMaxWidth().testTag("native-notepad"),
                        label = { Text("Нотатка") },
                        placeholder = { Text("Напиши думку…") },
                        minLines = 5,
                        maxLines = 12,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    Text(
                        "Текст зберігається автоматично, без кнопки.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    val words = notepadText.trim().takeIf { it.isNotEmpty() }
                        ?.split(Regex("\\s+"))?.size ?: 0
                    Text(
                        "Слів: $words · Символів: ${notepadText.length}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    NotesFolderPicker(
                        label = "Папка для нотатки",
                        folders = folders,
                        selectedId = destination,
                        enabled = !busy,
                        tagPrefix = "native-notepad-folder",
                        onSelect = { id ->
                            onCommand(
                                FlowCommand.SetNotepadFolder(id, UUID.randomUUID().toString(), System.currentTimeMillis()),
                            ) {}
                        },
                    )
                    Button(
                        onClick = {
                            onSaveNotepadAsNote(destination.takeIf { it.isNotEmpty() }) {
                                if (filterFolder != null) filterFolder = destination.takeIf { it.isNotEmpty() }
                            }
                        },
                        enabled = !busy && notepadText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-notepad-save"),
                    ) { Text("Додати до нотаток") }
                    OutlinedButton(
                        onClick = onMakeTask,
                        enabled = !busy && notepadText.isNotBlank(),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-notepad-make-task"),
                    ) { Text("Зробити задачею") }
                    Text(
                        "«Додати до нотаток» переносить текст у список нижче й відкриває чисте поле.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        item(key = "collection-heading") {
            Text(
                "Твої нотатки · ${notes.size}",
                modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium,
            )
        }
        item(key = "folder-filter") {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "all") {
                    FilterChip(
                        selected = filterFolder == null,
                        onClick = { filterFolder = null },
                        enabled = filterIsEditable,
                        label = { Text("Усі · ${notes.size}") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("native-notes-filter-all"),
                    )
                }
                items(folders, key = { it.id }) { folder ->
                    FilterChip(
                        selected = filterFolder == folder.id,
                        onClick = { filterFolder = folder.id },
                        enabled = filterIsEditable,
                        label = { Text("${folder.name} · ${notes.count { it.folder == folder.id }}") },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("native-notes-filter-${folder.id}"),
                    )
                }
            }
        }
        item(key = "search") {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Знайти нотатку") },
                singleLine = true,
                enabled = filterIsEditable,
                modifier = Modifier.fillMaxWidth().testTag("native-notes-search"),
            )
        }
        if (visibleNotes.isEmpty()) item(key = "empty") {
            Text(
                when {
                    search.isNotEmpty() -> "За цим запитом нотаток немає."
                    filterFolder != null -> "У цій папці ще немає нотаток."
                    else -> "Тут з’являться додані нотатки. Поточний текст уже зберігається у полі вище."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(visibleNotes, key = { it.key }) { note ->
            Card(Modifier.fillMaxWidth().testTag("native-note-${note.id ?: note.key}")) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (note.id != null && editingId == note.id) {
                        val editFocus = remember(note.key) { FocusRequester() }
                        LaunchedEffect(note.key) { editFocus.requestFocus() }
                        Text("Редагування нотатки", style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            value = editText,
                            onValueChange = { editText = it },
                            label = { Text("Текст нотатки") },
                            minLines = 5,
                            maxLines = 12,
                            enabled = !busy,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.fillMaxWidth().focusRequester(editFocus).testTag("native-note-editor"),
                        )
                        NotesFolderPicker(
                            label = "Папка",
                            folders = folders,
                            selectedId = editFolder,
                            enabled = !busy,
                            tagPrefix = "native-note-edit-folder",
                            onSelect = { editFolder = it },
                        )
                        Button(
                            onClick = {
                                val savedId = note.id
                                val savedFolder = editFolder
                                val payload = buildJsonObject {
                                    put("txt", editText)
                                    put("folder", savedFolder)
                                }
                                onCommand(
                                    FlowCommand.UpdateQuickNote(savedId, payload, UUID.randomUUID().toString(), System.currentTimeMillis()),
                                ) {
                                    if (filterFolder != null && filterFolder != savedFolder) filterFolder = null
                                    if (editingId == savedId) closeEditor()
                                }
                            },
                            enabled = !busy && editText.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-note-save-${note.id}"),
                        ) { Text("Зберегти нотатку") }
                        TextButton(
                            onClick = { closeEditor() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-note-edit-cancel"),
                        ) { Text("Скасувати") }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                folders.firstOrNull { it.id == note.folder }?.name ?: "Без папки",
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelMedium,
                            )
                            Text(note.dateTime, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                        SelectionContainer { Text(note.text) }
                        if (copiedKey == note.key) Text(
                            "Скопійовано",
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(note.text))
                                copiedKey = note.key
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("native-note-copy-${note.id ?: note.key}"),
                        ) { Text("Копіювати") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    editingId = note.id
                                    editText = note.text
                                    editFolder = note.folder
                                },
                                enabled = note.id != null && !busy && editingId == null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-note-edit-${note.id ?: note.key}"),
                            ) { Text("Редагувати") }
                            TextButton(
                                onClick = {
                                    deleteId = note.id
                                    deleteText = note.text
                                },
                                enabled = note.id != null && !busy && editingId == null,
                                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("native-note-delete-${note.id ?: note.key}"),
                            ) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                }
            }
        }
    }

    deleteId?.let { noteId ->
        AlertDialog(
            onDismissRequest = { if (!busy) { deleteId = null; deleteText = "" } },
            title = { Text("Видалити цю нотатку?") },
            text = { Text(deleteText, maxLines = 6, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onCommand(
                            FlowCommand.DeleteQuickNote(noteId, UUID.randomUUID().toString(), System.currentTimeMillis()),
                        ) {
                            if (deleteId == noteId) { deleteId = null; deleteText = "" }
                        }
                    },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-note-delete-confirm"),
                ) { Text("Видалити", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteId = null; deleteText = "" },
                    enabled = !busy,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("native-note-delete-cancel"),
                ) { Text("Скасувати") }
            },
        )
    }
}

@Composable
private fun NotesFolderPicker(
    label: String,
    folders: List<NotesScreenFolder>,
    selectedId: String,
    enabled: Boolean,
    tagPrefix: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        if (folders.isEmpty()) Text("Без папки", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (folders.none { it.id == selectedId }) item(key = "unfiled") {
                FilterChip(
                    selected = true,
                    onClick = {},
                    enabled = enabled,
                    label = { Text("Без папки") },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            items(folders, key = { it.id }) { folder ->
                FilterChip(
                    selected = selectedId == folder.id,
                    onClick = { onSelect(folder.id) },
                    enabled = enabled,
                    label = { Text(folder.name) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("$tagPrefix-${folder.id}"),
                )
            }
        }
    }
}

private data class NotesScreenFolder(val id: String, val name: String)
private data class NotesScreenEntry(val key: String, val id: Long?, val text: String, val folder: String, val dateTime: String)

private fun notesFolders(snapshot: FlowSnapshot): List<NotesScreenFolder> {
    val stored = snapshot.data["folders"] ?: FlowDefaults.create()["folders"]
    return (stored as? JsonArray).orEmpty().mapNotNull { value ->
        val folder = value as? JsonObject ?: return@mapNotNull null
        val id = folder.noteText("id") ?: return@mapNotNull null
        NotesScreenFolder(id, folder.noteText("nm")?.takeIf { it.isNotBlank() } ?: "Без назви")
    }
}

private val noteDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("uk"))

private fun notesEntries(snapshot: FlowSnapshot): List<NotesScreenEntry> = snapshot.qnotes.mapIndexed { index, note ->
    val id = (note["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    val rawDate = note.noteText("date").orEmpty()
    val date = runCatching { LocalDate.parse(rawDate).format(noteDateFormat) }.getOrDefault(rawDate)
    val dateTime = listOf(date, note.noteText("time").orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    NotesScreenEntry(
        key = id?.let { "note-$it" } ?: "unidentified-note-$index",
        id = id,
        text = note.noteText("txt").orEmpty(),
        folder = note.noteText("folder").orEmpty(),
        dateTime = dateTime,
    )
}

private fun JsonObject.noteText(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
