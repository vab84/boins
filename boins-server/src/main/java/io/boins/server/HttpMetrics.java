package io.boins.server;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * HTTP-level counters shown in the admin interface, complementing the storage metrics
 * (which carry the byte traffic and throughput numbers).
 */
public final class HttpMetrics {

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder clientErrors = new LongAdder();
    private final LongAdder serverErrors = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> byOperation = new ConcurrentHashMap<>();

    public void record(String operation, int status) {
        totalRequests.increment();
        if (status >= 500) {
            serverErrors.increment();
        } else if (status >= 400) {
            clientErrors.increment();
        }
        byOperation.computeIfAbsent(operation, op -> new LongAdder()).increment();
    }

    public Snapshot snapshot() {
        Map<String, Long> ops = new TreeMap<>();
        byOperation.forEach((op, counter) -> ops.put(op, counter.sum()));
        return new Snapshot(totalRequests.sum(), clientErrors.sum(), serverErrors.sum(), ops);
    }

    public record Snapshot(long totalRequests, long clientErrors, long serverErrors,
                           Map<String, Long> requestsByOperation) {
    }
}
