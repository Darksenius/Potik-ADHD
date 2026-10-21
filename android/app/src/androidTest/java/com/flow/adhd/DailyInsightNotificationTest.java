package com.flow.adhd;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.text.SimpleDateFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import androidx.core.app.NotificationManagerCompat;

/** Verifies daily insight delivery against persisted Room state and native Activity routing. */
@RunWith(AndroidJUnit4.class)
public class DailyInsightNotificationTest {
    private static final String DELIVERY_PREFS = "flow_daily_insight_delivery";
    private static final String DELIVERED_DAYS = "delivered_days";
    private static final long WAIT_MS = 8_000L;

    @Test public void earnedRoomClaimPostsOnceAndTapTargetsNativeArchiveWithoutCredit() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assumeTrue("This integration test requires the isolated native build", BuildConfig.NATIVE_RUNTIME);
        assertTrue("Refusing to touch the owner's application data", context.getPackageName().endsWith(".native"));
        FlowBridge bridge = new FlowBridge(context);
        FlowDao dao = FlowDatabase.getInstance(context).flowDao();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String oldState = bridge.load();
        String oldProjection = bridge.getNotifJson();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        android.content.SharedPreferences prefs = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE);
        boolean hadDeliveredDays = prefs.contains(DELIVERED_DAYS);
        Set<String> oldDeliveredDays = prefs.getStringSet(DELIVERED_DAYS, null) == null
                ? null : new HashSet<>(prefs.getStringSet(DELIVERED_DAYS, null));
        FlowNotifService.stop(context);
        manager.cancel(FlowNotifService.NOTIF_ID);
        manager.cancel(FlowNotifService.DAILY_INSIGHT_NOTIF_ID);
        Set<String> freshDeliverySet = new HashSet<>(oldDeliveredDays == null ? java.util.Collections.<String>emptySet() : oldDeliveredDays);
        freshDeliverySet.remove(today);
        prefs.edit().putStringSet(DELIVERED_DAYS, freshDeliverySet).commit();

        JSONObject state = oldState == null || oldState.isEmpty() ? new JSONObject() : new JSONObject(oldState);
        JSONArray progressDays = new JSONArray().put(today);
        JSONArray emptyClaims = new JSONArray();
        state.put("progressDays", progressDays).put("dailyInsightClaims", emptyClaims);
        JSONObject projection = oldProjection == null || oldProjection.isEmpty()
                ? new JSONObject() : new JSONObject(oldProjection);
        JSONObject insight = new JSONObject().put("day", today).put("cardId", 7).put("sequence", 1)
                .put("title", "Зупинись на хвилину").put("text", "Один короткий крок теж має значення.");
        projection.put("dailyInsight", insight);

        try {
            assumeTrue("Notifications must be enabled for this delivery test",
                    NotificationManagerCompat.from(context).areNotificationsEnabled());
            writeFixture(dao, state.toString(), projection.toString());
            refresh(context, bridge);
            NotificationChannelState channel = waitForChannel(manager);
            assumeTrue("The daily insight channel must be enabled", channel.enabled);
            assertNull("A progress day without a claim must not earn a card", findDaily(manager));

            JSONArray archivedClaim = new JSONArray().put(new JSONObject().put("day", today).put("cardId", 7)
                    .put("sequence", 1).put("earnedAt", 0));
            state.put("dailyInsightClaims", archivedClaim);
            writeFixture(dao, state.toString(), projection.toString());
            refresh(context, bridge);
            assertNull("An archived claim with earnedAt=0 must not notify", findDaily(manager));

            JSONArray earnedClaim = new JSONArray().put(new JSONObject().put("day", today).put("cardId", 7)
                    .put("sequence", 1).put("earnedAt", System.currentTimeMillis()));
            state.put("dailyInsightClaims", earnedClaim);
            writeFixture(dao, state.toString(), projection.toString());
            projection.put("dailyInsight", new JSONObject(insight.toString()).put("title", repeat('Т', 161)));
            writeFixture(dao, state.toString(), projection.toString());
            refresh(context, bridge);
            assertNull("Oversized titles must be rejected", findDaily(manager));

            projection.put("dailyInsight", new JSONObject(insight.toString()).put("text", repeat('Д', 2001)));
            writeFixture(dao, state.toString(), projection.toString());
            refresh(context, bridge);
            assertNull("Oversized text must be rejected", findDaily(manager));

            projection.put("dailyInsight", insight);
            writeFixture(dao, state.toString(), projection.toString());
            refresh(context, bridge);
            StatusBarNotification posted = awaitDaily(manager, true);
            assertNotNull(posted);
            assertEquals(FlowNotifService.DAILY_INSIGHT_NOTIF_ID, posted.getId());
            assertEquals(FlowNotifService.CHANNEL_DAILY_INSIGHT, posted.getNotification().getChannelId());
            assertEquals("День 1 · Зупинись на хвилину", posted.getNotification().extras.getCharSequence(Notification.EXTRA_TITLE).toString());
            assertEquals("Один короткий крок теж має значення.", posted.getNotification().extras
                    .getCharSequence(Notification.EXTRA_BIG_TEXT).toString());
            assertNotNull("The card must open its archive day", posted.getNotification().contentIntent);
            assertEquals("flow://daily-insight/" + today,
                    FlowNotifService.createDailyInsightOpenIntent(context, today).getDataString());

            manager.cancel(FlowNotifService.DAILY_INSIGHT_NOTIF_ID);
            refresh(context, bridge);
            assertNull("A delivered local date must not repost after another projection refresh", awaitDaily(manager, false));

            // Verify the notification targets the production native Activity and preserves its archive day.
            android.content.Intent tapIntent = FlowNotifService.createDailyInsightOpenIntent(context, today);
            assertEquals(NativeFlowActivity.class.getName(), tapIntent.getComponent().getClassName());
            assertEquals(today, tapIntent.getStringExtra("openInsightDay"));
            assertEquals("flow://daily-insight/" + today, tapIntent.getDataString());
            JSONObject afterTap = new JSONObject(bridge.load());
            assertEquals(progressDays.toString(), afterTap.getJSONArray("progressDays").toString());
            assertEquals(earnedClaim.toString(), afterTap.getJSONArray("dailyInsightClaims").toString());
        } finally {
            FlowNotifService.stop(context);
            manager.cancel(FlowNotifService.NOTIF_ID);
            manager.cancel(FlowNotifService.DAILY_INSIGHT_NOTIF_ID);
            writeFixture(dao, oldState == null ? "" : oldState,
                    oldProjection == null || oldProjection.isEmpty() ? "{}" : oldProjection);
            if (hadDeliveredDays) prefs.edit().putStringSet(DELIVERED_DAYS,
                    oldDeliveredDays == null ? java.util.Collections.<String>emptySet() : oldDeliveredDays).commit();
            else prefs.edit().remove(DELIVERED_DAYS).commit();
        }
    }

    private static void refresh(Context context, FlowBridge bridge) {
        // Refresh only the service projection. A plain service start also wakes
        // NativeRuntime, whose cached repository snapshot can rewrite this
        // test's Room fixture and repost a different daily insight.
        android.content.Intent intent = new android.content.Intent(context, FlowNotifService.class)
                .setAction(NativeRuntime.ACTION_PROJECTION_UPDATED);
        android.app.Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(() -> {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                context.startForegroundService(intent);
            else
                context.startService(intent);
        });
        // Service startup and onStartCommand are delivered on the main queue.
        // Drain that queue before the FIFO storage barrier, otherwise an older
        // refresh can run after the fixture is changed for the next assertion.
        instrumentation.waitForIdleSync();
        bridge.getNotifJson();
    }

    private static StatusBarNotification findDaily(NotificationManager manager) {
        for (StatusBarNotification item : manager.getActiveNotifications()) {
            if (item.getId() == FlowNotifService.DAILY_INSIGHT_NOTIF_ID
                    && item.getTag() == null) return item;
        }
        return null;
    }

    private static StatusBarNotification awaitDaily(NotificationManager manager, boolean present) throws Exception {
        long until = System.currentTimeMillis() + WAIT_MS;
        do {
            StatusBarNotification item = findDaily(manager);
            if (present && item != null) return item;
            if (!present && item == null) return null;
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < until);
        return present ? null : findDaily(manager);
    }

    private static NotificationChannelState waitForChannel(NotificationManager manager) throws Exception {
        long until = System.currentTimeMillis() + WAIT_MS;
        android.app.NotificationChannel channel;
        do {
            channel = manager.getNotificationChannel(FlowNotifService.CHANNEL_DAILY_INSIGHT);
            if (channel != null) return new NotificationChannelState(
                    channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < until);
        return new NotificationChannelState(false);
    }

    private static void writeFixture(FlowDao dao, String state, String projection) {
        FlowDatabase.callStorage(() -> {
            dao.writeState(state);
            dao.writeNotif(projection);
            return null;
        });
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }

    private static final class NotificationChannelState {
        final boolean enabled;
        NotificationChannelState(boolean enabled) { this.enabled = enabled; }
    }
}
