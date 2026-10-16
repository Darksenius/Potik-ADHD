package com.flow.adhd;

import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Immutable, content-addressed store for recovery snapshots. Construct with Context.getFilesDir(). */
public final class RecoveryArchiveStore {
    private static final String ARCHIVE_PREFIX = "recovery-";
    private final File archiveDir;
    private final File blobDir;

    public RecoveryArchiveStore(File filesDir) {
        if (filesDir == null) throw new IllegalArgumentException("filesDir is required");
        this.archiveDir = new File(filesDir, "recovery");
        this.blobDir = new File(archiveDir, "blobs");
    }

    /** Stores v2 metadata and content-addressed blobs. Existing archive and blob files are never removed. */
    public synchronized boolean archive(String suppliedJson, String originalState, String originalNotif,
                                        Map<String, byte[]> backups) {
        try {
            JSONObject supplied = new JSONObject(suppliedJson);
            long createdAt = nextCreatedAt();
            JSONArray sources = supplied.optJSONArray("sources");
            boolean opaque = sources == null;
            String opaqueIdentity = null;
            if (opaque) {
                JSONObject identityEnvelope = new JSONObject(supplied.toString());
                identityEnvelope.remove("savedAt");
                opaqueIdentity = sha256(identityEnvelope.toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject envelope = new JSONObject();
            if (!opaque && supplied.has("savedAt")) envelope.put("savedAt", supplied.get("savedAt"));
            List<String> sourceRefJson = new ArrayList<>();
            List<String> sourceDescriptors = new ArrayList<>();
            if (!opaque) {
                for (int i = 0; i < sources.length(); i++) {
                    JSONObject source = sources.getJSONObject(i);
                    String label = source.optString("source", "");
                    String raw = source.getString("raw");
                    JSONObject ref = blobRef(raw.getBytes(StandardCharsets.UTF_8));
                    ref.put("source", label);
                    sourceRefJson.add(ref.toString());
                    sourceDescriptors.add(label + "\u0000" + ref.getString("sha256") + "\u0000" + ref.getInt("size"));
                }
            }
            JSONObject stateRef = originalState == null ? null : blobRef(originalState.getBytes(StandardCharsets.UTF_8));
            JSONObject notifRef = originalNotif == null ? null : blobRef(originalNotif.getBytes(StandardCharsets.UTF_8));
            Map<String, String> backupRefJson = new java.util.TreeMap<>();
            Map<String, String> backupDescriptors = new java.util.TreeMap<>();
            if (backups != null) {
                List<String> keys = new ArrayList<>(backups.keySet());
                java.util.Collections.sort(keys);
                for (String key : keys) {
                    if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}")) return false;
                    byte[] bytes = backups.get(key);
                    JSONObject backup = new JSONObject().put("exists", bytes != null);
                    JSONObject backupBlob = bytes == null ? null : blobRef(bytes);
                    backup.put("blob", backupBlob == null ? JSONObject.NULL : backupBlob);
                    backupRefJson.put(key, backup.toString());
                    backupDescriptors.put(key, bytes == null ? "absent" : refDescriptor(backupBlob));
                }
            }
            if (opaqueIdentity != null) sourceDescriptors.add("opaque\u0000" + opaqueIdentity);
            String identity = RecoveryArchiveFormat.identity(sourceDescriptors,
                    refDescriptor(stateRef), refDescriptor(notifRef), backupDescriptors);
            if (!ensureDirectory(archiveDir) || !ensureDirectory(blobDir)) return false;
            if (hasIdentity(identity)) return true;
            JSONObject opaqueRef = opaque ? blobRef(suppliedJson.getBytes(StandardCharsets.UTF_8)) : null;
            String name = ARCHIVE_PREFIX + createdAt + "-" + UUID.randomUUID() + ".json";
            String indexJson = RecoveryArchiveFormat.serializeIndex(createdAt, envelope.toString(),
                    opaqueRef == null ? "null" : opaqueRef.toString(), opaqueIdentity, sourceRefJson,
                    stateRef == null ? "null" : stateRef.toString(), notifRef == null ? "null" : notifRef.toString(),
                    backupRefJson, identity);
            return writeAtomic(new File(archiveDir, name), indexJson.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    /** Metadata for valid v1 and v2 archives, newest first. */
    public synchronized String list() {
        JSONArray result = new JSONArray();
        File[] files = archiveDir.listFiles();
        if (files == null) return result.toString();
        List<File> candidates = new ArrayList<>();
        for (File file : files) if (isArchiveName(file.getName()) && file.isFile()) candidates.add(file);
        Collections.sort(candidates, Comparator.comparingLong(this::archiveCreatedAt).reversed().thenComparing(File::getName));
        for (File file : candidates) {
            try {
                byte[] bytes = readFully(file);
                JSONObject archive = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
                JSONObject item = new JSONObject().put("name", file.getName());
                item.put("createdAt", archive.optLong("createdAt", file.lastModified()));
                item.put("size", bytes.length);
                result.put(item);
            } catch (Exception ignored) { }
        }
        return result.toString();
    }

    /** Returns an exported, self-contained v1 archive envelope. Empty means invalid, incomplete, or unreadable. */
    public synchronized String readLatest() {
        try {
            JSONArray items = new JSONArray(list());
            if (items.length() == 0) return "";
            return read(items.getJSONObject(0).getString("name"));
        } catch (Exception e) { return ""; }
    }

    public synchronized String read(String name) {
        if (!isArchiveName(name)) return "";
        try {
            File safeDir = archiveDir.getCanonicalFile();
            File file = new File(safeDir, name).getCanonicalFile();
            if (!safeDir.equals(file.getParentFile()) || !file.isFile()) return "";
            String archiveJson = new String(readFully(file), StandardCharsets.UTF_8);
            String legacyExport = RecoveryArchiveFormat.legacyExport(archiveJson);
            if (legacyExport != null) return legacyExport;
            JSONObject stored = new JSONObject(archiveJson);
            if (stored.optInt("version", -1) != 2) return "";
            JSONObject exported = new JSONObject().put("version", 1).put("createdAt", stored.getLong("createdAt"));
            Object opaque = stored.opt("suppliedOpaque");
            if (opaque instanceof JSONObject) {
                exported.put("suppliedJson", readBlob((JSONObject) opaque));
                exported.put("originalState", readNullableBlob(stored.opt("originalState")));
                exported.put("originalNotif", readNullableBlob(stored.opt("originalNotif")));
                JSONObject backups = exportBackups(stored.getJSONObject("backups"));
                exported.put("backups", backups);
                return exported.toString();
            }
            JSONObject supplied = new JSONObject(stored.getJSONObject("suppliedEnvelope").toString());
            JSONArray sources = new JSONArray();
            JSONArray refs = stored.getJSONArray("sources");
            for (int i = 0; i < refs.length(); i++) {
                JSONObject ref = refs.getJSONObject(i);
                sources.put(new JSONObject().put("source", ref.getString("source")).put("raw", readBlob(ref)));
            }
            supplied.put("sources", sources);
            exported.put("suppliedJson", supplied.toString());
            exported.put("originalState", readNullableBlob(stored.opt("originalState")));
            exported.put("originalNotif", readNullableBlob(stored.opt("originalNotif")));
            exported.put("backups", exportBackups(stored.getJSONObject("backups")));
            return exported.toString();
        } catch (Exception e) { return ""; }
    }

    private JSONObject blobRef(byte[] raw) throws Exception {
        String hash = sha256(raw);
        File blob = new File(blobDir, hash + ".json");
        if (!blob.exists()) {
            if (!ensureDirectory(blobDir) || !writeAtomic(blob, raw)) throw new IllegalStateException("Unable to write recovery blob");
        } else if (!hash.equals(sha256(readFully(blob)))) {
            throw new IllegalStateException("Recovery blob hash mismatch");
        }
        return new JSONObject().put("sha256", hash).put("size", raw.length).put("ref", "blobs/" + hash + ".json");
    }

    private Object readNullableBlob(Object value) throws Exception {
        return value == null || value == JSONObject.NULL ? JSONObject.NULL : readBlob((JSONObject) value);
    }

    private byte[] readBlobBytes(Object value) throws Exception {
        if (!(value instanceof JSONObject)) throw new JSONException("Invalid blob reference");
        JSONObject ref = (JSONObject) value;
        String hash = ref.getString("sha256");
        String relative = ref.getString("ref");
        if (!hash.matches("[0-9a-f]{64}") || !relative.equals("blobs/" + hash + ".json")) throw new JSONException("Invalid blob path");
        File canonicalDir = blobDir.getCanonicalFile();
        File file = new File(canonicalDir, hash + ".json").getCanonicalFile();
        if (!canonicalDir.equals(file.getParentFile()) || !file.isFile()) throw new IllegalStateException("Missing recovery blob");
        byte[] bytes = readFully(file);
        if (bytes.length != ref.getInt("size") || !sha256(bytes).equals(hash)) throw new IllegalStateException("Corrupt recovery blob");
        return bytes;
    }

    private String readBlob(JSONObject ref) throws Exception { return new String(readBlobBytes(ref), StandardCharsets.UTF_8); }

    private JSONObject exportBackups(JSONObject backupRefs) throws Exception {
        JSONObject backups = new JSONObject();
        Iterator<String> keys = backupRefs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject backupRef = backupRefs.getJSONObject(key);
            Object blob = backupRef.opt("blob");
            JSONObject backup = new JSONObject().put("exists", backupRef.optBoolean("exists"));
            backup.put("bytesBase64", blob == null || blob == JSONObject.NULL ? JSONObject.NULL
                    : android.util.Base64.encodeToString(readBlobBytes(blob), android.util.Base64.NO_WRAP));
            backups.put(key, backup);
        }
        return backups;
    }

    private boolean hasIdentity(String identity) {
        File[] files = archiveDir.listFiles();
        if (files == null) return false;
        for (File file : files) {
            if (!isArchiveName(file.getName())) continue;
            try {
                JSONObject candidate = new JSONObject(new String(readFully(file), StandardCharsets.UTF_8));
                if (candidate.optInt("version", -1) == 2 && identity.equals(identity(candidate)) && verifyIndexRefs(candidate)) return true;
            }
            catch (Exception ignored) { }
        }
        return false;
    }

    private long nextCreatedAt() {
        long now = System.currentTimeMillis();
        long newest = Long.MIN_VALUE;
        File[] files = archiveDir.listFiles();
        if (files != null) for (File file : files) {
            if (isArchiveName(file.getName()) && file.isFile()) newest = Math.max(newest, archiveCreatedAt(file));
        }
        if (newest >= now && newest < Long.MAX_VALUE) return newest + 1L;
        return now;
    }

    private long archiveCreatedAt(File file) {
        try {
            JSONObject archive = new JSONObject(new String(readFully(file), StandardCharsets.UTF_8));
            return archive.optLong("createdAt", file.lastModified());
        } catch (Exception ignored) {
            return file.lastModified();
        }
    }

    private static String identity(JSONObject index) throws Exception {
        List<String> sources = new ArrayList<>();
        Object opaque = index.opt("suppliedOpaque");
        Object opaqueIdentityValue = index.opt("suppliedOpaqueIdentity");
        if (opaqueIdentityValue instanceof String && !((String) opaqueIdentityValue).isEmpty())
            sources.add("opaque\u0000" + opaqueIdentityValue);
        else if (opaque instanceof JSONObject) sources.add("opaque\u0000" + refDescriptor((JSONObject) opaque));
        JSONArray refs = index.getJSONArray("sources");
        for (int i = 0; i < refs.length(); i++) {
            JSONObject ref = refs.getJSONObject(i);
            sources.add(ref.optString("source", "") + "\u0000" + ref.getString("sha256") + "\u0000" + ref.getInt("size"));
        }
        Map<String, String> backups = new java.util.TreeMap<>();
        JSONObject backupRefs = index.getJSONObject("backups");
        Iterator<String> keys = backupRefs.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONObject backup = backupRefs.getJSONObject(key);
            Object blob = backup.opt("blob");
            backups.put(key, blob == null || blob == JSONObject.NULL ? "absent" : refDescriptor((JSONObject) blob));
        }
        return RecoveryArchiveFormat.identity(sources,
                refDescriptor(index.opt("originalState")), refDescriptor(index.opt("originalNotif")), backups);
    }

    public static String sha256(byte[] bytes) throws Exception {
        return RecoveryArchiveFormat.sha256(bytes);
    }

    private static String refDescriptor(JSONObject ref) throws Exception {
        return ref == null ? null : ref.getString("sha256") + ":" + ref.getInt("size");
    }
    private static String refDescriptor(Object value) throws Exception {
        return value == null || value == JSONObject.NULL ? null : refDescriptor((JSONObject) value);
    }
    private boolean verifyIndexRefs(JSONObject index) throws Exception {
        Object suppliedOpaque = index.opt("suppliedOpaque");
        if (suppliedOpaque instanceof JSONObject) readBlobBytes(suppliedOpaque);
        JSONArray refs = index.getJSONArray("sources");
        for (int i = 0; i < refs.length(); i++) readBlobBytes(refs.getJSONObject(i));
        Object state = index.opt("originalState");
        if (state instanceof JSONObject) readBlobBytes(state);
        Object notif = index.opt("originalNotif");
        if (notif instanceof JSONObject) readBlobBytes(notif);
        JSONObject backups = index.getJSONObject("backups");
        Iterator<String> keys = backups.keys();
        while (keys.hasNext()) {
            Object blob = backups.getJSONObject(keys.next()).opt("blob");
            if (blob instanceof JSONObject) readBlobBytes(blob);
        }
        return true;
    }

    private static boolean isArchiveName(String name) {
        return name != null && name.matches("recovery-[0-9]+-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.json");
    }
    private static boolean ensureDirectory(File dir) { return dir.isDirectory() || dir.mkdirs(); }
    private static byte[] readFully(File file) throws Exception {
        if (file.getName().matches("[0-9a-f]{64}\\.json")) return RecoveryArchiveFormat.readFile(file);
        return new AtomicFile(file).readFully();
    }
    private static boolean writeAtomic(File file, byte[] bytes) {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try { stream = atomic.startWrite(); stream.write(bytes); atomic.finishWrite(stream); return true; }
        catch (Exception e) { if (stream != null) atomic.failWrite(stream); return false; }
    }
}
