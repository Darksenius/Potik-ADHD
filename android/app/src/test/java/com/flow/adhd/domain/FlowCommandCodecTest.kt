package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowCommandCodecTest {
    @Test
    fun everyCommandRoundTripsWithStableKindAndMetadata() {
        commands().forEach { command ->
            val encoded = FlowCommandCodec.encode(command)
            assertTrue((encoded["kind"] as JsonPrimitive).content.isNotBlank())
            assertEquals(command, FlowCommandCodec.decode(encoded))
            assertEquals(encoded, FlowCommandCodec.encode(FlowCommandCodec.decode(encoded)))
        }
    }

    @Test
    fun undoPayloadAndCaptureOriginArePreserved() {
        val undo = FlowCommand.UndoTask(7, "undo", 123, obj("receipt"), obj("native"))
        assertEquals(undo, FlowCommandCodec.decode(FlowCommandCodec.encode(undo)))
        val capture = FlowCommand.CaptureTask("capture", "capture", 123, CaptureOrigin.NOTIFICATION, 88)
        assertEquals(capture, FlowCommandCodec.decode(FlowCommandCodec.encode(capture)))
    }

    @Test
    fun deferTaskKeepsStableCommandKindSeparateFromDeferralKind() {
        val encoded = FlowCommandCodec.encode(FlowCommand.DeferTask(7, QuestDeferralKind.NEXT_ZONE, 123, null, "defer", 123))
        assertEquals("DeferTask", (encoded["kind"] as JsonPrimitive).content)
        assertEquals("NEXT_ZONE", (encoded["deferralKind"] as JsonPrimitive).content)
    }

    @Test
    fun unknownKindAndMalformedOrUnsafeFieldsHaveStableRejectionCodes() {
        val unknown = assertThrows(FlowCommandCodecException::class.java) {
            FlowCommandCodec.decode(JsonObject(mapOf("kind" to JsonPrimitive("FutureCommand"))))
        }
        assertEquals("unknown-command", unknown.code)

        val encoded = FlowCommandCodec.encode(FlowCommand.UpdateTask(7, obj("task"), "update", 123))
        val missing = assertThrows(FlowCommandCodecException::class.java) {
            FlowCommandCodec.decode(JsonObject(encoded - "taskId"))
        }
        assertEquals("replay-malformed", missing.code)

        val unsafe = JsonObject(encoded + ("taskId" to JsonPrimitive(9_007_199_254_740_992L)))
        val unsafeResult = assertThrows(FlowCommandCodecException::class.java) { FlowCommandCodec.decode(unsafe) }
        assertEquals("replay-malformed", unsafeResult.code)

        val negative = JsonObject(encoded + ("taskId" to JsonPrimitive(-1)))
        val negativeResult = assertThrows(FlowCommandCodecException::class.java) { FlowCommandCodec.decode(negative) }
        assertEquals("replay-malformed", negativeResult.code)

        val wrongType = JsonObject(encoded + ("taskId" to JsonPrimitive("7")))
        val wrongTypeResult = assertThrows(FlowCommandCodecException::class.java) { FlowCommandCodec.decode(wrongType) }
        assertEquals("replay-malformed", wrongTypeResult.code)
    }

    @Test
    fun moveTaskRequiresOneStepAndStrictVisibleIdArray() {
        val command = FlowCommand.MoveTask(7, -1, listOf(0, 7, 9_007_199_254_740_991L), "move", 123)
        val encoded = FlowCommandCodec.encode(command)
        assertEquals("MoveTask", (encoded["kind"] as JsonPrimitive).content)
        assertEquals(command, FlowCommandCodec.decode(encoded))
        assertEquals(FlowCommand.MoveTask(7, 1, emptyList(), "empty", 123),
            FlowCommandCodec.decode(FlowCommandCodec.encode(FlowCommand.MoveTask(7, 1, emptyList(), "empty", 123))))

        listOf(0, -2, 2, Int.MIN_VALUE, Int.MAX_VALUE).forEach { direction ->
            assertThrows(IllegalArgumentException::class.java) { FlowCommand.MoveTask(7, direction, listOf(7), "invalid", 123) }
            val error = assertThrows(FlowCommandCodecException::class.java) {
                FlowCommandCodec.decode(JsonObject(encoded + ("direction" to JsonPrimitive(direction))))
            }
            assertEquals("replay-malformed", error.code)
        }

        val invalidIds: List<JsonElement> = listOf(
            JsonNull, JsonPrimitive("7"), JsonPrimitive(true), JsonPrimitive(1.5),
            JsonPrimitive(-1), JsonPrimitive(9_007_199_254_740_992L), JsonObject(emptyMap()), JsonArray(emptyList()),
        )
        invalidIds.forEach { invalidId ->
            val error = assertThrows(FlowCommandCodecException::class.java) {
                FlowCommandCodec.decode(JsonObject(encoded + ("visibleIds" to JsonArray(listOf(JsonPrimitive(7), invalidId)))))
            }
            assertEquals("replay-malformed", error.code)
        }
        listOf<JsonElement>(JsonNull, JsonPrimitive(7), JsonPrimitive("7"), JsonObject(emptyMap())).forEach { invalidArray ->
            val error = assertThrows(FlowCommandCodecException::class.java) {
                FlowCommandCodec.decode(JsonObject(encoded + ("visibleIds" to invalidArray)))
            }
            assertEquals("replay-malformed", error.code)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FlowCommandCodec.encode(FlowCommand.MoveTask(7, 1, listOf(9_007_199_254_740_992L), "unsafe", 123))
        }
    }

    @Test
    fun notificationEventKeepsExactRawTextAndRejectsNonStringOrExtraFields() {
        val command = FlowCommand.ApplyNotificationEvent("{\"type\":\"task_done\",\"title\":\"Нотатка\\nз текстом\"}", "notification", 123)
        val encoded = FlowCommandCodec.encode(command)
        assertEquals(command, FlowCommandCodec.decode(encoded))
        listOf<JsonElement>(JsonNull, JsonPrimitive(7), JsonObject(emptyMap())).forEach { invalidEvent ->
            val error = assertThrows(FlowCommandCodecException::class.java) {
                FlowCommandCodec.decode(JsonObject(encoded + ("event" to invalidEvent)))
            }
            assertEquals("replay-malformed", error.code)
        }
        val extra = assertThrows(FlowCommandCodecException::class.java) {
            FlowCommandCodec.decode(JsonObject(encoded + ("eventId" to JsonPrimitive(5))))
        }
        assertEquals("replay-malformed", extra.code)
    }

    private fun commands(): List<FlowCommand> {
        val id = "command"
        val at = 123L
        val p = obj("value")
        return listOf(
            FlowCommand.CaptureNote("note", id, at), FlowCommand.RecordPastTaskDone(7, at, id, at),
            FlowCommand.MarkTaskReminderFired(7, at, true, id, at),
            FlowCommand.ApplyNotificationEvent("{\"type\":\"task_done\",\"taskId\":7}", id, at),
            FlowCommand.SetZoneOff(7, true, id, at), FlowCommand.SetPlanDayNote("2026-10-16", "note", id, at),
            FlowCommand.ResetPlanDay("2026-10-16", id, at), FlowCommand.SetPlanZoneOff("2026-10-16", 7, true, id, at),
            FlowCommand.EditPlanZoneForDay("2026-10-16", 7, id, at), FlowCommand.SetTaskEditorDraft("draft", id, at),
            FlowCommand.CreateRareEvent("Event", "up", id, at), FlowCommand.ChangeRareEvent(7, 1.0, id, at), FlowCommand.DeleteRareEvent(7, id, at),
            FlowCommand.CaptureTask("capture", id, at, CaptureOrigin.APP, null),
            FlowCommand.CreateTask(p, id, at), FlowCommand.UpdateTask(7, p, id, at),
            FlowCommand.SetTaskDone(7, true, null, id, at), FlowCommand.UndoTask(7, id, at, p, null),
            FlowCommand.SetChecklistItem(7, 2, true, id, at), FlowCommand.ChangeCounter(7, 1.5, id, at),
            FlowCommand.RecordNegative(7, -1.0, id, at), FlowCommand.DeferTask(7, QuestDeferralKind.NEXT_ZONE, at, p, id, at),
            FlowCommand.MoveTask(7, -1, listOf(3, 7), id, at),
            FlowCommand.MoveTaskToSomeday(7, id, at), FlowCommand.RestoreTaskFromSomeday(7, id, at),
            FlowCommand.TrashTask(7, id, at), FlowCommand.RestoreTrashedTask(7, id, at), FlowCommand.HardDeleteTask(7, id, at),
            FlowCommand.SetPriority(1, null, id, at), FlowCommand.AddQuickNote(p, id, at), FlowCommand.UpdateQuickNote(7, p, id, at),
            FlowCommand.DeleteQuickNote(7, id, at), FlowCommand.SetNotepad(p, id, at), FlowCommand.SetNotepadFolder("folder", id, at),
            FlowCommand.SaveNotepadAsNote(null, id, at), FlowCommand.CreateNoteFolder(p, id, at), FlowCommand.UpdateNoteFolder("folder", p, id, at),
            FlowCommand.DeleteNoteFolder("folder", id, at), FlowCommand.CreateZone(p, id, at), FlowCommand.UpdateZone(7, p, id, at),
            FlowCommand.DeleteZone(7, id, at), FlowCommand.SetPlanDay("2026-10-16", p, id, at),
            FlowCommand.SetPlanRestDay("2026-10-16", true, id, at), FlowCommand.AddPlanItem("2026-10-16", p, id, at),
            FlowCommand.UpdatePlanItem("2026-10-16", 0, p, id, at), FlowCommand.DeletePlanItem("2026-10-16", 0, id, at),
            FlowCommand.CreateTemplate(p, id, at), FlowCommand.UpdateTemplate("template", p, id, at),
            FlowCommand.DeleteTemplate("template", id, at), FlowCommand.ApplyTemplate("template", "2026-10-16", 3, id, at),
            FlowCommand.CreateWeekTemplate(p, id, at), FlowCommand.UpdateWeekTemplate("week", p, id, at),
            FlowCommand.DeleteWeekTemplate("week", id, at), FlowCommand.ApplyWeekTemplate("week", "2026-10-16", 7, id, at),
            FlowCommand.DeletePlanRule(0, id, at), FlowCommand.SetFocus("focus", id, at), FlowCommand.ClearFocus(id, at),
            FlowCommand.AddFocusChip("chip", id, at), FlowCommand.RemoveFocusChip("chip", id, at), FlowCommand.SetEnergy(3, id, at),
            FlowCommand.SetPreferences(p, id, at), FlowCommand.LifecycleTick(id, at), FlowCommand.ImportSnapshot(p, id, at),
            FlowCommand.RestoreSnapshot(p, id, at),
        )
    }

    private fun obj(value: String) = JsonObject(mapOf("value" to JsonPrimitive(value)))
}
