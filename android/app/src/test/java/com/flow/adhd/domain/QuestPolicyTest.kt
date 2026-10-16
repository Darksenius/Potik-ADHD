package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuestPolicyTest {
    private val utc = ZoneId.of("UTC")
    private val zones = listOf(json("""{"id":1}"""), json("""{"id":2}"""))

    @Test fun priorityCannotBypassCurrentZone() {
        val now = epoch("2026-10-16T10:00:00Z")
        val context = QuestPolicy.Context(now, listOf(TimelineSegment(1, now - 1000, now + 60_000)), zones, listOf(9L), utc)
        val task = json("""{"id":9,"type":"simple","repeat":"none","zoneId":2}""")
        assertEquals("wrong-zone", QuestPolicy.availability(task, context).reason)
        assertTrue(QuestPolicy.select(listOf(task), context).isEmpty())
    }

    @Test fun expiredNextZoneDeferralReturnsAtNextSourceSegment() {
        val start = epoch("2026-10-16T10:00:00Z")
        val end = epoch("2026-10-16T11:00:00Z")
        val now = end + 1
        val timeline = listOf(TimelineSegment(1, start - 3600_000, start), TimelineSegment(2, start, end), TimelineSegment(0, end, end + 3600_000), TimelineSegment(1, end + 3600_000, end + 7200_000))
        val task = json("""{"id":1,"type":"simple","repeat":"none","zoneId":1,"questDeferral":{"kind":"next-zone","requestedAt":${start-1},"sourceZoneId":1,"targetZoneId":2,"startAt":$start,"endAt":$end}}""")
        val result = QuestPolicy.availability(task, QuestPolicy.Context(now, timeline, zones, zone = utc))
        assertEquals("wrong-zone", result.reason)
        assertEquals(end + 3600_000, result.availableAt)
    }

    @Test fun nextZoneRequestWithoutAnotherZoneTodayFallsBackToNotToday() {
        val at = epoch("2026-10-16T23:00:00Z")
        val context = QuestPolicy.Context(at, listOf(TimelineSegment(1, at - 3600_000, at + 3600_000)), zones, zone = utc)
        val task = json("""{"id":4,"type":"simple","zoneId":1,"unknown":"keep"}""")
        val deferral = QuestPolicy.createDeferral(task, QuestDeferralKind.NEXT_ZONE, context, at)
        assertEquals("not-today", deferral.string("kind"))
        assertEquals(epoch("2026-10-17T00:00:00Z"), deferral.number("startAt")?.toLong())
        assertEquals("keep", task.string("unknown"))
    }

    @Test fun lastNamedZoneFollowedByFreeTimeFallsBackToLinkedMidnightOrUnboundTwentyThreeHours() {
        val request = epoch("2026-10-16T21:30:00Z")
        val context = QuestPolicy.Context(request, listOf(
            TimelineSegment(18, epoch("2026-10-16T18:00:00Z"), epoch("2026-10-16T22:00:00Z")),
            TimelineSegment(0, epoch("2026-10-16T22:00:00Z"), epoch("2026-10-17T00:00:00Z")),
        ), listOf(json("""{"id":18}""")), zone = utc)

        val linked = QuestPolicy.createDeferral(json("""{"id":10,"type":"simple","zoneId":18}"""), QuestDeferralKind.NEXT_ZONE, context, request)
        assertEquals("not-today", linked.string("kind"))
        assertEquals(epoch("2026-10-17T00:00:00Z"), linked.number("startAt")?.toLong())

        val unbound = QuestPolicy.createDeferral(json("""{"id":11,"type":"simple"}"""), QuestDeferralKind.NEXT_ZONE, context, request)
        assertEquals("not-today", unbound.string("kind"))
        assertEquals(request + 23L * 60 * 60 * 1000, unbound.number("startAt")?.toLong())
    }

    @Test fun nextZoneDeferralSkipsFreeGapAndUsesNextNamedZone() {
        val request = epoch("2026-10-16T18:30:00Z")
        val startNext = epoch("2026-10-16T20:00:00Z")
        val endNext = epoch("2026-10-16T22:00:00Z")
        val context = QuestPolicy.Context(request, listOf(
            TimelineSegment(18, epoch("2026-10-16T18:00:00Z"), epoch("2026-10-16T19:00:00Z")),
            TimelineSegment(0, epoch("2026-10-16T19:00:00Z"), startNext),
            TimelineSegment(22, startNext, endNext),
        ), listOf(json("""{"id":18}"""), json("""{"id":22}""")), zone = utc)

        val result = QuestPolicy.createDeferral(json("""{"id":12,"type":"simple","zoneId":18}"""), QuestDeferralKind.NEXT_ZONE, context, request)
        assertEquals("next-zone", result.string("kind"))
        assertEquals(22L, result.number("targetZoneId")?.toLong())
        assertEquals(startNext, result.number("startAt")?.toLong())
        assertEquals(endNext, result.number("endAt")?.toLong())
    }

    @Test fun unboundNotTodayUsesTwentyThreeElapsedHoursAcrossDst() {
        val zone = ZoneId.of("Europe/Kyiv")
        val at = epoch("2026-03-28T12:00:00Z")
        val task = json("""{"id":4,"type":"simple"}""")
        val deferral = QuestPolicy.createDeferral(task, QuestDeferralKind.NOT_TODAY, QuestPolicy.Context(at, emptyList(), emptyList(), zone = zone), at)
        assertEquals(at + 23L * 60 * 60 * 1000, deferral.number("startAt")?.toLong())
        assertEquals("2026-03-29T11:00:00Z", Instant.ofEpochMilli(deferral.number("startAt")!!.toLong()).toString())
    }

    @Test fun linkedNotTodayWaitsForItsOwnZoneAfterLocalMidnight() {
        val now = epoch("2026-10-16T21:00:00Z") // Kyiv midnight
        val task = json("""{"id":5,"type":"simple","zoneId":2,"questDeferral":{"kind":"not-today","requestedAt":${now-60_000},"sourceZoneId":1,"targetZoneId":2,"startAt":$now}}""")
        val context = QuestPolicy.Context(now + 30_000, listOf(TimelineSegment(1, now, now + 3600_000), TimelineSegment(2, now + 3600_000, now + 7200_000)), zones, zone = ZoneId.of("Europe/Kyiv"))
        val result = QuestPolicy.availability(task, context)
        assertEquals("wrong-zone", result.reason)
        assertEquals(now + 3600_000, result.availableAt)
    }

    @Test fun oldCompletionFlagsDoNotHideTodaysRepeatingOccurrence() {
        val now = epoch("2026-10-16T10:00:00Z")
        val context = QuestPolicy.Context(now, listOf(TimelineSegment(0, now - 1000, now + 60_000)), zones, zone = utc)
        val repeat = json("""{"id":1,"type":"simple","repeat":"daily","done":true,"doneDate":"2026-10-15","completionCreditDate":"2026-10-15"}""")
        assertTrue(QuestPolicy.availability(repeat, context).eligible)
        val emptyDoneDay = json("""{"id":3,"type":"simple","repeat":"daily","done":true,"doneDate":"","completionCreditDate":"2026-10-15"}""")
        assertTrue(QuestPolicy.availability(emptyDoneDay, context).eligible)
        val once = json("""{"id":2,"type":"simple","repeat":"none","done":true,"doneDate":"2026-10-15"}""")
        assertFalse(QuestPolicy.availability(once, context).eligible)
    }

    @Test fun nullZoneAndEmptyRepeatKeepTypescriptFalsySemantics() {
        val now = epoch("2026-10-16T10:00:00Z")
        val context = QuestPolicy.Context(now, listOf(TimelineSegment(0, now - 1000, now + 60_000)), zones, zone = utc)
        val task = json("""{"id":8,"type":"simple","repeat":"","zoneId":null}""")
        assertTrue(QuestPolicy.availability(task, context).eligible)
    }

    @Test fun pendingDeferralReportsItsStartTime() {
        val now = epoch("2026-10-16T10:00:00Z")
        val start = now + 300_000
        val task = json("""{"id":7,"type":"simple","repeat":"none","questDeferral":{"kind":"not-today","requestedAt":$now,"sourceZoneId":0,"targetZoneId":0,"startAt":$start}}""")
        val result = QuestPolicy.availability(task, QuestPolicy.Context(now, emptyList(), zones, zone = utc))
        assertEquals("deferred-until", result.reason)
        assertEquals(start, result.availableAt)
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject
    private fun epoch(text: String): Long = Instant.parse(text).toEpochMilli()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
}
