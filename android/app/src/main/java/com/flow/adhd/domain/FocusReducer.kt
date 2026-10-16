package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.math.floor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Focus durations and labels share the existing persisted fields; focus itself awards no XP. */
object FocusReducer {
    private val iso = DateTimeFormatterBuilder().appendInstant(3).toFormatter()
    private val clock = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId): FlowCommandResult {
        val data = snapshot.data.toMutableMap()
        val at = command.activityAt
        val instant = try { Instant.ofEpochMilli(at) } catch (_: Exception) {
            return FlowCommandResult.Rejected("invalid-time", "Focus time is invalid")
        }
        val rawLog = data["focusLog"]
        if (rawLog != null && rawLog !is JsonArray) return FlowCommandResult.Rejected("invalid-focus-log", "Focus history must be an array")
        var log = (rawLog as? JsonArray)?.map { it as? JsonObject
            ?: return FlowCommandResult.Rejected("invalid-focus-log", "Focus history contains an invalid entry") } ?: emptyList()
        var focus = data.text("currentFocus").orEmpty()
        fun closeCurrent() {
            val last = log.lastOrNull() ?: return
            if (last.text("end").isNullOrEmpty()) {
                val start = try { Instant.parse(last.text("start")).toEpochMilli() } catch (_: Exception) { return }
                val duration = floor((at - start) / 60_000.0 + 0.5).toLong()
                log = log.dropLast(1) + JsonObject(last + mapOf("end" to JsonPrimitive(iso.format(instant)), "dur" to JsonPrimitive(duration)))
            }
        }
        fun clear() {
            if (focus.isEmpty()) return
            closeCurrent()
            focus = ""
        }
        fun start(value: String) {
            if (focus == value) return
            if (focus.isNotEmpty() && focus != value) closeCurrent()
            val local = instant.atZone(zone)
            log = log + buildJsonObject {
                put("val", value); put("start", iso.format(instant)); put("end", JsonNull)
                put("date", local.toLocalDate().toString()); put("time", clock.format(local))
            }
            focus = value
        }
        when (command) {
            is FlowCommand.SetFocus -> {
                if (focus == command.value) return FlowCommandResult.Applied(snapshot, emptyList())
                start(command.value)
            }
            is FlowCommand.ClearFocus -> {
                if (focus.isEmpty()) return FlowCommandResult.Applied(snapshot, emptyList())
                clear()
            }
            is FlowCommand.AddFocusChip -> {
                val chips = data["focusChips"] as? JsonArray ?: JsonArray(emptyList())
                if (command.value.isNotEmpty() && chips.none { (it as? JsonPrimitive)?.content == command.value }) {
                    data["focusChips"] = JsonArray(chips + JsonPrimitive(command.value))
                }
                start(command.value)
            }
            is FlowCommand.RemoveFocusChip -> {
                val chips = data["focusChips"] as? JsonArray ?: JsonArray(emptyList())
                data["focusChips"] = JsonArray(chips.filter { (it as? JsonPrimitive)?.content != command.value })
                if (focus == command.value) clear()
            }
            else -> return FlowCommandResult.Rejected("unsupported-command", "FocusReducer does not handle this command")
        }
        data["currentFocus"] = JsonPrimitive(focus)
        data["focusLog"] = JsonArray(log)
        val state = FlowSnapshot.fromData(JsonObject(data))
        return if (state.data == snapshot.data) FlowCommandResult.Applied(snapshot, emptyList())
            else FlowCommandResult.Applied(state, listOf(FlowEvent("focus_changed", at)))
    }

    private fun Map<String, kotlinx.serialization.json.JsonElement>.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
