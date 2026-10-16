package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotesReducerTest {
    private val zone = ZoneId.of("UTC")
    private val at = Instant.parse("2026-10-16T12:00:00Z").toEpochMilli()

    @Test
    fun missingFoldersUseExistingFallbackAndKeepUnknownRootData() {
        val input = FlowSnapshot.parse("""{"tasks":[],"unknown":{"keep":[1,true]}}""")
        val result = NotesReducer.reduce(
            input,
            FlowCommand.AddQuickNote(JsonObject(mapOf("txt" to j("capture"), "folder" to j("missing"))), "add", at),
            zone,
        ) as FlowCommandResult.Applied

        assertEquals("general", result.snapshot.qnotes.single()["folder"]?.jsonPrimitive?.content)
        assertEquals(input.data["unknown"], result.snapshot.data["unknown"])
        assertFalse(result.snapshot.data.containsKey("qnid"))

        val noFolders = FlowSnapshot.parse("""{"tasks":[],"folders":[],"unknown":{"keep":true}}""")
        val noFolderResult = NotesReducer.reduce(
            noFolders,
            FlowCommand.AddQuickNote(JsonObject(mapOf("txt" to j("loose"))), "loose", at),
            zone,
        ) as FlowCommandResult.Applied
        assertEquals("", noFolderResult.snapshot.qnotes.single()["folder"]?.jsonPrimitive?.content)
    }

    @Test
    fun saveNotepadCreatesNoteAndClearsNotebookInOneResult() {
        val input = FlowSnapshot.parse("""{"tasks":[],"notepad":"  Journal entry  ","notepadFolder":"gone","folders":[{"id":"keep","nm":"Keep","ico":"📁"}],"extra":9}""")
        val result = NotesReducer.reduce(input, FlowCommand.SaveNotepadAsNote(null, "save", at), zone)
            as FlowCommandResult.Applied

        val note = result.snapshot.qnotes.single()
        assertEquals("Journal entry", note["txt"]?.jsonPrimitive?.content)
        assertEquals("keep", note["folder"]?.jsonPrimitive?.content)
        assertEquals("", result.snapshot.notepad)
        assertEquals("keep", result.snapshot.data["notepadFolder"]?.jsonPrimitive?.content)
        assertEquals("9", result.snapshot.data["extra"]?.jsonPrimitive?.content)
    }

    @Test
    fun deletingFolderRebindsNotesClearsTaskBindingAndPreservesUnknownFields() {
        val input = FlowSnapshot.parse("""{"tasks":[{"id":1,"title":"task","folderId":"work","custom":{"keep":true}}],"folders":[{"id":"general","nm":"General","ico":"📝"},{"id":"work","nm":"Work","ico":"💼"}],"qnotes":[{"id":101,"txt":"note","folder":"work","custom":7}],"notepadFolder":"work","unknown":"keep"}""")
        val result = NotesReducer.reduce(input, FlowCommand.DeleteNoteFolder("work", "delete", at), zone)
            as FlowCommandResult.Applied

        val task = result.snapshot.data["tasks"]!!.jsonArray.single().jsonObject
        val note = result.snapshot.qnotes.single()
        assertEquals("null", task["folderId"].toString())
        assertNotNull(task["custom"])
        assertEquals("general", note["folder"]?.jsonPrimitive?.content)
        assertEquals("7", note["custom"]?.jsonPrimitive?.content)
        assertEquals("general", result.snapshot.data["notepadFolder"]?.jsonPrimitive?.content)
        assertEquals("keep", result.snapshot.data["unknown"]?.jsonPrimitive?.content)
        assertFalse(result.snapshot.data.containsKey("noteFolder"))
        assertFalse(result.snapshot.data.containsKey("taskFilter"))
    }

    @Test
    fun updatingFolderChangesNameAndIconWithoutChangingIdOrLinkedRecords() {
        val input = FlowSnapshot.parse("""{"tasks":[{"id":1,"title":"Task","folderId":"work"}],"folders":[{"id":"work","nm":"Old","ico":"💼","custom":true}],"qnotes":[{"id":100,"txt":"note","folder":"work"}],"extra":9}""")
        val result = NotesReducer.reduce(
            input,
            FlowCommand.UpdateNoteFolder("work", JsonObject(mapOf("nm" to j("  New name  "), "ico" to j(" 🗂 "))), "update-folder", at),
            zone,
        ) as FlowCommandResult.Applied

        val folder = result.snapshot.data["folders"]!!.jsonArray.single().jsonObject
        assertEquals("work", folder["id"]?.jsonPrimitive?.content)
        assertEquals("New name", folder["nm"]?.jsonPrimitive?.content)
        assertEquals("🗂", folder["ico"]?.jsonPrimitive?.content)
        assertEquals("true", folder["custom"]?.jsonPrimitive?.content)
        assertEquals(input.qnotes, result.snapshot.qnotes)
        assertEquals(input.data["tasks"], result.snapshot.data["tasks"])
        assertEquals("9", result.snapshot.data["extra"]?.jsonPrimitive?.content)
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun updatingFolderRejectsMissingFolderOrBlankName() {
        val input = FlowSnapshot.parse("""{"tasks":[],"folders":[{"id":"work","nm":"Work","ico":"💼"}]}""")
        val missing = NotesReducer.reduce(input, FlowCommand.UpdateNoteFolder("gone", JsonObject(mapOf("nm" to j("Name"))), "missing", at), zone)
        assertTrue(missing is FlowCommandResult.Rejected)
        val blank = NotesReducer.reduce(input, FlowCommand.UpdateNoteFolder("work", JsonObject(mapOf("nm" to j("  "))), "blank", at), zone)
        assertTrue(blank is FlowCommandResult.Rejected)
    }

    @Test
    fun sameDayNoteActivitiesClaimAtMostOneInsight() {
        val start = FlowSnapshot.parse("""{"tasks":[],"folders":[{"id":"impulse","nm":"Impulse","ico":"⚡"}],"progressionVersion":2,"progressDays":[]}""")
        val first = NotesReducer.reduce(
            start,
            FlowCommand.AddQuickNote(JsonObject(mapOf("txt" to j("one"))), "one", at),
            zone,
        ) as FlowCommandResult.Applied
        val second = NotesReducer.reduce(
            first.snapshot,
            FlowCommand.AddQuickNote(JsonObject(mapOf("txt" to j("two"))), "two", at + 60_000),
            zone,
            now = at + 60_000,
        ) as FlowCommandResult.Applied

        val claims = second.snapshot.data["dailyInsightClaims"]!!.jsonArray
        val claimEvents = first.events.count { it.type == "daily_insight_claimed" } +
            second.events.count { it.type == "daily_insight_claimed" }
        assertEquals(1, claims.size)
        assertEquals(1, claimEvents)
        assertTrue(second.snapshot.qnotes.size == 2)
    }

    private fun j(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
}
