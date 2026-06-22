package com.flow.adhd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
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
    public static final String GROUP_TASKS      = "com.flow.adhd.GROUP_TASKS";
    public static final String GROUP_RC         = "com.flow.adhd.GROUP_RC";

    public static final String ACTION_DONE       = "com.flow.adhd.ACTION_DONE";
    public static final String ACTION_NOTE       = "com.flow.adhd.ACTION_NOTE";
    public static final String ACTION_SHOW_TASKS = "com.flow.adhd.ACTION_SHOW_TASKS";
    public static final String ACTION_SHOW_RC    = "com.flow.adhd.ACTION_SHOW_RC";
    public static final String ACTION_TASK_DONE  = "com.flow.adhd.ACTION_TASK_DONE";
    public static final String ACTION_TASK_SKIP  = "com.flow.adhd.ACTION_TASK_SKIP";
    public static final String ACTION_RC_INC     = "com.flow.adhd.ACTION_RC_INC";
    public static final String ACTION_RC_DEC     = "com.flow.adhd.ACTION_RC_DEC";
    public static final String ACTION_RC_DONE    = "com.flow.adhd.ACTION_RC_DONE";
    public static final String ACTION_DISMISS    = "com.flow.adhd.ACTION_DISMISS";

    public static final String KEY_NOTE  = "flow_note_input";
    public static final String EXTRA_ID  = "flow_item_id";
    public static final String EXTRA_KIND = "flow_kind"; // "tasks" | "rc"
    public static final String FLOW_EVENT = "com.flow.adhd.FLOW_EVENT";

    private static final int  COLOR_FLOW   = 0xFF4f8ef7;
    private static final long REFRESH_MS   = 60_000L;  // оновлення основного щохвилини
    private static final long TEMP_MS      = 60_000L;  // тимчасові живуть 1 хв

    private FlowBridge bridge;
    private Handler    handler;
    private int        lastZoneColor = COLOR_FLOW;

    @Override
    public void onCreate() {
        super.onCreate();
        bridge = new FlowBridge(this);
        handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(refreshTick, REFRESH_MS);
    }

    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            rebuildFromDb();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) { rebuildFromDb(); return START_STICKY; }
        String action = intent.getAction();
        if (action == null) { rebuildFromDb(); return START_STICKY; }

        switch (action) {
            case ACTION_DONE:
                pushAndNotify("done_first");
                rebuildFromDb();
                break;

            case ACTION_NOTE: {
                Bundle results = RemoteInput.getResultsFromIntent(intent);
                if (results != null) {
                    CharSequence txt = results.getCharSequence(KEY_NOTE);
                    if (txt != null && txt.length() > 0)
                        pushAndNotify("note:" + txt.toString());
                }
                rebuildFromDb();
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
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) pushAndNotify("task_done:" + id);
                cancelNotif(intent.getIntExtra("notif_id", -1));
                rebuildFromDb();
                break;
            }
            case ACTION_TASK_SKIP: {
                String id = intent.getStringExtra(EXTRA_ID);
                if (id != null) {
                    pushAndNotify("task_skip:" + id);
                    // «Відкласти» → окреме нагадування через 1 годину (навіть коли апка закрита)
                    FlowAlarmReceiver.scheduleSnooze(this, id, findTaskTitle(id), 60 * 60 * 1000L);
                }
                cancelNotif(intent.getIntExtra("notif_id", -1));
                rebuildFromDb();
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
        return START_STICKY;
    }

    // ════════════════ ОСНОВНЕ СПОВІЩЕННЯ ════════════════

    private void rebuildFromDb() {
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

                // ── Поточна зона + задачі під неї, перераховані щохвилини ──
                // Знімок міг бути збережений давно; зона в ньому застаріває. tlDate==сьогодні
                // → беремо повний таймлайн (із денними винятками плану); інакше (після
                // опівночі, апку не відкривали) → базовий таймлайн зон, що повторюються щодня.
                String tlDate = j.optString("tlDate", "");
                boolean fresh = todayStr().equals(tlDate);
                JSONArray tl = fresh ? j.optJSONArray("zoneTimeline") : j.optJSONArray("zoneBaseline");
                if (tl == null) tl = j.optJSONArray("zoneTimeline"); // запасний варіант
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
                    time = String.format(java.util.Locale.US, "%02d:%02d",
                            cal.get(java.util.Calendar.HOUR_OF_DAY),
                            cal.get(java.util.Calendar.MINUTE));

                    // Перебудувати список задач під поточну зону: термінові + задачі
                    // цієї зони + позазональні (до 7). Працює і при закритій апці.
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
        if (desc != null && !desc.isEmpty()) {
            inbox.addLine(desc);
            inbox.addLine("─────────────────────────");
        }
        if (!tasks.isEmpty()) {
            for (String t : tasks.split("\\|"))
                if (!t.trim().isEmpty()) inbox.addLine("○  " + t.trim());
        } else {
            inbox.addLine("✓  Всі задачі виконано");
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
                .setContentText(text)
                .setSubText("Потік")
                .setContentIntent(openPI)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setStyle(inbox)
                .addAction(buildNoteAction())
                .addAction(actionService(android.R.drawable.ic_menu_agenda, "☑ Задачі", ACTION_SHOW_TASKS, 3))
                .addAction(actionService(android.R.drawable.ic_menu_rotate, "🔁 Рутина", ACTION_SHOW_RC, 4));

        if (total > 0) b.setProgress(100, done * 100 / total, false);

        startForeground(NOTIF_ID, b.build());
    }

    // ════════════════ ТИМЧАСОВІ: ЗАДАЧІ ════════════════

    private void showTaskNotifications() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        JSONArray arr = readArray("taskList");
        if (arr == null || arr.length() == 0) {
            // нема задач — підказка
            postTemp(NOTIF_TASK_BASE, GROUP_TASKS, "✓ Задач немає",
                    "Всі активні задачі виконано", null);
            scheduleTempCancel(NOTIF_TASK_BASE);
            return;
        }
        int count = Math.min(arr.length(), 7);
        for (int i = 0; i < count; i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t == null) continue;
            String id    = t.optString("id", "");
            String ttl   = t.optString("title", "Задача");
            String type  = t.optString("type", "simple");
            int notifId  = NOTIF_TASK_BASE + i;

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setColor(lastZoneColor)
                    .setContentTitle((i + 1) + ".  " + ttl)
                    .setContentText("Зона: задача #" + (i + 1))
                    .setGroup(GROUP_TASKS)
                    .setOngoing(false)
                    .setAutoCancel(true)
                    .setSilent(true)
                    .setTimeoutAfter(TEMP_MS)
                    .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                    .setContentIntent(openTasksPI())
                    .addAction(actionItem(android.R.drawable.checkbox_on_background, "✓ Виконати",
                            ACTION_TASK_DONE, id, notifId))
                    .addAction(actionItem(android.R.drawable.ic_media_next, "→ Відкласти",
                            ACTION_TASK_SKIP, id, notifId));

            nm.notify(notifId, b.build());
            scheduleTempCancel(notifId);
        }
        // summary для групи
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this, CHANNEL_TEMP)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(lastZoneColor)
                .setContentTitle("Задачі зони")
                .setContentText(count + " активних")
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
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
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
        JSONArray arr = readArray("taskList");
        if (arr == null || id == null) return "Задача";
        for (int i = 0; i < arr.length(); i++) {
            JSONObject t = arr.optJSONObject(i);
            if (t != null && id.equals(t.optString("id", ""))) return t.optString("title", "Задача");
        }
        return "Задача";
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
        handler.postDelayed(new Runnable() {
            @Override public void run() { cancelNotif(notifId); }
        }, TEMP_MS);
    }

    private void cancelNotif(int notifId) {
        if (notifId < 0) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(notifId);
    }

    /**
     * Пише подію в Room (надійний резерв через polling) І одразу шле broadcast,
     * який FlowPlugin перетворює на flowEvent для JS — миттєво, без 3-сек затримки.
     * Якщо апка закрита — broadcast нікуди не дійде, але Room+polling спрацює при відкритті.
     */
    private void pushAndNotify(String event) {
        bridge.pushEvent(event);
        Intent b = new Intent(FLOW_EVENT);
        b.putExtra("event", event);
        b.setPackage(getPackageName());
        sendBroadcast(b);
    }

    private PendingIntent openTasksPI() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        openIntent.putExtra("nav", "tasks");
        return PendingIntent.getActivity(this, 0, openIntent,
                flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private NotificationCompat.Action buildNoteAction() {
        RemoteInput remoteInput = new RemoteInput.Builder(KEY_NOTE)
                .setLabel("... задача або нотатка")
                .build();
        Intent noteIntent = new Intent(this, FlowNotifService.class);
        noteIntent.setAction(ACTION_NOTE);
        PendingIntent notePI = PendingIntent.getService(
                this, 2, noteIntent, flagMutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Action.Builder(
                android.R.drawable.ic_menu_send, "⚡ Нотатка", notePI)
                .addRemoteInput(remoteInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build();
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
        Intent i = new Intent(this, FlowNotifService.class);
        i.setAction(action);
        i.putExtra(EXTRA_ID, itemId);
        i.putExtra("notif_id", notifId);
        // requestCode унікальний: action.hashCode ^ notifId ^ itemId.hashCode
        int rc = (action.hashCode() ^ notifId ^ (itemId == null ? 0 : itemId.hashCode())) & 0x7fffffff;
        PendingIntent pi = PendingIntent.getService(this, rc, i,
                flagImmutable() | PendingIntent.FLAG_UPDATE_CURRENT);
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
        if (handler != null) handler.removeCallbacks(refreshTick);
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
