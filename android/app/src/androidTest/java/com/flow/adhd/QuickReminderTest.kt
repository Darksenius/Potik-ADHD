package com.flow.adhd

import android.app.NotificationManager
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import com.flow.adhd.data.CommandCommit
import com.flow.adhd.domain.FlowCommand
import com.flow.adhd.domain.NotificationProjection
import com.flow.adhd.domain.ProjectionContext
import com.flow.adhd.domain.SnapshotRead
import com.flow.adhd.domain.TaskSchedule
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Actual one-shot AlarmManager delivery in the isolated native package, while no Activity is launched. */
class QuickReminderTest {
    @Test fun simpleQuickReminderFiresOnceInBackgroundAndPersistsExactReceipt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Requires the isolated native runtime", BuildConfig.NATIVE_RUNTIME && context.packageName.endsWith(".native"))
        val container = AppContainer.get(context)
        container.nativeRuntime.refresh().await()
        val before = (container.commandRepository.read() as SnapshotRead.Ready).snapshot
        val id = UUID.randomUUID().toString()
        val title = "Quick reminder $id"
        val now = System.currentTimeMillis()
        val created = container.nativeRuntime.submit(FlowCommand.CreateTask(
            buildJsonObject {
                put("title", title)
                put("type", "simple")
                put("quickReminderDelayMinutes", 1)
            },
            "quick-reminder-$id", now,
        )).await()
        assertTrue("The task save must commit", created is CommandCommit.Committed)
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        var taskId: Long? = null
        try {
            val saved = (container.commandRepository.read() as SnapshotRead.Ready).snapshot
            val task = saved.tasks.single { it.title == title }
            taskId = task.id
            val notificationTag = "flow-task:${task.id}"
            val deadline = task.raw["quickReminderAt"]!!.toString().toLong()
            assertEquals("simple", task.type)
            assertTrue(deadline in (now + 59_000)..(now + 61_000))
            assertFalse(task.raw.containsKey("planDate"))
            assertFalse(task.raw.containsKey("schedDate"))
            assertTrue(task.raw["zoneId"] == null || task.raw["zoneId"] == JsonNull)
            assertFalse("A future quick reminder waits before entering the active task panel", TaskSchedule.available(
                task.raw, ProjectionContext(now, emptyList(), ZoneId.systemDefault())))
            assertFalse(NotificationProjection.build(saved, now, ZoneId.systemDefault()).rows("taskList").any { it.long("id") == task.id })
            assertTrue("The full projection retains the captured task before its reminder",
                NotificationProjection.build(saved, now, ZoneId.systemDefault()).rows("questTasks").any { it.long("id") == task.id })

            container.nativeRuntime.submit(FlowCommand.SetPriority(
                0, task.id, "pin-pending-reminder-$id", System.currentTimeMillis(),
            )).await()
            FlowNotifService.start(context)
            awaitPanel(notifications) { ids -> task.id.toString() !in ids }

            // NativeRuntime schedules the real exact alarm after commit. Do not invoke the receiver or
            // its test clock: this wait observes the OS-delivered notification and its persisted receipt.
            val end = System.currentTimeMillis() + 75_000
            var notificationShown = false
            var acknowledged = false
            while (System.currentTimeMillis() < end && !(notificationShown && acknowledged)) {
                notificationShown = notifications.activeNotifications.any { it.tag == notificationTag }
                val state = (container.commandRepository.read() as SnapshotRead.Ready).snapshot
                val current = state.tasks.firstOrNull { it.id == task.id }
                acknowledged = current?.raw?.get("quickReminderFiredAt")?.toString()?.toLongOrNull() == deadline
                if (!notificationShown || !acknowledged) Thread.sleep(500)
            }
            assertTrue("OS alarm should show the task reminder in the background", notificationShown)
            assertTrue("The queued sched_fired action should acknowledge this exact deadline", acknowledged)
            val projected = (container.commandRepository.read() as SnapshotRead.Ready).snapshot
            assertFalse("Acknowledged one-shot alarm must not be projected again",
                NotificationProjection.build(projected, System.currentTimeMillis(), ZoneId.systemDefault())
                    .getValue("schedList").jsonArray.any { it.jsonObject["id"]?.jsonPrimitive?.content == task.id.toString() })
            assertTrue("The simple task remains visible after reminder delivery",
                projected.tasks.any { it.id == task.id && it.type == "simple" && !it.done })
            awaitPanel(notifications) { ids -> task.id.toString() in ids }
        } finally {
            taskId?.let { notifications.cancel("flow-task:$it", 5000) }
            container.nativeRuntime.submit(FlowCommand.RestoreSnapshot(
                Json.parseToJsonElement(before.toJson()).jsonObject, "restore-quick-reminder-$id", System.currentTimeMillis(),
            )).await()
            FlowNotifService.stop(context)
        }
    }

    private fun awaitPanel(manager: NotificationManager, matches: (List<String>) -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 15_000L
        while (android.os.SystemClock.elapsedRealtime() < end) {
            val status = manager.activeNotifications.firstOrNull { it.id == FlowNotifService.NOTIF_ID }
            val ids = status?.notification?.extras?.getStringArray("flow_panel_ids")?.toList()
            if (ids != null && matches(ids)) return
            android.os.SystemClock.sleep(100L)
        }
        throw AssertionError("The real task panel must match the pending/due reminder state")
    }

    private fun JsonObject.rows(key: String) = this.getValue(key).jsonArray.map { it.jsonObject }
    private fun JsonObject.long(key: String) = this.getValue(key).jsonPrimitive.long
}
