package com.getcapacitor.myapp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.flow.adhd.RecoveryArchiveStore;
import com.flow.adhd.RecoveryArchiveFormat;
import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class RecoveryArchiveStoreTest {
    @Test public void sha256UsesUtf8Bytes() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                RecoveryArchiveStore.sha256("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void indexFormatCanonicalIdentityAndLegacyReaderHelpers() throws Exception {
        String ref = "{\"source\":\"room\",\"sha256\":\"" + RecoveryArchiveFormat.sha256("x".getBytes(StandardCharsets.UTF_8))
                + "\",\"size\":1,\"ref\":\"blobs/" + RecoveryArchiveFormat.sha256("x".getBytes(StandardCharsets.UTF_8)) + ".json\"}";
        Map<String, String> backup = new HashMap<>();
        backup.put("public", "{\"exists\":false,\"blob\":null}");
        String index = RecoveryArchiveFormat.serializeIndex(10, "{\"savedAt\":\"now\"}", "null", null,
                Collections.singletonList(ref), "null", "null", backup, "identity");
        assertTrue(index.startsWith("{\"version\":2,\"createdAt\":10,"));
        assertTrue(index.contains("\"sources\":[{"));
        assertFalse(index.contains("\"raw\""));

        String a = RecoveryArchiveFormat.identity(Arrays.asList("b", "a"), "state", null, backup);
        String b = RecoveryArchiveFormat.identity(Arrays.asList("a", "b"), "state", null, backup);
        assertEquals(a, b);
        String legacy = "{\"version\":1,\"createdAt\":3}";
        assertTrue(RecoveryArchiveFormat.isLegacyV1(legacy));
        assertEquals(legacy, RecoveryArchiveFormat.legacyExport(legacy));
        assertFalse(RecoveryArchiveFormat.isLegacyV1("{\"version\":2,\"createdAt\":3}"));

        File temp = Files.createTempFile("recovery-format", ".json").toFile();
        Files.write(temp.toPath(), index.getBytes(StandardCharsets.UTF_8));
        assertEquals(index, new String(RecoveryArchiveFormat.readFile(temp), StandardCharsets.UTF_8));
        assertTrue(temp.delete());
    }
}
