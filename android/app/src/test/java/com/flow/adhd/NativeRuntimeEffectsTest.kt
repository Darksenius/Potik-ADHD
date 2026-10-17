package com.flow.adhd

import com.flow.adhd.data.CommandCommit
import com.flow.adhd.domain.FlowEvent
import com.flow.adhd.domain.FlowSnapshot
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class NativeRuntimeEffectsTest {
    @Test fun exposesOnlyForegroundNavigationAndNoteReviewEffectsAfterCommit() {
        val events = listOf(
            FlowEvent("navigate_note", 1, buildJsonObject { put("noteId", 7) }),
            FlowEvent("navigate_task", 2),
            FlowEvent("navigate_insight", 3),
            FlowEvent("note_review_requested", 4, buildJsonObject { put("noteId", 7); put("dueAt", 5) }),
            FlowEvent("xp_awarded", 5),
        )
        val committed = CommandCommit.Committed(snapshot = FlowSnapshot.parse("{}"), commandId = "batch", replayed = false, events = events)

        assertEquals(events.take(4).map(::NativeRuntimeEffect), nativeRuntimeEffects(committed))
        assertEquals(emptyList<NativeRuntimeEffect>(), nativeRuntimeEffects(CommandCommit.Rejected("invalid", "rejected")))
    }
}
