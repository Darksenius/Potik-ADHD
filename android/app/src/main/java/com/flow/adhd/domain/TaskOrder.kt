package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Read-only ordering for the full task list and notification candidates. */
object TaskOrder {
    private data class Key(
        val group: Int,
        val primary: Long = Long.MAX_VALUE,
        val secondary: Int = Int.MAX_VALUE,
        val stable: Int,
    ) : Comparable<Key> {
        override fun compareTo(other: Key): Int {
            compareValues(group, other.group).takeIf { it != 0 }?.let { return it }
            compareValues(primary, other.primary).takeIf { it != 0 }?.let { return it }
            compareValues(secondary, other.secondary).takeIf { it != 0 }?.let { return it }
            return compareValues(stable, other.stable)
        }
    }

    /**
     * Returns every input task in relevance order. Eligibility remains owned by [QuestPolicy];
     * this function only ranks. The original order is retained for otherwise equal tasks.
     */
    fun rank(tasks: List<JsonObject>, context: QuestPolicy.Context): List<JsonObject> {
        val current = context.timeline.firstOrNull { it.startAt <= context.now && context.now < it.endAt }
        return tasks.mapIndexed { index, task -> task to key(task, index, context, current) }
            .sortedWith(compareBy<Pair<JsonObject, Key>> { it.second })
            .map { it.first }
    }

    private fun key(task: JsonObject, index: Int, context: QuestPolicy.Context, current: TimelineSegment?): Key {
        val availability = QuestPolicy.availability(task, context)
        val priority = context.priorities.indexOf(task.long("id")).let { if (it < 0) Int.MAX_VALUE else it }
        val zoneId = task.long("zoneId")
        val isCurrentZone = zoneId != null && current?.let { zoneId == it.zoneId && it.zoneId > 0L } == true
        val noZone = zoneId == null || task["zoneId"] == JsonNull || zoneId == 0L

        if (availability.eligible) {
            if (priority != Int.MAX_VALUE) return Key(group = 0, secondary = priority, stable = index)
            val due = scheduledDueAt(task, context)
            if (due != null) return Key(group = 1, primary = due, stable = index)
            if (isCurrentZone) return Key(group = 2, stable = index)
            if (noZone) return Key(group = 4, secondary = priority, stable = index)
            return Key(group = 2, stable = index)
        }

        val terminal = task.bool("trashed") || task.bool("someday") ||
            availability.reason in setOf("done", "missing-zone", "unassigned-zone", "journal")
        if (terminal) return Key(group = 6, stable = index)

        val effective = effectiveAt(task, context, availability.availableAt)
        if (effective != null && effective <= imminentThrough(context)) {
            return Key(group = 3, primary = effective, secondary = priority, stable = index)
        }
        return Key(group = 5, primary = effective ?: Long.MAX_VALUE, secondary = priority, stable = index)
    }

    /** Eligible scheduled work is ordered by its actual due instant; overdue work sorts first. */
    private fun scheduledDueAt(task: JsonObject, context: QuestPolicy.Context): Long? {
        val timeDeferral = (task["questDeferral"] as? JsonObject)
            ?.takeIf { it.string("kind") == "time" }
            ?.long("startAt")
        if (timeDeferral != null) return timeDeferral
        val type = task.string("type")
        val time = when (type) {
            "sched" -> task.string("schedTime")
            "alarm" -> task.string("alarmTime")
            else -> return null
        } ?: return null
        val localTime = parseTime(time) ?: return null
        val today = localDate(context.now, context.zone) ?: return null
        val date = if (type == "sched" && !TaskSchedule.isCalendarRepeat(task)) {
            TaskSchedule.planDate(task)?.let(::parseDate) ?: return null
        } else today
        return localMillis(date, localTime, context.zone)
    }

    /** Best known future occurrence for an ineligible task, including date+zone and deferrals. */
    private fun effectiveAt(task: JsonObject, context: QuestPolicy.Context, policyAt: Long?): Long? {
        val futurePolicyAt = policyAt?.takeIf { it > context.now }
        val projection = ProjectionContext(context.now, context.timeline, context.zone)
        val deferred = TaskSchedule.deferredOccurrenceAt(task, projection)?.takeIf { it > context.now }
        val dated = scheduledOccurrence(task, context)
        val zoneAt = zoneOccurrence(task, context)
        val datedAndZoned = if (dated != null && zoneAt != null) maxOf(dated, zoneAt) else dated ?: zoneAt
        return listOfNotNull(futurePolicyAt, deferred, datedAndZoned).maxOrNull()
    }

    private fun zoneOccurrence(task: JsonObject, context: QuestPolicy.Context): Long? {
        val id = task.long("zoneId")?.takeIf { it > 0L } ?: return null
        val plannedDay = TaskSchedule.planDate(task)?.let(::parseDate)
        val plannedAt = plannedDay?.atStartOfDay(context.zone)?.toInstant()?.toEpochMilli()
        return context.timeline.asSequence()
            .filter { it.zoneId == id && it.endAt > context.now }
            .mapNotNull { segment ->
                val start = maxOf(context.now, segment.startAt, plannedAt ?: Long.MIN_VALUE)
                if (start >= segment.endAt) null else start
            }
            .minOrNull()
    }

    private fun scheduledOccurrence(task: JsonObject, context: QuestPolicy.Context): Long? {
        val dateKey = TaskSchedule.planDate(task) ?: return null
        val date = parseDate(dateKey) ?: return null
        val time = when (task.string("type")) {
            "sched" -> task.string("schedTime")
            "alarm" -> task.string("alarmTime")
            else -> null
        }?.let(::parseTime) ?: LocalTime.MIDNIGHT
        return localMillis(date, time, context.zone)?.takeIf { it > context.now }
    }

    /** Near-term work ends with the next named-zone segment today, or local midnight. */
    private fun imminentThrough(context: QuestPolicy.Context): Long {
        val today = localDate(context.now, context.zone) ?: return context.now
        val dayEnd = try { today.plusDays(1).atStartOfDay(context.zone).toInstant().toEpochMilli() }
        catch (_: Exception) { return context.now }
        val nextNamedSegment = context.timeline.asSequence()
            .filter { it.startAt > context.now && it.zoneId > 0L && localDate(it.startAt, context.zone) == today }
            .minByOrNull { it.startAt }
        return nextNamedSegment?.endAt?.let { minOf(it, dayEnd) } ?: dayEnd
    }

    private fun localDate(at: Long, zone: ZoneId): LocalDate? = try { Instant.ofEpochMilli(at).atZone(zone).toLocalDate() } catch (_: Exception) { null }
    private fun parseDate(value: String): LocalDate? = try { LocalDate.parse(value) } catch (_: Exception) { null }
    private fun parseTime(value: String): LocalTime? = try { LocalTime.parse(value.trim()) } catch (_: Exception) { null }
    private fun localMillis(date: LocalDate, time: LocalTime, zone: ZoneId): Long? = try { date.atTime(time).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
}
