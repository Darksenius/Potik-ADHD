package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMergeTest {
    private val zone = ZoneId.of("UTC")
    private val now = 1_792_000_000_000L
    private fun json(raw: String) = Json.parseToJsonElement(raw) as JsonObject
    private fun merge(current: String, imported: String) = SnapshotMerge.merge(FlowSnapshot.parse(current), json(imported), zone, now, "import-command")

    @Test fun remapsZonesAndTaskIdsWhilePreservingImportedDoneAndTrash() {
        val result = merge(
            """{"nid":2,"zones":[{"id":1,"nm":"Work"}],"tasks":[{"id":1,"title":"Existing","created":"2024"}],"progressionVersion":2}""",
            """{"zones":[{"id":7,"nm":"Work"},{"id":8,"nm":"Home","future":true}],"tasks":[{"id":7,"title":"Imported","created":"2025","zoneId":7,"done":true,"trashed":true,"futureTask":{"kept":true}}],"taskHistory":[],"priorities":[],"qnotes":[],"dayTemplates":[],"weekTemplates":[],"planRules":[],"planItems":{},"planDayZones":{},"planDayOff":{}}""",
        ) as FlowCommandResult.Applied
        assertEquals(1L, (result.snapshot.data["zones"] as JsonArray).let { (it[0] as JsonObject)["id"].toString().toLong() })
        val imported = result.snapshot.tasks.first { it.title == "Imported" }
        assertEquals(2L, imported.id)
        assertTrue(imported.done)
        assertTrue(imported.trashed)
        assertEquals(1L, imported.zoneId)
        assertTrue(imported.raw.containsKey("futureTask"))
        assertEquals("true", ((result.snapshot.data["zones"] as JsonArray)[1] as JsonObject)["future"].toString())
    }

    @Test fun deterministicTemplateIdsAndDuplicateMergeAreIdempotent() {
        val current = """{"nid":1,"zones":[],"tasks":[],"dayTemplates":[],"weekTemplates":[],"planRules":[],"planItems":{},"planDayZones":{},"planDayOff":{},"progressionVersion":2}"""
        val imported = json("""{"zones":[],"tasks":[],"taskHistory":[],"dayTemplates":[{"id":"src","name":"Morning","blocks":[],"tasks":[]}],"weekTemplates":[{"id":"w","name":"Week","days":[{"tplId":"src"}]}],"planRules":[],"planItems":{},"planDayZones":{},"planDayOff":{}}""")
        val once = SnapshotMerge.merge(FlowSnapshot.parse(current), imported, zone, now, "same-command") as FlowCommandResult.Applied
        val twice = SnapshotMerge.merge(once.snapshot, imported, zone, now, "same-command") as FlowCommandResult.Applied
        assertEquals(once.snapshot.data["dayTemplates"], twice.snapshot.data["dayTemplates"])
        assertEquals(once.snapshot.data["weekTemplates"], twice.snapshot.data["weekTemplates"])
    }

    @Test fun historyIdentityConflictRejectsWithoutChangingCurrentSnapshot() {
        val current = """{"nid":2,"zones":[],"tasks":[],"taskHistory":[{"id":"1@x|once#1:10:completed","occurrenceId":"1@x|once","taskId":1,"taskCreated":"x","kind":"completed","sequence":1,"at":10,"date":"1970-01-01","time":"00:00","title":"old"}],"progressionVersion":2}"""
        val imported = """{"zones":[],"tasks":[],"taskHistory":[{"id":"1@x|once#1:10:completed","occurrenceId":"1@x|once","taskId":1,"taskCreated":"x","kind":"completed","sequence":1,"at":10,"date":"1970-01-01","time":"00:00","title":"different"}]}"""
        val before = FlowSnapshot.parse(current)
        val result = SnapshotMerge.merge(before, json(imported), zone, now, "conflict")
        assertTrue(result is FlowCommandResult.Rejected)
        assertEquals(current, before.toJson())
    }

    @Test fun malformedListsRejectInsteadOfDroppingImportedData() {
        val result = merge("{}", """{"tasks":{}}""")
        assertTrue(result is FlowCommandResult.Rejected)
    }

    @Test fun repeatedImportRemapsOnlyIncomingPresetKeysAndPreservesCurrentTasks() {
        val current = """{"nid":3,"zones":[],"tasks":[{"id":1,"title":"Current","created":"c","dayPresetKey":"shared:2026-10-16"}],"dayTemplates":[{"id":"shared","name":"Current template","blocks":[],"tasks":[]}],"weekTemplates":[],"progressionVersion":2}"""
        val imported = json("""{"nid":2,"zones":[],"tasks":[{"id":1,"title":"Imported","created":"i","dayPresetKey":"shared:2026-10-17"}],"taskHistory":[],"dayTemplates":[{"id":"shared","name":"Imported template","blocks":[],"tasks":[]}],"weekTemplates":[],"planRules":[],"planItems":{},"planDayZones":{},"planDayOff":{}}""")

        val once = SnapshotMerge.merge(FlowSnapshot.parse(current), imported, zone, now, "template-collision") as FlowCommandResult.Applied
        val currentTask = once.snapshot.tasks.single { it.title == "Current" }
        val importedTask = once.snapshot.tasks.single { it.title == "Imported" }
        val targetId = (once.snapshot.data["dayTemplates"] as JsonArray).map { (it as JsonObject)["id"].toString().trim('"') }
            .single { it != "shared" }
        assertEquals("shared:2026-10-16", (currentTask.raw["dayPresetKey"] as JsonPrimitive).content)
        assertEquals("$targetId:2026-10-17", (importedTask.raw["dayPresetKey"] as JsonPrimitive).content)

        val twice = SnapshotMerge.merge(once.snapshot, imported, zone, now, "template-collision") as FlowCommandResult.Applied
        assertEquals(2, twice.snapshot.tasks.size)
        assertEquals("shared:2026-10-16", (twice.snapshot.tasks.single { it.title == "Current" }.raw["dayPresetKey"] as JsonPrimitive).content)
        assertEquals("$targetId:2026-10-17", (twice.snapshot.tasks.single { it.title == "Imported" }.raw["dayPresetKey"] as JsonPrimitive).content)
    }

    @Test fun zoneAllocationIgnoresTaskIdsAndRemapsHistoryZoneOccurrenceAfterTaskIdentity() {
        val current = """{"nid":2,"zones":[],"tasks":[{"id":1,"title":"Current","created":"c"}],"taskHistory":[],"progressionVersion":2}"""
        val imported = json("""{
          "zones":[{"id":8,"nm":"Home"}],
          "tasks":[{"id":8,"title":"Imported","created":"2025","zoneId":8}],
          "taskHistory":[{"id":"8@2025|zone:2025-01-01:8:occ#1:10:completed","occurrenceId":"8@2025|zone:2025-01-01:8:occ","taskId":8,"taskCreated":"2025","title":"Imported","type":"simple","kind":"completed","at":10,"date":"2025-01-01","time":"00:00","sequence":1,"zoneId":8}],
          "planDayOff":{"2025-01-01":[9]},"dayTemplates":[],"weekTemplates":[],"planRules":[],"planItems":{},"planDayZones":{}
        }""")

        val result = SnapshotMerge.merge(FlowSnapshot.parse(current), imported, zone, now, "zone-history") as FlowCommandResult.Applied
        val importedZone = (result.snapshot.data["zones"] as JsonArray).single() as JsonObject
        assertEquals(1, importedZone["id"].toString().toInt())
        val event = (result.snapshot.data["taskHistory"] as JsonArray).single() as JsonObject
        assertEquals(1, event["zoneId"].toString().toInt())
        assertTrue((event["id"] as JsonPrimitive).content.startsWith("2@2025|zone:2025-01-01:1:"))
        assertTrue((event["occurrenceId"] as JsonPrimitive).content.startsWith("2@2025|zone:2025-01-01:1:"))
    }

    @Test fun noteIdsFollowCurrentMaximumAndDerivedQnidIsNotPersisted() {
        val current = """{"qnotes":[{"id":222,"txt":"old","folder":"general"}],"qnid":1,"progressionVersion":2}"""
        val imported = json("""{"qnotes":[{"id":1,"txt":"new","folder":"general"}]}""")
        val result = SnapshotMerge.merge(FlowSnapshot.parse(current), imported, zone, now, "notes") as FlowCommandResult.Applied
        val notes = result.snapshot.data["qnotes"] as JsonArray
        assertEquals(223, ((notes.last() as JsonObject)["id"] as JsonPrimitive).content.toInt())
        assertTrue("qnid" !in result.snapshot.data)
    }

    @Test fun dayOffZoneIdsAreReservedWhenAllocatingImportedZones() {
        val current = FlowSnapshot.parse("""{"zones":[],"tasks":[],"progressionVersion":2}""")
        val imported = json("""{"zones":[{"id":7,"nm":"Home"}],"planDayOff":{"2026-10-16":[1]}}""")
        val result = SnapshotMerge.merge(current, imported, zone, now, "day-off-zone") as FlowCommandResult.Applied
        val createdZone = (result.snapshot.data["zones"] as JsonArray).single() as JsonObject
        assertEquals(2, createdZone["id"].toString().toInt())
        assertEquals("[1]", (result.snapshot.data["planDayOff"] as JsonObject).getValue("2026-10-16").toString())
    }

    @Test fun mergingSameNameTemplateRemapsOnlyImportedEntries() {
        val current = FlowSnapshot.parse("""{
          "zones":[{"id":1,"nm":"Current zone"}],"tasks":[],
          "dayTemplates":[{"id":"current-template","name":"Morning","blocks":[],"tasks":[{"id":"current-entry","task":{"title":"Current entry","zoneId":1}}]}],
          "progressionVersion":2
        }""")
        val imported = json("""{
          "zones":[{"id":1,"nm":"Imported zone"}],
          "dayTemplates":[{"id":"source-template","name":"Morning","blocks":[],"tasks":[{"id":"imported-entry","task":{"title":"Imported entry","zoneId":1}}]}]
        }""")
        val result = SnapshotMerge.merge(current, imported, zone, now, "same-template") as FlowCommandResult.Applied
        val entries = (result.snapshot.data["dayTemplates"] as JsonArray).single().jsonObject.getValue("tasks").jsonArray
        val currentTask = entries.single { ((it as JsonObject)["id"] as JsonPrimitive).content == "current-entry" }.jsonObject.getValue("task").jsonObject
        val importedTask = entries.single { ((it as JsonObject)["id"] as JsonPrimitive).content == "imported-entry" }.jsonObject.getValue("task").jsonObject
        assertEquals(1, currentTask.getValue("zoneId").toString().toInt())
        assertEquals(2, importedTask.getValue("zoneId").toString().toInt())
    }

    @Test fun malformedCurrentCollectionsRejectInsteadOfBecomingEmpty() {
        val current = FlowSnapshot.parse("""{"folders":{},"progressionVersion":2}""")
        val result = SnapshotMerge.merge(current, json("""{"folders":[]}"""), zone, now, "bad-current")
        assertTrue(result is FlowCommandResult.Rejected)
    }
}
