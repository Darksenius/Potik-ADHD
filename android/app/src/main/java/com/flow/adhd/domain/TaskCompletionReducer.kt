package com.flow.adhd.domain

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** Completion toggle port; undo presentation and past-task commands remain separate. */
object TaskCompletionReducer {
    private val dateFormat = DateTimeFormatter.ISO_LOCAL_DATE
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.forLanguageTag("uk"))

    fun reduce(
        snapshot: FlowSnapshot,
        command: FlowCommand.SetTaskDone,
        zone: ZoneId,
        taskXp: Map<String, Double>,
        now: Long = command.activityAt,
        zoneOccurrenceKey: (Long, Long) -> String? = { _, _ -> null },
    ): FlowCommandResult {
        val task = snapshot.tasks.firstOrNull { it.id == command.taskId }
            ?: return applied(snapshot)
        if (task.trashed || task.type == "negative" || task.done == command.done) return applied(snapshot)
        val at = command.completedAt ?: command.activityAt
        val localDateTime = try { Instant.ofEpochMilli(at).atZone(zone) }
        catch (_: Exception) { return rejected("invalid-time", "Completion time is invalid") }
        if (at !in 0..MAX_DATE_MILLIS || now !in 0..MAX_DATE_MILLIS) return rejected("invalid-time", "Completion time is invalid")

        return try {
            val raw = task.raw
            val priorCredit = raw.flag("completionCredited") ?: task.done
            val shouldCredit = command.done && raw.flag("completionCredited") != true
            val credited = if (command.done) true else priorCredit
            val date = localDateTime.toLocalDate().format(dateFormat)
            val creditDate = when {
                command.done && shouldCredit -> JsonPrimitive(date)
                command.done -> raw["completionCreditDate"]
                credited -> JsonPrimitive(
                    raw.text("completionCreditDate")?.takeIf { it.isNotEmpty() }
                        ?: raw.text("doneDate")?.takeIf { it.isNotEmpty() }
                        ?: date,
                )
                else -> raw["completionCreditDate"]
            }
            val originAt = raw.objectValue("questDeferral")?.number("requestedAt")?.toLong() ?: at
            val taskZoneId = raw.long("zoneId")?.takeIf { it != 0L }
            val zoneKey = taskZoneId?.let { zoneOccurrenceKey(it, originAt) }
            val occurrence = TaskHistory.occurrenceId(raw, at, zoneKey, zone)
            val history = TaskHistory.seedKnown(snapshot.data.array("taskHistory"), snapshot.data.array("tasks"), zone).toMutableList()
            history.add(TaskHistory.event(raw, if (command.done) "completed" else "reopened", at,
                occurrence, TaskHistory.nextSequence(JsonArray(history)), zone))

            val updatedTask = raw.toMutableMap().apply {
                put("done", JsonPrimitive(command.done))
                put("completionCredited", JsonPrimitive(credited))
                put("occurrenceId", JsonPrimitive(occurrence))
                if (creditDate != null) put("completionCreditDate", creditDate) else remove("completionCreditDate")
                if (task.type == "timewin") put("completedToday", JsonPrimitive(command.done))
                if (task.type == "zonelinked") put("zoneDoneToday", JsonPrimitive(command.done))
                if (command.done) {
                    put("doneDate", JsonPrimitive(date))
                    put("doneAt", JsonPrimitive(localDateTime.toLocalTime().format(timeFormat)))
                    put("snoozeUntil", JsonPrimitive(0))
                    val deferral = raw["questDeferral"]
                    if (deferral != null) put("completionDeferral", deferral) else remove("completionDeferral")
                    remove("questDeferral")
                    val repeatMs = raw.number("repeatMs")
                    val nextRepeatAt = if (raw.text("repeat") == "interval" && repeatMs != null && repeatMs != 0.0)
                        Math.addExact(at, repeatMs.toLong()) else 0L
                    put("nextRepeatAt", JsonPrimitive(nextRepeatAt))
                } else {
                    remove("doneDate")
                    remove("doneAt")
                    val deferral = raw["completionDeferral"]
                    if (deferral != null) put("questDeferral", deferral) else remove("questDeferral")
                    remove("completionDeferral")
                    put("nextRepeatAt", JsonPrimitive(0))
                }
            }
            val tasks = JsonArray(snapshot.tasks.map { if (it.id == task.id) JsonObject(updatedTask) else it.raw })
            val state = snapshot.data.toMutableMap().apply {
                put("tasks", tasks)
                put("taskHistory", JsonArray(history))
                if (command.done) {
                    (this["priorities"] as? JsonArray)?.let { priorities ->
                        put("priorities", JsonArray(priorities.map { item -> if (item.longOrNullValue() == task.id) JsonNull else item }))
                    }
                }
                if (shouldCredit) put("done", number((snapshot.data.number("done") ?: 0.0) + 1.0))
            }

            val events = mutableListOf<FlowEvent>()
            var resultState = JsonObject(state)
            if (command.done) {
                val taskReward = taskXp[task.type] ?: 10.0
                if (shouldCredit && taskReward != 0.0) {
                    val award = Experience.award(resultState, taskReward, at, zone, now)
                    resultState = award.state
                    events.addAll(award.events)
                }
                val activity = Progression.recordActivity(resultState, at, zone, now)
                resultState = activity.state
                events.addAll(activity.events)
            }
            if (shouldCredit && date < Instant.ofEpochMilli(now).atZone(zone).toLocalDate().format(dateFormat)) {
                resultState = updateLateDayCount(resultState, date, zone)
            }
            FlowCommandResult.Applied(FlowSnapshot.fromData(resultState), events)
        } catch (error: Exception) {
            rejected("invalid-state", error.message ?: "Task completion could not be applied")
        }
    }

    private fun updateLateDayCount(state: JsonObject, day: String, zone: ZoneId): JsonObject {
        val log = state.objectValue("planDayLog") ?: JsonObject(emptyMap())
        val entry = (log[day] as? JsonObject)?.toMutableMap() ?: mutableMapOf(
            "focus" to JsonArray(emptyList()), "energy" to JsonPrimitive(0),
            "routineDone" to JsonObject(emptyMap()), "priorities" to JsonArray(emptyList()),
            "tasksDone" to JsonPrimitive(0),
        )
        val tasks = state.array("tasks")
        val history = state.array("taskHistory")
        entry["tasksDone"] = JsonPrimitive(TaskHistory.completedOccurrences(history, tasks, zone).count { it.text("date") == day })
        val updatedLog = JsonObject(log.toMutableMap().apply { put(day, JsonObject(entry)) })
        return JsonObject(state.toMutableMap().apply { put("planDayLog", updatedLog) })
    }

    private fun applied(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)

    private fun number(value: Double): JsonPrimitive =
        if (value.isFinite() && value % 1.0 == 0.0 && value in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) JsonPrimitive(value.toLong())
        else JsonPrimitive(value)

    private fun JsonObject.array(key: String): JsonArray = this[key] as? JsonArray ?: JsonArray(emptyList())
    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.flag(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNullValue()
    private fun JsonObject.number(key: String): Double? = (this[key] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonElement.longOrNullValue(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull

    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991.0
}
