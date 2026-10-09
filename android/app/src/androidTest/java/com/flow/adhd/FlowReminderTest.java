package com.flow.adhd;

import static org.junit.Assert.*;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.service.notification.StatusBarNotification;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Runs against Android notification delivery and the actual Room command queue. */
@RunWith(AndroidJUnit4.class)
public class FlowReminderTest {
    @Test public void delayedReminderHasOneNotificationAndOneReceiptForItsDeadline() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        FlowBridge bridge = new FlowBridge(context);
        String previous = bridge.getNotifJson();
        String id = "qa-" + System.currentTimeMillis();
        long due = System.currentTimeMillis() - 5000;
        String receipt = "sched_fired:" + id + ":" + due;
        JSONObject reminder = new JSONObject().put("id", id).put("title", "QA deadline").put("dueMs", due).put("fired", false);
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        FlowAlarmReceiver receiver = new FlowAlarmReceiver();
        try {
            bridge.saveNotif(new JSONObject().put("schedList", new JSONArray().put(reminder)).toString());
            receiver.onReceive(context, new Intent(FlowAlarmReceiver.ACTION_TASK_DUE));
            receiver.onReceive(context, new Intent(FlowAlarmReceiver.ACTION_TASK_DUE));
            int shown = 0;
            for (StatusBarNotification notification : notifications.getActiveNotifications()) {
                if (("flow-task:" + id).equals(notification.getTag())) shown++;
            }
            assertEquals("Repeated checks must not create duplicate reminders", 1, shown);
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            int acknowledged = 0;
            for (int i = 0; i < events.length(); i++) {
                if (receipt.equals(events.getJSONObject(i).getString("event"))) acknowledged++;
            }
            assertEquals("Receipt identifies the exact deadline, once", 1, acknowledged);
        } finally {
            notifications.cancel("flow-task:" + id, 5000);
            bridge.saveNotif(previous == null || previous.isEmpty() ? "{}" : previous);
        }
    }
}
