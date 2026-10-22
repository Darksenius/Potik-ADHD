package com.flow.adhd

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.SimpleTaskTiming
import com.flow.adhd.domain.SnapshotRead
import com.flow.adhd.domain.ZoneCalendar
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** Exercises quick reminders through the native Activity, Room, and command owner. */
class QuickReminderUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun simpleCaptureDelayReminderSurvivesTitleEditAndClearsOnExplicitOptOut() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        val title = "Quick reminder ${System.currentTimeMillis()}"
        var savedAt = 0L

        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-task-create").performClick()
            compose.onNodeWithTag("native-task-title").performTextReplacement(title)
            parentLazyScrollToNode("native-task-editor-body", "native-task-quick-delay")
            Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("native-task-quick-delay").assertIsDisplayed().assertTextContains("Через 10 хв", substring = true)
            parentLazyScrollToNode("native-task-editor-body", "native-task-title")
            compose.onNodeWithTag("native-task-title").performClick()
            compose.onNodeWithTag("native-task-save").assertIsDisplayed().assertIsEnabled()
            savedAt = System.currentTimeMillis()
            compose.waitForIdle()
            compose.onNodeWithTag("native-task-save").performClick()

            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) {
                app.commandRepository.state.value.let { state ->
                    (state as? SnapshotRead.Ready)?.snapshot?.tasks?.any { it.title == title && it.raw["quickReminderAt"] != null } == true
                }
            }
            var task = runBlocking { (app.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.first { it.title == title } }
            val firstDeadline = (task.raw["quickReminderAt"] as JsonPrimitive).content.toLong()
            assertEquals("simple", task.type)
            assertTrue("The deadline must be based on the save action", firstDeadline - savedAt in 590_000L..610_000L)
            val expectedPlanDate = requireNotNull(SimpleTaskTiming.localDateKey(firstDeadline, ZoneId.systemDefault()))
            assertEquals("A quick capture plans the reminder's local date", JsonPrimitive(expectedPlanDate), task.raw["planDate"])
            assertFalse("A fresh quick capture defers attention until its reminder", isEligible(task.raw, app))

            val editTag = "native-task-edit-${task.id}"
            parentLazyScrollToNode("native-tasks", "native-task-actions-${task.id}")
            compose.onNodeWithTag("native-task-actions-${task.id}").performClick()
            compose.onNodeWithTag(editTag).performClick()
            parentLazyScrollToNode("native-task-editor-body", "native-task-title")
            compose.onNodeWithTag("native-task-title").performTextReplacement("$title renamed")
            Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("native-task-save").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) {
                val state = app.commandRepository.state.value as? SnapshotRead.Ready
                state?.snapshot?.tasks?.any { it.id == task.id && it.title == "$title renamed" } == true
            }
            task = runBlocking { (app.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.first { it.id == task.id } }
            assertEquals("Editing only the title must preserve the reminder deadline", firstDeadline,
                (task.raw["quickReminderAt"] as JsonPrimitive).content.toLong())
            assertFalse("Title editing must not mark the reminder fired", task.raw.containsKey("quickReminderFiredAt"))

            parentLazyScrollToNode("native-tasks", "native-task-actions-${task.id}")
            compose.onNodeWithTag("native-task-actions-${task.id}").performClick()
            compose.onNodeWithTag(editTag).performClick()
            parentLazyScrollToNode("native-task-editor-body", "native-task-quick-reminder-off")
            compose.onNodeWithTag("native-task-quick-reminder-off").assertIsDisplayed().performClick()
            Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("native-task-save").assertIsDisplayed().assertIsEnabled().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            compose.waitUntil(10_000) {
                val state = app.commandRepository.state.value as? SnapshotRead.Ready
                state?.snapshot?.tasks?.firstOrNull { it.id == task.id }?.raw?.containsKey("quickReminderAt") == false
            }
            task = runBlocking { (app.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.first { it.id == task.id } }
            assertNull("Explicitly turning the reminder off clears its deadline", task.raw["quickReminderAt"])
            assertNull("Explicitly turning the reminder off clears its fired marker", task.raw["quickReminderFiredAt"])
        }
    }

    private fun parentLazyScrollToNode(parentTag: String, childTag: String) {
        compose.onNodeWithTag(parentTag).performScrollToNode(hasTestTag(childTag))
    }

    private fun isEligible(raw: JsonObject, app: AppContainer): Boolean {
        val snapshot = (app.commandRepository.state.value as SnapshotRead.Ready).snapshot
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val priorities = (snapshot.data["priorities"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.content?.toLongOrNull()
        }
        val context = QuestPolicy.Context(now, ZoneCalendar.timeline(snapshot.data, now, zone), snapshot.zones, priorities, zone)
        return QuestPolicy.availability(raw, context).eligible
    }
}
