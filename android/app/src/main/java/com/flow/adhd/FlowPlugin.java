package com.flow.adhd;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

@CapacitorPlugin(name = "FlowNotif")
public class FlowPlugin extends Plugin {

    private BroadcastReceiver receiver;

    @Override
    public void load() {
        // Слухаємо події від FlowNotifService (кнопки сповіщення)
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String event = intent.getStringExtra("event");
                if (event == null) return;
                JSObject data = new JSObject();
                data.put("event", event);
                notifyListeners("flowEvent", data);
            }
        };
        getContext().registerReceiver(receiver,
                new IntentFilter("com.flow.adhd.FLOW_EVENT"));
    }

    /** JS викликає щоб запустити/оновити сповіщення */
    @PluginMethod
    public void start(PluginCall call) {
        FlowNotifService.start(getContext());
        JSObject ret = new JSObject();
        ret.put("started", true);
        call.resolve(ret);
    }

    @PluginMethod
    public void update(PluginCall call) {
        FlowNotifService.start(getContext()); // re-start = update
        call.resolve();
    }

    @PluginMethod
    public void stop(PluginCall call) {
        FlowNotifService.stop(getContext());
        call.resolve();
    }

    /**
     * JS викликає це на broadcast-сигнал, щоб НЕГАЙНО вичитати чергу подій з Room.
     * Той самий шлях, що й 3-сек polling у MainActivity — Room лишається єдиним
     * джерелом, дублів немає (clearEvents всередині getEvents-циклу).
     */
    @PluginMethod
    public void drainEvents(PluginCall call) {
        FlowBridge bridge = new FlowBridge(getContext());
        String eventsJson = bridge.getEvents();
        JSObject ret = new JSObject();
        if ("[]".equals(eventsJson)) {
            ret.put("events", "[]");
            call.resolve(ret);
            return;
        }
        bridge.clearEvents();
        ret.put("events", eventsJson);
        call.resolve(ret);
    }

    @Override
    protected void handleOnDestroy() {
        if (receiver != null) {
            try { getContext().unregisterReceiver(receiver); }
            catch (Exception ignored) {}
        }
    }
}
