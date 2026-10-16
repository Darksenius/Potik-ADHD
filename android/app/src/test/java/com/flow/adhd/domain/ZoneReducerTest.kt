package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoneReducerTest {
    private val zone = ZoneId.of("UTC")
    private val at = 1_792_000_000_000L

    @Test fun createsWithSmallestUnusedSafeIdAndUiDefaults() {
        val initial = snapshot("""{"zones":[{"id":1},{"id":2}],"tasks":[{"id":20,"title":"linked","zoneId":3}],"lastZoneId":4}""")
        val result = applied(FlowCommand.CreateZone(obj("""{"nm":" Focus "}"""), "create", at), initial)
        val created = result.snapshot.zones.last()
        assertEquals(5L, created["id"].toString().toLong())
        assertEquals("Focus", created["nm"]?.toString()?.trim('"'))
        assertEquals(JsonArray(emptyList()), created["slots"])
        assertEquals(5.0, result.snapshot.data["xpTotal"].toString().toDouble(), 0.0)
    }

    @Test fun updatesPreservingUnknownZoneAndSlotFieldsAndRefreshesTaskBinding() {
        val initial = snapshot("""{"zones":[{"id":9007199254740991,"nm":"Old","color":"#000000","desc":"","prio":1,"slots":[{"s":"09:00","e":"10:00","futureSlot":7}],"futureZone":{"ok":true}}],"tasks":[{"id":1,"title":"linked","zoneId":9007199254740991,"futureTask":true}]}""")
        val result = applied(FlowCommand.UpdateZone(9007199254740991L, obj("""{"nm":" New ","color":"#ffffff","desc":" text ","prio":8,"slots":[{"s":"9:00","e":"10:00","newSlot":true}]}"""), "update", at), initial)
        val updated = result.snapshot.zones.single()
        assertEquals("New", updated["nm"]?.toString()?.trim('"'))
        assertEquals(5, updated["prio"]?.toString()?.toInt())
        assertEquals("09:00", (updated["slots"] as JsonArray).single().let { (it as JsonObject)["s"].toString().trim('"') })
        assertEquals("7", ((updated["slots"] as JsonArray).single() as JsonObject)["futureSlot"].toString())
        assertTrue(updated.containsKey("futureZone"))
        assertEquals("New", result.snapshot.tasks.single().raw["zoneName"]?.toString()?.trim('"'))
    }

    @Test fun roundsPriorityUsingJavaScriptHalfUpRuleAndRejectsUnknownPayloadKeys() {
        val initial = snapshot("""{"zones":[{"id":1,"nm":"Old","color":"#000000","desc":"","prio":1,"slots":[]}] }""")
        val rounded = applied(FlowCommand.UpdateZone(1, obj("""{"nm":"Old","color":"#000000","desc":"","prio":2.5,"slots":[]}"""), "round", at), initial)
        assertEquals(3, rounded.snapshot.zones.single()["prio"]?.toString()?.toInt())
        val rejected = ZoneReducer.reduce(initial, FlowCommand.UpdateZone(1, obj("""{"nm":"Old","color":"#000000","desc":"","prio":2,"slots":[],"extra":true}"""), "extra", at), zone, at)
        assertTrue(rejected is FlowCommandResult.Rejected)
    }

    @Test fun invalidTimeRangesRejectWithoutChangingSnapshot() {
        val initial = snapshot("""{"zones":[{"id":1,"nm":"Old","color":"#000000","desc":"","prio":1,"slots":[]}] }""")
        val result = ZoneReducer.reduce(initial, FlowCommand.UpdateZone(1, obj("""{"nm":"New","color":"#ffffff","desc":"","prio":3,"slots":[{"s":"10:00","e":"09:00"}]}"""), "bad", at), zone, at)
        assertTrue(result is FlowCommandResult.Rejected)
        assertEquals("Old", initial.zones.single()["nm"]?.toString()?.trim('"'))
    }

    @Test fun deleteRemovesZoneDefinitionAndRetainsTaskAndPlannerReferencesForRecovery() {
        val initial = snapshot("""{"zones":[{"id":7,"nm":"Old","color":"#000000","prio":1,"slots":[]}],"tasks":[{"id":1,"title":"keep","zoneId":7}],"planDayZones":{"2026-10-16":[{"zoneId":7,"s":"09:00","e":"10:00","ov":true},{"zoneId":7,"s":"10:00","e":"11:00","ov":false},{"zoneId":8,"s":"11:00","e":"12:00"}]},"planDayOff":{"2026-10-16":[7,8]},"dayTemplates":[{"id":"t","name":"T","blocks":[{"zoneId":7,"s":"09:00","e":"10:00"},{"zoneId":8,"s":"10:00","e":"11:00"}],"tasks":[]}],"planRules":[{"id":"r","start":"2026-10-16","end":"2026-10-20","days":[{"tplId":"t"}]}]}""")
        val next = applied(FlowCommand.DeleteZone(7, "delete", at), initial).snapshot
        assertTrue(next.zones.isEmpty())
        assertEquals(7L, next.tasks.single().zoneId)
        assertEquals(initial.data["planDayZones"], next.data["planDayZones"])
        assertEquals(initial.data["planDayOff"], next.data["planDayOff"])
        assertEquals(initial.data["dayTemplates"], next.data["dayTemplates"])
        assertEquals(1, (next.data["planRules"] as JsonArray).size)
    }

    private fun applied(command: FlowCommand, initial: FlowSnapshot) = ZoneReducer.reduce(initial, command, zone, at) as FlowCommandResult.Applied
    private fun snapshot(raw: String) = FlowSnapshot.parse(raw)
    private fun obj(raw: String) = Json.parseToJsonElement(raw) as JsonObject
}
