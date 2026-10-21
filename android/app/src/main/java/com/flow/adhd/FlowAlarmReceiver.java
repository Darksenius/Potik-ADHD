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
import android.net.Uri;
import android.content.SharedPreferences;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.TimeZone;

/** Native alarms queue durable commands; NativeRuntime applies them without an Activity. */
public class FlowAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION_SCHED_CHECK = "com.flow.adhd.SCHED_CHECK";
    public static final String ACTION_DAILY_RESET = "com.flow.adhd.DAILY_RESET";
    public static final String ACTION_SNOOZE      = "com.flow.adhd.SNOOZE_FIRE";
    // Точний будильник РІВНО на час найближчої задачі (а не 15-хв полінг)
    public static final String ACTION_TASK_DUE    = "com.flow.adhd.TASK_DUE";
    public static final String ACTION_DIAGNOSTIC_REMINDER = "com.flow.adhd.DIAGNOSTIC_REMINDER";
    private static final String ALARM_PREFS = "flow_alarm_schedule";
    private static final String PREF_NEXT_TASK_DUE = "next_task_due_ms";
    private static final String PREF_NEXT_TASK_DUE_EXACT = "next_task_due_exact";
    public static final int DIAGNOSTIC_NOTIFICATION_ID = 9012;
    static final String EXTRA_SNOOZE_DUE_MS = "snooze_due_ms";
    public static final String CHANNEL_REMIND      = "flow_remind";
    public static final int REMIND_NOTIFICATION_ID = 5000;
    private static final int REMIND_BASE = REMIND_NOTIFICATION_ID;
    private static final String LEGACY_ACTION_WATER = "com.flow.adhd.WATER_REMIND";
    private static final String LEGACY_CHANNEL_WATER = "flow_water";
    private static final int LEGACY_WATER_NOTIF_ID = 6001;
    private static final int LEGACY_WATER_REQUEST_CODE = 4;

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent != null ? intent.getAction() : null;
        if (action == null) return;
        Context appContext = context.getApplicationContext();
        Intent queuedIntent = new Intent(intent);
        BroadcastReceiver.PendingResult pending = goAsync();
        FlowDatabase.executeStorage(() -> {
            try {
                handleReceive(appContext, queuedIntent);
            } catch (Exception e) {
                android.util.Log.w("FlowAlarmReceiver", "Alarm command failed", e);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    private void handleReceive(Context context, Intent intent) {
        String action = intent.getAction();
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
        } else if (ACTION_DAILY_RESET.equals(action)) {
            // пишемо маркер у Room + broadcast — апка обробить миттєво або при відкритті
            pushAndNotify(context, bridge, "daily_reset");
            FlowNotifService.start(context);
            // переплановуємо на завтра 00:10
            scheduleDailyReset(context);
        } else if (ACTION_SNOOZE.equals(action)) {
            String id = intent.getStringExtra("snooze_id");
            String title = intent.getStringExtra("snooze_title");
            long due = intent.getLongExtra(EXTRA_SNOOZE_DUE_MS, 0L);
            if (id != null) context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                    .remove(snoozePrefKey(id)).apply();
            deliverSnoozeAt(context, bridge, id, title, due);
        } else if (ACTION_DIAGNOSTIC_REMINDER.equals(action)) {
            postDiagnosticReminder(context);
        }
    }

    /** Captures in Room; the platform facade wakes the native command owner. */
    private void pushAndNotify(Context context, FlowBridge bridge, String event) {
        bridge.pushEvent(event);
    }

    /** Перевіряє notif_json (масив schedList), чи настав час якоїсь задачі */
    private void checkSched(Context context, FlowBridge bridge) {
        checkSchedAt(context, bridge, System.currentTimeMillis(), TimeZone.getDefault());
    }

    void checkSchedAt(Context context, FlowBridge bridge, long now, TimeZone zone) {
        String notifJson = bridge.getNotifJson();
        if (notifJson == null || notifJson.isEmpty()) return;
        try {
            JSONObject j = new JSONObject(notifJson);
            JSONArray sched = effectiveSchedList(j, bridge, now, zone);
            if (sched == null) return;
            SharedPreferences delivered = context.getSharedPreferences("flow_delivered_reminders", Context.MODE_PRIVATE);
            for (int i = 0; i < sched.length(); i++) {
                JSONObject s = sched.optJSONObject(i);
                if (s == null) continue;
                String id = s.optString("id", "");
                boolean recurring = isCalendarRepeat(s);
                long due = recurring ? dueOccurrenceToday(s, now, zone) : s.optLong("dueMs", 0);
                boolean fired = !recurring && s.optBoolean("fired", false);
                String deliveryKey = deliveryKey(id, due);
                long deliveredDue = delivered.getLong(deliveryKey, -1);
                if (!recurring && deliveredDue != due) {
                    deliveredDue = delivered.getLong("due:" + id, -1); // v1.7 one-off compatibility
                }
                if (!fired && due > 0 && due <= now && deliveredDue != due
                        && !suppressedBySameDayAction(bridge, id, due, zone)) {
                    if (!isReminderTaskEligible(bridge, id)
                            || !fireReminder(context, id, s.optString("title", "Нагадування"))) continue;
                    // Keep delivery deduplication available when the Activity is closed.
                    SharedPreferences.Editor editor = delivered.edit().putLong(deliveryKey, due);
                    if (!recurring) editor.putLong("due:" + id, due);
                    editor.commit();
                    // JS state is acknowledged through the durable command queue.
                    pushAndNotify(context, bridge, "sched_fired:" + id + ":" + due);
                }
            }
        } catch (Exception ignored) {}
    }

    static String deliveryKey(String id, long occurrenceMs) { return "due:" + id + ":" + occurrenceMs; }

    static long roundUpToMinute(long timestampMs) {
        return ((timestampMs + 59_999L) / 60_000L) * 60_000L;
    }

    static Intent createSnoozeIntent(Context context, String id, String title, long dueMs) {
        Intent intent = new Intent(context, FlowAlarmReceiver.class);
        intent.setAction(ACTION_SNOOZE);
        intent.setData(Uri.parse("flow://snooze/" + Uri.encode(id == null ? "" : id)));
        intent.putExtra("snooze_id", id);
        intent.putExtra("snooze_title", title);
        intent.putExtra(EXTRA_SNOOZE_DUE_MS, dueMs);
        return intent;
    }

    boolean deliverSnoozeAt(Context context, FlowBridge bridge, String id, String title, long dueMs) {
        if (id == null || id.isEmpty() || dueMs <= 0 || !isReminderTaskEligible(bridge, id)) return false;
        SharedPreferences delivered = context.getSharedPreferences("flow_delivered_reminders", Context.MODE_PRIVATE);
        String key = deliveryKey(id, dueMs);
        if (delivered.getLong(key, -1L) == dueMs) return false;
        String shownTitle = title == null || title.isEmpty() ? "Відкладена задача" : title;
        if (!fireReminder(context, id, shownTitle)) return false;
        if (!delivered.edit().putLong(key, dueMs).commit()) return false;
        pushAndNotify(context, bridge, "sched_fired:" + id + ":" + dueMs);
        pushAndNotify(context, bridge, "task_remind:" + id);
        return true;
    }

    private boolean isReminderTaskEligible(FlowBridge bridge, String id) {
        try {
            JSONObject state = new JSONObject(bridge.load());
            JSONArray tasks = state.optJSONArray("tasks");
            if (tasks == null) return true; // legacy state without task metadata
            String taskId = id != null && id.endsWith("_pre") ? id.substring(0, id.length() - 4) : id;
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.optJSONObject(i);
                if (task == null || !taskId.equals(task.optString("id", ""))) continue;
                String type = task.optString("type", "");
                return !task.optBoolean("done", false) && !task.optBoolean("trashed", false)
                        && !task.optBoolean("someday", false) && !"someday".equals(type)
                        && !"negative".equals(type);
            }
            return false;
        } catch (Exception unavailable) {
            return true; // Preserve delivery for older snapshots that cannot be parsed.
        }
    }

    static boolean isCalendarRepeat(JSONObject row) {
        String repeat = row == null ? "" : row.optString("repeat", "none");
        return "daily".equals(repeat) || "weekly".equals(repeat) || "weekdays".equals(repeat)
                || "weekend".equals(repeat) || "custom".equals(repeat);
    }

    /** Next recurring trigger strictly after `afterMs`; anchorDate is the first eligible task day. */
    static long nextOccurrenceAfter(JSONObject row, long afterMs, TimeZone zone) {
        if (!isCalendarRepeat(row) || zone == null || !hasValidLocalTime(row)) return -1L;
        if ("custom".equals(row.optString("repeat", ""))) {
            JSONArray days = row.optJSONArray("repeatDays");
            boolean selected = false;
            if (days != null) for (int i = 0; i < Math.min(7, days.length()); i++) selected |= days.optBoolean(i, false);
            if (!selected) return -1L;
        }
        Calendar candidate = Calendar.getInstance(zone, Locale.US);
        candidate.setTimeInMillis(afterMs);
        clearTime(candidate);
        Calendar anchor = parseDate(row.optString("anchorDate", ""), zone);
        if (anchor == null) return -1L;
        if (candidate.before(anchor)) candidate.setTimeInMillis(anchor.getTimeInMillis());
        for (;;) {
            if (isEligibleDate(row, candidate, anchor)) {
                long due = triggerForDate(row, candidate, zone);
                if (due > afterMs) return due;
            }
            candidate.add(Calendar.DAY_OF_YEAR, 1);
        }
    }

    /** Latest recurring trigger from today, or -1; delayed receivers never replay older days. */
    static long dueOccurrenceToday(JSONObject row, long nowMs, TimeZone zone) {
        if (!isCalendarRepeat(row) || zone == null || !hasValidLocalTime(row)) return -1L;
        Calendar today = Calendar.getInstance(zone, Locale.US);
        today.setTimeInMillis(nowMs);
        clearTime(today);
        Calendar anchor = parseDate(row.optString("anchorDate", ""), zone);
        if (anchor == null) return -1L;
        long latest = -1L;
        Calendar target = (Calendar) today.clone();
        long leadDays = Math.max(0L, row.optLong("advanceMinutes", 0L) / (24L * 60L)) + 1L;
        for (long i = 0; i <= leadDays; i++) {
            if (!target.before(anchor) && isEligibleDate(row, target, anchor)) {
                long due = triggerForDate(row, target, zone);
                if (sameLocalDate(due, nowMs, zone) && due <= nowMs) latest = Math.max(latest, due);
            }
            target.add(Calendar.DAY_OF_YEAR, 1);
        }
        return latest;
    }

    private static boolean isEligibleDate(JSONObject row, Calendar date, Calendar anchor) {
        int weekday = (date.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday=0 .. Sunday=6
        switch (row.optString("repeat", "")) {
            case "daily": return true;
            case "weekly": return date.get(Calendar.DAY_OF_WEEK) == anchor.get(Calendar.DAY_OF_WEEK);
            case "weekdays": return weekday < 5;
            case "weekend": return weekday >= 5;
            case "custom":
                JSONArray days = row.optJSONArray("repeatDays");
                return days != null && weekday < days.length() && days.optBoolean(weekday, false);
            default: return false;
        }
    }

    private static long triggerForDate(JSONObject row, Calendar date, TimeZone zone) {
        String[] time = row.optString("localTime", "").split(":");
        if (time.length != 2) return -1L;
        try {
            int hour = Integer.parseInt(time[0]);
            int minute = Integer.parseInt(time[1]);
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) return -1L;
            Calendar trigger = Calendar.getInstance(zone, Locale.US);
            trigger.setLenient(true); // Java's local-time normalization handles DST gaps.
            trigger.clear();
            trigger.set(date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH), hour, minute, 0);
            trigger.set(Calendar.MILLISECOND, 0);
            long lead = Math.max(0, row.optLong("advanceMinutes", 0L));
            return trigger.getTimeInMillis() - lead * 60_000L;
        } catch (NumberFormatException ignored) { return -1L; }
    }

    private static boolean hasValidLocalTime(JSONObject row) {
        String[] time = row.optString("localTime", "").split(":");
        if (time.length != 2) return false;
        try {
            int hour = Integer.parseInt(time[0]);
            int minute = Integer.parseInt(time[1]);
            return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59;
        } catch (NumberFormatException ignored) { return false; }
    }

    private static Calendar parseDate(String text, TimeZone zone) {
        String[] parts = text.split("-");
        if (parts.length != 3) return null;
        try {
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            Calendar value = Calendar.getInstance(zone, Locale.US);
            value.clear();
            value.setLenient(false);
            value.set(year, month - 1, day, 0, 0, 0);
            value.getTimeInMillis(); // validate normalized date components
            return value;
        } catch (Exception ignored) { return null; }
    }

    private static void clearTime(Calendar value) {
        value.set(Calendar.HOUR_OF_DAY, 0);
        value.set(Calendar.MINUTE, 0);
        value.set(Calendar.SECOND, 0);
        value.set(Calendar.MILLISECOND, 0);
    }

    private static boolean sameLocalDate(long first, long second, TimeZone zone) {
        Calendar a = Calendar.getInstance(zone, Locale.US);
        Calendar b = Calendar.getInstance(zone, Locale.US);
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
                && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private boolean suppressedBySameDayAction(FlowBridge bridge, String reminderId, long dueMs, TimeZone zone) {
        String taskId = reminderId != null && reminderId.endsWith("_pre")
                ? reminderId.substring(0, reminderId.length() - 4) : reminderId;
        if (taskId == null || taskId.isEmpty()) return false;
        try {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            String done = "task_done:" + taskId;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String value = event.optString("event", "");
                if (done.equals(value)
                        && sameLocalDate(event.optLong("createdAt", 0L), dueMs, zone)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private boolean fireReminder(Context context, String id, String title) {
        createRemindChannel(context);
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_REMIND);
            if (channel == null || channel.getImportance() == NotificationManager.IMPORTANCE_NONE) return false;
        }
        String taskId = id != null && id.endsWith("_pre") ? id.substring(0, id.length() - 4) : id;
        String tag = FlowNotifService.TASK_REMINDER_TAG + id;
        PendingIntent pi = FlowNotifService.openTaskPendingIntent(context, taskId);
        String generation = FlowNotifService.actionGeneration(context, taskId, System.currentTimeMillis());

        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_REMIND)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("⏰ " + title)
                .setContentText("Запланований час настав")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(pi);

        b.addAction(FlowNotifService.taskAction(context, android.R.drawable.checkbox_on_background,
                        "Виконано", FlowNotifService.ACTION_TASK_DONE, taskId, tag, REMIND_BASE, generation))
                .addAction(FlowNotifService.taskAction(context, android.R.drawable.ic_media_next,
                        "Не зараз", FlowNotifService.ACTION_TASK_NEXT_ZONE, taskId, tag, REMIND_BASE, generation))
                .addAction(FlowNotifService.taskAction(context, android.R.drawable.ic_menu_close_clear_cancel,
                        "Не сьогодні", FlowNotifService.ACTION_TASK_NOT_TODAY, taskId, tag, REMIND_BASE, generation));

        try { nm.notify(tag, REMIND_BASE, b.build()); return true; }
        catch (SecurityException denied) { return false; }
    }

    private static void createRemindChannel(Context context) {
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

    // ════════════════ ПЛАНУВАННЯ ════════════════

    public static boolean schedule(Context ctx, String action, long triggerAtMs, int reqCode) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return false;
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
            return true;
        } catch (SecurityException se) {
            // На API 31+ без дозволу SCHEDULE_EXACT_ALARM — fallback на неточний
            try { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi); return true; }
            catch (Exception ignored) { return false; }
        } catch (Exception ignored) {}
        return false;
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
        if (!FlowDatabase.isStorageThread()) {
            Context appContext = ctx.getApplicationContext();
            FlowDatabase.executeStorage(() -> scheduleNextDue(appContext, bridge));
            return;
        }
        try {
            String notifJson = bridge.getNotifJson();
            if (notifJson == null || notifJson.isEmpty()) { cancelNextDue(ctx); return; }
            JSONObject j = new JSONObject(notifJson);
            JSONArray sched = effectiveSchedList(j, bridge, System.currentTimeMillis(), TimeZone.getDefault());
            if (sched == null) { cancelNextDue(ctx); return; }
            long now = System.currentTimeMillis(), next = Long.MAX_VALUE;
            for (int i = 0; i < sched.length(); i++) {
                JSONObject s = sched.optJSONObject(i);
                if (s == null) continue;
                long due = isCalendarRepeat(s)
                        ? nextOccurrenceAfter(s, now, TimeZone.getDefault()) : s.optLong("dueMs", 0);
                if ((isCalendarRepeat(s) || !s.optBoolean("fired", false)) && due > now && due < next) next = due;
            }
            if (next != Long.MAX_VALUE && schedule(ctx, ACTION_TASK_DUE, next, 3)) {
                boolean exact = Build.VERSION.SDK_INT < 31;
                AlarmManager manager = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (Build.VERSION.SDK_INT >= 31 && manager != null) exact = manager.canScheduleExactAlarms();
                ctx.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(PREF_NEXT_TASK_DUE, next)
                        .putBoolean(PREF_NEXT_TASK_DUE_EXACT, exact).apply();
            } else cancelNextDue(ctx);
        } catch (Exception ignored) { cancelNextDue(ctx); }
    }

    /**
     * Overlays durable native commands on the last JS reminder snapshot. This
     * prevents an old scheduled alarm from firing while the Activity is closed
     * and installs the one-off time derived from a pending deferral.
     */
    static JSONArray effectiveSchedList(JSONObject snapshot, FlowBridge bridge, long now, TimeZone zone) {
        try {
            JSONArray original = snapshot.optJSONArray("schedList");
            JSONArray output = new JSONArray();
            List<QuestPolicy.Segment> timeline = QuestPolicy.timelineForSnapshot(snapshot, zone);
            QuestPolicy.OccurrenceIndex occurrences = QuestPolicy.OccurrenceIndex.fromSnapshot(snapshot, zone);
            List<QuestPolicy.Task> source = QuestPolicy.tasksFromJson(snapshot.optJSONArray("questTasks"));
            JSONArray rawEvents;
            try { rawEvents = new JSONArray(bridge.getEventEnvelopes()); }
            catch (Exception ignored) { rawEvents = new JSONArray(); }
            List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(source,
                    QuestPolicy.eventsFromJson(rawEvents), now, timeline, zone, occurrences);
            Map<String, QuestPolicy.Task> byId = new HashMap<>();
            for (QuestPolicy.Task task : projected) {
                byId.put(task.id, task);
            }
            if (original != null) for (int i = 0; i < original.length(); i++) {
                JSONObject row = original.optJSONObject(i);
                if (row == null) continue;
                String id = row.optString("id", "");
                String taskId = id.endsWith("_pre") ? id.substring(0, id.length() - 4) : id;
                QuestPolicy.Task task = byId.get(taskId);
                if (task != null && (task.deferral != null || task.done || task.trashed || task.someday
                        || "negative".equals(task.type))) continue;
                if (task != null && !isCalendarRepeat(row)) {
                    if (task.snoozeUntil > now) {
                        output.put(row); // snoozeUntil is an absolute elapsed deadline.
                        continue;
                    }
                    if (("sched".equals(task.type) || "alarm".equals(task.type))
                            && (task.repeat == null || task.repeat.isEmpty() || "none".equals(task.repeat))) {
                        boolean pre = id.endsWith("_pre");
                        if (!task.reminderEnabled || (pre ? task.firedPre : "sched".equals(task.type)
                                ? task.firedSched : task.alarmFired)) continue;
                        long wallDue = oneOffWallDue(task, pre, now, zone);
                        if (wallDue != Long.MIN_VALUE) {
                            if (wallDue <= 0) continue;
                            row.put("dueMs", wallDue);
                        }
                    }
                } else if (task != null && isCalendarRepeat(row) && task.snoozeUntil <= now) {
                    String anchor = QuestPolicy.taskPlanDate(task);
                    if (anchor == null) anchor = QuestPolicy.createdLocalDate(task.created, zone);
                    if (anchor != null) row.put("anchorDate", anchor);
                }
                output.put(row);
            }
            for (QuestPolicy.Task task : projected) {
                if (task.deferral == null || !("sched".equals(task.type) || "alarm".equals(task.type))
                        || !task.reminderEnabled || task.done || task.trashed || task.someday
                        || "negative".equals(task.type)) continue;
                boolean fired = "sched".equals(task.type) ? task.firedSched : task.alarmFired;
                if (fired) continue;
                long due = deferredReminderAt(task, timeline);
                if (due <= 0) continue;
                JSONObject row = new JSONObject();
                row.put("id", task.id);
                row.put("title", task.title);
                row.put("dueMs", due);
                row.put("fired", false);
                output.put(row);
            }
            return output;
        } catch (Exception ignored) {
            return snapshot == null ? null : snapshot.optJSONArray("schedList");
        }
    }

    /** A missing/invalid wall time keeps the snapshot's old due instead of deleting repairable data. */
    static long oneOffWallDue(QuestPolicy.Task task, boolean pre, long now, TimeZone zone) {
        if (task == null || task.deferral != null || task.snoozeUntil > now
                || !(task.repeat == null || task.repeat.isEmpty() || "none".equals(task.repeat))) return Long.MIN_VALUE;
        if ("sched".equals(task.type)) {
            String date = QuestPolicy.taskPlanDate(task);
            long mainDue = QuestPolicy.localDateTime(date, task.schedTime, zone);
            if (mainDue == Long.MIN_VALUE) return Long.MIN_VALUE;
            if (pre) {
                if (task.firedPre || task.remindBeforeMinutes <= 0 || mainDue <= now) return -1L;
                return mainDue - task.remindBeforeMinutes * 60_000L;
            }
            return mainDue;
        }
        if ("alarm".equals(task.type)) {
            if (pre) return -1L;
            if (task.alarmFired) return -1L;
            return nextUnrepeatedAlarmDue(task, now, zone);
        }
        return Long.MIN_VALUE;
    }

    private static long nextUnrepeatedAlarmDue(QuestPolicy.Task task, long now, TimeZone zone) {
        String date = QuestPolicy.taskPlanDate(task);
        long minDue = Math.max(now - 59_999L, task.snoozeUntil);
        Calendar cursor = Calendar.getInstance(zone, Locale.US);
        cursor.setTimeInMillis(now);
        cursor.set(Calendar.HOUR_OF_DAY, 0); cursor.set(Calendar.MINUTE, 0);
        cursor.set(Calendar.SECOND, 0); cursor.set(Calendar.MILLISECOND, 0);
        for (int offset = 0; offset < 370; offset++) {
            String candidateDate = QuestPolicy.localDate(cursor.getTimeInMillis(), zone);
            if (date == null || candidateDate.compareTo(date) >= 0) {
                long due = QuestPolicy.localDateTime(candidateDate, task.alarmTime, zone);
                if (due != Long.MIN_VALUE && due >= minDue) return due;
            }
            cursor.add(Calendar.DAY_OF_YEAR, 1);
        }
        return -1L;
    }

    static long deferredReminderAt(QuestPolicy.Task task, List<QuestPolicy.Segment> timeline) {
        QuestPolicy.Deferral deferral = task.deferral;
        if (deferral == null) return -1L;
        if (QuestPolicy.DEFERRAL_NEXT_ZONE.equals(deferral.kind) || !task.hasZone) return deferral.startAt;
        long first = Long.MAX_VALUE;
        for (QuestPolicy.Segment segment : timeline) {
            if (segment.zoneId == deferral.targetZoneId && segment.endAt > deferral.startAt)
                first = Math.min(first, Math.max(segment.startAt, deferral.startAt));
        }
        return first == Long.MAX_VALUE ? -1L : first;
    }

    private static void cancelNextDue(Context context) {
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        Intent intent = new Intent(context, FlowAlarmReceiver.class);
        intent.setAction(ACTION_TASK_DUE);
        int flags = (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_NO_CREATE;
        PendingIntent existing = PendingIntent.getBroadcast(context, 3, intent, flags);
        if (existing != null) {
            if (manager != null) manager.cancel(existing);
            existing.cancel();
        }
        context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                .remove(PREF_NEXT_TASK_DUE).remove(PREF_NEXT_TASK_DUE_EXACT).apply();
    }

    /** Actual task alarm time tracked when this receiver successfully schedules it. */
    public static Long nextScheduledReminderAt(Context context) {
        long now = System.currentTimeMillis();
        long next = Long.MAX_VALUE;
        long due = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE)
                .getLong(PREF_NEXT_TASK_DUE, 0L);
        android.content.SharedPreferences preferences = context.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE);
        if (Build.VERSION.SDK_INT >= 31 && preferences.getBoolean(PREF_NEXT_TASK_DUE_EXACT, false)) {
            AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarms == null || !alarms.canScheduleExactAlarms()) return null;
        }
        int flags = (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_NO_CREATE;
        if (due > now) {
            Intent intent = new Intent(context, FlowAlarmReceiver.class);
            intent.setAction(ACTION_TASK_DUE);
            PendingIntent pending = PendingIntent.getBroadcast(context, 3, intent, flags);
            if (pending != null) next = due;
        }
        for (java.util.Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("snooze_due:")) continue;
            String id = key.substring("snooze_due:".length());
            if (!(entry.getValue() instanceof Long)) continue;
            long snoozeAt = (Long) entry.getValue();
            if (snoozeAt <= now) continue;
            PendingIntent pending = PendingIntent.getBroadcast(context, snoozeRequestCode(id),
                    createSnoozeIntent(context, id, "", snoozeAt), flags);
            if (pending != null) next = Math.min(next, snoozeAt);
        }
        return next == Long.MAX_VALUE ? null : next;
    }

    private static String snoozePrefKey(String id) { return "snooze_due:" + id; }

    private static int snoozeRequestCode(String id) {
        return 7000 + (id != null ? (id.hashCode() & 0xfff) : 0);
    }

    /** Schedules a user-requested, visible reminder check without touching task alarms. */
    public static long scheduleDiagnosticReminder(Context context) {
        long at = System.currentTimeMillis() + 60_000L;
        return schedule(context, ACTION_DIAGNOSTIC_REMINDER, at, DIAGNOSTIC_NOTIFICATION_ID) ? at : 0L;
    }

    private static void postDiagnosticReminder(Context context) {
        createRemindChannel(context);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || !NotificationManagerCompat.from(context).areNotificationsEnabled()) return;
        NotificationCompat.Builder notification = new NotificationCompat.Builder(context, CHANNEL_REMIND)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Перевірка нагадувань")
                .setContentText("Тестове нагадування Потоку спрацювало")
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(FlowNotifService.openTaskPendingIntent(context, ""));
        try { manager.notify(DIAGNOSTIC_NOTIFICATION_ID, notification.build()); }
        catch (SecurityException ignored) { /* Permission may change after scheduling. */ }
    }

    /** Cancel the old hourly water alarm and notification left by earlier versions. */
    static void cancelLegacyWater(Context ctx) {
        Intent intent = new Intent(ctx, FlowAlarmReceiver.class);
        intent.setAction(LEGACY_ACTION_WATER);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent legacy = PendingIntent.getBroadcast(ctx, LEGACY_WATER_REQUEST_CODE, intent,
                immutable | PendingIntent.FLAG_NO_CREATE);
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (legacy != null) {
            if (am != null) am.cancel(legacy);
            legacy.cancel();
        }
        Intent oldIncrement = new Intent(ctx, FlowNotifService.class);
        oldIncrement.setAction(FlowNotifService.ACTION_RC_INC);
        int oldIncrementCode = (FlowNotifService.ACTION_RC_INC.hashCode() ^ LEGACY_WATER_NOTIF_ID) & 0x7fffffff;
        PendingIntent oldIncrementIntent = PendingIntent.getService(ctx, oldIncrementCode, oldIncrement,
                immutable | PendingIntent.FLAG_NO_CREATE);
        if (oldIncrementIntent != null) oldIncrementIntent.cancel();
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.cancel(LEGACY_WATER_NOTIF_ID);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) nm.deleteNotificationChannel(LEGACY_CHANNEL_WATER);
        }
    }

    /** Викликати при старті апки — запускає всі цикли */
    public static void scheduleAll(Context ctx) {
        if (!FlowDatabase.isStorageThread()) {
            Context appContext = ctx.getApplicationContext();
            FlowDatabase.executeStorage(() -> scheduleAll(appContext));
            return;
        }
        cancelLegacyWater(ctx);
        schedule(ctx, ACTION_SCHED_CHECK, System.currentTimeMillis() + 60_000L, 1);
        scheduleDailyReset(ctx);
        scheduleNextDue(ctx, new FlowBridge(ctx));
    }

    /** Запланувати окреме нагадування про відкладену задачу через delayMs (точний будильник) */
    public static void scheduleSnooze(Context ctx, String id, String title, long delayMs) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        long at = roundUpToMinute(System.currentTimeMillis() + Math.max(0L, delayMs));
        Intent i = createSnoozeIntent(ctx, id, title, at);
        int fImm = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        int rc = snoozeRequestCode(id);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, rc, i, fImm | PendingIntent.FLAG_UPDATE_CURRENT);
        try {
            if (Build.VERSION.SDK_INT >= 23) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
            else am.setExact(AlarmManager.RTC_WAKEUP, at, pi);
            ctx.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(snoozePrefKey(id), at).apply();
        } catch (SecurityException se) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                ctx.getSharedPreferences(ALARM_PREFS, Context.MODE_PRIVATE).edit()
                        .putLong(snoozePrefKey(id), at).apply();
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }
}
