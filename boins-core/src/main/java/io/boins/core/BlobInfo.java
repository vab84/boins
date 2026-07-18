package io.boins.core;

import java.util.Map;

/**
 * Descriptive information about a stored blob.
 *
 * @param id           blob id
 * @param size         blob size in bytes
 * @param createTime   creation time, epoch millis
 * @param deleteTime   deletion time, epoch millis; {@code -1} if the blob is alive
 * @param key          logical name (S3 key / filename); may be {@code null}
 * @param contentType  MIME type; may be {@code null}
 * @param userMetadata user metadata pairs; never {@code null}
 * @param etag         hex MD5 of the content, or {@code md5OfPartMd5s-partCount} for multipart
 *                     uploads; may be {@code null} for blobs written without a digest
 * @param partCount    number of multipart parts; {@code 0} for single-shot uploads
 */
public record BlobInfo(
        long id,
        long size,
        long createTime,
        long deleteTime,
        String key,
        String contentType,
        Map<String, String> userMetadata,
        String etag,
        int partCount) {

    public BlobInfo {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public boolean deleted() {
        return deleteTime >= 0L;
    }
}
