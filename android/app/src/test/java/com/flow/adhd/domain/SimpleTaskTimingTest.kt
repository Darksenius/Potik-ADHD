package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SimpleTaskTimingTest {
    private val kyiv = ZoneId.of("Europe/Kyiv")

    @Test fun dayReminderUsesLocalFiveAndTodayAfterFiveUsesNow() {
        val beforeFive = Instant.parse("2026-10-22T01:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-22T02:00:00Z").toEpochMilli(), SimpleTaskTiming.dayReminderAt("today", beforeFive, kyiv))
        val afterFive = Instant.parse("2026-10-22T03:30:00Z").toEpochMilli()
        assertEquals(afterFive, SimpleTaskTiming.dayReminderAt("today", afterFive, kyiv))
        assertEquals(Instant.parse("2026-10-23T02:00:00Z").toEpochMilli(), SimpleTaskTiming.dayReminderAt("tomorrow", afterFive, kyiv))
        assertNull(SimpleTaskTiming.dayReminderAt("someday", afterFive, kyiv))
    }

    @Test fun tomorrowFiveTracksDstOffsetOfTargetDate() {
        val beforeSpringChange = Instant.parse("2026-03-28T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-03-29T02:00:00Z").toEpochMilli(), SimpleTaskTiming.dayReminderAt("tomorrow", beforeSpringChange, kyiv))
        val beforeAutumnChange = Instant.parse("2026-10-24T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-10-25T03:00:00Z").toEpochMilli(), SimpleTaskTiming.dayReminderAt("tomorrow", beforeAutumnChange, kyiv))
    }

    @Test fun delaySelectorCyclesThroughConfiguredSequence() {
        assertEquals(listOf(20L, 30L, 50L, 100L, 150L, 10L), listOf(10L, 20L, 30L, 50L, 100L, 150L).map(SimpleTaskTiming::nextDelayMinutes))
        assertEquals(listOf(10L, 10L), listOf(0L, 999L).map(SimpleTaskTiming::nextDelayMinutes))
    }
}
