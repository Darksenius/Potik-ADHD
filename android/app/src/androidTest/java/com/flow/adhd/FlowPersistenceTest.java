package com.flow.adhd;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.util.Base64;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class FlowPersistenceTest {
    private FlowDatabase db;
    private FlowDao dao;
    @Before public void setUp() {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        db = Room.inMemoryDatabaseBuilder(ctx, FlowDatabase.class).allowMainThreadQueries().build();
        dao = db.flowDao();
    }
    @After public void close() { db.close(); }

    @Test public void stateAndNotificationUpdatesKeepEachOther() throws Exception {
        Thread state = new Thread(() -> { for (int i = 0; i < 30; i++) dao.writeState("state-" + i); });
        Thread notif = new Thread(() -> { for (int i = 0; i < 30; i++) dao.writeNotif("notif-" + i); });
        state.start(); notif.start(); state.join(); notif.join();
        assertEquals("state-29", dao.getStateJson());
        assertEquals("notif-29", dao.getNotifJson());
    }
    @Test public void acknowledgementRemovesOnlyCommandsInSavedSnapshot() {
        dao.insertEvent(new PendingEvent("note:first"));
        dao.insertEvent(new PendingEvent("note:second"));
        List<PendingEvent> read = dao.getPendingEvents();
        dao.insertEvent(new PendingEvent("note:arrived-later"));
        dao.commitEvents("{\"notepad\":\"applied\"}", Arrays.asList(read.get(0).id, read.get(1).id));
        assertEquals("{\"notepad\":\"applied\"}", dao.getStateJson());
        assertEquals(1, dao.getPendingEvents().size());
        assertEquals("note:arrived-later", dao.getPendingEvents().get(0).event);
    }
    @Test public void rollbackRestoresBothStateAndPendingCommand() {
        dao.writeState("before");
        dao.insertEvent(new PendingEvent("note:keep"));
        long id = dao.getPendingEvents().get(0).id;
        try {
            db.runInTransaction(() -> {
                dao.commitEvents("after", Collections.singletonList(id));
                throw new IllegalStateException("Simulated transaction failure");
            });
            fail("Expected simulated failure");
        } catch (IllegalStateException expected) { }
        assertEquals("before", dao.getStateJson());
        assertEquals(id, dao.getPendingEvents().get(0).id);
    }

    @Test public void recoveryArchivePreservesInvalidSnapshotBackupsAndQueuedEvents() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File temp = new File(ctx.getCacheDir(), "recovery-test-" + System.currentTimeMillis());
        File publicDir = new File(temp, "public");
        File privateDir = new File(temp, "private");
        assertTrue(publicDir.mkdirs());
        assertTrue(privateDir.mkdirs());
        byte[] publicBytes = new byte[] {'{', 'b', 'a', 'd', '\n', 0, (byte) 0xff};
        byte[] privateBytes = new byte[] {'p', 'r', 'i', 'v', 'a', 't', 'e', 0, '\n'};
        writeBytes(new File(publicDir, "flow_backup.json"), publicBytes);
        writeBytes(new File(privateDir, "flow_backup.json"), privateBytes);
        dao.writeState("not valid json");
        dao.writeNotif("original-notification");
        dao.insertEvent(new PendingEvent("capture_note:queued"));

        FlowBridge bridge = new FlowBridge(ctx, dao, publicDir, privateDir);
        String supplied = "{\"recovered\":true}";
        JSONArray beforeArchives = new JSONArray(bridge.listRecoveryArchives());
        assertTrue(bridge.archiveRecovery(supplied));
        String archived = bridge.readRecoveryArchive();
        assertFalse(archived.isEmpty());
        JSONArray archives = new JSONArray(bridge.listRecoveryArchives());
        JSONObject listed = null;
        for (int i = 0; i < archives.length(); i++) {
            JSONObject candidate = archives.getJSONObject(i);
            boolean existedBefore = false;
            for (int j = 0; j < beforeArchives.length(); j++) {
                if (beforeArchives.getJSONObject(j).getString("name").equals(candidate.getString("name"))) existedBefore = true;
            }
            if (!existedBefore) { listed = candidate; break; }
        }
        assertNotNull("New archive must appear in metadata listing", listed);
        assertTrue(listed.getLong("createdAt") > 0);
        assertTrue(listed.getLong("size") > 0);
        String listedArchive = bridge.readRecoveryArchiveFile(listed.getString("name"));
        assertFalse(listedArchive.isEmpty());
        assertEquals(supplied, new JSONObject(listedArchive).getString("suppliedJson"));
        assertEquals("", bridge.readRecoveryArchiveFile("../" + listed.getString("name")));
        assertEquals("", bridge.readRecoveryArchiveFile("/etc/passwd"));
        org.json.JSONObject root = new org.json.JSONObject(archived);
        assertEquals(supplied, root.getString("suppliedJson"));
        assertEquals("not valid json", root.getString("originalState"));
        assertEquals("original-notification", root.getString("originalNotif"));
        org.json.JSONObject backups = root.getJSONObject("backups");
        assertArrayEquals(publicBytes, Base64.decode(backups.getJSONObject("public").getString("bytesBase64"), Base64.DEFAULT));
        assertArrayEquals(privateBytes, Base64.decode(backups.getJSONObject("private").getString("bytesBase64"), Base64.DEFAULT));
        assertEquals("not valid json", dao.getStateJson());
        assertEquals("original-notification", dao.getNotifJson());
        assertEquals("capture_note:queued", dao.getPendingEvents().get(0).event);

        assertTrue(bridge.saveWithEvents(supplied, "[]"));
        assertEquals("", bridge.getLastSaveError());
        assertEquals(supplied, dao.getStateJson());
        assertEquals("capture_note:queued", dao.getPendingEvents().get(0).event);
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
    }
}
