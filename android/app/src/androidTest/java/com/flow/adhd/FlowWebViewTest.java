package com.flow.adhd;

import static org.junit.Assert.*;

import android.os.SystemClock;
import android.util.Log;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Exercises the packaged React application in the actual Android WebView and Room bridge.
 * No store injection, fake bridge, or direct database writes are used. Run on an isolated
 * test AVD: each test leaves uniquely named synthetic tasks for inspection.
 * DOM input/click events exercise React handlers; these are not physical touch tests.
 */
@RunWith(AndroidJUnit4.class)
public class FlowWebViewTest {
    private static final long UI_TIMEOUT_MS = 45000;

    @Test public void inboxCaptureNeedsAnExplicitDayBeforeAppearingToday() throws Exception {
        String title = "Android r2 Inbox " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            click(scenario, "#tabs button:nth-child(3)");
            await(scenario, "document.querySelector('#quick-task') !== null", "Inbox opens");
            input(scenario, "#quick-task", title);
            click(scenario, ".quick-capture button");
            JSONObject captured = awaitTask(scenario, title, null);
            long id = captured.getLong("id");
            assertFalse("Capture must not silently assign today", captured.has("planDate"));
            click(scenario, "#tabs button:nth-child(1)");
            await(scenario, "document.querySelector('#tasks-list') !== null", "Today opens");
            assertFalse(value(scenario, "return document.querySelector('#tasks-list').textContent.indexOf("
                + JSONObject.quote(title) + ") >= 0;").getBoolean("value"));
            click(scenario, "#tabs button:nth-child(3)");
            value(scenario, "var button=Array.prototype.find.call(document.querySelectorAll('#inbox-sec .tt'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";});"
                + "if(!button)throw new Error('Inbox task missing');button.click();return true;");
            await(scenario, "document.querySelector('#task-date') !== null", "Title opens editor");
            click(scenario, ".editor-actions button:nth-child(2)"); // Explicit Today
            String day = value(scenario, "return document.querySelector('#task-date').value;").getString("value");
            assertTrue(day.matches("\\d{4}-\\d{2}-\\d{2}"));
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Planning saves");
            JSONObject planned = awaitTask(scenario, title, null);
            assertEquals(id, planned.getLong("id"));
            assertEquals(day, planned.getString("planDate"));
            scenario.recreate();
            awaitReady(scenario);
            await(scenario, "document.querySelector('#tasks-list').textContent.indexOf(" + JSONObject.quote(title)
                + ") >= 0", "Planned capture appears Today after recreation");
            assertEquals(id, awaitTask(scenario, title, null).getLong("id"));
        }
    }

    @Test public void simpleTaskSurvivesActivityRecreationWithSameId() throws Exception {
        String title = "Android smoke simple " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            click(scenario, "#add-fab");
            await(scenario, "document.querySelector('#task-title') !== null", "Task editor opens");
            input(scenario, "#task-title", title);
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Simple task saves");
            JSONObject before = awaitTask(scenario, title, null);
            assertEquals("simple", before.getString("type"));
            long id = before.getLong("id");

            scenario.recreate();
            awaitReady(scenario);
            await(scenario, "Array.prototype.some.call(document.querySelectorAll('#tasks-list .tt'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";})",
                "Persisted simple task renders after activity recreation");
            JSONObject after = awaitTask(scenario, title, null);
            assertEquals("Recreation must retain the task identity", id, after.getLong("id"));
            assertEquals(title, after.getString("title"));
        }
    }

    @Test public void scheduledTaskUsesSelectedCalendarDayAndSharedEditor() throws Exception {
        String title = "Android smoke scheduled " + UUID.randomUUID();
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            click(scenario, "#tabs button:nth-child(2)");
            await(scenario, "document.querySelector('.plan-day.sel') !== null", "Calendar opens");
            String initialDay = value(scenario,
                "return document.querySelector('.plan-day.sel .pd-num').textContent;").getString("value");
            click(scenario, ".week-nav button:last-child");
            await(scenario, "document.querySelector('.plan-day.sel .pd-num').textContent !== "
                + JSONObject.quote(initialDay), "Next week retains a selected day");
            click(scenario, ".pdd-head .pdd-add");
            await(scenario, "document.querySelector('#task-date') !== null", "Calendar task editor opens");
            String date = value(scenario, "return document.querySelector('#task-date').value;").getString("value");
            assertTrue("Selected calendar date must be filled", date.matches("\\d{4}-\\d{2}-\\d{2}"));
            input(scenario, "#task-title", title);
            click(scenario, ".editor-kinds button:nth-child(2)");
            await(scenario, "document.querySelector('#task-time') !== null", "Scheduled time input opens");
            input(scenario, "#task-time", "10:30");
            assertEquals("Changing the type must keep the selected calendar day", date,
                value(scenario, "return document.querySelector('#task-date').value;").getString("value"));
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Scheduled task saves");
            JSONObject before = awaitTask(scenario, title, "10:30");
            long id = before.getLong("id");
            assertEquals("sched", before.getString("type"));
            assertEquals(date, before.getString("planDate"));
            assertEquals(date, before.getString("schedDate"));
            await(scenario, "Array.prototype.some.call(document.querySelectorAll('.plan-task-nm'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";})",
                "Saved task appears in the selected calendar day");
            value(scenario, "var button=Array.prototype.find.call(document.querySelectorAll('.plan-task-nm'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";});"
                + "if(!button)throw new Error('Calendar task missing');button.click();return true;");
            await(scenario, "document.querySelector('#task-time') !== null", "Calendar opens shared task editor");
            assertEquals(title, value(scenario, "return document.querySelector('#task-title').value;").getString("value"));
            assertEquals(date, value(scenario, "return document.querySelector('#task-date').value;").getString("value"));
            input(scenario, "#task-time", "11:30");
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Calendar edit saves");
            JSONObject after = awaitTask(scenario, title, "11:30");
            assertEquals("Editing from Plan must update the original task", id, after.getLong("id"));
            assertEquals(date, after.getString("planDate"));
            assertEquals(date, after.getString("schedDate"));
        }
    }

    private static void awaitReady(ActivityScenario<MainActivity> scenario) throws Exception {
        await(scenario, "document.querySelector('#add-fab') !== null && "
            + "typeof window.FlowBridge === 'object' && typeof window.FlowBridge.load === 'function'",
            "React and the real Android bridge initialize");
        Log.i("FlowWebViewTest", value(scenario, "return navigator.userAgent;").getString("value"));
    }

    private static JSONObject awaitTask(ActivityScenario<MainActivity> scenario, String title, String time) throws Exception {
        String matches = "JSON.parse(window.FlowBridge.load() || '{}').tasks || []";
        String filter = ".filter(function(t){return t.title===" + JSONObject.quote(title) + ";})";
        await(scenario, "(function(){var tasks=(" + matches + ")" + filter + ";return tasks.length===1"
            + (time == null ? "" : " && tasks[0].schedTime===" + JSONObject.quote(time)) + ";})()",
            "Exactly one matching task is durable in Room" + (time == null ? "" : " at " + time));
        JSONArray tasks = value(scenario, "return (" + matches + ")" + filter + ";").getJSONArray("value");
        assertEquals("Saving or editing must not duplicate the task", 1, tasks.length());
        return tasks.getJSONObject(0);
    }

    private static void input(ActivityScenario<MainActivity> scenario, String selector, String text) throws Exception {
        value(scenario, "var input=document.querySelector(" + JSONObject.quote(selector) + ");"
            + "if(!input)throw new Error('Input missing');"
            + "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(input,"
            + JSONObject.quote(text) + ");"
            + "input.dispatchEvent(new Event('input',{bubbles:true}));"
            + "input.dispatchEvent(new Event('change',{bubbles:true}));return true;");
    }

    private static void click(ActivityScenario<MainActivity> scenario, String selector) throws Exception {
        value(scenario, "var button=document.querySelector(" + JSONObject.quote(selector) + ");"
            + "if(!button)throw new Error('Button missing');button.click();return true;");
    }

    private static void await(ActivityScenario<MainActivity> scenario, String expression, String message) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (value(scenario, "return !!(" + expression + ");").optBoolean("value")) return;
            SystemClock.sleep(100);
        }
        fail(message + "; visible page: " + value(scenario,
            "return document.body ? document.body.innerText.slice(0,1600) : 'No document body';").optString("value"));
    }

    /** Waits on the instrumentation thread, never on the WebView/UI thread. */
    private static JSONObject value(ActivityScenario<MainActivity> scenario, String body) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> result = new AtomicReference<>();
        String script = "(function(){try{return JSON.stringify({ok:true,value:(function(){" + body
            + "})()});}catch(e){return JSON.stringify({ok:false,error:String(e),stack:e.stack});}})()";
        scenario.onActivity(activity -> activity.getBridge().getWebView().evaluateJavascript(script, raw -> {
            result.set(raw);
            done.countDown();
        }));
        assertTrue("WebView JavaScript callback timed out", done.await(10, TimeUnit.SECONDS));
        Object decoded = new JSONTokener(result.get() == null ? "null" : result.get()).nextValue();
        assertTrue("WebView returned no JSON result: " + result.get(), decoded instanceof String);
        JSONObject response = new JSONObject((String) decoded);
        assertTrue("WebView JavaScript failed: " + response.optString("error") + " " + response.optString("stack"),
            response.optBoolean("ok"));
        return response;
    }
}
