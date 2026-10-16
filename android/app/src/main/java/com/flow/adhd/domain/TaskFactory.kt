package com.flow.adhd.domain

import java.time.Instant
import java.time.format.DateTimeFormatterBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Defaults shared by capture and the future native editor. Mirrors taskFactory.ts. */
object TaskFactory {
    private val isoMillis = DateTimeFormatterBuilder().appendInstant(3).toFormatter()

    fun create(
        id: Long, title: String, type: String, at: Long,
        zoneId: Long? = null, zone: JsonObject? = null, alarmTime: String = "",
    ): JsonObject {
        FlowSnapshot.safeId(JsonPrimitive(id), "tasks.id")
        FlowSnapshot.safeId(zoneId?.let(::JsonPrimitive), "tasks.zoneId")
        require(at >= 0) { "Task creation time must be non-negative" }
        return buildJsonObject {
            put("id", id); put("title", title); put("type", type)
            put("done", false); put("someday", false)
            put("zoneId", zoneId?.let(::JsonPrimitive) ?: JsonNull)
            put("zoneColor", zone?.get("color")?.takeIf { (it as? JsonPrimitive)?.content?.isNotBlank() == true } ?: JsonNull)
            put("zoneName", zone?.get("nm")?.takeIf { (it as? JsonPrimitive)?.content?.isNotBlank() == true } ?: JsonNull)
            put("expanded", false); put("created", isoMillis.format(Instant.ofEpochMilli(at)))
            if (type == "check") put("items", JsonArray(emptyList()))
            if (type == "counter" || type == "negative") put("counter", 0)
            if (type == "counter") put("counterTarget", 10)
            put("counterEnabled", type == "counter"); put("counterStep", 1); put("counterUnit", "count")
            if (type == "negative") put("negXp", 5)
            if (type == "note") put("note", "")
            if (type == "alarm") put("alarmTime", alarmTime)
            put("alarmFired", false)
            if (type == "sched") {
                put("schedDate", ""); put("schedTime", ""); put("firedSched", false); put("firedPre", false)
            }
            if (type == "timewin") {
                put("windowStart", "07:00"); put("windowEnd", "09:00"); put("completedToday", false)
            }
            if (type == "ctx") put("ctxTags", JsonArray(emptyList()))
            if (type == "zonelinked") put("zoneDoneToday", false)
            put("repeat", "none"); put("repeatDays", JsonArray(List(7) { JsonPrimitive(false) }))
            put("tags", JsonArray(emptyList()))
        }
    }
}
