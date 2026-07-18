package io.boins.core.internal;

import io.boins.core.BlobDeletedException;
import io.boins.core.BlobInfo;
import io.boins.core.BlobMetadata;
import io.boins.core.BlobNotFoundException;
import io.boins.core.Boins;
import io.boins.core.BoinsException;
import io.boins.core.BoinsOptions;
import io.boins.core.BoinsState;
import io.boins.core.StorageCorruptedException;
import io.boins.core.WriteResult;
import io.boins.core.faults.FaultStore;
import io.boins.core.metrics.MetricsCollector;
import io.boins.core.metrics.MetricsSnapshot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Default {@link Boins} implementation. See the package classes for the on-disk formats.
 *
 * <p>Concurrency model: index appends are serialized per repository (tiny records); blob
 * content is streamed concurrently on SSD and serialized per blob file on HDD; deletions
 * are guarded by striped per-blob-id locks so a check-then-delete is atomic (the old
 * implementation had a double-delete race that could hand the same cell to two writers).</p>
 */
public final class BoinsImpl implements Boins {

    private static final int DELETE_STRIPES = 256;

    private final BoinsOptions options;
    private final RepositorySet repositories;
    private final FreeCellRegistry freeCells;
    private final MetricsCollector metrics;
    private final FaultStore faults;
    private final ReentrantLock[] deleteStripes = new ReentrantLock[DELETE_STRIPES];
    private final ScheduledExecutorService scheduler;
    private volatile boolean closed;

    public BoinsImpl(BoinsOptions options) throws BoinsException {
        this.options = Objects.requireNonNull(options);
        this.faults = options.faultStore();
        for (int i = 0; i < DELETE_STRIPES; i++) {
            deleteStripes[i] = new ReentrantLock();
        }
        RepositorySet repos = null;
        FreeCellRegistry cells = null;
        try {
            repos = new RepositorySet(options);
            cells = FreeCellRegistry.open(options.freeCellsFile());
            this.repositories = repos;
            this.freeCells = cells;
            this.metrics = new MetricsCollector(options.metricsFile());
            validateFreeCells();
        } catch (IOException | BoinsException | RuntimeException e) {
            closeQuietly(cells);
            closeQuietly(repos);
            if (e instanceof BoinsException be) {
                throw be;
            }
            throw new BoinsException("Failed to open Boins storage", e);
        }
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "boins-maintenance");
            t.setDaemon(true);
            return t;
        });
        if (options.fsyncMode() == BoinsOptions.FsyncMode.INTERVAL) {
            scheduler.scheduleWithFixedDelay(this::safeFlush,
                    options.fsyncIntervalMillis(), options.fsyncIntervalMillis(), TimeUnit.MILLISECONDS);
        }
        scheduler.scheduleWithFixedDelay(this::safePersistMetrics,
                options.metricsFlushIntervalMillis(), options.metricsFlushIntervalMillis(), TimeUnit.MILLISECONDS);
    }

    // ---------------------------------------------------------------- write

    @Override
    public WriteResult write(byte[] blob, BlobMetadata metadata) throws BoinsException {
        Objects.requireNonNull(blob, "blob");
        return write(new ByteArrayInputStream(blob), blob.length, metadata);
    }

    @Override
    public WriteResult write(Path source, BlobMetadata metadata) throws BoinsException {
        Objects.requireNonNull(source, "source");
        try (InputStream in = Files.newInputStream(source)) {
            return write(in, Files.size(source), metadata);
        } catch (IOException e) {
            throw new BoinsException("Failed to read source file " + source, e);
        }
    }

    @Override
    public WriteResult write(InputStream in, long length, BlobMetadata metadata) throws BoinsException {
        return write(in, length, metadata, null, 0);
    }

    @Override
    public WriteResult write(InputStream in, long length, BlobMetadata metadata, byte[] md5, int partCount)
            throws BoinsException {
        Objects.requireNonNull(in, "in");
        BlobMetadata meta = metadata == null ? BlobMetadata.EMPTY : metadata;
        ensureOpen();
        if (length < 0L) {
            throw new BoinsException("Blob length must be >= 0, got " + length);
        }
        if (length > options.blobFileLimitBytes()) {
            throw new BoinsException("Blob length " + length + " exceeds the blob file limit 2^"
                    + options.blobFileLimit() + " bytes");
        }
        if (md5 != null && partCount < 0) {
            throw new BoinsException("partCount must be >= 0 when an md5 digest is supplied");
        }
        long startNanos = System.nanoTime();
        try {
            WriteResult result = writeInternal(in, length, meta, md5, partCount);
            metrics.recordWrite(length, System.nanoTime() - startNanos);
            return result;
        } catch (IOException e) {
            metrics.recordWriteError();
            recordFault(e, Map.of("operation", "write", "length", Long.toString(length)));
            throw new BoinsException("Failed to write blob of " + length + " bytes", e);
        } catch (BoinsException e) {
            metrics.recordWriteError();
            throw e;
        }
    }

    private WriteResult writeInternal(InputStream in, long length, BlobMetadata meta,
                                      byte[] md5Override, int partCountOverride)
            throws IOException, BoinsException {
        MessageDigest digest = md5Override == null ? md5Digest() : null;
        Placement placement = length == 0L ? placeEmpty() : place(in, length, digest);
        byte[] md5 = digest != null ? digest.digest() : md5Override;
        int partCount = digest != null ? 0 : partCountOverride;

        Repository repo = placement.repo;
        long keyPosition = repo.heap().add(meta.key());
        long metaPosition = repo.heap().add(encodeUserMeta(meta.userMetadata()));
        short contentTypeId = repo.contentTypes().idOf(meta.contentType());
        IndexRecord record = new IndexRecord(
                placement.fileIndex, placement.position, length, placement.cellSize,
                System.currentTimeMillis(), IndexRecord.ALIVE,
                keyPosition, metaPosition, contentTypeId, md5, partCount);
        if (placement.consumedBlobId >= 0L) {
            // Mark the donor record's cell as consumed; covered by the same index fsync
            // as the insert below, which keeps the free-cell cross-check sound after a crash.
            repo.index().markCellConsumed(placement.consumedBlobId);
        }
        long blobId = repo.index().insert(record);
        repo.noteWrite(length + IndexRecord.SIZE);
        if (options.fsyncMode() == BoinsOptions.FsyncMode.ALWAYS) {
            if (placement.blobFile != null) {
                placement.blobFile.force();
            }
            repo.heap().force();
            repo.contentTypes().force();
            repo.index().force();
        }
        return new WriteResult(blobId, length, etagString(md5, partCount));
    }

    /** Where the content ended up. {@code consumedBlobId >= 0} when a free cell was reused. */
    private record Placement(Repository repo, BlobFile blobFile, short fileIndex,
                             long position, long cellSize, long consumedBlobId) {
    }

    private Placement placeEmpty() throws BoinsException {
        Repository repo = repositories.forInsert(0L);
        return new Placement(repo, null, (short) 0, 0L, 0L, -1L);
    }

    private Placement place(InputStream in, long length, MessageDigest digest)
            throws IOException, BoinsException {
        while (true) {
            OptionalLong reusable = freeCells.pop(length);
            if (reusable.isEmpty()) {
                return placeAppend(in, length, digest);
            }
            long donorId = reusable.getAsLong();
            IndexRecord donor;
            Repository repo;
            try {
                repo = repositories.forBlobId(donorId);
                donor = repo.index().read(donorId);
            } catch (BlobNotFoundException | StorageCorruptedException e) {
                // Registry points at a nonexistent record: drop the entry and try the next cell.
                recordFault(new StorageCorruptedException(
                        "Free-cell registry entry for blobId=" + donorId + " has no valid index record", e));
                continue;
            }
            if (!donor.deleted() || donor.cellSize() < length) {
                recordFault(new StorageCorruptedException("Free-cell registry entry for blobId=" + donorId
                        + " contradicts the index (deleted=" + donor.deleted()
                        + ", cellSize=" + donor.cellSize() + ", needed=" + length + "); entry dropped"));
                continue;
            }
            BlobFile blobFile = repo.blobFile(donor.blobFileIndex());
            try {
                blobFile.write(donor.blobPosition(), in, length, digest);
            } catch (IOException e) {
                // The cell content is garbage now, but the cell itself is still reusable.
                freeCells.put(donorId, donor.cellSize());
                throw e;
            }
            return new Placement(repo, blobFile, donor.blobFileIndex(), donor.blobPosition(),
                    donor.cellSize(), donorId);
        }
    }

    private Placement placeAppend(InputStream in, long length, MessageDigest digest)
            throws IOException, BoinsException {
        Repository repo = repositories.forInsert(length);
        BlobFile blobFile = repo.blobFileForAppend(length);
        long position = blobFile.reserve(length);
        try {
            blobFile.write(position, in, length, digest);
        } catch (IOException e) {
            reclaimFailedAppend(repo, blobFile, position, length);
            throw e;
        }
        return new Placement(repo, blobFile, blobFile.index(), position, length, -1L);
    }

    /**
     * A failed streaming append leaves a reserved hole in the blob file. Registers the hole
     * as a free cell (via a pre-deleted index record) so a later write of a similar size
     * reclaims the space.
     */
    private void reclaimFailedAppend(Repository repo, BlobFile blobFile, long position, long length) {
        try {
            long now = System.currentTimeMillis();
            IndexRecord hole = new IndexRecord(blobFile.index(), position, length, length, now, now,
                    IndexRecord.NO_HEAP_POSITION, IndexRecord.NO_HEAP_POSITION, IndexRecord.NO_CONTENT_TYPE,
                    null, IndexRecord.NO_DIGEST);
            long holeId = repo.index().insert(hole);
            freeCells.put(holeId, length);
        } catch (IOException | RuntimeException e) {
            recordFault(e, Map.of("operation", "reclaimFailedAppend"));
        }
    }

    // ---------------------------------------------------------------- read

    @Override
    public InputStream read(long blobId) throws BoinsException {
        ensureOpen();
        try {
            Located located = locateAlive(blobId);
            return openStream(located, 0L, located.record.blobSize());
        } catch (IOException e) {
            metrics.recordReadError();
            recordFault(e, Map.of("operation", "read", "blobId", Long.toString(blobId)));
            throw new BoinsException("Failed to read blob " + blobId, e);
        } catch (BoinsException e) {
            metrics.recordReadError();
            throw e;
        }
    }

    @Override
    public InputStream read(long blobId, long offset, long length) throws BoinsException {
        ensureOpen();
        try {
            Located located = locateAlive(blobId);
            long blobSize = located.record.blobSize();
            if (offset < 0L || length < 0L || offset + length > blobSize) {
                throw new BoinsException("Range [" + offset + ", " + (offset + length)
                        + ") is out of bounds for blob " + blobId + " of size " + blobSize);
            }
            return openStream(located, offset, length);
        } catch (IOException e) {
            metrics.recordReadError();
            recordFault(e, Map.of("operation", "read", "blobId", Long.toString(blobId)));
            throw new BoinsException("Failed to read blob " + blobId, e);
        } catch (BoinsException e) {
            metrics.recordReadError();
            throw e;
        }
    }

    private record Located(Repository repo, IndexRecord record) {
    }

    private Located locateAlive(long blobId) throws IOException, BoinsException {
        Repository repo = repositories.forBlobId(blobId);
        IndexRecord record = repo.index().read(blobId);
        if (record.deleted()) {
            throw new BlobDeletedException(blobId);
        }
        return new Located(repo, record);
    }

    private InputStream openStream(Located located, long offset, long length) throws BoinsException {
        InputStream raw = length == 0L
                ? InputStream.nullInputStream()
                : located.repo.blobFile(located.record.blobFileIndex())
                        .openStream(located.record.blobPosition() + offset, length);
        return new MeteredInputStream(raw, metrics);
    }

    // ---------------------------------------------------------------- info / delete

    @Override
    public BlobInfo info(long blobId) throws BoinsException {
        ensureOpen();
        try {
            Repository repo = repositories.forBlobId(blobId);
            IndexRecord r = repo.index().read(blobId);
            return new BlobInfo(
                    blobId,
                    r.blobSize(),
                    r.createTime(),
                    r.deleteTime(),
                    repo.heap().read(r.keyPosition()),
                    repo.contentTypes().byId(r.contentTypeId()),
                    decodeUserMeta(repo.heap().read(r.metaPosition())),
                    etagString(r.md5(), r.partCount()),
                    Math.max(r.partCount(), 0));
        } catch (IOException e) {
            recordFault(e, Map.of("operation", "info", "blobId", Long.toString(blobId)));
            throw new BoinsException("Failed to read info of blob " + blobId, e);
        }
    }

    @Override
    public boolean delete(long blobId) throws BoinsException {
        ensureOpen();
        ReentrantLock stripe = deleteStripes[(int) (Math.floorMod(blobId, DELETE_STRIPES))];
        stripe.lock();
        try {
            Repository repo = repositories.forBlobId(blobId);
            IndexRecord record = repo.index().read(blobId);
            if (record.deleted()) {
                return false;
            }
            long cellSize = repo.index().markDeleted(blobId, System.currentTimeMillis());
            if (cellSize > 0L) {
                freeCells.put(blobId, cellSize);
            }
            if (options.fsyncMode() == BoinsOptions.FsyncMode.ALWAYS) {
                repo.index().force();
                freeCells.force();
            }
            metrics.recordDelete();
            return true;
        } catch (IOException e) {
            recordFault(e, Map.of("operation", "delete", "blobId", Long.toString(blobId)));
            throw new BoinsException("Failed to delete blob " + blobId, e);
        } finally {
            stripe.unlock();
        }
    }

    @Override
    public Map<Long, BoinsException> delete(Collection<Long> blobIds) {
        Objects.requireNonNull(blobIds, "blobIds");
        Map<Long, BoinsException> failures = new HashMap<>();
        for (Long blobId : blobIds) {
            try {
                delete(blobId);
            } catch (BoinsException e) {
                failures.put(blobId, e);
            }
        }
        return failures;
    }

    // ---------------------------------------------------------------- state / lifecycle

    @Override
    public BoinsState state() {
        return new BoinsState(repositories.states(), freeCells.state());
    }

    @Override
    public MetricsSnapshot metrics() {
        return metrics.snapshot();
    }

    @Override
    public long[] usableSpace() {
        return repositories.usableSpace();
    }

    @Override
    public long[] blobIdOffsets() {
        return repositories.offsets();
    }

    @Override
    public void flush() throws BoinsException {
        try {
            repositories.force();
            freeCells.force();
        } catch (IOException e) {
            recordFault(e, Map.of("operation", "flush"));
            throw new BoinsException("Failed to flush storage", e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        IOException first = null;
        try {
            metrics.persist();
        } catch (IOException e) {
            first = e;
        }
        try {
            freeCells.close();
        } catch (IOException e) {
            first = first == null ? e : addSuppressed(first, e);
        }
        try {
            repositories.close();
        } catch (IOException e) {
            first = first == null ? e : addSuppressed(first, e);
        }
        if (first != null) {
            throw first;
        }
    }

    // ---------------------------------------------------------------- internals

    /**
     * Startup cross-check: drops free-cell registry entries that contradict the index.
     * This makes the registry safe to be stale after a crash, with no write-ordering
     * requirements between the registry and the index during normal operation.
     */
    private void validateFreeCells() throws IOException {
        for (long blobId : freeCells.snapshotIds()) {
            String problem = null;
            try {
                Repository repo = repositories.forBlobId(blobId);
                IndexRecord record = repo.index().read(blobId);
                if (!record.deleted()) {
                    problem = "the blob is alive";
                } else if (record.cellSize() <= 0L) {
                    problem = "the cell was already consumed by a replacement blob";
                }
            } catch (BoinsException e) {
                problem = "no valid index record exists";
            }
            if (problem != null) {
                freeCells.remove(blobId);
                recordFault(new StorageCorruptedException("Dropped stale free-cell registry entry for blobId="
                        + blobId + ": " + problem + " (expected after an unclean shutdown)"));
            }
        }
        freeCells.force();
    }

    private void safeFlush() {
        try {
            repositories.force();
            freeCells.force();
        } catch (IOException | RuntimeException e) {
            recordFault(e, Map.of("operation", "backgroundFlush"));
        }
    }

    private void safePersistMetrics() {
        try {
            metrics.persist();
        } catch (IOException | RuntimeException e) {
            recordFault(e, Map.of("operation", "persistMetrics"));
        }
    }

    private void recordFault(Throwable t) {
        recordFault(t, Map.of());
    }

    private void recordFault(Throwable t, Map<String, String> context) {
        if (faults != null) {
            faults.record(t, context);
        }
    }

    private void ensureOpen() throws BoinsException {
        if (closed) {
            throw new BoinsException("Boins storage is closed");
        }
    }

    private static MessageDigest md5Digest() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private static String etagString(byte[] md5, int partCount) {
        if (partCount == IndexRecord.NO_DIGEST) {
            return null;
        }
        String hex = HexFormat.of().formatHex(md5);
        return partCount > 0 ? hex + "-" + partCount : hex;
    }

    static String encodeUserMeta(Map<String, String> meta) {
        if (meta == null || meta.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder(64);
        for (Map.Entry<String, String> e : meta.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static Map<String, String> decodeUserMeta(String encoded) {
        if (encoded == null || encoded.isEmpty()) {
            return Map.of();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                meta.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return Map.copyOf(meta);
    }

    private static IOException addSuppressed(IOException first, IOException next) {
        first.addSuppressed(next);
        return first;
    }

    private static void closeQuietly(AutoCloseable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
                // Best-effort cleanup on failed construction.
            }
        }
    }
}
