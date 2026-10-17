package com.flow.adhd

import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.SnapshotRead
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlowNoteReviewTest {
    @Test fun alarmIdentityIsUniquePerNote() {
        assertEquals("flow://note-review/41", FlowNoteReview.alarmDataUri(41L))
        assertNotEquals(FlowNoteReview.alarmDataUri(41L), FlowNoteReview.alarmDataUri(42L))
    }

    @Test fun deliveryRequiresTheNoteToStillExist() {
        val snapshot = FlowSnapshot.fromData(buildJsonObject {
            put("qnotes", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put("id", 41); put("txt", "Review") })
            })
        })
        assertTrue(FlowNoteReview.containsNote(SnapshotRead.Ready(snapshot), 41L))
        assertFalse(FlowNoteReview.containsNote(SnapshotRead.Ready(snapshot), 42L))
        assertFalse(FlowNoteReview.containsNote(SnapshotRead.Loading, 41L))
    }
}
