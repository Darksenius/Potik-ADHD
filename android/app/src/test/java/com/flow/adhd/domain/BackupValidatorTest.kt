package com.flow.adhd.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupValidatorTest {
    private val types = setOf("simple", "counter", "routine", "quest")

    @Test fun validatesCopyAddsEntityDefaultsAndPreservesUnknownFields() {
        val result = validate("""{
            "tasks":[{"id":4,"title":"Legacy","custom":{"nested":[1,true]}}],
            "zones":[{"id":2,"nm":"Home","customZone":"keep"}],
            "unknownTopLevel":{"ignore":true}
        }""")

        val task = result.getValue("tasks").jsonArray.single().jsonObject
        assertEquals("simple", task.getValue("type").toString().trim('"'))
        assertEquals("false", task.getValue("done").toString())
        assertTrue(task.containsKey("custom"))
        assertEquals("keep", result.getValue("zones").jsonArray.single().jsonObject.getValue("customZone").toString().trim('"'))
        assertFalse(result.containsKey("unknownTopLevel"))
    }

    @Test fun retainsLegacyHistoryItemsAndMapsLegacyThemeOnlyWhenPreferencesMissing() {
        val result = validate("""{"taskHistory":[{"id":"h1","occurrenceId":"o1","taskId":3,"taskCreated":"today","title":"Done","type":"simple","kind":"completed","at":1,"date":"today","time":"now","items":[{"text":"old","done":true}]}],"theme":"dark"}""")
        val history = result.getValue("taskHistory").jsonArray.single().jsonObject
        assertEquals("[{\"text\":\"old\",\"done\":true}]", history.getValue("items").toString())
        val prefs = result.getValue("preferences").jsonObject
        assertEquals("dark", prefs.getValue("themeMode").toString().trim('"'))
        assertEquals("false", prefs.getValue("reduceMotion").toString())
        assertEquals("true", prefs.getValue("showGamification").toString())

        val explicit = validate("""{"theme":"light","preferences":{"themeMode":"system"}}""")
        assertEquals("system", explicit.getValue("preferences").jsonObject.getValue("themeMode").toString().trim('"'))

        val notificationTheme = validate("""{"preferences":{"notificationTheme":"dark"}}""")
        assertEquals("dark", notificationTheme.getValue("preferences").jsonObject.getValue("notificationTheme").toString().trim('"'))
        assertInvalid("""{"preferences":{"notificationTheme":"sepia"}}""")
    }

    @Test fun preservesUnknownFieldsAndDefaultsWithinTemplateTasks() {
        val result = validate("""{"dayTemplates":[{"id":"d","name":"Day","tasks":[{"id":"e","task":{"title":"Template task","type":"simple","custom":7}}]}]}""")
        val task = result.getValue("dayTemplates").jsonArray.single().jsonObject.getValue("tasks").jsonArray.single().jsonObject.getValue("task").jsonObject
        assertFalse(task.containsKey("id"))
        assertEquals("false", task.getValue("done").toString())
        assertEquals("7", task.getValue("custom").toString())
    }

    @Test fun rejectsWrongTypesUnknownTaskTypesDuplicateIdsAndPrototypeKeys() {
        assertInvalid("""{"tasks":[{"id":1,"title":"bad","done":"false"}]}""")
        assertInvalid("""{"tasks":[{"id":1,"title":"bad","type":"future"}]}""")
        assertInvalid("""{"tasks":[{"id":1,"title":"one"},{"id":1,"title":"two"}]}""")
        assertInvalid("""{"zones":[{"id":1,"nm":"one"},{"id":1,"nm":"two"}]}""")
        assertInvalid("""{"tasks":[{"id":1,"title":"bad","custom":{"constructor":{}}}]}""")
        assertInvalid("""{"tasks":[],"constructor":{}}""")
    }

    @Test fun rejectsMalformedCollectionsWithoutDroppingRecords() {
        assertInvalid("""{"tasks":[{"id":1,"title":"ok"},null]}""")
        assertInvalid("""{"qnotes":[{"id":-1,"txt":"bad"}]}""")
        assertInvalid("""{"formatVersion":3,"tasks":[]}""")
        assertInvalid("""{"unrelated":true}""")
    }

    @Test fun acceptsEquivalentJsonIntegerNotationWithoutTreatingStringsAsNumbers() {
        val result = validate("""{"formatVersion":2.0,"energy":3e0,"tasks":[{"id":1.0,"title":"One"}]}""")
        assertEquals("2", result["formatVersion"].toString())
        assertEquals("3", result["energy"].toString())
        assertEquals("1", result["tasks"]!!.jsonArray.single().jsonObject["id"].toString())
        assertInvalid("""{"energy":"3"}""")
    }

    private fun validate(raw: String): JsonObject = BackupValidator.validate(Json.parseToJsonElement(raw).jsonObject, types)

    private fun assertInvalid(raw: String) {
        try {
            validate(raw)
            throw AssertionError("Expected SnapshotFormatException for: $raw")
        } catch (_: SnapshotFormatException) {
            // Expected.
        }
    }
}
