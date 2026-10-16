package com.flow.adhd.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.ZoneId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Pure, transactional merge of a validated backup into the current persisted snapshot. */
object SnapshotMerge {
    private const val MAX_SAFE_ID = 9_007_199_254_740_991L

    fun merge(current: FlowSnapshot, imported: JsonObject, zone: ZoneId, now: Long, commandId: String): FlowCommandResult {
        if (commandId.isBlank() || now < 0) return FlowCommandResult.Rejected("invalid-merge", "Merge command metadata is invalid")
        return try { mergeChecked(current, imported, zone, now, commandId) }
        catch (e: MergeConflict) { FlowCommandResult.Rejected("merge-conflict", e.message ?: "Imported data conflicts with current data") }
        catch (e: Exception) { FlowCommandResult.Rejected("invalid-merge", e.message ?: "Imported data cannot be merged") }
    }

    private fun mergeChecked(current: FlowSnapshot, imported: JsonObject, zone: ZoneId, now: Long, commandId: String): FlowCommandResult {
        val d = imported
        val s = current.data
        // A malformed collection must reject the whole operation; silently treating it as empty loses backup data.
        val listFields = listOf("zones", "folders", "tasks", "taskHistory", "priorities", "qnotes", "rareEvents", "focusChips", "dayTemplates", "weekTemplates", "planRules", "progressDays", "dailyInsightClaims", "recur")
        val objectFields = listOf("planItems", "planDayZones", "planDayOff", "planRestDays", "planSchedules", "planDayLog")
        listOf(s, d).forEach { root ->
            val source = if (root === s) "Stored" else "Imported"
            listFields.forEach { key -> root[key]?.let { if (it !is JsonArray) throw MergeConflict("$source '$key' must be a list") } }
            objectFields.forEach { key -> root[key]?.let { if (it !is JsonObject) throw MergeConflict("$source '$key' must be an object") } }
            listOf("zones", "folders", "tasks", "taskHistory", "qnotes", "rareEvents", "dayTemplates", "weekTemplates", "planRules", "dailyInsightClaims", "recur").forEach { key ->
                (root[key] as? JsonArray)?.forEachIndexed { index, item ->
                    if (item !is JsonObject) throw MergeConflict("$source '$key[$index]' must be an object")
                }
            }
            listOf("planItems", "planDayZones", "planDayOff").forEach { key ->
                (root[key] as? JsonObject)?.forEach { (day, value) ->
                    val entries = value as? JsonArray ?: throw MergeConflict("$source '$key.$day' must be a list")
                    entries.forEachIndexed { index, entry ->
                        val valid = when (key) {
                            "planDayOff" -> (entry as? JsonPrimitive)?.let { !it.isString && it.longOrNullSafe() != null } == true
                            else -> entry is JsonObject
                        }
                        if (!valid) throw MergeConflict("$source '$key.$day[$index]' is invalid")
                    }
                }
            }
        }
        fun arr(root: JsonObject, key: String): MutableList<JsonElement> = when (val value = root[key]) {
            null -> mutableListOf()
            is JsonArray -> value.toMutableList()
            else -> throw MergeConflict("Stored '$key' must be a list")
        }
        fun obj(root: JsonObject, key: String): MutableMap<String, JsonElement> = when (val value = root[key]) {
            null -> mutableMapOf()
            is JsonObject -> value.toMutableMap()
            else -> throw MergeConflict("Stored '$key' must be an object")
        }
        fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun stringValue(value: JsonElement?) = (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun id(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNullSafe()
        fun uniqueAppend(dst: MutableList<JsonElement>, src: List<JsonElement>, same: (JsonElement, JsonElement) -> Boolean) { src.forEach { item -> if (dst.none { same(it, item) }) dst.add(item) } }
        val out = s.toMutableMap()
        val zones = arr(s, "zones")
        val zoneMap = mutableMapOf<Long, Long>()
        val retained = mutableSetOf<Long>()
        fun collectZoneReferences(value: JsonElement) {
            when (value) {
                is JsonObject -> value.forEach { (key, child) ->
                    if (key == "zoneId" || key == "sourceZoneId" || key == "targetZoneId" || key == "lastZoneId") {
                        (child as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNullSafe()?.takeIf { it > 0 }?.let(retained::add)
                    }
                    if (key == "planDayOff" && child is JsonObject) {
                        child.values.filterIsInstance<JsonArray>().flatten().forEach { zone ->
                            (zone as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNullSafe()?.takeIf { it > 0 }?.let(retained::add)
                        }
                    }
                    collectZoneReferences(child)
                }
                is JsonArray -> value.forEach(::collectZoneReferences)
                else -> Unit
            }
        }
        listOf(s, d).forEach(::collectZoneReferences)
        (arr(s, "zones") + arr(d, "zones")).forEach { zone ->
            val zoneObject = zone as? JsonObject ?: throw MergeConflict("Stored zones contain an invalid item")
            id(zoneObject, "id")?.takeIf { it > 0 }?.let(retained::add)
        }
        val inZones = arr(d, "zones")
        inZones.forEach { el -> val z = el as? JsonObject ?: throw MergeConflict("Imported zones contain an invalid item")
            val old = id(z, "id") ?: throw MergeConflict("Imported zone has no valid ID")
            val name = str(z, "nm") ?: throw MergeConflict("Imported zone has no name")
            val existing = zones.mapNotNull { it as? JsonObject }.firstOrNull { str(it, "nm") == name }
            if (existing != null) zoneMap[old] = id(existing, "id") ?: old else {
                var fresh = 1L; while (fresh in retained) fresh++
                if (fresh > MAX_SAFE_ID) throw MergeConflict("No safe zone ID remains")
                retained.add(fresh); zoneMap[old] = fresh
                zones.add(JsonObject(z + ("id" to JsonPrimitive(fresh))))
            }
        }
        val templateMap = mutableMapOf<String, String>()
        val dayTemplates = arr(s, "dayTemplates")
        val week = arr(s, "weekTemplates")
        val allTemplateIds = (dayTemplates + week).mapNotNull { (it as? JsonObject)?.let { template -> str(template, "id") } }.toMutableSet()
        val dayTemplateIdsByName = linkedMapOf<String, String>()
        dayTemplates.forEach { (it as? JsonObject)?.let { template ->
            val name = str(template, "name")
            val id = str(template, "id")
            if (name != null && id != null) dayTemplateIdsByName.putIfAbsent(name, id)
        } }
        fun newTemplateId(prefix: String, index: Int): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$commandId|$now|$prefix|$index".toByteArray(StandardCharsets.UTF_8))
                .take(8).joinToString("") { "%02x".format(it) }
            var candidate = "$prefix$digest"
            var suffix = 1
            while (candidate in allTemplateIds) candidate = "$prefix$digest-${suffix++}"
            allTemplateIds.add(candidate)
            return candidate
        }
        arr(d, "dayTemplates").forEachIndexed { index, raw ->
            val template = raw as? JsonObject ?: throw MergeConflict("Imported day templates contain an invalid item")
            val id = str(template, "id") ?: throw MergeConflict("Imported template has no ID")
            val name = str(template, "name") ?: throw MergeConflict("Imported template has no name")
            val target = dayTemplateIdsByName[name] ?: newTemplateId("tpl", index).also { dayTemplateIdsByName[name] = it }
            templateMap[id] = target
        }
        fun zid(v: JsonElement?): JsonElement? { val n = (v as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNullSafe(); return if (n == null) v else JsonPrimitive(zoneMap[n] ?: n) }
        fun occurrence(v: JsonElement?): JsonElement? {
            val p = v as? JsonPrimitive ?: return v
            if (!p.isString) return v
            val regex = Regex("(^|\\|zone:)(\\d{4}-\\d{2}-\\d{2}:)(\\d+)(:)")
            return JsonPrimitive(regex.replace(p.content) { m -> m.groupValues[1] + m.groupValues[2] + (zoneMap[m.groupValues[3].toLongOrNull()] ?: m.groupValues[3].toLong()).toString() + m.groupValues[4] })
        }
        fun remap(o: JsonObject): JsonObject {
            val m = o.toMutableMap()
            listOf("zoneId", "sourceZoneId", "targetZoneId").forEach { k -> if (k in m) m[k] = zid(m[k]) ?: JsonNull }
            str(o, "dayPresetKey")?.let { key ->
                val separator = key.indexOf(':')
                if (separator >= 0) m["dayPresetKey"] = JsonPrimitive((templateMap[key.substring(0, separator)] ?: key.substring(0, separator)) + key.substring(separator))
            }
            listOf("questDeferral", "completionDeferral").forEach { k -> (m[k] as? JsonObject)?.let { deferral ->
                val remapped=deferral.toMutableMap()
                listOf("sourceZoneId","targetZoneId").forEach { field -> if(field in remapped) remapped[field]=zid(remapped[field]) ?: JsonNull }
                m[k]=JsonObject(remapped)
            } }
            listOf("zoneOccurrenceKey", "occurrenceId", "id").forEach { k -> if (k in m) m[k] = occurrence(m[k]) ?: JsonNull }
            return JsonObject(m)
        }

        val folders = arr(s, "folders")
        arr(d, "folders").forEach { el -> val f = el as? JsonObject ?: throw MergeConflict("Imported folders contain an invalid item"); val fid = f["id"] ?: throw MergeConflict("Imported folder has no ID")
            if (folders.none { (it as? JsonObject)?.get("id") == fid }) folders.add(f)
        }

        val tasks = arr(s, "tasks")
        val taskMap = mutableMapOf<Long, Long>()
        var nextId = maxOf((s["nid"] as? JsonPrimitive)?.longOrNullSafe() ?: 1L, tasks.mapNotNull { (it as? JsonObject)?.let { o -> id(o, "id")?.plus(1) } }.maxOrNull() ?: 1L, 1L)
        if (nextId > MAX_SAFE_ID) throw MergeConflict("Stored next task ID is not JavaScript-safe")
        val incomingTasks = arr(d, "tasks")
        incomingTasks.forEach { el -> val t0 = el as? JsonObject ?: throw MergeConflict("Imported tasks contain an invalid item"); val old = id(t0, "id") ?: throw MergeConflict("Imported task has no valid ID")
            val t = remap(t0)
            fun comparable(task: JsonObject): JsonObject {
                val m=task.toMutableMap(); m.remove("id")
                str(task,"occurrenceId")?.let { m["occurrenceId"]=JsonPrimitive(TaskHistory.remapIdentity(it,id(task,"id")?:old,str(task,"created")?:"undefined",0L)) }
                return JsonObject(m)
            }
            val duplicate = tasks.mapNotNull { it as? JsonObject }.firstOrNull { existing ->
                val legacy = str(t, "legacyRoutineId")
                (legacy != null && str(existing, "legacyRoutineId") == legacy) || comparable(existing) == comparable(t)
            }
            if (duplicate != null) taskMap[old] = id(duplicate, "id") ?: old else {
                if (nextId >= MAX_SAFE_ID) throw MergeConflict("No safe task ID remains")
                taskMap[old] = nextId
                val m = t.toMutableMap(); m["id"] = JsonPrimitive(nextId)
                val occ = str(t, "occurrenceId")
                if (occ != null) m["occurrenceId"] = JsonPrimitive(TaskHistory.remapIdentity(occ, old, str(t, "created") ?: "undefined", nextId))
                tasks.add(JsonObject(m)); nextId++
            }
        }

        val history = TaskHistory.seedKnown(arr(s, "taskHistory").let(::JsonArray), JsonArray(arr(s, "tasks")), zone).toMutableList()
        val importedHistory = TaskHistory.seedKnown(arr(d,"taskHistory").let(::JsonArray), JsonArray(incomingTasks), zone)
        importedHistory.forEach { el -> val raw = el as? JsonObject ?: throw MergeConflict("Imported history contains an invalid item"); val sourceId = id(raw, "taskId") ?: 0L; val mappedTask = taskMap[sourceId] ?: sourceId
            val m = remap(raw).toMutableMap(); m["taskId"] = JsonPrimitive(mappedTask)
            val created = str(raw, "taskCreated") ?: "undefined"
            listOf("id", "occurrenceId").forEach { k -> stringValue(m[k])?.let { m[k] = JsonPrimitive(TaskHistory.remapIdentity(it, sourceId, created, mappedTask)) } }
            val event = JsonObject(m); val eventId = str(event, "id")
            val prior = history.firstOrNull { (it as? JsonObject)?.let { event -> str(event, "id") } == eventId }
            if (prior != null && prior != event) throw MergeConflict("History contains a conflicting record with the same ID")
            if (prior == null) history.add(event)
        }

        val priorities = arr(s, "priorities")
        arr(d, "priorities").forEachIndexed { i, el -> if (i < 3 && el != JsonNull && (i >= priorities.size || priorities[i] == JsonNull)) {
            val old = (el as? JsonPrimitive)?.longOrNullSafe(); val mapped = old?.let { taskMap[it] }
            if (mapped != null && priorities.none { (it as? JsonPrimitive)?.longOrNullSafe() == mapped }) { while (priorities.size <= i) priorities.add(JsonNull); priorities[i] = JsonPrimitive(mapped) }
        } }
        val qnotes = arr(s, "qnotes")
        val noteIds = qnotes.mapIndexed { index, item ->
            val note = item as? JsonObject ?: throw MergeConflict("Stored quick note $index is invalid")
            id(note, "id") ?: throw MergeConflict("Stored quick note $index has no valid ID")
        }
        val largestNoteId = noteIds.maxOrNull() ?: 99L
        if (noteIds.any { it !in 0..MAX_SAFE_ID }) throw MergeConflict("Stored quick note ID is not JavaScript-safe")
        var qnid = maxOf(100L, largestNoteId + 1L)
        arr(d, "qnotes").forEach { el ->
            val note = el as? JsonObject ?: throw MergeConflict("Imported notes contain an invalid item")
            if (str(note, "txt") != null && qnotes.none { raw ->
                    val existing = raw as? JsonObject ?: return@none false
                    str(existing, "txt") == str(note, "txt") && existing["date"] == note["date"] &&
                        existing["time"] == note["time"] && existing["folder"] == note["folder"]
                }
            ) {
                if (qnid > MAX_SAFE_ID) throw MergeConflict("No safe note ID remains")
                qnotes.add(JsonObject(note + ("id" to JsonPrimitive(qnid++))))
            }
        }
        val rareEvents = arr(s, "rareEvents")
        arr(d, "rareEvents").forEach { element ->
            val event = element as? JsonObject ?: throw MergeConflict("Imported events contain an invalid item")
            val name = str(event, "nm")
            if (rareEvents.none { (it as? JsonObject)?.let { existing -> str(existing, "nm") } == name }) {
                rareEvents.add(event)
            }
        }

        val chips = arr(s, "focusChips")
        uniqueAppend(chips, arr(d, "focusChips")) { left, right -> left == right }

        fun remapTemplateEntry(entry: JsonElement): JsonElement {
            val entryObject = entry as? JsonObject ?: return entry
            val task = entryObject["task"] as? JsonObject ?: return entry
            return JsonObject(entryObject + ("task" to remap(task)))
        }

        arr(d, "dayTemplates").forEach { element ->
            val template = element as? JsonObject ?: throw MergeConflict("Imported day templates contain an invalid item")
            val name = str(template, "name") ?: throw MergeConflict("Imported template has no name")
            val sourceId = str(template, "id") ?: throw MergeConflict("Imported template has no ID")
            val targetId = templateMap.getValue(sourceId)
            val existing = dayTemplates.mapNotNull { it as? JsonObject }.firstOrNull { str(it, "name") == name }
            if (existing == null) {
                val mapped = remap(template).toMutableMap()
                mapped["id"] = JsonPrimitive(targetId)
                (mapped["blocks"] as? JsonArray)?.let { blocks -> mapped["blocks"] = JsonArray(blocks.map { block -> (block as? JsonObject)?.let(::remap) ?: block }) }
                (mapped["tasks"] as? JsonArray)?.let { entries -> mapped["tasks"] = JsonArray(entries.map(::remapTemplateEntry)) }
                dayTemplates.add(JsonObject(mapped))
            } else {
                val existingTasks = (existing["tasks"] as? JsonArray)?.toList().orEmpty()
                val knownEntryIds = existingTasks.mapNotNull { (it as? JsonObject)?.get("id") }.toSet()
                val newEntries = (template["tasks"] as? JsonArray)?.filter { (it as? JsonObject)?.get("id") !in knownEntryIds }.orEmpty()
                if (newEntries.isNotEmpty()) {
                    val index = dayTemplates.indexOf(existing)
                    dayTemplates[index] = JsonObject(existing + ("tasks" to JsonArray(existingTasks + newEntries.map(::remapTemplateEntry))))
                }
            }
        }

        fun remapTemplateDay(value: JsonObject): JsonObject = JsonObject(value.mapValues { (key, item) ->
            if (key != "tplId") item else {
                val sourceId = (item as? JsonPrimitive)?.takeIf { it.isString }?.content
                sourceId?.let { templateMap[it] }?.let(::JsonPrimitive) ?: item
            }
        })

        arr(d, "weekTemplates").forEachIndexed { index, element ->
            val template = element as? JsonObject ?: throw MergeConflict("Imported week templates contain an invalid item")
            val name = str(template, "name") ?: throw MergeConflict("Imported week template has no name")
            if (week.none { (it as? JsonObject)?.let { existing -> str(existing, "name") } == name }) {
                val mapped = template.toMutableMap()
                mapped["id"] = JsonPrimitive(newTemplateId("wk", index))
                (template["days"] as? JsonArray)?.let { days -> mapped["days"] = JsonArray(days.map { (it as? JsonObject)?.let(::remapTemplateDay) ?: it }) }
                week.add(JsonObject(mapped))
            }
        }

        val rules = arr(s, "planRules")
        arr(d, "planRules").forEach { element ->
            val rule = element as? JsonObject ?: throw MergeConflict("Imported plan rules contain an invalid item")
            val duplicate = rules.any { raw ->
                val existing = raw as? JsonObject ?: return@any false
                existing["start"] == rule["start"] && existing["end"] == rule["end"] && existing["name"] == rule["name"]
            }
            if (!duplicate) {
                val mapped = rule.mapValues { (key, value) ->
                    if (key == "days" && value is JsonArray) JsonArray(value.map { (it as? JsonObject)?.let(::remapTemplateDay) ?: it }) else value
                }
                rules.add(JsonObject(mapped))
            }
        }

        val planItems = obj(s, "planItems")
        (d["planItems"] as? JsonObject)?.forEach { (day, entries) ->
            val incoming = entries as? JsonArray ?: throw MergeConflict("Imported plan items for $day must be a list")
            val combined = (planItems[day] as? JsonArray)?.toMutableList() ?: mutableListOf()
            incoming.forEach { item ->
                val planItem = item as? JsonObject ?: throw MergeConflict("Imported plan item is invalid")
                val title = str(planItem, "title")
                if (title != null && combined.none { (it as? JsonObject)?.let { existing -> str(existing, "title") } == title }) combined.add(planItem)
            }
            planItems[day] = JsonArray(combined)
        }

        val planZones = obj(s, "planDayZones")
        (d["planDayZones"] as? JsonObject)?.forEach { (day, entries) ->
            val incoming = entries as? JsonArray ?: throw MergeConflict("Imported zone blocks for $day must be a list")
            val combined = (planZones[day] as? JsonArray)?.toMutableList() ?: mutableListOf()
            incoming.forEach { item ->
                val block = item as? JsonObject ?: throw MergeConflict("Imported zone block is invalid")
                val mapped = remap(block)
                val duplicate = combined.any { raw ->
                    val existing = raw as? JsonObject ?: return@any false
                    existing["zoneId"] == mapped["zoneId"] && existing["s"] == mapped["s"] && existing["e"] == mapped["e"]
                }
                if (!duplicate) combined.add(mapped)
            }
            planZones[day] = JsonArray(combined)
        }

        val planOff = obj(s, "planDayOff")
        (d["planDayOff"] as? JsonObject)?.forEach { (day, entries) ->
            val incoming = entries as? JsonArray ?: throw MergeConflict("Imported rest zones for $day must be a list")
            val combined = (planOff[day] as? JsonArray)?.toMutableList() ?: mutableListOf()
            incoming.forEach { zoneId ->
                val mapped = zid(zoneId)
                if (mapped != null && mapped != JsonNull && mapped !in combined) combined.add(mapped)
            }
            planOff[day] = JsonArray(combined)
        }

        out["zones"] = JsonArray(zones)
        out["folders"] = JsonArray(folders)
        out["tasks"] = JsonArray(tasks)
        out["taskHistory"] = JsonArray(history)
        out["nid"] = JsonPrimitive(nextId)
        out["priorities"] = JsonArray(priorities)
        out["qnotes"] = JsonArray(qnotes)
        out.remove("qnid")
        out["rareEvents"] = JsonArray(rareEvents)
        out["focusChips"] = JsonArray(chips)
        out["dayTemplates"] = JsonArray(dayTemplates)
        out["weekTemplates"] = JsonArray(week)
        out["planRules"] = JsonArray(rules)
        out["planItems"] = JsonObject(planItems)
        out["planDayZones"] = JsonObject(planZones)
        out["planDayOff"] = JsonObject(planOff)
        out["recur"] = JsonArray(emptyList())
        val days = arr(s, "progressDays")
        uniqueAppend(days, arr(d, "progressDays")) { left, right -> left == right }
        out["progressDays"] = JsonArray(days)
        val claims = arr(s, "dailyInsightClaims")
        uniqueAppend(claims, arr(d, "dailyInsightClaims")) { left, right -> left == right }
        out["dailyInsightClaims"] = JsonArray(claims)
        val normalized=Progression.initialize(JsonObject(out), now, zone).state
        val recalculated=ZoneReducer.recalculateUsage(normalized, now, zone)
        return FlowCommandResult.Applied(FlowSnapshot.fromData(recalculated), emptyList())
    }

    private class MergeConflict(message: String): IllegalArgumentException(message)
    private fun JsonPrimitive.longOrNullSafe(): Long? = if (isString) null else longOrNull
}
