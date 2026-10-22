package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
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

    @Test fun explicitDeferralCancelsSimpleQuickReminderAndUndoRestoresBothFields() {
        val due = at + 60 * 60_000L
        val taskWithReminder = JsonObject(initial.tasks.single().raw + mapOf(
            "quickReminderAt" to kotlinx.serialization.json.JsonPrimitive(due),
            "quickReminderFiredAt" to kotlinx.serialization.json.JsonPrimitive(due - 120_000L),
        ))
        val source = FlowSnapshot.fromData(JsonObject(initial.data + ("tasks" to JsonArray(listOf(taskWithReminder)))) )

        for (kind in listOf(QuestDeferralKind.NEXT_ZONE, QuestDeferralKind.NOT_TODAY)) {
            val deferred = engine.apply(source, command(kind).copy(commandId = "quick-${kind.name}"), zone, at) as FlowCommandResult.Applied
            val task = deferred.snapshot.tasks.single().raw
            assertFalse(task.containsKey("quickReminderAt"))
            assertFalse(task.containsKey("quickReminderFiredAt"))
            assertEquals(taskWithReminder["zoneId"], task["zoneId"])
            assertEquals(taskWithReminder["planDate"], task["planDate"])

            val offered = TaskUndo.offer(source, deferred, "defer", 300, at)
            val restored = TaskUndo.reduce(deferred.snapshot,
                FlowCommand.UndoTask(300, "undo-${kind.name}", at + 1, receipt = offered.undoReceipt), zone, at + 1
            ) as FlowCommandResult.Applied
            assertEquals(due, restored.snapshot.tasks.single().raw["quickReminderAt"]?.toString()?.toLong())
            assertEquals(due - 120_000L, restored.snapshot.tasks.single().raw["quickReminderFiredAt"]?.toString()?.toLong())
        }
    }

    @Test fun scheduledNextZoneDeferralIsOneHourAcrossZoneAndMidnightWhileOtherTypesKeepZoneRule() {
        val late = Instant.parse("2026-10-16T20:30:00Z").toEpochMilli() // 23:30 local
        val scheduled = FlowSnapshot.parse("""{"tasks":[{"id":300,"title":"Call","type":"sched","zoneId":7,"planDate":"2026-10-16","schedDate":"2026-10-16","schedTime":"18:00","reminderEnabled":true,"repeat":"none"}],"zones":[{"id":7,"nm":"Evening","slots":[{"s":"18:00","e":"20:00"}]},{"id":8,"nm":"Late","slots":[{"s":"20:00","e":"23:59"}]}]}""")
        val result = engine.apply(scheduled, FlowCommand.DeferTask(300, QuestDeferralKind.NEXT_ZONE, late, null, "sched-defer", late), zone, late) as FlowCommandResult.Applied
        val deferred = result.snapshot.tasks.single().raw
        val detail = deferred["questDeferral"] as JsonObject
        assertEquals("time", detail["kind"]?.toString()?.trim('"'))
        assertEquals(late + 3_600_000L, detail["startAt"]?.toString()?.toLong())
        assertEquals("2026-10-16", deferred["schedDate"]?.toString()?.trim('"'))
        assertEquals("7", deferred["zoneId"]?.toString())
        val timeline = ZoneCalendar.timeline(result.snapshot.data, late, zone)
        assertEquals(late + 3_600_000L, TaskSchedule.deferralReminderAt(deferred, timeline))
        assertEquals(late + 3_600_000L, TaskSchedule.nextReminderAt(deferred, ProjectionContext(late, timeline, zone)))
        val before = late + 3_599_999L
        val beforeTimeline = ZoneCalendar.timeline(result.snapshot.data, before, zone)
        assertFalse(QuestPolicy.availability(deferred, QuestPolicy.Context(before, beforeTimeline, result.snapshot.zones, zone = zone)).eligible)
        val after = late + 3_600_000L // 00:30 next day and outside the task's original zone
        val afterTimeline = ZoneCalendar.timeline(result.snapshot.data, after, zone)
        assertTrue(QuestPolicy.availability(deferred, QuestPolicy.Context(after, afterTimeline, result.snapshot.zones, zone = zone)).eligible)

        val regular = engine.apply(initial, command(), zone, at) as FlowCommandResult.Applied
        assertEquals("next-zone", (regular.snapshot.tasks.single().raw["questDeferral"] as JsonObject)["kind"]?.toString()?.trim('"'))
    }

    private fun command(kind: QuestDeferralKind = QuestDeferralKind.NEXT_ZONE, details: JsonObject? = null) =
        FlowCommand.DeferTask(300, kind, at, details, "defer", at)
}
