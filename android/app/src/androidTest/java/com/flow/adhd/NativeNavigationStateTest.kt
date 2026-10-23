package com.flow.adhd

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import java.time.LocalDate
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real Activity navigation in the isolated native install; no owner's phone data. */
class NativeNavigationStateTest {
    @get:org.junit.Rule val compose = createEmptyComposeRule()

    private fun requireNative() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
    }

    private fun awaitHome() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("native-task-create").fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun plannerDateAndNotesSearchSurviveTabSwitchAndRecreation() {
        requireNative()
        ActivityScenario.launch(NativeFlowActivity::class.java).use { scenario ->
            awaitHome()
            val selectedDay = LocalDate.now().plusWeeks(1).toString()
            compose.onNodeWithTag("native-nav-plan").performClick()
            compose.onNodeWithTag("native-planner-next-week").performClick()
            compose.onNodeWithTag("native-planner-day-$selectedDay").assertIsSelected()
            compose.onNodeWithTag("native-nav-notes").performClick()
            compose.onNodeWithTag("native-notes").performScrollToNode(hasTestTag("native-notes-search"))
            compose.onNodeWithTag("native-notes-search").performTextReplacement("keep this query")
            compose.onNodeWithTag("native-nav-plan").performClick()
            compose.onNodeWithTag("native-planner-day-$selectedDay").assertIsSelected()
            compose.onNodeWithTag("native-nav-notes").performClick()
            compose.onNodeWithTag("native-notes-search").assertIsDisplayed().assertTextContains("keep this query").assertIsNotFocused()
            scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("native-notes-search").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-notes-search").assertTextContains("keep this query")
            compose.onNodeWithTag("native-nav-plan").performClick()
            compose.onNodeWithTag("native-planner-day-$selectedDay").assertIsSelected()
        }
    }

    @Test fun taskSearchSurvivesEditorAndMoreKeepsScrollAfterReturning() {
        requireNative()
        ActivityScenario.launch(NativeFlowActivity::class.java).use {
            awaitHome()
            compose.onNodeWithTag("native-task-search-toggle").performClick()
            compose.onNodeWithTag("native-task-search").performTextReplacement("retained search")
            compose.onNodeWithTag("native-task-search").performImeAction()
            compose.onNodeWithTag("native-task-create").performClick()
            compose.onNodeWithTag("native-task-editor-close").performClick()
            compose.onNodeWithTag("native-task-search").assertTextContains("retained search").assertIsNotFocused()
            compose.onNodeWithTag("native-nav-more").performClick()
            compose.onNodeWithTag("native-more").performScrollToNode(hasText("Статистика"))
            compose.onNodeWithText("Статистика").performClick()
            compose.onNodeWithText("← Назад").performClick()
            compose.onNodeWithText("Статистика").assertIsDisplayed()
            compose.onNodeWithTag("native-nav-tasks").performClick()
            compose.onNodeWithTag("native-task-search").assertTextContains("retained search").assertIsNotFocused()
        }
    }

    @Test fun openedNoteKeepsUnsubmittedEditWhenVisitingAnotherTab() {
        requireNative()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = "Navigation note ${System.currentTimeMillis()}"
        val intent = Intent(context, NativeFlowActivity::class.java).setAction(Intent.ACTION_SEND)
            .setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        ActivityScenario.launch<NativeFlowActivity>(intent).use {
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("native-note-editor").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("native-note-editor").performTextReplacement("Unsubmitted $text")
            compose.onNodeWithTag("native-nav-tasks").performClick()
            compose.onNodeWithTag("native-nav-notes").performClick()
            compose.onNodeWithTag("native-note-editor").assertTextContains("Unsubmitted $text")
        }
    }
}
