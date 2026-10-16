package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Occurrence transitions use the same rules at startup, a day boundary, or a timer tick. */
object TaskLifecycle {
    private val savedDayFormat = DateTimeFormatter.ofPattern("EEE MMM dd yyyy", Locale.US)

    fun resetRepeating(snapshot: FlowSnapshot, at: Long, zone: ZoneId): FlowSnapshot {
        val day = day(at, zone)
        return mapTasks(snapshot, zone) { task ->
            val repeat = task.text("repeat")
            val anchor = TaskSchedule.planDate(task)
            when {
                task.flag("trashed") || task.flag("someday") || (task["questDeferral"] is JsonObject && !task.flag("done")) -> task
                repeat.isNullOrEmpty() || repeat == "none" || repeat == "everyzone" -> task
                (anchor != null && anchor > day) || (task.number("snoozeUntil") ?: 0.0) > at -> task
                !TaskSchedule.repeatDueOn(task, at, zone) -> task
                repeat == "interval" && !task.flag("done") -> task
                repeat != "interval" && progressDay(task, zone) >= day -> task
                else -> resetOccurrence(task, day)
            }
        }
    }

    fun checkZoneRepeats(
        snapshot: FlowSnapshot, at: Long, zone: ZoneId,
        occurrenceKey: (Long, Long) -> String?, taskId: Long? = null,
    ): FlowSnapshot {
        val day = day(at, zone)
        return mapTasks(snapshot, zone) { task ->
            val id = task.id("id")
            val zoneId = task.id("zoneId")
            val anchor = TaskSchedule.planDate(task)
            if ((taskId != null && id != taskId) || task.flag("trashed") || task.flag("someday")
                || (task["questDeferral"] is JsonObject && !task.flag("done"))
                || task.text("repeat") != "everyzone" || zoneId == null || zoneId == 0L
                || (anchor != null && anchor > day) || (task.number("snoozeUntil") ?: 0.0) > at) return@mapTasks task
            val key = occurrenceKey(zoneId, at)?.takeIf { it.isNotEmpty() } ?: return@mapTasks task
            val prior = task.text("zoneOccurrenceKey")
            if (key == prior) return@mapTasks task
            val result = if (prior.isNullOrEmpty()) task else resetOccurrence(task, day)
            JsonObject(result + ("zoneOccurrenceKey" to JsonPrimitive(key)))
        }
    }

    fun dailyReset(snapshot: FlowSnapshot, savedDate: String?, at: Long, zone: ZoneId): FlowSnapshot {
        val today = Instant.ofEpochMilli(at).atZone(zone).format(savedDayFormat)
        if (savedDate.isNullOrEmpty() || savedDate == today) return snapshot
        val day = day(at, zone)
        val tasks = snapshot.tasks.map { view ->
            val task = view.raw
            if (view.trashed) task
            else if (view.type == "negative" && !task.text("dayPresetKey").isNullOrEmpty()
                && task.text("repeat") == "none" && !task.text("planDate").isNullOrEmpty()
                && task.text("planDate")!! < day) task
            else if (view.type == "negative" && (task.text("counterDay").isNullOrEmpty() || task.text("counterDay")!! < day)) {
                JsonObject(task + mapOf("counter" to JsonPrimitive(0), "counterCreditedProgress" to JsonPrimitive(0), "counterDay" to JsonPrimitive(day)))
            } else task
        }
        val data = snapshot.data.toMutableMap().apply {
            put("tasks", JsonArray(tasks))
            (this["recur"] as? JsonArray)?.let { routines ->
                put("recur", JsonArray(routines.map { entry ->
                    if (entry is JsonObject) JsonObject(entry + mapOf("val" to JsonPrimitive(0), "done" to JsonPrimitive(false))) else entry
                }))
            }
        }
        return resetRepeating(FlowSnapshot.fromData(JsonObject(data)), at, zone)
    }

    fun tick(snapshot: FlowSnapshot, at: Long, zone: ZoneId, occurrenceKey: (Long, Long) -> String?): FlowCommandResult.Applied {
        val reset = dailyReset(snapshot, snapshot.data.text("saveDate"), at, zone)
        val repeating = resetRepeating(reset, at, zone)
        val zoned = checkZoneRepeats(repeating, at, zone, occurrenceKey)
        val today = Instant.ofEpochMilli(at).atZone(zone).format(savedDayFormat)
        val state = FlowSnapshot.fromData(JsonObject(zoned.data + ("saveDate" to JsonPrimitive(today))))
        val changed = state.tasks.filter { task -> snapshot.tasks.firstOrNull { it.id == task.id }?.raw != task.raw }.map { JsonPrimitive(it.id) }
        val events = if (changed.isEmpty()) emptyList() else listOf(FlowEvent("task_occurrences_reset", at,
            JsonObject(mapOf("taskIds" to JsonArray(changed)))))
        return FlowCommandResult.Applied(state, events)
    }

    private fun resetOccurrence(task: JsonObject, day: String): JsonObject = JsonObject(task.toMutableMap().apply {
        val counter = task.text("type") == "counter" || (task.text("type") != "negative" && task.flag("counterEnabled"))
        if (counter || task.text("type") == "negative") {
            put("counter", JsonPrimitive(0)); put("cntHit", JsonPrimitive(false)); put("counterCreditedProgress", JsonPrimitive(0))
        }
        (task["items"] as? JsonArray)?.let { items -> put("items", JsonArray(items.map { item ->
            if (item is JsonObject) JsonObject(item + mapOf("done" to JsonPrimitive(false), "credited" to JsonPrimitive(false))) else item
        })) }
        put("done", JsonPrimitive(false)); put("completionCredited", JsonPrimitive(false))
        listOf("completionCreditDate", "doneDate", "doneAt", "occurrenceId", "questDeferral", "completionDeferral").forEach(::remove)
        put("alarmFired", JsonPrimitive(false)); put("firedSched", JsonPrimitive(false)); put("firedPre", JsonPrimitive(false))
        put("nextRepeatAt", JsonPrimitive(0)); put("counterDay", JsonPrimitive(day))
        if (task.text("type") == "timewin") put("completedToday", JsonPrimitive(false))
        if (task.text("type") == "zonelinked") put("zoneDoneToday", JsonPrimitive(false))
        if (task.text("type") == "sched") { put("schedDate", JsonPrimitive(day)); put("planDate", JsonPrimitive(day)) }
        else if (TaskSchedule.planDate(task) != null) put("planDate", JsonPrimitive(day))
    })

    private fun mapTasks(snapshot: FlowSnapshot, zone: ZoneId, transform: (JsonObject) -> JsonObject): FlowSnapshot {
        val tasks = snapshot.tasks.map { transform(it.raw) }
        if (tasks == snapshot.tasks.map { it.raw }) return snapshot
        val history = TaskHistory.seedKnown(snapshot.data["taskHistory"] as? JsonArray ?: JsonArray(emptyList()),
            snapshot.data["tasks"] as? JsonArray ?: JsonArray(emptyList()), zone)
        return FlowSnapshot.fromData(JsonObject(snapshot.data + mapOf("tasks" to JsonArray(tasks), "taskHistory" to history)))
    }

    private fun progressDay(task: JsonObject, zone: ZoneId): String {
        val created = try { task.text("created")?.let { day(Instant.parse(it).toEpochMilli(), zone) } } catch (_: Exception) { null }
        return listOfNotNull(task.text("counterDay"), task.text("completionCreditDate"), task.text("doneDate"),
            TaskSchedule.planDate(task), created).filter { it.isNotEmpty() }.maxOrNull().orEmpty()
    }
    private fun day(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
    private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.id(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
}
