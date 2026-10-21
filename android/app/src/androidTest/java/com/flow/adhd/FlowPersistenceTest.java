package com.flow.adhd;

import static org.junit.Assert.*;
import android.content.Context;
import android.content.ContextWrapper;
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
import java.util.ArrayList;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
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
        db = Room.inMemoryDatabaseBuilder(ctx, FlowDatabase.class).build();
        dao = db.flowDao();
    }
    @After public void close() { db.close(); }

    private <T> T storage(Callable<T> work) { return FlowDatabase.callStorage(work); }

    @Test public void stateAndNotificationUpdatesKeepEachOther() throws Exception {
        Thread state = new Thread(() -> storage(() -> {
            for (int i = 0; i < 30; i++) dao.writeState("state-" + i);
            return null;
        }));
        Thread notif = new Thread(() -> storage(() -> {
            for (int i = 0; i < 30; i++) dao.writeNotif("notif-" + i);
            return null;
        }));
        state.start(); notif.start(); state.join(); notif.join();
        assertEquals("state-29", storage(() -> dao.getStateJson()));
        assertEquals("notif-29", storage(() -> dao.getNotifJson()));
    }
    @Test public void acknowledgementRemovesOnlyCommandsInSavedSnapshot() {
        storage(() -> {
            dao.insertEvent(new PendingEvent("note:first"));
            dao.insertEvent(new PendingEvent("note:second"));
            List<PendingEvent> read = dao.getPendingEvents();
            dao.insertEvent(new PendingEvent("note:arrived-later"));
            dao.commitEvents("{\"notepad\":\"applied\"}", Arrays.asList(read.get(0).id, read.get(1).id));
            assertEquals("{\"notepad\":\"applied\"}", dao.getStateJson());
            assertEquals(1, dao.getPendingEvents().size());
            assertEquals("note:arrived-later", dao.getPendingEvents().get(0).event);
            return null;
        });
    }
    @Test public void rollbackRestoresBothStateAndPendingCommand() {
        storage(() -> {
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
            return null;
        });
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
        storage(() -> {
            dao.writeState("not valid json");
            dao.writeNotif("original-notification");
            dao.insertEvent(new PendingEvent("capture_note:queued"));
            return null;
        });

        File isolatedFiles = new File(temp, "files");
        assertTrue(isolatedFiles.mkdirs());
        Context isolatedContext = new ContextWrapper(ctx) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return isolatedFiles; }
        };
        FlowBridge bridge = new FlowBridge(isolatedContext, dao, publicDir, privateDir);
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
        assertTrue(bridge.archiveRecovery(supplied));
        assertEquals("Identical recovery must reuse its archive", archives.length(), new JSONArray(bridge.listRecoveryArchives()).length());
        storage(() -> {
            assertEquals("not valid json", dao.getStateJson());
            assertEquals("original-notification", dao.getNotifJson());
            assertEquals("capture_note:queued", dao.getPendingEvents().get(0).event);
            return null;
        });

        storage(() -> {
            dao.writeState(supplied);
            assertEquals(supplied, dao.getStateJson());
            assertEquals("capture_note:queued", dao.getPendingEvents().get(0).event);
            return null;
        });
    }

    @Test public void rawRoomAccessFromMainThreadIsRejected() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                dao.getStateJson();
                fail("Raw Room access on the main thread must be rejected");
            } catch (IllegalStateException expected) { }
        });
    }

    @Test public void storageLaneIsFifoAndSupportsReentrantSynchronousBridgeWork() {
        List<Integer> order = new ArrayList<>();
        AtomicReference<String> nestedResult = new AtomicReference<>("");
        FlowDatabase.executeStorage(() -> {
            order.add(1);
            nestedResult.set(FlowDatabase.callStorage(() -> {
                order.add(2);
                return "nested";
            }));
        });
        FlowDatabase.executeStorage(() -> order.add(3));
        assertEquals("barrier", storage(() -> "barrier"));
        assertEquals(Arrays.asList(1, 2, 3), order);
        assertEquals("nested", nestedResult.get());
    }

    @Test public void snapshotParserPrefersAssignedWallOccurrencesAndKeepsAbsoluteFallback() throws Exception {
        TimeZone utc = TimeZone.getTimeZone("UTC");
        long start = QuestPolicy.localDateTime("2026-10-14", "09:00", utc);
        long end = QuestPolicy.localDateTime("2026-10-14", "12:00", utc);
        JSONObject absolute = new JSONObject().put("zoneId", 1).put("startAt", start)
                .put("endAt", end).put("key", "2026-10-14:1:08:00");
        JSONObject assigned = new JSONObject().put("zoneId", 1).put("startMinute", 9 * 60)
                .put("endMinute", 12 * 60);
        JSONObject day = new JSONObject().put("date", "2026-10-14")
                .put("assignedOccurrences", new JSONArray().put(assigned));
        JSONObject snapshot = new JSONObject().put("questWallDays", new JSONArray().put(day))
                .put("questZoneOccurrences", new JSONArray().put(absolute));
        QuestPolicy.OccurrenceIndex rebuilt = QuestPolicy.OccurrenceIndex.fromSnapshot(snapshot, utc);
        assertEquals("2026-10-14:1:09:00", rebuilt.keyAt(1, start + 60 * 60_000L, utc));

        JSONObject oldSnapshot = new JSONObject().put("questZoneOccurrences", new JSONArray().put(absolute));
        QuestPolicy.OccurrenceIndex legacy = QuestPolicy.OccurrenceIndex.fromSnapshot(oldSnapshot, utc);
        assertEquals("2026-10-14:1:08:00", legacy.keyAt(1, start + 60 * 60_000L, utc));
    }

    private static void writeBytes(File file, byte[] bytes) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) { stream.write(bytes); }
    }
}
