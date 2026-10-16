package com.flow.adhd.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressionTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    @Test fun skippedDaysRemainInActiveDayHistoryAndDoNotResetStreak() {
        val state = snapshot("""{"progressionVersion":2,"progressDays":["2026-10-10","2026-10-13"],"xp":4,"xpTotal":40,"level":1,"streak":8,"unlocked":[],"zones":[]}""")
        val result = Progression.recordActivity(state, at("2026-10-16"), zone, at("2026-10-16"))

        assertEquals(listOf("2026-10-10", "2026-10-13", "2026-10-16"), stringArray(result.state, "progressDays"))
        assertEquals(8, result.state["streak"]?.toString()?.toInt())
        assertTrue(result.events.any { it.type == "progress_day_recorded" })
    }

    @Test fun sameDayActivityIsIdempotentAndClaimsOnlyOnce() {
        val state = snapshot("""{"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"done":0,"unlocked":[],"zones":[]}""")
        val timestamp = at("2026-10-16")
        val first = Progression.recordActivity(state, timestamp, zone, timestamp)
        val second = Progression.recordActivity(first.state, timestamp, zone, timestamp)

        assertEquals(listOf("2026-10-16"), stringArray(second.state, "progressDays"))
        assertEquals(1, objectArray(second.state, "dailyInsightClaims").size)
        assertEquals(1, first.events.count { it.type == "daily_insight_claimed" })
        assertFalse(second.events.any { it.type == "daily_insight_claimed" || it.type == "progress_day_recorded" })
        assertEquals(first.state["dailyInsightClaims"], second.state["dailyInsightClaims"])
    }

    @Test fun olderActivitySeedsAnArchivedClaimWithoutClaimingToday() {
        val state = snapshot("""{"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"unlocked":[],"zones":[]}""")
        val result = Progression.recordActivity(state, at("2026-10-15"), zone, at("2026-10-16"))

        assertEquals(listOf("2026-10-15"), stringArray(result.state, "progressDays"))
        val claim = objectArray(result.state, "dailyInsightClaims").single()
        assertEquals("2026-10-15", claim["day"]?.toString()?.trim('"'))
        assertEquals(0, claim["earnedAt"]?.toString()?.toInt())
        assertFalse(result.events.any { it.type == "daily_insight_claimed" })
    }

    @Test fun initializesLegacyBalanceAndSeedsDatesWithoutLosingUnknownFields() {
        val state = snapshot("""{"progressionVersion":0,"progressDays":["2026-10-11"],"taskHistory":[{"kind":"completed","date":"2026-10-12"},{"kind":"reopened","date":"2026-10-14"}],"qnotes":[{"date":"2026-10-13"}],"xp":50,"xpTotal":120,"level":3,"done":0,"unlocked":[],"zones":[],"custom":{"nested":[1,true]}}""")
        val now = at("2026-10-16")
        val result = Progression.recordActivity(state, now, zone, now)

        assertEquals(2, result.state["progressionVersion"]?.toString()?.toInt())
        assertEquals(4, result.state["level"]?.toString()?.toInt())
        assertEquals(35, result.state["xp"]?.toString()?.toInt())
        assertEquals(listOf("2026-10-11", "2026-10-12", "2026-10-13", "2026-10-16"), stringArray(result.state, "progressDays"))
        assertEquals(state["custom"], result.state["custom"])
        assertTrue(result.events.any { it.type == "achievement_unlocked" && it.payload["id"] == JsonPrimitive("active1") })
    }

    @Test fun futureActivityDoesNotAddDayOrClaimAndInsightCountIsInjected() {
        val state = snapshot("""{"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"unlocked":[],"zones":[]}""")
        val today = at("2026-10-16")
        val future = at("2026-10-17")
        val result = Progression.recordActivity(state, future, zone, today, insightCount = 7)

        assertTrue(stringArray(result.state, "progressDays").isEmpty())
        assertTrue(objectArray(result.state, "dailyInsightClaims").isEmpty())
        assertFalse(result.events.any { it.type == "daily_insight_claimed" })
    }

    private fun snapshot(raw: String) = Json.parseToJsonElement(raw) as JsonObject

    private fun at(day: String) = LocalDate.parse(day).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun stringArray(state: JsonObject, key: String): List<String> =
        ((state[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }

    private fun objectArray(state: JsonObject, key: String): List<JsonObject> =
        ((state[key] as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { it as? JsonObject }
}
