package com.flow.adhd

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.domain.FlowStateReader
import com.flow.adhd.domain.SnapshotRead
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only smoke test for the debug native preview over the existing Room snapshot. */
@RunWith(AndroidJUnit4::class)
class NativePreviewTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<NativePreviewActivity>()

    @Test
    fun refreshAndRecreationDisplayExistingSnapshotWithoutChangingItOrUsingWebView() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reader = AppContainer.get(context).flowStateReader
        val bridge = FlowBridge(context)
        val savedBefore = bridge.load()

        composeRule.waitUntil(SNAPSHOT_TIMEOUT_MS) { reader.state.value.isTerminal() }
        val initial = reader.state.value
        assertSnapshotVisible(initial)
        assertNoWebViewInDecor()

        val afterRefresh = awaitNextRead(reader) {
            composeRule.onNodeWithTag("snapshot-refresh").performClick()
        }
        assertSnapshotVisible(afterRefresh)
        assertEquals("Refresh must not alter the saved Room snapshot", savedBefore, bridge.load())
        assertNoWebViewInDecor()

        val afterRecreation = awaitNextRead(reader) {
            composeRule.activityRule.scenario.recreate()
        }
        assertSnapshotVisible(afterRecreation)
        assertEquals("Activity recreation must not alter the saved Room snapshot", savedBefore, bridge.load())
        assertNoWebViewInDecor()
    }

    private fun assertSnapshotVisible(read: SnapshotRead) {
        when (read) {
            SnapshotRead.Loading -> fail("Snapshot read did not finish")
            SnapshotRead.Empty -> composeRule
                .onNodeWithText("Ще немає збережених даних.")
                .assertExists()
            is SnapshotRead.Unavailable -> fail("Existing snapshot was unavailable: ${read.code}")
            is SnapshotRead.Ready -> {
                val active = read.snapshot.tasks.filter { !it.trashed && !it.someday && !it.done }
                if (active.isEmpty()) {
                    composeRule.onNodeWithText("Незавершені задачі · 0").assertExists()
                } else {
                    val task = active.first()
                    val taskTag = "snapshot-task-${task.id}"
                    composeRule.onNodeWithTag("snapshot-content")
                        .performScrollToNode(hasTestTag(taskTag))
                    composeRule.onNodeWithTag(taskTag).assertExists()
                    composeRule.onNodeWithText(task.title).assertExists()
                }
            }
        }
    }

    private fun assertNoWebViewInDecor() {
        composeRule.activityRule.scenario.onActivity { activity ->
            assertFalse("Native preview decor must not contain a WebView", containsWebView(activity.window.decorView))
        }
    }

    private fun containsWebView(view: View): Boolean {
        if (view is WebView) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (containsWebView(view.getChildAt(index))) return true
            }
        }
        return false
    }

    private fun awaitNextRead(reader: FlowStateReader, trigger: () -> Unit): SnapshotRead {
        val result = CompletableDeferred<SnapshotRead>()
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val job = observer.launch {
            reader.state.drop(1).first { it.isTerminal() }.let(result::complete)
        }
        try {
            trigger()
            return runBlocking { withTimeout(SNAPSHOT_TIMEOUT_MS) { result.await() } }
        } finally {
            job.cancel()
            observer.cancel()
        }
    }

    private fun SnapshotRead.isTerminal(): Boolean = this !is SnapshotRead.Loading

    private companion object {
        const val SNAPSHOT_TIMEOUT_MS = 10_000L
    }
}
