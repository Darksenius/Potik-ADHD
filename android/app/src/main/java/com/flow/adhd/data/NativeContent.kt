package com.flow.adhd.data

import android.content.Context
import kotlinx.serialization.json.*

/** Loads the same authored catalogs as the web app, entirely from the installed APK. */
data class NativeContent(val taskXp: Map<String, Double>, val insights: Map<Int, JsonObject>, val progression: JsonObject = JsonObject(emptyMap())) {
    companion object {
        fun load(context: Context): NativeContent {
            fun read(name: String) = read(context, name)
            return parse(read("task-types.json"), read("daily-insights.json"), read("progression.json"))
        }

        fun read(context: Context, name: String): String = context.assets.open("content/$name").bufferedReader(Charsets.UTF_8).use { it.readText() }

        fun parse(taskTypesJson: String, insightsJson: String, progressionJson: String? = null): NativeContent {
            val types = Json.parseToJsonElement(taskTypesJson) as? JsonObject ?: error("Task catalog must be an object")
            require(types.isNotEmpty()) { "Task catalog must not be empty" }
            val taskXp = types.mapValues { (type, value) ->
                val definition = value as? JsonObject ?: error("Task type $type is invalid")
                val xp = (definition["xp"] as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
                require(xp != null && xp.isFinite() && xp >= 0) { "Task type $type has invalid XP" }
                xp
            }
            val book = Json.parseToJsonElement(insightsJson) as? JsonObject ?: error("Daily insight catalog must be an object")
            val cards = book["cards"] as? JsonArray ?: error("Daily insight catalog requires cards")
            require(cards.size == 100) { "Expected 100 daily insights" }
            val insights = linkedMapOf<Int, JsonObject>()
            cards.forEach { value ->
                val card = value as? JsonObject ?: error("Daily insight card is invalid")
                val id = (card["id"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: error("Daily insight ID is invalid")
                require(id in 1..100 && id !in insights) { "Daily insight IDs must be unique and within 1..100" }
                for (field in listOf("title", "text")) require((card[field] as? JsonPrimitive)?.takeIf { it.isString }?.content?.isNotBlank() == true) {
                    "Daily insight $id requires $field"
                }
                insights[id] = card
            }
            val progression = progressionJson?.let { Json.parseToJsonElement(it) as? JsonObject ?: error("Progression catalog must be an object") } ?: JsonObject(emptyMap())
            if (progressionJson != null) {
                require((progression["achievements"] as? JsonArray)?.size == 35 && (progression["tokens"] as? JsonArray)?.size == 20) { "Progression catalog is incomplete" }
            }
            return NativeContent(taskXp, insights, progression)
        }
    }
}
