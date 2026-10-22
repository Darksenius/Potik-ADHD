package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskScheduleTest {
    @Test fun weeklyRepeatKeepsItsCalendarAnchor() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"repeat":"weekly","planDate":"2026-10-18"}""")
        assertTrue(TaskSchedule.repeatDueOn(task, epoch("2026-10-25T12:00:00Z"), zone))
        assertFalse(TaskSchedule.repeatDueOn(task, epoch("2026-10-26T12:00:00Z"), zone))
        assertTrue(TaskSchedule.occursOn(task, "2026-10-25", context("2026-10-16T12:00:00Z", zone)))
        assertFalse(TaskSchedule.occursOn(task, "2026-10-17", context("2026-10-16T12:00:00Z", zone)))
    }

    @Test fun customRepeatUsesMondayFirstWeekdayArray() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"repeat":"custom","repeatDays":[true,false,false,false,false,false,true]}""")
        assertTrue(TaskSchedule.repeatDueOn(task, epoch("2026-10-19T12:00:00Z"), zone)) // Monday
        assertTrue(TaskSchedule.repeatDueOn(task, epoch("2026-10-25T12:00:00Z"), zone)) // Sunday
        assertFalse(TaskSchedule.repeatDueOn(task, epoch("2026-10-20T12:00:00Z"), zone))
    }

    @Test fun overnightWindowWrapsAcrossMidnightAndInvalidLegacyWindowStaysAccessible() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"type":"timewin","windowStart":"22:00","windowEnd":"02:00"}""")
        assertTrue(TaskSchedule.isTimeWindow(task, epoch("2026-10-16T23:59:00Z"), zone))
        assertTrue(TaskSchedule.isTimeWindow(task, epoch("2026-10-17T01:00:00Z"), zone))
        assertFalse(TaskSchedule.isTimeWindow(task, epoch("2026-10-17T03:00:00Z"), zone))
        assertTrue(TaskSchedule.isTimeWindow(json("""{"type":"timewin","windowStart":"bad"}"""), epoch("2026-10-17T03:00:00Z"), zone))
        assertTrue(TaskSchedule.isTimeWindow(json("""{"type":"timewin","windowStart":" 22:00 ","windowEnd":" 02:00 "}"""), epoch("2026-10-16T23:59:00Z"), zone))
    }

    @Test fun alarmSearchUsesLocalCalendarAcrossDstGap() {
        val zone = ZoneId.of("America/New_York")
        val now = epoch("2026-03-08T05:00:00Z") // midnight EST on the spring-forward date
        val task = json("""{"type":"alarm","alarmTime":"02:30","repeat":"none"}""")
        assertEquals(epoch("2026-03-08T07:30:00Z"), TaskSchedule.nextReminderAt(task, ProjectionContext(now, emptyList(), zone)))
    }

    @Test fun simpleQuickReminderIsOneShotAndIndependentOfOriginalAvailability() {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val due = epoch("2026-10-22T22:30:00Z") // 01:30 local on the following day
        val task = json("""{"id":7,"type":"simple","zoneId":99,"planDate":"2026-10-25","quickReminderAt":$due,"reminderEnabled":false}""")
        val before = ProjectionContext(due - 1, emptyList(), kyiv)
        val after = ProjectionContext(due, emptyList(), kyiv)
        assertEquals(due, TaskSchedule.nextReminderAt(task, before))
        assertFalse("The explicit one-off reminder does not alter normal quest availability", TaskSchedule.available(task, before))
        assertEquals("An overdue reminder remains pending until its exact receipt is committed", due,
            TaskSchedule.nextReminderAt(task, ProjectionContext(due + 1, emptyList(), kyiv)))
        assertNull(TaskSchedule.nextReminderAt(JsonObject(task + ("quickReminderFiredAt" to kotlinx.serialization.json.JsonPrimitive(due))), after))
        assertNull(TaskSchedule.nextReminderAt(JsonObject(task + ("done" to kotlinx.serialization.json.JsonPrimitive(true))), before))
        assertNull(TaskSchedule.nextReminderAt(JsonObject(task + ("someday" to kotlinx.serialization.json.JsonPrimitive(true))), before))
    }

    @Test fun scheduledAndAdvanceReminderUseStrictValidDatesAndNormalizedTimes() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"type":"sched","schedDate":"2026-10-16","schedTime":"9:05","remindBeforeMinutes":15}""")
        val due = epoch("2026-10-16T09:05:00Z")
        assertEquals(due, TaskSchedule.nextReminderAt(task, ProjectionContext(epoch("2026-10-16T08:00:00Z"), emptyList(), zone)))
        assertEquals(due - 15 * 60_000L, TaskSchedule.preReminderAt(task, due))
        assertEquals("2026-10-16", TaskSchedule.planDate(json("""{"type":"sched","schedDate":"2026-02-30","planDate":"2026-10-16"}""")))
    }

    @Test fun pastDoneProjectionUsesCompletedHistoryRatherThanLiveFlag() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"id":7,"created":"2026-10-14T08:00:00Z","type":"simple","repeat":"none","done":false}""")
        val event = json("""{"id":"x#1","occurrenceId":"x","taskId":7,"taskCreated":"2026-10-14T08:00:00Z","kind":"completed","sequence":1,"at":1791972000000,"date":"2026-10-14"}""")
        val context = context("2026-10-16T12:00:00Z", zone)
        assertTrue(TaskSchedule.doneOn(task, "2026-10-14", context, listOf(event)))
        assertFalse(TaskSchedule.doneOn(task, "2026-10-16", context, listOf(event)))
        assertFalse(TaskSchedule.doneOn(task, "2026-10-14", context, null))
        val doneToday = json("""{"id":7,"created":"2026-10-14T08:00:00Z","type":"simple","repeat":"none","done":true,"doneDate":"2026-10-16"}""")
        assertFalse(TaskSchedule.doneOn(doneToday, "2026-10-14", context, emptyList()))
    }

    @Test fun zeroZoneIdRemainsUnboundForUnscheduledCapture() {
        assertTrue(TaskSchedule.isUnscheduled(json("""{"id":1,"type":"simple","repeat":"none","zoneId":0}""")))
        assertFalse(TaskSchedule.isUnscheduled(json("""{"id":1,"type":"simple","repeat":"none","zoneId":8}""")))
    }

    @Test fun deferredOccurrenceFollowsTheTargetZoneWithoutChangingPermanentPlan() {
        val zone = ZoneId.of("UTC")
        val task = json("""{"id":3,"type":"sched","schedDate":"2026-10-16","zoneId":8,"questDeferral":{"kind":"zone","startAt":1792195200000,"targetZoneId":8}}""")
        val start = epoch("2026-10-17T00:00:00Z")
        val context = ProjectionContext(start - 1000, listOf(TimelineSegment(8, start + 30_000, start + 3_600_000)), zone)
        assertEquals(start + 30_000, TaskSchedule.deferredOccurrenceAt(task, context))
        assertEquals("2026-10-16", TaskSchedule.planDate(task))
        assertEquals("2026-10-17", TaskSchedule.displayPlanDate(task, context))
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
    private fun epoch(text: String): Long = Instant.parse(text).toEpochMilli()
    private fun context(now: String, zone: ZoneId) = ProjectionContext(epoch(now), emptyList(), zone)
}
