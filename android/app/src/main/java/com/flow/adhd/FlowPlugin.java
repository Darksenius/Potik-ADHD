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
import android.app.Activity;
import android.net.Uri;
import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import androidx.core.content.ContextCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import com.getcapacitor.annotation.ActivityCallback;

@CapacitorPlugin(name = "FlowNotif", permissions = {
    @Permission(alias = "notifications", strings = { Manifest.permission.POST_NOTIFICATIONS })
})
public class FlowPlugin extends Plugin {

    private BroadcastReceiver receiver;
    private volatile boolean backupPickerActive;

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
        Context appContext = getContext().getApplicationContext();
        FlowDatabase.executeStorage(() -> {
            try {
                JSObject ret = new JSObject();
                ret.put("events", new FlowBridge(appContext).getEventEnvelopes());
                resolveOnMain(call, ret);
            } catch (Exception e) {
                rejectOnMain(call, "Unable to read queued events");
            }
        });
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

    /** Read-only native scheduling and permission state for the settings diagnostics UI. */
    @PluginMethod
    public void notificationDiagnostics(PluginCall call) {
        Context context = getContext();
        boolean notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled();
        Boolean reminderChannelEnabled = notificationChannelEnabled(context, FlowAlarmReceiver.CHANNEL_REMIND);
        Boolean panelChannelEnabled = notificationChannelEnabled(context, FlowNotifService.CHANNEL_ID);
        Boolean quickActionsChannelEnabled = notificationChannelEnabled(context, FlowNotifService.CHANNEL_TEMP);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        boolean exactAlarmsAllowed = Build.VERSION.SDK_INT < 31
                || (alarms != null && alarms.canScheduleExactAlarms());
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        boolean batteryExempt = power != null && power.isIgnoringBatteryOptimizations(context.getPackageName());
        Long nextReminderAt = FlowAlarmReceiver.nextScheduledReminderAt(context);
        String lastSaveError = getActivity() instanceof MainActivity
                ? ((MainActivity) getActivity()).getLastSaveError() : "";

        JSObject result = new JSObject();
        result.put("notificationsEnabled", notificationsEnabled);
        result.put("reminderChannelEnabled", (Object) reminderChannelEnabled);
        result.put("panelChannelEnabled", (Object) panelChannelEnabled);
        result.put("quickActionsChannelEnabled", (Object) quickActionsChannelEnabled);
        result.put("exactAlarmsAllowed", exactAlarmsAllowed);
        result.put("batteryOptimizationExempt", batteryExempt);
        if (nextReminderAt == null) result.put("nextReminderAt", (Object) null);
        else result.put("nextReminderAt", nextReminderAt.longValue());
        result.put("lastSaveError", lastSaveError);
        result.put("androidVersion", Build.VERSION.SDK_INT);
        call.resolve(result);
    }

    /** Null means the channel is not present or the platform state could not be read. */
    private static Boolean notificationChannelEnabled(Context context, String channelId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Boolean.TRUE;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return null;
        NotificationChannel channel = manager.getNotificationChannel(channelId);
        return channel == null ? null : channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
    }

    /** Open the exact-alarm special access page only on Android 12 and newer. */
    @PluginMethod
    public void openExactAlarmSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT < 31) {
            JSObject result = new JSObject();
            result.put("opened", false);
            call.resolve(result);
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
            intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            JSObject result = new JSObject();
            result.put("opened", true);
            call.resolve(result);
        } catch (Exception e) {
            call.reject("Unable to open exact-alarm settings");
        }
    }

    /** Open app-specific settings; this never requests or forces battery exemption. */
    @PluginMethod
    public void openBatterySettings(PluginCall call) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(android.net.Uri.parse("package:" + getContext().getPackageName()));
            getActivity().startActivity(intent);
            call.resolve();
        } catch (Exception e) {
            call.reject("Unable to open app battery settings");
        }
    }

    /** Add a visible diagnostic notification in about one minute, if native scheduling works. */
    @PluginMethod
    public void scheduleDiagnosticReminder(PluginCall call) {
        long at = FlowAlarmReceiver.scheduleDiagnosticReminder(getContext());
        JSObject result = new JSObject();
        result.put("scheduled", at > 0L);
        if (at > 0L) result.put("firesAt", at);
        else result.put("firesAt", (Object) null);
        call.resolve(result);
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
        Context appContext = getContext().getApplicationContext();
        FlowDatabase.executeStorage(() -> {
            try {
                FlowBridge bridge = new FlowBridge(appContext);
                String eventsJson = bridge.getEvents();
                if (!"[]".equals(eventsJson)) bridge.clearEvents();
                JSObject ret = new JSObject();
                ret.put("events", eventsJson);
                resolveOnMain(call, ret);
            } catch (Exception e) {
                rejectOnMain(call, "Unable to drain queued events");
            }
        });
    }

    /** Ask the user to select one folder for portable backup or restore. */
    @PluginMethod
    public void chooseBackupFolder(PluginCall call) {
        if (backupPickerActive) { call.reject("A backup folder picker is already open"); return; }
        String purpose = call.getString("purpose", "backup");
        if (!"backup".equals(purpose) && !"restore".equals(purpose)) {
            call.reject("Invalid backup folder purpose");
            return;
        }
        backupPickerActive = true;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        try {
            startActivityForResult(call, intent, "backupFolderResult");
        } catch (Exception e) {
            backupPickerActive = false;
            call.reject("Unable to open folder picker");
        }
    }

    @ActivityCallback
    private void backupFolderResult(PluginCall call, ActivityResult result) {
        if (call == null) { backupPickerActive = false; return; }
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null
                || result.getData().getData() == null) {
            backupPickerActive = false;
            JSObject cancelled = new JSObject();
            cancelled.put("cancelled", true);
            call.resolve(cancelled);
            return;
        }
        Uri tree = result.getData().getData();
        String purpose = call.getString("purpose", "backup");
        FlowBackupStore store = FlowBackupStore.get(getContext());
        store.chooseFolder(tree, purpose, selected -> {
            backupPickerActive = false;
            if (!selected.success) {
                rejectOnMain(call, "Не вдалося прочитати вибрану папку резервної копії.");
                return;
            }
            JSObject response = new JSObject();
            response.put("cancelled", false);
            response.put("folderName", selected.folderName);
            response.put("hasBackup", selected.hasBackup);
            if ("restore".equals(purpose) && selected.hasBackup) response.put("raw", selected.raw);
            resolveOnMain(call, response);
        });
    }

    @PluginMethod
    public void backupStatus(PluginCall call) {
        call.resolve(backupStatusObject());
    }

    @PluginMethod
    public void flushBackup(PluginCall call) {
        FlowBackupStore.get(getContext()).flush(() -> resolveBackupStatus(call));
    }

    @PluginMethod
    public void confirmBackupReplacement(PluginCall call) {
        FlowBackupStore.get(getContext()).confirmReplacement(() -> resolveBackupStatus(call));
    }

    private void resolveBackupStatus(PluginCall call) {
        resolveOnMain(call, backupStatusObject());
    }

    private JSObject backupStatusObject() {
        org.json.JSONObject status = FlowBackupStore.get(getContext()).status();
        JSObject result = new JSObject();
        result.put("configured", status.optBoolean("configured", false));
        result.put("folderName", status.optString("folderName", ""));
        result.put("lastSuccessfulWrite", status.optLong("lastSuccessfulWrite", 0L));
        String error = status.optString("lastError", "");
        switch (error) {
            case "replacement_confirmation_required": error = "Файл резервної копії вже є в папці. Підтвердьте заміну після відновлення або окремо."; break;
            case "folder_access_failed": error = "Не вдалося отримати доступ до вибраної папки."; break;
            case "internal_write_failed": error = "Не вдалося зберегти внутрішню копію на пристрої."; break;
            case "saf_write_failed": error = "Не вдалося оновити копію у вибраній папці."; break;
            case "saf_read_failed": error = "Не вдалося прочитати копію з вибраної папки."; break;
            case "hash_failed": error = "Не вдалося перевірити цілісність резервної копії."; break;
        }
        result.put("lastError", error);
        return result;
    }

    private static void resolveOnMain(PluginCall call, JSObject result) {
        new Handler(Looper.getMainLooper()).post(() -> call.resolve(result));
    }

    private static void rejectOnMain(PluginCall call, String message) {
        new Handler(Looper.getMainLooper()).post(() -> call.reject(message));
    }

    @Override
    protected void handleOnDestroy() {
        if (receiver != null) {
            try { getContext().unregisterReceiver(receiver); }
            catch (Exception ignored) {}
        }
    }
}
