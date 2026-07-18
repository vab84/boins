package io.boins.core.faults;

import java.util.Map;

/**
 * Event delivered to {@link FaultStore} listeners on every recorded exception,
 * including duplicates suppressed by rate limiting.
 *
 * @param throwable   the recorded exception
 * @param context     additional context (e.g. HTTP request headers); never {@code null}
 * @param timeMillis  epoch millis of the occurrence
 * @param dedupHash   hash of the deduplication key (exception type + top stack frames)
 * @param occurrences total occurrences of this fault so far (including this one)
 * @param persisted   whether this occurrence caused a write to disk
 */
public record FaultEvent(
        Throwable throwable,
        Map<String, String> context,
        long timeMillis,
        String dedupHash,
        long occurrences,
        boolean persisted) {

    public FaultEvent {
        context = context == null ? Map.of() : Map.copyOf(context);
    }
}
