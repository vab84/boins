package io.boins.core.internal;

import io.boins.core.BlobNotFoundException;
import io.boins.core.StorageCorruptedException;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-record index file. Record ordinal + repository offset = blob id, so lookups are
 * a single positional read with no search.
 *
 * <p>Appends are serialized by a lock (records are tiny, contention is negligible) which
 * guarantees a hole-free, densely packed file. Point updates ({@link #markDeleted}) and
 * reads are positional and lock-free. The record count is cached — unlike the old
 * implementation, no file-size system call happens on the read path.</p>
 */
public final class IndexFile implements Closeable {

    private final Path path;
    private final FileChannel channel;
    private final long blobIdOffset;
    private final AtomicLong recordCount = new AtomicLong();
    private final ReentrantLock appendLock = new ReentrantLock();
    private volatile boolean dirty;

    IndexFile(Path path, long blobIdOffset) throws IOException, StorageCorruptedException {
        this.path = path;
        this.blobIdOffset = blobIdOffset;
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long size = channel.size();
        if (size % IndexRecord.SIZE != 0L) {
            throw new StorageCorruptedException("Index file size " + size + " is not a multiple of "
                    + IndexRecord.SIZE + ": " + path);
        }
        this.recordCount.set(size / IndexRecord.SIZE);
    }

    public long blobIdOffset() {
        return blobIdOffset;
    }

    /** Appends a record and returns the assigned blob id. */
    public long insert(IndexRecord record) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(IndexRecord.SIZE);
        record.writeTo(buffer);
        buffer.flip();
        appendLock.lock();
        try {
            long ordinal = recordCount.get();
            writeFully(buffer, ordinal * IndexRecord.SIZE);
            recordCount.set(ordinal + 1L);
            dirty = true;
            return blobIdOffset + ordinal;
        } finally {
            appendLock.unlock();
        }
    }

    /** Reads the record of {@code blobId}. */
    public IndexRecord read(long blobId) throws IOException, BlobNotFoundException, StorageCorruptedException {
        long position = positionOf(blobId);
        ByteBuffer buffer = ByteBuffer.allocate(IndexRecord.SIZE);
        readFully(buffer, position);
        buffer.flip();
        IndexRecord record = IndexRecord.readFrom(buffer);
        if (record.isHole()) {
            throw new StorageCorruptedException("Index record for blobId=" + blobId
                    + " is an unwritten hole (crash during insert?): " + path);
        }
        return record;
    }

    /**
     * Marks {@code blobId} deleted at {@code time} and returns the cell size of the record.
     * The caller is responsible for alive-state checking and synchronization per blob id.
     */
    public long markDeleted(long blobId, long time) throws IOException, BlobNotFoundException {
        long position = positionOf(blobId);
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(time).flip();
        writeFully(buffer, position + IndexRecord.OFFSET_DELETE_TIME);
        dirty = true;
        buffer.clear();
        readFully(buffer, position + IndexRecord.OFFSET_CELL_SIZE);
        buffer.flip();
        return buffer.getLong();
    }

    /**
     * Zeroes the cell size of a deleted record, marking its cell as consumed by a
     * replacement blob. Written together with the replacement insert so that a single
     * index fsync covers both; the startup cross-check drops free-cell registry entries
     * pointing at consumed records (see {@code BoinsImpl#validateFreeCells}).
     */
    public void markCellConsumed(long blobId) throws IOException, BlobNotFoundException {
        long position = positionOf(blobId);
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(0L).flip();
        writeFully(buffer, position + IndexRecord.OFFSET_CELL_SIZE);
        dirty = true;
    }

    public boolean contains(long blobId) {
        long ordinal = blobId - blobIdOffset;
        return ordinal >= 0L && ordinal < recordCount.get();
    }

    public long recordCount() {
        return recordCount.get();
    }

    /** Next blob id to be assigned. */
    public long nextBlobId() {
        return blobIdOffset + recordCount.get();
    }

    /** Highest assigned blob id, or {@code -1} when empty. */
    public long maxBlobId() {
        long count = recordCount.get();
        return count == 0L ? -1L : blobIdOffset + count - 1L;
    }

    public long sizeBytes() {
        return recordCount.get() * IndexRecord.SIZE;
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

    private long positionOf(long blobId) throws BlobNotFoundException {
        if (!contains(blobId)) {
            throw new BlobNotFoundException(blobId);
        }
        return (blobId - blobIdOffset) * IndexRecord.SIZE;
    }

    private void writeFully(ByteBuffer buffer, long position) throws IOException {
        long written = 0L;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer, position + written);
        }
    }

    private void readFully(ByteBuffer buffer, long position) throws IOException {
        long read = 0L;
        while (buffer.hasRemaining()) {
            int n = channel.read(buffer, position + read);
            if (n < 0) {
                throw new IOException("Unexpected end of index file " + path + " at position " + (position + read));
            }
            read += n;
        }
    }
}
