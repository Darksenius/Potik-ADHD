package com.flow.adhd.domain

import java.time.ZoneId
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Pure port of saveTask; storage and editor presentation remain outside the reducer. */
object TaskEditorReducer {
    private val repeatUnitMs = mapOf(
        "sec" to 1_000L, "min" to 60_000L, "hour" to 3_600_000L,
        "day" to 86_400_000L, "week" to 604_800_000L, "month" to 2_592_000_000L,
    )

    fun reduce(
        snapshot: FlowSnapshot,
        command: FlowCommand,
        zone: ZoneId,
        knownTypes: Set<String>,
        now: Long = command.activityAt,
        activeZoneId: Long? = null,
    ): FlowCommandResult {
        val create = command as? FlowCommand.CreateTask
        val update = command as? FlowCommand.UpdateTask
        if (create == null && update == null) return rejected("wrong-command", "Expected a task create or update command")
        val payload = create?.payload ?: update!!.payload
        val title = payload.string("title")?.trim()
            ?: return rejected("invalid-title", "Task title must be a string")
        if (title.isEmpty()) return rejected("empty-title", "Task title must not be blank")
        val type = payload.string("type")
            ?: return rejected("invalid-type", "Task type must be a string")
        if (type !in knownTypes) return rejected("unknown-type", "Task type is not supported")
        validatePayload(payload)?.let { return rejected("invalid-field", it) }
        if (command.activityAt !in 0..MAX_DATE_MILLIS || now !in 0..MAX_DATE_MILLIS) {
            return rejected("invalid-time", "Activity time is invalid")
        }

        return try {
            val isNew = create != null
            val oldTask = if (isNew) null else snapshot.tasks.firstOrNull { it.id == update!!.taskId }
            if (!isNew && oldTask == null) return applied(snapshot)

            val taskId = if (isNew) nextId(snapshot) else update!!.taskId
            if (taskId == null) return rejected("id-exhausted", "No safe task ID remains")
            val rawBefore = oldTask?.raw ?: TaskFactory.create(taskId, title, type, command.activityAt)
            val oldType = oldTask?.type ?: type
            val next = rawBefore.toMutableMap()
            val priorPlanDate = rawBefore.string("planDate")
            val priorRepeat = rawBefore.string("repeat") ?: "none"
            val priorZoneId = rawBefore.safeLong("zoneId")
            val priorRepeatDays = rawBefore["repeatDays"]
            val priorSchedDate = rawBefore.string("schedDate")
            val priorSchedTime = rawBefore.string("schedTime")
            val priorAlarmTime = rawBefore.string("alarmTime")
            val priorWindowStart = rawBefore.string("windowStart")
            val priorWindowEnd = rawBefore.string("windowEnd")
            val priorRepeatMs = rawBefore.safeDouble("repeatMs")
            val priorRepeatInterval = rawBefore.safeDouble("repeatInterval")
            val priorRepeatUnit = rawBefore.string("repeatUnit")

            next["title"] = JsonPrimitive(title)
            if (type != oldType) {
                next["type"] = JsonPrimitive(type)
                if (type == "check" && next["items"] == null) next["items"] = JsonArray(emptyList())
                if (type == "counter" && next["counter"] == null) {
                    next["counter"] = JsonPrimitive(0)
                    next["counterTarget"] = JsonPrimitive(10)
                }
                if (type == "alarm" && !rawBefore.hasText("alarmTime")) next["alarmTime"] = JsonPrimitive("")
                if (type == "note" && !rawBefore.hasText("note")) next["note"] = JsonPrimitive("")
                if (type == "ctx" && next["ctxTags"] == null) next["ctxTags"] = JsonArray(emptyList())
                if (type == "negative") next["negXp"] = JsonPrimitive(5)
            }

            var zoneId = if (payload.containsKey("zoneId")) payload.safeLong("zoneId") else null
            if (type == "zonelinked" && (zoneId == null || zoneId == 0L)) {
                zoneId = activeZoneId?.takeIf { it != 0L }
            }
            val zoneRecord = zoneId?.let { id -> snapshot.zones.firstOrNull { it.safeLong("id") == id } }
            next["zoneId"] = zoneId?.let(::JsonPrimitive) ?: JsonNull
            next["zoneColor"] = zoneRecord?.get("color")?.takeIf { it.stringOrNull()?.isNotBlank() == true } ?: JsonNull
            next["zoneName"] = zoneRecord?.get("nm")?.takeIf { it.stringOrNull()?.isNotBlank() == true } ?: JsonNull
            next["folderId"] = payload["folderId"]?.takeUnless { it == JsonNull } ?: JsonNull
            if (payload.containsKey("planDate")) {
                val plan = payload["planDate"]?.stringOrNull()?.takeIf { it.isNotEmpty() }
                if (plan == null) next.remove("planDate") else next["planDate"] = JsonPrimitive(plan)
            }
            val planDate = next["planDate"].stringOrNull()
            if (type != oldType || planDate != priorPlanDate) next["snoozeUntil"] = JsonPrimitive(0)
            if (type != "sched") {
                next.remove("schedDate")
                next.remove("schedTime")
            }

            val repeat = payload.string("repeat")?.takeIf { it.isNotEmpty() } ?: "none"
            next["repeat"] = JsonPrimitive(repeat)
            val repeatDays = payload["repeatDays"] as? JsonArray
            if (repeatDays != null) next["repeatDays"] = JsonArray(repeatDays.toList())
            val nextRepeatDays = next["repeatDays"]
            if (type != oldType || repeat != priorRepeat || zoneId != priorZoneId || planDate != priorPlanDate || nextRepeatDays != priorRepeatDays) {
                next["alarmFired"] = JsonPrimitive(false)
                next["firedSched"] = JsonPrimitive(false)
                next["firedPre"] = JsonPrimitive(false)
                next.remove("zoneOccurrenceKey")
            }
            if (type != "alarm") next.remove("alarmTime")
            if (type != "timewin") {
                next.remove("windowStart")
                next.remove("windowEnd")
            }
            if (repeat != "interval") {
                next.remove("repeatMs")
                next.remove("repeatInterval")
                next.remove("repeatUnit")
                next["nextRepeatAt"] = JsonPrimitive(0)
            }

            if (type == "sched") {
                val schedDate = payload.string("schedDate")?.takeIf { it.isNotEmpty() } ?: ""
                val schedTime = normalizeTime(payload.string("schedTime")) ?: ""
                next["schedDate"] = JsonPrimitive(schedDate)
                next["schedTime"] = JsonPrimitive(schedTime)
                if (schedDate != priorSchedDate || schedTime != priorSchedTime
                || (payload.boolean("reminderEnabled") != false) != (rawBefore.boolean("reminderEnabled") != false)
                    || payload.safeDouble("remindBeforeMinutes") != rawBefore.safeDouble("remindBeforeMinutes")) {
                    next["firedSched"] = JsonPrimitive(false)
                    next["firedPre"] = JsonPrimitive(false)
                    next["snoozeUntil"] = JsonPrimitive(0)
                }
                if (schedDate.isNotEmpty()) next["planDate"] = JsonPrimitive(schedDate) else next.remove("planDate")
            }
            if (payload.containsKey("reminderEnabled")) next["reminderEnabled"] = payload.getValue("reminderEnabled")
            if (payload.containsKey("remindBeforeMinutes")) next["remindBeforeMinutes"] = payload.getValue("remindBeforeMinutes")
            if (type == "timewin") {
                next["windowStart"] = payload.string("windowStart")?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonPrimitive("07:00")
                next["windowEnd"] = payload.string("windowEnd")?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonPrimitive("09:00")
            }
            if (type == "timewin") next["completedToday"] = JsonPrimitive(oldTask?.done ?: false) else next.remove("completedToday")
            if (type == "zonelinked") next["zoneDoneToday"] = JsonPrimitive(oldTask?.done ?: false) else next.remove("zoneDoneToday")
            if (repeat == "interval") {
                val repeatInterval = payload.safeDouble("repeatInterval")?.takeIf { it != 0.0 } ?: 30.0
                val repeatUnit = payload.string("repeatUnit")?.takeIf { it.isNotEmpty() } ?: "min"
                val repeatMs = (repeatUnitMs[repeatUnit] ?: 60_000L).toDouble() * repeatInterval
                if (!repeatMs.isFinite() || repeatMs < 0 || repeatMs > MAX_SAFE_INTEGER) {
                    return rejected("invalid-field", "repeatInterval is outside the supported range")
                }
                next["repeatInterval"] = number(repeatInterval)
                next["repeatUnit"] = JsonPrimitive(repeatUnit)
                next["repeatMs"] = number(repeatMs)
                if (repeatMs != priorRepeatMs || priorRepeat != "interval") {
                    val nextAt = if (oldTask?.done == true) now.toDouble() + repeatMs else 0.0
                    if (!nextAt.isFinite() || nextAt > MAX_SAFE_INTEGER) return rejected("invalid-field", "nextRepeatAt is outside the supported range")
                    next["nextRepeatAt"] = number(nextAt)
                }
            }
            if (type == "alarm") {
                val alarmTime = normalizeTime(payload.string("alarmTime")) ?: ""
                next["alarmTime"] = JsonPrimitive(alarmTime)
                if (alarmTime != priorAlarmTime) {
                    next["alarmFired"] = JsonPrimitive(false)
                    next["snoozeUntil"] = JsonPrimitive(0)
                }
            }
            if (payload.containsKey("note")) next["note"] = payload.getValue("note")
            val counterEnabled = type == "counter" || (type != "negative" && (payload.boolean("counterEnabled") ?: (oldType != "counter" && rawBefore.boolean("counterEnabled") == true)))
            next["counterEnabled"] = JsonPrimitive(counterEnabled)
            if (type == "counter" || (type != "negative" && counterEnabled)) {
                next["counter"] = number(rawBefore.safeDouble("counter") ?: 0.0)
                val fallbackTarget = if (type == "counter") 10.0 else 0.0
                val target = payload.safeDouble("counterTarget")?.coerceAtLeast(0.0)
                    ?: rawBefore.safeDouble("counterTarget")?.coerceAtLeast(0.0) ?: fallbackTarget
                next["counterTarget"] = number(target)
                val step = payload.safeLong("counterStep")?.takeIf { it > 0L } ?: rawBefore.safeLong("counterStep")?.takeIf { it > 0L } ?: 1L
                next["counterStep"] = JsonPrimitive(step)
                next["counterUnit"] = payload.string("counterUnit")?.takeIf { it.isNotEmpty() }?.let(::JsonPrimitive)
                    ?: rawBefore["counterUnit"]?.takeIf { it.stringOrNull()?.isNotEmpty() == true } ?: JsonPrimitive("count")
            }
            if (type == "negative") {
                val negXp = payload.safeLong("negXp")?.takeIf { it >= 0L } ?: rawBefore.safeLong("negXp")?.takeIf { it >= 0L } ?: 5L
                next["negXp"] = JsonPrimitive(negXp)
            }
            next["tags"] = JsonArray((payload["tags"] as? JsonArray).orEmpty().mapNotNull { it.stringOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let(::JsonPrimitive) })

            if (type == "check" && (rawBefore["items"] as? JsonArray).isNullOrEmpty() && payload["items"] is JsonArray) {
                next["items"] = JsonArray((payload["items"] as JsonArray).mapNotNull { item ->
                    val text = (item as? JsonObject)?.string("text")?.trim()?.takeIf(String::isNotEmpty) ?: return@mapNotNull null
                    JsonObject(mapOf("text" to JsonPrimitive(text), "done" to JsonPrimitive(false), "credited" to JsonPrimitive(false)))
                })
            }
            if (isNew && type == "check") next["expanded"] = JsonPrimitive(true)

            val finalPlanDate = next["planDate"].stringOrNull()
            if (type != oldType || zoneId != priorZoneId || finalPlanDate != priorPlanDate || repeat != priorRepeat
                || nextRepeatDays != priorRepeatDays || (repeat == "interval" && priorRepeat == "interval"
                    && (next["repeatMs"].safeDouble() != priorRepeatMs || next["repeatInterval"].safeDouble() != priorRepeatInterval || next["repeatUnit"].stringOrNull() != priorRepeatUnit))
                || next["schedDate"].stringOrNull() != priorSchedDate || next["schedTime"].stringOrNull() != priorSchedTime
                || next["alarmTime"].stringOrNull() != priorAlarmTime || next["windowStart"].stringOrNull() != priorWindowStart || next["windowEnd"].stringOrNull() != priorWindowEnd) {
                next.remove("questDeferral")
                next.remove("completionDeferral")
            }

            val tasks = if (isNew) JsonArray(listOf(JsonObject(next)) + snapshot.tasks.map { it.raw })
                else JsonArray(snapshot.tasks.map { if (it.id == update!!.taskId) JsonObject(next) else it.raw })
            val state = snapshot.data.toMutableMap().apply {
                put("tasks", tasks)
                if (isNew) put("nid", JsonPrimitive(taskId + 1L))
            }
            val activity = Progression.recordActivity(JsonObject(state), command.activityAt, zone, now)
            FlowCommandResult.Applied(FlowSnapshot.fromData(activity.state), activity.events)
        } catch (error: Exception) {
            rejected("invalid-state", error.message ?: "Task could not be saved")
        }
    }

    private fun nextId(snapshot: FlowSnapshot): Long? {
        val maxTask = snapshot.tasks.maxOfOrNull { it.id } ?: 299L
        if (maxTask >= MAX_SAFE_INTEGER) return null
        val rawNid = snapshot.data["nid"]?.safeLong()
        val next = maxOf(300L, rawNid ?: 300L, maxTask + 1L)
        return next.takeIf { it < MAX_SAFE_INTEGER }
    }

    private fun validatePayload(payload: JsonObject): String? {
        val stringFields = listOf("folderId", "planDate", "repeat", "schedDate", "schedTime", "windowStart", "windowEnd", "repeatUnit", "alarmTime", "note", "counterUnit")
        for (key in stringFields) if (payload.containsKey(key) && payload[key] != JsonNull && payload[key].stringOrNull() == null) return "$key must be a string"
        for (key in listOf("repeat", "schedDate", "schedTime", "windowStart", "windowEnd", "repeatUnit", "alarmTime", "note", "counterUnit")) {
            if (payload.containsKey(key) && payload[key] == JsonNull) return "$key must be a string"
        }
        val booleanFields = listOf("reminderEnabled", "counterEnabled")
        for (key in booleanFields) if (payload.containsKey(key) && payload[key].booleanValue() == null) return "$key must be a boolean"
        val numberFields = listOf("zoneId", "remindBeforeMinutes", "repeatInterval", "counterTarget", "counterStep", "negXp")
        for (key in numberFields) if (payload.containsKey(key) && payload[key] != JsonNull && payload[key].safeDouble() == null) return "$key must be a finite number"
        for (key in listOf("remindBeforeMinutes", "repeatInterval", "counterTarget", "counterStep", "negXp")) {
            if (payload.containsKey(key) && payload[key] == JsonNull) return "$key must be a number"
        }
        for (key in listOf("tags", "repeatDays", "items")) if (payload.containsKey(key) && payload[key] !is JsonArray) return "$key must be an array"
        (payload["tags"] as? JsonArray)?.forEach { if (it.stringOrNull() == null) return "tags entries must be strings" }
        (payload["repeatDays"] as? JsonArray)?.forEach { if (it.booleanValue() == null) return "repeatDays entries must be booleans" }
        (payload["items"] as? JsonArray)?.forEach { item ->
            if (item !is JsonObject || item["text"]?.stringOrNull() == null) return "items entries must have string text"
            for (field in listOf("done", "credited")) if (item.containsKey(field) && item[field].booleanValue() == null) return "items.$field must be a boolean"
        }
        if (payload.containsKey("zoneId") && payload["zoneId"] != JsonNull && payload.safeLong("zoneId") == null) return "zoneId must be a safe integer"
        return null
    }

    private fun normalizeTime(value: String?): String? {
        val match = TIME.matchEntire(value.orEmpty().trim()) ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()
        if (hour > 23 || minute > 59) return null
        return "%02d:%02d".format(Locale.ROOT, hour, minute)
    }

    private fun number(value: Double): JsonPrimitive =
        if (value.isFinite() && value % 1.0 == 0.0 && value in -MAX_SAFE_INTEGER.toDouble()..MAX_SAFE_INTEGER.toDouble()) JsonPrimitive(value.toLong())
        else JsonPrimitive(value)

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)

    private fun JsonObject.string(key: String): String? = this[key].stringOrNull()
    private fun JsonObject.hasText(key: String): Boolean = this[key].stringOrNull()?.isNotEmpty() == true
    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.boolean(key: String): Boolean? = this[key].booleanValue()
    private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.safeDouble(key: String): Double? = this[key].safeDouble()
    private fun JsonElement?.safeDouble(): Double? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.safeLong(key: String): Long? = this[key].safeLong()
    private fun JsonElement?.safeLong(): Long? {
        val value = this.safeDouble() ?: return null
        if (value % 1.0 != 0.0 || value !in 0.0..MAX_SAFE_INTEGER.toDouble()) return null
        return (this as? JsonPrimitive)?.longOrNull ?: value.toLong()
    }
    private fun JsonArray?.orEmpty(): List<JsonElement> = this?.toList() ?: emptyList()
    private fun JsonArray?.isNullOrEmpty(): Boolean = this == null || this.isEmpty()

    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
    private val TIME = Regex("(\\d{1,2}):(\\d{2})")
}
