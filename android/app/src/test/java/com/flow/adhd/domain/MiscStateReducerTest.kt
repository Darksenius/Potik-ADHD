package com.flow.adhd.domain

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscStateReducerTest {
    @Test
    fun draftCanBeClearedAndPreservesOtherDataWithoutActivity() {
        val input = FlowSnapshot.parse("""{"tasks":[],"taskEditorDraft":"unfinished","rareEvents":[],"xp":14,"progressDays":["2026-10-16"],"extra":{"keep":true}}""")
        val result = MiscStateReducer.reduce(input, FlowCommand.SetTaskEditorDraft("", "clear-draft", 123)) as FlowCommandResult.Applied

        assertEquals("", result.snapshot.data["taskEditorDraft"]?.jsonPrimitive?.content)
        assertEquals(input.data["rareEvents"], result.snapshot.data["rareEvents"])
        assertEquals(input.data["xp"], result.snapshot.data["xp"])
        assertEquals(input.data["progressDays"], result.snapshot.data["progressDays"])
        assertEquals(input.data["extra"], result.snapshot.data["extra"])
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun rareEventCreationTrimsNameAndAllocatesCollisionFreeTimestampId() {
        val input = FlowSnapshot.parse("""{"tasks":[],"rareEvents":[{"id":123,"nm":"first","val":2,"dir":"up","unit":"","custom":{"keep":true}},{"id":124,"nm":"second","val":1,"dir":"down","unit":"cups"}],"xp":8}""")
        val result = MiscStateReducer.reduce(input, FlowCommand.CreateRareEvent("  Water  ", "up", "add-event", 123)) as FlowCommandResult.Applied
        val events = result.snapshot.data["rareEvents"]!!.jsonArray
        val added = events.last().jsonObject

        assertEquals("125", added["id"]?.jsonPrimitive?.content)
        assertEquals("Water", added["nm"]?.jsonPrimitive?.content)
        assertEquals("0", added["val"]?.jsonPrimitive?.content)
        assertEquals("up", added["dir"]?.jsonPrimitive?.content)
        assertEquals("", added["unit"]?.jsonPrimitive?.content)
        assertEquals(input.data["rareEvents"]!!.jsonArray.first(), events.first())
        assertEquals(input.data["xp"], result.snapshot.data["xp"])
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun rareEventDeltaClampsAtZeroAndKeepsUnknownFieldsWithoutActivity() {
        val input = FlowSnapshot.parse("""{"tasks":[],"rareEvents":[{"id":20,"nm":"Water","val":2,"dir":"up","unit":"ml","custom":"stay"}],"xp":9,"done":4,"progressDays":["2026-10-16"]}""")
        val result = MiscStateReducer.reduce(input, FlowCommand.ChangeRareEvent(20, -5.0, "change-event", 124)) as FlowCommandResult.Applied
        val changed = result.snapshot.data["rareEvents"]!!.jsonArray.single().jsonObject

        assertEquals("0.0", changed["val"]?.jsonPrimitive?.content)
        assertEquals("stay", changed["custom"]?.jsonPrimitive?.content)
        assertEquals(input.data["xp"], result.snapshot.data["xp"])
        assertEquals(input.data["done"], result.snapshot.data["done"])
        assertEquals(input.data["progressDays"], result.snapshot.data["progressDays"])
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun deletionKeepsUnmatchedEventsAndMalformedStoredListsAreRejected() {
        val input = FlowSnapshot.parse("""{"tasks":[],"rareEvents":[{"id":1,"nm":"remove","val":0,"dir":"up","unit":""},{"id":2,"nm":"keep","val":4,"dir":"down","unit":""}],"extra":true}""")
        val deleted = MiscStateReducer.reduce(input, FlowCommand.DeleteRareEvent(1, "delete-event", 125)) as FlowCommandResult.Applied
        assertEquals("keep", deleted.snapshot.data["rareEvents"]!!.jsonArray.single().jsonObject["nm"]?.jsonPrimitive?.content)
        assertEquals(input.data["extra"], deleted.snapshot.data["extra"])

        val malformed = FlowSnapshot.parse("""{"tasks":[],"rareEvents":[null,{"id":2,"nm":"valid","val":0,"dir":"up","unit":""}]}""")
        val rejected = MiscStateReducer.reduce(malformed, FlowCommand.DeleteRareEvent(2, "delete-malformed", 126))
        assertTrue(rejected is FlowCommandResult.Rejected)
    }

    @Test
    fun blankNameAndUnknownDirectionAreRejected() {
        val snapshot = FlowSnapshot.parse("""{"tasks":[],"rareEvents":[]}""")
        assertTrue(MiscStateReducer.reduce(snapshot, FlowCommand.CreateRareEvent("  ", "up", "blank", 1)) is FlowCommandResult.Rejected)
        assertTrue(MiscStateReducer.reduce(snapshot, FlowCommand.CreateRareEvent("Water", "sideways", "direction", 1)) is FlowCommandResult.Rejected)
    }
}
