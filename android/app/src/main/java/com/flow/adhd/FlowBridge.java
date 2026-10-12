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
import android.util.Base64;

public class FlowBridge {

    private final FlowDao dao;
    private final Context ctx;
    private final File publicBackupOverride;
    private final File privateBackupOverride;
    private volatile String lastSaveError = "";

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
        dao.writeState(json);
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
    }

    /** Sanitized code for the most recent failed snapshot commit. */
    @JavascriptInterface
    public String getLastSaveError() { return lastSaveError; }

    /** Preserve all recovery inputs in a new archive; never alters Room or backups. */
    @JavascriptInterface
    public synchronized boolean archiveRecovery(String suppliedJson) {
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
    public synchronized String readRecoveryArchive() {
        try {
            org.json.JSONArray archives = new org.json.JSONArray(listRecoveryArchives());
            if (archives.length() == 0) return "";
            return readRecoveryArchiveFile(archives.getJSONObject(0).getString("name"));
        } catch (Exception ignored) { return ""; }
    }

    /** Metadata for readable immutable recovery archives, newest first. */
    @JavascriptInterface
    public synchronized String listRecoveryArchives() {
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
    public synchronized String readRecoveryArchiveFile(String name) {
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
        dao.writeNotif(notifJson);
        // знімок оновився → перепланувати точний будильник на найближчу задачу
        try { FlowAlarmReceiver.scheduleNextDue(ctx, this); } catch (Exception ignored) {}
    }

    /** window.FlowBridge.load() — повертає повний стан */
    @JavascriptInterface
    public String load() {
        String s = dao.getStateJson();
        return s != null ? s : "";
    }

    // id останньої прочитаної події — щоб clearEvents() видаляв лише її і старіші,
    // а не події, що прилетіли вже після читання (інакше нотатка зі шторки губиться)
    private volatile long lastReadEventId = 0;

    /** window.FlowBridge.getEvents() — повертає JSON-масив подій */
    @JavascriptInterface
    public String getEvents() {
        List<PendingEvent> events = dao.getPendingEvents();
        if (events.isEmpty()) return "[]";
        // org.json коректно екранує \n, \t, лапки тощо — на відміну від ручного склеювання
        org.json.JSONArray arr = new org.json.JSONArray();
        long maxId = lastReadEventId;
        for (PendingEvent e : events) {
            arr.put(e.event != null ? e.event : "");
            if (e.id > maxId) maxId = e.id;
        }
        lastReadEventId = maxId;
        return arr.toString();
    }

    /** New app versions read commands without removing them. Old string API remains compatible. */
    public String getEventEnvelopes() {
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
    }

    /** window.FlowBridge.clearEvents() — лише прочитані (до lastReadEventId) */
    @JavascriptInterface
    public void clearEvents() {
        if (lastReadEventId > 0) dao.clearEventsUpTo(lastReadEventId);
        else dao.clearEvents();
    }

    /** Викликається з Java-коду (не JS) */
    public void pushEvent(String event) {
        dao.insertEvent(new PendingEvent(event));
    }

    public String getNotifJson() {
        String s = dao.getNotifJson();
        return s != null ? s : "";
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
        synchronized (FlowBridge.class) {
            // Attempt the private fallback even when a public directory exists but is unwritable.
            return writeAtomicBackup(getPublicBackupDir(), json)
                    || writeAtomicBackup(getPrivateBackupDir(), json);
        }
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
        synchronized (FlowBridge.class) {
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
