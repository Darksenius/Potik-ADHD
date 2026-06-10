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
        String eventsJson = flowBridge.getEvents();
        if (eventsJson.equals("[]")) return;
        flowBridge.clearEvents();
        // Передаємо події в JS
        String js = "if(typeof handleNativeEvent==='function'){" +
                    "var evts=" + eventsJson + ";" +
                    "evts.forEach(function(e){handleNativeEvent(e);});" +
                    "}";
        getBridge().getWebView().evaluateJavascript(js, null);
    }

    @Override
    public void onDestroy() {
        if (pollHandler != null) pollHandler.removeCallbacks(eventPoller);
        super.onDestroy();
    }
}
