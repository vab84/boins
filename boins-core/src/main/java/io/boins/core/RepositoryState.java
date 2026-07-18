package io.boins.core;

/**
 * Snapshot of one repository.
 *
 * @param dir              repository directory
 * @param blobIdOffset     first blob id of the repository
 * @param blobCount        number of index records (alive + deleted)
 * @param maxBlobId        highest assigned blob id, or {@code -1} if empty
 * @param indexFileSize    size of the index file in bytes
 * @param blobFileCount    number of blob files
 * @param totalBlobBytes   sum of blob file sizes in bytes
 * @param usableSpace      free disk space in bytes
 * @param heapFileSize     size of the string heap file (keys, metadata) in bytes
 * @param contentTypeCount number of distinct content types
 */
public record RepositoryState(
        String dir,
        long blobIdOffset,
        long blobCount,
        long maxBlobId,
        long indexFileSize,
        int blobFileCount,
        long totalBlobBytes,
        long usableSpace,
        long heapFileSize,
        int contentTypeCount) {
}
