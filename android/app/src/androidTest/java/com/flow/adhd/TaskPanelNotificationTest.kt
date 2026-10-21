package com.flow.adhd

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flow.adhd.domain.FlowCommand
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real ongoing task panel RemoteViews actions against the isolated native app and Room store. */
@RunWith(AndroidJUnit4::class)
class TaskPanelNotificationTest {
    @Test
    fun remoteViewsPageDeferAndCompleteTargetSelectedTasksWithoutOpeningAnActivity() {
        assumeTrue("This integration contract applies only to the native development build", BuildConfig.NATIVE_RUNTIME)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".native")) { "Refusing to mutate the owner's application data" }

        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("Notifications must be enabled for the task panel test", manager.areNotificationsEnabled())

        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val activitiesStarted = CopyOnWriteArrayList<String>()
        val lifecycleCallback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.CREATED || stage == Stage.RESUMED) activitiesStarted += activity.javaClass.name
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(lifecycleCallback) }

        val database = FlowDatabase.getInstance(context)
        val bridge = FlowBridge(context)
        val app = AppContainer.get(context)
        val suffix = UUID.randomUUID().toString()
        val titles = (1..6).map { "Panel task $suffix $it" }
        runBlocking { withTimeout(TIMEOUT_MS) { app.nativeRuntime.refresh().await() } }
        val priorPriorities = readStore(database).state.optJSONArray("priorities") ?: JSONArray()

        try {
            instrumentation.runOnMainSync {
                assertTrue("The notification test must start without a resumed app Activity",
                    lifecycle.getActivitiesInStage(Stage.RESUMED).isEmpty())
            }

            FlowNotifService.start(context)
            val capturedIds = titles.map { title ->
                sendRemoteInput(context, awaitCaptureAction(manager, FlowNotifService.KEY_TASK), FlowNotifService.KEY_TASK, title)
                val captured = awaitStore(database, "The actual quick-capture action must persist '$title' without an Activity") { stored ->
                    stored.state.objects("tasks").any { it.optString("title") == title } &&
                        stored.notification.objects("questTasks").any { it.optString("title") == title }
                }
                idText(captured.state.objects("tasks").single { it.optString("title") == title })
            }

            val seeded = readStore(database)
            val seededTasks = seeded.state.objects("tasks").filter { it.optString("title").startsWith("Panel task $suffix ") }
            assertEquals("Six unique tasks must be seeded through the real quick-capture path", 6, seededTasks.size)
            assertTrue("All seeded tasks must be incomplete and immediately eligible simple tasks", seededTasks.all {
                it.optString("type", "simple") == "simple" && !it.optBoolean("done") && !it.optBoolean("trashed") &&
                    !it.optBoolean("someday") && !it.has("questDeferral")
            })
            assertEquals(6, capturedIds.toSet().size)

            // Existing due/current-zone tasks correctly outrank unbound captures. Pin only our
            // fixtures so actions never modify a pre-existing record; restore pins in finally.
            val pinned = capturedIds.take(3)
            pinned.forEachIndexed { slot, id ->
                runBlocking { withTimeout(TIMEOUT_MS) {
                    app.nativeRuntime.submit(FlowCommand.SetPriority(slot, id.toLong(), UUID.randomUUID().toString(), System.currentTimeMillis())).await()
                } }
            }

            // A Room projection commit precedes the service's queued/coalesced render.
            var panel = awaitPanel(manager, "The foreground service must render the latest captured tasks") { current ->
                current.ids.size == 5 && current.ids.take(3) == pinned
            }
            repeat(panel.ids.size) {
                if (panel.selectedId != pinned.first()) {
                    val previous = panel.selectedId
                    clickPanelAction(context, panel.status.notification.bigContentView, R.id.panel_next, "Select test fixture")
                    panel = awaitPanel(manager, "Paging must reach the pinned fixture") { it.selectedId != previous }
                }
            }
            assertEquals(pinned.first(), panel.selectedId)
            assertEquals("Task paging must keep the ongoing notification at its single stable ID",
                42, panel.status.id)
            assertTrue("The panel must expose between one and five unique eligible task IDs", panel.ids.size in 1..5)
            assertEquals(panel.ids.size, panel.ids.toSet().size)
            assertTrue("The notification must expose a generation for stale-action protection", panel.generation.isNotBlank())
            assertTrue("The selected ID must correspond to the published panel position",
                panel.selectedId == panel.ids.getOrNull(panel.index))
            assertEquals("Eligible pinned fixtures must lead the panel", pinned, panel.ids.take(3))
            assertTrue("The old per-card task notification slots must be absent",
                manager.activeNotifications.none { it.id in LEGACY_CARD_ID_RANGE })

            val firstSelection = panel.selectedId
            clickPanelAction(context, panel.status.notification.bigContentView, R.id.panel_next, "Next task")
            panel = awaitPanel(manager, "The real next PendingIntent must update the selected task") { current ->
                current.selectedId != firstSelection
            }
            val nextSelection = panel.selectedId
            assertTrue("Next must select another eligible task", nextSelection != firstSelection && nextSelection in panel.ids)

            clickPanelAction(context, panel.status.notification.bigContentView, R.id.panel_previous, "Previous task")
            panel = awaitPanel(manager, "The real previous PendingIntent must restore the prior selection") { current ->
                current.selectedId == firstSelection
            }
            assertEquals(firstSelection, panel.selectedId)

            // The selected notification action must defer the exact selected task, not a stale card's task.
            val deferredId = panel.selectedId
            val deferredActionView = clickPanelAction(
                context, panel.status.notification.bigContentView, R.id.panel_not_today, "Not today")
            val deferredStore = awaitStore(database, "The selected task's real Not today action must commit to Room") { stored ->
                taskById(stored.state, deferredId)?.optJSONObject("questDeferral")?.optString("kind") == "not-today"
            }
            val deferral = requireNotNull(taskById(deferredStore.state, deferredId)).getJSONObject("questDeferral")
            val deferredAt = deferral.getLong("requestedAt")
            assertTrue("The action must persist its requested time", deferredAt > 0L)
            assertTrue("An unzoned quick task must be deferred for approximately 23 hours",
                deferral.getLong("startAt") in (deferredAt + TWENTY_THREE_HOURS_MS - TIME_TOLERANCE_MS)..(deferredAt + TWENTY_THREE_HOURS_MS + TIME_TOLERANCE_MS))
            assertNotNull("The test must have clicked the actual action view", deferredActionView)

            panel = awaitPanel(manager, "The task panel must refresh after deferring the selected task") { current ->
                deferredId !in current.ids
            }
            val completedId = panel.selectedId
            assertTrue("The completion target must still be an eligible panel item", completedId in panel.ids)
            val beforeCompletion = readStore(database)
            val completionView = clickPanelAction(
                context, panel.status.notification.bigContentView, R.id.panel_complete, "Complete")
            val completed = awaitStore(database, "The real completion PendingIntent must complete the currently selected task") { stored ->
                taskById(stored.state, completedId)?.optBoolean("done") == true &&
                    stored.state.objects("taskHistory").count { idText(it, "taskId") == completedId && it.optString("kind") == "completed" } == 1
            }
            assertAward(beforeCompletion.state, completed.state, app.nativeContent.taskXp.getValue("simple"))
            assertEquals(1, completed.state.objects("taskHistory").count {
                idText(it, "taskId") == completedId && it.optString("kind") == "completed"
            })

            // Replaying the old RemoteViews click carries a stale generation and cannot award a second completion.
            assertTrue("The completion action must be a real clickable RemoteViews child", completionView.isClickable)
            assertTrue("The completion PendingIntent must remain invokable for the stale-action check", completionView.performClick())
            instrumentation.waitForIdleSync()
            SystemClock.sleep(300L)
            val replayed = readStore(database)
            assertEquals("A repeated stale completion must not award XP twice",
                completed.state.optDouble("xpTotal", 0.0), replayed.state.optDouble("xpTotal", 0.0), 0.000001)
            assertEquals("A repeated stale completion must not duplicate history", 1, replayed.state.objects("taskHistory").count {
                idText(it, "taskId") == completedId && it.optString("kind") == "completed"
            })
            assertEquals("The page actions must continue updating only the single task panel", 1,
                manager.activeNotifications.count { it.id == FlowNotifService.NOTIF_ID })
            assertTrue("No legacy per-task card IDs may remain active", manager.activeNotifications.none {
                it.id in LEGACY_CARD_ID_RANGE
            })
            assertTrue("Notification task controls must not open any app Activity: $activitiesStarted", activitiesStarted.isEmpty())
        } finally {
            repeat(3) { slot ->
                val id = if (priorPriorities.isNull(slot)) null else priorPriorities.optLong(slot).takeIf { it > 0 }
                runBlocking { withTimeout(TIMEOUT_MS) {
                    app.nativeRuntime.submit(FlowCommand.SetPriority(slot, id, UUID.randomUUID().toString(), System.currentTimeMillis())).await()
                } }
            }
            FlowNotifService.stop(context)
            manager.cancel(FlowNotifService.NOTIF_ID)
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(lifecycleCallback) }
        }
    }

    private fun awaitCaptureAction(manager: NotificationManager, key: String): Notification.Action {
        var found: Notification.Action? = null
        await("Foreground task panel must publish its real RemoteInput action for $key") {
            found = manager.activeNotifications.firstOrNull { it.id == FlowNotifService.NOTIF_ID }?.notification?.actions
                ?.firstOrNull { action -> action.remoteInputs?.any { it.resultKey == key } == true }
            found != null
        }
        return requireNotNull(found)
    }

    private fun sendRemoteInput(context: Context, action: Notification.Action, key: String, text: String) {
        val input = RemoteInput.Builder(key).build()
        val values = Bundle().apply { putCharSequence(key, text) }
        val results = Intent()
        RemoteInput.addResultsToIntent(arrayOf(input), results, values)
        action.actionIntent.send(context, 0, results)
    }

    private fun awaitPanel(
        manager: NotificationManager,
        message: String,
        predicate: (PanelState) -> Boolean = { true },
    ): PanelState {
        var found: PanelState? = null
        await(message) {
            val current = manager.activeNotifications.firstOrNull { it.id == FlowNotifService.NOTIF_ID }
                ?.let(::panelState)
            if (current != null && predicate(current)) { found = current; true } else false
        }
        return requireNotNull(found)
    }

    private fun panelState(status: android.service.notification.StatusBarNotification): PanelState? {
        val extras = status.notification.extras ?: return null
        val ids = extras.getStringArray(PANEL_IDS_KEY)?.toList() ?: return null
        val selectedId = extras.getString(PANEL_SELECTED_ID_KEY) ?: return null
        val index = extras.getInt(PANEL_INDEX_KEY, -1)
        val generation = extras.getString(PANEL_GENERATION_KEY) ?: return null
        return PanelState(status, ids, selectedId, index, generation)
    }

    private fun clickPanelAction(context: Context, remoteViews: android.widget.RemoteViews?, viewId: Int, label: String): View {
        assertNotNull("Expanded task notification must include custom RemoteViews", remoteViews)
        val applied = requireNotNull(remoteViews).apply(context, null)
        val target = applied.findViewById<View>(viewId)
        assertNotNull("Expanded RemoteViews must include the $label action", target)
        assertTrue("The $label child must invoke its actual PendingIntent", requireNotNull(target).performClick())
        return requireNotNull(target)
    }

    private fun awaitStore(database: FlowDatabase, message: String, predicate: (StoreView) -> Boolean): StoreView {
        var found: StoreView? = null
        await(message) {
            val stored = readStore(database)
            if (predicate(stored)) { found = stored; true } else false
        }
        return requireNotNull(found)
    }

    private fun readStore(database: FlowDatabase): StoreView = FlowDatabase.callStorage {
        database.runInTransaction(Callable {
            val dao = database.flowDao()
            val state = JSONObject(dao.stateJson?.takeIf { it.isNotBlank() } ?: "{}")
            val notification = JSONObject(dao.notifJson?.takeIf { it.isNotBlank() } ?: "{}")
            val journal = JSONObject(dao.commandJournalJson?.takeIf { it.isNotBlank() } ?: "{}")
            StoreView(state, notification, journal.optJSONObject("receipts") ?: JSONObject(), dao.pendingEvents)
        })
    }

    private fun assertAward(before: JSONObject, after: JSONObject, requested: Double) {
        val priorGain = if (before.optString("xpDay") == after.optString("xpDay"))
            before.optDouble("dayXpGained", 0.0).coerceAtLeast(0.0) else 0.0
        val credited = minOf(requested, (250.0 - priorGain).coerceAtLeast(0.0))
        assertEquals("The native task completion must apply the existing catalog reward and daily cap",
            before.optDouble("xpTotal", 0.0) + credited, after.getDouble("xpTotal"), 0.000001)
    }

    private fun taskById(state: JSONObject, taskId: String): JSONObject? =
        state.objects("tasks").firstOrNull { idText(it) == taskId }

    private fun idText(row: JSONObject, key: String = "id"): String = when (val value = row.opt(key)) {
        is Number -> value.toLong().toString()
        is String -> value
        else -> ""
    }

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val values = optJSONArray(name) ?: JSONArray()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    private data class PanelState(
        val status: android.service.notification.StatusBarNotification,
        val ids: List<String>,
        val selectedId: String,
        val index: Int,
        val generation: String,
    )

    private data class StoreView(
        val state: JSONObject,
        val notification: JSONObject,
        val receipts: JSONObject,
        val pending: List<PendingEvent>,
    )

    companion object {
        private const val PANEL_IDS_KEY = "flow_panel_ids"
        private const val PANEL_SELECTED_ID_KEY = "flow_panel_task_id"
        private const val PANEL_INDEX_KEY = "flow_panel_index"
        private const val PANEL_GENERATION_KEY = "flow_panel_generation"
        private const val TIMEOUT_MS = 30_000L
        private const val TWENTY_THREE_HOURS_MS = 23L * 60L * 60L * 1_000L
        private const val TIME_TOLERANCE_MS = 30_000L
        private val LEGACY_CARD_ID_RANGE = 1_000..1_100
    }
}
