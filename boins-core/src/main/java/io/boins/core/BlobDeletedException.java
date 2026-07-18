package io.boins.core;

/**
 * Thrown when the requested blob exists but has been deleted.
 */
public class BlobDeletedException extends BoinsException {

    private final long blobId;

    public BlobDeletedException(long blobId) {
        super("Blob has been deleted. blobId=" + blobId);
        this.blobId = blobId;
    }

    public long blobId() {
        return blobId;
    }
}
