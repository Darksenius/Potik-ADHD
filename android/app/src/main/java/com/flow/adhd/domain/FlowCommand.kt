package com.flow.adhd.domain

import kotlinx.serialization.json.JsonObject

enum class QuestDeferralKind { NEXT_ZONE, NOT_TODAY }
enum class CaptureOrigin { APP, NOTIFICATION }

/** A durable, replayable request to change one part of the application state. */
sealed class FlowCommand(
    commandIdValue: String,
    activityAtValue: Long,
) {
    open val commandId: String = commandIdValue
    open val activityAt: Long = activityAtValue

    init {
        require(commandIdValue.isNotBlank()) { "commandId must not be blank" }
        require(activityAtValue >= 0) { "activityAt must be a non-negative epoch timestamp" }
    }

    data class CaptureTask(
        val title: String, override val commandId: String, override val activityAt: Long,
        val origin: CaptureOrigin = CaptureOrigin.APP, val requestedId: Long? = null,
    ) : FlowCommand(commandId, activityAt) {
        init { require(requestedId == null || requestedId in 1L..9_007_199_254_740_991L) { "capture ID must be a positive JavaScript-safe integer" } }
    }
    data class CreateTask(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateTask(val taskId: Long, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetTaskDone(val taskId: Long, val done: Boolean, val completedAt: Long?, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UndoTask(
        val taskId: Long, override val commandId: String, override val activityAt: Long,
        val receipt: JsonObject? = null, val nativePayload: JsonObject? = null,
    ) : FlowCommand(commandId, activityAt)
    data class SetChecklistItem(val taskId: Long, val index: Int, val checked: Boolean, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(index >= 0) { "checklist index must be non-negative" } }
    }
    data class ChangeCounter(val taskId: Long, val delta: Double, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(delta.isFinite()) { "counter delta must be finite" } }
    }
    data class RecordNegative(val taskId: Long, val delta: Double, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(delta.isFinite()) { "negative-event delta must be finite" } }
    }
    data class DeferTask(val taskId: Long, val kind: QuestDeferralKind, val requestedAt: Long, val details: JsonObject?, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class MoveTaskToSomeday(val taskId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class RestoreTaskFromSomeday(val taskId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class TrashTask(val taskId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class RestoreTrashedTask(val taskId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class HardDeleteTask(val taskId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetPriority(val slot: Int, val taskId: Long?, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(slot in 0..2) { "priority slot must be between 0 and 2" } }
    }

    data class AddQuickNote(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class CaptureNote(val text: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class RecordPastTaskDone(val taskId: Long, val completedAt: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class MarkTaskReminderFired(val taskId: Long, val reminderAt: Long, val preReminder: Boolean, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateQuickNote(val noteId: Long, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeleteQuickNote(val noteId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetNotepad(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetNotepadFolder(val folderId: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SaveNotepadAsNote(val folderId: String?, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class CreateNoteFolder(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateNoteFolder(val folderId: String, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeleteNoteFolder(val folderId: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)

    /** Payload: nm. Updates additionally require color, desc, prio and slots. */
    data class CreateZone(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateZone(val zoneId: Long, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeleteZone(val zoneId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetZoneOff(val zoneId: Long, val off: Boolean, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    /** Payload: blocks array. An empty array is an explicit override of the day's template. */
    data class SetPlanDay(val date: String, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetPlanRestDay(val date: String, val isRest: Boolean, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetPlanDayNote(val date: String, val text: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ResetPlanDay(val date: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetPlanZoneOff(val date: String, val zoneId: Long, val off: Boolean, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class EditPlanZoneForDay(val date: String, val zoneId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    /** Payload: text, optional isEvent. Tasks and calendar annotations have distinct behavior. */
    data class AddPlanItem(val date: String, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdatePlanItem(val date: String, val index: Int, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeletePlanItem(val date: String, val index: Int, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class CreateTemplate(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateTemplate(val templateId: String, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeleteTemplate(val templateId: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ApplyTemplate(val templateId: String, val startDate: String, val spanDays: Int, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class CreateWeekTemplate(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class UpdateWeekTemplate(val templateId: String, val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeleteWeekTemplate(val templateId: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ApplyWeekTemplate(val templateId: String, val startDate: String, val spanDays: Int, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class DeletePlanRule(val index: Int, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)

    data class SetFocus(val value: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ClearFocus(override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class AddFocusChip(val value: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class RemoveFocusChip(val value: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetEnergy(val value: Int, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(value in 0..5) { "energy must be between 0 and 5" } }
    }
    data class SetPreferences(val values: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class SetTaskEditorDraft(val text: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class CreateRareEvent(val name: String, val direction: String, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ChangeRareEvent(val eventId: Long, val delta: Double, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt) {
        init { require(delta.isFinite()) { "event delta must be finite" } }
    }
    data class DeleteRareEvent(val eventId: Long, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class LifecycleTick(override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class ImportSnapshot(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
    data class RestoreSnapshot(val payload: JsonObject, override val commandId: String, override val activityAt: Long) : FlowCommand(commandId, activityAt)
}

/** A proposed domain event. Only the Repository can confirm its durable commit. */
data class FlowEvent(val type: String, val activityAt: Long, val payload: JsonObject = JsonObject(emptyMap()))

sealed interface FlowCommandResult {
    data class Applied(
        val snapshot: FlowSnapshot,
        val events: List<FlowEvent>,
        val undoReceipt: JsonObject? = null,
    ) : FlowCommandResult

    data class Rejected(val code: String, val message: String) : FlowCommandResult
}
