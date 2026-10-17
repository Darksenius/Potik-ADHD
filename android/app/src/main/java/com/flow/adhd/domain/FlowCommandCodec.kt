package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Stable JSON representation for durable command replay and idempotency. */
object FlowCommandCodec {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L
    private val common = setOf("kind", "commandId", "activityAt")

    fun encode(command: FlowCommand): JsonObject {
        val fields = when (command) {
            is FlowCommand.CaptureTask -> fields("CaptureTask", command, "title" to str(command.title), "origin" to str(command.origin.name), "requestedId" to nullableId(command.requestedId))
            is FlowCommand.CreateTask -> fields("CreateTask", command, "payload" to command.payload)
            is FlowCommand.UpdateTask -> fields("UpdateTask", command, "taskId" to id(command.taskId), "payload" to command.payload)
            is FlowCommand.SetTaskDone -> fields("SetTaskDone", command, "taskId" to id(command.taskId), "done" to bool(command.done), "completedAt" to nullableTime(command.completedAt))
            is FlowCommand.UndoTask -> fields("UndoTask", command, "taskId" to id(command.taskId), "receipt" to nullable(command.receipt), "nativePayload" to nullable(command.nativePayload))
            is FlowCommand.SetChecklistItem -> fields("SetChecklistItem", command, "taskId" to id(command.taskId), "index" to num(command.index), "checked" to bool(command.checked))
            is FlowCommand.ChangeCounter -> fields("ChangeCounter", command, "taskId" to id(command.taskId), "delta" to num(command.delta))
            is FlowCommand.RecordNegative -> fields("RecordNegative", command, "taskId" to id(command.taskId), "delta" to num(command.delta))
            is FlowCommand.DeferTask -> fields("DeferTask", command, "taskId" to id(command.taskId), "deferralKind" to str(command.kind.name), "requestedAt" to time(command.requestedAt), "details" to nullable(command.details))
            is FlowCommand.MoveTask -> fields("MoveTask", command, "taskId" to id(command.taskId), "direction" to num(command.direction), "visibleIds" to ids(command.visibleIds))
            is FlowCommand.MoveTaskToSomeday -> fields("MoveTaskToSomeday", command, "taskId" to id(command.taskId))
            is FlowCommand.RestoreTaskFromSomeday -> fields("RestoreTaskFromSomeday", command, "taskId" to id(command.taskId))
            is FlowCommand.TrashTask -> fields("TrashTask", command, "taskId" to id(command.taskId))
            is FlowCommand.RestoreTrashedTask -> fields("RestoreTrashedTask", command, "taskId" to id(command.taskId))
            is FlowCommand.HardDeleteTask -> fields("HardDeleteTask", command, "taskId" to id(command.taskId))
            is FlowCommand.SetPriority -> fields("SetPriority", command, "slot" to num(command.slot), "taskId" to nullableId(command.taskId))
            is FlowCommand.AddQuickNote -> fields("AddQuickNote", command, "payload" to command.payload)
            is FlowCommand.CaptureNote -> fields("CaptureNote", command, "text" to str(command.text))
            is FlowCommand.RecordPastTaskDone -> fields("RecordPastTaskDone", command, "taskId" to id(command.taskId), "completedAt" to time(command.completedAt))
            is FlowCommand.MarkTaskReminderFired -> fields("MarkTaskReminderFired", command, "taskId" to id(command.taskId), "reminderAt" to time(command.reminderAt), "preReminder" to bool(command.preReminder))
            is FlowCommand.ApplyNotificationEvent -> fields("ApplyNotificationEvent", command, "event" to str(command.event))
            is FlowCommand.UpdateQuickNote -> fields("UpdateQuickNote", command, "noteId" to id(command.noteId), "payload" to command.payload)
            is FlowCommand.DeleteQuickNote -> fields("DeleteQuickNote", command, "noteId" to id(command.noteId))
            is FlowCommand.SetNotepad -> fields("SetNotepad", command, "payload" to command.payload)
            is FlowCommand.SetNotepadFolder -> fields("SetNotepadFolder", command, "folderId" to str(command.folderId))
            is FlowCommand.SaveNotepadAsNote -> fields("SaveNotepadAsNote", command, "folderId" to (command.folderId?.let(::str) ?: JsonNull))
            is FlowCommand.FileNotepadAsNote -> fields("FileNotepadAsNote", command, "folderId" to (command.folderId?.let(::str) ?: JsonNull))
            is FlowCommand.ConvertNotepadToTask -> fields("ConvertNotepadToTask", command)
            is FlowCommand.CreateNoteFolder -> fields("CreateNoteFolder", command, "payload" to command.payload)
            is FlowCommand.UpdateNoteFolder -> fields("UpdateNoteFolder", command, "folderId" to str(command.folderId), "payload" to command.payload)
            is FlowCommand.DeleteNoteFolder -> fields("DeleteNoteFolder", command, "folderId" to str(command.folderId))
            is FlowCommand.CreateZone -> fields("CreateZone", command, "payload" to command.payload)
            is FlowCommand.UpdateZone -> fields("UpdateZone", command, "zoneId" to id(command.zoneId), "payload" to command.payload)
            is FlowCommand.DeleteZone -> fields("DeleteZone", command, "zoneId" to id(command.zoneId))
            is FlowCommand.SetZoneOff -> fields("SetZoneOff", command, "zoneId" to id(command.zoneId), "off" to bool(command.off))
            is FlowCommand.SetPlanDay -> fields("SetPlanDay", command, "date" to str(command.date), "payload" to command.payload)
            is FlowCommand.SetPlanRestDay -> fields("SetPlanRestDay", command, "date" to str(command.date), "isRest" to bool(command.isRest))
            is FlowCommand.SetPlanDayNote -> fields("SetPlanDayNote", command, "date" to str(command.date), "text" to str(command.text))
            is FlowCommand.ResetPlanDay -> fields("ResetPlanDay", command, "date" to str(command.date))
            is FlowCommand.SetPlanZoneOff -> fields("SetPlanZoneOff", command, "date" to str(command.date), "zoneId" to id(command.zoneId), "off" to bool(command.off))
            is FlowCommand.EditPlanZoneForDay -> fields("EditPlanZoneForDay", command, "date" to str(command.date), "zoneId" to id(command.zoneId))
            is FlowCommand.AddPlanItem -> fields("AddPlanItem", command, "date" to str(command.date), "payload" to command.payload)
            is FlowCommand.UpdatePlanItem -> fields("UpdatePlanItem", command, "date" to str(command.date), "index" to num(command.index), "payload" to command.payload)
            is FlowCommand.DeletePlanItem -> fields("DeletePlanItem", command, "date" to str(command.date), "index" to num(command.index))
            is FlowCommand.CreateTemplate -> fields("CreateTemplate", command, "payload" to command.payload)
            is FlowCommand.UpdateTemplate -> fields("UpdateTemplate", command, "templateId" to str(command.templateId), "payload" to command.payload)
            is FlowCommand.DeleteTemplate -> fields("DeleteTemplate", command, "templateId" to str(command.templateId))
            is FlowCommand.ApplyTemplate -> fields("ApplyTemplate", command, "templateId" to str(command.templateId), "startDate" to str(command.startDate), "spanDays" to num(command.spanDays))
            is FlowCommand.CreateWeekTemplate -> fields("CreateWeekTemplate", command, "payload" to command.payload)
            is FlowCommand.UpdateWeekTemplate -> fields("UpdateWeekTemplate", command, "templateId" to str(command.templateId), "payload" to command.payload)
            is FlowCommand.DeleteWeekTemplate -> fields("DeleteWeekTemplate", command, "templateId" to str(command.templateId))
            is FlowCommand.ApplyWeekTemplate -> fields("ApplyWeekTemplate", command, "templateId" to str(command.templateId), "startDate" to str(command.startDate), "spanDays" to num(command.spanDays))
            is FlowCommand.DeletePlanRule -> fields("DeletePlanRule", command, "index" to num(command.index))
            is FlowCommand.SetFocus -> fields("SetFocus", command, "value" to str(command.value))
            is FlowCommand.ClearFocus -> fields("ClearFocus", command)
            is FlowCommand.AddFocusChip -> fields("AddFocusChip", command, "value" to str(command.value))
            is FlowCommand.RemoveFocusChip -> fields("RemoveFocusChip", command, "value" to str(command.value))
            is FlowCommand.SetEnergy -> fields("SetEnergy", command, "value" to num(command.value))
            is FlowCommand.SetPreferences -> fields("SetPreferences", command, "values" to command.values)
            is FlowCommand.SetTaskEditorDraft -> fields("SetTaskEditorDraft", command, "text" to str(command.text))
            is FlowCommand.CreateRareEvent -> fields("CreateRareEvent", command, "name" to str(command.name), "direction" to str(command.direction))
            is FlowCommand.ChangeRareEvent -> fields("ChangeRareEvent", command, "eventId" to id(command.eventId), "delta" to num(command.delta))
            is FlowCommand.DeleteRareEvent -> fields("DeleteRareEvent", command, "eventId" to id(command.eventId))
            is FlowCommand.LifecycleTick -> fields("LifecycleTick", command)
            is FlowCommand.ImportSnapshot -> fields("ImportSnapshot", command, "payload" to command.payload)
            is FlowCommand.RestoreSnapshot -> fields("RestoreSnapshot", command, "payload" to command.payload)
        }
        return fields
    }

    fun decode(value: JsonObject): FlowCommand {
        val kind = value.string("kind") ?: malformed("kind must be a string")
        if (kind !in kinds) throw FlowCommandCodecException("unknown-command", "Unknown command kind: $kind")
        try {
            return when (kind) {
                "CaptureTask" -> read(value, "title", "origin", "requestedId") { c ->
                    FlowCommand.CaptureTask(c.string("title"), c.commandId, c.activityAt, c.enum("origin", CaptureOrigin.entries), c.nullableId("requestedId"))
                }
                "CreateTask" -> read(value, "payload") { c -> FlowCommand.CreateTask(c.obj("payload"), c.commandId, c.activityAt) }
                "UpdateTask" -> read(value, "taskId", "payload") { c -> FlowCommand.UpdateTask(c.id("taskId"), c.obj("payload"), c.commandId, c.activityAt) }
                "SetTaskDone" -> read(value, "taskId", "done", "completedAt") { c -> FlowCommand.SetTaskDone(c.id("taskId"), c.bool("done"), c.nullableTime("completedAt"), c.commandId, c.activityAt) }
                "UndoTask" -> read(value, "taskId", "receipt", "nativePayload") { c -> FlowCommand.UndoTask(c.id("taskId"), c.commandId, c.activityAt, c.nullableObj("receipt"), c.nullableObj("nativePayload")) }
                "SetChecklistItem" -> read(value, "taskId", "index", "checked") { c -> FlowCommand.SetChecklistItem(c.id("taskId"), c.int("index"), c.bool("checked"), c.commandId, c.activityAt) }
                "ChangeCounter" -> read(value, "taskId", "delta") { c -> FlowCommand.ChangeCounter(c.id("taskId"), c.double("delta"), c.commandId, c.activityAt) }
                "RecordNegative" -> read(value, "taskId", "delta") { c -> FlowCommand.RecordNegative(c.id("taskId"), c.double("delta"), c.commandId, c.activityAt) }
                "DeferTask" -> read(value, "taskId", "deferralKind", "requestedAt", "details") { c -> FlowCommand.DeferTask(c.id("taskId"), c.enum("deferralKind", QuestDeferralKind.entries), c.time("requestedAt"), c.nullableObj("details"), c.commandId, c.activityAt) }
                "MoveTask" -> read(value, "taskId", "direction", "visibleIds") { c -> FlowCommand.MoveTask(c.id("taskId"), c.int("direction"), c.ids("visibleIds"), c.commandId, c.activityAt) }
                "MoveTaskToSomeday" -> read(value, "taskId") { c -> FlowCommand.MoveTaskToSomeday(c.id("taskId"), c.commandId, c.activityAt) }
                "RestoreTaskFromSomeday" -> read(value, "taskId") { c -> FlowCommand.RestoreTaskFromSomeday(c.id("taskId"), c.commandId, c.activityAt) }
                "TrashTask" -> read(value, "taskId") { c -> FlowCommand.TrashTask(c.id("taskId"), c.commandId, c.activityAt) }
                "RestoreTrashedTask" -> read(value, "taskId") { c -> FlowCommand.RestoreTrashedTask(c.id("taskId"), c.commandId, c.activityAt) }
                "HardDeleteTask" -> read(value, "taskId") { c -> FlowCommand.HardDeleteTask(c.id("taskId"), c.commandId, c.activityAt) }
                "SetPriority" -> read(value, "slot", "taskId") { c -> FlowCommand.SetPriority(c.int("slot"), c.nullableId("taskId"), c.commandId, c.activityAt) }
                "AddQuickNote" -> read(value, "payload") { c -> FlowCommand.AddQuickNote(c.obj("payload"), c.commandId, c.activityAt) }
                "CaptureNote" -> read(value, "text") { c -> FlowCommand.CaptureNote(c.string("text"), c.commandId, c.activityAt) }
                "RecordPastTaskDone" -> read(value, "taskId", "completedAt") { c -> FlowCommand.RecordPastTaskDone(c.id("taskId"), c.time("completedAt"), c.commandId, c.activityAt) }
                "MarkTaskReminderFired" -> read(value, "taskId", "reminderAt", "preReminder") { c -> FlowCommand.MarkTaskReminderFired(c.id("taskId"), c.time("reminderAt"), c.bool("preReminder"), c.commandId, c.activityAt) }
                "ApplyNotificationEvent" -> read(value, "event") { c -> FlowCommand.ApplyNotificationEvent(c.string("event"), c.commandId, c.activityAt) }
                "UpdateQuickNote" -> read(value, "noteId", "payload") { c -> FlowCommand.UpdateQuickNote(c.id("noteId"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeleteQuickNote" -> read(value, "noteId") { c -> FlowCommand.DeleteQuickNote(c.id("noteId"), c.commandId, c.activityAt) }
                "SetNotepad" -> read(value, "payload") { c -> FlowCommand.SetNotepad(c.obj("payload"), c.commandId, c.activityAt) }
                "SetNotepadFolder" -> read(value, "folderId") { c -> FlowCommand.SetNotepadFolder(c.string("folderId"), c.commandId, c.activityAt) }
                "SaveNotepadAsNote" -> read(value, "folderId") { c -> FlowCommand.SaveNotepadAsNote(c.nullableString("folderId"), c.commandId, c.activityAt) }
                "FileNotepadAsNote" -> read(value, "folderId") { c -> FlowCommand.FileNotepadAsNote(c.nullableString("folderId"), c.commandId, c.activityAt) }
                "ConvertNotepadToTask" -> read(value) { c -> FlowCommand.ConvertNotepadToTask(c.commandId, c.activityAt) }
                "CreateNoteFolder" -> read(value, "payload") { c -> FlowCommand.CreateNoteFolder(c.obj("payload"), c.commandId, c.activityAt) }
                "UpdateNoteFolder" -> read(value, "folderId", "payload") { c -> FlowCommand.UpdateNoteFolder(c.string("folderId"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeleteNoteFolder" -> read(value, "folderId") { c -> FlowCommand.DeleteNoteFolder(c.string("folderId"), c.commandId, c.activityAt) }
                "CreateZone" -> read(value, "payload") { c -> FlowCommand.CreateZone(c.obj("payload"), c.commandId, c.activityAt) }
                "UpdateZone" -> read(value, "zoneId", "payload") { c -> FlowCommand.UpdateZone(c.id("zoneId"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeleteZone" -> read(value, "zoneId") { c -> FlowCommand.DeleteZone(c.id("zoneId"), c.commandId, c.activityAt) }
                "SetZoneOff" -> read(value, "zoneId", "off") { c -> FlowCommand.SetZoneOff(c.id("zoneId"), c.bool("off"), c.commandId, c.activityAt) }
                "SetPlanDay" -> read(value, "date", "payload") { c -> FlowCommand.SetPlanDay(c.string("date"), c.obj("payload"), c.commandId, c.activityAt) }
                "SetPlanRestDay" -> read(value, "date", "isRest") { c -> FlowCommand.SetPlanRestDay(c.string("date"), c.bool("isRest"), c.commandId, c.activityAt) }
                "SetPlanDayNote" -> read(value, "date", "text") { c -> FlowCommand.SetPlanDayNote(c.string("date"), c.string("text"), c.commandId, c.activityAt) }
                "ResetPlanDay" -> read(value, "date") { c -> FlowCommand.ResetPlanDay(c.string("date"), c.commandId, c.activityAt) }
                "SetPlanZoneOff" -> read(value, "date", "zoneId", "off") { c -> FlowCommand.SetPlanZoneOff(c.string("date"), c.id("zoneId"), c.bool("off"), c.commandId, c.activityAt) }
                "EditPlanZoneForDay" -> read(value, "date", "zoneId") { c -> FlowCommand.EditPlanZoneForDay(c.string("date"), c.id("zoneId"), c.commandId, c.activityAt) }
                "AddPlanItem" -> read(value, "date", "payload") { c -> FlowCommand.AddPlanItem(c.string("date"), c.obj("payload"), c.commandId, c.activityAt) }
                "UpdatePlanItem" -> read(value, "date", "index", "payload") { c -> FlowCommand.UpdatePlanItem(c.string("date"), c.int("index"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeletePlanItem" -> read(value, "date", "index") { c -> FlowCommand.DeletePlanItem(c.string("date"), c.int("index"), c.commandId, c.activityAt) }
                "CreateTemplate" -> read(value, "payload") { c -> FlowCommand.CreateTemplate(c.obj("payload"), c.commandId, c.activityAt) }
                "UpdateTemplate" -> read(value, "templateId", "payload") { c -> FlowCommand.UpdateTemplate(c.string("templateId"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeleteTemplate" -> read(value, "templateId") { c -> FlowCommand.DeleteTemplate(c.string("templateId"), c.commandId, c.activityAt) }
                "ApplyTemplate" -> read(value, "templateId", "startDate", "spanDays") { c -> FlowCommand.ApplyTemplate(c.string("templateId"), c.string("startDate"), c.int("spanDays"), c.commandId, c.activityAt) }
                "CreateWeekTemplate" -> read(value, "payload") { c -> FlowCommand.CreateWeekTemplate(c.obj("payload"), c.commandId, c.activityAt) }
                "UpdateWeekTemplate" -> read(value, "templateId", "payload") { c -> FlowCommand.UpdateWeekTemplate(c.string("templateId"), c.obj("payload"), c.commandId, c.activityAt) }
                "DeleteWeekTemplate" -> read(value, "templateId") { c -> FlowCommand.DeleteWeekTemplate(c.string("templateId"), c.commandId, c.activityAt) }
                "ApplyWeekTemplate" -> read(value, "templateId", "startDate", "spanDays") { c -> FlowCommand.ApplyWeekTemplate(c.string("templateId"), c.string("startDate"), c.int("spanDays"), c.commandId, c.activityAt) }
                "DeletePlanRule" -> read(value, "index") { c -> FlowCommand.DeletePlanRule(c.int("index"), c.commandId, c.activityAt) }
                "SetFocus" -> read(value, "value") { c -> FlowCommand.SetFocus(c.string("value"), c.commandId, c.activityAt) }
                "ClearFocus" -> read(value) { c -> FlowCommand.ClearFocus(c.commandId, c.activityAt) }
                "AddFocusChip" -> read(value, "value") { c -> FlowCommand.AddFocusChip(c.string("value"), c.commandId, c.activityAt) }
                "RemoveFocusChip" -> read(value, "value") { c -> FlowCommand.RemoveFocusChip(c.string("value"), c.commandId, c.activityAt) }
                "SetEnergy" -> read(value, "value") { c -> FlowCommand.SetEnergy(c.int("value"), c.commandId, c.activityAt) }
                "SetPreferences" -> read(value, "values") { c -> FlowCommand.SetPreferences(c.obj("values"), c.commandId, c.activityAt) }
                "SetTaskEditorDraft" -> read(value, "text") { c -> FlowCommand.SetTaskEditorDraft(c.string("text"), c.commandId, c.activityAt) }
                "CreateRareEvent" -> read(value, "name", "direction") { c -> FlowCommand.CreateRareEvent(c.string("name"), c.string("direction"), c.commandId, c.activityAt) }
                "ChangeRareEvent" -> read(value, "eventId", "delta") { c -> FlowCommand.ChangeRareEvent(c.id("eventId"), c.double("delta"), c.commandId, c.activityAt) }
                "DeleteRareEvent" -> read(value, "eventId") { c -> FlowCommand.DeleteRareEvent(c.id("eventId"), c.commandId, c.activityAt) }
                "LifecycleTick" -> read(value) { c -> FlowCommand.LifecycleTick(c.commandId, c.activityAt) }
                "ImportSnapshot" -> read(value, "payload") { c -> FlowCommand.ImportSnapshot(c.obj("payload"), c.commandId, c.activityAt) }
                "RestoreSnapshot" -> read(value, "payload") { c -> FlowCommand.RestoreSnapshot(c.obj("payload"), c.commandId, c.activityAt) }
                else -> throw FlowCommandCodecException("unknown-command", "Unknown command kind: $kind")
            }
        } catch (error: FlowCommandCodecException) {
            throw error
        } catch (error: Exception) {
            throw FlowCommandCodecException("replay-malformed", error.message ?: "Malformed command", error)
        }
    }

    private val kinds = setOf(
        "CaptureTask", "CreateTask", "UpdateTask", "SetTaskDone", "UndoTask", "SetChecklistItem", "ChangeCounter", "RecordNegative", "DeferTask",
        "MoveTask", "MoveTaskToSomeday", "RestoreTaskFromSomeday", "TrashTask", "RestoreTrashedTask", "HardDeleteTask", "SetPriority",
        "AddQuickNote", "CaptureNote", "RecordPastTaskDone", "MarkTaskReminderFired", "ApplyNotificationEvent", "UpdateQuickNote", "DeleteQuickNote", "SetNotepad", "SetNotepadFolder", "SaveNotepadAsNote", "FileNotepadAsNote", "ConvertNotepadToTask", "CreateNoteFolder", "UpdateNoteFolder", "DeleteNoteFolder",
        "CreateZone", "UpdateZone", "DeleteZone", "SetZoneOff", "SetPlanDay", "SetPlanRestDay", "SetPlanDayNote", "ResetPlanDay", "SetPlanZoneOff", "EditPlanZoneForDay", "AddPlanItem", "UpdatePlanItem", "DeletePlanItem",
        "CreateTemplate", "UpdateTemplate", "DeleteTemplate", "ApplyTemplate", "CreateWeekTemplate", "UpdateWeekTemplate", "DeleteWeekTemplate", "ApplyWeekTemplate", "DeletePlanRule",
        "SetFocus", "ClearFocus", "AddFocusChip", "RemoveFocusChip", "SetEnergy", "SetPreferences", "SetTaskEditorDraft", "CreateRareEvent", "ChangeRareEvent", "DeleteRareEvent", "LifecycleTick", "ImportSnapshot", "RestoreSnapshot",
    )

    private fun fields(kind: String, command: FlowCommand, vararg values: Pair<String, JsonElement>) = JsonObject(
        linkedMapOf<String, JsonElement>("kind" to str(kind), "commandId" to str(command.commandId), "activityAt" to time(command.activityAt)).apply {
            values.forEach { (key, value) -> put(key, value) }
        },
    )

    private fun read(value: JsonObject, vararg expected: String, build: (CommandFields) -> FlowCommand): FlowCommand {
        val required = common + expected.toSet()
        if (value.keys != required) malformed("Command fields do not match kind ${value.string("kind")}")
        val commandId = value.string("commandId") ?: malformed("commandId must be a string")
        if (commandId.isBlank()) malformed("commandId must not be blank")
        val at = value.time("activityAt")
        return build(CommandFields(value, commandId, at))
    }

    private class CommandFields(private val value: JsonObject, val commandId: String, val activityAt: Long) {
        fun string(key: String) = value.string(key) ?: malformed("$key must be a string")
        fun nullableString(key: String) = value.nullableString(key)
        fun bool(key: String) = value.bool(key)
        fun int(key: String) = value.int(key)
        fun double(key: String) = value.double(key)
        fun id(key: String) = value.id(key)
        fun ids(key: String) = value.ids(key)
        fun nullableId(key: String) = value.nullableId(key)
        fun time(key: String) = value.time(key)
        fun nullableTime(key: String) = value.nullableTime(key)
        fun obj(key: String) = value.obj(key)
        fun nullableObj(key: String) = value.nullableObj(key)
        fun <T : Enum<T>> enum(key: String, values: List<T>): T {
            val text = string(key)
            return values.firstOrNull { it.name == text } ?: malformed("$key has an unknown value")
        }
    }

    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.nullableString(key: String): String? = when (val item = this[key]) {
        JsonNull -> null
        is JsonPrimitive -> if (item.isString) item.content else malformed("$key must be a string or null")
        else -> malformed("$key must be a string or null")
    }
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
        ?: malformed("$key must be a boolean")
    private fun JsonObject.int(key: String): Int {
        val parsed = integer(key)
        if (parsed !in Int.MIN_VALUE..Int.MAX_VALUE) malformed("$key is outside the integer range")
        return parsed.toInt()
    }
    private fun JsonObject.double(key: String): Double = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        ?.takeIf { it.isFinite() } ?: malformed("$key must be a finite number")
    private fun JsonObject.id(key: String): Long = nullableId(key)
        ?: malformed("$key must be a non-negative JavaScript-safe ID")
    private fun JsonObject.nullableId(key: String): Long? = readId(this[key], key)
    private fun JsonObject.ids(key: String): List<Long> {
        val items = this[key] as? JsonArray ?: malformed("$key must be an array of IDs")
        return items.mapIndexed { index, item ->
            readId(item, "$key[$index]") ?: malformed("$key[$index] must be a non-negative JavaScript-safe ID")
        }
    }
    private fun readId(item: JsonElement?, key: String): Long? = when (item) {
        JsonNull -> null
        is JsonPrimitive -> {
            val number = item.takeUnless { it.isString }?.doubleOrNull
                ?: malformed("$key must be an integer ID or null")
            if (!number.isFinite() || number % 1.0 != 0.0 || number < 0 || number > MAX_SAFE_ID.toDouble()) malformed("$key must be a non-negative JavaScript-safe integer ID")
            number.toLong()
        }
        else -> malformed("$key must be an integer ID or null")
    }
    private fun JsonObject.time(key: String): Long {
        val number = integer(key)
        if (number < 0) malformed("$key must be non-negative")
        return number
    }
    private fun JsonObject.nullableTime(key: String): Long? = if (this[key] == JsonNull) null else time(key)
    private fun JsonObject.integer(key: String): Long {
        val item = this[key] as? JsonPrimitive ?: malformed("$key must be an integer")
        val number = item.takeUnless { it.isString }?.doubleOrNull
            ?: malformed("$key must be an integer")
        if (!number.isFinite() || number % 1.0 != 0.0 || number < Long.MIN_VALUE.toDouble() || number >= Long.MAX_VALUE.toDouble()) malformed("$key is outside the integer range")
        return number.toLong()
    }
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject ?: malformed("$key must be an object")
    private fun JsonObject.nullableObj(key: String): JsonObject? = when (val item = this[key]) {
        JsonNull -> null
        is JsonObject -> item
        else -> malformed("$key must be an object or null")
    }

    private fun id(value: Long): JsonPrimitive {
        require(value in 0..MAX_SAFE_ID) { "ID must be a non-negative JavaScript-safe integer" }
        return JsonPrimitive(value)
    }
    private fun nullableId(value: Long?) = value?.let(::id) ?: JsonNull
    private fun ids(values: List<Long>) = JsonArray(values.map(::id))
    private fun time(value: Long): JsonPrimitive {
        require(value >= 0) { "time must be non-negative" }
        return JsonPrimitive(value)
    }
    private fun nullableTime(value: Long?) = value?.let(::time) ?: JsonNull
    private fun str(value: String) = JsonPrimitive(value)
    private fun bool(value: Boolean) = JsonPrimitive(value)
    private fun num(value: Number) = JsonPrimitive(value)
    private fun nullable(value: JsonObject?) = value ?: JsonNull
    private fun malformed(message: String): Nothing = throw FlowCommandCodecException("replay-malformed", message)
}

class FlowCommandCodecException(val code: String, message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)
