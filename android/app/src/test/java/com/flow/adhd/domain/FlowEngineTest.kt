package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowEngineTest {
    private val engine = FlowEngine(mapOf("simple" to 10.0))
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()

    @Test fun quickCaptureHasNoTimeOrZoneBindingAndDoesNotAwardExperience() {
        val state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"custom":{"keep":true}}""")
        val result = engine.apply(state, FlowCommand.CaptureTask("  Забрати посилку  ", "capture", at), zone, at) as FlowCommandResult.Applied
        assertEquals("Забрати посилку", result.snapshot.tasks.single().title)
        assertEquals(null, result.snapshot.tasks.single().zoneId)
        assertFalse(result.snapshot.tasks.single().raw.containsKey("planDate"))
        assertEquals(state.data["xpTotal"], result.snapshot.data["xpTotal"])
        assertEquals(state.data["custom"], result.snapshot.data["custom"])
        assertTrue(result.events.any { it.type == "daily_insight_claimed" })
    }

    @Test fun invalidEditorCommandRejectsRatherThanDiscardingItsFields() {
        val state = FlowSnapshot.parse("""{"tasks":[]}""")
        val result = engine.apply(state, FlowCommand.CreateTask(JsonObject(emptyMap()), "editor", at), zone, at)
        assertTrue(result is FlowCommandResult.Rejected)
        assertTrue(state.tasks.isEmpty())
    }

    @Test fun notificationCaptureRetainsActionIdAndAwardsOnlyOnce() {
        val initial = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[]}""")
        val command = FlowCommand.CaptureTask("Із шторки", "notification", at, CaptureOrigin.NOTIFICATION, 1700)
        val first = engine.apply(initial, command, zone, at) as FlowCommandResult.Applied
        assertEquals(1700L, first.snapshot.tasks.single().id)
        assertEquals("10", first.snapshot.data["xpTotal"].toString())
        assertEquals("1701", first.snapshot.data["nid"].toString())
        val repeated = engine.apply(first.snapshot, command, zone, at) as FlowCommandResult.Applied
        assertTrue(first.snapshot === repeated.snapshot)
        assertTrue(repeated.events.isEmpty())
    }
}
