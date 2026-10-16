package com.flow.adhd;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.security.MessageDigest;
import java.util.regex.Pattern;

/** Pure Java disk-format helpers shared by the Android store and local JVM tests. */
public final class RecoveryArchiveFormat {
    private static final Pattern V1_VERSION = Pattern.compile("^\\s*\\{\\s*\"version\"\\s*:\\s*1(?:\\s*[,}])");

    private RecoveryArchiveFormat() { }

    public static byte[] readFile(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    public static boolean isLegacyV1(String json) {
        return json != null && V1_VERSION.matcher(json).find();
    }

    /** The v1 export format is already self-contained, so preserve its serialized payload unchanged. */
    public static String legacyExport(String json) {
        return isLegacyV1(json) ? json : null;
    }

    /** Canonical content identity. Descriptor order and metadata timestamps do not affect it. */
    public static String identity(List<String> sourceDescriptors, String originalState, String originalNotif,
                           Map<String, String> backupDescriptors) throws Exception {
        List<String> sources = new ArrayList<>(sourceDescriptors);
        Collections.sort(sources);
        TreeMap<String, String> backups = new TreeMap<>(backupDescriptors);
        StringBuilder canonical = new StringBuilder("sources\n");
        for (String source : sources) append(canonical, source);
        canonical.append("state\n"); append(canonical, originalState);
        canonical.append("notif\n"); append(canonical, originalNotif);
        canonical.append("backups\n");
        for (Map.Entry<String, String> backup : backups.entrySet()) {
            append(canonical, backup.getKey()); append(canonical, backup.getValue());
        }
        return sha256(canonical.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    /** Serialize pre-validated JSON fragments into a deterministic v2 index. */
    public static String serializeIndex(long createdAt, String suppliedEnvelopeJson, String opaqueSuppliedRefJson,
                                 String opaqueIdentity,
                                 List<String> sourceRefJson, String originalStateRefJson,
                                 String originalNotifRefJson, Map<String, String> backupRefJson,
                                 String identity) {
        StringBuilder out = new StringBuilder("{\"version\":2,\"createdAt\":").append(createdAt)
                .append(",\"suppliedEnvelope\":").append(suppliedEnvelopeJson)
                .append(",\"suppliedOpaque\":").append(opaqueSuppliedRefJson)
                .append(",\"suppliedOpaqueIdentity\":").append(opaqueIdentity == null ? "null" : quote(opaqueIdentity))
                .append(",\"sources\":[");
        for (int i = 0; i < sourceRefJson.size(); i++) {
            if (i != 0) out.append(',');
            out.append(sourceRefJson.get(i));
        }
        out.append("],\"originalState\":").append(originalStateRefJson)
                .append(",\"originalNotif\":").append(originalNotifRefJson)
                .append(",\"backups\":{");
        List<String> keys = new ArrayList<>(backupRefJson.keySet());
        Collections.sort(keys);
        for (int i = 0; i < keys.size(); i++) {
            if (i != 0) out.append(',');
            String key = keys.get(i);
            out.append(quote(key)).append(':').append(backupRefJson.get(key));
        }
        return out.append("},\"identity\":").append(quote(identity)).append('}').toString();
    }

    private static void append(StringBuilder target, String value) {
        if (value == null) { target.append("-1:"); return; }
        target.append(value.length()).append(':').append(value);
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default: if (c < 0x20) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
