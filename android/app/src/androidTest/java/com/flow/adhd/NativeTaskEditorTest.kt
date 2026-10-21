package com.flow.adhd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import android.widget.DatePicker
import android.widget.TimePicker
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.FlowCommandResult
import com.flow.adhd.domain.FlowDefaults
import com.flow.adhd.domain.FlowEngine
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.TaskFactory
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativeTaskEditor
import java.time.Instant
import java.time.ZoneId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated editor contracts. The application Activity, Room and physical user data are not opened. */
@RunWith(AndroidJUnit4::class)
class NativeTaskEditorTest {
    @get:Rule val compose = createComposeRule()
    private val now = Instant.parse("2026-10-17T12:00:00Z").toEpochMilli()

    @Test
    fun everyInstalledTypeCanBeChosenAndChecklistStepsReachTheSavePayload() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val catalog = Json.parseToJsonElement(com.flow.adhd.data.NativeContent.read(context, "task-types.json")) as JsonObject
        assertEquals(10, catalog.size)
        var saved: JsonObject? = null
        show(fixture()) { saved = it }
        compose.onNodeWithTag("native-task-title").performTextReplacement("Зібрати речі")
        catalog.forEach { (id, definition) ->
            val label = ((definition as JsonObject).getValue("label") as JsonPrimitive).content
            chooseType(id)
            if (id == "simple" || id == "sched") {
                compose.onNodeWithTag("native-task-type-$id").assertIsDisplayed().assertTextContains(label)
            } else {
                compose.onNodeWithTag("native-task-type").assertTextContains(label)
            }
        }
        chooseType("check")
        scrollTo("native-task-checklist")
        compose.onNodeWithTag("native-task-checklist").performTextReplacement("Документи\nКлючі\nНавушники")
        compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val payload = requireNotNull(saved)
            assertEquals(JsonPrimitive("check"), payload["type"])
            assertEquals(JsonPrimitive("Зібрати речі"), payload["title"])
            val steps = payload["items"] as JsonArray
            assertEquals(listOf("Документи", "Ключі", "Навушники"), steps.map { ((it as JsonObject)["text"] as JsonPrimitive).content })
            steps.forEach { item ->
                assertEquals(JsonPrimitive(false), (item as JsonObject)["done"])
                assertEquals(JsonPrimitive(false), item["credited"])
            }
            assertFalse(payload.containsKey("counter"))
            assertFalse(payload.containsKey("id"))
        }
    }

    @Test
    fun editingCounterSettingsPreservesRuntimeProgressAndUnknownFields() {
        val original = JsonObject(TaskFactory.create(700, "Вода", "counter", now - 86_400_000L) + mapOf(
            "counter" to JsonPrimitive(750), "counterCreditedProgress" to JsonPrimitive(750),
            "counterTarget" to JsonPrimitive(1500), "custom" to JsonObject(mapOf("kept" to JsonPrimitive(true))),
        ))
        val snapshot = fixture(original)
        var saved: JsonObject? = null
        show(snapshot, 700) { saved = it }
        expandAdvanced()
        scrollTo("native-task-counter-step")
        compose.onNodeWithTag("native-task-counter-step").performTextReplacement("250")
        choose("native-task-counter-unit", "ml")
        scrollTo("native-task-counter-target")
        compose.onNodeWithTag("native-task-counter-target").performTextReplacement("2000")
        scrollTo("native-task-tags")
        compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val payload = requireNotNull(saved)
            assertEquals(JsonPrimitive(250), payload["counterStep"])
            assertEquals(JsonPrimitive(2000), payload["counterTarget"])
            assertEquals(JsonPrimitive("ml"), payload["counterUnit"])
            assertEquals(original["counter"], payload["counter"])
            assertEquals(original["counterCreditedProgress"], payload["counterCreditedProgress"])
            assertEquals(original["custom"], payload["custom"])
            val applied = FlowEngine(mapOf("counter" to 2.0)).apply(snapshot,
                FlowCommand.UpdateTask(700, payload, "edit-counter", now), ZoneId.of("UTC"), now) as FlowCommandResult.Applied
            assertEquals(original["counter"], applied.snapshot.tasks.single().raw["counter"])
            assertEquals(original["counterCreditedProgress"], applied.snapshot.tasks.single().raw["counterCreditedProgress"])
        }
    }

    @Test
    fun scheduledEditorRejectsInvalidDateAndKeepsSaveVisibleAtTheEndOfAShortForm() {
        val draft = buildJsonObject {
            put("version", 1)
            put("values", buildJsonObject { put("title", "Візит"); put("type", "sched") })
        }.toString()
        var saved: JsonObject? = null
        show(fixture(draft = draft)) { saved = it }
        scrollTo("native-task-date")
        compose.onNodeWithTag("native-task-date").performTextReplacement("2026-02-30")
        scrollTo("native-task-time")
        compose.onNodeWithTag("native-task-time").performTextReplacement("15:45")
        compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-task-editor-error").assertIsDisplayed().assertTextContains("РРРР-ММ-ДД", substring = true)
        compose.runOnIdle { assertNull(saved) }
        expandAdvanced()
        scrollTo("native-task-date")
        compose.onNodeWithTag("native-task-date").performTextReplacement("2099-01-02")
        scrollTo("native-task-note")
        compose.onNodeWithTag("native-task-note").performTextReplacement("Адреса й документи\nПрийти за десять хвилин")
        scrollTo("native-task-reminder-advance")
        compose.onNodeWithTag("native-task-reminder-advance").performTextReplacement("15")
        scrollTo("native-task-tags")
        compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
        compose.runOnIdle {
            val payload = requireNotNull(saved)
            assertEquals(JsonPrimitive("sched"), payload["type"])
            assertEquals(JsonPrimitive("2099-01-02"), payload["schedDate"])
            assertEquals(JsonPrimitive("15:45"), payload["schedTime"])
            assertEquals(JsonPrimitive(15), payload["remindBeforeMinutes"])
            assertEquals(JsonPrimitive("Адреса й документи\nПрийти за десять хвилин"), payload["note"])
            assertTrue(payload["reminderEnabled"] == JsonPrimitive(true))
        }
    }

    @Test
    fun freshSimpleTaskKeepsSecondaryOptionsCollapsedAndReachable() {
        show(fixture()) { }
        compose.onNodeWithTag("native-task-folder").assertDoesNotExist()
        compose.onNodeWithTag("native-task-tags").assertDoesNotExist()
        compose.onNodeWithTag("native-task-counter-enabled").assertDoesNotExist()
        expandAdvanced()
        scrollTo("native-task-folder")
        compose.onNodeWithTag("native-task-folder").assertIsDisplayed()
        compose.onNodeWithTag("native-task-tags").assertIsDisplayed()
        compose.onNodeWithTag("native-task-counter-enabled").assertIsDisplayed()
    }

    @Test
    fun nativeDateAndTimeDialogsConfirmAndCancelWithoutChangingConfiguredValues() {
        val draft = buildJsonObject {
            put("version", 1)
            put("values", buildJsonObject {
                put("title", "Візит")
                put("type", "sched")
                put("date", "2099-01-02")
                put("time", "13:45")
            })
        }.toString()
        var saved: JsonObject? = null
        show(fixture(draft = draft)) { saved = it }

        scrollTo("native-task-date-picker")
        compose.onNodeWithTag("native-task-date-picker").performClick()
        onView(isAssignableFrom(DatePicker::class.java)).check(matches(isDisplayed())).check { view, _ ->
            assertEquals("uk", view.context.resources.configuration.locales[0].language)
        }
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithTag("native-task-date").assertTextContains("2099-01-02")
        compose.onNodeWithTag("native-task-date-picker").performClick()
        onView(isAssignableFrom(DatePicker::class.java)).check(matches(isDisplayed())).check { view, _ ->
            assertEquals("uk", view.context.resources.configuration.locales[0].language)
        }
        onView(withId(android.R.id.button2)).perform(click())
        compose.onNodeWithTag("native-task-date").assertTextContains("2099-01-02")

        scrollTo("native-task-time-picker")
        compose.onNodeWithTag("native-task-time-picker").performClick()
        onView(isAssignableFrom(TimePicker::class.java)).check(matches(isDisplayed())).check { view, _ ->
            assertEquals("uk", view.context.resources.configuration.locales[0].language)
        }
        onView(withId(android.R.id.button1)).perform(click())
        compose.onNodeWithTag("native-task-time").assertTextContains("13:45")
        compose.onNodeWithTag("native-task-time-picker").performClick()
        onView(isAssignableFrom(TimePicker::class.java)).check(matches(isDisplayed())).check { view, _ ->
            assertEquals("uk", view.context.resources.configuration.locales[0].language)
        }
        onView(withId(android.R.id.button2)).perform(click())
        compose.onNodeWithTag("native-task-time").assertTextContains("13:45")

        compose.onNodeWithTag("native-task-save").performClick()
        compose.runOnIdle {
            val payload = requireNotNull(saved)
            assertEquals(JsonPrimitive("2099-01-02"), payload["schedDate"])
            assertEquals(JsonPrimitive("13:45"), payload["schedTime"])
        }
    }

    private fun show(snapshot: FlowSnapshot, taskId: Long? = null, onSave: (JsonObject) -> Unit) {
        compose.setContent {
            FlowTheme {
                Box(Modifier.fillMaxWidth().height(420.dp)) {
                    NativeTaskEditor(snapshot, taskId, busy = false, onSave = onSave, onClose = {})
                }
            }
        }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag("native-task-save").assertIsEnabled(); true }.getOrDefault(false)
        }
    }

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag("native-task-editor-body").performScrollToNode(hasTestTag(tag))
    }

    private fun expandAdvanced() {
        scrollTo("native-task-advanced-toggle")
        val toggle = compose.onNodeWithTag("native-task-advanced-toggle").assertIsDisplayed()
        if (runCatching { toggle.assertTextContains("Приховати", substring = true) }.isFailure) toggle.performClick()
    }

    private fun choose(tag: String, id: String) {
        scrollTo(tag)
        compose.onNodeWithTag(tag).performClick()
        compose.onNodeWithTag("$tag-$id").performScrollTo().performClick()
    }

    private fun chooseType(id: String) {
        if (id == "simple" || id == "sched") {
            scrollTo("native-task-type-$id")
            compose.onNodeWithTag("native-task-type-$id").performClick()
        } else {
            choose("native-task-type", id)
        }
    }

    private fun fixture(task: JsonObject? = null, draft: String = "") = FlowSnapshot.parse(JsonObject(FlowDefaults.create() + mapOf(
        "tasks" to JsonArray(listOfNotNull(task)), "taskEditorDraft" to JsonPrimitive(draft),
    )).toString())
}
