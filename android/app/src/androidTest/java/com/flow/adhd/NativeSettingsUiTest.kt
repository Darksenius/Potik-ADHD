package com.flow.adhd

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NativeSettingsUiTest {
    @get:org.junit.Rule val compose = createEmptyComposeRule()

    @Test fun settingsKeepThemeAndReadPackagedHelpAndReleaseNotes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        runBlocking { app.nativeRuntime.submit(FlowCommand.SetPreferences(buildJsonObject { put("themeMode", "system") }, java.util.UUID.randomUUID().toString(), System.currentTimeMillis())).await() }
        ActivityScenario.launch(NativeFlowActivity::class.java).use { scenario ->
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-open-settings").performScrollTo().performClick()
            compose.onNodeWithTag("native-settings-appearance").performClick()
            compose.onNodeWithText("Як у системі").performClick()
            compose.onNodeWithText("Світла").performClick()
            compose.waitUntil(10_000) { ((app.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.data?.get("preferences") as? JsonObject)?.get("themeMode") == JsonPrimitive("light") }
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Світла").assertExists()
            compose.onNodeWithText("Що нового · ${BuildConfig.VERSION_NAME}").performScrollTo().performClick()
            val latest = Json.parseToJsonElement(com.flow.adhd.data.NativeContent.read(context, "release-notes.json")).jsonArray.first().jsonObject
            compose.onNodeWithText("${latest.getValue("version").jsonPrimitive.content} · ${latest.getValue("title").jsonPrimitive.content}").assertExists()
            compose.onNodeWithText("← Назад").performClick()
            compose.onNodeWithTag("native-settings").performScrollToNode(hasTestTag("native-settings-about"))
            compose.onNodeWithTag("native-settings-about").performClick()
            compose.onNodeWithTag("native-settings").performScrollToNode(hasText("Довідка"))
            compose.onNodeWithText("Довідка").performClick()
            compose.onNodeWithText("Панель квестів для реального життя").assertExists()
            assertTrue(com.flow.adhd.data.NativeContent.read(context, "source.txt").contains("class NativeCommandRepository"))
        }
    }

    @Test fun sharedTextAndNotificationTapOpenTheSavedNativeNote() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        val text = "Поширена думка ${System.currentTimeMillis()}"
        val intent = Intent(context, NativeFlowActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        var noteId = 0L
        ActivityScenario.launch<NativeFlowActivity>(intent).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-note-editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-note-editor").assertTextContains(text)
            val snapshot = runBlocking { (app.commandRepository.read() as SnapshotRead.Ready).snapshot }
            noteId = snapshot.qnotes.first { (it["txt"] as? JsonPrimitive)?.content == text }.getValue("id").jsonPrimitive.long
        }
        val open = Intent(context, NativeFlowActivity::class.java).putExtra("openNoteId", noteId.toString())
        ActivityScenario.launch<NativeFlowActivity>(open).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-note-editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-note-editor").assertTextContains(text)
        }
    }

    @Test fun bundledDemoAppliesFromSettingsAndDoesNotDuplicateCompletedExamples() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val app = AppContainer.get(context)
        runBlocking { app.nativeRuntime.submit(FlowCommand.ClearDemoTasks(java.util.UUID.randomUUID().toString(), System.currentTimeMillis())).await() }
        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-open-settings").performScrollTo().performClick()
            compose.onNodeWithTag("native-settings-demo").performClick()
            compose.onNodeWithTag("native-settings").performScrollToNode(hasTestTag("native-demo-install"))
            compose.onNodeWithTag("native-demo-install").performClick()
            fun demos() = (app.commandRepository.state.value as? SnapshotRead.Ready)?.snapshot?.tasks.orEmpty().filter { task -> (task.raw["dayPresetKey"] as? JsonPrimitive)?.content?.startsWith("potik-intro-v1:") == true }
            compose.waitUntil(10_000) { demos().size == 18 }
            assertEquals(10, demos().map { task -> task.type }.distinct().size)
            val task = demos().first { entry -> entry.type == "simple" && entry.raw["planDate"] == JsonPrimitive(java.time.LocalDate.now().toString()) }
            runBlocking { app.nativeRuntime.submit(FlowCommand.SetTaskDone(task.id, true, System.currentTimeMillis(), java.util.UUID.randomUUID().toString(), System.currentTimeMillis())).await() }
            compose.onNodeWithTag("native-demo-install").performClick()
            compose.waitForIdle()
            assertEquals(18, demos().size)
            assertTrue(demos().first { entry -> entry.id == task.id }.done)
        }
    }
}
