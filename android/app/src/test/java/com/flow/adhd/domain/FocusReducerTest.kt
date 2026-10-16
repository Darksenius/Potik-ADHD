package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Test

class FocusReducerTest {
    private val zone = ZoneId.of("UTC")
    private val at = Instant.parse("2026-10-16T10:00:00Z").toEpochMilli()
    private val initial = FlowSnapshot.parse("""{"tasks":[],"focusChips":["Робота"],"currentFocus":"Робота","focusLog":[{"val":"Робота","start":"2026-10-16T09:58:30.000Z","end":null,"custom":"keep"}],"xpTotal":10,"custom":{"keep":true}}""")

    @Test fun switchingClosesPreviousEntryWithJavascriptRoundingWithoutGivingExperience() {
        val result = FocusReducer.reduce(initial, FlowCommand.SetFocus("Відпочинок", "switch", at), zone) as FlowCommandResult.Applied
        val log = result.snapshot.data["focusLog"] as JsonArray
        assertEquals(2, log.size)
        assertEquals("2", (log.first() as JsonObject)["dur"].toString())
        assertEquals("\"keep\"", (log.first() as JsonObject)["custom"].toString())
        assertEquals("null", (log.last() as JsonObject)["end"].toString())
        assertEquals(initial.data["xpTotal"], result.snapshot.data["xpTotal"])
        assertEquals(initial.data["custom"], result.snapshot.data["custom"])
    }

    @Test fun removingActiveChipClosesLogAndClearIsIdempotent() {
        val result = FocusReducer.reduce(initial, FlowCommand.RemoveFocusChip("Робота", "remove", at), zone) as FlowCommandResult.Applied
        assertEquals("\"\"", result.snapshot.data["currentFocus"].toString())
        assertEquals("[]", result.snapshot.data["focusChips"].toString())
        assertEquals(1, (result.snapshot.data["focusLog"] as JsonArray).size)
        val again = FocusReducer.reduce(result.snapshot, FlowCommand.ClearFocus("clear", at), zone) as FlowCommandResult.Applied
        assertSame(result.snapshot, again.snapshot)
    }

    @Test fun addedChipIsUniqueAndStartsItsFocusWithoutChangingHistory() {
        val result = FocusReducer.reduce(initial, FlowCommand.AddFocusChip("Навчання", "add", at), zone) as FlowCommandResult.Applied
        assertEquals(2, (result.snapshot.data["focusChips"] as JsonArray).size)
        val again = FocusReducer.reduce(result.snapshot, FlowCommand.AddFocusChip("Навчання", "select", at + 60_000), zone) as FlowCommandResult.Applied
        assertEquals(2, (again.snapshot.data["focusChips"] as JsonArray).size)
        assertEquals(result.snapshot.data["focusLog"], again.snapshot.data["focusLog"])
        assertEquals(initial.data["xpTotal"], again.snapshot.data["xpTotal"])
    }

    @Test fun invalidStoredHistoryIsRejectedWithoutDroppingItsData() {
        val state = FlowSnapshot.parse("""{"tasks":[],"focusLog":["keep this invalid record"]}""")
        assertTrue(FocusReducer.reduce(state, FlowCommand.SetFocus("Робота", "start", at), zone) is FlowCommandResult.Rejected)
        assertEquals("[\"keep this invalid record\"]", state.data["focusLog"].toString())
    }
}
