package com.flow.adhd

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.QuestDeferralKind
import com.flow.adhd.domain.FlowSnapshot
import com.flow.adhd.domain.QuestPolicy
import com.flow.adhd.domain.ZoneCalendar
import com.flow.adhd.ui.FlowTheme
import com.flow.adhd.ui.NativePlannerScreen
import com.flow.adhd.ui.NativeDayZoneCard
import com.flow.adhd.ui.NativeTasksScreen
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated home presentation contracts: no repository, service, or owner data is opened. */
@RunWith(AndroidJUnit4::class)
class NativeHomeZoneUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultHomeShowsDayContextAndKeepsTasksBeyondTheFirstFive() {
        var planOpens = 0
        compose.setContent {
            NarrowHome {
                NativeTasksScreen(fixture(), JsonObject(emptyMap()), false, { _, _ -> }, {}, {},
                    onOpenPlan = { planOpens++ })
            }
        }

        compose.onNodeWithTag("native-current-zone-name").assertTextContains("День тесту")
        compose.onNodeWithTag("native-zone-clock").assertIsDisplayed()
        scrollTo("native-zone-open-plan")
        compose.onNodeWithTag("native-zone-open-plan").performClick()
        compose.runOnIdle { assertEquals(1, planOpens) }

        compose.onNodeWithTag("native-tasks").performScrollToNode(hasText("Найближчі"))
        compose.onNodeWithText("Найближчі").assertExists()
        compose.onNodeWithTag("native-tasks").performScrollToNode(hasText("Решта задач"))
        compose.onNodeWithText("Решта задач").assertExists()
        scrollTo("native-task-8")
        compose.onNodeWithTag("native-task-8").assertIsDisplayed()
        compose.onNodeWithText("Справа 08").assertExists()
    }

    @Test fun compactSearchCanFindATaskAndClosingItRestoresTheFullList() {
        compose.setContent {
            NarrowHome { NativeTasksScreen(fixture(), JsonObject(emptyMap()), false, { _, _ -> }, {}, {}) }
        }
        compose.onNodeWithTag("native-task-search").assertDoesNotExist()
        scrollTo("native-task-search-toggle")
        compose.onNodeWithTag("native-task-search-toggle").performClick()
        compose.onNodeWithTag("native-task-search").assertIsFocused()
        scrollTo("native-task-search")
        compose.onNodeWithTag("native-task-search").performTextReplacement("NO-MATCH-TO-THIS-TASK")
        scrollTo("native-tasks-empty")
        compose.onNodeWithText("За цими умовами задач немає.").assertExists()

        scrollTo("native-task-search")
        compose.onNodeWithTag("native-task-search").performTextReplacement("Справа 08")
        compose.onNodeWithTag("native-task-search").performImeAction()
        compose.onNodeWithTag("native-task-search").assertIsNotFocused()
        scrollTo("native-task-8")
        compose.onNodeWithTag("native-task-8").assertExists()
        scrollTo("native-task-search-toggle")
        compose.onNodeWithTag("native-task-search-toggle").performClick()
        compose.onNodeWithTag("native-task-search").assertDoesNotExist()
        scrollTo("native-task-1")
        compose.onNodeWithText("Справа 01").assertExists()
        scrollTo("native-task-search-toggle")
        compose.onNodeWithTag("native-task-search-toggle").performClick()
        compose.onNodeWithTag("native-task-search").assertIsFocused()
    }

    @Test fun freeTimeKeepsOnlyCurrentContextWithoutTimelineNoise() {
        val now = Instant.parse("2026-10-22T23:30:00Z").toEpochMilli()
        val snapshot = mutableStateOf(fixture(zoneName = "Ранок", start = "08:00", end = "10:00", taskCount = 0))
        compose.setContent {
            NarrowHome {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val current = snapshot.value
                    val context = QuestPolicy.Context(now, ZoneCalendar.timeline(current.data, now, ZoneOffset.UTC),
                        current.zones, zone = ZoneOffset.UTC)
                    NativeDayZoneCard(current, context)
                }
            }
        }
        compose.onNodeWithTag("native-current-zone-name").assertTextContains("Вільний час")
        compose.onNodeWithTag("native-zone-clock").assertTextContains("23:30")
        compose.onNodeWithTag("native-day-time-track").assertDoesNotExist()
        compose.onNodeWithTag("native-next-zone").assertDoesNotExist()

        snapshot.value = FlowSnapshot.parse("""{"formatVersion":2,"tasks":[],"zones":[]}""")
        compose.onNodeWithTag("native-current-zone-name").assertTextContains("Вільний час")
        compose.onNodeWithText("Без активної зони").assertExists()
    }

    @Test fun compactCounterAndItsMenuKeepProgressAndDeferralActions() {
        val commands = mutableListOf<FlowCommand>()
        val state = FlowSnapshot.parse("""{"formatVersion":2,"zones":[],"tasks":[{"id":99,"title":"Прогулянки","type":"counter","counter":2,"counterTarget":8,"counterStep":1}]}""")
        compose.setContent {
            NarrowHome { NativeTasksScreen(state, JsonObject(emptyMap()), false, { command, done -> commands += command; done() }, {}, {}) }
        }
        scrollTo("native-task-counter-plus-99")
        compose.onNodeWithTag("native-task-counter-plus-99").assertIsDisplayed().performClick()
        compose.onNodeWithTag("native-task-counter-minus-99").performClick()
        compose.runOnIdle {
            assertEquals(listOf(1.0, -1.0), commands.filterIsInstance<FlowCommand.ChangeCounter>().map { it.delta })
        }
        compose.onNodeWithTag("native-task-defer-99").assertDoesNotExist()
        compose.onNodeWithTag("native-task-actions-99").performClick()
        compose.onNodeWithTag("native-task-defer-99").performClick()
        compose.runOnIdle { assertEquals(QuestDeferralKind.NEXT_ZONE, commands.filterIsInstance<FlowCommand.DeferTask>().single().kind) }
    }

    @Test fun plannerNoteActionBringsTheEditorIntoViewPastALongTaskList() {
        val base = fixture()
        val tasks = base.tasks.map { task -> JsonObject(task.raw + ("planDate" to kotlinx.serialization.json.JsonPrimitive(java.time.LocalDate.now().toString()))) }
        val state = FlowSnapshot.parse(JsonObject(base.data + ("tasks" to JsonArray(tasks))).toString())
        compose.setContent { NarrowHome { NativePlannerScreen(state, false, { _, _ -> }, {}) } }
        compose.onNodeWithTag("native-planner-day-actions").performClick()
        compose.onNodeWithTag("native-planner-note-action").performClick()
        compose.onNodeWithTag("native-planner-note").assertIsDisplayed()
    }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("native-tasks").performScrollToNode(hasTestTag(tag))

    @Composable private fun NarrowHome(content: @Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
            FlowTheme { Box(Modifier.width(320.dp).fillMaxHeight()) { content() } }
        }
    }

    private fun fixture(zoneName: String = "День тесту", start: String = "00:00", end: String = "24:00", taskCount: Int = 8): FlowSnapshot {
        val zone = buildJsonObject {
            put("id", 101); put("nm", zoneName); put("desc", "Справи для поточної зони"); put("color", "#5284C7")
            put("slots", JsonArray(listOf(buildJsonObject { put("s", start); put("e", end) })))
        }
        val tasks = (1..taskCount).map { id -> buildJsonObject {
            put("id", id); put("title", "Справа ${id.toString().padStart(2, '0')}")
            put("type", "simple"); put("done", false); put("zoneId", 101)
        } }
        return FlowSnapshot.parse(buildJsonObject {
            put("formatVersion", 2); put("tasks", JsonArray(tasks)); put("zones", JsonArray(listOf(zone)))
        }.toString())
    }
}
