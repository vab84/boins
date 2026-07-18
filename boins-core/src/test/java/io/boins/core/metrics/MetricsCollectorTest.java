package io.boins.core.metrics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsCollectorTest {

    @TempDir
    Path tempDir;

    @Test
    void countersAccumulate() throws Exception {
        MetricsCollector collector = new MetricsCollector(null);
        collector.recordWrite(1_000L, 1_000_000L);
        collector.recordWrite(2_000L, 1_000_000L);
        collector.recordRead(500L, 2_000_000L);
        collector.recordDelete();
        collector.recordWriteError();
        collector.recordReadError();
        MetricsSnapshot s = collector.snapshot();
        assertEquals(3_000L, s.bytesWritten());
        assertEquals(500L, s.bytesRead());
        assertEquals(2L, s.blobsWritten());
        assertEquals(1L, s.blobsRead());
        assertEquals(1L, s.blobsDeleted());
        assertEquals(1L, s.writeErrors());
        assertEquals(1L, s.readErrors());
    }

    @Test
    void averageSpeedFromActiveTime() throws Exception {
        MetricsCollector collector = new MetricsCollector(null);
        // 10 MB written in 1 second of active write time → 10 MB/s
        collector.recordWrite(10_000_000L, 1_000_000_000L);
        assertEquals(10_000_000L, collector.snapshot().avgWriteBytesPerSecond());
        // no reads yet → zero, not division by zero
        assertEquals(0L, collector.snapshot().avgReadBytesPerSecond());
    }

    @Test
    void persistAndReload() throws Exception {
        Path file = tempDir.resolve("metrics.boins");
        MetricsCollector first = new MetricsCollector(file);
        first.recordWrite(4_242L, 7_000_000L);
        first.recordRead(100L, 1_000_000L);
        first.persist();
        assertTrue(Files.exists(file));

        MetricsCollector second = new MetricsCollector(file);
        MetricsSnapshot s = second.snapshot();
        assertEquals(4_242L, s.bytesWritten());
        assertEquals(100L, s.bytesRead());
        assertEquals(1L, s.blobsWritten());

        // New activity adds on top of the loaded base.
        second.recordWrite(8L, 1_000L);
        assertEquals(4_250L, second.snapshot().bytesWritten());
        assertEquals(2L, second.snapshot().blobsWritten());
    }

    @Test
    void collectedSinceSurvivesReload() throws Exception {
        Path file = tempDir.resolve("metrics.boins");
        MetricsCollector first = new MetricsCollector(file);
        long since = first.snapshot().collectedSince();
        first.persist();
        Thread.sleep(5L);
        MetricsCollector second = new MetricsCollector(file);
        assertEquals(since, second.snapshot().collectedSince());
    }

    @Test
    void corruptedFileFallsBackToDefaults() throws Exception {
        Path file = tempDir.resolve("metrics.boins");
        Files.writeString(file, "bytesWritten=not-a-number\n");
        MetricsCollector collector = new MetricsCollector(file);
        assertEquals(0L, collector.snapshot().bytesWritten());
    }

    @Test
    void readProgressFeedsThroughputWindow() throws Exception {
        MetricsCollector collector = new MetricsCollector(null);
        collector.recordReadProgress(1_000L);
        collector.recordRead(1_000L, 1_000_000L);
        // totals must not double-count window feeds
        assertEquals(1_000L, collector.snapshot().bytesRead());
    }
}
