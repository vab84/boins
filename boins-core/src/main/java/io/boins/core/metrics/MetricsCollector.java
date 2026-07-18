package io.boins.core.metrics;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects storage metrics: cumulative counters and sliding-window rates.
 *
 * <p>Counters are persisted to a properties file (on the storage file pool) and loaded
 * back on start, so totals accumulate across restarts. Persistence is driven externally:
 * the owner calls {@link #persist()} periodically and on shutdown.</p>
 *
 * <p>All recording methods are thread-safe and cheap (LongAdder + lock-free window).</p>
 */
public final class MetricsCollector {

    private static final int WINDOW_SECONDS = 60;
    private static final int RATE_SECONDS = 10;

    private final Path file;

    // Base values loaded from the metrics file (accumulated by previous runs).
    private long baseCollectedSince = System.currentTimeMillis();
    private long baseBytesWritten;
    private long baseBytesRead;
    private long baseBlobsWritten;
    private long baseBlobsRead;
    private long baseBlobsDeleted;
    private long baseWriteErrors;
    private long baseReadErrors;
    private long baseWriteNanos;
    private long baseReadNanos;

    // Deltas recorded by this run.
    private final LongAdder bytesWritten = new LongAdder();
    private final LongAdder bytesRead = new LongAdder();
    private final LongAdder blobsWritten = new LongAdder();
    private final LongAdder blobsRead = new LongAdder();
    private final LongAdder blobsDeleted = new LongAdder();
    private final LongAdder writeErrors = new LongAdder();
    private final LongAdder readErrors = new LongAdder();
    private final LongAdder writeNanos = new LongAdder();
    private final LongAdder readNanos = new LongAdder();

    private final RateWindow writeBytesWindow = new RateWindow(WINDOW_SECONDS);
    private final RateWindow readBytesWindow = new RateWindow(WINDOW_SECONDS);
    private final RateWindow writeOpsWindow = new RateWindow(WINDOW_SECONDS);
    private final RateWindow readOpsWindow = new RateWindow(WINDOW_SECONDS);

    /**
     * Creates a collector persisting to {@code file}. Existing accumulated values are loaded.
     *
     * @param file metrics file; may be {@code null} for a purely in-memory collector
     */
    public MetricsCollector(Path file) throws IOException {
        this.file = file;
        if (file != null && Files.exists(file)) {
            load();
        }
    }

    /** Records a completed write: totals, timing and rate windows. */
    public void recordWrite(long bytes, long nanos) {
        bytesWritten.add(bytes);
        blobsWritten.increment();
        writeNanos.add(nanos);
        writeBytesWindow.add(bytes);
        writeOpsWindow.add(1L);
    }

    /**
     * Records a completed read: totals, timing and the ops window. Byte throughput is fed
     * incrementally by {@link #recordReadProgress} while the stream is consumed, so it is
     * intentionally not added to the byte window here.
     */
    public void recordRead(long bytes, long nanos) {
        bytesRead.add(bytes);
        blobsRead.increment();
        readNanos.add(nanos);
        readOpsWindow.add(1L);
    }

    /** Records bytes flowing through an open read stream (byte throughput window only). */
    public void recordReadProgress(long bytes) {
        readBytesWindow.add(bytes);
    }

    public void recordDelete() {
        blobsDeleted.increment();
    }

    public void recordWriteError() {
        writeErrors.increment();
    }

    public void recordReadError() {
        readErrors.increment();
    }

    public MetricsSnapshot snapshot() {
        long totalWriteNanos = baseWriteNanos + writeNanos.sum();
        long totalReadNanos = baseReadNanos + readNanos.sum();
        long totBytesWritten = baseBytesWritten + bytesWritten.sum();
        long totBytesRead = baseBytesRead + bytesRead.sum();
        return new MetricsSnapshot(
                baseCollectedSince,
                totBytesWritten,
                totBytesRead,
                baseBlobsWritten + blobsWritten.sum(),
                baseBlobsRead + blobsRead.sum(),
                baseBlobsDeleted + blobsDeleted.sum(),
                baseWriteErrors + writeErrors.sum(),
                baseReadErrors + readErrors.sum(),
                writeBytesWindow.sum(RATE_SECONDS) / RATE_SECONDS,
                readBytesWindow.sum(RATE_SECONDS) / RATE_SECONDS,
                writeOpsWindow.sum(RATE_SECONDS) / (double) RATE_SECONDS,
                readOpsWindow.sum(RATE_SECONDS) / (double) RATE_SECONDS,
                perSecond(totBytesWritten, totalWriteNanos),
                perSecond(totBytesRead, totalReadNanos));
    }

    private static long perSecond(long bytes, long nanos) {
        if (nanos <= 0L) {
            return 0L;
        }
        return (long) (bytes / (nanos / 1_000_000_000.0));
    }

    /**
     * Persists cumulative counters atomically (temp file + move).
     */
    public synchronized void persist() throws IOException {
        if (file == null) {
            return;
        }
        Properties p = new Properties();
        MetricsSnapshot s = snapshot();
        p.setProperty("collectedSince", Long.toString(s.collectedSince()));
        p.setProperty("bytesWritten", Long.toString(s.bytesWritten()));
        p.setProperty("bytesRead", Long.toString(s.bytesRead()));
        p.setProperty("blobsWritten", Long.toString(s.blobsWritten()));
        p.setProperty("blobsRead", Long.toString(s.blobsRead()));
        p.setProperty("blobsDeleted", Long.toString(s.blobsDeleted()));
        p.setProperty("writeErrors", Long.toString(s.writeErrors()));
        p.setProperty("readErrors", Long.toString(s.readErrors()));
        p.setProperty("writeNanos", Long.toString(baseWriteNanos + writeNanos.sum()));
        p.setProperty("readNanos", Long.toString(baseReadNanos + readNanos.sum()));
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (OutputStream out = Files.newOutputStream(tmp)) {
            p.store(out, "Boins metrics");
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private void load() throws IOException {
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            p.load(in);
        }
        baseCollectedSince = parse(p, "collectedSince", baseCollectedSince);
        baseBytesWritten = parse(p, "bytesWritten", 0L);
        baseBytesRead = parse(p, "bytesRead", 0L);
        baseBlobsWritten = parse(p, "blobsWritten", 0L);
        baseBlobsRead = parse(p, "blobsRead", 0L);
        baseBlobsDeleted = parse(p, "blobsDeleted", 0L);
        baseWriteErrors = parse(p, "writeErrors", 0L);
        baseReadErrors = parse(p, "readErrors", 0L);
        baseWriteNanos = parse(p, "writeNanos", 0L);
        baseReadNanos = parse(p, "readNanos", 0L);
    }

    private static long parse(Properties p, String key, long fallback) {
        String v = p.getProperty(key);
        if (v == null) {
            return fallback;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
