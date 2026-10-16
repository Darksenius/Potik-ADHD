package com.flow.adhd.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/** Authored cycles and their applied date rules; task instances remain independent by date. */
object WeekTemplateReducer {
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long, taskTypes: Set<String>): FlowCommandResult {
        return try {
            val data = snapshot.data.toMutableMap()
            val templates = objects(data["weekTemplates"])
            when (command) {
                is FlowCommand.CreateWeekTemplate, is FlowCommand.UpdateWeekTemplate -> {
                    val payload = if (command is FlowCommand.CreateWeekTemplate) command.payload else (command as FlowCommand.UpdateWeekTemplate).payload
                    require(payload.keys.all { it in setOf("id", "name", "days") }) { "Unknown week preset field" }
                    val name = payload.text("name")?.trim().orEmpty()
                    require(name.isNotEmpty()) { "Week preset name is required" }
                    val days = payload["days"] as? JsonArray ?: throw IllegalArgumentException("Cycle days must be an array")
                    require(days.isNotEmpty()) { "A cycle must contain a day" }
                    days.forEach { value ->
                        val entry = value as? JsonObject ?: throw IllegalArgumentException("Cycle day must be an object")
                        require(entry.text("tplId") != null) { "Each cycle day needs a preset ID, which may be empty" }
                        val rest = entry["rest"]
                        require(rest == null || rest is JsonPrimitive && !rest.isString && rest.booleanOrNull != null) { "Rest flag must be boolean" }
                    }
                    val updating = command as? FlowCommand.UpdateWeekTemplate
                    val existing = updating?.let { cmd -> templates.indexOfFirst { it.text("id") == cmd.templateId } } ?: -1
                    if (updating != null && existing < 0) return unchanged(snapshot)
                    val requested = updating?.templateId ?: payload.text("id")?.takeIf { it.isNotEmpty() }
                    require(updating == null || payload.text("id") == null || payload.text("id") == updating.templateId) { "Preset IDs must match" }
                    val id = requested ?: uniqueId("wk${command.activityAt}", templates)
                    require(updating != null || templates.none { it.text("id") == id }) { "Preset ID already exists" }
                    val authored = JsonObject((templates.getOrNull(existing) ?: JsonObject(emptyMap())) + payload + mapOf("id" to JsonPrimitive(id), "name" to JsonPrimitive(name)))
                    data["weekTemplates"] = JsonArray(if (existing < 0) templates + authored else templates.mapIndexed { i, old -> if (i == existing) authored else old })
                }
                is FlowCommand.DeleteWeekTemplate -> data["weekTemplates"] = JsonArray(templates.filterNot { it.text("id") == command.templateId })
                is FlowCommand.ApplyWeekTemplate -> {
                    val template = templates.firstOrNull { it.text("id") == command.templateId } ?: return unchanged(snapshot)
                    val start = parseDay(command.startDate) ?: throw IllegalArgumentException("Start date is invalid")
                    require(command.spanDays in 1..3660) { "A schedule must cover 1 to 3660 days" }
                    val end = start.plusDays(command.spanDays - 1L)
                    require(end.year in 0..9999) { "End date is outside the calendar" }
                    val cycle = objects(template["days"])
                    require(cycle.isNotEmpty()) { "A cycle must contain a day" }
                    val manual = objectMap(data["planDayZones"])
                    val rest = objectMap(data["planRestDays"])
                    val off = objectMap(data["planDayOff"])
                    val dates = (0 until command.spanDays).map { start.plusDays(it.toLong()).toString() }
                    dates.forEach { day -> manual.remove(day); rest.remove(day); off.remove(day) }
                    val rules = objects(data["planRules"]).filterNot { rule ->
                        val from = rule.text("start"); val to = rule.text("end")
                        from != null && to != null && from >= command.startDate && to <= end.toString()
                    }
                    val rule = JsonObject(mapOf("id" to JsonPrimitive(uniqueId("pr${command.activityAt}", rules)),
                        "name" to (template["name"] ?: JsonPrimitive("")), "start" to JsonPrimitive(command.startDate),
                        "end" to JsonPrimitive(end.toString()), "days" to JsonArray(cycle)))
                    var tasks = snapshot.tasks.map { it.raw }
                    var nid = (data["nid"] as? JsonPrimitive)?.longOrNull ?: 300L
                    val dayTemplates = objects(data["dayTemplates"])
                    val materializer = DayPresetTasks(taskTypes)
                    dates.forEach { day ->
                        val id = PlannerProjection.ruleEntry(rule, day)?.text("tplId")
                        val preset = dayTemplates.firstOrNull { it.text("id") == id }
                        if (preset != null) {
                            val appended = materializer.append(tasks, nid, id!!, objects(preset["tasks"]), day, snapshot.zones, command.activityAt)
                            tasks = appended.tasks; nid = appended.nid
                        }
                    }
                    data["planDayZones"] = JsonObject(manual); data["planRestDays"] = JsonObject(rest); data["planDayOff"] = JsonObject(off)
                    data["planRules"] = JsonArray(rules + rule); data["tasks"] = JsonArray(tasks); data["nid"] = JsonPrimitive(nid)
                    val derived = ZoneReducer.recalculateUsage(JsonObject(data), now, zone)
                    val activity = Progression.recordActivity(derived, command.activityAt, zone, now)
                    return FlowCommandResult.Applied(FlowSnapshot.fromData(activity.state), activity.events)
                }
                is FlowCommand.DeletePlanRule -> {
                    val rules = objects(data["planRules"])
                    if (command.index !in rules.indices) return unchanged(snapshot)
                    data["planRules"] = JsonArray(rules.filterIndexed { index, _ -> index != command.index })
                    return applied(ZoneReducer.recalculateUsage(JsonObject(data), now, zone))
                }
                else -> return FlowCommandResult.Rejected("unsupported-command", "Not a week preset command")
            }
            if (data == snapshot.data) unchanged(snapshot) else applied(JsonObject(data))
        } catch (e: IllegalArgumentException) {
            FlowCommandResult.Rejected("invalid-week-template", e.message ?: "Week preset is invalid")
        } catch (e: IllegalStateException) {
            FlowCommandResult.Rejected("id-exhausted", e.message ?: "No safe task ID remains")
        }
    }

    private fun parseDay(value: String): LocalDate? = if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) null else
        runCatching { LocalDate.parse(value).takeIf { it.toString() == value } }.getOrNull()
    private fun uniqueId(base: String, objects: List<JsonObject>): String {
        val ids = objects.mapNotNull { it.text("id") }.toHashSet()
        var candidate = base; var suffix = 1
        while (candidate in ids) candidate = "$base-${suffix++}"
        return candidate
    }
    private fun objects(value: JsonElement?): List<JsonObject> {
        if (value == null) return emptyList()
        require(value is JsonArray) { "Stored list must be an array" }
        return value.map { it as? JsonObject ?: throw IllegalArgumentException("Stored list contains an invalid object") }
    }
    private fun objectMap(value: JsonElement?): MutableMap<String, JsonElement> {
        if (value == null) return mutableMapOf()
        require(value is JsonObject) { "Stored day map must be an object" }
        return value.toMutableMap()
    }
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun unchanged(snapshot: FlowSnapshot) = FlowCommandResult.Applied(snapshot, emptyList())
    private fun applied(data: JsonObject) = FlowCommandResult.Applied(FlowSnapshot.fromData(data), emptyList())
}
