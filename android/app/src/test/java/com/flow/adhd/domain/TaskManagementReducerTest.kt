package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagementReducerTest {
    private val zone = ZoneId.of("Europe/Kyiv")
    private val at = Instant.parse("2026-10-16T10:00:00Z").toEpochMilli()

    @Test fun completedTaskRoundTripsThroughSomedayWithoutReawarding() {
        val initial = snapshot("""{"id":1,"created":"c","title":"Done","type":"simple","done":true,"doneDate":"2026-10-16","doneAt":"12:00","completionCredited":true,"completionCreditDate":"2026-10-16","custom":{"kept":1}}""", extra = "\"xpTotal\":10,\"done\":1,\"priorities\":[1,null,null]")
        val moved = applied(reduce(initial, FlowCommand.MoveTaskToSomeday(1, "move", at)))
        assertTrue(moved.snapshot.tasks.single().someday)
        assertFalse(moved.snapshot.tasks.single().done)
        assertEquals(JsonPrimitive(10), moved.snapshot.data["xpTotal"])
        assertEquals("1", moved.snapshot.tasks.single().raw["custom"]?.jsonObject?.get("kept")?.toString())

        val restored = applied(reduce(moved.snapshot, FlowCommand.RestoreTaskFromSomeday(1, "restore", at))).snapshot
        val completed = applied(TaskCompletionReducer.reduce(restored,
            FlowCommand.SetTaskDone(1, true, at, "complete", at), zone, mapOf("simple" to 10.0), at)).snapshot
        assertEquals(JsonPrimitive(10), completed.data["xpTotal"])
        assertEquals(JsonPrimitive(1), completed.data["done"])
        assertEquals(Json.parseToJsonElement("[null,null,null]"), completed.data["priorities"])
    }

    @Test fun somedayCreditUsesExplicitFalseAndFallsBackPastEmptyDates() {
        val explicitFalse = snapshot("""{"id":6,"created":"c","title":"Uncredited","type":"simple","done":true,"completionCredited":false,"completionCreditDate":"","doneDate":""}""")
        val falseMoved = applied(reduce(explicitFalse, FlowCommand.MoveTaskToSomeday(6, "false", at))).snapshot.tasks.single().raw
        assertEquals(JsonPrimitive(false), falseMoved["completionCredited"])
        assertEquals(JsonPrimitive(""), falseMoved["completionCreditDate"])

        val credited = snapshot("""{"id":7,"created":"c","title":"Credited","type":"simple","done":true,"completionCredited":true,"completionCreditDate":"","doneDate":""}""")
        val creditedMoved = applied(reduce(credited, FlowCommand.MoveTaskToSomeday(7, "credited", at))).snapshot.tasks.single().raw
        assertEquals(JsonPrimitive(true), creditedMoved["completionCredited"])
        assertEquals(JsonPrimitive("2026-10-16"), creditedMoved["completionCreditDate"])
    }

    @Test fun trashAndHardDeleteSeedKnownCompletionAndKeepHistoryExtensions() {
        val prior = """{"id":"prior","occurrenceId":"old","kind":"completed","sequence":0,"at":1,"date":"2020-01-01","time":"00:00","future":{"kept":true}}"""
        val raw = """{"id":3,"created":"c","title":"Completed","type":"simple","done":true,"doneDate":"2026-10-16","doneAt":"12:00"}"""
        val initial = snapshot(raw, extra = "\"taskHistory\":[$prior],\"priorities\":[3,null,null]")
        val trashed = applied(reduce(initial, FlowCommand.TrashTask(3, "trash", at))).snapshot
        assertTrue(trashed.tasks.single().trashed)
        val history = trashed.data["taskHistory"] as JsonArray
        assertEquals(2, history.size)
        assertEquals(JsonPrimitive(true), (history.first() as JsonObject)["future"]?.jsonObject?.get("kept"))
        assertEquals(0, (trashed.data["priorities"] as JsonArray).count { it.toString() == "3" })

        val deleted = applied(reduce(trashed, FlowCommand.HardDeleteTask(3, "delete", at))).snapshot
        assertTrue(deleted.tasks.isEmpty())
        assertEquals(history, deleted.data["taskHistory"])
    }

    @Test fun priorityRequiresExistingCurrentlyAvailableUndoneTask() {
        val now = at
        val source = snapshot(
            """{"id":1,"created":"c","title":"Ready","type":"simple","done":false,"repeat":"none"}""",
            """{"id":2,"created":"c","title":"Done","type":"simple","done":true,"repeat":"none"}""",
            """{"id":3,"created":"c","title":"Future","type":"sched","done":false,"schedDate":"2026-10-17"}""",
            """{"id":4,"created":"c","title":"Blocked","type":"simple","done":false,"snoozeUntil":1792160000000}""",
            extra = "\"priorities\":[null,null,null]")
        val selected = applied(reduce(source, FlowCommand.SetPriority(1, 1, "priority", now))).snapshot
        assertEquals(Json.parseToJsonElement("[null,1,null]"), selected.data["priorities"])
        for (id in 2L..4L) {
            val same = reduce(selected, FlowCommand.SetPriority(0, id, "reject-$id", now))
            assertSame(selected, applied(same).snapshot)
        }
        assertSame(selected, applied(reduce(selected, FlowCommand.SetPriority(0, 999, "missing", now))).snapshot)
    }

    @Test fun nullPriorityClearsOnlyItsSlotAndInitializesDefaultSlotsWhenMissing() {
        val source = snapshot(extra = "\"priorities\":[1,2,3]")
        val cleared = applied(reduce(source, FlowCommand.SetPriority(1, null, "clear", at))).snapshot
        assertEquals(Json.parseToJsonElement("[1,null,3]"), cleared.data["priorities"])
        assertSame(cleared, applied(reduce(cleared, FlowCommand.SetPriority(1, null, "clear-again", at))).snapshot)

        val missing = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[]}""")
        val initialized = applied(reduce(missing, FlowCommand.SetPriority(2, null, "clear-default", at))).snapshot
        assertEquals(Json.parseToJsonElement("[null,null,null]"), initialized.data["priorities"])
    }

    @Test fun missingLifecycleTargetsAreNoOpsAndRestoreClearsTrashTimestamp() {
        val source = snapshot("""{"id":5,"created":"c","title":"Task","type":"simple","done":false,"trashed":true,"trashedAt":12}""")
        assertSame(source, applied(reduce(source, FlowCommand.RestoreTrashedTask(99, "missing", at))).snapshot)
        val restored = applied(reduce(source, FlowCommand.RestoreTrashedTask(5, "restore", at))).snapshot.tasks.single().raw
        assertEquals(JsonPrimitive(false), restored["trashed"])
        assertEquals(kotlinx.serialization.json.JsonNull, restored["trashedAt"])
    }

    private fun reduce(snapshot: FlowSnapshot, command: FlowCommand) = TaskManagementReducer.reduce(snapshot, command, zone, at)
    private fun applied(result: FlowCommandResult) = result as FlowCommandResult.Applied

    private fun snapshot(vararg tasks: String, extra: String = ""): FlowSnapshot {
        val rows = tasks.filter { it.isNotBlank() }.joinToString(",")
        val defaults = buildList {
            if (!extra.contains("\"taskHistory\"")) add("\"taskHistory\":[]")
            if (!extra.contains("\"priorities\"")) add("\"priorities\":[null,null,null]")
        }.joinToString(",")
        val fields = listOf(defaults, extra).filter { it.isNotBlank() }.joinToString(",")
        val suffix = if (fields.isBlank()) "" else ",$fields"
        return FlowSnapshot.parse("""{"formatVersion":2,"tasks":[$rows]$suffix}""")
    }
}
