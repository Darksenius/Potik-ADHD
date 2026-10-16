package com.flow.adhd;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class RecoveryArchiveStoreTest {
    @Test public void megabytePayloadUsesOneBlobAndSmallIndexAcrossRetries() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "archive-size-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        RecoveryArchiveStore store = new RecoveryArchiveStore(root);
        char[] chars = new char[1024 * 1024];
        java.util.Arrays.fill(chars, 'x');
        String raw = new String(chars);
        Map<String, byte[]> backups = new HashMap<>();
        backups.put("internal", raw.getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 10; i++) {
            JSONObject supplied = new JSONObject().put("savedAt", i).put("sources",
                    new JSONArray().put(new JSONObject().put("source", "Room").put("raw", raw)));
            assertTrue(store.archive(supplied.toString(), raw, null, backups));
        }
        JSONArray entries = new JSONArray(store.list());
        assertEquals(1, entries.length());
        assertTrue("1 MB state must have an index below 8 KiB", entries.getJSONObject(0).getLong("size") < 8192);
        assertEquals("Identical source, state and backup bytes share one blob", 1,
                new File(root, "recovery/blobs").listFiles().length);
        assertFalse(store.readLatest().isEmpty());
    }

    @Test public void v2DedupesByContentAndExportsLegacyShapeWhileReadingV1() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File root = new File(context.getCacheDir(), "archive-store-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        RecoveryArchiveStore store = new RecoveryArchiveStore(root);
        byte[] backup = new byte[] {1, 0, (byte) 255, 10};
        Map<String, byte[]> backups = new HashMap<>();
        backups.put("public", backup);
        String supplied = "{\"savedAt\":111,\"sources\":[{\"source\":\"room\",\"raw\":\"{\\\"a\\\":1}\"}]}";
        assertTrue(store.archive(supplied, "state", "notif", backups));
        JSONArray firstList = new JSONArray(store.list());
        assertEquals(1, firstList.length());
        String name = firstList.getJSONObject(0).getString("name");
        JSONObject index = new JSONObject(readText(new File(new File(root, "recovery"), name)));
        assertEquals(2, index.getInt("version"));
        assertFalse(index.toString().contains("bytesBase64"));
        assertFalse(index.getJSONArray("sources").getJSONObject(0).has("raw"));
        assertTrue(index.getJSONArray("sources").getJSONObject(0).has("sha256"));

        String changedTimestamp = supplied.replace("111", "222");
        assertTrue(store.archive(changedTimestamp, "state", "notif", backups));
        assertEquals("Same content must not create another index", 1, new JSONArray(store.list()).length());

        JSONObject exported = new JSONObject(store.read(name));
        assertEquals(1, exported.getInt("version"));
        JSONObject exportedSupplied = new JSONObject(exported.getString("suppliedJson"));
        assertEquals(111, exportedSupplied.getLong("savedAt"));
        assertEquals("{\"a\":1}", exportedSupplied.getJSONArray("sources").getJSONObject(0).getString("raw"));
        assertEquals("state", exported.getString("originalState"));
        assertArrayEquals(backup, Base64.decode(exported.getJSONObject("backups").getJSONObject("public").getString("bytesBase64"), Base64.DEFAULT));
        assertEquals(store.read(name), store.readLatest());

        long now = System.currentTimeMillis();
        String legacyName = "recovery-" + now + "-" + UUID.randomUUID() + ".json";
        String legacy = "{\"version\":1,\"createdAt\":" + now + ",\"suppliedJson\":\"{\\\"savedAt\\\":9,\\\"sources\\\":[{\\\"source\\\":\\\"old\\\",\\\"raw\\\":\\\"legacy\\\"}]}\",\"originalState\":null,\"originalNotif\":\"n\",\"backups\":{}}";
        writeText(new File(new File(root, "recovery"), legacyName), legacy);
        assertEquals(2, new JSONArray(store.list()).length());
        assertEquals(new JSONObject(legacy).toString(), store.read(legacyName));

        assertTrue(store.archive("{\"reason\":\"opaque\",\"savedAt\":1}", null, null, null));
        int afterOpaque = new JSONArray(store.list()).length();
        assertTrue(store.archive("{\"reason\":\"opaque\",\"savedAt\":2}", null, null, null));
        assertEquals("Opaque JSON timestamps must not create duplicate archives", afterOpaque,
                new JSONArray(store.list()).length());
        JSONObject newest = new JSONObject(store.readLatest());
        assertEquals("{\"reason\":\"opaque\",\"savedAt\":1}", newest.getString("suppliedJson"));
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(text.getBytes(StandardCharsets.UTF_8)); }
    }
}
