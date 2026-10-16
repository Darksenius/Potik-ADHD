package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Immutable port of the persisted notes and note-folder actions. */
object NotesReducer {
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("uk"))
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val seedFolders = listOf(
        folder("impulse", "Імпульс", "⚡"), folder("thought", "Думка", "💭"),
        folder("general", "Загальне", "📝"), folder("therapy", "Терапія", "💆"),
        folder("training", "Тренування", "💪"), folder("food", "Харчування", "🍎"),
        folder("work", "Робота", "💼"), folder("kid", "Дитина", "👶"),
    )

    fun reduce(
        snapshot: FlowSnapshot,
        command: FlowCommand,
        zone: ZoneId,
        now: Long = command.activityAt,
    ): FlowCommandResult {
        if (command !is FlowCommand.AddQuickNote && command !is FlowCommand.UpdateQuickNote &&
            command !is FlowCommand.DeleteQuickNote && command !is FlowCommand.SetNotepad &&
            command !is FlowCommand.SetNotepadFolder && command !is FlowCommand.SaveNotepadAsNote &&
            command !is FlowCommand.CreateNoteFolder && command !is FlowCommand.DeleteNoteFolder
        ) return rejected("unsupported-command", "NotesReducer does not handle this command")

        return try {
            val state = snapshot.data.toMutableMap()
            val folders = readFolders(snapshot.data)
            val notes = snapshot.qnotes
            when (command) {
                is FlowCommand.AddQuickNote -> {
                    val text = command.payload.text("txt") ?: return rejected("invalid-note", "Note text is required")
                    if (text.isBlank()) return rejected("invalid-note", "Note text must not be blank")
                    val folderId = validNoteFolder(command.payload.text("folder") ?: "impulse", folders)
                    val note = makeNote(nextNoteId(notes), text, folderId, command.activityAt, zone)
                    state["qnotes"] = JsonArray(listOf(JsonObject(note) ) + notes.map { it })
                    withActivity(state, command.activityAt, zone, now)
                }
                is FlowCommand.UpdateQuickNote -> {
                    val text = command.payload.text("txt")?.trim()
                        ?: return rejected("invalid-note", "Note text is required")
                    if (text.isBlank()) return rejected("invalid-note", "Note text must not be blank")
                    val index = notes.indexOfFirst { it.long("id") == command.noteId }
                    if (index < 0) return rejected("note-not-found", "Quick note does not exist")
                    val prior = notes[index]
                    val folderId = command.payload.text("folder")?.takeIf { it.isNotEmpty() }
                        ?: prior.text("folder")
                    val updated = prior.toMutableMap().apply {
                        this["txt"] = JsonPrimitive(text)
                        if (folderId != null) this["folder"] = JsonPrimitive(folderId)
                    }
                    if (JsonObject(updated) == prior) committed(snapshot)
                    else {
                        state["qnotes"] = JsonArray(notes.mapIndexed { i, note -> if (i == index) JsonObject(updated) else note })
                        withActivity(state, now, zone, now)
                    }
                }
                is FlowCommand.DeleteQuickNote -> {
                    if (notes.none { it.long("id") == command.noteId }) committed(snapshot)
                    else {
                        state["qnotes"] = JsonArray(notes.filterNot { it.long("id") == command.noteId })
                        committed(snapshotWith(state))
                    }
                }
                is FlowCommand.SetNotepad -> {
                    val text = command.payload.text("notepad") ?: return rejected("invalid-notepad", "Notepad text is required")
                    val folderId = command.payload.text("notepadFolder")
                    val textChanged = snapshot.data.text("notepad").orEmpty() != text
                    val folderChanged = folderId != null && snapshot.data.text("notepadFolder") != folderId
                    if (!textChanged && !folderChanged) committed(snapshot)
                    else {
                        state["notepad"] = JsonPrimitive(text)
                        if (folderId != null) state["notepadFolder"] = JsonPrimitive(folderId)
                        if (textChanged && text.isNotBlank()) withActivity(state, now, zone, now)
                        else committed(snapshotWith(state))
                    }
                }
                is FlowCommand.SetNotepadFolder -> {
                    if (snapshot.data.text("notepadFolder") == command.folderId) committed(snapshot)
                    else {
                        state["notepadFolder"] = JsonPrimitive(command.folderId)
                        committed(snapshotWith(state))
                    }
                }
                is FlowCommand.SaveNotepadAsNote -> {
                    val text = snapshot.data.text("notepad").orEmpty().trim()
                    if (text.isBlank()) return rejected("empty-notepad", "Notepad is empty")
                    val requested = command.folderId ?: snapshot.data.text("notepadFolder").orEmpty()
                    val folderId = folders.firstOrNull { it.text("id") == requested }?.text("id")
                        ?: folders.firstOrNull()?.text("id").orEmpty()
                    val note = makeNote(nextNoteId(notes), text, folderId, command.activityAt, zone)
                    state["qnotes"] = JsonArray(listOf(JsonObject(note)) + notes.map { it })
                    state["notepad"] = JsonPrimitive("")
                    state["notepadFolder"] = JsonPrimitive(folderId)
                    withActivity(state, command.activityAt, zone, now)
                }
                is FlowCommand.CreateNoteFolder -> {
                    val name = command.payload.text("nm")?.trim()
                        ?: return rejected("invalid-folder", "Folder name is required")
                    if (name.isBlank()) return rejected("invalid-folder", "Folder name must not be blank")
                    val icon = command.payload.text("ico")?.trim()?.takeIf { it.isNotEmpty() } ?: "📁"
                    val baseId = "f${command.activityAt}"
                    val used = folders.mapNotNull { it.text("id") }.toSet()
                    var folderId = baseId
                    var suffix = 1
                    while (folderId in used) folderId = "$baseId-${suffix++}"
                    val folder = folder(folderId, name, icon)
                    state["folders"] = JsonArray(folders.map { it } + folder)
                    committed(snapshotWith(state))
                }
                is FlowCommand.DeleteNoteFolder -> {
                    if (command.folderId.isBlank()) return rejected("invalid-folder", "Folder ID must not be blank")
                    val remaining = folders.filterNot { it.text("id") == command.folderId }
                    val fallback = validNoteFolder("general", remaining)
                    state["folders"] = JsonArray(remaining)
                    state["qnotes"] = JsonArray(notes.map { note ->
                        if (note.text("folder") == command.folderId) JsonObject(note.toMutableMap().apply {
                            this["folder"] = JsonPrimitive(fallback)
                        }) else note
                    })
                    val tasks = snapshot.data["tasks"] as? JsonArray
                    if (tasks != null) state["tasks"] = JsonArray(tasks.map { task ->
                        val obj = task as? JsonObject ?: return rejected("invalid-state", "Task record must be an object")
                        if (obj.text("folderId") == command.folderId) JsonObject(obj.toMutableMap().apply {
                            this["folderId"] = JsonNull
                        }) else obj
                    })
                    if (snapshot.data.text("notepadFolder") == command.folderId) {
                        state["notepadFolder"] = JsonPrimitive(remaining.firstOrNull()?.text("id").orEmpty())
                    }
                    committed(snapshotWith(state))
                }
                else -> rejected("unsupported-command", "NotesReducer does not handle this command")
            }
        } catch (error: Exception) {
            rejected("invalid-state", error.message ?: "State could not be updated")
        }
    }

    private fun readFolders(data: JsonObject): List<JsonObject> = when (val value = data["folders"]) {
        null -> seedFolders
        is JsonArray -> value.mapIndexed { index, item ->
            item as? JsonObject ?: throw IllegalArgumentException("folders[$index] must be an object")
        }
        else -> throw IllegalArgumentException("folders must be an array")
    }

    private fun validNoteFolder(requested: String, folders: List<JsonObject>): String =
        folders.firstOrNull { it.text("id") == requested }?.text("id")
            ?: folders.firstOrNull { it.text("id") == "general" }?.text("id")
            ?: folders.firstOrNull()?.text("id").orEmpty()

    private fun nextNoteId(notes: List<JsonObject>): Long {
        val maxId = notes.mapNotNull { FlowSnapshot.safeId(it["id"], "qnotes.id") }.maxOrNull() ?: 99L
        require(maxId < 9_007_199_254_740_991L) { "No safe quick-note ID remains" }
        return maxOf(100L, maxId + 1L)
    }

    private fun makeNote(id: Long, text: String, folderId: String, at: Long, zone: ZoneId): MutableMap<String, JsonElement> {
        val dateTime = Instant.ofEpochMilli(at).atZone(zone)
        return mutableMapOf(
            "id" to JsonPrimitive(id), "txt" to JsonPrimitive(text), "folder" to JsonPrimitive(folderId),
            "time" to JsonPrimitive(dateTime.format(timeFormat)), "date" to JsonPrimitive(dateTime.format(dateFormat)),
        )
    }

    private fun withActivity(state: MutableMap<String, JsonElement>, at: Long, zone: ZoneId, now: Long): FlowCommandResult {
        val result = Progression.recordActivity(JsonObject(state), at, zone, now, insightCount = 100)
        return FlowCommandResult.Applied(snapshotWith(result.state), result.events)
    }

    private fun committed(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun snapshotWith(state: Map<String, JsonElement>) = FlowSnapshot.fromData(JsonObject(state))
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun folder(id: String, name: String, icon: String) = JsonObject(mapOf(
        "id" to JsonPrimitive(id), "nm" to JsonPrimitive(name), "ico" to JsonPrimitive(icon),
    ))

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}
