package com.flow.adhd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.AtomicFile;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;

public class FlowBridge {

    private final FlowDao dao;
    private final Context ctx;

    public FlowBridge(Context context) {
        ctx = context.getApplicationContext();
        dao = FlowDatabase.getInstance(context).flowDao();
    }

    /** window.FlowBridge.save(json) — зберігає повний стан */
    @JavascriptInterface
    public void save(String json) {
        dao.writeState(json);
    }

    /** Acknowledgement cannot become durable without its resulting state. */
    @JavascriptInterface
    public boolean saveWithEvents(String json, String eventIdsJson) {
        try {
            new org.json.JSONObject(json); // Reject malformed snapshots before the transaction.
            org.json.JSONArray values = new org.json.JSONArray(eventIdsJson);
            List<Long> ids = new ArrayList<>();
            if (values.length() > 500) return false;
            for (int i = 0; i < values.length(); i++) {
                long id = values.getLong(i);
                if (id <= 0) return false;
                ids.add(id);
            }
            dao.commitEvents(json, ids);
            return true;
        } catch (Exception e) {
            android.util.Log.w("FlowBridge", "Command commit failed", e);
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
        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "FLOW");
        return pub;
    }

    private File getPrivateBackupDir() {
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
    public void exportTxt(String filename, String content) {
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
        } catch (Exception e) {
            // тихо ігноруємо — JS лишить fallback на Blob
        }
    }
}
