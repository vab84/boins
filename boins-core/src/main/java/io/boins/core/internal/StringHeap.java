package io.boins.core.internal;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Append-only heap of length-prefixed UTF-8 strings (blob keys, encoded user metadata).
 *
 * <p>Each entry is a 2-byte unsigned length followed by the UTF-8 bytes; the entry position
 * is its stable identifier. Strings are never rewritten or removed.</p>
 */
public final class StringHeap implements Closeable {

    /** Maximum encoded string size (unsigned 16-bit length prefix). */
    public static final int MAX_BYTES = 65_535;

    private final Path path;
    private final FileChannel channel;
    private final AtomicLong size = new AtomicLong();
    private final ReentrantLock appendLock = new ReentrantLock();
    private volatile boolean dirty;

    StringHeap(Path path) throws IOException {
        this.path = path;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.size.set(channel.size());
    }

    /**
     * Appends a string and returns its heap position, or {@code -1} for null/empty input.
     *
     * @throws IllegalArgumentException if the UTF-8 encoding exceeds {@link #MAX_BYTES}
     */
    public long add(String s) throws IOException {
        if (s == null || s.isEmpty()) {
            return IndexRecord.NO_HEAP_POSITION;
        }
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_BYTES) {
            throw new IllegalArgumentException("String is too long for the heap: " + utf8.length
                    + " bytes (max " + MAX_BYTES + ")");
        }
        ByteBuffer buffer = ByteBuffer.allocate(Short.BYTES + utf8.length);
        buffer.putShort((short) utf8.length).put(utf8).flip();
        appendLock.lock();
        try {
            long position = size.get();
            long written = 0L;
            while (buffer.hasRemaining()) {
                written += channel.write(buffer, position + written);
            }
            size.set(position + buffer.capacity());
            dirty = true;
            return position;
        } finally {
            appendLock.unlock();
        }
    }

    /** Reads the string at {@code position}, or {@code null} for a negative position. */
    public String read(long position) throws IOException {
        if (position < 0L) {
            return null;
        }
        long fileSize = size.get();
        if (position + Short.BYTES > fileSize) {
            throw new IOException("String heap position " + position + " is beyond file size " + fileSize + ": " + path);
        }
        ByteBuffer lenBuffer = ByteBuffer.allocate(Short.BYTES);
        readFully(lenBuffer, position);
        int length = lenBuffer.flip().getShort() & 0xFFFF;
        if (position + Short.BYTES + length > fileSize) {
            throw new IOException("String heap entry at " + position + " (length " + length
                    + ") is beyond file size " + fileSize + ": " + path);
        }
        ByteBuffer data = ByteBuffer.allocate(length);
        readFully(data, position + Short.BYTES);
        return new String(data.array(), StandardCharsets.UTF_8);
    }

    public long sizeBytes() {
        return size.get();
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

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        long read = 0L;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position + read);
            if (n < 0) {
                throw new IOException("Unexpected end of string heap " + path + " at position " + (position + read));
            }
            read += n;
        }
    }
}
