package com.flow.adhd;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.flow.adhd.domain.FlowDefaults;
import java.util.Calendar;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Owned AVD only: exercise the real Android timezone broadcast without an Activity. */
@RunWith(AndroidJUnit4.class)
public class FlowClockTest {
    @Test public void timezoneBroadcastRefreshesWallZoneWithoutOpeningActivity() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("This integration test requires the isolated native build", BuildConfig.NATIVE_RUNTIME);
        assertTrue("Refusing to touch the owner's application data", context.getPackageName().endsWith(".native"));
        FlowBridge bridge = new FlowBridge(context);
        FlowDao dao = FlowDatabase.getInstance(context).flowDao();
        String previousState = bridge.load();
        String previousNotif = bridge.getNotifJson();
        String previousZone = shell("getprop persist.sys.timezone").trim();
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowNotifService.stop(context);
        notifications.cancel(FlowNotifService.NOTIF_ID);
        try {
            shell("cmd alarm set-timezone Etc/UTC");
            awaitZone("Etc/UTC");
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Etc/UTC"));
            int start = now.get(Calendar.HOUR_OF_DAY) * 60;
            JSONObject state = new JSONObject(FlowDefaults.INSTANCE.create().toString());
            state.put("zones", new JSONArray()
                .put(zone(1, "Clock UTC zone", start))
                .put(zone(2, "Clock shifted zone", (start + 120) % 1440)));
            writeState(dao, state.toString());
            FlowNotifService.start(context);
            awaitTitle(context, "Clock UTC zone");
            shell("cmd alarm set-timezone Etc/GMT-2");
            awaitZone("Etc/GMT-2");
            // No explicit service update: BootReceiver must handle the system broadcast.
            awaitTitle(context, "Clock shifted zone");
            JSONObject saved = new JSONObject(bridge.load());
            assertEquals("Timezone refresh must preserve the authored zone definitions", state.getJSONArray("zones").toString(),
                    saved.getJSONArray("zones").toString());
        } finally {
            shell("cmd alarm set-timezone " + (previousZone.isEmpty() ? "Etc/UTC" : previousZone));
            awaitZone(previousZone.isEmpty() ? "Etc/UTC" : previousZone);
            context.stopService(new Intent(context, FlowNotifService.class));
            notifications.cancel(FlowNotifService.NOTIF_ID);
            writeStateAndNotif(dao, previousState, previousNotif);
        }
    }

    private static JSONObject zone(int id, String name, int startMinute) throws Exception {
        JSONArray slots = new JSONArray().put(new JSONObject().put("s", time(startMinute)).put("e", time(startMinute + 60)));
        return new JSONObject().put("id", id).put("nm", name).put("color", "#4f8ef7")
                .put("desc", name).put("prio", 1).put("slots", slots);
    }

    private static String time(int minute) {
        minute = Math.floorMod(minute, 1440);
        return String.format(java.util.Locale.US, "%02d:%02d", minute / 60, minute % 60);
    }

    private static void writeState(FlowDao dao, String json) {
        FlowDatabase.callStorage(() -> { dao.writeState(json); return null; });
    }

    private static void writeStateAndNotif(FlowDao dao, String state, String notif) {
        FlowDatabase.callStorage(() -> {
            dao.writeState(state);
            dao.writeNotif(notif);
            return null;
        });
    }

    private static String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toString("UTF-8");
        }
    }
    private static void awaitZone(String expected) {
        long until = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < until) {
            if (expected.equals(TimeZone.getDefault().getID())) return;
            SystemClock.sleep(100);
        }
        assertEquals(expected, TimeZone.getDefault().getID());
    }
    private static void awaitTitle(Context context, String expected) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        long until = SystemClock.elapsedRealtime() + 15000;
        while (SystemClock.elapsedRealtime() < until) {
            for (android.service.notification.StatusBarNotification item : manager.getActiveNotifications()) {
                if (item.getId() == FlowNotifService.NOTIF_ID
                    && String.valueOf(item.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE)).contains(expected)) return;
            }
            SystemClock.sleep(100);
        }
        fail("System timezone broadcast did not refresh foreground title to " + expected);
    }
}
