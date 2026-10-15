package com.flow.adhd;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HashSet;
import java.util.Set;
import java.util.Calendar;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real notification actions persist to Room before React is launched, then materialize once. */
@RunWith(AndroidJUnit4.class)
public class FlowCaptureTest {
    private static final long TIMEOUT_MS = 30000;

    @Test public void collidingTaskIdHashesKeepCardAndActionPendingIntentsDistinct() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String firstId = "84300921";
        String secondId = "1348346374";
        PendingIntent firstOpen = FlowNotifService.openTaskPendingIntent(context, firstId);
        PendingIntent secondOpen = FlowNotifService.openTaskPendingIntent(context, secondId);
        PendingIntent firstDone = FlowNotifService.taskAction(context, android.R.drawable.checkbox_on_background,
                "Виконано", FlowNotifService.ACTION_TASK_DONE, firstId, null, FlowNotifService.NOTIF_TASK_BASE).actionIntent;
        PendingIntent secondDone = FlowNotifService.taskAction(context, android.R.drawable.checkbox_on_background,
                "Виконано", FlowNotifService.ACTION_TASK_DONE, secondId, null, FlowNotifService.NOTIF_TASK_BASE).actionIntent;
        PendingIntent oldCardDone = FlowNotifService.taskAction(context, android.R.drawable.checkbox_on_background,
                "Виконано", FlowNotifService.ACTION_TASK_DONE, firstId, null, FlowNotifService.NOTIF_TASK_BASE,
                "generation-old").actionIntent;
        PendingIntent refreshedCardDone = FlowNotifService.taskAction(context, android.R.drawable.checkbox_on_background,
                "Виконано", FlowNotifService.ACTION_TASK_DONE, firstId, null, FlowNotifService.NOTIF_TASK_BASE,
                "generation-new").actionIntent;
        try {
            assertNotEquals("Different task cards must not share an open token", firstOpen, secondOpen);
            assertNotEquals("Different task actions must not share a service token", firstDone, secondDone);
            assertNotEquals("Refreshing a card after a zone transition must invalidate its stale action token",
                    oldCardDone, refreshedCardDone);
        } finally {
            firstOpen.cancel();
            secondOpen.cancel();
            firstDone.cancel();
            secondDone.cancel();
            oldCardDone.cancel();
            refreshedCardDone.cancel();
        }
    }

    @Test public void questActionsUseCurrentPayloadAndPruneRemovedTasks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previousNotif = bridge.getNotifJson();
        String suffix = UUID.randomUUID().toString();
        String counterId = "counter-" + suffix;
        String removedId = "removed-" + suffix;
        String freshNotTodayId = "not-today-" + suffix;
        String counterTitle = "Counter " + suffix;
        String ordinaryTitle = "Ordinary " + suffix;
        String freshNotTodayTitle = "Fresh not-today " + suffix;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String originalState = bridge.load();
        try {
            long now = System.currentTimeMillis();
            String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date(now));
            JSONObject counterTask = new JSONObject()
                .put("id", counterId).put("title", counterTitle).put("type", "counter")
                .put("counterEnabled", true).put("counterStep", 100).put("counterUnit", "ml")
                .put("counter", 99).put("counterTarget", 0).put("repeat", "daily")
                .put("repeatDays", new JSONArray().put(true).put(true).put(true).put(true).put(true).put(true).put(true))
                .put("planDate", todayKey).put("counterDay", todayKey);
            JSONObject ordinaryTask = new JSONObject()
                .put("id", removedId).put("title", ordinaryTitle).put("type", "simple")
                .put("counterEnabled", false);
            JSONObject freshNotTodayTask = new JSONObject()
                .put("id", freshNotTodayId).put("title", freshNotTodayTitle).put("type", "simple")
                .put("counterEnabled", false);
            JSONArray taskRows = new JSONArray().put(counterTask).put(ordinaryTask).put(freshNotTodayTask);
            JSONArray timeline = new JSONArray()
                .put(new JSONObject().put("zoneId", 1).put("startAt", now - 60 * 60_000L).put("endAt", now + 60 * 60_000L))
                .put(new JSONObject().put("zoneId", 2).put("startAt", now + 60 * 60_000L).put("endAt", now + 2 * 60 * 60_000L))
                .put(new JSONObject().put("zoneId", 1).put("startAt", now + 2 * 60 * 60_000L).put("endAt", now + 24 * 60 * 60_000L));
            bridge.saveNotif(new JSONObject().put("body", "3 активних · 0 виконано").put("done", 0).put("total", 3)
                .put("tasks", counterTitle + "|" + ordinaryTitle + "|" + freshNotTodayTitle).put("taskList", new JSONArray(taskRows.toString()))
                .put("questTasks", taskRows).put("questTimeline", timeline).put("questTimelineUntil", now + 24 * 60 * 60_000L).toString());
            FlowNotifService.start(context);
            showTaskNotifications(context);

            Notification counter = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE);
            assertEquals(counterTitle, counter.extras.getCharSequence(Notification.EXTRA_TITLE).toString().replaceFirst("^1\\.  ", ""));
            assertEquals("99 мл", counter.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            assertEquals("Each quest card has exactly the three primary actions", 3, counter.actions.length);
            assertTrue(hasAction(counter, "Виконано", FlowNotifService.ACTION_TASK_DONE));
            assertTrue(hasAction(counter, "Не зараз", FlowNotifService.ACTION_TASK_NEXT_ZONE));
            assertTrue(hasAction(counter, "Не сьогодні", FlowNotifService.ACTION_TASK_NOT_TODAY));
            assertFalse("Counter controls stay in the app", hasActionWithPrefix(counter, FlowNotifService.ACTION_TASK_INC));
            assertFalse("Counter controls stay in the app", hasActionWithPrefix(counter, FlowNotifService.ACTION_TASK_DEC));
            Notification ordinary = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 1);
            assertFalse(hasActionWithPrefix(ordinary, FlowNotifService.ACTION_TASK_INC));
            assertFalse(hasActionWithPrefix(ordinary, FlowNotifService.ACTION_TASK_DEC));
            assertEquals(3, ordinary.actions.length);
            assertTrue(hasAction(ordinary, "Виконано", FlowNotifService.ACTION_TASK_DONE));
            assertTrue(hasAction(ordinary, "Не зараз", FlowNotifService.ACTION_TASK_NEXT_ZONE));
            assertTrue(hasAction(ordinary, "Не сьогодні", FlowNotifService.ACTION_TASK_NOT_TODAY));
            Notification freshNotTodayCard = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 2);
            Notification.Action freshNotToday = findServiceAction(freshNotTodayCard,
                    FlowNotifService.ACTION_TASK_NOT_TODAY);

            Notification.Action ordinaryNext = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_NEXT_ZONE);
            ordinaryNext.actionIntent.send(context, 0, null);
            JSONObject deferralRow = awaitTaskEventPrefix(bridge, "task_defer:" + removedId + ":");
            String deferralEvent = deferralRow.getString("event");
            JSONObject deferral = new JSONObject(deferralEvent.substring(("task_defer:" + removedId + ":").length()));
            long nextRequestedAt = deferral.getLong("requestedAt");
            long targetStart = now + 60 * 60_000L;
            long targetEnd = now + 2 * 60 * 60_000L;
            assertTimestampNear("Deferral request time should match its durable event", deferralRow.getLong("createdAt"),
                    nextRequestedAt);
            SimpleDateFormat localDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            boolean targetIsSameLocalDay = localDay.format(new java.util.Date(nextRequestedAt))
                    .equals(localDay.format(new java.util.Date(targetStart)));
            if (targetIsSameLocalDay) {
                assertEquals("A same-day target segment uses next-zone", "next-zone", deferral.getString("kind"));
                assertEquals(1, deferral.getInt("sourceZoneId"));
                assertEquals(2, deferral.getInt("targetZoneId"));
                assertEquals("Next-zone availability starts at the resolved target segment", targetStart,
                        deferral.getLong("startAt"));
                assertEquals("Next-zone availability ends with the resolved target segment", targetEnd,
                        deferral.getLong("endAt"));
            } else {
                assertEquals("A next-day target falls back to not-today", "not-today", deferral.getString("kind"));
                assertEquals(0, deferral.getInt("targetZoneId"));
                assertEquals("Unbound fallback begins 23 hours after the actual tap", 23L * 60 * 60_000L,
                        deferral.getLong("startAt") - nextRequestedAt);
                assertFalse("Unbound fallback has no end time", deferral.has("endAt"));
            }

            Notification.Action ordinaryNotToday = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_NOT_TODAY);
            ordinaryNotToday.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            assertEquals("The old card cannot defer the same task a second time",
                    1, countEventPrefix(new JSONArray(bridge.getEventEnvelopes()), "task_defer:" + removedId + ":"));

            freshNotToday.actionIntent.send(context, 0, null);
            JSONObject notTodayRow = awaitTaskDeferralEvent(bridge, freshNotTodayId, "not-today");
            String notTodayEvent = notTodayRow.getString("event");
            JSONObject notToday = new JSONObject(notTodayEvent.substring(("task_defer:" + freshNotTodayId + ":").length()));
            long requestedAt = notToday.getLong("requestedAt");
            assertTimestampNear("Not-today request time should match its durable event", notTodayRow.getLong("createdAt"), requestedAt);
            assertEquals("An unbound task becomes available 23 hours after the tap", 23L * 60 * 60_000L,
                    notToday.getLong("startAt") - requestedAt);
            assertEquals(0, notToday.getInt("targetZoneId"));
            assertFalse("Unbound not-today deferral has no end time", notToday.has("endAt"));

            Notification.Action counterDone = findServiceAction(counter, FlowNotifService.ACTION_TASK_DONE);
            counterDone.actionIntent.send(context, 0, null);
            awaitTaskEvent(bridge, "task_done:" + counterId);
            counterDone.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            assertEquals("Pending Done cannot be queued twice", 1,
                    countEvent(new JSONArray(bridge.getEventEnvelopes()), "task_done:" + counterId));
            awaitForeground(context, "Зараз: 0 задач", null, null);
            assertEquals("Notification projection does not write task state", originalState, bridge.load());

            bridge.saveNotif(new JSONObject().put("taskList", new JSONArray()).put("questTasks", new JSONArray())
                .put("questTimeline", timeline).put("questTimelineUntil", now + 24 * 60 * 60_000L).toString());
            showTaskNotifications(context);
            awaitTaskNotificationTitle(manager, FlowNotifService.NOTIF_TASK_BASE, "Квести");
            assertFalse("Removed task notification should be pruned", hasActiveNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 1));
            assertFalse("Task summary should be pruned for an empty list", hasActiveNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 100));

            Notification.Action staleDone = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_DONE);
            Notification.Action staleDefer = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_NEXT_ZONE);
            JSONArray eventsBeforeStaleActions = new JSONArray(bridge.getEventEnvelopes());
            int priorCounterDoneEvents = countEvent(eventsBeforeStaleActions, "task_done:" + counterId);
            int priorDeferralEvents = countEventPrefix(eventsBeforeStaleActions, "task_defer:" + removedId + ":");
            staleDone.actionIntent.send(context, 0, null);
            staleDefer.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            assertEquals("Stale Done must not add another event", priorCounterDoneEvents, countEvent(events, "task_done:" + counterId));
            assertEquals("Stale card actions do not act on a removed task", priorDeferralEvents,
                    countEventPrefix(events, "task_defer:" + removedId + ":"));
        } finally {
            if (manager != null) {
                for (int i = 0; i < 8; i++) manager.cancel(FlowNotifService.NOTIF_TASK_BASE + i);
                manager.cancel(FlowNotifService.NOTIF_TASK_BASE + 100);
            }
            try {
                JSONArray pending = new JSONArray(bridge.getEventEnvelopes());
                JSONArray ownIds = new JSONArray();
                for (int i = 0; i < pending.length(); i++) {
                    JSONObject row = pending.getJSONObject(i);
                    String event = row.optString("event", "");
                    if (event.equals("task_inc:" + counterId) || event.equals("task_dec:" + counterId)
                            || event.equals("task_done:" + counterId) || event.equals("task_done:" + removedId)
                            || event.equals("task_done:" + freshNotTodayId)
                            || event.equals("task_skip:" + removedId)
                            || event.startsWith("task_defer:" + removedId + ":")
                            || event.startsWith("task_defer:" + freshNotTodayId + ":")) {
                        ownIds.put(row.getLong("id"));
                    }
                }
                if (ownIds.length() > 0) {
                    String state = originalState == null || originalState.isEmpty() ? "{}" : originalState;
                    bridge.saveWithEvents(state, ownIds.toString());
                }
            } catch (Exception ignored) {}
            bridge.save(originalState == null ? "" : originalState);
            bridge.saveNotif(previousNotif == null || previousNotif.isEmpty() ? "{}" : previousNotif);
            FlowNotifService.stop(context);
        }
    }

    @Test public void completedDailyAndEveryZoneRowsAcceptTheFreshOccurrenceActionOnce() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previousNotif = bridge.getNotifJson();
        String originalState = bridge.load();
        String suffix = UUID.randomUUID().toString();
        String dailyId = "daily-fresh-" + suffix;
        String everyZoneId = "zone-fresh-" + suffix;
        String dailyTitle = "Daily fresh " + suffix;
        String everyZoneTitle = "Every-zone fresh " + suffix;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowNotifService.stop(context);
        for (int i = 0; i < 8; i++) manager.cancel(FlowNotifService.NOTIF_TASK_BASE + i);
        manager.cancel(FlowNotifService.NOTIF_TASK_BASE + 100);
        try {
            long now = System.currentTimeMillis();
            Calendar yesterdayCalendar = Calendar.getInstance();
            yesterdayCalendar.setTimeInMillis(now);
            yesterdayCalendar.add(Calendar.DAY_OF_YEAR, -1);
            SimpleDateFormat localDay = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            String yesterday = localDay.format(yesterdayCalendar.getTime());
            JSONObject daily = new JSONObject().put("id", dailyId).put("title", dailyTitle)
                    .put("type", "simple").put("repeat", "daily").put("done", true)
                    .put("planDate", yesterday).put("doneDate", yesterday)
                    .put("completionCreditDate", yesterday);
            JSONObject everyZone = new JSONObject().put("id", everyZoneId).put("title", everyZoneTitle)
                    .put("type", "simple").put("repeat", "everyzone").put("zoneId", 1)
                    .put("done", true).put("doneDate", yesterday).put("completionCreditDate", yesterday)
                    .put("zoneOccurrenceKey", yesterday + ":1:00:00");
            JSONArray tasks = new JSONArray().put(daily).put(everyZone);
            JSONArray timeline = new JSONArray()
                    .put(new JSONObject().put("zoneId", 1).put("startAt", now - 60 * 60_000L).put("endAt", now + 60 * 60_000L))
                    .put(new JSONObject().put("zoneId", 2).put("startAt", now + 60 * 60_000L).put("endAt", now + 2 * 60 * 60_000L))
                    .put(new JSONObject().put("zoneId", 1).put("startAt", now + 2 * 60 * 60_000L).put("endAt", now + 24 * 60 * 60_000L));
            bridge.saveNotif(new JSONObject().put("body", "2 активних · 0 виконано").put("done", 0).put("total", 2)
                    .put("tasks", dailyTitle + "|" + everyZoneTitle).put("taskList", new JSONArray(tasks.toString()))
                    .put("questTasks", tasks).put("questTimeline", timeline)
                    .put("questTimelineUntil", now + 24 * 60 * 60_000L).toString());
            FlowNotifService.start(context);
            showTaskNotifications(context);

            Notification dailyCard = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE);
            Notification everyZoneCard = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 1);
            assertTrue("A daily task completed yesterday is actionable in today's occurrence",
                    dailyCard.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(dailyTitle));
            assertTrue("An every-zone task completed in an earlier slot is actionable in this slot",
                    everyZoneCard.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains(everyZoneTitle));

            Notification.Action dailyDone = findServiceAction(dailyCard, FlowNotifService.ACTION_TASK_DONE);
            dailyDone.actionIntent.send(context, 0, null);
            awaitTaskEvent(bridge, "task_done:" + dailyId);
            dailyDone.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            assertEquals("A second tap on the previous daily card is rejected", 1,
                    countEvent(new JSONArray(bridge.getEventEnvelopes()), "task_done:" + dailyId));

            Notification.Action everyZoneDone = findServiceAction(everyZoneCard, FlowNotifService.ACTION_TASK_DONE);
            everyZoneDone.actionIntent.send(context, 0, null);
            awaitTaskEvent(bridge, "task_done:" + everyZoneId);
            everyZoneDone.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            assertEquals("A second tap on the previous every-zone card is rejected", 1,
                    countEvent(new JSONArray(bridge.getEventEnvelopes()), "task_done:" + everyZoneId));
        } finally {
            if (manager != null) {
                for (int i = 0; i < 8; i++) manager.cancel(FlowNotifService.NOTIF_TASK_BASE + i);
                manager.cancel(FlowNotifService.NOTIF_TASK_BASE + 100);
            }
            try {
                JSONArray events = new JSONArray(bridge.getEventEnvelopes());
                JSONArray ownIds = new JSONArray();
                for (int i = 0; i < events.length(); i++) {
                    JSONObject row = events.optJSONObject(i);
                    String event = row == null ? "" : row.optString("event", "");
                    if (event.equals("task_done:" + dailyId) || event.equals("task_done:" + everyZoneId))
                        ownIds.put(row.getLong("id"));
                }
                if (ownIds.length() > 0) bridge.saveWithEvents(originalState == null ? "{}" : originalState, ownIds.toString());
            } catch (Exception ignored) {}
            bridge.save(originalState == null ? "" : originalState);
            bridge.saveNotif(previousNotif == null || previousNotif.isEmpty() ? "{}" : previousNotif);
            FlowNotifService.stop(context);
        }
    }

    @Test public void separateShadeActionsQueueAndMaterializeThroughRoom() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String suffix = UUID.randomUUID().toString();
        String taskText = "Shade task " + suffix;
        String noteText = "Shade note " + suffix;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowNotifService.stop(context);
        notificationManager.cancel(FlowNotifService.NOTIF_ID);
        for (int i = 0; i < 8; i++) notificationManager.cancel(FlowNotifService.NOTIF_TASK_BASE + i);
        notificationManager.cancel(FlowNotifService.NOTIF_TASK_BASE + 100);
        awaitNotificationAbsent(context);
        long timelineStart = System.currentTimeMillis();
        long timelineEnd = timelineStart + 35L * 24L * 60L * 60L * 1000L;
        JSONArray captureTimeline = new JSONArray().put(new JSONObject()
            .put("zoneId", 0).put("startAt", timelineStart - 60_000L).put("endAt", timelineEnd));
        new FlowBridge(context).saveNotif(new JSONObject().put("questTasks", new JSONArray())
            .put("questTimeline", captureTimeline).put("questTimelineUntil", timelineEnd).toString());
        FlowNotifService.start(context); // No MainActivity exists yet.
        try {
        Notification notification = awaitNotification(context);
        Notification.Action taskAction = findAction(notification, "+ Задача", FlowNotifService.KEY_TASK);
        Notification.Action noteAction = findAction(notification, "+ Нотатка", FlowNotifService.KEY_NOTE);
        assertFalse("Each capture button needs its own PendingIntent", taskAction.actionIntent.equals(noteAction.actionIntent));
        FlowBridge freshBridge = new FlowBridge(context);
        Set<Long> beforeWhitespace = eventIds(freshBridge);
        sendCapture(context, taskAction, FlowNotifService.KEY_TASK, "  \n  ");
        awaitReceipt(context, "Введи текст задачі");
        assertEquals("Whitespace task must not add any Room row", beforeWhitespace, eventIds(freshBridge));
        sendCapture(context, findAction(awaitNotification(context), "+ Нотатка", FlowNotifService.KEY_NOTE), FlowNotifService.KEY_NOTE, " \t ");
        awaitReceipt(context, "Введи текст нотатки");
        assertEquals("Whitespace note must not add any Room row", beforeWhitespace, eventIds(freshBridge));
        sendCapture(context, findAction(awaitNotification(context), "+ Задача", FlowNotifService.KEY_TASK), FlowNotifService.KEY_TASK, taskText);
        JSONObject capturedCommand = awaitCapturedTaskEvent(freshBridge, taskText);
        String capturedTaskIdText = captureTaskId(capturedCommand.getString("event"));
        long capturedTaskId = Long.parseLong(capturedTaskIdText);
        JSONObject capturedPayload = new JSONObject(capturedCommand.getString("event")
            .substring(("capture_task_v2:" + capturedTaskIdText + ":").length()));
        assertTrue(capturedTaskId > 0 && capturedTaskId <= 9_007_199_254_740_991L);
        assertEquals(capturedCommand.getLong("createdAt"), capturedPayload.getLong("createdAt"));
        Notification capturedCard = awaitTaskNotification(notificationManager, FlowNotifService.NOTIF_TASK_BASE,
                "1.  " + taskText);
        assertEquals("1.  " + taskText, capturedCard.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(3, capturedCard.actions.length);
        assertTrue(hasAction(capturedCard, "Виконано", FlowNotifService.ACTION_TASK_DONE));
        assertTrue(hasAction(capturedCard, "Не зараз", FlowNotifService.ACTION_TASK_NEXT_ZONE));
        assertTrue(hasAction(capturedCard, "Не сьогодні", FlowNotifService.ACTION_TASK_NOT_TODAY));
        awaitReceipt(context, "Запис задачі збережено");
        sendCapture(context, findAction(awaitNotification(context), "+ Нотатка", FlowNotifService.KEY_NOTE), FlowNotifService.KEY_NOTE, noteText);
        awaitReceipt(context, "Нотатку збережено");

        FlowBridge queuedBridge = new FlowBridge(context);
        JSONArray queued = awaitCommands(queuedBridge, taskText, noteText);
        assertEquals(2, queued.length());
        long taskEventId = -1, noteEventId = -1;
        for (int i = 0; i < queued.length(); i++) {
            JSONObject row = queued.getJSONObject(i);
            if (row.getString("event").startsWith("capture_task_v2:")) taskEventId = row.getLong("id");
            if (row.getString("event").startsWith("capture_note:")) noteEventId = row.getLong("id");
        }
        assertTrue(taskEventId > 0 && noteEventId > 0 && taskEventId != noteEventId);
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitWeb(scenario, "document.querySelector('#add-fab') !== null", "React app starts");
            awaitWeb(scenario, "(function(){var s=JSON.parse(window.FlowBridge.load()||'{}');"
                + "return (s.tasks||[]).filter(function(t){return t.title===" + JSONObject.quote(taskText)
                + ";}).length===1 && (s.qnotes||[]).filter(function(n){return n.txt===" + JSONObject.quote(noteText)
                + ";}).length===1;})()", "Both queued captures are committed to Room app_state");
            JSONObject state = new JSONObject(new FlowBridge(context).load());
            JSONObject task = findBy(state.getJSONArray("tasks"), "title", taskText);
            assertEquals("JS materializes the stable numeric ID allocated by native capture", capturedTaskId, task.getLong("id"));
            assertEquals("simple", task.getString("type"));
            assertFalse("Shade task has no planned date", task.has("planDate"));
            assertFalse("Shade task has no scheduled date", task.has("schedDate"));
            assertFalse("Shade task has no scheduled time", task.has("schedTime"));
            assertFalse("Shade task has no alarm time", task.has("alarmTime"));
            assertFalse("Shade task has no reminder", task.has("remindBeforeMinutes") && task.getInt("remindBeforeMinutes") != 0);
            JSONObject note = findBy(state.getJSONArray("qnotes"), "txt", noteText);
            assertEquals("impulse", note.getString("folder"));
            awaitAcknowledged(new FlowBridge(context), taskEventId, noteEventId);
            evaluate(scenario, "document.querySelector('#tabs button:nth-child(3)').click();return true;");
            awaitWeb(scenario, "document.querySelector('#inbox-sec') && document.querySelector('#inbox-sec').textContent.indexOf("
                + JSONObject.quote(taskText) + ")>=0", "Task is accessible from Inbox");
            evaluate(scenario, "document.querySelector('#tabs button:nth-child(4)').click();return true;");
            awaitWeb(scenario, "document.querySelector('.more-menu') !== null", "More sections open");
            evaluate(scenario, "var b=document.querySelector('.more-menu button[aria-label=\\\"Блокнот\\\"]');if(!b)throw new Error('Notes tab missing');b.click();return true;");
            awaitWeb(scenario, "document.querySelector('#qn-list') !== null", "Notes section opens");
            awaitWeb(scenario, "document.querySelector('#qn-list') && document.querySelector('#qn-list').textContent.indexOf("
                + JSONObject.quote(noteText) + ")>=0", "Note is accessible in Notes");
            scenario.recreate();
            awaitWeb(scenario, "(function(){var s=JSON.parse(window.FlowBridge.load()||'{}');"
                + "return (s.tasks||[]).filter(function(t){return t.title===" + JSONObject.quote(taskText)
                + " && t.id===" + capturedTaskId + ";}).length===1 && (s.qnotes||[]).filter(function(n){return n.txt===" + JSONObject.quote(noteText)
                + ";}).length===1;})()", "Activity recreation does not duplicate captures");
        }
        } finally {
            FlowNotifService.stop(context);
        }
    }

    private static Notification awaitNotification(Context context) throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
                if (item.getId() == FlowNotifService.NOTIF_ID) return item.getNotification();
            SystemClock.sleep(100);
        }
        fail("Foreground notification did not appear");
        return null;
    }

    private static void awaitNotificationAbsent(Context context) throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean present = false;
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
                present |= item.getId() == FlowNotifService.NOTIF_ID;
            if (!present) return;
            SystemClock.sleep(100);
        }
        fail("Previous main notification was not cleared before the capture fixture started");
    }

    private static void showTaskNotifications(Context context) {
        Intent intent = new Intent(context, FlowNotifService.class);
        intent.setAction(FlowNotifService.ACTION_SHOW_TASKS);
        if (android.os.Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    private static Notification awaitTaskNotification(NotificationManager manager, int id) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (hasActiveNotification(manager, id)) {
                for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
                    if (item.getId() == id) return item.getNotification();
            }
            SystemClock.sleep(100);
        }
        fail("Task notification did not appear: " + id);
        return null;
    }

    private static Notification awaitTaskNotification(NotificationManager manager, int id,
                                                       String expectedTitle) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        String lastTitle = "<none>";
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() != id) continue;
                CharSequence title = item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE);
                lastTitle = String.valueOf(title);
                if (expectedTitle.contentEquals(title)) return item.getNotification();
            }
            SystemClock.sleep(100);
        }
        fail("Task notification title was not updated to " + expectedTitle + "; current title=" + lastTitle);
        return null;
    }

    private static void awaitTaskNotificationTitle(NotificationManager manager, int id, String title) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() == id && title.contentEquals(item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE))) return;
            }
            SystemClock.sleep(100);
        }
        fail("Task notification title was not updated to: " + title);
    }

    private static void awaitTaskNotificationText(NotificationManager manager, int id, String text) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() != id) continue;
                CharSequence current = item.getNotification().extras.getCharSequence(Notification.EXTRA_TEXT);
                if (text.contentEquals(current)) return;
            }
            SystemClock.sleep(100);
        }
        fail("Task notification did not project counter value: " + text);
    }

    private static void awaitForeground(Context context, String contentContains, String contentExcludes,
                                        String lineContains) throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() != FlowNotifService.NOTIF_ID) continue;
                Notification notification = item.getNotification();
                CharSequence text = notification.extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence[] lines = notification.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                String expanded = lines == null ? "" : java.util.Arrays.toString(lines);
                if ((contentContains == null || (text != null && text.toString().contains(contentContains)))
                        && (contentExcludes == null || !expanded.contains(contentExcludes))
                        && (lineContains == null || expanded.contains(lineContains))) return;
            }
            SystemClock.sleep(100);
        }
        fail("Foreground notification did not reflect pending task commands");
    }

    private static boolean hasActiveNotification(NotificationManager manager, int id) {
        if (manager == null) return false;
        for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications())
            if (item.getId() == id) return true;
        return false;
    }

    private static boolean hasAction(Notification notification, String title, String actionName) {
        for (Notification.Action action : notification.actions)
            if (title.contentEquals(action.title) && action.actionIntent != null
                    && actionIntentAction(action).equals(actionName)) return true;
        return false;
    }

    private static boolean hasActionWithPrefix(Notification notification, String actionName) {
        for (Notification.Action action : notification.actions)
            if (actionIntentAction(action).equals(actionName)) return true;
        return false;
    }

    private static String actionIntentAction(Notification.Action action) {
        // Notification.Action exposes only the PendingIntent, whose encoded intent is not public;
        // distinguish action targets through the stable button labels in this fixture instead.
        if (action.title == null) return "";
        String title = action.title.toString();
        if (title.startsWith("+") && title.contains("мл")) return FlowNotifService.ACTION_TASK_INC;
        if (title.startsWith("−") && title.contains("мл")) return FlowNotifService.ACTION_TASK_DEC;
        if (title.contains("Виконано")) return FlowNotifService.ACTION_TASK_DONE;
        if (title.contains("Не зараз")) return FlowNotifService.ACTION_TASK_NEXT_ZONE;
        if (title.contains("Не сьогодні")) return FlowNotifService.ACTION_TASK_NOT_TODAY;
        return "";
    }

    private static Notification.Action findServiceAction(Notification notification, String actionName) {
        for (Notification.Action action : notification.actions)
            if (actionIntentAction(action).equals(actionName)) return action;
        fail("Missing task action " + actionName);
        return null;
    }

    private static long awaitTaskEvent(FlowBridge bridge, String event) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.getJSONObject(i);
                if (event.equals(row.optString("event"))) return row.getLong("id");
            }
            SystemClock.sleep(100);
        }
        fail("Task action was not durably queued: " + event);
        return -1;
    }

    private static JSONObject awaitTaskEventPrefix(FlowBridge bridge, String prefix) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.getJSONObject(i);
                if (row.optString("event", "").startsWith(prefix)) return row;
            }
            SystemClock.sleep(100);
        }
        fail("Task action was not durably queued with prefix: " + prefix);
        return null;
    }

    private static JSONObject awaitTaskDeferralEvent(FlowBridge bridge, String taskId, String kind) throws Exception {
        String prefix = "task_defer:" + taskId + ":";
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.getJSONObject(i);
                String event = row.optString("event", "");
                if (!event.startsWith(prefix)) continue;
                JSONObject deferral = new JSONObject(event.substring(prefix.length()));
                if (kind.equals(deferral.optString("kind"))) return row;
            }
            SystemClock.sleep(100);
        }
        fail("Task deferral was not durably queued: " + kind + " for " + taskId);
        return null;
    }

    private static void assertTimestampNear(String message, long first, long second) {
        assertTrue(message + " (difference=" + Math.abs(first - second) + "ms)",
                Math.abs(first - second) <= 60_000L);
    }

    private static boolean hasEvent(JSONArray events, String event) throws Exception {
        for (int i = 0; i < events.length(); i++)
            if (event.equals(events.getJSONObject(i).optString("event"))) return true;
        return false;
    }

    private static int countEvent(JSONArray events, String event) throws Exception {
        int count = 0;
        for (int i = 0; i < events.length(); i++)
            if (event.equals(events.getJSONObject(i).optString("event"))) count++;
        return count;
    }

    private static int countEventPrefix(JSONArray events, String prefix) throws Exception {
        int count = 0;
        for (int i = 0; i < events.length(); i++)
            if (events.getJSONObject(i).optString("event", "").startsWith(prefix)) count++;
        return count;
    }

    private static Notification.Action findAction(Notification notification, String title, String key) {
        for (Notification.Action action : notification.actions) {
            if (title.contentEquals(action.title) && action.getRemoteInputs() != null
                    && action.getRemoteInputs().length == 1
                    && key.equals(action.getRemoteInputs()[0].getResultKey())) return action;
        }
        fail("Missing action " + title + " with RemoteInput key " + key);
        return null;
    }

    private static void sendCapture(Context context, Notification.Action action, String key, String text) throws Exception {
        RemoteInput input = new RemoteInput.Builder(key).build();
        Intent results = new Intent();
        Bundle values = new Bundle();
        values.putCharSequence(key, text);
        RemoteInput.addResultsToIntent(new RemoteInput[] { input }, results, values);
        action.actionIntent.send(context, 0, results);
    }

    private static void awaitReceipt(Context context, String receipt) throws Exception {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        String lastText = "<none>";
        String lastLines = "<none>";
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() != FlowNotifService.NOTIF_ID) continue;
                Bundle extras = item.getNotification().extras;
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                lastText = String.valueOf(text);
                lastLines = lines == null ? "<none>" : java.util.Arrays.toString(lines);
                if (receipt.contentEquals(text) && lines != null && lines.length > 0 && receipt.contentEquals(lines[0])) return;
            }
            SystemClock.sleep(100);
        }
        fail("Notification did not show receipt: " + receipt + "; current text=" + lastText + "; lines=" + lastLines);
    }

    private static JSONArray awaitCommands(FlowBridge bridge, String task, String note) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray allRows = new JSONArray(bridge.getEventEnvelopes());
            JSONArray rows = new JSONArray();
            boolean hasTask = false, hasNote = false;
            for (int i = 0; i < allRows.length(); i++) {
                JSONObject row = allRows.getJSONObject(i);
                String event = row.getString("event");
                if (event.startsWith("capture_task_v2:")) {
                    String id = captureTaskId(event);
                    String body = event.substring(("capture_task_v2:" + id + ":").length());
                    if (task.equals(new JSONObject(body).optString("title", ""))) {
                        rows.put(row);
                        hasTask = true;
                    }
                } else if (("capture_note:" + note).equals(event)) {
                    rows.put(row);
                    hasNote = true;
                }
            }
            if (hasTask && hasNote) return rows;
            SystemClock.sleep(100);
        }
        fail("Both distinct commands did not become durable in Room");
        return null;
    }

    private static JSONObject awaitCapturedTaskEvent(FlowBridge bridge, String title) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray rows = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String event = row.optString("event", "");
                if (!event.startsWith("capture_task_v2:")) continue;
                String id = captureTaskId(event);
                JSONObject payload = new JSONObject(event.substring(("capture_task_v2:" + id + ":").length()));
                if (title.equals(payload.optString("title", ""))) return row;
            }
            SystemClock.sleep(100);
        }
        fail("Quick task capture command was not durably queued");
        return null;
    }

    private static String captureTaskId(String event) {
        String prefix = "capture_task_v2:";
        if (event == null || !event.startsWith(prefix)) throw new IllegalArgumentException("Not a v2 task capture");
        int separator = event.indexOf(':', prefix.length());
        if (separator <= prefix.length()) throw new IllegalArgumentException("Missing captured task ID");
        return event.substring(prefix.length(), separator);
    }

    private static void awaitCommand(FlowBridge bridge, String expected) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray rows = new JSONArray(bridge.getEventEnvelopes());
            if (hasEvent(rows, expected)) return;
            SystemClock.sleep(100);
        }
        fail("Command did not become durable in Room: " + expected);
    }

    private static Set<Long> eventIds(FlowBridge bridge) throws Exception {
        JSONArray rows = new JSONArray(bridge.getEventEnvelopes());
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) ids.add(rows.getJSONObject(i).getLong("id"));
        return ids;
    }

    private static void awaitAcknowledged(FlowBridge bridge, long taskId, long noteId) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            JSONArray rows = new JSONArray(bridge.getEventEnvelopes());
            boolean taskPending = false, notePending = false;
            for (int i = 0; i < rows.length(); i++) {
                long id = rows.getJSONObject(i).getLong("id");
                taskPending |= id == taskId;
                notePending |= id == noteId;
            }
            if (!taskPending && !notePending) return;
            SystemClock.sleep(100);
        }
        fail("Committed capture commands were not acknowledged from Room");
    }

    private static JSONObject findBy(JSONArray rows, String field, String text) throws Exception {
        JSONObject found = null;
        for (int i = 0; i < rows.length(); i++) if (text.equals(rows.getJSONObject(i).optString(field))) {
            assertNull("Capture should materialize exactly once", found);
            found = rows.getJSONObject(i);
        }
        assertNotNull("Missing saved capture: " + text, found);
        return found;
    }

    private static void awaitWeb(ActivityScenario<MainActivity> scenario, String expression, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (evaluate(scenario, "return !!(" + expression + ");")) return;
            SystemClock.sleep(100);
        }
        fail(message + "; page text: " + evaluateText(scenario));
    }

    private static boolean evaluate(ActivityScenario<MainActivity> scenario, String body) throws Exception {
        return value(scenario, body).optBoolean("value");
    }

    private static String evaluateText(ActivityScenario<MainActivity> scenario) throws Exception {
        return value(scenario, "return document.body ? document.body.innerText.slice(0,1200) : ''; ")
            .optString("value");
    }

    private static JSONObject value(ActivityScenario<MainActivity> scenario, String body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        String script = "(function(){try{return JSON.stringify({ok:true,value:(function(){" + body
            + "})()});}catch(e){return JSON.stringify({ok:false,error:String(e)});}})()";
        scenario.onActivity(activity -> activity.getBridge().getWebView().evaluateJavascript(script, raw -> {
            result.set(raw);
            done.countDown();
        }));
        assertTrue("WebView JavaScript callback timed out", done.await(10, TimeUnit.SECONDS));
        Object decoded = new org.json.JSONTokener(result.get() == null ? "null" : result.get()).nextValue();
        assertTrue("WebView returned no JSON result", decoded instanceof String);
        JSONObject response = new JSONObject((String) decoded);
        assertTrue("WebView JavaScript failed: " + response.optString("error"), response.optBoolean("ok"));
        return response;
    }
}
