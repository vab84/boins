package io.boins.core.faults;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaultStoreTest {

    @TempDir
    Path tempDir;

    private long fileCount() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    /** Exceptions created here have identical top frames, so we vary the type or creation site. */
    private static IOException sameSiteException(String message) {
        return new IOException(message);
    }

    @Test
    void firstOccurrenceIsPersisted() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            store.record(sameSiteException("disk failed"), Map.of("path", "/data"));
            assertEquals(1L, fileCount());
            FaultSummary summary = store.list().getFirst();
            assertEquals("java.io.IOException", summary.type());
            assertEquals(1L, summary.count());
            String report = store.reportText(summary.dedupHash());
            assertNotNull(report);
            assertTrue(report.contains("disk failed"));
            assertTrue(report.contains("path: /data"));
            assertTrue(report.contains("--- stack trace ---"));
        }
    }

    @Test
    void duplicatesAreRateLimited() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir, 5, 60_000L, 100)) {
            for (int i = 0; i < 50; i++) {
                store.record(sameSiteException("same fault " + i));
            }
            assertEquals(1L, fileCount(), "duplicates must share one report file");
            assertEquals(50L, store.list().getFirst().count());
        }
        // close() persists the final count even though rewrites were rate-limited
        try (FaultStore reopened = FaultStore.open(tempDir)) {
            assertEquals(50L, reopened.list().getFirst().count());
        }
    }

    @Test
    void differentTypesGetDifferentFiles() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            store.record(new IOException("io"));
            store.record(new IllegalStateException("state"));
            assertEquals(2L, fileCount());
            assertEquals(2, store.list().size());
        }
    }

    @Test
    void dedupKeyUsesStackFrames() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            IOException a = createAtSiteA();
            IOException b = createAtSiteB();
            store.record(a);
            store.record(b);
            assertEquals(2, store.list().size(), "different creation sites must not dedup together");
            assertNotEquals(store.list().get(0).dedupHash(), store.list().get(1).dedupHash());
        }
    }

    private IOException createAtSiteA() {
        return new IOException("same message");
    }

    private IOException createAtSiteB() {
        return new IOException("same message");
    }

    @Test
    void listenersReceiveEveryOccurrence() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir, 5, 60_000L, 100)) {
            List<FaultEvent> events = new ArrayList<>();
            store.addListener(events::add);
            // Create all exceptions at one code line so they share a deduplication key.
            for (int i = 0; i < 3; i++) {
                store.record(sameSiteException("x"));
            }
            assertEquals(3, events.size());
            assertTrue(events.get(0).persisted());
            assertFalse(events.get(1).persisted(), "rate-limited duplicate must not be persisted");
            assertEquals(3L, events.get(2).occurrences());
            assertEquals(events.get(0).dedupHash(), events.get(2).dedupHash());
        }
    }

    @Test
    void listenerFailureDoesNotBreakRecording() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            store.addListener(e -> {
                throw new RuntimeException("bad listener");
            });
            store.record(sameSiteException("still recorded"));
            assertEquals(1, store.list().size());
        }
    }

    @Test
    void stateRestoredOnReopen() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            store.record(sameSiteException("persisted fault"));
        }
        try (FaultStore reopened = FaultStore.open(tempDir)) {
            FaultSummary summary = reopened.list().getFirst();
            assertEquals("java.io.IOException", summary.type());
            assertEquals(1L, summary.count());
            assertEquals("persisted fault", summary.message());
        }
    }

    @Test
    void maxFilesEvictsOldest() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir, 5, 0L, 3)) {
            store.record(new IOException("a"));
            store.record(new IllegalStateException("b"));
            store.record(new IllegalArgumentException("c"));
            store.record(new UnsupportedOperationException("d"));
            assertEquals(3, store.list().size());
            assertEquals(3L, fileCount());
        }
    }

    @Test
    void nullThrowableIsIgnored() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir)) {
            store.record(null);
            assertEquals(0, store.list().size());
        }
    }

    @Test
    void cooldownZeroRewritesEveryTime() throws Exception {
        try (FaultStore store = FaultStore.open(tempDir, 5, 0L, 100)) {
            for (int i = 1; i <= 2; i++) {
                store.record(sameSiteException("v" + i));
            }
            FaultSummary summary = store.list().getFirst();
            assertEquals(2L, summary.count());
            String report = store.reportText(summary.dedupHash());
            assertTrue(report.contains("count: 2"));
        }
    }
}
