package io.boins.server.bucket;

import io.boins.core.BlobMetadata;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * In-flight S3 multipart uploads of one bucket.
 *
 * <p>Parts are streamed to temp files under {@code <bucket>/multipart/<uploadId>/}; upload
 * metadata is kept in memory. A server restart therefore aborts in-flight uploads — their
 * temp directories are swept away on startup, and clients simply retry (the standard
 * behaviour SDKs implement for {@code NoSuchUpload}).</p>
 */
public final class MultipartUploads implements Closeable {

    /** Uploads with no activity for this long are dropped by {@link #sweepExpired}. */
    public static final long DEFAULT_TTL_MILLIS = 24L * 60 * 60 * 1000;

    public record Part(int number, long size, String etagHex, Path file) {
    }

    public static final class Upload {
        private final String id;
        private final String key;
        private final BlobMetadata metadata;
        private final Path dir;
        private final Map<Integer, Part> parts = new ConcurrentHashMap<>();
        private final long initiatedMillis = System.currentTimeMillis();
        private volatile long lastActivityMillis = initiatedMillis;

        private Upload(String id, String key, BlobMetadata metadata, Path dir) {
            this.id = id;
            this.key = key;
            this.metadata = metadata;
            this.dir = dir;
        }

        public long initiatedMillis() {
            return initiatedMillis;
        }

        public String id() {
            return id;
        }

        public String key() {
            return key;
        }

        public BlobMetadata metadata() {
            return metadata;
        }

        public Map<Integer, Part> parts() {
            return parts;
        }
    }

    /** A completed upload ready to be written into the core as one blob. */
    public record Completed(List<Part> parts, long totalSize, byte[] md5OfPartMd5s) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path dir;
    private final Map<String, Upload> uploads = new ConcurrentHashMap<>();

    MultipartUploads(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
        // In-flight uploads do not survive a restart; clear leftover temp data.
        deleteRecursively(dir, false);
    }

    /** Starts a new upload for {@code key} and returns its upload id. */
    public Upload create(String key, BlobMetadata metadata) throws IOException {
        byte[] idBytes = new byte[16];
        RANDOM.nextBytes(idBytes);
        String id = HexFormat.of().formatHex(idBytes);
        Path uploadDir = dir.resolve(id);
        Files.createDirectories(uploadDir);
        Upload upload = new Upload(id, key, metadata, uploadDir);
        uploads.put(id, upload);
        return upload;
    }

    /** All in-flight uploads (for ListMultipartUploads). */
    public java.util.Collection<Upload> all() {
        return uploads.values();
    }

    /** The upload with {@code uploadId}, or {@code null}. */
    public Upload get(String uploadId) {
        Upload upload = uploads.get(uploadId);
        if (upload != null) {
            upload.lastActivityMillis = System.currentTimeMillis();
        }
        return upload;
    }

    /**
     * Streams one part to a temp file, computing its MD5.
     *
     * @return the stored part (its {@code etagHex} is the S3 part ETag)
     */
    public Part putPart(Upload upload, int partNumber, InputStream in) throws IOException {
        MessageDigest md5 = md5();
        Path partFile = upload.dir.resolve(partNumber + ".part");
        long size = 0L;
        try (OutputStream out = Files.newOutputStream(partFile)) {
            byte[] buffer = new byte[1 << 16];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                md5.update(buffer, 0, n);
                out.write(buffer, 0, n);
                size += n;
            }
        }
        Part part = new Part(partNumber, size, HexFormat.of().formatHex(md5.digest()), partFile);
        upload.parts.put(partNumber, part);
        upload.lastActivityMillis = System.currentTimeMillis();
        return part;
    }

    /**
     * Validates the client's part list against stored parts and computes the multipart digest.
     * The upload stays registered until {@link #abort} is called after the blob is written.
     *
     * @param requestedParts part numbers with their expected ETags, in request order
     */
    public Completed complete(Upload upload, List<Map.Entry<Integer, String>> requestedParts,
                              java.util.function.Function<String, RuntimeException> invalidPart,
                              java.util.function.Supplier<RuntimeException> invalidOrder) {
        if (requestedParts.isEmpty()) {
            throw invalidPart.apply("The multipart completion request contains no parts.");
        }
        List<Part> parts = new ArrayList<>(requestedParts.size());
        MessageDigest md5 = md5();
        int previousNumber = 0;
        long totalSize = 0L;
        for (Map.Entry<Integer, String> requested : requestedParts) {
            int number = requested.getKey();
            if (number <= previousNumber) {
                throw invalidOrder.get();
            }
            previousNumber = number;
            Part stored = upload.parts.get(number);
            if (stored == null) {
                throw invalidPart.apply("Part " + number + " was not uploaded.");
            }
            String expectedEtag = requested.getValue().replace("\"", "");
            if (!stored.etagHex.equalsIgnoreCase(expectedEtag)) {
                throw invalidPart.apply("Part " + number + " ETag mismatch.");
            }
            parts.add(stored);
            totalSize += stored.size;
            md5.update(HexFormat.of().parseHex(stored.etagHex));
        }
        return new Completed(parts, totalSize, md5.digest());
    }

    /** Opens a stream over the concatenated parts of a completed upload. */
    public InputStream concatenatedStream(Completed completed) throws IOException {
        List<InputStream> streams = new ArrayList<>(completed.parts().size());
        try {
            for (Part part : completed.parts()) {
                streams.add(Files.newInputStream(part.file()));
            }
        } catch (IOException e) {
            for (InputStream s : streams) {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // closing on failure path
                }
            }
            throw e;
        }
        return new java.io.SequenceInputStream(java.util.Collections.enumeration(streams));
    }

    /** Removes one stored part (e.g. after a failed digest check). Idempotent. */
    public void dropPart(Upload upload, int partNumber) throws IOException {
        Part part = upload.parts.remove(partNumber);
        if (part != null) {
            Files.deleteIfExists(part.file());
        }
    }

    /** Drops an upload and deletes its temp files. Idempotent. */
    public void abort(String uploadId) throws IOException {
        Upload upload = uploads.remove(uploadId);
        if (upload != null) {
            deleteRecursively(upload.dir, true);
        }
    }

    /** Removes uploads that have been inactive longer than {@code ttlMillis}. */
    public void sweepExpired(long ttlMillis) {
        long cutoff = System.currentTimeMillis() - ttlMillis;
        for (Upload upload : uploads.values()) {
            if (upload.lastActivityMillis < cutoff) {
                try {
                    abort(upload.id);
                } catch (IOException ignored) {
                    // best-effort sweep; leftover files are removed on next startup
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        deleteRecursively(dir, false);
        uploads.clear();
    }

    private static MessageDigest md5() {
        try {
            return MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is unavailable", e);
        }
    }

    private static void deleteRecursively(Path root, boolean deleteRoot) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder())
                    .filter(p -> deleteRoot || !p.equals(root))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                            // best-effort cleanup
                        }
                    });
        }
    }
}
