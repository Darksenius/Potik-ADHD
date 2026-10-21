package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Deferral changes availability, never the task's original date, zone, credit, or history. */
object TaskDeferralReducer {
    fun reduce(
        snapshot: FlowSnapshot, command: FlowCommand.DeferTask, zone: ZoneId,
        zoneOccurrenceKey: (Long, Long) -> String? = { id, at -> ZoneCalendar.occurrenceKey(snapshot.data, id, at, zone) },
    ): FlowCommandResult {
        val task = snapshot.tasks.firstOrNull { it.id == command.taskId }
            ?: return FlowCommandResult.Applied(snapshot, emptyList())
        if (task.trashed || task.done || task.someday) return FlowCommandResult.Applied(snapshot, emptyList())
        if (command.requestedAt !in 0..8_640_000_000_000_000L) {
            return FlowCommandResult.Rejected("invalid-time", "Deferral time is invalid")
        }
        val kind = if (command.kind == QuestDeferralKind.NEXT_ZONE) "next-zone" else "not-today"
        val explicit = command.details?.takeIf {
            QuestPolicy.isValidDeferral(it) && (
                (it["kind"] as? JsonPrimitive)?.content == kind ||
                    (task.type == "sched" && command.kind == QuestDeferralKind.NEXT_ZONE
                        && (it["kind"] as? JsonPrimitive)?.content == "time")
                )
        }
        val deferral = explicit ?: QuestPolicy.createDeferral(task.raw, command.kind,
            QuestPolicy.Context(now = command.requestedAt,
                timeline = ZoneCalendar.timeline(snapshot.data, command.requestedAt, zone),
                zones = snapshot.zones, zone = zone), command.requestedAt)
        val key = task.zoneId?.takeIf { it != 0L }?.let { zoneOccurrenceKey(it, command.requestedAt) }
        val updated = JsonObject(task.raw.toMutableMap().apply {
            put("occurrenceId", JsonPrimitive(TaskHistory.occurrenceId(task.raw, command.requestedAt, key, zone)))
            put("questDeferral", deferral)
            remove("completionDeferral")
            put("snoozeUntil", JsonPrimitive(0))
            put("firedSched", JsonPrimitive(false))
            put("firedPre", JsonPrimitive(false))
            put("alarmFired", JsonPrimitive(false))
        })
        if (updated == task.raw) return FlowCommandResult.Applied(snapshot, emptyList())
        val state = FlowSnapshot.fromData(JsonObject(snapshot.data + ("tasks" to JsonArray(
            snapshot.tasks.map { if (it.id == task.id) updated else it.raw },
        ))))
        return FlowCommandResult.Applied(state, listOf(FlowEvent("task_deferred", command.activityAt,
            JsonObject(mapOf("taskId" to JsonPrimitive(task.id), "deferral" to deferral)))))
    }
}
