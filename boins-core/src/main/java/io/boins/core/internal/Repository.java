package io.boins.core.internal;

import io.boins.core.BoinsException;
import io.boins.core.BoinsOptions;
import io.boins.core.RepositoryState;
import io.boins.core.StorageCorruptedException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One storage repository: an index, a string heap, a content type dictionary and a set of
 * blob files, all inside a single directory (typically one repository per physical disk).
 *
 * <p>A {@code manifest.boins} file pins the format version and the blob id offset, so a
 * repository is self-describing and safe to relocate (the old implementation encoded the
 * offset in the index file name, which was easy to break by renaming).</p>
 */
public final class Repository implements Closeable {

    static final int FORMAT_VERSION = 2;
    static final String MANIFEST_FILE = "manifest.boins";
    static final String INDEX_FILE = "index.boins";
    static final String HEAP_FILE = "heap.boins";
    static final String CONTENT_TYPES_FILE = "content-types.boins";
    private static final Pattern BLOB_FILE_PATTERN = Pattern.compile("^blob\\.(\\d+)\\.boins$");

    /** Refresh the cached disk free space every N writes. */
    private static final int USABLE_SPACE_REFRESH_PERIOD = 1_000;

    private final Path dir;
    private final BoinsOptions.RepositoryOptions options;
    private final long blobFileLimitBytes;
    private final long minBlobBytes;
    private final IndexFile index;
    private final StringHeap heap;
    private final ContentTypeDictionary contentTypes;
    private final List<BlobFile> blobFiles = new ArrayList<>();
    private final List<BlobFile> appendCandidates = new LinkedList<>();
    private final AtomicLong usableSpace = new AtomicLong();
    private final AtomicLong writeCounter = new AtomicLong();

    Repository(BoinsOptions.RepositoryOptions options, long blobFileLimitBytes, long minBlobBytes)
            throws IOException, BoinsException {
        this.options = options;
        this.dir = options.dir();
        this.blobFileLimitBytes = blobFileLimitBytes;
        this.minBlobBytes = minBlobBytes;
        Files.createDirectories(dir);
        checkManifest();
        this.index = new IndexFile(dir.resolve(INDEX_FILE), options.blobIdOffset());
        this.heap = new StringHeap(dir.resolve(HEAP_FILE));
        this.contentTypes = new ContentTypeDictionary(dir.resolve(CONTENT_TYPES_FILE));
        discoverBlobFiles();
        refreshUsableSpace();
    }

    public long blobIdOffset() {
        return options.blobIdOffset();
    }

    public IndexFile index() {
        return index;
    }

    public StringHeap heap() {
        return heap;
    }

    public ContentTypeDictionary contentTypes() {
        return contentTypes;
    }

    /** Returns the blob file with the given ordinal. */
    public BlobFile blobFile(int fileIndex) throws StorageCorruptedException {
        synchronized (blobFiles) {
            if (fileIndex < 0 || fileIndex >= blobFiles.size()) {
                throw new StorageCorruptedException("Blob file index " + fileIndex
                        + " is out of range [0, " + blobFiles.size() + ") in " + dir);
            }
            return blobFiles.get(fileIndex);
        }
    }

    /**
     * Picks a blob file with room for {@code blobSize} more bytes, creating a new file when
     * none fits. Files whose remaining capacity drops below {@code minBlobBytes} are dropped
     * from the candidate list — blobs that small may be rare, and rescanning full files on
     * every append is wasted work.
     */
    public BlobFile blobFileForAppend(long blobSize) throws IOException {
        synchronized (blobFiles) {
            Iterator<BlobFile> it = appendCandidates.iterator();
            while (it.hasNext()) {
                BlobFile bf = it.next();
                long newSize = bf.size() + blobSize;
                if (newSize <= blobFileLimitBytes) {
                    if (newSize > blobFileLimitBytes - minBlobBytes) {
                        it.remove();
                    }
                    return bf;
                }
            }
            if (blobFiles.size() > Short.MAX_VALUE) {
                throw new IOException("Blob file limit reached in " + dir
                        + " (" + blobFiles.size() + " files); increase blobFileLimit");
            }
            BlobFile bf = new BlobFile(dir.resolve("blob." + blobFiles.size() + ".boins"),
                    (short) blobFiles.size(), options.diskType());
            blobFiles.add(bf);
            appendCandidates.add(bf);
            return bf;
        }
    }

    /** Cached free disk space; refreshed every {@value #USABLE_SPACE_REFRESH_PERIOD} writes. */
    public long usableSpace() {
        return usableSpace.get();
    }

    /** Accounts a completed write against the cached free space. */
    public void noteWrite(long bytes) {
        if (writeCounter.incrementAndGet() % USABLE_SPACE_REFRESH_PERIOD == 0L) {
            refreshUsableSpace();
        } else {
            usableSpace.addAndGet(-bytes);
        }
    }

    public RepositoryState state() {
        int fileCount;
        long totalBlobBytes = 0L;
        synchronized (blobFiles) {
            fileCount = blobFiles.size();
            for (BlobFile bf : blobFiles) {
                totalBlobBytes += bf.size();
            }
        }
        return new RepositoryState(
                dir.toString(),
                blobIdOffset(),
                index.recordCount(),
                index.maxBlobId(),
                index.sizeBytes(),
                fileCount,
                totalBlobBytes,
                usableSpace(),
                heap.sizeBytes(),
                contentTypes.count());
    }

    public void force() throws IOException {
        List<BlobFile> files;
        synchronized (blobFiles) {
            files = List.copyOf(blobFiles);
        }
        for (BlobFile bf : files) {
            bf.force();
        }
        heap.force();
        contentTypes.force();
        index.force();
    }

    @Override
    public void close() throws IOException {
        IOException first = null;
        synchronized (blobFiles) {
            for (BlobFile bf : blobFiles) {
                first = closeQuietly(bf, first);
            }
        }
        first = closeQuietly(heap, first);
        first = closeQuietly(contentTypes, first);
        first = closeQuietly(index, first);
        if (first != null) {
            throw first;
        }
    }

    private static IOException closeQuietly(Closeable c, IOException first) {
        try {
            c.close();
            return first;
        } catch (IOException e) {
            if (first != null) {
                first.addSuppressed(e);
                return first;
            }
            return e;
        }
    }

    private void refreshUsableSpace() {
        usableSpace.set(dir.toFile().getUsableSpace());
    }

    private void checkManifest() throws IOException, StorageCorruptedException {
        Path manifest = dir.resolve(MANIFEST_FILE);
        if (Files.exists(manifest)) {
            Properties p = new Properties();
            try (InputStream in = Files.newInputStream(manifest)) {
                p.load(in);
            }
            int version = Integer.parseInt(p.getProperty("formatVersion", "-1").trim());
            long offset = Long.parseLong(p.getProperty("blobIdOffset", "-1").trim());
            if (version != FORMAT_VERSION) {
                throw new StorageCorruptedException("Unsupported repository format version " + version
                        + " (expected " + FORMAT_VERSION + ") in " + manifest);
            }
            if (offset != options.blobIdOffset()) {
                throw new StorageCorruptedException("Repository " + dir + " was created with blobIdOffset="
                        + offset + " but is now configured with " + options.blobIdOffset()
                        + ". Changing the offset of an existing repository corrupts all blob ids.");
            }
        } else {
            Properties p = new Properties();
            p.setProperty("formatVersion", Integer.toString(FORMAT_VERSION));
            p.setProperty("blobIdOffset", Long.toString(options.blobIdOffset()));
            try (OutputStream out = Files.newOutputStream(manifest)) {
                p.store(out, "Boins repository manifest");
            }
        }
    }

    private void discoverBlobFiles() throws IOException, StorageCorruptedException {
        record Found(int index, Path path) {
        }
        List<Found> found = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.toList()) {
                Matcher m = BLOB_FILE_PATTERN.matcher(f.getFileName().toString());
                if (m.matches()) {
                    found.add(new Found(Integer.parseInt(m.group(1)), f));
                }
            }
        }
        found.sort(Comparator.comparingInt(Found::index));
        for (int i = 0; i < found.size(); i++) {
            Found f = found.get(i);
            if (f.index != i) {
                throw new StorageCorruptedException("Blob files are not contiguous in " + dir
                        + ": expected blob." + i + ".boins, found " + f.path.getFileName());
            }
            if (f.index > Short.MAX_VALUE) {
                throw new StorageCorruptedException("Too many blob files in " + dir);
            }
            BlobFile bf = new BlobFile(f.path, (short) f.index, options.diskType());
            blobFiles.add(bf);
            if (bf.size() <= blobFileLimitBytes - minBlobBytes) {
                appendCandidates.add(bf);
            }
        }
    }
}
