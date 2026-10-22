package com.flow.adhd.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Applies validated preference patches without recording user activity. */
object PreferencesReducer {
    private val booleanKeys = setOf("reduceMotion", "showGamification", "waterSeedRetired")
    private val knownKeys = booleanKeys + setOf("themeMode", "notificationTheme")

    fun reduce(snapshot: FlowSnapshot, command: FlowCommand): FlowCommandResult {
        if (command !is FlowCommand.SetPreferences) {
            return FlowCommandResult.Rejected("unsupported-command", "PreferencesReducer does not handle this command")
        }
        val unknown = command.values.keys - knownKeys
        if (unknown.isNotEmpty()) {
            return FlowCommandResult.Rejected("invalid-preferences", "Unknown preference: ${unknown.sorted().first()}")
        }
        for ((key, value) in command.values) {
            val valid = when (key) {
                "themeMode" -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { it in setOf("system", "light", "dark") } == true
                "notificationTheme" -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.let { it in setOf("system", "light", "dark") } == true
                in booleanKeys -> (value as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull != null
                else -> false
            }
            if (!valid) return FlowCommandResult.Rejected("invalid-preferences", "Invalid value for preference: $key")
        }

        val previous = snapshot.data["preferences"]
        val stored = when (previous) {
            null -> emptyMap()
            is JsonObject -> previous.toMutableMap()
            else -> return FlowCommandResult.Rejected("invalid-state", "preferences must be an object")
        }
        val merged = JsonObject(stored + command.values)
        if (merged == previous) return FlowCommandResult.Applied(snapshot, emptyList())

        val state = snapshot.data.toMutableMap()
        state["preferences"] = merged
        when ((command.values["themeMode"] as? JsonPrimitive)?.content) {
            "light", "dark" -> state["theme"] = command.values.getValue("themeMode")
            // System mode keeps the legacy concrete theme for device-theme resolution elsewhere.
        }
        return FlowCommandResult.Applied(FlowSnapshot.fromData(JsonObject(state)), emptyList())
    }
}
