package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

private typealias BackupCheck = (JsonElement, String) -> JsonElement

/** Strict validation for imported backups. This validates a detached JSON tree and
 * applies only the compatibility defaults present in the web backup validator. */
object BackupValidator {
    private val forbiddenKeys = setOf("__proto__", "prototype", "constructor")
    private val taskRepeats = setOf("none", "daily", "weekly", "weekdays", "weekend", "everyzone", "interval", "custom")
    fun validate(raw: JsonObject, taskTypes: Set<String>): JsonObject {
        rejectPrototypeKeys(raw, "файл")
        checkObject(raw, "файл")
        val fields = recognizedFields
        if (fields.none { raw.containsKey(it) }) fail("файл не містить даних Потоку")

        val result = linkedMapOf<String, JsonElement>()
        for ((key, value) in raw) {
            if (key !in fields) continue
            result[key] = validateTop(key, value, taskTypes)
        }

        for (key in duplicateIdCollections) {
            val records = result[key] as? JsonArray ?: continue
            val ids = records.mapIndexed { index, item ->
                val obj = item as JsonObject
                obj["id"] ?: fail("$key[$index].id")
            }
            if (ids.toSet().size != ids.size) fail("$key: повторені id")
        }
        if (!result.containsKey("preferences")) {
            val theme = (result["theme"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (theme == "dark" || theme == "light") {
                result["preferences"] = obj("themeMode" to JsonPrimitive(theme), "reduceMotion" to JsonPrimitive(false), "showGamification" to JsonPrimitive(true))
            }
        }
        return JsonObject(result)
    }

    private val recognizedFields = setOf(
        "formatVersion", "tasks", "taskHistory", "zones", "folders", "qnotes", "recur", "xp", "xpTotal", "level", "done", "streak", "negCount", "nid",
        "progressionVersion", "progressDays", "dailyInsightClaims", "unlocked", "energy", "lastEnergyXp", "dayXpGained", "xpDay", "priorities", "focusChips", "focusLog",
        "currentFocus", "notepad", "notepadFolder", "taskEditorDraft", "theme", "preferences", "rareEvents", "dayTemplates", "weekTemplates", "weekTplSeeded",
        "planItems", "planRestDays", "planSchedules", "planDayZones", "planDayOff", "planRules", "planDayLog", "saveDate",
    )
    private val duplicateIdCollections = setOf("tasks", "taskHistory", "zones", "qnotes", "folders", "recur", "dayTemplates", "weekTemplates", "rareEvents")

    private fun validateTop(key: String, value: JsonElement, taskTypes: Set<String>): JsonElement = when (key) {
        "formatVersion" -> oneOf(value, listOf(JsonPrimitive(2)), key)
        "tasks" -> array(value, key) { e, p -> task(e, p, taskTypes) }
        "taskHistory" -> array(value, key) { e, p -> shape(e, p, required = setOf("id", "occurrenceId", "taskId", "taskCreated", "title", "type", "kind", "at", "date", "time"), fields = mapOf(
            "id" to ::string, "occurrenceId" to ::string, "taskId" to ::id, "taskCreated" to ::string, "title" to ::string,
            "type" to { v, path -> oneOf(v, taskTypes.map { JsonPrimitive(it) }, path) }, "kind" to { v, path -> oneOf(v, listOf("completed", "reopened"), path) },
            "at" to ::number, "date" to ::string, "time" to ::string, "sequence" to ::id, "zoneId" to ::nullableId, "counter" to ::number,
            "items" to { v, path -> array(v, path) { x, xp -> shape(x, xp, setOf("text", "done"), mapOf("text" to ::string, "done" to ::bool, "credited" to ::bool)) } },
        )) }
        "zones" -> array(value, key) { e, p -> shape(e, p, setOf("id", "nm"), mapOf("id" to ::id, "nm" to ::string, "color" to ::string, "slots" to { v, path -> array(v, path) { x, xp -> shape(x, xp, setOf("s", "e"), mapOf("s" to ::string, "e" to ::string)) } }, "desc" to ::string, "prio" to ::number, "active" to ::bool, "off" to ::bool, "bound" to ::bool, "actPin" to ::bool), mapOf("color" to JsonPrimitive("#4f8ef7"), "slots" to JsonArray(emptyList()))) }
        "folders" -> array(value, key) { e, p -> shape(e, p, setOf("id", "nm"), mapOf("id" to ::string, "nm" to ::string, "ico" to ::string), mapOf("ico" to JsonPrimitive("📁"))) }
        "qnotes" -> array(value, key) { e, p -> shape(e, p, setOf("id", "txt"), mapOf("id" to ::id, "txt" to ::string, "folder" to ::string, "time" to ::string, "date" to ::string), mapOf("folder" to JsonPrimitive("general"), "time" to JsonPrimitive(""), "date" to JsonPrimitive(""))) }
        "recur" -> array(value, key) { e, p -> shape(e, p, setOf("id", "nm"), mapOf("id" to ::string, "nm" to ::string, "color" to ::string, "unit" to { v, path -> oneOf(v, listOf("check", "count", "ml", "min", "kcal"), path) }, "val" to ::number, "done" to ::bool, "step" to ::number, "neg" to ::bool, "negXp" to ::number), mapOf("color" to JsonPrimitive("#4f8ef7"), "unit" to JsonPrimitive("check"), "val" to JsonPrimitive(0), "done" to JsonPrimitive(false), "step" to JsonPrimitive(1), "neg" to JsonPrimitive(false), "negXp" to JsonPrimitive(5))) }
        "xp", "xpTotal", "level", "done", "streak", "negCount", "lastEnergyXp", "dayXpGained" -> number(value, key)
        "nid" -> id(value, key)
        "progressionVersion" -> oneOf(value, listOf(JsonPrimitive(0), JsonPrimitive(1), JsonPrimitive(2)), key)
        "progressDays", "unlocked", "focusChips" -> strings(value, key)
        "dailyInsightClaims" -> array(value, key) { e, p -> shape(e, p, setOf("day", "cardId", "sequence", "earnedAt"), mapOf("day" to ::string, "cardId" to { v, path -> oneOf(v, (1..100).map { JsonPrimitive(it) }, path) }, "sequence" to ::id, "earnedAt" to ::number)) }
        "energy" -> oneOf(value, (0..5).map { JsonPrimitive(it) }, key)
        "xpDay", "currentFocus", "notepad", "notepadFolder", "taskEditorDraft", "saveDate" -> string(value, key)
        "priorities" -> array(value, key) { e, p -> if (e == JsonNull) e else id(e, p) }
        "focusLog" -> array(value, key) { e, p -> shape(e, p, setOf("val", "start"), mapOf("val" to ::string, "start" to ::string, "end" to { v, path -> nullable(v, path, ::string) }, "date" to ::string, "time" to ::string, "dur" to ::number), mapOf("end" to JsonNull, "date" to JsonPrimitive(""), "time" to JsonPrimitive(""))) }
        "theme" -> oneOf(value, listOf("light", "dark"), key)
        "preferences" -> shape(value, key, emptySet(), mapOf("themeMode" to { v, p -> oneOf(v, listOf("system", "light", "dark"), p) }, "notificationTheme" to { v, p -> oneOf(v, listOf("system", "light", "dark"), p) }, "reduceMotion" to ::bool, "showGamification" to ::bool, "waterSeedRetired" to ::bool))
        "rareEvents" -> array(value, key) { e, p -> shape(e, p, setOf("id", "nm"), mapOf("id" to ::id, "nm" to ::string, "val" to ::number, "dir" to { v, path -> oneOf(v, listOf("up", "down"), path) }, "unit" to ::string), mapOf("val" to JsonPrimitive(0), "dir" to JsonPrimitive("up"), "unit" to JsonPrimitive(""))) }
        "dayTemplates" -> array(value, key) { e, p -> shape(e, p, setOf("id", "name"), mapOf(
            "id" to ::string, "name" to ::string,
            "blocks" to { v, path -> array(v, path) { x, xp -> block(x, xp) } },
            "tasks" to { v, path -> array(v, path) { x, xp -> shape(x, xp, setOf("id", "task"), mapOf(
                "id" to ::string, "task" to { task, taskPath -> templateTask(task, taskPath, taskTypes) },
            )) } },
        ), mapOf("blocks" to JsonArray(emptyList()))) }
        "weekTemplates" -> array(value, key) { e, p -> shape(e, p, setOf("id", "name"), mapOf("id" to ::string, "name" to ::string, "days" to { v, path -> array(v, path) { x, xp -> shape(x, xp, emptySet(), mapOf("tplId" to ::string, "rest" to ::bool), mapOf("tplId" to JsonPrimitive(""), "rest" to JsonPrimitive(false))) } }), mapOf("days" to JsonArray(emptyList()))) }
        "weekTplSeeded" -> bool(value, key)
        "planItems" -> stringMap(value, key) { v, path -> array(v, path) { x, xp -> shape(x, xp, setOf("title"), mapOf("title" to ::string, "done" to ::bool, "isEvent" to ::bool, "isPrompt" to ::bool, "time" to ::string), mapOf("done" to JsonPrimitive(false))) } }
        "planRestDays" -> stringMap(value, key, ::bool)
        "planSchedules" -> stringMap(value, key, ::string)
        "planDayZones" -> stringMap(value, key) { v, path -> array(v, path) { x, xp -> block(x, xp) } }
        "planDayOff" -> stringMap(value, key) { v, path -> array(v, path, ::id) }
        "planRules" -> array(value, key) { e, p -> shape(e, p, setOf("id", "start", "end"), mapOf("id" to ::string, "name" to ::string, "start" to ::string, "end" to ::string, "days" to { v, path -> array(v, path) { x, xp -> shape(x, xp, emptySet(), mapOf("tplId" to ::string, "rest" to ::bool), mapOf("tplId" to JsonPrimitive(""), "rest" to JsonPrimitive(false))) } }), mapOf("name" to JsonPrimitive(""), "days" to JsonArray(emptyList()))) }
        "planDayLog" -> stringMap(value, key) { v, path -> shape(v, path, emptySet(), mapOf("focus" to ::strings, "energy" to ::number, "routineDone" to { x, xp -> stringMap(x, xp) { y, yp -> if ((y as? JsonPrimitive)?.booleanOrNull != null && !(y as JsonPrimitive).isString) y else string(y, yp) } }, "priorities" to { x, xp -> array(x, xp) { z, _ -> z } }, "tasksDone" to ::number, "note" to ::string), mapOf("focus" to JsonArray(emptyList()), "energy" to JsonPrimitive(0), "routineDone" to JsonObject(emptyMap()), "priorities" to JsonArray(emptyList()), "tasksDone" to JsonPrimitive(0))) }
        else -> fail(key)
    }

    private fun task(value: JsonElement, path: String, taskTypes: Set<String>): JsonElement {
        val fields = commonTaskFields(taskTypes)
        fields["id"] = ::id
        val checked = shape(value, path, setOf("id", "title"), fields, taskDefaults()) as JsonObject
        for (key in listOf("quickReminderAt", "quickReminderFiredAt")) {
            val value = (checked[key] as? JsonPrimitive)?.doubleOrNull ?: continue
            if (value < 1.0 || value > 8_640_000_000_000_000.0 || value % 1.0 != 0.0 || checked["type"] != JsonPrimitive("simple")) fail("$path.$key")
        }
        return checked
    }

    private fun templateTask(value: JsonElement, path: String, taskTypes: Set<String>): JsonElement {
        val source = checkObject(value, path)
        if ((source["title"] as? JsonPrimitive)?.let { it.isString } != true || (source["type"] as? JsonPrimitive)?.let { it.isString && it.content in taskTypes } != true) fail(path)
        val fields = commonTaskFields(taskTypes)
        val withId = JsonObject(source + ("id" to JsonPrimitive(0)))
        val checked = shape(withId, path, setOf("id", "title"), fields + ("id" to ::id), taskDefaults()) as JsonObject
        return JsonObject(checked - "id")
    }

    private fun commonTaskFields(taskTypes: Set<String>): MutableMap<String, BackupCheck> = linkedMapOf(
        "legacyRoutineId" to ::string, "dayPresetKey" to ::string, "title" to ::string,
        "type" to { v, p -> oneOf(v, taskTypes.map { JsonPrimitive(it) }, p) }, "done" to ::bool, "someday" to ::bool,
        "zoneId" to ::nullableId, "zoneColor" to { v, p -> nullable(v, p, ::string) }, "zoneName" to { v, p -> nullable(v, p, ::string) }, "folderId" to { v, p -> nullable(v, p, ::string) },
        "expanded" to ::bool, "created" to ::string, "trashed" to ::bool, "doneDate" to ::string, "doneAt" to ::string, "completionCredited" to ::bool, "completionCreditDate" to ::string, "snoozeUntil" to ::number,
        "quickReminderAt" to ::number, "quickReminderFiredAt" to ::number,
        "questDeferral" to ::questDeferral, "completionDeferral" to ::questDeferral, "occurrenceId" to ::string,
        "items" to { v, p -> array(v, p) { x, xp -> shape(x, xp, setOf("text"), mapOf("text" to ::string, "done" to ::bool, "credited" to ::bool), mapOf("done" to JsonPrimitive(false))) } },
        "counter" to ::number, "counterTarget" to ::number, "cntHit" to ::bool, "negXp" to ::number, "note" to ::string, "alarmTime" to ::string, "alarmFired" to ::bool,
        "counterEnabled" to ::bool, "counterStep" to ::number, "counterUnit" to { v, p -> oneOf(v, listOf("count", "ml", "min", "kcal"), p) }, "counterCreditedProgress" to ::number, "counterDay" to ::string,
        "schedDate" to ::string, "schedTime" to ::string, "firedSched" to ::bool, "firedPre" to ::bool, "reminderEnabled" to ::bool, "remindBeforeMinutes" to ::number, "windowStart" to ::string, "windowEnd" to ::string, "completedToday" to ::bool,
        "ctxTags" to ::strings, "zoneDoneToday" to ::bool, "zoneOccurrenceKey" to ::string, "repeat" to { v, p -> oneOf(v, taskRepeats.map { JsonPrimitive(it) }, p) },
        "repeatDays" to ::booleans, "tags" to ::strings, "repeatMs" to ::number, "nextRepeatAt" to ::number, "repeatInterval" to ::number,
        "repeatUnit" to { v, p -> oneOf(v, listOf("sec", "min", "hour", "day", "week", "month"), p) }, "planDate" to ::string, "trashedAt" to { v, p -> nullable(v, p, ::number) },
    )
    private fun taskDefaults() = mapOf("type" to JsonPrimitive("simple"), "done" to JsonPrimitive(false), "someday" to JsonPrimitive(false), "created" to JsonPrimitive("1970-01-01T00:00:00.000Z"), "repeat" to JsonPrimitive("none"), "repeatDays" to JsonArray(List(7) { JsonPrimitive(false) }), "tags" to JsonArray(emptyList()))

    private fun questDeferral(value: JsonElement, path: String): JsonElement {
        val res = shape(value, path, setOf("kind", "requestedAt", "sourceZoneId", "targetZoneId", "startAt"), mapOf("kind" to { v, p -> oneOf(v, listOf("next-zone", "not-today", "time"), p) }, "requestedAt" to ::number, "sourceZoneId" to ::id, "targetZoneId" to ::id, "startAt" to ::number, "endAt" to ::number)) as JsonObject
        val kind = (res["kind"] as JsonPrimitive).content
        if (kind == "next-zone") {
            val end = (res["endAt"] as? JsonPrimitive)?.doubleOrNull ?: fail("$path.endAt")
            val start = (res["startAt"] as JsonPrimitive).doubleOrNull!!
            if (end <= start) fail("$path.endAt")
        } else {
            if (res.containsKey("endAt")) fail("$path.endAt")
            if (kind == "time") {
                val start = (res["startAt"] as JsonPrimitive).doubleOrNull!!
                val requested = (res["requestedAt"] as JsonPrimitive).doubleOrNull!!
                if (start <= requested) fail("$path.startAt")
            }
        }
        return res
    }

    private fun block(value: JsonElement, path: String): JsonElement = shape(value, path, setOf("zoneId", "s", "e"), mapOf("zoneId" to ::id, "s" to ::string, "e" to ::string, "ov" to ::bool))

    private fun shape(
        value: JsonElement,
        path: String,
        required: Set<String>,
        fields: Map<String, BackupCheck>,
        defaults: Map<String, JsonElement> = emptyMap(),
    ): JsonElement {
        val source = checkObject(value, path)
        for (key in required) if (!source.containsKey(key)) fail("$path.$key")
        val result = linkedMapOf<String, JsonElement>()
        result.putAll(defaults)
        result.putAll(source)
        for ((key, check) in fields) if (source.containsKey(key)) result[key] = check(source.getValue(key), "$path.$key")
        return JsonObject(result)
    }

    private fun array(value: JsonElement, path: String, check: BackupCheck): JsonElement {
        val source = value as? JsonArray ?: fail(path)
        return JsonArray(source.mapIndexed { i, item -> check(item, "$path[$i]") })
    }

    private fun strings(value: JsonElement, path: String): JsonElement = array(value, path, ::string)
    private fun booleans(value: JsonElement, path: String): JsonElement = array(value, path, ::bool)

    private fun stringMap(value: JsonElement, path: String, check: BackupCheck): JsonElement {
        val source = checkObject(value, path)
        val result = linkedMapOf<String, JsonElement>()
        for ((key, item) in source) result[key] = check(item, "$path.$key")
        return JsonObject(result)
    }

    private fun checkObject(value: JsonElement, path: String): JsonObject {
        val obj = value as? JsonObject ?: fail(path)
        if (obj.keys.any { it in forbiddenKeys }) fail(path)
        return obj
    }

    private fun rejectPrototypeKeys(value: JsonElement, path: String) {
        when (value) {
            is JsonObject -> {
                if (value.keys.any { it in forbiddenKeys }) fail(path)
                value.forEach { (key, child) -> rejectPrototypeKeys(child, "$path.$key") }
            }
            is JsonArray -> value.forEachIndexed { index, child -> rejectPrototypeKeys(child, "$path[$index]") }
            else -> Unit
        }
    }

    private fun string(value: JsonElement, path: String): JsonElement =
        if ((value as? JsonPrimitive)?.isString == true) value else fail(path)

    private fun bool(value: JsonElement, path: String): JsonElement {
        val primitive = value as? JsonPrimitive ?: fail(path)
        if (primitive.isString || primitive.booleanOrNull == null) fail(path)
        return value
    }

    private fun number(value: JsonElement, path: String): JsonElement {
        val primitive = value as? JsonPrimitive ?: fail(path)
        val number = primitive.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: fail(path)
        return if (number % 1.0 == 0.0 && number >= Long.MIN_VALUE.toDouble() && number < Long.MAX_VALUE.toDouble())
            JsonPrimitive(number.toLong()) else JsonPrimitive(number)
    }

    private fun id(value: JsonElement, path: String): JsonElement {
        val primitive = value as? JsonPrimitive ?: fail(path)
        val n = if (primitive.isString) null else primitive.doubleOrNull
        if (n == null || !n.isFinite() || n < 0 || n > 9_007_199_254_740_991.0 || n % 1.0 != 0.0) fail(path)
        return JsonPrimitive(n.toLong())
    }

    private fun nullable(value: JsonElement, path: String, check: BackupCheck): JsonElement = if (value == JsonNull) value else check(value, path)
    private fun nullableId(value: JsonElement, path: String): JsonElement = nullable(value, path, ::id)

    private fun oneOf(value: JsonElement, options: List<JsonElement>, path: String): JsonElement {
        val number = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        return options.firstOrNull { option -> option == value || (number != null &&
            (option as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull == number) } ?: fail(path)
    }

    private fun oneOf(value: JsonElement, options: Collection<String>, path: String): JsonElement =
        if ((value as? JsonPrimitive)?.let { it.isString && it.content in options } == true) value else fail(path)

    private fun obj(vararg entries: Pair<String, JsonElement>) = JsonObject(mapOf(*entries))
    private fun fail(path: String): Nothing = throw SnapshotFormatException("Некоректні дані резервної копії: $path")

}
