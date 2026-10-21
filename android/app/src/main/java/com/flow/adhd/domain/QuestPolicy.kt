package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Native equivalent of the shared browser Quest eligibility and deferral policy. */
object QuestPolicy {
    data class Context(
        val now: Long,
        val timeline: List<TimelineSegment>,
        val zones: List<JsonObject>,
        val priorities: List<Long?> = emptyList(),
        val zone: ZoneId,
    )

    data class Availability(val eligible: Boolean, val reason: String? = null, val currentZoneId: Long, val availableAt: Long? = null)

    fun availability(task: JsonObject, context: Context): Availability {
        val current = segmentAt(context.timeline, context.now)
        val zoneId = current?.zoneId ?: 0L
        if (task.bool("trashed")) return unavailable("trashed", zoneId)
        if (task.bool("someday")) return unavailable("someday", zoneId)
        if (task.string("type") == "negative") return unavailable("journal", zoneId)
        if (isDoneForCurrentOccurrence(task, context)) return unavailable("done", zoneId)
        val taskZone = task.number("zoneId")
        if (taskZone != null && context.zones.none { it.number("id") == taskZone }) return unavailable("missing-zone", zoneId)
        if (task.string("type") == "zonelinked" && taskZone == null) return unavailable("unassigned-zone", zoneId)

        deferralAllowsTask(task, context, current)?.let { return it }
        task.obj("questDeferral")?.takeIf { it.string("kind") == "time" }?.let { deferral ->
            val start = deferral.number("startAt")?.toLong() ?: return unavailable("not-due", zoneId)
            if (context.now >= start) return Availability(true, currentZoneId = zoneId)
        }
        val base = baseAvailability(task, context, zoneId)
        if (!base.eligible) return base
        if (taskZone != null && taskZone != zoneId.toDouble()
            && task.obj("questDeferral")?.string("kind") != "time") return unavailable("wrong-zone", zoneId)
        return Availability(true, currentZoneId = zoneId)
    }

    fun select(tasks: List<JsonObject>, context: Context, limit: Int = 5): List<JsonObject> =
        tasks.mapIndexed { order, task -> Triple(task, order, context.priorities.indexOf(task.number("id")?.toLong())) }
            .filter { availability(it.first, context).eligible }
            .sortedWith(compareBy<Triple<JsonObject, Int, Int>> { if (it.third < 0) Int.MAX_VALUE else it.third }.thenBy { it.second })
            .take(maxOf(0, limit))
            .map { (task, _, index) -> JsonObject(task + ("priorityIndex" to JsonPrimitive(index))) }

    fun createDeferral(task: JsonObject, kind: QuestDeferralKind, context: Context, requestedAt: Long): JsonObject {
        val sourceZone = segmentAt(context.timeline, requestedAt)?.zoneId ?: 0L
        val requestedDay = localDay(requestedAt, context.zone)
        if (kind == QuestDeferralKind.NEXT_ZONE && task.string("type") == "sched") {
            val start = requestedAt + SCHEDULED_DEFERRAL_MS
            if (start <= MAX_DATE_MILLIS) return buildJsonObject {
                put("kind", "time"); put("requestedAt", requestedAt); put("sourceZoneId", sourceZone)
                put("targetZoneId", task.number("zoneId")?.toLong() ?: 0L); put("startAt", start)
            }
        }
        if (kind == QuestDeferralKind.NEXT_ZONE) {
            val next = context.timeline.firstOrNull {
                it.startAt > requestedAt && localDay(it.startAt, context.zone) == requestedDay && it.zoneId > 0L && it.zoneId != sourceZone
            }
            if (next != null) return buildJsonObject {
                put("kind", "next-zone"); put("requestedAt", requestedAt); put("sourceZoneId", sourceZone)
                put("targetZoneId", next.zoneId); put("startAt", next.startAt); put("endAt", next.endAt)
            }
        }
        val taskZone = task.number("zoneId")
        val start = if (taskZone != null) localDay(requestedAt, context.zone)?.plusDays(1)?.atStartOfDay(context.zone)?.toInstant()?.toEpochMilli()
            ?: requestedAt else requestedAt + 23L * 60 * 60 * 1000
        return buildJsonObject {
            put("kind", "not-today"); put("requestedAt", requestedAt); put("sourceZoneId", sourceZone)
            put("targetZoneId", taskZone?.toLong() ?: 0L); put("startAt", start)
        }
    }

    fun isValidDeferral(value: JsonElement?): Boolean {
        val item = value as? JsonObject ?: return false
        val kind = item.string("kind") ?: return false
        val requested = item.number("requestedAt") ?: return false
        safeId(item.number("sourceZoneId")) ?: return false
        safeId(item.number("targetZoneId")) ?: return false
        val start = item.number("startAt") ?: return false
        if (!requested.isFinite() || !start.isFinite()) return false
        return when (kind) {
            "next-zone" -> {
                val end = item.number("endAt") ?: return false
                end.isFinite() && end > start
            }
            "not-today" -> item["endAt"] == null
            "time" -> item["endAt"] == null && start > requested
            else -> false
        }
    }

    private fun deferralAllowsTask(task: JsonObject, context: Context, current: TimelineSegment?): Availability? {
        val d = task.obj("questDeferral") ?: return null
        val now = context.now
        val currentId = current?.zoneId ?: 0L
        val start = d.number("startAt")?.toLong() ?: return null
        val kind = d.string("kind")
        val target = d.number("targetZoneId")?.toLong() ?: 0L
        if (now < start) return Availability(false, "deferred-until", currentId, start)
        if (kind == "time") return Availability(true, currentZoneId = currentId)
        if (kind == "next-zone") {
            val end = d.number("endAt")?.toLong() ?: return unavailable("wrong-zone", currentId)
            val source = d.number("sourceZoneId")?.toLong() ?: 0L
            if (now < end) return if (currentId == target) Availability(true, currentZoneId = currentId) else unavailable("wrong-zone", currentId)
            if (currentId == source && current != null && current.startAt >= end) return Availability(true, currentZoneId = currentId)
            val returnAt = context.timeline.firstOrNull { it.startAt >= maxOf(now, end) && it.zoneId == source }?.startAt
            return Availability(false, "wrong-zone", currentId, returnAt)
        }
        if (task["zoneId"] == null || task["zoneId"] == JsonNull) return Availability(true, currentZoneId = currentId)
        if (currentId == target && current != null) return Availability(true, currentZoneId = currentId)
        val targetSegment = context.timeline.firstOrNull { it.endAt > maxOf(now, start) && it.zoneId == target }
        return Availability(false, "wrong-zone", currentId, targetSegment?.let { maxOf(it.startAt, start) })
    }

    private fun baseAvailability(task: JsonObject, context: Context, zoneId: Long): Availability {
        val now = context.now
        val today = localDay(now, context.zone)?.toString() ?: return unavailable("not-due", zoneId)
        if (isDoneForCurrentOccurrence(task, context)) return unavailable("done", zoneId)
        val planDate = TaskSchedule.planDate(task)
        if (planDate != null && planDate > today) return unavailable("future-date", zoneId)
        task.number("snoozeUntil")?.takeIf { it > now.toDouble() }?.let { return Availability(false, "snoozed", zoneId, it.toLong()) }
        if (task.string("type") == "timewin" && task.bool("completedToday") && (task.string("doneDate").isNullOrEmpty() || task.string("doneDate") == today)) return unavailable("timewin-completed", zoneId)
        val repeat = task.string("repeat")
        if (!repeat.isNullOrEmpty() && repeat !in setOf("none", "interval", "everyzone") && !TaskSchedule.repeatDueOn(task, now, context.zone)) return unavailable("not-due", zoneId)
        task.number("nextRepeatAt")?.takeIf { repeat == "interval" && it > now.toDouble() }?.let { return Availability(false, "not-due", zoneId, it.toLong()) }
        if (task.string("type") == "sched" && task.string("schedTime") != null) {
            val day = if (TaskSchedule.isCalendarRepeat(task)) today else planDate
            val dueTime = normalizeTime(task.string("schedTime"))
            val due = if (dueTime == null) null else day?.let { TaskHistory.localDateTimeMillis(it, dueTime, context.zone) }
            if (due != null && due > now) return Availability(false, "not-due", zoneId, due)
        }
        if (task.string("type") == "alarm" && task.string("alarmTime") != null) {
            val dueTime = normalizeTime(task.string("alarmTime"))
            val due = if (dueTime == null) null else TaskHistory.localDateTimeMillis(today, dueTime, context.zone)
            if (due != null && due > now) return Availability(false, "not-due", zoneId, due)
        }
        if (!TaskSchedule.isTimeWindow(task, now, context.zone)) return unavailable("outside-time-window", zoneId)
        val quick = task.string("type") in setOf("simple", "check", "counter", "note", "ctx") && planDate == null && (task["zoneId"] == null || task["zoneId"] == JsonNull)
        if (TaskSchedule.isUnscheduled(task) && !quick) return unavailable("not-due", zoneId)
        return Availability(true, currentZoneId = zoneId)
    }

    private fun isDoneForCurrentOccurrence(task: JsonObject, context: Context): Boolean {
        if (!task.bool("done")) return false
        val today = localDay(context.now, context.zone)?.toString() ?: return false
        val repeat = task.string("repeat")
        val completionDay = task.string("doneDate").takeUnless { it.isNullOrEmpty() } ?: task.string("completionCreditDate")
        if (TaskSchedule.isCalendarRepeat(task) && completionDay != null && completionDay < today && TaskSchedule.repeatDueOn(task, context.now, context.zone)) return false
        if (repeat == "interval" && task.number("nextRepeatAt")?.let { it != 0.0 && it <= context.now.toDouble() } == true) return false
        return true
    }

    private fun segmentAt(timeline: List<TimelineSegment>, at: Long) = timeline.firstOrNull { it.startAt <= at && at < it.endAt }
    private fun localDay(at: Long, zone: ZoneId): LocalDate? = try { Instant.ofEpochMilli(at).atZone(zone).toLocalDate() } catch (_: Exception) { null }
    private fun normalizeTime(value: String?): String? {
        val match = value?.let(Regex("^(\\d{1,2}):(\\d{2})$")::matchEntire) ?: return null
        val hour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        return hour.toString().padStart(2, '0') + ":" + minute.toString().padStart(2, '0')
    }
    private fun unavailable(reason: String, zoneId: Long) = Availability(false, reason, zoneId)
    private fun safeId(value: Double?): Long? = value?.takeIf { it.isFinite() && it >= 0 && it <= 9_007_199_254_740_991.0 && it % 1.0 == 0.0 }?.toLong()
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private const val SCHEDULED_DEFERRAL_MS = 60L * 60L * 1000L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
}
