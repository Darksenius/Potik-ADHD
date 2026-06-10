package com.flow.adhd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Після перезавантаження пристрою:
 *  - перезапускає foreground-сервіс сповіщення
 *  - переплановує будильники (sched-перевірка + щоденний скид)
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            try {
                FlowNotifService.start(context);
                FlowAlarmReceiver.scheduleAll(context);
            } catch (Exception ignored) {}
        }
    }
}
