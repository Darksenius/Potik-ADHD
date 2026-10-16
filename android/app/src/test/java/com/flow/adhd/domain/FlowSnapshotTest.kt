package com.flow.adhd.domain

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowSnapshotTest {
    @Test fun preservesUnicodeAndUnknownNestedValuesOnRoundTrip() {
        val raw = """{"formatVersion":2,"tasks":[{"id":7,"title":"Привіт 🌻","type":"future-kind","done":false,"custom":{"array":[1,true,null,{"ключ":"значення"}]}}],"unknown":{"deep":[{"x":9}]}}"""
        val snapshot = FlowSnapshot.parse(raw)

        assertEquals("Привіт 🌻", snapshot.tasks.single().title)
        assertEquals("future-kind", snapshot.tasks.single().type)
        assertEquals(snapshot.data, FlowSnapshot.parse(snapshot.toJson()).data)
        assertTrue(snapshot.data.containsKey("unknown"))
        assertTrue((snapshot.tasks.single().raw["custom"] as JsonObject).containsKey("array"))
    }

    @Test fun exposesArraysAndSafeDefaultsWithoutAddingMissingFields() {
        val snapshot = FlowSnapshot.parse("""{"tasks":[{"id":1,"title":"Legacy"}],"qnotes":[{"id":2,"txt":"note"}],"zones":[{"id":3,"nm":"Home"}]}""")
        val task = snapshot.tasks.single()

        assertEquals("Legacy", task.title)
        assertEquals("simple", task.type)
        assertFalse(task.done)
        assertFalse(task.trashed)
        assertFalse(task.someday)
        assertNull(task.note)
        assertNull(task.zoneId)
        assertEquals(2, snapshot.qnotes.single()["id"]?.toString()?.toInt())
        assertEquals(3, snapshot.zones.single()["id"]?.toString()?.toInt())
        assertEquals(0.0, snapshot.xpTotal, 0.0)
        assertEquals(1L, snapshot.level)
        assertEquals("", snapshot.notepad)
        assertFalse(snapshot.data.containsKey("formatVersion"))
        assertFalse(snapshot.tasks.single().raw.containsKey("type"))
    }

    @Test fun missingLegacyCollectionsReadAsEmptyAndStayAbsent() {
        val snapshot = FlowSnapshot.parse("""{"unrelated":{"keep":true}}""")

        assertTrue(snapshot.tasks.isEmpty())
        assertTrue(snapshot.qnotes.isEmpty())
        assertTrue(snapshot.zones.isEmpty())
        assertEquals(snapshot.data, FlowSnapshot.parse(snapshot.toJson()).data)
        assertFalse(snapshot.data.containsKey("tasks"))
    }

    @Test fun rejectsUnsupportedOrMalformedFormatVersion() {
        assertSnapshotFailure("""{"formatVersion":3,"tasks":[]}""")
        assertSnapshotFailure("""{"formatVersion":"2","tasks":[]}""")
    }

    @Test fun rejectsMalformedArraysAndDoesNotDropBadTaskRecords() {
        assertSnapshotFailure("""{"tasks":{}}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":"ok"},null]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":"one"},{"id":1,"title":"duplicate"}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":4}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":"x","done":"no"}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":"x","done":"true"}]}""")
        assertSnapshotFailure("""{"tasks":[],"qnotes":[false]}""")
        assertSnapshotFailure("""{"tasks":[],"zones":[null]}""")
    }

    @Test fun rejectsPresentCoreFieldsWithWrongTypes() {
        assertSnapshotFailure("""{"tasks":[],"xpTotal":"12"}""")
        assertSnapshotFailure("""{"tasks":[],"xpTotal":null}""")
        assertSnapshotFailure("""{"tasks":[],"level":"2"}""")
        assertSnapshotFailure("""{"tasks":[],"level":1.5}""")
        assertSnapshotFailure("""{"tasks":[],"notepad":false}""")
    }

    @Test fun enforcesNonNegativeJavaScriptSafeTaskAndZoneIds() {
        val maxSafe = FlowSnapshot.parse("""{"tasks":[{"id":9007199254740991,"title":"max","zoneId":0}]}""")
        assertEquals(9_007_199_254_740_991L, maxSafe.tasks.single().id)
        assertEquals(0L, maxSafe.tasks.single().zoneId)

        assertSnapshotFailure("""{"tasks":[{"id":9007199254740992,"title":"too large"}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":-1,"title":"negative"}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1.5,"title":"fraction"}]}""")
        assertSnapshotFailure("""{"tasks":[{"id":1,"title":"bad zone","zoneId":9007199254740992}]}""")
    }

    @Test fun acceptsSafeJavaScriptLevelBeyondIntAndRejectsUnsafeLevel() {
        val snapshot = FlowSnapshot.parse("""{"tasks":[],"level":9007199254740991}""")
        assertEquals(9_007_199_254_740_991L, snapshot.level)
        assertSnapshotFailure("""{"tasks":[],"level":9007199254740992}""")
        assertSnapshotFailure("""{"tasks":[],"level":0}""")
    }

    private fun assertSnapshotFailure(raw: String) {
        try {
            FlowSnapshot.parse(raw)
            throw AssertionError("Expected SnapshotFormatException for: $raw")
        } catch (_: SnapshotFormatException) {
            // Expected.
        }
    }
}
