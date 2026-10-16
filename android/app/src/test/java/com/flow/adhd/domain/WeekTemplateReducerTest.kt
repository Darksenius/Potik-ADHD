package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class WeekTemplateReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-24T09:00:00Z").toEpochMilli()
    private val engine = FlowEngine(mapOf("simple" to 10.0, "counter" to 2.0))
    private val initial = FlowSnapshot.parse("""{
      "tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"done":0,
      "progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],
      "zones":[{"id":7,"slots":[{"s":"18:00","e":"22:00"}]}],
      "dayTemplates":[{"id":"home","name":"Дім","blocks":[{"zoneId":7,"s":"18:00","e":"22:00"}],"tasks":[
        {"id":"sweep","task":{"title":"Підмести","type":"simple","zoneId":7}},
        {"id":"counter","task":{"title":"Лічильник","type":"counter","counterTarget":3}}]}],
      "weekTemplates":[{"id":"cycle","name":"Цикл","days":[{"tplId":"home"},{"tplId":"","rest":true},{"tplId":"home"}]}],
      "planDayZones":{"2026-10-24":[]},"planRestDays":{"2026-10-24":true},"planDayOff":{"2026-10-24":[7]},"custom":"keep"
    }""")

    @Test fun cycleUsesCivilDatesAcrossDstAndReapplyKeepsExistingCompletion() {
        val command = FlowCommand.ApplyWeekTemplate("cycle", "2026-10-24", 3, "apply", at)
        val applied = engine.apply(initial, command, zone, at) as FlowCommandResult.Applied
        assertEquals(4, applied.snapshot.tasks.size)
        assertEquals(setOf("2026-10-24", "2026-10-26"), applied.snapshot.tasks.map { it.raw["planDate"].toString().trim('"') }.toSet())
        assertEquals(4, applied.snapshot.tasks.map { it.id }.toSet().size)
        assertTrue(PlannerProjection.isRest(applied.snapshot.data, "2026-10-25"))
        assertEquals(initial.data["xpTotal"], applied.snapshot.data["xpTotal"])
        val first = applied.snapshot.tasks.first { it.raw["planDate"].toString() == "\"2026-10-24\"" && it.type == "simple" }
        val done = engine.apply(applied.snapshot, FlowCommand.SetTaskDone(first.id, true, at, "done", at), zone, at) as FlowCommandResult.Applied
        val again = engine.apply(done.snapshot, command.copy(commandId = "reapply"), zone, at) as FlowCommandResult.Applied
        assertEquals(done.snapshot.tasks.map { it.raw }, again.snapshot.tasks.map { it.raw })
        assertEquals(done.snapshot.data["xpTotal"], again.snapshot.data["xpTotal"])
        assertEquals("\"keep\"", again.snapshot.data["custom"].toString())
    }

    @Test fun invalidScheduleCannotPartiallyClearManualDaysOrAddTasks() {
        val invalidFlag = Json.parseToJsonElement("""{"name":"Bad","days":[{"tplId":"home","rest":"true"}]}""") as JsonObject
        assertTrue(engine.apply(initial, FlowCommand.CreateWeekTemplate(invalidFlag, "bad-flag", at), zone, at) is FlowCommandResult.Rejected)
        for ((date, span) in listOf("2026-02-30" to 2, "2026-10-24" to 0, "9999-12-31" to 2)) {
            assertTrue(engine.apply(initial, FlowCommand.ApplyWeekTemplate("cycle", date, span, "bad", at), zone, at) is FlowCommandResult.Rejected)
            assertEquals("[]", (initial.data["planDayZones"] as JsonObject)["2026-10-24"].toString())
            assertTrue(initial.tasks.isEmpty())
        }
    }

    @Test fun authoringAndDeletingWeekPresetKeepAppliedRuleAndInstances() {
        val payload = Json.parseToJsonElement("""{"id":"authored","name":"  Новий  ","days":[{"tplId":"home","rest":false}]}""") as JsonObject
        val authored = engine.apply(initial, FlowCommand.CreateWeekTemplate(payload, "create", at), zone, at) as FlowCommandResult.Applied
        val applied = engine.apply(authored.snapshot, FlowCommand.ApplyWeekTemplate("authored", "2026-10-24", 1, "apply", at), zone, at) as FlowCommandResult.Applied
        val deleted = engine.apply(applied.snapshot, FlowCommand.DeleteWeekTemplate("authored", "delete", at), zone, at) as FlowCommandResult.Applied
        assertEquals(applied.snapshot.data["planRules"], deleted.snapshot.data["planRules"])
        assertEquals(applied.snapshot.tasks.map { it.raw }, deleted.snapshot.tasks.map { it.raw })
        assertEquals(1, (deleted.snapshot.data["weekTemplates"] as JsonArray).size)
    }
}
