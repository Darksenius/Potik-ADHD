package com.flow.adhd;

import static org.junit.Assert.*;
import android.content.Context;
import androidx.room.Room;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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
}
