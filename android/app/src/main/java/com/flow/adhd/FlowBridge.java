package com.flow.adhd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.database.sqlite.SQLiteFullException;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.Callable;
import android.util.Base64;

public class FlowBridge {

    private static final long MAX_SAFE_JS_INTEGER = 9_007_199_254_740_991L;
    private static final long MAX_CAPTURED_TASK_ID = MAX_SAFE_JS_INTEGER - 1L;

    private final FlowDao dao;
    private final Context ctx;
    private final File publicBackupOverride;
    private final File privateBackupOverride;
    private volatile String lastSaveError = "";

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
    }

    /** window.FlowBridge.save(json) — зберігає повний стан */
    @JavascriptInterface
    public void save(String json) {
        callStorage(() -> { dao.writeState(json); return null; });
    }

    /** Acknowledgement cannot become durable without its resulting state. */
    @JavascriptInterface
    public boolean saveWithEvents(String json, String eventIdsJson) {
        org.json.JSONArray values;
        List<Long> ids = new ArrayList<>();
        try {
            new org.json.JSONObject(json); // Reject malformed snapshots before the transaction.
            values = new org.json.JSONArray(eventIdsJson);
            if (values.length() > 500) { lastSaveError = "invalid_state"; return false; }
            for (int i = 0; i < values.length(); i++) {
                long id = values.getLong(i);
                if (id <= 0) { lastSaveError = "invalid_state"; return false; }
                ids.add(id);
            }
        } catch (org.json.JSONException | NullPointerException e) {
            lastSaveError = "invalid_state";
            android.util.Log.w("FlowBridge", "Invalid state or event ids");
            return false;
        }
        return callStorage(() -> {
            try {
                dao.commitEvents(json, ids);
                lastSaveError = "";
                return true;
            } catch (SQLiteFullException e) {
                lastSaveError = "storage_full";
                android.util.Log.w("FlowBridge", "Command commit failed: storage_full", e);
                return false;
            } catch (Exception e) {
                lastSaveError = "database_error";
                android.util.Log.w("FlowBridge", "Command commit failed", e);
                return false;
            }
        });
    }

    /** Sanitized code for the most recent failed snapshot commit. */
    @JavascriptInterface
    public String getLastSaveError() { return lastSaveError; }

    /** Preserve all recovery inputs in a new archive; never alters Room or backups. */
    @JavascriptInterface
    public boolean archiveRecovery(String suppliedJson) {
        return callStorage(() -> archiveRecoveryOnStorage(suppliedJson));
    }

    private boolean archiveRecoveryOnStorage(String suppliedJson) {
        try {
            new org.json.JSONObject(suppliedJson);
            String originalState = dao.getStateJson();
            String originalNotif = dao.getNotifJson();
            org.json.JSONObject archive = new org.json.JSONObject();
            archive.put("version", 1);
            archive.put("createdAt", System.currentTimeMillis());
            archive.put("suppliedJson", suppliedJson);
            archive.put("originalState", originalState == null ? org.json.JSONObject.NULL : originalState);
            archive.put("originalNotif", originalNotif == null ? org.json.JSONObject.NULL : originalNotif);
            org.json.JSONObject backups = new org.json.JSONObject();
            putBackupBytes(backups, "public", new File(getPublicBackupDir(), BACKUP_FILE));
            putBackupBytes(backups, "private", new File(getPrivateBackupDir(), BACKUP_FILE));
            archive.put("backups", backups);

            File directory = new File(ctx.getFilesDir(), "recovery");
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            String name = "recovery-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".json";
            return writeAtomicFile(new File(directory, name), archive.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            android.util.Log.w("FlowBridge", "Recovery archive failed", e);
            return false;
        }
    }

    /** Returns the newest readable archive as JSON, or an empty string. */
    @JavascriptInterface
    public String readRecoveryArchive() {
        return callStorage(this::readRecoveryArchiveOnStorage);
    }

    private String readRecoveryArchiveOnStorage() {
        try {
            org.json.JSONArray archives = new org.json.JSONArray(listRecoveryArchivesOnStorage());
            if (archives.length() == 0) return "";
            return readRecoveryArchiveFileOnStorage(archives.getJSONObject(0).getString("name"));
        } catch (Exception ignored) { return ""; }
    }

    /** Metadata for readable immutable recovery archives, newest first. */
    @JavascriptInterface
    public String listRecoveryArchives() {
        return callStorage(this::listRecoveryArchivesOnStorage);
    }

    private String listRecoveryArchivesOnStorage() {
        org.json.JSONArray result = new org.json.JSONArray();
        File directory = new File(ctx.getFilesDir(), "recovery");
        File[] files = directory.listFiles();
        if (files == null) return result.toString();
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
        for (File file : files) {
            if (!isRecoveryArchiveName(file.getName())) continue;
            try {
                byte[] bytes = new AtomicFile(file).readFully();
                org.json.JSONObject archive = new org.json.JSONObject(new String(bytes, StandardCharsets.UTF_8));
                org.json.JSONObject item = new org.json.JSONObject();
                item.put("name", file.getName());
                item.put("createdAt", archive.optLong("createdAt", file.lastModified()));
                item.put("size", bytes.length);
                result.put(item);
            } catch (Exception ignored) { }
        }
        return result.toString();
    }

    /** Read one archive by its generated filename only; reject traversal and paths. */
    @JavascriptInterface
    public String readRecoveryArchiveFile(String name) {
        return callStorage(() -> readRecoveryArchiveFileOnStorage(name));
    }

    private String readRecoveryArchiveFileOnStorage(String name) {
        if (!isRecoveryArchiveName(name)) return "";
        try {
            File directory = new File(ctx.getFilesDir(), "recovery").getCanonicalFile();
            File file = new File(directory, name).getCanonicalFile();
            if (!directory.equals(file.getParentFile()) || !file.isFile()) return "";
            String json = new String(new AtomicFile(file).readFully(), StandardCharsets.UTF_8);
            new org.json.JSONObject(json);
            return json;
        } catch (Exception ignored) { return ""; }
    }

    private boolean isRecoveryArchiveName(String name) {
        return name != null && name.matches("recovery-[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json");
    }

    private void putBackupBytes(org.json.JSONObject backups, String key, File file) throws Exception {
        org.json.JSONObject value = new org.json.JSONObject();
        boolean exists = file.exists() || new File(file.getPath() + ".bak").exists();
        value.put("exists", exists);
        if (exists) value.put("bytesBase64", Base64.encodeToString(new AtomicFile(file).readFully(), Base64.NO_WRAP));
        else value.put("bytesBase64", org.json.JSONObject.NULL);
        backups.put(key, value);
    }

    private boolean writeAtomicFile(File path, byte[] contents) {
        AtomicFile file = new AtomicFile(path);
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(contents);
            file.finishWrite(stream);
            return true;
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }

    /** window.FlowBridge.saveNotif(json) — зберігає snapshot для сповіщення */
    @JavascriptInterface
    public void saveNotif(String notifJson) {
        callStorage(() -> {
            dao.writeNotif(notifJson);
            // Keep the saved snapshot and its derived alarm update in FIFO order.
            try { FlowAlarmReceiver.scheduleNextDue(ctx, this); } catch (Exception ignored) {}
            return null;
        });
    }

    /** window.FlowBridge.load() — повертає повний стан */
    @JavascriptInterface
    public String load() {
        return callStorage(() -> {
            String s = dao.getStateJson();
            return s != null ? s : "";
        });
    }

    // id останньої прочитаної події — щоб clearEvents() видаляв лише її і старіші,
    // а не події, що прилетіли вже після читання (інакше нотатка зі шторки губиться)
    private volatile long lastReadEventId = 0;

    /** window.FlowBridge.getEvents() — повертає JSON-масив подій */
    @JavascriptInterface
    public String getEvents() {
        return callStorage(() -> {
            List<PendingEvent> events = dao.getPendingEvents();
            if (events.isEmpty()) return "[]";
            org.json.JSONArray arr = new org.json.JSONArray();
            long maxId = lastReadEventId;
            for (PendingEvent e : events) {
                arr.put(e.event != null ? e.event : "");
                if (e.id > maxId) maxId = e.id;
            }
            lastReadEventId = maxId;
            return arr.toString();
        });
    }

    /** New app versions read commands without removing them. Old string API remains compatible. */
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

    /** window.FlowBridge.clearEvents() — лише прочитані (до lastReadEventId) */
    @JavascriptInterface
    public void clearEvents() {
        callStorage(() -> {
            if (lastReadEventId > 0) dao.clearEventsUpTo(lastReadEventId);
            else dao.clearEvents();
            return null;
        });
    }

    /** Викликається з Java-коду (не JS) */
    public void pushEvent(String event) {
        callStorage(() -> { dao.insertEvent(new PendingEvent(event)); return null; });
    }

    /**
     * Durably queues a quick task with a stable numeric ID that JS can later
     * materialize exactly once. The notification snapshot is never edited here.
     */
    public String queueCapturedTask(String title) {
        if (title == null || title.trim().isEmpty()) return null;
        return callStorage(() -> {
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

    /** window.FlowBridge.appVersion() — версія з build.gradle (єдине джерело правди) */
    @JavascriptInterface
    public String appVersion() {
        return BuildConfig.VERSION_NAME;
    }

    /**
     * Повертає папку для резервного копіювання.
     * Пріоритет: Documents/FLOW (публічна, виживає після переустановки)
     * Fallback: getExternalFilesDir (без дозволу, але стирається при деінсталяції)
     */
    private File getPublicBackupDir() {
        if (publicBackupOverride != null) return publicBackupOverride;
        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "FLOW");
        return pub;
    }

    private File getPrivateBackupDir() {
        if (privateBackupOverride != null) return privateBackupOverride;
        File base = ctx.getExternalFilesDir(null);
        return new File(base != null ? base : ctx.getFilesDir(), "FLOW-backup");
    }

    private static final String BACKUP_FILE = "flow_backup.json";

    /** window.FlowBridge.writeBackup(json) — записує резервну копію на диск */
    @JavascriptInterface
    public boolean writeBackup(String json) {
        return callStorage(() -> writeBackupOnStorage(json));
    }

    private boolean writeBackupOnStorage(String json) {
        // Attempt the private fallback even when a public directory exists but is unwritable.
        return writeAtomicBackup(getPublicBackupDir(), json)
                || writeAtomicBackup(getPrivateBackupDir(), json);
    }

    private boolean writeAtomicBackup(File directory, String json) {
        AtomicFile file = new AtomicFile(new File(directory, BACKUP_FILE));
        FileOutputStream stream = null;
        try {
            new org.json.JSONObject(json);
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            stream = file.startWrite();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
            file.finishWrite(stream);
            return true;
        } catch (Exception e) {
            if (stream != null) file.failWrite(stream);
            return false;
        }
    }

    /**
     * window.FlowBridge.readBackup()
     * Повертає вміст резервного файлу або "" якщо нема.
     */
    @JavascriptInterface
    public String readBackup() {
        return callStorage(this::readBackupOnStorage);
    }

    private String readBackupOnStorage() {
        File pub = new File(getPublicBackupDir(), BACKUP_FILE);
        File priv = new File(getPrivateBackupDir(), BACKUP_FILE);
        File[] candidates = pub.lastModified() >= priv.lastModified()
                ? new File[] {pub, priv} : new File[] {priv, pub};
        String unreadable = "";
        for (File candidate : candidates) {
            try {
                String json = new String(new AtomicFile(candidate).readFully(), StandardCharsets.UTF_8);
                if (unreadable.isEmpty()) unreadable = json;
                new org.json.JSONObject(json);
                return json;
            } catch (Exception ignored) {}
        }
        return unreadable; // Let the UI protect and export damaged copies instead of overwriting them.
    }

    /** window.FlowBridge.hasBackup() — true якщо є резервний файл */
    @JavascriptInterface
    public boolean hasBackup() {
        try {
            return !readBackup().isEmpty();
        } catch (Exception e) { return false; }
    }

    /**
     * window.FlowBridge.exportTxt(filename, content)
     * Записує текст у файл і відкриває системний діалог "Поділитися"
     * (зберегти у Файли / надіслати терапевту). Працює у WebView, де
     * <a download> не спрацьовує.
     */
    @JavascriptInterface
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
