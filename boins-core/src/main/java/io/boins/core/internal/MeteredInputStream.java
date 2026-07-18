package io.boins.core.internal;

import io.boins.core.metrics.MetricsCollector;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps a blob read stream to feed metrics: byte throughput while the stream is consumed,
 * and a completed-read record (total bytes + duration) on close.
 */
final class MeteredInputStream extends FilterInputStream {

    private final MetricsCollector metrics;
    private final long startNanos = System.nanoTime();
    private long bytes;
    private boolean closed;

    MeteredInputStream(InputStream delegate, MetricsCollector metrics) {
        super(delegate);
        this.metrics = metrics;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            bytes++;
            metrics.recordReadProgress(1L);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            bytes += n;
            metrics.recordReadProgress(n);
        }
        return n;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            metrics.recordRead(bytes, System.nanoTime() - startNanos);
        }
        super.close();
    }
}
