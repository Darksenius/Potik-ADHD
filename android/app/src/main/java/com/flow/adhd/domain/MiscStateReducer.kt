package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Handles persisted editor-draft and rare-event state that does not award activity or XP. */
object MiscStateReducer {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand): FlowCommandResult {
        if (command !is FlowCommand.SetTaskEditorDraft && command !is FlowCommand.CreateRareEvent &&
            command !is FlowCommand.ChangeRareEvent && command !is FlowCommand.DeleteRareEvent
        ) return rejected("unsupported-command", "MiscStateReducer does not handle this command")

        return try {
            when (command) {
                is FlowCommand.SetTaskEditorDraft -> {
                    if (snapshot.data["taskEditorDraft"] == JsonPrimitive(command.text)) applied(snapshot)
                    else {
                        val state = snapshot.data.toMutableMap()
                        state["taskEditorDraft"] = JsonPrimitive(command.text)
                        applied(FlowSnapshot.fromData(JsonObject(state)))
                    }
                }
                is FlowCommand.CreateRareEvent -> create(snapshot, command)
                is FlowCommand.ChangeRareEvent -> change(snapshot, command)
                is FlowCommand.DeleteRareEvent -> delete(snapshot, command)
                else -> rejected("unsupported-command", "MiscStateReducer does not handle this command")
            }
        } catch (error: IllegalArgumentException) {
            rejected("invalid-state", error.message ?: "Stored misc state is invalid")
        }
    }

    private fun create(snapshot: FlowSnapshot, command: FlowCommand.CreateRareEvent): FlowCommandResult {
        val name = command.name.trim()
        if (name.isEmpty()) return rejected("invalid-rare-event", "Rare-event name must not be blank")
        if (command.direction != "up" && command.direction != "down") {
            return rejected("invalid-rare-event", "Rare-event direction must be up or down")
        }
        val events = readRareEvents(snapshot.data)
        var id = command.activityAt.coerceAtLeast(1L)
        val used = events.map { it.id }.toHashSet()
        while (id in used) {
            if (id >= MAX_SAFE_ID) return rejected("rare-event-id-exhausted", "No safe rare-event ID remains")
            id++
        }
        if (id > MAX_SAFE_ID) return rejected("rare-event-id-exhausted", "No safe rare-event ID remains")
        val added = JsonObject(mapOf(
            "id" to JsonPrimitive(id), "nm" to JsonPrimitive(name), "val" to JsonPrimitive(0),
            "dir" to JsonPrimitive(command.direction), "unit" to JsonPrimitive(""),
        ))
        return replaceEvents(snapshot, events.map { it.raw } + added)
    }

    private fun change(snapshot: FlowSnapshot, command: FlowCommand.ChangeRareEvent): FlowCommandResult {
        val events = readRareEvents(snapshot.data)
        val index = events.indexOfFirst { it.id == command.eventId }
        if (index < 0) return applied(snapshot)
        val event = events[index]
        val value = (event.value + command.delta).coerceAtLeast(0.0)
        if (!value.isFinite()) return rejected("invalid-rare-event", "Rare-event value must remain finite")
        val updated = JsonObject(event.raw + ("val" to JsonPrimitive(value)))
        if (updated == event.raw) return applied(snapshot)
        return replaceEvents(snapshot, events.mapIndexed { i, item -> if (i == index) updated else item.raw })
    }

    private fun delete(snapshot: FlowSnapshot, command: FlowCommand.DeleteRareEvent): FlowCommandResult {
        val events = readRareEvents(snapshot.data)
        val remaining = events.filterNot { it.id == command.eventId }
        if (remaining.size == events.size) return applied(snapshot)
        return replaceEvents(snapshot, remaining.map { it.raw })
    }

    private data class StoredRareEvent(val raw: JsonObject, val id: Long, val value: Double)

    private fun readRareEvents(data: JsonObject): List<StoredRareEvent> {
        val value = data["rareEvents"] ?: return emptyList()
        val array = value as? JsonArray ?: throw IllegalArgumentException("rareEvents must be an array")
        val used = mutableSetOf<Long>()
        return array.mapIndexed { index, item ->
            val event = item as? JsonObject ?: throw IllegalArgumentException("rareEvents[$index] must be an object")
            val id = event.safeId("id") ?: throw IllegalArgumentException("rareEvents[$index].id must be a safe integer")
            if (!used.add(id)) throw IllegalArgumentException("rareEvents contains duplicate IDs")
            val name = event.text("nm") ?: throw IllegalArgumentException("rareEvents[$index].nm must be a string")
            if (name.isBlank()) throw IllegalArgumentException("rareEvents[$index].nm must not be blank")
            val amount = when (val rawAmount = event["val"]) {
                null -> 0.0
                is JsonPrimitive -> rawAmount.takeUnless { it.isString }?.doubleOrNull
                    ?.takeIf { it.isFinite() && it >= 0.0 }
                    ?: throw IllegalArgumentException("rareEvents[$index].val must be a non-negative finite number")
                else -> throw IllegalArgumentException("rareEvents[$index].val must be a number")
            }
            val direction = event.text("dir") ?: "up"
            if (direction != "up" && direction != "down") throw IllegalArgumentException("rareEvents[$index].dir must be up or down")
            val unit = event["unit"]
            if (unit != null && (unit !is JsonPrimitive || !unit.isString)) {
                throw IllegalArgumentException("rareEvents[$index].unit must be a string")
            }
            StoredRareEvent(event, id, amount)
        }
    }

    private fun replaceEvents(snapshot: FlowSnapshot, events: List<JsonObject>): FlowCommandResult {
        val state = snapshot.data.toMutableMap()
        state["rareEvents"] = JsonArray(events)
        return applied(FlowSnapshot.fromData(JsonObject(state)))
    }

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.safeId(key: String): Long? {
        val value = this[key] as? JsonPrimitive ?: return null
        val number = value.takeUnless { it.isString }?.doubleOrNull ?: return null
        if (!number.isFinite() || number % 1.0 != 0.0 || number < 0.0 || number > MAX_SAFE_ID.toDouble()) return null
        return value.longOrNull ?: number.toLong()
    }
}
