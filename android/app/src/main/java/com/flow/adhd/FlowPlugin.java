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
import android.Manifest;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

@CapacitorPlugin(name = "FlowNotif", permissions = {
    @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
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
        ContextCompat.registerReceiver(getContext(), receiver,
                new IntentFilter("com.flow.adhd.FLOW_EVENT"), ContextCompat.RECEIVER_NOT_EXPORTED);
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

    @PluginMethod
    public void readEvents(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("events", new FlowBridge(getContext()).getEventEnvelopes());
        call.resolve(ret);
    }

    @PluginMethod
    public void notificationPermission(PluginCall call) {
        JSObject ret = new JSObject();
        boolean enabled = NotificationManagerCompat.from(getContext()).areNotificationsEnabled();
        String state = enabled ? "granted" : "denied";
        if (Build.VERSION.SDK_INT >= 33 && getPermissionState("notifications") == PermissionState.PROMPT) state = "prompt";
        ret.put("state", state);
        call.resolve(ret);
    }

    @PluginMethod
    public void requestNotificationPermission(PluginCall call) {
        if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
            notificationPermission(call);
        } else requestPermissionForAlias("notifications", call, "notificationPermissionResult");
    }

    @PermissionCallback
    private void notificationPermissionResult(PluginCall call) { notificationPermission(call); }

    @PluginMethod
    public void openNotificationSettings(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
        getActivity().startActivity(intent);
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
