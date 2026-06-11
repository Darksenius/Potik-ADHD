package com.flow.adhd;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;

/**
 * Обробляє два типи будильників навіть коли апка закрита:
 *  - ACTION_SCHED_CHECK: перевіряє sched-задачі, чий час настав → heads-up нагадування
 *  - ACTION_DAILY_RESET: скидання о 00:10 (timewin.completedToday, рутина) через подію в Room
 *
 * Оскільки JS-логіка живе у WebView, фактичний скид робить апка при наступному
 * відкритті (читаючи saveDate). Тут ми пишемо подію-маркер у Room і оновлюємо
 * сповіщення, щоб користувач побачив нагадування одразу.
 */
public class FlowAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_SCHED_CHECK = "com.flow.adhd.SCHED_CHECK";
    public static final String ACTION_DAILY_RESET = "com.flow.adhd.DAILY_RESET";
    public static final String ACTION_SNOOZE      = "com.flow.adhd.SNOOZE_FIRE";
    // Точний будильник РІВНО на час найближчої задачі (а не 15-хв полінг)
    public static final String ACTION_TASK_DUE    = "com.flow.adhd.TASK_DUE";
    // Щогодинне нагадування про воду (09:00–21:00)
    public static final String ACTION_WATER       = "com.flow.adhd.WATER_REMIND";
    public static final String CHANNEL_REMIND      = "flow_remind";
    public static final String CHANNEL_WATER       = "flow_water";
    private static final int   REMIND_BASE         = 5000;
    private static final int   WATER_NOTIF_ID      = 6001;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;

        FlowBridge bridge = new FlowBridge(context);

        if (ACTION_SCHED_CHECK.equals(action)) {
            checkSched(context, bridge);
            // переплановуємо наступну перевірку через 15 хв (запасний механізм)
            schedule(context, ACTION_SCHED_CHECK, System.currentTimeMillis() + 15 * 60_000L, 1);
            // + точний будильник на найближчу задачу
            scheduleNextDue(context, bridge);
            // оновити основне сповіщення
            FlowNotifService.start(context);
        } else if (ACTION_TASK_DUE.equals(action)) {
            // настав ТОЧНИЙ час задачі: фаєримо нагадування і плануємо наступну
            checkSched(context, bridge);
            scheduleNextDue(context, bridge);
            FlowNotifService.start(context);
        } else if (ACTION_WATER.equals(action)) {
            waterRemind(context, bridge);
            scheduleWater(context);
        } else if (ACTION_DAILY_RESET.equals(action)) {
            // пишемо маркер у Room + broadcast — апка обробить миттєво або при відкритті
            pushAndNotify(context, bridge, "daily_reset");
            FlowNotifService.start(context);
            // переплановуємо на завтра 00:10 (+ страхуємо водяний цикл)
            scheduleDailyReset(context);
            scheduleWater(context);
        } else if (ACTION_SNOOZE.equals(action)) {
            // Відкладена задача: окреме нагадування через 1 годину (працює і коли апка закрита)
            String id = intent.getStringExtra("snooze_id");
            String title = intent.getStringExtra("snooze_title");
            fireReminder(context, id != null ? id : "", title != null && !title.isEmpty() ? title : "Відкладена задача");
            // маркер у застосунок (якщо відкритий — підсвітить задачу)
            pushAndNotify(context, bridge, "task_remind:" + (id != null ? id : ""));
        }
    }

    /** Пише подію в Room + шле broadcast для миттєвої доставки в JS */
    private void pushAndNotify(Context context, FlowBridge bridge, String event) {
        bridge.pushEvent(event);
        Intent b = new Intent(FlowNotifService.FLOW_EVENT);
        b.putExtra("event", event);
        b.setPackage(context.getPackageName());
        context.sendBroadcast(b);
    }

    /** Перевіряє notif_json (масив schedList), чи настав час якоїсь задачі */
    private void checkSched(Context context, FlowBridge bridge) {
        String notifJson = bridge.getNotifJson();
        if (notifJson == null || notifJson.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(notifJson);
            JSONArray sched = j.optJSONArray("schedList");
            if (sched == null) return;
            long now = System.currentTimeMillis();
            for (int i = 0; i < sched.length(); i++) {
                JSONObject s = sched.optJSONObject(i);
                if (s == null) continue;
                long due = s.optLong("dueMs", 0);
                boolean fired = s.optBoolean("fired", false);
                if (!fired && due > 0 && due <= now) {
                    fireReminder(context, s.optString("id", ""), s.optString("title", "Нагадування"));
                    // позначаємо як fired через подію (апка зафіксує в стані)
                    pushAndNotify(context, bridge, "sched_fired:" + s.optString("id", ""));
                }
            }
        } catch (Exception ignored) {}
    }

    private void fireReminder(Context context, String id, String title) {
        createRemindChannel(context);
        Intent open = new Intent(context, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        open.putExtra("nav", "tasks");
        int fImm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(context, 0, open,
                fImm | PendingIntent.FLAG_UPDATE_CURRENT);

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_REMIND)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ " + title)
                .setContentText("Запланований час настав")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(REMIND_BASE + (id.hashCode() & 0xffff), b.build());
    }

    private void createRemindChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_REMIND) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_REMIND, "Потік — нагадування", NotificationManager.IMPORTANCE_HIGH);
            ch.setDescription("Heads-up нагадування про заплановані задачі");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    // ════════════════ ВОДА ЩОГОДИНИ ════════════════

    /**
     * Сповіщення про воду: щогодини 09:00–21:00. Бере першу рутину з unit=="ml"
     * зі знімка (routineList) — кнопка «+крок мл» працює без відкриття апки
     * через стандартний шлях ACTION_RC_INC сервісу.
     */
    private void waterRemind(Context context, FlowBridge bridge) {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour < 9 || hour > 21) return;
        String notifJson = bridge.getNotifJson();
        if (notifJson == null || notifJson.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(notifJson);
            JSONArray rl = j.optJSONArray("routineList");
            if (rl == null) return;
            JSONObject water = null;
            for (int i = 0; i < rl.length(); i++) {
                JSONObject r = rl.optJSONObject(i);
                if (r != null && "ml".equals(r.optString("unit", ""))) { water = r; break; }
            }
            if (water == null) return; // водної рутини немає — нічого нагадувати
            String id   = water.optString("id", "");
            String nm   = water.optString("nm", "Вода");
            int    val  = water.optInt("val", 0);
            int    step = water.optInt("step", 100);

            createWaterChannel(context);
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            int fImm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
            PendingIntent openPi = PendingIntent.getActivity(context, 0, open,
                    fImm | PendingIntent.FLAG_UPDATE_CURRENT);

            // кнопка «+step мл» → той самий механізм, що в сповіщеннях рутини
            Intent inc = new Intent(context, FlowNotifService.class);
            inc.setAction(FlowNotifService.ACTION_RC_INC);
            inc.putExtra(FlowNotifService.EXTRA_ID, id);
            inc.putExtra("notif_id", WATER_NOTIF_ID);
            PendingIntent incPi = PendingIntent.getService(context,
                    (FlowNotifService.ACTION_RC_INC.hashCode() ^ WATER_NOTIF_ID) & 0x7fffffff,
                    inc, fImm | PendingIntent.FLAG_UPDATE_CURRENT);

            NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_WATER)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("💧 " + nm + ": " + val + " мл")
                    .setContentText("Час випити води")
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setAutoCancel(true)
                    .setTimeoutAfter(55 * 60_000L)
                    .setContentIntent(openPi)
                    .addAction(new NotificationCompat.Action.Builder(
                            android.R.drawable.ic_input_add, "＋" + step + " мл", incPi)
                            .setShowsUserInterface(false).build());

            NotificationManager nm2 = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm2 != null) nm2.notify(WATER_NOTIF_ID, b.build());
        } catch (Exception ignored) {}
    }

    private void createWaterChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null || nm.getNotificationChannel(CHANNEL_WATER) != null) return;
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_WATER, "Потік — вода", NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Щогодинне нагадування про воду (09:00–21:00)");
            ch.enableVibration(true);
            nm.createNotificationChannel(ch);
        }
    }

    // ════════════════ ПЛАНУВАННЯ ════════════════

    public static void schedule(Context ctx, String action, long triggerAtMs, int reqCode) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, FlowAlarmReceiver.class);
        i.setAction(action);
        int fImm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, reqCode, i,
                fImm | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                // setExact* — точний час навіть у Doze (важливо для sched-нагадувань)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pi);
            }
        } catch (SecurityException se) {
            // На API 31+ без дозволу SCHEDULE_EXACT_ALARM — fallback на неточний
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi); }
            catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    public static void scheduleDailyReset(Context ctx) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 10);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        if (c.getTimeInMillis() <= System.currentTimeMillis()) {
            c.add(Calendar.DAY_OF_YEAR, 1); // вже минуло сьогодні → завтра
        }
        schedule(ctx, ACTION_DAILY_RESET, c.getTimeInMillis(), 2);
    }

    /**
     * Точний будильник на НАЙБЛИЖЧУ незфаєрену задачу зі знімка (schedList).
     * Викликається при кожному оновленні знімка (saveNotif) і після кожного
     * спрацювання — нагадування приходить хвилина-в-хвилину, а не «раз на 15 хв».
     */
    public static void scheduleNextDue(Context ctx, FlowBridge bridge) {
        try {
            String notifJson = bridge.getNotifJson();
            if (notifJson == null || notifJson.isEmpty()) return;
            JSONObject j = new JSONObject(notifJson);
            JSONArray sched = j.optJSONArray("schedList");
            if (sched == null) return;
            long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
            for (int i = 0; i < sched.length(); i++) {
                JSONObject s = sched.optJSONObject(i);
                if (s == null) continue;
                long due = s.optLong("dueMs", 0);
                if (!s.optBoolean("fired", false) && due > now && due < next) next = due;
            }
            if (next != Long.MAX_VALUE) schedule(ctx, ACTION_TASK_DUE, next, 3);
        } catch (Exception ignored) {}
    }

    /** Наступне щогодинне нагадування про воду: найближча повна година в межах 09–21 */
    public static void scheduleWater(Context ctx) {
        Calendar c = Calendar.getInstance();
        c.add(Calendar.HOUR_OF_DAY, 1);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        int h = c.get(Calendar.HOUR_OF_DAY);
        if (h < 9) { c.set(Calendar.HOUR_OF_DAY, 9); }
        else if (h > 21) { c.add(Calendar.DAY_OF_YEAR, 1); c.set(Calendar.HOUR_OF_DAY, 9); }
        schedule(ctx, ACTION_WATER, c.getTimeInMillis(), 4);
    }

    /** Викликати при старті апки — запускає всі цикли */
    public static void scheduleAll(Context ctx) {
        schedule(ctx, ACTION_SCHED_CHECK, System.currentTimeMillis() + 60_000L, 1);
        scheduleDailyReset(ctx);
        scheduleWater(ctx);
        scheduleNextDue(ctx, new FlowBridge(ctx));
    }

    /** Запланувати окреме нагадування про відкладену задачу через delayMs (точний будильник) */
    public static void scheduleSnooze(Context ctx, String id, String title, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        Intent i = new Intent(ctx, FlowAlarmReceiver.class);
        i.setAction(ACTION_SNOOZE);
        i.putExtra("snooze_id", id);
        i.putExtra("snooze_title", title);
        int fImm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        int rc = 7000 + (id != null ? (id.hashCode() & 0xfff) : 0);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, rc, i, fImm | PendingIntent.FLAG_UPDATE_CURRENT);
        long at = System.currentTimeMillis() + delayMs;
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
        } catch (SecurityException se) {
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi); } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
}
