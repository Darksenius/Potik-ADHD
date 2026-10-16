package com.flow.adhd.domain

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Mutations for manual planner days and their durable legacy annotations. */
object PlannerReducer {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val promptTitle = "Заплануй наступний тиждень"
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long): FlowCommandResult {
        val date = when (command) {
            is FlowCommand.SetPlanDay -> command.date
            is FlowCommand.SetPlanRestDay -> command.date
            is FlowCommand.AddPlanItem -> command.date
            is FlowCommand.UpdatePlanItem -> command.date
            is FlowCommand.DeletePlanItem -> command.date
            else -> return rejected("wrong-command", "Expected a planner command")
        }
        if (!validDate(date)) return rejected("invalid-date", "Planner date must be a valid YYYY-MM-DD date")
        if (command.activityAt !in 0..MAX_DATE_MILLIS || now !in 0..MAX_DATE_MILLIS) return rejected("invalid-time", "Activity time is invalid")
        return try {
            when (command) {
                is FlowCommand.SetPlanDay -> setDay(snapshot, command, zone, now)
                is FlowCommand.SetPlanRestDay -> setRest(snapshot, command)
                is FlowCommand.AddPlanItem -> addItem(snapshot, command, zone, now)
                is FlowCommand.UpdatePlanItem -> updateItem(snapshot, command)
                is FlowCommand.DeletePlanItem -> deleteItem(snapshot, command)
                else -> rejected("wrong-command", "Expected a planner command")
            }
        } catch (e: IllegalArgumentException) {
            rejected("invalid-payload", e.message ?: "Planner payload is invalid")
        }
    }

    private fun setDay(snapshot: FlowSnapshot, command: FlowCommand.SetPlanDay, zone: ZoneId, now: Long): FlowCommandResult {
        if (command.payload.keys != setOf("blocks")) return rejected("invalid-payload", "SetPlanDay accepts only blocks")
        val blocks = command.payload["blocks"] as? JsonArray ?: return rejected("invalid-blocks", "blocks must be an array")
        val zones = snapshot.zones.mapNotNull { it.safeId() }.toSet()
        val normalized = blocks.mapIndexed { i, value ->
            val block = value as? JsonObject ?: return rejected("invalid-block", "blocks[$i] must be an object")
            val id = block.safeId("zoneId") ?: return rejected("invalid-zone", "blocks[$i].zoneId must be a valid zone ID")
            if (id !in zones) return rejected("invalid-zone", "blocks[$i].zoneId does not exist")
            val s = block.string("s") ?: return rejected("invalid-range", "blocks[$i].s must be a time")
            val e = block.string("e") ?: return rejected("invalid-range", "blocks[$i].e must be a time")
            val range = normalizeRange(s, e) ?: return rejected("invalid-range", "blocks[$i] must have a valid range with end after start")
            JsonObject(block + mapOf("s" to JsonPrimitive(range.first), "e" to JsonPrimitive(range.second)))
        }
        val changed = snapshot.data.toMutableMap()
        val plan = (snapshot.data["planDayZones"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        plan[command.date] = JsonArray(normalized)
        changed["planDayZones"] = JsonObject(plan)
        var result = ZoneReducer.recalculateUsage(JsonObject(changed), now, zone)
        if (normalized.isNotEmpty()) {
            val activity = Progression.recordActivity(result, command.activityAt, zone, now)
            result = activity.state
            return applied(result, activity.events)
        }
        return applied(result, emptyList())
    }

    private fun setRest(snapshot: FlowSnapshot, command: FlowCommand.SetPlanRestDay): FlowCommandResult {
        val changed = snapshot.data.toMutableMap()
        val rest = (snapshot.data["planRestDays"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        rest[command.date] = JsonPrimitive(command.isRest)
        changed["planRestDays"] = JsonObject(rest)
        if (command.isRest) {
            val current = items(snapshot.data, command.date)
            if (current.none { it.boolean("isPrompt") == true && (it.string("title") ?: "").contains(promptTitle) }) {
                putItems(changed, command.date, current + JsonObject(mapOf("title" to JsonPrimitive(promptTitle), "done" to JsonPrimitive(false), "isPrompt" to JsonPrimitive(true))))
            }
        }
        return applied(JsonObject(changed), emptyList())
    }

    private fun addItem(snapshot: FlowSnapshot, command: FlowCommand.AddPlanItem, zone: ZoneId, now: Long): FlowCommandResult {
        if (command.payload.keys.any { it !in setOf("text", "isEvent") }) return rejected("invalid-payload", "AddPlanItem has unknown fields")
        val text = command.payload.string("text")
            ?: return rejected("invalid-title", "text must be a string")
        val isEvent = command.payload.boolean("isEvent") ?: if (command.payload.containsKey("isEvent")) return rejected("invalid-payload", "isEvent must be a boolean") else false
        val match = Regex("^(\\d{1,2}:\\d{2})\\s+(.+)").find(text)
        val rawTitle = match?.groupValues?.get(2) ?: text
        if (isEvent) {
            val time = match?.groupValues?.get(1) ?: ""
            val item = JsonObject(mapOf("title" to JsonPrimitive(rawTitle), "done" to JsonPrimitive(false), "isEvent" to JsonPrimitive(true), "time" to JsonPrimitive(time)))
            val changed = snapshot.data.toMutableMap(); putItems(changed, command.date, items(snapshot.data, command.date) + item)
            return applied(JsonObject(changed), emptyList())
        }
        val scheduled = match != null
        val time = if (scheduled) normalizeTime(match!!.groupValues[1]) ?: return rejected("invalid-time", "Time is invalid") else ""
        val id = nextTaskId(snapshot) ?: return rejected("id-exhausted", "No safe task ID remains")
        val task = TaskFactory.create(id, rawTitle, if (scheduled) "sched" else "simple", command.activityAt).toMutableMap()
        task["planDate"] = JsonPrimitive(command.date)
        if (scheduled) { task["schedDate"] = JsonPrimitive(command.date); task["schedTime"] = JsonPrimitive(time); task["firedSched"] = JsonPrimitive(false); task["reminderEnabled"] = JsonPrimitive(true); task["remindBeforeMinutes"] = JsonPrimitive(0) }
        val changed = snapshot.data.toMutableMap(); changed["tasks"] = JsonArray(listOf(JsonObject(task)) + snapshot.tasks.map { it.raw }); changed["nid"] = JsonPrimitive(maxOf(id + 1, nextNid(snapshot)))
        val award = Experience.award(JsonObject(changed), 2.0, command.activityAt, zone, now)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(award.state), award.events)
    }

    private fun updateItem(snapshot: FlowSnapshot, command: FlowCommand.UpdatePlanItem): FlowCommandResult {
        if (command.payload.keys != setOf("done")) return rejected("invalid-payload", "UpdatePlanItem accepts only done")
        val done = command.payload.boolean("done") ?: return rejected("invalid-payload", "done must be a boolean")
        val items = items(snapshot.data, command.date)
        val old = items.getOrNull(command.index) ?: return applied(snapshot.data, emptyList())
        if (old.boolean("isPrompt") == true) return applied(snapshot.data, emptyList())
        val next = items.toMutableList(); next[command.index] = JsonObject(old + ("done" to JsonPrimitive(done)))
        val changed = snapshot.data.toMutableMap(); putItems(changed, command.date, next)
        return applied(JsonObject(changed), emptyList())
    }

    private fun deleteItem(snapshot: FlowSnapshot, command: FlowCommand.DeletePlanItem): FlowCommandResult {
        val items = items(snapshot.data, command.date)
        if (command.index !in items.indices) return applied(snapshot.data, emptyList())
        val changed = snapshot.data.toMutableMap(); putItems(changed, command.date, items.filterIndexed { i, _ -> i != command.index })
        return applied(JsonObject(changed), emptyList())
    }

    private fun nextTaskId(snapshot: FlowSnapshot): Long? {
        val nid = snapshot.data["nid"].safeLong() ?: 300L
        val maxTaskId = snapshot.tasks.maxOfOrNull { it.id } ?: 299L
        if (maxTaskId >= MAX_SAFE_ID) return null
        val next = maxOf(300L, nid, maxTaskId + 1L)
        return next.takeIf { it in 1 until MAX_SAFE_ID }
    }
    private fun nextNid(snapshot: FlowSnapshot) = snapshot.data["nid"].safeLong() ?: 300L
    private fun items(state: JsonObject, date: String): List<JsonObject> {
        val map = state["planItems"] ?: return emptyList()
        val day = (map as? JsonObject)?.get(date) ?: return emptyList()
        val array = day as? JsonArray ?: throw IllegalArgumentException("planItems.$date must be an array")
        return array.mapIndexed { index, item -> item as? JsonObject ?: throw IllegalArgumentException("planItems.$date[$index] must be an object") }
    }
    private fun putItems(state: MutableMap<String, JsonElement>, date: String, items: List<JsonObject>) {
        val map = (state["planItems"] as? JsonObject)?.toMutableMap() ?: mutableMapOf(); map[date] = JsonArray(items); state["planItems"] = JsonObject(map)
    }
    private fun validDate(value: String): Boolean = Regex("\\d{4}-\\d{2}-\\d{2}").matches(value) && runCatching { LocalDate.parse(value, dateFormat).toString() == value }.getOrDefault(false)
    private fun normalizeTime(value: String): String? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null; val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return formatMinute(h * 60 + min)
    }
    private fun normalizeRange(s: String, e: String): Pair<String, String>? {
        val start = normalizeTime(s)?.toMinutes() ?: return null
        val end = if (e == "24:00" || e == "00:00") 1440 else normalizeTime(e)?.toMinutes() ?: return null
        if (end <= start) return null
        return formatMinute(start) to if (end == 1440) "24:00" else formatMinute(end)
    }
    private fun formatMinute(value: Int) = String.format(Locale.ROOT, "%02d:%02d", value / 60, value % 60)
    private fun String.toMinutes() = substringBefore(':').toInt() * 60 + substringAfter(':').toInt()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.safeId(key: String = "id"): Long? = this[key].safeLong()?.takeIf { it >= 0 }
    private fun JsonElement?.safeLong(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_ID }
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun applied(state: JsonObject, events: List<FlowEvent>) = FlowCommandResult.Applied(FlowSnapshot.fromData(state), events)
}
