package com.flow.adhd.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.longOrNull

/** Installs the authored, introductory two-day demo without replacing user state. */
object DemoActions {
    private const val PRESET_ID = "potik-intro-v1"
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L
    private val labelFormat = DateTimeFormatter.ofPattern("d MMMM", Locale("uk", "UA"))

    fun clear(snapshot: FlowSnapshot, command: FlowCommand.ClearDemoTasks, zone: ZoneId): FlowCommandResult.Applied {
        val tasks = snapshot.data.array("tasks") ?: JsonArray(emptyList())
        val removeIds = tasks.mapNotNull { task ->
            val obj = task as? JsonObject ?: return@mapNotNull null
            if (obj.string("dayPresetKey")?.startsWith("$PRESET_ID:") == true) obj.safeLong("id") else null
        }.toSet()
        if (removeIds.isEmpty()) return FlowCommandResult.Applied(snapshot, emptyList())
        val changed = snapshot.data.toMutableMap()
        changed["tasks"] = JsonArray(tasks.filterNot { task -> (task as? JsonObject)?.safeLong("id") in removeIds })
        val history = snapshot.data.array("taskHistory") ?: JsonArray(emptyList())
        changed["taskHistory"] = TaskHistory.seedKnown(history, tasks, zone)
        val priorities = snapshot.data.array("priorities")
        if (priorities != null) changed["priorities"] = JsonArray(priorities.map { value -> if (value.safeLong() in removeIds) JsonNull else value })
        return FlowCommandResult.Applied(FlowSnapshot.fromData(JsonObject(changed)), emptyList())
    }

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand.InstallDemoPreset, zone: ZoneId, now: Long, taskTypes: Set<String>): FlowCommandResult {
      return try {
        if (command.activityAt > 8_640_000_000_000_000L || now > 8_640_000_000_000_000L) return rejected("invalid-time", "Activity time is invalid")
        val payload = command.payload
        val presetId = payload.string("id")?.takeIf { it.isNotBlank() } ?: return rejected("invalid-demo-preset", "Preset id is required")
        val today = Instant.ofEpochMilli(command.activityAt).atZone(zone).toLocalDate()
        val tomorrow = today.plusDays(1)
        val todayKey = today.toString()
        val tomorrowKey = tomorrow.toString()
        val zoneDefs = payload.array("zones")?.map { it as? JsonObject ?: fail("zones must contain objects") }
            ?: return rejected("invalid-demo-preset", "Preset zones are required")
        val currentZones = snapshot.zones
        val zones = currentZones.toMutableList()
        val zoneIds = linkedMapOf<String, Long>()
        val occupied = referencedZoneIds(snapshot.data, currentZones).toMutableSet()
        for (definition in zoneDefs) {
            val key = definition.string("key")?.takeIf(String::isNotBlank) ?: fail("Zone key is required")
            val name = definition.string("name")?.takeIf(String::isNotBlank) ?: fail("Zone name is required")
            val existing = zones.firstOrNull { it.string("nm") == name }
            if (existing != null) {
                zoneIds[key] = existing.safeLong("id") ?: fail("Existing demo zone has an invalid id")
                continue
            }
            var id = 1L
            while (id in occupied) id++
            if (id >= MAX_SAFE_ID) return rejected("id-exhausted", "No safe zone ID remains")
            occupied += id
            zoneIds[key] = id
            val slot = JsonObject(mapOf("s" to (definition["start"] ?: JsonPrimitive("07:00")), "e" to (definition["end"] ?: JsonPrimitive("12:00"))))
            zones += JsonObject(mapOf(
                "id" to JsonPrimitive(id), "nm" to JsonPrimitive(name),
                "color" to (definition["color"] ?: JsonPrimitive("#888888")),
                "slots" to JsonArray(listOf(slot)),
                "desc" to (definition["description"] ?: JsonPrimitive("")), "prio" to JsonPrimitive(1),
                "active" to JsonPrimitive(true), "off" to JsonPrimitive(false), "bound" to JsonPrimitive(true),
            ))
        }

        val definitions = (payload.array("today") ?: fail("today entries are required")) to (payload.array("tomorrow") ?: fail("tomorrow entries are required"))
        val todayEntries = definitions.first.map { makeEntry(it, zoneIds) }
        val tomorrowEntries = definitions.second.map { makeEntry(it, zoneIds) }
        val nid = snapshot.data.safeLong("nid")?.coerceAtLeast(300) ?: maxOf(300L, (snapshot.tasks.maxOfOrNull { it.id } ?: 299L) + 1L)
        val appender = DayPresetTasks(taskTypes)
        val first = appender.append(snapshot.tasks.map { it.raw }, nid, presetId, todayEntries, todayKey, zones, command.activityAt)
        val second = appender.append(first.tasks, first.nid, presetId, tomorrowEntries, tomorrowKey, zones, command.activityAt)
        val specById = (definitions.first + definitions.second).mapNotNull { it as? JsonObject }.associateBy { it.string("id") }
        val previousKeys = snapshot.tasks.mapNotNull { it.raw.string("dayPresetKey") }.toSet()
        val tasks = second.tasks.map { task ->
            val key = task.string("dayPresetKey") ?: return@map task
            if (key !in previousKeys && key.startsWith("$presetId:")) {
                val specId = key.removePrefix("$presetId:").substringBefore(':')
                val spec = specById[specId] ?: return@map task
                val fields = spec["fields"] as? JsonObject ?: JsonObject(emptyMap())
                JsonObject((task + fields + mapOf("repeat" to JsonPrimitive(spec.string("repeat") ?: "none"), "title" to JsonPrimitive("Приклад · ${task.string("title") ?: ""}"))))
            } else task
        }

        val folder = payload["folder"] as? JsonObject ?: fail("folder definition is required")
        val folderId = folder.string("id")?.takeIf(String::isNotBlank) ?: fail("folder id is required")
        val folders = (snapshot.data.array("folders") ?: JsonArray(emptyList())).toMutableList()
        if (folders.none { (it as? JsonObject)?.string("id") == folderId }) {
            folders += JsonObject(mapOf("id" to JsonPrimitive(folderId), "nm" to (folder["name"] ?: JsonPrimitive("DEMO")), "ico" to (folder["icon"] ?: JsonPrimitive("🧭"))))
        }
        val notes = (snapshot.data.array("qnotes") ?: JsonArray(emptyList())).map { it as? JsonObject ?: fail("qnotes must contain objects") }.toMutableList()
        val noteDefs = payload.array("notes") ?: JsonArray(emptyList())
        var qnid = maxOf(snapshot.data.safeLong("qnid") ?: 300L, (notes.mapNotNull { it.safeLong("id") }.maxOrNull() ?: 299L) + 1)
        for (item in noteDefs) {
            val spec = item as? JsonObject ?: fail("notes must contain objects")
            val text = spec.string("text")?.replace("{today}", today.format(labelFormat))?.replace("{tomorrow}", tomorrow.format(labelFormat)) ?: fail("note text is required")
            val time = spec.string("time") ?: ""
            if (notes.any { it.string("folder") == folderId && it.string("date") == "" && it.string("txt") == text }) continue
            while (notes.any { it.safeLong("id") == qnid }) qnid++
            if (qnid >= MAX_SAFE_ID) return rejected("id-exhausted", "No safe note ID remains")
            notes += JsonObject(mapOf("id" to JsonPrimitive(qnid++), "txt" to JsonPrimitive(text), "time" to JsonPrimitive(time), "date" to JsonPrimitive(""), "folder" to JsonPrimitive(folderId)))
        }
        val overrides = when (val value = snapshot.data["planDayZones"]) {
            null -> mutableMapOf()
            is JsonObject -> value.toMutableMap()
            else -> fail("planDayZones must be an object")
        }
        for (date in listOf(todayKey, tomorrowKey)) {
            val blocks: MutableList<JsonElement> = (overrides[date] as? JsonArray)?.let { it.toMutableList() }
                ?: PlannerProjection.dayBlocks(snapshot.data, date).map { it as JsonElement }.toMutableList()
            zoneDefs.forEach { def ->
                val id = zoneIds[def.string("key")] ?: fail("Unknown zone reference")
                val start = def.string("start") ?: "07:00"
                val end = def.string("end") ?: "12:00"
                if (blocks.none { block -> (block as? JsonObject)?.safeLong("zoneId") == id && (block as? JsonObject)?.string("s") == start && (block as? JsonObject)?.string("e") == end }) {
                    blocks.add(JsonObject(mapOf("zoneId" to JsonPrimitive(id), "s" to JsonPrimitive(start), "e" to JsonPrimitive(end))))
                }
            }
            overrides[date] = JsonArray(blocks)
        }
        val changed = snapshot.data.toMutableMap().apply {
            put("tasks", JsonArray(tasks)); put("nid", JsonPrimitive(second.nid)); put("zones", JsonArray(zones))
            put("folders", JsonArray(folders)); put("qnotes", JsonArray(notes)); put("qnid", JsonPrimitive(qnid))
            put("planDayZones", JsonObject(overrides))
        }
        FlowCommandResult.Applied(FlowSnapshot.fromData(JsonObject(changed)), emptyList())
      } catch (error: Exception) {
        rejected("invalid-demo-preset", error.message ?: "Demo preset is invalid; current data is unchanged")
      }
    }

    private fun makeEntry(value: JsonElement, zoneIds: Map<String, Long>): JsonObject {
        val spec = value as? JsonObject ?: fail("Task definitions must be objects")
        val id = spec.string("id")?.takeIf(String::isNotBlank) ?: fail("Task id is required")
        val task = linkedMapOf<String, JsonElement>(
            "title" to JsonPrimitive(spec.string("title") ?: fail("Task title is required")),
            "type" to JsonPrimitive(spec.string("type") ?: fail("Task type is required")),
            "note" to JsonPrimitive(spec.string("note") ?: ""),
            "tags" to JsonArray(listOf(JsonPrimitive("DEMO"))),
        )
        spec.string("zone")?.let { zone -> task["zoneId"] = JsonPrimitive(zoneIds[zone] ?: fail("Unknown zone: $zone")) }
        (spec["fields"] as? JsonObject)?.let { task.putAll(it) }
        return JsonObject(mapOf("id" to JsonPrimitive(id), "task" to JsonObject(task)))
    }

    private fun referencedZoneIds(data: JsonObject, zones: List<JsonObject>): Set<Long> = buildSet {
        zones.forEach { it.safeLong("id")?.let(::add) }
        data.safeLong("lastZoneId")?.let(::add)
        data.array("tasks")?.forEach { task ->
            val obj = task as? JsonObject ?: return@forEach
            listOf("zoneId").forEach { obj.safeLong(it)?.let(::add) }
            listOf("questDeferral", "completionDeferral").forEach { field -> (obj[field] as? JsonObject)?.let { def -> listOf("sourceZoneId", "targetZoneId").forEach { def.safeLong(it)?.let(::add) } } }
        }
        data.array("taskHistory")?.forEach { (it as? JsonObject)?.safeLong("zoneId")?.let(::add) }
        data.objectValue("planDayZones")?.values?.forEach { (it as? JsonArray)?.forEach { block -> (block as? JsonObject)?.safeLong("zoneId")?.let(::add) } }
        data.objectValue("planDayOff")?.values?.forEach { (it as? JsonArray)?.forEach { value -> value.safeLong()?.let(::add) } }
        listOf("dayTemplates", "planRules").forEach { field -> data.array(field)?.forEach { template ->
            val obj = template as? JsonObject ?: return@forEach
            (obj["blocks"] as? JsonArray)?.forEach { (it as? JsonObject)?.safeLong("zoneId")?.let(::add) }
            (obj["tasks"] as? JsonArray)?.forEach { item -> ((item as? JsonObject)?.get("task") as? JsonObject)?.safeLong("zoneId")?.let(::add) }
        } }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.safeLong(key: String): Long? = this[key].safeLong()
    private fun JsonElement?.safeLong(): Long? = (this as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it in 0..MAX_SAFE_ID }
    private fun JsonObject.array(key: String): JsonArray? = when (val item = this[key]) { null -> null; is JsonArray -> item; else -> fail("$key must be an array") }
    private fun JsonObject.objectValue(key: String): JsonObject? = when (val item = this[key]) { null -> null; is JsonObject -> item; else -> fail("$key must be an object") }
    private fun rejected(code: String, message: String) = FlowCommandResult.Rejected(code, message)
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
