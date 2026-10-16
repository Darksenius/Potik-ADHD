package com.flow.adhd.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Pure mutations for day notes, overrides, daily zone exclusions, and per-day zone edits. */
object PlannerDayActions {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991.0
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
    private val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val timePattern = Regex("^(\\d{1,2}):(\\d{2})$")

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: java.time.ZoneId, now: Long): FlowCommandResult {
        val date = when (command) {
            is FlowCommand.SetPlanDayNote -> command.date
            is FlowCommand.ResetPlanDay -> command.date
            is FlowCommand.SetPlanZoneOff -> command.date
            is FlowCommand.EditPlanZoneForDay -> command.date
            else -> return rejected("wrong-command", "Expected a planner day action")
        }
        if (!validDate(date)) return rejected("invalid-date", "Planner date must be a valid YYYY-MM-DD date")
        if (command.activityAt !in 0..MAX_DATE_MILLIS || now !in 0..MAX_DATE_MILLIS) {
            return rejected("invalid-time", "Activity time is invalid")
        }

        return try {
            when (command) {
                is FlowCommand.SetPlanDayNote -> setNote(snapshot, command, zone, now)
                is FlowCommand.ResetPlanDay -> reset(snapshot, command, zone, now)
                is FlowCommand.SetPlanZoneOff -> setZoneOff(snapshot, command)
                is FlowCommand.EditPlanZoneForDay -> editZoneForDay(snapshot, command, zone, now)
                else -> rejected("wrong-command", "Expected a planner day action")
            }
        } catch (e: DayActionFailure) {
            rejected(e.code, e.message ?: "Planner day action is invalid")
        } catch (e: Exception) {
            rejected("invalid-planner-day", e.message ?: "Planner day state is invalid")
        }
    }

    private fun setNote(snapshot: FlowSnapshot, command: FlowCommand.SetPlanDayNote, zone: java.time.ZoneId, now: Long): FlowCommandResult {
        val log = objectMap(snapshot.data, "planDayLog")
        val entry = (log[command.date] as? JsonObject)?.toMutableMap() ?: if (log.containsKey(command.date)) {
            fail("invalid-plan-log", "Stored day log must be an object")
        } else defaultDayLog().toMutableMap()
        entry["note"] = JsonPrimitive(command.text)
        log[command.date] = JsonObject(entry)
        val changed = snapshot.data.toMutableMap()
        changed["planDayLog"] = JsonObject(log)
        if (command.text.trim().isNotEmpty()) {
            val activity = Progression.recordActivity(JsonObject(changed), command.activityAt, zone, now)
            return applied(activity.state, activity.events)
        }
        return applied(JsonObject(changed))
    }

    private fun reset(snapshot: FlowSnapshot, command: FlowCommand.ResetPlanDay, zone: java.time.ZoneId, now: Long): FlowCommandResult {
        val changed = snapshot.data.toMutableMap()
        for (key in listOf("planDayZones", "planRestDays", "planDayOff")) {
            val days = objectMap(snapshot.data, key)
            days.remove(command.date)
            changed[key] = JsonObject(days)
        }
        val recalculated = ZoneReducer.recalculateUsage(JsonObject(changed), now, zone)
        return applied(recalculated)
    }

    private fun setZoneOff(snapshot: FlowSnapshot, command: FlowCommand.SetPlanZoneOff): FlowCommandResult {
        validateSafeId(command.zoneId, "zoneId")
        val days = objectMap(snapshot.data, "planDayOff")
        val current = idArray(days[command.date], "planDayOff.${command.date}")
        val updated = if (command.off) {
            if (command.zoneId in current) current else current + command.zoneId
        } else {
            current.filterNot { it == command.zoneId }
        }
        if (updated.isEmpty()) days.remove(command.date) else days[command.date] = JsonArray(updated.map(::JsonPrimitive))
        val changed = snapshot.data.toMutableMap()
        changed["planDayOff"] = JsonObject(days)
        return applied(JsonObject(changed))
    }

    private fun editZoneForDay(snapshot: FlowSnapshot, command: FlowCommand.EditPlanZoneForDay, zone: java.time.ZoneId, now: Long): FlowCommandResult {
        validateSafeId(command.zoneId, "zoneId")
        val zones = snapshot.zones
        val matching = zones.filter { safeId(it["id"]) == command.zoneId }
        if (matching.size > 1) fail("invalid-zones", "Stored zones contain duplicate IDs")
        val definition = matching.singleOrNull() ?: return applied(snapshot.data)
        val slots = slots(definition["slots"], "zones.${command.zoneId}.slots")

        val dayOverrides = objectMap(snapshot.data, "planDayZones")
        val materialized = if (dayOverrides.containsKey(command.date)) {
            validateBlocks(dayOverrides.getValue(command.date), "planDayZones.${command.date}")
        } else {
            effectiveRuleBlocks(snapshot.data, command.date)
        }
        val additions = slots.map { slot ->
            JsonObject(mapOf(
                "zoneId" to JsonPrimitive(command.zoneId),
                "s" to JsonPrimitive(slot.first),
                "e" to JsonPrimitive(slot.second),
                "ov" to JsonPrimitive(true),
            ))
        }
        dayOverrides[command.date] = JsonArray(materialized + additions)

        val daysOff = objectMap(snapshot.data, "planDayOff")
        val currentOff = idArray(daysOff[command.date], "planDayOff.${command.date}")
        daysOff[command.date] = JsonArray(currentOff.filterNot { it == command.zoneId }.plus(command.zoneId).map(::JsonPrimitive))

        val changed = snapshot.data.toMutableMap()
        changed["planDayZones"] = JsonObject(dayOverrides)
        changed["planDayOff"] = JsonObject(daysOff)
        val recalculated = ZoneReducer.recalculateUsage(JsonObject(changed), now, zone)
        return applied(recalculated)
    }

    /** Reuse the common projection so cycles and override precedence have one implementation. */
    private fun effectiveRuleBlocks(state: JsonObject, day: String): List<JsonElement> {
        for (key in listOf("planRules", "dayTemplates")) {
            arrayField(state, key, defaultEmpty = true).forEachIndexed { index, value ->
                if (value !is JsonObject) fail("invalid-collection", "$key[$index] must be an object")
            }
        }
        return validateBlocks(JsonArray(PlannerProjection.dayBlocks(state, day)), "effectiveDayBlocks.$day")
    }

    private fun validateBlocks(value: JsonElement, path: String): List<JsonElement> {
        val blocks = value as? JsonArray ?: fail("invalid-blocks", "$path must be an array")
        return blocks.mapIndexed { index, item ->
            val block = item as? JsonObject ?: fail("invalid-block", "$path[$index] must be an object")
            val id = safeId(block["zoneId"]) ?: fail("invalid-zone", "$path[$index].zoneId must be a safe integer ID")
            val start = stringField(block, "s", "$path[$index].s")
            val end = stringField(block, "e", "$path[$index].e")
            val range = normalizeRange(start, end) ?: fail("invalid-range", "$path[$index] must have a valid range")
            block["ov"]?.let { if ((it as? JsonPrimitive)?.let { primitive -> !primitive.isString && primitive.booleanOrNull != null } != true) fail("invalid-block", "$path[$index].ov must be a boolean") }
            JsonObject(block + mapOf("zoneId" to JsonPrimitive(id), "s" to JsonPrimitive(range.first), "e" to JsonPrimitive(range.second)))
        }
    }

    private fun slots(value: JsonElement?, path: String): List<Pair<String, String>> {
        if (value == null || value == JsonNull) return emptyList()
        val array = value as? JsonArray ?: fail("invalid-slots", "$path must be an array")
        return array.mapIndexed { index, item ->
            val slot = item as? JsonObject ?: fail("invalid-slots", "$path[$index] must be an object")
            val start = stringField(slot, "s", "$path[$index].s")
            val end = stringField(slot, "e", "$path[$index].e")
            normalizeRange(start, end) ?: fail("invalid-range", "$path[$index] must have a valid range")
        }
    }

    private fun normalizeRange(start: String, end: String): Pair<String, String>? {
        val normalizedStart = normalizeTime(start) ?: return null
        val normalizedEnd = if (end == "24:00" || end == "00:00") "24:00" else normalizeTime(end) ?: return null
        val startMinutes = minutes(normalizedStart)
        val endMinutes = if (normalizedEnd == "24:00") 24 * 60 else minutes(normalizedEnd)
        return if (startMinutes < endMinutes) normalizedStart to normalizedEnd else null
    }

    private fun normalizeTime(value: String): String? {
        val match = timePattern.matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return String.format(Locale.ROOT, "%02d:%02d", hour, minute)
    }

    private fun minutes(value: String): Int = value.substringBefore(':').toInt() * 60 + value.substringAfter(':').toInt()

    private fun validDate(value: String): Boolean = datePattern.matches(value) && runCatching { LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE) }.isSuccess

    private fun objectMap(state: JsonObject, key: String): MutableMap<String, JsonElement> = when (val value = state[key]) {
        null -> mutableMapOf()
        is JsonObject -> value.toMutableMap()
        else -> fail("invalid-collection", "Stored '$key' must be an object")
    }

    private fun arrayField(state: JsonObject, key: String, defaultEmpty: Boolean = false): JsonArray = when (val value = state[key]) {
        null -> if (defaultEmpty) JsonArray(emptyList()) else fail("invalid-collection", "Stored '$key' must be an array")
        is JsonArray -> value
        else -> fail("invalid-collection", "Stored '$key' must be an array")
    }

    private fun idArray(value: JsonElement?, path: String): List<Long> {
        if (value == null) return emptyList()
        val array = value as? JsonArray ?: fail("invalid-collection", "$path must be an array")
        return array.mapIndexed { index, element -> safeId(element) ?: fail("invalid-zone", "$path[$index] must be a safe zone ID") }
    }

    private fun safeId(value: JsonElement?): Long? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        val number = primitive.doubleOrNull ?: return null
        if (!number.isFinite() || number < 0 || number > MAX_SAFE_ID || number % 1.0 != 0.0) return null
        return primitive.longOrNull ?: number.toLong()
    }

    private fun validateSafeId(value: Long, field: String) {
        if (value !in 0..9_007_199_254_740_991L) fail("invalid-zone", "$field must be a safe non-negative integer")
    }

    private fun stringField(obj: JsonObject, key: String, path: String): String =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: fail("invalid-state", "$path must be a string")

    private fun defaultDayLog() = JsonObject(mapOf(
        "focus" to JsonArray(emptyList()),
        "energy" to JsonPrimitive(0),
        "routineDone" to JsonObject(emptyMap()),
        "priorities" to JsonArray(emptyList()),
        "tasksDone" to JsonPrimitive(0),
    ))

    private fun applied(state: JsonObject, events: List<FlowEvent> = emptyList()): FlowCommandResult =
        FlowCommandResult.Applied(FlowSnapshot.fromData(state), events)

    private fun rejected(code: String, message: String): FlowCommandResult = FlowCommandResult.Rejected(code, message)

    private fun fail(code: String, message: String): Nothing = throw DayActionFailure(code, message)
    private class DayActionFailure(val code: String, message: String) : IllegalArgumentException(message)
}
