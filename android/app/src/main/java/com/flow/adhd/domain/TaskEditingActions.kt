package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import kotlinx.serialization.json.*

/** Authoring actions preserve progress/credit. They never award XP merely for editing or copying. */
object TaskEditingActions {
    private const val MAX_ID = 9_007_199_254_740_991L
    private const val MAX_TIME = 8_640_000_000_000_000L
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long, taskTypes: Set<String>, activeZoneId: Long? = null): FlowCommandResult { return try {
        require(command.activityAt in 0..MAX_TIME)
        if (command is FlowCommand.SaveTaskEditor) submit(snapshot, command, zone, now, taskTypes, activeZoneId)
        else {
            val id = when (command) {
                is FlowCommand.EditChecklistItem -> command.taskId
                is FlowCommand.DeleteChecklistItem -> command.taskId
                is FlowCommand.SetContextTags -> command.taskId
                is FlowCommand.DuplicateTask -> command.taskId
                is FlowCommand.SnoozeTask -> command.taskId
                else -> error("Expected an authoring command")
            }
            val task = snapshot.tasks.firstOrNull { it.id == id }
            if (task == null || task.trashed) applied(snapshot) else when (command) {
                is FlowCommand.DuplicateTask -> duplicate(snapshot, task, command.activityAt)
                is FlowCommand.SnoozeTask -> snooze(snapshot, task, command, zone)
                is FlowCommand.SetContextTags -> {
                    if (task.type != "ctx") rejected("not-context-task", "Task does not have context tags")
                    else replace(snapshot, id, JsonObject(task.raw + ("ctxTags" to JsonArray(command.tags.map { JsonPrimitive(it.trim()) }.filter { it.content.isNotEmpty() }.distinct()))))
                }
                else -> {
                    if (task.type != "check") rejected("not-a-checklist", "Task is not a checklist")
                    else {
                        val items = (task.raw["items"] as? JsonArray).orEmpty().map { it as? JsonObject ?: error("Invalid checklist item") }
                        val changed = when (command) {
                            is FlowCommand.EditChecklistItem -> {
                                val text = command.text.trim()
                                if (text.isEmpty()) return rejected("empty-checklist-item", "Checklist text must not be blank")
                                if (command.index == null) items + buildJsonObject { put("text", text); put("done", false) }
                                else items.mapIndexed { index, item -> if (index == command.index) JsonObject(item + ("text" to JsonPrimitive(text))) else item }
                            }
                            is FlowCommand.DeleteChecklistItem -> items.filterIndexed { index, _ -> index != command.index }
                            else -> error("Expected checklist edit")
                        }
                        replace(snapshot, id, JsonObject(task.raw + ("items" to JsonArray(changed))))
                    }
                }
            }
        }
    } catch (error: Exception) { rejected("invalid-task-edit", error.message ?: "Task could not be edited") } }

    private fun submit(snapshot: FlowSnapshot, command: FlowCommand.SaveTaskEditor, zone: ZoneId, now: Long, taskTypes: Set<String>, activeZoneId: Long?): FlowCommandResult {
        val edit = command.taskId?.let { FlowCommand.UpdateTask(it, command.payload, command.commandId, command.activityAt) }
            ?: FlowCommand.CreateTask(command.payload, command.commandId, command.activityAt)
        val saved = TaskEditorReducer.reduce(snapshot, edit, zone, taskTypes, now, activeZoneId)
        if (saved !is FlowCommandResult.Applied) return saved
        val id = command.taskId ?: saved.snapshot.tasks.first().id
        var next = saved.snapshot
        if (command.payload.text("type") == "ctx" && command.payload["ctxTags"] is JsonArray) {
            val tags = (command.payload["ctxTags"] as JsonArray).map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: error("Invalid context tag") }
            val tagged = reduce(next, FlowCommand.SetContextTags(id, tags, command.commandId, command.activityAt), zone, now, taskTypes)
            if (tagged !is FlowCommandResult.Applied) return tagged
            next = tagged.snapshot
        }
        if (command.postponeMinutes != null || command.tomorrow) {
            val moved = snooze(next, next.tasks.first { it.id == id }, FlowCommand.SnoozeTask(id, command.postponeMinutes, command.tomorrow, command.commandId, command.activityAt), zone)
            if (moved !is FlowCommandResult.Applied) return moved
            next = moved.snapshot
        }
        val data = if (command.taskId == null) JsonObject(next.data + ("taskEditorDraft" to JsonPrimitive(""))) else next.data
        return FlowCommandResult.Applied(FlowSnapshot.fromData(data), saved.events)
    }

    private fun duplicate(snapshot: FlowSnapshot, task: FlowTask, at: Long): FlowCommandResult {
        val id = maxOf(300L, snapshot.data.long("nid") ?: 300L, snapshot.tasks.maxOfOrNull { it.id + 1L } ?: 300L)
        if (id >= MAX_ID) return rejected("id-exhausted", "No safe task ID remains")
        val copy = task.raw.toMutableMap()
        for (key in listOf("completionCreditDate", "doneDate", "doneAt", "occurrenceId", "legacyRoutineId", "questDeferral", "completionDeferral", "zoneOccurrenceKey", "counterDay", "dayPresetKey", "quickReminderAt", "quickReminderFiredAt")) copy.remove(key)
        copy.putAll(mapOf("id" to JsonPrimitive(id), "title" to JsonPrimitive(task.title + " (копія)"), "done" to JsonPrimitive(false),
            "completionCredited" to JsonPrimitive(false), "nextRepeatAt" to JsonPrimitive(0), "snoozeUntil" to JsonPrimitive(0), "expanded" to JsonPrimitive(false),
            "alarmFired" to JsonPrimitive(false), "firedSched" to JsonPrimitive(false), "firedPre" to JsonPrimitive(false),
            "created" to JsonPrimitive(DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(at)))))
        if (task.type == "timewin") copy["completedToday"] = JsonPrimitive(false)
        if (task.type == "zonelinked") copy["zoneDoneToday"] = JsonPrimitive(false)
        if (task.type in setOf("counter", "negative") || task.raw["counterEnabled"] == JsonPrimitive(true)) {
            copy["counter"] = JsonPrimitive(0); copy["cntHit"] = JsonPrimitive(false); copy["counterCreditedProgress"] = JsonPrimitive(0)
        }
        (task.raw["items"] as? JsonArray)?.let { items -> copy["items"] = JsonArray(items.map { value -> JsonObject((value as JsonObject) + mapOf("done" to JsonPrimitive(false), "credited" to JsonPrimitive(false))) }) }
        val index = snapshot.tasks.indexOfFirst { it.id == task.id }
        val tasks = snapshot.tasks.map { it.raw }.toMutableList().apply { add(index, JsonObject(copy)) }
        return applied(FlowSnapshot.fromData(JsonObject(snapshot.data + mapOf("tasks" to JsonArray(tasks), "nid" to JsonPrimitive(id + 1)))))
    }

    private fun snooze(snapshot: FlowSnapshot, task: FlowTask, command: FlowCommand.SnoozeTask, zone: ZoneId): FlowCommandResult {
        val base = command.activityAt
        require(base in 0..MAX_TIME)
        val local = Instant.ofEpochMilli(base).atZone(zone)
        val at = if (command.tomorrow) local.plusDays(1).toInstant().toEpochMilli() else {
            val raw = Math.addExact(base, Math.multiplyExact(command.minutes!!, 60_000L))
            Math.multiplyExact(Math.floorDiv(Math.addExact(raw, 59_999L), 60_000L), 60_000L)
        }
        require(at in 0..MAX_TIME)
        val due = Instant.ofEpochMilli(at).atZone(zone)
        val day = due.toLocalDate().toString()
        val time = if (command.tomorrow) task.raw.text(if (task.type == "alarm") "alarmTime" else "schedTime") else due.format(DateTimeFormatter.ofPattern("HH:mm"))
        val credited = (task.raw["completionCredited"] as? JsonPrimitive)?.booleanOrNull ?: task.done
        val updated = task.raw.toMutableMap()
        for (key in listOf("doneDate", "doneAt", "questDeferral", "completionDeferral")) updated.remove(key)
        updated.putAll(mapOf("planDate" to JsonPrimitive(day), "snoozeUntil" to JsonPrimitive(if (command.tomorrow) 0L else at), "done" to JsonPrimitive(false),
            "completionCredited" to JsonPrimitive(credited), "nextRepeatAt" to JsonPrimitive(0), "firedSched" to JsonPrimitive(false), "firedPre" to JsonPrimitive(false), "alarmFired" to JsonPrimitive(false)))
        if (credited) updated["completionCreditDate"] = JsonPrimitive(task.raw.text("completionCreditDate").ifEmpty { task.raw.text("doneDate").ifEmpty { local.toLocalDate().toString() } })
        if (task.type == "timewin") updated["completedToday"] = JsonPrimitive(false)
        if (task.type == "zonelinked") updated["zoneDoneToday"] = JsonPrimitive(false)
        if (task.type == "sched") { updated["schedDate"] = JsonPrimitive(day); if (time.isNotEmpty()) updated["schedTime"] = JsonPrimitive(time) }
        if (task.type == "alarm" && time.isNotEmpty()) updated["alarmTime"] = JsonPrimitive(time)
        return replace(snapshot, task.id, JsonObject(updated))
    }

    private fun replace(snapshot: FlowSnapshot, id: Long, task: JsonObject) = applied(FlowSnapshot.fromData(JsonObject(snapshot.data + ("tasks" to JsonArray(snapshot.tasks.map { if (it.id == id) task else it.raw })))))
    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}
