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
        zoneOccurrenceKey: (Long, Long) -> String? = { id, at -> ZoneCalendar.occurrenceKey(snapshot.data, id, at, zone) },
        activeZoneId: Long? = null,
    ): FlowCommandResult = when (command) {
        is FlowCommand.AddQuickNote, is FlowCommand.UpdateQuickNote, is FlowCommand.DeleteQuickNote,
        is FlowCommand.SetNotepad, is FlowCommand.SetNotepadFolder, is FlowCommand.SaveNotepadAsNote,
        is FlowCommand.CreateNoteFolder, is FlowCommand.UpdateNoteFolder, is FlowCommand.DeleteNoteFolder -> NotesReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.ChangeCounter, is FlowCommand.SetChecklistItem, is FlowCommand.RecordNegative ->
            CounterReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.SetTaskDone -> TaskCompletionReducer.reduce(snapshot, command, zone, taskXp, now, zoneOccurrenceKey).let { result ->
            if (command.done && result is FlowCommandResult.Applied) TaskUndo.offer(snapshot, result, "done", command.taskId, command.completedAt ?: command.activityAt) else result
        }
        is FlowCommand.DeferTask -> TaskDeferralReducer.reduce(snapshot, command, zone, zoneOccurrenceKey).let { result ->
            if (result is FlowCommandResult.Applied) TaskUndo.offer(snapshot, result, "defer", command.taskId, command.requestedAt) else result
        }
        is FlowCommand.UndoTask -> TaskUndo.reduce(snapshot, command, zone, now)
        is FlowCommand.MoveTaskToSomeday, is FlowCommand.RestoreTaskFromSomeday, is FlowCommand.TrashTask,
        is FlowCommand.RestoreTrashedTask, is FlowCommand.HardDeleteTask, is FlowCommand.SetPriority ->
            TaskManagementReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.CreateZone, is FlowCommand.UpdateZone, is FlowCommand.DeleteZone ->
            ZoneReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.SetZoneOff -> ZoneActivationReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.SetPlanDayNote, is FlowCommand.ResetPlanDay, is FlowCommand.SetPlanZoneOff,
        is FlowCommand.EditPlanZoneForDay -> PlannerDayActions.reduce(snapshot, command, zone, now)
        is FlowCommand.SetPlanDay, is FlowCommand.SetPlanRestDay, is FlowCommand.AddPlanItem,
        is FlowCommand.UpdatePlanItem, is FlowCommand.DeletePlanItem -> PlannerReducer.reduce(snapshot, command, zone, now)
        is FlowCommand.CreateTemplate, is FlowCommand.UpdateTemplate, is FlowCommand.DeleteTemplate,
        is FlowCommand.ApplyTemplate -> TemplateReducer.reduce(snapshot, command, zone, now, taskXp.keys)
        is FlowCommand.CreateWeekTemplate, is FlowCommand.UpdateWeekTemplate, is FlowCommand.DeleteWeekTemplate,
        is FlowCommand.ApplyWeekTemplate, is FlowCommand.DeletePlanRule -> WeekTemplateReducer.reduce(snapshot, command, zone, now, taskXp.keys)
        is FlowCommand.SetFocus, is FlowCommand.ClearFocus, is FlowCommand.AddFocusChip, is FlowCommand.RemoveFocusChip ->
            FocusReducer.reduce(snapshot, command, zone)
        is FlowCommand.SetEnergy -> Experience.setEnergy(snapshot.data, command.value, command.activityAt, zone, now)
            .let { FlowCommandResult.Applied(FlowSnapshot.fromData(it.state), it.events) }
        is FlowCommand.CaptureTask -> capture(snapshot, command, zone, now)
        is FlowCommand.CreateTask, is FlowCommand.UpdateTask -> TaskEditorReducer.reduce(snapshot, command, zone, taskXp.keys, now, activeZoneId)
        is FlowCommand.LifecycleTick -> TaskLifecycle.tick(snapshot, now, zone, zoneOccurrenceKey)
        is FlowCommand.SetPreferences -> PreferencesReducer.reduce(snapshot, command)
        is FlowCommand.SetTaskEditorDraft, is FlowCommand.CreateRareEvent, is FlowCommand.ChangeRareEvent,
        is FlowCommand.DeleteRareEvent -> MiscStateReducer.reduce(snapshot, command)
        is FlowCommand.CaptureNote, is FlowCommand.RecordPastTaskDone, is FlowCommand.MarkTaskReminderFired ->
            NotificationTaskActions.reduce(snapshot, command, zone, now, taskXp, zoneOccurrenceKey)
        is FlowCommand.ImportSnapshot, is FlowCommand.RestoreSnapshot -> import(snapshot, command, zone, now)
    }

    private fun import(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long): FlowCommandResult = try {
        when (command) {
            is FlowCommand.ImportSnapshot -> SnapshotMerge.merge(snapshot,
                SnapshotNormalizer.normalize(BackupValidator.validate(command.payload, taskXp.keys), zone, now), zone, now, command.commandId)
            is FlowCommand.RestoreSnapshot -> SnapshotRestore.restore(snapshot,
                BackupValidator.validate(command.payload, taskXp.keys), zone, now)
            else -> error("Expected an import command")
        }
    } catch (e: Exception) {
        FlowCommandResult.Rejected("invalid-backup", e.message ?: "Backup is invalid; current data is unchanged")
    }

    private fun capture(snapshot: FlowSnapshot, command: FlowCommand.CaptureTask, zone: ZoneId, now: Long): FlowCommandResult {
        val title = command.title.trim()
        if (title.isEmpty()) return FlowCommandResult.Rejected("empty-title", "Task title must not be blank")
        // Notification buttons may already reference this ID before any screen opens.
        if (command.requestedId != null && snapshot.tasks.any { it.id == command.requestedId }) {
            return FlowCommandResult.Applied(snapshot, emptyList())
        }
        val next = maxOf(300L, snapshot.tasks.maxOfOrNull { it.id + 1L } ?: 300L,
            (snapshot.data["nid"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: 300L)
        val id = command.requestedId ?: next
        if (next >= 9_007_199_254_740_991L || id >= 9_007_199_254_740_991L) return FlowCommandResult.Rejected("id-exhausted", "No safe task ID remains")
        val task = TaskFactory.create(id, title, "simple", command.activityAt)
        val data = snapshot.data.toMutableMap().apply {
            put("tasks", JsonArray(listOf(task) + snapshot.tasks.map { it.raw }))
            put("nid", JsonPrimitive(maxOf(next + 1L, id + 1L)))
        }
        val activity = Progression.recordActivity(JsonObject(data), command.activityAt, zone, now)
        if (command.origin == CaptureOrigin.NOTIFICATION) {
            val award = Experience.award(activity.state, 10.0, command.activityAt, zone, now)
            return FlowCommandResult.Applied(FlowSnapshot.fromData(award.state), activity.events + award.events)
        }
        return FlowCommandResult.Applied(FlowSnapshot.fromData(activity.state), activity.events)
    }
}
