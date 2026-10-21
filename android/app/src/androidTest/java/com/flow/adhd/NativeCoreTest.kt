package com.flow.adhd

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.CaptureOrigin
import com.flow.adhd.domain.FlowCommandResult
import com.flow.adhd.domain.FlowEngine
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.TaskFactory
import com.flow.adhd.domain.TaskHistory
import com.flow.adhd.domain.PlannerProjection
import com.flow.adhd.domain.ZoneCalendar
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.QuestDeferralKind
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
    @Test fun presetsCreateIndependentDaysAndUndoOnlyTheUnchangedCompletion() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-16T18:30:00Z").toEpochMilli()
        val engine = FlowEngine(mapOf("simple" to 10.0, "counter" to 2.0))
        var state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"zones":[],"nid":300,
          "priorities":[null,null,null],"xp":0,"xpTotal":0,"level":1,"done":0,
          "progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[]}""")
        fun apply(command: FlowCommand): FlowCommandResult.Applied {
            val result = engine.apply(state, command, zone, command.activityAt)
            check(result is FlowCommandResult.Applied) { "Native command rejected: $result" }
            state = result.snapshot
            return result
        }
        val preset = Json.parseToJsonElement("""{"id":"home","name":"Дім","blocks":[],"tasks":[
          {"id":"sweep","task":{"title":"Підмести","type":"simple"}},
          {"id":"stretch","task":{"title":"Розминка","type":"counter","counterTarget":3}}]}""") as JsonObject
        apply(FlowCommand.CreateTemplate(preset, "create", at))
        apply(FlowCommand.ApplyTemplate("home", "2026-10-16", 2, "apply", at))
        assertEquals(4, state.tasks.size)
        val today = state.tasks.first { it.type == "simple" && it.raw["planDate"].toString() == "\"2026-10-16\"" }
        val done = apply(FlowCommand.SetTaskDone(today.id, true, at, "done", at))
        check(done.undoReceipt != null)
        assertEquals("10", state.data["xpTotal"].toString())
        apply(FlowCommand.UndoTask(today.id, "undo", at + 1000, receipt = done.undoReceipt))
        assertEquals(false, state.tasks.first { it.id == today.id }.done)
        assertEquals("0", state.data["xpTotal"].toString())
        assertEquals("[\"2026-10-16\"]", state.data["progressDays"].toString())
        apply(FlowCommand.SetTaskDone(today.id, true, at + 2000, "redo", at + 2000))
        val tasks = state.tasks.map { it.raw }
        val xp = state.data["xpTotal"]
        apply(FlowCommand.ApplyTemplate("home", "2026-10-16", 2, "reapply", at + 3000))
        assertEquals(tasks, state.tasks.map { it.raw })
        assertEquals(xp, state.data["xpTotal"])
        assertEquals(false, state.tasks.first { it.type == "simple" && it.raw["planDate"].toString() == "\"2026-10-17\"" }.done)
        val stale = engine.apply(state, FlowCommand.UndoTask(today.id, "old-undo", at + 4000, receipt = done.undoReceipt), zone, at + 4000)
        assertEquals(true, stale is FlowCommandResult.Rejected)
    }

    @Test fun taskAndDayManagementPreserveCompletionCreditAndDeletedZoneReferences() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-16T19:00:00Z").toEpochMilli()
        val engine = FlowEngine(mapOf("simple" to 10.0, "sched" to 15.0, "zonelinked" to 10.0))
        var state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"zones":[],"nid":300,
          "priorities":[null,null,null],"xp":0,"xpTotal":0,"level":1,"done":0,
          "progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],"custom":"keep"}""")
        fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
        fun apply(command: FlowCommand) {
            val result = engine.apply(state, command, zone, at)
            check(result is FlowCommandResult.Applied) { "Native command rejected: $result" }
            state = result.snapshot
        }
        apply(FlowCommand.CreateZone(json("""{"nm":"Вечір"}"""), "zone", at))
        apply(FlowCommand.UpdateZone(1, json("""{"nm":"Вечір","color":"#ff6b35","desc":"Вдома","prio":3,"slots":[{"s":"18:00","e":"22:00"}]}"""), "zone-edit", at))
        apply(FlowCommand.AddPlanItem("2026-10-16", json("""{"text":"18:30 Прибрати кухню"}"""), "planned", at))
        val id = state.tasks.single().id
        apply(FlowCommand.SetPriority(0, id, "priority", at))
        assertEquals(id.toString(), (state.data["priorities"] as JsonArray)[0].toString())
        apply(FlowCommand.SetPriority(0, null, "clear-priority", at))
        assertEquals("null", (state.data["priorities"] as JsonArray)[0].toString())
        apply(FlowCommand.SetTaskDone(id, true, at, "done", at))
        val credited = state.data["xpTotal"]
        apply(FlowCommand.TrashTask(id, "trash", at))
        apply(FlowCommand.RestoreTrashedTask(id, "restore", at))
        apply(FlowCommand.MoveTaskToSomeday(id, "someday", at))
        apply(FlowCommand.RestoreTaskFromSomeday(id, "return", at))
        apply(FlowCommand.SetTaskDone(id, true, at, "done-again", at))
        assertEquals(credited, state.data["xpTotal"])
        assertEquals(1, TaskHistory.completedOccurrences(state.data["taskHistory"] as JsonArray, zone = zone).size)

        val blocks = json("""{"blocks":[{"zoneId":1,"s":"09:00","e":"10:00","custom":"keep"}]}""")
        apply(FlowCommand.SetPlanDay("2026-10-17", blocks, "day", at))
        val manual = state.data["planDayZones"]
        apply(FlowCommand.DeleteZone(1, "delete-zone", at))
        assertEquals(manual, state.data["planDayZones"])
        apply(FlowCommand.CreateZone(json("""{"nm":"Інша зона"}"""), "new-zone", at))
        assertEquals("2", state.zones.single()["id"].toString())
        assertEquals("\"keep\"", state.data["custom"].toString())
    }

    @Test fun lastNamedZoneUsesTheSameFallbackInNotificationsAndNativeCore() {
        val zone = ZoneId.of("UTC")
        val at = Instant.parse("2026-10-16T21:30:00Z").toEpochMilli()
        val state = Json.parseToJsonElement("""{"zones":[{"id":7,"slots":[{"s":"18:00","e":"22:00"}]}]}""") as JsonObject
        val timeline = ZoneCalendar.timeline(state, at, zone, 2)
        val task = Json.parseToJsonElement("""{"id":300,"title":"Підмести","zoneId":7}""") as JsonObject
        val native = QuestPolicy.createDeferral(task, QuestDeferralKind.NEXT_ZONE,
            QuestPolicy.Context(now = at, timeline = timeline, zones = listOf((state["zones"] as JsonArray)[0] as JsonObject), zone = zone), at)
        val notification = com.flow.adhd.QuestPolicy.deferNextZone(
            com.flow.adhd.QuestPolicy.Task.builder("300").zone(7).build(),
            timeline.map { com.flow.adhd.QuestPolicy.Segment(it.zoneId.toInt(), it.startAt, it.endAt) },
            at, java.util.TimeZone.getTimeZone("UTC"))
        check(notification != null)
        assertEquals("not-today", notification.kind)
        assertEquals("\"not-today\"", native["kind"].toString())
        assertEquals(Instant.parse("2026-10-17T00:00:00Z").toEpochMilli(), notification.startAt)
        assertEquals(notification.startAt.toString(), native["startAt"].toString())
        assertEquals("7", native["targetZoneId"].toString())
        assertEquals(7, notification.targetZoneId)
    }

    @Test fun zoneOverlaysDoNotResetCreditAndDeferredTasksReturnToTheirSourceZone() {
        val zone = ZoneId.of("UTC")
        val engine = FlowEngine(mapOf("simple" to 10.0))
        fun at(text: String) = Instant.parse(text).toEpochMilli()
        var state = FlowSnapshot.parse("""{
          "formatVersion":2,"xp":0,"xpTotal":0,"level":1,"done":0,
          "progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[],
          "tasks":[
            {"id":300,"title":"Провітрити","type":"simple","repeat":"everyzone","zoneId":7,"done":false,"created":"2026-10-16T09:00:00.000Z"},
            {"id":301,"title":"Підмести","type":"simple","repeat":"everyzone","zoneId":7,"done":false,"created":"2026-10-16T09:00:00.000Z"}],
          "zones":[{"id":7,"slots":[{"s":"18:00","e":"20:00"}]},
            {"id":8,"slots":[{"s":"20:00","e":"22:00"}]},
            {"id":9,"prio":5,"slots":[{"s":"19:00","e":"19:30"}]}]
        }""")
        fun apply(command: FlowCommand, now: Long = command.activityAt) {
            val result = engine.apply(state, command, zone, now)
            check(result is FlowCommandResult.Applied) { "Native command rejected: $result" }
            state = result.snapshot
        }
        val first = at("2026-10-16T18:05:00Z")
        apply(FlowCommand.LifecycleTick("first", first))
        apply(FlowCommand.SetTaskDone(300, true, first, "done", first))
        val overlay = at("2026-10-16T19:05:00Z")
        apply(FlowCommand.LifecycleTick("overlay", overlay))
        assertEquals(9L, ZoneCalendar.currentZoneId(ZoneCalendar.timeline(state.data, overlay, zone, 1), overlay))
        assertEquals(true, state.tasks.first { it.id == 300L }.done)
        assertEquals("10", state.data["xpTotal"].toString())

        val request = at("2026-10-16T19:45:00Z")
        apply(FlowCommand.DeferTask(301, QuestDeferralKind.NEXT_ZONE, request, null, "defer", request))
        fun available(now: Long): Boolean = QuestPolicy.availability(state.tasks.first { it.id == 301L }.raw,
            QuestPolicy.Context(now = now, timeline = ZoneCalendar.timeline(state.data, now, zone, 2), zones = state.zones, zone = zone)).eligible
        assertFalse(available(request))
        assertEquals(true, available(at("2026-10-16T20:15:00Z")))
        assertFalse(available(at("2026-10-16T22:15:00Z")))
        val returned = at("2026-10-17T18:05:00Z")
        apply(FlowCommand.LifecycleTick("returned", returned))
        assertEquals(true, available(returned))
        apply(FlowCommand.SetTaskDone(301, true, returned, "deferred-done", returned))
        val task = state.tasks.first { it.id == 301L }.raw
        assertEquals(true, task["completionDeferral"] is JsonObject)
        assertFalse(task.containsKey("questDeferral"))
        assertEquals(true, task["occurrenceId"].toString().contains("zone:2026-10-16:7:18:00"))
        assertEquals("20", state.data["xpTotal"].toString())
    }

    @Test fun plannerManualOverridesAndCivilDayCyclesRemainIndependentOfDst() {
        val state = Json.parseToJsonElement("""{
          "planDayZones":{"2026-10-25":[]},"planRestDays":{"2026-10-25":false},
          "planRules":[{"start":"2026-10-24","end":"2026-10-28","days":[
            {"tplId":"home","rest":true},{"tplId":"home","rest":true},{"tplId":"work","rest":false}]}],
          "dayTemplates":[{"id":"home","blocks":[{"zoneId":7,"s":"18:00","e":"22:00"}]},
            {"id":"work","blocks":[{"zoneId":8,"s":"09:00","e":"17:00","label":"template-only"}]}]
        }""") as JsonObject
        assertEquals(emptyList<JsonObject>(), PlannerProjection.dayBlocks(state, "2026-10-25"))
        assertFalse(PlannerProjection.isRest(state, "2026-10-25"))
        val next = PlannerProjection.dayBlocks(state, "2026-10-26").single()
        assertEquals("8", next["zoneId"].toString())
        assertFalse(next.containsKey("label"))
        assertEquals("7", PlannerProjection.dayBlocks(state, "2026-10-27").single()["zoneId"].toString())
    }

    @Test fun repeatedNotificationCaptureRetainsItsIdWithoutDuplicateReward() {
        val engine = FlowEngine(mapOf("simple" to 10.0))
        val zone = ZoneId.systemDefault()
        val at = System.currentTimeMillis()
        val state = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"nid":300,"xp":0,"xpTotal":0,"level":1,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"unlocked":[]}""")
        val command = FlowCommand.CaptureTask("Зі сповіщення", "notification", at, CaptureOrigin.NOTIFICATION, 777)
        val first = engine.apply(state, command, zone, at) as FlowCommandResult.Applied
        val repeated = engine.apply(first.snapshot, command, zone, at) as FlowCommandResult.Applied
        assertEquals(777L, first.snapshot.tasks.single().id)
        assertEquals("10", repeated.snapshot.data["xpTotal"].toString())
        assertEquals(first.snapshot.data, repeated.snapshot.data)
        assertEquals(0, repeated.events.size)
    }

    @Test fun allEditorTypesRetainDetailsAndDailyCounterStartsNewOccurrence() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = context.assets.open("content/task-types.json").bufferedReader().use { Json.parseToJsonElement(it.readText()) as JsonObject }
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
        val catalog = context.assets.open("content/task-types.json").bufferedReader().use {
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
