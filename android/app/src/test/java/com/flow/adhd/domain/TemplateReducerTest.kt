package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateReducerTest {
    private val zone = ZoneId.of("UTC")
    private val types = setOf("simple", "check")
    private val now = 1_797_120_000_000L
    private fun template(id: String = "work", name: String = "Work") = buildJsonObject {
        put("id", id); put("name", name)
        put("blocks", buildJsonArray { add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "10:00") }) })
        put("tasks", buildJsonArray { add(buildJsonObject { put("id", "read"); put("task", buildJsonObject { put("title", "Read"); put("type", "simple") }) }) })
    }
    private fun snapshot(extra: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())) = FlowSnapshot.fromData(buildJsonObject {
        put("formatVersion", 2); put("nid", 300); put("zones", buildJsonArray { add(buildJsonObject { put("id", 1); put("nm", "Work") }) })
        put("tasks", JsonArray(emptyList()))
    }.let { kotlinx.serialization.json.JsonObject(it + extra) })
    private fun reduce(s: FlowSnapshot, c: FlowCommand) = TemplateReducer.reduce(s, c, zone, now, types)
    private fun data(s: FlowSnapshot, c: FlowCommand) = (reduce(s, c) as FlowCommandResult.Applied).snapshot.data
    private fun create(id: String = "work") = FlowCommand.CreateTemplate(template(id), "create-$id", now)

    @Test fun appliesAcrossCivilDaysAndRepeatedApplicationKeepsTaskInstances() {
        val created = data(snapshot(), create())
        val apply = FlowCommand.ApplyTemplate("work", "2026-10-20", 2, "apply", now)
        val once = data(FlowSnapshot.fromData(created), apply)
        val twice = data(FlowSnapshot.fromData(once), apply)
        assertEquals(2, once["tasks"]!!.jsonArray.size)
        assertEquals(listOf("2026-10-20", "2026-10-21"), once["tasks"]!!.jsonArray.map { it.jsonObject["planDate"]!!.jsonPrimitive.content }.sorted())
        assertEquals(once["tasks"], twice["tasks"])
        assertEquals(once["nid"], twice["nid"])
        assertEquals(once["progressDays"], twice["progressDays"])
    }

    @Test fun updatingAndDeletingTemplateFreezesOnlyWinningRuleDates() {
        val original = template()
        val state = snapshot(buildJsonObject {
            put("dayTemplates", buildJsonArray { add(original) })
            put("planRules", buildJsonArray { add(buildJsonObject { put("id", "r"); put("start", "2026-10-20"); put("end", "2026-10-22"); put("days", buildJsonArray { repeat(3) { add(buildJsonObject { put("tplId", "work") }) } }) }) })
            put("planDayZones", buildJsonObject { put("2026-10-21", JsonArray(emptyList())) })
        })
        val update = FlowCommand.UpdateTemplate("work", template("work", "Changed"), "update", now)
        val updated = data(state, update)
        assertTrue(updated["planDayZones"]!!.jsonObject.containsKey("2026-10-20"))
        assertEquals(JsonArray(emptyList()), updated["planDayZones"]!!.jsonObject["2026-10-21"])
        assertTrue(updated["planDayZones"]!!.jsonObject.containsKey("2026-10-22"))
        val deleted = data(FlowSnapshot.fromData(updated), FlowCommand.DeleteTemplate("work", "delete", now))
        assertEquals(0, deleted["dayTemplates"]!!.jsonArray.size)
        assertTrue(deleted["planDayZones"]!!.jsonObject.containsKey("2026-10-20"))
    }

    @Test fun manualEmptyBlocksWinAndOrphanReferencesSurvive() {
        val s = snapshot(buildJsonObject {
            put("dayTemplates", buildJsonArray { add(template()) })
            put("planRules", buildJsonArray { add(buildJsonObject { put("start", "2026-10-20"); put("end", "2026-10-21"); put("days", buildJsonArray { repeat(2) { add(buildJsonObject { put("tplId", "work") }) } }) }) })
            put("planDayZones", buildJsonObject { put("2026-10-20", JsonArray(emptyList())) })
            put("extraRoot", "keep")
        })
        val deleted = data(s, FlowCommand.DeleteTemplate("work", "delete", now))
        assertEquals(0, deleted["planDayZones"]!!.jsonObject["2026-10-20"]!!.jsonArray.size)
        assertEquals(1, deleted["planDayZones"]!!.jsonObject["2026-10-21"]!!.jsonArray.size)
        assertEquals("keep", deleted["extraRoot"]!!.jsonPrimitive.content)
        assertEquals("work", deleted["planRules"]!!.jsonArray.single().jsonObject["days"]!!.jsonArray[0].jsonObject["tplId"]!!.jsonPrimitive.content)
    }

    @Test fun invalidInputAndMissingUpdateAreAtomic() {
        val s = snapshot()
        val bad = FlowCommand.CreateTemplate(buildJsonObject { put("name", "Bad"); put("blocks", buildJsonArray { add(buildJsonObject { put("zoneId", 1); put("s", "10:00"); put("e", "09:00") }) }) }, "bad", now)
        assertTrue(reduce(s, bad) is FlowCommandResult.Rejected)
        assertTrue(reduce(s, FlowCommand.UpdateTemplate("missing", template(), "missing", now)) is FlowCommandResult.Rejected)
        assertTrue(reduce(s, FlowCommand.ApplyTemplate("missing", "2026-10-20", 1, "missing-apply", now)) is FlowCommandResult.Rejected)
        assertEquals(s.data, s.data)
    }

    @Test fun dateBoundsMidnightEndAndDuplicateEntryIdsAreHandled() {
        val payload = buildJsonObject {
            put("name", "Day"); put("blocks", buildJsonArray {
                add(buildJsonObject { put("zoneId", 1); put("s", "09:00"); put("e", "00:00") })
            })
            put("tasks", buildJsonArray { repeat(2) {
                add(buildJsonObject { put("id", "same"); put("task", buildJsonObject { put("title", "Read"); put("type", "simple") }) })
            } })
        }
        val created = data(snapshot(), FlowCommand.CreateTemplate(payload, "create", now))
        val blocks = created["dayTemplates"]!!.jsonArray.single().jsonObject["blocks"]!!.jsonArray
        assertEquals("00:00", blocks.single().jsonObject["e"]!!.jsonPrimitive.content)
        val applied = data(FlowSnapshot.fromData(created), FlowCommand.ApplyTemplate("tpl$now", "2026-10-20", 1, "apply", now))
        assertEquals(2, applied["tasks"]!!.jsonArray.size)
        assertEquals(setOf("tpl$now:same:2026-10-20", "tpl$now:same-1:2026-10-20"), applied["tasks"]!!.jsonArray.map { it.jsonObject["dayPresetKey"]!!.jsonPrimitive.content }.toSet())
        assertTrue(reduce(FlowSnapshot.fromData(created), FlowCommand.ApplyTemplate("tpl$now", "9999-12-31", 2, "year-overflow", now)) is FlowCommandResult.Rejected)
        assertTrue(reduce(FlowSnapshot.fromData(created), FlowCommand.ApplyTemplate("tpl$now", "+10000-01-01", 1, "extended-year", now)) is FlowCommandResult.Rejected)
    }
}
