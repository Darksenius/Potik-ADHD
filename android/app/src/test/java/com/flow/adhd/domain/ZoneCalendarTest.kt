package com.flow.adhd.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneCalendarTest {
    private val utc = ZoneId.of("UTC")
    private val day = "2026-10-16"

    @Test fun plannedBlockOverridesBaselineButAssignmentKeepsBoth() {
        val state = json("""{"zones":[
          {"id":1,"prio":1,"slots":[{"s":"09:00","e":"12:00"}]},
          {"id":2,"prio":2,"slots":[{"s":"10:00","e":"11:00"}]}
        ],"planDayZones":{"$day":[{"zoneId":1,"s":"10:15","e":"10:45"}]}}""")
        val ranges = ZoneCalendar.dayRanges(state, day)
        assertEquals(3, ranges.size)
        assertEquals(11.0, ranges.last().priority, 0.0)
        assertTrue(ranges.last().planned)
        val timeline = ZoneCalendar.timeline(state, at(day, "00:00", utc), utc, 1)
        assertEquals(1L, ZoneCalendar.currentZoneId(timeline, at(day, "10:20", utc)))
        assertEquals(2L, ZoneCalendar.currentZoneId(timeline, at(day, "10:05", utc)))
        assertEquals(2L, ZoneCalendar.currentZoneId(timeline, at(day, "10:45", utc)))
        assertEquals(listOf(ZoneCalendar.AssignedRange(1, 540, 720), ZoneCalendar.AssignedRange(2, 600, 660)), ZoneCalendar.assignedOccurrences(state, day))
    }

    @Test fun malformedTimesWithoutBothComponentsAreSkipped() {
        val state = json("""{"zones":[
          {"id":1,"slots":[{"s":"","e":"24:00"},{"s":"9","e":"10:00"},{"s":"09:00","e":"10"}]}
        ]}""")
        assertTrue(ZoneCalendar.dayRanges(state, day).isEmpty())
    }
    @Test fun baselineFiltersAndEqualPriorityUsesEarlierOrder() {
        val state = json("""{"zones":[
          {"id":1,"prio":3,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":2,"prio":3,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":3,"off":true,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":4,"active":false,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":5,"bound":true,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":6,"bound":true,"actPin":true,"slots":[{"s":"09:00","e":"10:00"}]},
          {"id":7,"slots":[{"s":"09:00","e":"10:00"}]}
        ],"planDayOff":{"$day":[7]}}""")
        val timeline = ZoneCalendar.timeline(state, at(day, "00:00", utc), utc, 1)
        assertEquals(1L, ZoneCalendar.currentZoneId(timeline, at(day, "09:30", utc)))
        assertEquals(listOf(1L, 2L, 6L), ZoneCalendar.dayRanges(state, day).map { it.zoneId })
    }

    @Test fun assignedRangesMergeTouchingSlotsAndKeyIsUnaffectedByOverlay() {
        val state = json("""{"zones":[
          {"id":9007199254740991,"slots":[{"s":"09:00","e":"10:00"},{"s":"10:00","e":"11:00"}]},
          {"id":2,"prio":3,"slots":[{"s":"09:30","e":"10:30"}]}
        ]}""")
        val huge = 9_007_199_254_740_991L
        assertEquals(ZoneCalendar.AssignedRange(huge, 540, 660), ZoneCalendar.assignedOccurrences(state, day).single { it.zoneId == huge })
        assertEquals("$day:$huge:09:00", ZoneCalendar.occurrenceKey(state, huge, at(day, "10:15", utc), utc))
        assertNull(ZoneCalendar.occurrenceKey(state, huge, at(day, "11:00", utc), utc))
        val timeline = ZoneCalendar.timeline(state, at(day, "00:00", utc), utc, 1)
        assertEquals(2L, ZoneCalendar.currentZoneId(timeline, at(day, "10:15", utc)))
        assertEquals(0L, ZoneCalendar.currentZoneId(timeline, at(day, "11:00", utc)))
    }

    @Test fun dstDaysCoverRealElapsedMinutesWithoutLosingLocalPolicy() {
        val zone = ZoneId.of("America/New_York")
        val state = json("""{"zones":[{"id":8,"slots":[{"s":"01:00","e":"03:00"}]}]}""")
        val springDay = "2026-03-08"
        val fallDay = "2026-11-01"
        val spring = ZoneCalendar.timeline(state, at(springDay, "00:00", zone), zone, 1)
        val fall = ZoneCalendar.timeline(state, at(fallDay, "00:00", zone), zone, 1)
        assertEquals(23 * 60 * 60_000L, spring.last().endAt - spring.first().startAt)
        assertEquals(25 * 60 * 60_000L, fall.last().endAt - fall.first().startAt)
        assertEquals(8L, ZoneCalendar.currentZoneId(spring, at(springDay, "01:30", zone)))
        assertEquals(8L, ZoneCalendar.currentZoneId(fall, at(fallDay, "01:30", zone)))
        assertFalse(spring.any { it.startAt > it.endAt })
    }

    private fun at(date: String, time: String, zone: ZoneId): Long = LocalDate.parse(date).atTime(time.substringBefore(':').toInt(), time.substringAfter(':').toInt()).atZone(zone).toInstant().toEpochMilli()
    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
}
