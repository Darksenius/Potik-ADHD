package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskCompletionReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")

    @Test fun simpleDoneReopenDoneAwardsOnlyOnce() {
        val initial = snapshot(task("""{"id":1,"created":"c","title":"Simple","type":"simple","done":false,"repeat":"none"}"""))
        val first = reduce(initial, 1, true, 1_700_000_000_000L)
        val completed = applied(first)
        assertEquals(1, completed.snapshot.data["done"]?.toString()?.toInt())
        assertEquals(10.0, completed.snapshot.xpTotal, 0.0)
        assertTrue(completed.snapshot.tasks.single().done)
        assertEquals(listOf("completed"), eventKinds(completed.snapshot))

        val reopened = applied(reduce(completed.snapshot, 1, false, 1_700_000_001_000L))
        assertFalse(reopened.snapshot.tasks.single().done)
        assertEquals(1, reopened.snapshot.data["done"]?.toString()?.toInt())
        assertEquals(10.0, reopened.snapshot.xpTotal, 0.0)
        assertEquals(listOf("completed", "reopened"), eventKinds(reopened.snapshot))

        val again = applied(reduce(reopened.snapshot, 1, true, 1_700_000_002_000L))
        assertEquals(1, again.snapshot.data["done"]?.toString()?.toInt())
        assertEquals(10.0, again.snapshot.xpTotal, 0.0)
        assertEquals(listOf("completed", "reopened", "completed"), eventKinds(again.snapshot))
        assertEquals("2023-11-15", again.snapshot.tasks.single().raw["completionCreditDate"]?.toString()?.trim('"'))
    }

    @Test fun intervalSetsNextDueAndReopenClearsIt() {
        val initial = snapshot(task("""{"id":2,"created":"c","title":"Interval","type":"simple","done":false,"repeat":"interval","repeatMs":60000,"nextRepeatAt":0}"""))
        val doneAt = 1_700_000_000_000L
        val completed = applied(reduce(initial, 2, true, doneAt)).snapshot.tasks.single().raw
        assertEquals(doneAt + 60_000L, completed["nextRepeatAt"]?.toString()?.toLong())
        val reopened = applied(reduce(applied(reduce(initial, 2, true, doneAt)).snapshot, 2, false, doneAt + 1)).snapshot.tasks.single().raw
        assertEquals(0, reopened["nextRepeatAt"]?.toString()?.toInt())
    }

    @Test fun everyZoneResolverUsesDeferralOriginAndDeferralIsRestoredOnReopen() {
        val origin = 1_704_067_200_000L
        val deferral = """{"kind":"next-zone","requestedAt":$origin,"sourceZoneId":3,"targetZoneId":4,"startAt":$origin}"""
        val initial = snapshot(task("""{"id":4,"created":"c","title":"Zone task","type":"zonelinked","done":false,"repeat":"everyzone","zoneId":4,"questDeferral":$deferral,"customTask":{"x":1}}"""))
        var resolved: Pair<Long, Long>? = null
        val completed = applied(TaskCompletionReducer.reduce(initial, command(4, true, 1_704_412_800_000L), zone, mapOf("zonelinked" to 8.0), zoneOccurrenceKey = { zoneId, at -> resolved = zoneId to at; "entered-zone" }))
        val taskAfterDone = completed.snapshot.tasks.single().raw
        assertEquals(4L to origin, resolved)
        assertEquals("4@c|zone:entered-zone", taskAfterDone["occurrenceId"]?.toString()?.trim('"'))
        assertEquals(Json.parseToJsonElement(deferral), taskAfterDone["completionDeferral"])
        assertFalse("questDeferral" in taskAfterDone)
        assertEquals("1", taskAfterDone["customTask"]?.jsonObject?.get("x")?.toString())

        val taskAfterReopen = applied(reduce(completed.snapshot, 4, false, 1_704_412_801_000L)).snapshot.tasks.single().raw
        assertEquals(Json.parseToJsonElement(deferral), taskAfterReopen["questDeferral"])
        assertFalse("completionDeferral" in taskAfterReopen)
    }

    @Test fun preservesUnknownSnapshotAndHistoryFieldsAndDoesNotPersistTransientAlarmIds() {
        val priorHistory = """{"id":"old","occurrenceId":"legacy","taskId":9,"taskCreated":"old","title":"Old","type":"simple","kind":"completed","sequence":0,"at":1,"date":"2020-01-01","time":"00:00","futureEvent":{"kept":true}}"""
        val rawTask = """{"id":9,"created":"c","title":"Unknowns","type":"timewin","done":false,"repeat":"none","customTask":{"kept":true},"completedToday":false}"""
        val initial = snapshot(task(rawTask), extra = """, "customState":{"kept":true}, "taskHistory":[$priorHistory], "priorities":[9,2,null]""")
        val result = applied(reduce(initial, 9, true, 1_700_000_000_000L)).snapshot

        assertEquals(initial.data["customState"], result.data["customState"])
        assertEquals(Json.parseToJsonElement("""{"kept":true}"""), result.tasks.single().raw["customTask"])
        assertEquals(Json.parseToJsonElement("""{"kept":true}"""), (result.data["taskHistory"] as JsonArray)[0].jsonObject["futureEvent"])
        assertEquals(JsonPrimitive(true), result.tasks.single().raw["completedToday"])
        assertTrue("alarmTaskIds" !in result.data)
        val priorities = result.data["priorities"] as JsonArray
        assertEquals(Json.parseToJsonElement("[null,2,null]"), priorities)
    }

    @Test fun lateCompletionRecomputesDayCountFromHistoryAndPreservesDayLog() {
        val initial = snapshot(
            task("""{"id":3,"created":"c","title":"Yesterday","type":"simple","done":false,"repeat":"none"}"""),
            extra = """, "planDayLog":{"2024-03-01":{"focus":["work"],"energy":4,"routineDone":{"walk":true},"priorities":[3],"tasksDone":9,"note":"kept","future":"also kept"}}""",
        )
        val completedAt = Instant.parse("2024-03-01T10:00:00Z").toEpochMilli()
        val result = applied(TaskCompletionReducer.reduce(
            initial,
            FlowCommand.SetTaskDone(3, true, null, "late-completion", completedAt),
            zone,
            mapOf("simple" to 10.0),
            now = Instant.parse("2024-03-02T12:00:00Z").toEpochMilli(),
        )).snapshot
        val log = (result.data["planDayLog"] as JsonObject)["2024-03-01"] as JsonObject

        assertEquals(1, log["tasksDone"]?.toString()?.toInt())
        assertEquals("kept", log["note"]?.toString()?.trim('"'))
        assertEquals("also kept", log["future"]?.toString()?.trim('"'))
        assertEquals(Json.parseToJsonElement("""["work"]"""), log["focus"])
    }

    @Test fun ignoredCompletionReturnsTheOriginalSnapshot() {
        val negative = snapshot(task("""{"id":5,"created":"c","title":"Journal","type":"negative","done":false,"repeat":"none"}"""))
        val trashed = snapshot(task("""{"id":6,"created":"c","title":"Deleted","type":"simple","done":false,"trashed":true,"repeat":"none"}"""))
        val alreadyDone = snapshot(task("""{"id":7,"created":"c","title":"Done","type":"simple","done":true,"repeat":"none"}"""))
        assertSame(negative, applied(reduce(negative, 5, true, 1_700_000_000_000L)).snapshot)
        assertSame(trashed, applied(reduce(trashed, 6, true, 1_700_000_000_000L)).snapshot)
        assertSame(alreadyDone, applied(reduce(alreadyDone, 7, true, 1_700_000_000_000L)).snapshot)
    }

    private fun reduce(snapshot: FlowSnapshot, id: Long, done: Boolean, at: Long) =
        TaskCompletionReducer.reduce(snapshot, command(id, done, at), zone, mapOf("simple" to 10.0, "zonelinked" to 8.0, "timewin" to 5.0))

    private fun command(id: Long, done: Boolean, at: Long) = FlowCommand.SetTaskDone(id, done, at, "completion-$id-$done-$at", at)

    private fun applied(result: FlowCommandResult): FlowCommandResult.Applied = result as FlowCommandResult.Applied
    private fun eventKinds(snapshot: FlowSnapshot) = (snapshot.data["taskHistory"] as JsonArray).map { it.jsonObject["kind"]?.toString()?.trim('"') }

    private fun snapshot(task: JsonObject, extra: String = ""): FlowSnapshot = FlowSnapshot.parse(
        """{"formatVersion":2,"progressionVersion":2,"progressDays":[],"dailyInsightClaims":[],"xp":0,"xpTotal":0,"level":1,"done":0,"unlocked":[],"zones":[],"tasks":[${Json.encodeToString(JsonObject.serializer(), task)}]$extra}""",
    )

    private fun task(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject
}
