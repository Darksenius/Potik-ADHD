package com.flow.adhd

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flow.adhd.data.CommandCommit
import java.time.LocalDate
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

/**
 * Exercises the installed application's native owner, Room and foreground service without an Activity.
 * The separate .native install keeps these uniquely named records; no state is cleared or restored.
 */
@RunWith(AndroidJUnit4::class)
class NativeRuntimeTest {
    @Test
    fun remoteInputCaptureAndGenerationCheckedCompletionCommitWithoutAnActivity() {
        assumeTrue("This integration contract applies only to the native development build", BuildConfig.NATIVE_RUNTIME)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".native")) { "Refusing to mutate the owner's application data" }

        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val activitiesStarted = CopyOnWriteArrayList<String>()
        val lifecycleCallback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.CREATED || stage == Stage.RESUMED) activitiesStarted += activity.javaClass.name
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(lifecycleCallback) }

        try {
            instrumentation.runOnMainSync {
                assertTrue("The native integration test must begin without a resumed Activity", lifecycle.getActivitiesInStage(Stage.RESUMED).isEmpty())
            }
            if (Build.VERSION.SDK_INT >= 33) {
                instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
            }
            assertNativeEntryRoute(context)
            val app = AppContainer.get(context)
            val runtime = app.nativeRuntime
            val database = FlowDatabase.getInstance(context)
            val bridge = FlowBridge(context)
            val initialized = runBlocking {
                withTimeout(TIMEOUT_MS) { runtime.refresh().await() }
            }
            assertTrue("The application-owned runtime must initialize/drain its real Room store: $initialized", initialized is CommandCommit.Committed)
            val before = readStore(database)
            assertLegacyWritersRejected(bridge, before)

            val suffix = UUID.randomUUID().toString()
            val taskTitle = "Нативна задача $suffix"
            val noteText = "Нативна нотатка $suffix"
            FlowNotifService.start(context)
            sendRemoteInput(context, awaitCaptureAction(context, FlowNotifService.KEY_TASK), FlowNotifService.KEY_TASK, "  $taskTitle  ")
            val capturedTask = awaitStore(database, "RemoteInput task must reach Room and the notification projection") { stored ->
                stored.state.objects("tasks").count { it.optString("title") == taskTitle } == 1 &&
                    stored.notification.objects("questTasks").any { it.optString("title") == taskTitle }
            }
            val task = capturedTask.state.objects("tasks").single { it.optString("title") == taskTitle }
            val taskId = task.getLong("id")
            assertTrue("Captured task IDs must remain safe for the installed state contract", taskId in 1L..9_007_199_254_740_990L)
            assertFalse(task.optBoolean("done"))
            assertAward(before.state, capturedTask.state, 10.0)

            sendRemoteInput(context, awaitCaptureAction(context, FlowNotifService.KEY_NOTE), FlowNotifService.KEY_NOTE, "  $noteText  ")
            val capturedNote = awaitStore(database, "RemoteInput note must reach real Room without opening the app") { stored ->
                stored.state.objects("qnotes").count { it.optString("txt") == noteText } == 1
            }
            assertAward(capturedTask.state, capturedNote.state, 1.0)

            val generation = FlowNotifService.actionGeneration(context, taskId.toString(), System.currentTimeMillis())
            assertTrue("The service must derive a current action generation from the actual committed projection", generation.isNotBlank())
            startService(context, Intent(context, FlowNotifService::class.java)
                .setAction(FlowNotifService.ACTION_TASK_DONE)
                .putExtra(FlowNotifService.EXTRA_ID, taskId.toString())
                .putExtra(FlowNotifService.EXTRA_ACTION_GENERATION, generation))
            val completed = awaitStore(database, "The service's generation-checked completion must commit without an Activity") { stored ->
                stored.state.objects("tasks").any { it.optLong("id") == taskId && it.optBoolean("done") } &&
                    stored.state.objects("taskHistory").count { it.optLong("taskId") == taskId && it.optString("kind") == "completed" } == 1
            }
            assertAward(capturedNote.state, completed.state, app.nativeContent.taskXp.getValue("simple"))
            assertEquals(1, completed.state.objects("tasks").count { it.optString("title") == taskTitle })
            assertEquals(1, completed.state.objects("qnotes").count { it.optString("txt") == noteText })
            val newlyCommittedEvents = receiptKeys(completed.receipts) - receiptKeys(before.receipts)
            assertTrue("Both captures and completion must have durable native inbox receipts", newlyCommittedEvents.count { it.startsWith("native-event:") } >= 3)
            assertTrue("Captured commands must not remain queued after their state is committed", completed.pending.none {
                it.event.contains(taskTitle) || it.event == "capture_note:$noteText" || it.event == "task_done:$taskId"
            })

            // The FIFO block observes the precise inserted ID before runtime can read that lane.
            // Replaying completion also proves that acknowledging a new envelope awards no second credit.
            val replayEvent = "task_done:$taskId"
            val replayAt = System.currentTimeMillis()
            val replayId = FlowDatabase.callStorage {
                val priorIds = database.flowDao().pendingEvents.map { it.id }.toSet()
                bridge.pushEventAt(replayEvent, replayAt)
                database.flowDao().pendingEvents.single { it.event == replayEvent && it.id !in priorIds }.id
            }
            val replayed = awaitStore(database, "The runtime must acknowledge the exact queued duplicate alongside its durable receipt") { stored ->
                stored.receipts.has("native-event:$replayId") && stored.pending.none { it.id == replayId }
            }
            assertEquals(completed.state.getDouble("xpTotal"), replayed.state.getDouble("xpTotal"), 0.000001)
            assertEquals(1, replayed.state.objects("taskHistory").count { it.optLong("taskId") == taskId && it.optString("kind") == "completed" })
            assertTrue("Every receipt inspected with its snapshot must have acknowledged that exact pending row", receiptKeys(replayed.receipts)
                .filter { it.startsWith("native-event:") }
                .mapNotNull { it.removePrefix("native-event:").toLongOrNull() }
                .none { receiptId -> replayed.pending.any { it.id == receiptId } })
            assertTrue("Native commands must finish without starting MainActivity, NativeFlowActivity or a WebView host: $activitiesStarted", activitiesStarted.isEmpty())
        } finally {
            FlowNotifService.stop(context)
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(lifecycleCallback) }
        }
    }

    private fun assertNativeEntryRoute(context: Context) {
        val entryIntent = Intent(context, NativeRuntime.entryClass())
        assertEquals(NativeFlowActivity::class.java.name, entryIntent.component?.className)
        val launcher = context.packageManager.getLaunchIntentForPackage(context.packageName)
        assertNotNull("The separate development install must have a launcher", launcher)
        assertEquals(NativeFlowActivity::class.java.name, launcher?.component?.className)
        val insightIntent = FlowNotifService.createDailyInsightOpenIntent(context, LocalDate.now().toString())
        assertEquals("Actual notification navigation must target the native entry Activity", NativeFlowActivity::class.java.name, insightIntent.component?.className)
        @Suppress("DEPRECATION")
        val legacyInfo = context.packageManager.getActivityInfo(ComponentName(context, "com.flow.adhd.MainActivity"), PackageManager.MATCH_DISABLED_COMPONENTS)
        assertEquals("Older shortcuts must resolve to the native screen", NativeFlowActivity::class.java.name, legacyInfo.targetActivity)
    }

    private fun assertLegacyWritersRejected(bridge: FlowBridge, before: StoreView) {
        assertTrue("Platform capture must expose no independent snapshot writer or acknowledgement", FlowBridge::class.java.methods.none { it.name in setOf("save", "saveWithEvents", "saveNotif", "clearEvents") })
        assertEquals(before.state.toString(), JSONObject(bridge.load()).toString())
    }

    private fun assertAward(before: JSONObject, after: JSONObject, requested: Double) {
        val priorGain = if (before.optString("xpDay") == after.optString("xpDay")) before.optDouble("dayXpGained", 0.0).coerceAtLeast(0.0) else 0.0
        val credited = minOf(requested, (250.0 - priorGain).coerceAtLeast(0.0))
        assertEquals("The actual native pipeline must apply the catalog reward with the existing daily cap", before.optDouble("xpTotal", 0.0) + credited, after.getDouble("xpTotal"), 0.000001)
    }

    private fun awaitCaptureAction(context: Context, key: String): Notification.Action {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var found: Notification.Action? = null
        await("Foreground notification must publish its real RemoteInput action for $key") {
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

    private fun startService(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
    }

    private fun awaitStore(database: FlowDatabase, message: String, predicate: (StoreView) -> Boolean): StoreView {
        var found: StoreView? = null
        await(message) {
            val stored = readStore(database)
            if (predicate(stored)) { found = stored; true } else false
        }
        return requireNotNull(found)
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
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

    private fun receiptKeys(receipts: JSONObject): Set<String> = receipts.keys().asSequence().toSet()

    private fun JSONObject.objects(name: String): List<JSONObject> {
        val values = optJSONArray(name) ?: JSONArray()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
    }

    private data class StoreView(val state: JSONObject, val notification: JSONObject, val receipts: JSONObject, val pending: List<PendingEvent>)

    companion object {
        private const val TIMEOUT_MS = 30_000L
    }
}
