package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Pure counterpart of the browser undo receipt and notification action. */
object TaskUndo {
    private const val WINDOW_MS = 10_000L
    private val GLOBAL_KEYS = listOf("xp", "xpTotal", "level", "done", "dayXpGained", "xpDay", "unlocked", "priorities")

    fun offer(before: FlowSnapshot, result: FlowCommandResult.Applied, kind: String, taskId: Long, sourceAt: Long): FlowCommandResult.Applied {
        if (kind !in setOf("done", "defer") || sourceAt < 0 || sourceAt > Long.MAX_VALUE - WINDOW_MS) return result
        val old = before.tasks.firstOrNull { it.id == taskId }?.raw ?: return result
        val after = result.snapshot.tasks.firstOrNull { it.id == taskId }?.raw ?: return result
        if (old == after || old.string("created") == null || old.string("created") != after.string("created")) return result
        val receipt = JsonObject(mapOf(
            "beforeTask" to old,
            "afterTask" to after,
            "beforeGlobals" to globals(before.data),
            "afterGlobals" to globals(result.snapshot.data),
            "kind" to JsonPrimitive(kind),
            "sourceAt" to JsonPrimitive(sourceAt),
            "expiresAt" to JsonPrimitive(sourceAt + WINDOW_MS),
        ))
        return result.copy(undoReceipt = receipt)
    }

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand.UndoTask, zone: ZoneId, now: Long): FlowCommandResult {
        val receipt = command.receipt
        if (receipt != null) return restoreReceipt(snapshot, command, receipt, zone)
        return reduceNative(snapshot, command, zone, now)
    }

    private fun restoreReceipt(snapshot: FlowSnapshot, command: FlowCommand.UndoTask, receipt: JsonObject, zone: ZoneId): FlowCommandResult {
        val before = receipt.obj("beforeTask") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val after = receipt.obj("afterTask") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val beforeGlobals = receipt.obj("beforeGlobals") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val afterGlobals = receipt.obj("afterGlobals") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val kind = receipt.string("kind") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val sourceAt = receipt.long("sourceAt") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        val expiresAt = receipt.long("expiresAt") ?: return rejected("invalid-undo", "Undo receipt is invalid")
        if (kind !in setOf("done", "defer") || sourceAt < 0 || sourceAt > Long.MAX_VALUE - WINDOW_MS
            || expiresAt != sourceAt + WINDOW_MS || command.activityAt !in sourceAt..expiresAt
            || before.long("id") != command.taskId || after.long("id") != command.taskId
            || before.string("created") == null || before.string("created") != after.string("created")) return rejected("invalid-undo", "Undo receipt is invalid or expired")
        val current = snapshot.tasks.firstOrNull { it.id == command.taskId }?.raw
            ?: return rejected("stale-undo", "Task changed after the action")
        if (current != after) return rejected("stale-undo", "Task changed after the action")
        val changed = GLOBAL_KEYS.filter { beforeGlobals[it] != afterGlobals[it] }
        if (changed.any { snapshot.data[it] != afterGlobals[it] }) return rejected("stale-undo", "Progress changed after the action")
        if (kind == "done") {
            val occurrence = after.string("occurrenceId") ?: return rejected("invalid-undo", "Undo receipt is invalid")
            val history = (snapshot.data["taskHistory"] as? JsonArray) ?: JsonArray(emptyList())
            val latest = history.lastOrNull { it.obj()?.string("occurrenceId") == occurrence }?.obj()
            if (latest?.string("kind") != "completed" || latest?.long("at") != sourceAt) {
                return rejected("stale-undo", "Completion history changed after the action")
            }
        } else if (after.obj("questDeferral")?.long("requestedAt") != sourceAt) {
            return rejected("stale-undo", "Deferral history changed after the action")
        }
        val state = snapshot.data.toMutableMap()
        changed.forEach { key ->
            val value = beforeGlobals[key]
            if (value == null) state.remove(key) else state[key] = value
        }
        state["tasks"] = JsonArray(snapshot.tasks.map { task -> if (task.id == command.taskId) before else task.raw })
        if (kind == "done" && after["occurrenceId"] != null) {
            val at = command.activityAt
            val history = (snapshot.data["taskHistory"] as? JsonArray) ?: JsonArray(emptyList())
            val seq = TaskHistory.nextSequence(history)
            val occurrence = (after["occurrenceId"] as? JsonPrimitive)?.content ?: return rejected("invalid-undo", "Undo receipt is invalid")
            state["taskHistory"] = JsonArray(history + TaskHistory.event(after, "reopened", at, occurrence, seq, zone))
        }
        val checked = Progression.checkAchievements(JsonObject(state), command.activityAt)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(checked.state), checked.events)
    }

    private fun reduceNative(snapshot: FlowSnapshot, command: FlowCommand.UndoTask, zone: ZoneId, now: Long): FlowCommandResult {
        val payload = command.nativePayload ?: return rejected("invalid-undo", "Undo payload is missing")
        val id = payload.long("id") ?: return rejected("invalid-undo", "Undo payload is invalid")
        val created = payload.string("created") ?: return rejected("invalid-undo", "Undo payload is invalid")
        val kind = payload.string("kind") ?: return rejected("invalid-undo", "Undo payload is invalid")
        val sourceAt = payload.long("sourceAt") ?: return rejected("invalid-undo", "Undo payload is invalid")
        val undoneAt = payload.long("undoneAt") ?: return rejected("invalid-undo", "Undo payload is invalid")
        val before = payload.obj("before") ?: return rejected("invalid-undo", "Undo payload is invalid")
        if (id != command.taskId || kind !in setOf("done", "defer") || sourceAt <= 0 || undoneAt < sourceAt || undoneAt - sourceAt > WINDOW_MS
            || command.activityAt != undoneAt || now < undoneAt) return rejected("invalid-undo", "Undo payload is invalid or expired")
        val task = snapshot.tasks.firstOrNull { it.id == id && it.raw.string("created") == created }
            ?: return rejected("stale-undo", "Task changed after the action")
        if (task.trashed) return rejected("stale-undo", "Task is no longer available")
        if (!validBefore(before, kind)) return rejected("invalid-undo", "Undo task data is invalid")
        val history = (snapshot.data["taskHistory"] as? JsonArray) ?: JsonArray(emptyList())
        val completed = if (kind == "done") history.lastOrNull { it.obj()?.let { e -> e.long("taskId") == id && e.string("taskCreated") == created && e.string("kind") == "completed" && e.long("at") == sourceAt } == true }?.obj()
            ?: return rejected("stale-undo", "Completion history changed") else null
        if (kind == "defer" && (task.done || task.raw.obj("questDeferral")?.long("requestedAt") != sourceAt)) return rejected("stale-undo", "Deferral changed after the action")
        var updatedHistory = history
        if (completed != null) {
            val occurrence = completed.string("occurrenceId") ?: return rejected("invalid-undo", "Completion history is invalid")
            val last = history.lastOrNull { it.obj()?.string("occurrenceId") == occurrence }?.obj()
            if (last != completed) return rejected("stale-undo", "Completion history changed")
            val event = JsonObject(completed.toMutableMap().apply {
                put("kind", JsonPrimitive("reopened"))
                put("at", JsonPrimitive(undoneAt))
                put("sequence", JsonPrimitive(TaskHistory.nextSequence(history)))
                put("id", JsonPrimitive("$occurrence#undo:$undoneAt"))
            })
            updatedHistory = JsonArray(history + event)
        }
        val sourceDay = try { Instant.ofEpochMilli(sourceAt).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE) } catch (_: Exception) { null }
        val delayedPastOccurrence = kind == "done" && (!task.done || task.raw.string("doneDate") != sourceDay
            || task.raw.string("occurrenceId") != completed?.string("occurrenceId"))
        val tasks = if (delayedPastOccurrence) snapshot.tasks.map { it.raw }
        else snapshot.tasks.map { if (it.id == id) restoreFields(task.raw, before) else it.raw }
        val state = snapshot.data.toMutableMap().apply { put("tasks", JsonArray(tasks)); put("taskHistory", updatedHistory) }
        return applied(state)
    }

    private fun validBefore(before: JsonObject, kind: String): Boolean {
        if (kind == "done" && before["done"] != JsonPrimitive(false)) return false
        for (key in listOf("questDeferral", "completionDeferral")) if (before[key] != null && before[key] != JsonNull && !QuestPolicy.isValidDeferral(before[key])) return false
        for (key in listOf("done", "firedSched", "firedPre", "alarmFired", "completedToday", "zoneDoneToday")) {
            val value = before[key]; if (value != null && value != JsonNull && (value as? JsonPrimitive)?.booleanOrNull == null) return false
        }
        for (key in listOf("snoozeUntil", "nextRepeatAt")) {
            val value = before[key]; if (value != null && value != JsonNull && (value as? JsonPrimitive)?.let { !it.isString && it.longOrNull != null } != true) return false
        }
        for (key in listOf("doneDate", "doneAt", "occurrenceId")) {
            val value = before[key]; if (value != null && value != JsonNull && (value as? JsonPrimitive)?.isString != true) return false
        }
        return true
    }

    private fun restoreFields(task: JsonObject, before: JsonObject): JsonObject {
        val fields = listOf("done", "doneDate", "doneAt", "questDeferral", "completionDeferral", "snoozeUntil", "firedSched", "firedPre", "alarmFired", "completedToday", "zoneDoneToday", "nextRepeatAt", "occurrenceId")
        val output = task.toMutableMap()
        fields.forEach { key -> val value = before[key]; if (value == null || value == JsonNull) output.remove(key) else output[key] = value }
        return JsonObject(output)
    }

    private fun globals(data: JsonObject) = JsonObject(GLOBAL_KEYS.mapNotNull { key -> data[key]?.let { key to it } }.toMap())
    private fun applied(data: Map<String, JsonElement>) = try { FlowCommandResult.Applied(FlowSnapshot.fromData(JsonObject(data)), emptyList()) } catch (e: Exception) { rejected("invalid-undo", e.message ?: "Undo could not be applied") }
    private fun rejected(code: String, message: String): FlowCommandResult.Rejected = FlowCommandResult.Rejected(code, message)
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
