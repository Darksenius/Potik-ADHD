package com.flow.adhd

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleCallback
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.flow.adhd.data.CommandCommit
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises a real committed capture and its AlarmManager delivery without opening an Activity. */
@RunWith(AndroidJUnit4::class)
class NativeNoteReviewTest {
    @Test
    fun committedCaptureSchedulesAndDeliversNoteReviewWithoutStartingActivity() {
        assumeTrue("Note review integration runs on the Android 9 native development build", BuildConfig.NATIVE_RUNTIME && Build.VERSION.SDK_INT == 28)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        require(context.packageName.endsWith(".native")) { "Refusing to mutate the owner's application data" }
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assumeTrue("Notifications must be enabled on the test emulator", notifications.areNotificationsEnabled())

        val lifecycle = ActivityLifecycleMonitorRegistry.getInstance()
        val startedActivities = CopyOnWriteArrayList<String>()
        val callback = ActivityLifecycleCallback { activity, stage ->
            if (stage == Stage.CREATED || stage == Stage.RESUMED) startedActivities += activity.javaClass.name
        }
        instrumentation.runOnMainSync { lifecycle.addLifecycleCallback(callback) }

        val suffix = UUID.randomUUID().toString()
        val noteText = "Native review test $suffix"
        var noteId: Long? = null
        var testDueAt = 0L
        val preferenceStore = context.getSharedPreferences("flow_note_review", Context.MODE_PRIVATE)
        try {
            val runtime = AppContainer.get(context).nativeRuntime
            val initialized = runBlocking { withTimeout(TIMEOUT_MS) { runtime.refresh().await() } }
            assertTrue("Native runtime must initialize before the capture", initialized is CommandCommit.Committed)

            val capturedAt = System.currentTimeMillis()
            FlowBridge(context).pushEventAt("capture_note:$noteText", capturedAt)
            val committed = runBlocking { withTimeout(TIMEOUT_MS) { runtime.refresh().await() } }
            assertTrue("Queued CaptureNote must commit durably", committed is CommandCommit.Committed)
            val savedNote = (committed as CommandCommit.Committed).snapshot.qnotes.firstOrNull {
                (it["txt"] as? kotlinx.serialization.json.JsonPrimitive)?.content == noteText
            }
            assertNotNull("The captured note must exist in the committed snapshot", savedNote)
            noteId = requireNotNull(savedNote?.get("id")).let {
                requireNotNull((it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull)
            }
            val id = requireNotNull(noteId)

            val scheduled = await("CaptureNote's committed review request must be persisted") {
                val raw = preferenceStore.getString("pending", "{}") ?: "{}"
                JSONObject(raw).optLong(id.toString(), -1L).takeIf { it > 0L }?.let { testDueAt = it; true } ?: false
            }
            assertTrue(scheduled)
            assertTrue("The reducer should schedule review about five minutes after capture", kotlin.math.abs(testDueAt - (capturedAt + 300_000L)) < 30_000L)

            // Exercise the actual scheduled AlarmManager path quickly; the original five-minute request
            // was checked above and is replaced only for this unique test note.
            testDueAt = System.currentTimeMillis() + 1_000L
            FlowNoteReview.schedule(context, id, testDueAt)
            val tag = "note-review:$id"
            await("The note review alarm should post its notification") {
                notifications.activeNotifications.any { it.tag == tag }
            }
            val shown = notifications.activeNotifications.single { it.tag == tag }.notification
            assertEquals("Час повернутися до нотатки", shown.extras.getString("android.title"))
            assertEquals("Переглянь збережену нотатку", shown.extras.getString("android.text"))
            assertNotNull("The review notification must have a tap action", shown.contentIntent)
            val expectedOpen = Intent(context, NativeRuntime.entryClass())
                .setData(Uri.parse("flow://note-review-open/$id"))
            val expectedPending = PendingIntent.getActivity(context, FlowNoteReview.alarmRequestCode(id), expectedOpen,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            assertEquals("Tap target must be the app entry intent for this note", expectedPending, shown.contentIntent)

            await("Delivered review requests should be removed from durable pending state") {
                !JSONObject(preferenceStore.getString("pending", "{}") ?: "{}").has(noteId.toString())
            }
            assertTrue("The alarm delivery itself must not start an Activity: $startedActivities", startedActivities.isEmpty())
        } finally {
            instrumentation.runOnMainSync { lifecycle.removeLifecycleCallback(callback) }
            noteId?.let { id ->
                notifications.cancel("note-review:$id", FlowNoteReview.alarmRequestCode(id))
                val alarmIntent = Intent(context, FlowNoteReviewReceiver::class.java)
                    .setAction(FlowNoteReview.ACTION_REVIEW)
                    .setData(Uri.parse(FlowNoteReview.alarmDataUri(id)))
                val existing = PendingIntent.getBroadcast(context, FlowNoteReview.alarmRequestCode(id), alarmIntent,
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
                if (existing != null) {
                    (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(existing)
                    existing.cancel()
                }
                val pending = JSONObject(preferenceStore.getString("pending", "{}") ?: "{}")
                pending.remove(id.toString())
                preferenceStore.edit().putString("pending", pending.toString()).commit()
            }
        }
    }

    private fun await(message: String, predicate: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return true
            SystemClock.sleep(100L)
        }
        throw AssertionError(message)
    }

    companion object { private const val TIMEOUT_MS = 30_000L }
}
