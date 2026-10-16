package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventReducerTest {
    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()
    private val tapAt = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()
    private val yesterday = Instant.parse("2026-10-15T10:00:00Z").toEpochMilli()
    private val created = Instant.parse("2026-10-14T08:00:00Z").toEpochMilli()
    private val engine = FlowEngine(mapOf("simple" to 10.0, "counter" to 10.0, "sched" to 10.0, "zonelinked" to 8.0))

    @Test
    fun capturedNotificationTaskCanBeCompletedBeforeAnyActivityOpens() {
        val source = snapshot()
        val capture = captureEvent(1700, "  Із шторки  ", tapAt)
        val captured = applied(source, capture, tapAt)
        assertEquals(1700L, captured.snapshot.tasks.single().id)
        assertEquals("Із шторки", captured.snapshot.tasks.single().title)
        assertEquals(10.0, captured.snapshot.xpTotal, 0.0)

        val completed = applied(captured.snapshot, "task_done:1700", tapAt + 1_000L)
        assertTrue(completed.snapshot.tasks.single().done)
        assertEquals(20.0, completed.snapshot.xpTotal, 0.0)
        assertEquals(JsonPrimitive(1), completed.snapshot.data["done"])
        val history = completed.snapshot.data["taskHistory"] as JsonArray
        assertEquals(1, history.size)
        assertEquals("completed", (history.single() as JsonObject).text("kind"))

        val captureAgain = applied(completed.snapshot, capture, tapAt, "capture-again")
        assertEquals(completed.snapshot.data, captureAgain.snapshot.data)
        assertTrue(captureAgain.events.isEmpty())
    }

    @Test
    fun captureJsonRetainsColonsAndNewlinesAfterItsProtocolPrefix() {
        val title = "План: оренда\nі рахунки: сьогодні"
        val payload = buildJsonObject { put("title", "  $title  "); put("createdAt", tapAt) }
        val prettyPayload = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), payload)
        val result = applied(snapshot(), "capture_task_v2:1701:$prettyPayload", tapAt)
        assertEquals(title, result.snapshot.tasks.single().title)
        assertEquals(Instant.ofEpochMilli(tapAt).toString(),
            Instant.parse(result.snapshot.tasks.single().raw.text("created")).toString())
    }

    @Test
    fun legacyCaptureRetainsTitleSuffixAndAwardsCaptureExperience() {
        val result = applied(snapshot(), "capture_task:  Забрати: посилку\nдо вечора  ", tapAt)
        assertEquals("Забрати: посилку\nдо вечора", result.snapshot.tasks.single().title)
        assertEquals(10.0, result.snapshot.xpTotal, 0.0)
    }

    @Test
    fun noteAliasesAwardOnePointWhileSharedTextUsesThoughtWithoutBonus() {
        val first = applied(snapshot(), "capture_note:  Перша: думка\nще рядок  ", tapAt).snapshot
        assertEquals(1.0, first.xpTotal, 0.0)
        assertEquals("Перша: думка\nще рядок", first.qnotes.single().text("txt"))
        assertEquals("impulse", first.qnotes.single().text("folder"))

        val second = applied(first, "note: Друга думка", tapAt + 1L).snapshot
        assertEquals(2.0, second.xpTotal, 0.0)
        assertEquals(2, second.qnotes.size)

        val shared = applied(second, "share_text:  Спільний: текст\nз рядком  ", tapAt + 2L).snapshot
        assertEquals(2.0, shared.xpTotal, 0.0)
        assertEquals(3, shared.qnotes.size)
        assertEquals("Спільний: текст\nз рядком", shared.qnotes.first().text("txt"))
        assertEquals("thought", shared.qnotes.first().text("folder"))
    }

    @Test
    fun malformedAndUnknownProtocolsRejectWithoutChangingSource() {
        val source = snapshot(task())
        val before = source.toJson()
        val invalid = listOf(
            "", "future_command:7", "task_done:", "task_done:7:extra", "task_inc:-1",
            "task_skip:9007199254740992", "task_defer:7:not-json", "task_undo:{", "sched_fired:7:not-time",
            "capture_task:  ", "capture_note:  ", "note: \n ", "share_text: ",
            "capture_task_v2:0:{\"title\":\"No ID\",\"createdAt\":$tapAt}",
            "capture_task_v2:8:{\"title\":\"No time\"}",
            "capture_task_v2:8:{\"title\":\"Wrong time type\",\"createdAt\":\"$tapAt\"}",
            captureEvent(8, "Mismatched time", tapAt - 60_001L),
            "open_insight:2026-10-16:extra",
        )
        invalid.forEachIndexed { index, event ->
            val result = apply(source, event, tapAt, "invalid-$index")
            assertTrue("Expected rejection for '$event', got $result", result is FlowCommandResult.Rejected)
            assertEquals(before, source.toJson())
        }
    }

    @Test
    fun validActionsForMissingOrTrashedTasksAreIgnored() {
        val source = snapshot(task(fields = obj("""{"trashed":true}""")))
        listOf("task_done:999", "task_inc:999", "task_dec:999", "task_skip:999", "open_task:999", "task_done:7").forEach { event ->
            val result = applied(source, event, tapAt)
            assertEquals(event, source.data, result.snapshot.data)
            assertTrue(result.events.isEmpty())
        }
    }

    @Test
    fun oldTapCannotAffectNewTaskThatReusesItsId() {
        val source = snapshot(task(createdAt = tapAt + 1L))
        listOf("task_done:7", "task_inc:7", "task_skip:7", "open_task:7").forEach { event ->
            val result = applied(source, event, tapAt)
            assertEquals(event, source.data, result.snapshot.data)
            assertTrue(result.events.isEmpty())
        }
    }

    @Test
    fun yesterdayDailyCompletionAddsPastHistoryWithoutCompletingToday() {
        val source = snapshot(task(fields = obj("""{"repeat":"daily","counterDay":"2026-10-16","custom":{"kept":true}}""")))
        val result = applied(source, "task_done:7", yesterday)
        assertEquals(source.tasks.single().raw, result.snapshot.tasks.single().raw)
        assertFalse(result.snapshot.tasks.single().done)
        assertEquals(10.0, result.snapshot.xpTotal, 0.0)
        val history = result.snapshot.data["taskHistory"] as JsonArray
        assertEquals(1, history.size)
        val event = history.single() as JsonObject
        assertEquals("completed", event.text("kind"))
        assertEquals("2026-10-15", event.text("date"))
        assertEquals(JsonPrimitive(yesterday), event["at"])
        val repeated = applied(result.snapshot, "task_done:7", yesterday, "duplicate-past")
        assertEquals(result.snapshot.data, repeated.snapshot.data)
    }

    @Test
    fun yesterdayCounterTapCreatesWarningNoteWithoutChangingTodayOrAwardingXp() {
        val source = snapshot(task(type = "counter", fields = obj("""{"repeat":"daily","counter":4,"counterCreditedProgress":4,"counterDay":"2026-10-16"}""")))
        val result = applied(source, "task_inc:7", yesterday)
        assertEquals(source.tasks.single().raw, result.snapshot.tasks.single().raw)
        assertEquals(0.0, result.snapshot.xpTotal, 0.0)
        assertEquals(source.data["taskHistory"], result.snapshot.data["taskHistory"])
        val warning = result.snapshot.qnotes.single()
        assertEquals("impulse", warning.text("folder"))
        assertTrue(warning.text("txt")!!.contains("task_inc:7"))
        assertTrue(warning.text("txt")!!.contains("Task 7"))
    }

    @Test
    fun duplicateIntervalTapIsIgnoredEvenWhenReadAfterNextIntervalBecameDue() {
        val source = snapshot(task(fields = obj("""{"repeat":"interval","repeatMs":60000,"nextRepeatAt":0}""")))
        val first = applied(source, "task_done:7", tapAt).snapshot
        assertTrue(first.tasks.single().done)
        assertEquals(JsonPrimitive(tapAt + 60_000L), first.tasks.single().raw["nextRepeatAt"])
        val duplicate = applied(first, "task_done:7", tapAt + 1_000L, "interval-duplicate")
        assertEquals(first.data, duplicate.snapshot.data)
        assertTrue(duplicate.events.isEmpty())
    }

    @Test
    fun actionResetsOnlyTappedTaskAtTapTimeAndLeavesFinalBatchTickToRepository() {
        val oldProgress = obj("""{"repeat":"daily","counterDay":"2026-10-15","counter":7,"counterCreditedProgress":7}""")
        val source = snapshot(task(type = "counter", fields = oldProgress), task(8, "counter", fields = oldProgress))
        val result = applied(source, "task_inc:7", tapAt)
        assertEquals(JsonPrimitive(1), result.snapshot.tasks.first().raw["counter"])
        assertEquals("2026-10-16", result.snapshot.tasks.first().raw.text("counterDay"))
        assertEquals(source.tasks.last().raw, result.snapshot.tasks.last().raw)
        assertEquals(2.0, result.snapshot.xpTotal, 0.0)
    }

    @Test
    fun pastDayZoneCompletionIsRecordedBeforeOlderZoneGuardWithoutChangingLiveCounter() {
        val source = snapshot(
            task(type = "counter", fields = obj("""{"repeat":"everyzone","zoneId":7,"zoneOccurrenceKey":"2026-10-16:7:11:00","counterDay":"2026-10-16","counter":4,"counterCreditedProgress":4}""")),
            extra = obj("""{"zones":[{"id":7,"nm":"Work","slots":[{"s":"09:00","e":"10:00"},{"s":"11:00","e":"13:00"}]}]}"""),
        )
        val oldTap = Instant.parse("2026-10-15T09:30:00Z").toEpochMilli()
        val result = applied(source, "task_done:7", oldTap)
        assertEquals(source.tasks.single().raw, result.snapshot.tasks.single().raw)
        assertFalse(result.snapshot.tasks.single().done)
        assertEquals(10.0, result.snapshot.xpTotal, 0.0)
        val history = result.snapshot.data["taskHistory"] as JsonArray
        assertEquals(1, history.size)
        val completion = history.single() as JsonObject
        assertEquals("completed", completion.text("kind"))
        assertEquals("2026-10-15", completion.text("date"))
        assertEquals(JsonPrimitive(oldTap), completion["at"])
        assertTrue(completion.text("occurrenceId")!!.endsWith("|zone:2026-10-15:7:09:00"))
    }

    @Test
    fun tapFromOlderZoneOccurrenceCannotRollBackCurrentCounter() {
        val source = snapshot(
            task(type = "counter", fields = obj("""{"repeat":"everyzone","zoneId":7,"zoneOccurrenceKey":"2026-10-16:7:11:00","counter":4,"counterCreditedProgress":4}""")),
            extra = obj("""{"zones":[{"id":7,"nm":"Work","slots":[{"s":"09:00","e":"10:00"},{"s":"11:00","e":"13:00"}]}]}"""),
        )
        val oldTap = Instant.parse("2026-10-16T09:30:00Z").toEpochMilli()
        val result = applied(source, "task_inc:7", oldTap)
        assertEquals(source.data, result.snapshot.data)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun delayedReminderFromOlderZoneOccurrenceCannotResetCurrentProgress() {
        val source = snapshot(
            task(type = "alarm", fields = obj("""{"repeat":"everyzone","zoneId":7,"zoneOccurrenceKey":"2026-10-16:7:11:00","alarmTime":"09:30","counterEnabled":true,"counter":4,"counterCreditedProgress":4}""")),
            extra = obj("""{"zones":[{"id":7,"nm":"Work","slots":[{"s":"09:00","e":"10:00"},{"s":"11:00","e":"13:00"}]}]}"""),
        )
        val delivered = Instant.parse("2026-10-16T09:30:00Z").toEpochMilli()
        val result = applied(source, "sched_fired:7:$delivered", now)
        assertEquals(source.data, result.snapshot.data)
        assertTrue(result.events.isEmpty())
        val tick = engine.apply(result.snapshot, FlowCommand.LifecycleTick("after-reminder", now), zone, now) as FlowCommandResult.Applied
        assertEquals(source.tasks.single().raw, tick.snapshot.tasks.single().raw)
    }

    @Test
    fun reminderFromBeforeTaskCreationCannotAffectReusedId() {
        val source = snapshot(task(type = "alarm", createdAt = tapAt + 3_600_000L,
            fields = obj("""{"alarmTime":"09:30"}""")))
        val delivered = Instant.parse("2026-10-16T09:30:00Z").toEpochMilli()
        val result = applied(source, "sched_fired:7:$delivered", now)
        assertEquals(source.data, result.snapshot.data)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun mismatchedScheduledSignalsAreIgnoredAndMatchingPreReminderSetsOnlyItsFlag() {
        val source = snapshot(task(type = "sched", fields = obj("""{"schedDate":"2026-10-16","schedTime":"12:00","remindBeforeMinutes":10}""")))
        listOf("sched_fired:7:${now + 60_000L}", "sched_fired:7_pre:${now - 540_000L}").forEach { event ->
            val ignored = applied(source, event, now)
            assertEquals(source.data, ignored.snapshot.data)
            assertTrue(ignored.events.isEmpty())
        }
        val matched = applied(source, "sched_fired:7_pre:${now - 600_000L}", now - 600_000L).snapshot
        assertEquals(JsonPrimitive(true), matched.tasks.single().raw["firedPre"])
        assertEquals(JsonPrimitive(false), matched.tasks.single().raw["firedSched"])
        assertEquals(JsonPrimitive(false), matched.tasks.single().raw["alarmFired"])
        assertEquals(0.0, matched.xpTotal, 0.0)
        val fired = applied(matched, "sched_fired:7:$now", now).snapshot
        assertEquals(JsonPrimitive(true), fired.tasks.single().raw["firedSched"])
        assertEquals(JsonPrimitive(true), fired.tasks.single().raw["alarmFired"])
    }

    private fun captureEvent(id: Long, title: String, at: Long) = "capture_task_v2:$id:" +
        buildJsonObject { put("title", title); put("createdAt", at) }

    private fun apply(snapshot: FlowSnapshot, event: String, at: Long, commandId: String = "notification") =
        engine.apply(snapshot, FlowCommand.ApplyNotificationEvent(event, commandId, at), zone, now)

    private fun applied(snapshot: FlowSnapshot, event: String, at: Long, commandId: String = "notification"): FlowCommandResult.Applied {
        val result = apply(snapshot, event, at, commandId)
        assertTrue("Expected Applied for '$event', got $result", result is FlowCommandResult.Applied)
        return result as FlowCommandResult.Applied
    }

    private fun task(id: Long = 7, type: String = "simple", createdAt: Long = created, fields: JsonObject = JsonObject(emptyMap())) =
        JsonObject(TaskFactory.create(id, "Task $id", type, createdAt) + fields)

    private fun snapshot(vararg tasks: JsonObject, extra: JsonObject = JsonObject(emptyMap())): FlowSnapshot {
        val base = obj("""{"formatVersion":2,"nid":300,"xp":0,"xpTotal":0,"level":1,"done":0,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"taskHistory":[],"qnotes":[],"recur":[],"zones":[],"priorities":[null,null,null],"folders":[{"id":"impulse","nm":"Імпульс","ico":"⚡"},{"id":"thought","nm":"Думка","ico":"💭"}],"customRoot":{"kept":true}}""")
        return FlowSnapshot.fromData(JsonObject(base + ("tasks" to JsonArray(tasks.toList())) + extra))
    }

    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.content
}
