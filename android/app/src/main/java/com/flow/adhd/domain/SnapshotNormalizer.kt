package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.*

/** Converts the two retired task stores before an atomic import. No IO or rewards. */
object SnapshotNormalizer {
    private const val MAX_ID = 9_007_199_254_740_991L

    fun normalize(validated: JsonObject, zone: ZoneId, now: Long): JsonObject {
        val tasks = objects(validated["tasks"], "tasks").toMutableList()
        var next = maxOf(1L, validated.id("nid")?.takeIf { it > 0 } ?: 1L,
            tasks.maxOfOrNull { it.id("id")!! + 1L } ?: 1L)
        require(next <= MAX_ID) { "No safe task ID remains" }
        fun allocate(): Long {
            require(next < MAX_ID) { "No safe task ID remains" }
            return next++
        }
        val items = validated["planItems"] as? JsonObject ?: JsonObject(emptyMap())
        val annotations = linkedMapOf<String, JsonElement>()
        for ((day, rawEntries) in items) {
            val entries = objects(rawEntries, "planItems.$day")
            val retained = mutableListOf<JsonElement>()
            for (item in entries) {
                val title = item.text("title") ?: error("Plan item title is required")
                if (item.flag("isEvent") || item.flag("isPrompt") || title.contains("Заплануй наступний тиждень")) {
                    retained += if (item.flag("isEvent")) item else JsonObject(item + mapOf("isPrompt" to JsonPrimitive(true), "done" to JsonPrimitive(false)))
                    continue
                }
                val date = date(day) ?: error("Invalid legacy planner date: $day")
                val time = clock(item.text("time"))
                val created = date.atStartOfDay(zone).toInstant().toEpochMilli()
                require(created >= 0) { "Legacy task date precedes the supported epoch" }
                val task = TaskFactory.create(allocate(), title, if (time == null) "simple" else "sched", created).toMutableMap()
                task["planDate"] = JsonPrimitive(day)
                if (time != null) {
                    task["schedDate"] = JsonPrimitive(day); task["schedTime"] = JsonPrimitive(time)
                    task["reminderEnabled"] = JsonPrimitive(true)
                }
                task["done"] = JsonPrimitive(item.flag("done"))
                if (item.flag("done")) {
                    task["doneDate"] = JsonPrimitive(day); task["completionCredited"] = JsonPrimitive(true)
                    task["completionCreditDate"] = JsonPrimitive(day)
                }
                tasks += JsonObject(task)
            }
            annotations[day] = JsonArray(retained)
        }
        val routines = objects(validated["recur"], "recur")
        val day = savedDay(validated.text("saveDate"), zone, now)
        for (routine in routines) {
            val id = routine.text("id") ?: error("Routine ID is required")
            val unit = routine.text("unit") ?: "check"
            val name = routine.text("nm") ?: error("Routine name is required")
            val negative = routine.flag("neg")
            if (id == "r1" && unit == "ml" && name == "Вода" && !negative) continue
            if (tasks.any { it.text("legacyRoutineId") == id }) continue
            val check = unit == "check"
            val type = if (negative) "negative" else if (check) "simple" else "counter"
            val task = TaskFactory.create(allocate(), name, type, 0).toMutableMap()
            val savedCount = maxOf(0.0, routine.number("val") ?: 0.0)
            val count = if (negative && check && routine.flag("done")) maxOf(1.0, savedCount) else savedCount
            val step = routine.number("step")?.takeIf { it > 0 } ?: when (unit) { "ml" -> 100.0; "kcal" -> 50.0; else -> 1.0 }
            task["legacyRoutineId"] = JsonPrimitive(id); task["repeat"] = JsonPrimitive("daily"); task["counterDay"] = JsonPrimitive(day)
            if (negative || !check) {
                task["counter"] = number(count); task["counterStep"] = number(step)
                task["counterUnit"] = JsonPrimitive(if (check) "count" else unit)
                task["counterTarget"] = JsonPrimitive(0); task["counterCreditedProgress"] = number(count)
                if (negative) task["negXp"] = number(routine.number("negXp") ?: 5.0)
            } else {
                task["done"] = JsonPrimitive(routine.flag("done")); task["completionCredited"] = JsonPrimitive(routine.flag("done"))
                if (routine.flag("done")) {
                    task["doneDate"] = JsonPrimitive(day); task["completionCreditDate"] = JsonPrimitive(day)
                }
            }
            tasks += JsonObject(task)
        }
        val taskArray = JsonArray(tasks)
        return JsonObject(validated + mapOf(
            "tasks" to taskArray, "nid" to JsonPrimitive(next), "planItems" to JsonObject(annotations),
            "recur" to JsonArray(emptyList()),
            "taskHistory" to TaskHistory.seedKnown(validated["taskHistory"] as? JsonArray ?: JsonArray(emptyList()), taskArray, zone),
        ))
    }

    private fun savedDay(value: String?, zone: ZoneId, now: Long): String {
        date(value)?.let { return it.toString() }
        // JavaScript Date.toDateString() is the legacy backup field, in English.
        if (value != null) {
            try { return LocalDate.parse(value, DateTimeFormatter.ofPattern("EEE MMM dd uuuu", Locale.ENGLISH)).toString() } catch (_: Exception) { }
            try { return Instant.parse(value).atZone(zone).toLocalDate().toString() } catch (_: Exception) { }
        }
        return Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()
    }
    private fun date(value: String?): LocalDate? = try {
        value?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }?.let(LocalDate::parse)
    } catch (_: Exception) { null }
    private fun clock(value: String?): String? {
        val match = value?.let { Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(it) } ?: return null
        val hour = match.groupValues[1].toInt(); val minute = match.groupValues[2].toInt()
        return if (hour < 24 && minute < 60) String.format(Locale.ROOT, "%02d:%02d", hour, minute) else null
    }
    private fun objects(value: JsonElement?, path: String): List<JsonObject> = when (value) {
        null -> emptyList()
        is JsonArray -> value.map { it as? JsonObject ?: error("$path must contain objects") }
        else -> error("$path must be an array")
    }
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.id(key: String) = FlowSnapshot.safeId(this[key], key)
    private fun number(value: Double): JsonPrimitive = if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
}
