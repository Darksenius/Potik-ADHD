package com.flow.adhd;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

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
        handleNotifIntent(getIntent());
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleNotifIntent(intent);
    }

    private void handleNotifIntent(android.content.Intent intent) {
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
    public void onDestroy() {
        if (pollHandler != null) pollHandler.removeCallbacks(eventPoller);
        super.onDestroy();
    }
}
