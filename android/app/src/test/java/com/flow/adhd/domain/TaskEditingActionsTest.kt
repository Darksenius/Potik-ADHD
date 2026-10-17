package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TaskEditingActionsTest {
    private val at = Instant.parse("2026-10-17T09:00:05.001Z").toEpochMilli()
    private val utc = ZoneId.of("UTC")
    private val engine = FlowEngine(mapOf("simple" to 10.0, "sched" to 10.0, "counter" to 2.0, "check" to 10.0, "ctx" to 10.0))
    private fun fixture(task: JsonObject) = FlowSnapshot.fromData(JsonObject(FlowDefaults.create() + mapOf("tasks" to JsonArray(listOf(task)), "nid" to JsonPrimitive(800))))
    private fun apply(snapshot: FlowSnapshot, command: FlowCommand) = (engine.apply(snapshot, command, utc, at) as FlowCommandResult.Applied).snapshot

    @Test fun checklistRenamePreservesCreditAndNewStepsRemainIndependent() {
        var state = fixture(JsonObject(TaskFactory.create(700, "Steps", "check", at) + ("items" to JsonArray(listOf(buildJsonObject {
            put("text", "First"); put("done", true); put("credited", true); put("custom", "keep")
        })))))
        state = apply(state, FlowCommand.EditChecklistItem(700, 0, "Renamed", "rename", at))
        var items = state.tasks.single().raw["items"] as JsonArray
        assertEquals(JsonPrimitive(true), (items[0] as JsonObject)["credited"])
        assertEquals(JsonPrimitive("keep"), (items[0] as JsonObject)["custom"])
        assertEquals(0.0, state.xpTotal, 0.0)
        state = apply(state, FlowCommand.EditChecklistItem(700, null, "Second", "add", at))
        state = apply(state, FlowCommand.SetChecklistItem(700, 1, true, "check", at))
        assertEquals(3.0, state.xpTotal, 0.0)
        state = apply(state, FlowCommand.EditChecklistItem(700, 1, "Second renamed", "edit-second", at))
        state = apply(state, FlowCommand.SetChecklistItem(700, 1, true, "again", at))
        assertEquals(3.0, state.xpTotal, 0.0)
        state = apply(state, FlowCommand.DeleteChecklistItem(700, 0, "delete", at))
        items = state.tasks.single().raw["items"] as JsonArray
        assertEquals(1, items.size)
        assertEquals(JsonPrimitive("Second renamed"), (items.single() as JsonObject)["text"])
        assertEquals(JsonPrimitive(true), (items.single() as JsonObject)["credited"])
    }

    @Test fun copyResetsProgressButKeepsDefinitionAndIsIndependentOfThePreset() {
        val source = JsonObject(TaskFactory.create(700, "Letters", "counter", at) + mapOf(
            "counter" to JsonPrimitive(12), "counterCreditedProgress" to JsonPrimitive(12), "counterTarget" to JsonPrimitive(20),
            "cntHit" to JsonPrimitive(true), "done" to JsonPrimitive(true), "completionCredited" to JsonPrimitive(true),
            "dayPresetKey" to JsonPrimitive("preset:entry:2026-10-17"), "custom" to JsonPrimitive("keep"), "note" to JsonPrimitive("Definition"),
        ))
        val state = apply(fixture(source), FlowCommand.DuplicateTask(700, "copy", at))
        val copy = state.tasks.first()
        assertEquals(800L, copy.id)
        assertEquals("Letters (копія)", copy.title)
        assertFalse(copy.done)
        assertEquals(JsonPrimitive(0), copy.raw["counter"])
        assertEquals(JsonPrimitive(0), copy.raw["counterCreditedProgress"])
        assertEquals(JsonPrimitive(20), copy.raw["counterTarget"])
        assertEquals(JsonPrimitive("keep"), copy.raw["custom"])
        assertNull(copy.raw["dayPresetKey"])
        assertEquals(source, state.tasks[1].raw)
        assertEquals(0.0, state.xpTotal, 0.0)
    }

    @Test fun postponingRoundsForwardAndCannotFarmCompletionCredit() {
        var state = fixture(JsonObject(TaskFactory.create(700, "Call", "sched", at) + mapOf("schedDate" to JsonPrimitive("2026-10-17"), "schedTime" to JsonPrimitive("09:00"))))
        state = apply(state, FlowCommand.SetTaskDone(700, true, at, "complete", at))
        assertEquals(10.0, state.xpTotal, 0.0)
        state = apply(state, FlowCommand.SnoozeTask(700, 15, false, "later", at))
        val task = state.tasks.single()
        assertFalse(task.done)
        assertEquals(JsonPrimitive("09:16"), task.raw["schedTime"])
        assertEquals(JsonPrimitive(Instant.parse("2026-10-17T09:16:00Z").toEpochMilli()), task.raw["snoozeUntil"])
        assertEquals(JsonPrimitive(true), task.raw["completionCredited"])
        state = apply(state, FlowCommand.SetTaskDone(700, true, at, "complete-again", at))
        assertEquals(10.0, state.xpTotal, 0.0)
    }

    @Test fun editorSubmissionClearsDraftSetsContextAndRejectsOverflowAsOneChange() {
        val before = FlowSnapshot.fromData(JsonObject(FlowDefaults.create() + ("taskEditorDraft" to JsonPrimitive("draft"))))
        val payload = buildJsonObject { put("title", "Context"); put("type", "ctx"); put("ctxTags", JsonArray(listOf(JsonPrimitive("Вдома"), JsonPrimitive("15 хв")))) }
        val result = apply(before, FlowCommand.SaveTaskEditor(null, payload, null, false, "save", at))
        assertEquals("", (result.data["taskEditorDraft"] as JsonPrimitive).content)
        assertEquals(payload["ctxTags"], result.tasks.single().raw["ctxTags"])
        val rejected = engine.apply(before, FlowCommand.SaveTaskEditor(null, payload, Long.MAX_VALUE, false, "overflow", at), utc, at)
        assertTrue(rejected is FlowCommandResult.Rejected)
        assertTrue(before.tasks.isEmpty())
        assertEquals(JsonPrimitive("draft"), before.data["taskEditorDraft"])
    }
}
