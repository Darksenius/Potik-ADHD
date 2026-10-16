package com.flow.adhd.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Pure projection of effective planner blocks and rest-day rules. */
object PlannerProjection {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE

    fun dayBlocks(state: JsonObject, day: String): List<JsonObject> {
        require(parseDay(day) != null) { "day must be a valid YYYY-MM-DD date" }
        val manual = (state["planDayZones"] as? JsonObject)?.get(day)
        if (manual != null && manual != JsonNull) return manual.blocks("planDayZones.$day")

        val entry = ruleEntry(ruleFor(state, day), day) ?: return emptyList()
        val templateId = entry.string("tplId")?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val template = (state["dayTemplates"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.string("id") == templateId } ?: return emptyList()
        val blocks = template["blocks"] as? JsonArray ?: return emptyList()
        return blocks.mapIndexedNotNull { index, value ->
            val block = value as? JsonObject ?: throw IllegalArgumentException("dayTemplates.$templateId.blocks[$index] must be an object")
            buildJsonObject {
                block["zoneId"]?.let { put("zoneId", it) }
                block["s"]?.let { put("s", it) }
                block["e"]?.let { put("e", it) }
            }
        }
    }

    fun isRest(state: JsonObject, day: String): Boolean {
        require(parseDay(day) != null) { "day must be a valid YYYY-MM-DD date" }
        val manual = (state["planRestDays"] as? JsonObject)?.get(day)
        if (manual != null && manual != JsonNull) return truthy(manual)
        return ruleEntry(ruleFor(state, day), day)?.get("rest")?.let(::truthy) ?: false
    }

    /** The last stored rule whose inclusive range contains the validated day. */
    fun ruleFor(state: JsonObject, day: String): JsonObject? {
        val date = parseDay(day) ?: return null
        return (state["planRules"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.lastOrNull { rule ->
                val start = rule.string("start")?.let(::parseDay) ?: return@lastOrNull false
                val end = rule.string("end")?.let(::parseDay) ?: return@lastOrNull false
                !date.isBefore(start) && !date.isAfter(end)
            }
    }

    /** Returns the selected cycle entry; empty cycles and invalid dates have no entry. */
    fun ruleEntry(rule: JsonObject?, day: String): JsonObject? {
        rule ?: return null
        val date = parseDay(day) ?: return null
        val start = rule.string("start")?.let(::parseDay) ?: return null
        val days = rule["days"] as? JsonArray ?: return null
        if (days.isEmpty()) return null
        val index = if (days.size == 7) date.dayOfWeek.value - 1 else {
            val delta = ChronoUnit.DAYS.between(start, date)
            Math.floorMod(delta, days.size.toLong()).toInt()
        }
        return days.getOrNull(index) as? JsonObject
    }

    private fun JsonElement.blocks(path: String): List<JsonObject> {
        val array = this as? JsonArray ?: throw IllegalArgumentException("$path must be an array")
        return array.mapIndexed { index, element ->
            element as? JsonObject ?: throw IllegalArgumentException("$path[$index] must be an object")
        }
    }

    private fun parseDay(value: String): LocalDate? {
        if (!DATE_KEY.matches(value)) return null
        return try { LocalDate.parse(value, dateFormat).takeIf { it.format(dateFormat) == value } }
        catch (_: Exception) { null }
    }

    private fun truthy(value: JsonElement): Boolean = when (value) {
        JsonNull -> false
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.booleanOrNull != null -> value.booleanOrNull == true
            else -> value.doubleOrNull?.let { it != 0.0 } ?: false
        }
        else -> true
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content

    private val DATE_KEY = Regex("\\d{4}-\\d{2}-\\d{2}")
}
