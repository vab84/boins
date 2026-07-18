package io.boins.core.metrics;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Lock-free sliding window of per-second buckets used to compute recent rates.
 *
 * <p>Accuracy is best-effort: a rare race on bucket rollover may drop a few counts,
 * which is an acceptable trade-off for a metrics hot path.</p>
 */
final class RateWindow {

    private final int size;
    private final AtomicLongArray counts;
    private final AtomicLongArray stamps;

    RateWindow(int seconds) {
        this.size = seconds;
        this.counts = new AtomicLongArray(seconds);
        this.stamps = new AtomicLongArray(seconds);
    }

    void add(long n) {
        long sec = System.currentTimeMillis() / 1000L;
        int i = (int) (sec % size);
        long stamp = stamps.get(i);
        if (stamp != sec && stamps.compareAndSet(i, stamp, sec)) {
            counts.set(i, 0L);
        }
        counts.addAndGet(i, n);
    }

    /**
     * Sum of counts recorded during the last {@code seconds} full seconds
     * (excluding the current, still incomplete second).
     */
    long sum(int seconds) {
        long nowSec = System.currentTimeMillis() / 1000L;
        long from = nowSec - seconds;
        long sum = 0L;
        for (int i = 0; i < size; i++) {
            long stamp = stamps.get(i);
            if (stamp >= from && stamp < nowSec) {
                sum += counts.get(i);
            }
        }
        return sum;
    }
}
