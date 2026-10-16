package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskOrderReducerTest {
    @Test
    fun movingAcrossHiddenTasksSwapsOnlyAdjacentVisibleSlotsInFullListOrder() {
        val source = snapshot(
            task(1), task(2), task(3), task(4), task(5),
        )
        val down = move(source, 1, 1, listOf(5, 3, 1, 3, 999))
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), down.snapshot.tasks.map { it.id })
        assertSame(source.tasks[1].raw, down.snapshot.tasks[1].raw)
        assertSame(source.tasks[3].raw, down.snapshot.tasks[3].raw)
        assertTrue(down.events.isEmpty())

        val up = move(down.snapshot, 5, -1, listOf(1, 3, 5))
        assertEquals(listOf(3L, 2L, 5L, 4L, 1L), up.snapshot.tasks.map { it.id })
    }

    @Test
    fun boundaryMissingAndInvisibleTargetsAreNoOps() {
        val source = snapshot(task(1), task(2), task(3))
        val commands = listOf(
            FlowCommand.MoveTask(1, -1, listOf(1, 3), "first", 123),
            FlowCommand.MoveTask(3, 1, listOf(1, 3), "last", 123),
            FlowCommand.MoveTask(2, 1, listOf(1, 3), "hidden", 123),
            FlowCommand.MoveTask(999, -1, listOf(1, 3, 999), "missing", 123),
            FlowCommand.MoveTask(1, 1, emptyList(), "empty-visible", 123),
            FlowCommand.MoveTask(2, -1, listOf(2), "single-visible", 123),
        )
        commands.forEach { command ->
            val result = TaskOrderReducer.reduce(source, command)
            assertSame(command.commandId, source, result.snapshot)
            assertTrue(result.events.isEmpty())
        }
        val empty = snapshot()
        assertSame(empty, move(empty, 1, 1, listOf(1)).snapshot)
    }

    @Test
    fun trashedTasksKeepTheirSlotsEvenWhenIncludedInVisibleIds() {
        val source = snapshot(task(1), task(2, trashed = true), task(3), task(4, trashed = true), task(5))
        val result = move(source, 1, 1, listOf(1, 2, 3, 4, 5))
        assertEquals(listOf(3L, 2L, 1L, 4L, 5L), result.snapshot.tasks.map { it.id })
        assertSame(source.tasks[1].raw, result.snapshot.tasks[1].raw)
        assertSame(source.tasks[3].raw, result.snapshot.tasks[3].raw)
        assertSame(source, move(source, 2, 1, listOf(1, 2, 3, 4, 5)).snapshot)
    }

    @Test
    fun movePreservesUnknownFieldsAndDoesNotChangeXpHistoryOrActivity() {
        val source = snapshot(
            """{"id":1,"title":"First","done":true,"custom":{"future":[1,{"kept":true}]}}""",
            task(2),
            """{"id":3,"title":"Third","someday":true,"note":"Keep note","unknown":null}""",
            extra = """"xpTotal":42,"activityDates":["2026-10-15"],"lastActiveDate":"2026-10-15","taskHistory":[{"custom":"kept"}],"priorities":[1,null,3],"futureRoot":{"kept":true}""",
        )
        val result = move(source, 3, -1, listOf(1, 3))
        assertEquals(source.data - "tasks", result.snapshot.data - "tasks")
        val before = source.data["tasks"] as JsonArray
        val after = result.snapshot.data["tasks"] as JsonArray
        assertSame(before[0], after[2])
        assertSame(before[1], after[1])
        assertSame(before[2], after[0])
        assertTrue(result.events.isEmpty())
        assertEquals(null, result.undoReceipt)
    }

    private fun move(snapshot: FlowSnapshot, taskId: Long, direction: Int, visibleIds: List<Long>) =
        TaskOrderReducer.reduce(snapshot, FlowCommand.MoveTask(taskId, direction, visibleIds, "move", 123))

    private fun task(id: Long, trashed: Boolean = false) = """{"id":$id,"title":"Task $id","trashed":$trashed}"""

    private fun snapshot(vararg tasks: String, extra: String = ""): FlowSnapshot {
        val fields = if (extra.isBlank()) "" else ",$extra"
        return FlowSnapshot.fromData(Json.parseToJsonElement("""{"formatVersion":2,"tasks":[${tasks.joinToString(",")}]$fields}""") as JsonObject)
    }
}
