package com.flow.adhd.domain

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

    private fun commands(): List<FlowCommand> {
        val id = "command"
        val at = 123L
        val p = obj("value")
        return listOf(
            FlowCommand.CaptureTask("capture", id, at, CaptureOrigin.APP, null),
            FlowCommand.CreateTask(p, id, at), FlowCommand.UpdateTask(7, p, id, at),
            FlowCommand.SetTaskDone(7, true, null, id, at), FlowCommand.UndoTask(7, id, at, p, null),
            FlowCommand.SetChecklistItem(7, 2, true, id, at), FlowCommand.ChangeCounter(7, 1.5, id, at),
            FlowCommand.RecordNegative(7, -1.0, id, at), FlowCommand.DeferTask(7, QuestDeferralKind.NEXT_ZONE, at, p, id, at),
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
