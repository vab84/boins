package io.boins.core;

import io.boins.core.faults.FaultStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable configuration of a Boins core instance. Build with {@link #builder()}.
 */
public final class BoinsOptions {

    public static final int MIN_BLOB_FILE_LIMIT = 20;
    public static final int MAX_BLOB_FILE_LIMIT = 62;

    /** Storage device type. Affects the concurrency strategy for blob-file writes. */
    public enum DiskType { HDD, SSD }

    /** Durability policy for blob and index writes. */
    public enum FsyncMode {
        /** fsync after every write — maximum durability, lowest throughput. */
        ALWAYS,
        /** fsync dirty files periodically on a background thread. */
        INTERVAL,
        /** never fsync explicitly — leave flushing to the OS. */
        NEVER
    }

    /**
     * A single storage repository (typically one per physical disk).
     *
     * @param dir          directory holding the repository files; created if missing
     * @param blobIdOffset first blob id of this repository; ranges of different
     *                     repositories must not overlap
     * @param diskType     device type hint
     */
    public record RepositoryOptions(Path dir, long blobIdOffset, DiskType diskType) {
        public RepositoryOptions {
            Objects.requireNonNull(dir, "dir");
            Objects.requireNonNull(diskType, "diskType");
            if (blobIdOffset < 0L) {
                throw new IllegalArgumentException("blobIdOffset must be >= 0, got " + blobIdOffset);
            }
        }
    }

    private final List<RepositoryOptions> repositories;
    private final int blobFileLimit;
    private final long minBlobBytes;
    private final Path freeCellsFile;
    private final long minRepositoryFreeBytes;
    private final FsyncMode fsyncMode;
    private final long fsyncIntervalMillis;
    private final Path metricsFile;
    private final long metricsFlushIntervalMillis;
    private final FaultStore faultStore;

    private BoinsOptions(Builder b) {
        this.repositories = List.copyOf(b.repositories);
        this.blobFileLimit = b.blobFileLimit;
        this.minBlobBytes = b.minBlobBytes;
        this.minRepositoryFreeBytes = b.minRepositoryFreeBytes;
        this.fsyncMode = b.fsyncMode;
        this.fsyncIntervalMillis = b.fsyncIntervalMillis;
        this.metricsFlushIntervalMillis = b.metricsFlushIntervalMillis;
        this.faultStore = b.faultStore;

        if (repositories.isEmpty()) {
            throw new IllegalArgumentException("At least one repository is required");
        }
        if (blobFileLimit < MIN_BLOB_FILE_LIMIT || blobFileLimit > MAX_BLOB_FILE_LIMIT) {
            throw new IllegalArgumentException("blobFileLimit must be in [" + MIN_BLOB_FILE_LIMIT
                    + ", " + MAX_BLOB_FILE_LIMIT + "], got " + blobFileLimit);
        }
        if (minBlobBytes < 0L || minBlobBytes >= blobFileLimitBytes()) {
            throw new IllegalArgumentException("minBlobBytes must be in [0, 2^" + blobFileLimit + "), got " + minBlobBytes);
        }
        if (minRepositoryFreeBytes < 0L) {
            throw new IllegalArgumentException("minRepositoryFreeBytes must be >= 0");
        }
        if (fsyncIntervalMillis <= 0L) {
            throw new IllegalArgumentException("fsyncIntervalMillis must be > 0");
        }
        if (metricsFlushIntervalMillis <= 0L) {
            throw new IllegalArgumentException("metricsFlushIntervalMillis must be > 0");
        }
        Path firstDir = repositories.getFirst().dir();
        this.freeCellsFile = b.freeCellsFile != null ? b.freeCellsFile : firstDir.resolve("free-cells.boins");
        this.metricsFile = b.metricsFile != null ? b.metricsFile : firstDir.resolve("metrics.boins");
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<RepositoryOptions> repositories() {
        return repositories;
    }

    /** Limit of a single blob file size as a power of two (e.g. 36 means 2^36 = 64 GiB). */
    public int blobFileLimit() {
        return blobFileLimit;
    }

    public long blobFileLimitBytes() {
        return 1L << blobFileLimit;
    }

    /**
     * Probabilistic minimum blob size in bytes. Blobs smaller than this can still be stored;
     * the value only tunes the blob-file append selection: once the remaining capacity of a
     * blob file drops below this value, the file is no longer considered for appends.
     */
    public long minBlobBytes() {
        return minBlobBytes;
    }

    /** File tracking reusable cells of deleted blobs. */
    public Path freeCellsFile() {
        return freeCellsFile;
    }

    /** A repository is excluded from inserts when its disk free space drops below this value. */
    public long minRepositoryFreeBytes() {
        return minRepositoryFreeBytes;
    }

    public FsyncMode fsyncMode() {
        return fsyncMode;
    }

    public long fsyncIntervalMillis() {
        return fsyncIntervalMillis;
    }

    /** File where cumulative metrics are persisted. */
    public Path metricsFile() {
        return metricsFile;
    }

    public long metricsFlushIntervalMillis() {
        return metricsFlushIntervalMillis;
    }

    /** Optional store for internal exceptional events; may be {@code null}. */
    public FaultStore faultStore() {
        return faultStore;
    }

    public static final class Builder {
        private final List<RepositoryOptions> repositories = new ArrayList<>();
        private int blobFileLimit = 36;
        private long minBlobBytes = 1024L;
        private Path freeCellsFile;
        private long minRepositoryFreeBytes = 1L << 30; // 1 GiB
        private FsyncMode fsyncMode = FsyncMode.ALWAYS;
        private long fsyncIntervalMillis = 1_000L;
        private Path metricsFile;
        private long metricsFlushIntervalMillis = 10_000L;
        private FaultStore faultStore;

        private Builder() {
        }

        public Builder addRepository(Path dir, long blobIdOffset, DiskType diskType) {
            repositories.add(new RepositoryOptions(dir, blobIdOffset, diskType));
            return this;
        }

        public Builder addRepository(RepositoryOptions repository) {
            repositories.add(Objects.requireNonNull(repository));
            return this;
        }

        public Builder blobFileLimit(int powerOfTwo) {
            this.blobFileLimit = powerOfTwo;
            return this;
        }

        public Builder minBlobBytes(long bytes) {
            this.minBlobBytes = bytes;
            return this;
        }

        public Builder freeCellsFile(Path file) {
            this.freeCellsFile = file;
            return this;
        }

        public Builder minRepositoryFreeBytes(long bytes) {
            this.minRepositoryFreeBytes = bytes;
            return this;
        }

        public Builder fsyncMode(FsyncMode mode) {
            this.fsyncMode = Objects.requireNonNull(mode);
            return this;
        }

        public Builder fsyncIntervalMillis(long millis) {
            this.fsyncIntervalMillis = millis;
            return this;
        }

        public Builder metricsFile(Path file) {
            this.metricsFile = file;
            return this;
        }

        public Builder metricsFlushIntervalMillis(long millis) {
            this.metricsFlushIntervalMillis = millis;
            return this;
        }

        public Builder faultStore(FaultStore store) {
            this.faultStore = store;
            return this;
        }

        public BoinsOptions build() {
            return new BoinsOptions(this);
        }
    }
}
