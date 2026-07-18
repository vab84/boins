package io.boins.core;

/**
 * Result of a successful write operation.
 *
 * @param blobId assigned blob id
 * @param size   number of content bytes written
 * @param etag   hex MD5 of the content (or the caller-supplied multipart etag)
 */
public record WriteResult(long blobId, long size, String etag) {
}
