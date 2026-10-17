package com.flow.adhd

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.flow.adhd.domain.SnapshotRead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.longOrNull
import org.json.JSONObject
import java.util.concurrent.Executors

/** Durable, one-shot reminders to review newly captured notes. */
object FlowNoteReview {
    const val ACTION_REVIEW = "com.flow.adhd.NOTE_REVIEW"
    const val EXTRA_NOTE_ID = "openNoteId"
    private const val PREFS = "flow_note_review"
    private const val PREF_PENDING = "pending"
    private const val CHANNEL = FlowAlarmReceiver.CHANNEL_REMIND
    private val receiverExecutor = Executors.newSingleThreadExecutor()

    /** Save and schedule (or replace) the review for a note. */
    @JvmStatic
    @Synchronized
    fun schedule(context: Context, noteId: Long, dueAt: Long) {
        require(noteId >= 0L)
        require(dueAt > 0L)
        val app = context.applicationContext
        val pending = readPending(app).apply { put(noteId.toString(), dueAt) }
        if (!writePending(app, pending)) return
        installAlarm(app, noteId, dueAt)
    }

    /** Recreate all one-shot alarms from durable state after boot or app startup. */
    @JvmStatic
    @Synchronized
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val pending = readPending(app)
        for (key in pending.keys()) {
            val noteId = key.toLongOrNull() ?: continue
            val dueAt = pending.optLong(key, 0L)
            if (noteId >= 0L && dueAt > 0L) installAlarm(app, noteId, dueAt)
        }
    }

    private fun installAlarm(context: Context, noteId: Long, dueAt: Long) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = alarmIntent(context, noteId).putExtra("dueAt", dueAt)
        val pending = PendingIntent.getBroadcast(context, alarmRequestCode(noteId), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, dueAt, pending)
            } else {
                manager.setExact(AlarmManager.RTC_WAKEUP, dueAt, pending)
            }
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, dueAt, pending)
        }
    }

    private fun alarmIntent(context: Context, noteId: Long): Intent =
        Intent().setClassName(context.packageName, FlowNoteReviewReceiver::class.java.name)
            .setAction(ACTION_REVIEW)
            .setData(Uri.parse(alarmDataUri(noteId)))
            .putExtra(EXTRA_NOTE_ID, noteId)

    internal fun alarmDataUri(noteId: Long): String = "flow://note-review/$noteId"

    internal fun alarmRequestCode(noteId: Long): Int = (noteId xor (noteId ushr 32)).toInt()

    internal fun containsNote(snapshot: SnapshotRead, noteId: Long): Boolean =
        (snapshot as? SnapshotRead.Ready)?.snapshot?.qnotes?.any { note ->
            note["id"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.longOrNull } == noteId
        } == true

    private fun immutableFlag() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    private fun readPending(context: Context): JSONObject = try {
        JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PREF_PENDING, "{}") ?: "{}")
    } catch (_: Exception) { JSONObject() }

    private fun writePending(context: Context, pending: JSONObject): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PREF_PENDING, pending.toString()).commit()

    @Synchronized private fun removePending(context: Context, noteId: Long, expectedDueAt: Long): Boolean {
        val pending = readPending(context)
        val key = noteId.toString()
        if (pending.optLong(key, -1L) != expectedDueAt) return false
        pending.remove(key)
        return writePending(context, pending)
    }

    internal fun receive(context: Context, intent: Intent?, finish: () -> Unit) {
            if (intent?.action != ACTION_REVIEW) { finish(); return }
            val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
            val dueAt = intent.getLongExtra("dueAt", -1L)
            if (noteId < 0L || dueAt <= 0L) { finish(); return }
            val app = context.applicationContext
            receiverExecutor.execute {
                try {
                    val pending = readPending(app)
                    if (pending.optLong(noteId.toString(), -1L) != dueAt) return@execute
                    if (dueAt > System.currentTimeMillis()) { installAlarm(app, noteId, dueAt); return@execute }
                    val snapshot = runBlocking(Dispatchers.IO) {
                        val runtime = AppContainer.get(app).nativeRuntime
                        runtime.refresh().await()
                        runtime.repository.read()
                    }
                    if (!containsNote(snapshot, noteId)) {
                        removePending(app, noteId, dueAt)
                        return@execute
                    }
                    synchronized(this) {
                        if (readPending(app).optLong(noteId.toString(), -1L) == dueAt && postNotification(app, noteId)) removePending(app, noteId, dueAt)
                    }
                } catch (error: Exception) {
                    android.util.Log.w("FlowNoteReview", "Unable to deliver note review", error)
                } finally {
                    finish()
                }
            }
    }

    private fun postNotification(context: Context, noteId: Long): Boolean {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Потік — нагадування", NotificationManager.IMPORTANCE_HIGH))
        }
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val open = Intent(context, NativeRuntime.entryClass())
            .setData(Uri.parse("flow://note-review-open/$noteId"))
            .putExtra(EXTRA_NOTE_ID, noteId.toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val content = PendingIntent.getActivity(context, alarmRequestCode(noteId), open,
            PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag())
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Час повернутися до нотатки")
            .setContentText("Переглянь збережену нотатку")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(content)
            .build()
        return try {
            manager.notify("note-review:$noteId", alarmRequestCode(noteId), notification)
            true
        } catch (_: SecurityException) { false }
    }
}

/** Manifest-facing receiver kept top-level for Android component discovery. */
class FlowNoteReviewReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        FlowNoteReview.receive(context, intent) { pending.finish() }
    }
}
