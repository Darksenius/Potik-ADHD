package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class TaskDeferralReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-16T16:00:00Z").toEpochMilli() // 19:00
    private val engine = FlowEngine(mapOf("simple" to 10.0))
    private val initial = FlowSnapshot.parse("""{
      "tasks":[{"id":300,"title":"Підмести","type":"simple","zoneId":7,"repeat":"everyzone",
        "created":"2026-10-16T09:00:00.000Z","planDate":"2026-10-16","done":false,
        "completionCredited":false,"note":"Деталі","custom":{"keep":true},"alarmFired":true}],
      "zones":[{"id":7,"nm":"Вечір","slots":[{"s":"18:00","e":"20:00"}]},
        {"id":8,"nm":"Пізній вечір","slots":[{"s":"20:00","e":"22:00"}]}],
      "xpTotal":15,"taskHistory":[],"custom":"preserved"
    }""")

    @Test fun nextZonePreservesOriginalBindingsAndHistoryAndAwardsNothing() {
        val result = engine.apply(initial, command(), zone, at) as FlowCommandResult.Applied
        val before = initial.tasks.single().raw
        val task = result.snapshot.tasks.single().raw
        for (field in listOf("zoneId", "repeat", "planDate", "note", "custom", "completionCredited")) {
            assertEquals(field, before[field], task[field])
        }
        assertEquals(initial.data["xpTotal"], result.snapshot.data["xpTotal"])
        assertEquals(initial.data["taskHistory"], result.snapshot.data["taskHistory"])
        assertEquals(initial.data["custom"], result.snapshot.data["custom"])
        val d = task["questDeferral"] as JsonObject
        assertEquals("next-zone", d["kind"].toString().trim('"'))
        assertEquals("8", d["targetZoneId"].toString())
        assertEquals("2026-10-16:7:18:00", task["occurrenceId"].toString().trim('"').substringAfter("zone:"))
        assertEquals("false", task["alarmFired"].toString())
        assertFalse(task.containsKey("completionDeferral"))
        assertEquals(listOf("task_deferred"), result.events.map { it.type })
        val repeated = engine.apply(result.snapshot, command(), zone, at) as FlowCommandResult.Applied
        assertSame(result.snapshot, repeated.snapshot)
        assertTrue(repeated.events.isEmpty())
    }

    @Test fun validExplicitDeferralIsRetainedButMismatchedKindIsRecomputed() {
        val details = Json.parseToJsonElement("""{"kind":"not-today","requestedAt":1,"sourceZoneId":7,"targetZoneId":7,"startAt":2,"future":"kept"}""") as JsonObject
        val explicit = engine.apply(initial, command(QuestDeferralKind.NOT_TODAY, details), zone, at) as FlowCommandResult.Applied
        assertEquals(details, explicit.snapshot.tasks.single().raw["questDeferral"])
        val recomputed = engine.apply(initial, command(QuestDeferralKind.NEXT_ZONE, details), zone, at) as FlowCommandResult.Applied
        assertEquals("\"next-zone\"", (recomputed.snapshot.tasks.single().raw["questDeferral"] as JsonObject)["kind"].toString())
    }

    @Test fun absentDoneOrUnavailableTasksAreNoOpsAndBadTimesReject() {
        val missing = engine.apply(initial, command().copy(taskId = 999), zone, at) as FlowCommandResult.Applied
        assertSame(initial, missing.snapshot)
        for (flag in listOf("done", "trashed", "someday")) {
            val blocked = FlowSnapshot.parse("""{"tasks":[{"id":300,"title":"x","$flag":true}]}""")
            val result = engine.apply(blocked, command(), zone, at) as FlowCommandResult.Applied
            assertSame(blocked, result.snapshot)
        }
        assertTrue(engine.apply(initial, command().copy(requestedAt = -1), zone, at) is FlowCommandResult.Rejected)
    }

    private fun command(kind: QuestDeferralKind = QuestDeferralKind.NEXT_ZONE, details: JsonObject? = null) =
        FlowCommand.DeferTask(300, kind, at, details, "defer", at)
}
