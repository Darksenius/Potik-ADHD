package com.flow.adhd;

import static org.junit.Assert.*;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs against Android notification delivery and the actual Room command queue. */
@RunWith(AndroidJUnit4.class)
public class FlowReminderTest {
    @Test public void calendarRecurrenceHelpersHandleWeekdaysWeekendPreAndDst() throws Exception {
        TimeZone kyiv = TimeZone.getTimeZone("Europe/Kiev");
        JSONObject daily = calendarRow("daily", "2026-10-13", "09:00", 0, new JSONArray());
        assertEquals(at(kyiv, 2026, Calendar.OCTOBER, 14, 9, 0),
                FlowAlarmReceiver.nextOccurrenceAfter(daily, at(kyiv, 2026, Calendar.OCTOBER, 13, 9, 30), kyiv));

        JSONObject custom = calendarRow("custom", "2026-10-13", "09:00", 0,
                new JSONArray().put(false).put(false).put(false).put(true).put(false).put(false).put(false));
        assertEquals(at(kyiv, 2026, Calendar.OCTOBER, 15, 9, 0),
                FlowAlarmReceiver.nextOccurrenceAfter(custom, at(kyiv, 2026, Calendar.OCTOBER, 13, 9, 30), kyiv));

        JSONObject weekend = calendarRow("weekend", "2026-10-09", "09:00", 0, new JSONArray());
        assertEquals(at(kyiv, 2026, Calendar.OCTOBER, 10, 9, 0),
                FlowAlarmReceiver.nextOccurrenceAfter(weekend, at(kyiv, 2026, Calendar.OCTOBER, 9, 10, 0), kyiv));

        JSONObject pre = calendarRow("daily", "2026-10-13", "09:00", 30, new JSONArray());
        assertEquals(at(kyiv, 2026, Calendar.OCTOBER, 13, 8, 30),
                FlowAlarmReceiver.nextOccurrenceAfter(pre, at(kyiv, 2026, Calendar.OCTOBER, 13, 8, 0), kyiv));
        assertEquals(at(kyiv, 2026, Calendar.OCTOBER, 13, 8, 30),
                FlowAlarmReceiver.dueOccurrenceToday(pre, at(kyiv, 2026, Calendar.OCTOBER, 13, 8, 45), kyiv));

        JSONObject dst = calendarRow("daily", "2026-03-28", "09:00", 0, new JSONArray());
        long beforeDst = at(kyiv, 2026, Calendar.MARCH, 28, 9, 0);
        long nextAcrossDst = FlowAlarmReceiver.nextOccurrenceAfter(dst, beforeDst, kyiv);
        assertEquals("Kyiv's spring DST transition advances a daily 09:00 alarm by 23 hours",
                23L * 60 * 60 * 1000, nextAcrossDst - beforeDst);
        Calendar local = Calendar.getInstance(kyiv, Locale.US);
        local.setTimeInMillis(nextAcrossDst);
        assertEquals(2026, local.get(Calendar.YEAR));
        assertEquals(Calendar.MARCH, local.get(Calendar.MONTH));
        assertEquals(29, local.get(Calendar.DAY_OF_MONTH));
        assertEquals("DST transition preserves the configured wall-clock time", 9, local.get(Calendar.HOUR_OF_DAY));
    }

    @Test public void recurringAlarmDeliversEachOccurrenceOnceWithoutActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previous = bridge.getNotifJson();
        String originalState = bridge.load();
        String id = "qa-repeat-" + UUID.randomUUID();
        String doneId = "qa-done-repeat-" + UUID.randomUUID();
        TimeZone zone = TimeZone.getDefault();
        Calendar firstDay = Calendar.getInstance(zone, Locale.US);
        firstDay.set(Calendar.HOUR_OF_DAY, 0); firstDay.set(Calendar.MINUTE, 0);
        firstDay.set(Calendar.SECOND, 0); firstDay.set(Calendar.MILLISECOND, 0);
        Calendar secondDay = (Calendar) firstDay.clone(); secondDay.add(Calendar.DAY_OF_YEAR, 1);
        String anchor = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(firstDay.getTime());
        JSONObject reminder = calendarRow("daily", anchor, "09:00", 0, new JSONArray())
                .put("id", id).put("title", "QA recurring").put("dueMs", at(zone,
                        firstDay.get(Calendar.YEAR), firstDay.get(Calendar.MONTH), firstDay.get(Calendar.DAY_OF_MONTH), 9, 0))
                .put("fired", false);
        JSONObject doneReminder = new JSONObject(reminder.toString()).put("id", doneId).put("title", "QA done today");
        FlowAlarmReceiver receiver = new FlowAlarmReceiver();
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long firstDue = at(zone, firstDay.get(Calendar.YEAR), firstDay.get(Calendar.MONTH), firstDay.get(Calendar.DAY_OF_MONTH), 9, 0);
        long secondDue = at(zone, secondDay.get(Calendar.YEAR), secondDay.get(Calendar.MONTH), secondDay.get(Calendar.DAY_OF_MONTH), 9, 0);
        try {
            JSONArray liveTasks = new JSONArray()
                    .put(new JSONObject().put("id", id).put("type", "sched").put("done", false).put("trashed", false))
                    .put(new JSONObject().put("id", doneId).put("type", "sched").put("done", false).put("trashed", false));
            bridge.save(new JSONObject().put("tasks", liveTasks).toString());
            bridge.saveNotif(new JSONObject().put("schedList", new JSONArray().put(reminder).put(doneReminder)).toString());
            bridge.pushEvent("task_done:" + doneId);
            receiver.checkSchedAt(context, bridge, firstDue + 60_000L, zone);
            receiver.checkSchedAt(context, bridge, firstDue + 60_000L, zone);
            receiver.checkSchedAt(context, bridge, secondDue + 60_000L, zone);

            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            assertEquals("Each daily occurrence is delivered once", 1,
                    countEvent(events, "sched_fired:" + id + ":" + firstDue));
            assertEquals("The next day's recurrence is not suppressed by yesterday's delivery key", 1,
                    countEvent(events, "sched_fired:" + id + ":" + secondDue));
            assertEquals("Same-day Done suppresses the current occurrence", 0,
                    countEvent(events, "sched_fired:" + doneId + ":" + firstDue));
            assertEquals("Done does not suppress the next calendar occurrence", 1,
                    countEvent(events, "sched_fired:" + doneId + ":" + secondDue));
        } finally {
            notifications.cancel("flow-task:" + id, 5000);
            notifications.cancel("flow-task:" + doneId, 5000);
            acknowledgeTestReceipts(bridge, id, doneId, originalState);
            bridge.save(originalState == null ? "" : originalState);
            bridge.saveNotif(previous == null || previous.isEmpty() ? "{}" : previous);
        }
    }

    @Test public void delayedReminderHasOneNotificationAndOneReceiptForItsDeadline() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previous = bridge.getNotifJson();
        String originalState = bridge.load();
        String id = "qa-" + System.currentTimeMillis();
        long due = System.currentTimeMillis() - 5000;
        String receipt = "sched_fired:" + id + ":" + due;
        JSONObject reminder = new JSONObject().put("id", id).put("title", "QA deadline").put("dueMs", due).put("fired", false);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowAlarmReceiver receiver = new FlowAlarmReceiver();
        try {
            bridge.save(new JSONObject().put("tasks", new JSONArray().put(new JSONObject()
                    .put("id", id).put("type", "sched").put("done", false).put("trashed", false))).toString());
            bridge.saveNotif(new JSONObject().put("schedList", new JSONArray().put(reminder)).toString());
            receiver.onReceive(context, new Intent(FlowAlarmReceiver.ACTION_TASK_DUE));
            receiver.onReceive(context, new Intent(FlowAlarmReceiver.ACTION_TASK_DUE));
            // Direct receiver fixtures enqueue work on the shared FIFO lane; this synchronous bridge read
            // waits behind both deliveries before the test inspects notification side effects.
            bridge.getEventEnvelopes();
            int shown = 0;
            for (StatusBarNotification notification : notifications.getActiveNotifications()) {
                if (("flow-task:" + id).equals(notification.getTag())) shown++;
            }
            assertEquals("Repeated checks must not create duplicate reminders", 1, shown);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            int acknowledged = 0;
            for (int i = 0; i < events.length(); i++) {
                if (receipt.equals(events.getJSONObject(i).getString("event"))) acknowledged++;
            }
            assertEquals("Receipt identifies the exact deadline, once", 1, acknowledged);
        } finally {
            notifications.cancel("flow-task:" + id, 5000);
            acknowledgeTestReceipts(bridge, id, "", originalState);
            bridge.save(originalState == null ? "" : originalState);
            bridge.saveNotif(previous == null || previous.isEmpty() ? "{}" : previous);
        }
    }

    @Test public void snoozeIntentKeepsTitleRoundsDeadlineAndDeduplicatesOccurrence() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String originalState = bridge.load();
        String id = "qa-snooze-" + UUID.randomUUID();
        long unrounded = 1_792_000_123_456L;
        long due = FlowAlarmReceiver.roundUpToMinute(unrounded);
        Intent snooze = FlowAlarmReceiver.createSnoozeIntent(context, id, "Збережений заголовок", due);
        assertTrue(due >= unrounded && due - unrounded < 60_000L);
        assertEquals(0L, due % 60_000L);
        assertEquals(due, snooze.getLongExtra(FlowAlarmReceiver.EXTRA_SNOOZE_DUE_MS, 0L));
        assertEquals("Збережений заголовок", snooze.getStringExtra("snooze_title"));

        JSONObject task = new JSONObject().put("id", id).put("title", "Збережений заголовок")
                .put("type", "simple").put("done", false).put("trashed", false);
        FlowAlarmReceiver receiver = new FlowAlarmReceiver();
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        try {
            bridge.save(new JSONObject().put("tasks", new JSONArray().put(task)).toString());
            assertTrue("The first delivery shows the cached title", receiver.deliverSnoozeAt(
                    context, bridge, id, snooze.getStringExtra("snooze_title"), due));
            assertFalse("The same scheduled deadline cannot notify twice", receiver.deliverSnoozeAt(
                    context, bridge, id, "Fallback title", due));
            StatusBarNotification shown = null;
            for (StatusBarNotification item : notifications.getActiveNotifications()) {
                if (("flow-task:" + id).equals(item.getTag())) shown = item;
            }
            assertNotNull(shown);
            assertEquals("⏰ Збережений заголовок", shown.getNotification().extras.getString("android.title"));
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            assertEquals(1, countEvent(events, "sched_fired:" + id + ":" + due));
            assertEquals(1, countEvent(events, "task_remind:" + id));
        } finally {
            notifications.cancel("flow-task:" + id, 5000);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            JSONArray ids = new JSONArray();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                String value = event.optString("event", "");
                if (value.equals("sched_fired:" + id + ":" + due) || value.equals("task_remind:" + id)) {
                    ids.put(event.getLong("id"));
                }
            }
            if (ids.length() > 0) bridge.saveWithEvents(originalState, ids.toString());
            bridge.save(originalState);
        }
    }

    private static JSONObject calendarRow(String repeat, String anchor, String localTime,
                                          int advanceMinutes, JSONArray repeatDays) throws Exception {
        return new JSONObject().put("repeat", repeat).put("anchorDate", anchor).put("localTime", localTime)
                .put("advanceMinutes", advanceMinutes).put("repeatDays", repeatDays);
    }

    private static long at(TimeZone zone, int year, int month, int day, int hour, int minute) {
        Calendar value = Calendar.getInstance(zone, Locale.US);
        value.clear();
        value.set(year, month, day, hour, minute, 0);
        return value.getTimeInMillis();
    }

    private static int countEvent(JSONArray events, String exact) throws Exception {
        int count = 0;
        for (int i = 0; i < events.length(); i++) if (exact.equals(events.getJSONObject(i).optString("event"))) count++;
        return count;
    }

    private static void acknowledgeTestReceipts(FlowBridge bridge, String id, String doneId, String state) throws Exception {
        JSONArray events = new JSONArray(bridge.getEventEnvelopes());
        JSONArray ids = new JSONArray();
        String prefix = "sched_fired:" + id + ":";
        for (int i = 0; i < events.length(); i++) {
            JSONObject row = events.getJSONObject(i);
            String event = row.optString("event", "");
            if (event.startsWith(prefix) || event.startsWith("sched_fired:" + doneId + ":")
                    || event.equals("task_done:" + doneId)) ids.put(row.getLong("id"));
        }
        if (ids.length() > 0) bridge.saveWithEvents(state == null || state.isEmpty() ? "{}" : state, ids.toString());
        bridge.save(state == null ? "" : state);
    }
}
