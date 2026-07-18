package io.boins.core.faults;

/**
 * Summary of one deduplicated fault, as shown in the admin interface.
 *
 * @param dedupHash  hash of the deduplication key
 * @param type       fully qualified exception class name
 * @param message    exception message of the most recent occurrence; may be {@code null}
 * @param count      total occurrences
 * @param firstSeen  epoch millis of the first occurrence
 * @param lastSeen   epoch millis of the latest occurrence
 * @param file       report file name inside the fault directory
 */
public record FaultSummary(
        String dedupHash,
        String type,
        String message,
        long count,
        long firstSeen,
        long lastSeen,
        String file) {
}
