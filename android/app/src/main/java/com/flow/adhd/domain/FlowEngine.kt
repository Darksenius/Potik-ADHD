package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Pure command dispatch. Storage and command deduplication belong to the N3 repository. */
class FlowEngine(private val taskXp: Map<String, Double>) {
    fun apply(
        snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long,
        zoneOccurrenceKey: (Long, Long) -> String? = { _, _ -> null },
        activeZoneId: Long? = null,
    ): FlowCommandResult = when (command) {
        is FlowCommand.AddQuickNote, is FlowCommand.UpdateQuickNote, is FlowCommand.DeleteQuickNote,
        is FlowCommand.SetNotepad, is FlowCommand.SetNotepadFolder, is FlowCommand.SaveNotepadAsNote,
        is FlowCommand.CreateNoteFolder, is FlowCommand.DeleteNoteFolder -> NotesReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.ChangeCounter, is FlowCommand.SetChecklistItem, is FlowCommand.RecordNegative ->
            CounterReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.SetTaskDone -> TaskCompletionReducer.reduce(snapshot, command, zone, taskXp, now, zoneOccurrenceKey)
        is FlowCommand.SetEnergy -> Experience.setEnergy(snapshot.data, command.value, command.activityAt, zone, now)
            .let { FlowCommandResult.Applied(FlowSnapshot.fromData(it.state), it.events) }
        is FlowCommand.CaptureTask -> capture(snapshot, command, zone, now)
        is FlowCommand.CreateTask, is FlowCommand.UpdateTask -> TaskEditorReducer.reduce(snapshot, command, zone, taskXp.keys, now, activeZoneId)
        is FlowCommand.LifecycleTick -> TaskLifecycle.tick(snapshot, now, zone, zoneOccurrenceKey)
        else -> FlowCommandResult.Rejected("not-migrated", "This command has not been migrated yet")
    }

    private fun capture(snapshot: FlowSnapshot, command: FlowCommand.CaptureTask, zone: ZoneId, now: Long): FlowCommandResult {
        val title = command.title.trim()
        if (title.isEmpty()) return FlowCommandResult.Rejected("empty-title", "Task title must not be blank")
        val next = maxOf(300L, snapshot.tasks.maxOfOrNull { it.id + 1L } ?: 300L,
            (snapshot.data["nid"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: 300L)
        if (next >= 9_007_199_254_740_991L) return FlowCommandResult.Rejected("id-exhausted", "No safe task ID remains")
        val task = TaskFactory.create(next, title, "simple", command.activityAt)
        val data = snapshot.data.toMutableMap().apply {
            put("tasks", JsonArray(listOf(task) + snapshot.tasks.map { it.raw }))
            put("nid", JsonPrimitive(next + 1L))
        }
        val activity = Progression.recordActivity(JsonObject(data), command.activityAt, zone, now)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(activity.state), activity.events)
    }
}
