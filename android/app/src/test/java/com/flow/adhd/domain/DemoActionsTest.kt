package com.flow.adhd.domain

import java.time.ZoneId
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoActionsTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val engine = FlowEngine(mapOf("simple" to 10.0, "sched" to 10.0, "check" to 10.0, "counter" to 10.0, "note" to 10.0, "alarm" to 10.0, "timewin" to 10.0, "ctx" to 10.0, "negative" to 10.0, "zonelinked" to 10.0))
    private val at = java.time.Instant.parse("2026-10-16T10:00:00Z").toEpochMilli()
    private val payload = Json.parseToJsonElement("""
        {"id":"potik-intro-v1","folder":{"id":"potik-intro-day","name":"DEMO · День і нотатки","icon":"🧭"},
         "zones":[{"key":"morning","name":"Ранок · демо","color":"#e4aa35","start":"07:00","end":"12:00","description":"Morning"}],
         "today":[{"id":"one","title":"A task","type":"simple","note":"A note","repeat":"none"}],
         "tomorrow":[{"id":"two","title":"Another task","type":"alarm","note":"No auto alarms","repeat":"none","fields":{"alarmTime":"18:00","reminderEnabled":false}}],
         "notes":[{"text":"Today {today}","time":"09:00"}]}
    """.trimIndent()) as JsonObject

    @Test fun installationPreservesExistingFieldsAndIsDuplicateSafeWithoutXpOrAlarms() {
        val original = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[{"id":7,"title":"Keep me","type":"simple","futureField":{"v":4}}],"zones":[],"qnotes":[],"folders":[],"planDayZones":{},"xpTotal":23,"level":4,"nid":8,"qnid":10,"unknownState":{"keep":true}}""")
        val command = FlowCommand.InstallDemoPreset(payload, "install", at)
        val first = (engine.apply(original, command, zone, at) as FlowCommandResult.Applied).snapshot
        assertEquals(3, first.tasks.size)
        assertEquals(23.0, first.xpTotal, 0.0)
        assertEquals(4L, first.level)
        assertEquals(true, ((first.tasks.last().raw["futureField"] as JsonObject)["v"] as JsonPrimitive).longOrNull?.let { it == 4L })
        assertEquals(true, (first.data["unknownState"] as JsonObject).containsKey("keep"))
        assertEquals(false, first.tasks.first { it.title.contains("Another task") }.raw["reminderEnabled"].let { (it as JsonPrimitive).booleanOrNull })
        assertTrue(first.tasks.first { it.title.contains("Another task") }.raw["alarmFired"]?.let { (it as JsonPrimitive).booleanOrNull } == false)
        val repeated = (engine.apply(first, command.copy(commandId = "install-again"), zone, at) as FlowCommandResult.Applied).snapshot
        assertEquals(first.data, repeated.data)
        assertEquals(0, (engine.apply(original, command, zone, at) as FlowCommandResult.Applied).events.size)
    }

    @Test fun authoredCatalogInstallsExactlyTheTwoDayTenTypeIntroduction() {
        val catalogFile = listOf(
            File("src/content/demo-preset.json"),
            File("../src/content/demo-preset.json"),
            File("../../src/content/demo-preset.json"),
        ).firstOrNull(File::isFile) ?: error("Could not locate src/content/demo-preset.json from ${System.getProperty("user.dir")}")
        val catalog = Json.parseToJsonElement(catalogFile.readText()) as JsonObject
        val initial = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"zones":[],"qnotes":[],"folders":[],"planDayZones":{},"xpTotal":12,"level":3,"nid":300,"qnid":300}""")
        val result = (engine.apply(initial, FlowCommand.InstallDemoPreset(catalog, "demo", at), zone, at) as FlowCommandResult.Applied).snapshot
        assertEquals(18, result.tasks.size)
        assertEquals(setOf("simple", "check", "counter", "note", "alarm", "sched", "timewin", "ctx", "negative", "zonelinked"), result.tasks.map { it.type }.toSet())
        assertEquals(setOf("none", "daily", "weekly", "weekdays", "weekend", "interval", "custom", "everyzone"), result.tasks.mapNotNull { it.raw["repeat"]?.let { (it as JsonPrimitive).content } }.toSet())
        assertTrue(result.tasks.filter { it.type == "alarm" || it.type == "sched" }.all { it.raw["reminderEnabled"]?.let { v -> (v as JsonPrimitive).booleanOrNull } == false })
        assertEquals(12.0, result.xpTotal, 0.0)
        val repeated = (engine.apply(result, FlowCommand.InstallDemoPreset(catalog, "demo-again", at), zone, at) as FlowCommandResult.Applied).snapshot
        assertEquals(result.data, repeated.data)
    }

    @Test fun zoneIdSelectionSkipsOrphanReferencesAndAddsPlanBlocksForBothDays() {
        val original = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[{"id":1,"title":"orphan","type":"simple","zoneId":1}],"zones":[],"qnotes":[],"folders":[],"planDayZones":{},"nid":2,"qnid":1}""")
        val result = (engine.apply(original, FlowCommand.InstallDemoPreset(payload, "install", at), zone, at) as FlowCommandResult.Applied).snapshot
        val demoZoneId = result.zones.single().getValue("id").let { (it as JsonPrimitive).longOrNull }
        assertTrue(demoZoneId != 1L)
        val overrides = result.data["planDayZones"] as JsonObject
        assertEquals(1, (overrides.getValue("2026-10-16") as kotlinx.serialization.json.JsonArray).size)
        assertEquals(1, (overrides.getValue("2026-10-17") as kotlinx.serialization.json.JsonArray).size)
        assertFalse(result.tasks.any { it.title.startsWith("Приклад") && it.raw["reminderEnabled"]?.let { value -> (value as JsonPrimitive).booleanOrNull } == true })
    }

    @Test fun clearRemovesOnlyPresetTasksAndClearsPriorityReferencesWhileRetainingHistoryAndExtensions() {
        val initial = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[{"id":1,"title":"Demo","type":"simple","dayPresetKey":"potik-intro-v1:one:2026-10-16"},{"id":2,"title":"Mine","type":"simple","futureField":"keep"}],"zones":[],"qnotes":[],"folders":[],"priorities":[1,2,null],"taskHistory":[{"taskId":77,"future":"keep"}],"unknown":"unchanged"}""")
        val cleared = engine.apply(initial, FlowCommand.ClearDemoTasks("clear", at), zone, at) as FlowCommandResult.Applied
        assertEquals(listOf(2L), cleared.snapshot.tasks.map { it.id })
        assertEquals(JsonArray(listOf(JsonNull, JsonPrimitive(2), JsonNull)), cleared.snapshot.data["priorities"])
        assertEquals(initial.data["taskHistory"], cleared.snapshot.data["taskHistory"])
        assertEquals(initial.data["unknown"], cleared.snapshot.data["unknown"])
        assertEquals("keep", (cleared.snapshot.tasks.single().raw["futureField"] as JsonPrimitive).content)
        assertEquals(cleared.snapshot.data, (engine.apply(cleared.snapshot, FlowCommand.ClearDemoTasks("clear-again", at), zone, at) as FlowCommandResult.Applied).snapshot.data)
    }
}
