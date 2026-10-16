package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/** Pure Kotlin projection of the browser's zone calendar policy. */
object ZoneCalendar {
    data class ZoneRange(val zoneId: Long, val start: Int, val end: Int, val priority: Double, val order: Int, val planned: Boolean = false)
    data class AssignedRange(val zoneId: Long, val startMinute: Int, val endMinute: Int)

    private val dayFormat = DateTimeFormatter.ISO_LOCAL_DATE

    fun dayRanges(state: JsonObject, day: String): List<ZoneRange> {
        val zones = state["zones"] as? JsonArray ?: JsonArray(emptyList())
        val zoneById = linkedMapOf<Long, JsonObject>()
        zones.forEach { (it as? JsonObject)?.let { z -> z.safeId()?.let { id -> if (id !in zoneById) zoneById[id] = z } } }
        val offIds = ((state["planDayOff"] as? JsonObject)?.get(day) as? JsonArray)?.mapNotNull { it.safeId() }?.toSet().orEmpty()
        val result = mutableListOf<ZoneRange>()
        var order = 0
        zones.forEach { element ->
            val z = element as? JsonObject ?: return@forEach
            val id = z.safeId() ?: return@forEach
            if (z.truthy("off") || z["active"].booleanValue() == false || (z.truthy("bound") && !z.truthy("actPin")) || id in offIds) return@forEach
            (z["slots"] as? JsonArray)?.forEach { slotElement ->
                val slot = slotElement as? JsonObject ?: return@forEach
                val range = timeRange(slot.string("s"), slot.string("e")) ?: return@forEach
                result += ZoneRange(id, range.first, range.second, z.priority(), order++)
            }
        }
        PlannerProjection.dayBlocks(state, day).forEach { block ->
            val id = block.safeId("zoneId") ?: return@forEach
            val z = zoneById[id] ?: return@forEach
            if (z.truthy("off")) return@forEach
            val range = timeRange(block.string("s"), block.string("e")) ?: return@forEach
            result += ZoneRange(id, range.first, range.second, z.priority() + 10.0, order++, planned = true)
        }
        return result
    }

    fun assignedOccurrences(state: JsonObject, day: String): List<AssignedRange> {
        val result = mutableListOf<AssignedRange>()
        dayRanges(state, day).sortedWith(compareBy<ZoneRange> { it.zoneId }.thenBy { it.start }).forEach { r ->
            val prev = result.lastOrNull()
            if (prev != null && prev.zoneId == r.zoneId && r.start <= prev.endMinute)
                result[result.lastIndex] = prev.copy(endMinute = maxOf(prev.endMinute, r.end))
            else result += AssignedRange(r.zoneId, r.start, r.end)
        }
        return result
    }

    fun occurrenceKey(state: JsonObject, zoneId: Long, at: Long, zone: ZoneId): String? {
        val local = try { Instant.ofEpochMilli(at).atZone(zone) } catch (_: Exception) { return null }
        val day = local.toLocalDate().format(dayFormat)
        val minute = local.hour * 60 + local.minute
        val range = assignedOccurrences(state, day).firstOrNull { it.zoneId == zoneId && minute >= it.startMinute && minute < it.endMinute } ?: return null
        return "$day:$zoneId:${(range.startMinute / 60).toString().padStart(2, '0')}:${(range.startMinute % 60).toString().padStart(2, '0')}"
    }

    fun timeline(state: JsonObject, now: Long, zone: ZoneId, days: Int = 35): List<TimelineSegment> {
        val count = days.takeIf { it > 0 } ?: 35
        var date = try { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() } catch (_: Exception) { return emptyList() }
        val endDate = try { date.plusDays(count.toLong()) } catch (_: Exception) { return emptyList() }
        val result = mutableListOf<TimelineSegment>()
        fun append(id: Long, from: Long, to: Long) {
            if (to <= from) return
            val prev = result.lastOrNull()
            if (prev != null && prev.zoneId == id && prev.endAt == from) result[result.lastIndex] = prev.copy(endAt = to)
            else result += TimelineSegment(id, from, to)
        }
        while (date < endDate) {
            val day = date.format(dayFormat)
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val nextDate = date.plusDays(1)
            val end = nextDate.atStartOfDay(zone).toInstant().toEpochMilli()
            val ranges = dayRanges(state, day)
            if (end - start != 86_400_000L) {
                var cursor = start
                while (cursor < end) {
                    val local = Instant.ofEpochMilli(cursor).atZone(zone)
                    append(primaryAt(ranges, local.hour * 60 + local.minute), cursor, minOf(cursor + 60_000L, end))
                    cursor += 60_000L
                }
            } else {
                val boundaries = (listOf(0, 1440) + ranges.flatMap { listOf(it.start, it.end) }).distinct().sorted()
                for (i in 0 until boundaries.lastIndex) {
                    val fm = boundaries[i]; val tm = boundaries[i + 1]
                    val from = if (fm == 1440) end else date.atTime(fm / 60, fm % 60).atZone(zone).toInstant().toEpochMilli()
                    val to = if (tm == 1440) end else date.atTime(tm / 60, tm % 60).atZone(zone).toInstant().toEpochMilli()
                    val local = Instant.ofEpochMilli(from).atZone(zone)
                    append(primaryAt(ranges, local.hour * 60 + local.minute), from, to)
                }
            }
            date = nextDate
        }
        return result
    }

    fun currentZoneId(timeline: List<TimelineSegment>, at: Long): Long = timeline.firstOrNull { it.startAt <= at && at < it.endAt }?.zoneId ?: 0L

    private fun primaryAt(ranges: List<ZoneRange>, minute: Int): Long {
        var best: ZoneRange? = null
        ranges.forEach { r ->
            if (minute < r.start || minute >= r.end) return@forEach
            val b = best
            if (b == null || r.priority > b.priority || (r.priority == b.priority && r.order < b.order)) best = r
        }
        return best?.zoneId ?: 0L
    }

    private fun timeRange(start: String?, end: String?): Pair<Int, Int>? {
        val from = toMinutes(start) ?: return null
        var to = toMinutes(end) ?: return null
        if (to == 0) to = 1440
        if (from !in 0 until 1440 || to <= from || to > 1440) return null
        return from to to
    }
    // Mirrors date.ts toMinutes split/Number behavior for ordinary serialized clock values.
    private fun toMinutes(value: String?): Int? {
        if (value == null) return null
        val p = value.split(':')
        if (p.size < 2) return null
        fun number(s: String) = if (s.trim().isEmpty()) 0.0 else s.trim().toDoubleOrNull()
        val h = number(p.getOrElse(0) { "" }) ?: return null
        val m = number(p.getOrElse(1) { "" }) ?: return null
        val n = h * 60.0 + m
        return n.takeIf { it.isFinite() && it % 1.0 == 0.0 && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
    }
    private fun JsonObject.safeId(key: String = "id"): Long? = this[key].safeId()
    private fun JsonElement?.safeId(): Long? {
        val n = (this as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return null
        if (!n.isFinite() || n < 0.0 || n > 9_007_199_254_740_991.0 || n % 1.0 != 0.0) return null
        return n.toLong()
    }
    private fun JsonObject.priority(): Double {
        val n = (this["prio"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        return if (n == null || n == 0.0) 1.0 else n
    }
    private fun JsonObject.truthy(key: String): Boolean = when (val v = this[key]) {
        null, JsonNull -> false
        is JsonPrimitive -> when { v.isString -> v.content.isNotEmpty(); v.booleanOrNull != null -> v.booleanOrNull == true; else -> v.doubleOrNull?.let { it != 0.0 } ?: false }
        else -> true
    }
    private fun JsonElement?.booleanValue(): Boolean? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
