package com.flow.adhd;

import static org.junit.Assert.*;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
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
        FlowBridge bridge = new FlowBridge(context);
        String previousNotif = bridge.getNotifJson();
        String previousZone = shell("getprop persist.sys.timezone").trim();
        try {
            shell("cmd alarm set-timezone Etc/UTC");
            awaitZone("Etc/UTC");
            Calendar now = Calendar.getInstance(TimeZone.getTimeZone("Etc/UTC"));
            int start = now.get(Calendar.HOUR_OF_DAY) * 60;
            JSONArray segments = new JSONArray();
            if (start > 0) segments.put(range(2, 0, start));
            segments.put(range(1, start, start + 60));
            if (start + 60 < 1440) segments.put(range(2, start + 60, 1440));
            JSONArray wallDays = new JSONArray();
            Calendar date = (Calendar) now.clone(); date.add(Calendar.DAY_OF_YEAR, -2);
            SimpleDateFormat key = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            key.setTimeZone(TimeZone.getTimeZone("Etc/UTC"));
            for (int i = 0; i < 5; i++) {
                wallDays.put(new JSONObject().put("date", key.format(date.getTime())).put("segments", segments));
                date.add(Calendar.DAY_OF_YEAR, 1);
            }
            String fixture = new JSONObject().put("questWallDays", wallDays)
                .put("questTasks", new JSONArray()).put("schedList", new JSONArray())
                .put("questZones", new JSONArray()
                    .put(new JSONObject().put("id", 1).put("nm", "Clock UTC zone"))
                    .put(new JSONObject().put("id", 2).put("nm", "Clock shifted zone"))).toString();
            bridge.saveNotif(fixture);
            FlowNotifService.start(context);
            awaitTitle(context, "Clock UTC zone");
            shell("cmd alarm set-timezone Etc/GMT-2");
            awaitZone("Etc/GMT-2");
            // No explicit service update: BootReceiver must handle the system broadcast.
            awaitTitle(context, "Clock shifted zone");
            assertEquals("Timezone projection must not rewrite the authored wall schedule", fixture, bridge.getNotifJson());
        } finally {
            shell("cmd alarm set-timezone " + (previousZone.isEmpty() ? "Etc/UTC" : previousZone));
            awaitZone(previousZone.isEmpty() ? "Etc/UTC" : previousZone);
            bridge.saveNotif(previousNotif);
            context.stopService(new Intent(context, FlowNotifService.class));
        }
    }

    private static JSONObject range(int zoneId, int start, int end) throws Exception {
        return new JSONObject().put("zoneId", zoneId).put("startMinute", start).put("endMinute", end);
    }
    private static String shell(String command) throws Exception {
        ParcelFileDescriptor descriptor = InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command);
        try (InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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
