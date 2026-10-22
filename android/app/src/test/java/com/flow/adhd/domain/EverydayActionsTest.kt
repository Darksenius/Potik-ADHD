package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class EverydayActionsTest {
    private val zone = ZoneId.of("UTC")
    private val at = Instant.parse("2026-10-16T19:00:00Z").toEpochMilli()
    private val engine = FlowEngine(mapOf("simple" to 10.0, "counter" to 2.0, "sched" to 10.0, "alarm" to 10.0))
    private fun json(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private fun apply(state: FlowSnapshot, command: FlowCommand): FlowCommandResult.Applied =
        engine.apply(state, command, zone, at).let { it as? FlowCommandResult.Applied ?: error("Rejected: $it") }
    private fun initial(extra: JsonObject = JsonObject(emptyMap())) = FlowSnapshot.parse(JsonObject(FlowDefaults.create() + extra).toString())

    @Test fun dayNotePreservesTheLogAndRecordsOnlyActualActivityWithoutXp() {
        val source = initial(json("""{"planDayLog":{"2026-10-16":{"focus":["Дім"],"energy":4,"tasksDone":7,"custom":true}}}"""))
        val written = apply(source, FlowCommand.SetPlanDayNote("2026-10-16", "Спокійний день", "note", at))
        val log = written.snapshot.data["planDayLog"]!!.jsonObject["2026-10-16"]!!.jsonObject
        assertEquals(JsonPrimitive(7), log["tasksDone"])
        assertEquals(JsonPrimitive(true), log["custom"])
        assertEquals(JsonPrimitive("Спокійний день"), log["note"])
        assertEquals(JsonArray(listOf(JsonPrimitive("2026-10-16"))), written.snapshot.data["progressDays"])
        assertEquals(JsonPrimitive(0), written.snapshot.data["xpTotal"])
        val cleared = apply(initial(), FlowCommand.SetPlanDayNote("2026-10-16", "", "empty", at))
        assertEquals(JsonArray(emptyList()), cleared.snapshot.data["progressDays"])
    }

    @Test fun resettingOneDayRestoresItsRuleWithoutDeletingItsTasksOrNotes() {
        val source = initial(json("""{"tasks":[{"id":300,"title":"Виконати","planDate":"2026-10-16"}],
          "planDayZones":{"2026-10-16":[],"2026-10-17":[]},"planRestDays":{"2026-10-16":false},
          "planDayOff":{"2026-10-16":[5]},"planDayLog":{"2026-10-16":{"note":"Залишити"}},
          "dayTemplates":[{"id":"home","name":"Дім","blocks":[{"zoneId":5,"s":"18:00","e":"22:00"}]}],
          "planRules":[{"id":"rule","start":"2026-10-16","end":"2026-10-20","days":[{"tplId":"home"}]}]}"""))
        val reset = apply(source, FlowCommand.ResetPlanDay("2026-10-16", "reset", at)).snapshot
        assertEquals(1, PlannerProjection.dayBlocks(reset.data, "2026-10-16").size)
        assertTrue((reset.data["planDayZones"] as JsonObject).containsKey("2026-10-17"))
        assertEquals(source.data["tasks"], reset.data["tasks"])
        assertEquals(source.data["planDayLog"], reset.data["planDayLog"])
        assertEquals(JsonArray(emptyList()), reset.data["progressDays"])
    }

    @Test fun perDayZoneEditingCopiesSlotsAndHidesOnlyTheBaselineForThatDate() {
        val source = initial()
        val edited = apply(source, FlowCommand.EditPlanZoneForDay("2026-10-16", 5, "edit", at)).snapshot
        val blocks = PlannerProjection.dayBlocks(edited.data, "2026-10-16")
        assertEquals(1, blocks.size)
        assertEquals(JsonPrimitive(true), blocks[0]["ov"])
        assertEquals(JsonArray(listOf(JsonPrimitive(5))), edited.data["planDayOff"]!!.jsonObject["2026-10-16"])
        assertTrue(ZoneCalendar.dayRanges(edited.data, "2026-10-16").any { it.zoneId == 5L && it.planned })
        val restored = apply(edited, FlowCommand.SetPlanZoneOff("2026-10-16", 5, false, "restore", at)).snapshot
        assertFalse(restored.data["planDayOff"]!!.jsonObject.containsKey("2026-10-16"))
        assertEquals(blocks, PlannerProjection.dayBlocks(restored.data, "2026-10-16"))
        assertTrue(engine.apply(source, FlowCommand.EditPlanZoneForDay("2026-02-30", 5, "bad", at), zone, at) is FlowCommandResult.Rejected)
    }

    @Test fun globalZoneOffAndOnAreExplicitAndDoNotAwardXp() {
        val source = initial()
        val off = apply(source, FlowCommand.SetZoneOff(5, true, "off", at)).snapshot
        assertFalse(ZoneCalendar.dayRanges(off.data, "2026-10-16").any { it.zoneId == 5L })
        val offAgain = apply(off, FlowCommand.SetZoneOff(5, true, "off-again", at)).snapshot
        assertEquals(off.data, offAgain.data)
        val on = apply(offAgain, FlowCommand.SetZoneOff(5, false, "on", at)).snapshot
        assertTrue(ZoneCalendar.dayRanges(on.data, "2026-10-16").any { it.zoneId == 5L })
        assertEquals(JsonPrimitive(0), on.data["xpTotal"])
        assertEquals(JsonArray(emptyList()), on.data["progressDays"])
    }

    @Test fun pastCompletionLeavesTodaysProgressAndPrioritiesUntouchedAndCannotFarmXp() {
        val task = TaskFactory.create(300, "Розминка", "counter", at - 3 * 86_400_000L).toMutableMap().apply {
            put("repeat", JsonPrimitive("daily")); put("counter", JsonPrimitive(2)); put("counterDay", JsonPrimitive("2026-10-16"))
        }.let(::JsonObject)
        val source = initial(buildJsonObject {
            put("tasks", JsonArray(listOf(task))); put("priorities", JsonArray(listOf(JsonPrimitive(300), JsonNull, JsonNull)))
        })
        val tap = at - 86_400_000L
        val recorded = apply(source, FlowCommand.RecordPastTaskDone(300, tap, "yesterday", tap)).snapshot
        assertEquals(source.data["tasks"], recorded.data["tasks"])
        assertEquals(source.data["priorities"], recorded.data["priorities"])
        val event = recorded.data["taskHistory"]!!.jsonArray.single().jsonObject
        assertEquals(JsonPrimitive("2026-10-15"), event["date"])
        assertFalse(event.containsKey("counter"))
        assertEquals(JsonPrimitive(2), recorded.data["xpTotal"])
        val repeat = apply(recorded, FlowCommand.RecordPastTaskDone(300, tap, "repeat-yesterday", tap)).snapshot
        assertEquals(recorded.data, repeat.data)
    }

    @Test fun reminderReceiptMustMatchTheCurrentScheduleAndDoesNotEarnActivity() {
        val task = TaskFactory.create(300, "Подзвонити", "sched", at - 86_400_000L).toMutableMap().apply {
            put("schedDate", JsonPrimitive("2026-10-16")); put("schedTime", JsonPrimitive("19:00"))
            put("remindBeforeMinutes", JsonPrimitive(5)); put("reminderEnabled", JsonPrimitive(true))
        }.let(::JsonObject)
        val source = initial(buildJsonObject { put("tasks", JsonArray(listOf(task))) })
        val wrong = apply(source, FlowCommand.MarkTaskReminderFired(300, at + 60_000, false, "wrong", at)).snapshot
        assertEquals(source.data, wrong.data)
        val pre = apply(source, FlowCommand.MarkTaskReminderFired(300, at - 300_000, true, "pre", at)).snapshot
        assertEquals(JsonPrimitive(true), pre.tasks.single().raw["firedPre"])
        assertEquals(JsonPrimitive(false), pre.tasks.single().raw["firedSched"])
        val due = apply(pre, FlowCommand.MarkTaskReminderFired(300, at, false, "due", at)).snapshot
        assertEquals(JsonPrimitive(true), due.tasks.single().raw["firedSched"])
        assertEquals(JsonPrimitive(true), due.tasks.single().raw["alarmFired"])
        assertEquals(JsonArray(emptyList()), due.data["progressDays"])
    }

    @Test fun quickReminderReceiptAcknowledgesOnlyItsExactCurrentDeadline() {
        val first = at + 3_600_000L
        val task = TaskFactory.create(300, "Подзвонити", "simple", at).toMutableMap().apply {
            put("quickReminderAt", JsonPrimitive(first))
        }.let(::JsonObject)
        val source = initial(buildJsonObject { put("tasks", JsonArray(listOf(task))) })
        val stale = apply(source, FlowCommand.MarkTaskReminderFired(300, first - 1, false, "stale", at)).snapshot
        assertEquals(source.data, stale.data)
        val acknowledged = apply(source, FlowCommand.MarkTaskReminderFired(300, first, false, "quick", at)).snapshot
        assertEquals(JsonPrimitive(first), acknowledged.tasks.single().raw["quickReminderFiredAt"])
        val repeated = apply(acknowledged, FlowCommand.MarkTaskReminderFired(300, first, false, "again", at)).snapshot
        assertEquals(acknowledged.data, repeated.data)

        val rescheduled = JsonObject(task + ("quickReminderAt" to JsonPrimitive(first + 60_000)))
        val newer = initial(buildJsonObject { put("tasks", JsonArray(listOf(rescheduled))) })
        val oldReceipt = apply(newer, FlowCommand.MarkTaskReminderFired(300, first, false, "old-after-edit", at)).snapshot
        assertFalse(oldReceipt.tasks.single().raw.containsKey("quickReminderFiredAt"))
    }

    @Test fun completingBeforeQuickDeadlineAcknowledgesOnlyTheCurrentRepeatOccurrence() {
        val deadline = at + 3_600_000L
        val task = TaskFactory.create(300, "Daily", "simple", at).toMutableMap().apply {
            put("repeat", JsonPrimitive("daily"))
            put("quickReminderAt", JsonPrimitive(deadline))
        }.let(::JsonObject)
        val source = initial(buildJsonObject { put("tasks", JsonArray(listOf(task))) })
        val completed = apply(source, FlowCommand.SetTaskDone(300, true, at, "done-before-reminder", at)).snapshot
        assertEquals(JsonPrimitive(deadline), completed.tasks.single().raw["quickReminderFiredAt"])
        assertNull(TaskSchedule.nextReminderAt(completed.tasks.single().raw, ProjectionContext(deadline + 86_400_000L, emptyList(), zone)))
    }

    @Test fun notificationNotePreservesTheCaptureRewardAndNormalNotesDoNotGetIt() {
        val source = initial()
        val captured = apply(source, FlowCommand.CaptureNote("  Швидка думка  ", "capture", at)).snapshot
        assertEquals(JsonPrimitive("Швидка думка"), captured.qnotes.single()["txt"])
        assertEquals(JsonPrimitive("impulse"), captured.qnotes.single()["folder"])
        assertEquals(JsonPrimitive(1), captured.data["xpTotal"])
        val ordinary = apply(source, FlowCommand.AddQuickNote(buildJsonObject { put("txt", "Нотатка") }, "note", at)).snapshot
        assertEquals(JsonPrimitive(0), ordinary.data["xpTotal"])
    }
}
