package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskTitleScheduleTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    @Test fun markerIsRemovedAtAnyTitlePositionAndUsesTodayOrTomorrow() {
        val parsed = TaskTitleSchedule.parse("Передзвонити ///12:40/// після обіду") as TaskTitleSchedule.Result.Valid
        assertEquals("Передзвонити після обіду", parsed.value.title)
        assertEquals("12:40", parsed.value.time)
        val morning = Instant.parse("2026-10-21T08:00:00Z").toEpochMilli() // 11:00 local
        val afternoon = Instant.parse("2026-10-21T11:00:00Z").toEpochMilli() // 14:00 local
        assertEquals("2026-10-21", TaskTitleSchedule.defaultDate("12:40", morning, zone))
        assertEquals("2026-10-22", TaskTitleSchedule.defaultDate("12:40", afternoon, zone))
    }

    @Test fun markerAloneAndInvalidTimesReject() {
        assertEquals(TaskTitleSchedule.Result.EmptyTitle, TaskTitleSchedule.parse(" ///12:40/// "))
        assertEquals(TaskTitleSchedule.Result.InvalidTime, TaskTitleSchedule.parse("Зателефонувати ///25:40///"))
        assertEquals(TaskTitleSchedule.Result.InvalidTime, TaskTitleSchedule.parse("Зателефонувати ///12:4x///"))
    }

    @Test fun notificationCaptureSchedulesAndStripsMarkerWithoutReadingOtherText() {
        val at = Instant.parse("2026-10-21T08:00:00Z").toEpochMilli()
        val empty = FlowSnapshot.parse("{}")
        val result = FlowEngine(emptyMap()).apply(empty,
            FlowCommand.CaptureTask("Купити ///12:40/// молоко", "capture", at, CaptureOrigin.NOTIFICATION), zone, at,
        ) as FlowCommandResult.Applied
        val task = result.snapshot.tasks.single()
        assertEquals("Купити молоко", task.title)
        assertEquals("sched", task.type)
        assertEquals("2026-10-21", task.raw["schedDate"]?.toString()?.trim('"'))
        assertEquals("12:40", task.raw["schedTime"]?.toString()?.trim('"'))
        assertTrue(FlowEngine(emptyMap()).apply(empty,
            FlowCommand.CaptureTask("///12:40///", "bare", at), zone, at,
        ) is FlowCommandResult.Rejected)
    }
}
