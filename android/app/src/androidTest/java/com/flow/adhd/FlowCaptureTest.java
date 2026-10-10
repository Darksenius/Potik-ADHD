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
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real notification actions persist to Room before React is launched, then materialize once. */
@RunWith(AndroidJUnit4.class)
public class FlowCaptureTest {
    private static final long TIMEOUT_MS = 30000;

    @Test public void separateShadeActionsQueueAndMaterializeThroughRoom() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String suffix = UUID.randomUUID().toString();
        String taskText = "Shade task " + suffix;
        String noteText = "Shade note " + suffix;
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
        sendCapture(context, noteAction, FlowNotifService.KEY_NOTE, " \t ");
        awaitReceipt(context, "Введи текст нотатки");
        assertEquals("Whitespace note must not add any Room row", beforeWhitespace, eventIds(freshBridge));
        sendCapture(context, taskAction, FlowNotifService.KEY_TASK, taskText);
        awaitReceipt(context, "Запис задачі збережено");
        sendCapture(context, noteAction, FlowNotifService.KEY_NOTE, noteText);
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
        while (SystemClock.elapsedRealtime() < deadline) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() != FlowNotifService.NOTIF_ID) continue;
                Bundle extras = item.getNotification().extras;
                CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (receipt.contentEquals(text) && lines != null && lines.length > 0 && receipt.contentEquals(lines[0])) return;
            }
            SystemClock.sleep(100);
        }
        fail("Notification did not show receipt: " + receipt);
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
