package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Pure local-time rules used by simple-task capture. */
object SimpleTaskTiming {
    val delayMinutes: List<Long> = listOf(10, 20, 30, 50, 100, 150)

    /** Returns the one-shot reminder instant for today/tomorrow, or null for invalid input. */
    fun dayReminderAt(day: String, now: Long, zone: ZoneId): Long? {
        if (now !in 0..MAX_DATE_MILLIS) return null
        val today = try { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() } catch (_: Exception) { return null }
        val targetDay = when (day) {
            "today" -> today
            "tomorrow" -> try { today.plusDays(1) } catch (_: Exception) { return null }
            else -> return null
        }
        return try {
            val five = targetDay.atTime(FIVE_AM).atZone(zone).toInstant().toEpochMilli()
            if (day == "today" && now >= five) now else five
        } catch (_: Exception) {
            null
        }
    }

    fun localDateKey(at: Long, zone: ZoneId): String? = try {
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    } catch (_: Exception) {
        null
    }

    /** Returns the next selector value, wrapping after 150 minutes. */
    fun nextDelayMinutes(current: Long): Long {
        val index = delayMinutes.indexOf(current)
        return delayMinutes[(index + 1).takeIf { it in delayMinutes.indices } ?: 0]
    }

    private val FIVE_AM: LocalTime = LocalTime.of(5, 0)
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
}
