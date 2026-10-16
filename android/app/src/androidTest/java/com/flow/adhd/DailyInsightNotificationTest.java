package com.flow.adhd;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.service.notification.StatusBarNotification;
import androidx.test.core.app.ActivityScenario;
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

/** Verifies daily insight delivery against persisted Room state and the real tap queue. */
@RunWith(AndroidJUnit4.class)
public class DailyInsightNotificationTest {
    private static final String DELIVERY_PREFS = "flow_daily_insight_delivery";
    private static final String DELIVERED_DAYS = "delivered_days";
    private static final long WAIT_MS = 8_000L;

    @Test public void earnedRoomClaimPostsOnceAndTapQueuesArchiveOpenWithoutCredit() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        String oldState = bridge.load();
        String oldProjection = bridge.getNotifJson();
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new java.util.Date());
        android.content.SharedPreferences prefs = context.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE);
        boolean hadDeliveredDays = prefs.contains(DELIVERED_DAYS);
        Set<String> oldDeliveredDays = prefs.getStringSet(DELIVERED_DAYS, null) == null
                ? null : new HashSet<>(prefs.getStringSet(DELIVERED_DAYS, null));
        Set<Long> priorEventIds = eventIds(bridge);
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
            bridge.save(state.toString());
            bridge.saveNotif(projection.toString());
            refresh(context, bridge);
            NotificationChannelState channel = waitForChannel(manager);
            assumeTrue("The daily insight channel must be enabled", channel.enabled);
            assertNull("A progress day without a claim must not earn a card", findDaily(manager));

            JSONArray archivedClaim = new JSONArray().put(new JSONObject().put("day", today).put("cardId", 7)
                    .put("sequence", 1).put("earnedAt", 0));
            state.put("dailyInsightClaims", archivedClaim);
            bridge.save(state.toString());
            refresh(context, bridge);
            assertNull("An archived claim with earnedAt=0 must not notify", findDaily(manager));

            JSONArray earnedClaim = new JSONArray().put(new JSONObject().put("day", today).put("cardId", 7)
                    .put("sequence", 1).put("earnedAt", System.currentTimeMillis()));
            state.put("dailyInsightClaims", earnedClaim);
            bridge.save(state.toString());
            projection.put("dailyInsight", new JSONObject(insight.toString()).put("title", repeat('Т', 161)));
            bridge.saveNotif(projection.toString());
            refresh(context, bridge);
            assertNull("Oversized titles must be rejected", findDaily(manager));

            projection.put("dailyInsight", new JSONObject(insight.toString()).put("text", repeat('Д', 2001)));
            bridge.saveNotif(projection.toString());
            refresh(context, bridge);
            assertNull("Oversized text must be rejected", findDaily(manager));

            projection.put("dailyInsight", insight);
            bridge.saveNotif(projection.toString());
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

            // Exercise the same Activity intent path as the notification without
            // letting an old packaged WebView consume the event before inspection.
            JSONObject beforeTap = new JSONObject(bridge.load());
            android.content.Intent tapIntent = FlowNotifService.createDailyInsightOpenIntent(context, today);
            assertTrue(MainActivity.queueInsightOpenIntent(bridge, tapIntent));
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            assertEquals("The tap queues one durable archive-open event", 1,
                    countEvent(events, priorEventIds, "open_insight:" + today));
            JSONObject afterTap = new JSONObject(bridge.load());
            assertEquals(beforeTap.getJSONArray("progressDays").toString(), afterTap.getJSONArray("progressDays").toString());
            assertEquals(beforeTap.getJSONArray("dailyInsightClaims").toString(), afterTap.getJSONArray("dailyInsightClaims").toString());
        } finally {
            FlowNotifService.stop(context);
            manager.cancel(FlowNotifService.NOTIF_ID);
            manager.cancel(FlowNotifService.DAILY_INSIGHT_NOTIF_ID);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            JSONArray acknowledgements = new JSONArray();
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.getJSONObject(i);
                if (!priorEventIds.contains(event.optLong("id", -1L))
                        && event.optString("event", "").equals("open_insight:" + today)) {
                    acknowledgements.put(event.getLong("id"));
                }
            }
            if (acknowledgements.length() > 0) bridge.saveWithEvents(oldState == null || oldState.isEmpty() ? "{}" : oldState,
                    acknowledgements.toString());
            bridge.save(oldState == null ? "" : oldState);
            bridge.saveNotif(oldProjection == null || oldProjection.isEmpty() ? "{}" : oldProjection);
            if (hadDeliveredDays) prefs.edit().putStringSet(DELIVERED_DAYS,
                    oldDeliveredDays == null ? java.util.Collections.<String>emptySet() : oldDeliveredDays).commit();
            else prefs.edit().remove(DELIVERED_DAYS).commit();
        }
    }

    private static void refresh(Context context, FlowBridge bridge) {
        FlowNotifService.start(context);
        bridge.getNotifJson(); // FIFO storage barrier after the service's projection refresh.
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

    private static Set<Long> eventIds(FlowBridge bridge) throws Exception {
        Set<Long> result = new HashSet<>();
        JSONArray events = new JSONArray(bridge.getEventEnvelopes());
        for (int i = 0; i < events.length(); i++) result.add(events.getJSONObject(i).optLong("id", -1L));
        return result;
    }

    private static int countEvent(JSONArray events, Set<Long> priorIds, String exact) throws Exception {
        int count = 0;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (!priorIds.contains(event.optLong("id", -1L)) && exact.equals(event.optString("event", ""))) count++;
        }
        return count;
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