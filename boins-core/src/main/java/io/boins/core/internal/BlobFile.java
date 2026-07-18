package io.boins.core.internal;

import io.boins.core.BoinsOptions.DiskType;

import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * One large file storing many blobs back to back.
 *
 * <p><b>Writes.</b> A writer first {@linkplain #reserve reserves} a region, then streams
 * content into it. On SSD, multiple writers stream concurrently with positional writes;
 * on HDD, the streaming phase is serialized to keep the access pattern sequential.</p>
 *
 * <p><b>Reads.</b> Positional reads over the shared channel — no per-read file handles,
 * no interference with writes.</p>
 */
public final class BlobFile implements Closeable {

    private static final int COPY_BUFFER_SIZE = 1 << 16; // 64 KiB
    private static final int MAX_ZERO_WRITES = 1_000;

    private final Path path;
    private final short index;
    private final DiskType diskType;
    private final FileChannel channel;
    private final ReentrantLock hddWriteLock = new ReentrantLock();
    /** Expected file size after all in-flight reserved writes complete. */
    private final AtomicLong reservedSize = new AtomicLong();
    private volatile boolean dirty;

    BlobFile(Path path, short index, DiskType diskType) throws IOException {
        this.path = path;
        this.index = index;
        this.diskType = diskType;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.reservedSize.set(channel.size());
    }

    public Path path() {
        return path;
    }

    public short index() {
        return index;
    }

    /** Expected size after all pending writes; used for append planning. */
    public long size() {
        return reservedSize.get();
    }

    /** Reserves {@code length} bytes at the end of the file and returns the region start. */
    public long reserve(long length) {
        return reservedSize.getAndAdd(length);
    }

    /**
     * Streams exactly {@code length} bytes from {@code in} into the file at {@code position},
     * updating {@code digest} (if non-null) along the way.
     *
     * @throws EOFException if the stream ends before {@code length} bytes are read
     */
    public void write(long position, InputStream in, long length, MessageDigest digest) throws IOException {
        copy(position, in, length, digest);
        dirty = true;
    }

    /** Opens a stream over {@code length} bytes starting at {@code position}. */
    public InputStream openStream(long position, long length) {
        return new ChannelRangeInputStream(channel, position, length);
    }

    public void force() throws IOException {
        if (dirty) {
            dirty = false;
            channel.force(false);
        }
    }

    @Override
    public void close() throws IOException {
        if (channel.isOpen()) {
            force();
            channel.close();
        }
    }

    private void copy(long position, InputStream in, long length, MessageDigest digest) throws IOException {
        byte[] buffer = new byte[(int) Math.min(COPY_BUFFER_SIZE, Math.max(length, 1L))];
        long copied = 0L;
        while (copied < length) {
            checkInterrupted();
            int toRead = (int) Math.min(buffer.length, length - copied);
            int n = in.read(buffer, 0, toRead);
            if (n < 0) {
                throw new EOFException("Source stream ended after " + copied + " of " + length + " bytes");
            }
            if (digest != null) {
                digest.update(buffer, 0, n);
            }
            // On HDD, serialize per chunk (not per blob): whole-stream locking would let one
            // slow network client stall every writer of this file, while per-chunk locking
            // still keeps individual disk writes mostly sequential.
            if (diskType == DiskType.HDD) {
                hddWriteLock.lock();
                try {
                    writeFully(ByteBuffer.wrap(buffer, 0, n), position + copied);
                } finally {
                    hddWriteLock.unlock();
                }
            } else {
                writeFully(ByteBuffer.wrap(buffer, 0, n), position + copied);
            }
            copied += n;
        }
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        long written = 0L;
        int zeroWrites = 0;
        while (buffer.hasRemaining()) {
            checkInterrupted();
            int n = channel.write(buffer, position + written);
            if (n == 0 && ++zeroWrites >= MAX_ZERO_WRITES) {
                throw new IOException("Too many zero-length writes to blob file " + path);
            }
            written += n;
        }
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.interrupted()) {
            throw new InterruptedIOException("Thread interrupted during blob file write");
        }
    }
}
