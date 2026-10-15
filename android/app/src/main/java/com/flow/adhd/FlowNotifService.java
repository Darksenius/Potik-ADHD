package com.flow.adhd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import org.json.JSONArray;
import org.json.JSONObject;

public class FlowNotifService extends Service {

    public static final String CHANNEL_ID      = "flow_main";
    public static final String CHANNEL_TEMP     = "flow_temp";   // тимчасові (задачі/рутина)
    public static final int    NOTIF_ID         = 42;
    public static final int    NOTIF_TASK_BASE  = 1000;          // 1000..1004 — задачі
    public static final int    NOTIF_RC_BASE    = 2000;          // 2000..2009 — рутина
    public static final String TASK_REMINDER_TAG = "flow-task:";
    public static final String GROUP_TASKS      = "com.flow.adhd.GROUP_QUESTS";
    public static final String GROUP_RC         = "com.flow.adhd.GROUP_RC";

    public static final String ACTION_DONE       = "com.flow.adhd.ACTION_DONE";
    public static final String ACTION_CAPTURE_TASK = "com.flow.adhd.ACTION_CAPTURE_TASK";
    public static final String ACTION_CAPTURE_NOTE = "com.flow.adhd.ACTION_CAPTURE_NOTE";
    public static final String ACTION_SHOW_TASKS = "com.flow.adhd.ACTION_SHOW_TASKS";
    public static final String ACTION_SHOW_RC    = "com.flow.adhd.ACTION_SHOW_RC";
    public static final String ACTION_TASK_DONE  = "com.flow.adhd.ACTION_TASK_DONE";
    public static final String ACTION_TASK_SKIP  = "com.flow.adhd.ACTION_TASK_SKIP";
    public static final String ACTION_TASK_NEXT_ZONE = "com.flow.adhd.ACTION_TASK_NEXT_ZONE";
    public static final String ACTION_TASK_NOT_TODAY = "com.flow.adhd.ACTION_TASK_NOT_TODAY";
    public static final String ACTION_TASK_INC   = "com.flow.adhd.ACTION_TASK_INC";
    public static final String ACTION_TASK_DEC   = "com.flow.adhd.ACTION_TASK_DEC";
    public static final String ACTION_TASK_UNDO  = "com.flow.adhd.ACTION_TASK_UNDO";
    public static final String ACTION_RC_INC     = "com.flow.adhd.ACTION_RC_INC";
    public static final String ACTION_RC_DEC     = "com.flow.adhd.ACTION_RC_DEC";
    public static final String ACTION_RC_DONE    = "com.flow.adhd.ACTION_RC_DONE";
    public static final String ACTION_DISMISS    = "com.flow.adhd.ACTION_DISMISS";

    public static final String KEY_NOTE  = "flow_note_input";
    public static final String KEY_TASK  = "flow_task_input";
    public static final String EXTRA_ID  = "flow_item_id";
    public static final String EXTRA_KIND = "flow_kind"; // "tasks" | "rc"
    static final String EXTRA_ACTION_GENERATION = "flow_action_generation";
    private static final String EXTRA_UNDO_TOKEN = "flow_undo_token";
    public static final String FLOW_EVENT = "com.flow.adhd.FLOW_EVENT";

    private static final int  COLOR_FLOW   = 0xFF4f8ef7;
    private static final long REFRESH_MS   = 60_000L;  // оновлення основного щохвилини
    private static final long TEMP_MS      = 60_000L;  // тимчасові живуть 1 хв

    private FlowBridge bridge;
    private Handler    handler;
    private int        lastZoneColor = COLOR_FLOW;
    private String     captureReceipt = "";
    private long       captureReceiptAt = 0L;
    private volatile boolean destroyed;
    private final Object uiRefreshLock = new Object();
    private long uiRefreshBurstStartedAt = 0L;
    private final Map<Integer, Runnable> tempCancelCallbacks = new HashMap<>();
    private static final long UI_REFRESH_QUIET_MS = 1_500L;
    private static final long UI_REFRESH_MAX_BURST_MS = 4_000L;
    private static final long UNDO_WINDOW_MS = 10_000L;
    private static final String UNDO_PREFS = "flow_task_undo";
    private static final String[] UNDO_RUNTIME_FIELDS = {"done", "doneDate", "doneAt", "questDeferral",
            "completionDeferral", "snoozeUntil", "firedSched", "firedPre", "alarmFired", "completedToday",
            "zoneDoneToday", "nextRepeatAt", "occurrenceId", "completionCredited", "completionCreditDate"};
    private Runnable undoExpiryCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        destroyed = false;
        bridge = new FlowBridge(this);
        handler = new Handler(Looper.getMainLooper());
        // A foreground service must publish immediately; storage work is queued
        // on the shared FIFO lane and replaces this starter notification.
        createChannels();
        Notification bootstrap = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Потік")
                .setContentText("Оновлюю сповіщення…")
                .setOngoing(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
        startForeground(NOTIF_ID, bootstrap);
        handler.postDelayed(refreshTick, REFRESH_MS);
    }

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (destroyed) return;
            FlowDatabase.executeStorage(() -> {
                if (destroyed) return;
                try {
                    rebuildFromDb();
                    if (!destroyed && hasActiveQuestNotifications()) showTaskNotifications();
                } catch (Exception e) {
                    android.util.Log.w("FlowNotifService", "Periodic notification refresh failed", e);
                }
                if (!destroyed && handler != null) handler.postDelayed(refreshTick, REFRESH_MS);
            });
        }
    };

    /** One trailing projection refresh per burst; it reads current Room state and never replays commands. */
    private final Runnable coalescedUiRefresh = new Runnable() {
        @Override public void run() {
            synchronized (uiRefreshLock) { uiRefreshBurstStartedAt = 0L; }
            if (destroyed) return;
            FlowDatabase.executeStorage(() -> {
                if (destroyed) return;
                try {
                    rebuildFromDb();
                } catch (Exception e) {
                    android.util.Log.w("FlowNotifService", "Coalesced notification refresh failed", e);
                }
            });
        }
    };

    private void scheduleCoalescedUiRefresh() {
        if (destroyed || handler == null) return;
        long now = android.os.SystemClock.uptimeMillis();
        synchronized (uiRefreshLock) {
            if (destroyed || handler == null) return;
            if (uiRefreshBurstStartedAt == 0L) uiRefreshBurstStartedAt = now;
            long maxAt = uiRefreshBurstStartedAt + UI_REFRESH_MAX_BURST_MS;
            long dueAt = Math.min(now + UI_REFRESH_QUIET_MS, maxAt);
            handler.removeCallbacks(coalescedUiRefresh);
            handler.postDelayed(coalescedUiRefresh, Math.max(0L, dueAt - now));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Intent queuedIntent = intent == null ? null : new Intent(intent);
        FlowDatabase.executeStorage(() -> {
            try {
                handleStartCommand(queuedIntent);
            } catch (Exception e) {
                android.util.Log.w("FlowNotifService", "Notification command failed", e);
                try {
                    setCaptureReceipt("Відкрий Потік, щоб перевірити дію");
                    rebuildFromDb();
                } catch (Exception recoveryError) {
                    android.util.Log.w("FlowNotifService", "Unable to refresh after command failure", recoveryError);
                }
            } finally {
                scheduleCoalescedUiRefresh();
            }
        });
        return START_STICKY;
    }

    private void handleStartCommand(Intent intent) {
        if (intent == null) { rebuildFromDb(); return; }
        String action = intent.getAction();
        if (action == null) { rebuildFromDb(); return; }

        switch (action) {
            case ACTION_DONE:
                pushAndNotify("done_first");
                rebuildFromDb();
                break;

            case ACTION_CAPTURE_TASK:
            case ACTION_CAPTURE_NOTE: {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                boolean isTask = ACTION_CAPTURE_TASK.equals(action);
                CharSequence txt = results == null ? null : results.getCharSequence(isTask ? KEY_TASK : KEY_NOTE);
                String value = txt == null ? "" : txt.toString().trim();
                if (value.isEmpty()) {
                    setCaptureReceipt(isTask ? "Введи текст задачі" : "Введи текст нотатки");
                } else {
                    try {
                        if (isTask) {
                            String event = bridge.queueCapturedTask(value);
                            if (event == null) {
                                setCaptureReceipt("Не вдалося зберегти задачу");
                            } else {
                                setCaptureReceipt("Запис задачі збережено");
                                // queueCapturedTask already inserted this command in Room.
                                broadcastEvent(event);
                            }
                        } else {
                            String event = "capture_note:" + value;
                            bridge.pushEvent(event);
                            setCaptureReceipt("Нотатку збережено");
                            broadcastEvent(event);
                        }
                    } catch (Exception e) {
                        setCaptureReceipt("Не вдалося зберегти запис");
                    }
                }
                rebuildFromDb();
                if (isTask && !value.isEmpty()) showTaskNotifications();
                break;
            }

            case ACTION_SHOW_TASKS:
                rebuildFromDb();
                showTaskNotifications();
                break;

            case ACTION_SHOW_RC:
                rebuildFromDb();
                showRoutineNotifications();
                break;

            case ACTION_TASK_DONE: {
                invalidatePendingUndo(null);
                String id = intent.getStringExtra(EXTRA_ID);
                if (isTaskActionAvailable(id, intent.getStringExtra(EXTRA_ACTION_GENERATION))) {
                    QuestPolicy.Task task = findQuestTask(id);
                    long actionAt = System.currentTimeMillis();
                    JSONObject before = captureRuntimeBefore(task);
                    if (before != null) {
                        try { before.put("done", false); }
                        catch (Exception ignored) { before = null; }
                    }
                    String event = "task_done:" + id;
                    bridge.pushEventAt(event, actionAt);
                    if (before != null) storePendingUndo(task, "done", actionAt, before, findQuestTask(id));
                    broadcastEvent(event);
                    setCaptureReceipt("Виконано: " + receiptTaskTitle(task));
                    FlowAlarmReceiver.scheduleNextDue(this, bridge);
                }
                cancelTaskNotification(intent, id);
                rebuildFromDb();
                showTaskNotifications();
                break;
            }
            case ACTION_TASK_SKIP: // Older action IDs now map to the agreed "Не зараз" behavior.
            case ACTION_TASK_NEXT_ZONE:
            case ACTION_TASK_NOT_TODAY: {
                invalidatePendingUndo(null);
                String id = intent.getStringExtra(EXTRA_ID);
                if (isTaskActionAvailable(id, intent.getStringExtra(EXTRA_ACTION_GENERATION))) {
                    QuestPolicy.Task task = findQuestTask(id);
                    long actionAt = System.currentTimeMillis();
                    java.util.TimeZone zone = java.util.TimeZone.getDefault();
                    java.util.List<QuestPolicy.Segment> timeline = currentQuestTimeline();
                    QuestPolicy.Selection snapshot = currentQuestSelection(actionAt);
                    if (task != null && snapshot.valid) {
                        QuestPolicy.Deferral deferral = ACTION_TASK_NOT_TODAY.equals(action)
                                ? QuestPolicy.deferNotToday(task, timeline, actionAt, zone)
                                : QuestPolicy.deferNextZone(task, timeline, actionAt, zone);
                        if (deferral != null) {
                            JSONObject before = captureRuntimeBefore(task);
                            String event = deferral.asEvent(id);
                            bridge.pushEventAt(event, deferral.requestedAt);
                            if (before != null) storePendingUndo(task, "defer", deferral.requestedAt, before, findQuestTask(id));
                            broadcastEvent(event);
                            setCaptureReceipt(deferralReceipt(task, deferral));
                            // Replace any old date alarm immediately; the WebView may stay closed.
                            FlowAlarmReceiver.scheduleNextDue(this, bridge);
                        } else setCaptureReceipt("Відкрий Потік, щоб оновити зони");
                    } else {
                        setCaptureReceipt("Відкрий Потік, щоб оновити квести");
                    }
                }
                cancelTaskNotification(intent, id);
                rebuildFromDb();
                showTaskNotifications();
                break;
            }
            case ACTION_TASK_INC:
            case ACTION_TASK_DEC: {
                invalidatePendingUndo(null);
                String id = intent.getStringExtra(EXTRA_ID);
                JSONObject task = findCurrentTask(id);
                if (task != null && isCounterTask(task)
                        && isTaskActionAvailable(id, intent.getStringExtra(EXTRA_ACTION_GENERATION))) {
                    pushAndNotify((ACTION_TASK_INC.equals(action) ? "task_inc:" : "task_dec:") + id);
                } else {
                    cancelNotif(intent.getIntExtra("notif_id", -1));
                }
                rebuildFromDb();
                if (task != null && isCounterTask(task)) showTaskNotifications();
                break;
            }
            case ACTION_TASK_UNDO: {
                handleTaskUndo(intent);
                rebuildFromDb();
                showTaskNotifications();
                break;
            }
            case ACTION_RC_INC: {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) pushAndNotify("rc_inc:" + id);
                rebuildFromDb();
                refreshRoutineSingle(intent.getIntExtra("notif_id", -1), id);
                break;
            }
            case ACTION_RC_DEC: {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) pushAndNotify("rc_dec:" + id);
                rebuildFromDb();
                refreshRoutineSingle(intent.getIntExtra("notif_id", -1), id);
                break;
            }
            case ACTION_RC_DONE: {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) pushAndNotify("rc_done:" + id);
                cancelNotif(intent.getIntExtra("notif_id", -1));
                rebuildFromDb();
                break;
            }
            case ACTION_DISMISS:
                cancelNotif(intent.getIntExtra("notif_id", -1));
                break;

            default:
                rebuildFromDb();
        }
    }

    // ════════════════ ОСНОВНЕ СПОВІЩЕННЯ ════════════════

    private void rebuildFromDb() {
        if (destroyed) return;
        createChannels();
        String notifJson = bridge.getNotifJson();

        String zone = "Потік", slots = "", desc = "", tasks = "",
               routine = "", body = "Застосунок активний", time = "", zoneColor = "";
        int done = 0, total = 0, energy = 0;

        if (notifJson != null && !notifJson.isEmpty()) {
            try {
                JSONObject j = new JSONObject(notifJson);
                zone      = j.optString("zone", zone);
                slots     = j.optString("slots", slots);
                desc      = j.optString("desc", desc);
                tasks     = j.optString("tasks", tasks);
                routine   = j.optString("routine", routine);
                body      = j.optString("body", body);
                time      = j.optString("time", time);
                done      = j.optInt("done", done);
                total     = j.optInt("total", total);
                energy    = j.optInt("energy", energy);
                zoneColor = j.optString("zoneColor", "");

                // New payloads carry the resolved 35-day primary-zone timeline. Use
                // its current segment for the header as well as task eligibility, so
                // a plan-day override tomorrow cannot be replaced by today's baseline.
                long now = System.currentTimeMillis();
                JSONArray questTimelineRows = j.optJSONArray("questWallDays");
                if (questTimelineRows == null) questTimelineRows = j.optJSONArray("questTimeline");
                if (questTimelineRows != null) {
                    List<QuestPolicy.Segment> timeline = QuestPolicy.timelineForSnapshot(j, java.util.TimeZone.getDefault());
                    QuestPolicy.Segment current = QuestPolicy.segmentAt(timeline, now);
                    time = localTime(now);
                    if (current != null) {
                        JSONObject zoneInfo = findQuestZone(j.optJSONArray("questZones"), current.zoneId);
                        zone = current.zoneId == 0 ? "Вільний час"
                                : zoneInfo == null ? "Зона" : zoneInfo.optString("nm", "Зона");
                        desc = zoneInfo == null ? "" : zoneInfo.optString("desc", "");
                        zoneColor = current.zoneId == 0 ? "#888888"
                                : zoneInfo == null ? "" : zoneInfo.optString("color", "");
                        slots = localTime(current.startAt) + "–" + localTime(current.endAt);
                    } else {
                        // An expired or incomplete timeline must not borrow today's
                        // zone label and imply that its quest snapshot is current.
                        zone = "Потік"; slots = ""; desc = ""; zoneColor = "";
                    }
                } else {
                    // Legacy payload fallback only. The old compact timeline describes
                    // today's zones and cannot safely resolve a future local date.
                    String tlDate = j.optString("tlDate", "");
                    boolean fresh = todayStr().equals(tlDate);
                    JSONArray tl = fresh ? j.optJSONArray("zoneTimeline") : j.optJSONArray("zoneBaseline");
                    if (tl == null) tl = j.optJSONArray("zoneTimeline");
                    if (tl != null) {
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        int cur = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60
                                + cal.get(java.util.Calendar.MINUTE);
                        JSONObject best = null;
                        int bestPrio = -1;
                        for (int i = 0; i < tl.length(); i++) {
                            JSONObject seg = tl.optJSONObject(i);
                            if (seg == null) continue;
                            int s = toMin(seg.optString("s", ""));
                            int e = toMin(seg.optString("e", ""));
                            if (e == 0) e = 1440;
                            if (s < 0 || e < 0) continue;
                            int prio = seg.optInt("prio", 1);
                            if (cur >= s && cur < e && prio > bestPrio) { best = seg; bestPrio = prio; }
                        }
                        if (best != null) {
                            zone      = best.optString("nm", zone);
                            slots     = best.optString("s", "") + "–" + best.optString("e", "");
                            desc      = best.optString("desc", "");
                            zoneColor = best.optString("color", zoneColor);
                        } else {
                            zone = "Вільний час"; slots = ""; desc = "";
                        }
                        time = localTime(now);

                        JSONObject ztb = j.optJSONObject("zoneTasksById");
                        JSONArray  zl  = j.optJSONArray("zonelessTasks");
                        JSONArray  ur  = j.optJSONArray("urgentTasks");
                        if (ztb != null || zl != null || ur != null) {
                            StringBuilder sb = new StringBuilder();
                            int[] n = {0};
                            appendTasks(sb, ur, n);
                            if (best != null) {
                                int zid = best.optInt("id", 0);
                                if (zid != 0 && ztb != null) appendTasks(sb, ztb.optJSONArray(String.valueOf(zid)), n);
                            }
                            appendTasks(sb, zl, n);
                            tasks = sb.toString();
                        }
                    }
                }

                JSONArray sourceTaskList = j.optJSONArray("taskList");
                if (hasPendingTaskCommandsForRows(sourceTaskList)) {
                    JSONArray projectedTaskList = projectPendingTaskEvents(sourceTaskList);
                    tasks = applyPendingTaskBrief(tasks, sourceTaskList, projectedTaskList);
                    int pendingDone = pendingDoneCount(sourceTaskList);
                    if (pendingDone > 0) {
                        done = Math.min(total, done + pendingDone);
                        body = applyPendingDoneToBody(body, pendingDone);
                    }
                }
                QuestPolicy.Selection quests = currentQuestSelection(System.currentTimeMillis());
                if (quests.valid) {
                    tasks = quests.tasks.isEmpty() ? "" : questTaskBrief(quests.tasks);
                    body = questCountText(quests.tasks.size());
                } else {
                    tasks = quests.hint;
                    body = quests.hint;
                }
            } catch (Exception ignored) {}
        }
        lastZoneColor = parseZoneColor(zoneColor);
        buildMain(zone, slots, desc, tasks, routine, body, time, done, total, energy);
    }

    /** Додає назви задач із масиву до '|'-списку, не більше 7 загалом (n[0] — лічильник) */
    private void appendTasks(StringBuilder sb, JSONArray arr, int[] n) {
        if (arr == null) return;
        for (int i = 0; i < arr.length() && n[0] < 7; i++) {
            String t = arr.optString(i, "");
            if (t == null || t.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append("|");
            sb.append(t);
            n[0]++;
        }
    }

    private JSONObject findQuestZone(JSONArray zones, int id) {
        if (zones == null || id <= 0) return null;
        for (int i = 0; i < zones.length(); i++) {
            JSONObject zone = zones.optJSONObject(i);
            if (zone != null && zone.optInt("id", -1) == id) return zone;
        }
        return null;
    }

    private String localTime(long timestamp) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(timestamp);
        return String.format(java.util.Locale.US, "%02d:%02d",
                cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE));
    }

    private String questCountText(int count) {
        int lastTwo = count % 100;
        int last = count % 10;
        String noun = lastTwo >= 11 && lastTwo <= 14 ? "задач"
                : last == 1 ? "задача" : last >= 2 && last <= 4 ? "задачі" : "задач";
        return "Зараз: " + count + " " + noun;
    }

    /** "HH:MM" → хвилини доби; -1 якщо не парситься */
    private int toMin(String hm) {
        try {
            String[] p = hm.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) { return -1; }
    }

    private String todayStr() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        return String.format(java.util.Locale.US, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR),
                c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    private void buildMain(String zone, String slots, String desc, String tasks,
            String routine, String body, String time, int done, int total, int energy) {
        if (destroyed) return;

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("nav", "tasks");
        PendingIntent openPI = PendingIntent.getActivity(
                this, 0, openIntent, flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);

        String title = zone + (slots.isEmpty() ? "" : "  " + slots);
        String text  = (desc.isEmpty() ? "" : desc + "  •  ") + body;

        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        inbox.setBigContentTitle(title);
        inbox.setSummaryText(time.isEmpty() ? "Потік" : "Потік  " + time);
        String receipt = activeCaptureReceipt();
        if (!receipt.isEmpty()) inbox.addLine(receipt);
        if (desc != null && !desc.isEmpty()) {
            inbox.addLine(desc);
            inbox.addLine("─────────────────────────");
        }
        if (!tasks.isEmpty()) {
            for (String t : tasks.split("\\|"))
                if (!t.trim().isEmpty()) inbox.addLine("○  " + t.trim());
        } else {
            inbox.addLine("Зараз немає доречних квестів");
        }
        if (!routine.isEmpty()) {
            inbox.addLine("─────────────────────────");
            inbox.addLine(routine);
        }
        String[] eg = {"", "😴", "😐", "🙂", "😊", "🔥"};
        String energyStr = (energy > 0 && energy <= 5) ? "  " + eg[energy] : "";
        if (total > 0) {
            int pct = done * 100 / total;
            StringBuilder bar = new StringBuilder();
            for (int i = 0; i < 10; i++) bar.append(i < pct / 10 ? "█" : "░");
            inbox.addLine("─────────────────────────");
            inbox.addLine(bar + "  " + pct + "%" + energyStr);
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle(title)
                .setContentText(receipt.isEmpty() ? text : receipt)
                .setSubText("Потік")
                .setContentIntent(openPI)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(inbox)
                .addAction(buildCaptureAction(KEY_TASK, "+ Задача", "Назва задачі", ACTION_CAPTURE_TASK, 2))
                .addAction(buildCaptureAction(KEY_NOTE, "+ Нотатка", "Текст нотатки", ACTION_CAPTURE_NOTE, 4));

        UndoCapture undo = activeUndo();
        if (undo != null) {
            scheduleUndoExpiry(undo);
            b.addAction(buildUndoAction(undo));
        } else {
            b.addAction(actionService(android.R.drawable.ic_menu_agenda, "Розгорнути", ACTION_SHOW_TASKS, 3));
        }

        if (total > 0) b.setProgress(100, done * 100 / total, false);

        startForeground(NOTIF_ID, b.build());
    }

    // ════════════════ ТИМЧАСОВІ: ЗАДАЧІ ════════════════

    private void showTaskNotifications() {
        if (destroyed) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        // Clear two legacy slots from the older seven-card panel as well.
        pruneNotificationRange(NOTIF_TASK_BASE, 7);
        cancelNotif(NOTIF_TASK_BASE + 100);
        QuestPolicy.Selection selection = currentQuestSelection(System.currentTimeMillis());
        if (!selection.valid || selection.tasks.isEmpty()) {
            String hint = selection.valid ? "Немає доступних квестів зараз" : selection.hint;
            postTemp(NOTIF_TASK_BASE, GROUP_TASKS, "Квести", hint, null);
            scheduleTempCancel(NOTIF_TASK_BASE);
            return;
        }
        int count = selection.tasks.size();
        for (int i = 0; i < count; i++) {
            QuestPolicy.Task t = selection.tasks.get(i);
            String id = t.id;
            String ttl = t.title;
            int notifId  = NOTIF_TASK_BASE + i;
            String generation = actionGenerationForTask(t, System.currentTimeMillis());
            String content = t.counterEnabled
                    ? t.counter + " " + counterUnitLabel(t.counterUnit)
                        + (t.counterTarget > 0 ? " · ціль " + t.counterTarget + " " + counterUnitLabel(t.counterUnit) : "")
                    : "Квест " + (i + 1) + " із " + count;

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(lastZoneColor)
                    .setContentTitle((i + 1) + ".  " + ttl)
                    .setContentText(content)
                    .setGroup(GROUP_TASKS)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setTimeoutAfter(TEMP_MS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openTaskPendingIntent(this, id));
            b.addAction(actionItem(android.R.drawable.checkbox_on_background, "Виконано",
                            ACTION_TASK_DONE, id, notifId, generation))
                    .addAction(actionItem(android.R.drawable.ic_media_next, "Не зараз",
                            ACTION_TASK_NEXT_ZONE, id, notifId, generation))
                    .addAction(actionItem(android.R.drawable.ic_menu_close_clear_cancel, "Не сьогодні",
                            ACTION_TASK_NOT_TODAY, id, notifId, generation));

            nm.notify(notifId, b.build());
            scheduleTempCancel(notifId);
        }
        // summary для групи
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle("Квести")
                .setContentText(count + " доступних")
                .setGroup(GROUP_TASKS)
                .setGroupSummary(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(TEMP_MS);
        nm.notify(NOTIF_TASK_BASE + 100, summary.build());
        scheduleTempCancel(NOTIF_TASK_BASE + 100);
    }

    // ════════════════ ТИМЧАСОВІ: РУТИНА ════════════════

    private void showRoutineNotifications() {
        if (destroyed) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        pruneNotificationRange(NOTIF_RC_BASE, 10);
        cancelNotif(NOTIF_RC_BASE + 100);
        JSONArray arr = readArray("routineList");
        if (arr == null || arr.length() == 0) {
            postTemp(NOTIF_RC_BASE, GROUP_RC, "Рутина порожня", "Додай рутину в застосунку", null);
            scheduleTempCancel(NOTIF_RC_BASE);
            return;
        }
        int count = Math.min(arr.length(), 10);
        for (int i = 0; i < count; i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r == null) continue;
            postRoutineOne(nm, r, NOTIF_RC_BASE + i);
        }
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle("Рутина")
                .setContentText(count + " пунктів")
                .setGroup(GROUP_RC)
                .setGroupSummary(true)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(TEMP_MS);
        nm.notify(NOTIF_RC_BASE + 100, summary.build());
        scheduleTempCancel(NOTIF_RC_BASE + 100);
    }

    private void postRoutineOne(NotificationManager nm, JSONObject r, int notifId) {
        String id   = r.optString("id", "");
        String nm_  = r.optString("nm", "Рутина");
        String ico  = r.optString("ico", "");
        String unit = r.optString("unit", "");
        int    val  = r.optInt("val", 0);
        boolean isCheck = "check".equals(unit);
        boolean done = r.optBoolean("done", false);

        String unitLbl = "ml".equals(unit) ? "мл" : "min".equals(unit) ? "хв" : "";
        String valueText = isCheck ? (done ? "✓ виконано" : "○ не виконано")
                                    : (val + " " + unitLbl);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle((ico.isEmpty() ? "" : ico + " ") + nm_)
                .setContentText(valueText)
                .setGroup(GROUP_RC)
                .setOngoing(false)
                .setAutoCancel(false)
                .setSilent(true)
                .setTimeoutAfter(TEMP_MS)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(openTasksPI());

        if (isCheck) {
            b.addAction(actionItem(android.R.drawable.checkbox_on_background,
                    done ? "↺ Скинути" : "✓ Готово", ACTION_RC_DONE, id, notifId));
            b.addAction(actionItem(android.R.drawable.ic_menu_close_clear_cancel,
                    "Відкласти", ACTION_DISMISS, id, notifId));
        } else {
            b.addAction(actionItem(android.R.drawable.ic_input_add, "＋", ACTION_RC_INC, id, notifId));
            b.addAction(actionItem(android.R.drawable.ic_delete, "－", ACTION_RC_DEC, id, notifId));
            b.addAction(actionItem(android.R.drawable.ic_menu_close_clear_cancel,
                    "Відкласти", ACTION_DISMISS, id, notifId));
        }
        nm.notify(notifId, b.build());
        scheduleTempCancel(notifId);
    }

    /** Після +/− оновлюємо саме це сповіщення свіжим значенням з Room */
    private void refreshRoutineSingle(int notifId, String id) {
        if (destroyed) return;
        if (notifId < 0 || id == null) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        JSONArray arr = readArray("routineList");
        if (arr == null) return;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject r = arr.optJSONObject(i);
            if (r != null && id.equals(r.optString("id", ""))) {
                postRoutineOne(nm, r, notifId);
                return;
            }
        }
    }

    // ════════════════ ХЕЛПЕРИ ════════════════

    /** Знаходить назву задачі за id у знімку taskList (для тексту відкладеного нагадування) */
    private String findTaskTitle(String id) {
        JSONObject task = findCurrentTask(id);
        return task == null ? "Задача" : task.optString("title", "Задача");
    }

    private String receiptTaskTitle(QuestPolicy.Task task) {
        String title = task == null ? "Задачу" : task.title;
        if (title == null || title.trim().isEmpty()) title = "Задачу";
        title = title.trim();
        return title.length() > 80 ? title.substring(0, 77).trim() + "…" : title;
    }

    private String deferralReceipt(QuestPolicy.Task task, QuestPolicy.Deferral deferral) {
        String title = receiptTaskTitle(task);
        String verb = QuestPolicy.DEFERRAL_NOT_TODAY.equals(deferral.kind) ? "Не сьогодні" : "Не зараз";
        String target = "";
        if (QuestPolicy.DEFERRAL_NEXT_ZONE.equals(deferral.kind) && deferral.targetZoneId > 0) {
            JSONObject notif = readNotifObject();
            JSONObject zone = notif == null ? null : findQuestZone(notif.optJSONArray("questZones"), deferral.targetZoneId);
            if (zone != null) target = zone.optString("nm", "").trim();
        }
        String when = new java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.forLanguageTag("uk"))
                .format(new java.util.Date(deferral.startAt));
        if (!target.isEmpty()) return verb + ": «" + title + "» → " + target + " · " + when;
        return verb + ": «" + title + "» → " + when;
    }

    private boolean isTaskActionAvailable(String id, String expectedGeneration) {
        QuestPolicy.Task task = findQuestTask(id);
        if (task == null || "negative".equals(task.type) || task.trashed || task.someday) return false;
        long now = System.currentTimeMillis();
        java.util.TimeZone zone = java.util.TimeZone.getDefault();
        java.util.List<QuestPolicy.Segment> timeline = currentQuestTimeline();
        QuestPolicy.OccurrenceIndex occurrences = currentQuestOccurrences();
        if (QuestPolicy.isCurrentlyDone(task, now, timeline, zone, occurrences)) return false;
        if (expectedGeneration == null || expectedGeneration.isEmpty()) return false;
        return expectedGeneration.equals(QuestPolicy.actionGeneration(task, timeline, now, zone));
    }

    private QuestPolicy.Task findQuestTask(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONObject notif = readNotifObject();
        if (notif == null) return null;
        List<QuestPolicy.Task> source = QuestPolicy.tasksFromJson(notif.optJSONArray("questTasks"));
        List<QuestPolicy.Segment> timeline = QuestPolicy.timelineForSnapshot(notif, java.util.TimeZone.getDefault());
        QuestPolicy.OccurrenceIndex occurrences = QuestPolicy.OccurrenceIndex.fromSnapshot(
                notif, java.util.TimeZone.getDefault());
        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(source,
                QuestPolicy.eventsFromJson(readEventEnvelopes()), System.currentTimeMillis(), timeline,
                java.util.TimeZone.getDefault(), occurrences);
        for (QuestPolicy.Task task : projected) if (id.equals(task.id)) return task;
        return null;
    }

    private java.util.List<QuestPolicy.Segment> currentQuestTimeline() {
        JSONObject notif = readNotifObject();
        return notif == null ? java.util.Collections.<QuestPolicy.Segment>emptyList()
                : QuestPolicy.timelineForSnapshot(notif, java.util.TimeZone.getDefault());
    }

    private QuestPolicy.OccurrenceIndex currentQuestOccurrences() {
        JSONObject notif = readNotifObject();
        return QuestPolicy.OccurrenceIndex.fromSnapshot(notif, java.util.TimeZone.getDefault());
    }

    private QuestPolicy.Selection currentQuestSelection(long now) {
        JSONObject notif = readNotifObject();
        if (notif == null) return QuestPolicy.Selection.invalid("Відкрий Потік, щоб оновити квести");
        JSONArray rows = notif.optJSONArray("questTasks");
        JSONArray timelineRows = notif.optJSONArray("questWallDays");
        if (timelineRows == null) timelineRows = notif.optJSONArray("questTimeline");
        if (rows == null || timelineRows == null)
            return QuestPolicy.Selection.invalid("Відкрий Потік, щоб оновити квести");
        List<QuestPolicy.Segment> timeline = QuestPolicy.timelineForSnapshot(notif, java.util.TimeZone.getDefault());
        QuestPolicy.OccurrenceIndex occurrences = QuestPolicy.OccurrenceIndex.fromSnapshot(
                notif, java.util.TimeZone.getDefault());
        List<QuestPolicy.Task> source = QuestPolicy.tasksFromJson(rows);
        List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(source,
                QuestPolicy.eventsFromJson(readEventEnvelopes()), now, timeline,
                java.util.TimeZone.getDefault(), occurrences);
        return QuestPolicy.select(projected, timeline, QuestPolicy.timelineUntil(notif, timeline),
                now, java.util.TimeZone.getDefault(), QuestPolicy.MAX_CARDS, occurrences);
    }

    private String actionGenerationForTask(QuestPolicy.Task task, long now) {
        return QuestPolicy.actionGeneration(task, currentQuestTimeline(), now, java.util.TimeZone.getDefault());
    }

    static String actionGeneration(Context context, String taskId, long now) {
        if (taskId == null || taskId.isEmpty()) return "";
        try {
            FlowBridge bridge = new FlowBridge(context);
            String value = bridge.getNotifJson();
            if (value == null || value.isEmpty()) return "";
            JSONObject notif = new JSONObject(value);
            List<QuestPolicy.Segment> timeline = QuestPolicy.timelineForSnapshot(notif, java.util.TimeZone.getDefault());
            QuestPolicy.OccurrenceIndex occurrences = QuestPolicy.OccurrenceIndex.fromSnapshot(
                    notif, java.util.TimeZone.getDefault());
            List<QuestPolicy.Task> source = QuestPolicy.tasksFromJson(notif.optJSONArray("questTasks"));
            List<QuestPolicy.Task> projected = QuestPolicy.projectPendingEvents(source,
                    QuestPolicy.eventsFromJson(new JSONArray(bridge.getEventEnvelopes())), now, timeline,
                    java.util.TimeZone.getDefault(), occurrences);
            for (QuestPolicy.Task task : projected)
                if (taskId.equals(task.id)) return QuestPolicy.actionGeneration(task, timeline, now, java.util.TimeZone.getDefault());
        } catch (Exception ignored) { }
        return "";
    }

    private JSONObject readNotifObject() {
        try {
            String value = bridge.getNotifJson();
            return value == null || value.isEmpty() ? null : new JSONObject(value);
        } catch (Exception ignored) { return null; }
    }

    private JSONArray readEventEnvelopes() {
        try { return new JSONArray(bridge.getEventEnvelopes()); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private String questTaskBrief(java.util.List<QuestPolicy.Task> tasks) {
        StringBuilder result = new StringBuilder();
        if (tasks == null || tasks.isEmpty()) return "Немає доступних квестів зараз";
        for (QuestPolicy.Task task : tasks) {
            if (result.length() > 0) result.append('|');
            result.append(task.title == null || task.title.trim().isEmpty() ? "Задача" : task.title.trim());
            if (task.counterEnabled) result.append(" · ").append(task.counter).append(' ')
                    .append(counterUnitLabel(task.counterUnit));
        }
        return result.toString();
    }

    private boolean hasActiveQuestNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return false;
        try {
            for (android.service.notification.StatusBarNotification item : nm.getActiveNotifications()) {
                int id = item.getId();
                if (id >= NOTIF_TASK_BASE && id < NOTIF_TASK_BASE + QuestPolicy.MAX_CARDS) return true;
            }
        } catch (SecurityException ignored) {}
        return false;
    }

    private void cancelTaskNotification(Intent intent, String taskId) {
        cancelNotif(intent.getIntExtra("notif_id", -1));
        String tag = intent.getStringExtra("notif_tag");
        int id = intent.getIntExtra("notif_id", -1);
        if (tag != null && id >= 0) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(tag, id);
        }
        if (taskId != null && !taskId.isEmpty()) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(TASK_REMINDER_TAG + taskId, FlowAlarmReceiver.REMIND_NOTIFICATION_ID);
        }
    }

    private JSONArray projectPendingTaskEvents(JSONArray source) {
        if (source == null || source.length() == 0) return source;
        Map<String, JSONObject> tasks = new HashMap<>();
        Map<String, Long> deltas = new HashMap<>();
        Set<String> completedOrSkipped = new HashSet<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject task = source.optJSONObject(i);
            if (task != null) tasks.put(task.optString("id", ""), task);
        }
        try {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.optJSONObject(i);
                if (row == null) continue;
                String event = row.optString("event", "");
                String id;
                if (event.startsWith("task_done:")) {
                    id = event.substring("task_done:".length());
                    JSONObject task = tasks.get(id);
                    if (!isCalendarTask(task) || sameLocalDate(System.currentTimeMillis(), row.optLong("createdAt", 0L)))
                        completedOrSkipped.add(id);
                } else if (event.startsWith("task_skip:")) {
                    id = event.substring("task_skip:".length());
                    JSONObject task = tasks.get(id);
                    if (!isCalendarTask(task) || sameLocalDate(System.currentTimeMillis(), row.optLong("createdAt", 0L)))
                        completedOrSkipped.add(id);
                } else if (event.startsWith("task_inc:") || event.startsWith("task_dec:")) {
                    if (!sameLocalDate(System.currentTimeMillis(), row.optLong("createdAt", 0L))) continue;
                    boolean increment = event.startsWith("task_inc:");
                    id = event.substring(increment ? "task_inc:".length() : "task_dec:".length());
                    JSONObject task = tasks.get(id);
                    if (task == null || !isCounterTask(task) || completedOrSkipped.contains(id)) continue;
                    long step = Math.max(1, task.optInt("counterStep", 1));
                    long prior = deltas.containsKey(id) ? deltas.get(id) : 0L;
                    long delta = increment ? step : -step;
                    long next = prior + delta;
                    deltas.put(id, Math.max(-Integer.MAX_VALUE, Math.min(Integer.MAX_VALUE, next)));
                }
            }
        } catch (Exception ignored) {}

        JSONArray projected = new JSONArray();
        for (int i = 0; i < source.length(); i++) {
            JSONObject task = source.optJSONObject(i);
            if (task == null) continue;
            String id = task.optString("id", "");
            if (completedOrSkipped.contains(id) || !taskOccursToday(task)) continue;
            Long delta = deltas.get(id);
            if (isCounterTask(task)) {
                int current = Math.max(0, task.optInt("counter", 0));
                String counterDay = task.optString("counterDay", "");
                if (isCalendarTask(task) && !counterDay.isEmpty() && counterDay.compareTo(todayStr()) < 0) current = 0;
                if (delta != null) {
                long projectedValue = Math.max(0L, Math.min(Integer.MAX_VALUE, (long) current + delta));
                try { task.put("counter", (int) projectedValue); } catch (Exception ignored) {}
                } else if (current != task.optInt("counter", 0)) {
                    try { task.put("counter", current); } catch (Exception ignored) {}
                }
            }
            projected.put(task);
        }
        return projected;
    }

    private boolean hasPendingTaskCommandsForRows(JSONArray rows) {
        if (rows == null || rows.length() == 0) return false;
        Set<String> current = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject task = rows.optJSONObject(i);
            if (task != null) current.add(task.optString("id", ""));
        }
        try {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.optJSONObject(i);
                if (row == null) continue;
                String event = row.optString("event", "");
                int colon = event.indexOf(':');
                if (colon <= 0) continue;
                String kind = event.substring(0, colon);
                String id = event.substring(colon + 1);
                if (id.endsWith("_pre")) id = id.substring(0, id.length() - 4);
                if (current.contains(id) && ("task_inc".equals(kind) || "task_dec".equals(kind)
                        || "task_done".equals(kind) || "task_skip".equals(kind))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private int pendingDoneCount(JSONArray rows) {
        if (rows == null || rows.length() == 0) return 0;
        Set<String> current = new HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject task = rows.optJSONObject(i);
            if (task != null) current.add(task.optString("id", ""));
        }
        Set<String> doneIds = new HashSet<>();
        try {
            JSONArray events = new JSONArray(bridge.getEventEnvelopes());
            for (int i = 0; i < events.length(); i++) {
                JSONObject row = events.optJSONObject(i);
                if (row == null) continue;
                String event = row.optString("event", "");
                if (!event.startsWith("task_done:")) continue;
                String id = event.substring("task_done:".length());
                if (!current.contains(id)) continue;
                JSONObject task = null;
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject candidate = rows.optJSONObject(j);
                    if (candidate != null && id.equals(candidate.optString("id", ""))) { task = candidate; break; }
                }
                if (!isCalendarTask(task) || sameLocalDate(System.currentTimeMillis(), row.optLong("createdAt", 0L))) doneIds.add(id);
            }
        } catch (Exception ignored) {}
        return doneIds.size();
    }

    private String applyPendingTaskBrief(String zoneTasks, JSONArray source, JSONArray projected) {
        if (source == null || projected == null || zoneTasks == null || zoneTasks.isEmpty()) return zoneTasks;
        Map<String, JSONObject> byId = new HashMap<>();
        for (int i = 0; i < projected.length(); i++) {
            JSONObject task = projected.optJSONObject(i);
            if (task != null) byId.put(task.optString("id", ""), task);
        }
        Set<String> usedSourceIds = new HashSet<>();
        StringBuilder result = new StringBuilder();
        for (String title : zoneTasks.split("\\|")) {
            JSONObject sourceMatch = null;
            for (int i = 0; i < source.length(); i++) {
                JSONObject task = source.optJSONObject(i);
                if (task == null || usedSourceIds.contains(task.optString("id", ""))) continue;
                if (title.equals(task.optString("title", ""))) { sourceMatch = task; break; }
            }
            String shown = title;
            if (sourceMatch != null) {
                String id = sourceMatch.optString("id", "");
                usedSourceIds.add(id);
                JSONObject task = byId.get(id);
                if (task == null) continue;
                shown = task.optString("title", title);
                if (isCounterTask(task)) {
                    shown += " · " + Math.max(0, task.optInt("counter", 0)) + " "
                            + counterUnitLabel(task.optString("counterUnit", "count"));
                }
            }
            if (result.length() > 0) result.append('|');
            result.append(shown);
        }
        return result.toString();
    }

    private String applyPendingDoneToBody(String body, int count) {
        if (body == null || count <= 0) return body;
        java.util.regex.Matcher match = java.util.regex.Pattern
                .compile("^(.*?)(\\d+) активних · (\\d+) виконано$").matcher(body);
        if (!match.matches()) return body;
        int active = Integer.parseInt(match.group(2));
        int done = Integer.parseInt(match.group(3));
        return match.group(1) + Math.max(0, active - count) + " активних · " + (done + count) + " виконано";
    }

    private boolean sameLocalDate(long first, long second) {
        if (second <= 0L) return false;
        java.util.Calendar a = java.util.Calendar.getInstance();
        java.util.Calendar b = java.util.Calendar.getInstance();
        a.setTimeInMillis(first);
        b.setTimeInMillis(second);
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    private boolean isCalendarTask(JSONObject task) {
        if (task == null) return false;
        String repeat = task.optString("repeat", "none");
        return "daily".equals(repeat) || "weekly".equals(repeat) || "weekdays".equals(repeat)
                || "weekend".equals(repeat) || "custom".equals(repeat);
    }

    private boolean taskOccursToday(JSONObject task) {
        if (!isCalendarTask(task)) return true;
        String today = todayStr();
        String anchor = task.optString("anchorDate", "");
        if (!anchor.isEmpty() && today.compareTo(anchor) < 0) return false;
        java.util.Calendar now = java.util.Calendar.getInstance();
        int day = (now.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7; // Monday=0 .. Sunday=6
        switch (task.optString("repeat", "")) {
            case "daily": return true;
            case "weekly":
                if (anchor.isEmpty()) return true;
                try {
                    java.util.Calendar first = java.util.Calendar.getInstance();
                    first.clear();
                    String[] date = anchor.split("-");
                    first.set(Integer.parseInt(date[0]), Integer.parseInt(date[1]) - 1, Integer.parseInt(date[2]));
                    return first.get(java.util.Calendar.DAY_OF_WEEK) == now.get(java.util.Calendar.DAY_OF_WEEK);
                } catch (Exception ignored) { return false; }
            case "weekdays": return day < 5;
            case "weekend": return day >= 5;
            case "custom":
                JSONArray days = task.optJSONArray("repeatDays");
                return days != null && day < days.length() && days.optBoolean(day, false);
            default: return true;
        }
    }

    private JSONObject findCurrentTask(String id) {
        if (id == null || id.isEmpty()) return null;
        JSONArray arr = readArray("questTasks");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject task = arr.optJSONObject(i);
            if (task != null && id.equals(task.optString("id", ""))) return task;
        }
        arr = readArray("taskList");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject task = arr.optJSONObject(i);
            if (task != null && id.equals(task.optString("id", ""))) return task;
        }
        return null;
    }

    private boolean isCounterTask(JSONObject task) {
        return task != null && ("counter".equals(task.optString("type", ""))
                || task.optBoolean("counterEnabled", false));
    }

    private String counterUnitLabel(String unit) {
        if ("ml".equals(unit)) return "мл";
        if ("min".equals(unit)) return "хв";
        if ("kcal".equals(unit)) return "ккал";
        return "раз";
    }

    private JSONArray readArray(String key) {
        String notifJson = bridge.getNotifJson();
        if (notifJson == null || notifJson.isEmpty()) return null;
        try {
            JSONObject j = new JSONObject(notifJson);
            return j.optJSONArray(key);
        } catch (Exception e) { return null; }
    }

    private void postTemp(int notifId, String group, String title, String text, String unused) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle(title)
                .setContentText(text)
                .setGroup(group)
                .setSilent(true)
                .setAutoCancel(true)
                .setTimeoutAfter(TEMP_MS)
                .setContentIntent(openTasksPI());
        nm.notify(notifId, b.build());
    }

    private void scheduleTempCancel(final int notifId) {
        Runnable previous = tempCancelCallbacks.remove(notifId);
        if (previous != null && handler != null) handler.removeCallbacks(previous);
        Runnable callback = () -> {
            tempCancelCallbacks.remove(notifId);
            cancelNotif(notifId);
        };
        tempCancelCallbacks.put(notifId, callback);
        handler.postDelayed(callback, TEMP_MS);
    }

    private void cancelNotif(int notifId) {
        if (notifId < 0) return;
        Runnable callback = tempCancelCallbacks.remove(notifId);
        if (callback != null && handler != null) handler.removeCallbacks(callback);
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);
    }

    private void pruneNotificationRange(int base, int count) {
        for (int i = 0; i < count; i++) cancelNotif(base + i);
    }

    /**
     * Пише подію в Room (надійний резерв через polling) І одразу шле broadcast,
     * який FlowPlugin перетворює на flowEvent для JS — миттєво, без 3-сек затримки.
     * Якщо апка закрита — broadcast нікуди не дійде, але Room+polling спрацює при відкритті.
     */
    private void pushAndNotify(String event) {
        bridge.pushEvent(event);
        broadcastEvent(event);
    }

    private static final class UndoCapture {
        String token, id, created, kind, beforeJson, expectedAfterJson;
        long sourceAt, expiresAt;
    }

    private SharedPreferences undoPreferences() {
        return getSharedPreferences(UNDO_PREFS, Context.MODE_PRIVATE);
    }

    private UndoCapture activeUndo() {
        SharedPreferences prefs = undoPreferences();
        String token = prefs.getString("token", "");
        if (token == null || token.isEmpty()) return null;
        long expiresAt = prefs.getLong("expiresAt", 0L);
        if (expiresAt <= System.currentTimeMillis()) {
            invalidatePendingUndo(token);
            return null;
        }
        UndoCapture result = new UndoCapture();
        result.token = token;
        result.id = prefs.getString("id", "");
        result.created = prefs.getString("created", "");
        result.kind = prefs.getString("kind", "");
        result.sourceAt = prefs.getLong("sourceAt", 0L);
        result.expiresAt = expiresAt;
        result.beforeJson = prefs.getString("before", "");
        result.expectedAfterJson = prefs.getString("expectedAfter", "");
        if (result.id.isEmpty() || result.created.isEmpty() || result.beforeJson.isEmpty() || result.expectedAfterJson.isEmpty()
                || !("done".equals(result.kind) || "defer".equals(result.kind)) || result.sourceAt <= 0) {
            invalidatePendingUndo(token);
            return null;
        }
        return result;
    }

    private void storePendingUndo(QuestPolicy.Task task, String kind, long sourceAt, JSONObject before,
                                 QuestPolicy.Task expectedAfter) {
        if (task == null || expectedAfter == null || !task.id.equals(expectedAfter.id)
                || !task.created.equals(expectedAfter.created) || sourceAt <= 0 || before == null
                || sourceAt + UNDO_WINDOW_MS <= System.currentTimeMillis()) return;
        try {
            long id = Long.parseLong(task.id);
            if (id <= 0 || id >= 9_007_199_254_740_991L) return;
            String token = UUID.randomUUID().toString();
            long expiresAt = sourceAt + UNDO_WINDOW_MS;
            JSONObject expectedAfterJson = runtimeSnapshot(expectedAfter);
            boolean saved = undoPreferences().edit()
                    .putString("token", token).putString("id", task.id).putString("created", task.created)
                    .putString("kind", kind).putLong("sourceAt", sourceAt).putLong("expiresAt", expiresAt)
                    .putString("before", before.toString()).putString("expectedAfter", expectedAfterJson.toString()).commit();
            if (saved) {
                UndoCapture undo = activeUndo();
                if (undo != null) scheduleUndoExpiry(undo);
            }
        } catch (Exception ignored) {}
    }

    private void invalidatePendingUndo(String expectedToken) {
        SharedPreferences prefs = undoPreferences();
        if (expectedToken != null && !expectedToken.equals(prefs.getString("token", ""))) return;
        prefs.edit().clear().commit();
        if (handler != null && undoExpiryCallback != null) handler.removeCallbacks(undoExpiryCallback);
        undoExpiryCallback = null;
    }

    private void scheduleUndoExpiry(UndoCapture undo) {
        if (handler == null || undo == null) return;
        if (undoExpiryCallback != null) handler.removeCallbacks(undoExpiryCallback);
        final String token = undo.token;
        undoExpiryCallback = () -> FlowDatabase.executeStorage(() -> {
            UndoCapture current = activeUndo();
            if (current == null || !token.equals(current.token)) {
                scheduleCoalescedUiRefresh();
                return;
            }
            long remaining = current.expiresAt - System.currentTimeMillis();
            if (remaining > 0L) handler.postDelayed(undoExpiryCallback, remaining);
            else {
                invalidatePendingUndo(token);
                scheduleCoalescedUiRefresh();
            }
        });
        handler.postDelayed(undoExpiryCallback, Math.max(0L, undo.expiresAt - System.currentTimeMillis()));
    }

    private NotificationCompat.Action buildUndoAction(UndoCapture undo) {
        Intent intent = new Intent(this, FlowNotifService.class);
        intent.setAction(ACTION_TASK_UNDO);
        intent.setData(Uri.parse("flow://task/undo/" + Uri.encode(undo.token)));
        intent.putExtra(EXTRA_UNDO_TOKEN, undo.token);
        PendingIntent pending = PendingIntent.getService(this, 9910,
                intent, flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action.Builder(android.R.drawable.ic_menu_revert, "Скасувати", pending)
                .setShowsUserInterface(false).build();
    }

    private JSONObject captureRuntimeBefore(QuestPolicy.Task effectiveTask) {
        if (effectiveTask == null || effectiveTask.id.isEmpty() || effectiveTask.created.isEmpty()) return null;
        JSONObject notif = readNotifObject();
        JSONArray rows = notif == null ? null : notif.optJSONArray("questTasks");
        JSONObject source = null;
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject candidate = rows.optJSONObject(i);
            if (candidate != null && effectiveTask.id.equals(candidate.optString("id", ""))) {
                source = candidate; break;
            }
        }
        if (source == null) {
            source = new JSONObject();
            try {
                source.put("id", effectiveTask.id);
                source.put("created", effectiveTask.created);
                source.put("title", effectiveTask.title);
                source.put("type", effectiveTask.type);
            } catch (Exception ignored) { return null; }
        }
        if (!effectiveTask.created.equals(source.optString("created", ""))) return null;
        QuestPolicy.Task rawTask = QuestPolicy.Task.fromJson(source);
        JSONObject before = new JSONObject();
        try {
            for (String field : UNDO_RUNTIME_FIELDS) {
                before.put(field, source.has(field) && !source.isNull(field) ? source.get(field) : JSONObject.NULL);
            }
            putProjectedBoolean(before, "done", rawTask.done, effectiveTask.done);
            putProjectedString(before, "doneDate", rawTask.doneDate, effectiveTask.doneDate);
            putProjectedString(before, "doneAt", rawTask.doneAt, effectiveTask.doneAt);
            putProjectedDeferral(before, "questDeferral", rawTask.deferral, effectiveTask.deferral);
            putProjectedDeferral(before, "completionDeferral", rawTask.completionDeferral, effectiveTask.completionDeferral);
            putProjectedLong(before, "snoozeUntil", rawTask.snoozeUntil, effectiveTask.snoozeUntil);
            putProjectedBoolean(before, "firedSched", rawTask.firedSched, effectiveTask.firedSched);
            putProjectedBoolean(before, "firedPre", rawTask.firedPre, effectiveTask.firedPre);
            putProjectedBoolean(before, "alarmFired", rawTask.alarmFired, effectiveTask.alarmFired);
            putProjectedBoolean(before, "completedToday", rawTask.completedToday, effectiveTask.completedToday);
            putProjectedBoolean(before, "zoneDoneToday", rawTask.zoneDoneToday, effectiveTask.zoneDoneToday);
            putProjectedLong(before, "nextRepeatAt", rawTask.nextRepeatAt, effectiveTask.nextRepeatAt);
            putProjectedString(before, "occurrenceId", rawTask.occurrenceId, effectiveTask.occurrenceId);
            putProjectedBoolean(before, "completionCredited", rawTask.completionCredited, effectiveTask.completionCredited);
            putProjectedString(before, "completionCreditDate", rawTask.completionCreditDate, effectiveTask.completionCreditDate);
            return before;
        } catch (Exception ignored) { return null; }
    }

    private static void putProjectedBoolean(JSONObject before, String key, boolean raw, boolean effective) throws Exception {
        if (raw != effective) before.put(key, effective);
    }
    private static void putProjectedLong(JSONObject before, String key, long raw, long effective) throws Exception {
        if (raw != effective) before.put(key, effective);
    }
    private static void putProjectedString(JSONObject before, String key, String raw, String effective) throws Exception {
        if (!java.util.Objects.equals(raw, effective)) before.put(key, effective == null || effective.isEmpty() ? JSONObject.NULL : effective);
    }
    private static void putProjectedDeferral(JSONObject before, String key, QuestPolicy.Deferral raw,
                                             QuestPolicy.Deferral effective) throws Exception {
        String rawJson = raw == null ? "" : raw.toJson();
        String effectiveJson = effective == null ? "" : effective.toJson();
        if (!rawJson.equals(effectiveJson)) before.put(key,
                effective == null ? JSONObject.NULL : new JSONObject(effectiveJson));
    }

    private void handleTaskUndo(Intent intent) {
        UndoCapture undo = activeUndo();
        String token = intent.getStringExtra(EXTRA_UNDO_TOKEN);
        long now = System.currentTimeMillis();
        if (undo == null || token == null || !token.equals(undo.token)
                || now < undo.sourceAt || now > undo.expiresAt) {
            setCaptureReceipt("Цю дію вже не можна скасувати");
            return;
        }
        QuestPolicy.Task task = findQuestTask(undo.id);
        JSONObject row = findQuestTaskRow(undo.id);
        if (task == null || !undo.created.equals(task.created)
                || row != null && (!undo.created.equals(row.optString("created", ""))
                    || row.optBoolean("trashed", false) || row.optBoolean("someday", false))) {
            invalidatePendingUndo(token);
            setCaptureReceipt("Задача вже змінилася");
            return;
        }
        String currentRuntime;
        try { currentRuntime = runtimeSnapshot(task).toString(); }
        catch (Exception ignored) { currentRuntime = ""; }
        if (!undo.expectedAfterJson.equals(currentRuntime)) {
            invalidatePendingUndo(token);
            setCaptureReceipt("Задача вже змінилася");
            return;
        }
        boolean expected = "done".equals(undo.kind)
                ? task.done && undo.sourceAt <= now && QuestPolicy.localDate(undo.sourceAt, java.util.TimeZone.getDefault()).equals(task.doneDate)
                    && (task.doneAt.isEmpty() || task.doneAt.equals(QuestPolicy.localTime(undo.sourceAt, java.util.TimeZone.getDefault())))
                : task.deferral != null && task.deferral.requestedAt == undo.sourceAt;
        if (!expected) {
            invalidatePendingUndo(token);
            setCaptureReceipt("Задача вже змінилася");
            return;
        }
        try {
            long numericId = Long.parseLong(undo.id);
            JSONObject payload = new JSONObject();
            payload.put("id", numericId);
            payload.put("created", undo.created);
            payload.put("kind", undo.kind);
            payload.put("sourceAt", undo.sourceAt);
            payload.put("undoneAt", now);
            payload.put("before", new JSONObject(undo.beforeJson));
            String event = "task_undo:" + payload.toString();
            bridge.pushEventAt(event, now);
            invalidatePendingUndo(token);
            broadcastEvent(event);
            setCaptureReceipt("Скасовано: " + receiptTaskTitle(task));
            FlowAlarmReceiver.scheduleNextDue(this, bridge);
        } catch (Exception e) {
            setCaptureReceipt("Не вдалося скасувати дію");
        }
    }

    private static JSONObject runtimeSnapshot(QuestPolicy.Task task) throws Exception {
        JSONObject snapshot = new JSONObject();
        for (String field : UNDO_RUNTIME_FIELDS) {
            Object value;
            switch (field) {
                case "done": value = task.done; break;
                case "doneDate": value = task.doneDate; break;
                case "doneAt": value = task.doneAt; break;
                case "questDeferral": value = task.deferral == null ? JSONObject.NULL : new JSONObject(task.deferral.toJson()); break;
                case "completionDeferral": value = task.completionDeferral == null ? JSONObject.NULL : new JSONObject(task.completionDeferral.toJson()); break;
                case "snoozeUntil": value = task.snoozeUntil; break;
                case "firedSched": value = task.firedSched; break;
                case "firedPre": value = task.firedPre; break;
                case "alarmFired": value = task.alarmFired; break;
                case "completedToday": value = task.completedToday; break;
                case "zoneDoneToday": value = task.zoneDoneToday; break;
                case "nextRepeatAt": value = task.nextRepeatAt; break;
                case "occurrenceId": value = task.occurrenceId; break;
                case "completionCredited": value = task.completionCredited; break;
                case "completionCreditDate": value = task.completionCreditDate; break;
                default: throw new IllegalArgumentException("Unknown undo runtime field");
            }
            snapshot.put(field, value);
        }
        return snapshot;
    }

    private JSONObject findQuestTaskRow(String id) {
        JSONObject notif = readNotifObject();
        JSONArray rows = notif == null ? null : notif.optJSONArray("questTasks");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row != null && id != null && id.equals(row.optString("id", ""))) return row;
        }
        return null;
    }

    private void broadcastEvent(String event) {
        Intent b = new Intent(FLOW_EVENT);
        b.putExtra("event", event);
        b.setPackage(getPackageName());
        try { sendBroadcast(b); } catch (Exception ignored) { /* Room already holds the durable command. */ }
    }

    private PendingIntent openTasksPI() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("nav", "tasks");
        return PendingIntent.getActivity(this, 0, openIntent,
                flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    public static PendingIntent openTaskPendingIntent(Context context, String taskId) {
        Intent openIntent = new Intent(context, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("nav", "tasks");
        openIntent.putExtra("openTaskId", taskId);
        // Extras do not participate in PendingIntent identity. Include the
        // full task ID in data so distinct task cards (including colliding
        // String.hashCode() IDs) keep separate open intents on Android 9+.
        openIntent.setData(android.net.Uri.parse("flow://task/open/"
                + android.net.Uri.encode(taskId == null ? "" : taskId)));
        return PendingIntent.getActivity(context, 0, openIntent,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NotificationCompat.Action buildCaptureAction(String inputKey, String actionLabel, String inputLabel, String action, int requestCode) {
        RemoteInput remoteInput = new RemoteInput.Builder(inputKey)
                .setLabel(inputLabel)
                .build();
        Intent captureIntent = new Intent(this, FlowNotifService.class);
        captureIntent.setAction(action);
        PendingIntent capturePI = PendingIntent.getService(
                this, requestCode, captureIntent, flagMutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, actionLabel, capturePI)
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build();
    }

    private void setCaptureReceipt(String receipt) {
        captureReceipt = receipt;
        captureReceiptAt = android.os.SystemClock.elapsedRealtime();
        if (handler == null || destroyed) return;
        handler.postDelayed(() -> {
            if (destroyed) return;
            FlowDatabase.executeStorage(() -> {
                if (destroyed) return;
                if (android.os.SystemClock.elapsedRealtime() - captureReceiptAt >= 30_000L) {
                    captureReceipt = "";
                    scheduleCoalescedUiRefresh();
                }
            });
        }, 30_000L);
    }

    private String activeCaptureReceipt() {
        if (!captureReceipt.isEmpty()
                && android.os.SystemClock.elapsedRealtime() - captureReceiptAt < 30_000L) return captureReceipt;
        captureReceipt = "";
        return "";
    }

    private NotificationCompat.Action actionService(int icon, String label, String action, int rc) {
        Intent i = new Intent(this, FlowNotifService.class);
        i.setAction(action);
        PendingIntent pi = PendingIntent.getService(this, rc, i,
                flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action.Builder(icon, label, pi)
                .setShowsUserInterface(false).build();
    }

    private NotificationCompat.Action actionItem(int icon, String label, String action,
                                                 String itemId, int notifId) {
        return taskAction(this, icon, label, action, itemId, null, notifId);
    }

    private NotificationCompat.Action actionItem(int icon, String label, String action,
                                                 String itemId, int notifId, String generation) {
        return taskAction(this, icon, label, action, itemId, null, notifId, generation);
    }

    public static NotificationCompat.Action taskAction(Context context, int icon, String label,
                                                        String action, String itemId, String notifTag, int notifId) {
        return taskAction(context, icon, label, action, itemId, notifTag, notifId, "");
    }

    public static NotificationCompat.Action taskAction(Context context, int icon, String label,
                                                        String action, String itemId, String notifTag, int notifId,
                                                        String generation) {
        Intent i = new Intent(context, FlowNotifService.class);
        i.setAction(action);
        i.setData(android.net.Uri.parse("flow://quest/" + android.net.Uri.encode(action) + "/" + android.net.Uri.encode(itemId == null ? "" : itemId)
                + "/" + android.net.Uri.encode(notifTag == null ? "" : notifTag) + "/" + notifId
                + "/" + android.net.Uri.encode(generation == null ? "" : generation)));
        i.putExtra(EXTRA_ID, itemId);
        i.putExtra("notif_id", notifId);
        i.putExtra(EXTRA_ACTION_GENERATION, generation == null ? "" : generation);
        if (notifTag != null) i.putExtra("notif_tag", notifTag);
        // requestCode унікальний: action.hashCode ^ notifId ^ itemId.hashCode
        int rc = (action.hashCode() ^ notifId ^ (itemId == null ? 0 : itemId.hashCode())) & 0x7fffffff;
        PendingIntent pi = PendingIntent.getService(context, rc, i,
                (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0) | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action.Builder(icon, label, pi)
                .setShowsUserInterface(false).build();
    }

    private int flagImmutable() { return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0; }
    private int flagMutable()   { return Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE   : 0; }

    private int parseZoneColor(String hex) {
        if (hex != null && !hex.isEmpty()) {
            try { return android.graphics.Color.parseColor(hex); }
            catch (Exception ignored) {}
        }
        return COLOR_FLOW;
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Потік — активний", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Зона, задачі та рутина");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
            if (nm.getNotificationChannel(CHANNEL_TEMP) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_TEMP, "Потік — швидкі дії", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Тимчасові сповіщення задач і рутини");
                ch.setShowBadge(false);
                ch.enableVibration(false);
                ch.setSound(null, null);
                nm.createNotificationChannel(ch);
            }
        }
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public void onDestroy() {
        destroyed = true;
        synchronized (uiRefreshLock) { uiRefreshBurstStartedAt = 0L; }
        if (handler != null) handler.removeCallbacksAndMessages(null);
        stopForeground(true);
        super.onDestroy();
    }

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, FlowNotifService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(i);
        else
            ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, FlowNotifService.class));
    }
}
