package io.boins.core;

import java.util.Map;

/**
 * Optional descriptive attributes stored together with a blob.
 *
 * @param key          logical name of the blob (S3 object key or a filename); may be {@code null}
 * @param contentType  MIME type; may be {@code null}
 * @param userMetadata arbitrary small string pairs (S3 {@code x-amz-meta-*}); never {@code null}, may be empty
 */
public record BlobMetadata(String key, String contentType, Map<String, String> userMetadata) {

    public static final BlobMetadata EMPTY = new BlobMetadata(null, null, Map.of());

    public BlobMetadata {
        userMetadata = userMetadata == null ? Map.of() : Map.copyOf(userMetadata);
    }

    public BlobMetadata(String key, String contentType) {
        this(key, contentType, Map.of());
    }

    public static BlobMetadata ofKey(String key) {
        return new BlobMetadata(key, null, Map.of());
    }
}
