package io.boins.core.metrics;

/**
 * Immutable snapshot of accumulated storage metrics.
 *
 * <p>Cumulative counters survive restarts (they are persisted to the metrics file and
 * loaded back on open). Rates are computed over a sliding window of recent seconds.</p>
 *
 * @param collectedSince        epoch millis when accumulation originally started (survives restarts)
 * @param bytesWritten          total content bytes written
 * @param bytesRead             total content bytes read
 * @param blobsWritten          total write operations
 * @param blobsRead             total read operations
 * @param blobsDeleted          total delete operations
 * @param writeErrors           total failed writes
 * @param readErrors            total failed reads
 * @param writeBytesPerSecond   current write throughput (sliding window)
 * @param readBytesPerSecond    current read throughput (sliding window)
 * @param writeOpsPerSecond     current write operation rate (sliding window)
 * @param readOpsPerSecond      current read operation rate (sliding window)
 * @param avgWriteBytesPerSecond average write speed while writing (bytesWritten / active write time)
 * @param avgReadBytesPerSecond  average read speed while reading (bytesRead / active read time)
 */
public record MetricsSnapshot(
        long collectedSince,
        long bytesWritten,
        long bytesRead,
        long blobsWritten,
        long blobsRead,
        long blobsDeleted,
        long writeErrors,
        long readErrors,
        long writeBytesPerSecond,
        long readBytesPerSecond,
        double writeOpsPerSecond,
        double readOpsPerSecond,
        long avgWriteBytesPerSecond,
        long avgReadBytesPerSecond) {
}
