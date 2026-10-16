package com.flow.adhd;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Owns the app-private last-good snapshot and optional user-selected SAF backup folder. */
final class FlowBackupStore {
    static final String BACKUP_FILE = "flow_backup.json";
    static final long BACKUP_INTERVAL_MS = 300_000L;
    private static final String TAG = "FlowBackupStore";
    private static final String PREFS = "flow_backup_destination";
    private static final String KEY_URI = "tree_uri";
    private static final String KEY_FOLDER = "folder_name";
    private static final String KEY_LAST_WRITE = "last_successful_write";
    private static final String KEY_ERROR = "last_error";
    private static final String KEY_REPLACEMENT = "replacement_pending";
    private static final String KEY_EXTERNAL_HASH = "external_hash";

    private static volatile FlowBackupStore instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final File lastGoodFile;
    private final ScheduledExecutorService io = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "flow-backup-io");
        thread.setDaemon(true);
        return thread;
    });
    private String latestJson;
    private volatile String lastGoodHash = "";
    private ScheduledFuture<?> throttle;

    static FlowBackupStore get(Context context) {
        FlowBackupStore result = instance;
        if (result == null) synchronized (FlowBackupStore.class) {
            result = instance;
            if (result == null) instance = result = new FlowBackupStore(context.getApplicationContext());
        }
        return result;
    }

    static FlowBackupStore forTest(Context context) { return new FlowBackupStore(context.getApplicationContext()); }

    private FlowBackupStore(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.lastGoodFile = new File(new File(context.getFilesDir(), "backups"), BACKUP_FILE);
        try {
            if (lastGoodFile.exists()) {
                byte[] saved = new AtomicFile(lastGoodFile).readFully();
                lastGoodHash = sha256(saved);
                String candidate = new String(saved, java.nio.charset.StandardCharsets.UTF_8);
                if (validJson(candidate)) latestJson = candidate;
            }
        } catch (Exception ignored) { }
    }

    /** Called after the Room transaction has committed. All file and provider work stays off the Room lane. */
    void onRoomCommit(String json) {
        if (!validJson(json)) return;
        synchronized (this) {
            latestJson = json;
            if (throttle != null) throttle.cancel(false);
            throttle = io.schedule(() -> processLatest(false), 0L, TimeUnit.MILLISECONDS);
        }
    }

    boolean writeCompatibilitySnapshot(String json) {
        if (!validJson(json)) return false;
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            String hash = sha256(bytes);
            if (!hash.equals(lastGoodHash) && !writeInternal(bytes)) return false;
            lastGoodHash = hash;
            onRoomCommit(json);
            return true;
        } catch (Exception e) { return false; }
    }

    void flush() {
        synchronized (this) {
            if (throttle != null) throttle.cancel(false);
            io.execute(() -> processLatest(true));
        }
    }

    void flush(Runnable completed) {
        synchronized (this) {
            if (throttle != null) throttle.cancel(false);
            io.execute(() -> {
                try { processLatest(true); }
                catch (Exception e) { Log.w(TAG, "Forced backup flush failed", e); setError("saf_write_failed"); }
                finally { if (completed != null) completed.run(); }
            });
        }
    }

    private void processLatest(boolean force) {
        final String json;
        synchronized (this) { json = latestJson; }
        if (json == null || !validJson(json)) return;
        byte[] bytes = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash;
        try { hash = sha256(bytes); }
        catch (Exception e) { setError("hash_failed"); return; }
        if (!hash.equals(lastGoodHash)) {
            if (writeInternal(bytes)) lastGoodHash = hash;
            else { setError("internal_write_failed"); return; }
        }
        String tree = prefs.getString(KEY_URI, "");
        if (tree.isEmpty()) return;
        if (prefs.getBoolean(KEY_REPLACEMENT, false)) return;
        String externalHash = prefs.getString(KEY_EXTERNAL_HASH, "");
        if (hash.equals(externalHash)) {
            try {
                Uri current = findChild(Uri.parse(tree), BACKUP_FILE);
                if (current != null && sha256(readDocument(current)).equals(hash)) return;
            } catch (Exception e) {
                Log.w(TAG, "Unable to verify existing external backup; attempting repair", e);
            }
        }
        long previous = prefs.getLong(KEY_LAST_WRITE, 0L);
        long wait = force || previous == 0L ? 0L : Math.max(0L, BACKUP_INTERVAL_MS - (System.currentTimeMillis() - previous));
        if (wait > 0L) {
            synchronized (this) {
                if (throttle != null) throttle.cancel(false);
                throttle = io.schedule(() -> processLatest(false), wait, TimeUnit.MILLISECONDS);
            }
            return;
        }
        try {
            Uri folder = Uri.parse(tree);
            writeSafBackup(folder, bytes);
            if (tree.equals(prefs.getString(KEY_URI, ""))) {
                prefs.edit().putLong(KEY_LAST_WRITE, System.currentTimeMillis())
                        .putString(KEY_EXTERNAL_HASH, hash).putString(KEY_ERROR, "").apply();
            } else {
                // The destination changed while the provider call was in flight. Do not
                // mark the new folder as having received bytes written to the old one.
                io.execute(() -> processLatest(true));
            }
        } catch (Exception e) {
            Log.w(TAG, "SAF backup write failed", e);
            if (tree.equals(prefs.getString(KEY_URI, ""))) setError("saf_write_failed");
        }
    }

    private boolean writeInternal(byte[] bytes) {
        AtomicFile atomic = new AtomicFile(lastGoodFile);
        FileOutputStream stream = null;
        try {
            File parent = lastGoodFile.getParentFile();
            if (parent == null || (!parent.isDirectory() && !parent.mkdirs())) return false;
            stream = atomic.startWrite();
            stream.write(bytes);
            atomic.finishWrite(stream);
            return sha256(atomic.readFully()).equals(sha256(bytes));
        } catch (Exception e) {
            if (stream != null) atomic.failWrite(stream);
            return false;
        }
    }

    private void writeSafBackup(Uri folder, byte[] bytes) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri finalUri = findChild(folder, BACKUP_FILE);
        Uri previousUri = findChild(folder, BACKUP_FILE + ".previous");
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder));
        Uri tempUri = findChild(folder, BACKUP_FILE + ".tmp");
        if (tempUri != null) DocumentsContract.deleteDocument(resolver, tempUri);
        tempUri = DocumentsContract.createDocument(resolver, parent, "application/json", BACKUP_FILE + ".tmp");
        if (tempUri == null) throw new IllegalStateException("temp_create_failed");
        try (OutputStream out = resolver.openOutputStream(tempUri, "wt")) {
            if (out == null) throw new IllegalStateException("temp_open_failed");
            out.write(bytes);
            out.flush();
        }
        requireSameBytes(readDocument(tempUri), bytes);

        if (finalUri != null) {
            boolean finalValid = isValidBackup(new String(readDocument(finalUri), java.nio.charset.StandardCharsets.UTF_8));
            boolean previousValid = previousUri != null
                    && isValidBackup(new String(readDocument(previousUri), java.nio.charset.StandardCharsets.UTF_8));
            if (!finalValid && previousUri != null && previousValid) {
                DocumentsContract.deleteDocument(resolver, finalUri);
            } else {
                if (!finalValid && previousUri != null)
                    throw new IllegalStateException("existing_backups_unreadable");
                if (previousUri != null) DocumentsContract.deleteDocument(resolver, previousUri);
                Uri rotated = DocumentsContract.renameDocument(resolver, finalUri, BACKUP_FILE + ".previous");
                if (rotated == null) throw new IllegalStateException("previous_rotate_failed");
                // Some providers keep the document id, so a non-null returned
                // URI alone does not prove the requested name was installed.
                previousUri = findChild(folder, BACKUP_FILE + ".previous");
                if (previousUri == null) throw new IllegalStateException("previous_name_not_installed");
            }
        }
        Uri installed = DocumentsContract.renameDocument(resolver, tempUri, BACKUP_FILE);
        if (installed == null) {
            restorePreviousIfNeeded(folder, previousUri);
            throw new IllegalStateException("final_rename_failed");
        }
        try {
            Uri actualFinal = findChild(folder, BACKUP_FILE);
            if (actualFinal == null) throw new IllegalStateException("final_name_not_installed");
            requireSameBytes(readDocument(actualFinal), bytes);
        } catch (Exception e) {
            restorePreviousIfNeeded(folder, previousUri);
            throw e;
        }
        if (previousUri != null) DocumentsContract.deleteDocument(resolver, previousUri);
    }

    private void restorePreviousIfNeeded(Uri folder, Uri previousUri) {
        if (previousUri == null || findChild(folder, BACKUP_FILE) != null) return;
        try { DocumentsContract.renameDocument(context.getContentResolver(), previousUri, BACKUP_FILE); }
        catch (Exception ignored) { }
    }

    private Uri findChild(Uri tree, String name) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            try (android.database.Cursor cursor = context.getContentResolver().query(children,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (cursor == null) throw new IllegalStateException("folder_listing_failed");
                int idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                if (idCol < 0 || nameCol < 0) throw new IllegalStateException("folder_listing_failed");
                while (cursor.moveToNext()) if (name.equals(cursor.getString(nameCol)))
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idCol));
            }
        } catch (Exception e) {
            if (e instanceof IllegalStateException) throw (IllegalStateException) e;
            throw new IllegalStateException("folder_listing_failed", e);
        }
        return null;
    }

    private byte[] readDocument(Uri uri) throws Exception {
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (in == null) throw new IllegalStateException("document_read_failed");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) >= 0) out.write(buffer, 0, count);
            return out.toByteArray();
        }
    }

    private static void requireSameBytes(byte[] left, byte[] right) throws Exception {
        if (!sha256(left).equals(sha256(right))) throw new IllegalStateException("verification_failed");
    }

    static final class FolderResult {
        final boolean success;
        final boolean hasBackup;
        final String folderName;
        final String raw;
        FolderResult(boolean success, boolean hasBackup, String folderName, String raw) {
            this.success = success;
            this.hasBackup = hasBackup;
            this.folderName = folderName;
            this.raw = raw;
        }
    }

    void chooseFolder(Uri tree, String purpose, java.util.function.Consumer<FolderResult> completed) {
        try {
            context.getContentResolver().takePersistableUriPermission(tree,
                    IntentFlags.READ_WRITE);
            String oldTree = prefs.getString(KEY_URI, "");
            SharedPreferences.Editor pending = prefs.edit().putString(KEY_URI, tree.toString())
                    .putBoolean(KEY_REPLACEMENT, true).putString(KEY_ERROR, "");
            if (!tree.toString().equals(oldTree)) pending.putString(KEY_EXTERNAL_HASH, "")
                    .putLong(KEY_LAST_WRITE, 0L).putString(KEY_FOLDER, "");
            pending.apply();
        } catch (Exception e) {
            Log.w(TAG, "Unable to keep selected backup folder", e);
            setError("folder_access_failed");
            if (completed != null) completed.accept(new FolderResult(false, false, "", ""));
            return;
        }
        io.execute(() -> {
            try {
                String name = folderName(tree);
                Uri existing = findChild(tree, BACKUP_FILE);
                Uri previous = findChild(tree, BACKUP_FILE + ".previous");
                boolean hasBackup = existing != null || previous != null;
                boolean paused = existing != null || previous != null || "restore".equals(purpose);
                prefs.edit().putString(KEY_FOLDER, name).putBoolean(KEY_REPLACEMENT, paused).apply();
                String raw = "";
                if ("restore".equals(purpose) && hasBackup) raw = readSelectedBackup(tree);
                if (!paused && "backup".equals(purpose)) {
                    refreshRoomSnapshot();
                    processLatest(true);
                }
                if (completed != null) completed.accept(new FolderResult(true, hasBackup, name, raw));
            } catch (Exception e) {
                Log.w(TAG, "Unable to inspect selected backup folder", e);
                setError("folder_access_failed");
                if (completed != null) completed.accept(new FolderResult(false, false, "", ""));
            }
        });
    }

    private void refreshRoomSnapshot() {
        try {
            String current = FlowDatabase.callStorage(() -> FlowDatabase.getInstance(context).flowDao().getStateJson());
            if (current != null && validJson(current)) synchronized (this) { latestJson = current; }
        } catch (Exception e) { Log.w(TAG, "Unable to refresh the Room backup snapshot", e); }
    }

    String readSelectedBackup() {
        String tree = prefs.getString(KEY_URI, "");
        if (tree.isEmpty()) return "";
        return readSelectedBackup(Uri.parse(tree));
    }

    private String readSelectedBackup(Uri folder) {
        try {
            Uri file = findChild(folder, BACKUP_FILE);
            String finalRaw = file == null ? "" : new String(readDocument(file), java.nio.charset.StandardCharsets.UTF_8);
            if (isValidBackup(finalRaw)) return finalRaw;
            Uri previous = findChild(folder, BACKUP_FILE + ".previous");
            if (previous != null) {
                String previousRaw = new String(readDocument(previous), java.nio.charset.StandardCharsets.UTF_8);
                if (isValidBackup(previousRaw)) return previousRaw;
            }
            return finalRaw;
        } catch (Exception e) { setError("saf_read_failed"); return ""; }
    }

    private static boolean isValidBackup(String json) {
        if (json == null || json.isEmpty()) return false;
        try { new JSONObject(json); return true; }
        catch (Exception e) { return false; }
    }

    boolean hasSelectedBackup() {
        String tree = prefs.getString(KEY_URI, "");
        if (tree.isEmpty()) return false;
        Uri folder = Uri.parse(tree);
        return findChild(folder, BACKUP_FILE) != null || findChild(folder, BACKUP_FILE + ".previous") != null;
    }

    void confirmReplacement() {
        prefs.edit().putBoolean(KEY_REPLACEMENT, false).putString(KEY_ERROR, "").apply();
        flush();
    }

    void confirmReplacement(Runnable completed) {
        prefs.edit().putBoolean(KEY_REPLACEMENT, false).putString(KEY_ERROR, "").apply();
        flush(completed);
    }

    JSONObject status() {
        JSONObject result = new JSONObject();
        try {
            result.put("configured", !prefs.getString(KEY_URI, "").isEmpty());
            result.put("folderName", prefs.getString(KEY_FOLDER, ""));
            result.put("lastSuccessfulWrite", prefs.getLong(KEY_LAST_WRITE, 0L));
            String error = prefs.getString(KEY_ERROR, "");
            if (prefs.getBoolean(KEY_REPLACEMENT, false) && error.isEmpty()) error = "replacement_confirmation_required";
            result.put("lastError", error);
        } catch (Exception ignored) { }
        return result;
    }

    String localBackup() {
        try { return new String(new AtomicFile(lastGoodFile).readFully(), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception ignored) { return ""; }
    }

    private void setError(String error) { prefs.edit().putString(KEY_ERROR, error).apply(); }

    private static boolean validJson(String json) {
        try { new JSONObject(json); return true; }
        catch (Exception e) { return false; }
    }

    private static String folderName(Uri tree) {
        try {
            String id = DocumentsContract.getTreeDocumentId(tree);
            int slash = id.lastIndexOf('/');
            int colon = id.lastIndexOf(':');
            return id.substring(Math.max(slash, colon) + 1);
        } catch (Exception ignored) { return ""; }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static final class IntentFlags {
        static final int READ_WRITE = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
    }
}
