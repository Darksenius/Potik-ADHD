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

    @Test public void taskCounterActionsUseCurrentPayloadAndPruneRemovedTasks() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previousNotif = bridge.getNotifJson();
        String suffix = UUID.randomUUID().toString();
        String counterId = "counter-" + suffix;
        String removedId = "removed-" + suffix;
        String counterTitle = "Counter " + suffix;
        String ordinaryTitle = "Ordinary " + suffix;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String originalState = bridge.load();
        try {
            Calendar yesterday = Calendar.getInstance();
            yesterday.add(Calendar.DAY_OF_YEAR, -1);
            String yesterdayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(yesterday.getTime());
            String todayKey = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
            PendingEvent oldDone = new PendingEvent("task_done:" + counterId);
            oldDone.createdAt = yesterday.getTimeInMillis();
            FlowDatabase.getInstance(context).flowDao().insertEvent(oldDone);
            JSONObject counterTask = new JSONObject()
                .put("id", counterId).put("title", counterTitle).put("type", "simple")
                .put("counterEnabled", true).put("counterStep", 100).put("counterUnit", "ml")
                .put("counter", 99).put("counterTarget", 0).put("repeat", "daily")
                .put("repeatDays", new JSONArray().put(true).put(true).put(true).put(true).put(true).put(true).put(true))
                .put("anchorDate", "2000-01-01").put("counterDay", yesterdayKey);
            JSONObject ordinaryTask = new JSONObject()
                .put("id", removedId).put("title", ordinaryTitle).put("type", "simple")
                .put("counterEnabled", false);
            bridge.saveNotif(new JSONObject().put("body", "2 активних · 0 виконано").put("done", 0).put("total", 2)
                .put("tasks", counterTitle + "|" + ordinaryTitle).put("taskList", new JSONArray()
                .put(counterTask).put(ordinaryTask)).toString());
            FlowNotifService.start(context);
            showTaskNotifications(context);

            Notification counter = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE);
            assertEquals(counterTitle, counter.extras.getCharSequence(Notification.EXTRA_TITLE).toString().replaceFirst("^1\\.  ", ""));
            assertEquals("0 мл", counter.extras.getCharSequence(Notification.EXTRA_TEXT).toString());
            assertEquals("Android displays only three task actions", 3, counter.actions.length);
            assertTrue(hasAction(counter, "+100 мл", FlowNotifService.ACTION_TASK_INC));
            assertTrue(hasAction(counter, "−100 мл", FlowNotifService.ACTION_TASK_DEC));
            assertTrue(hasAction(counter, "✓ Виконати", FlowNotifService.ACTION_TASK_DONE));
            assertFalse("Counter notification preserves the three primary actions", hasActionWithPrefix(counter, FlowNotifService.ACTION_TASK_SKIP));
            Notification ordinary = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 1);
            assertFalse(hasActionWithPrefix(ordinary, FlowNotifService.ACTION_TASK_INC));
            assertFalse(hasActionWithPrefix(ordinary, FlowNotifService.ACTION_TASK_DEC));
            assertTrue(hasAction(ordinary, "→ Відкласти 15 хв", FlowNotifService.ACTION_TASK_SKIP));

            Notification.Action ordinaryDone = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_DONE);
            ordinaryDone.actionIntent.send(context, 0, null);
            awaitTaskEvent(bridge, "task_done:" + removedId);
            ordinaryDone.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            assertEquals("Pending Done cannot be queued twice", 1,
                    countEvent(new JSONArray(bridge.getEventEnvelopes()), "task_done:" + removedId));
            awaitForeground(context, "1 активних · 1 виконано", ordinaryTitle, null);

            Notification.Action increment = findServiceAction(counter, FlowNotifService.ACTION_TASK_INC);
            increment.actionIntent.send(context, 0, null);
            long incrementEventId = awaitTaskEvent(bridge, "task_inc:" + counterId);
            awaitTaskNotificationText(manager, FlowNotifService.NOTIF_TASK_BASE, "100 мл");
            awaitForeground(context, null, null, counterTitle + " · 100 мл");
            assertEquals("Notification projection does not write task state", originalState, bridge.load());

            String stateForCommit = originalState == null || originalState.isEmpty() ? "{}" : originalState;
            assertTrue(bridge.saveWithEvents(stateForCommit, new JSONArray().put(incrementEventId).toString()));
            counterTask.put("counter", 100).put("counterDay", todayKey);
            bridge.saveNotif(new JSONObject().put("taskList", new JSONArray().put(
                new JSONObject(counterTask.toString())).put(ordinaryTask)).toString());
            showTaskNotifications(context);
            awaitTaskNotificationText(manager, FlowNotifService.NOTIF_TASK_BASE, "100 мл");
            assertEquals("Acknowledged increment is not projected a second time", stateForCommit, bridge.load());
            counter = awaitTaskNotification(manager, FlowNotifService.NOTIF_TASK_BASE);

            Notification.Action decrement = findServiceAction(counter, FlowNotifService.ACTION_TASK_DEC);
            decrement.actionIntent.send(context, 0, null);
            long decrementEventId = awaitTaskEvent(bridge, "task_dec:" + counterId);
            awaitTaskNotificationText(manager, FlowNotifService.NOTIF_TASK_BASE, "0 мл");
            assertEquals("Notification projection does not write task state", stateForCommit, bridge.load());
            assertTrue(bridge.saveWithEvents(stateForCommit, new JSONArray().put(decrementEventId).toString()));

            bridge.saveNotif(new JSONObject().put("taskList", new JSONArray()).toString());
            showTaskNotifications(context);
            awaitTaskNotificationTitle(manager, FlowNotifService.NOTIF_TASK_BASE, "✓ Задач немає");
            assertFalse("Removed task notification should be pruned", hasActiveNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 1));
            assertFalse("Task summary should be pruned for an empty list", hasActiveNotification(manager, FlowNotifService.NOTIF_TASK_BASE + 100));

            Notification.Action staleDone = findServiceAction(counter, FlowNotifService.ACTION_TASK_DONE);
            Notification.Action staleSkip = findServiceAction(ordinary, FlowNotifService.ACTION_TASK_SKIP);
            JSONArray eventsBeforeStaleActions = new JSONArray(bridge.getEventEnvelopes());
            int priorCounterDoneEvents = countEvent(eventsBeforeStaleActions, "task_done:" + counterId);
            staleDone.actionIntent.send(context, 0, null);
            staleSkip.actionIntent.send(context, 0, null);
            SystemClock.sleep(500);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            assertEquals("Stale Done must not add another event", priorCounterDoneEvents,
                    countEvent(events, "task_done:" + counterId));
            assertFalse("Stale Skip must not queue an event", hasEvent(events, "task_skip:" + removedId));
            assertEquals("Duplicate Done remains deduplicated until acknowledgement", 1,
                    countEvent(events, "task_done:" + removedId));
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
                            || event.equals("task_skip:" + removedId)) {
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

    @Test public void separateShadeActionsQueueAndMaterializeThroughRoom() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String suffix = UUID.randomUUID().toString();
        String taskText = "Shade task " + suffix;
        String noteText = "Shade note " + suffix;
        NotificationManager notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowNotifService.stop(context);
        notificationManager.cancel(FlowNotifService.NOTIF_ID);
        awaitNotificationAbsent(context);
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
        awaitCommand(freshBridge, "capture_task:" + taskText);
        awaitReceipt(context, "Запис задачі збережено");
        sendCapture(context, findAction(awaitNotification(context), "+ Нотатка", FlowNotifService.KEY_NOTE), FlowNotifService.KEY_NOTE, noteText);
        awaitReceipt(context, "Нотатку збережено");

        FlowBridge queuedBridge = new FlowBridge(context);
        JSONArray queued = awaitCommands(queuedBridge, taskText, noteText);
        assertEquals(2, queued.length());
        long taskEventId = -1, noteEventId = -1;
        for (int i = 0; i < queued.length(); i++) {
            JSONObject row = queued.getJSONObject(i);
            if (row.getString("event").startsWith("capture_task:")) taskEventId = row.getLong("id");
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
            evaluate(scenario, "var b=document.querySelector('.more-menu button[aria-label=\\\"📝 Блокнот\\\"]');if(!b)throw new Error('Notes tab missing');b.click();return true;");
            awaitWeb(scenario, "document.querySelector('#qn-list') !== null", "Notes section opens");
            awaitWeb(scenario, "document.querySelector('#qn-list') && document.querySelector('#qn-list').textContent.indexOf("
                + JSONObject.quote(noteText) + ")>=0", "Note is accessible in Notes");
            scenario.recreate();
            awaitWeb(scenario, "(function(){var s=JSON.parse(window.FlowBridge.load()||'{}');"
                + "return (s.tasks||[]).filter(function(t){return t.title===" + JSONObject.quote(taskText)
                + ";}).length===1 && (s.qnotes||[]).filter(function(n){return n.txt===" + JSONObject.quote(noteText)
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
        if (title.contains("Виконати")) return FlowNotifService.ACTION_TASK_DONE;
        if (title.contains("Відкласти")) return FlowNotifService.ACTION_TASK_SKIP;
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
                if (("capture_task:" + task).equals(event) || ("capture_note:" + note).equals(event)) rows.put(row);
                hasTask |= ("capture_task:" + task).equals(event);
                hasNote |= ("capture_note:" + note).equals(event);
            }
            if (hasTask && hasNote) return rows;
            SystemClock.sleep(100);
        }
        fail("Both distinct commands did not become durable in Room");
        return null;
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
