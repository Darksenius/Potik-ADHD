package com.flow.adhd.domain

import java.time.ZoneId
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerReducerTest {
    private val zone = ZoneId.of("UTC")
    private val day = "2026-10-16"
    private fun snapshot(extra: JsonObject = JsonObject(emptyMap())) = FlowSnapshot.fromData(JsonObject(mapOf(
        "formatVersion" to kotlinx.serialization.json.JsonPrimitive(2),
        "nid" to kotlinx.serialization.json.JsonPrimitive(300),
        "zones" to kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("id", 1); put("nm", "Work") }) },
        "tasks" to kotlinx.serialization.json.JsonArray(emptyList()),
    ) + extra))
    private fun result(s: FlowSnapshot, c: FlowCommand) = PlannerReducer.reduce(s, c, zone, 1_800_000_000_000L)
    private fun applied(s: FlowSnapshot, c: FlowCommand) = (result(s, c) as FlowCommandResult.Applied).snapshot.data

    @Test fun rejectsInvalidDatesAndInvalidBlocksWithoutChangingInput() {
        val s = snapshot()
        val r = result(s, FlowCommand.SetPlanDay("2026-02-30", buildJsonObject { put("blocks", kotlinx.serialization.json.JsonArray(emptyList())) }, "x", 1))
        assertTrue(r is FlowCommandResult.Rejected)
        val invalid = result(s, FlowCommand.SetPlanDay(day, buildJsonObject {
            put("blocks", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "10:00") })
                add(buildJsonObject { put("zoneId", 88); put("s", "10:00"); put("e", "11:00") })
            })
        }, "y", 1))
        assertTrue(invalid is FlowCommandResult.Rejected)
        assertFalse(s.data.containsKey("planDayZones"))
    }

    @Test fun emptyManualOverrideWinsAndDoesNotMutateTemplate() {
        val template = buildJsonObject { put("id", "work"); put("blocks", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "10:00") }) }) }
        val s = snapshot(buildJsonObject {
            put("dayTemplates", kotlinx.serialization.json.buildJsonArray { add(template) })
            put("planRules", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("start", day); put("end", day); put("days", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("tplId", "work") }) }) }) })
        })
        val updated = applied(s, FlowCommand.SetPlanDay(day, buildJsonObject { put("blocks", kotlinx.serialization.json.JsonArray(emptyList())) }, "x", 1))
        assertTrue(PlannerProjection.dayBlocks(updated, day).isEmpty())
        assertEquals(template, (updated["dayTemplates"]!!.jsonArray[0]).jsonObject)
        assertNotSame(s.data, updated)
    }

    @Test fun addsSimpleAndScheduledTasksWithTwoXpAndDateAnchors() {
        for ((text, type, time) in listOf(Triple("Read book", "simple", ""), Triple("9:05 Meeting", "sched", "09:05"))) {
            val updated = applied(snapshot(), FlowCommand.AddPlanItem(day, buildJsonObject { put("text", text) }, "x", 1000))
            val task = updated["tasks"]!!.jsonArray.single().jsonObject
            assertEquals(type, task["type"]!!.jsonPrimitive.content)
            assertEquals(day, task["planDate"]!!.jsonPrimitive.content)
            if (type == "sched") {
                assertEquals(day, task["schedDate"]!!.jsonPrimitive.content)
                assertEquals(time, task["schedTime"]!!.jsonPrimitive.content)
            }
            assertEquals(2.0, updated["xp"]!!.jsonPrimitive.content.toDouble(), 0.001)
        }
    }

    @Test fun eventAppendsOnlyAndRestPromptIsUnique() {
        val empty = snapshot()
        val event = applied(empty, FlowCommand.AddPlanItem(day, buildJsonObject { put("text", "9:00 Birthday\nmore text"); put("isEvent", true) }, "a", 1))
        assertTrue(event["tasks"]!!.jsonArray.isEmpty())
        assertEquals("Birthday", event["planItems"]!!.jsonObject[day]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("9:00", event["planItems"]!!.jsonObject[day]!!.jsonArray.single().jsonObject["time"]!!.jsonPrimitive.content)
        val invalidTimeEvent = applied(empty, FlowCommand.AddPlanItem(day, buildJsonObject { put("text", "99:99 Event"); put("isEvent", true) }, "aa", 1))
        assertEquals("99:99", invalidTimeEvent["planItems"]!!.jsonObject[day]!!.jsonArray.single().jsonObject["time"]!!.jsonPrimitive.content)
        val rest = applied(empty, FlowCommand.SetPlanRestDay(day, true, "b", 1))
        val again = applied(FlowSnapshot.fromData(rest), FlowCommand.SetPlanRestDay(day, true, "c", 2))
        assertEquals(1, again["planItems"]!!.jsonObject[day]!!.jsonArray.size)
        assertTrue((result(FlowSnapshot.fromData(again), FlowCommand.UpdatePlanItem(day, 0, buildJsonObject { put("done", true) }, "d", 3)) as FlowCommandResult.Applied).snapshot.data["planItems"]!!.jsonObject[day]!!.jsonArray[0].jsonObject["done"]!!.jsonPrimitive.boolean.not())
    }

    @Test fun setDayUsesInjectedNowForZoneUsageDate() {
        val s = snapshot()
        val now = 1_800_000_000_000L
        val updated = PlannerReducer.reduce(s, FlowCommand.SetPlanDay(day, buildJsonObject {
            put("blocks", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "10:00") }) })
        }, "x", 10L), zone, now) as FlowCommandResult.Applied
        val expected = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
        assertEquals(expected, updated.snapshot.data["zoneUsageDay"]!!.jsonPrimitive.content)
    }

    @Test fun midnightEndNormalizesToEndOfDay() {
        val updated = applied(snapshot(), FlowCommand.SetPlanDay(day, buildJsonObject {
            put("blocks", kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "00:00") }) })
        }, "x", 10L))
        assertEquals("24:00", updated["planDayZones"]!!.jsonObject[day]!!.jsonArray.single().jsonObject["e"]!!.jsonPrimitive.content)
    }

    @Test fun updateIsIdempotentAndRejectsUnknownFieldsDeleteInvalidIndexIsNoop() {
        val initial = snapshot(buildJsonObject { put("planItems", buildJsonObject { put(day, kotlinx.serialization.json.buildJsonArray { add(buildJsonObject { put("title", "legacy"); put("done", false); put("custom", "keep") }) }) }) })
        val cmd = FlowCommand.UpdatePlanItem(day, 0, buildJsonObject { put("done", true) }, "x", 1)
        val once = applied(initial, cmd); val twice = applied(FlowSnapshot.fromData(once), cmd)
        assertEquals(once, twice)
        assertEquals("keep", twice["planItems"]!!.jsonObject[day]!!.jsonArray[0].jsonObject["custom"]!!.jsonPrimitive.content)
        assertTrue(result(initial, FlowCommand.UpdatePlanItem(day, 0, buildJsonObject { put("done", true); put("typo", false) }, "u", 1)) is FlowCommandResult.Rejected)
        assertEquals(initial.data, applied(initial, FlowCommand.DeletePlanItem(day, 9, "d", 1)))
    }
}
