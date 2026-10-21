package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskEditorReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val types = setOf("simple", "note", "check", "counter", "alarm", "sched", "timewin", "ctx", "negative", "zonelinked")

    @Test fun createsTrimmedChecklistWithFreshSafeIdAndInjectedActivityDay() {
        val initial = snapshot(tasks = listOf(task("""{"id":500,"created":"c","title":"Existing","type":"simple"}""")), nid = 301)
        val at = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()
        val result = applied(TaskEditorReducer.reduce(
            initial,
            FlowCommand.CreateTask(payload("""{"title":"  Pack bag  ","type":"check","items":[{"text":" Keys "},{"text":" "}],"tags":[" trip "," "]}"""), "create", at),
            zone, types, now = at,
        ))
        val created = result.snapshot.tasks.first()

        assertEquals(501L, created.id)
        assertEquals("Pack bag", created.title)
        assertEquals("check", created.type)
        assertTrue((created.raw["expanded"] as kotlinx.serialization.json.JsonPrimitive).content.toBoolean())
        assertEquals(Json.parseToJsonElement("""[{"text":"Keys","done":false,"credited":false}]"""), created.raw["items"])
        assertEquals(Json.parseToJsonElement("""["trip"]"""), created.raw["tags"])
        assertEquals(502, result.snapshot.data["nid"]?.toString()?.toInt())
        assertTrue((result.snapshot.data["progressDays"] as JsonArray).any { it.toString().trim('"') == "2024-03-01" })
    }

    @Test fun noteAndCounterPayloadsApplyEditorDefaultsAndValues() {
        val initial = snapshot()
        val at = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()
        val note = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Journal","type":"note","note":"first note"}"""), "note", at), zone, types, now = at,
        )).snapshot.tasks.first()
        assertEquals("first note", note.note)

        val counter = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Water","type":"counter","counterTarget":0,"counterStep":5,"counterUnit":"ml","repeat":"interval","repeatInterval":2,"repeatUnit":"hour"}"""), "counter", at), zone, types, now = at,
        )).snapshot.tasks.first()
        assertEquals(0, counter.raw["counterTarget"]?.toString()?.toInt())
        assertEquals(5, counter.raw["counterStep"]?.toString()?.toInt())
        assertEquals("ml", counter.raw["counterUnit"]?.toString()?.trim('"'))
        assertEquals(7_200_000L, counter.raw["repeatMs"]?.toString()?.toLong())
        assertEquals(0, counter.raw["nextRepeatAt"]?.toString()?.toInt())
        assertEquals(true, counter.raw["counterEnabled"]?.toString()?.toBoolean())
    }

    @Test fun typeChangeClearsStaleScheduleAndDeferralButPreservesUnknownTaskFields() {
        val raw = """{"id":12,"created":"c","title":"Scheduled","type":"sched","done":false,"someday":false,"planDate":"2024-03-03","schedDate":"2024-03-03","schedTime":"09:00","repeat":"daily","repeatDays":[true,false,false,false,false,false,false],"items":[{"text":"Legacy","done":false,"credited":false,"futureItem":{"preserve":9}}],"alarmFired":true,"firedSched":true,"firedPre":true,"snoozeUntil":88,"questDeferral":{"kind":"not-today"},"completionDeferral":{"kind":"not-today"},"future":{"preserve":7}}"""
        val initial = snapshot(tasks = listOf(task(raw)))
        val at = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()
        val result = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.UpdateTask(12, payload("""{"title":"  Plain  ","type":"simple","repeat":"none","tags":[]}"""), "update", at), zone, types, now = at,
        )).snapshot.tasks.single().raw

        assertEquals("Plain", result["title"]?.toString()?.trim('"'))
        assertFalse(result.containsKey("schedDate"))
        assertFalse(result.containsKey("schedTime"))
        assertEquals(0, result["snoozeUntil"]?.toString()?.toInt())
        assertFalse(result["alarmFired"]?.toString()?.toBoolean() ?: true)
        assertFalse(result["firedSched"]?.toString()?.toBoolean() ?: true)
        assertFalse(result["firedPre"]?.toString()?.toBoolean() ?: true)
        assertFalse(result.containsKey("questDeferral"))
        assertFalse(result.containsKey("completionDeferral"))
        assertEquals(Json.parseToJsonElement("""{"preserve":7}"""), result["future"])
        assertEquals(Json.parseToJsonElement("""[{"text":"Legacy","done":false,"credited":false,"futureItem":{"preserve":9}}]"""), result["items"])
        assertEquals("2024-03-03", result["planDate"]?.toString()?.trim('"'))
    }

    @Test fun zonelinkedUsesInjectedActiveZoneAndRefreshesZonePresentation() {
        val initial = snapshot(zones = listOf("""{"id":7,"nm":"Evening","color":"#abcdef"}"""))
        val at = 1_700_000_000_000L
        val task = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Zone task","type":"zonelinked"}"""), "zone", at), zone, types, now = at, activeZoneId = 7,
        )).snapshot.tasks.single()
        assertEquals(7L, task.zoneId)
        assertEquals("Evening", task.raw["zoneName"]?.toString()?.trim('"'))
        assertEquals("#abcdef", task.raw["zoneColor"]?.toString()?.trim('"'))
    }

    @Test fun malformedKnownFieldsAndUnknownTypesRejectWithoutMutation() {
        val initial = snapshot()
        val at = 1_700_000_000_000L
        val malformed = TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Bad","type":"simple","tags":"not-an-array"}"""), "bad", at), zone, types, now = at,
        )
        assertTrue(malformed is FlowCommandResult.Rejected)
        val unknown = TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Bad","type":"future-type"}"""), "unknown", at), zone, types, now = at,
        )
        assertTrue(unknown is FlowCommandResult.Rejected)
        val blank = TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"  ","type":"simple"}"""), "blank", at), zone, types, now = at,
        )
        assertTrue(blank is FlowCommandResult.Rejected)
        assertTrue(initial.tasks.isEmpty())
        assertNull(initial.data["nid"])
    }

    @Test fun missingUpdateIsIdempotent() {
        val initial = snapshot()
        val result = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.UpdateTask(99, payload("""{"title":"Not present","type":"simple"}"""), "missing", 1_700_000_000_000L), zone, types,
        ))
        assertTrue(result.snapshot === initial)
        assertTrue(result.events.isEmpty())
    }

    @Test fun editorMarkerCreatesScheduledTaskAndPreservesExplicitDate() {
        val at = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()
        val initial = snapshot()
        val created = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"  Call clinic ///12:40///  ","type":"simple","reminderEnabled":false}"""), "marked", at), zone, types, now = at,
        )).snapshot.tasks.single()
        assertEquals("Call clinic", created.title)
        assertEquals("sched", created.type)
        assertEquals("2024-03-01", created.raw["schedDate"]?.toString()?.trim('"'))
        assertEquals("12:40", created.raw["schedTime"]?.toString()?.trim('"'))
        assertEquals("true", created.raw["reminderEnabled"].toString())

        val explicit = applied(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Call ///12:40///","type":"simple","schedDate":"2024-03-04"}"""), "marked-date", at), zone, types, now = at,
        )).snapshot.tasks.single()
        assertEquals("2024-03-04", explicit.raw["schedDate"]?.toString()?.trim('"'))
        assertTrue(TaskEditorReducer.reduce(initial,
            FlowCommand.CreateTask(payload("""{"title":"Call ///24:60///","type":"simple"}"""), "bad-marked", at), zone, types, now = at,
        ) is FlowCommandResult.Rejected)
    }

    private fun applied(result: FlowCommandResult): FlowCommandResult.Applied = result as FlowCommandResult.Applied
    private fun payload(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun task(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun snapshot(tasks: List<JsonObject> = emptyList(), zones: List<String> = emptyList(), nid: Long? = null): FlowSnapshot {
        val taskJson = tasks.joinToString(",") { Json.encodeToString(JsonObject.serializer(), it) }
        val zoneJson = zones.joinToString(",")
        val nidField = nid?.let { ",\"nid\":$it" } ?: ""
        return FlowSnapshot.parse(
            """{"formatVersion":2,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"done":0,"unlocked":[],"zones":[$zoneJson],"tasks":[$taskJson]$nidField}""",
        )
    }
}
