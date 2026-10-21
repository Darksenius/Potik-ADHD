package com.flow.adhd

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Arrays
import java.util.Collections
import java.util.TimeZone

/** JSON persistence and undo coverage for scheduled deferrals, using Android's real org.json. */
@RunWith(AndroidJUnit4::class)
class ScheduledDeferralTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun scheduledTimeDeferralJsonRoundTripsAndRejectsMalformedValues() {
        val request = QuestPolicy.localDateTime("2026-10-14", "23:30", utc)
        val deadline = request + 60L * 60L * 1000L
        val deferral = QuestPolicy.Deferral(QuestPolicy.DEFERRAL_TIME, request, 2, 1, deadline, null)

        val restored = QuestPolicy.Task.fromJson(JSONObject(
            """{"id":"restored","type":"sched","questDeferral":${deferral.toJson()}}""",
        ))
        assertNotNull(restored)
        assertNotNull(restored.deferral)
        assertEquals(QuestPolicy.DEFERRAL_TIME, restored.deferral.kind)
        assertEquals(request, restored.deferral.requestedAt)
        assertEquals(2, restored.deferral.sourceZoneId)
        assertEquals(1, restored.deferral.targetZoneId)
        assertEquals(deadline, restored.deferral.startAt)
        assertNull(restored.deferral.endAt)

        val malformed = QuestPolicy.Task.fromJson(JSONObject(
            """{"id":"bad","type":"sched","questDeferral":{"kind":"time","requestedAt":10,"sourceZoneId":0,"targetZoneId":0,"startAt":10}}""",
        ))
        assertNotNull(malformed)
        assertNull(malformed.deferral)
    }

    @Test fun undoRestoresThePreviouslyUnsetScheduledDeferral() {
        val request = QuestPolicy.localDateTime("2026-10-14", "23:30", utc)
        val deadline = request + 60L * 60L * 1000L
        val midnight = QuestPolicy.nextLocalMidnight(request, utc)
        val timeline = Arrays.asList(
            QuestPolicy.Segment(1, request - 60L * 60L * 1000L, request - 30L * 60L * 1000L),
            QuestPolicy.Segment(2, request - 30L * 60L * 1000L, midnight),
            QuestPolicy.Segment(3, midnight, deadline + 60L * 60L * 1000L),
        )
        val task = QuestPolicy.Task.builder("scheduled")
            .created("2026-10-14T08:00:00.000Z").zone(1)
            .sched("2026-10-14", "08:00").build()
        val skipped = QuestPolicy.projectPendingEvents(
            Collections.singletonList(task),
            Collections.singletonList(QuestPolicy.Event.skip(task.id, request)),
            deadline, timeline, utc,
        ).single()
        assertNotNull(skipped.deferral)
        assertEquals(QuestPolicy.DEFERRAL_TIME, skipped.deferral.kind)
        assertEquals(deadline, skipped.deferral.startAt)

        val undo = QuestPolicy.Undo(task.id, task.created, "defer", request, deadline, JSONObject())
        val undone = QuestPolicy.projectPendingEvents(
            Collections.singletonList(task),
            Arrays.asList(QuestPolicy.Event.skip(task.id, request), QuestPolicy.Event.undo(undo)),
            deadline, timeline, utc,
        ).single()
        assertNull(undone.deferral)
    }
}
