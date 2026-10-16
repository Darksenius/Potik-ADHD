package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.*

/** Decodes the existing Android queue. The repository advances to 'now' after the whole FIFO batch. */
object NotificationEventReducer {
    private const val MAX_TIME = 8_640_000_000_000_000L
    private val taskAction = Regex("^task_(done|inc|dec|skip|defer):(\\d+)(?::([\\s\\S]*))?$")

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand.ApplyNotificationEvent, zone: ZoneId, now: Long, engine: FlowEngine): FlowCommandResult = try {
        if (command.activityAt !in 0..MAX_TIME || now !in 0..MAX_TIME) reject("Event timestamp is invalid")
        else decode(snapshot, command, zone, now, engine)
    } catch (error: Exception) { reject(error.message ?: "Notification event is invalid") }

    private fun decode(source: FlowSnapshot, event: FlowCommand.ApplyNotificationEvent, zone: ZoneId, now: Long, engine: FlowEngine): FlowCommandResult {
        val raw = event.event
        val at = event.activityAt
        val id = event.commandId
        fun apply(state: FlowSnapshot, command: FlowCommand) = engine.apply(state, command, zone, now)
        fun ignore(@Suppress("UNUSED_PARAMETER") reason: String) = FlowCommandResult.Applied(source, emptyList())
        fun navigate(type: String, payload: JsonObject) = FlowCommandResult.Applied(source, listOf(FlowEvent(type, at, payload)))
        if (raw == "daily_reset") return ignore("lifecycle-on-batch-commit")
        if (raw == "done_first" || raw.startsWith("rc_")) return ignore("retired-action")
        if (raw.startsWith("task_remind:")) return ignore("refresh-only")
        if (raw.startsWith("open_insight:")) {
            val day = raw.removePrefix("open_insight:")
            if (!day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) || runCatching { LocalDate.parse(day) }.isFailure) return reject("Insight date is invalid")
            return navigate("navigate_insight", buildJsonObject { put("day", day) })
        }
        if (raw.startsWith("open_task:")) {
            val taskId = parseId(raw.removePrefix("open_task:")) ?: return reject("Task ID is invalid")
            val task = source.tasks.firstOrNull { it.id == taskId && !it.trashed && belongs(it, at) } ?: return ignore("task-no-longer-available")
            return navigate("navigate_task", buildJsonObject { put("taskId", task.id) })
        }
        if (raw.startsWith("capture_task_v2:")) {
            val body = raw.removePrefix("capture_task_v2:")
            val separator = body.indexOf(':')
            if (separator < 1) return reject("Capture envelope is invalid")
            val taskId = parseId(body.substring(0, separator))?.takeIf { it > 0 && it < 9_007_199_254_740_991L } ?: return reject("Capture ID is invalid")
            val payload = Json.parseToJsonElement(body.substring(separator + 1)) as? JsonObject ?: return reject("Capture payload is invalid")
            val title = payload.text("title")?.trim()?.takeIf { it.isNotEmpty() } ?: return reject("Capture title is empty")
            val createdAt = payload.epoch("createdAt") ?: return reject("Capture timestamp is invalid")
            if (kotlin.math.abs(createdAt - at) > 60_000) return reject("Capture timestamp does not match its envelope")
            return apply(source, FlowCommand.CaptureTask(title, "$id:capture", createdAt, CaptureOrigin.NOTIFICATION, taskId))
        }
        if (raw.startsWith("capture_task:")) return apply(source,
            FlowCommand.CaptureTask(raw.removePrefix("capture_task:").trim(), "$id:capture", at, CaptureOrigin.NOTIFICATION))
        if (raw.startsWith("capture_note:") || raw.startsWith("note:")) {
            val text = raw.substringAfter(':').trim()
            val result = apply(source, FlowCommand.CaptureNote(text, "$id:note", at))
            if (result !is FlowCommandResult.Applied) return result
            val noteId = result.snapshot.qnotes.firstOrNull()?.get("id") ?: JsonNull
            return result.copy(events = result.events + FlowEvent("note_review_requested", at, buildJsonObject {
                put("noteId", noteId); put("dueAt", minOf(MAX_TIME, now + 300_000))
            }))
        }
        if (raw.startsWith("share_text:")) {
            val text = raw.removePrefix("share_text:").trim().take(12_000)
            if (text.isEmpty()) return reject("Shared text is empty")
            val folders = (source.data["folders"] as? JsonArray)?.map { it as? JsonObject ?: error("Invalid folder") }.orEmpty()
            val folder = folders.firstOrNull { it.text("id") == "thought" }?.text("id") ?: folders.firstOrNull()?.text("id") ?: ""
            val result = apply(source, FlowCommand.AddQuickNote(buildJsonObject { put("txt", text); put("folder", folder) }, "$id:share", at))
            if (result !is FlowCommandResult.Applied) return result
            return result.copy(events = result.events + FlowEvent("navigate_note", at, buildJsonObject {
                put("noteId", result.snapshot.qnotes.first().getValue("id"))
            }))
        }
        if (raw.startsWith("task_undo:")) {
            val payload = Json.parseToJsonElement(raw.removePrefix("task_undo:")) as? JsonObject ?: return reject("Undo payload is invalid")
            val taskId = FlowSnapshot.safeId(payload["id"], "undo.id") ?: return reject("Undo task ID is missing")
            return apply(source, FlowCommand.UndoTask(taskId, "$id:undo", at, nativePayload = payload))
        }
        if (raw.startsWith("sched_fired:")) {
            val match = Regex("^sched_fired:(\\d+)(_pre)?:(\\d+)$").matchEntire(raw) ?: return reject("Reminder receipt is invalid")
            val taskId = parseId(match.groupValues[1]) ?: return reject("Reminder task ID is invalid")
            val delivered = match.groupValues[3].toLongOrNull()?.takeIf { it in 1..MAX_TIME } ?: return reject("Reminder timestamp is invalid")
            val task = source.tasks.firstOrNull { it.id == taskId } ?: return ignore("task-no-longer-exists")
            if (!belongs(task, delivered)) return ignore("task-id-reused")
            if (task.raw.text("repeat") == "everyzone") {
                val savedOrder = occurrenceOrder(task.raw.text("zoneOccurrenceKey"))
                val deliveredOrder = occurrenceOrder(task.zoneId?.let { ZoneCalendar.occurrenceKey(source.data, it, delivered, zone) })
                if (savedOrder != null && deliveredOrder != null && deliveredOrder < savedOrder) return ignore("older-zone-occurrence")
            }
            val prepared = prepareTarget(source, task, delivered, zone)
            return apply(prepared, FlowCommand.MarkTaskReminderFired(taskId, delivered, match.groupValues[2].isNotEmpty(), "$id:reminder", at))
        }
        val match = taskAction.matchEntire(raw) ?: return reject("Unknown notification action")
        val action = match.groupValues[1]
        val taskId = parseId(match.groupValues[2]) ?: return reject("Task ID is invalid")
        if (action != "defer" && match.groups[3] != null) return reject("Unexpected task action payload")
        val original = source.tasks.firstOrNull { it.id == taskId } ?: return ignore("task-no-longer-exists")
        if (!belongs(original, at)) return ignore("task-id-reused")
        if (original.trashed || original.type == "negative") return ignore("task-no-longer-available")
        if (action == "done" && original.done && original.raw.text("repeat") == "interval"
            && (original.raw.number("nextRepeatAt") ?: 0.0) > at) return ignore("interval-already-completed")
        val stale = localDay(at, zone) != localDay(now, zone)
        val repeat = original.raw.text("repeat")
        val resetsByDay = !repeat.isNullOrEmpty() && repeat !in setOf("none", "interval")
        val carried = (original.raw["questDeferral"] as? JsonObject)?.number("requestedAt")?.let { at >= it } == true
        if (stale && resetsByDay && !carried && action in setOf("done", "inc", "dec")) {
            if (action == "done") return apply(source, FlowCommand.RecordPastTaskDone(taskId, at, "$id:past", at))
            return apply(source, FlowCommand.AddQuickNote(buildJsonObject {
                put("txt", "Дія зі сповіщення за ${localDay(at, zone)}: $raw · ${original.title}. Перевір і застосуй вручну: день уже змінився.")
                put("folder", "impulse")
            }, "$id:past-counter", at))
        }
        val key = original.zoneId?.let { ZoneCalendar.occurrenceKey(source.data, it, at, zone) }
        if (original.raw.text("repeat") == "everyzone") {
            val savedOrder = occurrenceOrder(original.raw.text("zoneOccurrenceKey"))
            val tappedOrder = occurrenceOrder(key)
            if (savedOrder != null && tappedOrder != null && tappedOrder < savedOrder) return ignore("older-zone-occurrence")
        }
        // Advance to the tap's occurrence, never to the processing day before older FIFO actions.
        val state = prepareTarget(source, original, at, zone)
        val task = state.tasks.first { it.id == taskId }
        if (task.done) return ignore("already-completed")
        return when (action) {
            "done" -> apply(state, FlowCommand.SetTaskDone(taskId, true, at, "$id:done", at))
            "inc", "dec" -> {
                if (task.someday || (task.type != "counter" && task.raw["counterEnabled"] != JsonPrimitive(true))) return ignore("not-an-active-counter")
                val step = task.raw.number("counterStep")?.takeIf { it > 0 } ?: 1.0
                apply(state, FlowCommand.ChangeCounter(taskId, if (action == "inc") step else -step, "$id:counter", at))
            }
            "skip" -> apply(state, FlowCommand.DeferTask(taskId, QuestDeferralKind.NEXT_ZONE, at, null, "$id:skip", at))
            "defer" -> {
                val details = Json.parseToJsonElement(match.groupValues[3]) as? JsonObject ?: return reject("Deferral payload is invalid")
                if (!QuestPolicy.isValidDeferral(details)) return reject("Deferral payload is invalid")
                val requested = details.epoch("requestedAt") ?: return reject("Deferral timestamp is invalid")
                if (kotlin.math.abs(requested - at) > 60_000 || (details.number("startAt") ?: -1.0) < requested) return reject("Deferral timestamp does not match its envelope")
                val kind = if (details.text("kind") == "next-zone") QuestDeferralKind.NEXT_ZONE else QuestDeferralKind.NOT_TODAY
                apply(state, FlowCommand.DeferTask(taskId, kind, requested, details, "$id:defer", at))
            }
            else -> reject("Unknown notification action")
        }
    }

    private fun prepareTarget(source: FlowSnapshot, task: FlowTask, at: Long, zone: ZoneId): FlowSnapshot {
        // Other queued tasks may carry deferrals not applied yet. Reset only this target here.
        var target = FlowSnapshot.fromData(JsonObject(source.data + ("tasks" to JsonArray(listOf(task.raw)))))
        target = TaskLifecycle.dailyReset(target, source.data.text("saveDate"), at, zone)
        target = TaskLifecycle.resetRepeating(target, at, zone)
        target = TaskLifecycle.checkZoneRepeats(target, at, zone, { zoneId, whenAt ->
            ZoneCalendar.occurrenceKey(source.data, zoneId, whenAt, zone)
        }, task.id)
        val state = source.data.toMutableMap()
        state["tasks"] = JsonArray(source.tasks.map { if (it.id == task.id) target.tasks.single().raw else it.raw })
        target.data["taskHistory"]?.let { state["taskHistory"] = it }
        return FlowSnapshot.fromData(JsonObject(state))
    }

    private fun belongs(task: FlowTask, at: Long): Boolean = task.raw.text("created")
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }?.let { it <= at } ?: true
    private fun occurrenceOrder(value: String?): String? = value?.let { Regex("^(\\d{4}-\\d{2}-\\d{2}):\\d+:(\\d{2}):(\\d{2})$").matchEntire(it) }
        ?.let { "${it.groupValues[1]}T${it.groupValues[2]}:${it.groupValues[3]}" }
    private fun parseId(value: String): Long? = value.takeIf { it.matches(Regex("\\d+")) }?.toLongOrNull()?.takeIf { it in 0..9_007_199_254_740_991L }
    private fun localDay(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.epoch(key: String) = number(key)?.takeIf { it in 0.0..MAX_TIME.toDouble() && it % 1.0 == 0.0 }?.toLong()
    private fun reject(message: String) = FlowCommandResult.Rejected("invalid-notification-event", message)
}
