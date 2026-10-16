package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CounterReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-16T09:00:00Z").toEpochMilli()

    @Test fun decrementThenIncrementCannotFarmCounterExperienceOrGoalBonus() {
        val initial = state("""{"id":1,"title":"Листи","type":"counter","counter":9,"counterTarget":10,"custom":"keep"}""")
        val first = apply(initial, FlowCommand.ChangeCounter(1, 1.0, "one", at))
        assertEquals("17", first.data["xpTotal"].toString())
        assertFalse(first.tasks.single().done)
        val lower = apply(first, FlowCommand.ChangeCounter(1, -1.0, "two", at))
        val higher = apply(lower, FlowCommand.ChangeCounter(1, 1.0, "three", at))
        assertEquals(first.data["xpTotal"], higher.data["xpTotal"])
        assertEquals("\"keep\"", higher.tasks.single().raw["custom"].toString())
    }

    @Test fun recheckingChecklistStepDoesNotAwardAgainOrAutoCompleteTask() {
        val initial = state("""{"id":1,"title":"Вихід","type":"check","items":[{"text":"Ключі","done":false,"custom":7}]}""")
        val checked = apply(initial, FlowCommand.SetChecklistItem(1, 0, true, "one", at))
        val unchecked = apply(checked, FlowCommand.SetChecklistItem(1, 0, false, "two", at))
        val rechecked = apply(unchecked, FlowCommand.SetChecklistItem(1, 0, true, "three", at))
        assertEquals("3", rechecked.data["xpTotal"].toString())
        assertFalse(rechecked.tasks.single().done)
        val item = (rechecked.tasks.single().raw["items"] as JsonArray).single() as JsonObject
        assertEquals("7", item["custom"].toString())
    }

    @Test fun zeroPenaltyJournalStillRecordsActivityWithoutFreeExperience() {
        val initial = state("""{"id":1,"title":"Подія","type":"negative","counter":0,"negXp":0}""")
        val result = apply(initial, FlowCommand.RecordNegative(1, 1.0, "one", at))
        assertEquals("0", result.data["xpTotal"].toString())
        assertEquals("1", result.data["negCount"].toString())
        assertEquals(1, (result.data["progressDays"] as JsonArray).size)
        assertEquals(1, (result.data["dailyInsightClaims"] as JsonArray).size)
    }

    private fun apply(state: FlowSnapshot, command: FlowCommand): FlowSnapshot =
        (CounterReducer.reduce(state, command, zone, at) as FlowCommandResult.Applied).snapshot
    private fun state(task: String) = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[$task],"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"dayXpGained":0,"xpDay":"Fri Oct 16 2026","unlocked":[],"zones":[]}""")
}
