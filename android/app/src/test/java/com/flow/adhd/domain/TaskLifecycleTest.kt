package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskLifecycleTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()

    @Test fun dailyOccurrenceResetsUnfinishedProgressAndPreservesUnknownFields() {
        val state = state("""{"id":1,"title":"Листи","type":"counter","created":"2026-10-14T09:00:00Z","repeat":"daily","done":false,"counter":8,"counterDay":"2026-10-15","counterCreditedProgress":8,"cntHit":true,"items":[{"text":"Один","done":true,"credited":true,"custom":7}],"custom":{"keep":true}}""")
        val result = TaskLifecycle.resetRepeating(state, at, zone)
        val task = result.tasks.single()
        assertEquals("0", task.raw["counter"].toString())
        assertEquals("false", task.raw["cntHit"].toString())
        assertEquals(state.tasks.single().raw["custom"], task.raw["custom"])
        val item = (task.raw["items"] as JsonArray).single() as JsonObject
        assertEquals("false", item["done"].toString())
        assertEquals("7", item["custom"].toString())
        assertEquals(result.data, TaskLifecycle.resetRepeating(result, at, zone).data)
    }

    @Test fun resetArchivesPreviousCompletionWithoutLosingItsTitle() {
        val state = state("""{"id":1,"title":"Учорашня справа","type":"simple","created":"2026-10-14T09:00:00Z","repeat":"daily","done":true,"doneDate":"2026-10-15","doneAt":"9:00"}""")
        val result = TaskLifecycle.resetRepeating(state, at, zone)
        assertFalse(result.tasks.single().done)
        val event = (result.data["taskHistory"] as JsonArray).single() as JsonObject
        assertEquals("\"Учорашня справа\"", event["title"].toString())
        assertEquals("\"2026-10-15\"", event["date"].toString())
        assertEquals("0", result.data["xpTotal"].toString())
    }

    @Test fun undoCreditTodayAndUnfinishedDeferralProtectCurrentOccurrence() {
        val undo = state("""{"id":1,"title":"Справа","type":"counter","created":"2026-10-14T09:00:00Z","repeat":"daily","counterDay":"2026-10-15","completionCreditDate":"2026-10-16","completionCredited":true,"counter":3}""")
        assertEquals(undo.data, TaskLifecycle.resetRepeating(undo, at, zone).data)
        val deferred = state("""{"id":1,"title":"Справа","type":"simple","created":"2026-10-14T09:00:00Z","repeat":"daily","questDeferral":{"kind":"not-today","startAt":0}}""")
        assertEquals(deferred.data, TaskLifecycle.resetRepeating(deferred, at, zone).data)
    }

    @Test fun firstZoneObservationOnlyIdentifiesSlotAndNextSlotResetsIt() {
        val state = state("""{"id":1,"title":"Справа","type":"zonelinked","created":"2026-10-14T09:00:00Z","repeat":"everyzone","zoneId":7,"done":true,"doneDate":"2026-10-15","doneAt":"10:00"}""")
        val first = TaskLifecycle.checkZoneRepeats(state, at, zone, { _, _ -> "slot-a" })
        assertTrue(first.tasks.single().done)
        assertEquals(first.data, TaskLifecycle.checkZoneRepeats(first, at, zone, { _, _ -> "slot-a" }).data)
        val next = TaskLifecycle.checkZoneRepeats(first, at, zone, { _, _ -> "slot-b" })
        assertFalse(next.tasks.single().done)
        assertEquals("\"slot-b\"", next.tasks.single().raw["zoneOccurrenceKey"].toString())
    }

    @Test fun dailyJournalResetPreservesDateScopedPresetAndTickEarnsNothing() {
        val prior = state("""{"id":1,"title":"Подія","type":"negative","created":"2026-10-14T09:00:00Z","repeat":"none","counter":7,"planDate":"2026-10-15","dayPresetKey":"day:1"},{"id":2,"title":"Журнал","type":"negative","counter":4,"counterDay":"2026-10-15"}""")
        val reset = TaskLifecycle.dailyReset(prior, "Thu Oct 15 2026", at, zone)
        assertEquals("7", reset.tasks.first().raw["counter"].toString())
        assertEquals("0", reset.tasks.last().raw["counter"].toString())
        val tick = TaskLifecycle.tick(reset, at, zone, { _, _ -> null })
        assertEquals("0", tick.snapshot.data["xpTotal"].toString())
        assertTrue((tick.snapshot.data["progressDays"] as JsonArray).isEmpty())
        assertTrue((tick.snapshot.data["dailyInsightClaims"] as JsonArray).isEmpty())
        assertEquals(tick.snapshot.data, TaskLifecycle.tick(tick.snapshot, at, zone, { _, _ -> null }).snapshot.data)
    }

    private fun state(tasks: String) = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[$tasks],"taskHistory":[],"xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"recur":[]}""")
}
