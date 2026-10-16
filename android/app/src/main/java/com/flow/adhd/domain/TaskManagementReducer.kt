package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Task lifecycle and day-priority commands, preserving the existing snapshot format. */
object TaskManagementReducer {
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long): FlowCommandResult {
        val state = snapshot.data.toMutableMap()
        val tasks = snapshot.data.array("tasks")
        val history = snapshot.data.array("taskHistory")
        val priorities = snapshot.data.array("priorities")
        val prioritySlots = (0 until maxOf(3, priorities.size)).map { priorities.getOrNull(it) ?: JsonNull }
        val taskId = when (command) {
            is FlowCommand.MoveTaskToSomeday -> command.taskId
            is FlowCommand.RestoreTaskFromSomeday -> command.taskId
            is FlowCommand.TrashTask -> command.taskId
            is FlowCommand.RestoreTrashedTask -> command.taskId
            is FlowCommand.HardDeleteTask -> command.taskId
            is FlowCommand.SetPriority -> command.taskId
            else -> return FlowCommandResult.Rejected("not-migrated", "This command has not been migrated yet")
        }
        val task = taskId?.let { id -> tasks.firstOrNull { (it as? JsonObject)?.long("id") == id } as? JsonObject }
        if (taskId != null && task == null && command !is FlowCommand.HardDeleteTask && command !is FlowCommand.SetPriority) return applied(snapshot)

        when (command) {
            is FlowCommand.MoveTaskToSomeday -> {
                val target = task!!
                state["tasks"] = JsonArray(tasks.map { item -> if (item === target) JsonObject(target.toMutableMap().apply {
                    put("someday", JsonPrimitive(true)); put("done", JsonPrimitive(false))
                    remove("questDeferral"); remove("completionDeferral")
                    val credited = target.flag("completionCredited") ?: target.flag("done") ?: false
                    put("completionCredited", JsonPrimitive(credited))
                    if (credited) {
                        val creditDate = target.text("completionCreditDate")?.takeIf { it.isNotEmpty() }
                            ?: target.text("doneDate")?.takeIf { it.isNotEmpty() }
                            ?: day(now, zone)
                        put("completionCreditDate", JsonPrimitive(creditDate))
                    }
                    remove("doneDate"); remove("doneAt"); put("nextRepeatAt", JsonPrimitive(0))
                    if (target.text("type") == "timewin") put("completedToday", JsonPrimitive(false))
                    if (target.text("type") == "zonelinked") put("zoneDoneToday", JsonPrimitive(false))
                }) else item })
                state["priorities"] = JsonArray(priorities.map { if (it.long() == command.taskId) JsonNull else it })
            }
            is FlowCommand.RestoreTaskFromSomeday -> state["tasks"] = mapTask(tasks, task!!) { put("someday", JsonPrimitive(false)) }
            is FlowCommand.TrashTask -> {
                state["taskHistory"] = TaskHistory.seedKnown(history, tasks, zone)
                state["tasks"] = mapTask(tasks, task!!) { put("trashed", JsonPrimitive(true)); put("trashedAt", JsonPrimitive(now)) }
                state["priorities"] = clearPriority(priorities, command.taskId)
            }
            is FlowCommand.RestoreTrashedTask -> state["tasks"] = mapTask(tasks, task!!) { put("trashed", JsonPrimitive(false)); put("trashedAt", JsonNull) }
            is FlowCommand.HardDeleteTask -> {
                state["taskHistory"] = TaskHistory.seedKnown(history, tasks, zone)
                state["tasks"] = JsonArray(tasks.filterNot { (it as? JsonObject)?.long("id") == command.taskId })
                state["priorities"] = clearPriority(priorities, command.taskId)
            }
            is FlowCommand.SetPriority -> {
                if (command.slot !in prioritySlots.indices) return applied(snapshot)
                if (command.taskId == null) {
                    state["priorities"] = JsonArray(prioritySlots.mapIndexed { index, item -> if (index == command.slot) JsonNull else item })
                    return appliedIfChanged(snapshot, state)
                }
                val candidate = task ?: return applied(snapshot)
                if (!TaskSchedule.canPrioritize(candidate, ProjectionContext(now, ZoneCalendar.timeline(snapshot.data, now, zone), zone))) return applied(snapshot)
                state["priorities"] = JsonArray(prioritySlots.map { if (it.long() == command.taskId) JsonNull else it }.mapIndexed { index, item ->
                    if (index == command.slot) JsonPrimitive(command.taskId) else item
                })
            }
            else -> Unit
        }
        return appliedIfChanged(snapshot, state)
    }

    private fun mapTask(tasks: JsonArray, target: JsonObject, update: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
        JsonArray(tasks.map { if (it === target) JsonObject(target.toMutableMap().apply(update)) else it })

    private fun clearPriority(priorities: JsonArray, id: Long) = JsonArray(priorities.map { if (it.long() == id) JsonNull else it })
    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun appliedIfChanged(snapshot: FlowSnapshot, state: Map<String, kotlinx.serialization.json.JsonElement>): FlowCommandResult.Applied {
        val result = JsonObject(state)
        return applied(if (result == snapshot.data) snapshot else FlowSnapshot.fromData(result))
    }
    private fun day(at: Long, zone: ZoneId) = java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun kotlinx.serialization.json.JsonElement?.long() = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
}
