package com.flow.adhd.domain

import java.time.ZoneId
import java.time.Instant
import java.time.LocalDate
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Pure zone mutations over the existing persisted snapshot format. */
object ZoneReducer {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
    private val palette = listOf("#4f8ef7", "#f5a623", "#7ed321", "#ff6b35", "#bd10e0", "#50e3c2", "#e24b4a", "#9b59b6", "#f8e71c", "#16a085")

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long): FlowCommandResult {
        val create = command as? FlowCommand.CreateZone
        val update = command as? FlowCommand.UpdateZone
        val delete = command as? FlowCommand.DeleteZone
        if (create == null && update == null && delete == null) return rejected("wrong-command", "Expected a zone create, update, or delete command")
        if (now !in 0..MAX_DATE_MILLIS || command.activityAt !in 0..MAX_DATE_MILLIS) return rejected("invalid-time", "Activity time is invalid")

        if (delete != null) {
            if (snapshot.zones.none { it.safeId() == delete.zoneId }) return applied(snapshot)
            val state = snapshot.data.toMutableMap()
            state["zones"] = JsonArray(snapshot.zones.filterNot { it.safeId() == delete.zoneId })
            // The TypeScript deleteZone removes only the definition and retains all references for recovery.
            val result = FlowSnapshot.fromData(JsonObject(state))
            return applied(recalculateUsage(result.data, now, zone))
        }

        val payload = create?.payload ?: update!!.payload
        val acceptedKeys = if (create != null) setOf("nm") else setOf("nm", "color", "desc", "prio", "slots")
        if (payload.keys.any { it !in acceptedKeys }) return rejected("invalid-payload", "Zone payload has unknown fields")
        val name = payload.string("nm")?.trim() ?: return rejected("invalid-name", "Zone name must be a string")
        if (name.isEmpty()) return rejected("empty-name", "Zone name must not be blank")
        val allocatedId = if (create != null) allocateId(snapshot) ?: return rejected("id-exhausted", "No safe zone ID remains") else null
        val color = if (create != null) palette[allocatedId!!.rem(palette.size).toInt()] else payload.string("color")
            ?: return rejected("invalid-color", "Zone color must be a string")
        if (color.isBlank()) return rejected("invalid-color", "Zone color must not be blank")
        val desc = if (create != null) "" else (payload.string("desc")?.trim() ?: return rejected("invalid-description", "Zone description must be a string"))
        val priority = if (create != null) 1 else payload.number("prio")?.let { kotlin.math.floor(it + 0.5).toInt().coerceIn(1, 5) }
            ?: return rejected("invalid-priority", "Zone priority must be a number")
        val slots = if (create != null) JsonArray(emptyList()) else normalizeSlots(payload["slots"], snapshot.zones.firstOrNull { it.safeId() == update!!.zoneId }?.get("slots"))
            ?: return rejected("invalid-slots", "Zone time ranges must be valid and end after they start")

        val state = snapshot.data.toMutableMap()
        val zones = snapshot.zones.toMutableList()
        if (create != null) {
            val id = allocatedId!!
            val newZone = JsonObject(mapOf("id" to JsonPrimitive(id), "nm" to JsonPrimitive(name), "color" to JsonPrimitive(color), "slots" to slots,
                "desc" to JsonPrimitive(desc), "prio" to JsonPrimitive(priority), "active" to JsonPrimitive(true)))
            state["zones"] = JsonArray(zones + newZone)
            val award = Experience.award(JsonObject(state), 5.0, command.activityAt, zone, now)
            val updated = FlowSnapshot.fromData(award.state)
            return FlowCommandResult.Applied(updated, award.events)
        }
        val existing = zones.indexOfFirst { it.safeId() == update!!.zoneId }
        if (existing < 0) return applied(snapshot)
        val old = zones[existing]
        val changed = old.toMutableMap().apply {
            put("nm", JsonPrimitive(name)); put("color", JsonPrimitive(color)); put("desc", JsonPrimitive(desc))
            put("prio", JsonPrimitive(priority)); put("slots", slots)
        }
        zones[existing] = JsonObject(changed)
        state["zones"] = JsonArray(zones)
        state["tasks"] = JsonArray(snapshot.data.array("tasks").map { task ->
            val obj = task as? JsonObject ?: return@map task
            if (obj.safeId("zoneId") != update!!.zoneId) obj else JsonObject(obj + mapOf("zoneName" to JsonPrimitive(name), "zoneColor" to JsonPrimitive(color)))
        })
        return applied(recalculateUsage(JsonObject(state), now, zone))
    }

    /** Recompute derived zone binding and activity while preserving planner materializations. */
    fun recalculateUsage(state: JsonObject, now: Long, zone: ZoneId): JsonObject {
        val zones = state.array("zones").mapNotNull { it as? JsonObject }
        val today = try { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() } catch (_: Exception) { return state }
        val todayString = today.toString()
        val usage = linkedMapOf<Long, MutableMap<String, JsonElement>>()
        fun use(id: Long): MutableMap<String, JsonElement> = usage.getOrPut(id) {
            mutableMapOf("bound" to JsonPrimitive(false), "past" to JsonPrimitive(false), "future" to JsonPrimitive(false),
                "nextDs" to JsonPrimitive(""), "nextS" to JsonPrimitive(""), "lastDs" to JsonPrimitive(""), "tpls" to JsonArray(emptyList()))
        }
        fun setFlag(id: Long, key: String, value: Boolean) { use(id)[key] = JsonPrimitive(value) }
        fun markFuture(id: Long, day: String, start: String) {
            val item = use(id); setFlag(id, "bound", true); setFlag(id, "future", true)
            val nextDay = item["nextDs"].stringValue().orEmpty(); val nextStart = item["nextS"].stringValue().orEmpty()
            if (nextDay.isEmpty() || day < nextDay || (day == nextDay && start < nextStart)) {
                item["nextDs"] = JsonPrimitive(day); item["nextS"] = JsonPrimitive(start)
            }
        }
        fun markPast(id: Long, day: String) {
            val item = use(id); setFlag(id, "bound", true); setFlag(id, "past", true)
            if (day > item["lastDs"].stringValue().orEmpty()) item["lastDs"] = JsonPrimitive(day)
        }
        (state["planDayZones"] as? JsonObject)?.forEach { (ds, value) -> (value as? JsonArray)?.forEach blockLoop@{ block ->
            val b = block as? JsonObject ?: return@blockLoop
            if (b.truthy("ov")) return@blockLoop
            val id = b.safeId("zoneId") ?: return@blockLoop
            if (ds >= todayString) markFuture(id, ds, b.string("s").orEmpty()) else markPast(id, ds)
        } }
        val templates = (state["dayTemplates"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        templates.forEach { template ->
            val name = template.string("name").orEmpty()
            (template["blocks"] as? JsonArray)?.forEach blockLoop@{ block ->
                val id = (block as? JsonObject)?.safeId("zoneId") ?: return@blockLoop
                val item = use(id); val names = (item["tpls"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
                if (name !in names) item["tpls"] = JsonArray(names.map(::JsonPrimitive) + JsonPrimitive(name))
            }
        }
        (state["planRules"] as? JsonArray)?.forEach { rule ->
            val ruleObj = rule as? JsonObject ?: return@forEach
            val start = ruleObj.string("start")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
            val end = ruleObj.string("end")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@forEach
            val days = ruleObj["days"] as? JsonArray ?: return@forEach
            if (days.isEmpty() || end < start) return@forEach
            val length = days.size
            days.forEachIndexed { idx, entry ->
                val templateId = (entry as? JsonObject)?.string("tplId")?.takeIf { it.isNotEmpty() } ?: return@forEachIndexed
                val template = templates.firstOrNull { it.string("id") == templateId } ?: return@forEachIndexed
                val blocks = (template["blocks"] as? JsonArray).orEmpty()
                val idAndStart = blocks.mapNotNull { b -> (b as? JsonObject)?.let { obj -> obj.safeId("zoneId")?.let { it to obj.string("s").orEmpty() } } }
                idAndStart.forEach { (id, _) -> setFlag(id, "bound", true) }
                if (start < today) {
                    val lastPast = minOf(end, today.minusDays(1))
                    if (lastPast >= start) idAndStart.forEach { (id, _) -> markPast(id, lastPast.toString()) }
                }
                if (end >= today) {
                    var date = maxOf(start, today)
                    while (date <= end) {
                        val cycleIndex = if (length == 7) date.dayOfWeek.value - 1 else Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(start, date).toInt(), length)
                        if (cycleIndex == idx) {
                            idAndStart.forEach { (id, s) -> markFuture(id, date.toString(), s) }
                            break
                        }
                        date = date.plusDays(1)
                    }
                }
            }
        }
        val zoneUsage = JsonObject(usage.entries.associate { (id, value) -> id.toString() to JsonObject(value) })
        val updated = zones.map { z ->
            val id = z.safeId() ?: return@map z
            val x = usage[id]
            val isBound = x?.get("bound") == JsonPrimitive(true)
            val next = z.toMutableMap()
            if (z.truthy("off")) next["active"] = JsonPrimitive(false)
            else if (!isBound) next["active"] = JsonPrimitive(true)
            else if (x?.get("future") == JsonPrimitive(true)) { next.remove("actPin"); next["active"] = JsonPrimitive(true) }
            else next["active"] = JsonPrimitive(z.truthy("actPin"))
            next["bound"] = JsonPrimitive(isBound)
            JsonObject(next)
        }
        return JsonObject(state + mapOf("zones" to JsonArray(updated), "zoneUsage" to zoneUsage, "zoneUsageDay" to JsonPrimitive(todayString)))
    }

    private fun allocateId(snapshot: FlowSnapshot): Long? {
        val used = mutableSetOf<Long>()
        fun retain(value: Long?) { if (value != null && value > 0) used += value }
        snapshot.zones.forEach { retain(it.safeId()) }
        snapshot.tasks.forEach { task ->
            retain(task.raw.safeId("zoneId"))
            listOf("questDeferral", "completionDeferral").forEach { key ->
                val d = task.raw[key] as? JsonObject
                retain(d?.safeId("sourceZoneId")); retain(d?.safeId("targetZoneId"))
            }
        }
        (snapshot.data["planDayZones"] as? JsonObject)?.values?.forEach { v -> (v as? JsonArray)?.forEach { retain((it as? JsonObject)?.safeId("zoneId")) } }
        (snapshot.data["planDayOff"] as? JsonObject)?.values?.forEach { v -> (v as? JsonArray)?.forEach { retain(it.safeId()) } }
        (snapshot.data["dayTemplates"] as? JsonArray)?.forEach { v ->
            val o = v as? JsonObject ?: return@forEach
            (o["blocks"] as? JsonArray)?.forEach { retain((it as? JsonObject)?.safeId("zoneId")) }
            (o["tasks"] as? JsonArray)?.forEach { retain(((it as? JsonObject)?.get("task") as? JsonObject)?.safeId("zoneId")) }
        }
        retain(snapshot.data.safeId("lastZoneId"))
        var candidate = 1L
        while (candidate in used && candidate < MAX_SAFE_ID) candidate++
        return candidate.takeUnless { it in used }
    }

    private fun normalizeSlots(value: JsonElement?, priorValue: JsonElement?): JsonArray? {
        val slots = value as? JsonArray ?: return null
        val priorSlots = priorValue as? JsonArray ?: JsonArray(emptyList())
        val normalized = mutableListOf<JsonElement>()
        for ((index, element) in slots.withIndex()) {
            val slot = element as? JsonObject ?: return null
            val start = slot.string("s") ?: return null
            val end = slot.string("e") ?: return null
            val range = normalizeRange(start, end) ?: return null
            val prior = priorSlots.getOrNull(index) as? JsonObject ?: JsonObject(emptyMap())
            normalized += JsonObject(prior + slot + mapOf("s" to JsonPrimitive(range.first), "e" to JsonPrimitive(range.second)))
        }
        return JsonArray(normalized)
    }

    private fun normalizeRange(start: String, end: String): Pair<String, String>? {
        fun parse(value: String, allow24: Boolean): Int? {
            val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null; val minute = m.groupValues[2].toIntOrNull() ?: return null
            if (minute !in 0..59 || (allow24 && h == 24 && minute == 0)) return if (allow24 && h == 24 && minute == 0) 1440 else null
            if (h !in 0..23) return null
            return h * 60 + minute
        }
        val s = parse(start, false) ?: return null
        val e = if (end == "00:00") 1440 else parse(end, true) ?: return null
        if (e <= s || e > 1440) return null
        return formatMinute(s) to if (e == 1440) "24:00" else formatMinute(e)
    }
    private fun formatMinute(v: Int) = String.format(Locale.ROOT, "%02d:%02d", v / 60, v % 60)

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun applied(state: JsonObject): FlowCommandResult.Applied = FlowCommandResult.Applied(FlowSnapshot.fromData(state), emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun JsonObject.array(key: String) = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.safeId(key: String = "id"): Long? = this[key].safeId()
    private fun JsonElement?.safeId(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() && it >= 0 && it <= MAX_SAFE_ID.toDouble() && it % 1.0 == 0.0 }?.toLong()
    private fun JsonObject.truthy(key: String): Boolean = when (val v = this[key]) { null, JsonNull -> false; is JsonPrimitive -> v.booleanOrNull == true || (v.isString && v.content.isNotEmpty()) || (v.doubleOrNull?.let { it != 0.0 } ?: false); else -> true }
}
