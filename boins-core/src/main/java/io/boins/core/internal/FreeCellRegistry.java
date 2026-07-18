package io.boins.core.internal;

import io.boins.core.FreeCellsState;
import io.boins.core.StorageCorruptedException;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Persistent registry of free cells — regions of deleted blobs available for reuse.
 *
 * <p>The file is a sequence of 17-byte slots: {@code blobId(8) + cellSize(8) + tombstone(1)}.
 * Consumed slots are tombstoned in place and their positions recycled for future entries,
 * so the file does not grow while the working set is stable.</p>
 *
 * <p>{@link #pop} implements best-fit with a size tolerance: a new blob may take a larger
 * cell only if the wasted fraction is small enough for that cell size (bigger cells demand
 * a tighter fit). This bounds internal fragmentation.</p>
 *
 * <p>Crash consistency: the registry is intentionally allowed to be stale — on startup the
 * owner must cross-check every entry against the index and drop entries whose blob is alive
 * (see {@code BoinsImpl}). This removes any write-ordering requirements.</p>
 */
public final class FreeCellRegistry implements Closeable {

    static final int SLOT_SIZE = Long.BYTES + Long.BYTES + 1;

    private final Path path;
    private final FileChannel channel;
    private final ReentrantLock lock = new ReentrantLock();
    private final TreeMap<Long, LinkedHashSet<Long>> bySize = new TreeMap<>();
    private final Map<Long, Cell> byId = new HashMap<>();
    private final ArrayDeque<Long> freeSlots = new ArrayDeque<>();
    private long fileSize;
    private volatile boolean dirty;

    private record Cell(long size, long slotPosition) {
    }

    public static FreeCellRegistry open(Path path) throws IOException, StorageCorruptedException {
        return new FreeCellRegistry(path);
    }

    private FreeCellRegistry(Path path) throws IOException, StorageCorruptedException {
        this.path = path;
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        load();
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        this.fileSize = channel.size();
    }

    /** Registers the cell of a deleted blob for reuse. Re-registering an id updates its size. */
    public void put(long blobId, long cellSize) throws IOException {
        if (blobId < 0L) {
            throw new IllegalArgumentException("blobId must be >= 0, got " + blobId);
        }
        if (cellSize <= 0L) {
            throw new IllegalArgumentException("cellSize must be > 0, got " + cellSize + " (blobId=" + blobId + ")");
        }
        lock.lock();
        try {
            Cell existing = byId.get(blobId);
            if (existing != null) {
                if (existing.size == cellSize) {
                    return;
                }
                removeFromSizeMap(blobId, existing.size);
                writeSlot(existing.slotPosition, blobId, cellSize, false);
                index(blobId, cellSize, existing.slotPosition);
            } else {
                Long slot = freeSlots.poll();
                long position = slot != null ? slot : fileSize;
                writeSlot(position, blobId, cellSize, false);
                if (slot == null) {
                    fileSize += SLOT_SIZE;
                }
                index(blobId, cellSize, position);
            }
            dirty = true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the best-fitting cell for a blob of {@code blobSize} bytes, or empty if no cell
     * fits within tolerance. The caller owns the returned cell exclusively; if the write
     * fails, the caller should {@link #put} it back.
     */
    public OptionalLong pop(long blobSize) throws IOException {
        if (blobSize <= 0L) {
            return OptionalLong.empty();
        }
        lock.lock();
        try {
            Map.Entry<Long, LinkedHashSet<Long>> entry = bySize.ceilingEntry(blobSize);
            if (entry == null || !fitsWithTolerance(entry.getKey(), blobSize)) {
                return OptionalLong.empty();
            }
            LinkedHashSet<Long> ids = entry.getValue();
            Long blobId = ids.iterator().next();
            consume(blobId, entry.getKey(), ids);
            return OptionalLong.of(blobId);
        } finally {
            lock.unlock();
        }
    }

    /** Drops a specific entry (startup cross-check against the index). No-op if absent. */
    public void remove(long blobId) throws IOException {
        lock.lock();
        try {
            Cell cell = byId.get(blobId);
            if (cell == null) {
                return;
            }
            LinkedHashSet<Long> ids = bySize.get(cell.size);
            consume(blobId, cell.size, ids);
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of registered blob ids (for the startup cross-check). */
    public long[] snapshotIds() {
        lock.lock();
        try {
            long[] ids = new long[byId.size()];
            int i = 0;
            for (Long id : byId.keySet()) {
                ids[i++] = id;
            }
            return ids;
        } finally {
            lock.unlock();
        }
    }

    public FreeCellsState state() {
        lock.lock();
        try {
            return new FreeCellsState(
                    byId.size(),
                    freeSlots.size(),
                    bySize.isEmpty() ? -1L : bySize.firstKey(),
                    bySize.isEmpty() ? -1L : bySize.lastKey(),
                    fileSize);
        } finally {
            lock.unlock();
        }
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

    // ---------------------------------------------------------------- fit tolerance

    /**
     * Maximum acceptable wasted fraction for a cell of {@code cellSize} bytes:
     * {@code 1 / exp(1.7 * (log2(cellSize) / 10) ^ 0.7)}.
     * Small cells tolerate generous waste (a 1 KiB cell ~53%), large cells demand a tight
     * fit (a 1 GiB cell ~7%), which keeps absolute waste bounded.
     */
    static double maxWasteFraction(long cellSize) {
        if (cellSize <= 0L) {
            return 0.0D;
        }
        double log2 = Math.log(cellSize) / Math.log(2.0D);
        return 1.0D / Math.exp(1.7D * Math.pow(log2 / 10.0D, 0.7D));
    }

    static boolean fitsWithTolerance(long cellSize, long blobSize) {
        if (blobSize <= 0L || cellSize < blobSize) {
            return false;
        }
        double wasted = (cellSize - blobSize) / (double) cellSize;
        return wasted <= maxWasteFraction(cellSize);
    }

    // ---------------------------------------------------------------- internals

    private void consume(long blobId, long cellSize, LinkedHashSet<Long> ids) throws IOException {
        Cell cell = byId.remove(blobId);
        ids.remove(blobId);
        if (ids.isEmpty()) {
            bySize.remove(cellSize);
        }
        writeTombstone(cell.slotPosition);
        freeSlots.add(cell.slotPosition);
        dirty = true;
    }

    private void index(long blobId, long cellSize, long slotPosition) {
        bySize.computeIfAbsent(cellSize, s -> new LinkedHashSet<>()).add(blobId);
        byId.put(blobId, new Cell(cellSize, slotPosition));
    }

    private void removeFromSizeMap(long blobId, long cellSize) {
        LinkedHashSet<Long> ids = bySize.get(cellSize);
        if (ids != null) {
            ids.remove(blobId);
            if (ids.isEmpty()) {
                bySize.remove(cellSize);
            }
        }
    }

    private void writeSlot(long position, long blobId, long cellSize, boolean tombstone) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(SLOT_SIZE);
        buffer.putLong(blobId).putLong(cellSize).put((byte) (tombstone ? 1 : 0)).flip();
        long written = 0L;
        while (buffer.hasRemaining()) {
            written += channel.write(buffer, position + written);
        }
    }

    private void writeTombstone(long slotPosition) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put((byte) 1).flip();
        while (buffer.hasRemaining()) {
            channel.write(buffer, slotPosition + SLOT_SIZE - 1);
        }
    }

    private void load() throws IOException, StorageCorruptedException {
        if (!Files.exists(path)) {
            return;
        }
        long size = Files.size(path);
        if (size % SLOT_SIZE != 0L) {
            throw new StorageCorruptedException("Free-cell registry size " + size
                    + " is not a multiple of " + SLOT_SIZE + ": " + path);
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
            long position = 0L;
            while (position < size) {
                long blobId = in.readLong();
                long cellSize = in.readLong();
                boolean tombstone = in.readBoolean();
                if (tombstone) {
                    freeSlots.add(position);
                } else if (blobId < 0L || cellSize <= 0L) {
                    throw new StorageCorruptedException("Invalid free-cell slot at position " + position
                            + " (blobId=" + blobId + ", cellSize=" + cellSize + "): " + path);
                } else {
                    index(blobId, cellSize, position);
                }
                position += SLOT_SIZE;
            }
        }
    }
}
