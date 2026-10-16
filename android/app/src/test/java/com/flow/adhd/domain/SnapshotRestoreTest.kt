package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotRestoreTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val now = 1_792_128_000_000L

    @Test fun defaultsMatchPersistedInitialSlicesAndExcludeWriteMetadata() {
        val defaults = FlowDefaults.create()
        assertEquals(2, defaults.getValue("formatVersion").toString().toInt())
        assertEquals(5, defaults.getValue("zones").jsonArray.size)
        assertEquals(8, defaults.getValue("folders").jsonArray.size)
        assertEquals(7, defaults.getValue("focusChips").jsonArray.size)
        assertEquals(3, defaults.getValue("priorities").jsonArray.size)
        assertEquals("thought", defaults.getValue("notepadFolder").toString().trim('"'))
        assertEquals("system", defaults.getValue("preferences").jsonObject.getValue("themeMode").toString().trim('"'))
        assertFalse(defaults.containsKey("saveDate"))
        assertFalse(defaults.containsKey("qnid"))
        assertFalse(defaults.containsKey("dailyInsightNotice"))
    }

    @Test fun restoreResetsPersistedValuesPreservesUnknownCurrentFieldsAndOverlaysBackup() {
        val current = snapshot("""{
            "tasks":[{"id":90,"title":"Current"}],"theme":"light","customRoot":{"keep":true},"qnid":888
        }""")
        val imported = json("""{
            "tasks":[{"id":3,"title":"Imported"}],"focusChips":["Backup"],"saveDate":"Thu Oct 15 2026","unknownImported":true
        }""")

        val restored = applied(SnapshotRestore.restore(current, imported, zone, now)).snapshot.data
        assertEquals("Imported", restored.getValue("tasks").jsonArray.single().jsonObject.getValue("title").toString().trim('"'))
        assertEquals("dark", restored.getValue("theme").toString().trim('"'))
        assertEquals("[\"Backup\"]", restored.getValue("focusChips").toString())
        assertTrue(restored.getValue("customRoot").jsonObject.getValue("keep").toString() == "true")
        assertFalse(restored.containsKey("saveDate"))
        assertFalse(restored.containsKey("qnid"))
        assertFalse(restored.containsKey("unknownImported"))
    }

    @Test fun legacyProgressionUsesVersionZeroAndRestoreDoesNotEmitActivityRewards() {
        val current = snapshot("""{"custom":"preserve"}""")
        val imported = json("""{"xp":25,"xpTotal":125,"level":2,"tasks":[]}""")

        val result = SnapshotRestore.restore(current, imported, zone, now)
        val restored = applied(result).snapshot.data
        assertEquals(2, restored.getValue("progressionVersion").toString().toInt())
        assertTrue(restored.getValue("xpTotal").toString().toDouble() >= 125.0)
        assertTrue((result as FlowCommandResult.Applied).events.isEmpty())
        assertEquals("preserve", restored.getValue("custom").toString().trim('"'))
    }

    @Test fun legacyRoutineConversionUsesBackupDateAndFailureReturnsRejected() {
        val current = snapshot("{}")
        val imported = json("""{
            "recur":[{"id":"r-old","nm":"Read","unit":"check","val":0,"done":true}],
            "saveDate":"Thu Oct 15 2026"
        }""")
        val restored = applied(SnapshotRestore.restore(current, imported, zone, now)).snapshot.data
        val migrated = restored.getValue("tasks").jsonArray.single().jsonObject
        assertEquals("2026-10-15", migrated.getValue("doneDate").toString().trim('"'))
        assertEquals("[]", restored.getValue("recur").toString())

        val malformedCurrent = snapshot("{}")
        val invalid = json("""{"planItems":{"invalid-date":[{"title":"Legacy task"}]}}""")
        val rejected = SnapshotRestore.restore(malformedCurrent, invalid, zone, now)
        assertTrue(rejected is FlowCommandResult.Rejected)
        assertEquals("{}", malformedCurrent.data.toString())
    }

    private fun snapshot(raw: String) = FlowSnapshot.parse(raw)
    private fun json(raw: String) = Json.parseToJsonElement(raw).jsonObject

    private fun applied(result: FlowCommandResult): FlowCommandResult.Applied = when (result) {
        is FlowCommandResult.Applied -> result
        is FlowCommandResult.Rejected -> throw AssertionError("Restore rejected: ${result.code}: ${result.message}")
    }
}
