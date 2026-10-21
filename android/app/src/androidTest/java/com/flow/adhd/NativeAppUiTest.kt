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
            compose.onNodeWithTag("native-tasks").performScrollToNode(hasTestTag("native-task-complete-${task.id}"))
            compose.onNodeWithTag("native-task-complete-${task.id}").performClick()
            compose.waitUntil(10_000) {
                val state = container.commandRepository.state.value as? SnapshotRead.Ready
                state?.snapshot?.tasks?.firstOrNull { it.id == task.id }?.done == true
            }
            compose.onNodeWithText("Скасувати").performClick()
            compose.waitUntil(10_000) {
                val state = container.commandRepository.state.value as? SnapshotRead.Ready
                state?.snapshot?.tasks?.firstOrNull { it.id == task.id }?.done == false
            }
            val undone = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot }
            assertEquals("Undo restores the experience of the saved task", saved.xpTotal, undone.xpTotal, 0.000001)
            compose.onNodeWithTag("native-tasks").performScrollToNode(hasTestTag("native-task-complete-${task.id}"))
            compose.onNodeWithTag("native-task-complete-${task.id}").performClick()
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
        val text = "Native notepad ${System.currentTimeMillis()}\n" + (1..12).joinToString("\n") { "Line $it remains readable in a saved note." }
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
            val note = snapshot.qnotes.single { (it["txt"] as? kotlinx.serialization.json.JsonPrimitive)?.content == text }
            val noteId = (note["id"] as kotlinx.serialization.json.JsonPrimitive).content
            val expandTag = "native-note-expand-$noteId"
            compose.onNodeWithTag("native-notes").performScrollToNode(hasTestTag(expandTag))
            compose.onNodeWithTag(expandTag).assertTextEquals("Читати повністю").performClick()
            compose.onNodeWithTag("native-notes").performScrollToNode(hasTestTag(expandTag))
            compose.onNodeWithTag(expandTag).assertTextEquals("Згорнути").performClick()
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
            compose.onNodeWithTag("native-planner-entry").assertTextContains("10:15 $title")
            // Complete the IME resize before locating the real touch target on short API28 screens.
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithText("Додати на цей день").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                (container.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.tasks?.any { task -> task.title == title } == true
            }
            val saved = runBlocking { (container.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.first { task -> task.title == title } }
            assertEquals("sched", saved.type)
            assertEquals(kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()), saved.raw["planDate"])
            assertEquals(kotlinx.serialization.json.JsonPrimitive("10:15"), saved.raw["schedTime"])
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-more").performScrollToNode(hasText("Зони"))
            compose.onNodeWithText("Зони", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("native-zone-edit-1").performScrollTo().performClick()
            compose.onNodeWithTag("native-zone-editor").assertIsDisplayed()
            compose.onNodeWithTag("native-form-save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-zone-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("native-zones").assertIsDisplayed()
        }
    }

    @Test fun statePersistsEnergyAndFocusAndShowsAllOfflineProgressionContent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        val focus = "Увага ${System.currentTimeMillis()}"
        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-more").performScrollToNode(hasText("Стан"))
            compose.onNodeWithText("Стан", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("native-energy-5").performClick()
            compose.waitUntil(10_000) { ((app.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.data?.get("energy") as? kotlinx.serialization.json.JsonPrimitive)?.content == "5" }
            compose.onNodeWithTag("native-tasks").performScrollToNode(hasTestTag("native-focus-input"))
            compose.onNodeWithTag("native-focus-input").performTextInput(focus)
            compose.onNodeWithTag("native-focus-input").assertTextContains(focus)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithTag("native-focus-start").performScrollTo().performClick()
            compose.waitUntil(10_000) { ((app.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.data?.get("currentFocus") as? kotlinx.serialization.json.JsonPrimitive)?.content == focus }
            compose.onNodeWithText("← Ще").performClick()
            compose.onNodeWithTag("native-more").performScrollToNode(hasText("Статистика"))
            compose.onNodeWithText("Статистика", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("native-stats").assertIsDisplayed()
            assertEquals(35, (app.nativeContent.progression["achievements"] as kotlinx.serialization.json.JsonArray).size)
            assertEquals(20, (app.nativeContent.progression["tokens"] as kotlinx.serialization.json.JsonArray).size)
            compose.onNodeWithTag("native-stats").performScrollToNode(hasText("Колекція знаків", substring = true))
            compose.onNodeWithText("Колекція знаків", substring = true).assertIsDisplayed()
        }
    }

    @Test fun authoringAndReapplyingADayPresetKeepsIndependentDateCompletions() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        val name = "Пресет ${System.currentTimeMillis()}"
        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-more").performScrollToNode(hasText("Пресети дня"))
            compose.onNodeWithText("Пресети дня", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("native-preset-create").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-nav-more").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("native-preset-name").performTextInput(name)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            compose.waitForIdle()
            compose.onNodeWithText("+ Типова задача").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-task-title").performTextInput("Типова справа $name")
            compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("native-task-save").assertIsEnabled(); true }.getOrDefault(false) }
            compose.onNodeWithTag("native-task-save").assertIsDisplayed().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("native-form-save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-preset-editor").fetchSemanticsNodes().isEmpty() }
            fun snapshot() = (app.commandRepository.state.value as SnapshotRead.Ready).snapshot
            val template = (snapshot().data["dayTemplates"] as kotlinx.serialization.json.JsonArray).filterIsInstance<kotlinx.serialization.json.JsonObject>().first { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == name }
            val id = (template["id"] as kotlinx.serialization.json.JsonPrimitive).content
            fun apply() {
                compose.onNodeWithTag("native-presets").performScrollToNode(hasTestTag("native-preset-apply-$id"))
                compose.onNodeWithTag("native-preset-apply-$id").performClick()
                compose.onNodeWithTag("native-preset-span").performTextReplacement("2")
                compose.onNodeWithTag("native-preset-apply-confirm").performClick()
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-preset-apply-confirm").fetchSemanticsNodes().isEmpty() }
            }
            apply()
            val copies = snapshot().tasks.filter { task -> (task.raw["dayPresetKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith("$id:") == true }
            assertEquals(2, copies.size)
            assertEquals(2, copies.map { task -> task.raw["planDate"] }.distinct().size)
            val todayTask = copies.first { task -> task.raw["planDate"] == kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()) }
            runBlocking { app.nativeRuntime.submit(com.flow.adhd.domain.FlowCommand.SetTaskDone(todayTask.id, true, System.currentTimeMillis(), java.util.UUID.randomUUID().toString(), System.currentTimeMillis())).await() }
            compose.waitForIdle()
            apply()
            val repeated = snapshot().tasks.filter { task -> (task.raw["dayPresetKey"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.startsWith("$id:") == true }
            assertEquals(2, repeated.size)
            assertTrue(repeated.first { task -> task.id == todayTask.id }.done)
            assertFalse(repeated.first { task -> task.id != todayTask.id }.done)
        }
    }

    @Test fun unfinishedTaskEditorDraftSurvivesClosingAndClearsOnlyWithASavedTask() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        val title = "Чернетка ${System.currentTimeMillis()}"
        ActivityScenario.launch(NativeFlowActivity::class.java).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-task-create").performClick()
            compose.onNodeWithTag("native-task-title").performTextReplacement(title)
            compose.onNodeWithTag("native-task-editor-body").performScrollToNode(hasTestTag("native-task-note"))
            compose.onNodeWithTag("native-task-note").performTextReplacement("Нотатка незбереженого редактора")
            compose.onNodeWithTag("native-task-editor-close").performClick()
            compose.waitUntil(10_000) {
                ((app.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.data?.get("taskEditorDraft") as? kotlinx.serialization.json.JsonPrimitive)?.content?.contains(title) == true
            }
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-task-create").performClick()
            compose.onNodeWithTag("native-task-editor-body").performScrollToNode(hasTestTag("native-task-title"))
            compose.onNodeWithTag("native-task-title").assertTextContains(title)
            compose.onNodeWithTag("native-task-editor-body").performScrollToNode(hasTestTag("native-task-note"))
            compose.onNodeWithTag("native-task-note").assertTextContains("Нотатка незбереженого редактора")
            compose.onNodeWithTag("native-task-save").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-task-editor").fetchSemanticsNodes().isEmpty() }
            android.os.SystemClock.sleep(400)
            val snapshot = runBlocking { (app.commandRepository.read() as SnapshotRead.Ready).snapshot }
            assertEquals("", (snapshot.data["taskEditorDraft"] as kotlinx.serialization.json.JsonPrimitive).content)
            assertEquals(1, snapshot.tasks.count { it.title == title })
            assertEquals("Нотатка незбереженого редактора", snapshot.tasks.first { it.title == title }.note)
        }
    }
}
