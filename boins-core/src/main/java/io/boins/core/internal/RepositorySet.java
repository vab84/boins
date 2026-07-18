package io.boins.core.internal;

import io.boins.core.BlobNotFoundException;
import io.boins.core.BoinsException;
import io.boins.core.BoinsOptions;
import io.boins.core.RepositoryState;
import io.boins.core.StorageFullException;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Routes operations to repositories.
 *
 * <p>Reads are routed by blob id (each repository owns a contiguous id range starting at
 * its offset). Inserts pick a repository randomly, weighted by free disk space, among
 * repositories that can accept the blob.</p>
 */
public final class RepositorySet implements Closeable {

    private final NavigableMap<Long, Repository> byOffset = new TreeMap<>();
    private final List<Repository> ordered = new ArrayList<>();
    private final long minRepositoryFreeBytes;

    RepositorySet(BoinsOptions options) throws IOException, BoinsException {
        this.minRepositoryFreeBytes = options.minRepositoryFreeBytes();
        try {
            for (BoinsOptions.RepositoryOptions ro : options.repositories()) {
                if (byOffset.containsKey(ro.blobIdOffset())) {
                    throw new IllegalArgumentException("Duplicate repository blobIdOffset " + ro.blobIdOffset());
                }
                Repository repo = new Repository(ro, options.blobFileLimitBytes(), options.minBlobBytes());
                byOffset.put(ro.blobIdOffset(), repo);
                ordered.add(repo);
            }
            validateRanges();
        } catch (IOException | BoinsException | RuntimeException e) {
            closeAll();
            throw e;
        }
    }

    /** Repository owning {@code blobId}. */
    public Repository forBlobId(long blobId) throws BlobNotFoundException {
        if (blobId < 0L) {
            throw new BlobNotFoundException(blobId);
        }
        Map.Entry<Long, Repository> entry = byOffset.floorEntry(blobId);
        if (entry == null || !entry.getValue().index().contains(blobId)) {
            throw new BlobNotFoundException(blobId);
        }
        return entry.getValue();
    }

    /**
     * Picks a repository for a new blob: random, weighted by free disk space, among
     * repositories with enough room. Weighting by space naturally balances filling.
     */
    public Repository forInsert(long blobSize) throws StorageFullException {
        List<Repository> eligible = new ArrayList<>(ordered.size());
        long totalSpace = 0L;
        for (Repository repo : ordered) {
            long space = repo.usableSpace();
            if (space >= blobSize + minRepositoryFreeBytes && hasIdCapacity(repo)) {
                eligible.add(repo);
                totalSpace += space;
            }
        }
        if (eligible.isEmpty() || totalSpace <= 0L) {
            throw new StorageFullException("No repository can accept a blob of " + blobSize
                    + " bytes (required free space: blob size + " + minRepositoryFreeBytes + " reserve)");
        }
        long point = ThreadLocalRandom.current().nextLong(totalSpace);
        long cumulative = 0L;
        for (Repository repo : eligible) {
            cumulative += repo.usableSpace();
            if (point < cumulative) {
                return repo;
            }
        }
        // Free space changed concurrently while iterating; any eligible repository is fine.
        return eligible.getLast();
    }

    public List<Repository> repositories() {
        return List.copyOf(ordered);
    }

    public List<RepositoryState> states() {
        List<RepositoryState> states = new ArrayList<>(ordered.size());
        for (Repository repo : ordered) {
            states.add(repo.state());
        }
        return states;
    }

    public long[] usableSpace() {
        long[] result = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            result[i] = ordered.get(i).usableSpace();
        }
        return result;
    }

    public long[] offsets() {
        long[] result = new long[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            result[i] = ordered.get(i).blobIdOffset();
        }
        return result;
    }

    public void force() throws IOException {
        for (Repository repo : ordered) {
            repo.force();
        }
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        for (Repository repo : ordered) {
            try {
                repo.close();
            } catch (IOException e) {
                if (first == null) {
                    first = e;
                } else {
                    first.addSuppressed(e);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    /** {@code true} while the repository's id range has room before the next repository's offset. */
    private boolean hasIdCapacity(Repository repo) {
        Long nextOffset = byOffset.higherKey(repo.blobIdOffset());
        return nextOffset == null || repo.index().nextBlobId() < nextOffset;
    }

    private void validateRanges() {
        for (Repository repo : ordered) {
            Long nextOffset = byOffset.higherKey(repo.blobIdOffset());
            if (nextOffset != null && repo.index().nextBlobId() > nextOffset) {
                throw new IllegalArgumentException("Repository at offset " + repo.blobIdOffset()
                        + " already contains blob ids reaching " + (repo.index().nextBlobId() - 1L)
                        + ", which overlaps the next repository offset " + nextOffset);
            }
        }
    }

    private void closeAll() {
        for (Repository repo : ordered) {
            try {
                repo.close();
            } catch (IOException ignored) {
                // Best-effort cleanup on failed construction.
            }
        }
    }
}
