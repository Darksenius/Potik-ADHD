package com.flow.adhd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.AtomicFile;
import androidx.core.content.FileProvider;
import com.flow.adhd.domain.TaskTitleSchedule;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.HashMap;
import java.util.Map;

/** Native platform facade for FIFO event capture, recovery archives and file export. */
public class FlowBridge {

    private static final long MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L;
    private static final long MAX_CAPTURED_TASK_ID = MAX_SAFE_JS_INTEGER - 1L;

    private final FlowDao dao;
    private final Context ctx;
    private final RecoveryArchiveStore recoveryArchiveStore;
    // Injection is retained for recovery-archive tests and legacy read-only import.
    private final File publicBackupOverride;
    private final File privateBackupOverride;

    private static <T> T callStorage(Callable<T> work) {
        return FlowDatabase.callStorage(work);
    }

    public FlowBridge(Context context) {
        this(context, FlowDatabase.getInstance(context).flowDao(), null, null);
    }

    FlowBridge(Context context, FlowDao dao, File publicBackupDir, File privateBackupDir) {
        ctx = context.getApplicationContext();
        this.dao = dao;
        publicBackupOverride = publicBackupDir;
        privateBackupOverride = privateBackupDir;
        recoveryArchiveStore = new RecoveryArchiveStore(ctx.getFilesDir());
    }

    /** Preserve all recovery inputs in a new archive; never alters Room or backups. */
    public boolean archiveRecovery(String suppliedJson) {
        return callStorage(() -> archiveRecoveryOnStorage(suppliedJson));
    }

    private boolean archiveRecoveryOnStorage(String suppliedJson) {
        try {
            String originalState = dao.getStateJson();
            String originalNotif = dao.getNotifJson();
            Map<String, byte[]> backups = new HashMap<>();
            backups.put("public", readRawBackup(new File(getLegacyPublicBackupDir(), FlowBackupStore.BACKUP_FILE)));
            backups.put("private", readRawBackup(new File(getLegacyPrivateBackupDir(), FlowBackupStore.BACKUP_FILE)));
            backups.put("internal", readRawBackup(new File(new File(ctx.getFilesDir(), "backups"), FlowBackupStore.BACKUP_FILE)));
            return recoveryArchiveStore.archive(suppliedJson, originalState, originalNotif, backups);
        } catch (Exception e) {
            android.util.Log.w("FlowBridge", "Recovery archive failed", e);
            return false;
        }
    }

    /** Returns the newest readable archive as JSON, or an empty string. */
    public String readRecoveryArchive() {
        return callStorage(recoveryArchiveStore::readLatest);
    }

    /** Metadata for readable immutable recovery archives, newest first. */
    public String listRecoveryArchives() {
        return callStorage(recoveryArchiveStore::list);
    }

    /** Read one archive by its generated filename only; reject traversal and paths. */
    public String readRecoveryArchiveFile(String name) {
        return callStorage(() -> recoveryArchiveStore.read(name));
    }

    private byte[] readRawBackup(File file) throws Exception {
        if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return null;
        return new AtomicFile(file).readFully();
    }

    /** window.FlowBridge.load() — повертає повний стан */
    public String load() {
        return callStorage(() -> {
            String s = dao.getStateJson();
            return s != null ? s : "";
        });
    }

    // id останньої прочитаної події — щоб clearEvents() видаляв лише її і старіші,
    // а не події, що прилетіли вже після читання (інакше нотатка зі шторки губиться)
    /** Read bounded FIFO envelopes without acknowledging them. */
    public String getEventEnvelopes() {
        return callStorage(() -> {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (PendingEvent e : dao.getPendingEvents()) {
                if (arr.length() >= 500) break;
                try {
                    org.json.JSONObject value = new org.json.JSONObject();
                    value.put("id", e.id);
                    value.put("event", e.event == null ? "" : e.event);
                    value.put("createdAt", e.createdAt);
                    arr.put(value);
                } catch (org.json.JSONException ignored) {}
            }
            return arr.toString();
        });
    }

    /** Durable event capture; the native repository owns application and acknowledgement. */
    public void pushEvent(String event) {
        callStorage(() -> { dao.insertEvent(new PendingEvent(event)); return null; });
        NativeRuntime.requestRefresh(ctx);
    }

    /** Queues an event with the action timestamp carried by its payload. */
    public void pushEventAt(String event, long createdAt) {
        callStorage(() -> {
            PendingEvent queued = new PendingEvent(event);
            queued.createdAt = createdAt;
            dao.insertEvent(queued);
            return null;
        });
        NativeRuntime.requestRefresh(ctx);
    }

    /** Durably queues a quick-task capture with a stable ID for the native repository to apply once. */
    public String queueCapturedTask(String title) {
        if (title == null) throw new IllegalArgumentException("Введи назву задачі");
        String validationError = TaskTitleSchedule.captureValidationError(title);
        if (validationError != null) throw new IllegalArgumentException(validationError);
        String queuedEvent = callStorage(() -> {
            long createdAt = System.currentTimeMillis();
            try {
                String stateJson = dao.getStateJson();
                org.json.JSONObject state = new org.json.JSONObject(
                        stateJson == null || stateJson.isEmpty() ? "{}" : stateJson);
                long taskId = allocateCapturedTaskId(createdAt, state.optLong("nid", 0L),
                        state.optJSONArray("tasks"), dao.getPendingEvents());
                if (taskId <= 0 || taskId > MAX_CAPTURED_TASK_ID) return null;

                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("title", title);
                payload.put("createdAt", createdAt);
                String event = "capture_task_v2:" + taskId + ":" + payload.toString();
                PendingEvent queued = new PendingEvent(event);
                queued.createdAt = createdAt;
                dao.insertEvent(queued);
                return event;
            } catch (Exception e) {
                android.util.Log.w("FlowBridge", "Quick task queue failed", e);
                return null;
            }
        });
        if (queuedEvent != null) NativeRuntime.requestRefresh(ctx);
        return queuedEvent;
    }

    static long allocateCapturedTaskId(long now, long savedNextId,
                                       org.json.JSONArray savedTasks, List<PendingEvent> pendingEvents) {
        return allocateCapturedTaskId(now, savedNextId, savedTaskIds(savedTasks), pendingCaptureTaskIds(pendingEvents));
    }

    static long allocateCapturedTaskId(long now, long savedNextId,
                                       long[] savedTaskIds, long[] pendingCaptureTaskIds) {
        long fromClock = now > 0 && now <= MAX_SAFE_JS_INTEGER / 1024L
                ? now * 1024L : MAX_SAFE_JS_INTEGER + 1L;
        long next = Math.max(fromClock, Math.max(1L, savedNextId));
        if (savedTaskIds != null) for (long id : savedTaskIds)
            if (id > 0 && id <= MAX_SAFE_JS_INTEGER) next = Math.max(next, id + 1L);
        if (pendingCaptureTaskIds != null) for (long id : pendingCaptureTaskIds)
            if (id > 0 && id <= MAX_SAFE_JS_INTEGER) next = Math.max(next, id + 1L);
        return next <= MAX_CAPTURED_TASK_ID ? next : -1L;
    }

    private static long[] savedTaskIds(org.json.JSONArray savedTasks) {
        if (savedTasks == null) return new long[0];
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < savedTasks.length(); i++) {
            org.json.JSONObject task = savedTasks.optJSONObject(i);
            long id = task == null ? -1L : safeNumericId(task.opt("id"));
            if (id > 0) ids.add(id);
        }
        return toLongArray(ids);
    }

    private static long[] pendingCaptureTaskIds(List<PendingEvent> pendingEvents) {
        if (pendingEvents == null) return new long[0];
        List<Long> ids = new ArrayList<>();
        for (PendingEvent event : pendingEvents) {
            long id = captureTaskId(event == null ? null : event.event);
            if (id > 0) ids.add(id);
        }
        return toLongArray(ids);
    }

    private static long[] toLongArray(List<Long> values) {
        long[] result = new long[values.size()];
        for (int i = 0; i < values.size(); i++) result[i] = values.get(i);
        return result;
    }

    private static long safeNumericId(Object value) {
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || number <= 0 || number > MAX_SAFE_JS_INTEGER || number != Math.rint(number)) return -1L;
            return (long) number;
        }
        if (value instanceof String) {
            try {
                long id = Long.parseLong((String) value);
                return id > 0 && id <= MAX_SAFE_JS_INTEGER ? id : -1L;
            } catch (NumberFormatException ignored) {}
        }
        return -1L;
    }

    private static long captureTaskId(String event) {
        String prefix = "capture_task_v2:";
        if (event == null || !event.startsWith(prefix)) return -1L;
        int separator = event.indexOf(':', prefix.length());
        if (separator <= prefix.length()) return -1L;
        return safeNumericId(event.substring(prefix.length(), separator));
    }

    public String getNotifJson() {
        return callStorage(() -> {
            String s = dao.getNotifJson();
            return s != null ? s : "";
        });
    }

    /** Historical locations are kept read-only so older installs can recover existing files. */
    private File getLegacyPublicBackupDir() {
        if (publicBackupOverride != null) return publicBackupOverride;
        return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "FLOW");
    }

    private File getLegacyPrivateBackupDir() {
        if (privateBackupOverride != null) return privateBackupOverride;
        File base = ctx.getExternalFilesDir(null);
        return new File(base != null ? base : ctx.getFilesDir(), "FLOW-backup");
    }

    /** Export an explicit owner-requested file through the Android share sheet. */
    public boolean exportTxt(String filename, String content) {
        try {
            File dir = new File(ctx.getCacheDir(), "exports");
            if (!dir.exists()) dir.mkdirs();
            String safeName = filename != null && !filename.isEmpty() ? new File(filename).getName() : "flow-export.txt";
            if (".".equals(safeName) || "..".equals(safeName)) safeName = "flow-export.txt";
            File f = new File(dir, safeName);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes("UTF-8"));
            fos.close();

            Uri uri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", f);

            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.putExtra(Intent.EXTRA_SUBJECT, "FLOW — експорт");
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Intent chooser = Intent.createChooser(share, "Зберегти / надіслати");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(chooser);
            return true;
        } catch (Exception e) {
            android.util.Log.w("FlowBridge", "File export failed", e);
            return false;
        }
    }
}
