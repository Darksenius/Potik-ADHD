package com.flow.adhd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowCommandResult
import com.flow.adhd.domain.FlowEngine
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.TaskFactory
import com.flow.adhd.domain.TaskHistory
import java.time.ZoneId
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only the pure native engine: no Activity, WebView, Room writes, or network. */
@RunWith(AndroidJUnit4::class)
class NativeCoreTest {
    @Test fun allEditorTypesRetainDetailsAndDailyCounterStartsNewOccurrence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = context.assets.open("public/contracts/task-types.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) as JsonObject }
        val engine = FlowEngine(catalog.mapValues { (_, row) -> ((row as JsonObject)["xp"] as JsonPrimitive).content.toDouble() })
        val zone = ZoneId.systemDefault()
        val at = System.currentTimeMillis()
        val day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
        var state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"done":0,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"qnotes":[],"recur":[],"zones":[{"id":7,"nm":"Вечір","color":"#ff6b35","slots":[{"s":"18:00","e":"22:00"}]}]}""")
        for (type in catalog.keys) {
            val payload = buildJsonObject {
                put("title", "Приклад $type"); put("type", type); put("note", "Деталі\nКод: 57")
                when (type) {
                    "sched" -> { put("schedDate", day); put("schedTime", "15:30"); put("reminderEnabled", true); put("remindBeforeMinutes", 10) }
                    "alarm" -> put("alarmTime", "22:00")
                    "timewin" -> { put("windowStart", "22:00"); put("windowEnd", "02:00") }
                    "check" -> put("items", Json.parseToJsonElement("""[{"text":"Ключі","done":false}]"""))
                    "counter" -> { put("counterTarget", 6); put("counterStep", 2); put("counterUnit", "count"); put("repeat", "daily") }
                    "negative" -> put("negXp", 0)
                    "zonelinked" -> put("zoneId", 7)
                }
            }
            val result = engine.apply(state, FlowCommand.CreateTask(payload, "create-$type", at), zone, at, activeZoneId = 7)
            check(result is FlowCommandResult.Applied) { "Editor rejected $type: $result" }
            state = result.snapshot
        }
        assertEquals(10, state.tasks.size)
        assertEquals(10, state.tasks.map { it.id }.toSet().size)
        state.tasks.forEach { assertEquals("Деталі\nКод: 57", it.note) }
        val counter = state.tasks.first { it.type == "counter" }
        assertEquals("2", counter.raw["counterStep"].toString())
        val completed = engine.apply(state, FlowCommand.SetTaskDone(counter.id, true, at, "done", at), zone, at) as FlowCommandResult.Applied
        val tomorrow = Instant.ofEpochMilli(at).atZone(zone).plusDays(1).toInstant().toEpochMilli()
        val tick = engine.apply(completed.snapshot, FlowCommand.LifecycleTick("tomorrow", tomorrow), zone, tomorrow) as FlowCommandResult.Applied
        assertFalse(tick.snapshot.tasks.first { it.id == counter.id }.done)
        assertEquals(completed.snapshot.data["xpTotal"], tick.snapshot.data["xpTotal"])
        assertEquals(1, TaskHistory.completedOccurrences(tick.snapshot.data["taskHistory"] as JsonArray, zone = zone).size)
        assertEquals(1, (tick.snapshot.data["dailyInsightClaims"] as JsonArray).size)
    }

    @Test fun captureCompleteReopenCounterAndNotesShareOneActivityDayOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = context.assets.open("public/contracts/task-types.json").bufferedReader().use {
            Json.parseToJsonElement(it.readText()) as JsonObject
        }
        assertEquals(10, catalog.size)
        val engine = FlowEngine(catalog.mapValues { (_, row) -> ((row as JsonObject)["xp"] as JsonPrimitive).content.toDouble() })
        val zone = ZoneId.systemDefault()
        val at = System.currentTimeMillis()
        var state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"done":0,"negCount":0,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"qnotes":[],"folders":[],"custom":"keep"}""")
        fun apply(command: FlowCommand) {
            val result = engine.apply(state, command, zone, at)
            check(result is FlowCommandResult.Applied) { "Native command rejected: $result" }
            state = result.snapshot
        }
        apply(FlowCommand.CaptureTask("Забрати посилку", "capture", at))
        val id = state.tasks.single().id
        apply(FlowCommand.SetTaskDone(id, true, at, "done", at))
        apply(FlowCommand.SetTaskDone(id, false, at, "reopen", at))
        apply(FlowCommand.SetTaskDone(id, true, at, "redo", at))
        assertEquals("10", state.data["xpTotal"].toString())
        apply(FlowCommand.AddQuickNote(Json.parseToJsonElement("""{"txt":"Думка офлайн"}""") as JsonObject, "note", at))
        val fixtures = listOf(TaskFactory.create(301, "Листи", "counter", at), TaskFactory.create(302, "Ключі", "check", at).let {
            JsonObject(it + ("items" to Json.parseToJsonElement("""[{"text":"Взяти ключі","done":false}]""")))
        }, TaskFactory.create(303, "Подія", "negative", at))
        state = FlowSnapshot.parse(JsonObject(state.data + ("tasks" to JsonArray(state.tasks.map { it.raw } + fixtures))).toString())
        apply(FlowCommand.ChangeCounter(301, 10.0, "counter", at))
        apply(FlowCommand.SetChecklistItem(302, 0, true, "step", at))
        apply(FlowCommand.RecordNegative(303, 1.0, "journal", at))
        assertEquals("30", state.data["xpTotal"].toString())
        assertEquals("25", state.data["xp"].toString())
        assertEquals(1, (state.data["progressDays"] as JsonArray).size)
        assertEquals(1, (state.data["dailyInsightClaims"] as JsonArray).size)
        assertEquals("Думка офлайн", (state.qnotes.single()["txt"] as JsonPrimitive).content)
        assertEquals("\"keep\"", state.data["custom"].toString())
        assertFalse(state.tasks.first { it.id == 301L }.done)
        assertFalse(state.tasks.first { it.id == 302L }.done)
        assertEquals(1, TaskHistory.completedOccurrences(state.data["taskHistory"] as JsonArray, zone = zone).size)
    }
}
