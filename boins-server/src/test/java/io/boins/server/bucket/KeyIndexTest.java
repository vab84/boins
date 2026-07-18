package io.boins.server.bucket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeyIndexTest {

    @TempDir
    Path tempDir;

    private Path file() {
        return tempDir.resolve("keys.boins");
    }

    @Test
    void putGetRemove() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            assertNull(index.put("docs/a.txt", 1L));
            assertEquals(1L, index.get("docs/a.txt"));
            assertEquals(1L, index.put("docs/a.txt", 2L)); // overwrite returns previous
            assertEquals(2L, index.get("docs/a.txt"));
            assertEquals(2L, index.remove("docs/a.txt"));
            assertNull(index.get("docs/a.txt"));
            assertNull(index.remove("docs/a.txt"));
            assertEquals(0, index.size());
        }
    }

    @Test
    void persistsAcrossReopen() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            index.put("k1", 10L);
            index.put("k2", 20L);
            index.put("ключ/с юникодом", 30L);
            index.remove("k1");
        }
        try (KeyIndex index = KeyIndex.open(file())) {
            assertNull(index.get("k1"));
            assertEquals(20L, index.get("k2"));
            assertEquals(30L, index.get("ключ/с юникодом"));
            assertEquals(2, index.size());
        }
    }

    @Test
    void tornTailIsTruncated() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            index.put("stable", 1L);
        }
        // simulate a crash mid-append: garbage half-record at the end
        Files.write(file(), new byte[]{1, 2, 3, 4, 5}, StandardOpenOption.APPEND);
        try (KeyIndex index = KeyIndex.open(file())) {
            assertEquals(1L, index.get("stable"));
            index.put("after-recovery", 2L); // appending after truncation works
        }
        try (KeyIndex index = KeyIndex.open(file())) {
            assertEquals(1L, index.get("stable"));
            assertEquals(2L, index.get("after-recovery"));
        }
    }

    @Test
    void compactionShrinksDominatedLog() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            // 5000 puts + 4990 deletes → log dominated by dead records → compaction
            for (int i = 0; i < 5_000; i++) {
                index.put("key-" + i, i);
            }
            for (int i = 0; i < 4_990; i++) {
                index.remove("key-" + i);
            }
            assertEquals(10, index.size());
        }
        long fileSize = Files.size(file());
        // Without compaction the log would hold all 9990 records (~300 KB). Compaction
        // keeps it bounded; the exact size depends on where the last compaction fired
        // relative to the COMPACT_MIN_RECORDS threshold.
        assertTrue(fileSize < 100_000, "log should have been compacted, size=" + fileSize);
        try (KeyIndex index = KeyIndex.open(file())) {
            assertEquals(10, index.size());
            assertEquals(4_999L, index.get("key-4999"));
        }
    }

    @Test
    void sortedViewIsSortedAndReadOnly() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            index.put("b", 2L);
            index.put("a", 1L);
            index.put("c", 3L);
            assertEquals("a", index.sortedView().firstKey());
            assertEquals("c", index.sortedView().lastKey());
            assertThrows(UnsupportedOperationException.class, () -> index.sortedView().put("x", 9L));
        }
    }

    @Test
    void invalidKeysRejected() throws Exception {
        try (KeyIndex index = KeyIndex.open(file())) {
            assertThrows(IllegalArgumentException.class, () -> index.put(null, 1L));
            assertThrows(IllegalArgumentException.class, () -> index.put("", 1L));
            assertThrows(IllegalArgumentException.class, () -> index.put("x".repeat(70_000), 1L));
        }
    }
}
