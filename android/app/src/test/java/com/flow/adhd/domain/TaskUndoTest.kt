package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskUndoTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()

    @Test fun liveCompletionUndoRestoresProgressPriorityAndOnlyTaskButKeepsActivity() {
        val before = snapshot("""{"id":1,"created":"c","title":"Task","type":"simple","done":false,"repeat":"none"}""", """, "priorities":[1],"progressDays":[],"dailyInsightClaims":[]""")
        val done = TaskCompletionReducer.reduce(before, FlowCommand.SetTaskDone(1, true, at, "done", at), zone, mapOf("simple" to 10.0)) as FlowCommandResult.Applied
        val offered = TaskUndo.offer(before, done, "done", 1, at)
        val command = FlowCommand.UndoTask(1, "undo", at + 10_000, receipt = offered.undoReceipt)
        val undone = applied(TaskUndo.reduce(offered.snapshot, command, zone, at + 10_000)).snapshot
        assertFalse(undone.tasks.single().done)
        assertEquals("0", undone.data["done"]?.toString())
        assertEquals("0", undone.data["xpTotal"]?.toString())
        assertEquals(Json.parseToJsonElement("[1]"), undone.data["priorities"])
        assertEquals("[\"2024-03-01\"]", undone.data["progressDays"].toString())
        assertEquals(2, (undone.data["taskHistory"] as JsonArray).size)
        assertEquals("reopened", ((undone.data["taskHistory"] as JsonArray).last().jsonObject["kind"]).toString().trim('"'))
    }

    @Test fun liveUndoRejectsChangedTaskOrChangedGlobalWithoutMutation() {
        val before = snapshot("""{"id":1,"created":"c","title":"Task","type":"simple","done":false,"repeat":"none"}""", """, "priorities":[1]""")
        val done = TaskCompletionReducer.reduce(before, FlowCommand.SetTaskDone(1, true, at, "done", at), zone, mapOf("simple" to 10.0)) as FlowCommandResult.Applied
        val offered = TaskUndo.offer(before, done, "done", 1, at)
        val editedTask = JsonObject(done.snapshot.tasks.single().raw + ("title" to JsonPrimitive("Edited")))
        val changedTask = FlowSnapshot.fromData(JsonObject(done.snapshot.data + ("tasks" to JsonArray(listOf(editedTask)))))
        assertTrue(TaskUndo.reduce(changedTask, FlowCommand.UndoTask(1, "u1", at + 1, receipt = offered.undoReceipt), zone, at + 1) is FlowCommandResult.Rejected)
        val changedGlobals = FlowSnapshot.fromData(JsonObject(done.snapshot.data + ("xpTotal" to Json.parseToJsonElement("999"))))
        val beforeReject = changedGlobals.data
        assertTrue(TaskUndo.reduce(changedGlobals, FlowCommand.UndoTask(1, "u2", at + 1, receipt = offered.undoReceipt), zone, at + 1) is FlowCommandResult.Rejected)
        assertEquals(beforeReject, changedGlobals.data)
    }

    @Test fun liveReceiptCannotBeReusedAfterUndoAndRedoAtAnotherTime() {
        val before = snapshot("""{"id":6,"created":"c","title":"Repeat","type":"simple","done":false,"repeat":"none"}""")
        val firstDone = TaskCompletionReducer.reduce(before, FlowCommand.SetTaskDone(6, true, at, "done-1", at), zone, mapOf("simple" to 10.0)) as FlowCommandResult.Applied
        val receipt = TaskUndo.offer(before, firstDone, "done", 6, at).undoReceipt
        val undone = applied(TaskUndo.reduce(firstDone.snapshot, FlowCommand.UndoTask(6, "undo-1", at + 1, receipt = receipt), zone, at + 1)).snapshot
        val redone = TaskCompletionReducer.reduce(undone, FlowCommand.SetTaskDone(6, true, at + 1, "done-2", at + 1), zone, mapOf("simple" to 10.0)) as FlowCommandResult.Applied
        val replay = TaskUndo.reduce(redone.snapshot, FlowCommand.UndoTask(6, "undo-replay", at + 2, receipt = receipt), zone, at + 2)
        assertEquals("stale-undo", (replay as FlowCommandResult.Rejected).code)
        assertTrue(redone.snapshot.tasks.single().done)
        assertEquals(at + 1, ((redone.snapshot.data["taskHistory"] as JsonArray).last().jsonObject["at"]).toString().toLong())
    }

    @Test fun deferralReceiptRestoresOnlyWhenCurrentTaskStillMatches() {
        val before = snapshot("""{"id":2,"created":"c","title":"Later","type":"simple","done":false,"repeat":"none"}""")
        val deferred = TaskDeferralReducer.reduce(before, FlowCommand.DeferTask(2, QuestDeferralKind.NOT_TODAY, at, null, "defer", at), zone) as FlowCommandResult.Applied
        val offered = TaskUndo.offer(before, deferred, "defer", 2, at)
        val result = TaskUndo.reduce(deferred.snapshot, FlowCommand.UndoTask(2, "undo", at + 1, receipt = offered.undoReceipt), zone, at + 1)
        assertEquals(before.tasks.single().raw, applied(result).snapshot.tasks.single().raw)
    }

    @Test fun delayedYesterdayCompletionOnlyReopensHistoricalOccurrence() {
        val yesterday = Instant.parse("2024-02-29T10:00:00Z").toEpochMilli()
        val original = snapshot("""{"id":3,"created":"c","title":"Daily","type":"simple","done":false,"repeat":"daily"}""")
        val completed = TaskCompletionReducer.reduce(original, FlowCommand.SetTaskDone(3, true, yesterday, "done", yesterday), zone, mapOf("simple" to 10.0), now = yesterday) as FlowCommandResult.Applied
        val resetCurrent = FlowSnapshot.fromData(JsonObject(completed.snapshot.data + ("tasks" to JsonArray(listOf(Json.parseToJsonElement("""{"id":3,"created":"c","title":"Daily","type":"simple","done":false,"repeat":"daily"}""").jsonObject)))))
        val payload = native(3, "c", "done", yesterday, yesterday + 5_000, original.tasks.single().raw)
        val undone = applied(TaskUndo.reduce(resetCurrent, FlowCommand.UndoTask(3, "undo", yesterday + 5_000, nativePayload = payload), zone, at)).snapshot
        assertEquals(resetCurrent.tasks.single().raw, undone.tasks.single().raw)
        val reopened = (undone.data["taskHistory"] as JsonArray).last().jsonObject
        assertEquals("reopened", reopened["kind"].toString().trim('"'))
        assertEquals("3", reopened["taskId"].toString())
        assertEquals("\"c\"", reopened["taskCreated"].toString())
        assertEquals("\"2024-02-29\"", reopened["date"].toString())
        assertEquals("10", undone.data["xpTotal"].toString())
    }

    @Test fun nativeUndoForDifferentSameDayOccurrenceOnlyReopensOriginalHistory() {
        val original = snapshot("""{"id":5,"created":"c","title":"Zone task","type":"simple","done":false,"repeat":"everyzone","zoneId":7,"zoneOccurrenceKey":"2024-03-01:7:09:00"}""")
        val completed = TaskCompletionReducer.reduce(original, FlowCommand.SetTaskDone(5, true, at, "done", at), zone, mapOf("simple" to 10.0)) as FlowCommandResult.Applied
        val originalTask = completed.snapshot.tasks.single().raw
        val currentTask = JsonObject(originalTask.toMutableMap().apply {
            put("occurrenceId", JsonPrimitive("5@c|zone:2024-03-01:7:18:00"))
            put("zoneOccurrenceKey", JsonPrimitive("2024-03-01:7:18:00"))
        })
        val current = FlowSnapshot.fromData(JsonObject(completed.snapshot.data + ("tasks" to JsonArray(listOf(currentTask)))))
        val payload = native(5, "c", "done", at, at + 2_000, original.tasks.single().raw)
        val undone = applied(TaskUndo.reduce(current, FlowCommand.UndoTask(5, "undo", at + 2_000, nativePayload = payload), zone, at + 2_000)).snapshot
        assertEquals(currentTask, undone.tasks.single().raw)
        val reopened = (undone.data["taskHistory"] as JsonArray).last().jsonObject
        assertEquals("reopened", reopened["kind"].toString().trim('"'))
        assertEquals("5", reopened["taskId"].toString())
        assertEquals("\"c\"", reopened["taskCreated"].toString())
        assertEquals("\"2024-03-01\"", reopened["date"].toString())
    }

    @Test fun nativeUndoAcceptsTenSecondBoundaryAndRejectsOutsideIt() {
        val beforeTask = Json.parseToJsonElement("""{"id":4,"created":"c","title":"Task","type":"simple","done":false}""").jsonObject
        val doneTask = Json.parseToJsonElement("""{"id":4,"created":"c","title":"Task","type":"simple","done":true,"doneDate":"2024-03-01","occurrenceId":"4@c|once","completionCredited":true}""").jsonObject
        val event = TaskHistory.event(doneTask, "completed", at, "4@c|once", 0, zone)
        val state = FlowSnapshot.fromData(JsonObject(snapshot(Json.encodeToString(JsonObject.serializer(), beforeTask)).data + mapOf(
            "tasks" to JsonArray(listOf(doneTask)), "taskHistory" to JsonArray(listOf(event)),
        )))
        val boundary = native(4, "c", "done", at, at + 10_000, beforeTask)
        assertTrue(TaskUndo.reduce(state, FlowCommand.UndoTask(4, "ok", at + 10_000, nativePayload = boundary), zone, at + 10_000) is FlowCommandResult.Applied)
        val outside = native(4, "c", "done", at, at + 10_001, beforeTask)
        assertTrue(TaskUndo.reduce(state, FlowCommand.UndoTask(4, "late", at + 10_001, nativePayload = outside), zone, at + 10_001) is FlowCommandResult.Rejected)
    }

    private fun native(id: Long, created: String, kind: String, source: Long, undone: Long, before: JsonObject) = JsonObject(mapOf(
        "id" to JsonPrimitive(id), "created" to JsonPrimitive(created),
        "kind" to JsonPrimitive(kind), "sourceAt" to JsonPrimitive(source),
        "undoneAt" to JsonPrimitive(undone), "before" to before,
    ))
    private fun snapshot(task: String, extra: String = "") = FlowSnapshot.parse("""{"formatVersion":2,"progressionVersion":2,"xp":0,"xpTotal":0,"level":1,"done":0,"unlocked":[],"zones":[],"tasks":[$task]$extra}""")
    private fun applied(result: FlowCommandResult) = result as FlowCommandResult.Applied
}
