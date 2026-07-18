package io.boins.core.internal;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * Read-only stream over a byte range of a shared {@link FileChannel}.
 *
 * <p>Uses positional reads, which do not touch the channel position and are safe to run
 * concurrently with writes and other readers on the same channel. Closing this stream does
 * <em>not</em> close the shared channel. Replaces the per-read file handle of the old
 * {@code PartialFileInputStream}.</p>
 */
public final class ChannelRangeInputStream extends InputStream {

    private final FileChannel channel;
    private long position;
    private long remaining;

    public ChannelRangeInputStream(FileChannel channel, long position, long length) {
        if (position < 0L || length < 0L) {
            throw new IllegalArgumentException("Negative position or length: position=" + position + ", length=" + length);
        }
        this.channel = channel;
        this.position = position;
        this.remaining = length;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (remaining <= 0L) {
            return -1;
        }
        int toRead = (int) Math.min(len, remaining);
        if (toRead == 0) {
            return 0;
        }
        int n = channel.read(ByteBuffer.wrap(b, off, toRead), position);
        if (n < 0) {
            throw new EOFException("Blob file ended before the expected range end: position=" + position
                    + ", remaining=" + remaining);
        }
        position += n;
        remaining -= n;
        return n;
    }

    @Override
    public long skip(long n) {
        long skipped = Math.min(Math.max(n, 0L), remaining);
        position += skipped;
        remaining -= skipped;
        return skipped;
    }

    @Override
    public int available() {
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }
}
