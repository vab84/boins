package io.boins.core;

/**
 * Thrown when the requested blob id does not exist in any repository.
 */
public class BlobNotFoundException extends BoinsException {

    private final long blobId;

    public BlobNotFoundException(long blobId) {
        super("Blob not found. blobId=" + blobId);
        this.blobId = blobId;
    }

    public long blobId() {
        return blobId;
    }
}
