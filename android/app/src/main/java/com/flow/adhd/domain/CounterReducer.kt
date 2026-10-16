package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Counter/checklist progress is independent of the separate task completion action. */
object CounterReducer {
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long = command.activityAt): FlowCommandResult {
        val taskId = when (command) {
            is FlowCommand.ChangeCounter -> command.taskId
            is FlowCommand.SetChecklistItem -> command.taskId
            is FlowCommand.RecordNegative -> command.taskId
            else -> return FlowCommandResult.Rejected("unsupported-command", "Unsupported progress command")
        }
        val task = snapshot.tasks.firstOrNull { it.id == taskId }
            ?: return FlowCommandResult.Rejected("task-not-found", "Task does not exist")
        if (task.trashed || task.done) return FlowCommandResult.Applied(snapshot, emptyList())
        val updated = task.raw.toMutableMap()
        val rewards = mutableListOf<Double>()
        val state = snapshot.data.toMutableMap()
        val date = Instant.ofEpochMilli(command.activityAt).atZone(zone).toLocalDate().toString()
        when (command) {
            is FlowCommand.ChangeCounter -> {
                if (task.type == "negative" || (task.type != "counter" && task.raw.flag("counterEnabled") != true)) {
                    return FlowCommandResult.Rejected("not-a-counter", "Task does not have a counter")
                }
                if (command.delta == 0.0) return FlowCommandResult.Applied(snapshot, emptyList())
                val prior = task.raw.number("counter") ?: 0.0
                val next = (prior + command.delta).coerceAtLeast(0.0)
                if (!next.isFinite()) return FlowCommandResult.Rejected("invalid-counter", "Counter overflow")
                val target = (task.raw.number("counterTarget") ?: if (task.type == "counter") 10.0 else 0.0).coerceAtLeast(0.0)
                val credited = task.raw.number("counterCreditedProgress") ?: prior
                val justHit = target > 0 && next >= target && task.raw.flag("cntHit") != true
                updated["counter"] = number(next)
                updated["counterDay"] = JsonPrimitive(date)
                updated["counterCreditedProgress"] = number(maxOf(credited, next))
                if (justHit) updated["cntHit"] = JsonPrimitive(true)
                if (next > credited) rewards.add(2.0)
                if (justHit) rewards.add(15.0)
            }
            is FlowCommand.SetChecklistItem -> {
                if (task.type != "check") return FlowCommandResult.Rejected("not-a-checklist", "Task is not a checklist")
                val items = task.raw["items"] as? JsonArray
                    ?: return FlowCommandResult.Rejected("item-not-found", "Checklist item does not exist")
                val item = items.getOrNull(command.index) as? JsonObject
                    ?: return FlowCommandResult.Rejected("item-not-found", "Checklist item does not exist")
                if (item.flag("done") == command.checked) return FlowCommandResult.Applied(snapshot, emptyList())
                val credit = command.checked && item.flag("credited") != true
                val nextItem = item.toMutableMap().apply {
                    put("done", JsonPrimitive(command.checked))
                    if (credit || item.flag("credited") == true) put("credited", JsonPrimitive(true))
                }
                updated["items"] = JsonArray(items.mapIndexed { index, value -> if (index == command.index) JsonObject(nextItem) else value })
                updated["counterDay"] = JsonPrimitive(date)
                if (credit) rewards.add(3.0)
            }
            is FlowCommand.RecordNegative -> {
                if (task.type != "negative" || command.delta <= 0) return FlowCommandResult.Rejected("invalid-event", "A journal event must increase its counter")
                val next = (task.raw.number("counter") ?: 0.0) + command.delta
                if (!next.isFinite()) return FlowCommandResult.Rejected("invalid-counter", "Counter overflow")
                updated["counter"] = number(next)
                updated["counterDay"] = JsonPrimitive(date)
                state["negCount"] = number((snapshot.data.number("negCount") ?: 0.0) + 1.0)
                rewards.add(-(task.raw.number("negXp") ?: 5.0).coerceAtLeast(0.0))
            }
            else -> error("Checked above")
        }
        state["tasks"] = JsonArray(snapshot.tasks.map { if (it.id == taskId) JsonObject(updated) else it.raw })
        var resultState = JsonObject(state)
        val events = mutableListOf<FlowEvent>()
        if (command is FlowCommand.RecordNegative) {
            val activity = Progression.recordActivity(resultState, command.activityAt, zone, now)
            resultState = activity.state
            events.addAll(activity.events)
        }
        for (reward in rewards) {
            val award = Experience.award(resultState, reward, command.activityAt, zone, now)
            resultState = award.state
            events.addAll(award.events)
        }
        return FlowCommandResult.Applied(FlowSnapshot.fromData(resultState), events)
    }

    private fun number(value: Double): JsonPrimitive =
        if (value % 1.0 == 0.0 && value >= -9_007_199_254_740_991.0 && value <= 9_007_199_254_740_991.0) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    private fun Map<String, JsonElement>.number(key: String): Double? =
        (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.flag(key: String): Boolean? = (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
}
