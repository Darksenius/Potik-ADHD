package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Parses an explicit local-time marker embedded in a task title. */
object TaskTitleSchedule {
    data class Parsed(val title: String, val time: String?)

    sealed interface Result {
        data class Valid(val value: Parsed) : Result
        data object InvalidTime : Result
        data object EmptyTitle : Result
    }

    private val marker = Regex("///([^/]*?)///")
    private val time = Regex("(\\d{1,2}):(\\d{2})")

    fun parse(rawTitle: String): Result {
        val matches = marker.findAll(rawTitle).toList()
        if (matches.isEmpty()) {
            val title = rawTitle.trim()
            return if (title.isEmpty()) Result.EmptyTitle else Result.Valid(Parsed(title, null))
        }
        if (matches.size != 1) return Result.InvalidTime
        val match = matches.single()
        val parts = time.matchEntire(match.groupValues[1]) ?: return Result.InvalidTime
        val hour = parts.groupValues[1].toIntOrNull() ?: return Result.InvalidTime
        val minute = parts.groupValues[2].toIntOrNull() ?: return Result.InvalidTime
        if (hour !in 0..23 || minute !in 0..59) return Result.InvalidTime
        val title = (rawTitle.replaceRange(match.range, " ")
            .replace(Regex("\\s+"), " ").trim())
        if (title.isEmpty()) return Result.EmptyTitle
        return Result.Valid(Parsed(title, "%02d:%02d".format(Locale.ROOT, hour, minute)))
    }

    /** Java queue validation; preserves valid marker text for the native reducer to materialize. */
    @JvmStatic
    fun captureValidationError(rawTitle: String): String? = when (parse(rawTitle)) {
        is Result.Valid -> null
        Result.EmptyTitle -> "Введи назву задачі"
        Result.InvalidTime -> "Некоректний час. Використай формат ///12:40///"
    }

    /** A marker schedules today when its local time is still ahead, otherwise the next day. */
    fun defaultDate(time: String, now: Long, zone: ZoneId): String {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val dueToday = TaskHistory.localDateTimeMillis(today.toString(), time, zone)
        return (if (dueToday != null && dueToday > now) today else today.plusDays(1)).format(DateTimeFormatter.ISO_LOCAL_DATE)
    }
}
