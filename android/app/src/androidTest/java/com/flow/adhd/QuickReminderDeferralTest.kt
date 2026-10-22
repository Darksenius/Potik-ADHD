package com.flow.adhd

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.view.View
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.SnapshotRead
import java.util.UUID
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Real task-panel defer and notification Undo against the isolated native Room store. */
class QuickReminderDeferralTest {
    @Test
    fun notTodayClearsQuickReminderAndUndoRestoresItsDeadline() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue(BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("Task panel notifications must be enabled", manager.areNotificationsEnabled())

        val app = AppContainer.get(context)
        val database = FlowDatabase.getInstance(context)
        app.nativeRuntime.refresh().await()
        val before = (app.commandRepository.read() as SnapshotRead.Ready).snapshot
        val suffix = UUID.randomUUID().toString()
        val title = "Deferrable reminder $suffix"
        try {
            val createdAt = System.currentTimeMillis()
            val created = app.nativeRuntime.submit(FlowCommand.CreateTask(
                kotlinx.serialization.json.buildJsonObject {
                    put("title", title)
                    put("type", "simple")
                    put("quickReminderDelayMinutes", 60)
                }, "quick-reminder-defer-$suffix", createdAt,
            )).await()
            assertTrue("The task must be durably created", created is CommandCommit.Committed)
            val task = (app.commandRepository.read() as SnapshotRead.Ready).snapshot.tasks.single { it.title == title }
            val deadline = task.raw.getValue("quickReminderAt").toString().toLong()
            assertEquals("simple", task.type)

            val pinned = app.nativeRuntime.submit(FlowCommand.SetPriority(
                0, task.id, "pin-quick-reminder-$suffix", System.currentTimeMillis(),
            )).await()
            assertTrue("The fixture must be pinned so the real panel can reach it", pinned is CommandCommit.Committed)
            FlowNotifService.start(context)

            var panel = awaitPanel(manager) { task.id.toString() in it.ids }
            repeat(panel.ids.size) {
                if (panel.selectedId != task.id.toString()) {
                    val priorSelection = panel.selectedId
                    clickPanelAction(context, panel.status.notification.bigContentView, R.id.panel_next)
                    panel = awaitPanel(manager) { it.selectedId != priorSelection }
                }
            }
            assertEquals("The test must select its pinned fixture through the real panel", task.id.toString(), panel.selectedId)

            clickPanelAction(context, panel.status.notification.bigContentView, R.id.panel_not_today)
            var deferred = awaitStore(database) { stored ->
                taskById(stored.state, task.id)?.optJSONObject("questDeferral")?.optString("kind") == "not-today"
            }
            var deferredTask = requireNotNull(taskById(deferred.state, task.id))
            assertFalse("Not Today must remove the pending quick-reminder deadline", deferredTask.has("quickReminderAt"))
            assertFalse("Not Today must remove its fired marker", deferredTask.has("quickReminderFiredAt"))
            assertFalse("A deferred task must have no scheduled alarm row", schedRows(deferred.notification).any { idText(it) == task.id.toString() })

            val undoAction = awaitUndoAction(manager)
            undoAction.actionIntent.send(context, 0, null)
            val restored = awaitStore(database) { stored ->
                taskById(stored.state, task.id)?.optLong("quickReminderAt", 0L) == deadline &&
                    schedRows(stored.notification).any { idText(it) == task.id.toString() && it.optBoolean("quickReminder", false) }
            }
            val restoredTask = requireNotNull(taskById(restored.state, task.id))
            assertEquals("Undo must retain the simple type", "simple", restoredTask.optString("type"))
            assertEquals("Undo must restore the original one-hour deadline", deadline, restoredTask.optLong("quickReminderAt"))
            assertFalse("Undo must not leave the task deferred", restoredTask.has("questDeferral"))
            assertTrue("Undo must restore the quick reminder projection", schedRows(restored.notification).any {
                idText(it) == task.id.toString() && it.optBoolean("quickReminder", false) && it.optLong("dueMs") == deadline
            })
        } finally {
            try {
                app.nativeRuntime.submit(FlowCommand.RestoreSnapshot(
                    Json.parseToJsonElement(before.toJson()).jsonObject,
                    "restore-quick-reminder-defer-$suffix", System.currentTimeMillis(),
                )).await()
            } finally {
                FlowNotifService.stop(context)
                manager.cancel(FlowNotifService.NOTIF_ID)
            }
        }
    }

    private fun clickPanelAction(context: Context, remoteViews: android.widget.RemoteViews?, viewId: Int) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertNotNull("The task panel must include expanded RemoteViews", remoteViews)
            val applied = requireNotNull(remoteViews).apply(context, null)
            val target = applied.findViewById<View>(viewId)
            assertNotNull("The panel action must exist", target)
            assertTrue("The actual RemoteViews action must invoke its PendingIntent", requireNotNull(target).performClick())
        }
    }

    private fun awaitPanel(manager: NotificationManager, predicate: (Panel) -> Boolean): Panel {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val status = manager.activeNotifications.firstOrNull { it.id == FlowNotifService.NOTIF_ID }
            val extras = status?.notification?.extras
            val ids = extras?.getStringArray("flow_panel_ids")?.toList()
            val selectedId = extras?.getString("flow_panel_task_id")
            if (status != null && ids != null && selectedId != null) {
                val panel = Panel(status, ids, selectedId)
                if (predicate(panel)) return panel
            }
            android.os.SystemClock.sleep(100L)
        }
        throw AssertionError("The real task panel did not reach the expected fixture state")
    }

    private fun awaitUndoAction(manager: NotificationManager): Notification.Action {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val action = manager.activeNotifications.firstOrNull { it.id == FlowNotifService.NOTIF_ID }
                ?.notification?.actions?.firstOrNull { it.title?.toString() == "Скасувати" }
            if (action != null) return action
            android.os.SystemClock.sleep(100L)
        }
        throw AssertionError("The notification must expose the real Undo action")
    }

    private fun awaitStore(database: FlowDatabase, predicate: (Store) -> Boolean): Store {
        val deadline = android.os.SystemClock.elapsedRealtime() + 30_000L
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            val stored = FlowDatabase.callStorage {
                database.runInTransaction(Callable {
                    val dao = database.flowDao()
                    Store(
                        JSONObject(dao.stateJson?.takeIf { it.isNotBlank() } ?: "{}"),
                        JSONObject(dao.notifJson?.takeIf { it.isNotBlank() } ?: "{}"),
                    )
                })
            }
            if (predicate(stored)) return stored
            android.os.SystemClock.sleep(100L)
        }
        throw AssertionError("Room did not reach the expected deferred/reminder state")
    }

    private fun taskById(state: JSONObject, id: Long): JSONObject? = state.objects("tasks").firstOrNull { idText(it) == id.toString() }
    private fun schedRows(notification: JSONObject): List<JSONObject> = notification.objects("schedList")
    private fun idText(row: JSONObject): String = when (val value = row.opt("id")) {
        is Number -> value.toLong().toString()
        is String -> value
        else -> ""
    }
    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: JSONArray()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }

    private data class Panel(val status: android.service.notification.StatusBarNotification, val ids: List<String>, val selectedId: String)
    private data class Store(val state: JSONObject, val notification: JSONObject)
}
