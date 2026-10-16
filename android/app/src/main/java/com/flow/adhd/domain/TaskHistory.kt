package com.flow.adhd.domain

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Pure helpers for the append-only history stored beside the existing task snapshot. */
object TaskHistory {
    private val dayPattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val timePattern = Regex("(\\d{1,2}):(\\d{2})")
    private val dayFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("uk"))

    fun identity(task: JsonObject): String = "${task.numberText("id") ?: "undefined"}@${task.string("created") ?: "undefined"}"

    fun occurrenceId(task: JsonObject, at: Long, zoneKey: String? = null, zone: ZoneId): String {
        task.string("occurrenceId")?.takeIf { it.isNotEmpty() }?.let { return it }
        val origin = task.objectValue("questDeferral")?.number("requestedAt")?.toLong() ?: at
        val day = task.string("counterDay")?.takeIf { it.isNotEmpty() }
            ?: task.string("planDate")?.takeIf { it.isNotEmpty() }
            ?: dayAt(origin, zone)
        val repeat = task.string("repeat")
        val suffix = when {
            repeat == "everyzone" -> "zone:${task.string("zoneOccurrenceKey")?.takeIf { it.isNotEmpty() }
                ?: zoneKey?.takeIf { it.isNotEmpty() } ?: "$day:${task.numberText("zoneId") ?: "0"}"}"
            repeat == "interval" -> "interval:$at"
            !repeat.isNullOrEmpty() && repeat != "none" -> "day:$day"
            else -> "once"
        }
        return "${identity(task)}|$suffix"
    }

    fun event(
        task: JsonObject, kind: String, at: Long, occurrenceId: String, sequence: Long,
        zone: ZoneId, date: String? = null, time: String? = null,
    ): JsonObject {
        require(kind == "completed" || kind == "reopened")
        val zoned = Instant.ofEpochMilli(at).atZone(zone)
        val eventDate = date ?: zoned.toLocalDate().format(dayFormatter)
        val eventTime = time ?: zoned.toLocalTime().format(timeFormatter)
        return buildJsonObject {
            put("id", "$occurrenceId#$sequence:$at:$kind")
            put("occurrenceId", occurrenceId)
            task["id"]?.let { put("taskId", it) }
            task["created"]?.let { put("taskCreated", it) }
            task["title"]?.let { put("title", it) }
            task["type"]?.let { put("type", it) }
            put("kind", kind)
            put("sequence", sequence)
            put("at", at)
            put("date", eventDate)
            put("time", eventTime)
            if ("zoneId" in task) put("zoneId", task.getValue("zoneId"))
            if ("counter" in task) put("counter", task.getValue("counter"))
        }
    }

    fun nextSequence(history: JsonArray): Long {
        val max = history.maxOfOrNull { (it as? JsonObject)?.number("sequence") ?: 0.0 } ?: -1.0
        return max.toLong() + 1L
    }

    /** Seeds only persisted done facts, keeping all existing event JSON and unknown fields intact. */
    fun seedKnown(history: JsonArray, tasks: JsonArray, zone: ZoneId): JsonArray {
        val result = history.toMutableList()
        val known = history.mapNotNull { (it as? JsonObject)?.string("occurrenceId") }.toMutableSet()
        var sequence = nextSequence(history)
        tasks.forEach { element ->
            val task = element as? JsonObject ?: return@forEach
            if (task.boolean("done") != true || task.string("type") == "negative") return@forEach
            val date = task.string("doneDate") ?: return@forEach
            val savedTime = task.string("doneAt")?.takeIf { it.isNotEmpty() } ?: "00:00"
            val at = localDateTimeMillis(date, savedTime, zone) ?: return@forEach
            val occurrence = occurrenceId(task, at, task.string("zoneOccurrenceKey"), zone)
            if (known.add(occurrence)) {
                result.add(event(task, "completed", at, occurrence, sequence++, zone, date, task.string("doneAt") ?: ""))
            }
        }
        return JsonArray(result)
    }

    /** The highest sequence wins, then the later timestamp, then reopened on an exact tie. */
    fun completedOccurrences(history: JsonArray, tasks: JsonArray = JsonArray(emptyList()), zone: ZoneId): List<JsonObject> {
        val latest = linkedMapOf<String, JsonObject>()
        seedKnown(history, tasks, zone).forEach { element ->
            val event = element as? JsonObject ?: return@forEach
            val occurrence = event.string("occurrenceId") ?: return@forEach
            val prior = latest[occurrence]
            if (prior == null || event.number("sequence", 0.0) > prior.number("sequence", 0.0)
                || event.number("sequence", 0.0) == prior.number("sequence", 0.0)
                && (event.number("at", 0.0) > prior.number("at", 0.0)
                    || event.number("at", 0.0) == prior.number("at", 0.0) && event.string("kind") == "reopened")) {
                latest[occurrence] = event
            }
        }
        return latest.values.filter { it.string("kind") == "completed" }
    }

    fun remapIdentity(value: String, taskId: Long, created: String, mappedId: Long): String {
        val prefix = "$taskId@$created|"
        return if (value.startsWith(prefix)) "$mappedId@$created|${value.substring(prefix.length)}" else value
    }

    /** Matches Date(day + 'T' + normalizedTime) with JavaScript local DST resolution. */
    fun localDateTimeMillis(day: String, time: String, zone: ZoneId): Long? {
        if (!dayPattern.matches(day)) return null
        val match = timePattern.matchEntire(time) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return try {
            val date = LocalDate.parse(day, dayFormatter)
            LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            null
        } catch (_: ArithmeticException) {
            null
        }
    }

    private fun dayAt(at: Long, zone: ZoneId): String = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().format(dayFormatter)
    private fun JsonObject.objectValue(name: String): JsonObject? = this[name] as? JsonObject
    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.boolean(name: String): Boolean? = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.number(name: String): Double? =
        (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.number(name: String, default: Double): Double = number(name) ?: default
    private fun JsonObject.numberText(name: String): String? = number(name)?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() }
}
