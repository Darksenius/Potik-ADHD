package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceTest {
    private val zone = ZoneId.of("UTC")

    @Test fun positiveAwardsRespectDailyCapAndCarryOverRemainingRoom() {
        val now = instant("2026-10-16T12:00:00Z")
        val state = snapshot("""{"progressionVersion":2,"xp":20,"xpTotal":20,"level":1,"xpDay":"Fri Oct 16 2026","dayXpGained":240,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"zones":[]}""")

        val partial = Experience.award(state, 30.0, now, zone, now)
        assertEquals(30, int(partial.state, "xp"))
        assertEquals(30, int(partial.state, "xpTotal"))
        assertEquals(250, int(partial.state, "dayXpGained"))
        assertEquals(10, partial.events.single { it.type == "xp_awarded" }.payload["amount"]?.toString()?.toDouble()?.toInt())
        assertFalse(partial.events.any { it.type == "xp_limit" })

        val capped = Experience.award(partial.state, 10.0, now, zone, now)
        assertEquals(30, int(capped.state, "xp"))
        assertEquals(30, int(capped.state, "xpTotal"))
        assertTrue(capped.events.any { it.type == "xp_limit" })
        assertFalse(capped.events.any { it.type == "xp_awarded" })
        assertEquals(listOf("2026-10-16"), strings(capped.state, "progressDays"))
    }

    @Test fun penaltiesDoNotFreeCapOrReduceLifetimeXpOrLevel() {
        val now = instant("2026-10-16T12:00:00Z")
        val state = snapshot("""{"progressionVersion":2,"xp":50,"xpTotal":500,"level":4,"xpDay":"Fri Oct 16 2026","dayXpGained":200,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"zones":[]}""")
        val result = Experience.award(state, -80.0, now, zone, now)

        assertEquals(0, int(result.state, "xp"))
        assertEquals(500, int(result.state, "xpTotal"))
        assertEquals(4, int(result.state, "level"))
        assertEquals(200, int(result.state, "dayXpGained"))
        assertTrue(result.events.any { it.type == "xp_awarded" && it.payload["tone"] == JsonPrimitive("loss") })
        assertEquals(state["progressDays"], result.state["progressDays"])
        assertFalse(result.events.any { it.type == "progress_day_recorded" })
    }

    @Test fun positiveAwardMapsLifetimeXpAndEmitsLevelUp() {
        val now = instant("2026-10-16T12:00:00Z")
        val state = snapshot("""{"progressionVersion":2,"xp":95,"xpTotal":95,"level":1,"xpDay":"Fri Oct 16 2026","dayXpGained":0,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"zones":[]}""")
        val result = Experience.award(state, 10.0, now, zone, now)

        assertEquals(2, int(result.state, "level"))
        assertEquals(5, int(result.state, "xp"))
        assertEquals(105, int(result.state, "xpTotal"))
        assertTrue(result.events.any { it.type == "level_up" && it.payload["level"] == JsonPrimitive(2) })
    }

    @Test fun lateAwardRecordsOnlyItsHistoricalDayAndArchivesItsInsight() {
        val today = instant("2026-10-16T12:00:00Z")
        val yesterday = instant("2026-10-15T15:00:00Z")
        val state = snapshot("""{"progressionVersion":2,"xp":0,"xpTotal":0,"level":1,"xpDay":"Fri Oct 16 2026","dayXpGained":0,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"zones":[]}""")
        val result = Experience.award(state, 3.0, yesterday, zone, today)

        assertEquals(listOf("2026-10-15"), strings(result.state, "progressDays"))
        val claim = objects(result.state, "dailyInsightClaims").single()
        assertEquals("2026-10-15", claim["day"]?.toString()?.trim('"'))
        assertEquals(0, claim["earnedAt"]?.toString()?.toInt())
        assertFalse(result.events.any { it.type == "daily_insight_claimed" })
        assertTrue(result.events.any { it.type == "xp_awarded" })
    }

    @Test fun energyAwardsAtMostOncePerHourAndStillUpdatesEnergyDuringCooldown() {
        val firstAt = instant("2026-10-16T12:00:00Z")
        val state = snapshot("""{"progressionVersion":2,"xp":0,"xpTotal":0,"level":1,"xpDay":"Fri Oct 16 2026","dayXpGained":0,"lastEnergyXp":0,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"zones":[]}""")
        val first = Experience.setEnergy(state, 2, firstAt, zone, firstAt)
        assertEquals(3, int(first.state, "xp"))
        assertEquals(firstAt, first.state["lastEnergyXp"]?.toString()?.toLong())

        val withinHour = firstAt + 30 * 60 * 1000L
        val second = Experience.setEnergy(first.state, 5, withinHour, zone, withinHour)
        assertEquals(5, int(second.state, "energy"))
        assertEquals(3, int(second.state, "xp"))
        assertEquals(firstAt, second.state["lastEnergyXp"]?.toString()?.toLong())
        assertTrue(second.events.isEmpty())

        val afterHour = firstAt + 60 * 60 * 1000L
        val third = Experience.setEnergy(second.state, 4, afterHour, zone, afterHour)
        assertEquals(6, int(third.state, "xp"))
        assertEquals(afterHour, third.state["lastEnergyXp"]?.toString()?.toLong())
    }

    private fun snapshot(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private fun instant(text: String) = Instant.parse(text).toEpochMilli()
    private fun int(state: JsonObject, key: String) = state[key]?.toString()?.toDouble()?.toInt()
    private fun strings(state: JsonObject, key: String): List<String> =
        ((state[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
    private fun objects(state: JsonObject, key: String): List<JsonObject> =
        ((state[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
}
