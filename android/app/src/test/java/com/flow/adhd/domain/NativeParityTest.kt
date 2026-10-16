package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.boolean
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Fixtures are produced by the existing TS functions, not by the Kotlin implementation. */
@RunWith(Parameterized::class)
class NativeParityTest(private val name: String, private val group: String, private val fixture: JsonObject) {
    @Test fun matchesExistingTypeScriptResult() {
        if (group == "task") {
            val input = fixture["input"] as JsonObject
            val actual = TaskFactory.create(
                input.number("id"), input.text("title")!!, input.text("type")!!,
                Instant.parse(input.text("created")).toEpochMilli(),
                input.number("zoneId"), input["zone"] as JsonObject, input.text("alarmTime").orEmpty(),
            )
            assertEquals(name, fixture["expectedTask"], actual)
        } else if (group == "notes") {
            val data = fixture["command"] as JsonObject
            val id = data.text("id")!!
            val at = data.number("activityAt")
            val payload = data["payload"] as JsonObject
            val command = when (data.text("kind")) {
                "AddQuickNote" -> FlowCommand.AddQuickNote(payload, id, at)
                "UpdateQuickNote" -> FlowCommand.UpdateQuickNote(data.number("noteId"), payload, id, at)
                "SaveNotepadAsNote" -> FlowCommand.SaveNotepadAsNote(data.text("folderId"), id, at)
                "DeleteFolder" -> FlowCommand.DeleteNoteFolder(data.text("folderId")!!, id, at)
                else -> error("Unsupported parity command in $name")
            }
            val result = NotesReducer.reduce(
                FlowSnapshot.parse(fixture["initialState"].toString()), command,
                ZoneId.of(data.text("zone")), data.number("now"),
            )
            check(result is FlowCommandResult.Applied) { "$name rejected: $result" }
            assertEquals(name, fixture["expectedState"], result.snapshot.data)
        } else if (group == "schedule") {
            val task = fixture["task"] as JsonObject
            val at = fixture.number("at")
            val zone = ZoneId.of(fixture.text("zone"))
            val timeline = (fixture["timeline"] as JsonArray).map { row ->
                val segment = row as JsonObject
                TimelineSegment(segment.number("zoneId"), segment.number("startAt"), segment.number("endAt"))
            }
            val context = ProjectionContext(at, timeline, zone)
            val value: Any? = when (fixture.text("method")) {
                "taskDoneOn" -> TaskSchedule.doneOn(task, fixture.text("day")!!, context, (fixture["history"] as JsonArray).map { it as JsonObject })
                "isTaskInInbox" -> TaskSchedule.isUnscheduled(task)
                "isTaskInTimeWindow" -> TaskSchedule.window(task, at, zone)
                "repeatDueOn" -> TaskSchedule.repeatDueOn(task, at, zone)
                "nextTaskReminderAt" -> TaskSchedule.nextReminderAt(task, context)
                "taskPreReminderAt" -> TaskSchedule.preReminderAt(task, at)
                else -> error("Unsupported schedule parity method")
            }
            val actual = when (value) {
                is Boolean -> JsonPrimitive(value)
                is Long -> JsonPrimitive(value)
                null -> JsonNull
                else -> error("Unexpected schedule value")
            }
            assertEquals(name, fixture["expectedValue"], actual)
        } else if (group == "editor") {
            val data = fixture["command"] as JsonObject
            val at = data.number("activityAt")
            val command = if (data.text("kind") == "CreateTask") FlowCommand.CreateTask(data["payload"] as JsonObject, name, at)
                else FlowCommand.UpdateTask(data.number("taskId"), data["payload"] as JsonObject, name, at)
            val result = TaskEditorReducer.reduce(FlowSnapshot.parse(fixture["initialState"].toString()), command,
                ZoneId.of(data.text("zone")), taskXp.keys, data.number("now"), (data["activeZoneId"] as? JsonPrimitive)?.long)
            check(result is FlowCommandResult.Applied) { "$name rejected: $result" }
            assertEquals(name, fixture["expectedState"], result.snapshot.data)
        } else if (group == "lifecycle") {
            val data = fixture["command"] as JsonObject
            val state = FlowSnapshot.parse(fixture["initialState"].toString())
            val at = data.number("at")
            val zone = ZoneId.of(data.text("zone"))
            val resolver = { zoneId: Long, instant: Long -> "${Instant.ofEpochMilli(instant).atZone(zone).toLocalDate()}:$zoneId:09:00" }
            val result = when (data.text("kind")) {
                "ResetRepeating" -> TaskLifecycle.resetRepeating(state, at, zone)
                "CheckZoneRepeats" -> TaskLifecycle.checkZoneRepeats(state, at, zone, resolver, (data["taskId"] as? JsonPrimitive)?.long)
                "DailyReset" -> TaskLifecycle.dailyReset(state, data.text("savedDate"), at, zone)
                else -> error("Unsupported lifecycle parity method")
            }
            assertEquals(name, fixture["expectedState"], result.data)
        } else if (group == "completion") {
            val data = fixture["command"] as JsonObject
            val at = data.number("completedAt")
            val zone = ZoneId.of(data.text("zone"))
            val result = TaskCompletionReducer.reduce(
                FlowSnapshot.parse(fixture["initialState"].toString()),
                FlowCommand.SetTaskDone(data.number("taskId"), (data["done"] as JsonPrimitive).boolean, at, name, at),
                zone, taskXp, data.number("now"),
                { zoneId, instant -> "${Instant.ofEpochMilli(instant).atZone(zone).toLocalDate()}:$zoneId:09:00" },
            )
            check(result is FlowCommandResult.Applied) { "$name rejected: $result" }
            assertEquals(name, fixture["expectedState"], result.snapshot.data)
        } else {
            val state = fixture["initialState"] as JsonObject
            val at = (fixture["activityAt"] as? JsonPrimitive)?.long ?: fixture.number("now")
            val zone = ZoneId.of(fixture.text("zone"))
            val now = fixture.number("now")
            val result = if (group == "xp") Experience.award(state,
                (fixture["amount"] as JsonPrimitive).content.toDouble(), at, zone, now)
            else Experience.setEnergy(state, fixture.number("value").toInt(), at, zone, now)
            assertEquals(name, fixture["expectedState"], result.state)
        }
    }

    companion object {
        private var taskXp: Map<String, Double> = emptyMap()
        @JvmStatic @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val root = NativeParityTest::class.java.getResourceAsStream("/native-parity.json")!!
                .bufferedReader(Charsets.UTF_8).use { Json.parseToJsonElement(it.readText()) as JsonObject }
            taskXp = (root["taskXp"] as JsonObject).mapValues { (_, value) -> (value as JsonPrimitive).content.toDouble() }
            return buildList {
                (root["taskFactoryCases"] as JsonArray).forEach { entry ->
                    val item = entry as JsonObject
                    add(arrayOf("task-${item.text("type")}", "task", item))
                }
                (root["notes"] as JsonArray).forEachIndexed { index, entry ->
                    val item = entry as JsonObject
                    add(arrayOf("notes-$index-${item.text("scenario")}", "notes", item))
                }
                for ((key, group) in listOf("xpCases" to "xp", "energyCases" to "energy", "completionCases" to "completion", "editorCases" to "editor", "lifecycleCases" to "lifecycle", "scheduleCases" to "schedule")) {
                    (root[key] as JsonArray).forEachIndexed { index, entry ->
                        val item = entry as JsonObject
                        add(arrayOf("$group-$index-${item.text("name") ?: item.text("scenario")}", group, item))
                    }
                }
            }
        }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        private fun JsonObject.number(key: String): Long = (this[key] as JsonPrimitive).long
    }
}
