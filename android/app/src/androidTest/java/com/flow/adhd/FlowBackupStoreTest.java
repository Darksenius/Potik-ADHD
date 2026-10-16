package com.flow.adhd;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class FlowBackupStoreTest {
    private static final String PREFS = "flow_backup_destination";

    @Test public void safWritesVerifyRotateProtectAndForceFlushWithoutCancelingCallback() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        String scope = UUID.randomUUID().toString();
        File isolatedFiles = new File(target.getCacheDir(), "backup-store-test-" + scope);
        assertTrue(isolatedFiles.mkdirs());
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() {
                File files = new File(isolatedFiles, "files");
                if (!files.isDirectory()) files.mkdirs();
                return files;
            }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return target.getSharedPreferences("backup-test-" + scope + "-" + name, mode);
            }
        };
        File root = new File(target.getCacheDir(), "backup-documents");
        deleteTree(root);
        assertTrue(root.isDirectory() || root.mkdirs());
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
                .putString("tree_uri", FlowBackupTestDocumentsProvider.treeUri().toString())
                .putString("folder_name", "Backup test folder")
                .putBoolean("replacement_pending", false).apply();
        FlowBackupTestDocumentsProvider.failBackupRename = false;
        FlowBackupStore store = FlowBackupStore.forTest(context);

        String first = "{\"savedAt\":1,\"tasks\":[]}";
        store.onRoomCommit(first);
        awaitFlush(store);
        assertEquals(first, read(new File(root, "flow_backup.json")));
        assertFalse(new File(root, "flow_backup.json.tmp").exists());
        assertFalse(new File(root, "flow_backup.json.previous").exists());

        // A normal commit coalesces behind the five-minute interval. Force flush must
        // cancel only that timer and still deliver its own completion callback.
        String second = "{\"savedAt\":2,\"tasks\":[]}";
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("last_successful_write", System.currentTimeMillis()).apply();
        store.onRoomCommit(second);
        Thread.sleep(250L);
        assertEquals(first, read(new File(root, "flow_backup.json")));
        awaitFlush(store);
        assertEquals(second, read(new File(root, "flow_backup.json")));

        assertTrue(new File(root, "flow_backup.json").delete());
        awaitFlush(store);
        assertEquals("Force flush repairs a missing destination file even when the content hash is cached",
                second, read(new File(root, "flow_backup.json")));

        // A provider rename failure leaves the existing final file intact.
        FlowBackupTestDocumentsProvider.failBackupRename = true;
        store.onRoomCommit("{\"savedAt\":3,\"tasks\":[]}");
        awaitFlush(store);
        assertEquals(second, read(new File(root, "flow_backup.json")));
        assertEquals("saf_write_failed", store.status().optString("lastError"));
        FlowBackupTestDocumentsProvider.failBackupRename = false;

        // If the final is damaged, recovery uses the verified previous document.
        assertTrue(new File(root, "flow_backup.json").renameTo(new File(root, "flow_backup.json.previous")));
        Files.write(new File(root, "flow_backup.json").toPath(), "{broken".getBytes(StandardCharsets.UTF_8));
        assertEquals(second, store.readSelectedBackup());

        // Existing files remain protected until an explicit replacement confirmation.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean("replacement_pending", true).putString("last_error", "").apply();
        store.onRoomCommit("{\"savedAt\":4,\"tasks\":[]}");
        awaitFlush(store);
        assertEquals("replacement_confirmation_required", store.status().optString("lastError"));
        assertEquals("{broken", read(new File(root, "flow_backup.json")));
        CountDownLatch confirmed = new CountDownLatch(1);
        store.confirmReplacement(confirmed::countDown);
        assertTrue("Confirm callback must run after attempted output", confirmed.await(5, TimeUnit.SECONDS));
        assertEquals("{\"savedAt\":4,\"tasks\":[]}", read(new File(root, "flow_backup.json")));
        assertTrue(store.status().optLong("lastSuccessfulWrite") > 0L);
        FlowBackupTestDocumentsProvider.failBackupRename = false;
    }

    private static void awaitFlush(FlowBackupStore store) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        store.flush(completed::countDown);
        assertTrue("Backup flush callback should complete", completed.await(5, TimeUnit.SECONDS));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void deleteTree(File target) {
        if (!target.exists()) return;
        File[] children = target.listFiles();
        if (children != null) for (File child : children) deleteTree(child);
        target.delete();
    }
}
