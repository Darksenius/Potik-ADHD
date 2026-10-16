package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** Pure XP and energy reducers. Presentation-only popup state is returned as domain events. */
object Experience {
    private const val DAILY_CAP = 250.0
    private const val ENERGY_XP_COOLDOWN_MS = 3_600_000L
    private val xpDayFormat = DateTimeFormatter.ofPattern("EEE MMM dd yyyy", Locale.US)

    fun award(
        state: JsonObject,
        amount: Double,
        at: Long,
        zone: ZoneId,
        now: Long = at,
    ): ActivityResult {
        if (!amount.isFinite() || amount == 0.0) return ActivityResult(state, emptyList())
        require(at >= 0) { "at must be non-negative" }

        var current = state
        val events = mutableListOf<FlowEvent>()
        if (current.number("progressionVersion") != 2.0) {
            val initialized = Progression.initialize(current, now, zone)
            current = initialized.state
            events += initialized.events
        }

        val today = try { Instant.ofEpochMilli(now).atZone(zone).format(xpDayFormat) }
        catch (_: Exception) { throw IllegalArgumentException("now has no supported local date") }
        val output = current.toMutableMap()
        val sameXpDay = current.string("xpDay") == today
        if (!sameXpDay) {
            output["xpDay"] = JsonPrimitive(today)
            output["dayXpGained"] = JsonPrimitive(0)
        }

        var appliedAmount = amount
        if (appliedAmount > 0.0) {
            val priorGain = if (sameXpDay) finiteNonNegative(current.number("dayXpGained")) ?: DAILY_CAP else 0.0
            val room = (DAILY_CAP - priorGain).coerceAtLeast(0.0)
            if (appliedAmount > room) appliedAmount = room
            if (appliedAmount == 0.0) {
                events += FlowEvent("xp_limit", at, buildJsonObject { put("limit", DAILY_CAP.toInt()) })
                val activity = Progression.recordActivity(JsonObject(output), at, zone, now)
                return ActivityResult(activity.state, events + activity.events)
            }
        }

        val priorDayGain = if (sameXpDay) finiteNonNegative(current.number("dayXpGained")) ?: DAILY_CAP else 0.0
        output["dayXpGained"] = numberElement(priorDayGain + appliedAmount.coerceAtLeast(0.0))

        var xp = current.number("xp") ?: 0.0
        val xpTotal = current.number("xpTotal") ?: 0.0
        var level = current.number("level") ?: 1.0
        xp += appliedAmount
        if (xp < 0.0) xp = 0.0

        if (appliedAmount > 0.0) {
            output["xpTotal"] = numberElement(xpTotal + appliedAmount)
            val mapped = Progression.progressionFromXp(Progression.totalXpToReachLevel(level) + xp)
            if (mapped.level >= level) {
                xp = mapped.xp
                level = mapped.level
            }
        }
        output["xp"] = numberElement(xp)
        output["level"] = numberElement(level)
        val updated = JsonObject(output)

        events += FlowEvent("xp_awarded", at, buildJsonObject {
            put("amount", appliedAmount)
            put("tone", if (appliedAmount < 0.0) "loss" else "gain")
        })
        val oldLevel = current.number("level") ?: 1.0
        if (appliedAmount > 0.0 && level > oldLevel) {
            events += FlowEvent("level_up", at, buildJsonObject { put("level", numberElement(level)) })
        }

        return if (appliedAmount > 0.0) {
            val activity = Progression.recordActivity(updated, at, zone, now)
            ActivityResult(activity.state, events + activity.events)
        } else {
            val checked = Progression.checkAchievements(updated, now)
            ActivityResult(checked.state, events + checked.events)
        }
    }

    fun setEnergy(
        state: JsonObject,
        value: Int,
        at: Long,
        zone: ZoneId,
        now: Long = at,
    ): ActivityResult {
        require(value in 0..5) { "energy must be between 0 and 5" }
        require(now >= 0) { "now must be non-negative" }
        require(at >= 0) { "at must be non-negative" }

        val output = state.toMutableMap()
        output["energy"] = JsonPrimitive(value)
        val last = state.number("lastEnergyXp")
        val shouldAward = last == null || last == 0.0 || now.toDouble() - last >= ENERGY_XP_COOLDOWN_MS
        if (!shouldAward) return ActivityResult(JsonObject(output), emptyList())

        output["lastEnergyXp"] = JsonPrimitive(now)
        return award(JsonObject(output), 3.0, at, zone, now)
    }

    private fun finiteNonNegative(value: Double?): Double? = value?.takeIf { it.isFinite() }?.coerceAtLeast(0.0)

    private fun numberElement(value: Double): JsonPrimitive =
        if (value.isFinite() && value % 1.0 == 0.0 && value >= -9_007_199_254_740_991.0 && value <= 9_007_199_254_740_991.0) JsonPrimitive(value.toLong())
        else JsonPrimitive(value)

    private fun JsonObject.number(name: String): Double? = (this[name] as? JsonPrimitive)
        ?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

    private fun JsonObject.string(name: String): String? = (this[name] as? JsonPrimitive)
        ?.takeIf { it.isString }?.content
}
