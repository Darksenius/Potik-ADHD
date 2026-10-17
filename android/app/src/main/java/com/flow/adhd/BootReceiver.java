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
        boolean boot = Intent.ACTION_BOOT_COMPLETED.equals(action);
        boolean clockChanged = Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_DATE_CHANGED.equals(action);
        boolean exactAlarmPermissionChanged = "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action);
        if (!boot && !clockChanged && !exactAlarmPermissionChanged) return;

        Context appContext = context.getApplicationContext();
        PendingResult pending = goAsync();
        FlowDatabase.executeStorage(() -> {
            try {
                if (boot) FlowNotifService.start(appContext);
                // RTC deadlines and the native wall-day projection both depend
                // on the device calendar. scheduleAll is reentrant on this lane.
                FlowAlarmReceiver.scheduleAll(appContext);
                if (BuildConfig.NATIVE_RUNTIME) FlowNoteReview.reschedule(appContext);
                if (clockChanged || exactAlarmPermissionChanged) FlowNotifService.start(appContext);
            } catch (Exception e) {
                android.util.Log.w("BootReceiver", "Unable to refresh native schedules", e);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }
}
