package com.flow.adhd.domain

import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Explicit activation state changes for a zone. This command never awards XP or records activity. */
object ZoneActivationReducer {
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long): FlowCommandResult {
        val setOff = command as? FlowCommand.SetZoneOff
            ?: return FlowCommandResult.Rejected("wrong-command", "Expected a set-zone-off command")
        if (now !in 0..MAX_DATE_MILLIS || setOff.activityAt !in 0..MAX_DATE_MILLIS) {
            return FlowCommandResult.Rejected("invalid-time", "Activity time is invalid")
        }
        if (setOff.zoneId !in 0..9_007_199_254_740_991L) return FlowCommandResult.Rejected("invalid-zone", "Zone ID is invalid")
        return try { activate(snapshot, setOff, zone, now) }
        catch (error: Exception) { FlowCommandResult.Rejected("invalid-zone-state", error.message ?: "Zone state is invalid") }
    }

    private fun activate(snapshot: FlowSnapshot, setOff: FlowCommand.SetZoneOff, zone: ZoneId, now: Long): FlowCommandResult {

        // Recompute first so pinning uses current planner/template bindings, not stale cached fields.
        val fresh = ZoneReducer.recalculateUsage(snapshot.data, now, zone)
        val zones = (fresh["zones"] as? JsonArray)?.toMutableList() ?: mutableListOf()
        val index = zones.indexOfFirst { element ->
            val id = (element as? JsonObject)?.get("id") as? JsonPrimitive
            id?.takeUnless { it.isString }?.longOrNull == setOff.zoneId
        }
        // The former toggle action was a no-op when the zone no longer existed.
        if (index < 0) return FlowCommandResult.Applied(snapshot, emptyList())

        val current = zones[index] as JsonObject
        val changed = current.toMutableMap()
        if (setOff.off) {
            changed["off"] = JsonPrimitive(true)
            changed.remove("actPin")
        } else {
            val usage = ((fresh["zoneUsage"] as? JsonObject)?.get(setOff.zoneId.toString()) as? JsonObject)
            val bound = current["bound"] == JsonPrimitive(true)
            val future = usage?.get("future") == JsonPrimitive(true)
            changed["off"] = JsonPrimitive(false)
            changed["active"] = JsonPrimitive(true)
            if (bound && !future) changed["actPin"] = JsonPrimitive(true) else changed.remove("actPin")
        }
        zones[index] = JsonObject(changed)
        val state = fresh.toMutableMap().apply {
            put("zones", JsonArray(zones))
            put("lastZoneId", JsonPrimitive(-1))
        }
        val recalculated = ZoneReducer.recalculateUsage(JsonObject(state), now, zone)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(recalculated), emptyList())
    }
}
