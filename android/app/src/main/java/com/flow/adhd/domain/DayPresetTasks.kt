package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Pure native port of dayPresetTasks.ts. Only authored task fields cross the preset boundary. */
class DayPresetTasks(private val taskTypes: Set<String>) {
    data class AppendResult(val tasks: List<JsonObject>, val nid: Long)

    fun sanitize(entry: JsonObject): JsonObject? {
        val entryId = entry.string("id")?.takeIf { it.isNotBlank() } ?: return null
        val source = entry["task"] as? JsonObject ?: return null
        val sourceType = source.string("type")?.takeIf { it in taskTypes } ?: return null
        val sourceTitle = source.string("title") ?: return null
        val parsedTitle = (TaskTitleSchedule.parse(sourceTitle) as? TaskTitleSchedule.Result.Valid)?.value ?: return null
        val type = if (parsedTitle.time == null) sourceType else "sched"
        if (type !in taskTypes) return null
        val title = parsedTitle.title
        val definition = LinkedHashMap<String, JsonElement>()
        definition["title"] = JsonPrimitive(title)
        definition["type"] = JsonPrimitive(type)
        source.safeLong("zoneId")?.let { definition["zoneId"] = JsonPrimitive(it) }
        source.string("note")?.let { definition["note"] = JsonPrimitive(it) }
        source.string("folderId")?.let { definition["folderId"] = JsonPrimitive(it) }
        source.stringArray("tags")?.let { definition["tags"] = JsonArray(it.map { value -> JsonPrimitive(value) }) }
        if (type == "check") {
            val items = (source["items"] as? JsonArray).orEmpty().mapNotNull { item ->
                val text = (item as? JsonObject)?.string("text")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                buildJsonObject { put("text", text); put("done", false) }
            }
            definition["items"] = JsonArray(items)
        }
        if (type == "counter" || type == "negative" || source.boolean("counterEnabled") == true) {
            source["counterTarget"]?.let { value ->
                val n = value.safeLongOrNull()?.takeIf { it >= 0 } ?: return null
                definition["counterTarget"] = JsonPrimitive(n)
            }
            source.boolean("counterEnabled")?.let { definition["counterEnabled"] = JsonPrimitive(it) }
            source["counterStep"]?.let { value ->
                val n = value.safeLongOrNull()?.takeIf { it >= 1 } ?: return null
                definition["counterStep"] = JsonPrimitive(n)
            }
            source.string("counterUnit")?.takeIf { it.isNotEmpty() }?.let { definition["counterUnit"] = JsonPrimitive(it) }
        }
        if (type == "negative") source["negXp"]?.let { value ->
            val n = value.safeLongOrNull()?.takeIf { it >= 0 } ?: return null
            definition["negXp"] = JsonPrimitive(n)
        }
        if (type == "alarm") {
            val time = normalizeTime(source.string("alarmTime") ?: "") ?: return null
            definition["alarmTime"] = JsonPrimitive(time)
        }
        if (type == "sched") {
            val time = parsedTitle.time ?: normalizeTime(source.string("schedTime") ?: "") ?: return null
            definition["schedTime"] = JsonPrimitive(time)
            definition["reminderEnabled"] = JsonPrimitive(source.boolean("reminderEnabled") != false)
            val minutes = source.number("remindBeforeMinutes")?.coerceAtLeast(0.0) ?: 0.0
            definition["remindBeforeMinutes"] = if (minutes % 1.0 == 0.0 && minutes <= MAX_SAFE_JS_INTEGER) JsonPrimitive(minutes.toLong()) else JsonPrimitive(minutes)
        }
        if (type == "timewin") {
            definition["windowStart"] = JsonPrimitive(normalizeTime(source.string("windowStart")?.takeIf { it.isNotEmpty() } ?: "07:00") ?: return null)
            definition["windowEnd"] = JsonPrimitive(normalizeTime(source.string("windowEnd")?.takeIf { it.isNotEmpty() } ?: "09:00") ?: return null)
        }
        if (type == "ctx") definition["ctxTags"] = JsonArray(source.stringArray("ctxTags").orEmpty().map { value -> JsonPrimitive(value) })
        return JsonObject(mapOf("id" to JsonPrimitive(entryId), "task" to JsonObject(definition)))
    }

    fun materialize(entry: JsonObject, templateId: String, date: String, id: Long, zones: List<JsonObject>, at: Long): JsonObject? {
        val safe = sanitize(entry) ?: return null
        requireSafeId(id)
        val definition = safe.getValue("task") as JsonObject
        val type = definition.string("type")!!
        val zoneId = definition.safeLong("zoneId")
        val zone = zoneId?.let { wanted -> zones.firstOrNull { it.safeLong("id") == wanted } }
        val task = TaskFactory.create(id, definition.string("title")!!, type, at, zoneId, zone).toMutableMap()
        val key = "$templateId:${safe.string("id")}:$date"
        task["dayPresetKey"] = JsonPrimitive(key)
        task["planDate"] = JsonPrimitive(date)
        if (definition.containsKey("note")) task["note"] = definition.getValue("note")
        task["folderId"] = definition["folderId"] ?: JsonNull
        task["tags"] = definition["tags"] ?: JsonArray(emptyList())
        task["snoozeUntil"] = JsonPrimitive(0)
        task["trashed"] = JsonPrimitive(false)
        task["nextRepeatAt"] = JsonPrimitive(0)
        task["cntHit"] = JsonPrimitive(false)
        task["completionCredited"] = JsonPrimitive(false)
        task["firedSched"] = JsonPrimitive(false)
        task["firedPre"] = JsonPrimitive(false)
        for (field in listOf("doneDate", "doneAt", "completionCreditDate", "occurrenceId", "questDeferral", "completionDeferral", "counterCreditedProgress", "zoneOccurrenceKey")) task.remove(field)
        if (type == "check") task["items"] = (definition["items"] as? JsonArray)?.map { item ->
            val obj = item as JsonObject
            buildJsonObject { put("text", obj.getValue("text")); put("done", false) }
        }?.let(::JsonArray) ?: JsonArray(emptyList())
        if (type == "counter" || type == "negative" || definition.boolean("counterEnabled") == true) {
            val target = definition["counterTarget"] ?: task["counterTarget"]
            if (target == null) task.remove("counterTarget") else task["counterTarget"] = target
            task["counterStep"] = definition["counterStep"] ?: task["counterStep"] ?: JsonPrimitive(1)
            task["counterUnit"] = definition["counterUnit"] ?: task["counterUnit"] ?: JsonPrimitive("count")
            task["counterEnabled"] = JsonPrimitive(type == "counter" || (type != "negative" && definition.boolean("counterEnabled") == true))
        }
        if (type == "negative") task["negXp"] = definition["negXp"] ?: task["negXp"] ?: JsonPrimitive(5)
        if (type == "alarm") task["alarmTime"] = definition["alarmTime"]!!
        if (type == "sched") {
            task["schedDate"] = JsonPrimitive(date); task["schedTime"] = definition.getValue("schedTime")
            task["planDate"] = JsonPrimitive(date)
            task["reminderEnabled"] = definition.getValue("reminderEnabled"); task["remindBeforeMinutes"] = definition.getValue("remindBeforeMinutes")
        }
        if (type == "timewin") { task["windowStart"] = definition.getValue("windowStart"); task["windowEnd"] = definition.getValue("windowEnd"); task["completedToday"] = JsonPrimitive(false) }
        if (type == "zonelinked") task["zoneDoneToday"] = JsonPrimitive(false)
        if (type == "counter" || type == "negative") {
            task["counterDay"] = JsonPrimitive(date)
            task["counterCreditedProgress"] = JsonPrimitive(0)
        }
        if (type == "ctx") task["ctxTags"] = definition["ctxTags"] ?: JsonArray(emptyList())
        return JsonObject(task)
    }

    fun append(tasks: List<JsonObject>, nid: Long, templateId: String, entries: List<JsonObject>, date: String, zones: List<JsonObject>, at: Long): AppendResult {
        requireSafeId(nid)
        val keys = tasks.mapNotNull { it.string("dayPresetKey") }.toMutableSet()
        val usedIds = tasks.mapNotNull { it.safeLong("id") }.toMutableSet()
        var nextId = nid
        val additions = mutableListOf<JsonObject>()
        for (entry in entries) {
            val safe = sanitize(entry) ?: continue
            val key = "$templateId:${safe.string("id")}:$date"
            if (key in keys) continue
            while (nextId in usedIds) {
                if (nextId == MAX_SAFE_JS_INTEGER) throw IllegalStateException("No safe task ID remains")
                nextId++
            }
            if (nextId == MAX_SAFE_JS_INTEGER) throw IllegalStateException("No safe task ID remains")
            val occurrence = materialize(safe, templateId, date, nextId, zones, at) ?: continue
            additions += occurrence; keys += key; usedIds += nextId
            if (nextId < MAX_SAFE_JS_INTEGER) nextId++
        }
        return AppendResult(additions + tasks, nextId)
    }

    private fun requireSafeId(id: Long) { require(id in 0..MAX_SAFE_JS_INTEGER) { "Task ID must be a non-negative JavaScript-safe integer" } }
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.safeLong(key: String): Long? = (this[key] as? JsonPrimitive)?.safeLongOrNull()
    private fun JsonElement.safeLongOrNull(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        val n = p.longOrNull ?: return null
        return n.takeIf { it in -MAX_SAFE_JS_INTEGER..MAX_SAFE_JS_INTEGER }
    }
    private fun JsonObject.stringArray(key: String): List<String>? = (this[key] as? JsonArray)?.mapNotNull { el ->
        (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    private fun normalizeTime(value: String): String? {
        val match = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(value) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d:%02d".format(java.util.Locale.ROOT, hour, minute)
    }

    private companion object { const val MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L }
}
