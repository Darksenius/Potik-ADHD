package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.json.*

/** Pure replacement for bridge/notifPayload.ts; the Java notification JSON contract is unchanged. */
object NotificationProjection {
    private val emptyArray = JsonArray(emptyList())
    private val defaultRepeatDays = JsonArray(List(7) { JsonPrimitive(false) })

    @JvmStatic
    @JvmOverloads
    fun build(snapshot: FlowSnapshot, now: Long, zone: ZoneId, insights: Map<Int, JsonObject> = emptyMap()): JsonObject {
        val state = snapshot.data
        validateState(snapshot)
        val tasks = snapshot.tasks.map { JsonObject(mapOf("type" to JsonPrimitive(it.type)) + it.raw) }
        val zones = snapshot.zones
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = today.toString()
        val timeline = ZoneCalendar.timeline(state, now, zone)
        val currentZoneId = ZoneCalendar.currentZoneId(timeline, now)
        val currentZone = zones.firstOrNull { id(it) == currentZoneId }
        val zoneName = if (currentZone == null) "Вільний час" else currentZone.text("nm") ?: "Зона"
        val priorities = state.array("priorities").map { FlowSnapshot.safeId(it, "priorities") }
        val context = QuestPolicy.Context(now, timeline, zones, priorities, zone)
        val ordered = TaskOrder.rank(tasks, context)
        val top = QuestPolicy.select(ordered, context, 5)
        val undone = QuestPolicy.select(ordered, context, tasks.size)
        val scheduleContext = ProjectionContext(now, timeline, zone)
        val done = tasks.count { it.flag("done") && it.text("doneDate") == day && TaskSchedule.available(it, scheduleContext) &&
            (it["zoneId"] == null || it["zoneId"] == JsonNull || id(it, "zoneId") == currentZoneId) }
        val priorityCount = top.count { it.number("priorityIndex")!! >= 0 }
        val focus = state.text("currentFocus").orEmpty().takeUnless { it == zoneName }.orEmpty()
        val zoneSlots = currentZone?.array("slots").orEmpty().map { it as JsonObject }
        val slots = if (zoneSlots.isEmpty()) "" else zoneSlots.first().text("s") + "–" + zoneSlots.last().text("e")
        val ranges = ZoneCalendar.dayRanges(state, day)
        fun zoneRow(range: ZoneCalendar.ZoneRange): JsonObject {
            val source = zones.first { id(it) == range.zoneId }
            return buildJsonObject {
                put("id", range.zoneId); put("s", clock(range.start)); put("e", clock(range.end))
                put("nm", source.text("nm") ?: "Зона"); put("desc", source.text("desc").orEmpty())
                put("color", source.text("color") ?: "#4f8ef7"); put("prio", numeric(range.priority))
            }
        }
        val routineRows = state.objects("recur").map { item -> buildJsonObject {
            put("id", item.text("id").orEmpty()); put("nm", item.text("nm").orEmpty()); put("ico", "")
            put("unit", item.text("unit").orEmpty()); put("val", numeric(item.number("val") ?: 0.0))
            put("step", numeric(item.number("step")?.takeIf { it != 0.0 } ?: 1.0)); put("done", item.flag("done"))
        } }
        val routine = routineRows.joinToString("  ·  ") { item ->
            val name = item.text("nm").orEmpty()
            when (item.text("unit")) {
                "check" -> name + if (item.flag("done")) " ✓" else " ○"
                else -> name + " " + numberText(item.number("val") ?: 0.0) + when (item.text("unit")) { "ml" -> "мл"; "min" -> "хв"; else -> "" }
            }
        }
        val reminders = tasks.flatMap { task ->
            if (TaskSchedule.isCalendarRepeat(task)) recurringReminderRows(task, now, zone, timeline)
            else {
                val due = TaskSchedule.nextReminderAt(task, scheduleContext)
                if (due == null) emptyList() else buildList {
                    add(reminder(task, due, fired = if (task.text("type") == "sched") task.flag("firedSched")
                        else task.flag("alarmFired") && localDay(due, zone) == day))
                    TaskSchedule.preReminderAt(task, due)?.takeIf { due > now }?.let { add(reminder(task, it, pre = true)) }
                }
            }
        }
        val wallDays = wallDays(state, today.minusDays(2), 39)
        val occurrences = wallDays.flatMap { wall ->
            val date = LocalDate.parse(wall.text("date"))
            wall.objects("assignedOccurrences").mapNotNull { range ->
                val start = wallInstant(date, range.number("startMinute")!!.toInt(), zone)
                val end = wallInstant(date, range.number("endMinute")!!.toInt(), zone)
                if (end <= start) null else buildJsonObject {
                    val zoneId = id(range, "zoneId")!!
                    put("zoneId", zoneId); put("startAt", start); put("endAt", end)
                    put("key", "$date:$zoneId:${clock(range.number("startMinute")!!.toInt())}")
                }
            }
        }
        return buildJsonObject {
            put("zone", zoneName); put("zoneColor", currentZone?.text("color")?.takeIf { it.isNotEmpty() } ?: if (currentZone == null) "#888" else "#4f8ef7")
            put("zoneTimeline", JsonArray(ranges.map(::zoneRow))); put("zoneBaseline", JsonArray(ranges.filterNot { it.planned }.map(::zoneRow)))
            put("zoneTasksById", JsonObject(top.filter { (id(it, "zoneId") ?: 0) != 0L }.groupBy { id(it, "zoneId").toString() }
                .mapValues { (_, values) -> JsonArray(values.map { JsonPrimitive(it.text("title")!!) }) }))
            put("zonelessTasks", JsonArray(top.filter { (id(it, "zoneId") ?: 0) == 0L }.map { JsonPrimitive(it.text("title")!!) }))
            put("urgentTasks", JsonArray(top.filter { it.number("priorityIndex")!! >= 0 }.map { JsonPrimitive(it.text("title")!!) }))
            put("tlDate", day); put("slots", slots); put("desc", currentZone?.text("desc")?.takeIf { it.isNotEmpty() } ?: focus)
            put("tasks", top.joinToString("|") { it.text("title")!! })
            put("taskList", JsonArray(top.filter { it.text("type") != "negative" }.map { taskListRow(it, today, zone) }))
            put("questTasks", JsonArray(ordered.map { JsonObject(it + ("priorityIndex" to JsonPrimitive(priorities.indexOf(id(it))))) }))
            put("questTimeline", JsonArray(timeline.map { buildJsonObject { put("zoneId", it.zoneId); put("startAt", it.startAt); put("endAt", it.endAt) } }))
            put("questWallDays", JsonArray(wallDays)); put("questZoneOccurrences", JsonArray(occurrences))
            put("questTimelineUntil", timeline.lastOrNull()?.endAt ?: now)
            put("questZones", JsonArray(zones.map { buildJsonObject {
                put("id", id(it)!!); put("nm", it.text("nm") ?: "Зона"); put("desc", it.text("desc").orEmpty())
                put("color", it.text("color")?.takeIf { value -> value.isNotEmpty() } ?: "#4f8ef7")
            } }))
            put("routine", routine); put("routineList", JsonArray(routineRows)); put("schedList", JsonArray(reminders))
            put("body", (if (priorityCount > 0) "Пріоритети: $priorityCount · " else "") +
                if (undone.isNotEmpty()) "${undone.size} активних · $done виконано" else "Зараз немає доречних задач")
            val time = Instant.ofEpochMilli(now).atZone(zone)
            put("time", clock(time.hour * 60 + time.minute)); put("done", done); put("total", undone.size + done)
            put("energy", numeric(state.number("energy") ?: 0.0)); put("title", "Потік · $zoneName" + if (focus.isNotEmpty()) " · $focus" else "")
            if (state.objectValue("preferences")?.get("showGamification") != JsonPrimitive(false)) {
                val claim = state.objects("dailyInsightClaims").firstOrNull { it.text("day") == day && (it.number("earnedAt") ?: 0.0) > 0 }
                val card = claim?.number("cardId")?.toInt()?.let(insights::get)
                if (claim != null && card != null) {
                    require(card.text("title") != null && card.text("text") != null) { "Daily insight content must contain title and text strings" }
                    put("dailyInsight", buildJsonObject {
                        put("day", day); put("cardId", claim["cardId"]!!); put("sequence", claim["sequence"]!!)
                        put("title", card.text("title")!!); put("text", card.text("text")!!)
                    })
                }
            }
        }
    }

    /** Calendar rules survive an absent WebView; deferrals remain a one-off absolute deadline. */
    @JvmStatic
    @JvmOverloads
    fun recurringReminderRows(task: JsonObject, now: Long, zone: ZoneId, timeline: List<TimelineSegment> = emptyList()): List<JsonObject> {
        validateTask(task)
        if (!TaskSchedule.isCalendarRepeat(task) || task.text("type") !in setOf("sched", "alarm") ||
            task.flag("trashed") || task.flag("someday") || task["reminderEnabled"] == JsonPrimitive(false)) return emptyList()
        val scheduled = task.text("type") == "sched"
        if (task.objectValue("questDeferral") != null) {
            if (task.flag(if (scheduled) "firedSched" else "alarmFired")) return emptyList()
            return TaskSchedule.deferralReminderAt(task, timeline)?.let { listOf(reminder(task, it)) }.orEmpty()
        }
        val time = normalizedTime(task.text(if (scheduled) "schedTime" else "alarmTime")) ?: return emptyList()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val first = TaskSchedule.planDate(task) ?: createdDay(task, today, zone)
        val lead = if (scheduled) maxOf(0.0, task.number("remindBeforeMinutes") ?: 0.0) else 0.0
        return buildList {
            for (pre in if (lead > 0) listOf(false, true) else listOf(false)) {
                for (offset in 0 until 370) {
                    val date = today.plusDays(offset.toLong())
                    val day = date.toString()
                    if (day < first || !TaskSchedule.repeatDueOn(task, date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(), zone) ||
                        (task.flag("done") && day <= (task.text("doneDate")?.takeIf { it.isNotEmpty() } ?: today.toString()))) continue
                    val mainDue = TaskHistory.localDateTimeMillis(day, time, zone) ?: continue
                    if (task.number("snoozeUntil")?.let { mainDue.toDouble() < it } == true) continue
                    if (date == today && task.flag(if (pre) "firedPre" else if (scheduled) "firedSched" else "alarmFired")) continue
                    if (pre && mainDue <= now) continue
                    val due = mainDue.toDouble() - if (pre) lead * 60_000 else 0.0
                    require(due.isFinite() && due >= Long.MIN_VALUE.toDouble() && due <= Long.MAX_VALUE.toDouble()) { "Reminder deadline overflows" }
                    add(JsonObject(reminder(task, due.toLong(), pre) + mapOf(
                        "repeat" to (task["repeat"] ?: JsonPrimitive("none")), "repeatDays" to task.arrayOrDefault("repeatDays", defaultRepeatDays),
                        "anchorDate" to JsonPrimitive(day), "localTime" to JsonPrimitive(time), "advanceMinutes" to numeric(if (pre) lead else 0.0),
                    )))
                    break
                }
            }
        }
    }

    private fun taskListRow(task: JsonObject, today: LocalDate, zone: ZoneId): JsonObject = buildJsonObject {
        val type = task.text("type") ?: "simple"
        val created = createdDay(task, today, zone)
        put("id", id(task)!!); put("title", task.text("title")!!); put("type", type)
        put("counter", numeric(task.number("counter") ?: 0.0))
        put("counterTarget", numeric(maxOf(0.0, task.number("counterTarget") ?: if (type == "counter") 10.0 else 0.0)))
        put("counterEnabled", type != "negative" && (type == "counter" || task.flag("counterEnabled")))
        put("counterStep", numeric(task.number("counterStep")?.takeIf { it > 0 } ?: 1.0)); put("counterUnit", task.text("counterUnit")?.takeIf { it.isNotEmpty() } ?: "count")
        put("repeat", task["repeat"] ?: JsonPrimitive("none")); put("repeatDays", task.arrayOrDefault("repeatDays", defaultRepeatDays))
        put("anchorDate", TaskSchedule.planDate(task) ?: created)
        put("counterDay", task.text("counterDay")?.takeIf { it.isNotEmpty() } ?: task.text("completionCreditDate")?.takeIf { it.isNotEmpty() } ?: created)
    }

    private fun reminder(task: JsonObject, due: Long, pre: Boolean = false, fired: Boolean = false): JsonObject = buildJsonObject {
        put("id", if (pre) JsonPrimitive("${id(task)}_pre") else JsonPrimitive(id(task)!!))
        put("title", (if (pre) "Незабаром: " else "") + task.text("title")); put("dueMs", due); put("fired", fired)
    }

    /** Resolve authored wall minutes through the same zone policy on fixed 24-hour UTC dates. */
    private fun wallDays(state: JsonObject, first: LocalDate, count: Int): List<JsonObject> {
        val wallTimeline = ZoneCalendar.timeline(state, first.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), ZoneOffset.UTC, count)
        return (0 until count).map { offset ->
            val date = first.plusDays(offset.toLong())
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            val end = start + 86_400_000L
            buildJsonObject {
                put("date", date.toString())
                put("segments", JsonArray(wallTimeline.filter { it.endAt > start && it.startAt < end }.map {
                    wallRange(it.zoneId, ((maxOf(start, it.startAt) - start) / 60_000).toInt(), ((minOf(end, it.endAt) - start) / 60_000).toInt())
                }))
                put("assignedOccurrences", JsonArray(ZoneCalendar.assignedOccurrences(state, date.toString()).map { wallRange(it.zoneId, it.startMinute, it.endMinute) }))
            }
        }
    }

    private fun wallRange(zoneId: Long, start: Int, end: Int) = buildJsonObject { put("zoneId", zoneId); put("startMinute", start); put("endMinute", end) }
    private fun wallInstant(day: LocalDate, minute: Int, zone: ZoneId): Long = day.plusDays((minute / 1440).toLong()).atTime((minute % 1440) / 60, minute % 60).atZone(zone).toInstant().toEpochMilli()
    private fun createdDay(task: JsonObject, fallback: LocalDate, zone: ZoneId): String = task.text("created")?.let {
        try { Instant.parse(it).atZone(zone).toLocalDate().toString() } catch (_: Exception) { throw SnapshotFormatException("tasks.created must be a valid timestamp") }
    } ?: fallback.toString()
    private fun localDay(at: Long, zone: ZoneId) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    private fun clock(minute: Int) = "${(minute / 60).toString().padStart(2, '0')}:${(minute % 60).toString().padStart(2, '0')}"
    private fun normalizedTime(value: String?): String? {
        val match = value?.let(Regex("^(\\d{1,2}):(\\d{2})$")::matchEntire) ?: return null
        val hour = match.groupValues[1].toInt(); val minute = match.groupValues[2].toInt()
        return if (hour <= 23 && minute <= 59) clock(hour * 60 + minute) else null
    }

    /** Missing retired fields use defaults; malformed present fields must not withdraw alarms silently. */
    private fun validateState(snapshot: FlowSnapshot) {
        val state = snapshot.data
        snapshot.tasks.forEach { validateTask(it.raw) }
        state.array("priorities").forEach { FlowSnapshot.safeId(it, "priorities") }
        state.text("currentFocus"); state.number("energy")
        state.objectValue("preferences")?.flag("showGamification")
        val zoneIds = snapshot.zones.map { item ->
            val zoneId = id(item) ?: throw SnapshotFormatException("zones.id is required")
            item.text("nm"); item.text("desc"); item.text("color"); item.number("prio")
            listOf("active", "bound", "actPin", "off").forEach { item.flag(it) }
            item.objects("slots").forEach { validateRange(it, "zones.slots") }
            zoneId
        }
        require(zoneIds.distinct().size == zoneIds.size) { "zones contains duplicate IDs" }
        state.objectValue("planDayOff")?.forEach { (day, value) ->
            requireDate(day, "planDayOff")
            array(value, "planDayOff.$day").forEach { FlowSnapshot.safeId(it, "planDayOff.$day") ?: throw SnapshotFormatException("planDayOff IDs cannot be null") }
        }
        state.objectValue("planDayZones")?.forEach { (day, value) ->
            requireDate(day, "planDayZones")
            objects(value, "planDayZones.$day").forEach { validateBlock(it) }
        }
        state.objects("dayTemplates").forEach { template ->
            require(template.text("id") != null) { "dayTemplates.id is required" }
            template.objects("blocks").forEach(::validateBlock)
        }
        state.objects("planRules").forEach { rule ->
            requireDate(rule.text("start") ?: "", "planRules.start"); requireDate(rule.text("end") ?: "", "planRules.end")
            rule.objects("days").forEach { it.text("tplId"); it.flag("rest") }
        }
        state.objects("recur").forEach { item ->
            listOf("id", "nm", "unit").forEach { item.text(it) }; listOf("val", "step").forEach { item.number(it) }; item.flag("done")
            require(item.text("id") != null && item.text("nm") != null) { "recur entries require id and nm" }
        }
        state.objects("dailyInsightClaims").forEach { claim ->
            requireDate(claim.text("day") ?: "", "dailyInsightClaims.day")
            listOf("cardId", "sequence").forEach { key ->
                val value = claim.number(key)
                val maximum = if (key == "cardId") Int.MAX_VALUE.toDouble() else 9_007_199_254_740_991.0
                require(value != null && value >= 1 && value <= maximum && value % 1.0 == 0.0) { "dailyInsightClaims.$key must be a positive safe integer" }
            }
            require((claim.number("earnedAt") ?: -1.0) >= 0) { "dailyInsightClaims.earnedAt must be non-negative" }
        }
    }

    private fun validateTask(task: JsonObject) {
        require(id(task) != null && task.text("title") != null) { "Reminder tasks require id and title" }
        id(task, "zoneId")
        listOf("type", "created", "repeat", "doneDate", "planDate", "schedDate", "schedTime", "alarmTime", "windowStart", "windowEnd", "counterDay", "completionCreditDate", "counterUnit").forEach { task.text(it) }
        listOf("done", "trashed", "someday", "completedToday", "reminderEnabled", "firedSched", "firedPre", "alarmFired", "counterEnabled").forEach { task.flag(it) }
        listOf("counter", "counterTarget", "counterStep", "remindBeforeMinutes", "snoozeUntil", "nextRepeatAt").forEach { task.number(it) }
        listOf("schedTime", "alarmTime", "windowStart", "windowEnd").forEach { key ->
            task.text(key)?.takeIf { it.isNotEmpty() }?.let { require(normalizedTime(it.trim()) != null) { "tasks.$key must be a valid clock time" } }
        }
        listOf("planDate", "schedDate", "doneDate", "counterDay", "completionCreditDate").forEach { key ->
            task.text(key)?.takeIf { it.isNotEmpty() }?.let { requireDate(it, "tasks.$key") }
        }
        task.array("repeatDays").forEach { value -> require(value is JsonPrimitive && !value.isString && value.booleanOrNull != null) { "tasks.repeatDays must contain booleans" } }
        task.objectValue("questDeferral")?.let { require(QuestPolicy.isValidDeferral(it)) { "tasks.questDeferral is malformed" } }
    }
    private fun validateBlock(block: JsonObject) { require(id(block, "zoneId") != null) { "Planner block requires zoneId" }; validateRange(block, "planner block") }
    private fun validateRange(range: JsonObject, path: String) {
        val start = normalizedTime(range.text("s")) ?: throw SnapshotFormatException("$path.s must be a clock time")
        val end = range.text("e")?.let { if (it in setOf("00:00", "24:00")) "24:00" else normalizedTime(it) }
            ?: throw SnapshotFormatException("$path.e must be a clock time")
        require(end > start) { "$path must end after it starts" }
    }
    private fun requireDate(day: String, path: String) { require(day.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) && try { LocalDate.parse(day).toString() == day } catch (_: Exception) { false }) { "$path must contain valid calendar dates" } }
    private fun id(value: JsonObject, key: String = "id") = FlowSnapshot.safeId(value[key], key)
    private fun numeric(value: Double): JsonPrimitive = if (value % 1.0 == 0.0 && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) JsonPrimitive(value.toLong()) else JsonPrimitive(value)
    private fun numberText(value: Double): String = numeric(value).content
    private fun JsonObject.text(key: String): String? = this[key]?.let { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: throw SnapshotFormatException("$key must be a string") }
    private fun JsonObject.flag(key: String): Boolean = this[key]?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: throw SnapshotFormatException("$key must be a boolean") } ?: false
    private fun JsonObject.number(key: String): Double? = this[key]?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.doubleOrNull?.takeIf(Double::isFinite) ?: throw SnapshotFormatException("$key must be a finite number") }
    private fun JsonObject.objectValue(key: String): JsonObject? = this[key]?.let { it as? JsonObject ?: throw SnapshotFormatException("$key must be an object") }
    private fun JsonObject.array(key: String): JsonArray = this[key]?.let { array(it, key) } ?: emptyArray
    private fun JsonObject.arrayOrDefault(key: String, fallback: JsonArray): JsonArray = this[key]?.let { array(it, key) } ?: fallback
    private fun JsonObject.objects(key: String): List<JsonObject> = objects(this[key], key)
    private fun array(value: JsonElement, path: String) = value as? JsonArray ?: throw SnapshotFormatException("$path must be an array")
    private fun objects(value: JsonElement?, path: String): List<JsonObject> = if (value == null) emptyList() else array(value, path).mapIndexed { index, item -> item as? JsonObject ?: throw SnapshotFormatException("$path[$index] must be an object") }
}
