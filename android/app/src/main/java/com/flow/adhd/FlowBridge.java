package com.flow.adhd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.webkit.JavascriptInterface;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

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
        String notif = dao.getNotifJson();
        if (notif == null) notif = "";
        dao.saveState(new AppState(json, notif));
    }

    /** window.FlowBridge.saveNotif(json) — зберігає snapshot для сповіщення */
    @JavascriptInterface
    public void saveNotif(String notifJson) {
        String state = dao.getStateJson();
        if (state == null) state = "";
        dao.saveState(new AppState(state, notifJson));
        // знімок оновився → перепланувати точний будильник на найближчу задачу
        try { FlowAlarmReceiver.scheduleNextDue(ctx, this); } catch (Exception ignored) {}
    }

    /** window.FlowBridge.load() — повертає повний стан */
    @JavascriptInterface
    public String load() {
        String s = dao.getStateJson();
        return s != null ? s : "";
    }

    /** window.FlowBridge.getEvents() — повертає JSON-масив подій */
    @JavascriptInterface
    public String getEvents() {
        List<PendingEvent> events = dao.getPendingEvents();
        if (events.isEmpty()) return "[]";
        // org.json коректно екранує \n, \t, лапки тощо — на відміну від ручного склеювання
        org.json.JSONArray arr = new org.json.JSONArray();
        for (PendingEvent e : events) {
            arr.put(e.event != null ? e.event : "");
        }
        return arr.toString();
    }

    /** window.FlowBridge.clearEvents() */
    @JavascriptInterface
    public void clearEvents() {
        dao.clearEvents();
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
    private File getBackupDir() {
        File pub = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS), "FLOW");
        if (pub.exists() || pub.mkdirs()) return pub;
        // fallback
        File priv = new File(ctx.getExternalFilesDir(null), "FLOW-backup");
        priv.mkdirs();
        return priv;
    }

    private static final String BACKUP_FILE = "flow_backup.json";

    /** window.FlowBridge.writeBackup(json) — записує резервну копію на диск */
    @JavascriptInterface
    public void writeBackup(String json) {
        try {
            File f = new File(getBackupDir(), BACKUP_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes(StandardCharsets.UTF_8));
            fos.close();
        } catch (Exception ignored) {}
    }

    /**
     * window.FlowBridge.readBackup()
     * Повертає вміст резервного файлу або "" якщо нема.
     */
    @JavascriptInterface
    public String readBackup() {
        try {
            File f = new File(getBackupDir(), BACKUP_FILE);
            if (!f.exists()) return "";
            FileInputStream fis = new FileInputStream(f);
            byte[] buf = new byte[(int)f.length()];
            fis.read(buf);
            fis.close();
            return new String(buf, StandardCharsets.UTF_8);
        } catch (Exception e) { return ""; }
    }

    /** window.FlowBridge.hasBackup() — true якщо є резервний файл */
    @JavascriptInterface
    public boolean hasBackup() {
        try {
            return new File(getBackupDir(), BACKUP_FILE).exists();
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
            File f = new File(dir, filename != null && !filename.isEmpty() ? filename : "flow-export.txt");
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
