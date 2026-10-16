package com.flow.adhd.domain

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferencesReducerTest {
    @Test
    fun appliesValidatedPatchPreservesStoredUnknownKeysAndDoesNotRecordActivity() {
        val input = FlowSnapshot.parse("""{"tasks":[],"theme":"dark","preferences":{"themeMode":"system","future":"keep"},"xp":12,"progressDays":["2026-10-16"]}""")
        val result = PreferencesReducer.reduce(
            input,
            FlowCommand.SetPreferences(JsonObject(mapOf("themeMode" to j("light"), "reduceMotion" to j(false))), "set", 123),
        ) as FlowCommandResult.Applied

        val prefs = result.snapshot.data["preferences"]!!.jsonObject
        assertEquals("light", prefs["themeMode"]!!.jsonPrimitive.content)
        assertEquals("keep", prefs["future"]!!.jsonPrimitive.content)
        assertEquals("false", prefs["reduceMotion"]!!.jsonPrimitive.content)
        assertEquals("light", result.snapshot.data["theme"]!!.jsonPrimitive.content)
        assertEquals(input.data["xp"], result.snapshot.data["xp"])
        assertEquals(input.data["progressDays"], result.snapshot.data["progressDays"])
        assertTrue(result.events.isEmpty())
    }

    @Test
    fun systemModeLeavesLegacyThemeAndUnknownOrMalformedValuesAreRejected() {
        val input = FlowSnapshot.parse("""{"tasks":[],"theme":"light","preferences":{"future":1}}""")
        val system = PreferencesReducer.reduce(input, FlowCommand.SetPreferences(JsonObject(mapOf("themeMode" to j("system"))), "system", 1))
            as FlowCommandResult.Applied
        assertEquals("light", system.snapshot.data["theme"]!!.jsonPrimitive.content)
        assertEquals("1", system.snapshot.data["preferences"]!!.jsonObject["future"]!!.jsonPrimitive.content)

        val unknown = PreferencesReducer.reduce(input, FlowCommand.SetPreferences(JsonObject(mapOf("other" to j(true))), "unknown", 2))
        assertTrue(unknown is FlowCommandResult.Rejected)
        val wrongType = PreferencesReducer.reduce(input, FlowCommand.SetPreferences(JsonObject(mapOf("showGamification" to j("yes"))), "wrong", 3))
        assertTrue(wrongType is FlowCommandResult.Rejected)
        val stringBoolean = PreferencesReducer.reduce(input, FlowCommand.SetPreferences(JsonObject(mapOf("reduceMotion" to j("true"))), "string-bool", 3))
        assertTrue(stringBoolean is FlowCommandResult.Rejected)
        val invalidTheme = PreferencesReducer.reduce(input, FlowCommand.SetPreferences(JsonObject(mapOf("themeMode" to j("auto"))), "theme", 4))
        assertTrue(invalidTheme is FlowCommandResult.Rejected)
    }

    private fun j(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
    private fun j(value: Boolean) = kotlinx.serialization.json.JsonPrimitive(value)
}
