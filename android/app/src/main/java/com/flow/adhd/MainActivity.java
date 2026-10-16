package com.flow.adhd;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import android.content.Intent;
import android.widget.Toast;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    private static final String STATE_SHARE_QUEUED = "flow_share_queued";
    private static final String EXTRA_SHARE_QUEUED = "com.flow.adhd.SHARE_QUEUED";
    private static final int MAX_SHARE_CHARS = 12_000;
    private static final int MAX_SHARE_SUBJECT_CHARS = 500;

    private FlowBridge flowBridge;
    private Handler    pollHandler;
    private WebView    webView;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(FlowPlugin.class);
        super.onCreate(savedInstanceState);

        flowBridge = new FlowBridge(this);

        // Додаємо JS-міст після ініціалізації WebView
        getBridge().getWebView().addJavascriptInterface(flowBridge, "FlowBridge");

        // Стартуємо сервіс сповіщень
        FlowNotifService.start(this);

        // Плануємо будильники: sched-нагадування + щоденний скид о 00:10
        FlowAlarmReceiver.scheduleAll(this);

        // Кожні 3 сек перевіряємо чи є події від сповіщення
        pollHandler = new Handler(Looper.getMainLooper());
        pollHandler.post(eventPoller);

        // Якщо відкрили з сповіщення — перейти на задачі
        handleIncomingIntent(getIntent(), savedInstanceState != null
                && savedInstanceState.getBoolean(STATE_SHARE_QUEUED, false));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent, false);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        Intent intent = getIntent();
        outState.putBoolean(STATE_SHARE_QUEUED,
                intent != null && intent.getBooleanExtra(EXTRA_SHARE_QUEUED, false));
        super.onSaveInstanceState(outState);
    }

    private void handleIncomingIntent(Intent intent, boolean restoredShareWasQueued) {
        handleNotifIntent(intent);
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || !"text/plain".equals(intent.getType())) return;
        if (restoredShareWasQueued || intent.getBooleanExtra(EXTRA_SHARE_QUEUED, false)) return;

        String body = shareText(intent.getCharSequenceExtra(Intent.EXTRA_TEXT));
        String rawSubject = shareText(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT));
        boolean truncated = false;
        String subject = rawSubject;
        if (subject.length() > MAX_SHARE_SUBJECT_CHARS) {
            subject = truncateShareText(subject, MAX_SHARE_SUBJECT_CHARS);
            truncated = true;
        }
        String captured = body;
        if (!subject.isEmpty()) {
            captured = body.isEmpty() ? subject : subject + "\n\n" + body;
        }
        if (captured.length() > MAX_SHARE_CHARS) truncated = true;
        if (truncated) {
            String notice = "\n\n[Довгий текст скорочено…]";
            captured = truncateShareText(captured, MAX_SHARE_CHARS - notice.length()) + notice;
        } else {
            captured = truncateShareText(captured, MAX_SHARE_CHARS);
        }
        if (captured.isEmpty() || flowBridge == null) return;

        // pushEvent is synchronous on Room's serialized storage lane. The share is
        // durable before the WebView is asked to drain events or the process can exit.
        String event = "share_text:" + captured;
        try {
            flowBridge.pushEvent(event);
            intent.putExtra(EXTRA_SHARE_QUEUED, true);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Unable to queue shared text", e);
            Toast.makeText(this, "Не вдалося зберегти спільний текст. Спробуйте ще раз.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent wake = new Intent(FlowNotifService.FLOW_EVENT);
            wake.putExtra("event", event);
            wake.setPackage(getPackageName());
            sendBroadcast(wake);
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "Unable to notify the app about shared text", e);
        }
    }

    private static String shareText(CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }

    private static String truncateShareText(String text, int maxChars) {
        if (text.length() <= maxChars) return text;
        int end = Math.max(0, maxChars);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).trim();
    }

    private void handleNotifIntent(android.content.Intent intent) {
        if (intent != null) {
            String taskId = intent.getStringExtra("openTaskId");
            if (taskId != null && !taskId.trim().isEmpty() && taskId.indexOf(':') < 0) {
                // Navigation is durable through Room if the WebView is not ready yet.
                // The JS bridge handles focus; native never mutates the task itself.
                String event = "open_task:" + taskId.trim();
                FlowDatabase.executeStorage(() -> flowBridge.pushEvent(event));
            }
        }
        queueInsightOpenIntent(flowBridge, intent);
        if (intent != null && "tasks".equals(intent.getStringExtra("nav"))) {
            android.os.Handler h = new android.os.Handler(Looper.getMainLooper());
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    getBridge().getWebView().evaluateJavascript(
                        "if(typeof swTab==='function')swTab('tasks',document.getElementById('nav-main'))", null);
                }
            }, 600);
        }
    }

    static boolean queueInsightOpenIntent(FlowBridge bridge, Intent intent) {
        if (bridge == null || intent == null) return false;
        String day = intent.getStringExtra("openInsightDay");
        if (!isIsoLocalDate(day)) return false;
        // Room keeps the navigation durable if the WebView is not ready yet.
        String event = "open_insight:" + day;
        FlowDatabase.executeStorage(() -> bridge.pushEvent(event));
        return true;
    }
    private static boolean isIsoLocalDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return false;
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        format.setLenient(false);
        try {
            java.util.Date parsed = format.parse(value);
            return parsed != null && value.equals(format.format(parsed));
        } catch (java.text.ParseException ignored) {
            return false;
        }
    }
    /** Last native snapshot save error for the settings diagnostics screen. */
    public String getLastSaveError() {
        return flowBridge == null ? "" : flowBridge.getLastSaveError();
    }

    private final Runnable eventPoller = new Runnable() {
        @Override
        public void run() {
            checkPendingEvents();
            pollHandler.postDelayed(this, 10000); // fallback; миттєвість через broadcast→drainEvents
        }
    };

    private void checkPendingEvents() {
        // Делегуємо вичитування в JS-дренаж (FP.drainEvents): він читає чергу Room
        // і очищає її атомарно та ЛИШЕ коли JS уже готовий. Раніше тут чергу
        // очищали ДО готовності JS (на холодному старті) — і нотатки зі шторки
        // зникали. Якщо JS ще не готовий, __flowDrainEvents відсутній → подія
        // лишається в Room до наступного тіку.
        try {
            getBridge().getWebView().evaluateJavascript(
                "window.__flowDrainEvents && window.__flowDrainEvents()", null);
        } catch (Exception ignored) {}
    }

    @Override
    public void onStop() {
        if (flowBridge != null) flowBridge.flushBackup();
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (pollHandler != null) pollHandler.removeCallbacks(eventPoller);
        super.onDestroy();
    }
}
