package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Native actions that must remain correct when an event outlives its original day. */
object NotificationTaskActions {
    private const val MAX_TIME = 8_640_000_000_000_000L

    fun reduce(
        snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long,
        taskXp: Map<String, Double>, occurrenceKey: (Long, Long) -> String?,
    ): FlowCommandResult = try {
        if (now !in 0..MAX_TIME || command.activityAt !in 0..MAX_TIME) rejected("invalid-time", "Activity time is invalid")
        else when (command) {
            is FlowCommand.CaptureNote -> captureNote(snapshot, command, zone, now)
            is FlowCommand.RecordPastTaskDone -> recordPast(snapshot, command, zone, now, taskXp, occurrenceKey)
            is FlowCommand.MarkTaskReminderFired -> markReminder(snapshot, command, zone)
            else -> rejected("wrong-command", "Expected a notification task action")
        }
    } catch (error: Exception) {
        rejected("invalid-notification-action", error.message ?: "Notification action is invalid")
    }

    private fun captureNote(snapshot: FlowSnapshot, command: FlowCommand.CaptureNote, zone: ZoneId, now: Long): FlowCommandResult {
        val text = command.text.trim()
        if (text.isEmpty()) return rejected("empty-note", "Note text must not be blank")
        val result = NotesReducer.reduce(snapshot, FlowCommand.AddQuickNote(buildJsonObject {
            put("txt", text); put("folder", "impulse")
        }, command.commandId, command.activityAt), zone, now)
        if (result !is FlowCommandResult.Applied) return result
        val award = Experience.award(result.snapshot.data, 1.0, command.activityAt, zone, now)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(award.state), result.events + award.events)
    }

    private fun recordPast(
        snapshot: FlowSnapshot, command: FlowCommand.RecordPastTaskDone, zone: ZoneId, now: Long,
        taskXp: Map<String, Double>, occurrenceKey: (Long, Long) -> String?,
    ): FlowCommandResult {
        val at = command.completedAt
        if (at !in 0..MAX_TIME) return rejected("invalid-time", "Completion time is invalid")
        val task = snapshot.tasks.firstOrNull { it.id == command.taskId } ?: return applied(snapshot)
        if (task.type == "negative") return applied(snapshot)
        val created = task.raw.text("created")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        if (created != null && created > at) return applied(snapshot)
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
        val key = task.zoneId?.takeIf { it != 0L }?.let { occurrenceKey(it, at) }
        val oldOccurrence = task.raw.toMutableMap().apply {
            remove("occurrenceId"); remove("questDeferral"); remove("counter"); remove("items")
            put("counterDay", JsonPrimitive(day)); put("planDate", JsonPrimitive(day))
            if (key == null) remove("zoneOccurrenceKey") else put("zoneOccurrenceKey", JsonPrimitive(key))
        }.let(::JsonObject)
        val identity = TaskHistory.occurrenceId(oldOccurrence, at, key, zone)
        val history = snapshot.data["taskHistory"].let { value ->
            if (value != null && value !is JsonArray) throw IllegalArgumentException("taskHistory must be an array")
            TaskHistory.seedKnown(value as? JsonArray ?: JsonArray(emptyList()), JsonArray(snapshot.tasks.map { it.raw }), zone)
        }
        for (value in history) {
            val event = value as? JsonObject ?: throw IllegalArgumentException("taskHistory must contain objects")
            if (event.text("occurrenceId") == identity && (event.text("kind") == "completed" || (event.number("at") ?: 0.0) >= at))
                return applied(snapshot)
        }
        val event = TaskHistory.event(oldOccurrence, "completed", at, identity, TaskHistory.nextSequence(history), zone)
        val state = JsonObject(snapshot.data + mapOf(
            "taskHistory" to JsonArray(history + event),
            "done" to number((snapshot.data.number("done") ?: 0.0) + 1.0),
        ))
        val activity = Progression.recordActivity(state, at, zone, now)
        val award = Experience.award(activity.state, taskXp[task.type] ?: 10.0, at, zone, now)
        // The live task and today's counter/checklist/priority never change for a past tap.
        return FlowCommandResult.Applied(FlowSnapshot.fromData(award.state), activity.events + award.events)
    }

    private fun markReminder(snapshot: FlowSnapshot, command: FlowCommand.MarkTaskReminderFired, zone: ZoneId): FlowCommandResult {
        val at = command.reminderAt
        if (at !in 1..MAX_TIME) return rejected("invalid-time", "Reminder time is invalid")
        val task = snapshot.tasks.firstOrNull { it.id == command.taskId } ?: return applied(snapshot)
        if (!command.preReminder && task.type == "simple") {
            val quickAt = task.raw.long("quickReminderAt") ?: return applied(snapshot)
            if (quickAt != at || task.raw.long("quickReminderFiredAt") == quickAt) return applied(snapshot)
            val changed = JsonObject(task.raw + ("quickReminderFiredAt" to JsonPrimitive(quickAt)))
            return applied(FlowSnapshot.fromData(JsonObject(snapshot.data + ("tasks" to JsonArray(snapshot.tasks.map {
                if (it.id == task.id) changed else it.raw
            })))))
        }
        val deferredAt = (task.raw["questDeferral"] as? JsonObject)?.number("requestedAt")?.toLong() ?: at
        val timeline = ZoneCalendar.timeline(snapshot.data, deferredAt, zone)
        val due = TaskSchedule.nextReminderAt(task.raw, ProjectionContext(at, timeline, zone)) ?: return applied(snapshot)
        val expected = if (command.preReminder) TaskSchedule.preReminderAt(task.raw, due) else due
        if (expected != at) return applied(snapshot)
        val flags = if (command.preReminder) mapOf("firedPre" to JsonPrimitive(true))
            else mapOf("firedSched" to JsonPrimitive(true), "alarmFired" to JsonPrimitive(true))
        val changed = JsonObject(task.raw + flags)
        if (changed == task.raw) return applied(snapshot)
        return applied(FlowSnapshot.fromData(JsonObject(snapshot.data + ("tasks" to JsonArray(snapshot.tasks.map {
            if (it.id == task.id) changed else it.raw
        })))))
    }

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun number(value: Double) = if (value % 1.0 == 0.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}
