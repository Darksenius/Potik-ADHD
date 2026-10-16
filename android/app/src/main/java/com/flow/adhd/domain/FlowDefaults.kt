package com.flow.adhd.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Persisted initial-state values corresponding to the web store's create-slice defaults. */
object FlowDefaults {
    /** qnid and saveDate are deliberately excluded: qnid is derived, saveDate is per-write metadata. */
    fun create(): JsonObject = buildJsonObject {
        put("formatVersion", 2)
        put("tasks", JsonArray(emptyList()))
        put("taskHistory", JsonArray(emptyList()))
        put("recur", JsonArray(emptyList()))
        put("zones", JsonArray(listOf(
            zone(1, "Сон", "#4f8ef7", "Відпочинок", listOf("00:00" to "06:00", "22:00" to "24:00")),
            zone(2, "Ранок", "#f5a623", "Ранкова рутина", listOf("06:00" to "09:00")),
            zone(3, "Робота", "#7ed321", "Продуктивний час", listOf("09:00" to "13:00", "14:00" to "18:00")),
            zone(4, "Обід", "#ff6b35", "Прийом їжі", listOf("13:00" to "14:00")),
            zone(5, "Вечір", "#bd10e0", "Відпочинок/сім'я", listOf("18:00" to "22:00")),
        )))
        put("folders", JsonArray(listOf(
            folder("impulse", "Імпульс", "⚡"), folder("thought", "Думка", "💭"),
            folder("general", "Загальне", "📝"), folder("therapy", "Терапія", "💆"),
            folder("training", "Тренування", "💪"), folder("food", "Харчування", "🍎"),
            folder("work", "Робота", "💼"), folder("kid", "Дитина", "👶"),
        )))
        put("qnotes", JsonArray(emptyList()))
        put("xp", 0); put("xpTotal", 0); put("level", 1); put("progressionVersion", 0)
        put("progressDays", JsonArray(emptyList())); put("dailyInsightClaims", JsonArray(emptyList()))
        put("done", 0); put("streak", 1); put("negCount", 0); put("nid", 300)
        put("unlocked", JsonArray(emptyList())); put("energy", 0); put("lastEnergyXp", 0); put("dayXpGained", 0); put("xpDay", "")
        put("priorities", JsonArray(List(3) { JsonNull }))
        put("focusChips", JsonArray(listOf("Потік", "Робота", "Дитина", "Навчання", "Cybersec", "Гра", "Відпочинок").map(::JsonPrimitive)))
        put("focusLog", JsonArray(emptyList())); put("currentFocus", "")
        put("planItems", JsonObject(emptyMap())); put("planRestDays", JsonObject(emptyMap()))
        put("planSchedules", JsonObject(emptyMap())); put("planDayZones", JsonObject(emptyMap())); put("planDayOff", JsonObject(emptyMap()))
        put("planRules", JsonArray(emptyList())); put("planDayLog", JsonObject(emptyMap()))
        put("rareEvents", JsonArray(emptyList())); put("dayTemplates", JsonArray(emptyList())); put("weekTemplates", JsonArray(emptyList())); put("weekTplSeeded", false)
        put("notepad", ""); put("notepadFolder", "thought"); put("taskEditorDraft", "")
        put("theme", "dark")
        put("preferences", buildJsonObject { put("themeMode", "system"); put("reduceMotion", false); put("showGamification", true) })
    }

    /** Fields replaced by an import/restore when the caller requests a clean base. */
    internal val persistedKeys: Set<String> = create().keys

    private fun zone(id: Int, name: String, color: String, description: String, slots: List<Pair<String, String>>) = buildJsonObject {
        put("id", id); put("nm", name); put("color", color)
        put("slots", JsonArray(slots.map { (start, end) -> buildJsonObject { put("s", start); put("e", end) } }))
        put("desc", description); put("prio", 1)
    }

    private fun folder(id: String, name: String, icon: String) = buildJsonObject {
        put("id", id); put("nm", name); put("ico", icon)
    }
}
