package com.flow.adhd.domain

import java.time.LocalDate
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Day-template authoring and application over the persisted snapshot format. */
object TemplateReducer {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L
    private const val MAX_DATE_MILLIS = 8_640_000_000_000_000L
    fun reduce(snapshot: FlowSnapshot, command: FlowCommand, zone: ZoneId, now: Long, taskTypes: Set<String>): FlowCommandResult {
        if (command !is FlowCommand.CreateTemplate && command !is FlowCommand.UpdateTemplate &&
            command !is FlowCommand.DeleteTemplate && command !is FlowCommand.ApplyTemplate) return rejected("wrong-command", "Expected a day-template command")
        if (now !in 0..MAX_DATE_MILLIS || command.activityAt !in 0..MAX_DATE_MILLIS) return rejected("invalid-time", "Activity time is invalid")
        return try {
            val reducer = DayPresetTasks(taskTypes)
            when (command) {
                is FlowCommand.CreateTemplate -> create(snapshot, command, zone, now, reducer)
                is FlowCommand.UpdateTemplate -> update(snapshot, command, zone, now, reducer)
                is FlowCommand.DeleteTemplate -> delete(snapshot, command, zone, now)
                is FlowCommand.ApplyTemplate -> apply(snapshot, command, zone, now, reducer)
                else -> rejected("wrong-command", "Expected a day-template command")
            }
        } catch (e: IllegalStateException) {
            if (e.message?.contains("safe task ID") == true) rejected("id-exhausted", "No safe task ID remains")
            else rejected("invalid-payload", e.message ?: "Template payload is invalid")
        } catch (e: IllegalArgumentException) { rejected("invalid-payload", e.message ?: "Template payload is invalid") }
    }

    private fun create(s: FlowSnapshot, c: FlowCommand.CreateTemplate, zone: ZoneId, now: Long, tasks: DayPresetTasks): FlowCommandResult {
        val (template, requestedId) = parse(c.payload, tasks) ?: return rejected("invalid-payload", "Template payload must contain a name and valid blocks/tasks")
        val templates = templates(s.data) ?: return rejected("invalid-state", "Stored day templates are malformed")
        val base = requestedId ?: "tpl${c.activityAt}"
        var id = base; var suffix = 1
        while (templates.any { it.string("id") == id }) { if (requestedId != null) return rejected("duplicate-template", "Template ID already exists"); id = "$base-${suffix++}" }
        val added = JsonObject(template + ("id" to JsonPrimitive(id)))
        val changed = JsonObject(s.data + ("dayTemplates" to JsonArray(templates + added)))
        val recalculated = ZoneReducer.recalculateUsage(changed, now, zone)
        val activity = Progression.recordActivity(recalculated, c.activityAt, zone, now)
        return applied(activity.state, activity.events)
    }

    private fun update(s: FlowSnapshot, c: FlowCommand.UpdateTemplate, zone: ZoneId, now: Long, tasks: DayPresetTasks): FlowCommandResult {
        val templates = templates(s.data) ?: return rejected("invalid-state", "Stored day templates are malformed")
        val index = templates.indexOfFirst { it.string("id") == c.templateId }
        if (index < 0) return rejected("template-not-found", "Template to update does not exist")
        if (c.payload.keys.any { it !in setOf("id", "name", "blocks", "tasks") } || (c.payload.string("id")?.let { it != c.templateId } == true)) return rejected("invalid-payload", "Template update has unknown or conflicting fields")
        val parsed = parse(c.payload, tasks) ?: return rejected("invalid-payload", "Template payload must contain a name and valid blocks/tasks")
        val changedTemplates = templates.toMutableList()
        val priorBlocks = (templates[index]["blocks"] as? JsonArray).orEmpty()
        val nextBlocks = (parsed.first["blocks"] as JsonArray).mapIndexed { i, element ->
            val prior = priorBlocks.getOrNull(i) as? JsonObject ?: JsonObject(emptyMap())
            JsonObject(prior + (element as JsonObject))
        }
        changedTemplates[index] = JsonObject(templates[index] + parsed.first + mapOf("blocks" to JsonArray(nextBlocks), "id" to JsonPrimitive(c.templateId)))
        val changed = freeze(c.templateId, s.data, changedTemplates) ?: return rejected("invalid-state", "Stored planner rules are malformed")
        val recalculated = ZoneReducer.recalculateUsage(changed, now, zone)
        val activity = Progression.recordActivity(recalculated, c.activityAt, zone, now)
        return applied(activity.state, activity.events)
    }

    private fun delete(s: FlowSnapshot, c: FlowCommand.DeleteTemplate, zone: ZoneId, now: Long): FlowCommandResult {
        val templates = templates(s.data) ?: return rejected("invalid-state", "Stored day templates are malformed")
        if (templates.none { it.string("id") == c.templateId }) return applied(s.data)
        val changed = freeze(c.templateId, s.data, templates.filterNot { it.string("id") == c.templateId })
            ?: return rejected("invalid-state", "Stored planner rules are malformed")
        return applied(ZoneReducer.recalculateUsage(changed, now, zone))
    }

    private fun apply(s: FlowSnapshot, c: FlowCommand.ApplyTemplate, zone: ZoneId, now: Long, preset: DayPresetTasks): FlowCommandResult {
        val templateList = templates(s.data) ?: return rejected("invalid-state", "Stored day templates are malformed")
        val template = templateList.firstOrNull { it.string("id") == c.templateId } ?: return rejected("template-not-found", "Template to apply does not exist")
        val start = parseDate(c.startDate) ?: return rejected("invalid-date", "Start date must be a valid YYYY-MM-DD date")
        if (c.spanDays !in 1..3660) return rejected("invalid-span", "Span must be between 1 and 3660 days")
        val end = try { start.plusDays(c.spanDays.toLong() - 1) } catch (_: Exception) { return rejected("invalid-date", "Date range exceeds the calendar") }
        if (end.year > 9999) return rejected("invalid-date", "Date range exceeds the supported calendar")
        val blocks = template["blocks"] as? JsonArray ?: return rejected("invalid-template", "Template blocks are invalid")
        if (blocks.any { it !is JsonObject }) return rejected("invalid-template", "Template blocks are invalid")
        val entries = if (template.containsKey("tasks")) template["tasks"] as? JsonArray ?: return rejected("invalid-template", "Template tasks are invalid") else JsonArray(emptyList())
        if (entries.any { it !is JsonObject }) return rejected("invalid-template", "Template tasks are invalid")
        if (entries.any { preset.sanitize(it as JsonObject) == null }) return rejected("invalid-template", "Template task entries are invalid")
        var taskList = s.tasks.map { it.raw }; var nid = s.data.number("nid")?.toLong() ?: 300L
        val plan = (s.data["planDayZones"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        for (i in 0 until c.spanDays) {
            val date = start.plusDays(i.toLong()).toString()
            plan[date] = JsonArray(blocks.map { block ->
                val obj = block as? JsonObject ?: return rejected("invalid-template", "Template block is invalid")
                JsonObject(obj.filterKeys { it in setOf("zoneId", "s", "e") })
            })
            val appended = preset.append(taskList, nid, c.templateId, entries.map { it as JsonObject }, date,
                s.zones, c.activityAt)
            taskList = appended.tasks; nid = appended.nid
            if (nid !in 0..MAX_SAFE_ID) return rejected("id-exhausted", "No safe task ID remains")
        }
        val changed = JsonObject(s.data + mapOf("planDayZones" to JsonObject(plan), "tasks" to JsonArray(taskList), "nid" to JsonPrimitive(nid)))
        val recalculated = ZoneReducer.recalculateUsage(changed, now, zone)
        val activity = Progression.recordActivity(recalculated, c.activityAt, zone, now)
        return applied(activity.state, activity.events)
    }

    private fun parse(payload: JsonObject, preset: DayPresetTasks): Pair<JsonObject, String?>? {
        if (payload.keys.any { it !in setOf("id", "name", "blocks", "tasks") }) return null
        val id = payload.string("id")?.takeIf { it.isNotBlank() }
        if (payload.containsKey("id") && id == null) return null
        val name = payload.string("name")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val blocks = payload["blocks"] as? JsonArray ?: return null
        val normalized = blocks.map { element ->
            val block = element as? JsonObject ?: return null
            if (block.keys.any { it !in setOf("zoneId", "s", "e") }) return null
            val zid = block.long("zoneId")?.takeIf { it > 0 } ?: return null
            val start = block.string("s") ?: return null; val end = block.string("e") ?: return null
            if (!validRange(start, end)) return null
            JsonObject(block + mapOf("zoneId" to JsonPrimitive(zid), "s" to JsonPrimitive(start), "e" to JsonPrimitive(end)))
        }
        val entries = if (payload.containsKey("tasks")) payload["tasks"] as? JsonArray ?: return null else JsonArray(emptyList())
        val safeEntries = mutableListOf<JsonObject>()
        val usedEntryIds = mutableSetOf<String>()
        for (element in entries) {
            val source = element as? JsonObject ?: return null
            val safe = preset.sanitize(source) ?: return null
            val base = safe.string("id") ?: return null
            var candidate = base; var suffix = 1
            while (candidate in usedEntryIds) candidate = "$base-${suffix++}"
            usedEntryIds += candidate
            safeEntries += JsonObject(safe + ("id" to JsonPrimitive(candidate)))
        }
        return (JsonObject(mapOf("name" to JsonPrimitive(name), "blocks" to JsonArray(normalized), "tasks" to JsonArray(safeEntries))) to id)
    }

    private fun freeze(id: String, state: JsonObject, templates: List<JsonObject>): JsonObject? {
        val manual = (state["planDayZones"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val rawRules = if (state.containsKey("planRules")) state["planRules"] as? JsonArray ?: return null else JsonArray(emptyList())
        if (rawRules.any { it !is JsonObject }) return null
        val rules = rawRules.map { it as JsonObject }
        if (rules.any { rule ->
                val start = rule.string("start")?.let(::parseDate)
                val end = rule.string("end")?.let(::parseDate)
                val days = rule["days"] as? JsonArray
                start == null || end == null || end < start || days == null || days.isEmpty() || days.any { it !is JsonObject }
            }) return null
        for (rule in rules) {
            val start = rule.string("start")?.let(::parseDate) ?: continue; val end = rule.string("end")?.let(::parseDate) ?: continue
            if (end < start || (rule["days"] as? JsonArray).isNullOrEmpty()) continue
            var date = start
            while (date <= end) {
                val key = date.toString()
                val winning = PlannerProjection.ruleFor(state, key)
                val entry = PlannerProjection.ruleEntry(winning, key)
                if (key !in manual && winning == rule && entry?.string("tplId") == id) {
                    val template = (state["dayTemplates"] as JsonArray).map { it as JsonObject }.firstOrNull { it.string("id") == id }
            val rawBlocks = template?.get("blocks") as? JsonArray ?: return null
            if (rawBlocks.any { it !is JsonObject }) return null
            manual[key] = JsonArray(rawBlocks.map { block -> JsonObject((block as JsonObject).filterKeys { it in setOf("zoneId", "s", "e") }) })
                }
                date = date.plusDays(1)
            }
        }
        val out = state.toMutableMap(); out["dayTemplates"] = JsonArray(templates)
        if (manual.isNotEmpty() || state.containsKey("planDayZones")) out["planDayZones"] = JsonObject(manual)
        return JsonObject(out)
    }

    private fun templates(state: JsonObject): List<JsonObject>? {
        if (!state.containsKey("dayTemplates")) return emptyList()
        val array = state["dayTemplates"] as? JsonArray ?: return null
        if (array.any { it !is JsonObject }) return null
        val values = array.map { it as JsonObject }
        if (values.any { template ->
                template.string("id") == null || template.string("name") == null ||
                    template["blocks"] !is JsonArray || (template["blocks"] as JsonArray).any { block ->
                        val obj = block as? JsonObject ?: return@any true
                        val zoneId = obj.long("zoneId")
                        val start = obj.string("s"); val end = obj.string("e")
                        zoneId == null || zoneId <= 0 || start == null || end == null || !validRange(start, end)
                    } ||
                    (template.containsKey("tasks") && (template["tasks"] !is JsonArray || (template["tasks"] as JsonArray).any { it !is JsonObject }))
            }) return null
        return values
    }
    private fun parseDate(value: String): LocalDate? {
        if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)) return null
        return runCatching { LocalDate.parse(value).takeIf { it.toString() == value } }.getOrNull()
    }
    private fun validRange(s: String, e: String): Boolean {
        fun mins(v: String, end: Boolean): Int? {
            if (end && (v == "24:00" || v == "00:00")) return 1440
            val m = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(v) ?: return null
            val h = m.groupValues[1].toIntOrNull() ?: return null; val n = m.groupValues[2].toIntOrNull() ?: return null
            if (n !in 0..59 || h !in 0..23) return null
            return h * 60 + n
        }
        val a = mins(s, false) ?: return false; val b = mins(e, true) ?: return false; return b > a
    }
    private fun JsonObject.string(k: String) = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun JsonObject.number(k: String) = (this[k] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun applied(state: JsonObject, events: List<FlowEvent> = emptyList()) = FlowCommandResult.Applied(FlowSnapshot.fromData(state), events)
}
