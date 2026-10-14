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
 * Fixtures and state checks use the real FlowBridge API; no fake bridge or direct
 * DAO/database writes are used. Run on an isolated test AVD: capture tests leave uniquely
 * named synthetic tasks for inspection, while recovery fixtures restore prior state in finally.
 * DOM input/click events exercise React handlers; these are not physical touch tests.
 */
@RunWith(AndroidJUnit4.class)
public class FlowWebViewTest {
    private static final long UI_TIMEOUT_MS = 45000;

    @Test public void personalReleaseNotesOpenReturnAndSurviveActivityRecreation() throws Exception {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            click(scenario, "#nav button:last-child");
            await(scenario, "document.querySelector('.settings-whats-new') !== null", "What's New is reachable in Settings");
            click(scenario, ".settings-whats-new");
            await(scenario, "document.querySelector('#whats-new-page') !== null", "Personal release page opens");
            assertEquals("1.7.6.r9", value(scenario, "return document.querySelector('.release-note').getAttribute('data-version');").getString("value"));
            assertTrue(value(scenario, "return document.querySelector('.release-note').open;").getBoolean("value"));
            assertEquals(3, value(scenario, "return document.querySelector('.release-note').querySelectorAll('.release-check').length;").getInt("value"));
            assertTrue(value(scenario, "return document.querySelector('.release-personal').textContent.indexOf('Пишу це для тебе') >= 0;").getBoolean("value"));
            click(scenario, ".release-note:nth-of-type(2) > summary");
            assertTrue(value(scenario, "return document.querySelectorAll('.release-note')[1].open;").getBoolean("value"));
            click(scenario, ".whats-new-header button");
            await(scenario, "document.querySelector('#settings-page') !== null", "Return goes to Settings");
            scenario.recreate();
            awaitReady(scenario);
            click(scenario, "#nav button:last-child");
            await(scenario, "document.querySelector('.settings-whats-new') !== null", "Settings after recreation");
            click(scenario, ".settings-whats-new");
            await(scenario, "document.querySelector('#whats-new-page') !== null", "Packaged release text remains available");
            assertEquals("1.7.6.r9", value(scenario, "return document.querySelector('.release-note').getAttribute('data-version');").getString("value"));
        }
    }

    @Test public void undatedInboxCaptureAppearsInNowAndRemainsInInboxUntilPlanned() throws Exception {
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
            await(scenario, "Array.prototype.some.call(document.querySelectorAll('#tasks-list .tc .tt'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";})",
                "Undated simple capture is immediately available in Now");
            click(scenario, "#tabs button:nth-child(3)");
            await(scenario, "Array.prototype.some.call(document.querySelectorAll('#inbox-sec .tt'),"
                + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";})",
                "Unplanned capture remains in Inbox for planning");
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
            click(scenario, "#tabs button:nth-child(1)");
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

    @Test public void recoveryArchivesUnreadableRoomAndAllowsTaskCapture() throws Exception {
        String title = "Android recovery " + UUID.randomUUID();
        String note = "Під'їзд 4\nЗалишити біля дверей 📦";
        android.content.Context context = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().getTargetContext();
        FlowBridge nativeBridge = new FlowBridge(context);
        String originalRoom = nativeBridge.load();
        String originalBackup = nativeBridge.readBackup();
        JSONObject obsoleteTask = new JSONObject().put("id", 900000001)
            .put("title", "Old unsupported habit").put("type", "habit");
        String obsoleteState = new JSONObject().put("tasks", new JSONArray().put(obsoleteTask)).toString();
        // Seed before launching any WebView: a running page's autosave must not race
        // the external-state fixture. All writes still go through the real FlowBridge.
        nativeBridge.save(obsoleteState);
        assertTrue(nativeBridge.writeBackup(obsoleteState));
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            await(scenario, "window.FlowBridge.readRecoveryArchive() !== '' && "
                + "JSON.parse(window.FlowBridge.readRecoveryArchive()).originalState === "
                + JSONObject.quote(obsoleteState), "Startup automatically archives incompatible Room data");
            assertFalse(value(scenario,
                "return document.querySelector('.recovery-banner') !== null;").getBoolean("value"));
            click(scenario, "#add-fab");
            await(scenario, "document.querySelector('#task-title') !== null", "Task editor opens");
            input(scenario, "#task-title", title);
            click(scenario, "#task-note-section summary");
            textArea(scenario, "#task-note", note);
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Task saves without a recovery decision");
            long id = awaitTaskNote(scenario, title, note).getLong("id");
            click(scenario, "#nav button:last-child");
            await(scenario, "document.querySelector('#incompatible-archives-title') !== null",
                "Settings exposes the archive section");
            assertTrue(value(scenario, "return document.querySelector('[aria-labelledby=\"incompatible-archives-title\"]').innerText.indexOf('Експортувати архів') >= 0;").getBoolean("value"));
            scenario.recreate();
            awaitReady(scenario);
            assertEquals("Automatic archive must allow durable task notes", id,
                awaitTaskNote(scenario, title, note).getLong("id"));
            JSONObject archive = value(scenario,
                "return JSON.parse(window.FlowBridge.readRecoveryArchive());").getJSONObject("value");
            assertEquals(obsoleteState, archive.getString("originalState"));
            click(scenario, "#nav button:last-child");
            await(scenario, "document.querySelector('#incompatible-archives-title') !== null", "Archive section reopens");
            String archiveName = new JSONArray(nativeBridge.listRecoveryArchives()).getJSONObject(0).getString("name");
            String exactArchive = nativeBridge.readRecoveryArchiveFile(archiveName);
            click(scenario, "[aria-labelledby=\"incompatible-archives-title\"] button");
            java.io.File exported = new java.io.File(context.getCacheDir(), "exports/" + archiveName);
            assertTrue("Settings export creates the selected archive file", exported.isFile());
            assertEquals("Export must preserve the exact archive", exactArchive,
                new String(new android.util.AtomicFile(exported).readFully(), java.nio.charset.StandardCharsets.UTF_8));
        } finally {
            nativeBridge.save(originalRoom);
            if (!originalBackup.isEmpty()) nativeBridge.writeBackup(originalBackup);
        }
    }

    @Test public void taskNoteSurvivesRoomRecreationTypeChangeAndInlineEdit() throws Exception {
        String title = "Android note " + UUID.randomUUID();
        String originalNote = "Під'їзд: 4\nЗателефонувати — після 18:00";
        String editedNote = "Код: 57\nЗалишити біля дверей 📦";
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            awaitReady(scenario);
            click(scenario, "#add-fab");
            await(scenario, "document.querySelector('#task-title') !== null", "Task editor opens");
            input(scenario, "#task-title", title);
            click(scenario, "#edit-body details:not(#task-note-section) summary");
            selectValue(scenario, "#task-type", "note");
            await(scenario, "document.querySelector('#task-note') !== null", "Task note field opens");
            textArea(scenario, "#task-note", originalNote);
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Note task saves");
            awaitTaskNote(scenario, title, originalNote);
            JSONObject created = awaitTask(scenario, title, null);
            assertEquals("note", created.getString("type"));
            long id = created.getLong("id");

            scenario.recreate();
            awaitReady(scenario);
            JSONObject afterRecreate = awaitTaskNote(scenario, title, originalNote);
            assertEquals("Recreation must retain the task identity", id, afterRecreate.getLong("id"));
            assertEquals("note", afterRecreate.getString("type"));

            clickTaskTitle(scenario, title);
            await(scenario, "document.querySelector('#task-note') !== null", "Existing task note opens in editor");
            assertEquals(originalNote, value(scenario, "return document.querySelector('#task-note').value;").getString("value"));
            click(scenario, ".editor-kinds button:first-child"); // Change legacy note task to primary simple type.
            await(scenario, "document.querySelector('#task-note').value === " + JSONObject.quote(originalNote),
                "Type change preserves the common note");
            click(scenario, "#edit-save-fab");
            await(scenario, "document.querySelector('#edit-page') === null", "Type change saves");
            JSONObject changedType = awaitTaskNote(scenario, title, originalNote);
            assertEquals(id, changedType.getLong("id"));
            assertEquals("simple", changedType.getString("type"));

            scenario.recreate();
            awaitReady(scenario);
            JSONObject afterTypeRecreate = awaitTaskNote(scenario, title, originalNote);
            assertEquals(id, afterTypeRecreate.getLong("id"));
            assertEquals("simple", afterTypeRecreate.getString("type"));

            String selector = "#task-note-" + id;
            expandTaskCard(scenario, title);
            await(scenario, "document.querySelector(" + JSONObject.quote(selector) + ") !== null",
                "Expanded simple task exposes inline note");
            textArea(scenario, selector, editedNote);
            JSONObject inlineEdit = awaitTaskNote(scenario, title, editedNote);
            assertEquals(id, inlineEdit.getLong("id"));

            scenario.recreate();
            awaitReady(scenario);
            JSONObject afterInlineRecreate = awaitTaskNote(scenario, title, editedNote);
            assertEquals(id, afterInlineRecreate.getLong("id"));
            assertEquals("simple", afterInlineRecreate.getString("type"));
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

    private static JSONObject awaitTaskNote(ActivityScenario<MainActivity> scenario, String title, String note) throws Exception {
        String matches = "JSON.parse(window.FlowBridge.load() || '{}').tasks || []";
        String filter = ".filter(function(t){return t.title===" + JSONObject.quote(title) + ";})";
        await(scenario, "(function(){var tasks=(" + matches + ")" + filter + ";return tasks.length===1"
            + " && tasks[0].note===" + JSONObject.quote(note) + ";})()",
            "Exactly one matching task with the expected durable Room note");
        JSONObject task = awaitTask(scenario, title, null);
        assertEquals("Room must retain the exact note text", note, task.getString("note"));
        return task;
    }

    private static void clickTaskTitle(ActivityScenario<MainActivity> scenario, String title) throws Exception {
        value(scenario, "var button=Array.prototype.find.call(document.querySelectorAll('.tc .tt'),"
            + "function(e){return e.textContent.trim()===" + JSONObject.quote(title) + ";});"
            + "if(!button)throw new Error('Task title missing');button.click();return true;");
        await(scenario, "document.querySelector('#edit-page') !== null", "Task editor opens from task title");
    }

    private static void expandTaskCard(ActivityScenario<MainActivity> scenario, String title) throws Exception {
        value(scenario, "var card=Array.prototype.find.call(document.querySelectorAll('.tc'),"
            + "function(e){var title=e.querySelector('.tt');return title && title.textContent.trim()==="
            + JSONObject.quote(title) + ";});"
            + "if(!card)throw new Error('Task card missing');"
            + "var button=card.querySelector('button[aria-label^=\"Розгорнути:\"]');"
            + "if(!button)throw new Error('Task card has no expand button');button.click();return true;");
    }

    private static void selectValue(ActivityScenario<MainActivity> scenario, String selector, String selected) throws Exception {
        value(scenario, "var input=document.querySelector(" + JSONObject.quote(selector) + ");"
            + "if(!input)throw new Error('Select missing');"
            + "Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(input,"
            + JSONObject.quote(selected) + ");"
            + "input.dispatchEvent(new Event('input',{bubbles:true}));"
            + "input.dispatchEvent(new Event('change',{bubbles:true}));return true;");
    }

    private static void textArea(ActivityScenario<MainActivity> scenario, String selector, String text) throws Exception {
        value(scenario, "var input=document.querySelector(" + JSONObject.quote(selector) + ");"
            + "if(!input)throw new Error('Textarea missing');"
            + "Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value').set.call(input,"
            + JSONObject.quote(text) + ");"
            + "input.dispatchEvent(new Event('input',{bubbles:true}));"
            + "input.dispatchEvent(new Event('change',{bubbles:true}));return true;");
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

    private static void clickButtonText(ActivityScenario<MainActivity> scenario, String text) throws Exception {
        value(scenario, "var button=Array.prototype.find.call(document.querySelectorAll('button'),"
            + "function(e){return e.textContent.trim()===" + JSONObject.quote(text) + ";});"
            + "if(!button)throw new Error('Button text missing: ' + " + JSONObject.quote(text) + ");"
            + "button.click();return true;");
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
