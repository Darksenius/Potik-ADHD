package com.flow.adhd.domain

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ActivityResult(val state: JsonObject, val events: List<FlowEvent>)
internal data class ProgressionPosition(val level: Double, val xp: Double)

/** Pure progression migration and activity bookkeeping for the existing JSON state shape. */
object Progression {
    private const val VERSION = 2
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L

    private data class Claim(val day: String, val cardId: Long, val sequence: Long, val raw: JsonObject)
    private data class Achievement(val id: String, val field: Metric, val target: Double)
    private enum class Metric { DONE, XP, LEVEL, ZONES, DAYS }

    private val achievements = listOf(
        Achievement("first", Metric.DONE, 1.0), Achievement("ten", Metric.DONE, 10.0),
        Achievement("xp100", Metric.XP, 100.0), Achievement("zones", Metric.ZONES, 5.0),
        Achievement("pro", Metric.LEVEL, 5.0), Achievement("habit", Metric.DONE, 5.0),
        Achievement("done25", Metric.DONE, 25.0), Achievement("done50", Metric.DONE, 50.0),
        Achievement("done100", Metric.DONE, 100.0), Achievement("done250", Metric.DONE, 250.0),
        Achievement("done500", Metric.DONE, 500.0), Achievement("xp500", Metric.XP, 500.0),
        Achievement("xp1000", Metric.XP, 1_000.0), Achievement("xp2500", Metric.XP, 2_500.0),
        Achievement("xp5000", Metric.XP, 5_000.0), Achievement("level10", Metric.LEVEL, 10.0),
        Achievement("level15", Metric.LEVEL, 15.0), Achievement("level20", Metric.LEVEL, 20.0),
        Achievement("level25", Metric.LEVEL, 25.0), Achievement("level30", Metric.LEVEL, 30.0),
        Achievement("level40", Metric.LEVEL, 40.0), Achievement("level50", Metric.LEVEL, 50.0),
        Achievement("level60", Metric.LEVEL, 60.0), Achievement("level75", Metric.LEVEL, 75.0),
        Achievement("level100", Metric.LEVEL, 100.0), Achievement("active1", Metric.DAYS, 1.0),
        Achievement("active3", Metric.DAYS, 3.0), Achievement("active7", Metric.DAYS, 7.0),
        Achievement("active14", Metric.DAYS, 14.0), Achievement("active21", Metric.DAYS, 21.0),
        Achievement("active30", Metric.DAYS, 30.0), Achievement("active45", Metric.DAYS, 45.0),
        Achievement("active60", Metric.DAYS, 60.0), Achievement("active75", Metric.DAYS, 75.0),
        Achievement("active100", Metric.DAYS, 100.0),
    )

    /**
     * Record one activity using injected event/current times and a local zone.
     * This updates progression metadata only; XP awards remain a separate command.
     */
    fun recordActivity(
        state: JsonObject,
        at: Long,
        zone: ZoneId,
        now: Long = at,
        insightCount: Int = 100,
    ): ActivityResult {
        require(insightCount > 0) { "insightCount must be positive" }
        require(now in 0..MAX_DATE_MILLIS) { "now is outside the supported timestamp range" }

        val today = localDay(now, zone) ?: throw IllegalArgumentException("now has no supported local date")
        val activityDay = localDay(at, zone)
        val migrate = state.number("progressionVersion") != VERSION.toDouble()
        val normalizedDays = if (migrate) seedActivityDays(state, today) else readProgressDays(state, today)
        val output = state.toMutableMap()
        val progression = if (migrate) normalizeProgression(state) else null
        if (progression != null) {
            output["progressionVersion"] = JsonPrimitive(VERSION)
            output["xp"] = numberElement(progression.xp)
            output["xpTotal"] = numberElement(nonNegativeFinite(state.number("xpTotal")))
            output["level"] = numberElement(progression.level)
        }

        val beforeDays = normalizedDays.toSet()
        if (at in 0..now && activityDay != null && activityDay <= today) normalizedDays.add(activityDay)
        val sortedDays = normalizedDays.sorted()
        val days = sortedDays.toSet()
        if (migrate || "progressDays" in state || sortedDays.isNotEmpty()) output["progressDays"] = dayArray(sortedDays)

        val priorClaims = state["dailyInsightClaims"] as? JsonArray ?: JsonArray(emptyList())
        val archived = archiveClaims(days, priorClaims, today, insightCount)
        val claimAlreadyToday = archived.any { it.day == today }
        val canClaimToday = at in 0..now && activityDay == today && today in days && !claimAlreadyToday
        val earned = if (canClaimToday) {
            val sequence = maxOf(days.size.toLong(), archived.maxOfOrNull { it.sequence + 1 } ?: 1L, 1L)
            Claim(today, (sequence - 1) % insightCount + 1L, sequence,
                claimObject(today, (sequence - 1) % insightCount + 1L, sequence, now))
        } else null
        val allClaims = (archived + listOfNotNull(earned)).sortedBy { it.day }

        val claimsJson = JsonArray(allClaims.map { it.raw })
        if (migrate || "dailyInsightClaims" in state || allClaims.isNotEmpty()) output["dailyInsightClaims"] = claimsJson

        val events = buildList {
            if (activityDay != null && at in 0..now && activityDay !in beforeDays && activityDay in days) {
                add(FlowEvent("progress_day_recorded", at, buildJsonObject { put("day", activityDay) }))
            }
            if (earned != null) {
                add(FlowEvent("daily_insight_claimed", now, earned.raw))
            }
        }
        val checked = checkAchievements(JsonObject(output), now)
        return ActivityResult(checked.state, events + checked.events)
    }

    /** Initialize/migrate progression and archive past active-day cards without earning today. */
    fun initialize(state: JsonObject, now: Long, zone: ZoneId, insightCount: Int = 100): ActivityResult {
        require(insightCount > 0) { "insightCount must be positive" }
        require(now in 0..MAX_DATE_MILLIS) { "now is outside the supported timestamp range" }
        val today = localDay(now, zone) ?: throw IllegalArgumentException("now has no supported local date")
        val progression = normalizeProgression(state)
        val days = seedActivityDays(state, today).sorted()
        val claims = archiveClaims(days.toSet(), state["dailyInsightClaims"] as? JsonArray ?: JsonArray(emptyList()), today, insightCount)
        val output = state.toMutableMap().apply {
            put("progressionVersion", JsonPrimitive(VERSION))
            put("progressDays", dayArray(days))
            put("xp", numberElement(progression.xp))
            put("xpTotal", numberElement(nonNegativeFinite(state.number("xpTotal"))))
            put("level", numberElement(progression.level))
            put("dailyInsightClaims", JsonArray(claims.map { it.raw }))
        }
        return checkAchievements(JsonObject(output), now)
    }

    /** Unlock threshold achievements against current metrics without recording activity. */
    fun checkAchievements(state: JsonObject, at: Long): ActivityResult {
        val unlocked = ((state["unlocked"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            ?: emptyList()).toMutableList()
        val metrics = mapOf(
            Metric.DONE to nonNegativeFinite(state.number("done")),
            Metric.XP to nonNegativeFinite(state.number("xpTotal")),
            Metric.LEVEL to nonNegativeFinite(state.number("level")),
            Metric.ZONES to ((state["zones"] as? JsonArray)?.size ?: 0).toDouble(),
            Metric.DAYS to ((state["progressDays"] as? JsonArray)?.size ?: 0).toDouble(),
        )
        val newlyUnlocked = achievements.filter { it.id !in unlocked && (metrics[it.field] ?: 0.0) >= it.target }
            .map { it.id }
        if (newlyUnlocked.isEmpty()) return ActivityResult(state, emptyList())
        unlocked.addAll(newlyUnlocked)
        val output = state.toMutableMap()
        output["unlocked"] = JsonArray(unlocked.map(::JsonPrimitive))
        return ActivityResult(JsonObject(output), newlyUnlocked.map { id ->
            FlowEvent("achievement_unlocked", at, buildJsonObject { put("id", id) })
        })
    }

    internal fun normalizeProgression(state: JsonObject): ProgressionPosition {
        val oldLevel = finiteFloor(state.number("level")).coerceIn(1.0, MAX_SAFE_INTEGER.toDouble())
        val oldXp = nonNegativeFinite(state.number("xp"))
        val version = finiteFloor(state.number("progressionVersion"))
        if (version >= VERSION) return ProgressionPosition(oldLevel, oldXp)

        val oldBalance = (100.0 * (oldLevel - 1.0) * oldLevel) / 2.0 + oldXp
        val mapped = progressionFromXp(oldBalance)
        return ProgressionPosition(maxOf(oldLevel, mapped.level), mapped.xp)
    }

    internal fun progressionFromXp(value: Double): ProgressionPosition {
        val total = nonNegativeFinite(value)
        var low = 1.0
        var high = MAX_SAFE_INTEGER.toDouble()
        while (low < high) {
            val middle = low + kotlin.math.ceil((high - low) / 2.0)
            if (totalXpToReachLevel(middle) <= total) low = middle else high = middle - 1.0
        }
        return ProgressionPosition(low, (total - totalXpToReachLevel(low)).coerceAtLeast(0.0))
    }

    internal fun totalXpToReachLevel(level: Double): Double {
        val k = (kotlin.math.floor(level) - 1.0).coerceIn(0.0, (MAX_SAFE_INTEGER - 1).toDouble())
        return 100.0 * k + (5.0 * k * (k - 1.0)) / 2.0
    }

    private fun readProgressDays(state: JsonObject, today: String): MutableSet<String> {
        val days = sortedSetOf<String>()
        (state["progressDays"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.let { day -> if (validDay(day, today)) days.add(day) } }
        return days
    }

    private fun seedActivityDays(state: JsonObject, today: String): MutableSet<String> {
        val days = sortedSetOf<String>()
        (state["progressDays"] as? JsonArray)?.forEach { (it as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.content?.let { day -> if (validDay(day, today)) days.add(day) } }
        (state["taskHistory"] as? JsonArray)?.forEach { element ->
            val event = element as? JsonObject ?: return@forEach
            if (event.string("kind") == "completed") event.string("date")?.takeIf { validDay(it, today) }?.let(days::add)
        }
        (state["qnotes"] as? JsonArray)?.forEach { element ->
            val note = element as? JsonObject ?: return@forEach
            note.string("date")?.takeIf { validDay(it, today) }?.let(days::add)
        }
        return days
    }

    private fun archiveClaims(days: Set<String>, rawClaims: JsonArray, today: String, insightCount: Int): List<Claim> {
        val byDay = linkedMapOf<String, Claim>()
        rawClaims.forEach { element ->
            val raw = element as? JsonObject ?: return@forEach
            val day = raw.string("day") ?: return@forEach
            val cardId = raw.safeInteger("cardId") ?: return@forEach
            val sequence = raw.safeInteger("sequence") ?: return@forEach
            val earnedAt = raw.number("earnedAt") ?: return@forEach
            if (day !in days || !validDay(day, today) || cardId !in 1L..insightCount.toLong() || sequence < 1L || earnedAt < 0.0 || byDay.containsKey(day)) return@forEach
            byDay[day] = Claim(day, cardId, sequence, raw)
        }
        var sequence = byDay.values.maxOfOrNull { it.sequence } ?: 0L
        for (day in days.sorted()) {
            if (day >= today || !validDay(day, today) || day in byDay) continue
            sequence++
            val cardId = (sequence - 1L) % insightCount + 1L
            byDay[day] = Claim(day, cardId, sequence, claimObject(day, cardId, sequence, 0L))
        }
        return byDay.values.sortedBy { it.day }
    }

    private fun claimObject(day: String, cardId: Long, sequence: Long, earnedAt: Long): JsonObject = buildJsonObject {
        put("day", day)
        put("cardId", cardId)
        put("sequence", sequence)
        put("earnedAt", earnedAt)
    }

    private fun validDay(day: String, today: String): Boolean {
        if (!DAY_PATTERN.matches(day) || day > today) return false
        return try { LocalDate.parse(day, DateTimeFormatter.ISO_LOCAL_DATE).toString() == day }
        catch (_: DateTimeException) { false }
    }

    private fun localDay(at: Long, zone: ZoneId): String? {
        if (at !in 0..MAX_DATE_MILLIS) return null
        return try {
            Instant.ofEpochMilli(at).atZone(zone).toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE)
                .takeIf { DAY_PATTERN.matches(it) }
        } catch (_: DateTimeException) { null }
    }

    private fun dayArray(days: List<String>) = JsonArray(days.map(::JsonPrimitive))

    private fun finiteFloor(value: Double?): Double = value?.takeIf { it.isFinite() }?.let { kotlin.math.floor(it) } ?: 1.0
    private fun nonNegativeFinite(value: Double?): Double = value?.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0

    private fun numberElement(value: Double): JsonPrimitive =
        if (value.isFinite() && value % 1.0 == 0.0 && value >= -MAX_SAFE_INTEGER && value <= MAX_SAFE_INTEGER) JsonPrimitive(value.toLong())
        else JsonPrimitive(value)

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.safeInteger(name: String): Long? {
        val value = (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: return null
        return value.takeIf { it in 0..MAX_SAFE_INTEGER }
    }

    private val DAY_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
}
