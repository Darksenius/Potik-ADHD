package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Swaps adjacent visible tasks while retaining every hidden or trashed array slot. */
object TaskOrderReducer {
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand.MoveTask): FlowCommandResult.Applied {
        val tasks = snapshot.tasks
        val visibleIds = command.visibleIds.toHashSet()
        val activeIndices = tasks.indices.filter { !tasks[it].trashed && tasks[it].id in visibleIds }
        val position = activeIndices.indexOfFirst { tasks[it].id == command.taskId }
        val target = position + command.direction
        if (position < 0 || target !in activeIndices.indices) return applied(snapshot)

        val rows = (snapshot.data["tasks"] as JsonArray).toMutableList()
        val fromIndex = activeIndices[position]
        val toIndex = activeIndices[target]
        val moved = rows[fromIndex]
        rows[fromIndex] = rows[toIndex]
        rows[toIndex] = moved
        return applied(FlowSnapshot.fromData(JsonObject(snapshot.data + ("tasks" to JsonArray(rows)))))
    }

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
}
