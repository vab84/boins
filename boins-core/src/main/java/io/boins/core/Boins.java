package io.boins.core;

import io.boins.core.internal.BoinsImpl;
import io.boins.core.metrics.MetricsSnapshot;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * Boins blob storage engine.
 *
 * <p>Blobs are stored together in large blob files; a compact fixed-size index maps a
 * {@code long} blob id to the blob location and attributes. Cells of deleted blobs are
 * reused by later writes of a similar size.</p>
 *
 * <p>All methods are thread-safe. Instances are heavyweight; open one per storage and
 * share it. Use in embedded mode directly, or through the S3-compatible {@code boins-server}.</p>
 */
public interface Boins extends Closeable {

    /**
     * Opens (creating if necessary) a Boins storage described by {@code options}.
     */
    static Boins open(BoinsOptions options) throws BoinsException {
        return new BoinsImpl(options);
    }

    /**
     * Opens a Boins storage configured by a YAML options file.
     *
     * <p>If the file does not exist, a commented default file is created at {@code optionsFile}
     * (parent directories included) and its defaults are used. An existing but invalid file
     * fails with a {@link BoinsException}. Relative paths inside the file are resolved
     * against the file's directory. See {@link BoinsOptionsFile}.</p>
     */
    static Boins open(Path optionsFile) throws BoinsException {
        return new BoinsImpl(BoinsOptionsFile.loadOrCreate(optionsFile));
    }

    /** Writes a blob from a byte array. */
    WriteResult write(byte[] blob, BlobMetadata metadata) throws BoinsException;

    /** Writes a blob from a file. */
    WriteResult write(Path source, BlobMetadata metadata) throws BoinsException;

    /**
     * Writes a blob by streaming exactly {@code length} bytes from {@code in}.
     * The MD5 digest is computed on the fly. The stream is not closed.
     */
    WriteResult write(InputStream in, long length, BlobMetadata metadata) throws BoinsException;

    /**
     * Writes a blob by streaming exactly {@code length} bytes from {@code in},
     * trusting the caller-supplied digest instead of computing one.
     * Used for S3 multipart uploads where the etag is not the content MD5.
     *
     * @param md5       16-byte digest to store (content MD5, or MD5 of part MD5s for multipart)
     * @param partCount number of multipart parts, or {@code 0} for a regular blob
     */
    WriteResult write(InputStream in, long length, BlobMetadata metadata, byte[] md5, int partCount)
            throws BoinsException;

    /**
     * Opens a stream over the whole content of a blob.
     * The caller must close the returned stream.
     */
    InputStream read(long blobId) throws BoinsException;

    /**
     * Opens a stream over a range of a blob: {@code length} bytes starting at {@code offset}.
     * The caller must close the returned stream.
     */
    InputStream read(long blobId, long offset, long length) throws BoinsException;

    /** Returns blob attributes. Works for deleted blobs too ({@link BlobInfo#deleted()}). */
    BlobInfo info(long blobId) throws BoinsException;

    /**
     * Marks a blob as deleted and registers its cell for reuse.
     *
     * @return {@code true} if the blob was alive and is now deleted,
     *         {@code false} if it was already deleted (idempotent)
     */
    boolean delete(long blobId) throws BoinsException;

    /**
     * Deletes a batch of blobs. Never throws for individual blobs.
     *
     * @return per-blob failures; empty if every deletion succeeded
     */
    Map<Long, BoinsException> delete(Collection<Long> blobIds);

    /** Snapshot of the storage state (repositories, free cells) for monitoring. */
    BoinsState state() throws BoinsException;

    /** Snapshot of accumulated metrics. */
    MetricsSnapshot metrics();

    /** Disk free space per repository, in repository order. */
    long[] usableSpace();

    /** Blob id offsets per repository, in repository order. */
    long[] blobIdOffsets();

    /** Forces all dirty files to disk regardless of the configured fsync mode. */
    void flush() throws BoinsException;

    /** Flushes and releases all resources. */
    @Override
    void close() throws IOException;
}
