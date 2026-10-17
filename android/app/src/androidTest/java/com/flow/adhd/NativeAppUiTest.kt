package com.flow.adhd

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.SnapshotRead
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual native Activity + actual development Room; never launches the owner's installed package. */
class NativeAppUiTest {
    @get:org.junit.Rule val compose = createEmptyComposeRule()

    @Test fun createWithNoteAndCompleteFromNativeScreenThenReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val title = "Native screen ${System.currentTimeMillis()}"
        val container = AppContainer.get(context)
        ActivityScenario.launch(NativeFlowActivity::class.java).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            scenario.onActivity { assertFalse("Native hierarchy contains a WebView", containsWebView(it.window.decorView)) }
            compose.onNodeWithTag("native-task-create").performClick()
            compose.onNodeWithTag("native-task-title").performTextReplacement(title)
            compose.onNodeWithTag("native-task-editor-body").performScrollToNode(hasTestTag("native-task-note"))
            compose.onNodeWithTag("native-task-note").performTextInput("A saved note without JavaScript")
            compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            val saved = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot }
            val task = saved.tasks.first { it.title == title }
            assertEquals("A saved note without JavaScript", task.note)
            compose.onNodeWithTag("native-task-complete-${task.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                val state = container.commandRepository.state.value as? SnapshotRead.Ready
                state?.snapshot?.tasks?.firstOrNull { it.id == task.id }?.done == true
            }
            scenario.recreate()
            compose.waitForIdle()
            val reopened = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot }
            assertTrue(reopened.tasks.first { it.id == task.id }.done)
            assertEquals(task.note, reopened.tasks.first { it.id == task.id }.note)
        }
    }

    @Test fun notepadSavesOnLeavingScreenAndFilesOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val text = "Native notepad ${System.currentTimeMillis()}\nSecond line stays"
        val container = AppContainer.get(context)
        ActivityScenario.launch(NativeFlowActivity::class.java).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-notes").performClick()
            compose.onNodeWithTag("native-notepad").performTextReplacement(text)
            // Leave immediately, before the 250ms debounce; the pause hook must submit the latest draft.
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            compose.waitUntil(10_000) {
                val read = container.commandRepository.state.value as? SnapshotRead.Ready
                read?.snapshot?.notepad == text
            }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("native-notepad").assertTextContains(text)
            compose.onNodeWithTag("native-notes").performScrollToNode(hasTestTag("native-notepad-save"))
            compose.onNodeWithTag("native-notepad-save").performClick()
            compose.waitUntil(10_000) {
                val snapshot = (container.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot
                snapshot?.notepad == "" && snapshot.qnotes.any { it["txt"].toString().contains("Native notepad") }
            }
            val snapshot = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot }
            assertEquals(1, snapshot.qnotes.count { (it["txt"] as? kotlinx.serialization.json.JsonPrimitive)?.content == text })
        }
    }

    private fun containsWebView(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { containsWebView(view.getChildAt(it)) }

    @Test fun plannerCreatesADatedTaskAndZoneEditingUsesTheWholeScreen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val title = "Календар ${System.currentTimeMillis()}"
        val container = AppContainer.get(context)
        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-plan").performClick()
            compose.onNodeWithTag("native-planner").performScrollToNode(hasTestTag("native-planner-entry"))
            compose.onNodeWithTag("native-planner-entry").performTextInput("10:15 $title")
            compose.onNodeWithText("Додати на цей день").performScrollTo().performClick()
            compose.waitUntil(10_000) {
                (container.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.tasks?.any { task -> task.title == title } == true
            }
            val saved = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.first { task -> task.title == title } }
            assertEquals("sched", saved.type)
            assertEquals(kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()), saved.raw["planDate"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive("10:15"), saved.raw["schedTime"])
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithText("Зони", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("native-zone-edit-1").performScrollTo().performClick()
            compose.onNodeWithTag("native-zone-editor").assertIsDisplayed()
            compose.onNodeWithTag("native-form-save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-zone-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("native-zones").assertIsDisplayed()
        }
    }
}
