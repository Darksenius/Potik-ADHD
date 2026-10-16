package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class TimelineSegment(val zoneId: Long, val startAt: Long, val endAt: Long)
data class ProjectionContext(val now: Long, val timeline: List<TimelineSegment>, val zone: ZoneId)

/** Read-only schedule projection using an explicit clock and local time zone. */
object TaskSchedule {
    private val dayFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val timePattern = Regex("(\\d{1,2}):(\\d{2})")
    private val dayPattern = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val calendarRepeats = setOf("daily", "weekly", "weekdays", "weekend", "custom")

    fun planDate(task: JsonObject): String? {
        if (task.string("type") == "sched") task.string("schedDate")?.takeIf(::isDateKey)?.let { return it }
        return task.string("planDate")?.takeIf(::isDateKey)
    }

    fun isCalendarRepeat(task: JsonObject): Boolean = task.string("repeat")?.let(calendarRepeats::contains) == true

    fun repeatDueOn(task: JsonObject, at: Long, zone: ZoneId): Boolean {
        val date = localDate(at, zone) ?: return false
        val mondayIndex = date.dayOfWeek.value - 1
        return when (task.string("repeat")) {
            "daily" -> true
            "weekdays" -> mondayIndex < 5
            "weekend" -> mondayIndex >= 5
            "custom" -> (task["repeatDays"] as? JsonArray)?.getOrNull(mondayIndex).booleanOrFalse()
            "weekly" -> {
                val anchorDay = planDate(task)
                val anchor = if (anchorDay != null) parseDay(anchorDay)?.dayOfWeek?.value
                    else task.string("created")?.let(::parseInstant)?.atZone(zone)?.dayOfWeek?.value
                // JS treats an invalid created timestamp as an invalid Date; its weekly fallback matches all days.
                anchor == null || date.dayOfWeek.value == anchor
            }
            "interval" -> task.number("nextRepeatAt")?.let { it != 0.0 && at.toDouble() >= it } ?: false
            else -> false
        }
    }

    fun deferredOccurrenceAt(task: JsonObject, context: ProjectionContext): Long? {
        val d = task.obj("questDeferral") ?: return null
        if (task.bool("done") || task.bool("trashed") || task.bool("someday")) return null
        val start = d.number("startAt")?.toLong() ?: return null
        val end = d.number("endAt")?.toLong()
        val now = context.now
        if (d.string("kind") == "next-zone") {
            if (end == null || now < end) return maxOf(start, minOf(now, end ?: start))
            val sourceId = d.number("sourceZoneId")?.toLong() ?: return null
            return context.timeline.firstOrNull { it.zoneId == sourceId && it.startAt >= end && it.endAt > now }
                ?.let { maxOf(it.startAt, now) }
        }
        if (task.number("zoneId") == null) return start
        val targetId = d.number("targetZoneId")?.toLong() ?: return null
        return context.timeline.firstOrNull { it.zoneId == targetId && it.endAt > maxOf(now, start) }
            ?.let { maxOf(it.startAt, start) }
    }

    fun displayPlanDate(task: JsonObject, context: ProjectionContext): String? {
        if (task.obj("questDeferral") != null && !task.bool("done")) {
            val at = deferredOccurrenceAt(task, context) ?: task.obj("questDeferral")?.number("startAt")?.toLong() ?: return null
            return formatDay(at, context.zone)
        }
        return planDate(task)
    }

    fun isDeferredOccurrenceOn(task: JsonObject, day: String, context: ProjectionContext): Boolean =
        task.obj("questDeferral") != null && !task.bool("done") && displayPlanDate(task, context) == day

    fun occursOn(task: JsonObject, day: String, context: ProjectionContext): Boolean {
        if (task.bool("trashed") || task.bool("someday") || !isDateKey(day)) return false
        if (task.obj("questDeferral") != null && !task.bool("done")) {
            val movedDay = displayPlanDate(task, context) ?: return false
            if (movedDay == day) return true
            if (day < movedDay || !isCalendarRepeat(task)) return false
        }
        if (isCalendarRepeat(task)) {
            val anchor = planDate(task) ?: task.string("created")?.let(::parseInstant)?.let { formatDay(it.toEpochMilli(), context.zone) }
            if (anchor != null && day < anchor) return false
            val noon = parseDay(day)?.atTime(12, 0)?.atZone(context.zone)?.toInstant()?.toEpochMilli() ?: return false
            return repeatDueOn(task, noon, context.zone)
        }
        if (task.string("repeat") == "interval" && task.bool("done")) {
            val next = task.number("nextRepeatAt")?.takeIf { it != 0.0 }?.toLong()
            if (next != null) return formatDay(next, context.zone) == day
        }
        return planDate(task) == day
    }

    fun doneOn(task: JsonObject, day: String, context: ProjectionContext, history: List<JsonObject>? = null): Boolean {
        if (history != null && day < (formatDay(context.now, context.zone) ?: return false)) {
            fun matches(event: JsonObject) =
                event["taskId"] == task["id"] && event["taskCreated"] == task["created"] && event.string("date") == day
            if (TaskHistory.completedOccurrences(JsonArray(history), zone = context.zone).any { matches(it) }) return true
            if (task.bool("done") && task.string("doneDate") == day) {
                return TaskHistory.completedOccurrences(JsonArray(history), JsonArray(listOf(task)), context.zone).any { matches(it) }
            }
            return false
        }
        if (task.string("repeat") == "interval") {
            val next = task.number("nextRepeatAt")?.takeIf { it != 0.0 }?.toLong()
            if (next != null && formatDay(next, context.zone) == day) return false
        }
        return task.bool("done") && (!isCalendarRepeat(task) || task.string("doneDate") == day)
    }

    fun canToggle(task: JsonObject, day: String, context: ProjectionContext): Boolean {
        val today = formatDay(context.now, context.zone)
        if (isCalendarRepeat(task) && day != today) return false
        if (task.string("repeat") == "interval") {
            val next = task.number("nextRepeatAt")
            return day == today && (next == null || next == 0.0 || next <= context.now.toDouble())
        }
        return true
    }

    /** Equivalent to the old unscheduled capture predicate; it is not an Inbox screen. */
    fun isUnscheduled(task: JsonObject): Boolean {
        if (task.bool("trashed") || task.bool("someday") || task.bool("done") || planDate(task) != null || task.number("zoneId")?.let { it != 0.0 } == true) return false
        val repeat = task.string("repeat")
        val type = task.string("type")
        val hasZone = task.number("zoneId")?.let { it != 0.0 } == true
        return (repeat.isNullOrEmpty() || repeat == "none") && !hasZone && type != "sched" && type != "alarm" && type != "timewin" && type != "zonelinked"
    }

    fun isTimeWindow(task: JsonObject, now: Long, zone: ZoneId): Boolean {
        if (task.string("type") != "timewin") return true
        val from = normalizedMinutes(task.string("windowStart")) ?: return true
        val to = normalizedMinutes(task.string("windowEnd")) ?: return true
        val local = Instant.ofEpochMilli(now).atZone(zone)
        val minute = local.hour * 60 + local.minute
        return if (from <= to) minute in from..to else minute >= from || minute <= to
    }

    fun window(task: JsonObject, now: Long, zone: ZoneId): Boolean = isTimeWindow(task, now, zone)

    fun available(task: JsonObject, context: ProjectionContext): Boolean {
        if (task.bool("trashed") || task.bool("someday")) return false
        if (task.bool("done")) return true
        val today = formatDay(context.now, context.zone) ?: return false
        if (planDate(task)?.let { it > today } == true) return false
        if (task.number("snoozeUntil")?.let { it > context.now.toDouble() } == true) return false
        if (!isTimeWindow(task, context.now, context.zone)) return false
        if (task.string("type") == "timewin" && task.bool("completedToday")) return false
        val repeat = task.string("repeat")
        if (!repeat.isNullOrEmpty() && repeat !in setOf("none", "interval", "everyzone") && !repeatDueOn(task, context.now, context.zone)) return false
        return true
    }

    fun isAvailable(task: JsonObject, context: ProjectionContext): Boolean = available(task, context)

    fun canPrioritize(task: JsonObject, context: ProjectionContext): Boolean =
        !task.bool("done") && task.string("type") != "negative" && available(task, context)

    fun prioritize(task: JsonObject, context: ProjectionContext): Boolean = canPrioritize(task, context)

    fun preReminderAt(task: JsonObject, due: Long): Long? {
        if (task.obj("questDeferral") != null) return null
        val minutes = task.number("remindBeforeMinutes") ?: 0.0
        if (task.string("type") != "sched" || task.bool("firedPre") || task["reminderEnabled"] == JsonPrimitive(false) || !minutes.isFinite() || minutes <= 0.0) return null
        val value = due.toDouble() - minutes * 60_000.0
        return value.takeIf { it.isFinite() && it >= Long.MIN_VALUE && it <= Long.MAX_VALUE }?.toLong()
    }

    fun deferralReminderAt(task: JsonObject, timeline: List<TimelineSegment>): Long? {
        val d = task.obj("questDeferral") ?: return null
        val start = d.number("startAt")?.toLong() ?: return null
        if (d.string("kind") == "next-zone" || task.number("zoneId") == null) return start
        val target = d.number("targetZoneId")?.toLong() ?: return null
        return timeline.firstOrNull { it.endAt > start && it.zoneId == target }?.let { maxOf(it.startAt, start) }
    }

    fun nextReminderAt(task: JsonObject, context: ProjectionContext): Long? {
        if (task.bool("done") || task.bool("trashed") || task.bool("someday") || task["reminderEnabled"] == JsonPrimitive(false)) return null
        val type = task.string("type")
        if (task.obj("questDeferral") != null && (type == "sched" || type == "alarm")) {
            val fired = if (type == "sched") task.bool("firedSched") else task.bool("alarmFired")
            if (fired) return null
            return deferralReminderAt(task, context.timeline)
        }
        if (type == "sched") {
            val due = TaskHistory.localDateTimeMillis(planDate(task) ?: return null, task.string("schedTime") ?: return null, context.zone) ?: return null
            if (task.number("snoozeUntil")?.let { due.toDouble() < it } == true) return null
            val repeat = task.string("repeat")
            if (!repeat.isNullOrEmpty() && repeat !in setOf("none", "interval", "everyzone") && !repeatDueOn(task, due, context.zone)) return null
            return due
        }
        if (type != "alarm" || normalizedMinutes(task.string("alarmTime")) == null) return null
        val repeat = task.string("repeat")
        if (repeat.isNullOrEmpty() || repeat == "none") {
            if (task.bool("alarmFired")) return null
        }
        val minDue = maxOf(context.now - 59_999L, task.number("snoozeUntil")?.toLong() ?: 0L)
        val plannedDay = planDate(task)
        var date = localDate(context.now, context.zone) ?: return null
        for (offset in 0 until 370) {
            val day = date.format(dayFormat)
            if (plannedDay == null || day >= plannedDay) {
                val due = TaskHistory.localDateTimeMillis(day, task.string("alarmTime")!!, context.zone)
                if (due != null && due >= minDue) {
                    if (repeat.isNullOrEmpty() || repeat in setOf("none", "interval", "everyzone") || repeatDueOn(task, due, context.zone)) {
                        val next = task.number("nextRepeatAt")
                        if (repeat != "interval" || next == null || next == 0.0 || due.toDouble() >= next) return due
                    }
                }
            }
            if (offset < 369) date = date.plusDays(1)
        }
        return null
    }

    private fun isDateKey(value: String): Boolean = parseDay(value) != null
    private fun parseDay(value: String): LocalDate? {
        if (!dayPattern.matches(value)) return null
        return try { LocalDate.parse(value, dayFormat).takeIf { it.format(dayFormat) == value } } catch (_: Exception) { null }
    }
    private fun localDate(at: Long, zone: ZoneId): LocalDate? = try { Instant.ofEpochMilli(at).atZone(zone).toLocalDate() } catch (_: Exception) { null }
    private fun formatDay(at: Long, zone: ZoneId): String? = localDate(at, zone)?.format(dayFormat)
    private fun parseInstant(value: String): Instant? = try { Instant.parse(value) } catch (_: Exception) { null }
    private fun normalizedMinutes(value: String?): Int? {
        val match = value?.trim()?.let(timePattern::matchEntire) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }
    private fun kotlinx.serialization.json.JsonElement?.booleanOrFalse(): Boolean = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
}
